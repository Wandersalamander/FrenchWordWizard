package com.example.myapplication.streak

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Schedules a one-shot wake alarm for the user's configured reminder time and
 * re-arms itself after every firing. We deliberately use inexact
 * [AlarmManager.setAndAllowWhileIdle] to avoid the Android 12+
 * SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permission. Doze may slip the fire
 * time by ~15 min, which is fine for a "before midnight" deadline.
 */
object StreakAlarmScheduler {
    private const val REQUEST_CODE = 0xA1A2

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StreakReminderReceiver::class.java)
            .setAction(StreakReminderReceiver.ACTION_FIRE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Schedule the next firing of the reminder. If the user disabled the
     * reminder, cancels instead. Safe to call repeatedly — the underlying
     * PendingIntent is reused (FLAG_UPDATE_CURRENT).
     */
    fun schedule(context: Context) {
        if (!StreakTracker.isReminderEnabled(context)) {
            cancel(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextTriggerEpochMillis(
            hour = StreakTracker.reminderHour(context),
            minute = StreakTracker.reminderMinute(context),
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    /**
     * Next occurrence of (hour, minute) in the device-local zone. If today's
     * slot has already passed (or matches "now" exactly), jump to tomorrow.
     */
    fun nextTriggerEpochMillis(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): Long {
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

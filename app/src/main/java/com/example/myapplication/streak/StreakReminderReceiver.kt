package com.example.myapplication.streak

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.quiz.MainActivity

class StreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Single entry-point: we always reschedule for tomorrow, even when we
        // skip the notification, so the alarm chain keeps going.
        try {
            if (shouldNotify(context)) postNotification(context)
        } finally {
            StreakAlarmScheduler.schedule(context)
        }
    }

    private fun shouldNotify(context: Context): Boolean {
        if (!StreakTracker.isReminderEnabled(context)) return false
        // Nothing to lose if there's no streak yet. The user asked for the
        // nudge to fire only when the streak is at risk.
        if (StreakTracker.currentStreak(context) == 0) return false
        // Already practiced today — streak is safe.
        if (StreakTracker.practicedToday(context)) return false
        return true
    }

    private fun postNotification(context: Context) {
        StreakNotifications.ensureChannel(context)

        val streak = StreakTracker.currentStreak(context)
        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "Keep your $streak-day streak"
        val text = "Practice before midnight to keep it going."

        val notification = NotificationCompat.Builder(context, StreakNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(StreakNotifications.STREAK_NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_FIRE = "com.example.myapplication.streak.FIRE"
    }
}

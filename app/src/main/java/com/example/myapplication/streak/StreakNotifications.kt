package com.example.myapplication.streak

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object StreakNotifications {
    const val CHANNEL_ID = "streak_reminders"
    const val STREAK_NOTIFICATION_ID = 2  // 1 is the foreground service

    /**
     * Idempotent — safe to call from MainActivity.onCreate and from the
     * receiver right before posting. We use IMPORTANCE_DEFAULT so the nudge
     * actually pings (the foreground-service channel is IMPORTANCE_MIN and
     * would be invisible).
     */
    fun ensureChannel(context: Context) {
        val name = "Streak reminders"
        val description = "Daily nudge if your practice streak is about to end."
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = description
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

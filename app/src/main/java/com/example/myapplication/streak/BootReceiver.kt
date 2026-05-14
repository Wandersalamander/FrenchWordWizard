package com.example.myapplication.streak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets all alarms on reboot, so we re-arm the streak reminder
 * here. The manifest filter listens for BOOT_COMPLETED + LOCKED_BOOT_COMPLETED
 * (the latter is direct-boot aware, useful on encrypted devices).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                StreakAlarmScheduler.schedule(context)
            }
        }
    }
}

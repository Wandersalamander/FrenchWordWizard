package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.InputStream

class MyForegroundService : Service() {
    lateinit var vocabDictionary: MyDictionary
    var updateThread: Thread? = null
    var threadRunning = false

    companion object {
        const val NOTIFICATION_CHANNEL_ID =
            "ForegroundServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? {
        println("MyForegroundService.onBind")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("MyForegroundService.onStartCommand")

        if (!threadRunning) {
            updateThread = Thread {
                while (true) {
                    try {
                        Thread.sleep(5_000) // TODO increase time
                        updateNotification()
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
            updateThread!!.start()
            threadRunning = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        println("MyForegroundService.onUnbind")
        return super.onUnbind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        println("MyForegroundService.onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        println("MyForegroundService.onDestroy")
        if (threadRunning) {
            updateThread?.interrupt()
            threadRunning = false
        }
        super.onDestroy()

    }

    override fun onCreate() {
        println("MyForegroundService.onCreate")
        super.onCreate()
        val sharedPreferences: SharedPreferences = getSharedPreferences(
            "vocabulary_preferences", Context.MODE_PRIVATE
        )
        val inputStream: InputStream = resources.openRawResource(
            R.raw.dictionary_sorted
        )
        vocabDictionary = MyDictionary(inputStream, sharedPreferences)

        startForeground(
            NOTIFICATION_ID,
            createNotification().build()
        )
    }

    private fun createNotification(): NotificationCompat.Builder {
        println("MyForegroundService.createNotification")
        createNotificationChannel()

        val notificationIntent = Intent(
            this,
            MainActivity::class.java
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_MUTABLE
        )

        return NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("Foreground Service Example")
            .setContentText("This is a foreground service.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
    }

    private fun createNotificationChannel() {
        println("MyForegroundService.createNotificationChannel")
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Foreground Service Channel",
            NotificationManager.IMPORTANCE_MIN
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val localVocab = vocabDictionary.getActiveVocabWeightened()
        if (localVocab != null) {
            val notificationIntent = Intent(
                this,
                MainActivity::class.java
            )
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_MUTABLE
            )

            val builder = NotificationCompat.Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setContentTitle("${localVocab.french} | ${localVocab.english}")
                .setContentText(localVocab.frenchLong)


            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }
}
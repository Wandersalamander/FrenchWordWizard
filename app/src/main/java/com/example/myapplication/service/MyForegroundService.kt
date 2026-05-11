package com.example.myapplication.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.dictionary.Language
import com.example.myapplication.dictionary.MyDictionary
import com.example.myapplication.dictionary.openDictionaryStream
import com.example.myapplication.quiz.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream

class MyForegroundService : Service() {
    private lateinit var vocabDictionary: MyDictionary

    // Scope tied to the service lifecycle. Cancelled in onDestroy so any
    // in-flight delay()/update unwinds cleanly instead of leaking a thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ForegroundServiceChannel"
        const val NOTIFICATION_ID = 1

        // Cadence for refreshing the notification's vocab. The notification is
        // for passive glancing — refreshing every few seconds wastes battery
        // and forces a full per-word pref reload across the dictionary on
        // every tick. 30s is a comfortable balance.
        private const val UPDATE_INTERVAL_MS = 30_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (updateJob?.isActive != true) {
            updateJob = scope.launch {
                while (isActive) {
                    updateNotification()
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        scope.cancel()
        updateJob = null
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        val sharedPreferences: SharedPreferences = getSharedPreferences(
            "vocabulary_preferences", Context.MODE_PRIVATE
        )
        val language = Language.fromCode(sharedPreferences.getString("app_language", null))
        val inputStream: InputStream = openDictionaryStream(this, language)
        vocabDictionary = MyDictionary(inputStream, sharedPreferences)

        startForeground(NOTIFICATION_ID, createNotification().build())
    }

    private fun createNotification(): NotificationCompat.Builder {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Foreground Service Example")
            .setContentText("This is a foreground service.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Foreground Service Channel",
            NotificationManager.IMPORTANCE_MIN,
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        vocabDictionary.reloadPreferences()
        val localVocab = vocabDictionary.getActiveVocabWeightened()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setContentTitle("${localVocab.french} | ${localVocab.english}")
            .setContentText(localVocab.getSomeFrenchLong())

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}

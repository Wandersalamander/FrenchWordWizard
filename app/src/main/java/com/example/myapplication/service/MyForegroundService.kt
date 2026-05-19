package com.example.myapplication.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.data.DataLayer
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

        private const val CHANNEL_NAME = "Vocabulary practice"
        private const val CHANNEL_DESCRIPTION =
            "Shows a vocabulary word in the notification shade so you can glance at it between sessions."

        // Cadence for refreshing the notification's vocab. The notification is
        // for passive glancing — refreshing every few seconds wastes battery
        // and forces a full per-word reload across the dictionary on every
        // tick. 30s is a comfortable balance.
        //
        // Each tick re-applies the shared in-memory vocab cache to this
        // service's MyDictionary so any changes the user committed in the
        // main activity since the last tick are reflected here. The post-Room
        // reload is just a Map lookup per Vocab, so we no longer need the
        // dirty-bit listener the SharedPreferences version had.
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
        val prefs = AppPrefs.get(this)
        val language = Language.fromCode(prefs.getString(AppPrefs.KEY_APP_LANGUAGE, null))
        val inputStream: InputStream = openDictionaryStream(this, language)
        vocabDictionary = MyDictionary(inputStream, DataLayer.vocabPersistence)

        startForeground(NOTIFICATION_ID, createNotification().build())
    }

    private fun createNotification(): NotificationCompat.Builder {
        createNotificationChannel()

        // The first notification is shown only until updateNotification() runs
        // (~immediately after onCreate). Use the app name rather than a fake
        // word so the placeholder isn't visible during that flash.
        val appName = getString(R.string.app_name)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(appName)
            .setContentText("Loading…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityPendingIntent())
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val notificationIntent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = CHANNEL_DESCRIPTION
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        // Re-sync each Vocab's in-memory stats from the shared persistence
        // cache so any commits from the main activity since the last tick are
        // reflected in this service's notification. Cheap — a Map lookup per
        // Vocab; the old SharedPreferences-listener dirty-bit isn't needed.
        vocabDictionary.reloadPreferences()
        val (vocab, _) = vocabDictionary.getActiveVocabWeighted()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityPendingIntent())
            .setContentTitle("${vocab.foreign} | ${vocab.english}")
            .setContentText(vocab.randomExample())

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}

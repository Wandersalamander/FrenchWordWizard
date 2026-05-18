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
import com.example.myapplication.data.AppPrefs
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
import java.util.concurrent.atomic.AtomicBoolean

class MyForegroundService : Service() {
    private lateinit var vocabDictionary: MyDictionary
    private lateinit var sharedPreferences: SharedPreferences

    // Scope tied to the service lifecycle. Cancelled in onDestroy so any
    // in-flight delay()/update unwinds cleanly instead of leaking a thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null

    // Flips true whenever any pref changes — read on the next tick to decide
    // whether to reparse the dictionary. Idle ticks (no practice happening in
    // MainActivity since the last tick) skip the per-vocab pref re-read
    // entirely, which used to be the per-30s cost. Initial state is false:
    // onCreate just built the dictionary from current prefs, so the first
    // tick has nothing fresh to pick up.
    private val prefsDirty = AtomicBoolean(false)

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Filter to per-vocab keys ("<32-char md5 hash><suffix>") so streak
            // / settings / mastery-counter changes don't trigger a reload —
            // those don't affect what the notification picks.
            if (key != null && isVocabKey(key)) prefsDirty.set(true)
        }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ForegroundServiceChannel"
        const val NOTIFICATION_ID = 1

        private const val CHANNEL_NAME = "Vocabulary practice"
        private const val CHANNEL_DESCRIPTION =
            "Shows a vocabulary word in the notification shade so you can glance at it between sessions."

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
        if (::sharedPreferences.isInitialized) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        scope.cancel()
        updateJob = null
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = AppPrefs.get(this)
        val language = Language.fromCode(sharedPreferences.getString(AppPrefs.KEY_APP_LANGUAGE, null))
        val inputStream: InputStream = openDictionaryStream(this, language)
        vocabDictionary = MyDictionary(inputStream, sharedPreferences)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)

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
        // Reload only when MainActivity actually persisted vocab changes since
        // the last tick. compareAndSet keeps the check + clear atomic against
        // concurrent listener callbacks.
        if (prefsDirty.compareAndSet(true, false)) {
            vocabDictionary.reloadPreferences()
        }
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

    /**
     * Vocab pref keys are written by [com.example.myapplication.dictionary.Vocab]
     * as "{32-char md5 hex}{suffix}" (e.g. "nTimesViewed", "viewTimeMilli",
     * "listen_lastDisplayed"). Anything else — streak counters, language,
     * tutorial flags — should not trigger a dictionary reload.
     */
    private fun isVocabKey(key: String): Boolean {
        if (key.length < 32) return false
        for (i in 0 until 32) {
            val c = key[i]
            val isHex = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!isHex) return false
        }
        return true
    }
}

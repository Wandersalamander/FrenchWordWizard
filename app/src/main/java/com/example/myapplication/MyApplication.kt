package com.example.myapplication

import android.app.Application
import com.example.myapplication.data.DataLayer
import com.example.myapplication.data.migration.LegacyImporter
import com.example.myapplication.streak.StreakTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Process-wide bootstrap. Runs before any Activity / Service / BroadcastReceiver,
 * so it's the safe spot to:
 *
 *  1. Initialize the data layer (Room DB, write scope, [com.example.myapplication.data.VocabPersistence]).
 *  2. Run the one-shot legacy SharedPreferences importer (idempotent via the
 *     marker in [com.example.myapplication.data.entities.MetadataEntity]).
 *  3. Preload the persistence cache and the streak in-memory state so the
 *     sync APIs the rest of the app calls don't have to hit SQLite each time.
 *
 * The work blocks the main thread briefly (~hundreds of ms on first launch
 * with a full dictionary's worth of legacy data; ~tens of ms on subsequent
 * launches). Since Application.onCreate runs pre-Activity, the user sees the
 * system splash, not a half-rendered screen, during this stretch.
 */
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DataLayer.init(this)
        // All three suspending calls dispatch to Dispatchers.IO internally
        // (Room's @Dao suspend methods), so wrapping in withContext(IO) keeps
        // the actual SQLite work off the main thread even though runBlocking
        // suspends it.
        runBlocking {
            withContext(Dispatchers.IO) {
                LegacyImporter.runIfNeeded(this@MyApplication)
                DataLayer.vocabPersistence.preload()
                StreakTracker.preload()
            }
        }
    }
}

package io.github.wandersalamander.vocabtrainer.data

import android.content.Context
import io.github.wandersalamander.vocabtrainer.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons for the data layer: the Room database, the shared
 * write scope used by all fire-and-forget save paths, and the
 * [VocabPersistence] facade Vocab / MyDictionary read through.
 *
 * Initialized once from [io.github.wandersalamander.vocabtrainer.MyApplication.onCreate].
 * Tests don't go through this — they construct Vocabs with a null persistence,
 * which short-circuits all I/O.
 */
object DataLayer {
    @Volatile private var initialized = false

    private lateinit var _database: AppDatabase
    private lateinit var _vocabPersistence: RoomVocabPersistence
    private lateinit var _writeScope: CoroutineScope

    val database: AppDatabase get() = ensureInitialized()._database
    val vocabPersistence: RoomVocabPersistence get() = ensureInitialized()._vocabPersistence
    val writeScope: CoroutineScope get() = ensureInitialized()._writeScope

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            _writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            _database = AppDatabase.get(context)
            _vocabPersistence = RoomVocabPersistence(_database.vocabDao(), _writeScope)
            initialized = true
        }
    }

    private fun ensureInitialized(): DataLayer {
        check(initialized) { "DataLayer not initialized — call DataLayer.init(context) from Application.onCreate first" }
        return this
    }
}

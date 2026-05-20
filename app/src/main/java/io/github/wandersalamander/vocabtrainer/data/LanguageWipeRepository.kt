package io.github.wandersalamander.vocabtrainer.data

import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-language "user wiped progress at" timestamp. Reads are sync (in-memory
 * cache); writes update the cache then persist asynchronously. Mirrors the
 * SharedPreferences-era access pattern used by MainActivity.onResume's
 * wipe-detection check.
 */
object LanguageWipeRepository {
    private val cache = ConcurrentHashMap<String, Long>()

    fun timestampFor(languageCode: String): Long {
        cache[languageCode]?.let { return it }
        val loaded = runBlocking {
            DataLayer.database.languageWipeDao().loadTimestamp(languageCode)
        } ?: 0L
        cache[languageCode] = loaded
        return loaded
    }

    fun recordWipe(languageCode: String, atMs: Long = System.currentTimeMillis()) {
        cache[languageCode] = atMs
        DataLayer.writeScope.launch {
            DataLayer.database.languageWipeDao().upsert(
                LanguageWipeEntity(languageCode = languageCode, wipedAtMs = atMs)
            )
        }
    }

    /** Test/migration helper: drop the in-memory cache without touching the DB. */
    fun clearCache() {
        cache.clear()
    }
}

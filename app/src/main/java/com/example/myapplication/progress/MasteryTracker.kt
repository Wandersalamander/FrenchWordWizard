package com.example.myapplication.progress

import android.content.Context
import com.example.myapplication.data.prefs.AppPrefs
import com.example.myapplication.data.DataLayer
import com.example.myapplication.data.db.entities.DailyMasteredCountEntity
import com.example.myapplication.dictionary.Language
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// Mastery counters lazy-hydrate per language on first read — there's no
// upfront preload step because the cache fills as the active language is used.

/**
 * Per-day count of words that transitioned to fully-mastered (every ladder
 * skill below SKILL_FINISHED_THRESHOLD). Powers the "+N today" highlight on
 * the lifetime progress bar.
 *
 * Scoped per active language — mastering a French word doesn't bump the
 * German counter and vice versa. State is held in memory (loaded once via
 * [preload]) and persisted to Room asynchronously.
 *
 * Lazy day rollover, same as [StreakTracker]: writes stamp the LocalDate, reads
 * return 0 if the stamp isn't today. No midnight job, no listener.
 */
object MasteryTracker {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private data class Entry(val date: String, val count: Int)

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun activeLanguageCode(context: Context): String =
        AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    private fun loadIntoCacheIfAbsent(languageCode: String): Entry? {
        cache[languageCode]?.let { return it }
        val loaded = runBlocking {
            DataLayer.database.dailyMasteredCountDao().load(languageCode)
        } ?: return null
        val entry = Entry(date = loaded.date, count = loaded.count)
        cache[languageCode] = entry
        return entry
    }

    fun todayCount(context: Context): Int {
        val lang = activeLanguageCode(context)
        val entry = loadIntoCacheIfAbsent(lang) ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (entry.date != today) return 0
        return entry.count
    }

    /** Bump today's count by one and return the new value. */
    fun recordMasteredToday(context: Context): Int {
        val lang = activeLanguageCode(context)
        val today = LocalDate.now().format(DATE_FORMAT)
        val current = loadIntoCacheIfAbsent(lang)
            ?.takeIf { it.date == today }
            ?.count ?: 0
        val next = current + 1
        val entry = Entry(date = today, count = next)
        cache[lang] = entry
        DataLayer.writeScope.launch {
            DataLayer.database.dailyMasteredCountDao().upsert(
                DailyMasteredCountEntity(
                    languageCode = lang,
                    date = today,
                    count = next,
                    lastModifiedMs = System.currentTimeMillis(),
                )
            )
        }
        return next
    }

    /** Importer entry point. */
    internal fun importLanguage(languageCode: String, date: String, count: Int) {
        cache[languageCode] = Entry(date = date, count = count)
        DataLayer.writeScope.launch {
            DataLayer.database.dailyMasteredCountDao().upsert(
                DailyMasteredCountEntity(
                    languageCode = languageCode,
                    date = date,
                    count = count,
                    lastModifiedMs = System.currentTimeMillis(),
                )
            )
        }
    }
}

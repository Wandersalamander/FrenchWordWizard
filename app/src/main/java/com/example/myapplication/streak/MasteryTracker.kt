package com.example.myapplication.streak

import android.content.Context
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.dictionary.Language
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Per-day count of words that transitioned to fully-mastered (every ladder
 * skill below SKILL_FINISHED_THRESHOLD). Powers the "+N today" highlight on
 * the lifetime progress bar.
 *
 * Scoped per active language — mastering a French word doesn't bump the
 * German counter and vice versa. Active language is read from
 * [AppPrefs.KEY_APP_LANGUAGE] on every call so the counter always tracks
 * whichever dictionary is currently in use.
 *
 * Lazy day rollover, same as [StreakTracker]: writes stamp the LocalDate, reads
 * return 0 if the stamp isn't today. No midnight job, no listener.
 */
object MasteryTracker {
    private const val KEY_DATE_PREFIX = "mastered_today_date_"
    private const val KEY_COUNT_PREFIX = "mastered_today_count_"
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun dateKey(languageCode: String): String = "$KEY_DATE_PREFIX$languageCode"
    private fun countKey(languageCode: String): String = "$KEY_COUNT_PREFIX$languageCode"

    private fun activeLanguageCode(context: Context): String =
        AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    fun todayCount(context: Context): Int {
        val p = AppPrefs.get(context)
        val lang = activeLanguageCode(context)
        val storedDate = p.getString(dateKey(lang), null) ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (storedDate != today) return 0
        return p.getInt(countKey(lang), 0)
    }

    /** Bump today's count by one and return the new value. */
    fun recordMasteredToday(context: Context): Int {
        val p = AppPrefs.get(context)
        val lang = activeLanguageCode(context)
        val today = LocalDate.now().format(DATE_FORMAT)
        val current = if (p.getString(dateKey(lang), null) == today) p.getInt(countKey(lang), 0) else 0
        val next = current + 1
        p.edit()
            .putString(dateKey(lang), today)
            .putInt(countKey(lang), next)
            .apply()
        return next
    }
}

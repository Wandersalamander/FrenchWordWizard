package com.example.myapplication.streak

import android.content.Context
import com.example.myapplication.data.AppPrefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Per-day count of words that transitioned to fully-mastered (every ladder
 * skill below SKILL_FINISHED_THRESHOLD). Powers the "+N today" highlight on
 * the lifetime progress bar.
 *
 * Lazy day rollover, same as [StreakTracker]: writes stamp the LocalDate, reads
 * return 0 if the stamp isn't today. No midnight job, no listener.
 */
object MasteryTracker {
    private const val KEY_DATE = "mastered_today_date"
    private const val KEY_COUNT = "mastered_today_count"
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun todayCount(context: Context): Int {
        val p = AppPrefs.get(context)
        val storedDate = p.getString(KEY_DATE, null) ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (storedDate != today) return 0
        return p.getInt(KEY_COUNT, 0)
    }

    /** Bump today's count by one and return the new value. */
    fun recordMasteredToday(context: Context): Int {
        val p = AppPrefs.get(context)
        val today = LocalDate.now().format(DATE_FORMAT)
        val current = if (p.getString(KEY_DATE, null) == today) p.getInt(KEY_COUNT, 0) else 0
        val next = current + 1
        p.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_COUNT, next)
            .apply()
        return next
    }
}

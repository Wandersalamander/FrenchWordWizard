package com.example.myapplication.streak

import android.content.Context
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.dictionary.Skill
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Per-skill count of (word, skill) pairs that retired below the mastery
 * threshold today — the gold "-N" suffix on each number in the ACTIVE WORDS
 * label. Independent of [MasteryTracker], which only ticks when EVERY
 * ladder skill finishes for a word; here we tick on each individual skill
 * crossing so the per-skill suffix reflects what the user just did.
 *
 * Lazy day rollover (same pattern as MasteryTracker / StreakTracker): every
 * write stamps today's LocalDate, every read returns 0 when the stamp isn't
 * today. No midnight job, no listener — the first read after midnight is
 * what resets it.
 */
object SkillFinishedTracker {
    private const val KEY_DATE = "skill_finished_today_date"
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun countKey(skill: Skill): String = "skill_finished_today_count_${skill.name}"

    fun todayCount(context: Context, skill: Skill): Int {
        val p = AppPrefs.get(context)
        val storedDate = p.getString(KEY_DATE, null) ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (storedDate != today) return 0
        return p.getInt(countKey(skill), 0)
    }

    /** Bump [skill]'s today-count by one and return the new value. */
    fun recordFinishedToday(context: Context, skill: Skill): Int {
        val p = AppPrefs.get(context)
        val today = LocalDate.now().format(DATE_FORMAT)
        val sameDay = p.getString(KEY_DATE, null) == today
        // When the date stamp rolls over, every skill's count resets — wipe
        // all three keys in the same edit so we don't leave yesterday's
        // counts hanging on the two skills that didn't tick this round.
        val edit = p.edit()
        if (!sameDay) {
            for (s in Skill.entries) edit.putInt(countKey(s), 0)
            edit.putString(KEY_DATE, today)
        }
        val current = if (sameDay) p.getInt(countKey(skill), 0) else 0
        val next = current + 1
        edit.putInt(countKey(skill), next).apply()
        return next
    }
}

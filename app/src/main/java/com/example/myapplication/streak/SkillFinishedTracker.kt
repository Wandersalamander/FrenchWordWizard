package com.example.myapplication.streak

import android.content.Context
import com.example.myapplication.data.AppPrefs
import com.example.myapplication.dictionary.Language
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
 * Scoped per active language — finishing a French skill doesn't bump the
 * German counter. The active language is read from [AppPrefs.KEY_APP_LANGUAGE]
 * on every call so the counter always tracks whichever dictionary is currently
 * loaded.
 *
 * Lazy day rollover (same pattern as MasteryTracker / StreakTracker): every
 * write stamps today's LocalDate, every read returns 0 when the stamp isn't
 * today. No midnight job, no listener — the first read after midnight is
 * what resets it.
 */
object SkillFinishedTracker {
    private const val KEY_DATE_PREFIX = "skill_finished_today_date_"
    private const val KEY_COUNT_PREFIX = "skill_finished_today_count_"
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun dateKey(languageCode: String): String = "$KEY_DATE_PREFIX$languageCode"
    private fun countKey(skill: Skill, languageCode: String): String =
        "$KEY_COUNT_PREFIX${skill.name}_$languageCode"

    private fun activeLanguageCode(context: Context): String =
        AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    fun todayCount(context: Context, skill: Skill): Int {
        val p = AppPrefs.get(context)
        val lang = activeLanguageCode(context)
        val storedDate = p.getString(dateKey(lang), null) ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (storedDate != today) return 0
        return p.getInt(countKey(skill, lang), 0)
    }

    /** Bump [skill]'s today-count by one and return the new value. */
    fun recordFinishedToday(context: Context, skill: Skill): Int {
        val p = AppPrefs.get(context)
        val lang = activeLanguageCode(context)
        val today = LocalDate.now().format(DATE_FORMAT)
        val sameDay = p.getString(dateKey(lang), null) == today
        // When the date stamp rolls over for this language, every per-skill
        // count for this language resets — wipe all three keys in the same
        // edit so we don't leave yesterday's counts hanging on the two skills
        // that didn't tick this round.
        val edit = p.edit()
        if (!sameDay) {
            for (s in Skill.entries) edit.putInt(countKey(s, lang), 0)
            edit.putString(dateKey(lang), today)
        }
        val current = if (sameDay) p.getInt(countKey(skill, lang), 0) else 0
        val next = current + 1
        edit.putInt(countKey(skill, lang), next).apply()
        return next
    }
}

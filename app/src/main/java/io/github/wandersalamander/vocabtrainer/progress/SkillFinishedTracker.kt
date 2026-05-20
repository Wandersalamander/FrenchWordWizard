package io.github.wandersalamander.vocabtrainer.progress

import android.content.Context
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-skill count of (word, skill) pairs that retired below the mastery
 * threshold today — the gold "-N" suffix on each number in the ACTIVE WORDS
 * label. Independent of [MasteryTracker], which only ticks when EVERY
 * ladder skill finishes for a word; here we tick on each individual skill
 * crossing so the per-skill suffix reflects what the user just did.
 *
 * Scoped per active language — finishing a French skill doesn't bump the
 * German counter. State is held in memory and persisted to Room asynchronously.
 *
 * Lazy day rollover (same pattern as MasteryTracker / StreakTracker): every
 * write stamps today's LocalDate, every read returns 0 when the stamp isn't
 * today. No midnight job, no listener — the first read after midnight is
 * what resets it.
 */
object SkillFinishedTracker {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private data class Key(val languageCode: String, val skill: Skill)
    private data class Entry(val date: String, val count: Int)

    private val cache = ConcurrentHashMap<Key, Entry>()
    private val loadedLanguages = ConcurrentHashMap.newKeySet<String>()

    private fun activeLanguageCode(context: Context): String =
        AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null) ?: Language.DEFAULT.code

    private fun ensureLanguageLoaded(languageCode: String) {
        if (loadedLanguages.contains(languageCode)) return
        runBlocking {
            val rows = DataLayer.database.dailySkillFinishedCountDao()
                .loadAllForLanguage(languageCode)
            for (row in rows) {
                cache[Key(row.languageCode, row.skill)] = Entry(row.date, row.count)
            }
        }
        loadedLanguages.add(languageCode)
    }

    fun todayCount(context: Context, skill: Skill): Int {
        val lang = activeLanguageCode(context)
        ensureLanguageLoaded(lang)
        val entry = cache[Key(lang, skill)] ?: return 0
        val today = LocalDate.now().format(DATE_FORMAT)
        if (entry.date != today) return 0
        return entry.count
    }

    /** Bump [skill]'s today-count by one and return the new value. */
    fun recordFinishedToday(context: Context, skill: Skill): Int {
        val lang = activeLanguageCode(context)
        ensureLanguageLoaded(lang)
        val today = LocalDate.now().format(DATE_FORMAT)
        // Detect whether the active language's per-skill counters are still
        // on today's date. If they aren't — i.e. the day rolled over since
        // the last write — every per-skill count for this language resets to
        // 0 (the user's "today" snapshot is fresh).
        val anySameDay = Skill.entries.any { s ->
            cache[Key(lang, s)]?.date == today
        }
        if (!anySameDay) {
            for (s in Skill.entries) cache[Key(lang, s)] = Entry(today, 0)
        }
        val key = Key(lang, skill)
        val current = cache[key]?.takeIf { it.date == today }?.count ?: 0
        val next = current + 1
        cache[key] = Entry(today, next)
        val now = System.currentTimeMillis()
        // Persist this skill + any sibling reset rows in a single batch so a
        // rollover doesn't leave yesterday's counts pinned on the other two.
        val rows = mutableListOf<DailySkillFinishedCountEntity>()
        if (!anySameDay) {
            for (s in Skill.entries) {
                rows += DailySkillFinishedCountEntity(
                    languageCode = lang,
                    skill = s,
                    date = today,
                    count = if (s == skill) next else 0,
                    lastModifiedMs = now,
                )
            }
        } else {
            rows += DailySkillFinishedCountEntity(
                languageCode = lang,
                skill = skill,
                date = today,
                count = next,
                lastModifiedMs = now,
            )
        }
        DataLayer.writeScope.launch {
            DataLayer.database.dailySkillFinishedCountDao().upsertBulk(rows)
        }
        return next
    }

    /** Importer entry point. */
    internal fun importEntry(languageCode: String, skill: Skill, date: String, count: Int) {
        cache[Key(languageCode, skill)] = Entry(date, count)
        loadedLanguages.add(languageCode)
        DataLayer.writeScope.launch {
            DataLayer.database.dailySkillFinishedCountDao().upsert(
                DailySkillFinishedCountEntity(
                    languageCode = languageCode,
                    skill = skill,
                    date = date,
                    count = count,
                    lastModifiedMs = System.currentTimeMillis(),
                )
            )
        }
    }
}

package com.example.myapplication.data.migration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.example.myapplication.data.DataLayer
import com.example.myapplication.data.db.entities.DailyMasteredCountEntity
import com.example.myapplication.data.db.entities.DailySkillFinishedCountEntity
import com.example.myapplication.data.db.entities.LanguageWipeEntity
import com.example.myapplication.data.db.entities.MetadataEntity
import com.example.myapplication.data.db.entities.STREAK_STATE_SINGLETON_ID
import com.example.myapplication.data.db.entities.StreakStateEntity
import com.example.myapplication.data.db.entities.VocabFlagsEntity
import com.example.myapplication.data.db.entities.VocabStatsEntity
import com.example.myapplication.dictionary.Skill

/**
 * One-shot importer that copies user data from the legacy SharedPreferences
 * layout into the Room database. Runs once per installation (gated by a
 * marker row in [com.example.myapplication.data.db.entities.MetadataEntity]);
 * subsequent launches see the marker and skip.
 *
 * Critically, the marker lives in Room, not in SharedPreferences. If the user
 * reinstalls via Android Auto Backup, prefs come back but Room is fresh empty
 * — we want the importer to re-seed in that case, which the in-Room marker
 * correctly signals.
 *
 * The legacy prefs are left in place. A future cleanup release can wipe them
 * once we're confident nothing downstream still reads them.
 */
object LegacyImporter {
    private const val TAG = "LegacyImporter"
    private const val MARKER_KEY = "legacy_prefs_import_v1"
    private const val MARKER_VALUE_DONE = "done"

    // Mirrored from the legacy code so the importer doesn't depend on those
    // sources still being live during a future cleanup.
    private const val LEGACY_PREFS_NAME = "vocabulary_preferences"
    private const val LEGACY_PROGRESS_WIPED_PREFIX = "progress_wiped_at_"
    private const val LEGACY_STREAK_CURRENT = "streak_current"
    private const val LEGACY_STREAK_LAST_DATE = "streak_last_date"
    private const val LEGACY_STREAK_LONGEST = "streak_longest"
    private const val LEGACY_STREAK_FREEZES = "streak_freezes"
    private const val LEGACY_STREAK_REMINDER_ENABLED = "streak_reminder_enabled"
    private const val LEGACY_STREAK_REMINDER_HOUR = "streak_reminder_hour"
    private const val LEGACY_STREAK_REMINDER_MINUTE = "streak_reminder_minute"
    private const val LEGACY_MASTERED_DATE_PREFIX = "mastered_today_date_"
    private const val LEGACY_MASTERED_COUNT_PREFIX = "mastered_today_count_"
    private const val LEGACY_SKILL_FINISHED_DATE_PREFIX = "skill_finished_today_date_"
    private const val LEGACY_SKILL_FINISHED_COUNT_PREFIX = "skill_finished_today_count_"

    // Skill key prefixes baked in at the time the SharedPreferences layer
    // wrote rows. Kept local so the importer keeps reading legacy data
    // identically even if the Skill enum changes.
    private val LEGACY_SKILL_PREFIXES: List<Pair<String, Skill>> = listOf(
        "listen_" to Skill.LISTEN,
        "invert_" to Skill.INVERT,
        "" to Skill.READ,  // empty prefix must come last (it matches every key)
    )

    // Field suffix length determines parse order: viewTimeMilli_prev must be
    // checked before viewTimeMilli, because the latter is a prefix of the
    // former and would otherwise win.
    private val LEGACY_STAT_SUFFIXES: List<String> = listOf(
        "viewTimeMilli_prev",
        "viewTimeMilli",
        "nTimesViewed",
        "nTimesFailed",
        "lastDisplayed",
        "lastTimeFailed",
    )

    /**
     * Idempotent: returns immediately if the marker is present. Suspend so the
     * caller can run it inside an existing coroutine; no separate dispatcher
     * switch is needed because every DAO call is already off-main.
     */
    suspend fun runIfNeeded(context: Context) {
        val db = DataLayer.database
        val metadataDao = db.metadataDao()
        if (metadataDao.get(MARKER_KEY) == MARKER_VALUE_DONE) return

        val prefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.isEmpty()) {
            // Fresh install — nothing to import; still record the marker so
            // we don't keep scanning an empty file on every launch.
            metadataDao.put(MetadataEntity(MARKER_KEY, MARKER_VALUE_DONE))
            return
        }

        val parsed = parseEntries(all)
        val now = System.currentTimeMillis()

        db.withTransaction {
            // Per-vocab stats: build one entity per (hash, skill) we saw.
            val statsRows = parsed.vocabStats.map { (key, fields) ->
                val (hash, skill) = key
                VocabStatsEntity(
                    hash = hash,
                    skill = skill,
                    viewTimeMs = fields.viewTimeMilli
                        ?: VocabStatsImportDefaults.DEFAULT_VIEW_TIME_MS,
                    viewTimeMsPrev = fields.viewTimeMilliPrev
                        ?: VocabStatsImportDefaults.DEFAULT_VIEW_TIME_MS,
                    nViewed = fields.nTimesViewed ?: 0,
                    nFailed = fields.nTimesFailed ?: 0f,
                    lastDisplayed = fields.lastDisplayed ?: 0L,
                    lastFailed = fields.lastTimeFailed ?: 0L,
                    lastModifiedMs = now,
                )
            }
            if (statsRows.isNotEmpty()) db.vocabDao().upsertStatsBulk(statsRows)

            // Per-vocab flags: emit a row for every hash that had either flag
            // (legacy code stored each independently; we materialize a row
            // when at least one was set).
            val flagsRows = parsed.vocabFlags.map { (hash, flags) ->
                VocabFlagsEntity(
                    hash = hash,
                    ignore = flags.ignore,
                    flaggedHard = flags.flaggedHard,
                    lastModifiedMs = now,
                )
            }
            if (flagsRows.isNotEmpty()) db.vocabDao().upsertFlagsBulk(flagsRows)

            // Streak: only write if at least one streak key was present;
            // otherwise leave the table empty so StreakTracker.preload sees
            // null and uses defaults.
            parsed.streak?.let { s ->
                db.streakDao().upsert(
                    StreakStateEntity(
                        id = STREAK_STATE_SINGLETON_ID,
                        current = s.current,
                        lastDate = s.lastDate,
                        longest = s.longest,
                        freezes = s.freezes,
                        reminderEnabled = s.reminderEnabled,
                        reminderHour = s.reminderHour,
                        reminderMinute = s.reminderMinute,
                        lastModifiedMs = now,
                    )
                )
            }

            for ((lang, wipedAt) in parsed.languageWipes) {
                db.languageWipeDao().upsert(LanguageWipeEntity(lang, wipedAt))
            }

            for ((lang, entry) in parsed.masteredToday) {
                db.dailyMasteredCountDao().upsert(
                    DailyMasteredCountEntity(
                        languageCode = lang,
                        date = entry.date,
                        count = entry.count,
                        lastModifiedMs = now,
                    )
                )
            }

            if (parsed.skillFinishedToday.isNotEmpty()) {
                db.dailySkillFinishedCountDao().upsertBulk(
                    parsed.skillFinishedToday.map { (key, count) ->
                        DailySkillFinishedCountEntity(
                            languageCode = key.languageCode,
                            skill = key.skill,
                            date = key.date,
                            count = count,
                            lastModifiedMs = now,
                        )
                    }
                )
            }

            metadataDao.put(MetadataEntity(MARKER_KEY, MARKER_VALUE_DONE))
        }

        Log.i(
            TAG,
            "Imported legacy SharedPreferences: " +
                "${parsed.vocabStats.size} (vocab,skill) stat rows, " +
                "${parsed.vocabFlags.size} flag rows, " +
                "${if (parsed.streak != null) 1 else 0} streak rows, " +
                "${parsed.languageWipes.size} wipe markers, " +
                "${parsed.masteredToday.size} mastered-today rows, " +
                "${parsed.skillFinishedToday.size} skill-finished rows."
        )
    }

    private fun parseEntries(all: Map<String, *>): ParsedLegacyState {
        val vocabStats = mutableMapOf<Pair<String, Skill>, VocabStatFields>()
        val vocabFlags = mutableMapOf<String, VocabFlagFields>()
        val languageWipes = mutableMapOf<String, Long>()
        val masteredDate = mutableMapOf<String, String>()
        val masteredCount = mutableMapOf<String, Int>()
        val skillFinishedDate = mutableMapOf<String, String>()
        val skillFinishedCount = mutableMapOf<SkillFinishedKey, Int>()

        var streakCurrent: Int? = null
        var streakLastDate: String? = null
        var streakLongest: Int? = null
        var streakFreezes: Int? = null
        var streakReminderEnabled: Boolean? = null
        var streakReminderHour: Int? = null
        var streakReminderMinute: Int? = null

        for ((key, raw) in all) {
            when {
                key == LEGACY_STREAK_CURRENT -> streakCurrent = (raw as? Int)
                key == LEGACY_STREAK_LAST_DATE -> streakLastDate = (raw as? String)
                key == LEGACY_STREAK_LONGEST -> streakLongest = (raw as? Int)
                key == LEGACY_STREAK_FREEZES -> streakFreezes = (raw as? Int)
                key == LEGACY_STREAK_REMINDER_ENABLED -> streakReminderEnabled = (raw as? Boolean)
                key == LEGACY_STREAK_REMINDER_HOUR -> streakReminderHour = (raw as? Int)
                key == LEGACY_STREAK_REMINDER_MINUTE -> streakReminderMinute = (raw as? Int)
                key.startsWith(LEGACY_PROGRESS_WIPED_PREFIX) -> {
                    val lang = key.removePrefix(LEGACY_PROGRESS_WIPED_PREFIX)
                    (raw as? Long)?.let { languageWipes[lang] = it }
                }
                key.startsWith(LEGACY_MASTERED_DATE_PREFIX) -> {
                    val lang = key.removePrefix(LEGACY_MASTERED_DATE_PREFIX)
                    (raw as? String)?.let { masteredDate[lang] = it }
                }
                key.startsWith(LEGACY_MASTERED_COUNT_PREFIX) -> {
                    val lang = key.removePrefix(LEGACY_MASTERED_COUNT_PREFIX)
                    (raw as? Int)?.let { masteredCount[lang] = it }
                }
                key.startsWith(LEGACY_SKILL_FINISHED_DATE_PREFIX) -> {
                    val lang = key.removePrefix(LEGACY_SKILL_FINISHED_DATE_PREFIX)
                    (raw as? String)?.let { skillFinishedDate[lang] = it }
                }
                key.startsWith(LEGACY_SKILL_FINISHED_COUNT_PREFIX) -> {
                    // Shape: skill_finished_today_count_<SKILL.name>_<lang>
                    val rest = key.removePrefix(LEGACY_SKILL_FINISHED_COUNT_PREFIX)
                    val parts = rest.split('_', limit = 2)
                    if (parts.size == 2) {
                        val skill = runCatching { Skill.valueOf(parts[0]) }.getOrNull()
                        if (skill != null) {
                            val lang = parts[1]
                            val cnt = (raw as? Int) ?: continue
                            // Date comes from the matching LEGACY_SKILL_FINISHED_DATE
                            // entry for this language. We resolve below once
                            // all keys are read, since iteration order isn't
                            // guaranteed.
                            skillFinishedCount[SkillFinishedKey(lang, skill, "")] = cnt
                        }
                    }
                }
                else -> tryParseVocabKey(key, raw, vocabStats, vocabFlags)
            }
        }

        // Now that we've seen every key, attach the per-language date to each
        // (lang, skill) count row. SkillFinishedTracker's storage shape
        // shares ONE date column per language across all three skills, so all
        // rows for a language get the same date stamp here.
        val resolvedSkillFinished = mutableMapOf<SkillFinishedKey, Int>()
        for ((tmpKey, count) in skillFinishedCount) {
            val date = skillFinishedDate[tmpKey.languageCode] ?: continue
            resolvedSkillFinished[SkillFinishedKey(tmpKey.languageCode, tmpKey.skill, date)] = count
        }

        val mastered = mutableMapOf<String, MasteredEntry>()
        for ((lang, count) in masteredCount) {
            val date = masteredDate[lang] ?: continue
            mastered[lang] = MasteredEntry(date = date, count = count)
        }

        // Streak: emit only if at least one legacy streak key was set.
        val streak = if (
            streakCurrent != null || streakLastDate != null || streakLongest != null ||
            streakFreezes != null || streakReminderEnabled != null ||
            streakReminderHour != null || streakReminderMinute != null
        ) {
            StreakImportFields(
                current = streakCurrent ?: 0,
                lastDate = streakLastDate,
                longest = streakLongest ?: 0,
                freezes = streakFreezes ?: 0,
                reminderEnabled = streakReminderEnabled ?: true,
                reminderHour = streakReminderHour ?: StreakImportDefaults.DEFAULT_REMINDER_HOUR,
                reminderMinute = streakReminderMinute ?: StreakImportDefaults.DEFAULT_REMINDER_MINUTE,
            )
        } else null

        return ParsedLegacyState(
            vocabStats = vocabStats,
            vocabFlags = vocabFlags,
            streak = streak,
            languageWipes = languageWipes,
            masteredToday = mastered,
            skillFinishedToday = resolvedSkillFinished,
        )
    }

    private fun tryParseVocabKey(
        key: String,
        raw: Any?,
        vocabStats: MutableMap<Pair<String, Skill>, VocabStatFields>,
        vocabFlags: MutableMap<String, VocabFlagFields>,
    ) {
        // Per-vocab keys: "<32-hex hash><skill prefix?><field>". Per-vocab
        // values were persisted as Strings by the legacy code (Vocab.kt did a
        // .putString().toString() round-trip), so we ignore non-String raws.
        if (key.length <= 32) return
        if (!isHex32(key)) return
        val hash = key.substring(0, 32)
        val rest = key.substring(32)
        val stringValue = raw as? String ?: return

        // Per-vocab flags don't have a skill prefix.
        when (rest) {
            "ignore" -> {
                val b = stringValue.toBooleanStrictOrNull() ?: return
                val current = vocabFlags[hash] ?: VocabFlagFields()
                vocabFlags[hash] = current.copy(ignore = b)
                return
            }
            "flaggedHard" -> {
                val b = stringValue.toBooleanStrictOrNull() ?: return
                val current = vocabFlags[hash] ?: VocabFlagFields()
                vocabFlags[hash] = current.copy(flaggedHard = b)
                return
            }
        }

        for ((prefix, skill) in LEGACY_SKILL_PREFIXES) {
            if (!rest.startsWith(prefix)) continue
            val suffix = rest.substring(prefix.length)
            // viewTimeMilli_prev MUST be matched before viewTimeMilli — the
            // ordering of LEGACY_STAT_SUFFIXES handles that.
            val matched = LEGACY_STAT_SUFFIXES.firstOrNull { it == suffix } ?: continue
            val key2 = hash to skill
            val current = vocabStats[key2] ?: VocabStatFields()
            vocabStats[key2] = when (matched) {
                "viewTimeMilli_prev" -> current.copy(viewTimeMilliPrev = stringValue.toLongOrNull())
                "viewTimeMilli" -> current.copy(viewTimeMilli = stringValue.toLongOrNull())
                "nTimesViewed" -> current.copy(nTimesViewed = stringValue.toIntOrNull())
                "nTimesFailed" -> current.copy(nTimesFailed = stringValue.toFloatOrNull())
                "lastDisplayed" -> current.copy(lastDisplayed = stringValue.toLongOrNull())
                "lastTimeFailed" -> current.copy(lastTimeFailed = stringValue.toLongOrNull())
                else -> current
            }
            return
        }
    }

    private fun isHex32(key: String): Boolean {
        for (i in 0 until 32) {
            val c = key[i]
            val isHex = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!isHex) return false
        }
        return true
    }

    private data class VocabStatFields(
        val viewTimeMilli: Long? = null,
        val viewTimeMilliPrev: Long? = null,
        val nTimesViewed: Int? = null,
        val nTimesFailed: Float? = null,
        val lastDisplayed: Long? = null,
        val lastTimeFailed: Long? = null,
    )

    private data class VocabFlagFields(
        val ignore: Boolean = false,
        val flaggedHard: Boolean = false,
    )

    private data class StreakImportFields(
        val current: Int,
        val lastDate: String?,
        val longest: Int,
        val freezes: Int,
        val reminderEnabled: Boolean,
        val reminderHour: Int,
        val reminderMinute: Int,
    )

    private data class SkillFinishedKey(
        val languageCode: String,
        val skill: Skill,
        val date: String,
    )

    private data class MasteredEntry(val date: String, val count: Int)

    private data class ParsedLegacyState(
        val vocabStats: Map<Pair<String, Skill>, VocabStatFields>,
        val vocabFlags: Map<String, VocabFlagFields>,
        val streak: StreakImportFields?,
        val languageWipes: Map<String, Long>,
        val masteredToday: Map<String, MasteredEntry>,
        val skillFinishedToday: Map<SkillFinishedKey, Int>,
    )

    /**
     * Defaults that must match the historical fallbacks in [SkillStats] so a
     * partially-written legacy row doesn't get re-anchored to a different
     * baseline by the importer. Duplicated here so this file doesn't depend on
     * the dictionary package's current internals.
     */
    private object VocabStatsImportDefaults {
        const val DEFAULT_VIEW_TIME_MS: Long = 10_000L
    }

    private object StreakImportDefaults {
        const val DEFAULT_REMINDER_HOUR: Int = 21
        const val DEFAULT_REMINDER_MINUTE: Int = 0
    }
}

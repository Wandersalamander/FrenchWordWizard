package io.github.wandersalamander.vocabtrainer.data.backup

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import io.github.wandersalamander.vocabtrainer.data.CommitButton
import io.github.wandersalamander.vocabtrainer.data.DataLayer
import io.github.wandersalamander.vocabtrainer.data.LanguageWipeRepository
import io.github.wandersalamander.vocabtrainer.data.SpotCheckResult
import io.github.wandersalamander.vocabtrainer.data.TipUsed
import io.github.wandersalamander.vocabtrainer.data.VocabInteraction
import io.github.wandersalamander.vocabtrainer.data.db.AppDatabase
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailyMasteredCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LifetimeScoreEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.MetadataEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.STREAK_STATE_SINGLETON_ID
import io.github.wandersalamander.vocabtrainer.data.db.entities.StreakStateEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabFlagsEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity
import io.github.wandersalamander.vocabtrainer.data.prefs.AppPrefs
import io.github.wandersalamander.vocabtrainer.dictionary.Language
import io.github.wandersalamander.vocabtrainer.dictionary.SentenceSource
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import io.github.wandersalamander.vocabtrainer.progress.LifetimeScoreTracker
import io.github.wandersalamander.vocabtrainer.progress.MasteryTracker
import io.github.wandersalamander.vocabtrainer.progress.SkillFinishedTracker
import io.github.wandersalamander.vocabtrainer.streak.StreakTracker
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/**
 * Whole-progress export / import as a single JSON document.
 *
 * Motivating use case: the `applicationId` rename. Android keys an app's
 * private storage to the applicationId, so changing it installs a brand-new
 * app with an empty data directory — the Room DB and SharedPreferences from
 * the old install are gone, and Android Auto Backup can't bridge it (it's
 * keyed to the package too). [export] writes everything the user has earned to
 * a user-chosen file (via the Storage Access Framework, so it survives outside
 * app-private storage); [import] reads it back into the renamed install.
 *
 * Scope is "all progress": every Room table plus the small whitelisted
 * [AppPrefs] UI settings. The downloadable LLM model is intentionally excluded
 * — it's large and re-downloadable.
 *
 * Import is a merge (upsert by primary key), not a wipe: importing onto an
 * install that already has data overwrites rows that share a key and leaves
 * the rest. On a fresh post-rename install the DB is empty, so the merge is a
 * plain restore. The whole DB write runs in one transaction, ordered so the
 * `vocab_review_log` → `vocab_stats` foreign key is always satisfied.
 *
 * Serialization is hand-rolled over [org.json] (bundled with Android) so we
 * don't pull in a serialization dependency and the Room entities stay free of
 * annotations. Enums are stored by [Enum.name]; unknown enum names on import
 * are skipped per-row rather than failing the whole restore, so a backup from
 * a build with extra enum cases still imports what it can.
 */
object ProgressBackup {
    private const val TAG = "ProgressBackup"

    /** Identifies our files and guards against importing unrelated JSON. */
    const val FORMAT = "vocabtrainer-progress"

    /**
     * Envelope-format version (not the DB schema version). Bumped only if the
     * JSON layout itself changes incompatibly. We refuse to import an envelope
     * newer than we understand.
     */
    const val ENVELOPE_VERSION = 1

    /** Whitelisted [AppPrefs] keys carried in the backup. */
    private val PREF_KEYS = setOf(
        AppPrefs.KEY_APP_LANGUAGE,
        AppPrefs.KEY_TUTORIAL_SHOWN,
        AppPrefs.KEY_SPOT_CHECK_TUTORIAL_SHOWN,
        AppPrefs.KEY_WORDLIST_SORT_INDEX,
        AppPrefs.KEY_WORDLIST_SELECTED_SKILL,
        AppPrefs.KEY_SENTENCE_SOURCE,
        AppPrefs.KEY_AUTO_ADD_THRESHOLD,
    )

    /**
     * Whether [key] should ride the backup. The fixed whitelist plus the
     * per-language auto-add threshold keys, which share a prefix (one per
     * language code) rather than a single fixed name — so we match by prefix the
     * way the picker reads them.
     */
    private fun isBackedUpPrefKey(key: String): Boolean =
        key in PREF_KEYS || key.startsWith(AppPrefs.KEY_AUTO_ADD_THRESHOLD_PREFIX)

    /** Thrown when the document isn't a backup we can read. */
    class InvalidBackupException(message: String) : Exception(message)

    data class ImportSummary(
        val distinctWords: Int,
        val statRows: Int,
        val flagRows: Int,
        val reviewLogRows: Int,
        val streakImported: Boolean,
        val prefsImported: Int,
    )

    // ---------------------------------------------------------------- export

    /**
     * Read the whole dataset out of Room + [AppPrefs] and render it as a
     * pretty-printed JSON string. Suspend: every DAO call is suspend, and the
     * caller is expected to be off the main thread.
     */
    suspend fun export(context: Context): String {
        val db = DataLayer.database

        val statsArr = JSONArray()
        for (row in db.vocabDao().loadAllStats()) statsArr.put(statsToJson(row))

        val flagsArr = JSONArray()
        for (row in db.vocabDao().loadAllFlags()) flagsArr.put(flagsToJson(row))

        val logArr = JSONArray()
        for (row in db.vocabReviewLogDao().loadAll()) logArr.put(reviewLogToJson(row))

        val wipeArr = JSONArray()
        for (row in db.languageWipeDao().loadAll()) wipeArr.put(languageWipeToJson(row))

        val masteredArr = JSONArray()
        for (row in db.dailyMasteredCountDao().loadAll()) masteredArr.put(dailyMasteredToJson(row))

        val skillFinishedArr = JSONArray()
        for (row in db.dailySkillFinishedCountDao().loadAll()) {
            skillFinishedArr.put(dailySkillFinishedToJson(row))
        }

        val lifetimeArr = JSONArray()
        for (row in db.lifetimeScoreDao().loadAll()) lifetimeArr.put(lifetimeScoreToJson(row))

        val metadataArr = JSONArray()
        for (row in db.metadataDao().loadAll()) metadataArr.put(metadataToJson(row))

        val streakRow = db.streakDao().load()

        val tables = JSONObject().apply {
            put("vocab_stats", statsArr)
            put("vocab_flags", flagsArr)
            put("vocab_review_log", logArr)
            put("streak_state", streakRow?.let { streakToJson(it) } ?: JSONObject.NULL)
            put("language_wipe", wipeArr)
            put("daily_mastered_count", masteredArr)
            put("daily_skill_finished_count", skillFinishedArr)
            put("lifetime_score", lifetimeArr)
            put("metadata", metadataArr)
        }

        val root = JSONObject().apply {
            put("format", FORMAT)
            put("version", ENVELOPE_VERSION)
            put("schemaVersion", AppDatabase.SCHEMA_VERSION)
            put("exportedAtMs", System.currentTimeMillis())
            put("prefs", exportPrefs(context))
            put("tables", tables)
        }
        return root.toString(2)
    }

    private fun exportPrefs(context: Context): JSONObject {
        val out = JSONObject()
        for ((key, value) in AppPrefs.get(context).all) {
            if (!isBackedUpPrefKey(key) || value == null) continue
            val cell = JSONObject()
            when (value) {
                is Boolean -> cell.put("type", "boolean").put("value", value)
                is Int -> cell.put("type", "int").put("value", value)
                is Long -> cell.put("type", "long").put("value", value)
                is Float -> cell.put("type", "float").put("value", value.toDouble())
                is String -> cell.put("type", "string").put("value", value)
                is Set<*> -> {
                    val arr = JSONArray()
                    for (s in value) arr.put(s.toString())
                    cell.put("type", "stringset").put("value", arr)
                }
                else -> continue
            }
            out.put(key, cell)
        }
        return out
    }

    // ---------------------------------------------------------------- import

    suspend fun import(context: Context, stream: InputStream): ImportSummary =
        import(context, stream.bufferedReader().use { it.readText() })

    /**
     * Parse [json] and merge it into Room + [AppPrefs]. Parsing happens fully
     * before any write, so a malformed document throws [InvalidBackupException]
     * without leaving a partial import. The DB write is one transaction; the
     * prefs write and cache refresh follow once the data is durably in.
     */
    suspend fun import(context: Context, json: String): ImportSummary {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidBackupException("Not a valid JSON file.")
        }

        if (root.optString("format") != FORMAT) {
            throw InvalidBackupException("This file isn't a $FORMAT backup.")
        }
        val envelopeVersion = root.optInt("version", -1)
        if (envelopeVersion > ENVELOPE_VERSION) {
            throw InvalidBackupException(
                "This backup was made by a newer version of the app (format v$envelopeVersion). " +
                    "Update the app, then import again."
            )
        }
        if (root.optInt("schemaVersion", -1) > AppDatabase.SCHEMA_VERSION) {
            throw InvalidBackupException(
                "This backup is from a newer app version. Update the app, then import again."
            )
        }

        val tables = root.optJSONObject("tables")
            ?: throw InvalidBackupException("Backup is missing its data.")

        // Parse everything up front (off the DB) so a bad row aborts before we touch storage.
        val statsRows = parseList(tables.optJSONArray("vocab_stats")) { jsonToStats(it) }
        val flagsRows = parseList(tables.optJSONArray("vocab_flags")) { jsonToFlags(it) }
        val logRows = parseList(tables.optJSONArray("vocab_review_log")) { jsonToReviewLog(it) }
        val wipeRows = parseList(tables.optJSONArray("language_wipe")) { jsonToLanguageWipe(it) }
        val masteredRows = parseList(tables.optJSONArray("daily_mastered_count")) { jsonToDailyMastered(it) }
        val skillFinishedRows =
            parseList(tables.optJSONArray("daily_skill_finished_count")) { jsonToDailySkillFinished(it) }
        val lifetimeRows = parseList(tables.optJSONArray("lifetime_score")) { jsonToLifetimeScore(it) }
        val metadataRows = parseList(tables.optJSONArray("metadata")) { jsonToMetadata(it) }
        val streakRow = tables.optJSONObject("streak_state")?.let { jsonToStreak(it) }

        val db = DataLayer.database
        db.withTransaction {
            // vocab_stats first: it's the FK parent for vocab_review_log.
            if (statsRows.isNotEmpty()) db.vocabDao().upsertStatsBulk(statsRows)
            if (flagsRows.isNotEmpty()) db.vocabDao().upsertFlagsBulk(flagsRows)
            if (logRows.isNotEmpty()) db.vocabReviewLogDao().importBulk(logRows)
            streakRow?.let { db.streakDao().upsert(it) }
            for (row in wipeRows) db.languageWipeDao().upsert(row)
            for (row in masteredRows) db.dailyMasteredCountDao().upsert(row)
            if (skillFinishedRows.isNotEmpty()) db.dailySkillFinishedCountDao().upsertBulk(skillFinishedRows)
            for (row in lifetimeRows) db.lifetimeScoreDao().upsert(row)
            for (row in metadataRows) db.metadataDao().put(row)
        }

        val prefsImported = importPrefs(context, root.optJSONObject("prefs"))

        refreshInMemoryState(context)

        val summary = ImportSummary(
            distinctWords = (statsRows.map { it.hash } + flagsRows.map { it.hash }).distinct().size,
            statRows = statsRows.size,
            flagRows = flagsRows.size,
            reviewLogRows = logRows.size,
            streakImported = streakRow != null,
            prefsImported = prefsImported,
        )
        Log.i(TAG, "Imported progress backup: $summary")
        return summary
    }

    private fun importPrefs(context: Context, prefs: JSONObject?): Int {
        if (prefs == null) return 0
        val editor = AppPrefs.get(context).edit()
        var count = 0
        for (key in prefs.keys()) {
            if (!isBackedUpPrefKey(key)) continue
            val cell = prefs.optJSONObject(key) ?: continue
            when (cell.optString("type")) {
                "boolean" -> editor.putBoolean(key, cell.optBoolean("value"))
                "int" -> editor.putInt(key, cell.optInt("value"))
                "long" -> editor.putLong(key, cell.optLong("value"))
                "float" -> editor.putFloat(key, cell.optDouble("value").toFloat())
                "string" -> editor.putString(key, cell.optString("value"))
                "stringset" -> {
                    val arr = cell.optJSONArray("value") ?: JSONArray()
                    val set = HashSet<String>(arr.length())
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    editor.putStringSet(key, set)
                }
                else -> continue
            }
            count++
        }
        // commit (not apply): we're already off-main and the cache refresh
        // below reads back through the active-language pref we may have changed.
        editor.commit()
        return count
    }

    /**
     * Rebuild every in-memory cache that fronts Room so the running app sees
     * imported data without a process restart, then bump the active language's
     * wipe timestamp. MainActivity.onResume treats an advanced wipe timestamp
     * as "the stats changed under me, rebuild the dictionary" — the same signal
     * the per-language reset uses — so returning to the quiz reflects the
     * import.
     */
    private suspend fun refreshInMemoryState(context: Context) {
        DataLayer.vocabPersistence.preload()
        StreakTracker.preload()
        LanguageWipeRepository.clearCache()
        MasteryTracker.clearCache()
        LifetimeScoreTracker.clearCache()
        SkillFinishedTracker.clearCache()

        val activeLanguage = AppPrefs.get(context).getString(AppPrefs.KEY_APP_LANGUAGE, null)
            ?: Language.DEFAULT.code
        LanguageWipeRepository.recordWipe(activeLanguage)
    }

    // ----------------------------------------------------- entity <-> json

    private fun statsToJson(e: VocabStatsEntity) = JSONObject().apply {
        put("hash", e.hash)
        put("skill", e.skill.name)
        put("viewTimeMs", e.viewTimeMs)
        put("viewTimeMsPrev", e.viewTimeMsPrev)
        put("nViewed", e.nViewed)
        put("nFailed", e.nFailed.toDouble())
        put("lastDisplayed", e.lastDisplayed)
        put("lastFailed", e.lastFailed)
        put("lastModifiedMs", e.lastModifiedMs)
        put("fastTracked", e.fastTracked)
        put("refreshIntervalDays", e.refreshIntervalDays.toDouble())
        val interactions = JSONArray()
        for (i in e.recentInteractions) {
            interactions.put(JSONObject().put("t", i.timestampMs).put("f", i.failAmount.toDouble()))
        }
        put("recentInteractions", interactions)
    }

    private fun jsonToStats(o: JSONObject): VocabStatsEntity? {
        val skill = enumOrNull<Skill>(o.optString("skill")) ?: return null
        val interactions = mutableListOf<VocabInteraction>()
        val arr = o.optJSONArray("recentInteractions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                interactions.add(
                    VocabInteraction(
                        timestampMs = item.optLong("t"),
                        failAmount = item.optDouble("f").toFloat(),
                    )
                )
            }
        }
        return VocabStatsEntity(
            hash = o.optString("hash"),
            skill = skill,
            viewTimeMs = o.optLong("viewTimeMs"),
            viewTimeMsPrev = o.optLong("viewTimeMsPrev"),
            nViewed = o.optInt("nViewed"),
            nFailed = o.optDouble("nFailed").toFloat(),
            lastDisplayed = o.optLong("lastDisplayed"),
            lastFailed = o.optLong("lastFailed"),
            lastModifiedMs = o.optLong("lastModifiedMs"),
            recentInteractions = interactions,
            fastTracked = o.optBoolean("fastTracked", false),
            refreshIntervalDays = if (o.has("refreshIntervalDays")) {
                o.optDouble("refreshIntervalDays", 1.0).toFloat()
            } else 1f,
        )
    }

    private fun flagsToJson(e: VocabFlagsEntity) = JSONObject().apply {
        put("hash", e.hash)
        put("ignore", e.ignore)
        put("flaggedHard", e.flaggedHard)
        put("lastModifiedMs", e.lastModifiedMs)
    }

    private fun jsonToFlags(o: JSONObject) = VocabFlagsEntity(
        hash = o.optString("hash"),
        ignore = o.optBoolean("ignore"),
        flaggedHard = o.optBoolean("flaggedHard"),
        lastModifiedMs = o.optLong("lastModifiedMs"),
    )

    private fun reviewLogToJson(e: VocabReviewLogEntity) = JSONObject().apply {
        put("id", e.id)
        put("vocabHash", e.vocabHash)
        put("skill", e.skill.name)
        put("timestampMs", e.timestampMs)
        put("viewTimeMs", e.viewTimeMs)
        put("timeToFirstActionMs", e.timeToFirstActionMs ?: JSONObject.NULL)
        put("pausedMs", e.pausedMs)
        put("commitButton", e.commitButton.name)
        put("tipUsed", e.tipUsed.name)
        put("progressiveReveals", e.progressiveReveals)
        put("wordMaskTaps", e.wordMaskTaps)
        put("wordReplays", e.wordReplays)
        put("sentenceMaskTaps", e.sentenceMaskTaps)
        put("spotCheckResult", e.spotCheckResult.name)
        put("sentenceSource", e.sentenceSource.name)
        put("listeningEnabled", e.listeningEnabled)
        put("compromised", e.compromised)
    }

    private fun jsonToReviewLog(o: JSONObject): VocabReviewLogEntity? {
        val skill = enumOrNull<Skill>(o.optString("skill")) ?: return null
        val commitButton = enumOrNull<CommitButton>(o.optString("commitButton")) ?: return null
        val tipUsed = enumOrNull<TipUsed>(o.optString("tipUsed")) ?: return null
        val spotCheckResult = enumOrNull<SpotCheckResult>(o.optString("spotCheckResult")) ?: return null
        val sentenceSource = enumOrNull<SentenceSource>(o.optString("sentenceSource")) ?: return null
        return VocabReviewLogEntity(
            id = o.optLong("id"),
            vocabHash = o.optString("vocabHash"),
            skill = skill,
            timestampMs = o.optLong("timestampMs"),
            viewTimeMs = o.optLong("viewTimeMs"),
            timeToFirstActionMs = if (o.isNull("timeToFirstActionMs")) null else o.optLong("timeToFirstActionMs"),
            pausedMs = o.optLong("pausedMs"),
            commitButton = commitButton,
            tipUsed = tipUsed,
            progressiveReveals = o.optInt("progressiveReveals"),
            wordMaskTaps = o.optInt("wordMaskTaps"),
            wordReplays = o.optInt("wordReplays"),
            sentenceMaskTaps = o.optInt("sentenceMaskTaps"),
            spotCheckResult = spotCheckResult,
            sentenceSource = sentenceSource,
            listeningEnabled = o.optBoolean("listeningEnabled"),
            compromised = o.optBoolean("compromised"),
        )
    }

    private fun streakToJson(e: StreakStateEntity) = JSONObject().apply {
        put("current", e.current)
        put("lastDate", e.lastDate ?: JSONObject.NULL)
        put("longest", e.longest)
        put("freezes", e.freezes)
        put("reminderEnabled", e.reminderEnabled)
        put("reminderHour", e.reminderHour)
        put("reminderMinute", e.reminderMinute)
        put("lastModifiedMs", e.lastModifiedMs)
    }

    private fun jsonToStreak(o: JSONObject) = StreakStateEntity(
        id = STREAK_STATE_SINGLETON_ID,
        current = o.optInt("current"),
        lastDate = if (o.isNull("lastDate")) null else o.optString("lastDate"),
        longest = o.optInt("longest"),
        freezes = o.optInt("freezes"),
        reminderEnabled = o.optBoolean("reminderEnabled", true),
        reminderHour = o.optInt("reminderHour", 21),
        reminderMinute = o.optInt("reminderMinute", 0),
        lastModifiedMs = o.optLong("lastModifiedMs"),
    )

    private fun languageWipeToJson(e: LanguageWipeEntity) = JSONObject().apply {
        put("languageCode", e.languageCode)
        put("wipedAtMs", e.wipedAtMs)
    }

    private fun jsonToLanguageWipe(o: JSONObject) = LanguageWipeEntity(
        languageCode = o.optString("languageCode"),
        wipedAtMs = o.optLong("wipedAtMs"),
    )

    private fun dailyMasteredToJson(e: DailyMasteredCountEntity) = JSONObject().apply {
        put("languageCode", e.languageCode)
        put("date", e.date)
        put("count", e.count)
        put("lastModifiedMs", e.lastModifiedMs)
    }

    private fun jsonToDailyMastered(o: JSONObject) = DailyMasteredCountEntity(
        languageCode = o.optString("languageCode"),
        date = o.optString("date"),
        count = o.optInt("count"),
        lastModifiedMs = o.optLong("lastModifiedMs"),
    )

    private fun dailySkillFinishedToJson(e: DailySkillFinishedCountEntity) = JSONObject().apply {
        put("languageCode", e.languageCode)
        put("skill", e.skill.name)
        put("date", e.date)
        put("count", e.count)
        put("lastModifiedMs", e.lastModifiedMs)
    }

    private fun jsonToDailySkillFinished(o: JSONObject): DailySkillFinishedCountEntity? {
        val skill = enumOrNull<Skill>(o.optString("skill")) ?: return null
        return DailySkillFinishedCountEntity(
            languageCode = o.optString("languageCode"),
            skill = skill,
            date = o.optString("date"),
            count = o.optInt("count"),
            lastModifiedMs = o.optLong("lastModifiedMs"),
        )
    }

    private fun lifetimeScoreToJson(e: LifetimeScoreEntity) = JSONObject().apply {
        put("languageCode", e.languageCode)
        put("score", e.score)
        put("lastModifiedMs", e.lastModifiedMs)
    }

    private fun jsonToLifetimeScore(o: JSONObject) = LifetimeScoreEntity(
        languageCode = o.optString("languageCode"),
        score = o.optLong("score"),
        lastModifiedMs = o.optLong("lastModifiedMs"),
    )

    private fun metadataToJson(e: MetadataEntity) = JSONObject().apply {
        put("key", e.key)
        put("value", e.value)
    }

    private fun jsonToMetadata(o: JSONObject) = MetadataEntity(
        key = o.optString("key"),
        value = o.optString("value"),
    )

    // ------------------------------------------------------------- helpers

    /** Map a JSON array to a list, dropping entries the mapper rejects (null). */
    private inline fun <T> parseList(arr: JSONArray?, map: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map(o)?.let { out.add(it) }
        }
        return out
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        if (name.isNullOrEmpty()) null else runCatching { enumValueOf<T>(name) }.getOrNull()
}

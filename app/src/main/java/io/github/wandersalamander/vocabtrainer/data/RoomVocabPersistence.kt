package io.github.wandersalamander.vocabtrainer.data

import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabReviewLogDao
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabFlagsEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity
import io.github.wandersalamander.vocabtrainer.dictionary.Skill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Room-backed [VocabPersistence]. Holds an in-memory cache so reads from the
 * quiz round path (10s of per-round lookups) are O(1) map hits, and dispatches
 * writes to a background coroutine so the UI thread never blocks on SQLite.
 *
 * The cache is rebuilt on [preload]; this is called once at app startup and
 * again whenever the foreground service ticks (cheap — map mutations only).
 * Per-write the in-memory entry is updated synchronously *before* the DB
 * write is launched, so subsequent reads see the new value immediately even
 * if the persist hasn't reached disk yet.
 *
 * When [save] is called with a [ReviewLogEntry], both writes go into the
 * same `launch` block so the stats upsert lands before the log insert — the
 * FK from `vocab_review_log` → `vocab_stats` is satisfied in order.
 */
class RoomVocabPersistence(
    private val vocabDao: VocabDao,
    private val reviewLogDao: VocabReviewLogDao,
    private val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VocabPersistence {

    private val cache = ConcurrentHashMap<String, VocabSnapshot>()

    override fun load(hash: String): VocabSnapshot? = cache[hash]

    override fun save(hash: String, snapshot: VocabSnapshot, reviewLog: ReviewLogEntry?) {
        cache[hash] = snapshot
        val now = System.currentTimeMillis()
        val statsRows = snapshot.perSkill.map { (skill, s) ->
            VocabStatsEntity(
                hash = hash,
                skill = skill,
                viewTimeMs = s.viewTimeMs,
                viewTimeMsPrev = s.viewTimeMsPrev,
                nViewed = s.nViewed,
                nFailed = s.nFailed,
                lastDisplayed = s.lastDisplayed,
                lastFailed = s.lastFailed,
                lastModifiedMs = now,
                recentInteractions = s.recentInteractions,
                fastTracked = s.fastTracked,
            )
        }
        val flagsRow = VocabFlagsEntity(
            hash = hash,
            ignore = snapshot.ignore,
            flaggedHard = snapshot.flaggedHard,
            lastModifiedMs = now,
        )
        val logRow = reviewLog?.let { it.toEntity() }
        writeScope.launch {
            vocabDao.upsertStatsBulk(statsRows)
            vocabDao.upsertFlags(flagsRow)
            if (logRow != null) reviewLogDao.append(logRow)
        }
    }

    private fun ReviewLogEntry.toEntity(): VocabReviewLogEntity = VocabReviewLogEntity(
        vocabHash = vocabHash,
        skill = skill,
        timestampMs = timestampMs,
        viewTimeMs = viewTimeMs,
        timeToFirstActionMs = timeToFirstActionMs,
        pausedMs = pausedMs,
        commitButton = commitButton,
        tipUsed = tipUsed,
        progressiveReveals = progressiveReveals,
        wordMaskTaps = wordMaskTaps,
        wordReplays = wordReplays,
        sentenceMaskTaps = sentenceMaskTaps,
        spotCheckResult = spotCheckResult,
        sentenceSource = sentenceSource,
        listeningEnabled = listeningEnabled,
        compromised = compromised,
    )

    override suspend fun preload() {
        val stats = vocabDao.loadAllStats()
        val flags = vocabDao.loadAllFlags().associateBy { it.hash }
        val byHash = stats.groupBy { it.hash }
        val newCache = HashMap<String, VocabSnapshot>(byHash.size)
        for ((hash, rows) in byHash) {
            val perSkill = rows.associate { row ->
                row.skill to SkillStatsSnapshot(
                    viewTimeMs = row.viewTimeMs,
                    viewTimeMsPrev = row.viewTimeMsPrev,
                    nViewed = row.nViewed,
                    nFailed = row.nFailed,
                    lastDisplayed = row.lastDisplayed,
                    lastFailed = row.lastFailed,
                    recentInteractions = row.recentInteractions,
                    fastTracked = row.fastTracked,
                )
            }
            val f = flags[hash]
            newCache[hash] = VocabSnapshot(
                perSkill = perSkill,
                ignore = f?.ignore ?: false,
                flaggedHard = f?.flaggedHard ?: false,
            )
        }
        // Some hashes may have a flags row but no stats yet (e.g. a word the
        // user only marked "Hard" without ever practicing it). Catch those by
        // iterating the flag-only hashes too.
        for ((hash, f) in flags) {
            if (hash in newCache) continue
            newCache[hash] = VocabSnapshot(
                perSkill = emptyMap(),
                ignore = f.ignore,
                flaggedHard = f.flaggedHard,
            )
        }
        cache.clear()
        cache.putAll(newCache)
    }

    override fun evict(hashes: Collection<String>) {
        for (h in hashes) cache.remove(h)
    }

    companion object {
        /**
         * Initial-default snapshot for a word that's never been saved. Used by
         * [io.github.wandersalamander.vocabtrainer.dictionary.Vocab] when the cache returns
         * null so the field defaults match the historical SharedPreferences
         * fallbacks (DEFAULT_VIEW_TIME_MS, zeroes everywhere else, both flags
         * false).
         */
        fun defaultSnapshot(): VocabSnapshot = VocabSnapshot(
            perSkill = emptyMap(),
            ignore = false,
            flaggedHard = false,
        )
    }
}

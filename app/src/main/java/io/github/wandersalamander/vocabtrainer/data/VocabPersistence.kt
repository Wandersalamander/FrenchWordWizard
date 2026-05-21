package io.github.wandersalamander.vocabtrainer.data

import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Read/write seam for per-vocab persisted state. Sits between [Vocab] / the
 * dictionary and whichever backend actually owns the bytes (Room today,
 * potentially Drive AppDataFolder or a server in the future). Sync API on
 * purpose — callers (UI, foreground service) already expect the legacy
 * SharedPreferences-flavored "read returns the latest committed value, write
 * is fire-and-forget" semantics.
 *
 * Tests pass a no-op or null implementation (see Vocab's tests) — Vocab
 * tolerates a missing persistence layer the same way it tolerated a null
 * SharedPreferences.
 */
interface VocabPersistence {
    /** Returns the cached snapshot for [hash], or null when the cache has no entry. */
    fun load(hash: String): VocabSnapshot?

    /**
     * Apply [snapshot] to the in-memory cache and persist it (fire-and-forget).
     * When [reviewLog] is non-null, also append the log row in the same write
     * batch so the FK from log → stats is satisfied in order.
     */
    fun save(hash: String, snapshot: VocabSnapshot, reviewLog: ReviewLogEntry? = null)

    /**
     * Fill the in-memory cache from the backing store. Suspend — callers from
     * outside a coroutine context (e.g. MyDictionary.reloadPreferences in the
     * foreground service) wrap in runBlocking. Idempotent: subsequent calls
     * rebuild the cache.
     */
    suspend fun preload()

    /**
     * Remove cached rows for the given hashes. The SettingsActivity wipe-progress
     * flow uses this after deleting the matching DB rows to keep the in-memory
     * view consistent.
     */
    fun evict(hashes: Collection<String>)
}

/**
 * Per-vocab snapshot returned from / passed to [VocabPersistence]. Independent
 * of the mutable in-memory [io.github.wandersalamander.vocabtrainer.dictionary.SkillStats]
 * so the persistence layer can hand callers a stable view without exposing
 * shared references.
 */
data class VocabSnapshot(
    val perSkill: Map<Skill, SkillStatsSnapshot>,
    val ignore: Boolean,
    val flaggedHard: Boolean,
)

data class SkillStatsSnapshot(
    val viewTimeMs: Long,
    val viewTimeMsPrev: Long,
    val nViewed: Int,
    val nFailed: Float,
    val lastDisplayed: Long,
    val lastFailed: Long,
    val recentInteractions: List<VocabInteraction> = emptyList(),
)

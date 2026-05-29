package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

/**
 * Append-only DAO for the per-round review log. Writes are one row per
 * committed round; reads are not on the picker hot path — they're for future
 * training / per-pair history views, so no in-memory cache.
 */
@Dao
interface VocabReviewLogDao {
    @Insert
    suspend fun append(entry: VocabReviewLogEntity): Long

    /** All rows, oldest first — used by the progress backup export. */
    @Query("SELECT * FROM vocab_review_log ORDER BY id ASC")
    suspend fun loadAll(): List<VocabReviewLogEntity>

    /**
     * Bulk insert preserving the original [VocabReviewLogEntity.id]s, used by
     * the progress backup import. REPLACE on conflict makes re-importing the
     * same backup idempotent rather than duplicating the log. Parent
     * `vocab_stats` rows must already be inserted in the same transaction so
     * the FK is satisfied.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun importBulk(rows: List<VocabReviewLogEntity>)

    /** All log rows for a (vocab, skill) pair, oldest first. */
    @Query(
        "SELECT * FROM vocab_review_log WHERE vocab_hash = :hash AND skill = :skill " +
            "ORDER BY timestamp_ms ASC"
    )
    suspend fun loadForPair(hash: String, skill: Skill): List<VocabReviewLogEntity>

    /** Used by the per-language wipe flow to drop the log alongside the stats. */
    @Query("DELETE FROM vocab_review_log WHERE vocab_hash IN (:hashes)")
    suspend fun deleteForHashes(hashes: Collection<String>)

    /** Total row count — useful for diagnostics / "your log has N entries" UI. */
    @Query("SELECT COUNT(*) FROM vocab_review_log")
    suspend fun count(): Long
}

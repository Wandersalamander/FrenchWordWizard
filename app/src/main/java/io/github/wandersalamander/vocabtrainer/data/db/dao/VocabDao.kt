package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabFlagsEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity

/**
 * Per-vocab DAO. Bulk reads are used at dictionary load time to hydrate the
 * in-memory map all at once (a few-ms SELECT * is much cheaper than 5000 ×
 * pointed reads). Writes go through [upsertStats] / [upsertFlags] from the
 * per-round save path.
 */
@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab_stats")
    suspend fun loadAllStats(): List<VocabStatsEntity>

    @Query("SELECT * FROM vocab_flags")
    suspend fun loadAllFlags(): List<VocabFlagsEntity>

    @Upsert
    suspend fun upsertStats(stats: VocabStatsEntity)

    @Upsert
    suspend fun upsertFlags(flags: VocabFlagsEntity)

    @Upsert
    suspend fun upsertStatsBulk(stats: List<VocabStatsEntity>)

    @Upsert
    suspend fun upsertFlagsBulk(flags: List<VocabFlagsEntity>)

    /** Used by SettingsActivity's per-language wipe. */
    @Query("DELETE FROM vocab_stats WHERE hash IN (:hashes)")
    suspend fun deleteStatsForHashes(hashes: Collection<String>)

    @Query("DELETE FROM vocab_flags WHERE hash IN (:hashes)")
    suspend fun deleteFlagsForHashes(hashes: Collection<String>)
}

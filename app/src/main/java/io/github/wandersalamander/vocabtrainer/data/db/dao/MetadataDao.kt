package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.MetadataEntity

@Dao
interface MetadataDao {
    @Query("SELECT value FROM metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    /** All rows — used by the progress backup export. */
    @Query("SELECT * FROM metadata")
    suspend fun loadAll(): List<MetadataEntity>

    @Upsert
    suspend fun put(entry: MetadataEntity)
}

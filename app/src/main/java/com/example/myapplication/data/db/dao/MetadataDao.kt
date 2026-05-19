package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapplication.data.db.entities.MetadataEntity

@Dao
interface MetadataDao {
    @Query("SELECT value FROM metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: MetadataEntity)
}

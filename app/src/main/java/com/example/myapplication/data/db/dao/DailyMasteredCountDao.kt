package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapplication.data.db.entities.DailyMasteredCountEntity

@Dao
interface DailyMasteredCountDao {
    @Query("SELECT * FROM daily_mastered_count WHERE languageCode = :languageCode")
    suspend fun load(languageCode: String): DailyMasteredCountEntity?

    @Upsert
    suspend fun upsert(entry: DailyMasteredCountEntity)
}

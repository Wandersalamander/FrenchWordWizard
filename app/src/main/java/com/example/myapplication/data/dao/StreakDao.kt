package com.example.myapplication.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapplication.data.entities.STREAK_STATE_SINGLETON_ID
import com.example.myapplication.data.entities.StreakStateEntity

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak_state WHERE id = $STREAK_STATE_SINGLETON_ID")
    suspend fun load(): StreakStateEntity?

    @Upsert
    suspend fun upsert(state: StreakStateEntity)
}

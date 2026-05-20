package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.STREAK_STATE_SINGLETON_ID
import io.github.wandersalamander.vocabtrainer.data.db.entities.StreakStateEntity

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak_state WHERE id = $STREAK_STATE_SINGLETON_ID")
    suspend fun load(): StreakStateEntity?

    @Upsert
    suspend fun upsert(state: StreakStateEntity)
}

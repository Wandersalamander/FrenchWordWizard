package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.LifetimeScoreEntity

@Dao
interface LifetimeScoreDao {
    @Query("SELECT * FROM lifetime_score WHERE languageCode = :languageCode")
    suspend fun load(languageCode: String): LifetimeScoreEntity?

    @Upsert
    suspend fun upsert(entry: LifetimeScoreEntity)
}

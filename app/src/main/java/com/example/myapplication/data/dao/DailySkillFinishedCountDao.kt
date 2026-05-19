package com.example.myapplication.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.myapplication.data.entities.DailySkillFinishedCountEntity
import com.example.myapplication.dictionary.Skill

@Dao
interface DailySkillFinishedCountDao {
    @Query(
        "SELECT * FROM daily_skill_finished_count " +
            "WHERE languageCode = :languageCode AND skill = :skill"
    )
    suspend fun load(languageCode: String, skill: Skill): DailySkillFinishedCountEntity?

    @Query("SELECT * FROM daily_skill_finished_count WHERE languageCode = :languageCode")
    suspend fun loadAllForLanguage(languageCode: String): List<DailySkillFinishedCountEntity>

    @Upsert
    suspend fun upsert(entry: DailySkillFinishedCountEntity)

    @Upsert
    suspend fun upsertBulk(entries: List<DailySkillFinishedCountEntity>)
}

package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.dictionary.Skill

@Dao
interface DailySkillFinishedCountDao {
    @Query(
        "SELECT * FROM daily_skill_finished_count " +
            "WHERE languageCode = :languageCode AND skill = :skill"
    )
    suspend fun load(languageCode: String, skill: Skill): DailySkillFinishedCountEntity?

    @Query("SELECT * FROM daily_skill_finished_count WHERE languageCode = :languageCode")
    suspend fun loadAllForLanguage(languageCode: String): List<DailySkillFinishedCountEntity>

    /** All rows — used by the progress backup export. */
    @Query("SELECT * FROM daily_skill_finished_count")
    suspend fun loadAll(): List<DailySkillFinishedCountEntity>

    @Upsert
    suspend fun upsert(entry: DailySkillFinishedCountEntity)

    @Upsert
    suspend fun upsertBulk(entries: List<DailySkillFinishedCountEntity>)
}

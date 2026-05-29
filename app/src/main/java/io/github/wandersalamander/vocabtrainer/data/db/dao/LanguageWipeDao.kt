package io.github.wandersalamander.vocabtrainer.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity

@Dao
interface LanguageWipeDao {
    @Query("SELECT wipedAtMs FROM language_wipe WHERE languageCode = :languageCode")
    suspend fun loadTimestamp(languageCode: String): Long?

    /** All rows — used by the progress backup export. */
    @Query("SELECT * FROM language_wipe")
    suspend fun loadAll(): List<LanguageWipeEntity>

    @Upsert
    suspend fun upsert(entry: LanguageWipeEntity)
}

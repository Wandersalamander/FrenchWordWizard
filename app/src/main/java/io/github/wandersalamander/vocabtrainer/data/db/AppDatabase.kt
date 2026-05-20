package io.github.wandersalamander.vocabtrainer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.wandersalamander.vocabtrainer.data.db.dao.DailyMasteredCountDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.DailySkillFinishedCountDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.LanguageWipeDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.MetadataDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.StreakDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabDao
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailyMasteredCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.MetadataEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.StreakStateEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabFlagsEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity

/**
 * Single SQLite store for all user data: per-vocab stats and flags, streak
 * state, per-language wipe markers, daily mastery counters. Settings that are
 * truly small and app-local (chosen language, tutorial-shown flag, sort index)
 * stay in [AppPrefs] / SharedPreferences — Room is for the user-progress data
 * we want to be able to back up, sync, and migrate cleanly. See ADR-001.
 */
@Database(
    entities = [
        VocabStatsEntity::class,
        VocabFlagsEntity::class,
        StreakStateEntity::class,
        LanguageWipeEntity::class,
        DailyMasteredCountEntity::class,
        DailySkillFinishedCountEntity::class,
        MetadataEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vocabDao(): VocabDao
    abstract fun streakDao(): StreakDao
    abstract fun languageWipeDao(): LanguageWipeDao
    abstract fun dailyMasteredCountDao(): DailyMasteredCountDao
    abstract fun dailySkillFinishedCountDao(): DailySkillFinishedCountDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        private const val DB_NAME = "app.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        /**
         * Adds [VocabStatsEntity.recentInteractions] (TEXT, default empty
         * string). Existing rows decode to an empty list via [Converters],
         * giving long-time users a clean baseline without resetting counters.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE vocab_stats ADD COLUMN recentInteractions TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}

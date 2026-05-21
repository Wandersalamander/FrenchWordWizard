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
import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabReviewLogDao
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailyMasteredCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.MetadataEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.StreakStateEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabFlagsEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity

/**
 * Single SQLite store for all user data: per-vocab stats and flags, the
 * append-only per-round review log, streak state, per-language wipe markers,
 * daily mastery counters. Settings that are truly small and app-local (chosen
 * language, tutorial-shown flag, sort index) stay in [AppPrefs] /
 * SharedPreferences — Room is for the user-progress data we want to be able
 * to back up, sync, and migrate cleanly. See ADR-001.
 */
@Database(
    entities = [
        VocabStatsEntity::class,
        VocabFlagsEntity::class,
        VocabReviewLogEntity::class,
        StreakStateEntity::class,
        LanguageWipeEntity::class,
        DailyMasteredCountEntity::class,
        DailySkillFinishedCountEntity::class,
        MetadataEntity::class,
    ],
    version = 3,
    // JSON schemas are exported to app/schemas/<AppDatabase>/<version>.json
    // (configured via the ksp room.schemaLocation arg in build.gradle).
    // MigrationTestHelper reads them; commit the generated files alongside
    // every schema bump.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vocabDao(): VocabDao
    abstract fun vocabReviewLogDao(): VocabReviewLogDao
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
                .addMigrations(MIGRATION_2_3)
                .build()
                .also { instance = it }
        }

        /**
         * Adds the append-only [VocabReviewLogEntity] table. Purely additive —
         * no existing rows touched, no columns added to existing tables — so
         * pre-v3 users keep all their stats, flags, streak, and daily counters
         * intact and just start logging interactions from the upgrade onward.
         *
         * Column types mirror what Room generates from the entity:
         * - Kotlin `Long` / `Int` → `INTEGER NOT NULL`
         * - Kotlin `Long?` → `INTEGER` (nullable)
         * - Kotlin `String` / enum (via TypeConverter) → `TEXT NOT NULL`
         * - Kotlin `Boolean` → `INTEGER NOT NULL` (0 / 1)
         *
         * FK to `vocab_stats(hash, skill)` with `ON DELETE CASCADE` so the
         * per-language wipe in SettingsActivity also drops matching log rows.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQL kept byte-identical to what Room generates in
                // AppDatabase_Impl.createAllTables() for this entity, so the
                // runtime schema validator that fires after migrations is
                // guaranteed to see a matching TableInfo. If you change the
                // entity, regenerate (./gradlew kspDebugKotlin) and copy the
                // CREATE TABLE / CREATE INDEX lines back in here.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vocab_review_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`vocab_hash` TEXT NOT NULL, " +
                        "`skill` TEXT NOT NULL, " +
                        "`timestamp_ms` INTEGER NOT NULL, " +
                        "`view_time_ms` INTEGER NOT NULL, " +
                        "`time_to_first_action_ms` INTEGER, " +
                        "`paused_ms` INTEGER NOT NULL, " +
                        "`commit_button` TEXT NOT NULL, " +
                        "`tip_used` TEXT NOT NULL, " +
                        "`progressive_reveals` INTEGER NOT NULL, " +
                        "`word_mask_taps` INTEGER NOT NULL, " +
                        "`word_replays` INTEGER NOT NULL, " +
                        "`sentence_mask_taps` INTEGER NOT NULL, " +
                        "`spot_check_result` TEXT NOT NULL, " +
                        "`sentence_source` TEXT NOT NULL, " +
                        "`listening_enabled` INTEGER NOT NULL, " +
                        "`compromised` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`vocab_hash`, `skill`) REFERENCES `vocab_stats`(`hash`, `skill`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_vocab_review_log_vocab_hash_skill_timestamp_ms` " +
                        "ON `vocab_review_log` (`vocab_hash`, `skill`, `timestamp_ms`)"
                )
            }
        }
    }
}

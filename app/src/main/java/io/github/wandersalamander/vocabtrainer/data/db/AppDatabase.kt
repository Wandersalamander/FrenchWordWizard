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
import io.github.wandersalamander.vocabtrainer.data.db.dao.LifetimeScoreDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.MetadataDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.StreakDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabDao
import io.github.wandersalamander.vocabtrainer.data.db.dao.VocabReviewLogDao
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailyMasteredCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.DailySkillFinishedCountEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LanguageWipeEntity
import io.github.wandersalamander.vocabtrainer.data.db.entities.LifetimeScoreEntity
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
 * to back up, sync, and migrate cleanly. See ADR-001
 * (docs/adr/0001-room-as-source-of-truth-and-backup-strategy.md).
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
        LifetimeScoreEntity::class,
        MetadataEntity::class,
    ],
    version = 7,
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
    abstract fun lifetimeScoreDao(): LifetimeScoreDao
    abstract fun metadataDao(): MetadataDao

    companion object {
        private const val DB_NAME = "app.db"

        // Current schema version. MUST equal @Database(version = …) above.
        // Exposed so migration tests assert against one version instead of a
        // scattered literal; MigrationCoverageTest fails if the two drift.
        internal const val SCHEMA_VERSION = 7

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                // Migration policy: deliberately NO fallbackToDestructiveMigration.
                // A missing migration must fail loudly in dev/CI (MigrationCoverageTest
                // opens the earliest schema through this exact list), never silently
                // wipe user progress — protecting that progress is the whole point of
                // the Room-as-source-of-truth design (ADR-001). Every schema bump adds
                // its Migration to ALL_MIGRATIONS and ships a test.
                .addMigrations(*ALL_MIGRATIONS)
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

        /**
         * Adds [VocabStatsEntity.fastTracked] (INTEGER NOT NULL, default 0).
         * Replaces the prior "bump nTimesViewed to 6" hack with an honest
         * sub-threshold short-circuit in [SkillStats.failureProbability], so
         * the counters / WordList display / refresh-pool stability calc all
         * stay truthful. Purely additive — every existing row decodes to
         * fastTracked=false, which is the correct default for any pre-v4 pair
         * (the bump-based fast-track never landed in a shipped build).
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vocab_stats` " +
                        "ADD COLUMN `fastTracked` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds [LifetimeScoreEntity] — one row per language tracking the
         * monotone-increasing knowledge score (mastery + retention events).
         * Purely additive — no existing rows touched. Pre-v5 users get an
         * absent row, which [LifetimeScoreTracker] backfills lazily from
         * the current lifetime-mastered count on first read.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lifetime_score` (" +
                        "`languageCode` TEXT NOT NULL, " +
                        "`score` INTEGER NOT NULL, " +
                        "`lastModifiedMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`languageCode`))"
                )
            }
        }

        /**
         * Adds [VocabStatsEntity.refreshIntervalDays] (REAL NOT NULL, default
         * 1.0). Replaces the prior streak-based stability formula in
         * [io.github.wandersalamander.vocabtrainer.dictionary.Vocab.failureProbabilityMasteredWords]
         * with a doubling refresh schedule maintained per (vocab, skill) on
         * [io.github.wandersalamander.vocabtrainer.dictionary.SkillStats]. Purely
         * additive — every pre-v6 row decodes to 1.0, meaning existing mastered
         * pairs become refresh-eligible 1 day after their last view. That's
         * intentional: the old formula over-stabilized words, so this thaw is
         * the migration we want.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vocab_stats` " +
                        "ADD COLUMN `refreshIntervalDays` REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        /**
         * Adds [VocabReviewLogEntity.speakRecordings] (INTEGER NOT NULL,
         * default 0) — the per-round count of SPEAK takes, used by
         * [io.github.wandersalamander.vocabtrainer.data.scoring.RoundScoring.speakReRecordPenalty]
         * to charge each re-record. Purely additive — every pre-v7 row decodes
         * to 0 (no takes captured), which yields zero re-record penalty on
         * replay. That's correct: the fact didn't exist before this version, so
         * history re-derives exactly as it scored at the time.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vocab_review_log` " +
                        "ADD COLUMN `speak_recordings` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Single source of truth for the migrations get() applies.
        // MigrationCoverageTest exercises this same array through the real
        // builder, so registering a migration here wires it into prod AND the
        // coverage test at once. Declared after MIGRATION_2_3 so the property
        // initializer sees a non-null value.
        internal val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}

package io.github.wandersalamander.vocabtrainer.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Materializes a v2-schema [AppDatabase] file on disk for migration tests.
 *
 * v2 shipped before `exportSchema = true`, so there's no `2.json` for
 * [androidx.room.testing.MigrationTestHelper.createDatabase] to read. Both the
 * per-migration test ([Migration2To3Test]) and the end-to-end coverage test
 * ([MigrationCoverageTest]) bootstrap v2 by replaying the exact CREATE TABLE
 * statements Room generated at v2 (the current v3 set minus `vocab_review_log`
 * and its index).
 *
 * These statements are frozen v2 history — do NOT edit them when the live
 * schema changes. Schema evolution is expressed through later migrations and
 * their exported JSON, not by mutating this baseline.
 */
internal object LegacyV2Schema {

    /**
     * Create [dbName] on disk with the v2 schema (user_version = 2). When
     * [seedSampleRows] is true, inserts one `vocab_stats` and one `vocab_flags`
     * row (hash `h1`) that callers can assert survives the migration.
     */
    fun materialize(context: Context, dbName: String, seedSampleRows: Boolean) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(/* version = */ 2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V2_VOCAB_STATS)
                    db.execSQL(V2_VOCAB_FLAGS)
                    db.execSQL(V2_STREAK_STATE)
                    db.execSQL(V2_LANGUAGE_WIPE)
                    db.execSQL(V2_DAILY_MASTERED_COUNT)
                    db.execSQL(V2_DAILY_SKILL_FINISHED_COUNT)
                    db.execSQL(V2_METADATA)
                    db.execSQL(ROOM_MASTER_TABLE)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        factory.create(config).use { helper ->
            helper.writableDatabase.let { db ->
                if (seedSampleRows) {
                    db.execSQL(
                        "INSERT INTO vocab_stats (hash, skill, viewTimeMs, viewTimeMsPrev, " +
                            "nViewed, nFailed, lastDisplayed, lastFailed, lastModifiedMs, " +
                            "recentInteractions) " +
                            "VALUES ('h1', 'READ', 5000, 5000, 3, 0.5, 1000, 0, 1000, '')"
                    )
                    db.execSQL(
                        "INSERT INTO vocab_flags (hash, ignore, flaggedHard, lastModifiedMs) " +
                            "VALUES ('h1', 0, 1, 1000)"
                    )
                }
            }
        }
    }

    // CREATE TABLE statements mirror what `AppDatabase_Impl.createAllTables`
    // generated at v2 (= the current v3 set minus `vocab_review_log`).
    private const val V2_VOCAB_STATS =
        "CREATE TABLE IF NOT EXISTS `vocab_stats` (" +
            "`hash` TEXT NOT NULL, `skill` TEXT NOT NULL, " +
            "`viewTimeMs` INTEGER NOT NULL, `viewTimeMsPrev` INTEGER NOT NULL, " +
            "`nViewed` INTEGER NOT NULL, `nFailed` REAL NOT NULL, " +
            "`lastDisplayed` INTEGER NOT NULL, `lastFailed` INTEGER NOT NULL, " +
            "`lastModifiedMs` INTEGER NOT NULL, " +
            "`recentInteractions` TEXT NOT NULL DEFAULT '', " +
            "PRIMARY KEY(`hash`, `skill`))"

    private const val V2_VOCAB_FLAGS =
        "CREATE TABLE IF NOT EXISTS `vocab_flags` (" +
            "`hash` TEXT NOT NULL, `ignore` INTEGER NOT NULL, " +
            "`flaggedHard` INTEGER NOT NULL, `lastModifiedMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`hash`))"

    private const val V2_STREAK_STATE =
        "CREATE TABLE IF NOT EXISTS `streak_state` (" +
            "`id` INTEGER NOT NULL, `current` INTEGER NOT NULL, `lastDate` TEXT, " +
            "`longest` INTEGER NOT NULL, `freezes` INTEGER NOT NULL, " +
            "`reminderEnabled` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, " +
            "`reminderMinute` INTEGER NOT NULL, `lastModifiedMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    private const val V2_LANGUAGE_WIPE =
        "CREATE TABLE IF NOT EXISTS `language_wipe` (" +
            "`languageCode` TEXT NOT NULL, `wipedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`languageCode`))"

    private const val V2_DAILY_MASTERED_COUNT =
        "CREATE TABLE IF NOT EXISTS `daily_mastered_count` (" +
            "`languageCode` TEXT NOT NULL, `date` TEXT NOT NULL, " +
            "`count` INTEGER NOT NULL, `lastModifiedMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`languageCode`))"

    private const val V2_DAILY_SKILL_FINISHED_COUNT =
        "CREATE TABLE IF NOT EXISTS `daily_skill_finished_count` (" +
            "`languageCode` TEXT NOT NULL, `skill` TEXT NOT NULL, " +
            "`date` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
            "`lastModifiedMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`languageCode`, `skill`))"

    private const val V2_METADATA =
        "CREATE TABLE IF NOT EXISTS `metadata` (" +
            "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"

    // Room's own bookkeeping table — created automatically on a normal
    // Room.databaseBuilder open. We create it explicitly here so the post-open
    // schema validation (MigrationTestHelper, or Room's real builder) doesn't
    // flag a missing table.
    private const val ROOM_MASTER_TABLE =
        "CREATE TABLE IF NOT EXISTS room_master_table " +
            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
}

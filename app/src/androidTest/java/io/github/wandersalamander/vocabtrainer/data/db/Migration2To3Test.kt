package io.github.wandersalamander.vocabtrainer.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates [AppDatabase.MIGRATION_2_3]: the additive upgrade that adds the
 * `vocab_review_log` table + index, without touching any pre-existing row in
 * `vocab_stats` / `vocab_flags` / streak / daily counters.
 *
 * v2 was shipped before `exportSchema = true`, so there's no `2.json` for
 * [MigrationTestHelper.createDatabase] to read. We bootstrap the v2 schema
 * by replaying the exact CREATE TABLE statements Room generated for v2 (the
 * v3 set minus `vocab_review_log`), then hand the resulting DB to the
 * helper which runs the migration and validates the destination against
 * `3.json`. Future migrations (3→4 onward) won't need this dance — both
 * source and target JSON will exist.
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Before
    fun deleteAnyStaleDb() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
    }

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
    }

    @Test
    fun migration_2_3_addsLogTable_and_preservesExistingRows() {
        seedV2DatabaseWithSampleRows()

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            /* version = */ 3,
            /* validateDroppedTables = */ true,
            AppDatabase.MIGRATION_2_3,
        )

        // vocab_stats row from v2 survived unchanged.
        migrated.query(
            "SELECT hash, skill, nViewed, nFailed, recentInteractions FROM vocab_stats"
        ).use { c ->
            assertTrue("expected the seeded vocab_stats row to survive", c.moveToFirst())
            assertEquals("h1", c.getString(0))
            assertEquals("READ", c.getString(1))
            assertEquals(3, c.getInt(2))
            assertEquals(0.5f, c.getFloat(3), 1e-6f)
            assertEquals("", c.getString(4))
        }

        // vocab_flags row from v2 also survived.
        migrated.query("SELECT ignore, flaggedHard FROM vocab_flags WHERE hash = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
            assertEquals(1, c.getInt(1))
        }

        // New vocab_review_log table exists and starts empty.
        migrated.query("SELECT COUNT(*) FROM vocab_review_log").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }

        // Composite index on (vocab_hash, skill, timestamp_ms) is in place.
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'vocab_review_log'"
        ).use { c ->
            val indexNames = buildList {
                while (c.moveToNext()) add(c.getString(0))
            }
            assertTrue(
                "expected the (vocab_hash, skill, timestamp_ms) index, got $indexNames",
                indexNames.any {
                    it == "index_vocab_review_log_vocab_hash_skill_timestamp_ms"
                },
            )
        }

        // FK is wired with ON DELETE CASCADE — deleting the stats row should
        // cascade to any future log rows. Smoke-test by inserting a log row
        // referencing the seeded pair, deleting the parent, and verifying the
        // child went too.
        migrated.execSQL(
            "INSERT INTO vocab_review_log (vocab_hash, skill, timestamp_ms, view_time_ms, " +
                "time_to_first_action_ms, paused_ms, commit_button, tip_used, " +
                "progressive_reveals, word_mask_taps, word_replays, sentence_mask_taps, " +
                "spot_check_result, sentence_source, listening_enabled, compromised) " +
                "VALUES ('h1', 'READ', 5000, 3000, 1200, 0, 'NEXT', 'NONE', " +
                "0, 0, 0, 0, 'NONE', 'EASY', 1, 0)"
        )
        // FK enforcement is off by default on a raw connection; turn it on so
        // the cascade actually fires.
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM vocab_stats WHERE hash = 'h1' AND skill = 'READ'")
        migrated.query("SELECT COUNT(*) FROM vocab_review_log").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "ON DELETE CASCADE should have removed the child log row",
                0,
                c.getInt(0),
            )
        }
    }

    /**
     * Materialize a v2 schema on disk at the path the helper expects, then
     * seed it with one stats row and one flags row so the migration test has
     * something to verify survives. The CREATE TABLE statements mirror what
     * `AppDatabase_Impl.createAllTables` generated at v2 (= the current v3
     * set minus `vocab_review_log` and its index).
     */
    private fun seedV2DatabaseWithSampleRows() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
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

    companion object {
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
        // Room.databaseBuilder open. We create it explicitly here so
        // MigrationTestHelper's post-migration validation doesn't flag a
        // missing table.
        private const val ROOM_MASTER_TABLE =
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
    }
}

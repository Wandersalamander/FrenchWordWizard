package io.github.wandersalamander.vocabtrainer.data.db

import androidx.room.testing.MigrationTestHelper
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
 * Validates [AppDatabase.MIGRATION_5_6]: adds
 * [io.github.wandersalamander.vocabtrainer.data.db.entities.VocabStatsEntity.refreshIntervalDays]
 * to `vocab_stats` as `REAL NOT NULL DEFAULT 1.0`. Replaces the prior
 * cleanStreakDays-based stability formula in `Vocab.failureProbabilityMasteredWords`
 * with a doubling per-pair refresh schedule maintained on [SkillStats]. Purely
 * additive — pre-v6 rows decode to 1.0 (the 1-day floor), which is the intended
 * thaw: existing mastered words become refresh-eligible again instead of
 * sitting indefinitely under the over-stabilizing implicit-views streak.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6Test {

    private val dbName = "migration-5-6-test.db"

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
    fun migration_5_6_addsRefreshIntervalDays_andPreservesExistingRows() {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO vocab_stats (hash, skill, viewTimeMs, viewTimeMsPrev, " +
                    "nViewed, nFailed, lastDisplayed, lastFailed, lastModifiedMs, " +
                    "recentInteractions, fastTracked) " +
                    "VALUES ('h1', 'READ', 1500, 1500, 50, 0.0, 1000, 0, 1000, '', 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            /* version = */ 6,
            /* validateDroppedTables = */ true,
            AppDatabase.MIGRATION_5_6,
        )

        migrated.query(
            "SELECT nViewed, nFailed, refreshIntervalDays FROM vocab_stats WHERE hash = 'h1'"
        ).use { c ->
            assertTrue("seeded row should survive 5→6", c.moveToFirst())
            assertEquals(50, c.getInt(0))
            assertEquals(0.0f, c.getFloat(1), 1e-6f)
            assertEquals(
                "new column defaults to 1.0 (1-day floor) for pre-v6 rows",
                1.0f,
                c.getFloat(2),
                1e-6f,
            )
        }

        migrated.execSQL("UPDATE vocab_stats SET refreshIntervalDays = 32.0 WHERE hash = 'h1'")
        migrated.query("SELECT refreshIntervalDays FROM vocab_stats WHERE hash = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(32.0f, c.getFloat(0), 1e-6f)
        }
    }
}

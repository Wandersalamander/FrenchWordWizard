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
 * Validates [AppDatabase.MIGRATION_3_4]: adds [VocabStatsEntity.fastTracked] to
 * `vocab_stats` as `INTEGER NOT NULL DEFAULT 0`, replacing the prior
 * nTimesViewed-bump hack with an honest sub-threshold short-circuit in
 * [SkillStats.failureProbability]. Purely additive — every existing row must
 * decode to fastTracked=false without any other column changing.
 *
 * Unlike [Migration2To3Test], v3 was exported (3.json exists), so this uses
 * the simpler [MigrationTestHelper.createDatabase] entry point rather than
 * hand-rolling the source schema.
 */
@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    private val dbName = "migration-3-4-test.db"

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
    fun migration_3_4_addsFastTrackedColumn_and_preservesExistingRows() {
        // Seed a v3 database with a representative row whose nTimesViewed deliberately
        // differs from the old fast-track bump value (6) — the migration must NOT
        // touch counters in any way.
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO vocab_stats (hash, skill, viewTimeMs, viewTimeMsPrev, " +
                    "nViewed, nFailed, lastDisplayed, lastFailed, lastModifiedMs, " +
                    "recentInteractions) " +
                    "VALUES ('h1', 'READ', 1500, 1500, 4, 1.5, 1000, 0, 1000, '')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            /* version = */ 4,
            /* validateDroppedTables = */ true,
            AppDatabase.MIGRATION_3_4,
        )

        // Existing row carried over untouched, including the new column defaulted to 0.
        migrated.query(
            "SELECT nViewed, nFailed, fastTracked FROM vocab_stats WHERE hash = 'h1'"
        ).use { c ->
            assertTrue("seeded row should survive 3→4", c.moveToFirst())
            assertEquals(4, c.getInt(0))
            assertEquals(1.5f, c.getFloat(1), 1e-6f)
            assertEquals(
                "new column defaults to 0 (false) for pre-v4 rows",
                0,
                c.getInt(2),
            )
        }

        // Smoke-test: the column is writable as a boolean (INTEGER 0/1).
        migrated.execSQL("UPDATE vocab_stats SET fastTracked = 1 WHERE hash = 'h1'")
        migrated.query("SELECT fastTracked FROM vocab_stats WHERE hash = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
}

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
 * Validates [AppDatabase.MIGRATION_6_7]: adds
 * [io.github.wandersalamander.vocabtrainer.data.db.entities.VocabReviewLogEntity.speakRecordings]
 * to `vocab_review_log` as `INTEGER NOT NULL DEFAULT 0`. The per-round count of
 * SPEAK takes feeds the re-record penalty in
 * [io.github.wandersalamander.vocabtrainer.data.scoring.RoundScoring.speakReRecordPenalty].
 * Purely additive — pre-v7 log rows decode to 0 (no takes captured), which is
 * the only correct default: the fact didn't exist before this version, so a
 * replay of old history charges no re-record penalty.
 */
@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    private val dbName = "migration-6-7-test.db"

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
    fun migration_6_7_addsSpeakRecordings_andPreservesExistingRows() {
        helper.createDatabase(dbName, 6).use { db ->
            // Parent stats row first (the log FKs vocab_stats(hash, skill)).
            db.execSQL(
                "INSERT INTO vocab_stats (hash, skill, viewTimeMs, viewTimeMsPrev, " +
                    "nViewed, nFailed, lastDisplayed, lastFailed, lastModifiedMs, " +
                    "recentInteractions, fastTracked, refreshIntervalDays) " +
                    "VALUES ('h1', 'SPEAK', 1500, 1500, 3, 0.0, 1000, 0, 1000, '', 0, 1.0)"
            )
            db.execSQL(
                "INSERT INTO vocab_review_log (vocab_hash, skill, timestamp_ms, " +
                    "view_time_ms, time_to_first_action_ms, paused_ms, commit_button, " +
                    "tip_used, progressive_reveals, word_mask_taps, word_replays, " +
                    "sentence_mask_taps, spot_check_result, sentence_source, " +
                    "listening_enabled, compromised) " +
                    "VALUES ('h1', 'SPEAK', 2000, 1500, 1500, 0, 'NEXT', 'NONE', " +
                    "0, 0, 0, 0, 'NONE', 'EASY', 1, 0)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            /* version = */ 7,
            /* validateDroppedTables = */ true,
            AppDatabase.MIGRATION_6_7,
        )

        migrated.query(
            "SELECT commit_button, speak_recordings FROM vocab_review_log WHERE vocab_hash = 'h1'"
        ).use { c ->
            assertTrue("seeded log row should survive 6→7", c.moveToFirst())
            assertEquals("NEXT", c.getString(0))
            assertEquals(
                "new column defaults to 0 (no takes captured) for pre-v7 rows",
                0,
                c.getInt(1),
            )
        }

        migrated.execSQL("UPDATE vocab_review_log SET speak_recordings = 4 WHERE vocab_hash = 'h1'")
        migrated.query("SELECT speak_recordings FROM vocab_review_log WHERE vocab_hash = 'h1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4, c.getInt(0))
        }
    }
}

package io.github.wandersalamander.vocabtrainer.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end guard against the classic migration footgun: bumping
 * `@Database(version = …)` and the exported schema but forgetting to register
 * the new migration in [AppDatabase.ALL_MIGRATIONS].
 *
 * Where [Migration2To3Test] validates one specific migration's SQL, this opens
 * the EARLIEST shipped schema (v2) through the REAL production builder — the
 * same `addMigrations(*ALL_MIGRATIONS)` list `AppDatabase.get` uses. Room then
 * walks every registered migration up to [AppDatabase.SCHEMA_VERSION] and
 * validates the resulting schema against the compiled entities:
 *
 *  - a missing migration throws `IllegalStateException("A migration from X to Y
 *    was required …")`,
 *  - a migration that produces the wrong shape fails Room's post-migration
 *    validation,
 *  - and a [AppDatabase.SCHEMA_VERSION] that drifts from `@Database(version)`
 *    trips the version assertion below.
 *
 * Any of those turns this test red before the broken build can reach a device.
 */
@RunWith(AndroidJUnit4::class)
class MigrationCoverageTest {

    private val dbName = "migration-coverage-test.db"

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun deleteAnyStaleDb() {
        context.deleteDatabase(dbName)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun earliestSchema_migratesToCurrent_throughRealMigrationList() {
        // Lay down a real v2 database (with a sample row) on disk.
        LegacyV2Schema.materialize(context, dbName, seedSampleRows = true)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        try {
            // Room's builder is lazy; touching the connection forces the open,
            // which runs the migration chain and the schema validation.
            val support = db.openHelper.writableDatabase
            assertEquals(
                "v2 DB should migrate all the way to the current schema version",
                AppDatabase.SCHEMA_VERSION,
                support.version,
            )

            // The seeded v2 row survived the full migration chain, not just 2→3.
            support.query("SELECT COUNT(*) FROM vocab_stats").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("seeded v2 row should survive every migration", 1, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}

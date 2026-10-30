package com.devil.phoenixproject.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Android-driver reproduction of a canonical 1.0.8 (v59) database opening in 1.0.9
 * (v60). The old DDL is pinned to the released tag, not reconstructed from today's
 * schema. Native SQLite exercises Android's open-helper callbacks without JDBC.
 *
 * SDK 35 is a representative Pixel 9 platform, not evidence of the reporting
 * device's OS, database contents, APK configuration, or full application startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Android109UpgradeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context by lazy {
        IsolatedDatabaseContext(
            ApplicationProvider.getApplicationContext(),
            temporaryFolder.newFolder("android-upgrade"),
        )
    }

    @Test
    fun v108Database_upgradesThroughProductionDriver_andReopensWithOldAndNewData() {
        assertEquals(60L, PhoenixDatabase.Schema.version, "Revisit this release-specific fixture when the schema advances")
        seedReleased108Database()

        val upgraded = DriverFactory(context).createDriver()
        try {
            assertHealthyUpgrade(upgraded)
            assertReleasedDataPreserved(upgraded)
            assertEquals(0L, upgraded.longValue("SELECT COUNT(*) FROM PolarBeatSample"))
            assertEquals(0L, upgraded.longValue("SELECT COUNT(*) FROM PolarEcgSegment"))

            // Exercise generated v60 queries as well as DDL inspection. Old rows
            // must deserialize with null telemetry, and new telemetry must persist.
            val queries = PhoenixDatabase(upgraded).phoenixDatabaseQueries
            queries.insertPolarBeatSample(SESSION_ID, 1_730_000_001_000, "WORKING", 120, 500.0, 35.0, 3.0, 1.0, 42)
            queries.insertPolarEcgSegment(SESSION_ID, 123_000_000, 1_730_000_001_000, "WORKING", "12,15,-3")
            queries.insertMetricWithPolar(
                SESSION_ID, 1_730_000_001_000, 100.0, 101.0, 250.0, 251.0, 20.0, 21.0, 100.0, 0,
                120, 500.0, 3.0, 1.0, 42, 1, 2, 3,
            )
            assertTrue(queries.selectPolarBeatSamplesBySession(SESSION_ID).executeAsList().isNotEmpty())
            assertTrue(queries.selectPolarEcgSegmentsBySession(SESSION_ID).executeAsList().isNotEmpty())
        } finally {
            upgraded.close()
        }

        val reopened = DriverFactory(context).createDriver()
        try {
            assertHealthyUpgrade(reopened)
            assertReleasedDataPreserved(reopened)
            val queries = PhoenixDatabase(reopened).phoenixDatabaseQueries
            val beat = queries.selectPolarBeatSamplesBySession(SESSION_ID).executeAsOne()
            assertEquals(120L, beat.hrBpm)
            assertEquals(500.0, beat.rrMs)
            assertEquals("12,15,-3", queries.selectPolarEcgSegmentsBySession(SESSION_ID).executeAsOne().samplesUvCsv)
            val metric = queries.selectMetricsBySession(SESSION_ID).executeAsList().last()
            assertEquals(120L, metric.heartRateBpm)
            assertEquals(3L, metric.chestAccZMg)
            assertEquals(2L, reopened.longValue("SELECT COUNT(*) FROM MetricSample"))

            // onOpen must enable FK enforcement on both the upgrade and reopen.
            assertFailsWith<android.database.sqlite.SQLiteException> {
                reopened.execute(null, "INSERT INTO MetricSample(sessionId, timestamp) VALUES ('missing-session', 1)", 0)
            }
        } finally {
            reopened.close()
        }
    }

    private fun seedReleased108Database() {
        assertTrue(!context.getDatabasePath(DatabaseFileNames.TARGET).exists())
        val fixture = checkNotNull(javaClass.getResource("/startup109/v1.0.8-schema-v59.sql"))
            .readText()
        val statements = fixture.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
        assertEquals(136, statements.size, "The complete released DDL fixture must be retained")
        context.openOrCreateDatabase(DatabaseFileNames.TARGET, Context.MODE_PRIVATE, null).use { database ->
            statements.forEach(database::execSQL)
            database.execSQL("PRAGMA foreign_keys = ON")
            database.execSQL(
                """
                INSERT INTO WorkoutSession(
                    id, timestamp, mode, targetReps, weightPerCableKg, totalReps,
                    workingReps, duration, exerciseName, profile_id, rackItemsJson,
                    local_sync_generation, synced_sync_generation
                ) VALUES ('$SESSION_ID', 1730000000000, 'OldSchool', 8, 20.5, 8,
                    8, 45000, 'Upgrade fixture squat', 'default', '[]', 7, 3)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO MetricSample(
                    id, sessionId, timestamp, position, positionB, velocity,
                    velocityB, load, loadB, power, status
                ) VALUES (41, '$SESSION_ID', 1730000000100, 100.5, 101.5, 250.0,
                    251.0, 20.5, 21.5, 105.0, 2)
                """.trimIndent(),
            )
            database.version = 59
            assertEquals(59, database.version)
            database.rawQuery("PRAGMA table_info(MetricSample)", null).use { cursor ->
                val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertTrue("heartRateBpm" !in columns, "The seed must predate migration 59")
            }
        }
    }

    private fun assertHealthyUpgrade(driver: SqlDriver) {
        assertEquals(60L, driver.longValue("PRAGMA user_version"))
        assertEquals("ok", driver.textValue("PRAGMA quick_check"))
        assertEquals(1L, driver.longValue("PRAGMA foreign_keys"))
        driver.executeQuery(null, "PRAGMA foreign_key_check", { cursor ->
            assertTrue(!cursor.next().value, "Upgrade must not leave dangling foreign keys")
            QueryResult.Value(Unit)
        }, 0)
    }

    private fun assertReleasedDataPreserved(driver: SqlDriver) {
        val queries = PhoenixDatabase(driver).phoenixDatabaseQueries
        val session = queries.selectSessionById(SESSION_ID).executeAsOne()
        assertEquals(1L, driver.longValue("SELECT COUNT(*) FROM WorkoutSession"))
        assertEquals(1_730_000_000_000L, session.timestamp)
        assertEquals("Upgrade fixture squat", session.exerciseName)
        assertEquals(20.5, session.weightPerCableKg)
        assertEquals(8L, session.totalReps)
        assertEquals(7L, session.local_sync_generation)
        assertEquals(3L, session.synced_sync_generation)
        assertNull(session.avgHeartRateBpm)
        assertNull(session.hrr300Bpm)

        val metric = queries.selectMetricById(41).executeAsOne()
        assertEquals(SESSION_ID, metric.sessionId)
        assertEquals(1_730_000_000_100L, metric.timestamp)
        assertEquals(100.5, metric.position)
        assertEquals(21.5, metric.loadB)
        assertEquals(2L, metric.status)
        assertNull(metric.heartRateBpm)
        assertNull(metric.chestAccZMg)
    }

    private fun SqlDriver.longValue(sql: String): Long = executeQuery(null, sql, { cursor ->
        check(cursor.next().value) { "No result for $sql" }
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0).value

    private fun SqlDriver.textValue(sql: String): String = executeQuery(null, sql, { cursor ->
        check(cursor.next().value) { "No result for $sql" }
        QueryResult.Value(checkNotNull(cursor.getString(0)))
    }, 0).value

    /** Keep the production filename and file coordinator, inside a per-test directory. */
    private class IsolatedDatabaseContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getDatabasePath(name: String): File = File(directory, name)

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    private companion object {
        const val SESSION_ID = "upgrade-108-workout"
    }
}

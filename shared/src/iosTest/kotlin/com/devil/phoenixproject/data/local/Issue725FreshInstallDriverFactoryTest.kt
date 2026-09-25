package com.devil.phoenixproject.data.local

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

/**
 * Runtime regression for issue #725: iOS DriverFactory must open a clean
 * filesystem database without throwing DB_TARGET_VALIDATION_FAILED.
 *
 * The production path is the system under test — do not substitute an
 * in-memory NativeSqliteDriver here.
 */
@OptIn(ExperimentalForeignApi::class)
class Issue725FreshInstallDriverFactoryTest {
    @BeforeTest
    fun setUp() {
        deleteAllPhoenixDatabaseArtifacts()
    }

    @AfterTest
    fun tearDown() {
        deleteAllPhoenixDatabaseArtifacts()
    }

    @Test
    fun createDriver_fromCleanFilesystem_enablesWalAndValidatesCurrentSchema() {
        val driver = DriverFactory().createDriver()
        try {
            assertEquals(PhoenixDatabase.Schema.version, driver.queryLong("PRAGMA user_version"))
            assertEquals("wal", driver.queryText("PRAGMA journal_mode").lowercase())
            assertEquals("ok", driver.queryText("PRAGMA quick_check").lowercase())
            assertEquals(1L, driver.writerForeignKeys())
        } finally {
            driver.close()
        }
    }

    @Test
    fun createDriver_secondLaunch_reopensExistingWalDatabase() {
        DriverFactory().createDriver().close()

        val driver = DriverFactory().createDriver()
        try {
            assertEquals(PhoenixDatabase.Schema.version, driver.queryLong("PRAGMA user_version"))
            assertEquals("wal", driver.queryText("PRAGMA journal_mode").lowercase())
            assertEquals("ok", driver.queryText("PRAGMA quick_check").lowercase())
            assertEquals(1L, driver.writerForeignKeys())
        } finally {
            driver.close()
        }
    }

    @Test
    fun reopenedDriver_rejectsMetricSampleWithMissingSession() {
        DriverFactory().createDriver().close()

        val driver = DriverFactory().createDriver()
        try {
            driver.execute(
                null,
                "INSERT INTO WorkoutSession(id, timestamp, mode, targetReps, weightPerCableKg) VALUES ('valid-session', 1, 'OldSchool', 0, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO MetricSample(sessionId, timestamp) VALUES ('valid-session', 2)",
                0,
            )
            val failure = assertFailsWith<Throwable> {
                driver.execute(
                    null,
                    "INSERT INTO MetricSample(sessionId, timestamp) VALUES ('missing-session', 1)",
                    0,
                )
            }
            assertTrue(failure.message?.contains("FOREIGN KEY", ignoreCase = true) == true)
        } finally {
            driver.close()
        }
    }

    private fun deleteAllPhoenixDatabaseArtifacts() {
        val names = listOf(
            DatabaseFileNames.LEGACY,
            DatabaseFileNames.TARGET,
            DatabaseFileNames.STAGING,
            DatabaseFileNames.RECOVERY,
            DatabaseFileNames.LOCK,
        )
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        val fileManager = NSFileManager.defaultManager
        for (name in names) {
            val base = DatabaseFileContext.databasePath(name, null)
            for (suffix in suffixes) {
                val path = "$base$suffix"
                if (fileManager.fileExistsAtPath(path)) {
                    check(fileManager.removeItemAtPath(path, error = null)) {
                        "Could not delete database artifact at $path"
                    }
                }
            }
        }
    }

    // NativeSqliteDriver answers a non-transactional executeQuery from its reader pool,
    // but DriverFactory sets PRAGMA foreign_keys = ON on the writer connection, which is
    // the one every insert, update and delete uses. Read the pragma there.
    private fun SqlDriver.writerForeignKeys(): Long =
        object : TransacterImpl(this) {}.transactionWithResult { queryLong("PRAGMA foreign_keys") }

    private fun SqlDriver.queryLong(sql: String): Long {
        var value: Long? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                check(cursor.next().value) { "Expected one row for $sql" }
                value = cursor.getLong(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return checkNotNull(value) { "$sql returned no value" }
    }

    private fun SqlDriver.queryText(sql: String): String {
        var value: String? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                check(cursor.next().value) { "Expected one row for $sql" }
                value = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return checkNotNull(value) { "$sql returned no value" }
    }
}

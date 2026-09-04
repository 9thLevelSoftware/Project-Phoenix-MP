package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import co.touchlab.sqliter.NO_VERSION_CHECK
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

@OptIn(ExperimentalForeignApi::class)
class Issue725RecurrenceTest {
    private val fileManager = NSFileManager.defaultManager

    @BeforeTest
    fun setUp() {
        deleteAllDatabaseArtifacts()
    }

    @AfterTest
    fun tearDown() {
        deleteAllDatabaseArtifacts()
    }

    @Test
    fun legacyLibraryRootDatabaseIsMovedBeforeDriverOpenAndDataIsPreserved() {
        val legacyDriver = NativeSqliteDriver(
            schema = PhoenixDatabase.Schema,
            name = DatabaseFileNames.LEGACY,
        )
        legacyDriver.execute(
            null,
            "INSERT INTO UserProfile(id, name, colorIndex, createdAt, isActive) VALUES ('legacy-profile', 'Legacy', 0, 1, 1)",
            0,
        )
        legacyDriver.close()

        moveArtifact(
            DatabaseFileContext.databasePath(DatabaseFileNames.LEGACY, null),
            legacyLibraryRootPath(),
        )

        val driver = DriverFactory().createDriver()
        try {
            assertEquals("Legacy", queryScalar(driver, "SELECT name FROM UserProfile WHERE id = 'legacy-profile'"))
        } finally {
            driver.close()
        }

        assertFalse(fileManager.fileExistsAtPath(legacyLibraryRootPath()))
        assertTrue(fileManager.fileExistsAtPath(DatabaseFileContext.databasePath(DatabaseFileNames.TARGET, null)))
    }

    @Test
    fun driverOpenRepairsDuplicatePersonalRecordsBeforeMigrationIndexCreation() {
        val setupDriver = NativeSqliteDriver(
            schema = PhoenixDatabase.Schema,
            name = DatabaseFileNames.TARGET,
        )
        setupDriver.execute(null, "DROP INDEX idx_pr_unique", 0)
        setupDriver.execute(
            null,
            """
            INSERT INTO PersonalRecord(
                id, exerciseId, exerciseName, weight, reps, oneRepMax, achievedAt,
                workoutMode, prType, volume, phase, profile_id
            ) VALUES
                (1, 'bench', 'Bench', 100.0, 5, 110.0, 100,
                 'Old School', 'MAX_WEIGHT', 500.0, 'COMBINED', 'default'),
                (2, 'bench', 'Bench', 105.0, 5, 115.0, 200,
                 'Old School', 'MAX_WEIGHT', 525.0, 'COMBINED', 'default')
            """.trimIndent(),
            0,
        )
        createHistoricalExerciseVideoTable(setupDriver)
        setupDriver.execute(null, "PRAGMA user_version = 18", 0)
        setupDriver.close()

        val driver = DriverFactory().createDriver()
        try {
            assertEquals("1", queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM PersonalRecord"))
            assertEquals("2", queryScalar(driver, "SELECT CAST(id AS TEXT) FROM PersonalRecord WHERE exerciseId = 'bench'"))
            assertTrue(queryScalar(
                driver,
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_pr_unique'",
            ) != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun driverOpenRepairsDuplicateExternalActivitiesBeforeMigrationIndexCreation() {
        val setupDriver = NativeSqliteDriver(
            schema = PhoenixDatabase.Schema,
            name = DatabaseFileNames.TARGET,
        )
        setupDriver.execute(null, "DROP INDEX idx_external_activity_dedup", 0)
        setupDriver.execute(
            null,
            """
            INSERT INTO ExternalActivity(
                id, externalId, provider, name, startedAt, syncedAt, profileId
            ) VALUES
                ('activity-old', 'same', 'provider', 'Old', 100, 200, 'default'),
                ('activity-new', 'same', 'provider', 'New', 100, 300, 'default')
            """.trimIndent(),
            0,
        )
        createHistoricalExerciseVideoTable(setupDriver)
        setupDriver.execute(null, "PRAGMA user_version = 30", 0)
        setupDriver.close()

        val driver = DriverFactory().createDriver()
        try {
            assertEquals("1", queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM ExternalActivity"))
            assertEquals("activity-new", queryScalar(driver, "SELECT id FROM ExternalActivity"))
            assertTrue(queryScalar(
                driver,
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_external_activity_dedup'",
            ) != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migration31FallbackDeduplicatesBeforeRecreatingUniqueIndex() {
        val setupDriver = NativeSqliteDriver(
            schema = PhoenixDatabase.Schema,
            name = DatabaseFileNames.TARGET,
        )
        setupDriver.execute(null, "DROP INDEX idx_external_activity_dedup", 0)
        setupDriver.execute(
            null,
            """
            INSERT INTO ExternalActivity(
                id, externalId, provider, name, startedAt, syncedAt, profileId
            ) VALUES
                ('activity-old', 'same', 'provider', 'Old', 100, 200, 'default'),
                ('activity-new', 'same', 'provider', 'New', 100, 300, 'default')
            """.trimIndent(),
            0,
        )
        setupDriver.execute(null, "PRAGMA user_version = 31", 0)
        setupDriver.close()

        val driver = rawDriver(DatabaseFileNames.TARGET)
        try {
            ResilientMigratingSchema(PhoenixDatabase.Schema).migrate(driver, 31, 32)

            assertEquals(32L, queryLong(driver, "PRAGMA user_version"))
            assertEquals("1", queryScalar(driver, "SELECT CAST(COUNT(*) AS TEXT) FROM ExternalActivity"))
            assertEquals("activity-new", queryScalar(driver, "SELECT id FROM ExternalActivity"))
            assertTrue(queryScalar(
                driver,
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_external_activity_dedup'",
            ) != null)
        } finally {
            driver.close()
        }
    }

    @Test
    fun backupExclusionFailureIsBestEffort() {
        var continued = false

        runBestEffortBackupExclusion("/injected") {
            error("injected backup attribute failure")
        }
        continued = true

        assertTrue(continued)
    }

    private fun createHistoricalExerciseVideoTable(driver: SqlDriver) {
        driver.execute(
            null,
            "DROP TABLE IF EXISTS ExerciseImage",
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS ExerciseVideo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exerciseId TEXT NOT NULL,
                angle TEXT NOT NULL DEFAULT '',
                videoUrl TEXT NOT NULL DEFAULT '',
                thumbnailUrl TEXT NOT NULL DEFAULT '',
                isTutorial INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            0,
        )
    }

    private fun rawDriver(name: String): NativeSqliteDriver = NativeSqliteDriver(
        DatabaseConfiguration(
            name = name,
            version = NO_VERSION_CHECK,
            create = {},
        ),
    )

    private fun queryScalar(driver: SqlDriver, sql: String): String? {
        var value: String? = null
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value
    }

    private fun queryLong(driver: SqlDriver, sql: String): Long? {
        var value: Long? = null
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getLong(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return value
    }

    private fun moveArtifact(from: String, to: String) {
        check(fileManager.moveItemAtPath(from, toPath = to, error = null)) {
            "Could not move test database from $from to $to"
        }
        for (suffix in SIDECAR_SUFFIXES) {
            val source = "$from$suffix"
            val destination = "$to$suffix"
            if (fileManager.fileExistsAtPath(source)) {
                check(fileManager.moveItemAtPath(source, toPath = destination, error = null)) {
                    "Could not move test database sidecar from $source to $destination"
                }
            }
        }
    }

    private fun deleteAllDatabaseArtifacts() {
        val names = listOf(
            DatabaseFileNames.LEGACY,
            DatabaseFileNames.TARGET,
            DatabaseFileNames.STAGING,
            DatabaseFileNames.RECOVERY,
            DatabaseFileNames.LOCK,
        )
        for (name in names) {
            val path = DatabaseFileContext.databasePath(name, null)
            deletePathAndSidecars(path)
        }
        deletePathAndSidecars(legacyLibraryRootPath())
    }

    private fun deletePathAndSidecars(path: String) {
        for (suffix in SIDECAR_SUFFIXES + "") {
            val candidate = "$path$suffix"
            if (fileManager.fileExistsAtPath(candidate)) {
                check(fileManager.removeItemAtPath(candidate, error = null)) {
                    "Could not delete test database artifact at $candidate"
                }
            }
        }
    }

    private companion object {
        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}

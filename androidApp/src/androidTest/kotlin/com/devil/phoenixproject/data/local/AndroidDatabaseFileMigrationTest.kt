package com.devil.phoenixproject.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDatabaseFileMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteAllMigrationArtifacts()
    }

    @After
    fun tearDown() {
        deleteAllMigrationArtifacts()
    }

    @Test
    fun populatedLegacyDatabaseMigratesAndRecoveryIsRemovedOnSecondLaunch() {
        createLegacyDatabase(value = "kept-row").close()

        val firstDriver = DriverFactory(context).createDriver()
        assertEquals("kept-row", firstDriver.queryString("SELECT value FROM MigrationProbe"))
        firstDriver.close()

        assertFalse(databaseFile(LEGACY_DATABASE).exists())
        assertTrue(databaseFile(TARGET_DATABASE).exists())
        assertTrue(databaseFile(RECOVERY_DATABASE).exists())
        assertNoLegacySidecars()

        // Recovery validation may create transient SQLite sidecars. Cleanup owns
        // the entire recovery artifact, not only its main database file.
        databaseFile("$RECOVERY_DATABASE-wal").writeBytes(byteArrayOf())
        databaseFile("$RECOVERY_DATABASE-shm").writeBytes(byteArrayOf())

        val secondDriver = DriverFactory(context).createDriver()
        assertEquals("kept-row", secondDriver.queryString("SELECT value FROM MigrationProbe"))
        secondDriver.close()

        assertNoArtifact(RECOVERY_DATABASE)
        assertNoLegacyArtifacts()
    }

    @Test
    fun committedWalRowsAreCheckpointedBeforeLegacyCutover() {
        val legacy = createLegacyDatabase(value = "main-row")
        legacy.enableWriteAheadLogging()
        legacy.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { cursor ->
            check(cursor.moveToFirst()) { "wal_autocheckpoint pragma returned no row" }
        }
        legacy.execSQL("INSERT INTO MigrationProbe(value) VALUES ('wal-row')")
        assertTrue("test precondition: WAL should exist", databaseFile("$LEGACY_DATABASE-wal").exists())

        val driver = DriverFactory(context).createDriver()
        assertEquals(2L, driver.queryLong("SELECT COUNT(*) FROM MigrationProbe"))
        assertEquals("wal-row", driver.queryString("SELECT value FROM MigrationProbe ORDER BY rowid DESC LIMIT 1"))
        driver.close()
        legacy.close()

        assertNoLegacyArtifacts()
        assertTrue(databaseFile(TARGET_DATABASE).exists())
        assertTrue(databaseFile(RECOVERY_DATABASE).exists())
    }

    @Test
    fun corruptLegacyDatabaseBlocksWithoutCreatingPhoenixTarget() {
        databaseFile(LEGACY_DATABASE).apply {
            parentFile?.mkdirs()
            writeBytes("not a sqlite database".encodeToByteArray())
        }

        val failure = runCatching {
            val driver = DriverFactory(context).createDriver()
            try {
                driver.queryLong("PRAGMA user_version")
            } finally {
                driver.close()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(databaseFile(LEGACY_DATABASE).exists())
        assertFalse(databaseFile(TARGET_DATABASE).exists())
        assertFalse(databaseFile(RECOVERY_DATABASE).exists())
    }

    @Test
    fun interruptedStagingIsDiscardedAndMigrationRestartsFromLegacy() {
        createLegacyDatabase(value = "canonical-row").close()
        databaseFile(STAGING_DATABASE).writeBytes("incomplete".encodeToByteArray())

        val driver = DriverFactory(context).createDriver()
        assertEquals("canonical-row", driver.queryString("SELECT value FROM MigrationProbe"))
        driver.close()

        assertFalse(databaseFile(STAGING_DATABASE).exists())
        assertFalse(databaseFile(LEGACY_DATABASE).exists())
        assertTrue(databaseFile(TARGET_DATABASE).exists())
        assertTrue(databaseFile(RECOVERY_DATABASE).exists())
    }

    @Test
    fun freshInstallCreatesOnlyPhoenixDatabaseArtifacts() {
        val driver = DriverFactory(context).createDriver()
        assertEquals(PhoenixDatabase.Schema.version, driver.queryLong("PRAGMA user_version"))
        driver.close()

        assertTrue(databaseFile(TARGET_DATABASE).exists())
        assertFalse(databaseFile(RECOVERY_DATABASE).exists())
        assertNoLegacyArtifacts()
    }

    private fun createLegacyDatabase(value: String): SQLiteDatabase {
        val file = databaseFile(LEGACY_DATABASE)
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE MigrationProbe(value TEXT NOT NULL)")
            execSQL("INSERT INTO MigrationProbe(value) VALUES (?)", arrayOf(value))
            execSQL("PRAGMA user_version = ${PhoenixDatabase.Schema.version}")
        }
    }

    private fun SqlDriver.queryLong(sql: String): Long {
        var result: Long? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                check(cursor.next().value) { "Expected one row for $sql" }
                result = cursor.getLong(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return checkNotNull(result)
    }

    private fun SqlDriver.queryString(sql: String): String {
        var result: String? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                check(cursor.next().value) { "Expected one row for $sql" }
                result = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return checkNotNull(result)
    }

    private fun assertNoLegacyArtifacts() {
        assertFalse(databaseFile(LEGACY_DATABASE).exists())
        assertNoLegacySidecars()
    }

    private fun assertNoLegacySidecars() {
        LEGACY_SIDECAR_SUFFIXES.forEach { suffix ->
            assertFalse("legacy sidecar remains: $suffix", databaseFile("$LEGACY_DATABASE$suffix").exists())
        }
    }

    private fun assertNoArtifact(name: String) {
        assertFalse(databaseFile(name).exists())
        LEGACY_SIDECAR_SUFFIXES.forEach { suffix ->
            assertFalse("artifact sidecar remains: $name$suffix", databaseFile("$name$suffix").exists())
        }
    }

    private fun deleteAllMigrationArtifacts() {
        listOf(LEGACY_DATABASE, TARGET_DATABASE, RECOVERY_DATABASE, STAGING_DATABASE, LOCK_FILE).forEach { name ->
            databaseFile(name).delete()
        }
        listOf(LEGACY_DATABASE, TARGET_DATABASE, RECOVERY_DATABASE, STAGING_DATABASE).forEach { name ->
            LEGACY_SIDECAR_SUFFIXES.forEach { suffix -> databaseFile("$name$suffix").delete() }
        }
    }

    private fun databaseFile(name: String): File = context.getDatabasePath(name)

    private companion object {
        const val LEGACY_DATABASE = "vitruvian.db"
        const val TARGET_DATABASE = "phoenix.db"
        const val STAGING_DATABASE = "phoenix.db.migrating"
        const val RECOVERY_DATABASE = "phoenix-recovery.db"
        const val LOCK_FILE = "phoenix-db-migration.lock"
        val LEGACY_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}

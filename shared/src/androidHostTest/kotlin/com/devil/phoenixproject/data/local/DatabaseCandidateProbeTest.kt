package com.devil.phoenixproject.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.readProjectFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Issue #764: the DB_DUAL_DATABASES recovery may only set aside a candidate that provably holds no
 * user data. These run the production classifier, probe and quarantine helpers on real SQLite files.
 */
class DatabaseCandidateProbeTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("phoenix-probe-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun freshlySeededDatabaseIsEmpty() {
        val db = freshDatabase("phoenix.db")

        assertEquals(CandidateContent.EMPTY, classify(db))
    }

    @Test
    fun anyUserRowMakesTheCandidateHoldUserData() {
        val cases = mapOf(
            "workout" to "INSERT INTO WorkoutSession(id, timestamp, mode, targetReps, weightPerCableKg) VALUES ('s1', 1, 'OldSchool', 10, 20.0)",
            "second profile" to "INSERT INTO UserProfile(id, name, colorIndex, createdAt, isActive) VALUES ('p2', 'Partner', 1, 1, 0)",
            "renamed default profile" to "UPDATE UserProfile SET name = 'Alex' WHERE id = 'default'",
            "linked portal account" to "UPDATE UserProfile SET supabase_user_id = 'u-1' WHERE id = 'default'",
            "favourite stock exercise" to "UPDATE Exercise SET isFavorite = 1 WHERE id = (SELECT id FROM Exercise LIMIT 1)",
            "performed stock exercise" to "UPDATE Exercise SET timesPerformed = 1 WHERE id = (SELECT id FROM Exercise LIMIT 1)",
            "custom exercise" to "UPDATE Exercise SET isCustom = 1 WHERE id = (SELECT id FROM Exercise LIMIT 1)",
            "gamification progress" to "UPDATE GamificationStats SET totalWorkouts = 1",
            "rpg progress" to "UPDATE RpgAttributes SET strength = 3",
            "other profile preferences" to "INSERT INTO UserProfilePreferences(profile_id) VALUES ('p2')",
            "default body weight" to "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET body_weight_kg = 82.5",
            "default units" to "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET weight_unit = 'KG'",
            "default weight increment" to "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET weight_increment = 2.5",
            "default equipment rack" to
                "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET equipment_rack_json = '{\"version\":1,\"items\":[{\"id\":\"bar\"}]}'",
            "default workout preferences" to
                "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET workout_preferences_json = '{\"version\":1,\"stopAtTop\":true}'",
            "default LED scheme" to "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET led_color_scheme_id = 3",
            "default LED preferences" to
                "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET led_preferences_json = '{\"version\":1,\"x\":1}'",
            "default VBT switch" to "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET vbt_enabled = 0",
            "default VBT preferences" to
                "$DEFAULT_PREFERENCES; UPDATE UserProfilePreferences SET vbt_preferences_json = '{\"version\":1,\"x\":1}'",
            "routine" to "INSERT INTO Routine(id, name, createdAt) VALUES ('r1', 'Push', 1)",
            "unknown future table" to "CREATE TABLE FutureThing(id TEXT); INSERT INTO FutureThing VALUES ('x')",
        )
        cases.forEach { (label, sql) ->
            val db = freshDatabase("case.db")
            JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
                sql.split(";").map(String::trim).filter(String::isNotEmpty).forEach { driver.execute(null, it, 0) }
            }

            assertEquals(CandidateContent.HAS_USER_DATA, classify(db), label)
            deleteWithSidecars(db)
        }
    }

    @Test
    fun aDefaultPreferencesRowWhoseValuesAllEqualTheSeedIsNotUserData() {
        val db = freshDatabase("phoenix.db")
        JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
            driver.execute(null, DEFAULT_PREFERENCES, 0)
            // Sync metadata only: a write that stored the seed values again, then a push.
            driver.execute(
                null,
                "UPDATE UserProfilePreferences SET core_local_generation = 2, core_updated_at = 5, core_dirty = 0, " +
                    "workout_local_generation = 1, workout_server_revision = 4",
                0,
            )
        }

        assertEquals(CandidateContent.EMPTY, classify(db))
    }

    @Test
    fun catalogueMediaRepairLedgerAndDeviceLogsAreNotUserData() {
        val db = freshDatabase("phoenix.db")
        JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
            driver.execute(null, "INSERT INTO AppliedDataRepair(repair_key, applied_at) VALUES ('k', 1)", 0)
            driver.execute(
                null,
                "INSERT INTO ConnectionLog(timestamp, eventType, level, message) VALUES (1, 'CONNECT', 'INFO', 'hi')",
                0,
            )
        }

        assertEquals(CandidateContent.EMPTY, classify(db))
    }

    @Test
    fun anOlderSchemaWithoutNewerTablesOrColumnsIsClassifiedWithoutError() {
        val db = File(dir, "vitruvian.db")
        JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
            driver.execute(null, "CREATE TABLE Exercise(id TEXT PRIMARY KEY, name TEXT, isFavorite INTEGER NOT NULL DEFAULT 0)", 0)
            driver.execute(null, "CREATE TABLE WorkoutSession(id TEXT PRIMARY KEY, timestamp INTEGER)", 0)
            driver.execute(null, "INSERT INTO Exercise(id, name) VALUES ('old-1', 'Bench')", 0)
        }
        assertEquals(CandidateContent.EMPTY, classify(db))

        JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
            driver.execute(null, "INSERT INTO WorkoutSession(id, timestamp) VALUES ('s', 1)", 0)
        }
        assertEquals(CandidateContent.HAS_USER_DATA, classify(db))
    }

    @Test
    fun aFileThatIsNotADatabaseIsUninspectable() {
        val db = File(dir, "vitruvian.db").apply { writeBytes(ByteArray(4096) { 7 }) }

        assertEquals(CandidateContent.UNINSPECTABLE, classify(db))
    }

    @Test
    fun rowsCommittedOnlyToTheWalAreFoundBecauseTheWalIsProbedToo() {
        val db = freshDatabase("phoenix.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.path}").use { writer ->
            writer.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA wal_autocheckpoint = 0")
                statement.executeUpdate(
                    "INSERT INTO WorkoutSession(id, timestamp, mode, targetReps, weightPerCableKg) " +
                        "VALUES ('wal-only', 1, 'OldSchool', 10, 20.0)",
                )
            }
            assertTrue(File("${db.path}-wal").length() > 0, "the row must still be only in the WAL")

            // With the connection still open the row lives only in -wal; a probe that copied the
            // main file alone would call this candidate empty.
            val withWal = probeDatabaseCopy(db, dir, ::classifyScratch)
            val mainOnly = File(dir, "main-only.db").also { Files.copy(db.toPath(), it.toPath()) }
            val mainOnlyResult = probeDatabaseCopy(mainOnly, dir, ::classifyScratch)

            assertEquals(CandidateContent.HAS_USER_DATA, withWal)
            assertEquals(CandidateContent.EMPTY, mainOnlyResult)
        }
    }

    @Test
    fun probingLeavesTheOriginalByteIdenticalAndRemovesItsScratchCopy() {
        val db = freshDatabase("vitruvian.db")
        val wal = File("${db.path}-wal").apply { writeBytes(ByteArray(0)) }
        val before = db.readBytes()

        probeDatabaseCopy(db, dir, ::classifyScratch)

        assertContentEquals(before, db.readBytes())
        assertTrue(wal.exists())
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(DATABASE_PROBE_SCRATCH_PREFIX) })
    }

    @Test
    fun aCandidateLargerThanAFreshInstallIsNeverCopiedAndCountsAsData() {
        val db = File(dir, "phoenix.db")
        java.io.RandomAccessFile(db, "rw").use { it.setLength(DATABASE_PROBE_SIZE_LIMIT_BYTES + 1) }
        var inspected = false

        val result = probeDatabaseCopy(db, dir) { inspected = true; CandidateContent.EMPTY }

        assertEquals(CandidateContent.HAS_USER_DATA, result)
        assertFalse(inspected)
    }

    @Test
    fun quarantineMovesSidecarsAndMainIntoANewFolderWithoutDeletingAnything() {
        val db = File(dir, "vitruvian.db").apply { writeText("main") }
        File("${db.path}-wal").writeText("wal")
        File("${db.path}-shm").writeText("shm")

        val folder = quarantineDatabaseFiles(db, dir, DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET, 1_000L)

        assertEquals(File(dir, "$DATABASE_QUARANTINE_DIRECTORY/1000-CANONICAL_LEGACY_TARGET-vitruvian.db"), folder)
        assertFalse(db.exists())
        assertFalse(File("${db.path}-wal").exists())
        assertEquals("main", File(folder, "vitruvian.db").readText())
        assertEquals("wal", File(folder, "vitruvian.db-wal").readText())
        assertEquals("shm", File(folder, "vitruvian.db-shm").readText())
    }

    @Test
    fun quarantineNeverOverwritesAnEarlierFolderAndFinishesAPartialMove() {
        val db = File(dir, "phoenix.db").apply { writeText("first") }
        val first = quarantineDatabaseFiles(db, dir, DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET, 5L)

        // An interrupted earlier move left only the main file behind; its sidecar was already moved.
        db.writeText("second")
        val second = quarantineDatabaseFiles(db, dir, DatabaseDiagnosticReason.CANONICAL_LEGACY_TARGET, 5L)

        assertEquals("first", File(first, "phoenix.db").readText())
        assertEquals("second", File(second, "phoenix.db").readText())
        assertTrue(second.name.endsWith("-1"))
        assertFalse(db.exists())
    }

    @Test
    fun leftoverProbeScratchIsDiscardedAndNothingElse() {
        val scratch = File(dir, "${DATABASE_PROBE_SCRATCH_PREFIX}phoenix.db").apply { writeText("x") }
        val keep = File(dir, "phoenix.db").apply { writeText("keep") }

        deleteProbeScratch(dir)

        assertFalse(scratch.exists())
        assertTrue(keep.exists())
    }

    private fun classify(db: File): CandidateContent = probeDatabaseCopy(db, dir, ::classifyScratch)

    private fun classifyScratch(scratch: File): CandidateContent =
        JdbcSqliteDriver("jdbc:sqlite:${scratch.path}").use { driver ->
            DatabaseUserDataClassifier.classify(SqlDriverProbeQueries(driver))
        }

    /** A database as a first launch leaves it: current schema, catalogue, default profile, zero stats. */
    private fun freshDatabase(name: String): File {
        val db = File(dir, name)
        JdbcSqliteDriver("jdbc:sqlite:${db.path}").use { driver ->
            PhoenixDatabase.Schema.create(driver)
            val database = PhoenixDatabase(driver)
            val catalogue = requireNotNull(readProjectFile("src/commonMain/composeResources/files/exercises.json"))
            runBlocking { ExerciseImporter(database).importFromFreeExerciseJson(catalogue).getOrThrow() }
            database.phoenixDatabaseQueries.insertProfile("default", "Default", 0L, 1L, 1L)
            driver.execute(null, "INSERT INTO UserProfilePreferences(profile_id) VALUES ('default')", 0)
            driver.execute(null, "INSERT INTO GamificationStats(id, lastUpdated, profile_id) VALUES (1, 1, 'default')", 0)
            driver.execute(null, "INSERT INTO RpgAttributes(id, profile_id) VALUES (1, 'default')", 0)
        }
        return db
    }

    private fun deleteWithSidecars(db: File) {
        db.delete()
        listOf("-wal", "-shm", "-journal").forEach { File("${db.path}$it").delete() }
    }

    private companion object {
        /** The default profile's seeded preferences row, in case the schema seed did not create it. */
        const val DEFAULT_PREFERENCES = "INSERT OR IGNORE INTO UserProfilePreferences(profile_id) VALUES ('default')"
    }
}

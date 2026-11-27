package com.devil.phoenixproject.data.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryActivityTracker
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.util.BackupData
import com.devil.phoenixproject.util.BaseDataBackupManager
import com.devil.phoenixproject.util.BackupJsonWriter
import com.devil.phoenixproject.util.ImportResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T3.1–T3.2 (issue #1425): guarded VACUUM on a file-backed driver (freelist reclaimed, logical
 * and file size shrink, typed refusals) and the JSON backup size contract (raw-on export loses
 * exactly the trimmed rows within the estimator tolerance; raw-off export is byte-identical
 * modulo `exportedAt`). T3.3 (iOS WAL with a concurrent reader) runs on iosSimulatorArm64Test.
 */
class TelemetryCompactionMaintenanceTest {

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ===== T3.1: VACUUM =====

    @Test
    fun `vacuum reclaims the freelist and shrinks logical and file size`() {
        val dir = createTempDir("telemetry-vacuum")
        val dbFile = File(dir, "telemetry.db")
        // File-backed JDBC drivers open per-statement connections, so the shared test helper's
        // foreign-keys read-back assertion does not apply; create the schema directly.
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}").also { PhoenixDatabase.Schema.create(it) }
        val fixture = Fixture(driver)

        val maintenance = fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 })
        val before = maintenance.measure()
        val fileBytesBefore = dbFile.length()
        assertTrue(before.logicalBytes > 0)

        val result = runBlocking { fixture.service(maintenance).compact(vacuum = true) }

        val ran = assertIs<VacuumOutcome.Ran>(result.vacuum)
        assertTrue(ran.bytesAfter <= ran.bytesBefore, "VACUUM never grows the logical size")
        val after = maintenance.measure()
        assertEquals(0L, after.freelistCount, "a successful VACUUM empties the freelist")
        assertTrue(after.logicalBytes < before.logicalBytes, "logical size shrinks after trimming")
        assertTrue(dbFile.length() < fileBytesBefore, "the on-disk file shrinks after VACUUM")
        assertEquals(ran.bytesBefore, result.databaseBytesBefore)
        assertEquals(ran.bytesAfter, result.databaseBytesAfter)
        driver.close()
    }

    @Test
    fun `vacuum is typed-skipped for active workouts small freelists and tight volumes`() {
        val dir = createTempDir("telemetry-vacuum-precheck")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, "telemetry.db").absolutePath}")
            .also { PhoenixDatabase.Schema.create(it) }
        val fixture = Fixture(driver)

        // A fresh, quiet DB has a freelist below 1 MiB.
        val fresh = fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 })
        assertEquals(
            VacuumPrecheck.Blocked(VacuumSkipReason.INSUFFICIENT_FREELIST),
            fresh.canVacuum(),
        )

        // G0: the session guard alone refuses VACUUM.
        val guarded = fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 }, guard = { true })
        assertEquals(VacuumPrecheck.Blocked(VacuumSkipReason.ACTIVE_WORKOUT), guarded.canVacuum())

        // After deletes the freelist is large; a tight volume refuses the copy.
        runBlocking { fixture.service(fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 })).compact(vacuum = false) }
        val tight = fixture.maintenance(volumeBytes = { 1_024L })
        assertIs<VacuumPrecheck.Blocked>(tight.canVacuum()).let {
            assertEquals(VacuumSkipReason.INSUFFICIENT_FREE_VOLUME, it.reason)
        }

        // And a missing volume figure (tvOS) is a typed skip, never an error.
        val unsupported = fixture.maintenance(volumeBytes = { null })
        assertEquals(VacuumPrecheck.Blocked(VacuumSkipReason.UNSUPPORTED_PLATFORM), unsupported.canVacuum())
        driver.close()
    }

    // ===== T3.2: backup size =====

    @Test
    fun `raw on export shrinks by exactly the trimmed rows within the estimate tolerance`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val fixture = Fixture(driver, idleRows = 500)
        val manager = TestBackupManager(PhoenixDatabase(driver), includeRawTelemetry = true)
        val estimator = TelemetryBackupBytesEstimator(manager)
        val service = fixture.service(fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 }), manager)

        val exportBefore = runBlocking { manager.exportToJson() }
        val samplesBefore = testJson.decodeFromString<BackupData>(exportBefore).data.metricSamples.size

        val report = runBlocking { service.analyze() }
        val estimate = estimator.estimateBackupBytesReclaimable(
            sampleRows = fixture.rawRows(),
            rowsReclaimable = report.rowsReclaimable,
        )?.toDouble() ?: error("raw telemetry is on, so a backup byte estimate must exist")
        val result = runBlocking { service.compact(vacuum = false) }
        val exportAfter = runBlocking { manager.exportToJson() }
        val samplesAfter = testJson.decodeFromString<BackupData>(exportAfter).data.metricSamples.size

        assertEquals(samplesBefore - result.rowsDeleted, samplesAfter, "exactly rowsDeleted entries disappear")
        val byteDelta = (exportBefore.length - exportAfter.length).toDouble()
        val tolerance = estimate * 0.15
        assertTrue(
            kotlin.math.abs(byteDelta - estimate) <= tolerance,
            "byte delta $byteDelta must be within 15% of the estimate $estimate",
        )
    }

    @Test
    fun `raw off export is byte identical apart from the export timestamp`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(::createTestSchema)
        val fixture = Fixture(driver, idleRows = 500)
        val manager = TestBackupManager(PhoenixDatabase(driver), includeRawTelemetry = false)
        val service = fixture.service(fixture.maintenance(volumeBytes = { 64L * 1024 * 1024 * 1024 }), manager)

        val before = runBlocking { manager.exportToJson() }
        runBlocking { service.compact(vacuum = false) }
        val after = runBlocking { manager.exportToJson() }

        assertEquals(withoutExportedAt(before), withoutExportedAt(after))
        assertEquals(
            emptyList(),
            testJson.decodeFromString<BackupData>(after).data.metricSamples,
            "raw telemetry off means the export never carries MetricSample rows",
        )
    }

    private fun withoutExportedAt(json: String): String =
        json.replace(Regex("\"exportedAt\":\"[^\"]*\""), "\"exportedAt\":\"\"")

    // ===== Fixture =====

    private class Fixture(
        private val driver: JdbcSqliteDriver,
        idleRows: Int = 25_000,
    ) {
        private val database = PhoenixDatabase(driver)

        init {
            val queries = database.phoenixDatabaseQueries
            queries.insertUserProfileIgnore("default", "Default", 0L, 1L, 1L)
            queries.insertDefaultProfilePreferences("default", 1L)
            queries.insertAppliedDataRepair(TelemetryCompactionService.EPOCH_ZERO_REPAIR_KEY, 1L)
            driver.execute(null, "INSERT INTO Exercise (id, name, muscleGroup, muscleGroups, equipment, defaultCableConfig, isBodyweight) VALUES ('ex', 'Ex', 'Chest', 'Chest', 'HANDLES', 'DOUBLE', 0)", 0)
            driver.execute(
                null,
                "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg, exerciseId, profile_id) VALUES ('s1', 1700000000000, 'OldSchool', 10, 20.0, 'ex', 'default')",
                0,
            )
            database.transaction {
                // 50 Hz idle lead-in then a confirmed stroke; the policy trims the idle prefix
                // down to one second of context.
                for (index in 0 until idleRows) {
                    driver.execute(
                        null,
                        "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status, metric_unit_version) " +
                            "VALUES (${index + 1L}, 's1', ${1700000000000L + index * 20L}, 100.0, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, 0, 2)",
                        0,
                    )
                }
                val movement = listOf(101f, 103f, 105.5f, 108f, 111f, 114f, 117f, 120f, 123f, 126f, 129f, 132f, 135f)
                movement.forEachIndexed { index, position ->
                    driver.execute(
                        null,
                        "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status, metric_unit_version) " +
                            "VALUES (${idleRows + index + 1L}, 's1', ${1700000000000L + (idleRows + index) * 20L}, $position, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, 0, 2)",
                        0,
                    )
                }
            }
            driver.execute(
                null,
                "INSERT INTO RepMetric (sessionId, repNumber, startTimestamp, endTimestamp, durationMs, concentricDurationMs, " +
                    "concentricPositions, concentricLoadsA, concentricLoadsB, concentricVelocities, concentricTimestamps, " +
                    "eccentricDurationMs, eccentricPositions, eccentricLoadsA, eccentricLoadsB, eccentricVelocities, eccentricTimestamps, " +
                    "peakForceA, peakForceB, avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB, " +
                    "peakVelocity, avgVelocityConcentric, avgVelocityEccentric, rangeOfMotionMm, peakPowerWatts, avgPowerWatts) " +
                    "VALUES ('s1', 1, ${1700000000000L + idleRows * 20L}, ${1700000002000L + idleRows * 20L}, 2000, 1000, '[]', '[]', '[]', '[]', '[]', " +
                    "1000, '[]', '[]', '[]', '[]', '[]', 100.0, 100.0, 90.0, 90.0, 80.0, 80.0, 1.0, 0.8, 0.6, 400.0, 200.0, 150.0)",
                0,
            )
        }

        fun rawRows(): List<com.devil.phoenixproject.database.MetricSample> =
            database.phoenixDatabaseQueries.selectArchivalMetricsBySession("s1").executeAsList().take(200)

        fun maintenance(
            volumeBytes: () -> Long? = { 64L * 1024 * 1024 * 1024 },
            guard: () -> Boolean = { false },
        ): DatabaseMaintenance = DatabaseMaintenance(
            driver = driver,
            availableVolumeBytes = volumeBytes,
            workoutSessionGuard = guard,
        )

        fun service(
            maintenance: DatabaseMaintenance,
            backupManager: com.devil.phoenixproject.util.DataBackupManager = TestBackupManager(database, includeRawTelemetry = true),
        ): TelemetryCompactionService = TelemetryCompactionService(
            database = database,
            preferencesManager = FakePreferencesManager(),
            barrier = ProfileMutationBarrier(),
            activityTracker = ProfileRecoveryActivityTracker(),
            workoutSessionGuard = { false },
            databaseMaintenance = maintenance,
            backupBytesEstimator = TelemetryBackupBytesEstimator(backupManager),
        )
    }

    /** Minimal BaseDataBackupManager for exportToJson-based byte assertions. */
    private class TestBackupManager(
        database: PhoenixDatabase,
        private val includeRawTelemetry: Boolean,
    ) : BaseDataBackupManager(
        database,
        com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository(database),
        com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository(database),
            profileLocalSafetyStore = com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore(
                com.russhwolf.settings.MapSettings(),
            ),
            gamificationRepository = com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository(database),
        ),
        null,
        FakePreferencesManager(),
        null,
    ) {
        override val includeRawTelemetryInBackups: Boolean get() = includeRawTelemetry

        override fun createBackupWriter(): BackupJsonWriter =
            BackupJsonWriter(
                File(createTempDir("telemetry-backup-export"), "export.json").absolutePath,
            )

        override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)
        override suspend fun importFromFile(filePath: String): Result<ImportResult> = error("unused")
        override suspend fun shareBackup() = Unit
        override fun getSessionBackupDirectory(): String = ""
        override fun listBackupFileSizes(): List<Long> = emptyList()
        override fun openBackupFolder() = Unit
        override fun pruneOldBackups(keepCount: Int) = Unit
    }

    private companion object {
        fun createTempDir(prefix: String): File =
            File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
    }
}

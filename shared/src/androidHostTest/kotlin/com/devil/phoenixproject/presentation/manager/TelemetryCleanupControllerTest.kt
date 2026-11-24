package com.devil.phoenixproject.presentation.manager

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.maintenance.DatabaseMaintenance
import com.devil.phoenixproject.data.maintenance.TelemetryBackupBytesEstimator
import com.devil.phoenixproject.data.maintenance.TelemetryCompactionService
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryActivityTracker
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T4.1 (issue #1425): the cleanup controller state machine — Checking → Ready → Cleaning →
 * Done, Blocked on an active workout or a running backup, the pre-backup path never stopping
 * the export, and (signoff #11) BOTH backup dialog actions running pre-backup cleanup.
 */
class TelemetryCleanupControllerTest {

    @Test
    fun `controller walks checking ready cleaning and done`() {
        val fixture = Fixture()
        val controller = fixture.controller

        runBlocking {
            controller.open()
            withTimeout(10_000) { controller.state.first { it is TelemetryCleanupUiState.Checking } }
            val ready = withTimeout(10_000) { controller.state.first { it is TelemetryCleanupUiState.Ready } }
            assertIs<TelemetryCleanupUiState.Ready>(ready)

            controller.cleanUp()
            val done = withTimeout(10_000) { controller.state.first { it is TelemetryCleanupUiState.Done } }
            assertIs<TelemetryCleanupUiState.Done>(done)
            assertEquals(4, done.result.rowsDeleted, "the seeded idle prefix is trimmed")
        }
    }

    @Test
    fun `blocked on active workout and on running backup`() {
        val workoutGuarded = Fixture(guard = { true })
        runBlocking {
            workoutGuarded.controller.open()
            val blocked = withTimeout(10_000) { workoutGuarded.controller.state.first { it is TelemetryCleanupUiState.Blocked } }
            assertEquals(
                TelemetryCleanupUiState.Blocked(BlockedReason.ACTIVE_WORKOUT),
                blocked,
            )
        }

        val backupRunning = Fixture(backupRunning = { true })
        runBlocking {
            backupRunning.controller.open()
            val blocked = withTimeout(10_000) { backupRunning.controller.state.first { it is TelemetryCleanupUiState.Blocked } }
            assertEquals(
                TelemetryCleanupUiState.Blocked(BlockedReason.BACKUP_RUNNING),
                blocked,
            )
        }
    }

    @Test
    fun `pre backup cleanup never stops the export`() {
        // The delete fails mid-pass (driver throws on DELETE); the export path must still return.
        val fixture = Fixture(failDeletes = true)
        runBlocking {
            fixture.controller.runBeforeManualBackup()
        }
    }

    @Test
    fun `both save and share run pre backup cleanup in the settings dialog`() {
        val root = run {
            var dir = File(System.getProperty("user.dir") ?: ".")
            while (!File(dir, "shared/src/phoneMain").exists()) {
                dir = dir.parentFile ?: break
            }
            dir
        }
        val settingsTab = File(root, "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt").readText()
        val occurrences = Regex("""cleanupController\.runBeforeManualBackup\(\)""").findAll(settingsTab).count()
        assertEquals(2, occurrences, "pre-backup cleanup must run before BOTH Save and Share (signoff #5 / T4.1)")
    }

    // ===== Fixture =====

    private class Fixture(
        guard: () -> Boolean = { false },
        backupRunning: () -> Boolean = { false },
        failDeletes: Boolean = false,
    ) {
        private val driver: SqlDriver = FailingDeleteDriver(
            delegate = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
            failDeletes = failDeletes,
        ).also(::createTestSchema)
        private val database = PhoenixDatabase(driver)
        private val prefs = FakePreferencesManager()
        private val manager = com.devil.phoenixproject.testutil.FakeDataBackupManager()
        private val service = TelemetryCompactionService(
            database = database,
            preferencesManager = prefs,
            barrier = ProfileMutationBarrier(),
            activityTracker = ProfileRecoveryActivityTracker(),
            workoutSessionGuard = guard,
            databaseMaintenance = DatabaseMaintenance(
                driver = driver,
                availableVolumeBytes = { 64L * 1024L * 1024L * 1024L },
                workoutSessionGuard = guard,
            ),
            backupBytesEstimator = TelemetryBackupBytesEstimator(manager),
        )

        val controller = TelemetryCleanupController(
            service = service,
            workoutSessionGuard = guard,
            isBackupRunning = backupRunning,
            scope = CoroutineScope(Dispatchers.Default),
        )

        init {
            val queries = database.phoenixDatabaseQueries
            queries.insertAppliedDataRepair(TelemetryCompactionService.EPOCH_ZERO_REPAIR_KEY, 1L)
            driver.execute(null, "INSERT INTO Exercise (id, name, muscleGroup, muscleGroups, equipment, defaultCableConfig, isBodyweight) VALUES ('ex', 'Ex', 'Chest', 'Chest', 'HANDLES', 'DOUBLE', 0)", 0)
            driver.execute(
                null,
                "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg, exerciseId) VALUES ('s1', 1700000000000, 'OldSchool', 10, 20.0, 'ex')",
                0,
            )
            val positions = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 5.5f, 8f, 11f)
            positions.forEachIndexed { index, offset ->
                driver.execute(
                    null,
                    "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status, metric_unit_version) " +
                        "VALUES (${index + 1L}, 's1', ${1700000000000L + index * 250L}, ${100f + offset}, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, 0, 2)",
                    0,
                )
            }
            driver.execute(
                null,
                "INSERT INTO RepMetric (sessionId, repNumber, startTimestamp, endTimestamp, durationMs, concentricDurationMs, " +
                    "concentricPositions, concentricLoadsA, concentricLoadsB, concentricVelocities, concentricTimestamps, " +
                    "eccentricDurationMs, eccentricPositions, eccentricLoadsA, eccentricLoadsB, eccentricVelocities, eccentricTimestamps, " +
                    "peakForceA, peakForceB, avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB, " +
                    "peakVelocity, avgVelocityConcentric, avgVelocityEccentric, rangeOfMotionMm, peakPowerWatts, avgPowerWatts) " +
                    "VALUES ('s1', 1, 1700000010000, 1700000012000, 2000, 1000, '[]', '[]', '[]', '[]', '[]', " +
                    "1000, '[]', '[]', '[]', '[]', '[]', 100.0, 100.0, 90.0, 90.0, 80.0, 80.0, 1.0, 0.8, 0.6, 400.0, 200.0, 150.0)",
                0,
            )
        }
    }

    /** Fails every DELETE so the pre-backup path must prove it swallows cleanup failures. */
    private class FailingDeleteDriver(
        private val delegate: SqlDriver,
        private val failDeletes: Boolean,
    ) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (failDeletes && sql.trimStart().startsWith("DELETE")) {
                throw IllegalStateException("injected delete failure")
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

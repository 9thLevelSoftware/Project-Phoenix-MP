package com.devil.phoenixproject.data.maintenance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryActivityTracker
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.FakePreferencesManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T3.3 (issue #1425) on the iOS simulator with a REAL `NativeSqliteDriver` WAL database:
 * a guarded VACUUM with a concurrent open reader returns `Ran` (busy allowed) or a typed
 * `Skipped`, never throws out of the service, and the database passes `PRAGMA quick_check`.
 */
class TelemetryCompactionIosVacuumTest {

    @Test
    fun vacuumOnWALWithConcurrentReaderIsTypedAndQuickCheckStaysClean() {
        val name = "telemetry-compaction-vacuum-${Random.nextLong(0, Long.MAX_VALUE)}.db"
        val writer = openDriver(name)
        val reader = openDriver(name)
        val database = PhoenixDatabase(writer)
        val maintenance = DatabaseMaintenance(
            driver = writer,
            availableVolumeBytes = { 64L * 1024 * 1024 * 1024 },
            workoutSessionGuard = { false },
        )

        seedEligibleSet(database, writer)

        // Concurrent open reader: hold a read transaction on the second connection.
        writer.execute(null, "PRAGMA journal_mode = WAL", 0)
        reader.execute(null, "BEGIN", 0)
        reader.execute(null, "SELECT COUNT(*) FROM MetricSample", 0)

        // Direct guarded VACUUM: Ran (busy allowed) or typed Skipped, never an exception.
        val outcome = maintenance.vacuum()
        assertTrue(
            outcome is VacuumOutcome.Ran || outcome is VacuumOutcome.Skipped,
            "vacuum() must return a typed outcome with a concurrent reader, was $outcome",
        )

        reader.execute(null, "COMMIT", 0)

        // Full service pass over the seeded set: deletes + post-delete VACUUM on WAL.
        val service = TelemetryCompactionService(
            database = database,
            preferencesManager = FakePreferencesManager(),
            barrier = ProfileMutationBarrier(),
            activityTracker = ProfileRecoveryActivityTracker(),
            workoutSessionGuard = { false },
            databaseMaintenance = maintenance,
            backupBytesEstimator = TelemetryBackupBytesEstimator(com.devil.phoenixproject.testutil.FakeDataBackupManager()),
        )
        val result = service.compactBlocking(vacuum = true)
        assertTrue(
            result.vacuum is VacuumOutcome.Ran || result.vacuum is VacuumOutcome.Skipped,
            "the service must never throw through VACUUM, was ${result.vacuum}",
        )
        assertTrue(result.rowsDeleted > 0, "the seeded idle prefix is trimmed on iOS too")

        // The database must stay structurally clean after VACUUM under WAL.
        val quickCheck = writer.executeQuery(
            null,
            "PRAGMA quick_check",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value
        assertEquals("ok", quickCheck)

        writer.close()
        reader.close()
    }

    private fun TelemetryCompactionService.compactBlocking(vacuum: Boolean): CompactionResult {
        var result: CompactionResult? = null
        kotlinx.coroutines.runBlocking {
            result = compact(vacuum = vacuum)
        }
        return result ?: error("compact() returned no result")
    }

    private fun openDriver(name: String): NativeSqliteDriver = NativeSqliteDriver(
        schema = PhoenixDatabase.Schema,
        name = name,
        onConfiguration = { config ->
            config.copy(
                extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = false),
            )
        },
    )

    private fun seedEligibleSet(database: PhoenixDatabase, driver: NativeSqliteDriver) {
        val queries = database.phoenixDatabaseQueries
        queries.insertAppliedDataRepair(TelemetryCompactionService.EPOCH_ZERO_REPAIR_KEY, 1L)
        driver.execute(
            null,
            "INSERT INTO Exercise (id, name, muscleGroup, muscleGroups, equipment, defaultCableConfig, isBodyweight) " +
                "VALUES ('ex', 'Ex', 'Chest', 'Chest', 'HANDLES', 'DOUBLE', 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg, exerciseId) " +
                "VALUES ('s1', 1760000000000, 'OldSchool', 10, 20.0, 'ex')",
            0,
        )
        database.transaction {
            for (index in 0 until SEED_IDLE_ROWS) {
                driver.execute(
                    null,
                    "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, " +
                        "load, loadB, power, status, metric_unit_version) " +
                        "VALUES (${index + 1L}, 's1', ${1760000000000L + index * 20L}, 100.0, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, 0, 2)",
                    0,
                )
            }
            val movement = listOf(101f, 103f, 105.5f, 108f, 111f, 114f, 117f, 120f, 123f, 126f, 129f, 132f, 135f)
            movement.forEachIndexed { index, position ->
                driver.execute(
                    null,
                    "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, " +
                        "load, loadB, power, status, metric_unit_version) " +
                        "VALUES (${SEED_IDLE_ROWS + index + 1L}, 's1', ${1760000000000L + (SEED_IDLE_ROWS + index) * 20L}, " +
                        "$position, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, 0, 2)",
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
                "VALUES ('s1', 1, ${1760000000000L + SEED_IDLE_ROWS * 20L}, ${1760000002000L + SEED_IDLE_ROWS * 20L}, 2000, 1000, " +
                "'[]', '[]', '[]', '[]', '[]', 1000, '[]', '[]', '[]', '[]', '[]', 100.0, 100.0, 90.0, 90.0, 80.0, 80.0, " +
                "1.0, 0.8, 0.6, 400.0, 200.0, 150.0)",
            0,
        )
    }

    private companion object {
        const val SEED_IDLE_ROWS = 30_000
    }
}

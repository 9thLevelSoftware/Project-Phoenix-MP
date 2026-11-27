package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import com.devil.phoenixproject.data.repository.SqlDelightRepMetricRepository
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PAIRED_CABLE_POWER_METHOD
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.createTestDriver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RepMetricPowerProvenanceTest {
    @Test
    fun `upgrade keeps old millimetre power unknown and new paired power survives repository reload`() = runTest {
        val driver = createTestDriver()
        try {
            // Reproduce the table immediately before migration 70, including an existing v2 row.
            val currentDdl = driver.executeQuery(
                null,
                "SELECT sql FROM sqlite_master WHERE name = 'RepMetric'",
                { cursor ->
                    cursor.next()
                    QueryResult.Value(requireNotNull(cursor.getString(0)))
                },
                0,
            ).value
            driver.execute(null, "DROP TABLE RepMetric", 0)
            driver.execute(null, currentDdl.replace("power_method TEXT,", ""), 0)
            driver.execute(
                null,
                "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg) " +
                    "VALUES ('power-session', 1, 'OldSchool', 2, 25)",
                0,
            )
            driver.execute(
                null,
                """INSERT INTO RepMetric (
                    sessionId, repNumber, startTimestamp, endTimestamp, durationMs,
                    concentricDurationMs, concentricPositions, concentricLoadsA, concentricLoadsB,
                    concentricVelocities, concentricTimestamps, eccentricDurationMs,
                    eccentricPositions, eccentricLoadsA, eccentricLoadsB, eccentricVelocities,
                    eccentricTimestamps, peakForceA, peakForceB, avgForceConcentricA,
                    avgForceConcentricB, avgForceEccentricA, avgForceEccentricB, peakVelocity,
                    avgVelocityConcentric, avgVelocityEccentric, rangeOfMotionMm,
                    peakPowerWatts, avgPowerWatts, metric_unit_version
                ) VALUES (
                    'power-session', 1, 1, 2, 1, 1, '[100]', '[40]', '[10]', '[600]', '[0]',
                    1, '[100]', '[40]', '[10]', '[-200]', '[1]',
                    40, 10, 40, 10, 40, 10, 600, 500, 200, 350, 375, 250, 2
                )""".trimIndent(),
                0,
            )

            PhoenixDatabase.Schema.migrate(driver, 70, 71)
            val repository = SqlDelightRepMetricRepository(PhoenixDatabase(driver))
            val legacy = repository.getRepMetrics("power-session").single()
            assertEquals(2, legacy.metricUnitVersion)
            assertNull(legacy.powerMethod)
            val paired = legacy.copy(repNumber = 2, powerMethod = PAIRED_CABLE_POWER_METHOD)
            repository.saveRepMetrics("power-session", listOf(paired))

            // The resilient fallback can replay the additive migration without changing provenance.
            assertTrue(applyMigrationResilient(driver, 70).all { it.success || it.recoverable })
            val reloaded = SqlDelightRepMetricRepository(PhoenixDatabase(driver))
            assertEquals(listOf(null, PAIRED_CABLE_POWER_METHOD), reloaded.getRepMetrics("power-session").map { it.powerMethod })
            val summaries = reloaded.getRepMetricSummaries("power-session")
            assertEquals(listOf(null, PAIRED_CABLE_POWER_METHOD), summaries.map { it.powerMethod })
            val wire = PortalSyncAdapter.toPortalWorkoutSessions(
                listOf(
                    PortalSyncAdapter.SessionWithReps(
                        session = WorkoutSession(
                            id = "power-session", timestamp = 1, mode = "OldSchool",
                            reps = 2, weightPerCableKg = 25f, totalReps = 2,
                        ),
                        repSummaries = summaries,
                    ),
                ),
                "user",
            ).single().exercises.single().sets.single().repSummaries
            assertEquals("UNAVAILABLE", wire[0].powerMethod)
            assertNull(wire[0].powerWatts)
            assertNull(wire[0].peakPowerWatts)
            assertEquals(PAIRED_CABLE_POWER_METHOD, wire[1].powerMethod)
            assertEquals(250f, wire[1].powerWatts)
            assertEquals(375f, wire[1].peakPowerWatts)
        } finally {
            driver.close()
        }
    }
}

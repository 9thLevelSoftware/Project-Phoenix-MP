package com.devil.phoenixproject.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Issue #1400 F1: migration 65 scales legacy cm / cm-s metric values to mm / mm-s
 * exactly once. Every scaling statement is row-guarded by `metric_unit_version`
 * (1 = legacy, 2 = mm), so replaying the step — including the one-statement-at-a-time
 * replay `applyMigrationResilient` performs after a partial failure — can never
 * double-scale, while rows that arrive after a partial run are still scaled.
 *
 * Loads, percentages and counts are untouched. Old-scale derived estimates
 * (personal MVT samples, stored velocity-1RM estimates) are invalidated, not rescaled.
 */
class Issue1400Migration65UnitScalingTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Test
    fun `migration 65 scales legacy metric values exactly once and is idempotent`() {
        PhoenixDatabase.Schema.create(driver)
        seedLegacyRows()

        val first = applyMigrationResilient(driver, 65)
        assertTrue(
            first.none { !it.success && !it.recoverable },
            "migration 65 must apply cleanly: ${first.filter { !it.success }.map { it.error }}",
        )

        assertScaledExactlyOnce()
        assertInvalidationApplied()

        // Replay the whole step (the resilient fallback re-runs every statement one by
        // one after a partial failure): the row guard makes the second application a no-op.
        applyMigrationResilient(driver, 65)
        assertScaledExactlyOnce()
        assertInvalidationApplied()

        // A row that arrives AFTER the first application (staged legacy data, restore of
        // an old payload) is still scaled on the next replay, while scaled rows stay put.
        insertLegacyMetricRow(id = 42, position = 10.0, velocity = 20.0, power = 30.0)
        applyMigrationResilient(driver, 65)
        assertScaledExactlyOnce()
        assertDouble(
            id = 42,
            position = 100.0,
            velocity = 200.0,
            power = 300.0,
            message = "a late legacy row must scale exactly once on replay",
        )
    }

    private fun assertScaledExactlyOnce() {
        assertDouble(id = 41, position = 350.0, velocity = 700.0, power = 14000.0, message = "MetricSample")
        assertEquals(360.0, scalar("SELECT positionB FROM MetricSample WHERE id = 41"))
        assertEquals(710.0, scalar("SELECT velocityB FROM MetricSample WHERE id = 41"))
        assertEquals(20.5, scalar("SELECT load FROM MetricSample WHERE id = 41"), "loads must not scale")
        assertEquals(21.5, scalar("SELECT loadB FROM MetricSample WHERE id = 41"), "loads must not scale")
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM MetricSample WHERE id = 41"))

        assertEquals(700.0, scalar("SELECT peakVelocity FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(600.0, scalar("SELECT avgVelocityConcentric FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(500.0, scalar("SELECT avgVelocityEccentric FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(350.0, scalar("SELECT rangeOfMotionMm FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(14000.0, scalar("SELECT peakPowerWatts FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(12000.0, scalar("SELECT avgPowerWatts FROM RepMetric WHERE sessionId = 's1'"))
        assertEquals(40.0, scalar("SELECT peakForceA FROM RepMetric WHERE sessionId = 's1'"), "loads must not scale")
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM RepMetric WHERE sessionId = 's1'"))

        assertEquals(700.0, scalar("SELECT mcvMmS FROM RepBiomechanics WHERE sessionId = 's1'"))
        assertEquals(800.0, scalar("SELECT peakVelocityMmS FROM RepBiomechanics WHERE sessionId = 's1'"))
        assertEquals(25.0, scalar("SELECT velocityLossPercent FROM RepBiomechanics WHERE sessionId = 's1'"), "percentages must not scale")
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM RepBiomechanics WHERE sessionId = 's1'"))

        assertEquals(450.0, scalar("SELECT avgMcvMmS FROM WorkoutSession WHERE id = 's1'"))
        assertEquals(20.0, scalar("SELECT heaviestLiftKg FROM WorkoutSession WHERE id = 's1'"), "loads must not scale")
        assertEquals(25.0, scalar("SELECT totalVelocityLossPercent FROM WorkoutSession WHERE id = 's1'"), "percentages must not scale")
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM WorkoutSession WHERE id = 's1'"))

        assertEquals(700.0, scalar("SELECT concentricVelAvg FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(800.0, scalar("SELECT concentricVelMax FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(14000.0, scalar("SELECT concentricWattAvg FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(15000.0, scalar("SELECT concentricWattMax FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(500.0, scalar("SELECT eccentricVelAvg FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(600.0, scalar("SELECT eccentricVelMax FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(11000.0, scalar("SELECT eccentricWattAvg FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(12000.0, scalar("SELECT eccentricWattMax FROM PhaseStatistics WHERE sessionId = 's1'"))
        assertEquals(20.0, scalar("SELECT concentricKgAvg FROM PhaseStatistics WHERE sessionId = 's1'"), "loads must not scale")
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM PhaseStatistics WHERE sessionId = 's1'"))
    }

    private fun assertInvalidationApplied() {
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM ExerciseMvt"), "old-scale personal MVT must be invalidated")
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM VelocityOneRepMaxEstimate"), "old-scale velocity-1RM estimates must be invalidated")
    }

    private fun assertDouble(id: Long, position: Double, velocity: Double, power: Double, message: String) {
        assertEquals(position, scalar("SELECT position FROM MetricSample WHERE id = $id"), message)
        assertEquals(velocity, scalar("SELECT velocity FROM MetricSample WHERE id = $id"), message)
        assertEquals(power, scalar("SELECT power FROM MetricSample WHERE id = $id"), message)
        assertEquals(2L, scalarLong("SELECT metric_unit_version FROM MetricSample WHERE id = $id"), message)
    }

    // ==================== seeding ====================

    private fun seedLegacyRows() {
        // metric_unit_version is left unset on purpose: the default of 1 marks these
        // rows exactly like migration 65 finds pre-upgrade rows.
        driver.execute(
            null,
            "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg, avgMcvMmS, heaviestLiftKg, totalVelocityLossPercent) " +
                "VALUES ('s1', 1700000000000, 'OldSchool', 5, 20.0, 45.0, 20.0, 25.0)",
            0,
        )
        insertLegacyMetricRow(id = 41, position = 35.0, velocity = 70.0, power = 1400.0)
        driver.execute(
            null,
            "INSERT INTO RepMetric (sessionId, repNumber, startTimestamp, endTimestamp, durationMs, concentricDurationMs, " +
                "concentricPositions, concentricLoadsA, concentricLoadsB, concentricVelocities, concentricTimestamps, " +
                "eccentricDurationMs, eccentricPositions, eccentricLoadsA, eccentricLoadsB, eccentricVelocities, eccentricTimestamps, " +
                "peakForceA, peakForceB, avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB, " +
                "peakVelocity, avgVelocityConcentric, avgVelocityEccentric, rangeOfMotionMm, peakPowerWatts, avgPowerWatts) " +
                "VALUES ('s1', 1, 1, 2, 1, 1, '[35]', '[20]', '[21]', '[70]', '[0]', 1, '[10]', '[20]', '[21]', '[50]', '[1]', " +
                "40, 41, 40, 41, 40, 41, 70, 60, 50, 35, 1400, 1200)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO RepBiomechanics (sessionId, repNumber, mcvMmS, peakVelocityMmS, velocityZone, velocityLossPercent, " +
                "normalizedForceN, normalizedPositionPct, strengthProfile, asymmetryPercent, dominantSide, avgLoadA, avgLoadB, timestamp) " +
                "VALUES ('s1', 1, 70, 80, 'GRIND', 25, '[1]', '[0]', 'FLAT', 1, 'A', 20, 21, 2)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO ExerciseMvt (exerciseId, profile_id, personalMvtMs, sampleCount, updatedAt) " +
                "VALUES ('e1', 'default', 0.45, 3, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO PhaseStatistics (sessionId, concentricKgAvg, concentricKgMax, concentricVelAvg, concentricVelMax, " +
                "concentricWattAvg, concentricWattMax, eccentricKgAvg, eccentricKgMax, eccentricVelAvg, eccentricVelMax, " +
                "eccentricWattAvg, eccentricWattMax, timestamp) " +
                "VALUES ('s1', 20, 21, 70, 80, 1400, 1500, 19, 20, 50, 60, 1100, 1200, 3)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO VelocityOneRepMaxEstimate (exerciseId, estimatedPerCableKg, mvtUsedMs, r2, distinctLoads, passedQualityGate, computedAt) " +
                "VALUES ('e1', 55, 0.45, 0.9, 3, 1, 1)",
            0,
        )
    }

    private fun insertLegacyMetricRow(id: Long, position: Double, velocity: Double, power: Double) {
        driver.execute(
            null,
            "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, load, loadB, power) " +
                "VALUES ($id, 's1', 1700000000000, $position, ${position + 1}, $velocity, ${velocity + 1}, 20.5, 21.5, $power)",
            0,
        )
    }

    // ==================== query helpers ====================

    private fun scalar(sql: String): Double {
        var value: Double? = null
        driver.executeQuery(null, sql, { cursor ->
            if (cursor.next().value) value = cursor.getDouble(0)
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0).value
        return requireNotNull(value) { "no result for $sql" }
    }

    private fun scalarLong(sql: String): Long {
        var value: Long? = null
        driver.executeQuery(null, sql, { cursor ->
            if (cursor.next().value) value = cursor.getLong(0)
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0).value
        return requireNotNull(value) { "no result for $sql" }
    }
}

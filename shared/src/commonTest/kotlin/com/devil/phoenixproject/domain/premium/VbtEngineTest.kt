package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for VBT (Velocity Based Training) computation in BiomechanicsEngine.
 *
 * Covers:
 * - Mean Concentric Velocity (MCV) calculation
 * - Velocity zone classification
 * - Velocity loss tracking across reps
 * - Estimated reps remaining projection
 * - Auto-stop recommendation (shouldStopSet)
 */
class VbtEngineTest {

    // ========== Helper Functions ==========

    /**
     * Create a WorkoutMetric with specified velocity values.
     * Uses max(abs(velocityA), abs(velocityB)) for MCV calculation.
     */
    private fun createMetric(
        velocityA: Double = 0.0,
        velocityB: Double = 0.0,
        loadA: Float = 50f,
        loadB: Float = 50f,
        positionA: Float = 0f,
        positionB: Float = 0f
    ): WorkoutMetric = WorkoutMetric(
        timestamp = System.currentTimeMillis(),
        loadA = loadA,
        loadB = loadB,
        positionA = positionA,
        positionB = positionB,
        velocityA = velocityA,
        velocityB = velocityB
    )

    /**
     * Create a list of metrics with uniform velocity for MCV testing.
     */
    private fun createUniformMetrics(velocity: Double, count: Int = 5): List<WorkoutMetric> =
        (1..count).map { createMetric(velocityA = velocity, velocityB = velocity) }

    // ========== MCV Calculation Tests (VBT-01) ==========

    @Test
    fun `MCV from single metric uses max of abs velocities`() {
        val engine = BiomechanicsEngine()
        val metrics = listOf(createMetric(velocityA = 50.0, velocityB = 0.0))

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(50f, result.velocity.meanConcentricVelocityMmS, 0.1f,
            "Single metric with velocityA=50, velocityB=0 should give MCV=50")
    }

    @Test
    fun `MCV from single metric takes higher of two velocities`() {
        val engine = BiomechanicsEngine()
        val metrics = listOf(createMetric(velocityA = 30.0, velocityB = 70.0))

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(70f, result.velocity.meanConcentricVelocityMmS, 0.1f,
            "Should take max(30, 70) = 70 as the movement velocity")
    }

    @Test
    fun `MCV from multiple metrics calculates correct average`() {
        val engine = BiomechanicsEngine()
        // 5 samples: max velocities are 100, 120, 110, 115, 105 -> average = 110
        val metrics = listOf(
            createMetric(velocityA = 100.0, velocityB = 50.0),  // max = 100
            createMetric(velocityA = 80.0, velocityB = 120.0),  // max = 120
            createMetric(velocityA = 110.0, velocityB = 90.0),  // max = 110
            createMetric(velocityA = 115.0, velocityB = 100.0), // max = 115
            createMetric(velocityA = 105.0, velocityB = 105.0)  // max = 105
        )

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(110f, result.velocity.meanConcentricVelocityMmS, 0.1f,
            "Average of (100, 120, 110, 115, 105) should be 110")
    }

    @Test
    fun `MCV handles negative velocities with abs value`() {
        val engine = BiomechanicsEngine()
        val metrics = listOf(createMetric(velocityA = -200.0, velocityB = 150.0))

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(200f, result.velocity.meanConcentricVelocityMmS, 0.1f,
            "abs(-200) = 200 should be used as max velocity")
    }

    @Test
    fun `empty metrics list returns MCV of zero`() {
        val engine = BiomechanicsEngine()
        val emptyMetrics = emptyList<WorkoutMetric>()

        val result = engine.processRep(1, emptyMetrics, emptyMetrics, System.currentTimeMillis())

        assertEquals(0f, result.velocity.meanConcentricVelocityMmS, 0.1f,
            "Empty metrics should give MCV=0")
        assertEquals(BiomechanicsVelocityZone.GRIND, result.velocity.zone,
            "Empty metrics should classify as GRIND zone")
    }

    // ========== Peak Velocity Tests ==========

    @Test
    fun `peak velocity captures maximum velocity in rep`() {
        val engine = BiomechanicsEngine()
        val metrics = listOf(
            createMetric(velocityA = 100.0, velocityB = 50.0),   // max = 100
            createMetric(velocityA = 200.0, velocityB = 180.0),  // max = 200 (peak)
            createMetric(velocityA = 150.0, velocityB = 140.0)   // max = 150
        )

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(200f, result.velocity.peakVelocityMmS, 0.1f,
            "Peak velocity should be 200 (highest sample)")
    }

    // ========== Zone Classification Tests (VBT-02) ==========

    @Test
    fun `zone classification at GRIND boundary - 249 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(249.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.GRIND, result.velocity.zone,
            "MCV=249 should be GRIND (< 250)")
    }

    @Test
    fun `zone classification at SLOW boundary - 250 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(250.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.SLOW, result.velocity.zone,
            "MCV=250 should be SLOW (>= 250)")
    }

    @Test
    fun `zone classification at SLOW upper - 499 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(499.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.SLOW, result.velocity.zone,
            "MCV=499 should be SLOW (< 500)")
    }

    @Test
    fun `zone classification at MODERATE boundary - 500 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(500.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.MODERATE, result.velocity.zone,
            "MCV=500 should be MODERATE (>= 500)")
    }

    @Test
    fun `zone classification at MODERATE upper - 749 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(749.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.MODERATE, result.velocity.zone,
            "MCV=749 should be MODERATE (< 750)")
    }

    @Test
    fun `zone classification at FAST boundary - 750 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(750.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.FAST, result.velocity.zone,
            "MCV=750 should be FAST (>= 750)")
    }

    @Test
    fun `zone classification at FAST upper - 999 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(999.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.FAST, result.velocity.zone,
            "MCV=999 should be FAST (< 1000)")
    }

    @Test
    fun `zone classification at EXPLOSIVE boundary - 1000 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(1000.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.EXPLOSIVE, result.velocity.zone,
            "MCV=1000 should be EXPLOSIVE (>= 1000)")
    }

    @Test
    fun `zone classification high EXPLOSIVE - 1500 mm per s`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(1500.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertEquals(BiomechanicsVelocityZone.EXPLOSIVE, result.velocity.zone,
            "MCV=1500 should be EXPLOSIVE")
    }

    // ========== Velocity Loss Tests (VBT-03) ==========

    @Test
    fun `rep 1 has null velocity loss`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(1000.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertNull(result.velocity.velocityLossPercent,
            "Rep 1 should have null velocityLossPercent")
    }

    @Test
    fun `rep 2 at 80 percent of rep 1 velocity gives 20 percent loss`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 1000 mm/s
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 800 mm/s (80% of 1000)
        val result = engine.processRep(2, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        assertEquals(20f, result.velocity.velocityLossPercent!!, 0.1f,
            "Rep 2 at 80% of rep 1 should show 20% velocity loss")
    }

    @Test
    fun `rep 2 at 90 percent of rep 1 velocity gives 10 percent loss`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 1000 mm/s
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 900 mm/s (90% of 1000)
        val result = engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())

        assertEquals(10f, result.velocity.velocityLossPercent!!, 0.1f,
            "Rep 2 at 90% of rep 1 should show 10% velocity loss")
    }

    @Test
    fun `rep faster than rep 1 clamps velocity loss to zero`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 800 mm/s
        engine.processRep(1, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 900 mm/s (faster than rep 1!)
        val result = engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())

        assertEquals(0f, result.velocity.velocityLossPercent!!, 0.1f,
            "Velocity loss should be clamped to 0 when rep is faster than rep 1")
    }

    @Test
    fun `progressive velocity loss across multiple reps`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 1000 mm/s (baseline)
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 950 mm/s (5% loss)
        val rep2 = engine.processRep(2, createUniformMetrics(950.0), emptyList(), System.currentTimeMillis())
        assertEquals(5f, rep2.velocity.velocityLossPercent!!, 0.1f)

        // Rep 3: MCV = 850 mm/s (15% loss from rep 1)
        val rep3 = engine.processRep(3, createUniformMetrics(850.0), emptyList(), System.currentTimeMillis())
        assertEquals(15f, rep3.velocity.velocityLossPercent!!, 0.1f)

        // Rep 4: MCV = 750 mm/s (25% loss from rep 1)
        val rep4 = engine.processRep(4, createUniformMetrics(750.0), emptyList(), System.currentTimeMillis())
        assertEquals(25f, rep4.velocity.velocityLossPercent!!, 0.1f)
    }

    // ========== Auto-Stop Tests (VBT-05) ==========

    @Test
    fun `rep 1 never triggers shouldStopSet`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(500.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertFalse(result.velocity.shouldStopSet,
            "Rep 1 should never trigger shouldStopSet")
    }

    @Test
    fun `shouldStopSet true when velocity loss equals threshold`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 800 (exactly 20% loss)
        val result = engine.processRep(2, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        assertTrue(result.velocity.shouldStopSet,
            "shouldStopSet should be true when velocity loss equals 20% threshold")
    }

    @Test
    fun `shouldStopSet true when velocity loss exceeds threshold`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 3: MCV = 700 (30% loss)
        val result = engine.processRep(3, createUniformMetrics(700.0), emptyList(), System.currentTimeMillis())

        assertTrue(result.velocity.shouldStopSet,
            "shouldStopSet should be true when velocity loss (30%) exceeds 20% threshold")
    }

    @Test
    fun `shouldStopSet false when velocity loss below threshold`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 900 (10% loss)
        val result = engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())

        assertFalse(result.velocity.shouldStopSet,
            "shouldStopSet should be false when velocity loss (10%) is below 20% threshold")
    }

    @Test
    fun `custom velocity loss threshold works`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 30f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 750 (25% loss - below 30% custom threshold)
        val rep2 = engine.processRep(2, createUniformMetrics(750.0), emptyList(), System.currentTimeMillis())
        assertFalse(rep2.velocity.shouldStopSet,
            "25% loss should not trigger 30% threshold")

        // Rep 3: MCV = 700 (30% loss - at threshold)
        val rep3 = engine.processRep(3, createUniformMetrics(700.0), emptyList(), System.currentTimeMillis())
        assertTrue(rep3.velocity.shouldStopSet,
            "30% loss should trigger 30% threshold")
    }

    // ========== Rep Projection Tests (VBT-04) ==========

    @Test
    fun `rep 1 has null estimated reps remaining`() {
        val engine = BiomechanicsEngine()
        val metrics = createUniformMetrics(1000.0)

        val result = engine.processRep(1, metrics, metrics, System.currentTimeMillis())

        assertNull(result.velocity.estimatedRepsRemaining,
            "Rep 1 should have null estimatedRepsRemaining")
    }

    @Test
    fun `rep 2 projects remaining reps from decay rate`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 950 (5% loss, 5% per rep decay rate)
        // At 5% per rep, to reach 20% threshold needs 3 more reps (10%, 15%, 20%)
        val result = engine.processRep(2, createUniformMetrics(950.0), emptyList(), System.currentTimeMillis())

        assertTrue(result.velocity.estimatedRepsRemaining != null,
            "Rep 2 should have estimated reps remaining")
        // At 5% loss per rep, from current 5% loss, need 3 more reps to hit 20%
        assertEquals(3, result.velocity.estimatedRepsRemaining,
            "At 5% loss per rep from 5% current, should estimate 3 reps to 20%")
    }

    @Test
    fun `projection with linear decay matches expected reps`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 25f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 900 (10% loss -> 10% per rep)
        // At 10% per rep, from 10% current loss, need 1.5 more to hit 25% -> rounds to 1
        val rep2 = engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())
        // (25 - 10) / 10 = 1.5 -> 1 remaining
        assertTrue(rep2.velocity.estimatedRepsRemaining in 1..2,
            "At 10% per rep from 10%, should estimate 1-2 reps to 25%")
    }

    @Test
    fun `projection returns null when velocity increasing`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 800
        engine.processRep(1, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 850 (velocity increasing, not decaying)
        val result = engine.processRep(2, createUniformMetrics(850.0), emptyList(), System.currentTimeMillis())

        // When velocity is increasing, projection is nonsensical
        assertNull(result.velocity.estimatedRepsRemaining,
            "Should return null when velocity is increasing (no decay)")
    }

    @Test
    fun `projection clamped to maximum 99 reps`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        // Rep 1: MCV = 1000
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 999 (0.1% loss -> would project huge number)
        val result = engine.processRep(2, createUniformMetrics(999.0), emptyList(), System.currentTimeMillis())

        if (result.velocity.estimatedRepsRemaining != null) {
            assertTrue(result.velocity.estimatedRepsRemaining!! <= 99,
                "Estimated reps should be clamped to max 99")
        }
    }

    // ========== Reset Tests ==========

    @Test
    fun `reset clears firstRepMcv and velocity tracking`() {
        val engine = BiomechanicsEngine()

        // Process 2 reps to build state
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())
        engine.processRep(2, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        engine.reset()

        // After reset, rep 1 should have no velocity loss (fresh baseline)
        val result = engine.processRep(1, createUniformMetrics(600.0), emptyList(), System.currentTimeMillis())

        assertNull(result.velocity.velocityLossPercent,
            "After reset, rep 1 should have null velocity loss")
        assertFalse(result.velocity.shouldStopSet,
            "After reset, rep 1 should not trigger stop")
    }

    // ========== Set Summary Integration ==========

    @Test
    fun `getSetSummary returns correct totalVelocityLossPercent`() {
        val engine = BiomechanicsEngine()

        // Rep 1: MCV = 1000 (baseline)
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())

        // Rep 2: MCV = 900 (10% loss)
        engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())

        // Rep 3: MCV = 800 (20% loss from rep 1)
        engine.processRep(3, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        val summary = engine.getSetSummary()

        assertEquals(20f, summary!!.totalVelocityLossPercent!!, 0.1f,
            "Total velocity loss should be 20% (1000 -> 800)")
    }

    @Test
    fun `getSetSummary returns correct avgMcvMmS`() {
        val engine = BiomechanicsEngine()

        // 3 reps: 1000, 900, 800 -> avg = 900
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis())
        engine.processRep(2, createUniformMetrics(900.0), emptyList(), System.currentTimeMillis())
        engine.processRep(3, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())

        val summary = engine.getSetSummary()

        assertEquals(900f, summary!!.avgMcvMmS, 0.1f,
            "Average MCV should be 900 mm/s")
    }

    @Test
    fun `getSetSummary returns correct zone distribution`() {
        val engine = BiomechanicsEngine()

        // 2 EXPLOSIVE, 1 FAST, 1 MODERATE
        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), System.currentTimeMillis()) // EXPLOSIVE
        engine.processRep(2, createUniformMetrics(1100.0), emptyList(), System.currentTimeMillis()) // EXPLOSIVE
        engine.processRep(3, createUniformMetrics(800.0), emptyList(), System.currentTimeMillis())  // FAST
        engine.processRep(4, createUniformMetrics(600.0), emptyList(), System.currentTimeMillis())  // MODERATE

        val summary = engine.getSetSummary()

        assertEquals(2, summary!!.zoneDistribution[BiomechanicsVelocityZone.EXPLOSIVE])
        assertEquals(1, summary.zoneDistribution[BiomechanicsVelocityZone.FAST])
        assertEquals(1, summary.zoneDistribution[BiomechanicsVelocityZone.MODERATE])
    }
}

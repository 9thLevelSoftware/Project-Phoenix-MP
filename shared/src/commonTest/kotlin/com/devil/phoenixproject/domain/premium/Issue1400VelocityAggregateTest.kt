package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1400 (F2/F3): set-level velocity aggregates use only aggregate-eligible reps with a
 * valid MCV, set velocity loss is clamped to 0-100, and per-rep loss, the auto-end signal and
 * the set loss share one baseline (the first eligible valid rep).
 */
class Issue1400VelocityAggregateTest {

    private fun samples(velocity: Double, load: Float = 25f, count: Int = 5): List<WorkoutMetric> =
        List(count) { index ->
            WorkoutMetric(
                timestamp = 1_000L + index,
                loadA = load,
                loadB = load,
                positionA = 0f,
                positionB = 0f,
                velocityA = velocity,
                velocityB = velocity,
            )
        }

    private fun BiomechanicsEngine.rep(repNumber: Int, velocity: Double, eligible: Boolean = true) =
        processRep(
            repNumber = repNumber,
            concentricMetrics = samples(velocity),
            allRepMetrics = samples(velocity),
            timestamp = repNumber * 1_000L,
            aggregateEligible = eligible,
        )

    // ---------- F3: set summary ----------

    @Test
    fun `rising MCV gives 0 percent set loss, never negative`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, 450.0)
        engine.rep(2, 520.0)
        engine.rep(3, 600.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(0f, summary.totalVelocityLossPercent)
    }

    @Test
    fun `decay from 600 to 450 gives 25 percent set loss`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, 600.0)
        engine.rep(2, 520.0)
        engine.rep(3, 450.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(25f, assertNotNull(summary.totalVelocityLossPercent), 0.01f)
    }

    @Test
    fun `a zero MCV first rep is skipped and the next valid rep is the baseline`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, 0.0)
        engine.rep(2, 600.0)
        engine.rep(3, 450.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(25f, assertNotNull(summary.totalVelocityLossPercent), 0.01f)
        assertEquals(525f, summary.avgMcvMmS, 0.01f, "A zero MCV rep must not drag the set MCV down")
    }

    @Test
    fun `a non finite first rep is skipped and the next valid rep is the baseline`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, Double.NaN)
        engine.rep(2, 600.0)
        engine.rep(3, 450.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(25f, assertNotNull(summary.totalVelocityLossPercent), 0.01f)
        assertEquals(525f, summary.avgMcvMmS, 0.01f)
    }

    @Test
    fun `fewer than two eligible valid reps gives null set loss`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, 200.0, eligible = false)
        engine.rep(2, 600.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertNull(summary.totalVelocityLossPercent)
        assertEquals(600f, summary.avgMcvMmS)
    }

    @Test
    fun `a slow calibration-contaminated rep 1 is excluded from set MCV, loss and zones`() {
        // Reporter shape: rep 1 is dragged down by calibration pulls; the working reps are fast.
        val engine = BiomechanicsEngine()
        engine.rep(1, 300.0, eligible = false)
        engine.rep(2, 800.0)
        engine.rep(3, 760.0)
        engine.rep(4, 720.0)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(760f, summary.avgMcvMmS, 0.01f)
        assertEquals(10f, assertNotNull(summary.totalVelocityLossPercent), 0.01f)
        assertEquals(
            mapOf(BiomechanicsVelocityZone.FAST to 2, BiomechanicsVelocityZone.MODERATE to 1),
            summary.zoneDistribution,
            "The ineligible SLOW rep must not appear in the zone distribution",
        )
        assertEquals(4, summary.repResults.size, "Every rep stays listed for the per-rep display")
        assertFalse(summary.repResults.first().aggregateEligible)
    }

    // ---------- shared baseline: per-rep loss, auto-end and set loss agree ----------

    @Test
    fun `an ineligible rep never becomes the baseline and never signals auto-end`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)
        val rep1 = engine.rep(1, 300.0, eligible = false)
        val rep2 = engine.rep(2, 800.0)
        val rep3 = engine.rep(3, 600.0)

        assertNull(rep1.velocity.velocityLossPercent)
        assertFalse(rep1.velocity.shouldStopSet)
        assertNull(rep2.velocity.velocityLossPercent, "Rep 2 is the first eligible rep, so it is the baseline")
        assertEquals(25f, assertNotNull(rep3.velocity.velocityLossPercent), 0.01f)
        assertTrue(rep3.velocity.shouldStopSet)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(
            rep3.velocity.velocityLossPercent,
            summary.totalVelocityLossPercent,
            "Per-rep and set loss must use the same baseline",
        )
    }

    @Test
    fun `a ramp rep after the baseline reports no loss and cannot trigger auto-end`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)
        engine.rep(1, 800.0)
        val ramp = engine.rep(2, 200.0, eligible = false)

        assertNull(ramp.velocity.velocityLossPercent)
        assertFalse(ramp.velocity.shouldStopSet)
        assertNull(ramp.velocity.estimatedRepsRemaining)
    }

    @Test
    fun `reps remaining projects from the baseline rep, not from rep 1`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)
        engine.rep(1, 300.0, eligible = false)
        engine.rep(2, 1000.0)
        val rep3 = engine.rep(3, 950.0)

        // 5 % loss over one rep since the baseline -> 15 % remaining at 5 %/rep = 3 reps.
        assertEquals(3, rep3.velocity.estimatedRepsRemaining)
    }

    @Test
    fun `reset clears the baseline rep`() {
        val engine = BiomechanicsEngine()
        engine.rep(1, 800.0)
        engine.reset()
        val next = engine.rep(1, 400.0)

        assertNull(next.velocity.velocityLossPercent, "After reset rep 1 is a fresh baseline")
    }

    // ---------- F2: constant-load ramp eligibility ----------

    @Test
    fun `old school rep below 90 percent of commanded load is a ramp rep`() {
        assertFalse(
            reachedCommandedLoadForAggregates(
                programMode = ProgramMode.OldSchool,
                progressionRegressionKg = 0f,
                commandedPerCableKg = 25f,
                concentricMetrics = samples(500.0, load = 10f),
            ),
        )
    }

    @Test
    fun `old school rep at or above 90 percent of commanded load is eligible`() {
        assertTrue(
            reachedCommandedLoadForAggregates(
                programMode = ProgramMode.OldSchool,
                progressionRegressionKg = 0f,
                commandedPerCableKg = 25f,
                concentricMetrics = samples(500.0, load = 22.5f),
            ),
        )
    }

    @Test
    fun `single cable old school rep is judged on the loaded cable`() {
        val singleCable = samples(500.0).map { it.copy(loadA = 25f, loadB = 0f) }
        assertTrue(
            reachedCommandedLoadForAggregates(
                programMode = ProgramMode.OldSchool,
                progressionRegressionKg = 0f,
                commandedPerCableKg = 25f,
                concentricMetrics = singleCable,
            ),
        )
    }

    @Test
    fun `variable load modes and progression sets get no ramp filter`() {
        val light = samples(500.0, load = 5f)
        listOf(ProgramMode.Pump, ProgramMode.TUT, ProgramMode.TUTBeast, ProgramMode.EccentricOnly, ProgramMode.Echo)
            .forEach { mode ->
                assertTrue(
                    reachedCommandedLoadForAggregates(mode, 0f, 25f, light),
                    "$mode has no constant commanded load",
                )
            }
        assertTrue(reachedCommandedLoadForAggregates(ProgramMode.OldSchool, 1f, 25f, light))
        assertTrue(reachedCommandedLoadForAggregates(ProgramMode.OldSchool, 0f, 0f, light))
    }

    @Test
    fun `a rack counterweight disables the ramp filter`() {
        // The machine holds programmed minus counterweight, so a full-load rep reads below 90 %.
        assertTrue(
            reachedCommandedLoadForAggregates(
                programMode = ProgramMode.OldSchool,
                progressionRegressionKg = 0f,
                commandedPerCableKg = 25f,
                concentricMetrics = samples(500.0, load = 20f),
                counterweightKg = 10f,
            ),
        )
    }

    // ---------- policy helper ----------

    @Test
    fun `loss is clamped to 0 through 100 and null for invalid inputs`() {
        assertEquals(0f, VelocityLossBaseline.lossPercent(500f, 700f))
        assertTrue(assertNotNull(VelocityLossBaseline.lossPercent(500f, 0.001f)) <= 100f)
        assertNull(VelocityLossBaseline.lossPercent(0f, 400f))
        assertNull(VelocityLossBaseline.lossPercent(500f, 0f))
        assertNull(VelocityLossBaseline.lossPercent(Float.NaN, 400f))
    }
}

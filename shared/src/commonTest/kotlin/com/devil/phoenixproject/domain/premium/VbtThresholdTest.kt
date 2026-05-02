package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VbtThresholdTest {

    private fun createUniformMetrics(velocity: Double, count: Int = 5): List<WorkoutMetric> =
        (1..count).map {
            WorkoutMetric(
                timestamp = currentTimeMillis(),
                loadA = 50f,
                loadB = 50f,
                positionA = 0f,
                positionB = 0f,
                velocityA = velocity,
                velocityB = velocity,
            )
        }

    @Test
    fun `default threshold 20 percent triggers shouldStopSet at 20 percent loss`() {
        val engine = BiomechanicsEngine()

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(800.0), emptyList(), currentTimeMillis())

        assertTrue(rep2.velocity.shouldStopSet, "20% loss should trigger default 20% threshold")
        assertEquals(20f, rep2.velocity.velocityLossPercent!!, 0.1f)
    }

    @Test
    fun `default threshold 20 percent does NOT trigger at 19 percent loss`() {
        val engine = BiomechanicsEngine()

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(810.0), emptyList(), currentTimeMillis())

        assertFalse(rep2.velocity.shouldStopSet, "19% loss should not trigger default 20% threshold")
    }

    @Test
    fun `custom threshold 10 percent triggers at 10 percent loss`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 10f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(900.0), emptyList(), currentTimeMillis())

        assertTrue(rep2.velocity.shouldStopSet, "10% loss should trigger 10% threshold")
    }

    @Test
    fun `custom threshold 30 percent does NOT trigger at 25 percent loss`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 30f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(750.0), emptyList(), currentTimeMillis())

        assertFalse(rep2.velocity.shouldStopSet, "25% loss should not trigger 30% threshold")
    }

    @Test
    fun `custom threshold 30 percent triggers at 30 percent loss`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 30f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(700.0), emptyList(), currentTimeMillis())

        assertTrue(rep2.velocity.shouldStopSet, "30% loss should trigger 30% threshold")
    }

    @Test
    fun `updated threshold applies to subsequent reps`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 30f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(850.0), emptyList(), currentTimeMillis())
        assertFalse(rep2.velocity.shouldStopSet, "15% loss should not trigger initial 30% threshold")

        engine.updateVelocityLossThresholdPercent(10f)

        val rep3 = engine.processRep(3, createUniformMetrics(850.0), emptyList(), currentTimeMillis())
        assertTrue(rep3.velocity.shouldStopSet, "15% loss should trigger updated 10% threshold")
    }

    @Test
    fun `custom threshold 50 percent triggers only at extreme loss`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 50f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())

        val rep2 = engine.processRep(2, createUniformMetrics(600.0), emptyList(), currentTimeMillis())
        assertFalse(rep2.velocity.shouldStopSet, "40% loss should not trigger 50% threshold")

        val rep3 = engine.processRep(3, createUniformMetrics(500.0), emptyList(), currentTimeMillis())
        assertTrue(rep3.velocity.shouldStopSet, "50% loss should trigger 50% threshold")
    }

    @Test
    fun `rep 1 never triggers shouldStopSet regardless of threshold`() {
        val thresholds = listOf(5f, 10f, 20f, 30f, 50f)
        for (threshold in thresholds) {
            val engine = BiomechanicsEngine(velocityLossThresholdPercent = threshold)
            val result = engine.processRep(1, createUniformMetrics(100.0), emptyList(), currentTimeMillis())
            assertFalse(result.velocity.shouldStopSet, "Rep 1 should never trigger at threshold=$threshold")
        }
    }

    @Test
    fun `shouldStopSet transitions from false to true across reps`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())

        val rep2 = engine.processRep(2, createUniformMetrics(900.0), emptyList(), currentTimeMillis())
        assertFalse(rep2.velocity.shouldStopSet, "10% loss: not yet at threshold")

        val rep3 = engine.processRep(3, createUniformMetrics(850.0), emptyList(), currentTimeMillis())
        assertFalse(rep3.velocity.shouldStopSet, "15% loss: not yet at threshold")

        val rep4 = engine.processRep(4, createUniformMetrics(800.0), emptyList(), currentTimeMillis())
        assertTrue(rep4.velocity.shouldStopSet, "20% loss: at threshold, should trigger")
    }

    @Test
    fun `reset clears state for new set with same threshold`() {
        val engine = BiomechanicsEngine(velocityLossThresholdPercent = 20f)

        engine.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        val rep2 = engine.processRep(2, createUniformMetrics(800.0), emptyList(), currentTimeMillis())
        assertTrue(rep2.velocity.shouldStopSet, "Pre-reset: threshold reached")

        engine.reset()

        val newRep1 = engine.processRep(1, createUniformMetrics(500.0), emptyList(), currentTimeMillis())
        assertNull(newRep1.velocity.velocityLossPercent, "After reset, rep 1 has null loss")
        assertFalse(newRep1.velocity.shouldStopSet, "After reset, rep 1 never triggers")

        val newRep2 = engine.processRep(2, createUniformMetrics(450.0), emptyList(), currentTimeMillis())
        assertEquals(10f, newRep2.velocity.velocityLossPercent!!, 0.1f, "Loss computed from new baseline")
        assertFalse(newRep2.velocity.shouldStopSet, "10% loss below 20% threshold")
    }

    @Test
    fun `estimatedRepsRemaining uses configured threshold`() {
        val engine10 = BiomechanicsEngine(velocityLossThresholdPercent = 10f)
        val engine30 = BiomechanicsEngine(velocityLossThresholdPercent = 30f)

        // Both engines: rep 1 at 1000, rep 2 at 950 (5% loss, 5% per rep decay)
        engine10.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())
        engine30.processRep(1, createUniformMetrics(1000.0), emptyList(), currentTimeMillis())

        val result10 = engine10.processRep(2, createUniformMetrics(950.0), emptyList(), currentTimeMillis())
        val result30 = engine30.processRep(2, createUniformMetrics(950.0), emptyList(), currentTimeMillis())

        // 10% threshold: from 5% loss at 5%/rep -> (10-5)/5 = 1 rep remaining
        assertEquals(1, result10.velocity.estimatedRepsRemaining, "10% threshold: 1 rep remaining from 5% loss at 5%/rep")

        // 30% threshold: from 5% loss at 5%/rep -> (30-5)/5 = 5 reps remaining
        assertEquals(5, result30.velocity.estimatedRepsRemaining, "30% threshold: 5 reps remaining from 5% loss at 5%/rep")
    }
}

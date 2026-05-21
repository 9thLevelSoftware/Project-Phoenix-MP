package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CableUsageDetectorTest {

    @Test
    fun `detects load imbalance between cables`() {
        val metrics = listOf(
            sample(loadA = 30f, loadB = 8f, positionA = 100f, positionB = 100f, velocityA = 50.0, velocityB = 5.0),
            sample(loadA = 28f, loadB = 9f, positionA = 180f, positionB = 102f, velocityA = -40.0, velocityB = -5.0),
        )

        assertTrue(CableUsageDetector.isSingleCableUsage(metrics))
    }

    @Test
    fun `detects ROM imbalance when idle cable load is noisy`() {
        val metrics = listOf(
            sample(loadA = 28f, loadB = 18f, positionA = 100f, positionB = 100f, velocityA = 80.0, velocityB = 12.0),
            sample(loadA = 30f, loadB = 20f, positionA = 220f, positionB = 105f, velocityA = -70.0, velocityB = -10.0),
            sample(loadA = 27f, loadB = 19f, positionA = 110f, positionB = 103f, velocityA = 75.0, velocityB = 15.0),
        )

        assertTrue(CableUsageDetector.isSingleCableUsage(metrics))
    }

    @Test
    fun `does not flag balanced dual-cable work`() {
        val metrics = listOf(
            sample(loadA = 40f, loadB = 38f, positionA = 100f, positionB = 100f, velocityA = 80.0, velocityB = 78.0),
            sample(loadA = 42f, loadB = 41f, positionA = 200f, positionB = 198f, velocityA = -75.0, velocityB = -74.0),
        )

        assertFalse(CableUsageDetector.isSingleCableUsage(metrics))
    }

    @Test
    fun `returns false for empty metrics`() {
        assertFalse(CableUsageDetector.isSingleCableUsage(emptyList()))
    }

    private fun sample(
        loadA: Float,
        loadB: Float,
        positionA: Float,
        positionB: Float,
        velocityA: Double,
        velocityB: Double,
    ): WorkoutMetric = WorkoutMetric(
        timestamp = 0L,
        loadA = loadA,
        loadB = loadB,
        positionA = positionA,
        positionB = positionB,
        velocityA = velocityA,
        velocityB = velocityB,
    )
}

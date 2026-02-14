package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.activeDWSM
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for RepMetric persistence during workout flow.
 * Verifies DATA-01 gap closure: per-rep metric data persists to RepMetric table at set completion.
 */
class RepMetricPersistenceTest {

    private fun createTestRepMetricData(repNumber: Int, isWarmup: Boolean = false): RepMetricData {
        return RepMetricData(
            repNumber = repNumber,
            isWarmup = isWarmup,
            startTimestamp = 1000L * repNumber,
            endTimestamp = 1000L * repNumber + 800L,
            durationMs = 800L,
            concentricDurationMs = 300L,
            concentricPositions = floatArrayOf(0f, 50f, 100f),
            concentricLoadsA = floatArrayOf(10f, 12f, 11f),
            concentricLoadsB = floatArrayOf(10f, 12f, 11f),
            concentricVelocities = floatArrayOf(30f, 40f, 35f),
            concentricTimestamps = longArrayOf(0L, 100L, 200L),
            eccentricDurationMs = 500L,
            eccentricPositions = floatArrayOf(100f, 50f, 0f),
            eccentricLoadsA = floatArrayOf(11f, 10f, 9f),
            eccentricLoadsB = floatArrayOf(11f, 10f, 9f),
            eccentricVelocities = floatArrayOf(20f, 25f, 22f),
            eccentricTimestamps = longArrayOf(300L, 500L, 700L),
            peakForceA = 12f,
            peakForceB = 12f,
            avgForceConcentricA = 11f,
            avgForceConcentricB = 11f,
            avgForceEccentricA = 10f,
            avgForceEccentricB = 10f,
            peakVelocity = 40f,
            avgVelocityConcentric = 35f,
            avgVelocityEccentric = 22f,
            rangeOfMotionMm = 100f,
            peakPowerWatts = 480f,
            avgPowerWatts = 385f
        )
    }

    @Test
    fun `rep metrics are persisted at set completion with correct session ID`() = runTest {
        val harness = activeDWSM()
        val coordinator = harness.coordinator
        val sessionId = coordinator.currentSessionId
        assertNotNull(sessionId, "Session ID should be set after starting workout")

        // Simulate scored reps by adding RepMetricData to the accumulation list
        val repMetrics = listOf(
            createTestRepMetricData(1),
            createTestRepMetricData(2),
            createTestRepMetricData(3)
        )
        repMetrics.forEach { coordinator.setRepMetrics.add(it) }
        assertEquals(3, coordinator.setRepMetrics.size, "Should have 3 accumulated rep metrics")

        // Trigger set completion
        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        // Verify rep metrics were persisted to the fake repository
        val saved = harness.fakeRepMetricRepo.savedMetrics[sessionId]
        assertNotNull(saved, "Rep metrics should be saved for session $sessionId")
        assertEquals(3, saved.size, "Should have persisted 3 rep metrics")

        // Verify setRepMetrics list is cleared after persistence
        assertTrue(coordinator.setRepMetrics.isEmpty(), "setRepMetrics should be cleared after persistence")

        harness.cleanup()
    }

    @Test
    fun `rep metrics not persisted when accumulation list is empty`() = runTest {
        val harness = activeDWSM()
        val coordinator = harness.coordinator
        val sessionId = coordinator.currentSessionId
        assertNotNull(sessionId)

        // Do NOT add any rep metrics - list is empty
        assertTrue(coordinator.setRepMetrics.isEmpty())

        // Trigger set completion
        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        // No metrics should be saved
        val saved = harness.fakeRepMetricRepo.savedMetrics[sessionId]
        assertTrue(saved == null || saved.isEmpty(), "No rep metrics should be saved when none were accumulated")

        harness.cleanup()
    }

    @Test
    fun `persisted rep metric count matches accumulated count`() = runTest {
        val harness = activeDWSM()
        val coordinator = harness.coordinator
        val sessionId = coordinator.currentSessionId
        assertNotNull(sessionId)

        // Add 5 rep metrics (1 warmup + 4 working)
        coordinator.setRepMetrics.add(createTestRepMetricData(1, isWarmup = true))
        coordinator.setRepMetrics.add(createTestRepMetricData(2))
        coordinator.setRepMetrics.add(createTestRepMetricData(3))
        coordinator.setRepMetrics.add(createTestRepMetricData(4))
        coordinator.setRepMetrics.add(createTestRepMetricData(5))
        val expectedCount = coordinator.setRepMetrics.size

        // Trigger set completion
        harness.activeSessionEngine.handleSetCompletion()
        advanceUntilIdle()

        // Verify count matches
        val savedCount = harness.fakeRepMetricRepo.getRepMetricCount(sessionId)
        assertEquals(expectedCount.toLong(), savedCount, "Persisted count should match accumulated count")

        harness.cleanup()
    }
}

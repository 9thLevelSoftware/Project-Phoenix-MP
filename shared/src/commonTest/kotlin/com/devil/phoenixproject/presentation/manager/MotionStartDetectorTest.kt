package com.devil.phoenixproject.presentation.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MotionStartDetectorTest {
    @Test
    fun emitsStartedAfterSustainedLoadAboveThreshold() = runTest {
        val detector = MotionStartDetector(loadThresholdKg = 5f, holdDurationMs = 1500L)
        val events = mutableListOf<MotionStartEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.events.collect { events.add(it) }
        }

        // Simulate 2 seconds of sustained load above threshold
        repeat(20) { i ->
            detector.onMetricReceived(load = 10f, timestampMs = i * 100L)
        }

        assertTrue(
            events.any {
                it is MotionStartEvent.Started
            },
            "Should emit Started after sustained load",
        )
        job.cancel()
    }

    @Test
    fun doesNotEmitStartedIfLoadDropsBelowThreshold() = runTest {
        val detector = MotionStartDetector(loadThresholdKg = 5f, holdDurationMs = 1500L)
        val events = mutableListOf<MotionStartEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.events.collect { events.add(it) }
        }

        // Load for 1 second then drop
        repeat(10) { i -> detector.onMetricReceived(load = 10f, timestampMs = i * 100L) }
        detector.onMetricReceived(load = 2f, timestampMs = 1000L)
        // Continue with no load
        repeat(10) { i -> detector.onMetricReceived(load = 2f, timestampMs = 1100L + i * 100L) }

        assertFalse(
            events.any {
                it is MotionStartEvent.Started
            },
            "Should not emit Started if load dropped",
        )
        job.cancel()
    }

    @Test
    fun emitsCancelledWhenLoadDropsAfterHoldBegan() = runTest {
        val detector = MotionStartDetector(loadThresholdKg = 5f, holdDurationMs = 1500L)
        val events = mutableListOf<MotionStartEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.events.collect { events.add(it) }
        }

        // Start holding, then drop
        repeat(5) { i -> detector.onMetricReceived(load = 10f, timestampMs = i * 100L) }
        detector.onMetricReceived(load = 2f, timestampMs = 500L)

        assertTrue(
            events.any {
                it is MotionStartEvent.Cancelled
            },
            "Should emit Cancelled when load drops",
        )
        job.cancel()
    }

    @Test
    fun emitsCountdownTicksDuringHold() = runTest {
        val detector = MotionStartDetector(loadThresholdKg = 5f, holdDurationMs = 1500L)
        val events = mutableListOf<MotionStartEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.events.collect { events.add(it) }
        }

        // Hold for 500ms (not enough to start, should get ticks)
        repeat(5) { i -> detector.onMetricReceived(load = 10f, timestampMs = i * 100L) }

        val ticks = events.filterIsInstance<MotionStartEvent.CountdownTick>()
        assertTrue(ticks.isNotEmpty(), "Should emit countdown ticks during hold")
        assertTrue(
            ticks.all {
                it.remainingMs > 0
            },
            "All ticks should have positive remaining time",
        )
        job.cancel()
    }

    @Test
    fun resetClearsHoldState() = runTest {
        val detector = MotionStartDetector(loadThresholdKg = 5f, holdDurationMs = 1500L)
        val events = mutableListOf<MotionStartEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            detector.events.collect { events.add(it) }
        }

        // Start holding
        repeat(5) { i -> detector.onMetricReceived(load = 10f, timestampMs = i * 100L) }
        // Reset
        detector.reset()
        // Continue loading from time 0 -- should need full hold duration again
        repeat(10) { i -> detector.onMetricReceived(load = 10f, timestampMs = i * 100L) }

        // Should NOT have emitted Started because after reset, the hold only went 1000ms (10*100), not 1500ms
        assertFalse(
            events.any {
                it is MotionStartEvent.Started
            },
            "Should need full hold duration after reset",
        )
        job.cancel()
    }
}

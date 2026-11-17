package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #1400 (F2), driven through the real RepCounterFromMachine -> ActiveSessionEngine
 * rep-notification and monitor-sample handlers:
 *
 * (a) warmup completes in its own notification and a monitor sample follows: rep 1 starts at
 *     the warmup mark and counts towards set aggregates.
 * (b) warmup is force-completed in the same notification as working rep 1 (coalesced): rep 1
 *     starts at the last observed warmup rep event, never at set start, and stays out of set
 *     aggregates; with no warmup event at all the boundary is unknown and rep 1 is excluded.
 * (c) warmup disabled: rep 1 keeps the whole-set window and counts.
 * (d) Old School ramp rep below 90 % of the commanded load: counted but excluded.
 *
 * Rep counts are unchanged in every path.
 */
class Issue1400Rep1BoundaryTest {

    private var notificationOffsetMs = 0L

    private fun metric(timestamp: Long, load: Float, velocity: Double) = WorkoutMetric(
        timestamp = timestamp,
        loadA = load,
        loadB = load,
        positionA = if (velocity > 0) 300f else 120f,
        positionB = if (velocity > 0) 300f else 120f,
        velocityA = velocity,
        velocityB = velocity,
    )

    private fun newHarness(scope: TestScope) =
        DWSMTestHarness(scope, biomechanicsDispatcher = StandardTestDispatcher(scope.testScheduler))

    private suspend fun DWSMTestHarness.emit(vararg samples: WorkoutMetric) {
        samples.forEach { fakeBleRepo.emitMetric(it) }
        testScope.testScheduler.advanceUntilIdle()
    }

    private suspend fun DWSMTestHarness.notify(
        repsSetCount: Int,
        repsRomCount: Int,
        counter: Int,
        repsSetTotal: Int = 8,
    ) {
        notificationOffsetMs += 1L
        fakeBleRepo.emitRepNotification(
            modernRepPacket(
                repsSetCount = repsSetCount,
                repsSetTotal = repsSetTotal,
                timestamp = nowMs + notificationOffsetMs,
                topCounter = counter,
                completeCounter = counter,
                repsRomCount = repsRomCount,
            ),
        )
        testScope.testScheduler.advanceUntilIdle()
    }

    private suspend fun startOldSchoolSet(harness: DWSMTestHarness, warmupReps: Int = 3) {
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = warmupReps,
                weightPerCableKg = 25f,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
    }

    /** Working-rep concentric samples at [load] kg/cable and 400 mm/s, plus the eccentric return. */
    private fun workingRep(startMs: Long, load: Float = 25f) = arrayOf(
        metric(timestamp = startMs, load = load, velocity = 400.0),
        metric(timestamp = startMs + 100L, load = load, velocity = 400.0),
        metric(timestamp = startMs + 200L, load = load, velocity = 400.0),
        metric(timestamp = startMs + 300L, load = load, velocity = -200.0),
    )

    @Test
    fun `a - separate warmup sample - rep 1 starts at the warmup mark and is eligible`() = runTest {
        val harness = newHarness(this)
        startOldSchoolSet(harness)

        harness.emit(*Array(3) { metric(timestamp = 1_000L + it * 100L, load = 1f, velocity = 30.0) })
        harness.notify(repsSetCount = 0, repsRomCount = 3, counter = 3)
        harness.emit(metric(timestamp = 1_500L, load = 1f, velocity = 2.0)) // stamps the warmup mark

        harness.emit(*workingRep(startMs = 2_000L))
        harness.notify(repsSetCount = 1, repsRomCount = 3, counter = 4)
        advanceUntilIdle()

        assertEquals(1, harness.coordinator.repCount.value.workingReps)
        assertEquals(1_500L, harness.coordinator.repBoundaryTimestamps.value.first())
        val rep1 = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertEquals(400f, rep1.velocity.meanConcentricVelocityMmS, "Calibration samples must be outside rep 1")
        assertTrue(rep1.aggregateEligible)
        harness.cleanup()
    }

    @Test
    fun `b - coalesced warmup and rep 1 - starts at the last warmup event and is excluded`() = runTest {
        val harness = newHarness(this)
        startOldSchoolSet(harness)

        // Two calibration pulls observed as their own rep events...
        harness.emit(
            metric(timestamp = 1_000L, load = 1f, velocity = 30.0),
            metric(timestamp = 1_100L, load = 1f, velocity = 30.0),
        )
        harness.notify(repsSetCount = 0, repsRomCount = 1, counter = 1)
        harness.emit(
            metric(timestamp = 1_200L, load = 1f, velocity = 30.0),
            metric(timestamp = 1_300L, load = 1f, velocity = 30.0),
        )
        harness.notify(repsSetCount = 0, repsRomCount = 2, counter = 2)
        // ...then the third pull and working rep 1 arrive in ONE notification.
        harness.emit(
            metric(timestamp = 1_400L, load = 1f, velocity = 30.0),
            metric(timestamp = 1_500L, load = 1f, velocity = 30.0),
            *workingRep(startMs = 2_000L),
        )
        harness.notify(repsSetCount = 1, repsRomCount = 2, counter = 4)
        advanceUntilIdle()

        val counts = harness.coordinator.repCount.value
        assertEquals(3, counts.warmupReps, "The counter force-completes the warmup")
        assertEquals(1, counts.workingReps, "Rep 1 is still counted")
        assertEquals(
            1_300L,
            harness.coordinator.repBoundaryTimestamps.value.first(),
            "Rep 1 must start at the last observed warmup rep event, not at set start (0L)",
        )
        val rep1 = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertFalse(rep1.aggregateEligible, "Unobserved calibration pulls may still be in rep 1's window")
        assertNull(rep1.velocity.velocityLossPercent)

        // Later working reps (driven straight into the engine, as SetSummaryWorkingRepsTest does,
        // because the harness rep boundaries run on the wall clock) set and measure the baseline.
        val engine = harness.coordinator.biomechanicsEngine
        val fast = List(4) { metric(timestamp = 4_000L + it, load = 25f, velocity = 600.0) }
        val slow = List(4) { metric(timestamp = 5_000L + it, load = 25f, velocity = 450.0) }
        val rep2 = engine.processRep(repNumber = 2, concentricMetrics = fast, allRepMetrics = fast, timestamp = 4_000L)
        assertNull(rep2.velocity.velocityLossPercent, "The first eligible rep is the velocity-loss baseline")
        val rep3 = engine.processRep(repNumber = 3, concentricMetrics = slow, allRepMetrics = slow, timestamp = 5_000L)
        assertEquals(25f, assertNotNull(rep3.velocity.velocityLossPercent), 0.01f)

        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(525f, summary.avgMcvMmS, 0.01f, "Rep 1 must not enter set MCV")
        assertEquals(25f, assertNotNull(summary.totalVelocityLossPercent), 0.01f, "Rep 1 must not be the set baseline")
        assertEquals(3, summary.repResults.size, "Rep 1 stays listed for the per-rep display")
        harness.cleanup()
    }

    @Test
    fun `b - coalesced with no warmup event - boundary unknown and rep 1 is excluded`() = runTest {
        val harness = newHarness(this)
        startOldSchoolSet(harness)

        harness.emit(
            *Array(3) { metric(timestamp = 1_000L + it * 100L, load = 1f, velocity = 30.0) },
            *workingRep(startMs = 2_000L),
        )
        harness.notify(repsSetCount = 1, repsRomCount = 0, counter = 4)
        advanceUntilIdle()

        assertEquals(1, harness.coordinator.repCount.value.workingReps)
        val rep1 = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertFalse(rep1.aggregateEligible)
        assertNull(rep1.velocity.velocityLossPercent)
        assertFalse(rep1.velocity.shouldStopSet)
        harness.cleanup()
    }

    @Test
    fun `c - warmup disabled - rep 1 keeps the whole-set window and is eligible`() = runTest {
        val harness = newHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        harness.startCableSet(targetReps = 10)
        harness.repCounter.configure(
            warmupTarget = 0,
            workingTarget = 10,
            isJustLift = false,
            stopAtTop = false,
        )

        harness.emit(*workingRep(startMs = 1_000L))
        harness.notify(repsSetCount = 1, repsRomCount = 0, counter = 1, repsSetTotal = 10)
        advanceUntilIdle()

        assertEquals(1, harness.coordinator.repCount.value.workingReps)
        val rep1 = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertEquals(400f, rep1.velocity.meanConcentricVelocityMmS)
        assertTrue(rep1.aggregateEligible)
        harness.cleanup()
    }

    @Test
    fun `d - old school ramp rep below 90 percent of commanded load is counted but excluded`() = runTest {
        val harness = newHarness(this)
        startOldSchoolSet(harness)

        harness.emit(*Array(3) { metric(timestamp = 1_000L + it * 100L, load = 1f, velocity = 30.0) })
        harness.notify(repsSetCount = 0, repsRomCount = 3, counter = 3)
        harness.emit(metric(timestamp = 1_500L, load = 1f, velocity = 2.0))

        // Working rep 1 while the 25 kg/cable load is still ramping on (12 kg < 22.5 kg).
        harness.emit(*workingRep(startMs = 2_000L, load = 12f))
        harness.notify(repsSetCount = 1, repsRomCount = 3, counter = 4)
        advanceUntilIdle()
        val ramp = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertEquals(1, ramp.repNumber)
        assertFalse(ramp.aggregateEligible)
        assertEquals(1, harness.coordinator.repCount.value.workingReps, "The ramp rep is still counted")

        // The next full-load rep is the baseline, not the ramp rep.
        val engine = harness.coordinator.biomechanicsEngine
        val full = List(4) { metric(timestamp = 4_000L + it, load = 25f, velocity = 400.0) }
        val rep2 = engine.processRep(repNumber = 2, concentricMetrics = full, allRepMetrics = full, timestamp = 4_000L)
        assertNull(rep2.velocity.velocityLossPercent, "The ramp rep must not be the velocity-loss baseline")
        val summary = assertNotNull(engine.getSetSummary())
        assertEquals(400f, summary.avgMcvMmS, 0.01f)
        harness.cleanup()
    }

    @Test
    fun `d - a rack counterweight disables the ramp filter instead of excluding every rep`() = runTest {
        val harness = newHarness(this)
        startOldSchoolSet(harness)
        // A counterweight lowers the machine load below the programmed 25 kg/cable.
        harness.coordinator._workoutParameters.value =
            harness.coordinator._workoutParameters.value.copy(counterweightKg = 10f)

        harness.emit(*Array(3) { metric(timestamp = 1_000L + it * 100L, load = 1f, velocity = 30.0) })
        harness.notify(repsSetCount = 0, repsRomCount = 3, counter = 3)
        harness.emit(metric(timestamp = 1_500L, load = 1f, velocity = 2.0))
        harness.emit(*workingRep(startMs = 2_000L, load = 20f))
        harness.notify(repsSetCount = 1, repsRomCount = 3, counter = 4)
        advanceUntilIdle()

        val rep1 = assertNotNull(harness.coordinator.biomechanicsEngine.latestRepResult.value)
        assertTrue(rep1.aggregateEligible)
        harness.cleanup()
    }
}

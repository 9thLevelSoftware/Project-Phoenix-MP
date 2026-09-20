package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * AF-1 / F-022: warm-up samples must not decide a set's heaviest lift, and working rep 1's
 * biomechanics window must start where the warm-up ended.
 *
 * A warm-up rep is deliberately light, but a hard first pull against a light load can spike
 * the measured force above anything the working reps produce. That peak used to become the
 * set's `heaviestLiftKgPerCable`, and from there the max-weight PR and its 1RM estimate.
 * The same window bug fed the first working rep's mean concentric velocity - the baseline
 * for velocity-loss auto-end and for the VBT 1RM estimate - with every warm-up sample.
 */
class SetSummaryWorkingRepsTest {

    /** Timestamp of the sample that stamps the warm-up mark (see `completeWarmup`). */
    private val warmupEndTimestamp = 3_100L

    private val warmupMetrics = listOf(
        metric(timestamp = 1_000L, load = 30f, velocity = 120.0),
        metric(timestamp = 1_100L, load = 30f, velocity = -90.0),
        metric(timestamp = 1_200L, load = 28f, velocity = 110.0),
    )

    private val workingMetrics = listOf(
        metric(timestamp = 2_000L, load = 20f, velocity = 150.0),
        metric(timestamp = 2_100L, load = 20f, velocity = -120.0),
        metric(timestamp = 2_200L, load = 19f, velocity = 140.0),
        metric(timestamp = 2_300L, load = 18f, velocity = -110.0),
    )

    private fun metric(timestamp: Long, load: Float, velocity: Double) = WorkoutMetric(
        timestamp = timestamp,
        loadA = load,
        loadB = load,
        positionA = if (velocity > 0) 300f else 120f,
        positionB = if (velocity > 0) 300f else 120f,
        velocityA = velocity,
        velocityB = velocity,
    )

    private fun DWSMTestHarness.summary(
        warmupRepsCount: Int,
        warmupCompleteTimeMs: Long,
    ) = activeSessionEngine.calculateSetSummaryMetrics(
        metrics = warmupMetrics + workingMetrics,
        repCount = 5,
        fallbackWeightKg = 20f,
        configuredWeightKgPerCable = 20f,
        warmupRepsCount = warmupRepsCount,
        workingRepsCount = 5,
        warmupCompleteTimeMs = warmupCompleteTimeMs,
    )

    @Test
    fun `heaviest lift is the working peak, not a heavier warmup transient`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.summary(warmupRepsCount = 3, warmupCompleteTimeMs = 1_500L)

        assertEquals(20f, summary.heaviestLiftKgPerCable, "Warm-up samples must not set a max-weight PR")
        assertEquals(20f, summary.peakLoadKgPerCable)
        harness.cleanup()
    }

    @Test
    fun `heaviest lift uses every sample when the warmup mark was never recorded`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.summary(warmupRepsCount = 3, warmupCompleteTimeMs = 0L)

        assertEquals(30f, summary.heaviestLiftKgPerCable)
        harness.cleanup()
    }

    @Test
    fun `heaviest lift uses every sample for a set without warmup reps`() = runTest {
        val harness = DWSMTestHarness(this)

        // A set with no warm-up marks warm-up complete on its first active sample, so the
        // mark sits at set start. Filtering on it could only drop a genuine working sample.
        val summary = harness.summary(warmupRepsCount = 0, warmupCompleteTimeMs = 1_050L)

        assertEquals(30f, summary.heaviestLiftKgPerCable)
        harness.cleanup()
    }

    @Test
    fun `heaviest lift uses every sample when nothing was recorded after the warmup`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.summary(warmupRepsCount = 3, warmupCompleteTimeMs = 9_999L)

        assertEquals(30f, summary.heaviestLiftKgPerCable)
        harness.cleanup()
    }

    @Test
    fun `warmup completion seeds the rep boundary list`() = runTest {
        val harness = DWSMTestHarness(this)
        startWarmupSet(harness)

        completeWarmup(harness)

        assertTrue(harness.coordinator.repCount.value.isWarmupComplete)
        assertEquals(warmupEndTimestamp, harness.coordinator.warmupCompleteTimeMs)
        assertEquals(
            listOf(warmupEndTimestamp),
            harness.coordinator.repBoundaryTimestamps.value,
            "Working rep 1's window must start at the end of warm-up",
        )
        harness.cleanup()
    }

    @Test
    fun `working rep 1 velocity excludes warmup reps and near-zero samples`() = runTest {
        val harness = DWSMTestHarness(this, biomechanicsDispatcher = StandardTestDispatcher(testScheduler))
        startWarmupSet(harness)
        completeWarmup(harness)

        // Working rep 1: four fast concentric samples, two near-stationary samples inside
        // the dead-band, and the eccentric return.
        listOf(
            metric(timestamp = 3_200L, load = 25f, velocity = 400.0),
            metric(timestamp = 3_300L, load = 25f, velocity = 400.0),
            metric(timestamp = 3_400L, load = 25f, velocity = 400.0),
            metric(timestamp = 3_500L, load = 25f, velocity = 400.0),
            metric(timestamp = 3_600L, load = 25f, velocity = 5.0),
            metric(timestamp = 3_700L, load = 25f, velocity = 5.0),
            metric(timestamp = 3_800L, load = 25f, velocity = -200.0),
        ).forEach { harness.fakeBleRepo.emitMetric(it) }
        advanceUntilIdle()

        harness.fakeBleRepo.emitRepNotification(
            harness.modernRepPacket(
                repsSetCount = 1,
                repsSetTotal = 8,
                timestamp = harness.nowMs + 2L,
                topCounter = 4,
                completeCounter = 4,
                repsRomCount = 3,
            ),
        )
        advanceUntilIdle()

        assertEquals(1, harness.coordinator.repCount.value.workingReps)
        val result = assertNotNull(
            harness.coordinator.biomechanicsEngine.latestRepResult.value,
            "Working rep 1 should have produced a biomechanics result",
        )
        assertEquals(1, result.repNumber)
        assertEquals(
            400f,
            result.velocity.meanConcentricVelocityMmS,
            "Rep 1's MCV must come from the working rep alone, with dead-band samples excluded",
        )
        harness.cleanup()
    }

    private suspend fun startWarmupSet(harness: DWSMTestHarness) {
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 3,
                weightPerCableKg = 25f,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
    }

    /**
     * Three slow warm-up reps, then the machine reports the warm-up as complete and one more
     * sample arrives - that sample stamps [warmupEndTimestamp] as the warm-up mark.
     */
    private suspend fun completeWarmup(harness: DWSMTestHarness) {
        listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L).forEach { timestamp ->
            harness.fakeBleRepo.emitMetric(metric(timestamp = timestamp, load = 12f, velocity = 30.0))
        }
        harness.testScope.testScheduler.advanceUntilIdle()

        harness.fakeBleRepo.emitRepNotification(
            harness.modernRepPacket(
                repsSetCount = 0,
                repsSetTotal = 8,
                timestamp = harness.nowMs + 1L,
                topCounter = 3,
                completeCounter = 3,
                repsRomCount = 3,
            ),
        )
        harness.testScope.testScheduler.advanceUntilIdle()

        harness.fakeBleRepo.emitMetric(
            metric(timestamp = warmupEndTimestamp, load = 12f, velocity = 2.0),
        )
        harness.testScope.testScheduler.advanceUntilIdle()
    }
}

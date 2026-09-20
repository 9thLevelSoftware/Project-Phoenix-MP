package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        isEchoMode: Boolean = false,
    ) = activeSessionEngine.calculateSetSummaryMetrics(
        metrics = warmupMetrics + workingMetrics,
        repCount = 5,
        fallbackWeightKg = 20f,
        configuredWeightKgPerCable = 20f,
        isEchoMode = isEchoMode,
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
        assertTrue(
            summary.avgLoadKgPerCable <= summary.peakLoadKgPerCable,
            "The set's average load must not exceed its peak (${summary.avgLoadKgPerCable} > ${summary.peakLoadKgPerCable})",
        )
        harness.cleanup()
    }

    @Test
    fun `a warmup transient cannot set a phase specific max weight PR`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.summary(warmupRepsCount = 3, warmupCompleteTimeMs = 1_500L)

        // These two peaks become PostSaveWorkoutInput.peakConcentricForceKg /
        // peakEccentricForceKg -> updatePhaseSpecificPRs -> MAX_WEIGHT rows with phase
        // CONCENTRIC/ECCENTRIC, which the "% of PR" resolver prefers over COMBINED.
        assertEquals(20f, summary.peakForceConcentricA, "A warm-up transient must not set a CONCENTRIC PR")
        assertEquals(20f, summary.peakForceConcentricB)
        assertEquals(20f, summary.peakForceEccentricA, "A warm-up transient must not set an ECCENTRIC PR")
        assertEquals(20f, summary.peakForceEccentricB)
        assertTrue(summary.avgForceConcentricA <= summary.peakForceConcentricA)
        assertTrue(summary.avgForceEccentricA <= summary.peakForceEccentricA)
        harness.cleanup()
    }

    @Test
    fun `echo volume follows the working peak`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.summary(warmupRepsCount = 3, warmupCompleteTimeMs = 1_500L, isEchoMode = true)

        // Echo logs measured force as its volume weight, and totalVolumeKg is persisted.
        assertEquals(20f * 2f * 5f, summary.totalVolumeKg, "Echo volume must use the working peak")
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
        // The mark is past both 30 kg samples, so dropping the `warmupRepsCount > 0` half
        // of the gate would filter the peak down to 28 and fail this assertion (R-3/R-10).
        val summary = harness.summary(warmupRepsCount = 0, warmupCompleteTimeMs = 1_150L)

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

    @Test
    fun `a set without warmup reps leaves the rep boundary list untouched`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        harness.startCableSet(targetReps = 10)
        // Issue #222 forces every *cable* set to the 3-rep firmware calibration buffer,
        // so the zero-warmup shape (bodyweight, Just Lift) is driven through the shared
        // rep counter rather than through the workout parameters.
        harness.repCounter.configure(
            warmupTarget = 0,
            workingTarget = 10,
            isJustLift = false,
            stopAtTop = false,
        )

        listOf(1_000L, 1_100L, 1_200L).forEach { timestamp ->
            harness.fakeBleRepo.emitMetric(metric(timestamp = timestamp, load = 20f, velocity = 150.0))
        }
        advanceUntilIdle()

        assertTrue(harness.coordinator.repCount.value.isWarmupComplete, "No warm-up target means warm-up is complete")
        assertEquals(1_000L, harness.coordinator.warmupCompleteTimeMs, "The mark is still stamped for the duration")
        assertTrue(
            harness.coordinator.repBoundaryTimestamps.value.isEmpty(),
            "A set without warm-up reps must keep rep 1 on the whole-set fallback window",
        )
        harness.cleanup()
    }

    @Test
    fun `no warmup mark is recorded when the machine reports working rep 1 first`() = runTest {
        val harness = DWSMTestHarness(this)
        startWarmupSet(harness)

        listOf(1_000L, 1_500L, 2_000L).forEach { timestamp ->
            harness.fakeBleRepo.emitMetric(metric(timestamp = timestamp, load = 30f, velocity = 150.0))
        }
        advanceUntilIdle()

        // A dropped packet: the machine's first report already carries working rep 1, so
        // RepCounterFromMachine force-completes the warm-up in the same notification.
        harness.fakeBleRepo.emitRepNotification(
            harness.modernRepPacket(
                repsSetCount = 1,
                repsSetTotal = 8,
                timestamp = harness.nowMs + 1L,
                topCounter = 4,
                completeCounter = 4,
                repsRomCount = 0,
            ),
        )
        advanceUntilIdle()
        harness.fakeBleRepo.emitMetric(metric(timestamp = 2_500L, load = 5f, velocity = -20.0))
        advanceUntilIdle()

        val repCount = harness.coordinator.repCount.value
        assertEquals(3, repCount.warmupReps, "The counter force-completes the warm-up")
        assertEquals(1, repCount.workingReps)
        assertEquals(
            0L,
            harness.coordinator.warmupCompleteTimeMs,
            "A mark stamped after working rep 1 would subtract rep 1 from the max-weight PR",
        )

        // With no mark the peak covers the whole set, exactly as before this PR.
        val summary = harness.summary(
            warmupRepsCount = repCount.warmupReps,
            warmupCompleteTimeMs = harness.coordinator.warmupCompleteTimeMs,
        )
        assertEquals(30f, summary.heaviestLiftKgPerCable)
        harness.cleanup()
    }

    @Test
    fun `working rep 1 still produces a result when the warmup mark carries the rep`() = runTest {
        val harness = DWSMTestHarness(this, biomechanicsDispatcher = StandardTestDispatcher(testScheduler))
        startWarmupSet(harness)
        completeWarmup(harness, markVelocity = 300.0)

        completeWorkingRep1WithoutFurtherSamples(harness)

        val result = assertNotNull(
            harness.coordinator.biomechanicsEngine.latestRepResult.value,
            "Rep 1 must still establish the velocity-loss baseline instead of dropping out",
        )
        assertEquals(1, result.repNumber)
        assertEquals(
            300f,
            result.velocity.meanConcentricVelocityMmS,
            "The baseline must be the moving sample the window fell back to",
        )
        assertEquals(
            1,
            harness.coordinator.setRepMetrics.value.size,
            "Rep 1 must still write its quality row",
        )
        harness.cleanup()
    }

    @Test
    fun `a near-stationary warmup mark does not become rep 1's velocity baseline`() = runTest {
        val harness = DWSMTestHarness(this, biomechanicsDispatcher = StandardTestDispatcher(testScheduler))
        startWarmupSet(harness)
        completeWarmup(harness) // the mark's own sample sits inside the dead-band

        completeWorkingRep1WithoutFurtherSamples(harness)

        assertNull(
            harness.coordinator.biomechanicsEngine.latestRepResult.value,
            "A dead-band sample must not be published as rep 1's biomechanics result",
        )
        assertEquals(
            1,
            harness.coordinator.setRepMetrics.value.size,
            "The quality row is still written - only the velocity baseline is withheld",
        )

        // The baseline is therefore established by the next genuine rep, and velocity
        // loss keeps working for the rest of the set. Baselined on a 2 mm/s rep 1, every
        // later loss would coerce to 0 and auto-end would be silently off.
        val engine = harness.coordinator.biomechanicsEngine
        val fast = List(4) { metric(timestamp = 4_000L + it, load = 25f, velocity = 400.0) }
        val slow = List(4) { metric(timestamp = 5_000L + it, load = 25f, velocity = 200.0) }
        engine.processRep(repNumber = 2, concentricMetrics = fast, allRepMetrics = fast, timestamp = 4_000L)
        val third = engine.processRep(repNumber = 3, concentricMetrics = slow, allRepMetrics = slow, timestamp = 5_000L)

        assertEquals(
            50f,
            third.velocity.velocityLossPercent,
            "Velocity loss must be measured against a genuine rep, not against the warm-up mark",
        )
        harness.cleanup()
    }

    /** Rep 1's notification with no sample after the mark, so the seeded window is empty. */
    private suspend fun completeWorkingRep1WithoutFurtherSamples(harness: DWSMTestHarness) {
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
        harness.testScope.testScheduler.advanceUntilIdle()
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
    private suspend fun completeWarmup(harness: DWSMTestHarness, markVelocity: Double = 2.0) {
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
            metric(timestamp = warmupEndTimestamp, load = 12f, velocity = markVelocity),
        )
        harness.testScope.testScheduler.advanceUntilIdle()
    }
}

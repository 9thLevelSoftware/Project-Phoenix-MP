package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.CommandLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #712 / F-070: an AMRAP (or Just Lift) set has no rep target, so the only things that
 * can end it are the auto-stop paths - and every one of them is gated on warm-up completion.
 * When the machine never reports the warm-up reps the gate never opens, the set runs forever,
 * and the user's only exit is Stop Set with the warm-up display stuck at 0.
 *
 * These tests pin both halves of the contract: the healthy path still ends the set (the #712
 * regression guard), and a set whose warm-up never arrives ends after
 * [WorkoutCoordinator.AMRAP_WARMUP_FALLBACK_MS] of handles at rest - but only after the set
 * showed real movement, and only while the handles stay racked.
 *
 * Every timing here is on the SAMPLE clock ([WorkoutMetric.timestamp]), which is what the
 * fallback measures; in production it is the same wall clock the machine's samples are
 * stamped with as they are parsed.
 */
class AmrapAutoEndTest {

    // ===== (a) the healthy path still ends the set (#712 regression guard) =====

    @Test
    fun `an AMRAP set with counted warmup reps ends when the handles are released`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startAmrapSet(harness)
            val states = recordWorkoutStates(harness)
            emitMovement(harness)

            // The machine reports the three ROM warm-up reps plus two working reps.
            harness.fakeBleRepo.emitRepNotification(
                harness.modernRepPacket(
                    repsSetCount = 2,
                    repsSetTotal = 8,
                    timestamp = harness.nowMs + 1L,
                    topCounter = 5,
                    completeCounter = 5,
                    repsRomCount = 3,
                ),
            )
            advanceUntilIdle()
            assertTrue(
                harness.coordinator.repCount.value.isWarmupComplete,
                "fixture must complete the warm-up",
            )

            // The established idiom for the 2.5s position countdown: the countdown's start is
            // on the real clock, so put it in the past and let the next at-rest sample expire it.
            harness.coordinator.workoutStartTime = currentTimeMillis() - 10_000L
            harness.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L
            harness.fakeBleRepo.emitMetric(metric(timestamp = 5_000L, position = 0f))
            advanceUntilIdle()

            assertReachedSetSummary(states)
            assertEquals(SetEndReason.CABLE_RELEASED, persistedReason(harness, lease))
        } finally {
            harness.cleanup()
        }
    }

    // ===== (b) the fallback: warm-up stuck at 0 =====

    @Test
    fun `a set whose warmup never completes ends after ten seconds of handles at rest`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startAmrapSet(harness)
            val states = recordWorkoutStates(harness)
            emitMovement(harness)
            assertFalse(
                harness.coordinator.repCount.value.isWarmupComplete,
                "fixture must leave the warm-up stuck - no rep notification is ever sent",
            )

            // The handles go back on the rack at t = 5_000 and stay there.
            harness.fakeBleRepo.emitMetric(metric(timestamp = 5_000L, position = 0f))
            harness.fakeBleRepo.emitMetric(metric(timestamp = 10_000L, position = 0f))
            harness.fakeBleRepo.emitMetric(metric(timestamp = 14_999L, position = 0f))
            advanceUntilIdle()

            // One millisecond short of the window: the set is still the user's.
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            harness.fakeBleRepo.emitMetric(metric(timestamp = 15_000L, position = 0f))
            advanceUntilIdle()

            assertReachedSetSummary(states)
            // The set really is over, not merely summarised.
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Active)
            // A set whose warm-up never registered has no counted working reps, so the engine
            // persists no CompletedSet row for it - exactly as for any other zero-rep set.
            assertEquals(
                emptyList(),
                harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId),
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== (c) the fallback never ends a set that was not being worked =====

    @Test
    fun `handles at rest without any movement never auto-end the set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val lease = startAmrapSet(harness)

            // The user never grabbed the handles: nothing but rest, for four times the window.
            listOf(1_000L, 10_000L, 20_000L, 30_000L, 41_000L).forEach { timestamp ->
                harness.fakeBleRepo.emitMetric(metric(timestamp = timestamp, position = 0f))
            }
            advanceUntilIdle()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(
                emptyList(),
                harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `handles leaving the rack restart the rest window`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAmrapSet(harness)
            val states = recordWorkoutStates(harness)
            emitMovement(harness)

            // Nine seconds of rest, then one sample with the handles off the rack.
            harness.fakeBleRepo.emitMetric(metric(timestamp = 5_000L, position = 0f))
            harness.fakeBleRepo.emitMetric(metric(timestamp = 13_900L, position = 0f))
            harness.fakeBleRepo.emitMetric(metric(timestamp = 14_000L, position = 600f))
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            // The window restarts from t = 14_100, so the old deadline must not fire.
            harness.fakeBleRepo.emitMetric(metric(timestamp = 14_100L, position = 0f))
            harness.fakeBleRepo.emitMetric(metric(timestamp = 24_099L, position = 0f))
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            harness.fakeBleRepo.emitMetric(metric(timestamp = 24_100L, position = 0f))
            advanceUntilIdle()
            assertReachedSetSummary(states)
        } finally {
            harness.cleanup()
        }
    }

    // ===== the one "unlimited reps" predicate =====

    @Test
    fun `the packet sentinel and the params predicate agree`() {
        val amrap = cableParams(isAmrap = true)
        val justLift = cableParams(isJustLift = true)
        val fixed = cableParams()

        assertTrue(amrap.usesUnlimitedRepTarget)
        assertTrue(justLift.usesUnlimitedRepTarget)
        assertFalse(fixed.usesUnlimitedRepTarget)

        listOf(amrap, justLift).forEach { params ->
            assertEquals(
                0xFF.toByte(),
                programFrame(params)[0x04],
                "an unlimited-rep set must carry the firmware's 0xFF sentinel",
            )
        }
        assertEquals(
            (fixed.reps + fixed.warmupReps).toByte(),
            programFrame(fixed)[0x04],
            "a fixed-rep set must carry its finite total",
        )
    }

    @Test
    fun `the execution lease agrees with the params predicate`() = runTest {
        listOf(
            cableParams(isAmrap = true) to true,
            cableParams(isJustLift = true) to true,
            cableParams() to false,
        ).forEach { (params, expected) ->
            val harness = DWSMTestHarness(this)
            try {
                harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
                harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
                harness.dwsm.updateWorkoutParameters(params)
                harness.dwsm.startWorkout(skipCountdown = true)
                advanceUntilIdle()

                assertEquals(
                    expected,
                    harness.activeSessionEngine.currentExecutionLeaseForTest().usesUnlimitedRepTarget,
                    "lease must match usesUnlimitedRepTarget for $params",
                )
                assertEquals(expected, params.usesUnlimitedRepTarget)
            } finally {
                harness.cleanup()
            }
        }
    }

    // ===== fixtures =====

    /**
     * A standalone set (no routine loaded) auto-advances from its summary to
     * [WorkoutState.Completed] as soon as the summary countdown runs, so the summary has to be
     * recorded as it happens rather than read off the state flow afterwards.
     */
    private fun TestScope.recordWorkoutStates(harness: DWSMTestHarness): List<WorkoutState> {
        val states = mutableListOf<WorkoutState>()
        backgroundScope.launch { harness.coordinator.workoutState.collect { states.add(it) } }
        return states
    }

    private fun assertReachedSetSummary(states: List<WorkoutState>) {
        assertTrue(
            states.any { it is WorkoutState.SetSummary },
            "the set must end by itself and show its summary; saw $states",
        )
    }

    private fun cableParams(
        isAmrap: Boolean = false,
        isJustLift: Boolean = false,
    ) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 8,
        warmupReps = 3,
        weightPerCableKg = 25f,
        stallDetectionEnabled = false,
        isAMRAP = isAmrap,
        isJustLift = isJustLift,
        selectedExerciseId = TestFixtures.benchPress.id,
    )

    private fun programFrame(params: WorkoutParameters) = BlePacketFactory.createProgramParams(
        params = params,
        maxWeightPerCableKg = CommandLimits.TRAINER_PLUS_MAX_WEIGHT_PER_CABLE_KG,
    )

    /** An AMRAP cable set with a three-rep warm-up target the machine has not reported yet. */
    private suspend fun startAmrapSet(harness: DWSMTestHarness): ExecutionLease {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        harness.dwsm.updateWorkoutParameters(cableParams(isAmrap = true))
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    /** Handles off the rack through a range wider than MIN_RANGE_THRESHOLD. */
    private suspend fun emitMovement(harness: DWSMTestHarness) {
        listOf(600f, 700f, 620f).forEachIndexed { index, position ->
            harness.fakeBleRepo.emitMetric(
                metric(timestamp = 1_000L + index * 100L, position = position, velocity = 200.0),
            )
        }
        harness.testScope.testScheduler.advanceUntilIdle()
        assertTrue(
            harness.repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD),
            "fixture must produce a meaningful position range",
        )
    }

    private fun metric(
        timestamp: Long,
        position: Float,
        velocity: Double = 0.0,
    ) = WorkoutMetric(
        timestamp = timestamp,
        positionA = position,
        positionB = position,
        velocityA = velocity,
        velocityB = velocity,
        loadA = 25f,
        loadB = 25f,
    )

    private suspend fun persistedReason(
        harness: DWSMTestHarness,
        lease: ExecutionLease,
    ): SetEndReason = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single().setEndReason
}

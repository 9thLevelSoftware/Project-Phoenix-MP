package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1226 (Add Exercise missing from the final routine summary): the routine's
 * terminal set summary is presented and held for EVERY summary preference — the hold is
 * the fix's load-bearing behaviour (Automatic must not skip the terminal summary, and no
 * auto-advance scheduler may fire there). Intermediate summaries keep their per-preference
 * behaviour unchanged.
 *
 * Covers the architecture-review test matrix: terminal presentation under {-1, 0, 5},
 * intermediate invariance for the same values, the iOS manager auto-advance job not firing
 * at terminal, an explicit Complete Routine tap still routing onward, and unchanged
 * Add Exercise semantics at the held summary.
 */
internal class DWSMTerminalSummaryHoldTest {

    // ===== terminal presentation under {-1, 0, 5} =====

    @Test
    fun `terminal summary is presented and held under Automatic`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1) // Automatic: skip summary
            completeSingleSetOfTerminalRoutine(harness)

            // The terminal summary must NOT be skipped: it is the Add Exercise /
            // Complete Routine surface the report says is missing.
            val summary = assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertTrue(summary.repCount > 0)
            assertTrue(harness.dwsm.isTerminalRoutineSummary(), "terminal summary must offer Add Exercise")

            // Held: no scheduler may advance it.
            advanceTimeBy(60_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal summary is presented and held under a timed countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(5) // timed: auto-advance after 5s
            completeSingleSetOfTerminalRoutine(harness)

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertTrue(harness.dwsm.isTerminalRoutineSummary())

            // The countdown must not fire at terminal (engine delay and manager job inert).
            advanceTimeBy(60_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(
                harness.coordinator.workoutState.value,
                "the terminal summary must hold past the countdown",
            )
            assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal summary behaviour under Manual is unchanged`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual: hold until the user acts
            completeSingleSetOfTerminalRoutine(harness)

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertTrue(harness.dwsm.isTerminalRoutineSummary())

            advanceTimeBy(60_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        } finally {
            harness.cleanup()
        }
    }

    // ===== intermediate invariance for {-1, 0, 5} =====

    @Test
    fun `intermediate summaries still skip under Automatic`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            completeFirstSetOfMultiSetRoutine(harness)

            advanceTimeBy(1_000)
            runCurrent()
            val state = harness.coordinator.workoutState.value
            assertFalse(
                state is WorkoutState.SetSummary,
                "intermediate summaries must still be skipped under Automatic, got $state",
            )
            assertFalse(harness.dwsm.isTerminalRoutineSummary())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `intermediate summaries still auto-advance under a timed countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(5)
            completeFirstSetOfMultiSetRoutine(harness)

            // Presented first...
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            // ...then auto-advanced after the countdown (unchanged).
            advanceTimeBy(6_000)
            runCurrent()
            val state = harness.coordinator.workoutState.value
            assertFalse(
                state is WorkoutState.SetSummary,
                "intermediate summaries must still auto-advance, got $state",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `intermediate summaries still hold under Manual`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            completeFirstSetOfMultiSetRoutine(harness)

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            advanceTimeBy(60_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    // ===== explicit Complete Routine still routes onward =====

    @Test
    fun `explicit Complete Routine proceeds from the held terminal summary`() = runTest {
        for (summarySeconds in listOf(-1, 5, 0)) {
            val harness = DWSMTestHarness(this)
            try {
                harness.setActiveSummaryCountdownSeconds(summarySeconds)
                completeSingleSetOfTerminalRoutine(harness)
                assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
                val extra = extraExercise(harness)

                // The user's Complete Routine tap is the no-args proceedFromSummary.
                harness.dwsm.proceedFromSummary()
                advanceUntilIdle()
                drainRestIfPresent(harness)

                assertIs<RoutineFlowState.Complete>(
                    harness.coordinator.routineFlowState.value,
                    "summaryCountdownSeconds=$summarySeconds: Complete Routine must route onward",
                )
                assertFalse(harness.dwsm.isTerminalRoutineSummary(), "a completed flow is not a terminal summary")
                assertFalse(
                    harness.dwsm.appendExerciseToActiveSession(extra),
                    "post-Complete SESSION_APPEND must stay refused",
                )
            } finally {
                harness.cleanup()
            }
        }
    }

    // ===== iOS manager auto-advance job (scheduler #3) =====

    @Test
    fun `iOS manager auto-advance job is not armed at the terminal summary`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 2, setsPerExercise = 2)

            // Terminal summary: the manager job must stay inert for every timed value.
            installSummaryState(harness, routine, exerciseIndex = 1, setIndex = 1)
            assertFalse(
                harness.dwsm.shouldAutoAdvanceSummaryInManager(5, isIos = true),
                "the manager job must not fire at a terminal summary",
            )
            assertFalse(harness.dwsm.shouldAutoAdvanceSummaryInManager(30, isIos = true))

            // Intermediate summary on iOS: still armed (unchanged).
            installSummaryState(harness, routine, exerciseIndex = 0, setIndex = 0)
            assertTrue(
                harness.dwsm.shouldAutoAdvanceSummaryInManager(5, isIos = true),
                "the manager job must still fire at intermediate summaries",
            )
            // Manual/Automatic values never arm the job.
            assertFalse(harness.dwsm.shouldAutoAdvanceSummaryInManager(0, isIos = true))
            assertFalse(harness.dwsm.shouldAutoAdvanceSummaryInManager(-1, isIos = true))
            // Non-iOS platforms never arm the job at all.
            assertFalse(harness.dwsm.shouldAutoAdvanceSummaryInManager(5, isIos = false))

            // Behavioural: with the iOS override armed, the terminal summary still holds.
            harness.dwsm.isIosPlatformOverrideForTest = true
            harness.setActiveSummaryCountdownSeconds(5)
            completeSingleSetOfTerminalRoutine(harness)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            advanceTimeBy(60_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(
                harness.coordinator.workoutState.value,
                "the iOS manager job must not fire at the terminal summary",
            )
            assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
        } finally {
            harness.cleanup()
        }
    }

    // ===== Add Exercise semantics unchanged at the held summary =====

    @Test
    fun `Add Exercise at the held terminal summary stays session-only`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1) // held under Automatic now
            val routine = completeSingleSetOfTerminalRoutine(harness)

            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertTrue(harness.dwsm.isTerminalRoutineSummary(), "the held summary must offer Add Exercise")

            assertTrue(harness.dwsm.appendExerciseToActiveSession(extraExercise(harness)), "append must be accepted")
            val loaded = assertNotNull(harness.coordinator.loadedRoutine.value)
            assertEquals(2, loaded.exercises.size, "the session routine gains the extra")
            assertEquals(
                1,
                assertNotNull(harness.fakeWorkoutRepo.getRoutineById(routine.id)).exercises.size,
                "the saved routine template must stay unchanged",
            )
            assertFalse(
                harness.dwsm.isTerminalRoutineSummary(),
                "a summary with a successor must not offer Add Exercise again",
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== helpers =====

    /**
     * Load a real one-exercise saved routine (a one-exercise saved routine is in scope for
     * the hold), run its single set, and complete it — the routine's terminal step. Stops
     * after one virtual second so a timed summary's own countdown cannot fire mid-test.
     */
    private suspend fun TestScope.completeSingleSetOfTerminalRoutine(harness: DWSMTestHarness): Routine {
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
        startAndCompleteSet(harness, routine, exerciseIndex = 0, setIndex = 0)
        return routine
    }

    /** Load a 2x2 routine and complete its FIRST set — a non-terminal step. */
    private suspend fun TestScope.completeFirstSetOfMultiSetRoutine(harness: DWSMTestHarness): Routine {
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 2, setsPerExercise = 2, weightKg = 25f)
        startAndCompleteSet(harness, routine, exerciseIndex = 0, setIndex = 0)
        return routine
    }

    private suspend fun TestScope.startAndCompleteSet(
        harness: DWSMTestHarness,
        routine: Routine,
        exerciseIndex: Int,
        setIndex: Int,
    ) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.fakeWorkoutRepo.addRoutine(routine)
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(exerciseIndex, setIndex)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true,
        )
        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        // One virtual second lets the completion job settle without firing a 5s countdown.
        advanceTimeBy(1_000)
        runCurrent()
    }

    /** Directly install a SetSummary at the given routine coordinates (no engine run). */
    private fun installSummaryState(
        harness: DWSMTestHarness,
        routine: Routine,
        exerciseIndex: Int,
        setIndex: Int,
    ) {
        harness.coordinator._loadedRoutine.value = routine
        harness.coordinator._currentExerciseIndex.value = exerciseIndex
        harness.coordinator._currentSetIndex.value = setIndex
        harness.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 0f,
            avgLoadKgPerCable = 0f,
            repCount = 8,
        )
    }

    /** Consume any rest presentation after a proceed so the flow can reach completion. */
    private suspend fun TestScope.drainRestIfPresent(harness: DWSMTestHarness) {
        repeat(3) {
            runCurrent()
            if (harness.coordinator.routineFlowState.value is RoutineFlowState.Complete) return
            if (harness.coordinator.workoutState.value is WorkoutState.Resting) {
                harness.dwsm.skipRest()
            }
            advanceTimeBy(2_000)
            runCurrent()
        }
    }

    /** A sheet-shaped extra: the session append must normalize id / superset / orderIndex. */
    private fun extraExercise(harness: DWSMTestHarness): RoutineExercise {
        val template = harness.coordinator.loadedRoutine.value!!.exercises.last()
        return template.copy(
            id = "sheet-returned-id",
            supersetId = "leaked-superset",
            orderInSuperset = 3,
            orderIndex = 0,
        )
    }
}

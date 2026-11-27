package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1413 regression contract.
 *
 * Live report: with `Profile -> Set summary duration = Manual`, the Set Summary
 * holds after a completed set (correct), but every tap on its visible
 * "Next Set" button is rejected app-side with
 * `WORKOUT_PERSISTENCE ... Rest transition install failed reason=AUTHORITY_REJECTED`
 * and the screen stays frozen (51 taps on the iPad simulator / Phantom provider:
 * 1 silent successful tap + 50 rejections).
 *
 * Root cause: the explicit continue installs the rest transition and runs the
 * successor through `startNextSetOrExerciseFor`, which advances
 * `_currentExerciseIndex`/`_currentSetIndex` and then enters SetReady via
 * `startWorkoutOrSetReady(autoplay=false)` WITHOUT publishing `WorkoutState.Idle`.
 * `ActiveWorkoutScreen` only navigates to SetReady when `workoutState` leaves
 * SetSummary/Resting/Active, so the summary card stays visible; every retry tap
 * re-enters the rest-plan install with the frozen completion identity and dies in
 * `hasCompletionRestAuthority` (`_currentSetIndex` no longer matches the claimed
 * identity), logging `reason=AUTHORITY_REJECTED` forever. The sibling
 * `deferManualStart` arm already publishes Idle (Issue #572 / #1394 comment);
 * these tests pin the same publication on the two autoplay-off successor arms
 * (distinct exercise without deferManualStart, and within-entry next set).
 *
 * The tests drive the REAL production tap handler
 * (`DefaultWorkoutSessionManager.proceedFromSummary`) through
 * `handleSetCompletion -> held SetSummary -> proceedFromSummary()` with fake BLE.
 */
class Issue1413ManualSummaryContinueIdleTest {

    private fun entry(
        id: String,
        order: Int,
        exercise: Exercise,
        setReps: List<Int?> = listOf(10),
    ): RoutineExercise = RoutineExercise(
        id = id,
        // Force a cable accessory: non-cable fixtures classify as bodyweight and
        // route startWorkout to BodyweightRepEntry, which is not the reported path.
        exercise = exercise.copy(equipment = "CABLE"),
        orderIndex = order,
        setReps = setReps,
        weightPerCableKg = 25f,
        setRestSeconds = listOf(2),
    )

    private fun routine(id: String, exercises: List<RoutineExercise>): Routine =
        Routine(id = id, name = id, exercises = exercises)

    private suspend fun TestScope.startFirstSet(harness: DWSMTestHarness) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
    }

    private suspend fun TestScope.completeActiveSetToHeldSummary(harness: DWSMTestHarness) {
        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "the completed set must show its held Manual summary",
        )
    }

    @Test
    fun `manual summary continue inside a multi set entry advances to the next set readiness`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual: summary holds
            val routine = routine(
                "issue-1413-within-entry",
                listOf(entry("re-bench-multiset", 0, TestFixtures.benchPress, setReps = listOf(10, 10))),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            val programCommandsBefore = harness.fakeBleRepo.programCommands.size
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "Issue #1413: the manual continue must publish Idle so the SetReady screen can navigate",
            )
            val ready = assertIs<RoutineFlowState.SetReady>(
                harness.dwsm.coordinator.routineFlowState.value,
                "the successor must be staged at the next set readiness",
            )
            assertEquals(0, ready.exerciseIndex)
            assertEquals(1, ready.setIndex)
            assertEquals(1, harness.dwsm.coordinator._currentSetIndex.value)
            assertEquals(
                programCommandsBefore,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG before explicit Start",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `manual summary continue across a distinct exercise boundary advances to the next entry readiness`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual: summary holds
            val routine = routine(
                "issue-1413-distinct-exercise",
                listOf(
                    entry("re-bench-press", 0, TestFixtures.benchPress),
                    entry("re-bicep-curl", 1, TestFixtures.bicepCurl),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            val programCommandsBefore = harness.fakeBleRepo.programCommands.size
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "Issue #1413: the distinct-exercise manual continue must publish Idle " +
                    "so the SetReady screen can navigate",
            )
            val ready = assertIs<RoutineFlowState.SetReady>(
                harness.dwsm.coordinator.routineFlowState.value,
                "the successor must be staged at the next entry readiness",
            )
            assertEquals(1, ready.exerciseIndex)
            assertEquals(0, ready.setIndex)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                programCommandsBefore,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG before explicit Start",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `manual summary continue never logs rest transition install failures across taps`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual: summary holds
            ConnectionLogRepository.instance.clearAll()
            val routine = routine(
                "issue-1413-no-warning",
                listOf(entry("re-bench-multiset", 0, TestFixtures.benchPress, setReps = listOf(10, 10))),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            // First tap: the explicit continue.
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()
            // A later tap against whatever state remains must be a quiet no-op.
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            val failures = ConnectionLogRepository.instance.logs.value.count {
                it.message == "Rest transition install failed"
            }
            assertEquals(
                0,
                failures,
                "Issue #1413: no tap around the manual continue may log " +
                    "Rest transition install failed (AUTHORITY_REJECTED)",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `superseded completion is still rejected after the workout reaches the successor position`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual: summary holds
            val routine = routine(
                "issue-1413-stale-guard",
                listOf(entry("re-bench-multiset", 0, TestFixtures.benchPress, setReps = listOf(10, 10))),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            assertNotNull(completion.routineIdentity)

            // The workout genuinely moves to the successor set's readiness position.
            harness.dwsm.enterSetReady(0, 1)
            advanceUntilIdle()

            harness.activeSessionEngine.installInitialRestPlanForTest(completion, 60)
            assertIs<InitialRestPlanInstallResult.AuthorityRejected>(
                harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest,
                "the superseded completion must not install over the successor position",
            )
        } finally {
            harness.cleanup()
        }
    }
}

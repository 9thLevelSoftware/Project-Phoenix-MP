package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1018 (Add Exercise from the terminal set summary): terminal-summary predicate
 * ([DefaultWorkoutSessionManager.isTerminalRoutineSummary]) and the second-extra / session
 * aggregation behaviour around the session-only append.
 *
 * The predicate must use the session query (`getNextStep == null` / cached successor null),
 * never list position: a routine that ends in a superset is terminal before its last
 * flat-list entry, and a routine whose last flat entry is a superset member is NOT terminal
 * while `getNextStep` still finds a successor.
 */
internal class DWSMTerminalSummaryPredicateTest {

    // ===== T3: terminal predicate =====

    @Test
    fun `T3 non-terminal superset summary is not terminal even when current exercise is the last flat-list entry`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = supersetFirstRoutine()
            installSummaryState(harness, routine, exerciseIndex = routine.exercises.lastIndex, setIndex = 1)

            // List position says "last exercise"...
            assertEquals(
                routine.exercises.lastIndex,
                harness.coordinator.currentExerciseIndex.value,
                "fixture must place the current exercise on the last flat-list entry",
            )
            // ...but the session query still finds the standalone after the superset.
            val successor = harness.routineFlowManager.getNextStep(routine, routine.exercises.lastIndex, 1)
            assertNotNull(successor, "getNextStep must still find the standalone after the superset")
            assertFalse(
                harness.dwsm.isTerminalRoutineSummary(),
                "superset summary with a live successor must not be terminal",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `T3 terminal linear summary is terminal`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 2, setsPerExercise = 2)
            installSummaryState(harness, routine, exerciseIndex = 1, setIndex = 1)

            assertEquals(null, harness.routineFlowManager.getNextStep(routine, 1, 1))
            assertTrue(
                harness.dwsm.isTerminalRoutineSummary(),
                "last set of the last exercise must be terminal",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `T3 Just Lift temp-single non-summary and completed flow are never terminal`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
            installSummaryState(harness, routine, exerciseIndex = 0, setIndex = 0)
            assertTrue(harness.dwsm.isTerminalRoutineSummary(), "baseline: 1x1 routine summary is terminal")

            // Just Lift is not a routine summary.
            harness.coordinator._workoutParameters.value =
                harness.coordinator._workoutParameters.value.copy(isJustLift = true)
            assertFalse(harness.dwsm.isTerminalRoutineSummary(), "Just Lift must not be terminal")
            harness.coordinator._workoutParameters.value =
                harness.coordinator._workoutParameters.value.copy(isJustLift = false)

            // temp_single_ routines are out of scope.
            installSummaryState(
                harness,
                routine.copy(id = DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX + "x"),
                exerciseIndex = 0,
                setIndex = 0,
            )
            assertFalse(harness.dwsm.isTerminalRoutineSummary(), "temp_single_ routine must not be terminal")

            // Not a SetSummary at all.
            installSummaryState(harness, routine, exerciseIndex = 0, setIndex = 0)
            harness.coordinator._workoutState.value = WorkoutState.Idle
            assertFalse(harness.dwsm.isTerminalRoutineSummary(), "non-SetSummary state must not be terminal")

            // Routine flow already complete (celebration screen).
            installSummaryState(harness, routine, exerciseIndex = 0, setIndex = 0)
            harness.coordinator._routineFlowState.value = RoutineFlowState.Complete(
                routineName = routine.name,
                totalSets = 1,
                totalExercises = 1,
                totalDurationMs = 0L,
            )
            assertFalse(harness.dwsm.isTerminalRoutineSummary(), "Complete flow must not be terminal")
        } finally {
            harness.cleanup()
        }
    }

    // ===== T10: a second extra is offered only after the first extra is terminal =====

    @Test
    fun `T10 second extra is offered after the first extra reaches its own terminal summary`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
            installSummaryState(harness, routine, exerciseIndex = 0, setIndex = 0)
            assertTrue(harness.dwsm.isTerminalRoutineSummary())

            // First extra: accepted, and the summary that now has a successor is not terminal.
            assertTrue(harness.dwsm.appendExerciseToActiveSession(extraExercise(harness)), "first extra must append")
            val afterFirst = harness.coordinator.loadedRoutine.value
            assertNotNull(afterFirst)
            assertEquals(2, afterFirst.exercises.size)
            assertFalse(
                harness.dwsm.isTerminalRoutineSummary(),
                "summary with a successor must not offer Add Exercise again",
            )
            assertFalse(
                harness.dwsm.appendExerciseToActiveSession(extraExercise(harness)),
                "a second extra must be refused while the first successor exists",
            )

            // The first extra is itself running and reaches its own terminal summary.
            val extraIndex = afterFirst.exercises.lastIndex
            installSummaryState(harness, afterFirst, exerciseIndex = extraIndex, setIndex = 0)
            assertTrue(
                harness.dwsm.isTerminalRoutineSummary(),
                "the first extra's own terminal summary must offer Add Exercise again",
            )
            assertTrue(harness.dwsm.appendExerciseToActiveSession(extraExercise(harness)), "second extra must append")
            assertEquals(3, harness.coordinator.loadedRoutine.value!!.exercises.size)
        } finally {
            harness.cleanup()
        }
    }

    // ===== T11: the appended exercise's rows aggregate with the routine session =====

    @Test
    fun `T11 completed sets of an appended exercise are visible to the completion export input`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.setActiveSummaryCountdownSeconds(0) // manual summary shows the SetSummary state
            val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.fakeWorkoutRepo.addRoutine(routine)
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = com.devil.phoenixproject.domain.model.RepCount(
                warmupReps = 0,
                workingReps = 8,
                totalReps = 8,
                isWarmupComplete = true,
            )
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()
            assertIsSetSummary(harness)

            // Append one extra exercise for this session only and advance into it.
            val extra = extraExercise(harness)
            val extraExerciseId = extra.exercise.id
            assertTrue(harness.dwsm.appendExerciseToActiveSession(extra), "append must be accepted on the terminal summary")
            harness.dwsm.proceedFromSummary()
            advanceUntilIdle()
            drainRestIntoNextStep(harness)
            assertEquals(1, harness.coordinator.currentExerciseIndex.value, "proceed must enter the appended exercise")
            assertEquals(0, harness.coordinator.currentSetIndex.value)

            // Perform and complete one set of the appended exercise.
            harness.dwsm.startSetFromReady()
            advanceUntilIdle()
            val extraLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = com.devil.phoenixproject.domain.model.RepCount(
                warmupReps = 0,
                workingReps = 6,
                totalReps = 6,
                isWarmupComplete = true,
            )
            harness.activeSessionEngine.handleSetCompletion(extraLease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            runCurrent()

            // The completion export input (getSessionsForRoutineSession feeds
            // writeRoutineHealthData / exportRoutine(sessionId)) sees both sets under the
            // same routine session id, including the appended exercise's row.
            val routineSessionId = harness.coordinator.currentRoutineSessionId
            assertNotNull(routineSessionId, "the routine session id must be established for the workout")
            val rows = harness.fakeWorkoutRepo.allSessions().filter { it.routineSessionId == routineSessionId }
            assertTrue(
                rows.size >= 2,
                "expected per-set rows for both exercises, got ${rows.size} of ${harness.fakeWorkoutRepo.allSessions().size} stored rows",
            )
            assertTrue(
                rows.any { it.exerciseId == extraExerciseId },
                "the appended exercise's set must be visible to the completion export input",
            )
            assertTrue(
                rows.all { it.routineSessionId == routineSessionId },
                "every aggregated row must belong to the same routine session",
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== helpers =====

    /**
     * Routine whose display order is [superset, standalone] while the FLAT list ends with the
     * superset's last member: the standalone sits earlier in `exercises` but displays after
     * the superset. The current exercise is therefore the last flat-list entry while
     * `getNextStep` still finds the standalone after the superset.
     */
    private fun supersetFirstRoutine(): Routine {
        val supersetId = "ss-t3"
        val ssFirst = RoutineExercise(
            id = "ss-a",
            exercise = TestFixtures.benchPress,
            orderIndex = 2,
            setReps = listOf(10, 10),
            weightPerCableKg = 25f,
            supersetId = supersetId,
            orderInSuperset = 0,
        )
        val ssLast = RoutineExercise(
            id = "ss-b",
            exercise = TestFixtures.bicepCurl,
            orderIndex = 3,
            setReps = listOf(10, 10),
            weightPerCableKg = 15f,
            supersetId = supersetId,
            orderInSuperset = 1,
        )
        val trailing = RoutineExercise(
            id = "solo",
            exercise = TestFixtures.squat,
            orderIndex = 1,
            setReps = listOf(8),
            weightPerCableKg = 40f,
        )
        return Routine(
            id = "r-t3-superset-first",
            name = "Superset First",
            exercises = listOf(trailing, ssFirst, ssLast),
            supersets = listOf(
                Superset(
                    id = supersetId,
                    routineId = "r-t3-superset-first",
                    name = "SS",
                    restBetweenSeconds = 10,
                    orderIndex = 0,
                ),
            ),
        )
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

    /** Directly install a SetSummary at the given routine coordinates (no engine cache in play). */
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

    private fun assertIsSetSummary(harness: DWSMTestHarness) {
        assertTrue(
            harness.coordinator.workoutState.value is WorkoutState.SetSummary,
            "expected SetSummary, got ${harness.coordinator.workoutState.value}",
        )
    }

    /** Consume any rest presentation after a proceed so navigation lands on the next step. */
    private suspend fun TestScope.drainRestIntoNextStep(harness: DWSMTestHarness) {
        repeat(3) {
            runCurrent()
            if (harness.coordinator.currentExerciseIndex.value == 1 && harness.coordinator.currentSetIndex.value == 0) return
            if (harness.coordinator.workoutState.value is WorkoutState.Resting) {
                harness.dwsm.skipRest()
            }
            runCurrent()
        }
    }
}

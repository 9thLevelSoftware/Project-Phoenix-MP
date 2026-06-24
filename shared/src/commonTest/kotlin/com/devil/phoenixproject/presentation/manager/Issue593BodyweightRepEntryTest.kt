package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #593: Custom bodyweight+equipment routine absent from Analytics;
 * Recent Activity shows 0 reps. Reporter: KB Swings routine with KB
 * equipment load, 3 sets, reps mode (duration == null), v0.9.2 on
 * Android 17 / Pixel 9 XL. Completed routine surfaced in Home Recent
 * Activity as "0 reps · 11.0 lbs" rows but disappeared from Analytics
 * under every time filter.
 *
 * Root cause: `ActiveSessionEngine.handleSetCompletion()` only
 * prompted for bodyweight rep entry when the routine exercise had
 * `duration > 0`. Default-reps-mode routine exercises leave
 * `duration == null`, so the 30-second bodyweight fallback timer
 * auto-completed every set and `saveWorkoutSession()` persisted
 * `workingReps=0, totalReps=0`. PR #592's
 * `selectHistoryVisibleSessions` filter then excluded the entire
 * routine from Analytics, while Home Recent Activity still showed
 * the misleading "0 reps · <load>" rows.
 *
 * Fix: extend the rep-entry gate so it fires for any routine
 * bodyweight set whose reps have not been confirmed via the
 * rep-entry dialog — not only when `currentExercise.duration > 0`.
 * The recursion guard (`bodyweightCompletionVariantOverride`)
 * ensures `confirmBodyweightSetResult` -> `handleSetCompletion()`
 * falls through to save.
 *
 * Regression coverage:
 *  - Bodyweight routine with `duration == null` (KB Swings / KB load)
 *    enters `WorkoutState.BodyweightRepEntry` on first set completion
 *    instead of silently saving zero reps.
 *  - After user confirms reps, the second `handleSetCompletion()`
 *    falls through to `saveWorkoutSession()` and persists
 *    `workingReps > 0` and `totalReps > 0` rows so the routine
 *    shows up in `groupedWorkoutHistory`.
 *  - Bodyweight routine with `duration > 0` (existing PR #592 path)
 *    continues to enter the rep-entry dialog.
 *  - Cable / non-bodyweight routines are never routed into the
 *    bodyweight rep-entry dialog.
 */
class Issue593BodyweightRepEntryTest {

    /**
     * Custom KB Swings exercise: empty `equipment` means it has no
     * cable accessory, so `isBodyweight` is true even with KB load
     * added through the routine editor.
     */
    private val kbSwings = Exercise(
        name = "KB Swings",
        muscleGroup = "Full Body",
        muscleGroups = "Full Body,Glutes,Hamstrings",
        equipment = "",
        id = "kb-swing-custom-001",
        isCustom = true,
    )

    private fun kbSwingsRoutine(
        setsPerExercise: Int = 3,
        duration: Int? = null,
        weightKg: Float = 11.0f,
        repsPerSet: Int = 10,
    ): Routine = Routine(
        id = "kb-routine",
        name = "KB Swings Routine",
        exercises = listOf(
            RoutineExercise(
                id = "re-kb-0",
                exercise = kbSwings,
                orderIndex = 0,
                setReps = List(setsPerExercise) { repsPerSet },
                weightPerCableKg = weightKg,
                programMode = ProgramMode.OldSchool,
                eccentricLoad = EccentricLoad.LOAD_100,
                echoLevel = EchoLevel.HARDER,
                duration = duration,
            ),
        ),
    )

    /**
     * Regression: pre-fix the first completion of a routine-bodyweight
     * set with `duration == null` fell through to `saveWorkoutSession()`
     * with `workingReps=0`. Post-fix the same completion must enter
     * `WorkoutState.BodyweightRepEntry` so the user can enter reps.
     *
     * This test deliberately calls `handleSetCompletion()` without
     * first calling `startWorkout()`: the goal is to verify the gate
     * is purely routine-driven, so the routine is loaded but no
     * BLE/session context is set up. The gate must fire and return
     * without touching the (uninitialised) session store.
     */
    @Test
    fun `routine bodyweight set with null duration enters rep entry dialog instead of saving zero reps`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = kbSwingsRoutine(duration = null, setsPerExercise = 3)
            harness.fakeExerciseRepo.addExercise(kbSwings)
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            // The fix must prompt on the very first handleSetCompletion() call
            // for this routine-bodyweight set. The user has not yet confirmed
            // any rep count.
            assertNull(
                harness.dwsm.coordinator.bodyweightCompletionVariantOverride,
                "Rep-entry gate precondition: override must start null before any set completes",
            )

            harness.activeSessionEngine.handleSetCompletion()
            advanceUntilIdle()

            val state = harness.dwsm.coordinator.workoutState.value
            assertTrue(
                state is WorkoutState.BodyweightRepEntry,
                "Issue #593 regression: handleSetCompletion() for routine-bodyweight KB Swings " +
                    "(duration == null) must enter the rep-entry dialog. Got: $state",
            )
            assertEquals(kbSwings.id, state.exerciseKey, "Exercise key should match KB Swings")
            assertEquals(1, state.currentSet, "currentSet should be 1 on the first set")
            assertEquals(3, state.totalSets, "totalSets should reflect the routine's 3-set config")

            // Critical: no zero-rep session should have been written. The
            // gate fires before saveWorkoutSession() runs, so the analytics
            // filter cannot drop the routine. Without startWorkout(),
            // currentSessionId is null, so even if the gate had not
            // fired, saveWorkoutSession() would have early-returned.
            // We assert zero sessions all the same so the contract is
            // explicit.
            assertEquals(
                0,
                harness.fakeWorkoutRepo.allSessions().size,
                "handleSetCompletion() must not persist any session before the user enters reps",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Behavior preservation: the timed-bodyweight path (existing PR
     * #592 / pre-#593 behavior) must keep working. With `duration > 0`
     * the rep-entry dialog still fires.
     */
    @Test
    fun `routine bodyweight set with non-null duration still enters rep entry dialog`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = kbSwingsRoutine(duration = 30)
            harness.fakeExerciseRepo.addExercise(kbSwings)
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            harness.activeSessionEngine.handleSetCompletion()
            advanceUntilIdle()

            val state = harness.dwsm.coordinator.workoutState.value
            assertTrue(
                state is WorkoutState.BodyweightRepEntry,
                "Timed-bodyweight path must still enter rep-entry dialog. Got: $state",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * After `confirmBodyweightSetResult` sets the override, the second
     * `handleSetCompletion()` call must skip the dialog and fall
     * through to `saveWorkoutSession()`. This guards the recursion
     * guard introduced by the fix.
     *
     * The harness is wired to simulate a full BLE workout: connect,
     * load a routine, set up a set with a 1-second duration so the
     * timer fires within `advanceTimeBy(1_500)`, then confirm reps.
     * The pre-fix bug persisted `workingReps=0` for this exact path
     * when `duration` was null or 0; the fix ensures the entered rep
     * count survives regardless of the duration value.
     */
    @Test
    fun `confirmBodyweightSetResult lets second handleSetCompletion fall through to save with entered reps`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = kbSwingsRoutine(duration = 1, setsPerExercise = 3)
            harness.fakeExerciseRepo.addExercise(kbSwings)
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            // Drive the 1-second timer to completion so the production
            // path calls handleSetCompletion() the way the user's
            // routine does.
            advanceTimeBy(1_500)
            runCurrent()

            val first = harness.dwsm.coordinator.workoutState.value
            assertTrue(
                first is WorkoutState.BodyweightRepEntry,
                "After the bodyweight timer fires, the rep-entry dialog must show. Got: $first",
            )

            // Simulate the user entering 12 reps in the dialog. The
            // confirm path sets the override and re-enters
            // handleSetCompletion(), which should fall through to
            // saveWorkoutSession() (the recursion guard).
            val variant = com.devil.phoenixproject.domain.usecase.BodyweightVolumeCalculator
                .getDefaultVariantForExercise(kbSwings.name)
            harness.activeSessionEngine.confirmBodyweightSetResult(reps = 12, variant = variant)
            advanceUntilIdle()

            val sessions = harness.fakeWorkoutRepo.allSessions()
            assertEquals(1, sessions.size, "Exactly one session must be persisted for the entered set")
            val persisted = sessions.first()
            assertEquals(12, persisted.workingReps, "Entered rep count must be persisted as workingReps")
            assertEquals(12, persisted.totalReps, "Entered rep count must be persisted as totalReps")
            assertTrue(
                persisted.weightPerCableKg > 0f,
                "KB load (11.0 lbs) must be preserved alongside the entered reps",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Cable / non-bodyweight routines are never routed into the
     * bodyweight rep-entry dialog. The harness uses a built-in
     * cable Bench Press fixture (equipment = "BAR") so the routine
     * contains a real cable exercise.
     */
    @Test
    fun `cable routine set is never routed into bodyweight rep entry dialog`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // Build a single-cable-routine with a Bench Press that
            // uses "BAR" equipment. We construct the exercise inline
            // so the test does not depend on TestFixtures.allExercises.
            val bench = Exercise(
                name = "Bench Press (cable)",
                muscleGroup = "Chest",
                muscleGroups = "Chest,Triceps,Shoulders",
                equipment = "BAR",
                id = "cable-bench-001",
            )
            val routine = Routine(
                id = "cable-routine",
                name = "Cable Routine",
                exercises = listOf(
                    RoutineExercise(
                        id = "re-cable-0",
                        exercise = bench,
                        orderIndex = 0,
                        setReps = listOf(8),
                        weightPerCableKg = 40f,
                        programMode = ProgramMode.OldSchool,
                    ),
                ),
            )
            harness.fakeExerciseRepo.addExercise(bench)
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            // Pre-seed a non-zero rep count and a metric so the cable
            // completion path has data to persist (mirrors the existing
            // cable-handleSetCompletion tests in DWSMWorkoutLifecycleTest).
            harness.dwsm.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 8,
                totalReps = 8,
                isWarmupComplete = true,
            )
            harness.dwsm.coordinator.collectedMetrics.value = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 40f,
                    loadB = 40f,
                    positionA = 100f,
                    positionB = 100f,
                    velocityA = 50.0,
                    velocityB = 50.0,
                ),
            )

            harness.activeSessionEngine.handleSetCompletion()
            advanceUntilIdle()

            val state = harness.dwsm.coordinator.workoutState.value
            assertTrue(
                state !is WorkoutState.BodyweightRepEntry,
                "Cable routine sets must not be routed into the bodyweight rep-entry dialog. Got: $state",
            )
        } finally {
            harness.cleanup()
        }
    }
}

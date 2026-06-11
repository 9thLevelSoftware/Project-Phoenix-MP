package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Regression tests for issue #534: Weighted Vest not added to body weight for
 * Effective Load on body-weight exercises.
 *
 * Before the fix, `ActiveSessionEngine.updateActiveRackSelection` only wrote
 * `_activeRackItemIds` to the coordinator — it did NOT recompute
 * `_currentRackLoadAdjustment`. As a result, `applyBodyweightVolume` (called from
 * `confirmBodyweightSetResult` on the body-weight post-timer path) read the empty
 * default `RackLoadAdjustment()` and persisted the wrong volume.
 *
 * After the fix, `updateActiveRackSelection` recomputes the adjustment
 * synchronously from the in-memory rack item list and writes it to
 * `_currentRackLoadAdjustment` AND the `_workoutParameters` mirror fields.
 */
class BodyweightRackLoadTest {

    private fun createBodyweightPushUpRoutine(sets: Int, repsPerSet: Int, durationSeconds: Int): Routine {
        val pushUp = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "push-up-001",
        )
        return Routine(
            id = "bodyweight-routine",
            name = "Bodyweight Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "bodyweight-push-up",
                    exercise = pushUp,
                    orderIndex = 0,
                    setReps = List(sets) { repsPerSet },
                    weightPerCableKg = 0f,
                    duration = durationSeconds,
                    setRestSeconds = List(sets) { 0 },
                ),
            ),
        )
    }

    private fun vest(weightKg: Float = 3.62874f): RackItem = RackItem(
        id = "vest-8lb",
        name = "Weighted Vest 8lb",
        category = RackItemCategory.WEIGHTED_VEST,
        weightKg = weightKg,
        behavior = RackItemBehavior.ADDED_RESISTANCE,
        enabled = true,
        sortOrder = 1,
    )

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float = 0.5f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "Expected $expected ± $tolerance, got $actual",
        )
    }

    @Test
    fun `Issue 534 - vest toggled before body-weight set is included in persisted volume`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakePrefsManager.setBodyWeightKg(80f)

        val v = vest()
        harness.fakeEquipmentRackRepo.upsert(v)
        advanceUntilIdle()

        val routine = createBodyweightPushUpRoutine(sets = 3, repsPerSet = 10, durationSeconds = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        // Toggle the vest BEFORE startWorkout. This is the pre-set path; captureRackLoadSnapshot
        // also fires at startWorkout and will pick up the new selection.
        harness.dwsm.updateActiveRackSelection(listOf(v.id))

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceTimeBy(1_100)
        runCurrent()

        val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
        val decline18 = entry.variants.first { it.label == "Decline 18\"" }

        harness.dwsm.confirmBodyweightSetResult(reps = 10, variant = decline18)
        advanceTimeBy(1_000)
        runCurrent()

        // The adjustment must reflect the vest mass.
        assertFloatEquals(3.62874f, harness.dwsm.coordinator._currentRackLoadAdjustment.value.externalAddedLoadKg)
        assertEquals(0f, harness.dwsm.coordinator._currentRackLoadAdjustment.value.counterweightKg)
        assertEquals(listOf(v.id), harness.dwsm.coordinator._currentRackLoadAdjustment.value.selectedItems.map { it.id })

        // Effective per-rep weight: 80 * 0.73 + 3.62874 = 62.02874 kg
        // Volume for 10 reps: 620.2874 kg
        val summary = assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)
        assertFloatEquals(62.02874f, summary.heaviestLiftKgPerCable)
        assertFloatEquals(620.2874f, summary.totalVolumeKg, tolerance = 1.0f)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
        assertFloatEquals(62.02874f, session.heaviestLiftKg ?: 0f)
        assertFloatEquals(620.2874f, session.totalVolumeKg ?: 0f, tolerance = 1.0f)

        val completedSet = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
        assertEquals(10, completedSet.actualReps)
        assertFloatEquals(62.02874f, completedSet.actualWeightKg)

        harness.cleanup()
    }

    @Test
    fun `Issue 534 - vest toggled AFTER startWorkout on body-weight exercise is included in persisted volume`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakePrefsManager.setBodyWeightKg(80f)

        val v = vest()
        harness.fakeEquipmentRackRepo.upsert(v)
        advanceUntilIdle()

        val routine = createBodyweightPushUpRoutine(sets = 3, repsPerSet = 10, durationSeconds = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        // Start workout WITHOUT the vest toggled. The current exercise is body-weight
        // (Push Up) so the body-weight-specific recompute path in
        // ActiveSessionEngine.updateActiveRackSelection applies.
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceTimeBy(1_100)
        runCurrent()

        // Now toggle the vest on the live-set screen — this is the actual repro path.
        // With the body-weight-specific recompute, _currentRackLoadAdjustment is refreshed
        // even though the cable path is intentionally locked. applyBodyweightVolume (called
        // from confirmBodyweightSetResult) then reads the fresh adjustment and persists
        // the vest-aware volume.
        harness.dwsm.updateActiveRackSelection(listOf(v.id))
        // Capture the adjustment right after the toggle, before confirmBodyweightSetResult
        // triggers auto-advance to the next set (which would re-snapshot the empty
        // defaultRackItemIds via startWorkout → captureRackLoadSnapshot).
        val capturedAdjustment = harness.dwsm.coordinator._currentRackLoadAdjustment.value.externalAddedLoadKg

        val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
        val decline18 = entry.variants.first { it.label == "Decline 18\"" }

        harness.dwsm.confirmBodyweightSetResult(reps = 10, variant = decline18)
        advanceTimeBy(1_000)
        runCurrent()

        assertFloatEquals(3.62874f, capturedAdjustment)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
        // If the body-weight fix regresses, this will be 584.0 (vest-less).
        assertFloatEquals(620.2874f, session.totalVolumeKg ?: 0f, tolerance = 1.0f)

        harness.cleanup()
    }

    /**
     * Regression test for issue #534 RC-4: routine-driven SetReady / PreSet entry
     * (via `loadRoutine` + `enterSetReady`) must seed the rack load adjustment
     * from the exercise's `defaultRackItemIds` even when the user never explicitly
     * toggled the rack on the live-set screen.
     *
     * Before this fix, the SetReady entry path called
     * `coordinator.setActiveRackSelection(defaultRackItemIds)` (the no-precomputed-
     * adjustment overload) which left `_currentRackLoadAdjustment` at the empty
     * default. The body-weight effective load formula on `SetReadyScreen` then
     * ignored the vest even though the chip was visibly selected.
     */
    @Test
    fun `Issue 534 - vest configured as defaultRackItemIds seeds adjustment on enterSetReady`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakePrefsManager.setBodyWeightKg(80f)

        val v = vest()
        harness.fakeEquipmentRackRepo.upsert(v)
        advanceUntilIdle()

        // Create routine with vest pre-attached to the exercise as the default selection.
        val pushUp = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "push-up-001",
        )
        val routine = Routine(
            id = "bodyweight-routine",
            name = "Bodyweight Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "bodyweight-push-up",
                    exercise = pushUp,
                    orderIndex = 0,
                    setReps = listOf(10, 10, 10),
                    weightPerCableKg = 0f,
                    duration = 1,
                    setRestSeconds = listOf(0, 0, 0),
                    defaultRackItemIds = listOf(v.id),
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        // The adjustment must reflect the vest mass WITHOUT an explicit toggle.
        assertFloatEquals(3.62874f, harness.dwsm.coordinator._currentRackLoadAdjustment.value.externalAddedLoadKg)
        assertEquals(0f, harness.dwsm.coordinator._currentRackLoadAdjustment.value.counterweightKg)
        assertEquals(listOf(v.id), harness.dwsm.coordinator._currentRackLoadAdjustment.value.selectedItems.map { it.id })

        // Mirror fields on workout parameters must also be populated.
        assertFloatEquals(3.62874f, harness.dwsm.coordinator._workoutParameters.value.externalAddedLoadKg)
        assertEquals(0f, harness.dwsm.coordinator._workoutParameters.value.counterweightKg)

        harness.cleanup()
    }
}

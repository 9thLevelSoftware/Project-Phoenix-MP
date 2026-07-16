package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SessionBodyweightAction
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Regression coverage for issue #600: session-scoped current bodyweight prompt. */
class DWSMSessionBodyweightTest {

    private val pushUp = Exercise(
        name = "Push Up",
        muscleGroup = "Chest",
        muscleGroups = "Chest,Triceps,Shoulders",
        equipment = "",
        id = "push-up-issue-600",
    )

    private val cablePress = Exercise(
        name = "Bench Press (cable)",
        muscleGroup = "Chest",
        muscleGroups = "Chest,Triceps,Shoulders",
        equipment = "BAR",
        id = "cable-bench-issue-600",
    )

    private fun bodyweightRoutine(id: String = "bodyweight-routine"): Routine = Routine(
        id = id,
        name = "Bodyweight Routine",
        exercises = listOf(
            RoutineExercise(
                id = "$id-push-up",
                exercise = pushUp,
                orderIndex = 0,
                setReps = listOf(10, 10),
                weightPerCableKg = 0f,
                duration = 1,
                setRestSeconds = listOf(0, 0),
            ),
        ),
    )

    private fun mixedRoutineStartingWithCable(): Routine = Routine(
        id = "mixed-routine",
        name = "Mixed Routine",
        exercises = listOf(
            RoutineExercise(
                id = "mixed-cable",
                exercise = cablePress,
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = 40f,
            ),
            RoutineExercise(
                id = "mixed-push-up",
                exercise = pushUp,
                orderIndex = 1,
                setReps = listOf(10),
                weightPerCableKg = 0f,
                duration = 1,
            ),
        ),
    )

    private fun cableRoutine(): Routine = Routine(
        id = "cable-routine",
        name = "Cable Routine",
        exercises = listOf(
            RoutineExercise(
                id = "cable-only",
                exercise = cablePress,
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = 40f,
            ),
        ),
    )

    private fun vest(weightKg: Float = 3.62874f): RackItem = RackItem(
        id = "vest-8lb-issue-600",
        name = "Weighted Vest 8lb",
        category = RackItemCategory.WEIGHTED_VEST,
        weightKg = weightKg,
        behavior = RackItemBehavior.ADDED_RESISTANCE,
        enabled = true,
        sortOrder = 1,
    )

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float = 0.5f) {
        assertTrue(abs(expected - actual) < tolerance, "Expected $expected ± $tolerance, got $actual")
    }

    private fun seedExercises(harness: DWSMTestHarness) {
        harness.fakeExerciseRepo.addExercise(pushUp)
        harness.fakeExerciseRepo.addExercise(cablePress)
    }

    @Test
    fun `bodyweight routine initializes prompt state`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertTrue(state.routineHasBodyweight)
            assertEquals(false, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
            assertNull(state.lastAction)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `mixed routine starting with cable still initializes bodyweight prompt`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.dwsm.loadRoutine(mixedRoutineStartingWithCable())
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertTrue(state.routineHasBodyweight)
            assertEquals(false, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cable routine does not initialize bodyweight prompt`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.dwsm.loadRoutine(cableRoutine())
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(false, state.routineHasBodyweight)
            assertEquals(false, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `confirm stored uses saved preference`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.setActiveBodyWeightKg(82f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            harness.dwsm.confirmSessionBodyWeight(weightKg = null, saveToProfile = false)

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(true, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
            assertEquals(SessionBodyweightAction.CONFIRMED_STORED, state.lastAction)
            assertFloatEquals(82f, harness.dwsm.resolvedBodyWeightKg())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `session edit clamps high values without changing saved preference`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.setActiveBodyWeightKg(75f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            harness.dwsm.confirmSessionBodyWeight(weightKg = 350f, saveToProfile = false)
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(SessionBodyweightAction.EDITED_FOR_SESSION, state.lastAction)
            assertFloatEquals(300f, state.sessionBodyWeightKg ?: 0f)
            assertFloatEquals(300f, harness.dwsm.resolvedBodyWeightKg())
            assertFloatEquals(75f, harness.settingsManager.userPreferences.value.bodyWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `session edit clamps low values without changing saved preference`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.setActiveBodyWeightKg(75f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            harness.dwsm.confirmSessionBodyWeight(weightKg = 5f, saveToProfile = false)
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(SessionBodyweightAction.EDITED_FOR_SESSION, state.lastAction)
            assertFloatEquals(20f, state.sessionBodyWeightKg ?: 0f)
            assertFloatEquals(75f, harness.settingsManager.userPreferences.value.bodyWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `save to profile updates preference after session override`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.setActiveBodyWeightKg(75f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            harness.dwsm.confirmSessionBodyWeight(weightKg = 91f, saveToProfile = true)
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(SessionBodyweightAction.EDITED_AND_SAVED_TO_PROFILE, state.lastAction)
            assertFloatEquals(91f, state.sessionBodyWeightKg ?: 0f)
            assertFloatEquals(91f, harness.settingsManager.userPreferences.value.bodyWeightKg)
            assertFloatEquals(91f, harness.dwsm.resolvedBodyWeightKg())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `skip preserves saved fallback semantics`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.setActiveBodyWeightKg(84f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()

            harness.dwsm.skipSessionBodyWeightPrompt()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(true, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
            assertEquals(SessionBodyweightAction.SKIPPED, state.lastAction)
            assertFloatEquals(84f, harness.dwsm.resolvedBodyWeightKg())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `new routine load clears previous session override`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.dwsm.loadRoutine(bodyweightRoutine("first-bodyweight"))
            advanceUntilIdle()
            harness.dwsm.confirmSessionBodyWeight(weightKg = 90f, saveToProfile = false)

            harness.dwsm.loadRoutine(cableRoutine())
            advanceUntilIdle()

            val state = harness.dwsm.sessionBodyweightState.value
            assertEquals(false, state.routineHasBodyweight)
            assertEquals(false, state.promptHandled)
            assertNull(state.sessionBodyWeightKg)
            assertNull(state.lastAction)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `session override feeds bodyweight rep entry`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.setActiveBodyWeightKg(80f)
            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()
            harness.dwsm.confirmSessionBodyWeight(weightKg = 90f, saveToProfile = false)
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceTimeBy(1_100)
            runCurrent()

            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
            assertFloatEquals(90f, entry.bodyWeightKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `session override feeds persisted volume with weighted vest`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            seedExercises(harness)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.setActiveBodyWeightKg(80f)
            val v = vest()
            harness.fakeEquipmentRackRepo.upsert(v)
            advanceUntilIdle()

            harness.dwsm.loadRoutine(bodyweightRoutine())
            advanceUntilIdle()
            harness.dwsm.confirmSessionBodyWeight(weightKg = 90f, saveToProfile = false)
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.updateActiveRackSelection(listOf(v.id))

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceTimeBy(1_100)
            runCurrent()

            val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
            val decline18 = entry.variants.first { it.label == "Decline 18\"" }
            harness.dwsm.confirmBodyweightSetResult(reps = 10, variant = decline18)
            advanceTimeBy(1_000)
            runCurrent()

            val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
            assertFloatEquals(69.32874f, session.heaviestLiftKg ?: 0f)
            assertFloatEquals(693.2874f, session.totalVolumeKg ?: 0f, tolerance = 1.0f)
        } finally {
            harness.cleanup()
        }
    }
}

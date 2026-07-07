package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Integration tests verifying that v0.9.0 features coexist without interfering.
 *
 * These tests verify CONFIGURATION-LEVEL coexistence: multiple features enabled
 * simultaneously don't cause constructor failures, state corruption, or preference
 * cross-contamination. Full BLE-driven behavioral tests are out of scope here —
 * they require HandleState transitions and metric ingestion that belong in
 * higher-level E2E tests.
 *
 * Uses DWSMTestHarness which wires all 22+ dependencies of ActiveSessionEngine.
 */
class ActiveSessionEngineIntegrationTest {

    @Test
    fun bodyweightAndVbtFeaturesCoexistWithoutConstructorFailure() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Enable both bodyweight volume tracking and VBT auto-end simultaneously
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                bodyWeightKg = 85f,
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 15,
            ),
        )
        advanceUntilIdle()

        // Verify both features' settings propagated through SettingsManager
        val prefs = harness.settingsManager.userPreferences.value
        assertEquals(85f, prefs.bodyWeightKg, "bodyWeightKg should be set")
        assertTrue(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss should be enabled")
        assertEquals(15, prefs.velocityLossThresholdPercent, "VBT threshold should be 15%")

        // Verify coordinator was constructed with VBT config (from DWSM construction)
        // The coordinator's biomechanicsEngine should exist and be operational
        val bioEngine = harness.coordinator.biomechanicsEngine
        // latestRepResult should be null (no reps processed yet) — not crashed
        assertEquals(null, bioEngine.latestRepResult.value, "No biomechanics result before any reps")

        harness.cleanup()
    }

    @Test
    fun autoStartRoutineAndVbtThresholdsDoNotCorruptState() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Set auto-start routine AND VBT thresholds together
        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                autoStartRoutine = true,
                autoStartCountdownSeconds = 3,
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 25,
            ),
        )
        advanceUntilIdle()

        val prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine should be enabled")
        assertEquals(3, prefs.autoStartCountdownSeconds, "countdown should be 3s")
        assertTrue(prefs.autoEndOnVelocityLoss, "VBT auto-end should be enabled")
        assertEquals(25, prefs.velocityLossThresholdPercent, "VBT threshold should be 25%")

        // Verify coordinator workout state is still Idle (no spurious transitions)
        val workoutState = harness.coordinator.workoutState.value
        assertFalse(harness.coordinator.isWorkoutActive, "No workout should be active")

        harness.cleanup()
    }

    @Test
    fun preferencesIsolationAcrossFeatures() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        // Start with defaults
        var prefs = harness.settingsManager.userPreferences.value
        assertFalse(prefs.autoStartRoutine, "autoStartRoutine should default to false")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg should default to 0")
        assertEquals(20, prefs.velocityLossThresholdPercent, "VBT threshold should default to 20")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss should default to false")
        assertFalse(prefs.autoBackupEnabled, "autoBackupEnabled should default to false")

        // Set only autoStartRoutine — other features should remain at defaults
        harness.fakePrefsManager.setAutoStartRoutine(true)
        advanceUntilIdle()

        prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine should now be true")
        assertEquals(0f, prefs.bodyWeightKg, "bodyWeightKg unchanged after setting autoStart")
        assertEquals(20, prefs.velocityLossThresholdPercent, "VBT threshold unchanged")
        assertFalse(prefs.autoEndOnVelocityLoss, "autoEndOnVelocityLoss unchanged")
        assertFalse(prefs.autoBackupEnabled, "autoBackupEnabled unchanged")

        // Set only bodyWeightKg — autoStartRoutine should remain true
        harness.fakePrefsManager.setBodyWeightKg(75f)
        advanceUntilIdle()

        prefs = harness.settingsManager.userPreferences.value
        assertTrue(prefs.autoStartRoutine, "autoStartRoutine still true")
        assertEquals(75f, prefs.bodyWeightKg, "bodyWeightKg should now be 75")

        harness.cleanup()
    }

    @Test
    fun coordinatorTracksVbtConfigurationUpdates() = runTest {
        val harness = DWSMTestHarness(this)
        advanceUntilIdle()

        assertFalse(
            harness.coordinator.autoEndOnVelocityLoss,
            "Coordinator should reflect default autoEndOnVelocityLoss = false",
        )
        assertEquals(
            20f,
            harness.coordinator.biomechanicsEngine.currentVelocityLossThresholdPercent,
            "Coordinator should reflect default VBT threshold",
        )

        harness.fakePrefsManager.setPreferences(
            UserPreferences(
                autoEndOnVelocityLoss = true,
                velocityLossThresholdPercent = 35,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            harness.coordinator.autoEndOnVelocityLoss,
            "Coordinator should update autoEndOnVelocityLoss from preferences",
        )
        assertEquals(
            35f,
            harness.coordinator.biomechanicsEngine.currentVelocityLossThresholdPercent,
            "Coordinator should update VBT threshold from preferences",
        )

        harness.cleanup()
    }

    @Test
    fun rotationCompletionOnFiveThreeOneCycleEmitsNewWeekNumber() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            val benchRoutine = harness.fakeWorkoutRepo.getRoutineById("routine-bench")
            assertNotNull(benchRoutine)
            harness.fakeExerciseRepo.addExercise(benchRoutine.exercises.first().exercise)
            assertTrue(harness.dwsm.loadRoutineFromCycleAsync(benchRoutine.id, cycle.id, dayNumber = 4))
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 5,
                totalReps = 5,
                isWarmupComplete = true,
            )
            harness.coordinator.collectedMetrics.value = listOf(
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

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertEquals(2, completionEvent.newWeekNumber)
            assertFalse(completionEvent.tmBumped)
            assertEquals(2, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun reCompletingLastDayWhenCycleAlreadyAtTargetWeekSkipsSecondRegeneration() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness, weekNumber = 2)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            val benchRoutine = harness.fakeWorkoutRepo.getRoutineById("routine-bench")
            assertNotNull(benchRoutine)
            harness.fakeExerciseRepo.addExercise(benchRoutine.exercises.first().exercise)
            assertTrue(harness.dwsm.loadRoutineFromCycleAsync(benchRoutine.id, cycle.id, dayNumber = 4))
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 5,
                totalReps = 5,
                isWarmupComplete = true,
            )
            harness.coordinator.collectedMetrics.value = listOf(
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

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertNull(completionEvent.newWeekNumber)
            assertFalse(completionEvent.tmBumped)
            assertEquals(2, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
        } finally {
            harness.cleanup()
        }
    }

    private fun seedFiveThreeOneCycle(
        harness: DWSMTestHarness,
        weekNumber: Int = 1,
    ): TrainingCycle {
        val bench = Exercise(
            id = BENCH_ID,
            name = "Bench Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = "BAR",
            oneRepMaxKg = 100f,
        )
        harness.fakeExerciseRepo.addExercise(bench)

        val routine = Routine(
            id = "routine-bench",
            name = "Bench Day",
            exercises = listOf(
                RoutineExercise(
                    id = "re-bench",
                    exercise = bench,
                    orderIndex = 0,
                    setReps = listOf(5, 5, null),
                    weightPerCableKg = 40f,
                    programMode = ProgramMode.OldSchool,
                    isAMRAP = true,
                    usePercentOfPR = true,
                    setWeightsPercentOfPR = listOf(59, 68, 77),
                ),
            ),
        )
        harness.fakeWorkoutRepo.addRoutine(routine)

        val cycle = TrainingCycle.create(
            id = "cycle-531",
            name = "5/3/1",
            weekNumber = weekNumber,
            templateId = "template_531",
            days = listOf(
                CycleDay.create(id = "day-1", cycleId = "cycle-531", dayNumber = 1, name = "Bench", routineId = routine.id),
                CycleDay.create(id = "day-2", cycleId = "cycle-531", dayNumber = 2, name = "Squat", routineId = null),
                CycleDay.create(id = "day-3", cycleId = "cycle-531", dayNumber = 3, name = "Press", routineId = null),
                CycleDay.create(id = "day-4", cycleId = "cycle-531", dayNumber = 4, name = "Deadlift", routineId = routine.id),
            ),
        )
        harness.fakeTrainingCycleRepo.addCycle(cycle)
        return cycle
    }

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
    }
}

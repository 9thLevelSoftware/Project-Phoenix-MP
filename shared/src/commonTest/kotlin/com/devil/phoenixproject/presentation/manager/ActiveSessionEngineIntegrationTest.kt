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
    fun completing_day_four_of_real_seven_day_531_cycle_emits_new_week_number() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            completeCycleWorkoutDay(
                harness = harness,
                routineId = "routine-deadlift",
                cycleId = cycle.id,
                dayNumber = 4,
                engine = harness.activeSessionEngine,
            )
            advanceUntilIdle()

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertTrue(completionEvent.isRotationComplete)
            assertEquals(2, completionEvent.newWeekNumber)
            assertFalse(completionEvent.tmBumped)
            assertEquals(2, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun first_set_of_day_four_531_cycle_does_not_advance_week() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            assertTrue(harness.dwsm.loadRoutineFromCycleAsync("routine-deadlift", cycle.id, dayNumber = 4))
            harness.testScope.advanceUntilIdle()
            completeLoadedCycleSet(
                harness = harness,
                exerciseIndex = 0,
                setIndex = 0,
                engine = harness.activeSessionEngine,
            )
            advanceUntilIdle()

            assertNull(harness.coordinator.cycleDayCompletionEvent.value)
            assertEquals(1, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
            assertEquals(cycle.id, harness.coordinator.activeCycleId)
            assertEquals(4, harness.coordinator.activeCycleDayNumber)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun consecutive_day_four_531_completions_advance_from_persisted_week() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            completeCycleWorkoutDay(
                harness = harness,
                routineId = "routine-deadlift",
                cycleId = cycle.id,
                dayNumber = 4,
                engine = harness.activeSessionEngine,
            )
            advanceUntilIdle()

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertTrue(completionEvent.isRotationComplete)
            assertEquals(2, completionEvent.newWeekNumber)
            assertFalse(completionEvent.tmBumped)
            assertEquals(2, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)

            completeCycleWorkoutDay(
                harness = harness,
                routineId = "routine-deadlift",
                cycleId = cycle.id,
                dayNumber = 4,
                engine = harness.activeSessionEngine,
            )
            advanceUntilIdle()

            val secondCompletionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(secondCompletionEvent)
            assertTrue(secondCompletionEvent.isRotationComplete)
            assertEquals(3, secondCompletionEvent.newWeekNumber)
            assertFalse(secondCompletionEvent.tmBumped)
            assertEquals(3, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun null_use_case_does_not_emit_new_week_or_tm_bumped() = runTest {
        val harness = DWSMTestHarness(this)
        var nullUseCaseEngine: ActiveSessionEngine? = null
        try {
            val cycle = seedFiveThreeOneCycle(harness)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            nullUseCaseEngine = ActiveSessionEngine(
                coordinator = harness.coordinator,
                bleRepository = harness.fakeBleRepo,
                workoutRepository = harness.fakeWorkoutRepo,
                exerciseRepository = harness.fakeExerciseRepo,
                personalRecordRepository = harness.fakePRRepo,
                repCounter = harness.repCounter,
                preferencesManager = harness.fakePrefsManager,
                gamificationManager = harness.gamificationManager,
                trainingCycleRepository = harness.fakeTrainingCycleRepo,
                completedSetRepository = harness.fakeCompletedSetRepo,
                syncTriggerManager = null,
                repMetricRepository = harness.fakeRepMetricRepo,
                biomechanicsRepository = harness.fakeBiomechanicsRepo,
                recommendWeightAdjustmentUseCase = harness.recommendWeightAdjustmentUseCase,
                equipmentRackRepository = harness.fakeEquipmentRackRepo,
                applyEquipmentRackLoadUseCase = harness.applyEquipmentRackLoadUseCase,
                settingsManager = harness.settingsManager,
                userProfileRepository = harness.fakeUserProfileRepo,
                scope = harness.workoutScope,
                regenerateFiveThreeOneUseCase = null,
                elapsedRealtimeProvider = { testScheduler.currentTime },
            )
            nullUseCaseEngine.flowDelegate = harness.activeSessionEngine.flowDelegate

            completeCycleWorkoutDay(
                harness = harness,
                routineId = "routine-deadlift",
                cycleId = cycle.id,
                dayNumber = 4,
                engine = nullUseCaseEngine,
            )
            advanceUntilIdle()

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertTrue(completionEvent.isRotationComplete)
            assertNull(completionEvent.newWeekNumber)
            assertFalse(completionEvent.tmBumped)
            assertEquals(1, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
        } finally {
            nullUseCaseEngine?.cleanup()
            harness.cleanup()
        }
    }

    @Test
    fun completing_week_four_day_four_rolls_to_week_one_and_bumps_training_maxes() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val cycle = seedFiveThreeOneCycle(harness, weekNumber = 4)
            harness.fakeTrainingCycleRepo.initializeProgress(cycle.id)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            completeCycleWorkoutDay(
                harness = harness,
                routineId = "routine-deadlift",
                cycleId = cycle.id,
                dayNumber = 4,
                engine = harness.activeSessionEngine,
            )
            advanceUntilIdle()

            val completionEvent = harness.coordinator.cycleDayCompletionEvent.value
            assertNotNull(completionEvent)
            assertTrue(completionEvent.isRotationComplete)
            assertEquals(1, completionEvent.newWeekNumber)
            assertTrue(completionEvent.tmBumped)
            assertEquals(1, harness.fakeTrainingCycleRepo.getCycleById(cycle.id)?.weekNumber)
            assertEquals(
                100f + (1.25f / 0.9f),
                harness.fakeExerciseRepo.getExerciseById(BENCH_ID)?.oneRepMaxKg,
            )
            assertEquals(
                160f + (2.5f / 0.9f),
                harness.fakeExerciseRepo.getExerciseById(DEADLIFT_ID)?.oneRepMaxKg,
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun seedFiveThreeOneCycle(
        harness: DWSMTestHarness,
        weekNumber: Int = 1,
    ): TrainingCycle {
        val bench = seededMainLift(BENCH_ID, "Bench Press", 100f)
        val squat = seededMainLift(SQUAT_ID, "Squat", 140f)
        val press = seededMainLift(SHOULDER_PRESS_ID, "Shoulder Press", 90f)
        val deadlift = seededMainLift(DEADLIFT_ID, "Conventional Deadlift", 160f)
        val inclineBench = accessoryExercise("incline", "Incline Bench Press")
        val row = accessoryExercise("row", "Bent Over Row")
        val plank = Exercise(id = "plank", name = "Plank", muscleGroup = "Core", muscleGroups = "Core", equipment = "")
        val facePull = accessoryExercise("face-pull", "Face Pull")
        val lunge = accessoryExercise("lunge", "Lunge")
        val tricep = accessoryExercise("tricep", "Overhead Tricep Extension")
        val crunch = Exercise(id = "crunch", name = "Crunch", muscleGroup = "Core", muscleGroups = "Core", equipment = "")
        val shrug = accessoryExercise("shrug", "Shrug")
        val goodMorning = accessoryExercise("good-morning", "Good Morning")

        listOf(bench, squat, press, deadlift, inclineBench, row, plank, facePull, lunge, tricep, crunch, shrug, goodMorning)
            .forEach(harness.fakeExerciseRepo::addExercise)

        val benchRoutine = Routine(
            id = "routine-bench",
            name = "Bench Day",
            exercises = orderedExercises(
                mainLiftRoutineExercise("re-bench", bench),
                accessoryRoutineExercise("re-incline", inclineBench),
                accessoryRoutineExercise("re-row", row),
                accessoryRoutineExercise("re-plank", plank, reps = listOf(null, null, null), usePercentOfPr = false, setWeightsPercentOfPR = emptyList()),
            ),
        )
        val squatRoutine = Routine(
            id = "routine-squat",
            name = "Squat Day",
            exercises = orderedExercises(
                mainLiftRoutineExercise("re-squat", squat),
                accessoryRoutineExercise("re-press-accessory", press, setWeightsPercentOfPR = listOf(65, 65, 65)),
                accessoryRoutineExercise("re-face-pull", facePull, reps = listOf(15, 15, 15), setWeightsPercentOfPR = listOf(55, 55, 55)),
                accessoryRoutineExercise("re-lunge", lunge),
            ),
        )
        val pressRoutine = Routine(
            id = "routine-press",
            name = "Press Day",
            exercises = orderedExercises(
                mainLiftRoutineExercise("re-press", press),
                accessoryRoutineExercise("re-tricep", tricep, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
                accessoryRoutineExercise("re-row-2", row),
                accessoryRoutineExercise("re-crunch", crunch, reps = listOf(15, 15, 15), usePercentOfPr = false, setWeightsPercentOfPR = emptyList()),
            ),
        )
        val deadliftRoutine = Routine(
            id = "routine-deadlift",
            name = "Deadlift Day",
            exercises = orderedExercises(
                mainLiftRoutineExercise("re-deadlift", deadlift),
                accessoryRoutineExercise("re-incline-2", inclineBench),
                accessoryRoutineExercise("re-shrug", shrug, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
                accessoryRoutineExercise("re-good-morning", goodMorning, reps = listOf(12, 12, 12), setWeightsPercentOfPR = listOf(60, 60, 60)),
            ),
        )

        listOf(benchRoutine, squatRoutine, pressRoutine, deadliftRoutine).forEach(harness.fakeWorkoutRepo::addRoutine)

        val cycle = TrainingCycle.create(
            id = "cycle-531",
            name = "5/3/1",
            weekNumber = weekNumber,
            templateId = "template_531",
            days = listOf(
                CycleDay.create(id = "day-1", cycleId = "cycle-531", dayNumber = 1, name = "Bench", routineId = benchRoutine.id),
                CycleDay.create(id = "day-2", cycleId = "cycle-531", dayNumber = 2, name = "Squat", routineId = squatRoutine.id),
                CycleDay.create(id = "day-3", cycleId = "cycle-531", dayNumber = 3, name = "Press", routineId = pressRoutine.id),
                CycleDay.create(id = "day-4", cycleId = "cycle-531", dayNumber = 4, name = "Deadlift", routineId = deadliftRoutine.id),
                CycleDay.restDay(id = "day-5", cycleId = "cycle-531", dayNumber = 5),
                CycleDay.restDay(id = "day-6", cycleId = "cycle-531", dayNumber = 6),
                CycleDay.restDay(id = "day-7", cycleId = "cycle-531", dayNumber = 7),
            ),
        )
        harness.fakeTrainingCycleRepo.addCycle(cycle)
        return cycle
    }

    private suspend fun completeCycleWorkoutDay(
        harness: DWSMTestHarness,
        routineId: String,
        cycleId: String,
        dayNumber: Int,
        engine: ActiveSessionEngine,
    ) {
        assertTrue(harness.dwsm.loadRoutineFromCycleAsync(routineId, cycleId, dayNumber))
        harness.testScope.advanceUntilIdle()

        val routine = harness.fakeWorkoutRepo.getRoutineById(routineId)
        assertNotNull(routine)
        for ((exerciseIndex, exercise) in routine.exercises.withIndex()) {
            for (setIndex in exercise.setReps.indices) {
                completeLoadedCycleSet(
                    harness = harness,
                    exerciseIndex = exerciseIndex,
                    setIndex = setIndex,
                    engine = engine,
                )
            }
        }
    }

    private suspend fun completeLoadedCycleSet(
        harness: DWSMTestHarness,
        exerciseIndex: Int,
        setIndex: Int,
        engine: ActiveSessionEngine,
    ) {
        harness.dwsm.enterSetReady(exerciseIndex, setIndex)
        harness.testScope.advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.advanceUntilIdle()

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

        engine.handleSetCompletion()
        harness.testScope.advanceUntilIdle()
    }

    private fun seededMainLift(id: String, name: String, oneRepMaxKg: Float): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = "Strength",
        muscleGroups = "Strength",
        equipment = "BAR",
        oneRepMaxKg = oneRepMaxKg,
    )

    private fun accessoryExercise(id: String, name: String): Exercise = Exercise(
        id = id,
        name = name,
        muscleGroup = "Accessory",
        muscleGroups = "Accessory",
        equipment = "BAR",
        oneRepMaxKg = 50f,
    )

    private fun mainLiftRoutineExercise(
        id: String,
        exercise: Exercise,
        setWeightsPercentOfPR: List<Int> = listOf(59, 68, 77),
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = exercise,
        orderIndex = 0,
        setReps = listOf(5, 5, null),
        weightPerCableKg = 40f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = true,
        usePercentOfPR = true,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
    )

    private fun accessoryRoutineExercise(
        id: String,
        exercise: Exercise,
        reps: List<Int?> = listOf(10, 10, 10),
        usePercentOfPr: Boolean = true,
        setWeightsPercentOfPR: List<Int> = listOf(65, 65, 65),
    ): RoutineExercise = RoutineExercise(
        id = id,
        exercise = exercise,
        orderIndex = 1,
        setReps = reps,
        weightPerCableKg = 25f,
        programMode = ProgramMode.OldSchool,
        isAMRAP = false,
        usePercentOfPR = usePercentOfPr,
        setWeightsPercentOfPR = setWeightsPercentOfPR,
    )

    private fun orderedExercises(vararg exercises: RoutineExercise): List<RoutineExercise> =
        exercises.mapIndexed { index, exercise -> exercise.copy(orderIndex = index) }

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
        const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
        const val SQUAT_ID = "UjIGHxCav-lS9B2I"
        const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"
    }
}

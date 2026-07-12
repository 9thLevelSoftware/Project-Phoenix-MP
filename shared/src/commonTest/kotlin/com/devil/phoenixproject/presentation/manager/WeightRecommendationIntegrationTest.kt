package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WeightRecommendationIntegrationTest {

    @Test
    fun setCompletionWithHighQualityPopulatesRecommendationForNextSet() = runTest {
        val harness = readyActiveRoutineHarness()

        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))

        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)

        val recommendation = harness.coordinator.weightAdjustmentRecommendation.value
        assertEquals(WeightAdjustmentDirection.INCREASE, recommendation?.direction)
        assertEquals(27.5f, recommendation?.recommendedWeightKgPerCable)
        assertEquals("bench-press-001", recommendation?.targetExerciseId)
        assertEquals(1, recommendation?.targetSetIndex)

        harness.cleanup()
    }

    @Test
    fun setCompletionUsesNextSetProgrammedWeightAsRecommendationBaseline() = runTest {
        val baseRoutine = testRoutine()
        val routine = baseRoutine.copy(
            exercises = listOf(
                baseRoutine.exercises.first().copy(
                    setWeightsPerCableKg = listOf(20f, 30f),
                    weightPerCableKg = 20f,
                ),
            ),
        )
        val harness = readyActiveRoutineHarness(routine = routine)

        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))

        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)

        val recommendation = harness.coordinator.weightAdjustmentRecommendation.value
        assertEquals(WeightAdjustmentDirection.INCREASE, recommendation?.direction)
        assertEquals(30f, recommendation?.currentWeightKgPerCable)
        assertEquals(32.5f, recommendation?.recommendedWeightKgPerCable)
        assertEquals(1, recommendation?.targetSetIndex)

        harness.cleanup()
    }

    @Test
    fun applyRecommendationUpdatesSetReadyAdjustedWeightWithoutChangingRoutineDefault() = runTest {
        val harness = readyActiveRoutineHarness(
            preferences = UserPreferences(
                weightUnit = com.devil.phoenixproject.domain.model.WeightUnit.KG,
                weightIncrement = 2.5f,
                summaryCountdownSeconds = 0,
            ),
        )
        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))
        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)
        harness.dwsm.proceedFromSummary()
        advanceUntilIdle()

        harness.dwsm.applyWeightRecommendation()
        advanceUntilIdle()

        val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        assertEquals(27.5f, setReady.adjustedWeight)
        assertEquals(27.5f, harness.coordinator.workoutParameters.value.weightPerCableKg)
        assertEquals(25f, harness.coordinator.loadedRoutine.value?.exercises?.first()?.weightPerCableKg)
        assertNull(harness.coordinator.weightAdjustmentRecommendation.value)

        harness.cleanup()
    }

    @Test
    fun dismissRecommendationClearsState() = runTest {
        val harness = readyActiveRoutineHarness()
        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))
        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)
        assertTrue(harness.coordinator.weightAdjustmentRecommendation.value != null)

        harness.dwsm.dismissWeightRecommendation()

        assertNull(harness.coordinator.weightAdjustmentRecommendation.value)
        harness.cleanup()
    }

    @Test
    fun disabledPreferenceSuppressesRecommendation() = runTest {
        val harness = readyActiveRoutineHarness(
            preferences = UserPreferences(
                weightUnit = com.devil.phoenixproject.domain.model.WeightUnit.KG,
                weightIncrement = 2.5f,
                weightSuggestionsEnabled = false,
            ),
        )
        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))

        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)

        assertNull(harness.coordinator.weightAdjustmentRecommendation.value)
        harness.cleanup()
    }

    @Test
    fun activeSetCannotApplyRecommendationMidSet() = runTest {
        val harness = readyActiveRoutineHarness()
        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))
        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)
        assertTrue(harness.coordinator.weightAdjustmentRecommendation.value != null)

        harness.coordinator._workoutState.value = WorkoutState.Active
        harness.dwsm.applyWeightRecommendation()

        assertTrue(harness.coordinator.weightAdjustmentRecommendation.value != null)
        assertEquals(25f, harness.coordinator.workoutParameters.value.weightPerCableKg)
        harness.cleanup()
    }

    @Test
    fun bodyweightRoutineSetSuppressesRecommendation() = runTest {
        val bodyweightExercise = Exercise(
            id = "push-up-001",
            name = "Push Up",
            muscleGroup = "Chest",
            equipment = "",
        )
        val routine = Routine(
            id = "bodyweight-routine",
            name = "Bodyweight Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "bodyweight-set",
                    exercise = bodyweightExercise,
                    orderIndex = 0,
                    setReps = listOf(10, 10),
                    weightPerCableKg = 0f,
                    programMode = ProgramMode.OldSchool,
                ),
            ),
        )
        val harness = readyActiveRoutineHarness(
            routine = routine,
            startSet = false,
            expectActive = false,
        )
        seedCompletedSet(harness, actualReps = 10, qualityScores = listOf(95, 92, 90, 88))

        harness.activeSessionEngine.handleSetCompletion()
        advanceTimeBy(1_000)

        assertNull(harness.coordinator.weightAdjustmentRecommendation.value)
        harness.cleanup()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.readyActiveRoutineHarness(
        routine: Routine = testRoutine(),
        preferences: UserPreferences = UserPreferences(
            weightUnit = com.devil.phoenixproject.domain.model.WeightUnit.KG,
            weightIncrement = 2.5f,
        ),
        startSet: Boolean = true,
        expectActive: Boolean = true,
    ): DWSMTestHarness {
        val harness = DWSMTestHarness(this)
        harness.setActiveProfilePreferences(preferences)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        if (startSet) {
            harness.dwsm.startSetFromReady()
            advanceUntilIdle()
        }
        if (expectActive) {
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        }
        return harness
    }

    private fun testRoutine(): Routine = Routine(
        id = "weight-suggestion-routine",
        name = "Weight Suggestion Routine",
        exercises = listOf(
            RoutineExercise(
                id = "routine-exercise-1",
                exercise = Exercise(
                    id = "bench-press-001",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                ),
                orderIndex = 0,
                setReps = listOf(10, 10),
                weightPerCableKg = 25f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    private fun seedCompletedSet(
        harness: DWSMTestHarness,
        actualReps: Int,
        qualityScores: List<Int>,
    ) {
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = actualReps,
            totalReps = actualReps,
            isWarmupComplete = true,
        )
        harness.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 25f,
                loadB = 25f,
                positionA = 120f,
                positionB = 120f,
                velocityA = 80.0,
                velocityB = 80.0,
            ),
        )
        qualityScores.forEachIndexed { index, _ ->
            harness.coordinator.repQualityScorer.scoreRep(repMetric(index + 1))
        }
    }

    private fun repMetric(repNumber: Int): RepMetricData = RepMetricData(
        repNumber = repNumber,
        isWarmup = false,
        startTimestamp = repNumber * 1000L,
        endTimestamp = repNumber * 1000L + 800L,
        durationMs = 800L,
        concentricDurationMs = 300L,
        concentricPositions = floatArrayOf(),
        concentricLoadsA = floatArrayOf(),
        concentricLoadsB = floatArrayOf(),
        concentricVelocities = floatArrayOf(200f, 201f, 199f, 200f),
        concentricTimestamps = longArrayOf(),
        eccentricDurationMs = 600L,
        eccentricPositions = floatArrayOf(),
        eccentricLoadsA = floatArrayOf(),
        eccentricLoadsB = floatArrayOf(),
        eccentricVelocities = floatArrayOf(),
        eccentricTimestamps = longArrayOf(),
        peakForceA = 25f,
        peakForceB = 25f,
        avgForceConcentricA = 25f,
        avgForceConcentricB = 25f,
        avgForceEccentricA = 24f,
        avgForceEccentricB = 24f,
        peakVelocity = 210f,
        avgVelocityConcentric = 200f,
        avgVelocityEccentric = 120f,
        rangeOfMotionMm = 500f,
        peakPowerWatts = 500f,
        avgPowerWatts = 350f,
    )
}

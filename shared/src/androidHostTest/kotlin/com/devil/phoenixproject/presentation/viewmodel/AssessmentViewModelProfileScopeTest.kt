package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCableIntent
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AssessmentViewModelProfileScopeTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    @Test
    fun acceptResult_forwardsExplicitProfileAndPreservesUnitBoundary() = runTest {
        val assessmentRepository = FakeAssessmentRepository()
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.acceptResult(profileId = "athlete-a", overrideKg = 120f)
        advanceUntilIdle()

        val saved = assessmentRepository.savedSessions.single()
        assertEquals("athlete-a", saved.profileId)
        assertEquals(120f, saved.userOverrideTotalKg)
        assertEquals(30f, saved.weightPerCableKg)
        assertIs<AssessmentStep.Complete>(viewModel.currentStep.value)
        assertEquals(
            120f,
            (viewModel.currentStep.value as AssessmentStep.Complete).finalOneRepMaxKg,
        )
    }

    @Test
    fun acceptResult_rejectsBlankProfileBeforeStartingSave() = runTest {
        val assessmentRepository = FakeAssessmentRepository()
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        assertFailsWith<IllegalArgumentException> {
            viewModel.acceptResult(profileId = " ")
        }

        assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
        assertEquals(emptyList(), assessmentRepository.attemptedSessions)
    }

    @Test
    fun ordinarySaveFailureRestoresResultsAndEmitsOneEvent() = runTest {
        val assessmentRepository = FakeAssessmentRepository().apply {
            saveSessionFailure = IllegalStateException("test failure")
        }
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.events.test {
            viewModel.acceptResult(profileId = "athlete-a")
            advanceUntilIdle()

            assertEquals(AssessmentUiEvent.SaveFailed, awaitItem())
            expectNoEvents()
            assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
            assertEquals(
                "athlete-a",
                assessmentRepository.attemptedSessions.single().profileId,
            )
            assertEquals(emptyList(), assessmentRepository.savedSessions)
        }
    }

    @Test
    fun cancellationIsNotConvertedIntoRetryOrFailureEvent() = runTest {
        val assessmentRepository = FakeAssessmentRepository().apply {
            saveSessionFailure = CancellationException("test cancellation")
        }
        val viewModel = readyViewModel(assessmentRepository)
        reachResults(viewModel)

        viewModel.events.test {
            viewModel.acceptResult(profileId = "athlete-a")
            advanceUntilIdle()

            expectNoEvents()
            assertIs<AssessmentStep.Saving>(viewModel.currentStep.value)
            assertEquals(
                "athlete-a",
                assessmentRepository.attemptedSessions.single().profileId,
            )
        }
    }

    @Test
    fun startingLoad_isExactlyTwentyTotalKgForEveryProfile() =
        runTest {
            val sharedExercises = exerciseRepository()
            val profileA = readyViewModel(FakeAssessmentRepository(), sharedExercises)
            val profileB = readyViewModel(FakeAssessmentRepository(), sharedExercises)
            advanceUntilIdle()

            profileA.selectExerciseById("bench")
            profileB.selectExerciseById("bench")
            advanceUntilIdle()

            assertEquals(
                20f,
                (profileA.currentStep.value as AssessmentStep.ProgressiveLoading)
                    .suggestedWeightKg,
            )
            assertEquals(
                20f,
                (profileB.currentStep.value as AssessmentStep.ProgressiveLoading)
                    .suggestedWeightKg,
            )
        }

    @Test
    fun singleCableAssessment_usesPerCableStartLoadCeilingAndSavedWeight() = runTest {
        val assessmentRepository = FakeAssessmentRepository()
        val singleExercises = FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "single-curl",
                    name = "Single Arm Curl",
                    muscleGroup = "Arms",
                    equipment = "HANDLES",
                    cableIntent = ExerciseCableIntent.SINGLE,
                ),
            )
        }
        val viewModel = readyViewModel(assessmentRepository, singleExercises)
        advanceUntilIdle()

        viewModel.selectExerciseById("single-curl")
        advanceUntilIdle()

        val initial = assertIs<AssessmentStep.ProgressiveLoading>(viewModel.currentStep.value)
        assertEquals(10f, initial.suggestedWeightKg)
        assertEquals(false, initial.isTotalLoad)

        // With LoadCeiling.PER_CABLE (110 kg), suggested next weight after 100 kg stays <= 110f (not 220f).
        viewModel.recordSet(100f, 0.8f)
        val next = assertIs<AssessmentStep.ProgressiveLoading>(viewModel.currentStep.value)
        assertEquals(110f, next.suggestedWeightKg)
        assertEquals(false, next.isTotalLoad)

        // Finish at high load -> acceptResult stores per-cable weight without halving.
        viewModel.recordSet(110f, 0.25f)
        val results = assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
        viewModel.acceptResult(profileId = "athlete-a", overrideKg = 115f)
        advanceUntilIdle()

        val saved = assessmentRepository.savedSessions.single()
        assertEquals(105f, saved.weightPerCableKg)
        assertEquals(230f, saved.userOverrideTotalKg)
        assertEquals(results.estimatedOneRepMaxKg * 2f, saved.estimatedOneRepMaxTotalKg)
        val result = assessmentRepository.getLatestAssessment("single-curl", "athlete-a")!!
        val points = kotlinx.serialization.json.Json.decodeFromString<List<Map<String, String>>>(result.loadVelocityData)
        assertEquals(listOf("200.0", "220.0"), points.map { it.getValue("loadKg") })
        assertEquals(115f, (viewModel.currentStep.value as AssessmentStep.Complete).finalOneRepMaxKg)
    }

    @Test
    fun startingLoadBasis_matchesSingleIndependentDualUnifiedAndUnknownExercises() = runTest {
        val configurations = listOf(
            Triple(ExerciseCableIntent.SINGLE, "HANDLES", false),
            Triple(ExerciseCableIntent.DUAL, "HANDLES", false),
            Triple(ExerciseCableIntent.DUAL, "BAR", true),
            Triple(ExerciseCableIntent.EITHER, "BAR", false),
        )
        for ((intent, equipment, expectedTotal) in configurations) {
            val exercise = Exercise(id = "test", name = "Test", muscleGroup = "Arms", equipment = equipment, cableIntent = intent)
            val exercises = FakeExerciseRepository().apply { addExercise(exercise) }
            val viewModel = readyViewModel(FakeAssessmentRepository(), exercises)
            advanceUntilIdle()
            viewModel.selectExerciseById("test")
            advanceUntilIdle()
            val step = assertIs<AssessmentStep.ProgressiveLoading>(viewModel.currentStep.value)
            assertEquals(expectedTotal, step.isTotalLoad)
            assertEquals(if (expectedTotal) 20f else 10f, step.suggestedWeightKg)
        }
    }

    private fun readyViewModel(
        assessmentRepository: FakeAssessmentRepository,
        exerciseRepository: FakeExerciseRepository = exerciseRepository(),
    ): AssessmentViewModel = AssessmentViewModel(
        exerciseRepository = exerciseRepository,
        assessmentRepository = assessmentRepository,
        assessmentEngine = AssessmentEngine(),
    )

    private fun exerciseRepository() =
        FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                    cableIntent = ExerciseCableIntent.DUAL,
                ),
            )
        }

    private suspend fun TestScope.reachResults(viewModel: AssessmentViewModel) {
        advanceUntilIdle()
        viewModel.selectExerciseById("bench")
        advanceUntilIdle()
        viewModel.recordSet(40f, 1.0f)
        viewModel.recordSet(80f, 0.25f)
        assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
    }
}

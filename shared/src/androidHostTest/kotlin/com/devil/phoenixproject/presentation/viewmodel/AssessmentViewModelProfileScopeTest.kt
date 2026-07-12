package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeAssessmentRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    fun startingLoad_isExactlyTwentyTotalKgForEveryProfileDespiteGlobalExerciseOneRm() =
        runTest {
            val sharedExercises = exerciseRepository(oneRepMaxKg = 100f)
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

    private fun readyViewModel(
        assessmentRepository: FakeAssessmentRepository,
        exerciseRepository: FakeExerciseRepository = exerciseRepository(),
    ): AssessmentViewModel = AssessmentViewModel(
        exerciseRepository = exerciseRepository,
        assessmentRepository = assessmentRepository,
        assessmentEngine = AssessmentEngine(),
    )

    private fun exerciseRepository(oneRepMaxKg: Float? = null) =
        FakeExerciseRepository().apply {
            addExercise(
                Exercise(
                    id = "bench",
                    name = "Bench Press",
                    muscleGroup = "Chest",
                    equipment = "BAR",
                    oneRepMaxKg = oneRepMaxKg,
                ),
            )
        }

    private suspend fun TestScope.reachResults(viewModel: AssessmentViewModel) {
        advanceUntilIdle()
        viewModel.selectExerciseById("bench")
        advanceUntilIdle()
        viewModel.recordSet(40f, 3, 1.0f, 1.1f)
        viewModel.recordSet(80f, 3, 0.25f, 0.30f)
        assertIs<AssessmentStep.Results>(viewModel.currentStep.value)
    }
}

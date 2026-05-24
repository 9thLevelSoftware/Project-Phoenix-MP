package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundPreviewDto
import com.devil.phoenixproject.data.sync.IntegrationPlaygroundSimulationResponse
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeExternalExerciseTemplateRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakeExternalProgramRepository
import com.devil.phoenixproject.testutil.FakeExternalRoutineRepository
import com.devil.phoenixproject.testutil.FakeIntegrationSyncCursorRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ExternalProgramsViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Test
    fun commitPreviewDoesNotUpdateDifferentProgram() = runTest {
        val programRepository = FakeExternalProgramRepository()
        val apiClient = FakePortalApiClient()
        val userProfileRepository = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-1")
        }
        val programA = ExternalProgram(
            id = "program-local-a",
            externalId = "program-a",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Program A",
            scriptText = "old A",
            profileId = "profile-1",
        )
        val programB = ExternalProgram(
            id = "program-local-b",
            externalId = "program-b",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Program B",
            scriptText = "old B",
            profileId = "profile-1",
        )
        programRepository.upsertPrograms(listOf(programA, programB))
        apiClient.playgroundSimulationResult = Result.success(
            IntegrationPlaygroundSimulationResponse(
                status = "ok",
                preview = IntegrationPlaygroundPreviewDto(
                    programExternalId = programA.externalId,
                    updatedProgramText = "new A",
                ),
            ),
        )
        val viewModel = ExternalProgramsViewModel(
            repository = programRepository,
            integrationManager = createIntegrationManager(apiClient, programRepository),
            userProfileRepository = userProfileRepository,
        )
        advanceUntilIdle()

        viewModel.simulateProgram(programA)
        advanceUntilIdle()
        viewModel.commitPreview(programB)
        advanceUntilIdle()

        assertEquals("old B", programRepository.programs.single { it.id == programB.id }.scriptText)
        assertEquals(false, programRepository.programs.single { it.id == programB.id }.needsSync)
        assertNull(viewModel.uiState.value.playgroundPreview)
    }

    private fun createIntegrationManager(
        apiClient: FakePortalApiClient,
        programRepository: FakeExternalProgramRepository,
    ) = IntegrationManager(
        apiClient = apiClient,
        activityRepository = FakeExternalActivityRepository(),
        routineRepository = FakeExternalRoutineRepository(),
        programRepository = programRepository,
        measurementRepository = FakeExternalMeasurementRepository(),
        templateRepository = FakeExternalExerciseTemplateRepository(),
        cursorRepository = FakeIntegrationSyncCursorRepository(),
    )
}

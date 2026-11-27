package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.integration.HealthBackfillManager
import com.devil.phoenixproject.data.integration.HealthBodyWeightSample
import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthIntegrationBodyWeightReader
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExternalActivityRepository
import com.devil.phoenixproject.testutil.FakeExternalExerciseTemplateRepository
import com.devil.phoenixproject.testutil.FakeExternalMeasurementRepository
import com.devil.phoenixproject.testutil.FakeExternalProgramRepository
import com.devil.phoenixproject.testutil.FakeExternalRoutineRepository
import com.devil.phoenixproject.testutil.FakeIntegrationSyncCursorRepository
import com.devil.phoenixproject.testutil.FakePortalApiClient
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.russhwolf.settings.MapSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class IntegrationsViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var externalActivityRepo: FakeExternalActivityRepository
    private lateinit var externalRoutineRepo: FakeExternalRoutineRepository
    private lateinit var externalProgramRepo: FakeExternalProgramRepository
    private lateinit var externalMeasurementRepo: FakeExternalMeasurementRepository
    private lateinit var externalTemplateRepo: FakeExternalExerciseTemplateRepository
    private lateinit var cursorRepo: FakeIntegrationSyncCursorRepository
    private lateinit var workoutRepo: FakeWorkoutRepository
    private lateinit var completedSetRepo: FakeCompletedSetRepository
    private lateinit var userProfileRepo: FakeUserProfileRepository
    private lateinit var portalApi: FakePortalApiClient
    private lateinit var portalTokenStorage: PortalTokenStorage
    private lateinit var healthIntegration: HealthIntegration
    private lateinit var viewModel: IntegrationsViewModel

    @Before
    fun setUp() {
        externalActivityRepo = FakeExternalActivityRepository()
        externalRoutineRepo = FakeExternalRoutineRepository()
        externalProgramRepo = FakeExternalProgramRepository()
        externalMeasurementRepo = FakeExternalMeasurementRepository()
        externalTemplateRepo = FakeExternalExerciseTemplateRepository()
        cursorRepo = FakeIntegrationSyncCursorRepository()
        workoutRepo = FakeWorkoutRepository()
        completedSetRepo = FakeCompletedSetRepository()
        userProfileRepo = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default")
        }
        portalApi = FakePortalApiClient()
        portalTokenStorage = PortalTokenStorage(MapSettings())
        healthIntegration = mockk(relaxed = true)

        val integrationManager = IntegrationManager(
            apiClient = portalApi,
            activityRepository = externalActivityRepo,
            routineRepository = externalRoutineRepo,
            programRepository = externalProgramRepo,
            measurementRepository = externalMeasurementRepo,
            templateRepository = externalTemplateRepo,
            cursorRepository = cursorRepo,
        )
        val backfillManager = HealthBackfillManager(
            workoutRepository = workoutRepo,
            completedSetRepository = completedSetRepo,
            cursorRepository = cursorRepo,
            healthWriter = healthIntegration,
        )
        val migrationGate = object : RequiredMigrationGate {
            override val requiredMigrationState: StateFlow<RequiredMigrationState> =
                MutableStateFlow(RequiredMigrationState.Ready)

            override suspend fun awaitRequiredMigrations() = Unit
        }
        val bodyWeightSyncManager = HealthBodyWeightSyncManager(
            bodyWeightReader = HealthIntegrationBodyWeightReader(healthIntegration),
            externalActivityRepository = externalActivityRepo,
            externalMeasurementRepository = externalMeasurementRepo,
            requiredMigrationGate = migrationGate,
            userProfileRepository = userProfileRepo,
        )

        viewModel = IntegrationsViewModel(
            externalActivityRepo = externalActivityRepo,
            externalRoutineRepo = externalRoutineRepo,
            externalProgramRepo = externalProgramRepo,
            externalMeasurementRepo = externalMeasurementRepo,
            externalTemplateRepo = externalTemplateRepo,
            integrationManager = integrationManager,
            workoutRepository = workoutRepo,
            healthIntegration = healthIntegration,
            healthBackfillManager = backfillManager,
            healthBodyWeightSyncManager = bodyWeightSyncManager,
            userProfileRepository = userProfileRepo,
            portalTokenStorage = portalTokenStorage,
        )
    }

    @Test
    fun `onHealthPermissionResult false preserves errorMessage alongside snackbar and settings events`() = runTest {
        coEvery { healthIntegration.hasPermissions() } returns false
        coEvery { healthIntegration.hasBodyWeightReadPermission() } returns false

        val events = mutableListOf<IntegrationUiEvent>()
        val collector = launch {
            viewModel.uiEvents.collect { events.add(it) }
        }
        advanceUntilIdle()

        viewModel.onHealthPermissionResult(granted = false)
        advanceUntilIdle()

        val errorMessage = viewModel.uiState.value.errorMessage
        assertNotNull(errorMessage)
        assertTrue(errorMessage.contains("Health permissions were not granted"))
        assertTrue(events.any { it is IntegrationUiEvent.Snackbar })
        assertTrue(events.any { it is IntegrationUiEvent.OpenHealthPermissionSettings })
        assertEquals(
            ConnectionStatus.ERROR,
            viewModel.uiState.value.integrationStatuses[IntegrationProvider.GOOGLE_HEALTH]?.status,
        )

        collector.cancel()
    }

    @Test
    fun `syncPreviousHealthWorkouts failure preserves errorMessage across finally`() = runTest {
        coEvery { healthIntegration.isAvailable() } returns true
        coEvery { healthIntegration.hasPermissions() } returns false

        viewModel.syncPreviousHealthWorkouts()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.operationLoading.isEmpty())
        val errorMessage = state.errorMessage
        assertNotNull(errorMessage)
        assertTrue(errorMessage.contains("Health sync failed:"))
    }

    @Test
    fun `body weight sync formats weight in active WeightUnit LB and KG`() = runTest {
        coEvery { healthIntegration.isAvailable() } returns true
        coEvery { healthIntegration.hasPermissions() } returns true
        coEvery { healthIntegration.hasBodyWeightReadPermission() } returns true
        coEvery { healthIntegration.readLatestScaleBodyWeight() } returns Result.success(
            HealthBodyWeightSample(
                weightKg = 80f,
                measuredAtMs = 1_700_000_000_000L,
                externalId = "bw-1",
            ),
        )

        userProfileRepo.mutateCore("default") { it.copy(weightUnit = WeightUnit.LB) }
        viewModel.onHealthPermissionResult(granted = true)
        advanceUntilIdle()

        val lbMessage = viewModel.uiState.value.successMessage
        assertNotNull(lbMessage)
        assertTrue(lbMessage.contains("176.4 lbs"), "Expected lbs formatting, got: $lbMessage")

        userProfileRepo.mutateCore("default") { it.copy(weightUnit = WeightUnit.KG) }
        viewModel.onHealthPermissionResult(granted = true)
        advanceUntilIdle()

        val kgMessage = viewModel.uiState.value.successMessage
        assertNotNull(kgMessage)
        assertTrue(kgMessage.contains("80 kg"), "Expected kg formatting, got: $kgMessage")
    }

    @Test
    fun `syncPreviousHealthWorkouts does not swallow CancellationException into errorMessage`() = runTest {
        coEvery { healthIntegration.isAvailable() } throws CancellationException("cancelled")

        viewModel.syncPreviousHealthWorkouts()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.operationLoading.isEmpty())
        assertNull(state.errorMessage)
    }
}

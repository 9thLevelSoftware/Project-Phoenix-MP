package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.devil.phoenixproject.data.integration.HealthBackfillManager
import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthIntegrationBodyWeightReader
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.migration.RequiredMigrationGate
import com.devil.phoenixproject.data.migration.RequiredMigrationState
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
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
import com.russhwolf.settings.MapSettings
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntegrationsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var activityRepo: FakeExternalActivityRepository
    private lateinit var measurementRepo: FakeExternalMeasurementRepository
    private lateinit var viewModel: IntegrationsViewModel

    @Before
    fun setUp() {
        activityRepo = FakeExternalActivityRepository()
        val routineRepo = FakeExternalRoutineRepository()
        val programRepo = FakeExternalProgramRepository()
        measurementRepo = FakeExternalMeasurementRepository()
        val templateRepo = FakeExternalExerciseTemplateRepository()
        val cursorRepo = FakeIntegrationSyncCursorRepository()
        val workoutRepo = FakeWorkoutRepository()
        val completedSetRepo = FakeCompletedSetRepository()
        val profileRepo = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default")
        }
        val portalApi = FakePortalApiClient()
        val portalTokenStorage = PortalTokenStorage(MapSettings())
        val healthIntegration = mockk<HealthIntegration>(relaxed = true)
        val integrationManager = IntegrationManager(
            apiClient = portalApi,
            activityRepository = activityRepo,
            routineRepository = routineRepo,
            programRepository = programRepo,
            measurementRepository = measurementRepo,
            templateRepository = templateRepo,
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
            externalActivityRepository = activityRepo,
            externalMeasurementRepository = measurementRepo,
            requiredMigrationGate = migrationGate,
            userProfileRepository = profileRepo,
        )
        viewModel = IntegrationsViewModel(
            externalActivityRepo = activityRepo,
            externalRoutineRepo = routineRepo,
            externalProgramRepo = programRepo,
            externalMeasurementRepo = measurementRepo,
            externalTemplateRepo = templateRepo,
            integrationManager = integrationManager,
            workoutRepository = workoutRepo,
            healthIntegration = healthIntegration,
            healthBackfillManager = backfillManager,
            healthBodyWeightSyncManager = bodyWeightSyncManager,
            userProfileRepository = profileRepo,
            portalTokenStorage = portalTokenStorage,
        )

        stopKoin()
        startKoin {
            modules(
                module {
                    viewModel { viewModel }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun hevyAndLiftosaurCards_hideNonActivityChipsAndSurfaceErrorSubtitlesAndLbsUnit() {
        runBlocking {
            activityRepo.updateIntegrationStatus(
                provider = IntegrationProvider.HEVY,
                status = ConnectionStatus.ERROR,
                profileId = "default",
                errorMessage = "Hevy API key expired",
            )
            activityRepo.updateIntegrationStatus(
                provider = IntegrationProvider.LIFTOSAUR,
                status = ConnectionStatus.ERROR,
                profileId = "default",
                errorMessage = "Liftosaur rate limit exceeded",
            )
            measurementRepo.upsertMeasurements(
                listOf(
                    ExternalBodyMeasurement(
                        id = "bw-lb-1",
                        provider = IntegrationProvider.GOOGLE_HEALTH,
                        externalId = "ext-bw-1",
                        measurementType = HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT,
                        value = 80.0,
                        unit = "kg",
                        measuredAt = 1_700_000_000_000L,
                        profileId = "default",
                    ),
                ),
            )
        }

        rule.setContent {
            MaterialTheme {
                IntegrationsScreen(weightUnit = WeightUnit.LB)
            }
        }
        rule.waitForIdle()

        // Health body weight badge uses "lbs" (not "lb")
        rule.onNodeWithText("Body weight: 176.4 lbs").assertIsDisplayed()
        rule.onAllNodesWithText("0 workouts").assertCountEquals(2)

        // Error subtitles render for Hevy & Liftosaur
        rule.onNodeWithText("Hevy API key expired").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Liftosaur rate limit exceeded").performScrollTo().assertIsDisplayed()

        // Non-activity chips remain gated off while EXTERNAL_NON_ACTIVITY_INTEGRATIONS_ENABLED == false
        rule.onNodeWithText("0 routines").assertDoesNotExist()
        rule.onNodeWithText("0 folders").assertDoesNotExist()
        rule.onNodeWithText("0 templates").assertDoesNotExist()
        rule.onNodeWithText("0 measurements").assertDoesNotExist()
        rule.onNodeWithText("0 programs").assertDoesNotExist()
        rule.onNodeWithText("0 current").assertDoesNotExist()
        rule.onNodeWithText("0 stats").assertDoesNotExist()
    }
}

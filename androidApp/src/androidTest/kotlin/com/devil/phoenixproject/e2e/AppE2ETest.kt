package com.devil.phoenixproject.e2e

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.devil.phoenixproject.AndroidAppHost
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.SyncRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.PortalApiClient
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.PullRoutineDto
import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto
import com.devil.phoenixproject.database.AssessmentResult
import com.devil.phoenixproject.database.ExerciseSignature
import com.devil.phoenixproject.database.PhaseStatistics
import com.devil.phoenixproject.di.appModule
import com.devil.phoenixproject.di.platformModule
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.EulaViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.testutil.FakeBiomechanicsRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeCsvExporter
import com.devil.phoenixproject.testutil.FakeCsvImporter
import com.devil.phoenixproject.testutil.FakeDataBackupManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

@RunWith(AndroidJUnit4::class)
class AppE2ETest : KoinTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var resolvedMainViewModel: MainViewModel

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            allowOverride(true)
            modules(appModule, platformModule, testModule)
        }
        fakeBleRepository = GlobalContext.get().get<BleRepository>() as FakeBleRepository
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun splashThenHomeContentAppears() {
        launchApp()

        composeRule.onNodeWithText("PROJECT PHOENIX").assertIsDisplayed()

        advancePastSplash()

        composeRule.onNodeWithText("Recent Activity").assertIsDisplayed()
        composeRule.onNodeWithText("Click to Connect").assertIsDisplayed()
        composeRule.onNodeWithText("PROJECT PHOENIX").assertDoesNotExist()
    }

    @Test
    fun bottomNavNavigatesToSettings() {
        launchApp()
        advancePastSplash()

        composeRule.onNode(hasText("Settings") and hasClickAction()).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Like My Work?").assertIsDisplayed()
    }

    @Test
    fun activeWorkoutSurvivesActivityRecreationWithoutReplayingSplash() {
        launchApp()
        advancePastSplash()
        startWorkoutAndWaitForActiveState()

        composeRule.onNodeWithText("Stop Set").assertIsDisplayed()

        val originalViewModel = resolvedMainViewModel
        val originalBleRepository = GlobalContext.get().get<BleRepository>()

        composeRule.activityRule.scenario.recreate()
        setHostContent()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("PROJECT PHOENIX").assertCountEquals(0)
        composeRule.onNodeWithText("Stop Set").assertIsDisplayed()
        assertThat(resolvedMainViewModel).isSameInstanceAs(originalViewModel)
        assertThat(resolvedMainViewModel.workoutState.value).isInstanceOf(WorkoutState.Active::class.java)
        assertThat(GlobalContext.get().get<BleRepository>()).isSameInstanceAs(originalBleRepository)
    }

    @Test
    fun androidScopedDependenciesRemainStableAcrossRecreation() {
        launchApp()
        advancePastSplash()

        val firstViewModel = resolvedMainViewModel
        val firstBleRepository = GlobalContext.get().get<BleRepository>()
        val secondBleRepository = GlobalContext.get().get<BleRepository>()

        assertThat(firstBleRepository).isSameInstanceAs(secondBleRepository)

        composeRule.activityRule.scenario.recreate()
        setHostContent()
        composeRule.waitForIdle()

        assertThat(resolvedMainViewModel).isSameInstanceAs(firstViewModel)
    }

    private fun launchApp() {
        composeRule.mainClock.autoAdvance = false
        setHostContent()
    }

    private fun advancePastSplash() {
        composeRule.mainClock.advanceTimeBy(SPLASH_DURATION_MS)
        composeRule.waitForIdle()
    }

    private fun setHostContent() {
        composeRule.setContent {
            val mainViewModel: MainViewModel = org.koin.compose.viewmodel.koinActivityViewModel()
            SideEffect {
                resolvedMainViewModel = mainViewModel
            }
            AndroidAppHost()
        }
        composeRule.waitForIdle()
    }

    private fun startWorkoutAndWaitForActiveState() {
        composeRule.runOnIdle {
            resolvedMainViewModel.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 2,
                    warmupReps = 0,
                    weightPerCableKg = 20f,
                ),
            )
        }

        runBlocking {
            fakeBleRepository.emitMetric(
                WorkoutMetric(
                    positionA = 100f,
                    positionB = 100f,
                    loadA = 10f,
                    loadB = 10f,
                ),
            )
        }

        composeRule.runOnIdle {
            resolvedMainViewModel.startWorkout(skipCountdown = true)
        }

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            resolvedMainViewModel.workoutState.value is WorkoutState.Active
        }
    }

    private companion object {
        const val SPLASH_DURATION_MS = 3000L
    }
}

private val testModule = module {
    single<Settings> { testSettings }
    single<PreferencesManager> { FakePreferencesManager() }
    single<BleRepository> { FakeBleRepository() }
    single<WorkoutRepository> { FakeWorkoutRepository() }
    single<ExerciseRepository> { FakeExerciseRepository() }
    single<PersonalRecordRepository> { FakePersonalRecordRepository() }
    single<GamificationRepository> { FakeGamificationRepository() }
    single<TrainingCycleRepository> { FakeTrainingCycleRepository() }
    single<UserProfileRepository> { FakeUserProfileRepository() }
    single<CsvExporter> { FakeCsvExporter() }
    single<CsvImporter> { FakeCsvImporter() }
    single<SyncRepository> {
        object : SyncRepository {
            override suspend fun getSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSessionSyncDto> = emptyList()
            override suspend fun getPRsModifiedSince(timestamp: Long, profileId: String): List<PersonalRecordSyncDto> = emptyList()
            override suspend fun getRoutinesModifiedSince(timestamp: Long, profileId: String): List<RoutineSyncDto> = emptyList()
            override suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto> = emptyList()
            override suspend fun getBadgesModifiedSince(timestamp: Long, profileId: String): List<EarnedBadgeSyncDto> = emptyList()
            override suspend fun getGamificationStatsForSync(profileId: String): GamificationStatsSyncDto? = null
            override suspend fun getWorkoutSessionsModifiedSince(timestamp: Long, profileId: String): List<WorkoutSession> = emptyList()
            override suspend fun getFullRoutinesModifiedSince(timestamp: Long, profileId: String): List<Routine> = emptyList()
            override suspend fun updateServerIds(mappings: IdMappings) = Unit
            override suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>) = Unit
            override suspend fun mergePRs(records: List<PersonalRecordSyncDto>) = Unit
            override suspend fun mergeRoutines(routines: List<RoutineSyncDto>) = Unit
            override suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>) = Unit
            override suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>, profileId: String) = Unit
            override suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?, profileId: String) = Unit
            override suspend fun mergePortalRoutines(routines: List<PullRoutineDto>, lastSync: Long, profileId: String) = Unit
            override suspend fun getFullCyclesForSync(profileId: String): List<PortalSyncAdapter.CycleWithContext> = emptyList()
            override suspend fun getFullPRsModifiedSince(timestamp: Long, profileId: String): List<com.devil.phoenixproject.domain.model.PersonalRecord> = emptyList()
            override suspend fun getPhaseStatisticsForSessions(sessionIds: List<String>): List<PhaseStatistics> = emptyList()
            override suspend fun getAllExerciseSignatures(): List<ExerciseSignature> = emptyList()
            override suspend fun getAllAssessments(profileId: String): List<AssessmentResult> = emptyList()
            override suspend fun updateSessionTimestamp(sessionId: String, timestamp: Long) = Unit
            override suspend fun mergePortalCycles(cycles: List<PullTrainingCycleDto>, profileId: String) = Unit
            override suspend fun mergePortalSessions(sessions: List<WorkoutSession>) = Unit
            override suspend fun mergePersonalRecords(records: List<PersonalRecordSyncDto>, profileId: String) = Unit
        }
    }
    single { ConnectivityChecker(ApplicationProvider.getApplicationContext()) }
    single { PortalTokenStorage(get()) }
    single<SupabaseConfig> {
        SupabaseConfig(url = "https://test.supabase.co", anonKey = "test-key")
    }
    single<CompletedSetRepository> { FakeCompletedSetRepository() }
    single<RepMetricRepository> { FakeRepMetricRepository() }
    single<BiomechanicsRepository> { FakeBiomechanicsRepository() }
    single {
        PortalApiClient(
            supabaseConfig = get(),
            tokenStorage = get(),
        )
    }
    single { SyncManager(get(), get(), get(), get(), get(), get(), get()) }
    single { SyncTriggerManager(get(), get()) }
    single { RepCounterFromMachine() }
    single { ResolveRoutineWeightsUseCase(get()) }
    single<DataBackupManager> { FakeDataBackupManager() }
    single<com.devil.phoenixproject.data.integration.ExternalActivityRepository> {
        object : com.devil.phoenixproject.data.integration.ExternalActivityRepository {
            override fun getAll(profileId: String, provider: com.devil.phoenixproject.domain.model.IntegrationProvider?): kotlinx.coroutines.flow.Flow<List<com.devil.phoenixproject.domain.model.ExternalActivity>> = kotlinx.coroutines.flow.flowOf(emptyList())
            override suspend fun getUnsyncedActivities(profileId: String): List<com.devil.phoenixproject.domain.model.ExternalActivity> = emptyList()
            override suspend fun upsertActivities(activities: List<com.devil.phoenixproject.domain.model.ExternalActivity>) = Unit
            override suspend fun markSynced(ids: List<String>) = Unit
            override suspend fun markSyncedBySyncKeys(syncKeys: List<com.devil.phoenixproject.data.integration.ExternalActivitySyncKey>, profileId: String) = Unit
            override suspend fun deleteActivities(provider: com.devil.phoenixproject.domain.model.IntegrationProvider, profileId: String) = Unit
            override fun getIntegrationStatus(provider: com.devil.phoenixproject.domain.model.IntegrationProvider, profileId: String): kotlinx.coroutines.flow.Flow<com.devil.phoenixproject.domain.model.IntegrationStatus?> = kotlinx.coroutines.flow.flowOf(null)
            override fun getAllIntegrationStatuses(profileId: String): kotlinx.coroutines.flow.Flow<List<com.devil.phoenixproject.domain.model.IntegrationStatus>> = kotlinx.coroutines.flow.flowOf(emptyList())
            override suspend fun updateIntegrationStatus(provider: com.devil.phoenixproject.domain.model.IntegrationProvider, status: com.devil.phoenixproject.domain.model.ConnectionStatus, profileId: String, lastSyncAt: Long?, errorMessage: String?) = Unit
        }
    }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    single { ThemeViewModel(get()) }
    single { EulaViewModel(get()) }
}

private val testSettings = MapSettings(
    mutableMapOf(
        "eula_accepted_version" to Constants.EULA_VERSION,
        "eula_accepted_timestamp" to 1L,
    ),
)

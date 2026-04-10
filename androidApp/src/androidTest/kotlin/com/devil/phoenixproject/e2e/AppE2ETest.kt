package com.devil.phoenixproject.e2e

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devil.phoenixproject.App
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
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.test.KoinTest

@RunWith(AndroidJUnit4::class)
class AppE2ETest : KoinTest {

    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var fakeWorkoutRepository: FakeWorkoutRepository
    private lateinit var fakeExerciseRepository: FakeExerciseRepository

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            allowOverride(true)
            modules(appModule, platformModule, testModule)
        }

        fakeBleRepository = getKoin().get()
        fakeWorkoutRepository = getKoin().get()
        fakeExerciseRepository = getKoin().get()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun splashThenHomeContentAppears() {
        launchApp()
        advanceIntoVisibleSplash()

        composeRule.onNodeWithTag(TestTags.APP_SPLASH).assertIsDisplayed()

        advancePastSplash(elapsedMillis = SPLASH_ASSERTION_DELAY_MS)

        composeRule.onNodeWithTag(TestTags.APP_MAIN_SHELL).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.SCREEN_HOME).assertIsDisplayed()
        composeRule.onNodeWithText("Click to Connect").assertIsDisplayed()
        composeRule.onAllNodesWithTag(TestTags.APP_SPLASH).assertCountEquals(0)
    }

    @Test
    fun bottomNavNavigatesToSettings() {
        launchApp()
        advancePastSplash()
        waitForTag(TestTags.SCREEN_HOME)

        composeRule.onNode(hasText("Settings") and hasClickAction()).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Like My Work?").assertIsDisplayed()
    }

    @Test
    fun activeWorkoutSurvivesActivityRecreation() {
        seedRoutineWorkout()
        launchApp()
        advancePastSplash()
        waitForTag(TestTags.SCREEN_HOME)

        composeRule.onNodeWithTag(TestTags.ACTION_ROUTINES).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        waitForText(ROTATION_TEST_ROUTINE_NAME)

        composeRule.onNodeWithText(ROTATION_TEST_ROUTINE_NAME).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        waitForTag(TestTags.ACTION_ROUTINE_CARD_START)

        composeRule.onNodeWithTag(TestTags.ACTION_ROUTINE_CARD_START).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        waitForTag(TestTags.SCREEN_ROUTINE_OVERVIEW)
        waitForTag(TestTags.ACTION_START_ROUTINE)

        composeRule.onNodeWithTag(TestTags.ACTION_START_ROUTINE).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        waitForTag(TestTags.ACTION_START_SET)

        composeRule.onNodeWithTag(TestTags.ACTION_START_SET).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        waitForTag(TestTags.SCREEN_ACTIVE_WORKOUT)

        composeRule.mainClock.autoAdvance = false
        composeRule.activityRule.scenario.recreate()
        setAppContent()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(TestTags.APP_SPLASH).assertCountEquals(0)
        composeRule.onNodeWithTag(TestTags.SCREEN_ACTIVE_WORKOUT).assertIsDisplayed()
    }

    private fun launchApp() {
        composeRule.mainClock.autoAdvance = false
        setAppContent()
    }

    private fun setAppContent() {
        composeRule.setContent {
            val mainViewModel: MainViewModel = koinViewModel()
            App(mainViewModel = mainViewModel)
        }
    }

    private fun advanceIntoVisibleSplash() {
        composeRule.mainClock.advanceTimeBy(SPLASH_ASSERTION_DELAY_MS)
        composeRule.waitForIdle()
    }

    private fun advancePastSplash(elapsedMillis: Long = 0L) {
        composeRule.mainClock.advanceTimeBy(SPLASH_DURATION_MS - elapsedMillis)
        composeRule.waitForIdle()
    }

    private fun seedRoutineWorkout() {
        fakeBleRepository.simulateConnect(deviceName = "Vee_Test")

        val exercise = Exercise(
            id = ROTATION_TEST_EXERCISE_ID,
            name = "Rotation Test Row",
            muscleGroup = "Back",
            muscleGroups = "Back",
            equipment = "HANDLES",
        )
        fakeExerciseRepository.addExercise(exercise)

        fakeWorkoutRepository.addRoutine(
            Routine(
                id = ROTATION_TEST_ROUTINE_ID,
                name = ROTATION_TEST_ROUTINE_NAME,
                exercises = listOf(
                    RoutineExercise(
                        id = "rotation-routine-exercise",
                        exercise = exercise,
                        orderIndex = 0,
                        weightPerCableKg = 20f,
                        programMode = ProgramMode.OldSchool,
                    ),
                ),
            ),
        )
    }

    private fun waitForTag(tag: String) {
        waitForCondition("tag '$tag'") {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        waitForCondition("text '$text'") {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS

        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) {
                return
            }

            composeRule.mainClock.advanceTimeBy(WAIT_FRAME_STEP_MS)
            composeRule.waitForIdle()
            SystemClock.sleep(WAIT_REAL_STEP_MS)
        }

        throw AssertionError("Timed out waiting for $description")
    }

    private companion object {
        const val SPLASH_DURATION_MS = 3000L
        const val SPLASH_ASSERTION_DELAY_MS = 1_000L
        const val WAIT_TIMEOUT_MS = 5_000L
        const val WAIT_FRAME_STEP_MS = 16L
        const val WAIT_REAL_STEP_MS = 25L
        const val ROTATION_TEST_EXERCISE_ID = "rotation-test-exercise"
        const val ROTATION_TEST_ROUTINE_ID = "rotation-test-routine"
        const val ROTATION_TEST_ROUTINE_NAME = "Rotation Test Routine"
    }
}

private val testModule = module {
    single<Settings> { testSettings }
    single<PreferencesManager> { FakePreferencesManager() }
    single { FakeBleRepository() }
    single<BleRepository> { get<FakeBleRepository>() }
    single { FakeWorkoutRepository() }
    single<WorkoutRepository> { get<FakeWorkoutRepository>() }
    single { FakeExerciseRepository() }
    single<ExerciseRepository> { get<FakeExerciseRepository>() }
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
            override suspend fun mergePersonalRecords(records: List<PersonalRecordSyncDto>, profileId: String) = Unit
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
    viewModel {
        MainViewModel(
            bleRepository = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            personalRecordRepository = get(),
            repCounter = get(),
            preferencesManager = get(),
            gamificationRepository = get(),
            trainingCycleRepository = get(),
            completedSetRepository = get(),
            repMetricRepository = get(),
            biomechanicsRepository = get(),
            resolveWeightsUseCase = get(),
            detectionManager = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
        )
    }
    single { ThemeViewModel(get()) }
    single { EulaViewModel(get()) }
}

private val testSettings = MapSettings(
    mutableMapOf(
        "eula_accepted_version" to Constants.EULA_VERSION,
        "eula_accepted_timestamp" to 1L,
    ),
)

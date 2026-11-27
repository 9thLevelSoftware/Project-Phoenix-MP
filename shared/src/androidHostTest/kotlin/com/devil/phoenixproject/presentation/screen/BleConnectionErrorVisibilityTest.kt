package com.devil.phoenixproject.presentation.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.devil.phoenixproject.presentation.util.TestTags
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.ServerDeletionNotice
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.ProfileSwitcherViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
class BleConnectionErrorVisibilityTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private lateinit var fixture: RoutineCompleteRuntimeFixture
    private var navController: NavHostController? = null

    @Before
    fun setUp() {
        fixture = RoutineCompleteRuntimeFixture()
        val syncManager = mockk<SyncManager>(relaxed = true).also {
            every { it.syncState } returns MutableStateFlow(SyncState.Idle)
            every { it.isAuthenticated } returns MutableStateFlow(false)
            every { it.lastSyncTime } returns MutableStateFlow(0L)
            every { it.serverDeletionNotice } returns MutableStateFlow<ServerDeletionNotice?>(null)
        }
        stopKoin()
        startKoin {
            modules(
                module {
                    single<UserProfileRepository> { fixture.fakeUserProfileRepository }
                    single<TrainingCycleRepository> { fixture.fakeTrainingCycleRepository }
                    single<WorkoutRepository> { fixture.fakeWorkoutRepository }
                    single<ExerciseRepository> { fixture.fakeExerciseRepository }
                    single<PersonalRecordRepository> { fixture.fakePersonalRecordRepository }
                    single<ProfileExerciseBaselineRepository> { fixture.fakeBaselineRepository }
                    single { PortalTokenStorage(MapSettings()) }
                    single<SyncManager> { syncManager }
                    single { RoutineTimeEstimator(get()) }
                    single { TemplateConverter(get(), get()) }
                    viewModel { RoutineCsvViewModel(get(), get(), get()) }
                    single { com.devil.phoenixproject.data.csv.CsvImportInbox() }
                },
            )
        }
    }

    @After
    fun tearDown() {
        fixture.close()
        stopKoin()
    }

    private fun launchShell(initialRoute: String? = null) {
        val profileSwitcherViewModel = ProfileSwitcherViewModel(fixture.fakeUserProfileRepository)
        rule.setContent {
            val nav = rememberNavController().also { navController = it }
            EnhancedMainScreen(
                viewModel = fixture.viewModel,
                exerciseRepository = fixture.fakeExerciseRepository,
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                dynamicColorAvailable = false,
                dynamicColorEnabled = false,
                onDynamicColorEnabledChange = {},
                profileSwitcherViewModel = profileSwitcherViewModel,
                navController = nav,
            )
            if (initialRoute != null) {
                LaunchedEffect(initialRoute) {
                    var waitedMs = 0
                    while (nav.currentBackStack.value.isEmpty() && waitedMs < 10_000) {
                        kotlinx.coroutines.delay(25)
                        waitedMs += 25
                    }
                    nav.navigate(initialRoute)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun topBarConnectFailure_surfacesSingleConnectionErrorDialogAndClearsOnDismiss() {
        fixture.fakeBleRepository.shouldFailConnect = true
        launchShell(initialRoute = NavigationRoutes.DailyRoutines.route)

        rule.onNodeWithContentDescription("Tap to connect to machine").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            fixture.viewModel.connectionError.value != null
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("Connection Failed").assertCountEquals(1)
        rule.onNodeWithText("No devices found").assertIsDisplayed()

        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Connection Failed").assertDoesNotExist()
    }

    @Test
    fun routineOverviewConnectFailure_surfacesConnectionErrorDialog() {
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { fixture.fakeExerciseRepository.addExercise(it.exercise) }
        fixture.fakeWorkoutRepository.addRoutine(routine)
        fixture.viewModel.enterRoutineOverview(routine)
        fixture.fakeBleRepository.shouldFailConnect = true

        launchShell(initialRoute = NavigationRoutes.RoutineOverview.route)
        rule.waitUntil(timeoutMillis = 5_000) {
            fixture.viewModel.routineFlowState.value is RoutineFlowState.Overview
        }
        rule.waitForIdle()

        // Tap Start on RoutineOverviewScreen -> ensureConnection fails
        rule.onNodeWithTag(TestTags.ACTION_START_ROUTINE_EXERCISE).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            fixture.viewModel.connectionError.value != null
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("Connection Failed").assertCountEquals(1)
        rule.onNodeWithText("No devices found").assertIsDisplayed()

        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Connection Failed").assertDoesNotExist()
    }

    @Test
    fun setReadyScreen_connectionFailureSurfacesDialogThroughEnhancedMainScreen() {
        val routine = WorkoutStateFixtures.createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { fixture.fakeExerciseRepository.addExercise(it.exercise) }
        fixture.fakeWorkoutRepository.addRoutine(routine)
        fixture.viewModel.workoutSessionManager.loadRoutine(routine)
        fixture.viewModel.enterSetReadyWithAdjustments(0, 0, 20f, 10)
        fixture.fakeBleRepository.shouldFailConnect = true

        launchShell(initialRoute = NavigationRoutes.SetReady.route)

        // Trigger ensureConnection failure while SetReadyScreen is active (e.g. via top bar or reconnect)
        rule.onNodeWithContentDescription("Tap to connect to machine").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            fixture.viewModel.connectionError.value != null
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("Connection Failed").assertCountEquals(1)
        rule.onNodeWithText("No devices found").assertIsDisplayed()
    }
}

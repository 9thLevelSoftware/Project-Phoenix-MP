package com.devil.phoenixproject.presentation.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.ServerDeletionNotice
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.ProfileSwitcherViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * Issue #1164 FULL-SHELL chrome coverage: the exit runs inside the real
 * [EnhancedMainScreen] scaffold — the exact shell the reported screenshot shows
 * (no top bar and no back chevron on the ROUTINE COMPLETE route) — over the production
 * NavGraph and real origin screens.
 *
 * Asserted shell contract:
 *  - on routine_complete the shell chrome (top bar) stays hidden and the reserved Done
 *    footer is the reachable exit (the reported terminal-dead-end context);
 *  - one Done tap runs the production exit action and lands on the REAL origin surface
 *    (DailyRoutinesScreen rendering the fixture routine) with the full cleanup contract.
 *
 * SyncManager is the only non-fakeable collaborator the shell resolves from Koin; it is
 * stubbed here (its four exposed flows) because it is a concrete final class with eight
 * constructor dependencies. Everything else is the same production/fake wiring as
 * RoutineCompleteFullShellNavigationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineCompleteEnhancedShellChromeTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var fixture: RoutineCompleteRuntimeFixture? = null
    private var navController: NavHostController? = null

    @Before
    fun setUpKoin() {
        val fx = RoutineCompleteRuntimeFixture().also { fixture = it }
        val syncManager = mockk<SyncManager>(relaxed = true).also {
            every { it.syncState } returns MutableStateFlow(SyncState.Idle)
            every { it.isAuthenticated } returns MutableStateFlow(false)
            every { it.lastSyncTime } returns MutableStateFlow(0L)
            every { it.serverDeletionNotice } returns MutableStateFlow<ServerDeletionNotice?>(null)
        }
        startKoin {
            modules(
                module {
                    single<UserProfileRepository> { fx.fakeUserProfileRepository }
                    single<TrainingCycleRepository> { fx.fakeTrainingCycleRepository }
                    single<WorkoutRepository> { fx.fakeWorkoutRepository }
                    single<ExerciseRepository> { fx.fakeExerciseRepository }
                    single<PersonalRecordRepository> { fx.fakePersonalRecordRepository }
                    single<ProfileExerciseBaselineRepository> { fx.fakeBaselineRepository }
                    single<SyncManager> { syncManager }
                    single { RoutineTimeEstimator(get()) }
                    single { TemplateConverter(get(), get()) }
                    viewModel { RoutineCsvViewModel(get(), get(), get()) }
                },
            )
        }
    }

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
        stopKoin()
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun enhancedShell_chromeStaysHiddenOnComplete_doneReturnsToRealOriginSurface() {
        val fx = fixture!!
        // ViewModel construction must happen outside composition (lint:
        // ViewModelConstructorInComposable).
        val profileSwitcherViewModel = ProfileSwitcherViewModel(fx.fakeUserProfileRepository)
        fx.driveToComplete(
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            trainingCyclesOrigin = false,
        )
        rule.setContent {
            val nav = rememberNavController().also { navController = it }
            EnhancedMainScreen(
                viewModel = fx.viewModel,
                exerciseRepository = fx.fakeExerciseRepository,
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                dynamicColorAvailable = false,
                dynamicColorEnabled = false,
                onDynamicColorEnabledChange = {},
                profileSwitcherViewModel = profileSwitcherViewModel,
                navController = nav,
            )
            LaunchedEffect(Unit) {
                // The shell composes its NavHost a beat after the first frame (chrome
                // visibility gating); wait for the graph before pushing the flow.
                var waitedMs = 0
                while (navController!!.currentBackStack.value.isEmpty() && waitedMs < 10_000) {
                    kotlinx.coroutines.delay(25)
                    waitedMs += 25
                }
                nav.navigate(NavigationRoutes.DailyRoutines.route)
                nav.navigate(NavigationRoutes.RoutineComplete.route)
            }
        }
        rule.waitForIdle()

        assertEquals(
            "Precondition: the completion route must be showing inside the EnhancedMainScreen shell",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        // Reported context preserved in the shell: no top-bar chrome on this route and the
        // reserved Done footer is the reachable exit.
        rule.onNodeWithText("Daily Routines").assertDoesNotExist()
        rule.onNodeWithText("DONE").assertIsDisplayed()

        val stackBefore = backStack()
        rule.onNodeWithText("DONE").performClick()
        rule.waitForIdle()

        assertEquals(
            "Done must pop to the daily_routines route inside the EnhancedMainScreen shell",
            NavigationRoutes.DailyRoutines.route,
            currentRoute(),
        )
        rule.onNodeWithText("beginner").assertIsDisplayed()
        val cleanup = fx.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_enhancedShell",
            mapOf(
                "graph" to "EnhancedMainScreen + production NavGraph",
                "destination" to currentRoute(),
                "originSurface" to "DailyRoutinesScreen (real screen, fixture routine visible)",
                "chromeOnComplete" to "top bar hidden (reported context), reserved Done footer reachable",
                "cleanup" to cleanup,
                "clickedRealDone" to true,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    private fun currentRoute(): String =
        navController!!.currentBackStackEntry?.destination?.route ?: "<null>"

    private fun backStack(): String =
        navController!!.currentBackStack.value.joinToString(" -> ") {
            it.destination.route ?: "<null>"
        }
}

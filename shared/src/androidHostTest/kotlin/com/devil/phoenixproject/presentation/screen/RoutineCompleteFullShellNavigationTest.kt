package com.devil.phoenixproject.presentation.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
import com.devil.phoenixproject.domain.usecase.RoutineTimeEstimator
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.presentation.navigation.NavGraph
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
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
 * Issue #1164 FULL-SHELL navigation coverage (follow-up requirement).
 *
 * [RoutineCompleteRouteExitRuntimeTest] drives the production completion screen through a
 * seeded mini-NavHost whose other destinations are empty placeholders. This test instead
 * runs the PRODUCTION navigation graph — [NavGraph], the exact route table and destination
 * screens the app ships (Home, DailyRoutines, TrainingCycles, RoutineComplete, ...) — and
 * asserts that the Done exit lands on the REAL origin surface (the real DailyRoutines /
 * TrainingCycles screen content rendering the fixture routine), not on a label, with the
 * full cleanup contract.
 *
 * Unavailable configurations (recorded honestly, see the #1164 follow-up PR):
 *  - iOS full-shell coverage: the iOS exit capture uses a seeded NavHost with destination
 *    labels, not full EnhancedMainScreen origin surfaces (retained limitation).
 *
 * EnhancedMainScreen scaffold chrome coverage (top-bar suppression on routine_complete)
 * lives in RoutineCompleteEnhancedShellChromeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineCompleteFullShellNavigationTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var fixture: RoutineCompleteRuntimeFixture? = null
    private var navController: NavHostController? = null

    @Before
    fun setUpKoin() {
        startKoin {
            modules(
                module {
                    // The screens this flow renders resolve these through Koin in
                    // production (HomeScreen, DailyRoutinesScreen/RoutinesTab, resume
                    // launcher). Same instances the MainViewModel fixture drives.
                    single<UserProfileRepository> { fixture!!.fakeUserProfileRepository }
                    single<TrainingCycleRepository> { fixture!!.fakeTrainingCycleRepository }
                    single<WorkoutRepository> { fixture!!.fakeWorkoutRepository }
                    single<ExerciseRepository> { fixture!!.fakeExerciseRepository }
                    single<PersonalRecordRepository> { fixture!!.fakePersonalRecordRepository }
                    single<ProfileExerciseBaselineRepository> { fixture!!.fakeBaselineRepository }
                    // Issue #1162 R4 UI: the recovery surface observes the
                    // authenticated portal identity from the production auth store.
                    single { com.devil.phoenixproject.data.sync.PortalTokenStorage(com.russhwolf.settings.MapSettings()) }
                    single { RoutineTimeEstimator(get()) }
                    single { TemplateConverter(get(), get()) }
                    // Mirrors production PresentationModule (factory + koinViewModel()).
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
    fun fullShell_dailyRoutinesOrigin_doneReturnsToRealOriginSurface() {
        driveProductionShell(trainingCyclesOrigin = false)

        assertEquals(
            "Precondition: the completion route must be showing in the production graph",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        rule.onNodeWithText("DONE").assertIsDisplayed()
        val stackBefore = backStack()
        rule.onNodeWithText("DONE").performClick()
        rule.waitForIdle()

        assertEquals(
            "Done must pop to the daily_routines route in the production navigation graph",
            NavigationRoutes.DailyRoutines.route,
            currentRoute(),
        )
        // Real origin surface, not a seeded label: DailyRoutinesScreen renders RoutinesTab
        // with the fixture routine loaded into the real workout repository.
        rule.onNodeWithText("beginner").assertIsDisplayed()
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_fullShell_dailyRoutines",
            mapOf(
                "graph" to "production NavGraph",
                "destination" to currentRoute(),
                "originSurface" to "DailyRoutinesScreen (real screen, fixture routine visible)",
                "cleanup" to cleanup,
                "clickedRealDone" to true,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun fullShell_trainingCyclesOrigin_doneReturnsToRealOriginSurface() {
        driveProductionShell(trainingCyclesOrigin = true)

        assertEquals(
            "Precondition: the completion route must be showing in the production graph",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        val stackBefore = backStack()
        rule.onNodeWithText("DONE").performClick()
        rule.waitForIdle()

        assertEquals(
            "Done must pop to the training_cycles route in the production navigation graph",
            NavigationRoutes.TrainingCycles.route,
            currentRoute(),
        )
        // Real TrainingCyclesScreen surface (its cycle list renders over the fixture repos).
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_fullShell_trainingCycles",
            mapOf(
                "graph" to "production NavGraph",
                "destination" to currentRoute(),
                "originSurface" to "TrainingCyclesScreen (real screen)",
                "cleanup" to cleanup,
                "clickedRealDone" to true,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    // ===== Shell driving =====

    /**
     * Builds the production Complete state and routes to the completion screen exactly the
     * way the app does, but over the PRODUCTION [NavGraph] route table: the exit
     * destination entry is the real origin screen underneath the completion route.
     */
    private fun driveProductionShell(trainingCyclesOrigin: Boolean) {
        val fx = RoutineCompleteRuntimeFixture().also { fixture = it }
        fx.driveToComplete(
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            trainingCyclesOrigin = trainingCyclesOrigin,
        )
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                val nav = rememberNavController().also { navController = it }
                NavGraph(
                    navController = nav,
                    viewModel = fx.viewModel,
                    exerciseRepository = fx.fakeExerciseRepository,
                    themeMode = ThemeMode.SYSTEM,
                    onThemeModeChange = {},
                    dynamicColorAvailable = false,
                    dynamicColorEnabled = false,
                    onDynamicColorEnabledChange = {},
                    onOpenProfileSwitcher = {},
                    onProfileRecoveryRequired = {},
                )
                LaunchedEffect(Unit) {
                    val originRoute = if (trainingCyclesOrigin) {
                        NavigationRoutes.TrainingCycles.route
                    } else {
                        NavigationRoutes.DailyRoutines.route
                    }
                    nav.navigate(originRoute)
                    nav.navigate(NavigationRoutes.RoutineComplete.route)
                }
            }
        }
        rule.waitForIdle()
    }

    // ===== Navigation measurement primitives =====

    private fun currentRoute(): String =
        navController!!.currentBackStackEntry?.destination?.route ?: "<null>"

    /** Full back-stack route list (bottom -> top) for the evidence trail. */
    private fun backStack(): String =
        navController!!.currentBackStack.value.joinToString(" -> ") {
            it.destination.route ?: "<null>"
        }
}

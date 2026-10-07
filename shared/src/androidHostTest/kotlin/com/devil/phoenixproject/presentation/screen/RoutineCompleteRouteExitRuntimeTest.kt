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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1164 route-Done runtime verification.
 *
 * Taps the REAL Done footer on the REAL [RoutineCompleteScreen] through a real
 * NavController and asserts the production exit action
 * (routineExitDestination() -> exitRoutineFlow() -> safePopOrNavigate(dest)):
 * - both launch origins (DailyRoutines and TrainingCycles) exit to the correct route;
 * - the full cleanup contract holds (Complete -> NotInRoutine, loaded routine cleared,
 *   Idle, launch origin cleared);
 * - repeated entry/exit works;
 * - Android Back runs the identical cleanup;
 * - the destination-absent fallback (safePopOrNavigate navigate branch) strands nobody.
 *
 * Every assertion records the full back-stack before and after the exit so the evidence
 * trail shows exactly which entry the production exit action landed on.
 *
 * NOTE: org.junit.Assert.assertEquals is (message, expected, actual) — JUnit order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineCompleteRouteExitRuntimeTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var fixture: RoutineCompleteRuntimeFixture? = null
    private var navController: NavHostController? = null

    @After
    fun tearDown() {
        fixture?.close()
        fixture = null
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun done_dailyRoutinesOrigin_popsToDailyRoutinesAndCleansUp() {
        driveToCompleteRoute(trainingCyclesOrigin = false)

        assertEquals(
            "Precondition: the completion route must be showing",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        val stackBefore = backStack()
        rule.onNodeWithText("DONE").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(
            "Done must pop to the daily_routines route for a DailyRoutines-origin routine",
            NavigationRoutes.DailyRoutines.route,
            currentRoute(),
        )
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_dailyRoutines",
            mapOf(
                "destination" to currentRoute(),
                "cleanup" to cleanup,
                "clickedRealDone" to true,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun done_trainingCyclesOrigin_popsToTrainingCyclesAndCleansUp() {
        driveToCompleteRoute(trainingCyclesOrigin = true)

        assertEquals(
            "Precondition: the completion route must be showing",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        val stackBefore = backStack()
        rule.onNodeWithText("DONE").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(
            "Done must pop to the training_cycles route for a TrainingCycles-origin routine",
            NavigationRoutes.TrainingCycles.route,
            currentRoute(),
        )
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_trainingCycles",
            mapOf(
                "destination" to currentRoute(),
                "cleanup" to cleanup,
                "clickedRealDone" to true,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun androidBack_runsIdenticalCleanupToDone() {
        driveToCompleteRoute(trainingCyclesOrigin = true)

        // The screen's BackHandler runs the same shared exit action as Done
        // (lens-navigation-ux-5); dispatch real back through the OnBackPressedDispatcher.
        val stackBefore = backStack()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()

        assertEquals(
            "Android Back must exit to the same destination as Done",
            NavigationRoutes.TrainingCycles.route,
            currentRoute(),
        )
        val cleanup = fixture!!.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_androidBack",
            mapOf(
                "destination" to currentRoute(),
                "cleanup" to cleanup,
                "dispatched" to "onBackPressedDispatcher",
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun repeatedEntryExit_secondCycleStillCleansUp() {
        driveToCompleteRoute(trainingCyclesOrigin = false)
        rule.onNodeWithText("DONE").assertIsDisplayed().performClick()
        rule.waitForIdle()
        fixture!!.assertExitCleanup()

        // Re-enter the completion route over a TrainingCycles origin (new completion
        // state, its own origin entry under the completion route) and exit again.
        val fx = fixture!!
        fx.driveToComplete(
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            trainingCyclesOrigin = true,
        )
        rule.runOnIdle {
            navController!!.navigate(NavigationRoutes.TrainingCycles.route)
            navController!!.navigate(NavigationRoutes.RoutineComplete.route)
        }
        rule.waitForIdle()
        val stackBefore = backStack()

        rule.onNodeWithText("DONE").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(
            "Second entry/exit must exit to the current origin's destination",
            NavigationRoutes.TrainingCycles.route,
            currentRoute(),
        )
        val cleanup = fx.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_repeated",
            mapOf(
                "destination" to currentRoute(),
                "cleanup" to cleanup,
                "cycles" to 2,
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-xxhdpi")
    fun destinationAbsentFallback_navigatesInsteadOfStranding() {
        val fx = RoutineCompleteRuntimeFixture().also { fixture = it }
        fx.driveToComplete(
            routineName = "beginner",
            exerciseCount = 5,
            setsPerExercise = 3,
            completedSetKeys = 12,
            durationMs = 20 * 60_000L + 14_000L,
            trainingCyclesOrigin = false,
        )
        // Backstack: home -> routine_complete (the exit destination is NOT on the stack).
        rule.setContent {
            val nav = rememberNavController().also { navController = it }
            Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = NavigationRoutes.Home.route) {
                    composable(NavigationRoutes.Home.route) {}
                    composable(NavigationRoutes.DailyRoutines.route) {}
                    composable(NavigationRoutes.TrainingCycles.route) {}
                    composable(NavigationRoutes.RoutineComplete.route) {
                        RoutineCompleteScreen(nav, fx.viewModel)
                    }
                }
            }
            LaunchedEffect(Unit) {
                nav.navigate(NavigationRoutes.RoutineComplete.route)
            }
        }
        rule.waitForIdle()
        assertEquals(
            "Precondition: completion route showing without the exit destination on the stack",
            NavigationRoutes.RoutineComplete.route,
            currentRoute(),
        )
        val stackBefore = backStack()

        rule.onNodeWithText("DONE").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(
            "With the destination absent from the backstack, safePopOrNavigate must navigate " +
                "to it instead of stranding the user (issue #1164 destination-absent fallback)",
            NavigationRoutes.DailyRoutines.route,
            currentRoute(),
        )
        val cleanup = fx.assertExitCleanup()
        RoutineCompleteRuntimeEvidence.record(
            "routeExit_destinationAbsentFallback",
            mapOf(
                "destination" to currentRoute(),
                "cleanup" to cleanup,
                "fallbackBranch" to "navigate(dest) with popUpTo(home)",
                "backStackBefore" to stackBefore,
                "backStackAfter" to backStack(),
            ),
        )
    }

    // ===== Navigation measurement primitives =====

    private fun currentRoute(): String =
        navController!!.currentBackStackEntry?.destination?.route ?: "<null>"

    /** Full back-stack route list (bottom -> top) for the evidence trail. */
    private fun backStack(): String =
        navController!!.currentBackStack.value.joinToString(" -> ") {
            it.destination.route ?: "<null>"
        }

    /**
     * Builds the production state (Complete) and routes to the completion screen the way
     * ActiveWorkoutScreen does: exit destination present on the backstack under it.
     */
    private fun driveToCompleteRoute(trainingCyclesOrigin: Boolean) {
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
            val nav = rememberNavController().also { navController = it }
            Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = NavigationRoutes.Home.route) {
                    composable(NavigationRoutes.Home.route) {}
                    composable(NavigationRoutes.DailyRoutines.route) {}
                    composable(NavigationRoutes.TrainingCycles.route) {}
                    composable(NavigationRoutes.RoutineComplete.route) {
                        RoutineCompleteScreen(nav, fx.viewModel)
                    }
                }
            }
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
        rule.waitForIdle()
    }
}

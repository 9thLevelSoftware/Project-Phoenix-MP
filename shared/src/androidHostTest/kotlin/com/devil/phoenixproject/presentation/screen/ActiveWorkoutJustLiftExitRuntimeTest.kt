package com.devil.phoenixproject.presentation.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.navigation.exitJustLift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runtime coverage for the destination-specific, idempotent Issue #1366 exit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveWorkoutJustLiftExitRuntimeTest {
    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private var navController: NavHostController? = null

    @Test
    fun callbackAndIdleObserverDeliveriesOnlyPopActiveWorkout() {
        val sourceEntryId = showActiveWorkout(withJustLift = true)
        assertEquals(NavigationRoutes.ActiveWorkout.route, currentRoute())

        // The confirmed Exit callback and synchronous-Idle observer may both deliver.
        rule.runOnIdle {
            navController!!.exitJustLift(sourceEntryId)
            navController!!.exitJustLift(sourceEntryId)
        }
        rule.waitForIdle()

        assertEquals(NavigationRoutes.JustLift.route, currentRoute())
        assertEquals(
            listOf(NavigationRoutes.Home.route, NavigationRoutes.JustLift.route),
            routeStack(),
        )
    }

    @Test
    fun missingJustLiftEntryFallsBackWithoutPoppingHome() {
        val sourceEntryId = showActiveWorkout(withJustLift = false)

        rule.runOnIdle { navController!!.exitJustLift(sourceEntryId) }
        rule.waitForIdle()

        assertEquals(NavigationRoutes.JustLift.route, currentRoute())
        assertEquals(
            listOf(NavigationRoutes.Home.route, NavigationRoutes.JustLift.route),
            routeStack(),
        )
    }

    @Test
    fun staleExitDoesNotNavigateFromALaterActiveWorkoutEntry() {
        val oldSourceEntryId = showActiveWorkout(withJustLift = true)
        rule.runOnIdle {
            navController!!.navigate(NavigationRoutes.Settings.route)
            navController!!.navigate(NavigationRoutes.ActiveWorkout.route)
        }
        rule.waitForIdle()
        val laterEntryId = navController!!.currentBackStackEntry!!.id
        assertNotEquals(oldSourceEntryId, laterEntryId)
        val before = routeStack()

        rule.runOnIdle { navController!!.exitJustLift(oldSourceEntryId) }
        rule.waitForIdle()

        assertEquals(NavigationRoutes.ActiveWorkout.route, currentRoute())
        assertEquals(before, routeStack())
    }

    private fun showActiveWorkout(withJustLift: Boolean): String {
        rule.setContent {
            val nav = rememberNavController().also { navController = it }
            Box(Modifier.fillMaxSize()) {
                NavHost(navController = nav, startDestination = NavigationRoutes.Home.route) {
                    composable(NavigationRoutes.Home.route) {}
                    composable(NavigationRoutes.JustLift.route) {}
                    composable(NavigationRoutes.ActiveWorkout.route) {}
                    composable(NavigationRoutes.Settings.route) {}
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            if (withJustLift) navController!!.navigate(NavigationRoutes.JustLift.route)
            navController!!.navigate(NavigationRoutes.ActiveWorkout.route)
        }
        rule.waitForIdle()
        assertTrue(routeStack().contains(NavigationRoutes.Home.route))
        return navController!!.currentBackStackEntry!!.id
    }

    private fun currentRoute(): String =
        navController!!.currentBackStackEntry?.destination?.route ?: "<null>"

    private fun routeStack(): List<String> = navController!!.currentBackStack.value.mapNotNull {
        it.destination.route
    }
}

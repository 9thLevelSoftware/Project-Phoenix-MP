package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnhancedMainScreenKeepScreenOnTest {
    @Test
    fun justLiftRouteKeepsScreenAwakeBeforeWorkoutSessionStarts() {
        assertTrue(
            shouldKeepScreenOnForRoute(
                route = NavigationRoutes.JustLift.route,
                isInWorkoutSession = false,
            ),
            "Just Lift setup/ready screen should keep Auto-Lock disabled before the first set starts",
        )
    }

    @Test
    fun nonWorkoutRouteWithoutSessionAllowsScreenToSleep() {
        assertFalse(
            shouldKeepScreenOnForRoute(
                route = NavigationRoutes.Home.route,
                isInWorkoutSession = false,
            ),
            "Home should not keep Auto-Lock disabled when no workout session is active",
        )
    }

    @Test
    fun activeWorkoutSessionKeepsScreenAwakeFromAnyRoute() {
        assertTrue(
            shouldKeepScreenOnForRoute(
                route = NavigationRoutes.Home.route,
                isInWorkoutSession = true,
            ),
            "Active workout session should keep Auto-Lock disabled across workout navigation",
        )
    }
}

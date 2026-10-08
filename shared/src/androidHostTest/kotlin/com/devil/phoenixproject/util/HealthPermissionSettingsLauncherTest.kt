package com.devil.phoenixproject.util

import android.content.ActivityNotFoundException
import android.os.Build
import android.provider.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Health Connect settings chain must keep walking after a start failure.
 * A missing home-settings activity used to escape the first catch and never
 * reach the app-details fallback.
 */
class HealthPermissionSettingsLauncherTest {

    @Test
    fun belowApi34_triesTheHealthConnectSettingsIntentOnceBeforeAppDetails() {
        val actions = healthConnectSettingsActions(Build.VERSION_CODES.TIRAMISU)

        assertEquals(
            listOf(
                "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            actions,
        )
        assertEquals(actions.distinct(), actions)
    }

    @Test
    fun api34AndAbove_keepsPermissionHomeThenAppDetails() {
        val actions = healthConnectSettingsActions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        assertEquals(
            listOf(
                "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS",
                "android.health.connect.action.HEALTH_HOME_SETTINGS",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            actions,
        )
        assertEquals(actions.distinct(), actions)
    }

    @Test
    fun olderReleases_matchThePre34Chain() {
        assertEquals(
            healthConnectSettingsActions(Build.VERSION_CODES.TIRAMISU),
            healthConnectSettingsActions(Build.VERSION_CODES.O),
        )
        assertTrue(
            healthConnectSettingsActions(Build.VERSION_CODES.VANILLA_ICE_CREAM)
                .contains("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"),
        )
    }

    @Test
    fun missingHomeSettings_fallsThroughToAppDetails() {
        val started = mutableListOf<String>()

        val opened = launchHealthConnectSettingsChain(
            attempts = listOf("permissions", "home", "app-details"),
        ) { target ->
            started += target
            if (target != "app-details") {
                throw ActivityNotFoundException("no activity for $target")
            }
        }

        assertEquals(listOf("permissions", "home", "app-details"), started)
        assertEquals(2, opened)
    }

    @Test
    fun equivalentStartFailure_continuesToNextFallback() {
        val started = mutableListOf<String>()

        val opened = launchHealthConnectSettingsChain(
            attempts = listOf("permissions", "home", "app-details"),
        ) { target ->
            started += target
            if (target == "permissions") {
                throw SecurityException("start blocked")
            }
        }

        assertEquals(listOf("permissions", "home"), started)
        assertEquals(1, opened)
    }

    @Test
    fun firstSuccess_doesNotTryLaterFallbacks() {
        val started = mutableListOf<String>()

        val opened = launchHealthConnectSettingsChain(
            attempts = listOf("permissions", "home", "app-details"),
        ) { target ->
            started += target
        }

        assertEquals(listOf("permissions"), started)
        assertEquals(0, opened)
    }

    @Test
    fun everyStartFailure_isGuarded() {
        val opened = launchHealthConnectSettingsChain(
            attempts = listOf("permissions", "home", "app-details"),
        ) { target ->
            throw ActivityNotFoundException("no activity for $target")
        }

        assertNull(opened)
    }
}

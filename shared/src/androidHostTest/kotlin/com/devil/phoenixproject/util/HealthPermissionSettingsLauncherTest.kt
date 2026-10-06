package com.devil.phoenixproject.util

import android.content.ActivityNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Health Connect settings chain must keep walking after a start failure.
 * A missing home-settings activity used to escape the first catch and never
 * reach the app-details fallback.
 */
class HealthPermissionSettingsLauncherTest {

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

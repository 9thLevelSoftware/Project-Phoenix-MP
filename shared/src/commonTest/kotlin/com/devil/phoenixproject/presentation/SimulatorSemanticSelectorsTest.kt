package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.presentation.util.ConnectionAccessibilityDescription
import com.devil.phoenixproject.presentation.util.ConnectionSemanticState
import com.devil.phoenixproject.presentation.util.TestTags
import com.devil.phoenixproject.presentation.util.connectionSemanticStateFor
import com.devil.phoenixproject.presentation.util.eulaCanAccept
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic semantic contract for the iOS simulator harness checkpoints.
 *
 * The shared module does not currently expose a Compose UI-test runtime on the
 * simulator target. These tests therefore exercise the production contracts
 * that own stable selector values, EULA gating, and connection-state semantics
 * rather than trying to locate checkout-relative source files at runtime.
 */
class SimulatorSemanticSelectorsTest {
    @Test
    fun startupAndWorkoutScreensExposeStableHarnessTags() {
        assertEquals(
            listOf(
                "app-root",
                "app-splash",
                "screen-eula",
                "app-main-shell",
                "app-nav-host",
                "screen-home",
                "action-just-lift",
                "screen-just-lift",
            ),
            listOf(
                TestTags.APP_ROOT,
                TestTags.APP_SPLASH,
                TestTags.SCREEN_EULA,
                TestTags.APP_MAIN_SHELL,
                TestTags.APP_NAV_HOST,
                TestTags.SCREEN_HOME,
                TestTags.ACTION_JUST_LIFT,
                TestTags.SCREEN_JUST_LIFT,
            ),
        )
    }

    @Test
    fun eulaControlsExposeStableTagsAndKeepAcceptanceGating() {
        assertEquals("eula-scroll-container", TestTags.EULA_SCROLL_CONTAINER)
        assertEquals("eula-age-confirmation", TestTags.EULA_AGE_CONFIRMATION)
        assertEquals("eula-accept", TestTags.EULA_ACCEPT)

        assertEquals(false, eulaCanAccept(ageConfirmed = false, hasScrolledToBottom = false))
        assertEquals(false, eulaCanAccept(ageConfirmed = false, hasScrolledToBottom = true))
        assertEquals(false, eulaCanAccept(ageConfirmed = true, hasScrolledToBottom = false))
        assertEquals(true, eulaCanAccept(ageConfirmed = true, hasScrolledToBottom = true))
    }

    @Test
    fun homeJustLiftRetainsStableSelectorContract() {
        assertEquals("screen-home", TestTags.SCREEN_HOME)
        assertEquals("action-just-lift", TestTags.ACTION_JUST_LIFT)
    }

    @Test
    fun connectionControlMapsEveryStateToStableTagAndAccessibilityDescription() {
        val cases = listOf(
            ConnectionState.Disconnected to ConnectionSemanticState(
                testTag = TestTags.CONNECTION_STATUS_DISCONNECTED,
                accessibilityDescription = ConnectionAccessibilityDescription.DISCONNECTED,
            ),
            ConnectionState.Scanning to ConnectionSemanticState(
                testTag = TestTags.CONNECTION_STATUS_CONNECTING,
                accessibilityDescription = ConnectionAccessibilityDescription.CONNECTING,
            ),
            ConnectionState.Connecting to ConnectionSemanticState(
                testTag = TestTags.CONNECTION_STATUS_CONNECTING,
                accessibilityDescription = ConnectionAccessibilityDescription.CONNECTING,
            ),
            ConnectionState.Connected(
                deviceName = "Phantom",
                deviceAddress = "00:00",
            ) to ConnectionSemanticState(
                testTag = TestTags.CONNECTION_STATUS_CONNECTED,
                accessibilityDescription = ConnectionAccessibilityDescription.CONNECTED,
            ),
            ConnectionState.Error("Connection failed") to ConnectionSemanticState(
                testTag = TestTags.CONNECTION_STATUS_ERROR,
                accessibilityDescription = ConnectionAccessibilityDescription.ERROR,
            ),
        )

        cases.forEach { (state, expected) ->
            assertEquals(expected, connectionSemanticStateFor(state), state::class.simpleName)
        }
    }
}

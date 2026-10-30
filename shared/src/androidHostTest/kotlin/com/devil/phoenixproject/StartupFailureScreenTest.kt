package com.devil.phoenixproject

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupFailureScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun genericFailureCanShareRetryAndContactSupportWithoutExposingDatabaseExport() {
        var shares = 0
        var retries = 0
        rule.setContent {
            PersistedFileStartupFailureScreen(
                StartupDependencyResolution.Failed("STARTUP_INITIALIZATION_FAILED", true, cause = IllegalStateException("private")),
                onShareDiagnostics = { shares++; false },
                onRetry = { retries++ },
            )
        }
        rule.onNodeWithText("Email support").assertExists()
        rule.onNodeWithText("Export database files").assertDoesNotExist()
        rule.onNodeWithText("Share startup diagnostics").performScrollTo().performClick()
        assertEquals(1, shares)
        rule.onNodeWithText("The share sheet could not be opened. You can try again.").assertExists()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, retries)
    }
}

package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source-level semantic contract for the iOS simulator harness checkpoints.
 *
 * The shared module does not currently expose a Compose UI-test runtime on the
 * simulator target, so these tests pin the rendered modifier/semantics wiring
 * that the future Phantom driver will query. They intentionally read the
 * production call sites rather than only checking that constants exist: a tag
 * inventory without a modifier at the rendered node is not an automation
 * selector.
 */
class SimulatorSemanticSelectorsTest {
    @Test
    fun startupAndWorkoutScreensExposeStableRenderedTags() {
        val tags = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt")
        val app = source("src/commonMain/kotlin/com/devil/phoenixproject/App.kt")
        val splash = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SplashScreen.kt")
        val eula = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EulaScreen.kt")
        val main = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt")
        val home = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt")
        val justLift = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt")

        listOf(
            "APP_ROOT" to "app-root",
            "APP_SPLASH" to "app-splash",
            "SCREEN_EULA" to "screen-eula",
            "APP_MAIN_SHELL" to "app-main-shell",
            "APP_NAV_HOST" to "app-nav-host",
            "SCREEN_HOME" to "screen-home",
            "ACTION_JUST_LIFT" to "action-just-lift",
            "SCREEN_JUST_LIFT" to "screen-just-lift",
            "EULA_SCROLL_CONTAINER" to "eula-scroll-container",
            "EULA_AGE_CONFIRMATION" to "eula-age-confirmation",
            "EULA_ACCEPT" to "eula-accept",
            "CONNECTION_STATUS_DISCONNECTED" to "connection-status-disconnected",
            "CONNECTION_STATUS_CONNECTING" to "connection-status-connecting",
            "CONNECTION_STATUS_CONNECTED" to "connection-status-connected",
            "CONNECTION_STATUS_ERROR" to "connection-status-error",
        ).forEach { (name, value) ->
            assertContains(tags, "const val $name = \"$value\"", message = name)
        }

        assertContains(app, ".testTag(TestTags.APP_ROOT)")
        assertContains(splash, ".testTag(TestTags.APP_SPLASH)")
        assertContains(eula, ".testTag(TestTags.SCREEN_EULA)")
        assertContains(main, ".testTag(TestTags.APP_MAIN_SHELL)")
        assertContains(main, "TestTags.APP_NAV_HOST")
        assertContains(home, ".testTag(TestTags.SCREEN_HOME)")
        assertContains(home, "modifier = Modifier.testTag(TestTags.ACTION_JUST_LIFT)")
        assertContains(justLift, ".testTag(TestTags.SCREEN_JUST_LIFT)")
    }

    @Test
    fun eulaControlsExposeScrollAgeAndAcceptSemanticsWithoutChangingGating() {
        val eula = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EulaScreen.kt")

        assertContains(eula, ".testTag(TestTags.EULA_SCROLL_CONTAINER)")
        assertContains(eula, ".testTag(TestTags.EULA_AGE_CONFIRMATION)")
        assertContains(eula, ".testTag(TestTags.EULA_ACCEPT)")
        assertContains(eula, "contentDescription = \"End User License Agreement content\"")
        assertContains(eula, "contentDescription = \"I certify that I am at least 18 years of age.\"")
        assertContains(eula, "contentDescription = \"Accept End User License Agreement\"")
        assertContains(eula, "role = Role.Checkbox")
        assertContains(eula, "onClick = onAccept")
        assertContains(eula, "enabled = canAccept")
        assertContains(eula, "val canAccept = ageConfirmed && hasScrolledToBottom")
    }

    @Test
    fun homeJustLiftRetainsVisibleLabelNavigationAndButtonSemantics() {
        val home = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt")
        val actionButton = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AnimatedActionButton.kt",
        )

        assertContains(home, "label = \"Just Lift\"")
        assertContains(home, "onClick = onJustLift")
        assertContains(home, "modifier = Modifier.testTag(TestTags.ACTION_JUST_LIFT)")
        assertContains(home, "contentDescription = \"Open Just Lift\"")
        assertContains(actionButton, "role = Role.Button")
        assertContains(actionButton, "this.onClick(label = semanticContentDescription)")
    }

    @Test
    fun connectionControlRoutesEveryStateToAStableTagAndAccessibleAction() {
        val main = source("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt")
        val connectionStart = main.indexOf("private fun ConnectionStatusIndicator(")
        val connectionEnd = main.indexOf("/**", connectionStart + 1)
        assertTrue(connectionStart >= 0, "ConnectionStatusIndicator must remain a rendered control")
        assertTrue(connectionEnd > connectionStart, "ConnectionStatusIndicator source block must be bounded")
        val connection = main.substring(connectionStart, connectionEnd)

        assertContains(connection, "isConnected -> TestTags.CONNECTION_STATUS_CONNECTED")
        assertContains(connection, "isConnecting -> TestTags.CONNECTION_STATUS_CONNECTING")
        assertContains(connection, "isError -> TestTags.CONNECTION_STATUS_ERROR")
        assertContains(connection, "else -> TestTags.CONNECTION_STATUS_DISCONNECTED")
        assertContains(connection, ".testTag(connectionStatusTag)")
        assertContains(connection, "this.contentDescription = contentDescription")
        assertContains(connection, "role = Role.Button")
        assertContains(connection, "onClick = onToggleConnection")
        assertContains(connection, "Connected to machine. Tap to disconnect")
        assertContains(connection, "Connecting to machine")
        assertContains(connection, "Connection error. Tap to reconnect")
        assertContains(connection, "Tap to connect to machine")
    }

    private fun source(relativePath: String): String = assertNotNull(
        readProjectFile(relativePath),
        "Could not locate production source $relativePath from the simulator test runner.",
    )
}

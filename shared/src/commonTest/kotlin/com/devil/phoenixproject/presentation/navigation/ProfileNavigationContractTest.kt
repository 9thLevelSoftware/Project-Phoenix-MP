package com.devil.phoenixproject.presentation.navigation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileNavigationContractTest {
    private val routes = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt",
    )
    private val main = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
    )
    private val graph = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
    )
    private val dialogs = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileDialogs.kt",
    )
    private val switcherSheet = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSwitcherSheet.kt",
    )
    private val tags = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/TestTags.kt",
    )
    private val profileScreen = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
    )
    private val justLift = source(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt",
    )

    @Test
    fun `one Profile route and the exact centered Home BottomNavItem contract`() {
        assertEquals(
            1,
            Regex("""object\s+Profile\s*:\s*NavigationRoutes\("profile"\)""")
                .findAll(routes)
                .count(),
        )
        val enumBody = bracedBlockFrom(routes, "enum class BottomNavItem")
        val entries = Regex("""(?m)^\s*([A-Z_]+)\s*\(""")
            .findAll(enumBody)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf("ANALYTICS", "INSIGHTS", "HOME", "PROFILE", "SETTINGS"),
            entries,
        )
        assertEquals(5, entries.size)
        assertContains(enumBody, "HOME(NavigationRoutes.Home.route)")
        assertFalse(enumBody.contains("WORKOUT"))
        assertFalse(main.contains("BottomNavItem.WORKOUT"))

        listOf(
            "BottomNavItem.HOME -> Icons.Default.Home",
            "BottomNavItem.HOME -> homeContentDescription",
            "BottomNavItem.HOME -> isHomeRoute",
            "BottomNavItem.HOME -> com.devil.phoenixproject.presentation.util.TestTags.NAV_HOME",
            "BottomNavItem.HOME -> onHomeClick",
        ).forEach { branch -> assertContains(main, branch) }
        assertContains(main, "homeContentDescription = stringResource(Res.string.cd_home)")
        assertContains(main, "onHomeClick = {")

        mapOf(
            "values" to "Home",
            "values-de" to "Startseite",
            "values-es" to "Inicio",
            "values-nl" to "Startpagina",
            "values-fr" to "Accueil",
        ).forEach { (directory, label) ->
            val strings = source("src/commonMain/composeResources/$directory/strings.xml")
            assertContains(strings, "<string name=\"cd_home\">$label</string>")
        }
    }

    @Test
    fun `five canonical cells fit compact width with equal accessible targets`() {
        val iteration = bracedBlockFrom(main, "BottomNavItem.entries.forEach")

        assertContains(main, ".padding(horizontal = 8.dp)")
        assertContains(main, "Arrangement.spacedBy(4.dp)")
        assertContains(main, ".selectableGroup()")
        assertEquals(1, Regex("""\.weight\(1f\)""").findAll(iteration).count())
        assertContains(iteration, "PhoenixBottomNavigationItem(")
        assertContains(main, ".heightIn(min = 48.dp)")
        assertFalse(main.contains("widthIn(min = 64.dp"))
    }

    @Test
    fun `merged tab semantics preserve all five stable navigation tags`() {
        val item = bracedBlockFrom(main, "private fun PhoenixBottomNavigationItem(")
        val clearIndex = item.indexOf(".clearAndSetSemantics")
        val tagIndex = item.indexOf(".testTag(testTag)")

        assertContains(main, ".selectableGroup()")
        assertContains(item, ".combinedClickable(")
        assertContains(item, "role = Role.Tab")
        assertContains(item, "this.selected = selected")
        assertContains(item, "this.onClick(label = contentDescription)")
        assertContains(item, "this.onLongClick(label = longClickLabel)")
        assertTrue(clearIndex >= 0 && tagIndex > clearIndex)
        assertFalse(
            Regex("""Modifier\s*\.\s*weight\(1f\)\s*\.\s*testTag\(""")
                .containsMatchIn(main),
        )
        listOf(
            "NAV_ANALYTICS",
            "NAV_INSIGHTS",
            "NAV_HOME",
            "NAV_PROFILE",
            "NAV_SETTINGS",
        ).forEach { tag ->
            assertEquals(1, Regex("""const val $tag\b""").findAll(tags).count(), tag)
            assertContains(main, "TestTags.$tag")
        }
        assertFalse(tags.contains("NAV_WORKOUTS"))
        assertFalse(main.contains("NAV_WORKOUTS"))
    }

    @Test
    fun `Profile pointer and semantic long press haptics open only the switcher`() {
        val longPress = bracedBlockFrom(main, "onProfileLongClick =")
        val item = bracedBlockFrom(main, "private fun PhoenixBottomNavigationItem(")

        assertContains(main, "HapticFeedbackType.LongPress")
        assertContains(longPress, "performHapticFeedback(HapticFeedbackType.LongPress)")
        assertContains(longPress, "profileSwitcherViewModel.openSwitcher()")
        assertFalse(longPress.contains("navController.navigate"))
        assertFalse(longPress.contains("openAddDialog"))
        assertContains(main, "BottomNavItem.PROFILE -> onProfileLongClick")
        assertContains(main, "onLongClick = itemLongClick")
        assertContains(item, "this.onLongClick(label = longClickLabel)")
    }

    @Test
    fun `normal Profile tap preserves root state and selection`() {
        val tap = bracedBlockFrom(main, "onProfileClick =")
        val bottomBarVisibility = bracedBlockFrom(main, "val shouldShowBottomBar")

        assertContains(tap, "navController.navigate(NavigationRoutes.Profile.route)")
        assertContains(tap, "popUpTo(NavigationRoutes.Home.route) { saveState = true }")
        assertContains(tap, "launchSingleTop = true")
        assertContains(tap, "restoreState = true")
        assertContains(bottomBarVisibility, "NavigationRoutes.Profile.route")
        assertTrue(
            Regex(
                """BottomNavItem\.PROFILE\s*->\s*currentRoute\s*==\s*NavigationRoutes\.Profile\.route""",
            ).containsMatchIn(main),
        )
    }

    @Test
    fun `NavGraph registers Profile callbacks and localized destination titles`() {
        assertContains(graph, "route = NavigationRoutes.Profile.route")
        assertContains(graph, "ProfileScreen(")
        assertContains(graph, "onOpenProfileSwitcher = onOpenProfileSwitcher")
        assertContains(graph, "onProfileRecoveryRequired = onProfileRecoveryRequired")
        assertContains(graph, "Res.string.nav_profile")
        assertContains(main, "val profileTitle = stringResource(Res.string.nav_profile)")
        assertContains(main, "profileTitle = profileTitle")
        assertContains(main, "NavigationRoutes.Profile.route -> profileTitle")
        assertEquals(1, Regex("""const val SCREEN_PROFILE\b""").findAll(tags).count())
        assertContains(profileScreen, ".testTag(TestTags.SCREEN_PROFILE)")
        assertFalse(main.contains("const val SCREEN_PROFILE"))
    }

    @Test
    fun `root overlays expose in-flight inline errors and blocking recovery`() {
        assertContains(main, "val switchingInFlight")
        assertContains(main, "repositorySwitching || localOperationInFlight")
        assertContains(main, "val switchingTargetProfileId")
        assertContains(main, "switchingInFlight = switchingInFlight")
        assertContains(main, "switchingTargetProfileId = switchingTargetProfileId")
        assertContains(main, "Res.string.profile_switch_failed")
        assertContains(main, "Res.string.profile_create_failed")
        assertContains(main, "Res.string.profile_recovery_retry_failed")
        assertContains(main, "ProfileRecoveryDialog(")
        assertContains(switcherSheet, "switchingInFlight: Boolean")
        assertContains(main, "isSubmitting = switchingInFlight")
        assertContains(dialogs, "errorMessage: String?")
        assertContains(dialogs, "LiveRegionMode.Polite")

        val recoveryDialog = bracedBlockFrom(dialogs, "fun ProfileRecoveryDialog(")
        assertContains(recoveryDialog, "onDismissRequest = {}")
        assertFalse(recoveryDialog.contains("dismissButton"))
        assertContains(recoveryDialog, "Res.string.profile_recovery_title")
        assertContains(recoveryDialog, "Res.string.profile_recovery_message")
    }

    @Test
    fun `legacy Home and Just Lift selectors and their four source files are absent`() {
        listOf(main, justLift).forEach { screen ->
            assertFalse(screen.contains("ProfileSidePanel"))
            assertFalse(screen.contains("ProfileSpeedDial"))
            assertFalse(screen.contains("showAddProfileDialog"))
            assertFalse(screen.contains("AddProfileDialog("))
        }

        listOf(
            "ProfileSidePanel.kt",
            "ProfileSpeedDial.kt",
            "EditProfileDialog.kt",
            "DeleteProfileDialog.kt",
        ).forEach { fileName ->
            assertNull(
                readProjectFile(
                    "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/$fileName",
                ),
                fileName,
            )
        }
    }

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    private fun bracedBlockFrom(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "Missing marker: $marker" }
        val openIndex = source.indexOf('{', markerIndex)
        require(openIndex >= 0) { "Missing opening brace after: $marker" }
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("Missing closing brace after: $marker")
    }
}

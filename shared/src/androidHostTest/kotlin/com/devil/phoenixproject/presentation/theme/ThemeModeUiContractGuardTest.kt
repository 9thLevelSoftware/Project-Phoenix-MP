package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeUiContractGuardTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String = File(projectRoot, relativePath).readText()

    @Test
    fun settingsTab_editsThemeModeInsteadOfBooleanDarkMode() {
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt")

        assertTrue(
            source.contains("themeMode: ThemeMode"),
            "SettingsTab must receive ThemeMode so System/Light/Dark can be represented.",
        )
        assertTrue(
            source.contains("onThemeModeChange: (ThemeMode) -> Unit"),
            "SettingsTab must emit ThemeMode changes directly.",
        )
        assertFalse(
            source.contains("darkModeEnabled: Boolean"),
            "SettingsTab must not collapse theme state to a dark-mode boolean.",
        )
        assertFalse(
            source.contains("onDarkModeChange: (Boolean) -> Unit"),
            "SettingsTab must not emit boolean dark-mode changes.",
        )
    }

    @Test
    fun navGraph_passesThemeModeThroughSettingsWithoutBooleanCoercion() {
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt")

        assertFalse(
            source.contains("darkModeEnabled = themeMode == ThemeMode.DARK"),
            "Settings wiring must not treat System as Light by comparing only against DARK.",
        )
        assertFalse(
            source.contains("onDarkModeChange ="),
            "Settings wiring must pass onThemeModeChange directly.",
        )
        assertTrue(
            source.contains("themeMode = themeMode"),
            "Settings wiring should pass ThemeMode directly.",
        )
        assertTrue(
            source.contains("onThemeModeChange = onThemeModeChange"),
            "Settings wiring should pass the ThemeMode callback directly.",
        )
    }

    @Test
    fun commonTheme_mapsSystemToLifecycleSafePlatformDark() {
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt")

        assertTrue(
            source.contains("ThemeMode.SYSTEM -> rememberPlatformSystemDark()"),
            "System theme mode must use the lifecycle-safe platform dark signal (Configuration.uiMode on Android) rather than the transient isSystemInDarkTheme().",
        )
        assertFalse(
            source.contains("isSystemInDarkTheme()"),
            "Theme.kt must not directly call isSystemInDarkTheme(); use rememberPlatformSystemDark() for lifecycle-safe resume reconciliation.",
        )
    }

    @Test
    fun androidSystemDarkDiagnostic_readsTheLatestComposeSignalOnResume() {
        val source = read("shared/src/androidMain/kotlin/com/devil/phoenixproject/ui/theme/PlatformSystemDark.android.kt")

        assertTrue(
            source.contains("Lifecycle.Event.ON_RESUME") && source.contains("isDark = refreshed"),
            "The Android-owned theme source must reconcile its state from Configuration.uiMode on every lifecycle resume so lock/unlock can repair a stale system appearance.",
        )
        assertTrue(
            source.contains("val currentComposeSignal by rememberUpdatedState(composeSignal)"),
            "The lifecycle observer must bridge a recomposed isSystemInDarkTheme() value with rememberUpdatedState so ON_RESUME does not compare Configuration.uiMode with the first composition's stale Compose signal.",
        )
        assertTrue(
            source.contains("currentComposeSignal != refreshed"),
            "The resume mismatch diagnostic must compare Configuration.uiMode with the current Compose signal rather than the observer's initial captured value.",
        )
    }

}

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
    fun commonTheme_mapsSystemToSystemDarkTheme() {
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt")

        assertTrue(
            source.contains("ThemeMode.SYSTEM -> isSystemInDarkTheme()"),
            "System theme mode must continue to follow the platform system dark-theme signal.",
        )
    }

    @Test
    fun iosContentView_recreatesComposeHostWhenSwiftUIColorSchemeChanges() {
        val source = read("iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift")

        assertTrue(
            source.contains("@Environment(\\.colorScheme)"),
            "ContentView should observe SwiftUI colorScheme changes.",
        )
        assertTrue(
            source.contains(".id(colorScheme)"),
            "Compose host should be recreated when the system color scheme changes on iOS.",
        )
    }
}

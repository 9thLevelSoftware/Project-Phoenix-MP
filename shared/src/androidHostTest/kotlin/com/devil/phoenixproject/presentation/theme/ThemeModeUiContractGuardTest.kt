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
    fun commonTheme_mapsSystemThroughResolver() {
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt")
        assertTrue(
            source.contains("resolveUseDarkColors(") &&
                source.contains("rememberPlatformSystemDark()"),
            "SYSTEM must go through resolveUseDarkColors + rememberPlatformSystemDark, not a raw isSystemInDarkTheme() call.",
        )
        assertFalse(
            source.contains("isSystemInDarkTheme()"),
            "Theme.kt must not call isSystemInDarkTheme() directly.",
        )
    }

    @Test
    fun androidSystemDark_usesResolverAndApplicationConfiguration() {
        val source = read(
            "shared/src/androidMain/kotlin/com/devil/phoenixproject/ui/theme/PlatformSystemDark.android.kt",
        )
        assertTrue(
            source.contains("resolveSystemDark("),
            "Android SYSTEM appearance must call resolveSystemDark so UNDEFINED cannot latch light.",
        )
        assertTrue(
            source.contains("applicationContext.resources.configuration"),
            "Android SYSTEM appearance must read application configuration, not only Activity resources.",
        )
        assertTrue(
            source.contains("Lifecycle.Event.ON_RESUME"),
            "Resume must still re-sample, but through the resolver.",
        )
        assertFalse(
            Regex("""isDark\s*=\s*refreshed""").containsMatchIn(source),
            "Do not assign a raw Activity uiMode boolean on resume. That is the #691 latch.",
        )
    }

}

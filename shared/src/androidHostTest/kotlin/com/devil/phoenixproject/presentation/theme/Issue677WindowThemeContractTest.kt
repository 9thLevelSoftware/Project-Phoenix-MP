package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Issue677WindowThemeContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String = File(projectRoot, relativePath).readText()

    @Test
    fun valuesTheme_isNotMaterialLight() {
        val theme = read("androidApp/src/main/res/values/themes.xml")
        assertFalse(
            theme.contains("Theme.Material.Light"),
            "values/themes.xml must not use Theme.Material.Light. That Light parent is the recreation flash and hostile Activity context for #677.",
        )
        assertTrue(
            theme.contains("@color/phoenix_window_background"),
            "values/themes.xml must set android:windowBackground to @color/phoenix_window_background.",
        )
    }

    @Test
    fun windowBackgroundColor_dayIsSlate50_nightIsSlate900() {
        val day = read("androidApp/src/main/res/values/colors.xml")
        assertTrue(
            day.contains("#FFF8FAFC") || day.contains("#F8FAFC"),
            "Day phoenix_window_background must be Slate50 so LIGHT / SYSTEM+light does not flash Slate900.",
        )
        val night = read("androidApp/src/main/res/values-night/colors.xml")
        assertTrue(
            night.contains("#FF0F172A") || night.contains("#0F172A"),
            "Night phoenix_window_background must be Slate900 so SYSTEM+dark cannot inherit a light window.",
        )
    }

    @Test
    fun manifest_doesNotHandleUiModeInConfigChanges() {
        val manifest = read("androidApp/src/main/AndroidManifest.xml")
        val activityBlock = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertFalse(
            activityBlock.contains("uiMode"),
            "Do not put uiMode back in configChanges without an onConfigurationChanged handler.",
        )
    }

    @Test
    fun mainActivity_enableEdgeToEdgeUsesPersistedThemeAndApplicationNight() {
        val source = read("androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt")
        assertTrue(source.contains("SystemBarStyle"))
        assertTrue(source.contains("THEME_MODE_KEY"))
        assertTrue(source.contains("applicationContext.resources.configuration"))
        assertTrue(source.contains("enableEdgeToEdge("))
        assertTrue(source.contains("setBackgroundDrawable"))
        assertTrue(source.contains("R.color.phoenix_window_background_dark"))
        assertTrue(source.contains("R.color.phoenix_window_background_light"))
        assertFalse(source.contains("0xFF0F172A"), "MainActivity must not hardcode the dark window hex.")
        assertFalse(source.contains("0xFFF8FAFC"), "MainActivity must not hardcode the light window hex.")
    }

    @Test
    fun mainActivityWindowColors_areUnqualifiedResources() {
        val day = read("androidApp/src/main/res/values/colors.xml")
        val night = read("androidApp/src/main/res/values-night/colors.xml")
        assertTrue(day.contains("name=\"phoenix_window_background_light\""))
        assertTrue(day.contains("name=\"phoenix_window_background_dark\""))
        assertTrue(
            day.contains("#FFF8FAFC") || day.contains("#F8FAFC"),
            "Unqualified light window color must stay Slate50.",
        )
        assertTrue(
            day.contains("#FF0F172A") || day.contains("#0F172A"),
            "Unqualified dark window color must stay Slate900.",
        )
        assertFalse(
            night.contains("phoenix_window_background_light"),
            "Light window color must stay unqualified so a night configuration cannot swap it.",
        )
        assertFalse(
            night.contains("phoenix_window_background_dark"),
            "Dark window color must stay unqualified so a night configuration cannot swap it.",
        )
    }

    @Test
    fun application_appliesPersistedNightModeBeforeActivity() {
        val app = read("androidApp/src/main/kotlin/com/devil/phoenixproject/PhoenixApp.kt")
        assertTrue(
            app.contains("applyPersistedApplicationNightMode(") &&
                app.contains("attachBaseContext"),
            "PhoenixApp must apply persisted night mode in attachBaseContext so the OS starting window uses the matching values/values-night qualifier.",
        )
        val helper = read("shared/src/androidMain/kotlin/com/devil/phoenixproject/ui/theme/PlatformNightMode.android.kt")
        assertTrue(helper.contains("setApplicationNightMode"))
        assertTrue(helper.contains("THEME_MODE_KEY"))
        assertTrue(helper.contains("MODE_NIGHT_NO"))
        assertTrue(helper.contains("MODE_NIGHT_YES"))
        assertTrue(helper.contains("MODE_NIGHT_AUTO"))
        val viewModel = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModel.kt")
        assertTrue(
            viewModel.contains("applyPlatformNightMode("),
            "ThemeViewModel.setThemeMode must reapply application night mode so SYSTEM can follow the device after LIGHT/DARK.",
        )
    }
}

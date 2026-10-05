package com.devil.phoenixproject.data.preferences

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cold-start locale and workout notification copy read the language preference
 * before [SettingsPreferencesManager] is constructed. Both must use the same
 * public key the manager writes.
 */
class LanguagePreferenceKeyContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String = File(projectRoot, relativePath).readText()

    @Test
    fun coldStartLocaleAndNotificationCopyUsePublicKeyLanguage() {
        val preferences = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt",
        )
        val mainActivity = read("androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt")
        val service = read(
            "androidApp/src/main/kotlin/com/devil/phoenixproject/service/WorkoutForegroundService.kt",
        )

        assertTrue(preferences.contains("const val KEY_LANGUAGE = \"language\""))
        assertFalse(preferences.contains("private const val KEY_LANGUAGE"))
        assertTrue(mainActivity.contains("SettingsPreferencesManager.KEY_LANGUAGE"))
        assertFalse(mainActivity.contains("getString(\"language\""))
        assertTrue(service.contains("SettingsPreferencesManager.KEY_LANGUAGE"))
        assertFalse(service.contains("PERSISTED_LANGUAGE_KEY"))
        assertFalse(service.contains("\"language\""))
    }
}

package com.devil.phoenixproject.util

import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locale-default formatting must follow the in-app language. A blank language
 * is the system-default choice and must not replace [Locale.getDefault].
 */
class DefaultLocaleSyncTest {

    private lateinit var previous: Locale

    @BeforeTest
    fun setUp() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(previous)
    }

    @Test
    fun nonBlankLanguageReplacesJvmDefaultFormatting() {
        syncDefaultLocale("de")

        assertEquals("de", Locale.getDefault().language)
        assertEquals("1,5", String.format("%.1f", 1.5))
    }

    @Test
    fun blankLanguageLeavesJvmDefaultUntouched() {
        syncDefaultLocale("")
        syncDefaultLocale("   ")

        assertEquals(Locale.US, Locale.getDefault())
        assertEquals("1.5", String.format("%.1f", 1.5))
    }

    @Test
    fun applyAppLocaleUpdatesDefaultEvenWithoutAnActivity() {
        applyAppLocale("fr")

        assertEquals("fr", Locale.getDefault().language)
        assertEquals("1,5", String.format("%.1f", 1.5))
    }

    @Test
    fun applyAppLocaleBlankLeavesDefaultUntouched() {
        applyAppLocale("")

        assertEquals(Locale.US, Locale.getDefault())
        assertEquals("1.5", String.format("%.1f", 1.5))
    }

    @Test
    fun processStartReadsPersistedLanguageIntoDefaultLocale() {
        val app = read("androidApp/src/main/kotlin/com/devil/phoenixproject/PhoenixApp.kt")

        assertTrue(app.contains("syncDefaultLocale(langCode.orEmpty())"))
        assertTrue(app.contains("SettingsPreferencesManager.KEY_LANGUAGE"))
        assertTrue(app.contains("ThemeViewModel.THEME_PREFS_FILE"))
    }

    private fun read(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        return File(dir, relativePath).readText()
    }
}

package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ThemeViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Test
    fun `theme mode enum names are stable persisted values`() {
        assertEquals("SYSTEM", ThemeMode.SYSTEM.name)
        assertEquals("LIGHT", ThemeMode.LIGHT.name)
        assertEquals("DARK", ThemeMode.DARK.name)
    }

    @Test
    fun `defaults to system theme when no preference saved`() = runTest {
        val viewModel = ThemeViewModel(MapSettings())

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
        assertEquals(false, viewModel.dynamicColorEnabled.value)
    }

    @Test
    fun `invalid saved theme defaults to system theme`() = runTest {
        val settings = MapSettings()
        settings.putString("theme_mode", "not-a-theme")

        val viewModel = ThemeViewModel(settings)

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
    }

    @Test
    fun `stored theme mode values restore from settings`() = runTest {
        ThemeMode.entries.forEach { mode ->
            val settings = MapSettings()
            settings.putString("theme_mode", mode.name)

            val viewModel = ThemeViewModel(settings)

            assertEquals(mode, viewModel.themeMode.value)
        }
    }

    @Test
    fun `setThemeMode updates state and persists every mode`() = runTest {
        val settings = MapSettings()
        val viewModel = ThemeViewModel(settings)

        ThemeMode.entries.forEach { mode ->
            viewModel.setThemeMode(mode)
            advanceUntilIdle()

            assertEquals(mode, viewModel.themeMode.value)
            assertEquals(mode.name, settings.getStringOrNull("theme_mode"))
        }
    }

    @Test
    fun `setDynamicColorEnabled updates state and persists`() = runTest {
        val settings = MapSettings()
        val viewModel = ThemeViewModel(settings)

        viewModel.setDynamicColorEnabled(true)
        advanceUntilIdle()

        assertEquals(true, viewModel.dynamicColorEnabled.value)
        assertEquals(true, settings.getBoolean("dynamic_color_enabled", false))

        viewModel.setDynamicColorEnabled(false)
        advanceUntilIdle()

        assertEquals(false, viewModel.dynamicColorEnabled.value)
        assertEquals(false, settings.getBoolean("dynamic_color_enabled", true))
    }
}

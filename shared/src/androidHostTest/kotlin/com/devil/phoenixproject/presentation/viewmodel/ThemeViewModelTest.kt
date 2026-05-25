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
    fun `defaults to system theme when no preference saved`() = runTest {
        val viewModel = ThemeViewModel(MapSettings())

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
        assertEquals(false, viewModel.dynamicColorEnabled.value)
    }

    @Test
    fun `setThemeMode updates state and persists`() = runTest {
        val settings = MapSettings()
        val viewModel = ThemeViewModel(settings)

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
        assertEquals(ThemeMode.DARK.name, settings.getStringOrNull("theme_mode"))
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

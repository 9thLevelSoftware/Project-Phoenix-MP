package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class ThemeViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Test
    fun `defaults to system theme when no preference saved`() = runTest {
        val viewModel = ThemeViewModel(MapSettings())

        assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)
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
}

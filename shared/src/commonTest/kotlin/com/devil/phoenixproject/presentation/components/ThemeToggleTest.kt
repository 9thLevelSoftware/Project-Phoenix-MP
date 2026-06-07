package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeToggleTest {

    @Test
    fun `theme toggle cycles system to light`() {
        assertEquals(ThemeMode.LIGHT, nextThemeModeAfterToggle(ThemeMode.SYSTEM))
    }

    @Test
    fun `theme toggle cycles light to dark`() {
        assertEquals(ThemeMode.DARK, nextThemeModeAfterToggle(ThemeMode.LIGHT))
    }

    @Test
    fun `theme toggle cycles dark to system`() {
        assertEquals(ThemeMode.SYSTEM, nextThemeModeAfterToggle(ThemeMode.DARK))
    }
}

package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Color theme for superset visual distinction.
 * Maps domain SupersetColors indices to actual Compose Color values.
 *
 * These colors cycle through 4 distinct, accessible colors:
 * - Indigo (index 0): #6366F1
 * - Pink (index 1): #EC4899
 * - Green (index 2): #10B981
 * - Amber (index 3): #F59E0B
 */
object SupersetTheme {
    val Indigo = Color(0xFF6366F1)
    val Pink = Color(0xFFEC4899)
    val Green = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)

    val colors = listOf(Indigo, Pink, Green, Amber)

    /**
     * Get the color for a given superset color index.
     * Cycles through the 4 colors if index exceeds range.
     */
    fun colorForIndex(index: Int): Color = colors[index % colors.size]

    /**
     * Get a background tint color for superset containers.
     * Uses lower alpha for subtle visual grouping.
     *
     * @param index The superset color index
     * @param isDarkTheme Whether the app is in dark theme mode
     * @return A semi-transparent version of the superset color
     */
    @Composable
    fun backgroundTint(index: Int, isDarkTheme: Boolean): Color {
        val base = colorForIndex(index)
        val alpha = if (isDarkTheme) 0.12f else 0.08f
        return base.copy(alpha = alpha)
    }
}

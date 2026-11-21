package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * Theme helper functions for consistent styling across screens.
 */

/**
 * Returns true when the current MaterialTheme background is dark (luminance < 0.5).
 * Respects the app's ThemeMode setting rather than the system theme.
 * Single source of truth for the dark/light threshold used by all background brushes.
 */
@Composable
private fun isDarkBackground(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Returns an opaque vertical gradient brush for screen backgrounds.
 * All stops are composited over an opaque base surface so the Activity window never bleeds through.
 *
 * Uses MaterialTheme.colorScheme to detect dark/light mode,
 * respecting the app's theme preference (not just system theme).
 */
@Composable
fun screenBackgroundBrush(): Brush {
    val isDark = isDarkBackground()
    val colorScheme = MaterialTheme.colorScheme
    return if (isDark) {
        val baseSurface = colorScheme.surfaceContainerLowest.copy(alpha = 1f)
        Brush.verticalGradient(
            0.0f to baseSurface,
            0.5f to colorScheme.primary.copy(alpha = 0.12f).compositeOver(baseSurface),
            1.0f to baseSurface,
        )
    } else {
        val baseSurface = colorScheme.surfaceContainerLowest.copy(alpha = 1f)
        val bottomSurface = colorScheme.surface.copy(alpha = 1f)
        Brush.verticalGradient(
            0.0f to baseSurface,
            0.5f to colorScheme.primary.copy(alpha = 0.15f).compositeOver(baseSurface),
            1.0f to bottomSurface,
        )
    }
}

/**
 * Returns an opaque vertical gradient brush for celebration screens (e.g. RoutineCompleteScreen).
 * Uses the flame triplet (FlameOrange / FlameYellow / FlameRed) composited over an opaque
 * surface base colour so the Activity window never bleeds through.
 */
@Composable
fun celebrationBackgroundBrush(): Brush {
    val isDark = isDarkBackground()
    val colorScheme = MaterialTheme.colorScheme
    return if (isDark) {
        val baseSurface = colorScheme.surfaceContainerLowest.copy(alpha = 1f)
        Brush.verticalGradient(
            0.00f to baseSurface,
            0.25f to FlameOrange.copy(alpha = 0.20f).compositeOver(baseSurface),
            0.50f to FlameYellow.copy(alpha = 0.15f).compositeOver(baseSurface),
            0.75f to FlameRed.copy(alpha = 0.15f).compositeOver(baseSurface),
            1.00f to baseSurface,
        )
    } else {
        val baseSurface = colorScheme.surfaceContainerLowest.copy(alpha = 1f)
        val bottomSurface = colorScheme.surface.copy(alpha = 1f)
        Brush.verticalGradient(
            0.00f to baseSurface,
            0.25f to FlameOrange.copy(alpha = 0.10f).compositeOver(baseSurface),
            0.50f to FlameYellow.copy(alpha = 0.08f).compositeOver(baseSurface),
            0.75f to FlameRed.copy(alpha = 0.08f).compositeOver(baseSurface),
            1.00f to bottomSurface,
        )
    }
}

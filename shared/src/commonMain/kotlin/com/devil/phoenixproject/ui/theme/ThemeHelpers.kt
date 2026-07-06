package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
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
 * Returns a vertical gradient brush for screen backgrounds.
 * Dark mode: Slate with subtle primary (orange) accent at centre — clear warm glow.
 * Light mode: White with primary tint at 15% alpha — perceptible brand warmth (gap-2-12).
 *
 * Uses MaterialTheme.colorScheme to detect dark/light mode,
 * respecting the app's theme preference (not just system theme).
 */
@Composable
fun screenBackgroundBrush(): Brush {
    val isDark = isDarkBackground()
    return if (isDark) {
        Brush.verticalGradient(
            0.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.5f to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
        )
    } else {
        Brush.verticalGradient(
            0.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.5f to MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            1.0f to MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * Returns a vertical gradient brush for celebration screens (e.g. RoutineCompleteScreen).
 * Uses the flame triplet (FlameOrange / FlameYellow / FlameRed) at low alpha over the
 * surface base colour, mirroring screenBackgroundBrush()'s dark/light split.
 *
 * Dark mode: colours are slightly more opaque so the warm fire glow reads against dark slate.
 * Light mode: colours are kept very translucent so the gradient stays tasteful on white.
 */
@Composable
fun celebrationBackgroundBrush(): Brush {
    val isDark = isDarkBackground()
    return if (isDark) {
        Brush.verticalGradient(
            0.00f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.25f to FlameOrange.copy(alpha = 0.20f),
            0.50f to FlameYellow.copy(alpha = 0.15f),
            0.75f to FlameRed.copy(alpha = 0.15f),
            1.00f to MaterialTheme.colorScheme.surfaceContainerLowest,
        )
    } else {
        Brush.verticalGradient(
            0.00f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.25f to FlameOrange.copy(alpha = 0.10f),
            0.50f to FlameYellow.copy(alpha = 0.08f),
            0.75f to FlameRed.copy(alpha = 0.08f),
            1.00f to MaterialTheme.colorScheme.surface,
        )
    }
}

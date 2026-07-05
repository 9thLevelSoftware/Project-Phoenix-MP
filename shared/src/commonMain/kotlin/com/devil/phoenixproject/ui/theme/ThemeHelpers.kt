package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance

/**
 * Theme helper functions for consistent styling across screens.
 */

/**
 * Returns a vertical gradient brush for screen backgrounds.
 * Dark mode: Slate with subtle plum accent in center
 * Light mode: Light with subtle mint wash
 *
 * Uses MaterialTheme.colorScheme to detect dark/light mode,
 * respecting the app's theme preference (not just system theme).
 */
@Composable
fun screenBackgroundBrush(): Brush {
    // Check if we're in dark mode by examining the background color luminance
    // This respects the app's ThemeMode setting, not just system theme
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) {
        Brush.verticalGradient(
            0.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.5f to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
        )
    } else {
        Brush.verticalGradient(
            0.0f to MaterialTheme.colorScheme.surfaceContainerLowest,
            0.5f to MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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

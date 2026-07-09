package com.devil.phoenixproject.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic color/elevation accessors for Phoenix's high-visibility chrome surfaces.
 *
 * Issue #640 was caused by Android dynamic dark color schemes returning light
 * wallpaper-derived values for surface roles that the Routines screen uses as
 * structural chrome: the top app bar, bottom navigation bar, and routine cards.
 * Keeping these reads behind tiny helpers gives tests a stable behavior contract
 * without parsing Kotlin source files or matching formatting-sensitive call-site
 * strings.
 */
internal fun phoenixTopAppBarContainerColor(colorScheme: ColorScheme): Color =
    colorScheme.surface

internal fun phoenixBottomNavigationContainerColor(colorScheme: ColorScheme): Color =
    colorScheme.surfaceContainerHigh

internal fun routineCardContainerColor(
    colorScheme: ColorScheme,
    isSelected: Boolean,
): Color = if (isSelected) {
    colorScheme.primaryContainer.copy(alpha = 0.4f)
} else {
    colorScheme.surfaceContainerHighest
}

internal fun routineCardDefaultElevation(expanded: Boolean): Dp =
    if (expanded) 8.dp else 2.dp

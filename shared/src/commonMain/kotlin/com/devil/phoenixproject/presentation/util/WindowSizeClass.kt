package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Represents the size class of the window for responsive layouts.
 * Based on Material 3 window size class breakpoints.
 */
enum class WindowWidthSizeClass {
    /** Phones in portrait (< 600dp) */
    Compact,
    /** Small tablets, phones in landscape (600-840dp) */
    Medium,
    /** Large tablets, desktops (> 840dp) */
    Expanded
}

enum class WindowHeightSizeClass {
    /** Short screens (< 480dp) */
    Compact,
    /** Medium height (480-900dp) */
    Medium,
    /** Tall screens (> 900dp) */
    Expanded
}

data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    val isExpandedTablet: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * CompositionLocal for accessing WindowSizeClass throughout the app.
 * Defaults to Compact (phone) if not provided.
 */
val LocalWindowSizeClass = compositionLocalOf {
    WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Compact,
        heightSizeClass = WindowHeightSizeClass.Medium,
        widthDp = 400.dp,
        heightDp = 800.dp
    )
}

/**
 * Calculate WindowSizeClass from screen dimensions.
 */
fun calculateWindowSizeClass(widthDp: Dp, heightDp: Dp): WindowSizeClass {
    val widthClass = when {
        widthDp < 600.dp -> WindowWidthSizeClass.Compact
        widthDp < 840.dp -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightClass = when {
        heightDp < 480.dp -> WindowHeightSizeClass.Compact
        heightDp < 900.dp -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return WindowSizeClass(
        widthSizeClass = widthClass,
        heightSizeClass = heightClass,
        widthDp = widthDp,
        heightDp = heightDp
    )
}

/**
 * Responsive dimension helpers for common UI patterns.
 */
object ResponsiveDimensions {

    /**
     * Calculate responsive chart height based on window size.
     * @param baseHeight The phone-sized height (compact)
     * @param mediumMultiplier Scale factor for medium tablets (default 1.25)
     * @param expandedMultiplier Scale factor for large tablets (default 1.5)
     */
    @Composable
    fun chartHeight(
        baseHeight: Dp,
        mediumMultiplier: Float = 1.25f,
        expandedMultiplier: Float = 1.5f
    ): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> baseHeight * expandedMultiplier
            WindowWidthSizeClass.Medium -> baseHeight * mediumMultiplier
            WindowWidthSizeClass.Compact -> baseHeight
        }
    }

    /**
     * Calculate max width for cards to prevent over-stretching on tablets.
     * Returns null for phones (use full width), or a max width for tablets.
     */
    @Composable
    fun cardMaxWidth(): Dp? {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 600.dp
            WindowWidthSizeClass.Medium -> 500.dp
            WindowWidthSizeClass.Compact -> null  // No max, use full width
        }
    }

    /**
     * Calculate responsive component size (for gauges, HUDs, etc.)
     */
    @Composable
    fun componentSize(baseSize: Dp): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> baseSize * 1.6f
            WindowWidthSizeClass.Medium -> baseSize * 1.3f
            WindowWidthSizeClass.Compact -> baseSize
        }
    }
}

// Extension for Dp multiplication
private operator fun Dp.times(factor: Float): Dp = (this.value * factor).dp

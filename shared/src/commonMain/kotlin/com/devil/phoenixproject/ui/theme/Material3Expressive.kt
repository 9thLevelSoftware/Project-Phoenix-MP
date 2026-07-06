package com.devil.phoenixproject.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Motion Specs
 * Fluid, organic, and playful spring animations
 */
object ExpressiveMotion {
    /**
     * Standard expressive spring for most interactions (buttons, cards)
     * Low stiffness (relaxed) + Low bounciness (playful but not chaotic)
     */
    val SpringDefault = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /**
     * Snappy spring for quick transitions (toggles, checkboxes)
     */
    val SpringSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * Bouncy spring for emphasis (errors, attention grabbers)
     */
    val SpringBouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioHighBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /**
     * Bouncy spring typed for Color — use with animateColorAsState.
     * Same character as SpringBouncy but carries the Color type parameter
     * so Kotlin inference works without extra casting.
     */
    val SpringBouncyColor = spring<Color>(
        dampingRatio = Spring.DampingRatioHighBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /**
     * Standard spring typed for IntSize — use with expandVertically() / shrinkVertically().
     * Same character as SpringDefault but carries the IntSize type parameter
     * required by expand/shrink enter/exit transitions.
     */
    val SpringDefaultIntSize = spring<IntSize>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /**
     * Fast no-overshoot spring for collapsing a value to zero — use when scaling
     * down to 0f, where any bounce would produce a negative-scale glitch.
     */
    val SpringCollapseToZero = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )
}

/**
 * Material 3 Expressive Card Colors
 * Uses surfaceContainerHighest for better contrast
 */
@Composable
fun expressiveCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
)

/**
 * Material 3 Expressive Card Shape
 * More rounded corners (20dp vs standard 16dp)
 */
val expressiveCardShape = ExpressiveShapeValues.Medium

/**
 * Material 3 Expressive Card Elevation
 * Higher elevation (8dp vs standard 4dp)
 */
@Composable
fun expressiveCardElevation(pressed: Boolean = false) = CardDefaults.cardElevation(
    defaultElevation = if (pressed) 4.dp else 8.dp,
)

/**
 * Material 3 Expressive Card Border
 * Thicker border (2dp vs standard 1dp)
 */
@Composable
fun expressiveCardBorder() = BorderStroke(
    2.dp,
    MaterialTheme.colorScheme.outlineVariant,
)

/**
 * Material 3 Expressive Button Shape
 * More rounded corners
 */
val expressiveButtonShape = ExpressiveShapeValues.Medium

/**
 * Material 3 Expressive Button Elevation
 * Higher elevation
 */
@Composable
fun expressiveButtonElevation() = androidx.compose.material3.ButtonDefaults.buttonElevation(
    defaultElevation = 4.dp,
    pressedElevation = 2.dp,
)

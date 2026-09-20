package com.devil.phoenixproject.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

/**
 * Material 3 Expressive Motion Specs
 * Fluid, organic, and playful spring animations
 */
object ExpressiveMotion {
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

package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive Shape System
 * More rounded, bolder shapes for expressive design
 */
object ExpressiveShapeValues {
    // Expressive: More rounded corners than standard Material 3
    val ExtraSmall = RoundedCornerShape(8.dp)   // Standard: 4dp
    val Small = RoundedCornerShape(12.dp)        // Standard: 8dp
    val Medium = RoundedCornerShape(20.dp)      // Standard: 12dp (much more rounded)
    val Large = RoundedCornerShape(28.dp)        // Standard: 16dp (very rounded)
    val ExtraLarge = RoundedCornerShape(32.dp)   // Pill-shaped for buttons
}

/**
 * Material 3 Expressive Shapes for MaterialTheme
 */
val ExpressiveShapes = Shapes(
    extraSmall = ExpressiveShapeValues.ExtraSmall,
    small = ExpressiveShapeValues.Small,
    medium = ExpressiveShapeValues.Medium,
    large = ExpressiveShapeValues.Large,
    extraLarge = ExpressiveShapeValues.ExtraLarge
)

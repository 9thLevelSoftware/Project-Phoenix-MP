package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

/**
 * Semantic color palette for accessibility-aware UI elements.
 *
 * Uses the standard codebase colors (red/green/amber).
 * Access via [AccessibilityTheme.colors] in any composable.
 */
@Immutable
data class AccessibilityColors(
    // Semantic status
    val success: Color,
    val error: Color,
    val warning: Color,
    val neutral: Color,

    // Velocity zones (VBT standard: Cyan=Explosive -> Red=Grind)
    val zoneExplosive: Color,
    val zoneFast: Color,
    val zoneModerate: Color,
    val zoneSlow: Color,
    val zoneGrind: Color,

    // Asymmetry severity
    val asymmetryGood: Color,
    val asymmetryCaution: Color,
    val asymmetryBad: Color,

    // Rep quality
    val qualityExcellent: Color,
    val qualityGood: Color,
    val qualityFair: Color,
    val qualityBelowAverage: Color,
    val qualityPoor: Color,

    // Reserved for future phases (readiness card)
    val statusGreen: Color,
    val statusYellow: Color,
    val statusRed: Color
)

/**
 * Standard palette -- preserves current visual appearance.
 * Velocity zones aligned to BiomechanicsHistoryCard mapping (VBT standard):
 * Cyan=Explosive, Green=Fast, Amber=Moderate, Orange=Slow, Red=Grind.
 */
val StandardPalette = AccessibilityColors(
    // Semantic status (from Color.kt SignalSuccess/Error/Warning)
    success = Color(0xFF22C55E),
    error = Color(0xFFEF4444),
    warning = Color(0xFFF59E0B),
    neutral = Color(0xFF9E9E9E),

    // Velocity zones (BiomechanicsHistoryCard canonical mapping)
    zoneExplosive = Color(0xFF06B6D4),   // Cyan -- fastest
    zoneFast = Color(0xFF22C55E),         // Green
    zoneModerate = Color(0xFFF59E0B),     // Amber
    zoneSlow = Color(0xFFF97316),         // Orange
    zoneGrind = Color(0xFFEF4444),        // Red -- near failure

    // Asymmetry severity
    asymmetryGood = Color(0xFF4CAF50),
    asymmetryCaution = Color(0xFFFFC107),
    asymmetryBad = Color(0xFFF44336),

    // Rep quality
    qualityExcellent = Color(0xFF00E676),
    qualityGood = Color(0xFF43A047),
    qualityFair = Color(0xFFFDD835),
    qualityBelowAverage = Color(0xFFFF9800),
    qualityPoor = Color(0xFFE53935),

    // Reserved -- match status colors for now
    statusGreen = Color(0xFF22C55E),
    statusYellow = Color(0xFFF59E0B),
    statusRed = Color(0xFFEF4444)
)

/**
 * CompositionLocal for the active accessibility color palette.
 * Static (staticCompositionLocalOf) -- entire value swaps on mode change.
 */
val LocalAccessibilityColors = staticCompositionLocalOf { StandardPalette }

/**
 * Convenience accessor for accessibility colors within composables.
 *
 * Usage:
 * ```kotlin
 * val color = AccessibilityTheme.colors.success
 * ```
 */
object AccessibilityTheme {
    val colors: AccessibilityColors
        @Composable @ReadOnlyComposable
        get() = LocalAccessibilityColors.current
}

/**
 * Returns the theme-aware color for a velocity zone.
 * Consolidates duplicate implementations from WorkoutHud, SetSummaryCard,
 * and BiomechanicsHistoryCard into a single source of truth.
 */
@Composable
fun velocityZoneColor(zone: BiomechanicsVelocityZone): Color {
    val colors = AccessibilityTheme.colors
    return when (zone) {
        BiomechanicsVelocityZone.EXPLOSIVE -> colors.zoneExplosive
        BiomechanicsVelocityZone.FAST -> colors.zoneFast
        BiomechanicsVelocityZone.MODERATE -> colors.zoneModerate
        BiomechanicsVelocityZone.SLOW -> colors.zoneSlow
        BiomechanicsVelocityZone.GRIND -> colors.zoneGrind
    }
}

/**
 * Returns the translatable human-readable label for a velocity zone.
 * Must be called from a @Composable context.
 */
@Composable
fun velocityZoneLabel(zone: BiomechanicsVelocityZone): String = when (zone) {
    BiomechanicsVelocityZone.EXPLOSIVE -> stringResource(Res.string.zone_explosive)
    BiomechanicsVelocityZone.FAST -> stringResource(Res.string.zone_fast)
    BiomechanicsVelocityZone.MODERATE -> stringResource(Res.string.zone_moderate)
    BiomechanicsVelocityZone.SLOW -> stringResource(Res.string.zone_slow)
    BiomechanicsVelocityZone.GRIND -> stringResource(Res.string.zone_grind)
}

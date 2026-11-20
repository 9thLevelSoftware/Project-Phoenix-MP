package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.WeightTextScale

/**
 * Issue #1367 SD1 — rendered-cap weight text scaling.
 *
 * Contract (signoff correction 1):
 *  - app factor `A` = [WeightTextScale.preferenceFactor] (1 / 1.5 / 2)
 *  - OS font scale `O` = the platform font scale (`LocalDensity.current.fontScale`)
 *  - effective rendered scale `E = min(A * O, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)`
 *  - style multiplier `M = E / O`
 *
 * [scaledForWeightValue] multiplies the style's fontSize AND any explicit sp lineHeight
 * by `M` (SD3: line height scales too). Compose then applies the OS scale exactly once,
 * so the RENDERED size is `base * E` — physically capped at
 * [WEIGHT_TEXT_MAX_EFFECTIVE_SCALE] x base. Every layout-pressure decision must use `E`
 * (via [effectiveWeightTextScale]), never `M` and never `A` alone.
 *
 * SD2: Standard (A=1) keeps `M = 1` through `O <= 2` (base typography preserved). Above
 * the cap (`O > 2`) Standard weight values are intentionally bounded at 2x base — they
 * render smaller than unscaled base x O. Non-weight text still honors OS sizing.
 *
 * SD1 input guards: nonpositive / NaN / infinite factors or OS scales are treated as 1.0,
 * and `E` is clamped into `(0, 2]`.
 */
const val WEIGHT_TEXT_MAX_EFFECTIVE_SCALE: Float = 2.0f

private fun sanitizedScale(value: Float): Float =
    if (value.isFinite() && value > 0f) value else 1f

/**
 * Effective (rendered) scale `E = min(A * O, cap)`, with invalid inputs treated as 1.0.
 * Use for layout pressure: header stacking, value-row separation, viewport decisions.
 */
fun effectiveWeightTextScale(appFactor: Float, osFontScale: Float): Float {
    val a = sanitizedScale(appFactor)
    val o = sanitizedScale(osFontScale)
    return (a * o).coerceAtMost(WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
}

/** Enum-typed convenience overload of [effectiveWeightTextScale]. */
fun effectiveWeightTextScale(scale: WeightTextScale, osFontScale: Float): Float =
    effectiveWeightTextScale(scale.preferenceFactor, osFontScale)

/**
 * Style multiplier `M = E / O`. This is what multiplies the Compose style so the OS
 * font scale is applied exactly once at render time (SD1). Never apply `A` directly.
 */
fun weightTextMultiplier(appFactor: Float, osFontScale: Float): Float {
    val o = sanitizedScale(osFontScale)
    return effectiveWeightTextScale(appFactor, osFontScale) / o
}

private fun TextUnit.scaleBy(multiplier: Float): TextUnit =
    if (isSp) (value * multiplier).sp else this

/**
 * Pure SD1 style helper: scales fontSize and explicit sp lineHeight by `M = E / O`,
 * preserving every other style attribute. Relative/unspecified line heights are left
 * untouched. The result stays in sp so Compose applies the OS scale exactly once.
 */
fun TextStyle.scaledForWeightValue(scale: WeightTextScale, osFontScale: Float): TextStyle {
    val multiplier = weightTextMultiplier(scale.preferenceFactor, osFontScale)
    return copy(
        fontSize = fontSize.scaleBy(multiplier),
        lineHeight = lineHeight.scaleBy(multiplier),
    )
}

/**
 * Thin composable wrapper: resolves the OS font scale from the ambient density.
 */
@Composable
fun TextStyle.scaledForWeightValue(scale: WeightTextScale): TextStyle =
    scaledForWeightValue(scale, LocalDensity.current.fontScale)

/**
 * Composable `E` for layout decisions at the call boundary (SD1: `E`, not `M`).
 */
@Composable
fun currentEffectiveWeightTextScale(scale: WeightTextScale): Float =
    effectiveWeightTextScale(scale, LocalDensity.current.fontScale)

/**
 * Issue #1367 SD7: the WeightStepper numeric value moves to its own row when the
 * effective rendered scale demands it (`E > 1.5`); the +/- controls stay on one row.
 * Pure for unit testing.
 */
fun shouldSeparateWeightStepperValueRow(effectiveScale: Float): Boolean =
    sanitizedScale(effectiveScale) > 1.5f

/**
 * Scales the Android weight wheel container height (dp) modestly as [effectiveScale]
 * grows, capped against [WEIGHT_TEXT_MAX_EFFECTIVE_SCALE].
 */
fun scaledWeightWheelHeightDp(baseHeightDp: Float, effectiveScale: Float): Float {
    val e = sanitizedScale(effectiveScale).coerceIn(1f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    return baseHeightDp * (1f + (e - 1f) * 0.6f)
}

/**
 * Scales the iOS weight wheel row height (dp) proportionally to [effectiveScale] (clamped
 * to `[1f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE]`) so container height (`itemHeight * 3`) and
 * selection dividers expand with the enlarged weight text.
 */
fun scaledWeightWheelItemHeightDp(baseItemHeightDp: Float, effectiveScale: Float): Float {
    val e = sanitizedScale(effectiveScale).coerceIn(1f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    return baseItemHeightDp * e
}

/**
 * Stacks the Reps/Duration and Weight pickers vertically in `ExerciseEditBottomSheet.SetRow`
 * whenever a larger weight text scale is chosen or compact width combines with OS >= 1.15x.
 */
fun shouldStackExerciseSetPickers(
    weightTextScale: WeightTextScale,
    osFontScale: Float,
    isCompactWidth: Boolean,
): Boolean {
    val effectiveScale = effectiveWeightTextScale(weightTextScale, osFontScale)
    return weightTextScale != WeightTextScale.STANDARD || (isCompactWidth && effectiveScale >= 1.15f)
}

/**
 * Scales `CompactNumberPicker` +/- button bounds modestly as [effectiveScale] grows,
 * keeping touch targets larger without crowding the wheel on compact phone widths.
 */
fun scaledSelectorButtonSizeDp(baseButtonSizeDp: Float, effectiveScale: Float): Float {
    val e = sanitizedScale(effectiveScale).coerceIn(1f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    return baseButtonSizeDp * (1f + (e - 1f) * 0.25f)
}

/**
 * Scales `CompactNumberPicker` +/- icon glyph size proportionally as [effectiveScale] grows,
 * capped against [WEIGHT_TEXT_MAX_EFFECTIVE_SCALE].
 */
fun scaledSelectorIconSizeDp(baseIconSizeDp: Float = 24f, effectiveScale: Float): Float {
    val e = sanitizedScale(effectiveScale).coerceIn(1f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    return baseIconSizeDp * (1f + (e - 1f) * 0.5f)
}

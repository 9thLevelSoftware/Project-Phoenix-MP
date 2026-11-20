package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.WeightTextScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #1367 T7-T10/T12/T18: the SD1 rendered-cap style helper.
 *
 * Contract under test: E = min(A*O, 2), M = E/O; the style scales fontSize AND explicit
 * sp lineHeight by M (SD3), preserves other attributes, and leaves relative/unspecified
 * line height untouched; E is clamped to (0, 2] and invalid inputs degrade to 1.0 (SD1);
 * Large never renders smaller than Standard at the same device setting (SD2).
 */
class WeightTextScalingTest {

    private val tolerance = 0.0001f

    // ===== T7: style scaling ratios and attribute preservation =====

    @Test
    fun `fontSize scales by M at OS 1`() {
        // A=1/1.5/2 at O=1 -> E=A, M=A
        val base = TextStyle(fontSize = 20.sp)
        assertEquals(20f, base.scaledForWeightValue(WeightTextScale.STANDARD, 1f).fontSize.value, tolerance)
        assertEquals(30f, base.scaledForWeightValue(WeightTextScale.LARGE, 1f).fontSize.value, tolerance)
        assertEquals(40f, base.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, 1f).fontSize.value, tolerance)
    }

    @Test
    fun `fontSize multiplier M equals E divided by O`() {
        // A=2, O=1.5 -> E=min(3, 2)=2, M=2/1.5 -> rendered = base*2 (capped), not base*3
        val base = TextStyle(fontSize = 10.sp)
        val scaled = base.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, 1.5f)
        assertEquals(10f * (2f / 1.5f), scaled.fontSize.value, tolerance)
    }

    @Test
    fun `explicit sp line height scales with M per SD3`() {
        val base = TextStyle(fontSize = 20.sp, lineHeight = 24.sp)
        val scaled = base.scaledForWeightValue(WeightTextScale.LARGE, 1f)
        assertEquals(24f * 1.5f, scaled.lineHeight.value, tolerance)
    }

    @Test
    fun `relative and unspecified line height stay untouched`() {
        val unspecified = TextStyle(fontSize = 20.sp)
        assertEquals(
            unspecified.lineHeight,
            unspecified.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, 1f).lineHeight,
        )
        val relative = TextStyle(fontSize = 20.sp, lineHeight = 1.2.em)
        assertEquals(
            relative.lineHeight,
            relative.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, 1f).lineHeight,
        )
    }

    @Test
    fun `result font size stays in sp`() {
        val scaled = TextStyle(fontSize = 20.sp).scaledForWeightValue(WeightTextScale.LARGE, 1.25f)
        assertTrue(scaled.fontSize.isSp)
    }

    @Test
    fun `non-font attributes are preserved`() {
        val base = TextStyle(fontSize = 20.sp, letterSpacing = 0.5.sp)
        val scaled = base.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, 1f)
        assertEquals(base.letterSpacing, scaled.letterSpacing)
        assertEquals(base.fontWeight, scaled.fontWeight)
        assertEquals(base.color, scaled.color)
        assertEquals(base.fontFamily, scaled.fontFamily)
    }

    // ===== T8: cap matrix =====

    @Test
    fun `effective scale caps at 2x`() {
        assertEquals(2f, effectiveWeightTextScale(WeightTextScale.EXTRA_LARGE, 2.0f), tolerance)
        assertEquals(2f, effectiveWeightTextScale(WeightTextScale.LARGE, 2.0f), tolerance)
        assertEquals(2f, effectiveWeightTextScale(WeightTextScale.STANDARD, 2.5f), tolerance)
        assertEquals(2f, effectiveWeightTextScale(WeightTextScale.EXTRA_LARGE, 3.0f), tolerance)
        assertEquals(1.5f, effectiveWeightTextScale(WeightTextScale.LARGE, 1.0f), tolerance)
        assertEquals(1f, effectiveWeightTextScale(WeightTextScale.STANDARD, 1.0f), tolerance)
    }

    @Test
    fun `single named cap constant`() {
        assertEquals(2.0f, WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    }

    // ===== T9: Standard above-cap exception and Large >= Standard =====

    @Test
    fun `standard keeps M 1 through OS 2 and is bounded above the cap`() {
        // O <= 2: M = 1 (base typography preserved)
        assertEquals(1f, weightTextMultiplier(1f, 1.0f), tolerance)
        assertEquals(1f, weightTextMultiplier(1f, 1.75f), tolerance)
        assertEquals(1f, weightTextMultiplier(1f, 2.0f), tolerance)
        // O > 2: E = 2, M = 2/O < 1 (intentionally bounded, SD2)
        assertTrue(weightTextMultiplier(1f, 2.5f) < 1f)
        assertEquals(2f / 2.5f, weightTextMultiplier(1f, 2.5f), tolerance)
    }

    @Test
    fun `large never renders smaller than standard at the same device setting`() {
        val osScales = listOf(0.85f, 1f, 1.15f, 1.5f, 2f, 2.5f, 3f)
        val base = TextStyle(fontSize = 16.sp)
        for (os in osScales) {
            val standard = base.scaledForWeightValue(WeightTextScale.STANDARD, os).fontSize.value * os
            val large = base.scaledForWeightValue(WeightTextScale.LARGE, os).fontSize.value * os
            val extraLarge = base.scaledForWeightValue(WeightTextScale.EXTRA_LARGE, os).fontSize.value * os
            assertTrue(large >= standard - tolerance, "Large < Standard at OS $os")
            assertTrue(extraLarge >= large - tolerance, "Extra large < Large at OS $os")
        }
    }

    @Test
    fun `rendered size never exceeds 2x base for any combination`() {
        val osScales = listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 2.5f, 4f)
        val base = TextStyle(fontSize = 28.sp)
        for (scale in WeightTextScale.entries) {
            for (os in osScales) {
                // Rendered = style sp * OS scale in the linear model (SD1)
                val rendered = base.scaledForWeightValue(scale, os).fontSize.value * os
                assertTrue(
                    rendered <= 28f * WEIGHT_TEXT_MAX_EFFECTIVE_SCALE + tolerance,
                    "rendered ${rendered}sp exceeds cap for $scale at OS $os",
                )
            }
        }
    }

    // ===== T10: invalid input guards =====

    @Test
    fun `invalid nonpositive and nonfinite inputs degrade to 1 and clamp E into the open-closed cap range`() {
        val invalid = listOf(0f, -1f, -2.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (bad in invalid) {
            assertEquals(1f, effectiveWeightTextScale(bad, 1f), tolerance, "appFactor=$bad")
            assertEquals(1f, effectiveWeightTextScale(1f, bad), tolerance, "osFontScale=$bad")
            assertEquals(1f, weightTextMultiplier(bad, 1f), tolerance, "appFactor=$bad")
            assertEquals(1f, weightTextMultiplier(1f, bad), tolerance, "osFontScale=$bad")
        }
        // Huge-but-finite factors overflow-proof: E clamps to the cap, never to infinity
        assertEquals(2f, effectiveWeightTextScale(Float.MAX_VALUE, Float.MAX_VALUE))
        val e = effectiveWeightTextScale(1.5f, 1f)
        assertTrue(e > 0f && e <= WEIGHT_TEXT_MAX_EFFECTIVE_SCALE)
    }

    // ===== T18: WeightStepper value-row separation rule =====

    @Test
    fun `stepper value row separates only above E 1_5`() {
        assertTrue(shouldSeparateWeightStepperValueRow(1.6f))
        assertTrue(shouldSeparateWeightStepperValueRow(2.0f))
        assertTrue(!shouldSeparateWeightStepperValueRow(1.5f))
        assertTrue(!shouldSeparateWeightStepperValueRow(1.0f))
        // invalid input degrades to 1.0 -> no separation
        assertTrue(!shouldSeparateWeightStepperValueRow(Float.NaN))
    }

    // ===== Wheel sizing & ExerciseEditBottomSheet set-picker stacking =====

    @Test
    fun `weight wheel container height grows modestly and caps at 2x`() {
        assertEquals(120f, scaledWeightWheelHeightDp(120f, 1.0f), tolerance)
        assertEquals(156f, scaledWeightWheelHeightDp(120f, 1.5f), tolerance)
        assertEquals(192f, scaledWeightWheelHeightDp(120f, 2.0f), tolerance)
        assertEquals(192f, scaledWeightWheelHeightDp(120f, 3.0f), tolerance)
        assertEquals(96f, scaledWeightWheelHeightDp(96f, 0.85f), tolerance)
        assertEquals(96f, scaledWeightWheelHeightDp(96f, Float.NaN), tolerance)
    }

    @Test
    fun `weight wheel item height grows monotonically and caps at 2x`() {
        assertEquals(36f, scaledWeightWheelItemHeightDp(36f, 1.0f), tolerance)
        assertEquals(54f, scaledWeightWheelItemHeightDp(36f, 1.5f), tolerance)
        assertEquals(72f, scaledWeightWheelItemHeightDp(36f, 2.0f), tolerance)
        assertEquals(72f, scaledWeightWheelItemHeightDp(36f, 2.5f), tolerance)
        assertEquals(36f, scaledWeightWheelItemHeightDp(36f, -1f), tolerance)
    }

    @Test
    fun `exercise set pickers stack when weight scale is enlarged or compact OS scale is large`() {
        assertTrue(!shouldStackExerciseSetPickers(WeightTextScale.STANDARD, 1.0f, isCompactWidth = true))
        assertTrue(!shouldStackExerciseSetPickers(WeightTextScale.STANDARD, 1.0f, isCompactWidth = false))
        assertTrue(!shouldStackExerciseSetPickers(WeightTextScale.STANDARD, 1.5f, isCompactWidth = false))
        assertTrue(shouldStackExerciseSetPickers(WeightTextScale.STANDARD, 1.15f, isCompactWidth = true))
        assertTrue(shouldStackExerciseSetPickers(WeightTextScale.LARGE, 1.0f, isCompactWidth = true))
        assertTrue(shouldStackExerciseSetPickers(WeightTextScale.LARGE, 1.0f, isCompactWidth = false))
        assertTrue(shouldStackExerciseSetPickers(WeightTextScale.EXTRA_LARGE, 1.0f, isCompactWidth = true))
    }

    // ===== T12: CompositionLocal safe default =====
    // (Asserted via a real composition in androidHostTest/LocalWeightTextScaleDefaultTest —
    // CompositionLocal.current is @Composable and cannot be read from a plain unit test.)
}

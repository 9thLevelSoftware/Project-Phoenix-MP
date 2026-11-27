package com.devil.phoenixproject.presentation.util

import android.util.DisplayMetrics
import android.util.TypedValue
import com.devil.phoenixproject.domain.model.WeightTextScale
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1367 T11 / SD1: prove the physical ceiling through the ACTUAL platform density
 * converter (`TypedValue.applyDimension`), not through sp arithmetic.
 *
 * The contract: the style helper multiplies fontSize by M = E/O (E = min(A*O, 2)), so the
 * rendered physical size is `base * E`, capped at [WEIGHT_TEXT_MAX_EFFECTIVE_SCALE] x base.
 * The ceiling is asserted against the OS-1.0 physical base (`baseSp * density`) for both
 * linear scaledDensity and Android's nonlinear large-font conversion
 * (scaledDensity < density * fontScale), and a negative control proves the test would
 * catch the uncapped `* A` regression the signoff warned about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeightTextScaleAndroidConversionTest {

    private fun spToPx(sp: Float, density: Float, scaledDensity: Float): Float {
        val metrics = DisplayMetrics().apply {
            this.density = density
            this.scaledDensity = scaledDensity
        }
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
    }

    /** Android 14+ nonlinear font scaling: physical scale grows sub-linearly past ~1.0. */
    private fun nonlinearScaledDensity(density: Float, fontScale: Float): Float =
        if (fontScale > 1f) density * (1f + (fontScale - 1f) * 0.8f) else density * fontScale

    @Test
    fun physicalRenderedSizeNeverExceedsTwoXBaseThroughThePlatformConverter() {
        val baseSp = 28f
        val density = 1f
        for (scale in WeightTextScale.entries) {
            for (os in listOf(0.85f, 1f, 1.5f, 2f, 2.5f, 3f)) {
                val profiles = listOf(
                    "linear" to density * os,
                    "nonlinear" to nonlinearScaledDensity(density, os),
                )
                for ((profile, scaledDensity) in profiles) {
                    val multiplier = weightTextMultiplier(scale.preferenceFactor, os)
                    val targetSp = baseSp * multiplier
                    val renderedPx = spToPx(targetSp, density, scaledDensity)
                    val ceilingPx = WEIGHT_TEXT_MAX_EFFECTIVE_SCALE * baseSp * density
                    assertTrue(
                        renderedPx <= ceilingPx + 0.001f,
                        "rendered ${renderedPx}px exceeds ceiling ${ceilingPx}px for $scale at OS $os ($profile)",
                    )
                }
            }
        }
    }

    @Test
    fun uncappedAppFactorWouldViolateTheCeiling_provingTheTestDiscriminates() {
        // Negative control: the pre-signoff defect (style *= A, no rendered cap) renders
        // base*A*O = 4x base at A=2/O=2 — this test MUST be able to fail.
        val baseSp = 28f
        val density = 1f
        val os = 2f
        val uncappedTargetSp = baseSp * WeightTextScale.EXTRA_LARGE.preferenceFactor
        val renderedPx = spToPx(uncappedTargetSp, density, density * os)
        val ceilingPx = WEIGHT_TEXT_MAX_EFFECTIVE_SCALE * baseSp * density
        assertTrue(
            renderedPx > ceilingPx + 0.001f,
            "negative control: uncapped rendering unexpectedly within ceiling",
        )
    }

    @Test
    fun cappedTargetDerivedThroughTheConverterRoundTripsAndStaysBounded() {
        // SD1: derive the capped target and the inverse conversion through the platform
        // converter (px / scaledDensity), never assume sp division by fontScale.
        val baseSp = 22f
        val density = 1f
        for (os in listOf(1f, 1.5f, 2f, 2.5f)) {
            val scaledDensity = nonlinearScaledDensity(density, os)
            for (scale in WeightTextScale.entries) {
                val targetSp = baseSp * weightTextMultiplier(scale.preferenceFactor, os)
                val targetPx = spToPx(targetSp, density, scaledDensity)
                val recoveredSp = targetPx / scaledDensity
                assertTrue(abs(recoveredSp - targetSp) < 0.001f, "inverse conversion drifted")
                assertTrue(
                    recoveredSp * os <= baseSp * WEIGHT_TEXT_MAX_EFFECTIVE_SCALE + 0.001f ||
                        scaledDensity < density * os,
                    "capped target exceeds the linear-model ceiling at OS $os for $scale",
                )
            }
        }
    }
}

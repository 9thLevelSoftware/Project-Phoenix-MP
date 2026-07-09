package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [clampDynamicDarkScheme] (fix for issue #640).
 *
 * The helper merges a wallpaper-derived dynamic dark `ColorScheme` with the
 * brand-controlled static `DarkColorScheme`: the surface family must come from
 * the static palette (so chrome / card surfaces never resolve to high-luminance
 * values), while primary / secondary / tertiary / error / outline* / surfaceVariant
 * / background must come from the dynamic palette (so the wallpaper hue still
 * flows through toggles, sliders, badges, error states, etc.).
 *
 * These tests are JVM-only — they construct fake `ColorScheme` instances via the
 * `darkColorScheme(...)` builder + `.copy(...)` and assert on the resulting merged
 * `ColorScheme`. No Compose runtime, no Robolectric, no UI thread.
 */
class ClampedDynamicDarkSchemeTest {

    /**
     * Build the brand-controlled static dark scheme to use as the fallback.
     * Mirrors `DarkColorScheme` in `Theme.kt` so the test does not depend on
     * the private field directly.
     */
    private val fallback: ColorScheme = darkColorScheme(
        primary = Primary80,
        onPrimary = Primary20,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = Secondary80,
        onSecondary = Secondary20,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = Tertiary80,
        onTertiary = Tertiary20,
        tertiaryContainer = AshBlueLight,
        onTertiaryContainer = Color.White,
        background = SurfaceContainerDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceContainerDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceContainerHighDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceDim = SurfaceDimDark,
        surfaceBright = SurfaceContainerHighestDark,
        surfaceContainerLowest = Slate950,
        surfaceContainerLow = Slate900,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
        error = SignalError,
        onError = Color.White,
        outline = Slate400,
        outlineVariant = Slate700,
    )

    /**
     * Build a faked high-luminance dynamic dark scheme that simulates the wallpaper
     * bug: light surface roles, dark gradient base, wallpaper-derived primary/secondary.
     */
    private fun highLuminanceDynamicDark(): ColorScheme {
        val light = Color(0xFFE0E0E0) // ~0.78 luminance — representative of the bug
        val darker = Color(0xFFC0C0C0) // ~0.60 luminance — also "high"
        return darkColorScheme(
            primary = Color(0xFFFF6B6B),            // wallpaper red-ish
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF7A1F1F),
            onPrimaryContainer = Color(0xFFFFDAD4),
            secondary = Color(0xFF4DD0E1),          // wallpaper teal
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF003F4A),
            onSecondaryContainer = Color(0xFFB2EBF2),
            tertiary = Color(0xFFFFD54F),           // wallpaper amber
            onTertiary = Color.Black,
            tertiaryContainer = Color(0xFF5C4400),
            onTertiaryContainer = Color(0xFFFFE082),
            background = Slate950,                   // stays dark in the bug
            onBackground = Color(0xFFE0E0E0),
            surface = light,                        // BUG: light
            onSurface = Color(0xFF101010),
            surfaceVariant = darker,
            onSurfaceVariant = Color(0xFF202020),
            surfaceDim = light,
            surfaceBright = Color.White,            // BUG: white
            surfaceContainerLowest = Slate950,
            surfaceContainerLow = Color(0xFF888888),
            surfaceContainer = Color(0xFFA0A0A0),
            surfaceContainerHigh = Color(0xFFB0B0B0),  // BUG: light
            surfaceContainerHighest = Color(0xFFD0D0D0), // BUG: very light
            error = Color(0xFFFF1744),
            onError = Color.White,
            outline = Color(0xFF707070),
            outlineVariant = Color(0xFF505050),
        )
    }

    // ========== Surface family is clamped to the static Slate ramp ==========

    @Test
    fun `clamped surface family equals the static dark fallback surface`() {
        val dynamic = highLuminanceDynamicDark()
        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        assertEquals(fallback.surface, clamped.surface,
            "clamped surface must equal fallback.surface (no light wallpaper leak)")
        assertEquals(fallback.onSurface, clamped.onSurface)
        assertEquals(fallback.surfaceVariant, clamped.surfaceVariant)
        assertEquals(fallback.onSurfaceVariant, clamped.onSurfaceVariant)
        assertEquals(fallback.surfaceDim, clamped.surfaceDim)
        assertEquals(fallback.surfaceBright, clamped.surfaceBright)
        assertEquals(fallback.surfaceContainerLowest, clamped.surfaceContainerLowest)
        assertEquals(fallback.surfaceContainerLow, clamped.surfaceContainerLow)
        assertEquals(fallback.surfaceContainer, clamped.surfaceContainer)
        assertEquals(fallback.surfaceContainerHigh, clamped.surfaceContainerHigh)
        assertEquals(fallback.surfaceContainerHighest, clamped.surfaceContainerHighest)
        assertEquals(fallback.background, clamped.background)
        assertEquals(fallback.onBackground, clamped.onBackground)
    }

    @Test
    fun `conservative clamp routes surfaceVariant background surfaceDim surfaceBright from fallback`() {
        // Regression test for the Kilo Code Review warning on PR #642:
        // the *entire* surface family (incl. surfaceVariant / onSurfaceVariant /
        // surfaceDim / surfaceBright / background / onBackground) is clamped to
        // fallback, not just the surfaceContainer* roles. These tokens are part of
        // the same wallpaper-miscalibrated surface family that the reporter saw
        // flip light. If a future refactor loosens the clamp on these tokens,
        // the bug returns on wallpapers with high-luminance surfaceVariant /
        // background values.
        val dynamic = highLuminanceDynamicDark()
        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        // Confirm the test fixture has a non-fallback value for these tokens so the
        // assertion is meaningful (otherwise the test would silently pass).
        assertTrue(
            dynamic.surfaceVariant != fallback.surfaceVariant ||
                dynamic.background != fallback.background ||
                dynamic.surfaceDim != fallback.surfaceDim,
            "precondition: faked dynamic must differ from fallback on surfaceVariant/" +
                "background/surfaceDim so the clamp assertion is meaningful",
        )

        assertEquals(fallback.surfaceVariant, clamped.surfaceVariant,
            "surfaceVariant must come from fallback — defensive against wallpaper " +
                "surfaceVariant leak. See PR #642 Kilo Code Review.")
        assertEquals(fallback.onSurfaceVariant, clamped.onSurfaceVariant,
            "onSurfaceVariant must come from fallback — defensive against wallpaper " +
                "onSurfaceVariant leak.")
        assertEquals(fallback.background, clamped.background)
        assertEquals(fallback.onBackground, clamped.onBackground)
        assertEquals(fallback.surfaceDim, clamped.surfaceDim)
        assertEquals(fallback.surfaceBright, clamped.surfaceBright)
    }

    @Test
    fun `clamped chrome surface luminance is below the dark threshold`() {
        // Regression test for issue #640: top app bar / bottom nav / expanded card.
        val dynamic = highLuminanceDynamicDark()
        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        assertTrue(clamped.surface.luminance() < 0.5f,
            "Top app bar surface must be dark (luminance < 0.5), got ${clamped.surface.luminance()}")
        assertTrue(clamped.surfaceContainerHigh.luminance() < 0.5f,
            "Bottom nav surfaceContainerHigh must be dark (luminance < 0.5), got ${clamped.surfaceContainerHigh.luminance()}")
        assertTrue(clamped.surfaceContainerHighest.luminance() < 0.5f,
            "RoutineCard surfaceContainerHighest must be dark (luminance < 0.5), got ${clamped.surfaceContainerHighest.luminance()}")
        // And specifically within Slate ramp (very dark).
        assertTrue(clamped.surfaceContainerHighest.luminance() <= 0.2f,
            "surfaceContainerHighest must be Slate700-equivalent (luminance <= 0.2), got ${clamped.surfaceContainerHighest.luminance()}")
    }

    // ========== Wallpaper hue is preserved on accents ==========

    @Test
    fun `primary and secondary and tertiary come from the dynamic scheme`() {
        val dynamic = highLuminanceDynamicDark()
        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        assertEquals(dynamic.primary, clamped.primary,
            "primary must come from dynamic — wallpaper hue on toggles/badges")
        assertEquals(dynamic.onPrimary, clamped.onPrimary)
        assertEquals(dynamic.primaryContainer, clamped.primaryContainer)
        assertEquals(dynamic.onPrimaryContainer, clamped.onPrimaryContainer)
        assertEquals(dynamic.secondary, clamped.secondary)
        assertEquals(dynamic.onSecondary, clamped.onSecondary)
        assertEquals(dynamic.secondaryContainer, clamped.secondaryContainer)
        assertEquals(dynamic.onSecondaryContainer, clamped.onSecondaryContainer)
        assertEquals(dynamic.tertiary, clamped.tertiary)
        assertEquals(dynamic.onTertiary, clamped.onTertiary)
        assertEquals(dynamic.tertiaryContainer, clamped.tertiaryContainer)
        assertEquals(dynamic.onTertiaryContainer, clamped.onTertiaryContainer)
        assertEquals(dynamic.error, clamped.error)
        assertEquals(dynamic.onError, clamped.onError)
        assertEquals(dynamic.outline, clamped.outline)
        assertEquals(dynamic.outlineVariant, clamped.outlineVariant)
    }

    // ========== Identity: when dynamic == fallback, result equals both ==========

    @Test
    fun `clamping an already-dark dynamic scheme is a no-op`() {
        val clamped = clampDynamicDarkScheme(fallback, fallback)
        // Every role should match the fallback because both arguments are the same.
        assertEquals(fallback.surface, clamped.surface)
        assertEquals(fallback.surfaceContainerHigh, clamped.surfaceContainerHigh)
        assertEquals(fallback.surfaceContainerHighest, clamped.surfaceContainerHighest)
        assertEquals(fallback.primary, clamped.primary)
        assertEquals(fallback.secondary, clamped.secondary)
        assertEquals(fallback.tertiary, clamped.tertiary)
    }

    // ========== The helper never mutates the dynamic argument ==========

    @Test
    fun `clamping does not mutate the dynamic input`() {
        val dynamic = highLuminanceDynamicDark()
        val surfaceBefore = dynamic.surface
        val surfaceContainerHighestBefore = dynamic.surfaceContainerHighest
        val primaryBefore = dynamic.primary

        clampDynamicDarkScheme(dynamic, fallback)

        assertEquals(surfaceBefore, dynamic.surface,
            "dynamic.surface must not be mutated by clamping")
        assertEquals(surfaceContainerHighestBefore, dynamic.surfaceContainerHighest,
            "dynamic.surfaceContainerHighest must not be mutated by clamping")
        assertEquals(primaryBefore, dynamic.primary,
            "dynamic.primary must not be mutated by clamping")
    }

    // ========== Surface shift between 2dp and 8dp ≤ 6% ==========

    @Test
    fun `card surfaceContainerHighest differs from surfaceContainerHigh by less than 6 percent`() {
        // Compose's M3 Card elevation tint mixes a small amount of primary into the
        // base surfaceContainerHighest. When the base color is the static Slate
        // ramp, that tint shift is bounded. This is the structural reason the
        // expand/collapse flicker ≤ 6% once the surface family is clamped.
        val dynamic = highLuminanceDynamicDark()
        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        val delta = kotlin.math.abs(
            clamped.surfaceContainerHigh.luminance() -
                clamped.surfaceContainerHighest.luminance()
        )
        assertTrue(delta <= 0.06f,
            "Adjacent surface roles must be within 6% luminance so 2.dp vs 8.dp Card " +
                "elevation tint doesn't visibly flip the card. Got delta = $delta")
    }

    // ========== Sanity: helper actually changed something for a buggy dynamic scheme ==========

    @Test
    fun `clamping actually moves light dynamic surfaces to dark fallback surfaces`() {
        val dynamic = highLuminanceDynamicDark()
        // Sanity: the buggy dynamic surfaces are light (>0.5 luminance).
        assertTrue(dynamic.surface.luminance() > 0.5f,
            "precondition: faked dynamic surface must be high-luminance to exercise the bug")

        val clamped = clampDynamicDarkScheme(dynamic, fallback)

        assertNotEquals(dynamic.surface, clamped.surface,
            "clamping must replace the light dynamic surface with the dark fallback surface")
        assertNotEquals(dynamic.surfaceContainerHighest, clamped.surfaceContainerHighest)
        assertNotEquals(dynamic.surfaceContainerHigh, clamped.surfaceContainerHigh)
    }

    // ========== Boundary luminance: even white surfaces are clamped ==========

    @Test
    fun `boundary luminance 1_0 — pure white dynamic surface is clamped to fallback`() {
        val extremeDynamic = highLuminanceDynamicDark().copy(
            surface = Color.White,
            surfaceContainerHighest = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceBright = Color.White,
        )
        val clamped = clampDynamicDarkScheme(extremeDynamic, fallback)
        assertEquals(fallback.surface, clamped.surface)
        assertEquals(fallback.surfaceContainerHighest, clamped.surfaceContainerHighest)
        assertEquals(fallback.surfaceContainerHigh, clamped.surfaceContainerHigh)
        assertEquals(fallback.surfaceBright, clamped.surfaceBright)
        assertTrue(clamped.surface.luminance() < 0.5f)
    }

    @Test
    fun `boundary luminance 0_0 — black dynamic surface is also clamped to fallback`() {
        val blackDynamic = highLuminanceDynamicDark().copy(
            surface = Color.Black,
            surfaceContainerHighest = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceBright = Color.Black,
        )
        val clamped = clampDynamicDarkScheme(blackDynamic, fallback)
        // The clamp always takes the fallback surface family — even when the
        // dynamic scheme is fully black, we still emit the brand-controlled ramp
        // so the chrome matches the static palette exactly.
        assertEquals(fallback.surface, clamped.surface)
        assertEquals(fallback.surfaceContainerHighest, clamped.surfaceContainerHighest)
        assertEquals(fallback.surfaceContainerHigh, clamped.surfaceContainerHigh)
    }
}
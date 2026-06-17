package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowSizeClassTest {
    @Test
    fun compactWidthWithBoldTextUsesCompactAccessibilityLayout() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 390.dp,
            heightDp = 844.dp,
        )

        assertTrue(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = true,
            ),
        )
    }

    @Test
    fun compactWidthWithLargeFontScaleUsesCompactAccessibilityLayout() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 390.dp,
            heightDp = 844.dp,
        )

        assertTrue(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1.2f,
                boldTextEnabled = false,
            ),
        )
    }

    @Test
    fun widerLayoutsDoNotUseCompactAccessibilityForBoldTextOnly() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 700.dp,
            heightDp = 844.dp,
        )

        assertFalse(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = true,
            ),
        )
    }

    @Test
    fun shortHeightCompactWidthUsesCompactAccessibilityLayout() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Compact,
            widthDp = 390.dp,
            heightDp = 440.dp,
        )

        assertTrue(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
        )
    }

    @Test
    fun shortHeightWiderLayoutUsesCompactAccessibilityLayout() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            widthDp = 740.dp,
            heightDp = 390.dp,
        )

        assertTrue(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
        )
    }

    @Test
    fun defaultAndroidSizedLayoutPreservesExistingNonCompactBehavior() {
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 400.dp,
            heightDp = 800.dp,
        )

        assertFalse(
            shouldUseCompactAccessibilityLayout(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
        )
    }

    // ========== Issue #571: shouldStackWeightCards ==========
    //
    // The helper is reduced to `heightSizeClass == Compact` (see WindowSizeClass.kt). The
    // previous signature also took fontScale/boldTextEnabled/fontScaleThreshold, but those
    // parameters were dead code (see Gemini review on PR #574 — the broader
    // `shouldUseCompactAccessibilityLayout` already returns true for compact heights, so
    // the `&&` was a no-op). Tests are intentionally exhaustive over the 3 size-class
    // dimensions to lock down the exact behaviour.

    @Test
    fun stackWeightCards_iPhone16ProPortraitDefaultTypographyStaysSideBySide() {
        // The bug: on iPhone 16 Pro portrait (402x874dp) with default Dynamic Type
        // and Bold Text off, the iOS CompactNumberPicker wheel eats drags intended
        // for the ProgressionSlider. Default iPhone portrait should keep the two
        // weight cards side-by-side, not stacked.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 402.dp,
            heightDp = 874.dp,
        )

        assertFalse(
            shouldStackWeightCards(windowSizeClass = window),
            "iPhone 16 Pro portrait (height=Medium) must NOT stack the weight cards. " +
                "Stacking on a tall portrait device is what caused the gesture-stealing " +
                "bug in #571.",
        )
    }

    @Test
    fun stackWeightCards_iPhone16ProPortraitWithBoldTextStaysSideBySide() {
        // Bold Text on (compact width + medium height) was previously a stacked candidate,
        // but unless the height is also compact we should still keep the weight cards
        // side-by-side. Stacking them on a tall portrait device is what caused the
        // gesture-stealing bug in #571. Note: the new helper signature no longer takes
        // boldTextEnabled, so this test is now a documentation guard that *removing* the
        // parameter did not change behaviour — Bold Text cannot induce stacking.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 402.dp,
            heightDp = 874.dp,
        )

        assertFalse(
            shouldStackWeightCards(windowSizeClass = window),
            "Bold Text no longer influences shouldStackWeightCards; tall portrait is " +
                "always side-by-side.",
        )
    }

    @Test
    fun stackWeightCards_iPhoneLandscapeShortHeightStacksBothCards() {
        // iPhone landscape (740x390dp) genuinely needs the stacked layout to fit.
        // heightSizeClass == Compact + widthSizeClass == Compact ⇒ stack.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Compact,
            widthDp = 740.dp,
            heightDp = 390.dp,
        )

        assertTrue(
            shouldStackWeightCards(windowSizeClass = window),
        )
    }

    @Test
    fun stackWeightCards_shortHeightTabletStacks() {
        // A short-height medium-width window (e.g. iPad mini landscape with
        // split-screen) is also forced to the stacked layout.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Medium,
            heightSizeClass = WindowHeightSizeClass.Compact,
            widthDp = 700.dp,
            heightDp = 440.dp,
        )

        assertTrue(
            shouldStackWeightCards(windowSizeClass = window),
        )
    }

    @Test
    fun stackWeightCards_tallTabletStaysSideBySide() {
        // Tablets and large phones (width Medium/Expanded) with normal height should
        // never stack the weight cards. Width does not influence the helper, so a tablet
        // here behaves the same as any other window with height=Medium.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 900.dp,
            heightDp = 1200.dp,
        )

        assertFalse(
            shouldStackWeightCards(windowSizeClass = window),
        )
    }

    @Test
    fun stackWeightCards_heightIsTheSoleDeterminant() {
        // Documents the simplified contract: heightSizeClass == Compact is the only
        // input. widthSizeClass and accessibility settings do not influence the result.
        val heightCases = mapOf(
            WindowHeightSizeClass.Compact to true,
            WindowHeightSizeClass.Medium to false,
            WindowHeightSizeClass.Expanded to false,
        )
        val widthCases = listOf(
            WindowWidthSizeClass.Compact,
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded,
        )
        for ((height, expectedStack) in heightCases) {
            for (width in widthCases) {
                val window = WindowSizeClass(
                    widthSizeClass = width,
                    heightSizeClass = height,
                    widthDp = 400.dp,
                    heightDp = 800.dp,
                )
                val stack = shouldStackWeightCards(windowSizeClass = window)
                assertEquals(
                    expectedStack,
                    stack,
                    "shouldStackWeightCards must be determined solely by heightSizeClass. " +
                        "Failed for width=$width, height=$height (expected=$expectedStack, got=$stack).",
                )
            }
        }
    }
}

package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.unit.dp
import kotlin.test.Test
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
            shouldStackWeightCards(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
        )
    }

    @Test
    fun stackWeightCards_iPhone16ProPortraitWithBoldTextStaysSideBySide() {
        // Bold Text on (compact width + medium height) is a stacked candidate, but
        // unless the height is also compact we should still keep the weight cards
        // side-by-side. Stacking them on a tall portrait device is what caused the
        // gesture-stealing bug in #571.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Compact,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 402.dp,
            heightDp = 874.dp,
        )

        assertFalse(
            shouldStackWeightCards(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = true,
            ),
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
            shouldStackWeightCards(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
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
            shouldStackWeightCards(
                windowSizeClass = window,
                fontScale = 1f,
                boldTextEnabled = false,
            ),
        )
    }

    @Test
    fun stackWeightCards_tallTabletStaysSideBySide() {
        // Tablets and large phones (width Medium/Expanded) with normal height should
        // never stack the weight cards.
        val window = WindowSizeClass(
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            widthDp = 900.dp,
            heightDp = 1200.dp,
        )

        assertFalse(
            shouldStackWeightCards(
                windowSizeClass = window,
                fontScale = 1.3f,
                boldTextEnabled = true,
            ),
        )
    }

    @Test
    fun stackWeightCards_consistentWithCompactAccessibilityGating() {
        // Invariant: if shouldStackWeightCards is true, then
        // shouldUseCompactAccessibilityLayout must also be true. The reverse is NOT
        // required: there are cases (compact width + medium height + bold text) where
        // the screen is "compact accessibility" overall but the weight cards should
        // still stay side-by-side (issue #571).
        val cases = listOf(
            Triple(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Compact, 1f),
            Triple(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Compact, 1.2f),
            Triple(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium, 1f),
            Triple(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium, 1.2f),
            Triple(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Expanded, 1f),
            Triple(WindowWidthSizeClass.Medium, WindowHeightSizeClass.Compact, 1f),
            Triple(WindowWidthSizeClass.Medium, WindowHeightSizeClass.Medium, 1.2f),
            Triple(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Compact, 1f),
            Triple(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Medium, 1.2f),
        )
        for ((widthClass, heightClass, fontScale) in cases) {
            for (boldText in listOf(false, true)) {
                val window = WindowSizeClass(
                    widthSizeClass = widthClass,
                    heightSizeClass = heightClass,
                    widthDp = 400.dp,
                    heightDp = 800.dp,
                )
                val compact = shouldUseCompactAccessibilityLayout(
                    windowSizeClass = window,
                    fontScale = fontScale,
                    boldTextEnabled = boldText,
                )
                val stack = shouldStackWeightCards(
                    windowSizeClass = window,
                    fontScale = fontScale,
                    boldTextEnabled = boldText,
                )
                assertTrue(
                    !stack || compact,
                    "Invariant violated for ${widthClass}/${heightClass}/fs=$fontScale/bold=$boldText: " +
                        "stack=$stack but compact=$compact (stack must imply compact)",
                )
            }
        }
    }
}

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
}

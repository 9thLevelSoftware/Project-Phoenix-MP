package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for KmpUtils.formatFloat, focused on the non-finite guard.
 *
 * Regression: a workout-history entry holding a NaN weight/force value caused
 * the Analytics > History tab to crash on scroll, because kotlin.math.roundToInt()
 * throws IllegalArgumentException("Cannot round NaN value.") when fed NaN. The
 * formatter now treats non-finite values as 0 so the UI degrades gracefully
 * instead of crashing.
 */
class KmpUtilsTest {

    @Test
    fun formatTimestamp_compactMonthDay_doesNotFallBackToNumericDate() {
        val formatted = KmpUtils.formatTimestamp(1_718_452_800_000L, "MMM d")

        assertTrue(Regex("[A-Z][a-z]{2} \\d{1,2}").matches(formatted), formatted)
        assertFalse('/' in formatted, formatted)
        assertFalse(',' in formatted, formatted)
    }

    @Test
    fun formatFloat_nan_withDecimals_returnsZeroNoThrow() {
        assertEquals("0.0", KmpUtils.formatFloat(Float.NaN, 1))
        assertEquals("0.00", KmpUtils.formatFloat(Float.NaN, 2))
    }

    @Test
    fun formatFloat_nan_zeroDecimals_returnsZeroNoThrow() {
        assertEquals("0", KmpUtils.formatFloat(Float.NaN, 0))
    }

    @Test
    fun formatFloat_infinity_returnsZeroNoThrow() {
        assertEquals("0", KmpUtils.formatFloat(Float.POSITIVE_INFINITY, 0))
        assertEquals("0.0", KmpUtils.formatFloat(Float.NEGATIVE_INFINITY, 1))
    }

    @Test
    fun formatFloat_finiteValues_unchanged() {
        assertEquals("10", KmpUtils.formatFloat(10f, 0))
        assertEquals("10.5", KmpUtils.formatFloat(10.5f, 1))
        assertEquals("3.14", KmpUtils.formatFloat(3.14159f, 2))
    }

    @Test
    fun formatFloat_extensionAndDouble_handleNonFinite() {
        assertEquals("0.0", Float.NaN.format(1))
        assertEquals("0.0", Double.NaN.format(1))
    }
}

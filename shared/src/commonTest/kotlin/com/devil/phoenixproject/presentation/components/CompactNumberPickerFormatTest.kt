package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CompactNumberPickerFormatTest {

    @Test
    fun formatCompactNumberPickerValue_quarterKgStep_preservesQuarterValues() {
        assertEquals("10", formatCompactNumberPickerValue(10f, 0.25f))
        assertEquals("10.25", formatCompactNumberPickerValue(10.25f, 0.25f))
        assertEquals("10.5", formatCompactNumberPickerValue(10.5f, 0.25f))
        assertEquals("10.75", formatCompactNumberPickerValue(10.75f, 0.25f))
    }

    @Test
    fun formatCompactNumberPickerValue_halfStep_usesSingleDecimalWhenNeeded() {
        assertEquals("10", formatCompactNumberPickerValue(10f, 0.5f))
        assertEquals("10.5", formatCompactNumberPickerValue(10.5f, 0.5f))
    }
}

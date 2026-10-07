package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactNumberPickerValuesTest {

    @Test
    fun integerStep_includesBothEnds() {
        assertEquals(
            (0..10).map { it.toFloat() },
            compactNumberPickerValues(0f..10f, 1f),
        )
    }

    @Test
    fun halfStep_landsOnHalves() {
        assertEquals(
            listOf(10f, 10.5f, 11f, 11.5f, 12f),
            compactNumberPickerValues(10f..12f, 0.5f),
        )
    }

    @Test
    fun tenthStep_reachesRangeEnd() {
        val values = compactNumberPickerValues(0f..1f, 0.1f)
        assertEquals(0f, values.first())
        assertEquals(1f, values.last())
        assertEquals(11, values.size)
        assertTrue(values.zipWithNext().all { (left, right) -> right > left })
    }

    @Test
    fun endOffGrid_appendsRangeEnd() {
        assertEquals(
            listOf(0f, 3f, 6f, 9f, 10f),
            compactNumberPickerValues(0f..10f, 3f),
        )
    }

    @Test
    fun stepLargerThanSpan_stillIncludesEnd() {
        assertEquals(listOf(0f, 2f), compactNumberPickerValues(0f..2f, 5f))
    }

    @Test
    fun singlePointRange_returnsThatValue() {
        assertEquals(listOf(5f), compactNumberPickerValues(5f..5f, 1f))
    }

    @Test
    fun nonPositiveStep_returnsEmpty() {
        assertTrue(compactNumberPickerValues(0f..10f, 0f).isEmpty())
        assertTrue(compactNumberPickerValues(0f..10f, -0.5f).isEmpty())
    }

    @Test
    fun invertedRange_returnsEmpty() {
        assertTrue(compactNumberPickerValues(10f..0f, 1f).isEmpty())
    }
}

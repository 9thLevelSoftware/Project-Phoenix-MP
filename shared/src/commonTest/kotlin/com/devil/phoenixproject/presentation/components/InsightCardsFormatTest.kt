package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals

class InsightCardsFormatTest {

    @Test
    fun formatOneDecimal_preservesTenths() {
        // 1.5k reps and 6.7k kg were rendering as 1.0k / 6.0k under integer division.
        assertEquals("1.5", formatOneDecimal(1_500f / 1_000f))
        assertEquals("6.7", formatOneDecimal(6_700f / 1_000f))
        assertEquals("1.2", formatOneDecimal(1.24f))
    }

    @Test
    fun formatOneDecimal_wholeValuesStillShowOneDecimal() {
        assertEquals("1.0", formatOneDecimal(1f))
        assertEquals("2.0", formatOneDecimal(2f))
        assertEquals("10.0", formatOneDecimal(10.04f))
    }
}

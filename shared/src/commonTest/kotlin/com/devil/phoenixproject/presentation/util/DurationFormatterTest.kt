package com.devil.phoenixproject.presentation.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationFormatterTest {

    @Test
    fun formatDuration_subMinute_keepsSeconds() {
        assertEquals("0:45", DurationFormatter.formatDuration(45_000L))
    }

    @Test
    fun formatDuration_exactMinute_padsSeconds() {
        assertEquals("1:00", DurationFormatter.formatDuration(60_000L))
    }

    @Test
    fun formatDuration_minutesPlusSeconds() {
        assertEquals("2:10", DurationFormatter.formatDuration(130_000L))
    }
}

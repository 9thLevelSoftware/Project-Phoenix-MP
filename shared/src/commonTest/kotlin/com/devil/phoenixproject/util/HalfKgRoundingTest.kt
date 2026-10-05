package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

class HalfKgRoundingTest {

    @Test
    fun roundToHalfKg_exactHalf_staysPut() {
        assertEquals(10.0f, 10.0f.roundToHalfKg())
        assertEquals(10.5f, 10.5f.roundToHalfKg())
        assertEquals(0.0f, 0.0f.roundToHalfKg())
    }

    @Test
    fun roundToHalfKg_nearestHalf() {
        assertEquals(10.0f, 10.2f.roundToHalfKg())
        assertEquals(10.5f, 10.3f.roundToHalfKg())
        assertEquals(0.0f, 0.24f.roundToHalfKg())
        assertEquals(0.5f, 0.25f.roundToHalfKg())
    }

    @Test
    fun roundToHalfKg_tieRoundsTowardPositiveInfinity() {
        // 10.25 * 2 = 20.5, and roundToInt ties go toward +∞, so this is 10.5.
        assertEquals(10.5f, 10.25f.roundToHalfKg())
    }
}

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OneRepMaxCalculatorTest {

    private val tol = 0.01f

    @Test
    fun `epley matches formula for multi-rep sets`() {
        // 100 * (1 + 10/30) = 133.333
        assertEquals(133.33f, OneRepMaxCalculator.epley(100f, 10), tol)
    }

    @Test
    fun `epley returns weight for single rep and zero for invalid`() {
        assertEquals(100f, OneRepMaxCalculator.epley(100f, 1), tol)
        assertEquals(0f, OneRepMaxCalculator.epley(100f, 0), tol)
    }

    @Test
    fun `brzycki matches formula for low reps`() {
        // 100 * 36 / (37 - 5) = 112.5
        assertEquals(112.5f, OneRepMaxCalculator.brzycki(100f, 5), tol)
    }

    @Test
    fun `estimate uses brzycki at or below ten reps`() {
        // 100 * 36 / 32 = 112.5
        assertEquals(112.5f, OneRepMaxCalculator.estimate(100f, 5), tol)
    }

    @Test
    fun `estimate is continuous at the ten rep boundary`() {
        // Brzycki(10) = 100*36/27 = 133.333 == Epley(10) = 100*(1+10/30)
        assertEquals(133.33f, OneRepMaxCalculator.estimate(100f, 10), tol)
    }

    @Test
    fun `estimate uses epley above ten reps`() {
        // 100 * (1 + 11/30) = 136.667
        assertEquals(136.67f, OneRepMaxCalculator.estimate(100f, 11), tol)
    }

    @Test
    fun `estimate handles single rep and invalid inputs`() {
        assertEquals(100f, OneRepMaxCalculator.estimate(100f, 1), tol)
        assertEquals(0f, OneRepMaxCalculator.estimate(100f, 0), tol)
        assertEquals(0f, OneRepMaxCalculator.estimate(0f, 5), tol)
    }

    @Test
    fun `estimate never evaluates an unsafe brzycki denominator`() {
        // reps > 10 always routes to Epley, so 37 - reps is never <= 0
        assertEquals(100f * (1f + 40f / 30f), OneRepMaxCalculator.estimate(100f, 40), tol)
    }

    @Test
    fun `brzycki returns zero for invalid weight and out-of-range reps`() {
        assertEquals(0f, OneRepMaxCalculator.brzycki(100f, 0), tol)
        assertEquals(0f, OneRepMaxCalculator.brzycki(100f, 37), tol)
        assertEquals(0f, OneRepMaxCalculator.brzycki(-10f, 5), tol)
    }
}

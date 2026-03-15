package com.devil.phoenixproject.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for UnitConverter enhancements (Issue #266: weight increment support)
 */
class UnitConverterTest {

    // ===== formatDecimal tests =====

    @Test
    fun formatDecimal_wholeNumber_showsNoDecimal() {
        assertEquals("10", UnitConverter.formatDecimal(10.0f))
        assertEquals("0", UnitConverter.formatDecimal(0.0f))
        assertEquals("100", UnitConverter.formatDecimal(100.0f))
    }

    @Test
    fun formatDecimal_oneDecimalPlace_showsDecimal() {
        assertEquals("10.5", UnitConverter.formatDecimal(10.5f))
        assertEquals("0.5", UnitConverter.formatDecimal(0.5f))
        assertEquals("99.9", UnitConverter.formatDecimal(99.9f))
    }

    @Test
    fun formatDecimal_smallFraction_showsOneDecimal() {
        assertEquals("0.1", UnitConverter.formatDecimal(0.1f))
        assertEquals("5.3", UnitConverter.formatDecimal(5.3f))
    }

    // ===== roundToIncrement tests =====

    @Test
    fun roundToIncrement_halfKg_roundsCorrectly() {
        assertEquals(10.0f, UnitConverter.roundToIncrement(10.0f, 0.5f))
        assertEquals(10.5f, UnitConverter.roundToIncrement(10.3f, 0.5f))
        assertEquals(10.0f, UnitConverter.roundToIncrement(10.2f, 0.5f))
        // 10.25 / 0.5 = 20.5 → round-to-even = 20 → 10.0
        assertEquals(10.0f, UnitConverter.roundToIncrement(10.25f, 0.5f))
        // 10.26+ rounds up
        assertEquals(10.5f, UnitConverter.roundToIncrement(10.26f, 0.5f))
    }

    @Test
    fun roundToIncrement_oneTenthLb_roundsCorrectly() {
        val result = UnitConverter.roundToIncrement(10.14f, 0.1f)
        assertTrue(abs(result - 10.1f) < 0.001f, "Expected ~10.1, got $result")
    }

    @Test
    fun roundToIncrement_twoAndHalfKg_roundsCorrectly() {
        assertEquals(10.0f, UnitConverter.roundToIncrement(11.0f, 2.5f))
        assertEquals(12.5f, UnitConverter.roundToIncrement(12.0f, 2.5f))
        assertEquals(12.5f, UnitConverter.roundToIncrement(13.0f, 2.5f))
    }

    @Test
    fun roundToIncrement_zeroIncrement_returnsOriginal() {
        assertEquals(10.3f, UnitConverter.roundToIncrement(10.3f, 0.0f))
    }

    @Test
    fun roundToIncrement_negativeIncrement_returnsOriginal() {
        assertEquals(10.3f, UnitConverter.roundToIncrement(10.3f, -1.0f))
    }

    // ===== roundToMachineIncrement tests =====

    @Test
    fun roundToMachineIncrement_alwaysRoundsToHalfKg() {
        assertEquals(10.0f, UnitConverter.roundToMachineIncrement(10.0f))
        assertEquals(10.5f, UnitConverter.roundToMachineIncrement(10.3f))
        assertEquals(10.0f, UnitConverter.roundToMachineIncrement(10.2f))
        assertEquals(20.5f, UnitConverter.roundToMachineIncrement(20.7f))
    }

    // ===== formatWeight tests (Issue #266: decimal support) =====

    @Test
    fun formatWeight_kg_wholeNumber() {
        assertEquals("10 kg", UnitConverter.formatWeight(10.0f, useLb = false))
    }

    @Test
    fun formatWeight_kg_decimal() {
        assertEquals("10.5 kg", UnitConverter.formatWeight(10.5f, useLb = false))
    }

    @Test
    fun formatWeight_lb_convertsAndFormats() {
        // 10kg = 22.0462 lbs
        val result = UnitConverter.formatWeight(10.0f, useLb = true)
        assertTrue(result.endsWith("lbs"), "Expected lbs suffix, got $result")
        assertTrue(result.contains("22"), "Expected ~22 lbs, got $result")
    }

    // ===== Constants validation =====

    @Test
    fun weightIncrementOptions_kgAreValid() {
        assertEquals(listOf(0.5f, 1.0f, 2.5f, 5.0f), Constants.WEIGHT_INCREMENT_OPTIONS_KG)
    }

    @Test
    fun weightIncrementOptions_lbAreValid() {
        assertEquals(listOf(0.1f, 0.5f, 1.0f, 2.5f, 5.0f), Constants.WEIGHT_INCREMENT_OPTIONS_LB)
    }
}

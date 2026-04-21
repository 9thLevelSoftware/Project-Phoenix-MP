package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Issue #266: Weight increment wiring across all control surfaces.
 * Validates that UserPreferences, UnitConverter, and Constants work together correctly
 * for configurable weight increments.
 */
class WeightIncrementWiringTest {

    // ===== effectiveWeightIncrement: configured value =====

    @Test
    fun effectiveWeightIncrement_configured_returnsExactValue() {
        val prefs = UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = 2.5f)
        assertEquals(2.5f, prefs.effectiveWeightIncrement)
    }

    @Test
    fun effectiveWeightIncrement_configuredLb_returnsExactValue() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = 0.5f)
        assertEquals(0.5f, prefs.effectiveWeightIncrement)
    }

    // ===== effectiveWeightIncrement: defaults when unset (-1) =====

    @Test
    fun effectiveWeightIncrement_defaultKg_returnsHalfKg() {
        val prefs = UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = -1f)
        assertEquals(0.5f, prefs.effectiveWeightIncrement)
    }

    @Test
    fun effectiveWeightIncrement_defaultLb_returnsOneLb() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = -1f)
        assertEquals(1.0f, prefs.effectiveWeightIncrement)
    }

    // ===== effectiveWeightIncrementKg: conversion from LB =====

    @Test
    fun effectiveWeightIncrementKg_lbUnit_convertsCorrectly() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = 1.0f)
        // 1 lb ~ 0.453592 kg
        val result = prefs.effectiveWeightIncrementKg
        assertTrue(abs(result - 0.4536f) < 0.01f, "Expected ~0.4536kg, got $result")
    }

    @Test
    fun effectiveWeightIncrementKg_kgUnit_returnsDirectly() {
        val prefs = UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = 5.0f)
        assertEquals(5.0f, prefs.effectiveWeightIncrementKg)
    }

    @Test
    fun effectiveWeightIncrementKg_defaultLb_convertsDefaultToKg() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = -1f)
        // Default lb = 1.0 lb ~ 0.4536 kg
        val result = prefs.effectiveWeightIncrementKg
        assertTrue(abs(result - 0.4536f) < 0.01f, "Expected ~0.4536kg, got $result")
    }

    // ===== roundToIncrement: various step sizes =====

    @Test
    fun roundToIncrement_halfKgStep() {
        assertEquals(10.0f, UnitConverter.roundToIncrement(10.2f, 0.5f))
        assertEquals(10.5f, UnitConverter.roundToIncrement(10.3f, 0.5f))
    }

    @Test
    fun roundToIncrement_twoPointFiveKgStep() {
        assertEquals(10.0f, UnitConverter.roundToIncrement(11.0f, 2.5f))
        assertEquals(12.5f, UnitConverter.roundToIncrement(12.0f, 2.5f))
    }

    @Test
    fun roundToIncrement_fiveKgStep() {
        assertEquals(10.0f, UnitConverter.roundToIncrement(12.0f, 5.0f))
        assertEquals(15.0f, UnitConverter.roundToIncrement(13.0f, 5.0f))
    }

    @Test
    fun roundToIncrement_oneTenthLbStep() {
        val result = UnitConverter.roundToIncrement(10.14f, 0.1f)
        assertTrue(abs(result - 10.1f) < 0.001f, "Expected ~10.1, got $result")
    }

    @Test
    fun roundToIncrement_zeroStep_returnsOriginal() {
        assertEquals(7.3f, UnitConverter.roundToIncrement(7.3f, 0.0f))
    }

    // ===== roundToMachineIncrement: always 0.5kg =====

    @Test
    fun roundToMachineIncrement_roundsToHalfKg() {
        assertEquals(10.0f, UnitConverter.roundToMachineIncrement(10.0f))
        assertEquals(10.5f, UnitConverter.roundToMachineIncrement(10.3f))
        assertEquals(10.0f, UnitConverter.roundToMachineIncrement(10.2f))
        assertEquals(20.5f, UnitConverter.roundToMachineIncrement(20.7f))
    }

    // ===== Weight increment reset on unit change =====

    @Test
    fun weightIncrementReset_sentinelValue_givesCorrectDefault() {
        // When weightIncrement is reset to -1f (sentinel), verify defaults are correct per unit
        val kgPrefs = UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = -1f)
        assertEquals(0.5f, kgPrefs.effectiveWeightIncrement, "KG default should be 0.5")

        val lbPrefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = -1f)
        assertEquals(1.0f, lbPrefs.effectiveWeightIncrement, "LB default should be 1.0")
    }

    @Test
    fun weightIncrementReset_previousValueCleared() {
        // Simulate: user sets 5.0lb, then switches to KG which resets to -1f
        val beforeSwitch = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = 5.0f)
        assertEquals(5.0f, beforeSwitch.effectiveWeightIncrement)

        // After unit change: increment reset to sentinel
        val afterSwitch = beforeSwitch.copy(weightUnit = WeightUnit.KG, weightIncrement = -1f)
        assertEquals(0.5f, afterSwitch.effectiveWeightIncrement, "After unit change, should use KG default")
    }

    // ===== Constants validation =====

    @Test
    fun incrementOptions_kgContainsExpected() {
        assertEquals(listOf(0.5f, 1.0f, 2.5f, 5.0f), Constants.WEIGHT_INCREMENT_OPTIONS_KG)
    }

    @Test
    fun incrementOptions_lbContainsExpected() {
        assertEquals(listOf(0.1f, 0.5f, 1.0f, 2.5f, 5.0f), Constants.WEIGHT_INCREMENT_OPTIONS_LB)
    }

    @Test
    fun defaultConstants_matchPreferencesDefaults() {
        assertEquals(
            Constants.DEFAULT_WEIGHT_INCREMENT_KG,
            UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = -1f).effectiveWeightIncrement,
        )
        assertEquals(
            Constants.DEFAULT_WEIGHT_INCREMENT_LB,
            UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = -1f).effectiveWeightIncrement,
        )
    }
}

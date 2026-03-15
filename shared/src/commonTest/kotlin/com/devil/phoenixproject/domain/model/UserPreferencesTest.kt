package com.devil.phoenixproject.domain.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for UserPreferences weight increment support (Issue #266)
 */
class UserPreferencesTest {

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

    @Test
    fun effectiveWeightIncrement_customValue_returnsCustom() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = 0.1f)
        assertEquals(0.1f, prefs.effectiveWeightIncrement)
    }

    @Test
    fun effectiveWeightIncrementKg_kgUnit_returnsDirectly() {
        val prefs = UserPreferences(weightUnit = WeightUnit.KG, weightIncrement = 2.5f)
        assertEquals(2.5f, prefs.effectiveWeightIncrementKg)
    }

    @Test
    fun effectiveWeightIncrementKg_lbUnit_convertsToKg() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = 1.0f)
        // 1 lb ≈ 0.4536 kg
        val result = prefs.effectiveWeightIncrementKg
        assertTrue(abs(result - 0.4536f) < 0.01f, "Expected ~0.4536kg, got $result")
    }

    @Test
    fun effectiveWeightIncrementKg_defaultLb_convertsDefaultToKg() {
        val prefs = UserPreferences(weightUnit = WeightUnit.LB, weightIncrement = -1f)
        // Default lb = 1.0 lb ≈ 0.4536 kg
        val result = prefs.effectiveWeightIncrementKg
        assertTrue(abs(result - 0.4536f) < 0.01f, "Expected ~0.4536kg, got $result")
    }
}

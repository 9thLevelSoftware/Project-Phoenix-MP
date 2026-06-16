package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightDisplayRegressionTest {

    private companion object {
        const val KG_TO_LB = 2.20462f
        const val FLOAT_TOLERANCE = 0.01f
        const val MAX_WEIGHT_KG = 220f
        const val MIN_WEIGHT_KG = 0.5f
    }

    @Test
    fun dualCable_kg_displaysSelectedPerCableWeight() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(80f, result)
    }

    @Test
    fun dualCable_lb_convertsSelectedPerCableWeightOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )

        assertTrue(abs(result - (80f * KG_TO_LB)) < FLOAT_TOLERANCE)
    }

    @Test
    fun singleCable_kg_displaysSelectedPerCableWeight() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = 1,
            unit = WeightUnit.KG,
        )

        assertEquals(80f, result)
    }

    @Test
    fun nullCableCount_kg_displaysStoredPerCableWeight() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            cableCount = null,
            unit = WeightUnit.KG,
        )

        assertEquals(80f, result)
    }

    @Test
    fun zeroWeight_staysZero() {
        assertEquals(0f, WeightDisplayFormatter.toDisplayWeight(0f, cableCount = 2, unit = WeightUnit.KG))
        assertEquals("0", WeightDisplayFormatter.formatDisplayWeight(0f, cableCount = 2, unit = WeightUnit.KG))
    }

    @Test
    fun maxWeight_dualCable_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(MAX_WEIGHT_KG, result)
    }

    @Test
    fun prWeight_withCableCount_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 100f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(100f, result)
    }

    @Test
    fun fractionalWeight_dualCable_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MIN_WEIGHT_KG,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(MIN_WEIGHT_KG, result)
    }

    @Test
    fun formatAndToDisplay_areConsistentForPerCableDisplay() {
        val numericResult = WeightDisplayFormatter.toDisplayWeight(80f, 2, WeightUnit.KG)
        val stringResult = WeightDisplayFormatter.formatDisplayWeight(80f, 2, WeightUnit.KG)

        assertEquals("80", stringResult)
        assertEquals(numericResult.toInt().toString(), stringResult)
    }

    @Test
    fun unusualCableCounts_doNotChangeOrdinaryDisplay() {
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, 0, WeightUnit.KG))
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, -1, WeightUnit.KG))
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, 3, WeightUnit.KG))
    }

    @Test
    fun explicitTwoCableTotal_isSeparateFromOrdinaryDisplay() {
        val ordinary = WeightDisplayFormatter.toDisplayWeight(50f, 2, WeightUnit.KG)
        val total = WeightDisplayFormatter.toTwoCableTotalDisplayWeight(50f, WeightUnit.KG)

        assertEquals(50f, ordinary)
        assertEquals(100f, total)
    }

    @Test
    fun explicitTwoCableTotal_formatsFractionalValues() {
        val result = WeightDisplayFormatter.formatTwoCableTotalWeight(27.5f, WeightUnit.KG)

        assertEquals("55", result)
    }

    @Test
    fun negativeWeight_passesThroughWithoutCableMultiplication() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = -50f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(-50f, result)
    }
}

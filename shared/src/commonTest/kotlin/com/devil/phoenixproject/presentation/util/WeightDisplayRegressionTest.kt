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
    fun kg_displaysSelectedPerCableWeight() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            unit = WeightUnit.KG,
        )

        assertEquals(80f, result)
    }

    @Test
    fun lb_convertsSelectedPerCableWeightOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 80f,
            unit = WeightUnit.LB,
        )

        assertTrue(abs(result - (80f * KG_TO_LB)) < FLOAT_TOLERANCE)
    }

    @Test
    fun ordinaryDisplay_matchesPerCableHelper() {
        assertEquals(
            WeightDisplayFormatter.toPerCableDisplayWeight(80f, WeightUnit.KG),
            WeightDisplayFormatter.toDisplayWeight(80f, WeightUnit.KG),
            "Cable count is not an ordinary-display input",
        )
        assertEquals(
            WeightDisplayFormatter.formatPerCableWeight(80f, WeightUnit.KG),
            WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.KG),
        )
    }

    @Test
    fun zeroWeight_staysZero() {
        assertEquals(0f, WeightDisplayFormatter.toDisplayWeight(0f, unit = WeightUnit.KG))
        assertEquals("0", WeightDisplayFormatter.formatDisplayWeight(0f, unit = WeightUnit.KG))
    }

    @Test
    fun maxWeight_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MAX_WEIGHT_KG,
            unit = WeightUnit.KG,
        )

        assertEquals(MAX_WEIGHT_KG, result)
    }

    @Test
    fun prWeight_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 100f,
            unit = WeightUnit.KG,
        )

        assertEquals(100f, result)
    }

    @Test
    fun fractionalWeight_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = MIN_WEIGHT_KG,
            unit = WeightUnit.KG,
        )

        assertEquals(MIN_WEIGHT_KG, result)
    }

    @Test
    fun formatAndToDisplay_areConsistentForPerCableDisplay() {
        val numericResult = WeightDisplayFormatter.toDisplayWeight(80f, WeightUnit.KG)
        val stringResult = WeightDisplayFormatter.formatDisplayWeight(80f, WeightUnit.KG)

        assertEquals("80", stringResult)
        assertEquals(numericResult.toInt().toString(), stringResult)
    }

    @Test
    fun explicitTwoCableTotal_isSeparateFromOrdinaryDisplay() {
        val ordinary = WeightDisplayFormatter.toDisplayWeight(50f, WeightUnit.KG)
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
            unit = WeightUnit.KG,
        )

        assertEquals(-50f, result)
    }
}

package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightDisplayFormatterTest {

    private companion object {
        const val KG_TO_LB = 2.20462f
        const val FLOAT_TOLERANCE = 0.01f
    }

    @Test
    fun toDisplayWeight_twoCableMetadata_kg_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals(50f, result, "Official app displays selected load as per-cable weight")
    }

    @Test
    fun toDisplayWeight_twoCableMetadata_lb_convertsPerCableOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.LB,
        )

        assertTrue(
            abs(result - (50f * KG_TO_LB)) < FLOAT_TOLERANCE,
            "Two-cable metadata must not double ordinary lb display",
        )
    }

    @Test
    fun toDisplayWeight_ignoresInvalidCableMetadata() {
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, cableCount = 0, unit = WeightUnit.KG))
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, cableCount = -1, unit = WeightUnit.KG))
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, cableCount = 3, unit = WeightUnit.KG))
        assertEquals(80f, WeightDisplayFormatter.toDisplayWeight(80f, cableCount = null, unit = WeightUnit.KG))
    }

    @Test
    fun formatDisplayWeight_integerResult_noDecimals() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals("50", result)
    }

    @Test
    fun formatDisplayWeight_decimalResult_oneDecimal() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50.25f,
            cableCount = 2,
            unit = WeightUnit.KG,
        )

        assertEquals("50.3", result)
    }

    @Test
    fun explicitTwoCableTotal_kg_doublesOnlyWhenCalledExplicitly() {
        val result = WeightDisplayFormatter.toTwoCableTotalDisplayWeight(
            weightPerCableKg = 50f,
            unit = WeightUnit.KG,
        )

        assertEquals(100f, result, "Only the explicitly named total helper doubles")
    }

    @Test
    fun explicitTwoCableTotal_lb_doublesThenConverts() {
        val result = WeightDisplayFormatter.toTwoCableTotalDisplayWeight(
            weightPerCableKg = 50f,
            unit = WeightUnit.LB,
        )

        assertTrue(
            abs(result - (100f * KG_TO_LB)) < FLOAT_TOLERANCE,
            "Explicit total helper should show two-cable total in the selected unit",
        )
    }

    @Test
    fun formatTwoCableTotalWeight_formatsExplicitHelper() {
        val result = WeightDisplayFormatter.formatTwoCableTotalWeight(
            weightPerCableKg = 37.5f,
            unit = WeightUnit.KG,
        )

        assertEquals("75", result)
    }
}

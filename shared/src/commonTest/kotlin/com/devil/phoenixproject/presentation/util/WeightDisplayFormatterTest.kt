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
    fun toDisplayWeight_kg_staysPerCable() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            unit = WeightUnit.KG,
        )

        assertEquals(50f, result, "Selected load is displayed as per-cable weight")
    }

    @Test
    fun toDisplayWeight_lb_convertsPerCableOnly() {
        val result = WeightDisplayFormatter.toDisplayWeight(
            weightPerCableKg = 50f,
            unit = WeightUnit.LB,
        )

        assertTrue(
            abs(result - (50f * KG_TO_LB)) < FLOAT_TOLERANCE,
            "Ordinary lb display converts the per-cable load only",
        )
    }

    @Test
    fun ordinaryDisplay_matchesPerCableHelper() {
        assertEquals(
            WeightDisplayFormatter.toPerCableDisplayWeight(80f, WeightUnit.KG),
            WeightDisplayFormatter.toDisplayWeight(80f, WeightUnit.KG),
            "Ordinary load text is the per-cable value; cable count is not an input",
        )
        assertEquals(
            WeightDisplayFormatter.formatPerCableWeight(50.25f, WeightUnit.KG),
            WeightDisplayFormatter.formatDisplayWeight(50.25f, WeightUnit.KG),
        )
    }

    @Test
    fun formatDisplayWeight_integerResult_noDecimals() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50f,
            unit = WeightUnit.KG,
        )

        assertEquals("50", result)
    }

    @Test
    fun formatDisplayWeight_decimalResult_oneDecimal() {
        val result = WeightDisplayFormatter.formatDisplayWeight(
            weightPerCableKg = 50.25f,
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

    @Test
    fun formatTwoCableTotalWeight_lb_roundsTenKgPerCable() {
        val result = WeightDisplayFormatter.formatTwoCableTotalWeight(
            weightPerCableKg = 10f,
            unit = WeightUnit.LB,
        )

        assertEquals(
            "44.1",
            result,
            "10 kg/cable is 44.092 lb across two cables and rounds to one decimal",
        )
    }

    @Test
    fun formatDisplayNumber_matchesFormatDisplayWeightWithoutReconverting() {
        val samples = listOf(0f, 20f, 20.4f, 20.5f, 20.6f, 50.25f, 110f)
        samples.forEach { kg ->
            assertEquals(
                WeightDisplayFormatter.formatDisplayWeight(kg, WeightUnit.KG),
                WeightDisplayFormatter.formatDisplayNumber(kg),
                "kg $kg",
            )
            assertEquals(
                WeightDisplayFormatter.formatDisplayWeight(kg, WeightUnit.LB),
                WeightDisplayFormatter.formatDisplayNumber(
                    WeightDisplayFormatter.toDisplayWeight(kg, WeightUnit.LB),
                ),
                "lb $kg",
            )
        }
    }

    @Test
    fun formatDisplayNumber_keepsFractionsThatIntegerRoundingDrops() {
        assertEquals("20", WeightDisplayFormatter.formatDisplayNumber(20f))
        assertEquals("20.4", WeightDisplayFormatter.formatDisplayNumber(20.4f))
        assertEquals("20.5", WeightDisplayFormatter.formatDisplayNumber(20.5f))
        assertEquals("20.6", WeightDisplayFormatter.formatDisplayNumber(20.6f))
        assertEquals("50.3", WeightDisplayFormatter.formatDisplayNumber(50.25f))
    }
}

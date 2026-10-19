package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.UnitConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DropSetMinWeightInputFormatTest {

    @Test
    fun nullWeight_isEmpty() {
        assertEquals("", formatDropSetMinWeightInput(null, WeightUnit.KG, ::identityKg))
    }

    @Test
    fun kilograms_useFormatDecimalWithoutRawFloatText() {
        assertEquals("10", formatDropSetMinWeightInput(10f, WeightUnit.KG, ::identityKg))
        assertEquals("2", formatDropSetMinWeightInput(2.0f, WeightUnit.KG, ::identityKg))
        assertEquals("7.5", formatDropSetMinWeightInput(7.5f, WeightUnit.KG, ::identityKg))
        assertEquals("2.3", formatDropSetMinWeightInput(2.2999999f, WeightUnit.KG, ::identityKg))

        val artifact = formatDropSetMinWeightInput(2.2999999f, WeightUnit.KG, ::identityKg)
        assertFalse(artifact.contains("999"))
        assertFalse(artifact.endsWith(".0"))
    }

    @Test
    fun pounds_formatTheConvertedDisplayValue() {
        val formatted = formatDropSetMinWeightInput(10f, WeightUnit.LB, ::kgToDisplay)
        assertEquals(UnitConverter.formatDecimal(UnitConverter.kgToLb(10f)), formatted)
        assertFalse(formatted.contains("999"))
        assertFalse(formatted.endsWith(".0"))
    }

    private fun identityKg(kg: Float, unit: WeightUnit): Float {
        assertEquals(WeightUnit.KG, unit)
        return kg
    }

    private fun kgToDisplay(kg: Float, unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> kg
        WeightUnit.LB -> UnitConverter.kgToLb(kg)
    }
}

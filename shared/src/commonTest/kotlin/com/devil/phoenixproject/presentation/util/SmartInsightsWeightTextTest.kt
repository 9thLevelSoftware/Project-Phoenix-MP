package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SmartInsightsWeightTextTest {

    @Test
    fun plateauLoad_kg_usesFormatterAndGivenLabel() {
        assertEquals("40 kg", SmartInsightsWeightText.plateauLoad(40f, WeightUnit.KG, "kg"))
        assertEquals(
            "${WeightDisplayFormatter.formatDisplayWeight(40.25f, WeightUnit.KG)} kg",
            SmartInsightsWeightText.plateauLoad(40.25f, WeightUnit.KG, "kg"),
        )
    }

    @Test
    fun plateauLoad_lb_convertsPerCableWeight() {
        val text = SmartInsightsWeightText.plateauLoad(40f, WeightUnit.LB, "lbs")
        assertEquals("${WeightDisplayFormatter.formatDisplayWeight(40f, WeightUnit.LB)} lbs", text)
        assertFalse(text.contains("kg"))
    }

    @Test
    fun volumeAmount_kg_roundsToWholeKilograms() {
        assertEquals("570", SmartInsightsWeightText.volumeAmount(570f, WeightUnit.KG))
        assertEquals("38", SmartInsightsWeightText.volumeAmount(37.5f, WeightUnit.KG))
        assertEquals("37", SmartInsightsWeightText.volumeAmount(37.4f, WeightUnit.KG))
    }

    @Test
    fun volumeAmount_lb_usesTheSamePoundFactorAsHistoryVolume() {
        val volumeKg = 570f
        assertEquals(
            UnitConverter.kgToLb(volumeKg),
            WeightDisplayFormatter.toDisplayWeight(volumeKg, WeightUnit.LB),
        )
        assertEquals(
            UnitConverter.kgToLb(volumeKg).roundToInt().toString(),
            SmartInsightsWeightText.volumeAmount(volumeKg, WeightUnit.LB),
        )
    }

    @Test
    fun volumeAmount_nonFiniteVolume_printsZero() {
        assertEquals("0", SmartInsightsWeightText.volumeAmount(Float.NaN, WeightUnit.KG))
        assertEquals("0", SmartInsightsWeightText.volumeAmount(Float.POSITIVE_INFINITY, WeightUnit.LB))
    }

    @Test
    fun readinessVolume_includesRoundedAmountAndGivenLabel() {
        assertEquals("1200 kg", SmartInsightsWeightText.volumeWithUnit(1200f, WeightUnit.KG, "kg"))
        assertEquals(
            "${UnitConverter.kgToLb(1200f).roundToInt()} lbs",
            SmartInsightsWeightText.volumeWithUnit(1200f, WeightUnit.LB, "lbs"),
        )
    }
}

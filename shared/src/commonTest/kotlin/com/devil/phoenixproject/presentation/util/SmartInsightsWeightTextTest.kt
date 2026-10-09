package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.readProjectFile
import com.devil.phoenixproject.util.UnitConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartInsightsWeightTextTest {

    @Test
    fun unitLabel_matchesExistingKgAndLbsStringResources() {
        val strings = requireNotNull(
            readProjectFile("src/commonMain/composeResources/values/strings.xml"),
        )
        assertTrue(strings.contains("<string name=\"label_kg\">${SmartInsightsWeightText.unitLabel(WeightUnit.KG)}</string>"))
        assertTrue(strings.contains("<string name=\"label_lbs\">${SmartInsightsWeightText.unitLabel(WeightUnit.LB)}</string>"))
        assertEquals("kg", SmartInsightsWeightText.unitLabel(WeightUnit.KG))
        assertEquals("lbs", SmartInsightsWeightText.unitLabel(WeightUnit.LB))
    }

    @Test
    fun plateauLoad_kg_usesFormatterAndKgLabel() {
        assertEquals(
            "${WeightDisplayFormatter.formatDisplayWeight(40f, WeightUnit.KG)} kg",
            SmartInsightsWeightText.plateauLoad(40f, WeightUnit.KG),
        )
        assertEquals("40 kg", SmartInsightsWeightText.plateauLoad(40f, WeightUnit.KG))
        assertEquals(
            "${WeightDisplayFormatter.formatDisplayWeight(40.25f, WeightUnit.KG)} kg",
            SmartInsightsWeightText.plateauLoad(40.25f, WeightUnit.KG),
        )
    }

    @Test
    fun plateauLoad_lb_convertsPerCableWeight() {
        val text = SmartInsightsWeightText.plateauLoad(40f, WeightUnit.LB)
        assertEquals(
            "${WeightDisplayFormatter.formatDisplayWeight(40f, WeightUnit.LB)} lbs",
            text,
        )
        assertFalse(text.contains("kg"))
        assertTrue(text.endsWith(" lbs"))
    }

    @Test
    fun volumeAmount_kg_keepsWholeKilograms() {
        assertEquals("570", SmartInsightsWeightText.volumeAmount(570f, WeightUnit.KG))
        assertEquals(
            WeightDisplayFormatter.formatDisplayWeight(37.5f, WeightUnit.KG),
            SmartInsightsWeightText.volumeAmount(37.5f, WeightUnit.KG),
        )
    }

    @Test
    fun volumeAmount_lb_usesTheSamePoundFactorAsHistoryVolume() {
        val volumeKg = 570f
        assertEquals(
            UnitConverter.kgToLb(volumeKg),
            WeightDisplayFormatter.toDisplayWeight(volumeKg, WeightUnit.LB),
        )
        assertEquals(
            WeightDisplayFormatter.formatDisplayWeight(volumeKg, WeightUnit.LB),
            SmartInsightsWeightText.volumeAmount(volumeKg, WeightUnit.LB),
        )
        assertFalse(SmartInsightsWeightText.volumeAmount(volumeKg, WeightUnit.LB).contains("kg"))
    }

    @Test
    fun readinessVolume_includesConvertedAmountAndExistingLabel() {
        assertEquals("1200 kg", SmartInsightsWeightText.volumeWithUnit(1200f, WeightUnit.KG))
        assertEquals(
            "${WeightDisplayFormatter.formatDisplayWeight(1200f, WeightUnit.LB)} lbs",
            SmartInsightsWeightText.volumeWithUnit(1200f, WeightUnit.LB),
        )
    }
}

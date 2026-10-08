package com.devil.phoenixproject.presentation.components.cycle

import com.devil.phoenixproject.domain.model.formatPercent
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressionSettingsSheetTest {

    @Test
    fun weightIncreasePercentLabel_preservesTenths() {
        // Integer division of round(Float) turned 2.5 into 2 and 0.5 into 0.
        assertEquals("2.5%", label(2.5f))
        assertEquals("0.5%", label(0.5f))
        assertEquals("1.5%", label(1.5f))
    }

    @Test
    fun weightIncreasePercentLabel_wholeValuesOmitTrailingZero() {
        assertEquals("2%", label(2f))
        assertEquals("2%", label(2.0f))
        assertEquals("10%", label(10f))
    }

    private fun label(weightPercent: Float): String =
        formatPercent(weightIncreasePercentLabelValue(weightPercent), "en")
}

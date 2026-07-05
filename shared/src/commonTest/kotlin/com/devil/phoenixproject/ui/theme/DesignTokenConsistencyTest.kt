package com.devil.phoenixproject.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesignTokenConsistencyTest {
    @Test
    fun standardPalette_references_signal_constants() {
        assertEquals(SignalSuccess, StandardPalette.success)
        assertEquals(SignalError, StandardPalette.error)
        assertEquals(SignalWarning, StandardPalette.warning)
    }

    @Test
    fun dataColors_are_pairwise_distinct_from_signals_and_each_other() {
        assertNotEquals(DataColors.Volume, DataColors.LoadA, "Volume and LoadA must be distinguishable in combined charts")
        assertNotEquals(SignalWarning, DataColors.Intensity, "warning signal and intensity series must be distinguishable")
        assertNotEquals(SignalError, DataColors.HeartRate, "error signal and heart-rate series must be distinguishable")
    }
}

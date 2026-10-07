package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.presentation.util.programModeShortLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the shared short labels used by mode pills and confirmation badges.
 */
class ProgramModeShortLabelTest {

    @Test
    fun short_labels_match_existing_abbreviations() {
        assertEquals("OLD", programModeShortLabel(ProgramMode.OldSchool))
        assertEquals("TUT", programModeShortLabel(ProgramMode.TUT))
        assertEquals("PUMP", programModeShortLabel(ProgramMode.Pump))
        assertEquals("ECC", programModeShortLabel(ProgramMode.EccentricOnly))
        assertEquals("BEAST", programModeShortLabel(ProgramMode.TUTBeast))
        assertEquals("ECHO", programModeShortLabel(ProgramMode.Echo))
    }
}

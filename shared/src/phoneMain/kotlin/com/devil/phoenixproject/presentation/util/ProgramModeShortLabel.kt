package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * Short pill and badge label for a program mode.
 * Shared by ModeSelector and ModeConfirmationScreen.
 */
fun programModeShortLabel(mode: ProgramMode): String = when (mode) {
    ProgramMode.OldSchool -> "OLD"
    ProgramMode.TUT -> "TUT"
    ProgramMode.Pump -> "PUMP"
    ProgramMode.EccentricOnly -> "ECC"
    ProgramMode.TUTBeast -> "BEAST"
    ProgramMode.Echo -> "ECHO"
}

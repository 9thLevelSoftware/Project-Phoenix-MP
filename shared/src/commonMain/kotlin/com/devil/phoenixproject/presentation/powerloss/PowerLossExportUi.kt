package com.devil.phoenixproject.presentation.powerloss

/**
 * Issue #1392 — export entry + legacy-export omission disclosure (P5, ui-spec §6).
 *
 * `Export power data` leads to the versioned power-history export (P4 codec). The
 * legacy Phoenix/Strong/Hevy export confirmation discloses that the format omits
 * power-loss settings and per-attempt details. Copy never claims synced/portal
 * parity and never fabricates data.
 */
data class PowerLossExportUi(
    val entryLabel: String = PowerLossUiCopy.EXPORT_ENTRY,
    val legacyOmissionDisclosure: String = PowerLossUiCopy.LEGACY_EXPORT_DISCLOSURE,
    val choosePowerDataExportAction: String = PowerLossUiCopy.LEGACY_EXPORT_CHOOSE_POWER,
    val exportWithoutPowerDetailsAction: String = PowerLossUiCopy.LEGACY_EXPORT_CONTINUE,
    val localDataDisclosure: String = PowerLossUiCopy.LOCAL_DATA_DISCLOSURE,
)

/** Pending legacy-export confirmation dialog state. */
data class LegacyExportConfirmationUi(
    val visible: Boolean,
    val disclosure: String = PowerLossUiCopy.LEGACY_EXPORT_DISCLOSURE,
    val primaryAction: String = PowerLossUiCopy.LEGACY_EXPORT_CHOOSE_POWER,
    val secondaryAction: String = PowerLossUiCopy.LEGACY_EXPORT_CONTINUE,
)

/** Show the omission disclosure before any legacy export run. */
fun legacyExportConfirmation(): LegacyExportConfirmationUi =
    LegacyExportConfirmationUi(visible = true)

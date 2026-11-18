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

/** Export-flow intents (P5 item 4). */
sealed interface PowerLossExportIntent {
    /** The user asked for a legacy (non-power) export: disclosure comes first. */
    data object RequestLegacyExport : PowerLossExportIntent

    /** `Choose power-data export` — route to the versioned power-history export. */
    data object ChoosePowerDataExport : PowerLossExportIntent

    /** `Export without power details` — the legacy run proceeds after disclosure. */
    data object ExportWithoutPowerDetails : PowerLossExportIntent

    /** Disclosure dismissed (outside tap / back): no export runs. */
    data object DismissLegacyConfirmation : PowerLossExportIntent
}

/**
 * Pure export-flow state machine: a legacy export ALWAYS surfaces the omission
 * disclosure first; `Export power data` routes to the versioned power-history
 * export. Nothing runs silently without the disclosure (ui-spec §6).
 */
data class PowerLossExportFlowState(
    val legacyConfirmationVisible: Boolean = false,
    val legacyExportApproved: Boolean = false,
    val powerExportRequested: Boolean = false,
) {
    fun reduce(intent: PowerLossExportIntent): PowerLossExportFlowState = when (intent) {
        PowerLossExportIntent.RequestLegacyExport -> copy(
            legacyConfirmationVisible = true,
            legacyExportApproved = false,
            powerExportRequested = false,
        )
        PowerLossExportIntent.ChoosePowerDataExport -> copy(
            legacyConfirmationVisible = false,
            legacyExportApproved = false,
            powerExportRequested = true,
        )
        PowerLossExportIntent.ExportWithoutPowerDetails -> copy(
            legacyConfirmationVisible = false,
            legacyExportApproved = true,
            powerExportRequested = false,
        )
        PowerLossExportIntent.DismissLegacyConfirmation -> copy(
            legacyConfirmationVisible = false,
            legacyExportApproved = false,
            powerExportRequested = false,
        )
    }

    companion object {
        val INITIAL = PowerLossExportFlowState()
    }
}

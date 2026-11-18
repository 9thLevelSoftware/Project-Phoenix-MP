package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode

/**
 * Issue #1392 — set-ready / Just Lift / bottom-sheet target readback facts (P5,
 * ui-spec §2–§3). Pure presentation state so every readback row is unit-testable.
 *
 * Rules (SD6, ui-spec §2/§3):
 *  - The readback card renders ONLY when the saved target is POWER (active or
 *    dormant) — plain REP sets render nothing (REP regression unchanged).
 *  - Active POWER shows `Power-loss target · 20%`, `Max 20 working reps`,
 *    `Saved reps: 12` and the adjacent `Power-loss target active` badge — never
 *    `9 / 12` from nominal reps.
 *  - A POWER config on an excluded mode/type is retained but inert, with the
 *    `Power-loss target is not active in this mode.` banner — POWER never arms
 *    from an unknown or excluded mode.
 *  - Active-set UI is read-only: `Target locked for this set.` Editing happens
 *    before the next set, never from the active set.
 */
data class PowerLossTargetReadbackUi(
    /** False for plain REP sets — the card renders nothing. */
    val visible: Boolean,
    val lines: List<String>,
    val targetLockedLine: String?,
    val savedRepsLine: String?,
    val powerActiveBadge: String?,
    val dormantBanner: String?,
) {
    val targetLocked: Boolean get() = targetLockedLine != null

    companion object {
        val HIDDEN = PowerLossTargetReadbackUi(
            visible = false,
            lines = emptyList(),
            targetLockedLine = null,
            savedRepsLine = null,
            powerActiveBadge = null,
            dormantBanner = null,
        )
    }
}

/**
 * Build the pre/during-set target readback from the per-exercise config and its
 * execution context. [locked] renders the during-set `Target locked for this
 * set.` line — active-set UI never edits the target.
 */
fun powerLossTargetReadback(
    config: PowerLossConfig,
    supportedExecution: Boolean,
    savedNominalReps: Int? = config.savedNominalReps,
    locked: Boolean = false,
): PowerLossTargetReadbackUi {
    if (config.targetMode != TargetMode.POWER) return PowerLossTargetReadbackUi.HIDDEN
    val savedRepsLine = savedNominalReps?.let { PowerLossUiCopy.savedRepsHeader(it) }
    val lockedLine = if (locked) PowerLossUiCopy.TARGET_LOCKED_SHORT else null
    return if (supportedExecution) {
        val badge = PowerLossUiCopy.POWER_ACTIVE_BADGE
        PowerLossTargetReadbackUi(
            visible = true,
            lines = listOfNotNull(
                lockedLine,
                PowerLossUiCopy.hudTargetReadback(config.thresholdPercent),
                PowerLossUiCopy.hudCapReadback(config.maxWorkingReps).takeIf { it.isNotEmpty() },
                savedRepsLine,
                badge,
            ),
            targetLockedLine = lockedLine,
            savedRepsLine = savedRepsLine,
            powerActiveBadge = badge,
            dormantBanner = null,
        )
    } else {
        PowerLossTargetReadbackUi(
            visible = true,
            lines = listOfNotNull(
                lockedLine,
                PowerLossUiCopy.INCOMPATIBLE_SAVED_BANNER,
                savedRepsLine,
            ),
            targetLockedLine = lockedLine,
            savedRepsLine = savedRepsLine,
            powerActiveBadge = null,
            dormantBanner = PowerLossUiCopy.INCOMPATIBLE_SAVED_BANNER,
        )
    }
}

/**
 * Execution-context helper: whether the power-loss target can run for this
 * exercise at all (protocol family + non-excluded execution type). Timed and
 * bodyweight executions are excluded exactly like unsupported protocol families.
 */
fun powerLossExecutionSupported(
    config: PowerLossConfig,
    mode: com.devil.phoenixproject.domain.model.ProgramMode,
    isJustLiftExecutionStyle: Boolean = false,
    isTimed: Boolean = false,
    isBodyweight: Boolean = false,
): Boolean {
    val family = PowerLossProtocolFamily.of(mode, isJustLiftExecutionStyle)
    return family.supportsPowerLoss && !isTimed && !isBodyweight
}

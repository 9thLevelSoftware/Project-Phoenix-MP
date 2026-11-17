package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode

/**
 * Issue #1392 — pure config draft/commit/cancel state machine (P5, ui-spec §3).
 *
 * Modal/bottom-sheet edits are a local draft: Apply validates every effective field
 * and commits atomically; Cancel (or outside dismissal / system Back) discards the
 * unapplied draft and restores the original target/settings. Toggling REP↔POWER
 * preserves dormant values and the saved nominal reps.
 *
 * Pure Kotlin only — no Compose — so every row of the ui-spec config state table is
 * unit-testable. SD6: preset labels are nonbreaking (`10%`, `Custom` never split).
 */
data class PowerLossConfigDraftState(
    /** Last applied (authoritative) config — what the parent model holds. */
    val committed: PowerLossConfig = PowerLossConfig.DEFAULT,
    /** Working copy under edit. */
    val draft: PowerLossConfig = PowerLossConfig.DEFAULT,
    /** Raw custom-threshold input text (kept verbatim while invalid). */
    val customThresholdInput: String = "",
    /** True while the Custom chip is selected (draft threshold edited by hand). */
    val customSelected: Boolean = false,
    /** Raw max-working-reps input text. */
    val maxRepsInput: String = PowerLossConfig.DEFAULT.maxWorkingReps.toString(),
    /** Dynamic upper bound from the accepted execution plan (SD4 — never a guessed universal 254). */
    val maxAllowedWorkingReps: Int = 254,
    /** The exercise's protocol family (V1: Old School / Pump / Echo / Just Lift). */
    val protocolFamily: PowerLossProtocolFamily = PowerLossProtocolFamily.OLD_SCHOOL,
    /** Internal capability gate (validated paired samples available). Default off. */
    val pairedSamplesValidated: Boolean = true,
    /** Pending unsupported-mode confirmation, or null. */
    val pendingModeConfirmation: ModeConfirmation? = null,
    /** A previously saved POWER config on an excluded mode: retained but inert. */
    val incompatibleSavedPowerConfig: Boolean = false,
) {
    val capabilityAvailable: Boolean
        get() = PowerLossCapabilityBridge.isEligible(protocolFamily, pairedSamplesValidated)

    val effectiveTargetMode: TargetMode
        get() = if (capabilityAvailable) draft.targetMode else TargetMode.REP_TARGET

    /** POWER UI is expanded only for an effective POWER target. */
    val powerSectionExpanded: Boolean get() = effectiveTargetMode == TargetMode.POWER

    /** Persisted POWER config that cannot run in this mode: dormant/inert, no active badge. */
    val dormantPowerConfig: Boolean
        get() = draft.targetMode == TargetMode.POWER && !powerSectionExpanded

    /** Caution is persistent at 36–40 inclusive; 35 shows none (ui-spec §3.4). */
    val highThresholdCautionVisible: Boolean
        get() = draft.thresholdPercent in HIGH_THRESHOLD_CAUTION_MIN..PowerLossConfig.MAX_THRESHOLD_PERCENT

    val customThresholdError: String?
        get() = if (customSelected && parseCustomThreshold() == null) {
            PowerLossUiCopy.CUSTOM_THRESHOLD_ERROR
        } else {
            null
        }

    val maxRepsError: String?
        get() {
            val parsed = parseMaxReps() ?: return PowerLossUiCopy.maxWorkingRepsError(maxAllowedWorkingReps)
            return if (parsed < PowerLossConfig.MIN_MAX_WORKING_REPS || parsed > maxAllowedWorkingReps) {
                PowerLossUiCopy.maxWorkingRepsError(maxAllowedWorkingReps)
            } else {
                null
            }
        }

    /** Apply is enabled only with every effective field valid and a capability path. */
    val canApply: Boolean
        get() = pendingModeConfirmation == null &&
            customThresholdError == null &&
            maxRepsError == null &&
            (effectiveTargetMode != TargetMode.POWER || capabilityAvailable)

    val nearLimitCueReadback: String
        get() = PowerLossUiCopy.nearLimitCueReadback(draft.thresholdPercent)

    /** Preset chips with SD6 nonbreaking labels. */
    val presetChips: List<PresetChip>
        get() = ThresholdPreset.entries.map { preset ->
            PresetChip(
                preset = preset,
                label = preset.nonbreakingLabel(),
                selected = when (preset) {
                    ThresholdPreset.CUSTOM -> customSelected
                    else -> !customSelected && draft.thresholdPercent == preset.percent
                },
            )
        }

    // ── Transition helpers (each returns the next state) ───────────────────────

    fun selectTargetMode(mode: TargetMode): PowerLossConfigDraftState {
        // Entering an excluded mode with a POWER draft demands confirmation (§3).
        val unsupportedSwitch = mode == TargetMode.POWER && !protocolFamily.supportsPowerLoss
        return if (unsupportedSwitch) {
            copy(pendingModeConfirmation = ModeConfirmation(targetModeRequested = mode))
        } else {
            copy(
                draft = draft.copy(targetMode = mode),
                pendingModeConfirmation = null,
                incompatibleSavedPowerConfig = false,
            )
        }
    }

    /** Dialog confirm: mode change proceeds with effective REP; POWER settings kept dormant. */
    fun confirmUseRepTarget(): PowerLossConfigDraftState = copy(
        draft = draft.copy(targetMode = TargetMode.REP_TARGET),
        pendingModeConfirmation = null,
        incompatibleSavedPowerConfig = true,
    )

    /** Dialog cancel: `Keep current mode` — mode and target preserved. */
    fun dismissModeConfirmation(): PowerLossConfigDraftState =
        copy(pendingModeConfirmation = null)

    fun selectPreset(preset: ThresholdPreset): PowerLossConfigDraftState = when (preset) {
        ThresholdPreset.CUSTOM -> copy(customSelected = true)
        else -> copy(
            draft = draft.copy(thresholdPercent = preset.percent),
            customSelected = false,
            // Preset tap replaces an invalid custom draft and clears the error (§3.3).
            customThresholdInput = preset.percent.toString(),
        )
    }

    /** Free-typed custom threshold; the raw draft is kept while invalid (§3.3). */
    fun updateCustomThreshold(text: String): PowerLossConfigDraftState {
        val parsed = text.trim().toIntOrNull()?.takeIf { text.trim().all { it in '0'..'9' || it == '-' } }
        return copy(
            customThresholdInput = text,
            customSelected = true,
            draft = if (parsed != null && parsed in PowerLossConfig.MIN_THRESHOLD_PERCENT..PowerLossConfig.MAX_THRESHOLD_PERCENT) {
                draft.copy(thresholdPercent = parsed)
            } else {
                draft
            },
        )
    }

    fun updateMaxReps(text: String): PowerLossConfigDraftState {
        val parsed = text.trim().toIntOrNull()
        return copy(
            maxRepsInput = text,
            draft = if (parsed != null && parsed in PowerLossConfig.MIN_MAX_WORKING_REPS..maxAllowedWorkingReps) {
                draft.copy(maxWorkingReps = parsed)
            } else {
                draft
            },
        )
    }

    fun setRequireTwoConsecutive(enabled: Boolean): PowerLossConfigDraftState =
        copy(draft = draft.copy(requireTwoConsecutive = enabled))

    /**
     * Apply: validate all effective fields and commit atomically. Returns the
     * committed state, or this state unchanged when validation fails.
     */
    fun apply(): PowerLossConfigDraftState {
        if (!canApply) return this
        val threshold = if (customSelected) parseCustomThreshold() ?: return this else draft.thresholdPercent
        val maxReps = parseMaxReps() ?: return this
        val committedConfig = draft.copy(
            thresholdPercent = threshold,
            maxWorkingReps = maxReps,
        ).validated() ?: return this
        return copy(committed = committedConfig, draft = committedConfig)
    }

    /**
     * Cancel / outside dismissal / system Back: discard the unapplied draft and
     * restore the original mode/target/settings (dormant values preserved).
     */
    fun cancel(): PowerLossConfigDraftState = copy(
        draft = committed,
        customSelected = committed.thresholdPercent !in ThresholdPreset.entries
            .filter { it != ThresholdPreset.CUSTOM }
            .map { it.percent },
        customThresholdInput = committed.thresholdPercent.toString(),
        maxRepsInput = committed.maxWorkingReps.toString(),
        pendingModeConfirmation = null,
    )

    private fun parseCustomThreshold(): Int? =
        customThresholdInput.trim().toIntOrNull()?.takeIf {
            it in PowerLossConfig.MIN_THRESHOLD_PERCENT..PowerLossConfig.MAX_THRESHOLD_PERCENT
        }

    private fun parseMaxReps(): Int? =
        maxRepsInput.trim().toIntOrNull()?.takeIf {
            it in PowerLossConfig.MIN_MAX_WORKING_REPS..maxAllowedWorkingReps
        }

    companion object {
        const val HIGH_THRESHOLD_CAUTION_MIN = 36

        /**
         * Draft seeded from an existing per-exercise config (profile-loaded or template).
         * Profile-default boundary: seeds are per-exercise/per-profile — never reused
         * across profiles (callers scope by profile; see [PowerLossExecutionScope]).
         */
        fun seededFrom(
            config: PowerLossConfig,
            maxAllowedWorkingReps: Int,
            protocolFamily: PowerLossProtocolFamily,
            pairedSamplesValidated: Boolean = true,
        ): PowerLossConfigDraftState = PowerLossConfigDraftState(
            committed = config,
            draft = config,
            customThresholdInput = config.thresholdPercent.toString(),
            customSelected = config.thresholdPercent !in ThresholdPreset.entries
                .filter { it != ThresholdPreset.CUSTOM }
                .map { it.percent },
            maxRepsInput = config.maxWorkingReps.toString(),
            maxAllowedWorkingReps = maxAllowedWorkingReps,
            protocolFamily = protocolFamily,
            pairedSamplesValidated = pairedSamplesValidated,
            incompatibleSavedPowerConfig =
                config.targetMode == TargetMode.POWER && !protocolFamily.supportsPowerLoss,
        )
    }
}

/** Threshold preset chips: `10%`, `20%`, `30%`, `Custom` (default 20). */
enum class ThresholdPreset(val percent: Int) {
    P10(10),
    P20(20),
    P30(30),
    CUSTOM(20),
    ;

    /** SD6: numeric-unit labels are nonbreaking — `10%` never splits into `10` / `%`. */
    fun nonbreakingLabel(): String = when (this) {
        CUSTOM -> "Cus${PowerLossUiCopy.WJ}tom"
        else -> "${percent}${PowerLossUiCopy.WJ}%"
    }
}

/** Selected preset chip presentation state. */
data class PresetChip(
    val preset: ThresholdPreset,
    val label: String,
    val selected: Boolean,
)

/** Pending unsupported-mode confirmation dialog (ui-spec §3). */
data class ModeConfirmation(
    val targetModeRequested: TargetMode,
    val title: String = PowerLossUiCopy.MODE_CONFIRMATION_TITLE,
    val body: String = PowerLossUiCopy.modeConfirmationBody("this mode"),
    val cancelAction: String = PowerLossUiCopy.MODE_CONFIRMATION_CANCEL,
    val confirmAction: String = PowerLossUiCopy.MODE_CONFIRMATION_CONFIRM,
)

/**
 * Capability gate bridge — the domain rule, surfaced for UI state. Unknown or
 * unsupported protocol families never arm POWER.
 */
object PowerLossCapabilityBridge {
    fun isEligible(
        protocolFamily: PowerLossProtocolFamily,
        pairedSamplesValidated: Boolean,
    ): Boolean = protocolFamily.supportsPowerLoss && pairedSamplesValidated

    /**
     * Strict effective-mode reader for persisted values: an unknown enum NEVER
     * arms POWER (spec §7).
     */
    fun effectiveTargetMode(persisted: String?): TargetMode =
        TargetMode.fromPersisted(persisted) ?: TargetMode.REP_TARGET
}

/**
 * Profile/execution scoping for ad-hoc and routine config propagation (ui-spec §3
 * "Apply ad-hoc choices to next execution only; no cross-profile reuse").
 */
class PowerLossExecutionScope {
    private val byProfile = mutableMapOf<String, PowerLossConfig>()

    fun savedDefaultFor(profileId: String): PowerLossConfig =
        byProfile[profileId] ?: PowerLossConfig.DEFAULT

    /** Persist through the caller's existing save mechanism only — never cross-profile. */
    fun rememberFor(profileId: String, config: PowerLossConfig) {
        byProfile[profileId] = config
    }

    /** One-shot frozen config for the next execution; clears nothing for other profiles. */
    fun freezeForExecution(profileId: String): PowerLossConfig = savedDefaultFor(profileId)
}

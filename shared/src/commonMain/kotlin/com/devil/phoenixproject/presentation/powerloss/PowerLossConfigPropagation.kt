package com.devil.phoenixproject.presentation.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.PowerLossProtocolFamily
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.presentation.manager.PowerLossRuntimeSettings

/**
 * Issue #1392 — config propagation helpers (P5; integration-model §4).
 *
 * RoutineFlowManager, DefaultWorkoutSessionManager, ExerciseConfigViewModel and
 * MainViewModel copy the per-exercise `ExerciseConfig.powerLoss` into the runtime
 * seam with these helpers: initial, next set/exercise, adjusted/retry and
 * transition paths. Unsupported timed/bodyweight/TUT paths and any unknown
 * persisted target enum resolve to inert REP_TARGET — POWER never arms from an
 * unknown or excluded mode (spec §7, SD1).
 */
internal fun powerLossRuntimeSettingsFor(
    config: PowerLossConfig,
    mode: ProgramMode,
    isJustLiftExecutionStyle: Boolean = false,
    savedNominalReps: Int? = config.savedNominalReps,
    pairedSamplesValidated: Boolean = false,
): PowerLossRuntimeSettings {
    val family = PowerLossProtocolFamily.of(mode, isJustLiftExecutionStyle)
    // Retained-but-inert: an excluded mode keeps the saved POWER values but the
    // effective runtime target is REP_TARGET.
    val effectiveMode =
        if (family.supportsPowerLoss) config.targetMode else TargetMode.REP_TARGET
    return PowerLossRuntimeSettings(
        targetMode = effectiveMode,
        thresholdPercent = config.thresholdPercent,
        maxWorkingReps = config.maxWorkingReps,
        requireTwoConsecutive = config.requireTwoConsecutive,
        savedNominalReps = savedNominalReps,
        protocolFamily = family,
        pairedSamplesValidated = pairedSamplesValidated,
    )
}

/**
 * Strict reader for persisted target-mode strings: an unknown value NEVER arms
 * POWER. Returns a full inert config carrying the remaining persisted values.
 */
internal fun powerLossConfigFromPersisted(
    persistedTargetMode: String?,
    thresholdPercent: Int = PowerLossConfig.DEFAULT.thresholdPercent,
    maxWorkingReps: Int = PowerLossConfig.DEFAULT.maxWorkingReps,
    requireTwoConsecutive: Boolean = false,
    savedNominalReps: Int? = null,
): PowerLossConfig = PowerLossConfig(
    targetMode = TargetMode.fromPersisted(persistedTargetMode) ?: TargetMode.REP_TARGET,
    thresholdPercent = thresholdPercent,
    maxWorkingReps = maxWorkingReps,
    requireTwoConsecutive = requireTwoConsecutive,
    savedNominalReps = savedNominalReps,
)

/**
 * Unsupported execution paths (timed, bodyweight) keep their saved POWER values
 * but resolve to the inert default for execution: POWER never arms from an
 * excluded mode (spec §7). TUT/TUT Beast/Eccentric-Only are excluded by the
 * protocol family inside [powerLossRuntimeSettingsFor].
 */
internal fun powerLossExecutionConfig(
    config: PowerLossConfig,
    isTimed: Boolean,
    isBodyweight: Boolean,
): PowerLossConfig = if (isTimed || isBodyweight) PowerLossConfig.DEFAULT else config

/**
 * Internal capability gate for the power-loss runtime seam (spec §1/§7: the
 * feature stays off by default behind an internal gate until the P6–P8 release
 * gate authorises enablement — never enabled by a worker). The gate models the
 * "validated paired samples" prerequisite only; protocol-family eligibility is
 * resolved per set inside [powerLossRuntimeSettingsFor].
 */
internal object PowerLossRuntimeGate {
    /**
     * True only when validated paired samples exist for the connected trainer.
     * Default OFF — POWER config intent is retained but `powerActive` stays false.
     */
    var pairedSamplesValidated: Boolean = false
}

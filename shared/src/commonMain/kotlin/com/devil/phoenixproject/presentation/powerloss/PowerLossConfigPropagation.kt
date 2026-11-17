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

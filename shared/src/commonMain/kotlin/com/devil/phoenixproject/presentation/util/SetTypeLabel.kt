package com.devil.phoenixproject.presentation.util

import com.devil.phoenixproject.domain.model.RepCount

/**
 * Issue #646: pure-Kotlin helper for the workout set-type badge.
 *
 * Decides whether the active workout header should read
 * "Calibration Reps" / "Warm-up Set N" / "Working Set".
 *
 * Kept free of `@Composable` and string resources so it stays trivially
 * testable as plain JVM code. Call sites resolve the sealed label to a
 * locale string via [com.devil.phoenixproject.presentation.R] mappings.
 *
 * `showCalibrationLabel` is `false` at Set Ready because the firmware
 * calibration buffer runs during the active set, not at Set Ready.
 */
sealed class SetTypeLabel {
    data object Calibration : SetTypeLabel()
    data class Warmup(val setNumber: Int) : SetTypeLabel()
    data object Working : SetTypeLabel()
}

// ponytail: 3 mirrors Constants.DEFAULT_WARMUP_REPS; revisit if firmware
// calibration buffer size ever changes.
private const val CALIBRATION_BUFFER_REPS = 3

fun setTypeLabel(
    repCount: RepCount,
    currentWarmupSetIndex: Int,
    showCalibrationLabel: Boolean,
): SetTypeLabel = when {
    showCalibrationLabel && repCount.warmupReps < CALIBRATION_BUFFER_REPS && !repCount.isWarmupComplete ->
        SetTypeLabel.Calibration
    currentWarmupSetIndex >= 0 ->
        SetTypeLabel.Warmup(currentWarmupSetIndex + 1)
    else ->
        SetTypeLabel.Working
}

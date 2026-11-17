package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric

/**
 * Issue #1400 (F2): a constant-load rep counts towards set-level velocity aggregates only
 * once its concentric mean per-cable load reaches this fraction of the commanded weight.
 */
internal const val RAMP_ELIGIBLE_LOAD_FRACTION = 0.9f

/**
 * Issue #1400 (F2): the load-ramp eligibility rule.
 *
 * In Old School (including Just Lift in Old School) the commanded per-cable load is constant,
 * but the rep lifted while the machine is still ramping the weight on after the calibration
 * pulls is performed at a fraction of it. That rep is genuinely counted (count, volume, PR and
 * per-rep display are unchanged) but it must not become the velocity-loss baseline or enter
 * set MCV.
 *
 * Returns false only for that ramp case: Old School, no per-rep progression/regression, no
 * rack counterweight, a positive commanded weight, and a concentric mean per-cable load (the
 * loaded cable of each sample, so single-cable exercises are judged on the working cable)
 * below [RAMP_ELIGIBLE_LOAD_FRACTION] of the commanded weight. Variable-load modes (Pump, TUT,
 * TUT Beast, Eccentric Only, Echo) and progression/regression sets have no constant commanded
 * load and get no ramp filter. A counterweight lowers the machine load below the programmed
 * weight by a rack-dependent amount, so those sets also get no ramp filter rather than risk
 * marking every working rep ineligible. No reps are dropped by position and nothing is
 * filtered by speed.
 *
 * @param commandedPerCableKg the per-cable weight the machine was commanded (already clamped
 *   to the connected model's ceiling by the caller)
 */
internal fun reachedCommandedLoadForAggregates(
    programMode: ProgramMode,
    progressionRegressionKg: Float,
    commandedPerCableKg: Float,
    concentricMetrics: List<WorkoutMetric>,
    counterweightKg: Float = 0f,
): Boolean {
    if (programMode != ProgramMode.OldSchool) return true
    if (progressionRegressionKg != 0f) return true
    if (counterweightKg != 0f) return true
    if (!commandedPerCableKg.isFinite() || commandedPerCableKg <= 0f) return true
    val perCableLoads = concentricMetrics
        .map { maxOf(it.loadA, it.loadB) }
        .filter { it.isFinite() }
    if (perCableLoads.isEmpty()) return true
    return perCableLoads.average() >= commandedPerCableKg * RAMP_ELIGIBLE_LOAD_FRACTION
}

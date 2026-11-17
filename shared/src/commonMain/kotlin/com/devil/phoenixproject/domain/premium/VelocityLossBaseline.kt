package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.BiomechanicsRepResult

/**
 * Issue #1400 (F3): the single velocity-loss baseline policy.
 *
 * Per-rep velocity loss, the velocity-loss auto-end signal ([com.devil.phoenixproject.domain.model.VelocityResult.shouldStopSet])
 * and the set-level [com.devil.phoenixproject.domain.model.BiomechanicsSetSummary.totalVelocityLossPercent]
 * all use this policy, so the three values agree:
 *
 * - Only reps that are [BiomechanicsRepResult.aggregateEligible] and have a valid MCV
 *   (finite and > 0) count. Calibration/ramp reps and empty windows never set the baseline.
 * - The baseline is the first such rep's MCV; the comparison is the latest such rep's MCV.
 * - Loss is `(baseline - current) / baseline * 100`, clamped to 0..100, so a faster later
 *   rep reads 0 %, never a negative loss.
 */
internal object VelocityLossBaseline {

    /** True when [mcvMmS] can take part in velocity aggregates. */
    fun isValidMcv(mcvMmS: Float): Boolean = mcvMmS.isFinite() && mcvMmS > 0f

    /** True when [result] counts towards set-level velocity aggregates and the loss baseline. */
    fun countsTowardsAggregates(result: BiomechanicsRepResult): Boolean =
        result.aggregateEligible && isValidMcv(result.velocity.meanConcentricVelocityMmS)

    /** Clamped loss of [currentMcvMmS] against [baselineMcvMmS]; null when either is invalid. */
    fun lossPercent(baselineMcvMmS: Float, currentMcvMmS: Float): Float? {
        if (!isValidMcv(baselineMcvMmS) || !isValidMcv(currentMcvMmS)) return null
        return ((baselineMcvMmS - currentMcvMmS) / baselineMcvMmS * 100f).coerceIn(0f, 100f)
    }

    /**
     * Set-level loss over [results]: the first versus the last counting rep.
     * Null when fewer than two reps count.
     */
    fun setLossPercent(results: List<BiomechanicsRepResult>): Float? {
        val counting = results.filter(::countsTowardsAggregates)
        if (counting.size < 2) return null
        return lossPercent(
            baselineMcvMmS = counting.first().velocity.meanConcentricVelocityMmS,
            currentMcvMmS = counting.last().velocity.meanConcentricVelocityMmS,
        )
    }
}

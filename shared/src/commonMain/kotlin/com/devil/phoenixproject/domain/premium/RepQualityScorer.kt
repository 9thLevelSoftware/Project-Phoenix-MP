package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepQualityScore
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.util.RunningAverage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scores each rep 0-100 based on four weighted components:
 * - ROM consistency (0-30 points)
 * - Velocity consistency (0-25 points)
 * - Eccentric control (0-25 points)
 * - Movement smoothness (0-20 points)
 *
 * Stateful: accumulates per-set baselines for ROM and velocity consistency.
 * Call [reset] between sets.
 */
class RepQualityScorer {

    companion object {
        private const val ROM_MAX_POINTS = 30f
        private const val VELOCITY_MAX_POINTS = 25f
        private const val ECCENTRIC_MAX_POINTS = 25f
        private const val SMOOTHNESS_MAX_POINTS = 20f
        private const val DEVIATION_MULTIPLIER = 3f
        private const val IDEAL_ECCENTRIC_RATIO = 2.0f
        private const val SMOOTHNESS_CV_MULTIPLIER = 2f
        private const val SMOOTHNESS_NEUTRAL = 10f
        private const val TREND_THRESHOLD = 5f
    }

    private val romAverage = RunningAverage()
    private val velocityAverage = RunningAverage()
    private val scoredReps = mutableListOf<RepQualityScore>()

    /**
     * Score a single rep based on its metric data.
     *
     * First rep of a set receives perfect ROM and velocity scores (no baseline to compare).
     * Subsequent reps are scored relative to the running average.
     */
    fun scoreRep(repData: RepMetricData): RepQualityScore {
        val isFirstRep = romAverage.count() == 0

        val romScore = scoreRom(repData.rangeOfMotionMm, isFirstRep)
        val velocityScore = scoreVelocity(repData.avgVelocityConcentric, isFirstRep)
        val eccentricScore = scoreEccentricControl(repData.eccentricDurationMs, repData.concentricDurationMs)
        val smoothness = scoreSmoothness(repData.concentricVelocities)

        // Update running averages after scoring (so current rep is scored against prior average)
        romAverage.add(repData.rangeOfMotionMm)
        velocityAverage.add(repData.avgVelocityConcentric)

        val composite = (romScore + velocityScore + eccentricScore + smoothness)
            .roundToInt()
            .coerceIn(0, 100)

        val result = RepQualityScore(
            composite = composite,
            romScore = romScore,
            velocityScore = velocityScore,
            eccentricControlScore = eccentricScore,
            smoothnessScore = smoothness,
            repNumber = repData.repNumber
        )

        scoredReps.add(result)
        return result
    }

    /**
     * Get aggregated quality statistics for the current set.
     *
     * @throws IllegalStateException if no reps have been scored
     */
    fun getSetSummary(): SetQualitySummary {
        check(scoredReps.isNotEmpty()) { "No reps scored yet" }

        val best = scoredReps.maxBy { it.composite }
        val worst = scoredReps.minBy { it.composite }
        val avg = scoredReps.map { it.composite }.average().roundToInt()

        return SetQualitySummary(
            averageScore = avg,
            bestScore = best.composite,
            worstScore = worst.composite,
            bestRepNumber = best.repNumber,
            worstRepNumber = worst.repNumber,
            trend = getTrend(),
            repScores = scoredReps.toList()
        )
    }

    /**
     * Compute the quality trend from scored reps.
     *
     * Needs >= 3 reps. Compares first-half average to second-half average.
     * - Second half > first half + 5: IMPROVING
     * - Second half < first half - 5: DECLINING
     * - Otherwise: STABLE
     */
    fun getTrend(): QualityTrend {
        if (scoredReps.size < 3) return QualityTrend.STABLE

        val mid = scoredReps.size / 2
        val firstHalfAvg = scoredReps.take(mid).map { it.composite }.average()
        val secondHalfAvg = scoredReps.drop(mid).map { it.composite }.average()

        return when {
            secondHalfAvg > firstHalfAvg + TREND_THRESHOLD -> QualityTrend.IMPROVING
            secondHalfAvg < firstHalfAvg - TREND_THRESHOLD -> QualityTrend.DECLINING
            else -> QualityTrend.STABLE
        }
    }

    /**
     * Clear all state for a new set.
     */
    fun reset() {
        romAverage.reset()
        velocityAverage.reset()
        scoredReps.clear()
    }

    private fun scoreRom(repROM: Float, isFirstRep: Boolean): Float {
        if (isFirstRep) return ROM_MAX_POINTS
        val avg = romAverage.average()
        if (avg == 0f) return ROM_MAX_POINTS
        val deviation = abs(repROM - avg) / avg
        return (ROM_MAX_POINTS * maxOf(0f, 1f - deviation * DEVIATION_MULTIPLIER)).coerceIn(0f, ROM_MAX_POINTS)
    }

    private fun scoreVelocity(avgVelocity: Float, isFirstRep: Boolean): Float {
        if (isFirstRep) return VELOCITY_MAX_POINTS
        val avg = velocityAverage.average()
        if (avg == 0f) return VELOCITY_MAX_POINTS
        val deviation = abs(avgVelocity - avg) / avg
        return (VELOCITY_MAX_POINTS * maxOf(0f, 1f - deviation * DEVIATION_MULTIPLIER)).coerceIn(0f, VELOCITY_MAX_POINTS)
    }

    private fun scoreEccentricControl(eccentricMs: Long, concentricMs: Long): Float {
        if (concentricMs == 0L) return 0f
        val ratio = eccentricMs.toFloat() / concentricMs.toFloat()
        return (ECCENTRIC_MAX_POINTS * maxOf(0f, 1f - abs(ratio - IDEAL_ECCENTRIC_RATIO) / IDEAL_ECCENTRIC_RATIO))
            .coerceIn(0f, ECCENTRIC_MAX_POINTS)
    }

    private fun scoreSmoothness(velocities: FloatArray): Float {
        if (velocities.isEmpty()) return SMOOTHNESS_NEUTRAL
        val mean = velocities.average().toFloat()
        if (mean == 0f) return SMOOTHNESS_NEUTRAL
        val variance = velocities.map { (it - mean) * (it - mean) }.average().toFloat()
        val coeffOfVariation = sqrt(variance) / mean
        return (SMOOTHNESS_MAX_POINTS * maxOf(0f, 1f - coeffOfVariation * SMOOTHNESS_CV_MULTIPLIER))
            .coerceIn(0f, SMOOTHNESS_MAX_POINTS)
    }
}

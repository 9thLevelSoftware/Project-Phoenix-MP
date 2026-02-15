package com.devil.phoenixproject.domain.assessment

import kotlin.math.roundToInt

/**
 * Core engine for VBT-based strength assessment.
 *
 * Estimates a user's one-rep max (1RM) from progressive-weight sets by fitting
 * an ordinary least squares (OLS) linear regression to load vs. mean concentric
 * velocity data points, then extrapolating to the velocity at which 1RM occurs.
 *
 * Pure domain logic with no UI or database dependencies.
 */
class AssessmentEngine {

    /**
     * Estimate one-rep max from load-velocity data points using linear regression.
     *
     * Performs OLS regression: velocity = slope * load + intercept
     * Then extrapolates load at velocity = config.oneRmVelocityMs.
     *
     * @param points Load-velocity data points from assessment sets
     * @param config Assessment configuration parameters
     * @return Assessment result with estimated 1RM, or null if insufficient/invalid data
     */
    fun estimateOneRepMax(
        points: List<LoadVelocityPoint>,
        config: AssessmentConfig = AssessmentConfig()
    ): AssessmentResult? {
        if (points.size < config.minSets) return null

        val n = points.size.toDouble()
        val sumX = points.sumOf { it.loadKg.toDouble() }
        val sumY = points.sumOf { it.meanVelocityMs.toDouble() }
        val sumXY = points.sumOf { it.loadKg.toDouble() * it.meanVelocityMs.toDouble() }
        val sumX2 = points.sumOf { it.loadKg.toDouble() * it.loadKg.toDouble() }

        val denominator = n * sumX2 - sumX * sumX
        // Avoid division by zero (all loads identical)
        if (denominator == 0.0) return null

        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        // Velocity should decrease with increasing load (negative slope)
        if (slope >= 0.0) return null

        // Extrapolate load at 1RM velocity
        // velocity = slope * load + intercept
        // load = (velocity - intercept) / slope
        val estimatedLoad = (config.oneRmVelocityMs.toDouble() - intercept) / slope

        // Compute R-squared
        val meanY = sumY / n
        val ssTot = points.sumOf { (it.meanVelocityMs.toDouble() - meanY).let { d -> d * d } }
        val ssRes = points.sumOf { p ->
            val predicted = slope * p.loadKg.toDouble() + intercept
            val residual = p.meanVelocityMs.toDouble() - predicted
            residual * residual
        }
        val r2 = if (ssTot == 0.0) 1.0f else (1.0 - ssRes / ssTot).toFloat()

        return AssessmentResult(
            estimatedOneRepMaxKg = estimatedLoad.toFloat(),
            loadVelocityPoints = points,
            r2 = r2,
            velocityAt1RM = config.oneRmVelocityMs
        )
    }

    /**
     * Determine whether the assessment should stop based on latest velocity.
     *
     * @param latestVelocity Mean concentric velocity of the most recent set (m/s)
     * @param config Assessment configuration parameters
     * @return true if velocity is at or below the stop threshold
     */
    fun shouldStopAssessment(
        latestVelocity: Float,
        config: AssessmentConfig = AssessmentConfig()
    ): Boolean {
        return latestVelocity <= config.velocityThresholdMs
    }

    /**
     * Suggest the next weight for the assessment based on current velocity.
     *
     * Uses velocity-based progression:
     * - > 0.8 m/s: large jump (2x increment)
     * - > 0.5 m/s: standard jump (1x increment)
     * - > 0.3 m/s: small jump (0.5x increment)
     * - <= 0.3 m/s: no more sets needed
     *
     * Result is clamped to 0.5kg increments (machine resolution) and max 220kg.
     *
     * @param currentLoadKg Current set weight in kilograms
     * @param currentVelocity Mean concentric velocity of current set (m/s)
     * @param config Assessment configuration parameters
     * @return Suggested weight for next set in kilograms
     */
    fun suggestNextWeight(
        currentLoadKg: Float,
        currentVelocity: Float,
        config: AssessmentConfig = AssessmentConfig()
    ): Float {
        val increment = when {
            currentVelocity > 0.8f -> config.weightIncrementKg * 2f
            currentVelocity > 0.5f -> config.weightIncrementKg
            currentVelocity > 0.3f -> config.weightIncrementKg / 2f
            else -> 0f
        }

        val rawWeight = currentLoadKg + increment

        // Clamp to 0.5kg increments (machine resolution)
        val snapped = (rawWeight * 2f).roundToInt() / 2f

        // Clamp to max 220kg
        return snapped.coerceAtMost(220f)
    }
}

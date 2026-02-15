package com.devil.phoenixproject.domain.assessment

/**
 * A single load-velocity data point from one assessment set.
 *
 * @property loadKg Weight used for the set in kilograms
 * @property meanVelocityMs Mean concentric velocity in meters/second
 */
data class LoadVelocityPoint(
    val loadKg: Float,
    val meanVelocityMs: Float
)

/**
 * Result of a single assessment set.
 *
 * @property setNumber 1-indexed set number in the assessment
 * @property loadKg Weight used for this set in kilograms
 * @property reps Number of reps performed
 * @property meanVelocityMs Mean concentric velocity in meters/second
 * @property peakVelocityMs Peak concentric velocity in meters/second
 */
data class AssessmentSetResult(
    val setNumber: Int,
    val loadKg: Float,
    val reps: Int,
    val meanVelocityMs: Float,
    val peakVelocityMs: Float
)

/**
 * Final assessment output with estimated 1RM and regression quality metrics.
 *
 * @property estimatedOneRepMaxKg Estimated one-rep max extrapolated from load-velocity regression
 * @property loadVelocityPoints Data points used for the regression
 * @property r2 R-squared value indicating regression fit quality (0.0 to 1.0)
 * @property velocityAt1RM The velocity threshold used for 1RM extrapolation (m/s)
 */
data class AssessmentResult(
    val estimatedOneRepMaxKg: Float,
    val loadVelocityPoints: List<LoadVelocityPoint>,
    val r2: Float,
    val velocityAt1RM: Float
)

/**
 * Configurable parameters for the strength assessment protocol.
 *
 * @property minSets Minimum number of sets (data points) required for regression
 * @property maxSets Maximum number of assessment sets
 * @property velocityThresholdMs Velocity below which assessment should stop (m/s)
 * @property oneRmVelocityMs Velocity at which 1RM is assumed to occur (m/s)
 * @property startingWeightPercent Starting weight as fraction of estimated max
 * @property weightIncrementKg Standard weight increment between sets (kg)
 */
data class AssessmentConfig(
    val minSets: Int = 2,
    val maxSets: Int = 5,
    val velocityThresholdMs: Float = 0.3f,
    val oneRmVelocityMs: Float = 0.17f,
    val startingWeightPercent: Float = 0.4f,
    val weightIncrementKg: Float = 10f
)

package com.devil.phoenixproject.domain.model

/**
 * Direction for a next-set weight suggestion.
 */
enum class WeightAdjustmentDirection {
    INCREASE,
    MAINTAIN,
    DECREASE,
}

/**
 * Confidence for a suggestion based on available set evidence.
 */
enum class RecommendationConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Pure input for next-set weight recommendation.
 *
 * All weights are machine/storage weight per cable in kilograms.
 */
data class WeightAdjustmentInput(
    val exerciseId: String?,
    val exerciseName: String?,
    val targetExerciseId: String?,
    val targetSetIndex: Int?,
    val targetReps: Int,
    val actualReps: Int,
    val currentWeightKgPerCable: Float,
    val weightIncrementKg: Float,
    val qualitySummary: SetQualitySummary?,
    val biomechanicsSummary: BiomechanicsSetSummary?,
    val isBodyweight: Boolean,
    val hasNextSetTarget: Boolean,
)

/**
 * Runtime-only suggestion for the next Set Ready screen.
 */
data class WeightAdjustmentRecommendation(
    val direction: WeightAdjustmentDirection,
    val currentWeightKgPerCable: Float,
    val recommendedWeightKgPerCable: Float,
    val confidence: RecommendationConfidence,
    val reasonCode: String,
    val explanation: String,
    val targetExerciseId: String?,
    val targetSetIndex: Int?,
) {
    val deltaKgPerCable: Float
        get() = recommendedWeightKgPerCable - currentWeightKgPerCable
}

package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.RecommendationConfidence
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WeightAdjustmentInput
import com.devil.phoenixproject.domain.model.WeightAdjustmentRecommendation
import com.devil.phoenixproject.util.Constants
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Computes conservative, suggestion-only next-set weight adjustments from completed-set quality data.
 */
class RecommendWeightAdjustmentUseCase {

    operator fun invoke(input: WeightAdjustmentInput): WeightAdjustmentRecommendation? {
        if (input.isBodyweight || !input.hasNextSetTarget) return null
        if (input.currentWeightKgPerCable < Constants.MIN_WEIGHT_KG) return null
        if (input.weightIncrementKg <= 0f) return null
        if (input.qualitySummary == null && input.biomechanicsSummary == null) return null

        val severeVelocityLoss = input.biomechanicsSummary.hasSevereVelocityLoss()
        val quality = input.qualitySummary
        val completedTarget = input.targetReps > 0 && input.actualReps >= input.targetReps

        if (severeVelocityLoss) {
            return buildDecrease(
                input = input,
                reasonCode = "VELOCITY_LOSS_LOAD_TOO_HIGH",
                explanation = "Consider reducing weight next set - velocity loss suggests the load was too high.",
                confidence = RecommendationConfidence.HIGH,
            )
        }

        if (quality != null) {
            val finalRepScore = quality.repScores.maxByOrNull { it.repNumber }?.composite
            val averageScore = quality.averageScore

            if (averageScore < LOW_QUALITY_THRESHOLD ||
                (!completedTarget && averageScore < MIXED_QUALITY_MIN)
            ) {
                return buildDecrease(
                    input = input,
                    reasonCode = "TARGET_MISSED_LOW_QUALITY",
                    explanation = "Consider reducing weight next set - rep quality dropped before the target was complete.",
                    confidence = RecommendationConfidence.HIGH,
                )
            }

            if (completedTarget &&
                averageScore >= INCREASE_AVERAGE_THRESHOLD &&
                finalRepScore != null &&
                finalRepScore >= INCREASE_FINAL_REP_THRESHOLD
            ) {
                return buildIncrease(input, quality)
            }

            if (completedTarget &&
                averageScore >= INCREASE_AVERAGE_THRESHOLD &&
                finalRepScore != null &&
                finalRepScore < INCREASE_FINAL_REP_THRESHOLD
            ) {
                return maintain(
                    input = input,
                    reasonCode = "FINAL_REP_QUALITY_LOW_MAINTAIN",
                    explanation = "Keep this weight - the final rep quality was not strong enough to increase safely.",
                    confidence = RecommendationConfidence.MEDIUM,
                )
            }

            return maintain(
                input = input,
                reasonCode = "QUALITY_MIXED_MAINTAIN",
                explanation = "Keep this weight - rep quality was mixed.",
                confidence = if (completedTarget) RecommendationConfidence.MEDIUM else RecommendationConfidence.LOW,
            )
        }

        return maintain(
            input = input,
            reasonCode = "INSUFFICIENT_QUALITY_DATA",
            explanation = "Keep this weight - there is not enough quality data to adjust safely.",
            confidence = RecommendationConfidence.LOW,
        )
    }

    private fun buildIncrease(
        input: WeightAdjustmentInput,
        quality: SetQualitySummary,
    ): WeightAdjustmentRecommendation? {
        val recommended = nextHigherIncrement(input.currentWeightKgPerCable, input.weightIncrementKg)
        if (recommended > Constants.MAX_WEIGHT_PER_CABLE_KG || recommended <= input.currentWeightKgPerCable) return null

        val confidence = if (quality.repScores.size >= HIGH_CONFIDENCE_REP_COUNT) {
            RecommendationConfidence.HIGH
        } else {
            RecommendationConfidence.MEDIUM
        }

        return WeightAdjustmentRecommendation(
            direction = WeightAdjustmentDirection.INCREASE,
            currentWeightKgPerCable = input.currentWeightKgPerCable,
            recommendedWeightKgPerCable = recommended,
            confidence = confidence,
            reasonCode = "HIGH_QUALITY_TARGET_COMPLETE",
            explanation = "Try adding weight next set - target reps were completed with strong rep quality.",
            targetExerciseId = input.targetExerciseId,
            targetSetIndex = input.targetSetIndex,
        )
    }

    private fun buildDecrease(
        input: WeightAdjustmentInput,
        reasonCode: String,
        explanation: String,
        confidence: RecommendationConfidence,
    ): WeightAdjustmentRecommendation? {
        val recommended = nextLowerIncrement(input.currentWeightKgPerCable, input.weightIncrementKg)
        if (recommended < Constants.MIN_WEIGHT_KG || recommended >= input.currentWeightKgPerCable) return null

        return WeightAdjustmentRecommendation(
            direction = WeightAdjustmentDirection.DECREASE,
            currentWeightKgPerCable = input.currentWeightKgPerCable,
            recommendedWeightKgPerCable = recommended,
            confidence = confidence,
            reasonCode = reasonCode,
            explanation = explanation,
            targetExerciseId = input.targetExerciseId,
            targetSetIndex = input.targetSetIndex,
        )
    }

    private fun maintain(
        input: WeightAdjustmentInput,
        reasonCode: String,
        explanation: String,
        confidence: RecommendationConfidence,
    ): WeightAdjustmentRecommendation = WeightAdjustmentRecommendation(
        direction = WeightAdjustmentDirection.MAINTAIN,
        currentWeightKgPerCable = input.currentWeightKgPerCable,
        recommendedWeightKgPerCable = input.currentWeightKgPerCable,
        confidence = confidence,
        reasonCode = reasonCode,
        explanation = explanation,
        targetExerciseId = input.targetExerciseId,
        targetSetIndex = input.targetSetIndex,
    )

    private fun BiomechanicsSetSummary?.hasSevereVelocityLoss(): Boolean {
        if (this == null) return false
        return totalVelocityLossPercent?.let { it >= SEVERE_VELOCITY_LOSS_PERCENT } == true ||
            repResults.any { result ->
                result.velocity.shouldStopSet ||
                    (result.velocity.estimatedRepsRemaining != null && result.velocity.estimatedRepsRemaining <= 0)
            }
    }

    private fun nextHigherIncrement(current: Float, increment: Float): Float {
        val steps = floor((current / increment) + EPSILON).toInt() + 1
        return roundKg(steps * increment)
    }

    private fun nextLowerIncrement(current: Float, increment: Float): Float {
        val steps = ceil((current / increment) - EPSILON).toInt() - 1
        return roundKg(steps * increment)
    }

    private fun roundKg(value: Float): Float = kotlin.math.round(value * 10_000f) / 10_000f

    private companion object {
        const val LOW_QUALITY_THRESHOLD = 60
        const val MIXED_QUALITY_MIN = 65
        const val INCREASE_AVERAGE_THRESHOLD = 85
        const val INCREASE_FINAL_REP_THRESHOLD = 75
        const val HIGH_CONFIDENCE_REP_COUNT = 4
        const val SEVERE_VELOCITY_LOSS_PERCENT = 25f
        const val EPSILON = 0.0001f
    }
}

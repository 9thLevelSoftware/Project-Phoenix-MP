package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightRecommendationTest {

    @Test
    fun increaseRecommendationCarriesPositiveDelta() {
        val recommendation = WeightAdjustmentRecommendation(
            direction = WeightAdjustmentDirection.INCREASE,
            currentWeightKgPerCable = 20f,
            recommendedWeightKgPerCable = 22.5f,
            confidence = RecommendationConfidence.MEDIUM,
            reasonCode = "HIGH_QUALITY_TARGET_COMPLETE",
            explanation = "Try adding weight next set.",
            targetExerciseId = "bench-press-001",
            targetSetIndex = 1,
        )

        assertEquals(2.5f, recommendation.deltaKgPerCable)
        assertTrue(recommendation.explanation.isNotBlank())
        assertTrue(recommendation.reasonCode.isNotBlank())
    }

    @Test
    fun decreaseRecommendationCarriesNegativeDelta() {
        val recommendation = WeightAdjustmentRecommendation(
            direction = WeightAdjustmentDirection.DECREASE,
            currentWeightKgPerCable = 20f,
            recommendedWeightKgPerCable = 17.5f,
            confidence = RecommendationConfidence.HIGH,
            reasonCode = "TARGET_MISSED_LOW_QUALITY",
            explanation = "Consider reducing weight next set.",
            targetExerciseId = "bench-press-001",
            targetSetIndex = 1,
        )

        assertEquals(-2.5f, recommendation.deltaKgPerCable)
    }

    @Test
    fun maintainRecommendationCarriesZeroDelta() {
        val recommendation = WeightAdjustmentRecommendation(
            direction = WeightAdjustmentDirection.MAINTAIN,
            currentWeightKgPerCable = 20f,
            recommendedWeightKgPerCable = 20f,
            confidence = RecommendationConfidence.LOW,
            reasonCode = "QUALITY_MIXED_MAINTAIN",
            explanation = "Keep this weight for the next set.",
            targetExerciseId = "bench-press-001",
            targetSetIndex = 1,
        )

        assertEquals(0f, recommendation.deltaKgPerCable)
    }
}

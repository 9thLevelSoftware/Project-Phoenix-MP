package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.AsymmetryResult
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.BiomechanicsVelocityZone
import com.devil.phoenixproject.domain.model.ForceCurveResult
import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RecommendationConfidence
import com.devil.phoenixproject.domain.model.RepQualityScore
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.StrengthProfile
import com.devil.phoenixproject.domain.model.VelocityResult
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WeightAdjustmentInput
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendWeightAdjustmentUseCaseTest {

    private lateinit var useCase: RecommendWeightAdjustmentUseCase

    @BeforeTest
    fun setup() {
        useCase = RecommendWeightAdjustmentUseCase()
    }

    @Test
    fun highQualityCompletedSetRecommendsIncreaseByOneIncrement() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                currentWeight = 20f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(90, 88, 86, 82)),
            ),
        )

        assertEquals(WeightAdjustmentDirection.INCREASE, recommendation?.direction)
        assertEquals(22.5f, recommendation?.recommendedWeightKgPerCable)
        assertEquals(2.5f, recommendation?.deltaKgPerCable)
        assertEquals("HIGH_QUALITY_TARGET_COMPLETE", recommendation?.reasonCode)
    }

    @Test
    fun mixedQualityCompletedSetRecommendsMaintain() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                currentWeight = 20f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(78, 72, 70, 68)),
            ),
        )

        assertEquals(WeightAdjustmentDirection.MAINTAIN, recommendation?.direction)
        assertEquals(20f, recommendation?.recommendedWeightKgPerCable)
        assertEquals(RecommendationConfidence.MEDIUM, recommendation?.confidence)
        assertEquals("QUALITY_MIXED_MAINTAIN", recommendation?.reasonCode)
    }

    @Test
    fun lowQualityFailedSetRecommendsDecrease() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 7,
                currentWeight = 20f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(55, 52, 50, 48)),
            ),
        )

        assertEquals(WeightAdjustmentDirection.DECREASE, recommendation?.direction)
        assertEquals(17.5f, recommendation?.recommendedWeightKgPerCable)
        assertEquals(-2.5f, recommendation?.deltaKgPerCable)
        assertEquals("TARGET_MISSED_LOW_QUALITY", recommendation?.reasonCode)
    }

    @Test
    fun missingQualityAndBiomechanicsSuppressesRecommendation() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                quality = null,
                biomechanics = null,
            ),
        )

        assertNull(recommendation)
    }

    @Test
    fun bodyweightExerciseSuppressesRecommendation() {
        val recommendation = useCase(
            input(
                isBodyweight = true,
                targetReps = 10,
                actualReps = 10,
                quality = qualitySummary(scores = listOf(95, 92, 90)),
            ),
        )

        assertNull(recommendation)
    }

    @Test
    fun recommendationSuppressesWhenIncreaseWouldExceedMaxWeight() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                currentWeight = 110f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(95, 92, 90)),
            ),
        )

        assertNull(recommendation)
    }

    @Test
    fun recommendationSuppressesWhenDecreaseWouldGoBelowMinimumWeight() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 5,
                currentWeight = 0f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(40, 35, 30)),
            ),
        )

        assertNull(recommendation)
    }

    @Test
    fun finalRepQualityBelowThresholdMaintainsInsteadOfIncreasing() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                currentWeight = 20f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(98, 96, 94, 70), averageOverride = 89),
            ),
        )

        assertEquals(WeightAdjustmentDirection.MAINTAIN, recommendation?.direction)
        assertEquals("FINAL_REP_QUALITY_LOW_MAINTAIN", recommendation?.reasonCode)
    }

    @Test
    fun vbtStopSignalRecommendsDecrease() {
        val recommendation = useCase(
            input(
                targetReps = 10,
                actualReps = 10,
                currentWeight = 20f,
                increment = 2.5f,
                quality = qualitySummary(scores = listOf(88, 86, 84, 80)),
                biomechanics = biomechanicsSummary(shouldStopSet = true, totalVelocityLossPercent = 28f),
            ),
        )

        assertEquals(WeightAdjustmentDirection.DECREASE, recommendation?.direction)
        assertEquals("VELOCITY_LOSS_LOAD_TOO_HIGH", recommendation?.reasonCode)
    }

    @Test
    fun configuredIncrementIsUsedAndRoundedToIncrementGrid() {
        val recommendation = useCase(
            input(
                targetReps = 8,
                actualReps = 8,
                currentWeight = 20.1f,
                increment = 0.5f,
                quality = qualitySummary(scores = listOf(90, 88, 86)),
            ),
        )

        assertEquals(20.5f, recommendation?.recommendedWeightKgPerCable)
    }

    private fun input(
        targetReps: Int,
        actualReps: Int,
        currentWeight: Float = 20f,
        increment: Float = 2.5f,
        quality: SetQualitySummary? = qualitySummary(scores = listOf(90, 88, 86)),
        biomechanics: BiomechanicsSetSummary? = null,
        isBodyweight: Boolean = false,
    ): WeightAdjustmentInput = WeightAdjustmentInput(
        exerciseId = "bench-press-001",
        exerciseName = "Bench Press",
        targetExerciseId = "bench-press-001",
        targetSetIndex = 1,
        targetReps = targetReps,
        actualReps = actualReps,
        currentWeightKgPerCable = currentWeight,
        weightIncrementKg = increment,
        qualitySummary = quality,
        biomechanicsSummary = biomechanics,
        isBodyweight = isBodyweight,
        hasNextSetTarget = true,
    )

    private fun qualitySummary(
        scores: List<Int>,
        averageOverride: Int? = null,
    ): SetQualitySummary {
        val repScores = scores.mapIndexed { index, score ->
            RepQualityScore(
                composite = score,
                romScore = 25f,
                velocityScore = 20f,
                eccentricControlScore = 20f,
                smoothnessScore = 15f,
                repNumber = index + 1,
            )
        }
        return SetQualitySummary(
            averageScore = averageOverride ?: scores.average().toInt(),
            bestScore = scores.maxOrNull() ?: 0,
            worstScore = scores.minOrNull() ?: 0,
            bestRepNumber = 1,
            worstRepNumber = scores.size,
            trend = QualityTrend.STABLE,
            repScores = repScores,
        )
    }

    private fun biomechanicsSummary(
        shouldStopSet: Boolean,
        totalVelocityLossPercent: Float?,
    ): BiomechanicsSetSummary {
        val repResult = BiomechanicsRepResult(
            velocity = VelocityResult(
                meanConcentricVelocityMmS = 450f,
                peakVelocityMmS = 600f,
                zone = BiomechanicsVelocityZone.SLOW,
                velocityLossPercent = totalVelocityLossPercent,
                estimatedRepsRemaining = if (shouldStopSet) 0 else 3,
                shouldStopSet = shouldStopSet,
                repNumber = 1,
            ),
            forceCurve = ForceCurveResult(
                normalizedForceN = floatArrayOf(),
                normalizedPositionPct = floatArrayOf(),
                stickingPointPct = null,
                strengthProfile = StrengthProfile.FLAT,
                repNumber = 1,
            ),
            asymmetry = AsymmetryResult(
                asymmetryPercent = 0f,
                dominantSide = "BALANCED",
                avgLoadA = 20f,
                avgLoadB = 20f,
                repNumber = 1,
            ),
            repNumber = 1,
            timestamp = 0L,
        )
        return BiomechanicsSetSummary(
            repResults = listOf(repResult),
            avgMcvMmS = 450f,
            peakVelocityMmS = 600f,
            totalVelocityLossPercent = totalVelocityLossPercent,
            zoneDistribution = mapOf(BiomechanicsVelocityZone.SLOW to 1),
            avgAsymmetryPercent = 0f,
            dominantSide = "BALANCED",
            strengthProfile = StrengthProfile.FLAT,
            avgForceCurve = null,
        )
    }
}

package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.QualityTrend
import com.devil.phoenixproject.domain.model.RepMetricData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for RepQualityScorer covering all four scoring components,
 * trend detection, set summary, and reset behavior.
 */
class RepQualityScorerTest {

    private fun createRepData(
        repNumber: Int = 1,
        rangeOfMotionMm: Float = 500f,
        avgVelocityConcentric: Float = 200f,
        eccentricDurationMs: Long = 2000L,
        concentricDurationMs: Long = 1000L,
        concentricVelocities: FloatArray = floatArrayOf(190f, 200f, 210f, 200f, 195f),
    ): RepMetricData = RepMetricData(
        repNumber = repNumber,
        isWarmup = false,
        startTimestamp = 0L,
        endTimestamp = 3000L,
        durationMs = 3000L,
        concentricDurationMs = concentricDurationMs,
        concentricPositions = floatArrayOf(),
        concentricLoadsA = floatArrayOf(),
        concentricLoadsB = floatArrayOf(),
        concentricVelocities = concentricVelocities,
        concentricTimestamps = longArrayOf(),
        eccentricDurationMs = eccentricDurationMs,
        eccentricPositions = floatArrayOf(),
        eccentricLoadsA = floatArrayOf(),
        eccentricLoadsB = floatArrayOf(),
        eccentricVelocities = floatArrayOf(),
        eccentricTimestamps = longArrayOf(),
        peakForceA = 100f,
        peakForceB = 100f,
        avgForceConcentricA = 80f,
        avgForceConcentricB = 80f,
        avgForceEccentricA = 70f,
        avgForceEccentricB = 70f,
        peakVelocity = 250f,
        avgVelocityConcentric = avgVelocityConcentric,
        avgVelocityEccentric = 150f,
        rangeOfMotionMm = rangeOfMotionMm,
        peakPowerWatts = 500f,
        avgPowerWatts = 350f
    )

    // ========== First Rep Behavior ==========

    @Test
    fun `first rep returns perfect ROM and velocity scores with computed eccentric and smoothness`() {
        val scorer = RepQualityScorer()
        val rep = createRepData(repNumber = 1)
        val score = scorer.scoreRep(rep)

        assertEquals(30f, score.romScore, "First rep ROM should be perfect 30")
        assertEquals(25f, score.velocityScore, "First rep velocity should be perfect 25")
        assertTrue(score.eccentricControlScore >= 0f && score.eccentricControlScore <= 25f)
        assertTrue(score.smoothnessScore >= 0f && score.smoothnessScore <= 20f)
        assertEquals(1, score.repNumber)
    }

    // ========== Consistent Reps ==========

    @Test
    fun `consistent reps maintain high composite scores above 90`() {
        val scorer = RepQualityScorer()
        // Score 5 identical reps -- should all score very high
        repeat(5) { i ->
            val rep = createRepData(repNumber = i + 1)
            val score = scorer.scoreRep(rep)
            assertTrue(
                score.composite > 90,
                "Rep ${i + 1} composite ${score.composite} should be >90 for consistent reps"
            )
        }
    }

    // ========== ROM Scoring ==========

    @Test
    fun `ROM deviation of 20 percent penalizes ROM component`() {
        val scorer = RepQualityScorer()
        // First rep establishes baseline at 500mm
        scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 500f))
        // Second rep at 400mm (20% deviation from 500mm average)
        val score = scorer.scoreRep(createRepData(repNumber = 2, rangeOfMotionMm = 400f))

        assertTrue(
            score.romScore < 20f,
            "ROM score ${score.romScore} should be penalized for 20% deviation (expected ~12)"
        )
        assertTrue(
            score.romScore > 5f,
            "ROM score ${score.romScore} should not be zero for 20% deviation"
        )
    }

    // ========== Velocity Scoring ==========

    @Test
    fun `velocity deviation of 20 percent penalizes velocity component`() {
        val scorer = RepQualityScorer()
        // First rep establishes baseline at 200 mm/s
        scorer.scoreRep(createRepData(repNumber = 1, avgVelocityConcentric = 200f))
        // Second rep at 160 mm/s (20% deviation)
        val score = scorer.scoreRep(createRepData(repNumber = 2, avgVelocityConcentric = 160f))

        assertTrue(
            score.velocityScore < 17f,
            "Velocity score ${score.velocityScore} should be penalized for 20% deviation"
        )
        assertTrue(
            score.velocityScore > 3f,
            "Velocity score ${score.velocityScore} should not be zero for 20% deviation"
        )
    }

    // ========== Eccentric Control Scoring ==========

    @Test
    fun `eccentric ratio at ideal 2 point 0 gives full 25 points`() {
        val scorer = RepQualityScorer()
        val rep = createRepData(
            repNumber = 1,
            eccentricDurationMs = 2000L,
            concentricDurationMs = 1000L
        )
        val score = scorer.scoreRep(rep)

        assertEquals(25f, score.eccentricControlScore, "Ideal 2.0 ratio should give full 25 points")
    }

    @Test
    fun `eccentric ratio at 0 point 5 gives low eccentric score`() {
        val scorer = RepQualityScorer()
        val rep = createRepData(
            repNumber = 1,
            eccentricDurationMs = 500L,
            concentricDurationMs = 1000L
        )
        val score = scorer.scoreRep(rep)

        assertTrue(
            score.eccentricControlScore < 8f,
            "Eccentric score ${score.eccentricControlScore} should be low for 0.5 ratio (expected ~6.25)"
        )
    }

    // ========== Smoothness Scoring ==========

    @Test
    fun `smooth velocity array gives high smoothness score above 18`() {
        val scorer = RepQualityScorer()
        // Very consistent velocities - low variance
        val rep = createRepData(
            repNumber = 1,
            concentricVelocities = floatArrayOf(200f, 201f, 199f, 200f, 200f)
        )
        val score = scorer.scoreRep(rep)

        assertTrue(
            score.smoothnessScore > 18f,
            "Smoothness ${score.smoothnessScore} should be >18 for smooth velocity array"
        )
    }

    @Test
    fun `erratic velocity array gives low smoothness score below 10`() {
        val scorer = RepQualityScorer()
        // Wild variance in velocities
        val rep = createRepData(
            repNumber = 1,
            concentricVelocities = floatArrayOf(50f, 300f, 80f, 350f, 100f)
        )
        val score = scorer.scoreRep(rep)

        assertTrue(
            score.smoothnessScore < 10f,
            "Smoothness ${score.smoothnessScore} should be <10 for erratic velocity array"
        )
    }

    // ========== Trend Detection ==========

    @Test
    fun `trend detects IMPROVING pattern`() {
        val scorer = RepQualityScorer()
        // First half: poor ROM consistency (big deviations)
        scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 500f))
        scorer.scoreRep(createRepData(repNumber = 2, rangeOfMotionMm = 380f)) // bad
        scorer.scoreRep(createRepData(repNumber = 3, rangeOfMotionMm = 360f)) // bad
        // Second half: back to consistent (scores improve)
        scorer.scoreRep(createRepData(repNumber = 4, rangeOfMotionMm = 440f))
        scorer.scoreRep(createRepData(repNumber = 5, rangeOfMotionMm = 440f))
        scorer.scoreRep(createRepData(repNumber = 6, rangeOfMotionMm = 440f))

        assertEquals(QualityTrend.IMPROVING, scorer.getTrend())
    }

    @Test
    fun `trend detects DECLINING pattern`() {
        val scorer = RepQualityScorer()
        // First half: consistent ROM
        scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 500f))
        scorer.scoreRep(createRepData(repNumber = 2, rangeOfMotionMm = 500f))
        scorer.scoreRep(createRepData(repNumber = 3, rangeOfMotionMm = 500f))
        // Second half: ROM falls off significantly
        scorer.scoreRep(createRepData(repNumber = 4, rangeOfMotionMm = 350f))
        scorer.scoreRep(createRepData(repNumber = 5, rangeOfMotionMm = 300f))
        scorer.scoreRep(createRepData(repNumber = 6, rangeOfMotionMm = 250f))

        assertEquals(QualityTrend.DECLINING, scorer.getTrend())
    }

    @Test
    fun `trend returns STABLE for consistent scores`() {
        val scorer = RepQualityScorer()
        // All reps identical - scores should be stable
        repeat(6) { i ->
            scorer.scoreRep(createRepData(repNumber = i + 1))
        }

        assertEquals(QualityTrend.STABLE, scorer.getTrend())
    }

    // ========== Set Summary ==========

    @Test
    fun `set summary computes correct aggregate stats`() {
        val scorer = RepQualityScorer()
        // Score 3 reps with varying quality
        val score1 = scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 500f))
        val score2 = scorer.scoreRep(createRepData(repNumber = 2, rangeOfMotionMm = 500f))
        val score3 = scorer.scoreRep(createRepData(repNumber = 3, rangeOfMotionMm = 350f)) // worse

        val summary = scorer.getSetSummary()

        assertEquals(3, summary.repScores.size)
        assertEquals(score3.composite, summary.worstScore)
        // Best score should be one of the first two (consistent reps)
        assertTrue(summary.bestScore >= summary.worstScore)
        assertTrue(summary.averageScore in summary.worstScore..summary.bestScore)
        assertTrue(summary.bestRepNumber in 1..3)
        assertTrue(summary.worstRepNumber in 1..3)
    }

    // ========== Reset ==========

    @Test
    fun `reset clears all state and scoring after reset behaves like first rep`() {
        val scorer = RepQualityScorer()
        // Score some reps to build state
        scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 500f))
        scorer.scoreRep(createRepData(repNumber = 2, rangeOfMotionMm = 400f))
        scorer.scoreRep(createRepData(repNumber = 3, rangeOfMotionMm = 300f))

        scorer.reset()

        // After reset, first rep should get perfect ROM and velocity again
        val freshScore = scorer.scoreRep(createRepData(repNumber = 1, rangeOfMotionMm = 600f))
        assertEquals(30f, freshScore.romScore, "After reset, first rep ROM should be perfect 30")
        assertEquals(25f, freshScore.velocityScore, "After reset, first rep velocity should be perfect 25")
    }
}

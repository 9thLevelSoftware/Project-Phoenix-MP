package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.premium.BiomechanicsEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary tests for the 2% BALANCED asymmetry threshold shared between mobile and portal.
 *
 * Asymmetry percent formula (ASYM-01, BiomechanicsEngine.computeAsymmetry):
 *    asymmetryPercent = abs(avgLoadA - avgLoadB) / max(avgLoadA, avgLoadB) * 100,  clamped to [0, 100]
 *
 * Dominant-side classification (ASYM-02):
 *    asymmetry <  2%  → BALANCED
 *    asymmetry >= 2%  → "A" if A > B, else "B"
 *    exact equality / both-zero → BALANCED
 *
 * These are the wire-level values that get stored on PortalRepSummaryDto.asymmetryPct and
 * read back by both the portal UI and mobile history views, so portal and mobile must
 * agree on how to categorise each boundary.
 */
class AsymmetryThresholdTest {

    private val engine = BiomechanicsEngine()

    /**
     * Build a list of workout metrics where every sample has exactly (loadA, loadB).
     * That way the avg of the list is just (loadA, loadB) itself — no sampling noise.
     */
    private fun metricsWithLoads(loadA: Float, loadB: Float, count: Int = 5): List<WorkoutMetric> {
        return List(count) {
            WorkoutMetric(
                timestamp = 1000L + it,
                loadA = loadA,
                loadB = loadB,
                positionA = 0f,
                positionB = 0f,
            )
        }
    }

    // ==================== Exact Boundary Values ====================

    @Test
    fun zeroPercentDifferenceIsBalanced() {
        val result = engine.computeAsymmetry(1, metricsWithLoads(50f, 50f))
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(0f, result.asymmetryPercent, "Identical loads → 0% asymmetry")
    }

    @Test
    fun zeroPointZeroOnePercentIsBalanced() {
        // Tiny measurement noise: A=1000, B=999.9 → 0.01% asymmetry → BALANCED
        val result = engine.computeAsymmetry(1, metricsWithLoads(1000f, 999.9f))
        assertEquals("BALANCED", result.dominantSide, "0.01% asymmetry is BALANCED (well below 2% threshold)")
    }

    @Test
    fun oneNinetyNinePercentIsBalanced() {
        // 1.99% asymmetry: just below the 2% threshold → BALANCED.
        // B/A ratio such that |A-B|/max(A,B) = 0.0199:
        //   A = 1000, B = A*(1-0.0199) = 980.1 → asymmetry = 1.99%
        val result = engine.computeAsymmetry(1, metricsWithLoads(1000f, 980.1f))
        assertEquals(
            "BALANCED",
            result.dominantSide,
            "1.99% < 2% threshold → BALANCED",
        )
    }

    @Test
    fun exactlyTwoPercentIsNotBalanced() {
        // ASYM-02 uses `asymmetry < 2f` → 2.0% is OUTSIDE BALANCED
        // A = 1000, B = 980 → asymmetry = 2.0%
        val result = engine.computeAsymmetry(1, metricsWithLoads(1000f, 980f))
        // Due to IEEE-754 arithmetic, 1000 - 980 = 20, 20/1000 * 100 = 2.0 exactly.
        assertEquals(
            "A",
            result.dominantSide,
            "Exactly 2% asymmetry with A>B classifies as 'A' (threshold is strict <2%)",
        )
    }

    @Test
    fun twoPointZeroOnePercentIsNotBalanced() {
        // Just above threshold
        val result = engine.computeAsymmetry(1, metricsWithLoads(1000f, 979.9f))
        assertEquals("A", result.dominantSide, "2.01% A > B → 'A'")
    }

    // ==================== Direction (A > B vs B > A) ====================

    @Test
    fun aDominantWhenAIsHigher() {
        val result = engine.computeAsymmetry(1, metricsWithLoads(100f, 90f)) // 10%
        assertEquals("A", result.dominantSide, "A=100, B=90 → dominant side = A")
    }

    @Test
    fun bDominantWhenBIsHigher() {
        val result = engine.computeAsymmetry(1, metricsWithLoads(90f, 100f)) // 10%
        assertEquals("B", result.dominantSide, "A=90, B=100 → dominant side = B")
    }

    @Test
    fun fivePercentAsymmetryAWins() {
        // A = 100, B = 95 → asymmetry = 5.0%
        val result = engine.computeAsymmetry(1, metricsWithLoads(100f, 95f))
        assertEquals("A", result.dominantSide)
    }

    @Test
    fun fivePercentAsymmetryBWins() {
        val result = engine.computeAsymmetry(1, metricsWithLoads(95f, 100f))
        assertEquals("B", result.dominantSide)
    }

    // ==================== Edge Cases ====================

    @Test
    fun bothZeroLoadsAreBalanced() {
        val result = engine.computeAsymmetry(1, metricsWithLoads(0f, 0f))
        assertEquals(
            "BALANCED",
            result.dominantSide,
            "Both cables at 0 load → BALANCED (no division by zero)",
        )
        assertEquals(0f, result.asymmetryPercent)
    }

    @Test
    fun emptyMetricsAreBalanced() {
        val result = engine.computeAsymmetry(1, emptyList())
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(0f, result.asymmetryPercent)
        assertEquals(0f, result.avgLoadA)
        assertEquals(0f, result.avgLoadB)
    }

    @Test
    fun asymmetryIsClampedToHundredPercent() {
        // A=100, B=0 → (100-0)/100 * 100 = 100%, classified as A-dominant
        val result = engine.computeAsymmetry(1, metricsWithLoads(100f, 0f))
        assertEquals(100f, result.asymmetryPercent, "A=100, B=0 → 100% asymmetry")
        assertEquals("A", result.dominantSide)
    }

    @Test
    fun asymmetryHandlesNegativeDiffViaAbs() {
        // Both orderings of the same magnitude should produce the same asymmetry value.
        val ab = engine.computeAsymmetry(1, metricsWithLoads(100f, 70f))
        val ba = engine.computeAsymmetry(1, metricsWithLoads(70f, 100f))
        assertEquals(
            ab.asymmetryPercent,
            ba.asymmetryPercent,
            "abs() means asymmetry is direction-independent in magnitude",
        )
        assertEquals("A", ab.dominantSide)
        assertEquals("B", ba.dominantSide)
    }
}

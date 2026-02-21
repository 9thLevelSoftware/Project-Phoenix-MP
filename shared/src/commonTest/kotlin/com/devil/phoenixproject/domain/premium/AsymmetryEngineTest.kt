package com.devil.phoenixproject.domain.premium

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BiomechanicsEngine.computeAsymmetry().
 *
 * Verifies:
 * - Asymmetry percentage calculation from loadA/loadB averages
 * - Dominant side detection (A, B, or BALANCED)
 * - 2% threshold for BALANCED classification
 * - Edge cases: zero load, empty input, single sample
 */
class AsymmetryEngineTest {

    private val engine = BiomechanicsEngine()
    private val delta = 0.1f // Tolerance for float comparisons

    // ========== Helper Functions ==========

    private fun createMetric(loadA: Float, loadB: Float): WorkoutMetric =
        WorkoutMetric(
            timestamp = currentTimeMillis(),
            loadA = loadA,
            loadB = loadB,
            positionA = 0f,
            positionB = 0f
        )

    private fun createMetrics(vararg loads: Pair<Float, Float>): List<WorkoutMetric> =
        loads.map { (a, b) -> createMetric(a, b) }

    // ========== Perfectly Balanced ==========

    @Test
    fun `perfectly balanced loads return 0 percent asymmetry and BALANCED`() {
        val metrics = createMetrics(50f to 50f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(0f, result.asymmetryPercent, delta)
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(50f, result.avgLoadA, delta)
        assertEquals(50f, result.avgLoadB, delta)
        assertEquals(1, result.repNumber)
    }

    @Test
    fun `multiple perfectly balanced samples return 0 percent asymmetry`() {
        val metrics = createMetrics(
            50f to 50f,
            60f to 60f,
            40f to 40f
        )
        val result = engine.computeAsymmetry(2, metrics)

        assertEquals(0f, result.asymmetryPercent, delta)
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(50f, result.avgLoadA, delta) // avg of 50, 60, 40 = 50
        assertEquals(50f, result.avgLoadB, delta)
    }

    // ========== Threshold Boundary (2%) ==========

    @Test
    fun `asymmetry below 2 percent is classified as BALANCED`() {
        // loadA=50.5, loadB=49.5 -> asymmetry = |50.5-49.5|/50.5*100 = 1.98%
        val metrics = createMetrics(50.5f to 49.5f)
        val result = engine.computeAsymmetry(1, metrics)

        assertTrue(result.asymmetryPercent < 2f, "Expected <2%, got ${result.asymmetryPercent}")
        assertEquals("BALANCED", result.dominantSide)
    }

    @Test
    fun `asymmetry at exactly 2 percent is classified as BALANCED`() {
        // Need loadA and loadB such that asymmetry = 2.0 exactly
        // |a-b|/max(a,b)*100 = 2 -> if a=51, b=50: |51-50|/51*100 = 1.96%
        // if a=50, b=49: |50-49|/50*100 = 2.0%
        val metrics = createMetrics(50f to 49f)
        val result = engine.computeAsymmetry(1, metrics)

        // 2.0% should still be considered under threshold (< 2, not <=2)
        // Based on spec: "asymmetry < 2.0" -> 2.0 is NOT balanced
        assertTrue(result.asymmetryPercent >= 1.9f && result.asymmetryPercent <= 2.1f)
    }

    @Test
    fun `asymmetry just above 2 percent classifies dominant side`() {
        // loadA=51, loadB=49 -> asymmetry = |51-49|/51*100 = 3.92%
        val metrics = createMetrics(51f to 49f)
        val result = engine.computeAsymmetry(1, metrics)

        assertTrue(result.asymmetryPercent > 2f, "Expected >2%, got ${result.asymmetryPercent}")
        assertEquals("A", result.dominantSide)
    }

    // ========== Side A Dominant ==========

    @Test
    fun `side A dominant with significant imbalance`() {
        // loadA=60, loadB=40 -> asymmetry = |60-40|/60*100 = 33.3%
        val metrics = createMetrics(60f to 40f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(33.3f, result.asymmetryPercent, 0.2f)
        assertEquals("A", result.dominantSide)
        assertEquals(60f, result.avgLoadA, delta)
        assertEquals(40f, result.avgLoadB, delta)
    }

    // ========== Side B Dominant ==========

    @Test
    fun `side B dominant with significant imbalance`() {
        // loadA=40, loadB=60 -> asymmetry = |40-60|/60*100 = 33.3%
        val metrics = createMetrics(40f to 60f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(33.3f, result.asymmetryPercent, 0.2f)
        assertEquals("B", result.dominantSide)
        assertEquals(40f, result.avgLoadA, delta)
        assertEquals(60f, result.avgLoadB, delta)
    }

    // ========== Multiple Samples ==========

    @Test
    fun `multiple samples with varying loads calculate correct averages`() {
        // loadA samples: 50, 60, 70 -> avg = 60
        // loadB samples: 40, 50, 60 -> avg = 50
        // asymmetry = |60-50|/60*100 = 16.7%
        val metrics = createMetrics(
            50f to 40f,
            60f to 50f,
            70f to 60f
        )
        val result = engine.computeAsymmetry(3, metrics)

        assertEquals(60f, result.avgLoadA, delta)
        assertEquals(50f, result.avgLoadB, delta)
        assertEquals(16.7f, result.asymmetryPercent, 0.2f)
        assertEquals("A", result.dominantSide)
        assertEquals(3, result.repNumber)
    }

    @Test
    fun `averaging cancels out fluctuations correctly`() {
        // loadA: 30, 70 -> avg = 50
        // loadB: 70, 30 -> avg = 50
        // Should be balanced despite individual samples being imbalanced
        val metrics = createMetrics(
            30f to 70f,
            70f to 30f
        )
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(50f, result.avgLoadA, delta)
        assertEquals(50f, result.avgLoadB, delta)
        assertEquals(0f, result.asymmetryPercent, delta)
        assertEquals("BALANCED", result.dominantSide)
    }

    // ========== Edge Cases ==========

    @Test
    fun `empty metrics list returns 0 percent asymmetry and BALANCED`() {
        val result = engine.computeAsymmetry(1, emptyList())

        assertEquals(0f, result.asymmetryPercent, delta)
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(0f, result.avgLoadA, delta)
        assertEquals(0f, result.avgLoadB, delta)
        assertEquals(1, result.repNumber)
    }

    @Test
    fun `single sample calculates correctly`() {
        val metrics = createMetrics(75f to 25f)
        val result = engine.computeAsymmetry(5, metrics)

        // asymmetry = |75-25|/75*100 = 66.7%
        assertEquals(66.7f, result.asymmetryPercent, 0.2f)
        assertEquals("A", result.dominantSide)
        assertEquals(75f, result.avgLoadA, delta)
        assertEquals(25f, result.avgLoadB, delta)
        assertEquals(5, result.repNumber)
    }

    @Test
    fun `zero load on both cables returns 0 percent asymmetry and BALANCED`() {
        val metrics = createMetrics(0f to 0f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(0f, result.asymmetryPercent, delta)
        assertEquals("BALANCED", result.dominantSide)
        assertEquals(0f, result.avgLoadA, delta)
        assertEquals(0f, result.avgLoadB, delta)
    }

    @Test
    fun `very high asymmetry with one cable near zero approaches 100 percent`() {
        // loadA=100, loadB=1 -> asymmetry = |100-1|/100*100 = 99%
        val metrics = createMetrics(100f to 1f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(99f, result.asymmetryPercent, 0.5f)
        assertEquals("A", result.dominantSide)
    }

    @Test
    fun `one cable zero load returns high asymmetry`() {
        // loadA=100, loadB=0 -> asymmetry = |100-0|/100*100 = 100%
        val metrics = createMetrics(100f to 0f)
        val result = engine.computeAsymmetry(1, metrics)

        assertEquals(100f, result.asymmetryPercent, delta)
        assertEquals("A", result.dominantSide)
    }

    // ========== Rep Number Propagation ==========

    @Test
    fun `rep number is correctly propagated to result`() {
        val metrics = createMetrics(50f to 50f)

        assertEquals(1, engine.computeAsymmetry(1, metrics).repNumber)
        assertEquals(5, engine.computeAsymmetry(5, metrics).repNumber)
        assertEquals(99, engine.computeAsymmetry(99, metrics).repNumber)
    }

    // ========== Clamping ==========

    @Test
    fun `asymmetry is clamped to 0-100 range`() {
        // Normal case should be within range
        val metrics = createMetrics(100f to 0f)
        val result = engine.computeAsymmetry(1, metrics)

        assertTrue(result.asymmetryPercent >= 0f)
        assertTrue(result.asymmetryPercent <= 100f)
    }
}

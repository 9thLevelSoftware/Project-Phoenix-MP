package com.devil.phoenixproject.domain.assessment

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AssessmentEngine: load-velocity regression, 1RM estimation,
 * velocity threshold detection, and weight suggestion logic.
 *
 * Test cases verify:
 * - Valid 2-point linear regression produces accurate 1RM estimates (+/- 2kg)
 * - Valid 3+ point regression accuracy
 * - Insufficient data returns null
 * - Invalid slope (positive) returns null
 * - Velocity threshold detection (shouldStopAssessment)
 * - Progressive weight suggestions (suggestNextWeight)
 * - R-squared quality metric
 * - Edge cases: identical velocities, single point, max weight clamping
 */
class AssessmentEngineTest {

    private val engine = AssessmentEngine()
    private val tolerance = 2f // +/- 2kg tolerance for 1RM estimates

    // =========================================================================
    // estimateOneRepMax - valid regression cases
    // =========================================================================

    @Test
    fun `estimateOneRepMax with 2 points produces accurate 1RM`() {
        // Hand-calculated: slope = (0.6 - 1.2) / (80 - 40) = -0.015
        // intercept = 1.2 - (-0.015 * 40) = 1.8
        // At velocity 0.17: load = (0.17 - 1.8) / -0.015 = 108.67
        val points = listOf(
            LoadVelocityPoint(40f, 1.2f),
            LoadVelocityPoint(80f, 0.6f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNotNull(result, "Should produce result from 2 valid points")
        assertEquals(108.67f, result.estimatedOneRepMaxKg, tolerance,
            "1RM should be approximately 108.67kg")
        assertEquals(2, result.loadVelocityPoints.size)
        assertEquals(0.17f, result.velocityAt1RM)
    }

    @Test
    fun `estimateOneRepMax with 3 points produces accurate 1RM`() {
        // 3-point regression: (30, 1.3), (60, 0.8), (90, 0.3)
        // OLS: slope = -0.01667, intercept = 1.8
        // At 0.17: load = (0.17 - 1.8) / -0.01667 = 97.78
        val points = listOf(
            LoadVelocityPoint(30f, 1.3f),
            LoadVelocityPoint(60f, 0.8f),
            LoadVelocityPoint(90f, 0.3f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNotNull(result, "Should produce result from 3 valid points")
        assertTrue(result.estimatedOneRepMaxKg in 96f..100f,
            "1RM should be in 96-100kg range, got ${result.estimatedOneRepMaxKg}")
        assertEquals(3, result.loadVelocityPoints.size)
    }

    @Test
    fun `estimateOneRepMax computes R-squared for perfect linear data`() {
        // Perfectly linear data: R^2 should be 1.0
        val points = listOf(
            LoadVelocityPoint(40f, 1.2f),
            LoadVelocityPoint(80f, 0.6f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNotNull(result)
        assertEquals(1.0f, result.r2, 0.01f,
            "R-squared should be 1.0 for perfectly linear data")
    }

    @Test
    fun `estimateOneRepMax with 4 points and noise produces reasonable R-squared`() {
        // Slightly noisy data
        val points = listOf(
            LoadVelocityPoint(30f, 1.25f),
            LoadVelocityPoint(50f, 0.95f),
            LoadVelocityPoint(70f, 0.55f),
            LoadVelocityPoint(90f, 0.30f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNotNull(result)
        assertTrue(result.r2 > 0.9f,
            "R-squared should be high for nearly linear data, got ${result.r2}")
        assertTrue(result.estimatedOneRepMaxKg > 90f && result.estimatedOneRepMaxKg < 120f,
            "1RM should be reasonable, got ${result.estimatedOneRepMaxKg}")
    }

    // =========================================================================
    // estimateOneRepMax - edge cases and invalid data
    // =========================================================================

    @Test
    fun `estimateOneRepMax with 1 point returns null`() {
        val points = listOf(LoadVelocityPoint(40f, 1.2f))

        val result = engine.estimateOneRepMax(points)

        assertNull(result, "Should return null for insufficient data (1 point)")
    }

    @Test
    fun `estimateOneRepMax with 0 points returns null`() {
        val result = engine.estimateOneRepMax(emptyList())

        assertNull(result, "Should return null for empty data")
    }

    @Test
    fun `estimateOneRepMax with positive slope returns null`() {
        // Invalid: velocity increases with load (impossible in practice)
        val points = listOf(
            LoadVelocityPoint(40f, 0.5f),
            LoadVelocityPoint(80f, 0.8f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNull(result, "Should return null when slope is positive (invalid data)")
    }

    @Test
    fun `estimateOneRepMax with identical velocities returns null`() {
        // Zero slope (flat line) - can't extrapolate
        val points = listOf(
            LoadVelocityPoint(40f, 0.8f),
            LoadVelocityPoint(80f, 0.8f)
        )

        val result = engine.estimateOneRepMax(points)

        assertNull(result, "Should return null when velocities are identical (zero slope)")
    }

    @Test
    fun `estimateOneRepMax respects custom minSets config`() {
        val config = AssessmentConfig(minSets = 3)
        val points = listOf(
            LoadVelocityPoint(40f, 1.2f),
            LoadVelocityPoint(80f, 0.6f)
        )

        val result = engine.estimateOneRepMax(points, config)

        assertNull(result, "Should return null when points < custom minSets")
    }

    @Test
    fun `estimateOneRepMax uses custom oneRmVelocity`() {
        val config = AssessmentConfig(oneRmVelocityMs = 0.2f)
        val points = listOf(
            LoadVelocityPoint(40f, 1.2f),
            LoadVelocityPoint(80f, 0.6f)
        )

        val result = engine.estimateOneRepMax(points, config)

        assertNotNull(result)
        // At 0.2 m/s: load = (0.2 - 1.8) / -0.015 = 106.67
        assertEquals(106.67f, result.estimatedOneRepMaxKg, tolerance)
        assertEquals(0.2f, result.velocityAt1RM)
    }

    // =========================================================================
    // shouldStopAssessment
    // =========================================================================

    @Test
    fun `shouldStopAssessment returns true below threshold`() {
        assertTrue(engine.shouldStopAssessment(0.25f),
            "Should stop when velocity (0.25) is below default threshold (0.3)")
    }

    @Test
    fun `shouldStopAssessment returns true at threshold`() {
        assertTrue(engine.shouldStopAssessment(0.3f),
            "Should stop when velocity equals threshold")
    }

    @Test
    fun `shouldStopAssessment returns false above threshold`() {
        assertFalse(engine.shouldStopAssessment(0.35f),
            "Should not stop when velocity (0.35) is above threshold (0.3)")
    }

    @Test
    fun `shouldStopAssessment respects custom threshold`() {
        val config = AssessmentConfig(velocityThresholdMs = 0.5f)

        assertTrue(engine.shouldStopAssessment(0.45f, config),
            "Should stop at 0.45 with custom threshold 0.5")
        assertFalse(engine.shouldStopAssessment(0.55f, config),
            "Should not stop at 0.55 with custom threshold 0.5")
    }

    // =========================================================================
    // suggestNextWeight
    // =========================================================================

    @Test
    fun `suggestNextWeight large jump for high velocity`() {
        // > 0.8 m/s: 2x increment (default 10kg -> +20kg)
        val result = engine.suggestNextWeight(40f, 1.0f)
        assertEquals(60f, result,
            "Should suggest 60kg (40 + 2*10) for high velocity")
    }

    @Test
    fun `suggestNextWeight standard jump for medium velocity`() {
        // > 0.5 m/s: 1x increment (default 10kg)
        val result = engine.suggestNextWeight(60f, 0.6f)
        assertEquals(70f, result,
            "Should suggest 70kg (60 + 10) for medium velocity")
    }

    @Test
    fun `suggestNextWeight small jump for low velocity`() {
        // > 0.3 m/s: 0.5x increment (default 10kg -> +5kg)
        val result = engine.suggestNextWeight(80f, 0.45f)
        assertEquals(85f, result,
            "Should suggest 85kg (80 + 5) for low velocity")
    }

    @Test
    fun `suggestNextWeight no jump at threshold`() {
        // <= 0.3 m/s: no more sets
        val result = engine.suggestNextWeight(90f, 0.25f)
        assertEquals(90f, result,
            "Should return same weight when at/below threshold")
    }

    @Test
    fun `suggestNextWeight clamps to half-kg increments`() {
        // 33 + 5 = 38, already on 0.5kg boundary
        // But test with odd starting weight
        val config = AssessmentConfig(weightIncrementKg = 7f)
        val result = engine.suggestNextWeight(33f, 0.45f, config)
        // 33 + 3.5 = 36.5 - should be on 0.5kg boundary
        assertEquals(36.5f, result,
            "Should clamp to 0.5kg increments")
    }

    @Test
    fun `suggestNextWeight clamps to max 220kg`() {
        val result = engine.suggestNextWeight(215f, 1.0f)
        // 215 + 20 = 235, clamped to 220
        assertEquals(220f, result,
            "Should clamp to maximum 220kg")
    }

    @Test
    fun `suggestNextWeight respects custom increment`() {
        val config = AssessmentConfig(weightIncrementKg = 5f)
        val result = engine.suggestNextWeight(40f, 1.0f, config)
        // > 0.8: 2x increment = 10kg
        assertEquals(50f, result,
            "Should use custom increment (5kg * 2 = 10kg)")
    }
}

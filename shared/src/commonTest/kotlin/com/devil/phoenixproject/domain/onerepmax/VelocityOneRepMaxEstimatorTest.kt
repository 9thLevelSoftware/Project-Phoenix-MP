package com.devil.phoenixproject.domain.onerepmax

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VelocityOneRepMaxEstimatorTest {
    private val estimator = VelocityOneRepMaxEstimator(AssessmentEngine())

    private fun point(load: Float, mcvMmS: Float, ts: Long = 0L) =
        WorkoutVelocityPoint(loadPerCableKg = load, mcvMmS = mcvMmS, timestampMs = ts, workingReps = 5)

    @Test fun `three distinct loads produce a passing estimate`() {
        // load 40 @ 1.2 m/s, 60 @ 0.9 m/s, 80 @ 0.6 m/s → perfectly linear → 1RM ~100kg at 0.30 m/s
        val result = estimator.estimate(
            points = listOf(point(40f, 1200f), point(60f, 900f), point(80f, 600f)),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        assertTrue(result.passedQualityGate, "3 clean points on a line should pass")
        assertTrue(result.estimatedPerCableKg in 95f..105f, "expected ~100kg, got ${result.estimatedPerCableKg}")
    }

    @Test fun `single distinct load returns null`() {
        assertNull(estimator.estimate(listOf(point(60f, 800f), point(60f, 790f)), mvtMs = 0.30f))
    }

    @Test fun `positive slope returns null`() {
        // velocity increasing with load is non-physiological -> AssessmentEngine rejects
        assertNull(estimator.estimate(listOf(point(40f, 600f), point(80f, 900f)), mvtMs = 0.30f))
    }

    @Test fun `duplicate loads are deduped keeping most recent`() {
        // newest 60kg point (ts=100) should win over older (ts=1); 80kg adds the required 3rd distinct load
        val result = estimator.estimate(
            points = listOf(
                point(40f, 1200f, ts = 50),
                point(60f, 900f, ts = 100),
                point(60f, 100f, ts = 1),
                point(80f, 600f, ts = 150),
            ),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        // 3 unique load buckets after dedup (40, 60-winner, 80)
        assertTrue(result.distinctLoads == 3)
    }

    @Test fun `warmup-only or zero-rep points excluded by caller contract are not required here`() {
        // estimator trusts caller to pass working points; verify it still needs 3 distinct loads
        assertNull(estimator.estimate(listOf(point(50f, 700f)), mvtMs = 0.20f))
    }

    // Issue #644: a velocity-1RM estimate that hits AssessmentEngine's 1.0 kg hardware floor
    // means the regression didn't reach the 1RM velocity — diagnostic, not a usable baseline.
    // The estimator must mark it unpassing so the resolver falls through to stored-1RM / PR
    // fallback and the producer (ComputeVelocityOneRepMaxUseCase) never persists it.

    @Test fun `estimate that lands on the 1 kg hardware floor is not passing`() {
        // Points (1kg@0.5, 2kg@0.3, 3kg@0.1 m/s) → OLS slope -0.20, intercept 0.70.
        // At mvtMs=0.50: load = (0.50 - 0.70) / -0.20 = 1.0 kg → hardware floor.
        val result = estimator.estimate(
            points = listOf(
                point(1f, 500f),
                point(2f, 300f),
                point(3f, 100f),
            ),
            mvtMs = 0.50f,
        )
        assertNotNull(result, "estimator should still return a result for visibility")
        assertTrue(result.estimatedPerCableKg <= VelocityOneRepMaxEstimator.MIN_USABLE_ESTIMATE_KG,
            "expected estimate at/below floor, got ${result.estimatedPerCableKg}")
        assertFalse(result.passedQualityGate,
            "a floor-clamped estimate must not be marked passing (Issue #644)")
    }

    @Test fun `isUsableEstimate predicate matches the documented contract`() {
        // The shared predicate is the single source of truth — verify the boundary.
        assertFalse(VelocityOneRepMaxEstimator.isUsableEstimate(0f))
        assertFalse(VelocityOneRepMaxEstimator.isUsableEstimate(1.0f))
        assertTrue(VelocityOneRepMaxEstimator.isUsableEstimate(1.5f))
        assertTrue(VelocityOneRepMaxEstimator.isUsableEstimate(100f))
    }

    @Test fun `MIN_USABLE_ESTIMATE_KG equals 1 kg`() {
        // Document the floor explicitly so a future refactor doesn't drift the contract.
        assertEquals(1.0f, VelocityOneRepMaxEstimator.MIN_USABLE_ESTIMATE_KG)
    }
}

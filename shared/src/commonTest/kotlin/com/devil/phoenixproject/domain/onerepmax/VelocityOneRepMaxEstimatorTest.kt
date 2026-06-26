package com.devil.phoenixproject.domain.onerepmax

import com.devil.phoenixproject.domain.assessment.AssessmentEngine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VelocityOneRepMaxEstimatorTest {
    private val estimator = VelocityOneRepMaxEstimator(AssessmentEngine())

    private fun point(load: Float, mcvMmS: Float, ts: Long = 0L) =
        WorkoutVelocityPoint(loadPerCableKg = load, mcvMmS = mcvMmS, timestampMs = ts, workingReps = 5)

    @Test fun `two distinct loads produce a passing estimate`() {
        // load 40 @ 1.2 m/s (1200 mm/s), load 80 @ 0.6 m/s -> 1RM at 0.30 m/s
        val result = estimator.estimate(
            points = listOf(point(40f, 1200f), point(80f, 600f)),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        assertTrue(result.passedQualityGate, "2 clean points on a line should pass")
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
        // newest 60kg point (ts=100) should win over older (ts=1)
        val result = estimator.estimate(
            points = listOf(point(40f, 1200f, ts = 50), point(60f, 900f, ts = 100), point(60f, 100f, ts = 1)),
            mvtMs = 0.30f,
        )
        assertNotNull(result)
        // distinctLoads counts unique buckets, not raw points
        assertTrue(result.distinctLoads == 2)
    }

    @Test fun `warmup-only or zero-rep points excluded by caller contract are not required here`() {
        // estimator trusts caller to pass working points; verify it still needs 2 loads
        assertNull(estimator.estimate(listOf(point(50f, 700f)), mvtMs = 0.20f))
    }
}

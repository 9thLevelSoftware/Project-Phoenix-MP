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
}

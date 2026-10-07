package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Diagnostic-only merge-gate probes; not production changes. */
class Issue1182MergeGateAuditTest {
    @Test
    fun measuredPeakEqualToConfiguredRemainsMeasured() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
                metrics = listOf(WorkoutMetric(timestamp = 1000L, positionA = 600f, positionB = 600f, velocityA = 200.0, velocityB = 200.0, loadA = 5f, loadB = 5f)),
                repCount = 1, fallbackWeightKg = 5f, configuredWeightKgPerCable = 5f,
                isEchoMode = true, workingRepsCount = 1, cableCountHint = 2,
            )
            assertEquals(5f, summary.heaviestLiftKgPerCable)
            assertEquals(5f, EchoAchievedLoadResolver.fromSummary(summary), "Accepted working load equal to configured metadata is still measured")
        } finally { harness.cleanup() }
    }

    @Test
    fun noSamplesInWorkingWindowIsUnavailable() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
                metrics = listOf(WorkoutMetric(timestamp = 1000L, positionA = 600f, positionB = 600f, velocityA = 200.0, velocityB = 200.0, loadA = 80f, loadB = 80f)),
                repCount = 4, fallbackWeightKg = 5f, configuredWeightKgPerCable = 5f,
                isEchoMode = true, warmupRepsCount = 3, workingRepsCount = 1,
                warmupCompleteTimeMs = 2000L, cableCountHint = 2,
            )
            assertNull(EchoAchievedLoadResolver.fromSummary(summary), "Warmup-only telemetry must not be advertised as a measured working peak")
        } finally { harness.cleanup() }
    }
}

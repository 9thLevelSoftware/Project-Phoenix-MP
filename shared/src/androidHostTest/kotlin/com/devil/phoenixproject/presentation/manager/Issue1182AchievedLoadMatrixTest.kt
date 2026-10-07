package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Issue #1182 acceptance matrix for Echo achieved-load provenance (merge-gate R1/R2/R5):
 * equal peak, short windows, asymmetric/single cable, invalid and zero-load telemetry,
 * warmup-only and empty working windows. Availability is accepted-working-sample
 * provenance, never numerical equality and never the compatibility fallback.
 */
class Issue1182AchievedLoadMatrixTest {

    private fun sample(
        timestamp: Long,
        loadA: Float,
        loadB: Float,
        velocity: Double = 200.0,
    ) = WorkoutMetric(
        timestamp = timestamp,
        positionA = 600f,
        positionB = 600f,
        velocityA = velocity,
        velocityB = velocity,
        loadA = loadA,
        loadB = loadB,
    )

    private fun ActiveSessionEngine.summaryOf(
        metrics: List<WorkoutMetric>,
        configured: Float,
        repCount: Int = 1,
        warmupReps: Int = 0,
        workingReps: Int = repCount,
        warmupCompleteTimeMs: Long = 0L,
        cableCountHint: Int? = 2,
    ): WorkoutState.SetSummary = calculateSetSummaryMetrics(
        metrics = metrics,
        repCount = repCount,
        fallbackWeightKg = configured,
        configuredWeightKgPerCable = configured,
        isEchoMode = true,
        warmupRepsCount = warmupReps,
        workingRepsCount = workingReps,
        warmupCompleteTimeMs = warmupCompleteTimeMs,
        cableCountHint = cableCountHint,
    )

    @Test
    fun `measured peak equal to configured metadata remains measured`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, loadA = 5f, loadB = 5f)),
                configured = 5f,
            )
            assertEquals(5f, summary.measuredWorkingPeakKgPerCable)
            assertEquals(5f, EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `short windows stay measured`() = runTest {
        // <=10-sample windows are measurements (not phase-metric territory).
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, 4.5f, 4.5f), sample(1_100L, 5f, 5f)),
                configured = 20f,
            )
            assertEquals(5f, EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `warmup-only telemetry is never advertised as a working peak`() = runTest {
        // Merge-gate R2: the whole window predates the warmup mark.
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, 80f, 80f)),
                configured = 5f,
                repCount = 4,
                warmupReps = 3,
                workingReps = 1,
                warmupCompleteTimeMs = 2_000L,
            )
            assertNull(summary.measuredWorkingPeakKgPerCable)
            assertNull(EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `empty working window with fallback seed is unavailable`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(metrics = emptyList(), configured = 5f)
            assertNull(summary.measuredWorkingPeakKgPerCable)
            assertNull(EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `dual-cable windows use the cable-aware peak per cable`() = runTest {
        // 30 + 10 total = 40 -> 20 per cable; the heavier side never leaks in as 30.
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, loadA = 30f, loadB = 10f)),
                configured = 5f,
            )
            assertEquals(20f, EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `asymmetric single-cable windows use the active cable without halving`() = runTest {
        // One loaded side and one idle side is single-cable: 40 stays 40 (Issue #6 semantics).
        val harness = DWSMTestHarness(this)
        try {
            val noHintSummary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, loadA = 40f, loadB = 0f)),
                configured = 5f,
                cableCountHint = null,
            )
            assertEquals(40f, EchoAchievedLoadResolver.fromSummary(noHintSummary), "single-cable peak is not halved")

            val oneCableHintSummary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, loadA = 40f, loadB = 0f)),
                configured = 5f,
                cableCountHint = 1,
            )
            assertEquals(40f, EchoAchievedLoadResolver.fromSummary(oneCableHintSummary))

            // A DUAL hint with an incomplete short window keeps the conservative dual
            // fallback (30 + 10 -> 20 per cable); existing cable semantics are preserved.
            val dualHintSummary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, loadA = 30f, loadB = 10f)),
                configured = 5f,
                cableCountHint = 2,
            )
            assertEquals(20f, EchoAchievedLoadResolver.fromSummary(dualHintSummary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalid telemetry is not a measurement`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val nanSummary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, Float.NaN, Float.NaN)),
                configured = 5f,
            )
            assertNull(nanSummary.measuredWorkingPeakKgPerCable, "NaN telemetry must resolve unavailable")
            assertNull(EchoAchievedLoadResolver.fromSummary(nanSummary))

            val zeroSummary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(sample(1_000L, 0f, 0f, velocity = 0.0)),
                configured = 5f,
            )
            assertNull(zeroSummary.measuredWorkingPeakKgPerCable, "zero-load telemetry is never 0 kg lifted")
            assertNull(EchoAchievedLoadResolver.fromSummary(zeroSummary))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `measured working samples after the warmup mark win over warmup transients`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val summary = harness.activeSessionEngine.summaryOf(
                metrics = listOf(
                    sample(1_000L, 80f, 80f), // warmup transient
                    sample(2_500L, 35f, 35f), // accepted working sample
                ),
                configured = 5f,
                repCount = 4,
                warmupReps = 3,
                workingReps = 1,
                warmupCompleteTimeMs = 2_000L,
            )
            assertEquals(35f, EchoAchievedLoadResolver.fromSummary(summary))
        } finally {
            harness.cleanup()
        }
    }
}

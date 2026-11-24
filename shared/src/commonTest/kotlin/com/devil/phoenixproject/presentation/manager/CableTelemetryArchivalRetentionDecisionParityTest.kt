package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1.1 (issue #1425): [CableTelemetryArchivalRetention.planRetention] is a pure extraction of
 * [CableTelemetryArchivalRetention.selectForRomCableSet] — the wrapper rebuilds exactly the kept
 * list the inline implementation produced, for every existing fixture plus generated variants,
 * and every [RetentionKeepReason] is reachable.
 */
class CableTelemetryArchivalRetentionDecisionParityTest {

    @Test
    fun `wrapper rebuild equals the decision for every fixture`() {
        val reasonsSeen = mutableSetOf<RetentionKeepReason>()
        for ((samples, romEnabled) in fixtures()) {
            val decision = CableTelemetryArchivalRetention.planRetention(samples, romEnabled)
            if (decision is RetentionDecision.Keep) reasonsSeen += decision.reason
            assertEquals(
                CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled),
                rebuild(samples, decision),
                "parity mismatch for fixture with ${samples.size} samples romEnabled=$romEnabled",
            )
        }
        assertEquals(
            RetentionKeepReason.values().toSet(),
            reasonsSeen,
            "every keep reason must be reachable by the parity fixtures",
        )
    }

    @Test
    fun `decision is side effect free and fixture determined`() {
        val samples = longIdleTrace()
        val first = CableTelemetryArchivalRetention.planRetention(samples, romEnabled = true)
        val second = CableTelemetryArchivalRetention.planRetention(samples, romEnabled = true)
        assertEquals(first, second)
        assertEquals(28, samples.size, "input list is untouched")
    }

    private fun rebuild(samples: List<WorkoutMetric>, decision: RetentionDecision): List<WorkoutMetric> =
        when (decision) {
            is RetentionDecision.Keep -> samples
            is RetentionDecision.Trim -> {
                val (retainedSafetySamples, _) = samples.subList(0, decision.firstRetainedIndex).partition {
                    (it.status and SampleStatus.SAFETY_STATUS_MASK) != 0
                }
                retainedSafetySamples + samples.subList(decision.firstRetainedIndex, samples.size)
            }
        }

    private fun fixtures(): List<Pair<List<WorkoutMetric>, Boolean>> {
        val fixtures = mutableListOf<Pair<List<WorkoutMetric>, Boolean>>()
        // NOT_ROM
        fixtures += confidentTrace() to false
        fixtures += metrics(List(12) { 100f }, List(12) { 200f }) to false
        // EMPTY
        fixtures += emptyList<WorkoutMetric>() to true
        // TOO_FEW_SAMPLES
        fixtures += metrics(List(3) { 100f }, List(3) { 200f }) to true
        fixtures += metrics(List(5) { 100f }, List(5) { 200f }) to true
        // INVALID_ORDER_OR_POSITION
        val invalid = confidentTrace().toMutableList().also { it[5] = it[5].copy(positionB = Float.NaN) }
        fixtures += invalid to true
        val decreasing = confidentTrace().toMutableList().also { it[5] = it[5].copy(timestamp = it[4].timestamp - 1L) }
        fixtures += decreasing to true
        // TIMESTAMP_OVERFLOW
        fixtures += overflowTrace() to true
        // BASELINE_TOO_SHORT
        fixtures += shortDurationTrace() to true
        // BASELINE_UNSTABLE
        fixtures += unstableBaselineTrace() to true
        // OPENING_DRIFT
        fixtures += driftTrace() to true
        // NO_CONFIRMED_DEPARTURE
        fixtures += jitterTrace() to true
        fixtures += metrics(List(12) { 100f }, List(12) { 200f }) to true
        // NO_CONTEXT_BOUNDARY
        fixtures += immediateMovementTrace() to true
        // DISCARD_OUTSIDE_BASELINE
        fixtures += longIdleTrace(deviatedIndices = setOf(4, 5)) to true
        // Trim fixtures: generated variants of the existing traces
        for (movingCable in 0..1) {
            for (direction in listOf(1f, -1f)) {
                val displacements = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 5.5f, 8f, 11f)
                val a = displacements.map { 100f + if (movingCable == 0) direction * it else 0f }
                val b = displacements.map { 200f + if (movingCable == 1) direction * it else 0f }
                fixtures += metrics(a, b) to true
            }
        }
        fixtures += fiftyHertzIdleTrace() to true
        fixtures += fiftyHertzIdleTrace(SampleStatus.ROM_OUTSIDE_HIGH) to true
        fixtures += irregularTimestampTrace() to true
        fixtures += confirmedShortExcursionTrace() to true
        fixtures += confidentTrace().toMutableList().also { it[5] = it[5].copy(timestamp = it[4].timestamp) } to true
        fixtures += longIdleTrace() to true
        fixtures += longIdleTrace(status = SampleStatus.ROM_OUTSIDE_HIGH, deviatedIndices = setOf(8)) to true
        fixtures += longIdleTrace(SampleStatus.REP_TOP_READY or SampleStatus.REP_BOTTOM_READY) to true
        fixtures += longIdleTrace().toMutableList().also {
            it[5] = it[5].copy(status = SampleStatus.ROM_OUTSIDE_HIGH)
            it[10] = it[10].copy(status = SampleStatus.DELOAD_WARN)
        } to true
        // Generated variants
        for (length in listOf(20, 24, 28, 32, 40)) {
            fixtures += longIdleTrace(length = length) to true
        }
        for (deviation in listOf(setOf(4), setOf(4, 5), setOf(8), setOf(0), setOf(16))) {
            fixtures += longIdleTrace(deviatedIndices = deviation) to true
        }
        assertTrue(fixtures.size >= 30, "generated variants keep the fixture set broad")
        return fixtures
    }

    private fun longIdleTrace(
        status: Int = 0,
        deviatedIndices: Set<Int> = emptySet(),
        length: Int = 28,
    ): List<WorkoutMetric> {
        val movementStart = length - 7
        val positionsA = (0 until length).map { index ->
            when (index) {
                in movementStart..movementStart + 2 -> 106f + (index - movementStart) * 2f
                in deviatedIndices -> 103f
                else -> 100f
            }
        }
        return metrics(positionsA, List(positionsA.size) { 200f }).toMutableList().also {
            if (status != 0) it[5] = it[5].copy(status = status)
        }
    }

    private fun fiftyHertzIdleTrace(statusAtStart: Int = 0): List<WorkoutMetric> {
        val idleSampleCount = 2_000
        val samplesAfterMovement = 51
        return (0 until idleSampleCount + samplesAfterMovement).map { index ->
            val positionA = if (index < idleSampleCount) 100f else 100f + (index - idleSampleCount + 1)
            WorkoutMetric(
                timestamp = index * 20L,
                loadA = 20f,
                loadB = 18f,
                positionA = positionA,
                positionB = 200f,
                status = if (index == 0) statusAtStart else 0,
            )
        }
    }

    private fun irregularTimestampTrace(): List<WorkoutMetric> {
        val timestamps = listOf(0L, 250L, 500L, 750L, 1_000L, 1_250L, 1_400L, 1_600L, 1_900L, 2_200L, 2_450L, 2_700L, 2_950L)
        val positionsA = listOf(100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 106f, 108f, 110f)
        return timestamps.mapIndexed { index, timestamp ->
            WorkoutMetric(
                timestamp = timestamp,
                loadA = 20f,
                loadB = 18f,
                positionA = positionsA[index],
                positionB = 200f,
            )
        }
    }

    private fun confirmedShortExcursionTrace(): List<WorkoutMetric> {
        val displacement = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.4f, 1f, 2f, 3.4f, 5.2f, 5.7f, 5.9f, 6f)
        return metrics(displacement.map { 100f + it }, List(displacement.size) { 250f })
    }

    private fun jitterTrace(): List<WorkoutMetric> {
        val a = listOf(100f, 100.8f, 99.6f, 101f, 99f, 101.4f, 99.3f, 100.7f, 106f, 100.4f, 104.9f, 100.2f)
        return metrics(a, List(a.size) { 200f })
    }

    private fun driftTrace(): List<WorkoutMetric> {
        val a = listOf(100f, 100.3f, 100.7f, 101f, 103f, 105.5f, 108f, 110f)
        return metrics(a, List(a.size) { 200f })
    }

    private fun immediateMovementTrace(): List<WorkoutMetric> {
        val timestamps = listOf(500L, 750L, 1_000L, 1_250L, 1_300L, 1_400L, 1_500L)
        val positions = listOf(100f, 100f, 100f, 100f, 106f, 110f, 114f)
        return timestamps.mapIndexed { index, timestamp ->
            WorkoutMetric(
                timestamp = timestamp,
                loadA = 20f,
                loadB = 18f,
                positionA = positions[index],
                positionB = 200f,
            )
        }
    }

    private fun overflowTrace(): List<WorkoutMetric> =
        (0 until 12).map { index ->
            WorkoutMetric(
                timestamp = Long.MAX_VALUE - 100L + index,
                loadA = 20f,
                loadB = 18f,
                positionA = 100f,
                positionB = 200f,
            )
        }

    private fun shortDurationTrace(): List<WorkoutMetric> =
        (0 until 6).map { index ->
            WorkoutMetric(
                timestamp = index * 80L,
                loadA = 20f,
                loadB = 18f,
                positionA = 100f,
                positionB = 200f,
            )
        }

    private fun unstableBaselineTrace(): List<WorkoutMetric> {
        val a = listOf(100f, 104f, 98f, 102f, 100f, 100f, 100f, 100f, 106f, 110f, 114f, 118f)
        return metrics(a, List(a.size) { 200f })
    }

    private fun confidentTrace(): List<WorkoutMetric> {
        val displacement = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 5.5f, 8f, 11f)
        return metrics(displacement.map { 100f + it }, List(displacement.size) { 200f })
    }

    private fun metrics(positionA: List<Float>, positionB: List<Float>): List<WorkoutMetric> {
        require(positionA.size == positionB.size)
        return positionA.indices.map { index ->
            WorkoutMetric(
                timestamp = index * SAMPLE_INTERVAL_MS,
                loadA = 20f,
                loadB = 18f,
                positionA = positionA[index],
                positionB = positionB[index],
            )
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 250L
    }
}

package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.test.Test
import kotlin.test.assertEquals

class CableTelemetryArchivalRetentionTest {

    @Test
    fun `slow movement from either cable and either direction keeps one second context`() {
        val displacements = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 5.5f, 8f, 11f)
        val expected = (4 until displacements.size).map { it * SAMPLE_INTERVAL_MS }

        for (movingCable in 0..1) {
            for (direction in listOf(1f, -1f)) {
                val a = displacements.map { 100f + if (movingCable == 0) direction * it else 0f }
                val b = displacements.map { 200f + if (movingCable == 1) direction * it else 0f }
                val samples = metrics(a, b)

                val retained = CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true)

                assertEquals(expected, retained.map { it.timestamp })
                assertEquals(samples.drop(4), retained, "retain the first stroke/calibration and every following sample")
            }
        }
    }

    @Test
    fun `fifty hertz idle telemetry is trimmed with one second context`() {
        val idleSampleCount = 2_000 // 40 seconds at 50 Hz; movement begins at 40,000 ms.
        val samplesAfterMovement = 51
        val samples = (0 until idleSampleCount + samplesAfterMovement).map { index ->
            val positionA = if (index < idleSampleCount) 100f else 100f + (index - idleSampleCount + 1)
            WorkoutMetric(
                timestamp = index * 20L,
                loadA = 20f,
                loadB = 18f,
                positionA = positionA,
                positionB = 200f,
            )
        }

        val retained = CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true)

        assertEquals(1_950, samples.size - retained.size, "trim all but one second of idle context")
        assertEquals(samples.drop(1_950), retained, "preserve the full context and every later sample")
    }

    @Test
    fun `irregular timestamps retain the last sample at or before one second cutoff`() {
        val timestamps = listOf(0L, 250L, 500L, 750L, 1_000L, 1_250L, 1_400L, 1_600L, 1_900L, 2_200L, 2_450L, 2_700L, 2_950L)
        val positionsA = listOf(100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 106f, 108f, 110f)
        val samples = timestamps.mapIndexed { index, timestamp ->
            WorkoutMetric(
                timestamp = timestamp,
                loadA = 20f,
                loadB = 18f,
                positionA = positionsA[index],
                positionB = 200f,
            )
        }

        val retained = CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true)

        assertEquals(timestamps.drop(6), retained.map { it.timestamp })
        assertEquals(samples.drop(6), retained, "retain the sample at 1,400 ms and preserve original order")
    }

    @Test
    fun `confirmed short excursion beyond five millimeters is retained`() {
        val displacement = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.4f, 1f, 2f, 3.4f, 5.2f, 5.7f, 5.9f, 6f)
        val samples = metrics(displacement.map { 100f + it }, List(displacement.size) { 250f })

        assertEquals(samples.drop(4), CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `jitter and an unconfirmed spike do not establish movement`() {
        val a = listOf(100f, 100.8f, 99.6f, 101f, 99f, 101.4f, 99.3f, 100.7f, 106f, 100.4f, 104.9f, 100.2f)
        val samples = metrics(a, List(a.size) { 200f })

        assertEquals(samples, CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `startup already drifting makes the onset ambiguous`() {
        val a = listOf(100f, 100.3f, 100.7f, 101f, 103f, 105.5f, 108f, 110f)
        val samples = metrics(a, List(a.size) { 200f })

        assertEquals(samples, CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `immediate movement keeps the full payload when pre-movement context is unavailable`() {
        val timestamps = listOf(500L, 750L, 1_000L, 1_250L, 1_300L, 1_400L, 1_500L)
        val positions = listOf(100f, 100f, 100f, 100f, 106f, 110f, 114f)
        val samples = timestamps.mapIndexed { index, timestamp ->
            WorkoutMetric(
                timestamp = timestamp,
                loadA = 20f,
                loadB = 18f,
                positionA = positions[index],
                positionB = 200f,
            )
        }

        assertEquals(samples, CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `invalid positions and decreasing timestamps keep the complete payload`() {
        val samples = confidentTrace()
        val invalid = samples.toMutableList().also { it[5] = it[5].copy(positionB = Float.NaN) }
        val decreasing = samples.toMutableList().also { it[5] = it[5].copy(timestamp = it[4].timestamp - 1L) }

        assertEquals(invalid, CableTelemetryArchivalRetention.selectForRomCableSet(invalid, romEnabled = true))
        assertEquals(decreasing, CableTelemetryArchivalRetention.selectForRomCableSet(decreasing, romEnabled = true))
    }

    @Test
    fun `repeated timestamps at the context boundary are accepted and retained`() {
        val samples = confidentTrace().toMutableList().also {
            it[5] = it[5].copy(timestamp = it[4].timestamp)
        }

        assertEquals(samples.drop(4), CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `machine safety status beyond the baseline clamps the retained window to the flagged sample`() {
        val samples = longIdleTrace(SampleStatus.ROM_OUTSIDE_HIGH)

        assertEquals(samples.drop(5), CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `rep readiness flags in discarded prefix do not disable trimming`() {
        val samples = longIdleTrace(SampleStatus.REP_TOP_READY or SampleStatus.REP_BOTTOM_READY)

        assertEquals(
            samples.drop(17),
            CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true),
        )
    }

    @Test
    fun `subthreshold displacement in the discarded prefix prevents trimming`() {
        val positionsA = (0 until 28).map { index ->
            when (index) {
                4, 5 -> 103f
                in 21..23 -> 106f + (index - 21) * 2f
                else -> 100f
            }
        }
        val samples = metrics(positionsA, List(positionsA.size) { 200f })

        assertEquals(samples, CableTelemetryArchivalRetention.selectForRomCableSet(samples, romEnabled = true))
    }

    @Test
    fun `disabled ROM semantics and idle-only cancellation keep all samples`() {
        val moving = confidentTrace()
        val idle = metrics(List(12) { 100f }, List(12) { 200f })

        assertEquals(moving, CableTelemetryArchivalRetention.selectForRomCableSet(moving, romEnabled = false))
        assertEquals(idle, CableTelemetryArchivalRetention.selectForRomCableSet(idle, romEnabled = true))
    }

    private fun longIdleTrace(status: Int): List<WorkoutMetric> {
        val positionsA = (0 until 28).map { index ->
            when (index) {
                in 21..23 -> 106f + (index - 21) * 2f
                else -> 100f
            }
        }
        return metrics(positionsA, List(positionsA.size) { 200f }).toMutableList().also {
            it[5] = it[5].copy(status = status)
        }
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

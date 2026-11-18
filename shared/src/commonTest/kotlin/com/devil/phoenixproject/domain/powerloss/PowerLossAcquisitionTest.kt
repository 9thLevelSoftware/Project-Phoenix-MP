package com.devil.phoenixproject.domain.powerloss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P1 acquisition contract tests: MCP V1 math (asymmetric and
 * single-cable units, signed velocity), quality-rejection reasons, ordered
 * accumulation, and the concentric phase normalizer.
 */
class PowerLossAcquisitionTest {

    private fun sample(
        loadA: Float = 20f,
        loadB: Float = 20f,
        vA: Float = 500f,
        vB: Float = 500f,
        ticks: Long = 0L,
        ts: Long = 1_000L,
        quality: SampleQualityMarkers = SampleQualityMarkers(),
    ) = ValidatedPairedSample(
        executionId = "exec-1",
        windowOrdinal = 1,
        loadAKg = loadA,
        loadBKg = loadB,
        velocityAMmS = vA,
        velocityBMmS = vB,
        positionAMm = 0f,
        positionBMm = 0f,
        deviceTicks = ticks,
        monotonicTimestampMs = ts,
        quality = quality,
    )

    // ===== MCP V1 math =====

    @Test
    fun `MCP V1 is the arithmetic sample mean of summed paired-cable power`() {
        // 20 kg × 500 mm/s per cable: P = 9.81/1000 × (20×500 + 20×500) = 196.2 W
        val s = sample()
        val expectedSample = 9.81f / 1000f * (20f * 500f + 20f * 500f)
        assertEquals(expectedSample, s.samplePowerWatts())
        val result = MeanConcentricPower.compute(listOf(s, s.copy(monotonicTimestampMs = 1_010L), s.copy(monotonicTimestampMs = 1_020L)))
        assertEquals(expectedSample, result.mcpWatts)
        assertNull(result.qualityReason)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun `asymmetric loads use per-cable loads with no substitution`() {
        // A: 30 kg × 400 mm/s, B: 10 kg × 400 mm/s → 9.81/1000 × (12000 + 4000) = 156.96 W
        val s = sample(loadA = 30f, loadB = 10f, vA = 400f, vB = 400f)
        assertEquals(9.81f / 1000f * (30f * 400f + 10f * 400f), s.samplePowerWatts())
    }

    @Test
    fun `single-cable set contributes zero from the idle cable`() {
        // A active only: 9.81/1000 × 25×600 = 147.15 W even though B velocity is nonzero.
        val s = sample(loadA = 25f, loadB = 0f, vA = 600f, vB = 900f)
        assertEquals(9.81f / 1000f * (25f * 600f), s.samplePowerWatts())
    }

    @Test
    fun `signed lowering velocity never becomes lifting power`() {
        val s = sample(loadA = 20f, loadB = 20f, vA = -500f, vB = -500f)
        assertEquals(0f, s.samplePowerWatts())
        val mixed = sample(loadA = 20f, loadB = 20f, vA = -500f, vB = 500f)
        assertEquals(9.81f / 1000f * (20f * 500f), mixed.samplePowerWatts())
    }

    // ===== Quality reasons =====

    @Test
    fun `insufficient samples are rejected`() {
        val result = MeanConcentricPower.compute(listOf(sample(), sample(ts = 1_010L)))
        assertNull(result.mcpWatts)
        assertEquals(McpQualityReason.INSUFFICIENT_SAMPLES, result.qualityReason)
    }

    @Test
    fun `gap stale rejected and overflow contamination are enumerable and reject the window`() {
        val gap = MeanConcentricPower.compute(
            listOf(sample(), sample(ts = 1_010L, quality = SampleQualityMarkers(gapBefore = true)), sample(ts = 1_020L)),
        )
        assertEquals(McpQualityReason.SAMPLE_GAP, gap.qualityReason)

        val stale = MeanConcentricPower.compute(
            listOf(sample(), sample(ts = 1_010L, quality = SampleQualityMarkers(stale = true)), sample(ts = 1_020L)),
        )
        assertEquals(McpQualityReason.STALE_DATA, stale.qualityReason)

        val rejected = MeanConcentricPower.compute(
            listOf(sample(), sample(ts = 1_010L, quality = SampleQualityMarkers(rejected = true)), sample(ts = 1_020L)),
        )
        assertEquals(McpQualityReason.REJECTED_SAMPLE, rejected.qualityReason)

        val clamped = MeanConcentricPower.compute(
            listOf(sample(), sample(ts = 1_010L, quality = SampleQualityMarkers(clamped = true)), sample(ts = 1_020L)),
        )
        assertEquals(McpQualityReason.REJECTED_SAMPLE, clamped.qualityReason)

        val overflow = MeanConcentricPower.compute(
            listOf(sample(), sample(ts = 1_010L), sample(ts = 1_020L)),
            overflowed = true,
        )
        assertEquals(McpQualityReason.BUFFER_OVERFLOW, overflow.qualityReason)
    }

    // ===== Ordered accumulator =====

    @Test
    fun `accumulator rejects duplicate and out-of-order timestamps`() {
        val acc = OrderedSampleAccumulator()
        assertTrue(acc.submit(sample(ts = 1_000L)))
        assertFalse(acc.submit(sample(ts = 1_000L))) // duplicate
        assertFalse(acc.submit(sample(ts = 999L))) // out of order
        assertEquals(2, acc.rejectedCount)
        assertEquals(1, acc.drain().size)
    }

    @Test
    fun `accumulator counts gaps at or over 100 ms`() {
        val acc = OrderedSampleAccumulator()
        acc.submit(sample(ts = 1_000L))
        acc.submit(sample(ts = 1_099L)) // 99 ms — not a gap
        assertEquals(0, acc.gapCount)
        acc.submit(sample(ts = 1_199L)) // 100 ms — gap
        assertEquals(1, acc.gapCount)
    }

    @Test
    fun `accumulator overflow is counted and the window is marked unusable`() {
        val acc = OrderedSampleAccumulator(capacity = 2)
        acc.submit(sample(ts = 1_000L))
        acc.submit(sample(ts = 1_010L))
        assertFalse(acc.submit(sample(ts = 1_020L)))
        assertTrue(acc.overflowed)
        assertEquals(1, acc.overflowCount)
    }

    @Test
    fun `drain returns ordered batch exactly once`() {
        val acc = OrderedSampleAccumulator()
        acc.submit(sample(ts = 1_000L))
        acc.submit(sample(ts = 1_010L))
        val first = acc.drain()
        assertEquals(listOf(1_000L, 1_010L), first.map { it.monotonicTimestampMs })
        assertTrue(acc.drain().isEmpty())
    }

    // ===== Concentric phase normalizer =====

    private fun normSample(vA: Float, vB: Float, ts: Long, loadA: Float = 20f, loadB: Float = 20f) =
        sample(loadA = loadA, loadB = loadB, vA = vA, vB = vB, ts = ts)

    @Test
    fun `concentric starts only above the forward threshold on an active cable`() {
        val n = ConcentricPhaseNormalizer()
        assertNull(n.onSample(normSample(vA = 10f, vB = 10f, ts = 1))) // at threshold, not above
        assertEquals(ConcentricPhaseNormalizer.State.IDLE, n.state)
        n.onSample(normSample(vA = 11f, vB = 0f, ts = 2))
        assertEquals(ConcentricPhaseNormalizer.State.ACCUMULATING, n.state)
    }

    @Test
    fun `window seals exactly once on the next fresh sample after two below-threshold samples`() {
        val n = ConcentricPhaseNormalizer()
        n.onSample(normSample(vA = 500f, vB = 500f, ts = 1))
        n.onSample(normSample(vA = 300f, vB = 300f, ts = 2))
        n.onSample(normSample(vA = 5f, vB = 5f, ts = 3)) // below #1
        assertEquals(ConcentricPhaseNormalizer.State.ACCUMULATING, n.state)
        n.onSample(normSample(vA = 5f, vB = 5f, ts = 4)) // below #2 → PENDING_SEAL
        assertEquals(ConcentricPhaseNormalizer.State.PENDING_SEAL, n.state)
        val sealed = n.onSample(normSample(vA = 0f, vB = 0f, ts = 5)) // sealing sample
        assertEquals(4, sealed?.size) // sealed sample itself excluded
        assertEquals(ConcentricPhaseNormalizer.State.IDLE, n.state)
        assertEquals(1, n.sealedWindows.size)
    }

    @Test
    fun `validated top-turnaround seals without waiting for two below-threshold samples`() {
        val n = ConcentricPhaseNormalizer()
        n.onSample(normSample(vA = 500f, vB = 500f, ts = 1))
        n.onSample(normSample(vA = 400f, vB = 400f, ts = 2))
        // Top turnaround: forward velocity crosses to non-forward on an active cable.
        n.onSample(normSample(vA = -200f, vB = 400f, ts = 3))
        assertEquals(ConcentricPhaseNormalizer.State.PENDING_SEAL, n.state)
    }

    @Test
    fun `stale or rejected samples never advance the normalizer`() {
        val n = ConcentricPhaseNormalizer()
        val stale = normSample(vA = 500f, vB = 500f, ts = 1)
            .copy(quality = SampleQualityMarkers(stale = true))
        assertNull(n.onSample(stale))
        assertEquals(ConcentricPhaseNormalizer.State.IDLE, n.state)
    }
}

package com.devil.phoenixproject.domain.powerloss

import kotlin.math.max

/**
 * Issue #1392 — P1 acquisition + identity contract.
 *
 * MCP V1 is computed from **pre-EMA validated paired control samples only** —
 * never from legacy `avgPowerWatts` or midpoint segmentation (SD1). The metric
 * is the arithmetic sample mean of per-sample summed paired-cable power:
 *
 * ```
 * P_sample = 9.81/1000 × (loadA × max(vA, 0) + loadB × max(vB, 0))   [W]
 * ```
 *
 * with loads in kg and raw signed velocities in mm/s. An idle cable contributes
 * 0; there is no cross-cable velocity substitution. Signed lowering velocity
 * never becomes lifting power.
 */

/** Machine cable identity. */
enum class PowerCable { A, B }

/** Enumerable MCP V1 quality-rejection reasons (P1 acceptance). */
enum class McpQualityReason {
    /** Timestamp gap at/over the freshness bound inside the window. */
    SAMPLE_GAP,

    /** Stale sample (freshness violation) contaminated the window. */
    STALE_DATA,

    /** A rejected/clamped sample contaminated the window. */
    REJECTED_SAMPLE,

    /** The ordered accumulator overflowed and dropped samples. */
    BUFFER_OVERFLOW,

    /** Fewer than the minimum usable samples. */
    INSUFFICIENT_SAMPLES,

    /** Concentric phase could not be established for the window. */
    PHASE_UNAVAILABLE,
    ;
}

/** Per-sample quality markers from the validated paired control-sample derivation. */
data class SampleQualityMarkers(
    val clamped: Boolean = false,
    val rejected: Boolean = false,
    val stale: Boolean = false,
    /** Gap of at/over 100 ms before this sample. */
    val gapBefore: Boolean = false,
) {
    val contaminated: Boolean get() = clamped || rejected || stale || gapBefore
}

/**
 * Versioned validated pre-EMA paired control sample (MCP metric version 1).
 * Raw signed velocities: negative = lowering. Never smoothed/EMA-filtered.
 */
data class ValidatedPairedSample(
    val executionId: String,
    val windowOrdinal: Int,
    val loadAKg: Float,
    val loadBKg: Float,
    val velocityAMmS: Float,
    val velocityBMmS: Float,
    val positionAMm: Float,
    val positionBMm: Float,
    val deviceTicks: Long,
    val monotonicTimestampMs: Long,
    val quality: SampleQualityMarkers = SampleQualityMarkers(),
) {
    /** True when cable has load applied (idle cable = 0 kg). */
    fun isCableActive(cable: PowerCable): Boolean = when (cable) {
        PowerCable.A -> loadAKg > 0f
        PowerCable.B -> loadBKg > 0f
    }

    /**
     * MCP V1 sample power in W: `9.81/1000 × (loadA×max(vA,0) + loadB×max(vB,0))`.
     * Idle cable contributes 0; no cross-cable substitution; lowering never counts.
     */
    fun samplePowerWatts(): Float {
        val a = if (isCableActive(PowerCable.A)) loadAKg * max(velocityAMmS, 0f) else 0f
        val b = if (isCableActive(PowerCable.B)) loadBKg * max(velocityBMmS, 0f) else 0f
        return GRAVITY_PER_MMS_TO_W * (a + b)
    }

    companion object {
        /** `9.81 / 1000`: kg·mm/s → W. */
        const val GRAVITY_PER_MMS_TO_W = 9.81f / 1000f

        /** Freshness bound: a gap at/over 100 ms is a sample gap (P1 tests). */
        const val MAX_SAMPLE_GAP_MS = 100L

        /** Minimum usable samples for a valid MCP window (P1 acceptance). */
        const val MIN_SAMPLES = 3
    }
}

/** Result of an MCP V1 computation over one sealed concentric window. */
data class McpComputation(
    /** Arithmetic sample mean of [ValidatedPairedSample.samplePowerWatts]; null when unusable. */
    val mcpWatts: Float?,
    /** Non-null exactly when [mcpWatts] is null. */
    val qualityReason: McpQualityReason?,
    val sampleCount: Int,
) {
    companion object {
        fun unavailable(reason: McpQualityReason, sampleCount: Int = 0): McpComputation =
            McpComputation(mcpWatts = null, qualityReason = reason, sampleCount = sampleCount)
    }
}

/** MCP V1 signal derivation (pure). */
object MeanConcentricPower {
    /** Metric version stamp carried into persistence (P4). */
    const val POWER_METRIC_VERSION = 1

    /**
     * Compute MCP V1 over the sealed concentric window's validated samples.
     *
     * Order of rejection: contaminated windows (gap / stale / rejected / overflow)
     * fail first, then insufficient samples, then the arithmetic mean. A computed
     * mean that is non-positive is reported as-is (callers treat non-positive as
     * an invalid measurement for policy qualification; nothing is fabricated).
     */
    fun compute(
        samples: List<ValidatedPairedSample>,
        overflowed: Boolean = false,
    ): McpComputation {
        if (overflowed) return McpComputation.unavailable(McpQualityReason.BUFFER_OVERFLOW, samples.size)
        if (samples.any { it.quality.gapBefore }) {
            return McpComputation.unavailable(McpQualityReason.SAMPLE_GAP, samples.size)
        }
        if (samples.any { it.quality.stale }) {
            return McpComputation.unavailable(McpQualityReason.STALE_DATA, samples.size)
        }
        if (samples.any { it.quality.rejected || it.quality.clamped }) {
            return McpComputation.unavailable(McpQualityReason.REJECTED_SAMPLE, samples.size)
        }
        if (samples.size < ValidatedPairedSample.MIN_SAMPLES) {
            return McpComputation.unavailable(McpQualityReason.INSUFFICIENT_SAMPLES, samples.size)
        }
        val mean = samples.map { it.samplePowerWatts() }.average().toFloat()
        return McpComputation(mcpWatts = mean, qualityReason = null, sampleCount = samples.size)
    }
}

/**
 * Bounded ordered accumulator for validated paired control samples (P1).
 *
 * Ordering is by monotonic timestamp with device-tick tie-breaks within the
 * execution epoch. Duplicates and out-of-order submissions are rejected (counted,
 * never silently dropped); overflow is counted and the window is marked unusable.
 * Consumption is serialized: [drain] returns and clears the pending ordered batch.
 */
class OrderedSampleAccumulator(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val pending = mutableListOf<ValidatedPairedSample>()

    var rejectedCount: Int = 0
        private set
    var overflowCount: Int = 0
        private set
    var gapCount: Int = 0
        private set

    private var lastTimestampMs: Long = Long.MIN_VALUE

    val overflowed: Boolean get() = overflowCount > 0

    /**
     * Submit one sample. Returns false when the sample was rejected (duplicate or
     * out-of-order timestamp) or overflowed; the counters record which.
     * Callers serialize submissions (single-writer delivery path).
     */
    fun submit(sample: ValidatedPairedSample): Boolean {
        if (lastTimestampMs != Long.MIN_VALUE && sample.monotonicTimestampMs <= lastTimestampMs) {
            rejectedCount++
            return false
        }
        if (lastTimestampMs != Long.MIN_VALUE &&
            sample.monotonicTimestampMs - lastTimestampMs >= ValidatedPairedSample.MAX_SAMPLE_GAP_MS
        ) {
            gapCount++
        }
        if (pending.size >= capacity) {
            overflowCount++
            return false
        }
        pending.add(sample)
        lastTimestampMs = sample.monotonicTimestampMs
        return true
    }

    /** Serialize consumption: returns the ordered pending batch and clears it. */
    fun drain(): List<ValidatedPairedSample> {
        val batch = pending.toList()
        pending.clear()
        return batch
    }

    companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}

/**
 * Finite-state concentric phase normalizer (P1).
 *
 * - Concentric starts when an active cable's forward velocity exceeds
 *   [forwardThresholdMmS] (10 mm/s).
 * - The window ends on a validated top-turnaround or when both active forward
 *   velocities are ≤ [forwardThresholdMmS] for two fresh samples.
 * - The window seals **exactly once** on the next fresh ordered sample; that
 *   sample belongs to the next window (or idle), never to the sealed one.
 *
 * Only sealed windows are eligible for MCP V1; mid-window reads are forbidden.
 */
class ConcentricPhaseNormalizer(
    private val forwardThresholdMmS: Float = FORWARD_THRESHOLD_MM_S,
) {
    enum class State { IDLE, ACCUMULATING, PENDING_SEAL }

    var state: State = State.IDLE
        private set

    private val window = mutableListOf<ValidatedPairedSample>()
    private var belowThresholdStreak = 0

    /** Sealed concentric windows in order. */
    val sealedWindows = mutableListOf<List<ValidatedPairedSample>>()

    /**
     * Feed one fresh ordered sample. Returns the sealed window when this sample
     * completed a seal (the sample itself is not part of the window), else null.
     */
    fun onSample(sample: ValidatedPairedSample): List<ValidatedPairedSample>? {
        if (sample.quality.stale || sample.quality.rejected) return null
        return when (state) {
            State.IDLE -> {
                if (isForwardAboveThreshold(sample)) {
                    window.add(sample)
                    state = State.ACCUMULATING
                }
                null
            }
            State.ACCUMULATING -> {
                window.add(sample)
                val bothBelow = bothActiveAtOrBelowThreshold(sample)
                val turnaround = isTopTurnaround(sample)
                if (turnaround || bothBelow) {
                    belowThresholdStreak = if (bothBelow) belowThresholdStreak + 1 else belowThresholdStreak
                    if (turnaround || belowThresholdStreak >= 2) {
                        state = State.PENDING_SEAL
                    }
                } else {
                    belowThresholdStreak = 0
                }
                null
            }
            State.PENDING_SEAL -> {
                // Seal exactly once on the next fresh ordered sample; that sample
                // is excluded from the sealed window.
                val sealed = window.toList()
                window.clear()
                belowThresholdStreak = 0
                sealedWindows.add(sealed)
                state = State.IDLE
                if (isForwardAboveThreshold(sample)) {
                    window.add(sample)
                    state = State.ACCUMULATING
                }
                sealed
            }
        }
    }

    private fun forwardVelocities(sample: ValidatedPairedSample): List<Float> =
        buildList {
            if (sample.isCableActive(PowerCable.A)) add(sample.velocityAMmS)
            if (sample.isCableActive(PowerCable.B)) add(sample.velocityBMmS)
        }

    private fun isForwardAboveThreshold(sample: ValidatedPairedSample): Boolean =
        forwardVelocities(sample).any { it > forwardThresholdMmS }

    private fun bothActiveAtOrBelowThreshold(sample: ValidatedPairedSample): Boolean {
        val v = forwardVelocities(sample)
        if (v.isEmpty()) return true // idle counts as at/below threshold
        return v.all { it <= forwardThresholdMmS }
    }

    /**
     * Validated top-turnaround: the sample's forward velocity crossed from above
     * threshold to non-forward (≤ 0) on an active cable.
     */
    private fun isTopTurnaround(sample: ValidatedPairedSample): Boolean {
        val previous = window.getOrNull(window.size - 2) ?: return false
        val active = PowerCable.entries.filter { sample.isCableActive(it) }
        return active.any { cable ->
            previousVelocity(previous, cable) > forwardThresholdMmS &&
                currentVelocity(sample, cable) <= 0f
        }
    }

    private fun previousVelocity(sample: ValidatedPairedSample, cable: PowerCable): Float =
        when (cable) {
            PowerCable.A -> sample.velocityAMmS
            PowerCable.B -> sample.velocityBMmS
        }

    private fun currentVelocity(sample: ValidatedPairedSample, cable: PowerCable): Float =
        previousVelocity(sample, cable)

    companion object {
        const val FORWARD_THRESHOLD_MM_S = 10f
    }
}

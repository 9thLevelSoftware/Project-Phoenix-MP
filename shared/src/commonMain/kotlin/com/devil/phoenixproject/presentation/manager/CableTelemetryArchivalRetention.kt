package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.math.abs

/** Selects a frozen archival payload without changing live set data or summary inputs. */
internal object CableTelemetryArchivalRetention {
    /** Provisional minimum excursion from a stationary baseline that qualifies as movement. */
    const val MOVEMENT_EXCURSION_THRESHOLD_MM = 5f

    /** Retain this much pre-onset telemetry as context for calibration and interpretation. */
    const val PRIOR_CONTEXT_MS = 1_000L

    private const val BASELINE_SAMPLE_COUNT = 4
    private const val BASELINE_MIN_DURATION_MS = 500L
    private const val BASELINE_MAX_DEVIATION_MM = 2f
    private const val CONFIRMATION_SAMPLE_COUNT = 3
    private const val DEPARTURE_BACKTRACK_MM = 0.25f
    private const val BACKTRACK_NOISE_TOLERANCE_MM = 1f

    /**
     * Trim only a confidently stationary leading prefix. Unsupported or ambiguous input is
     * returned unchanged; the caller supplies a completion-frozen ROM capability, never live phase.
     */
    fun selectForRomCableSet(
        samples: List<WorkoutMetric>,
        romEnabled: Boolean,
    ): List<WorkoutMetric> {
        if (!romEnabled || samples.isEmpty()) return samples
        if (samples.size < BASELINE_SAMPLE_COUNT + CONFIRMATION_SAMPLE_COUNT - 1) return samples
        if (!hasValidOrderedPositions(samples)) return samples

        val baselineStartMs = samples.first().timestamp
        if (baselineStartMs > Long.MAX_VALUE - BASELINE_MIN_DURATION_MS) return samples
        val durationEndIndex = samples.indexOfFirst {
            it.timestamp >= baselineStartMs + BASELINE_MIN_DURATION_MS
        }
        if (durationEndIndex < 0) return samples
        val baselineEndIndex = maxOf(BASELINE_SAMPLE_COUNT - 1, durationEndIndex)
        val baselineWindow = samples.take(baselineEndIndex + 1)

        val baselineA = median(baselineWindow.map { it.positionA })
        val baselineB = median(baselineWindow.map { it.positionB })
        if (baselineWindow.any { metric ->
                abs(metric.positionA - baselineA) > BASELINE_MAX_DEVIATION_MM ||
                    abs(metric.positionB - baselineB) > BASELINE_MAX_DEVIATION_MM
            }
        ) {
            return samples
        }

        // A monotonic opening may already be in motion, even if it has not yet crossed 5 mm.
        // Treat that as ambiguous rather than synthesizing a stationary baseline from a stroke.
        if (hasMonotonicOpeningDrift(baselineWindow.map { it.positionA }) ||
            hasMonotonicOpeningDrift(baselineWindow.map { it.positionB })
        ) {
            return samples
        }

        val departureIndex = firstConfirmedDeparture(
            samples = samples,
            baselineA = baselineA,
            baselineB = baselineB,
            firstCandidateIndex = baselineWindow.size,
        ) ?: return samples
        val departureTimestamp = samples[departureIndex].timestamp
        if (departureTimestamp < Long.MIN_VALUE + PRIOR_CONTEXT_MS) return samples
        val contextStartTimestamp = departureTimestamp - PRIOR_CONTEXT_MS
        val firstRetainedIndex = samples.indexOfFirst { it.timestamp >= contextStartTimestamp }
        if (firstRetainedIndex <= 0) return samples
        return samples.subList(firstRetainedIndex, samples.size).toList()
    }

    private fun hasValidOrderedPositions(samples: List<WorkoutMetric>): Boolean {
        var previousTimestamp: Long? = null
        for (sample in samples) {
            if (!sample.positionA.isFinite() || !sample.positionB.isFinite()) return false
            val previous = previousTimestamp
            if (previous != null && sample.timestamp <= previous) return false
            previousTimestamp = sample.timestamp
        }
        return true
    }

    private fun firstConfirmedDeparture(
        samples: List<WorkoutMetric>,
        baselineA: Float,
        baselineB: Float,
        firstCandidateIndex: Int,
    ): Int? {
        var earliestDeparture: Int? = null
        for (cable in 0..1) {
            val baseline = if (cable == 0) baselineA else baselineB
            for (candidate in firstCandidateIndex until samples.size - CONFIRMATION_SAMPLE_COUNT + 1) {
                val direction = movementDirection(samples[candidate], baseline, cable) ?: continue
                val confirmed = (candidate until candidate + CONFIRMATION_SAMPLE_COUNT).all { index ->
                    movementDirection(samples[index], baseline, cable) == direction
                }
                if (!confirmed) continue

                var departure = candidate
                while (departure > firstCandidateIndex) {
                    val previous = signedExcursion(samples[departure - 1], baseline, cable, direction)
                        ?: break
                    val current = signedExcursion(samples[departure], baseline, cable, direction)
                        ?: break
                    if (previous < DEPARTURE_BACKTRACK_MM ||
                        previous > current + BACKTRACK_NOISE_TOLERANCE_MM
                    ) {
                        break
                    }
                    departure--
                }
                if (earliestDeparture == null || departure < earliestDeparture) {
                    earliestDeparture = departure
                }
                break
            }
        }
        return earliestDeparture
    }

    private fun movementDirection(sample: WorkoutMetric, baseline: Float, cable: Int): Int? {
        val delta = position(sample, cable) - baseline
        if (!delta.isFinite() || abs(delta) < MOVEMENT_EXCURSION_THRESHOLD_MM) return null
        return if (delta > 0f) 1 else -1
    }

    private fun signedExcursion(sample: WorkoutMetric, baseline: Float, cable: Int, direction: Int): Float? {
        val signed = (position(sample, cable) - baseline) * direction
        return signed.takeIf { it.isFinite() }
    }

    private fun position(sample: WorkoutMetric, cable: Int): Float =
        if (cable == 0) sample.positionA else sample.positionB

    private fun hasMonotonicOpeningDrift(positions: List<Float>): Boolean {
        val increasing = positions.zipWithNext().all { (left, right) -> right >= left } &&
            positions.zipWithNext().any { (left, right) -> right > left }
        val decreasing = positions.zipWithNext().all { (left, right) -> right <= left } &&
            positions.zipWithNext().any { (left, right) -> right < left }
        return increasing || decreasing
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }
}

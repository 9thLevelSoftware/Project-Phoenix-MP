package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.math.abs

/** Why [CableTelemetryArchivalRetention.planRetention] kept a payload unchanged. */
enum class RetentionKeepReason {
    NOT_ROM,
    EMPTY,
    TOO_FEW_SAMPLES,
    INVALID_ORDER_OR_POSITION,
    TIMESTAMP_OVERFLOW,
    BASELINE_TOO_SHORT,
    BASELINE_UNSTABLE,
    OPENING_DRIFT,
    NO_CONFIRMED_DEPARTURE,
    NO_CONTEXT_BOUNDARY,
    DISCARD_OUTSIDE_BASELINE,
}

/**
 * Pure retention outcome for issue #1425 retroactive compaction. [RetentionDecision.Trim]
 * names the split point only; the kept payload is rebuilt by the caller (or the
 * [CableTelemetryArchivalRetention.selectForRomCableSet] wrapper) so the decision stays
 * side-effect-free and fixture-determined.
 */
sealed class RetentionDecision {
    data class Keep(val reason: RetentionKeepReason) : RetentionDecision()

    data class Trim(
        val firstRetainedIndex: Int,
        val boundaryTimestamp: Long,
        val retainedSafetyCount: Int,
        val discardCount: Int,
    ) : RetentionDecision()
}

/** Selects a frozen archival payload without changing live set data or summary inputs. */
internal object CableTelemetryArchivalRetention {
    /** Provisional minimum excursion from a stationary baseline that qualifies as movement. */
    private const val MOVEMENT_EXCURSION_THRESHOLD_MM = 5f

    /** Retain this much pre-onset telemetry as context for calibration and interpretation. */
    private const val PRIOR_CONTEXT_MS = 1_000L

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
        return when (val decision = planRetention(samples, romEnabled)) {
            is RetentionDecision.Keep -> samples
            is RetentionDecision.Trim -> {
                // Preserve safety events individually without widening the retained context window.
                val (retainedSafetySamples, _) = samples.subList(0, decision.firstRetainedIndex).partition {
                    (it.status and SampleStatus.SAFETY_STATUS_MASK) != 0
                }
                retainedSafetySamples + samples.subList(decision.firstRetainedIndex, samples.size)
            }
        }
    }

    /**
     * Pure decision form of [selectForRomCableSet] (issue #1425). Constants and arithmetic are
     * unchanged from the original inline implementation; [selectForRomCableSet] is a thin wrapper
     * that rebuilds the kept list from this decision.
     */
    fun planRetention(
        samples: List<WorkoutMetric>,
        romEnabled: Boolean,
    ): RetentionDecision {
        if (!romEnabled) return RetentionDecision.Keep(RetentionKeepReason.NOT_ROM)
        if (samples.isEmpty()) return RetentionDecision.Keep(RetentionKeepReason.EMPTY)
        if (samples.size < BASELINE_SAMPLE_COUNT + CONFIRMATION_SAMPLE_COUNT - 1) {
            return RetentionDecision.Keep(RetentionKeepReason.TOO_FEW_SAMPLES)
        }
        if (!hasValidOrderedPositions(samples)) {
            return RetentionDecision.Keep(RetentionKeepReason.INVALID_ORDER_OR_POSITION)
        }

        val baselineStartMs = samples.first().timestamp
        if (baselineStartMs > Long.MAX_VALUE - BASELINE_MIN_DURATION_MS) {
            return RetentionDecision.Keep(RetentionKeepReason.TIMESTAMP_OVERFLOW)
        }
        val durationEndIndex = samples.indexOfFirst {
            it.timestamp >= baselineStartMs + BASELINE_MIN_DURATION_MS
        }
        if (durationEndIndex < 0) return RetentionDecision.Keep(RetentionKeepReason.BASELINE_TOO_SHORT)
        val baselineEndIndex = maxOf(BASELINE_SAMPLE_COUNT - 1, durationEndIndex)
        val baselineWindow = samples.take(baselineEndIndex + 1)

        val baselineA = median(baselineWindow.map { it.positionA })
        val baselineB = median(baselineWindow.map { it.positionB })
        if (baselineWindow.any { metric ->
                abs(metric.positionA - baselineA) > BASELINE_MAX_DEVIATION_MM ||
                    abs(metric.positionB - baselineB) > BASELINE_MAX_DEVIATION_MM
            }
        ) {
            return RetentionDecision.Keep(RetentionKeepReason.BASELINE_UNSTABLE)
        }

        // A monotonic opening may already be in motion, even if it has not yet crossed 5 mm.
        // Treat that as ambiguous rather than synthesizing a stationary baseline from a stroke.
        if (hasMonotonicOpeningDrift(baselineWindow.map { it.positionA }) ||
            hasMonotonicOpeningDrift(baselineWindow.map { it.positionB })
        ) {
            return RetentionDecision.Keep(RetentionKeepReason.OPENING_DRIFT)
        }

        val departureIndex = firstConfirmedDeparture(
            samples = samples,
            baselineA = baselineA,
            baselineB = baselineB,
            firstCandidateIndex = baselineWindow.size,
        ) ?: return RetentionDecision.Keep(RetentionKeepReason.NO_CONFIRMED_DEPARTURE)
        val departureTimestamp = samples[departureIndex].timestamp
        if (departureTimestamp < Long.MIN_VALUE + PRIOR_CONTEXT_MS) {
            return RetentionDecision.Keep(RetentionKeepReason.TIMESTAMP_OVERFLOW)
        }
        val contextStartTimestamp = departureTimestamp - PRIOR_CONTEXT_MS
        val lastContextIndex = samples.indexOfLast { it.timestamp <= contextStartTimestamp }
        if (lastContextIndex < 0) return RetentionDecision.Keep(RetentionKeepReason.NO_CONTEXT_BOUNDARY)
        val boundaryTimestamp = samples[lastContextIndex].timestamp
        // Retain every sample sharing the boundary timestamp, not only the last duplicate.
        val firstRetainedIndex = samples.indexOfFirst { it.timestamp >= boundaryTimestamp }

        // Preserve safety events individually without widening the retained context window.
        val (retainedSafetySamples, discardedSamples) = samples.subList(0, firstRetainedIndex).partition {
            (it.status and SampleStatus.SAFETY_STATUS_MASK) != 0
        }
        if (discardedSamples.any { sample ->
                abs(sample.positionA - baselineA) > BASELINE_MAX_DEVIATION_MM ||
                    abs(sample.positionB - baselineB) > BASELINE_MAX_DEVIATION_MM
            }
        ) {
            return RetentionDecision.Keep(RetentionKeepReason.DISCARD_OUTSIDE_BASELINE)
        }
        return RetentionDecision.Trim(
            firstRetainedIndex = firstRetainedIndex,
            boundaryTimestamp = boundaryTimestamp,
            retainedSafetyCount = retainedSafetySamples.size,
            discardCount = discardedSamples.size,
        )
    }

    private fun hasValidOrderedPositions(samples: List<WorkoutMetric>): Boolean =
        samples.all { it.positionA.isFinite() && it.positionB.isFinite() } &&
            samples.zipWithNext().all { (previous, current) -> current.timestamp >= previous.timestamp }

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
                    val current = signedExcursion(samples[departure], baseline, cable, direction)
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

    private fun signedExcursion(sample: WorkoutMetric, baseline: Float, cable: Int, direction: Int): Float =
        (position(sample, cable) - baseline) * direction

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

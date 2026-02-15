package com.devil.phoenixproject.domain.detection

import com.devil.phoenixproject.domain.model.WorkoutMetric
import kotlin.math.abs

/**
 * Extracts exercise signatures from real-time WorkoutMetric streams.
 *
 * The signature captures the biomechanical fingerprint of an exercise:
 * - ROM (range of motion) from position valleys
 * - Rep duration from valley timing
 * - Load symmetry between cables
 * - Velocity profile shape
 * - Cable usage pattern
 */
class SignatureExtractor {

    companion object {
        /** Minimum samples required (~3 reps at 10Hz) */
        private const val MIN_SAMPLES = 30

        /** Minimum position difference to consider as a valley (in mm) */
        private const val VALLEY_THRESHOLD_MM = 10f

        /** Moving average window size for smoothing */
        private const val SMOOTHING_WINDOW = 5

        /** Load threshold to consider a cable as "active" (in kg) */
        private const val CABLE_ACTIVE_THRESHOLD_KG = 1.0f

        /** Symmetry range for dual symmetric classification */
        private const val SYMMETRIC_LOWER = 0.4f
        private const val SYMMETRIC_UPPER = 0.6f
    }

    /**
     * Extract an ExerciseSignature from a list of workout metrics.
     *
     * @param metrics List of WorkoutMetric samples (typically 10Hz)
     * @return ExerciseSignature if sufficient data, null otherwise
     */
    fun extractSignature(metrics: List<WorkoutMetric>): ExerciseSignature? {
        // Minimum data check
        if (metrics.size < MIN_SAMPLES) return null

        // Calculate smoothed positions using moving average
        val smoothedPositions = smoothPositions(metrics)

        // Detect valleys (rep boundaries)
        val valleys = detectValleys(smoothedPositions, metrics)
        if (valleys.size < 2) return null // Need at least 2 valleys for 1 rep

        // Calculate ROM from peak-valley differences (using raw positions for accuracy)
        val romMm = calculateRom(metrics, valleys)

        // Calculate rep duration from valley timing
        val durationMs = calculateDuration(metrics, valleys)

        // Calculate load symmetry
        val symmetryRatio = calculateSymmetry(metrics)

        // Detect cable usage pattern
        val cableConfig = detectCableUsage(metrics, symmetryRatio)

        // Classify velocity profile from first rep's concentric phase
        val velocityProfile = classifyVelocityProfile(metrics, valleys)

        return ExerciseSignature(
            romMm = romMm,
            durationMs = durationMs,
            symmetryRatio = symmetryRatio,
            velocityProfile = velocityProfile,
            cableConfig = cableConfig,
            sampleCount = valleys.size - 1 // Number of complete reps
        )
    }

    /**
     * Apply moving average smoothing to position data.
     */
    private fun smoothPositions(metrics: List<WorkoutMetric>): List<Float> {
        if (metrics.size < SMOOTHING_WINDOW) {
            return metrics.map { it.positionA }
        }

        return metrics.indices.map { i ->
            val start = maxOf(0, i - SMOOTHING_WINDOW / 2)
            val end = minOf(metrics.size - 1, i + SMOOTHING_WINDOW / 2)
            (start..end).map { metrics[it].positionA }.average().toFloat()
        }
    }

    /**
     * Detect valleys (local minima) in smoothed position data.
     * A valley is a local minimum where the position is lower than nearby samples.
     *
     * Uses a sliding window approach to find local minima.
     */
    private fun detectValleys(
        smoothedPositions: List<Float>,
        metrics: List<WorkoutMetric>
    ): List<Int> {
        if (smoothedPositions.size < 5) return emptyList()

        val valleys = mutableListOf<Int>()
        val windowSize = 5 // Look +/- 2 samples for local minimum

        // Find all local minima
        for (i in 2 until smoothedPositions.size - 2) {
            val current = smoothedPositions[i]
            val window = (-2..2).map { smoothedPositions[i + it] }

            // Current is a local minimum if it's the smallest in the window
            if (current == window.minOrNull() && current < window.maxOrNull()!! - VALLEY_THRESHOLD_MM) {
                // Avoid detecting valleys too close together (minimum half a rep apart)
                if (valleys.isEmpty() || i - valleys.last() >= 8) {
                    valleys.add(i)
                }
            }
        }

        // Check edge cases: first sample might be a valley
        if (valleys.isEmpty() || valleys.first() > 5) {
            val firstFew = smoothedPositions.take(5)
            val restAvg = smoothedPositions.drop(5).take(10).average()
            if (firstFew.first() < restAvg - VALLEY_THRESHOLD_MM) {
                valleys.add(0, 0)
            }
        }

        // Check edge cases: last samples might contain a valley
        if (smoothedPositions.size > 10) {
            val lastFew = smoothedPositions.takeLast(5)
            val beforeLastAvg = smoothedPositions.dropLast(5).takeLast(10).average()
            if (lastFew.last() < beforeLastAvg - VALLEY_THRESHOLD_MM) {
                if (valleys.isEmpty() || smoothedPositions.size - 1 - valleys.last() >= 8) {
                    valleys.add(smoothedPositions.size - 1)
                }
            }
        }

        return valleys
    }

    /**
     * Calculate average ROM from valley-peak differences across all detected reps.
     * Uses raw position data for accuracy (not smoothed).
     */
    private fun calculateRom(
        metrics: List<WorkoutMetric>,
        valleys: List<Int>
    ): Float {
        if (valleys.size < 2) return 0f

        val roms = mutableListOf<Float>()

        for (i in 0 until valleys.size - 1) {
            val valleyStart = valleys[i]
            val valleyEnd = minOf(valleys[i + 1], metrics.size - 1)

            // Find peak and valley positions using raw data
            val repMetrics = metrics.subList(valleyStart, valleyEnd + 1)
            val peakPos = repMetrics.maxOfOrNull { it.positionA } ?: continue
            val valleyPos = repMetrics.minOfOrNull { it.positionA } ?: continue

            roms.add(peakPos - valleyPos)
        }

        return if (roms.isNotEmpty()) roms.average().toFloat() else 0f
    }

    /**
     * Calculate average rep duration from valley timestamps.
     */
    private fun calculateDuration(
        metrics: List<WorkoutMetric>,
        valleys: List<Int>
    ): Long {
        if (valleys.size < 2) return 0L

        val durations = mutableListOf<Long>()

        for (i in 0 until valleys.size - 1) {
            val startTime = metrics[valleys[i]].timestamp
            val endTime = metrics[valleys[i + 1]].timestamp
            durations.add(endTime - startTime)
        }

        return if (durations.isNotEmpty()) durations.average().toLong() else 0L
    }

    /**
     * Calculate load symmetry ratio: loadA / (loadA + loadB)
     */
    private fun calculateSymmetry(metrics: List<WorkoutMetric>): Float {
        val totalLoadA = metrics.sumOf { it.loadA.toDouble() }
        val totalLoadB = metrics.sumOf { it.loadB.toDouble() }
        val totalLoad = totalLoadA + totalLoadB

        return if (totalLoad > 0) {
            (totalLoadA / totalLoad).toFloat()
        } else {
            0.5f
        }
    }

    /**
     * Detect cable usage pattern based on load distribution.
     */
    private fun detectCableUsage(
        metrics: List<WorkoutMetric>,
        symmetryRatio: Float
    ): CableUsage {
        // Check if either cable is always inactive (< 1kg)
        val loadBAlwaysInactive = metrics.all { it.loadB < CABLE_ACTIVE_THRESHOLD_KG }
        val loadAAlwaysInactive = metrics.all { it.loadA < CABLE_ACTIVE_THRESHOLD_KG }

        return when {
            loadBAlwaysInactive -> CableUsage.SINGLE_LEFT
            loadAAlwaysInactive -> CableUsage.SINGLE_RIGHT
            symmetryRatio in SYMMETRIC_LOWER..SYMMETRIC_UPPER -> CableUsage.DUAL_SYMMETRIC
            else -> CableUsage.DUAL_ASYMMETRIC
        }
    }

    /**
     * Classify velocity profile based on first rep's concentric phase.
     * Divides concentric phase into thirds and compares average velocities.
     */
    private fun classifyVelocityProfile(
        metrics: List<WorkoutMetric>,
        valleys: List<Int>
    ): VelocityShape {
        if (valleys.size < 2) return VelocityShape.LINEAR

        // Get first rep: from first valley to second valley
        val repStart = valleys[0]
        val repEnd = valleys[1]
        val repLength = repEnd - repStart

        // Concentric phase is first half of rep (valley to peak)
        val concentricEnd = repStart + repLength / 2
        val concentricMetrics = metrics.subList(repStart, concentricEnd)

        if (concentricMetrics.size < 3) return VelocityShape.LINEAR

        // Divide into thirds
        val thirdSize = concentricMetrics.size / 3
        if (thirdSize == 0) return VelocityShape.LINEAR

        val firstThird = concentricMetrics.take(thirdSize)
        val secondThird = concentricMetrics.drop(thirdSize).take(thirdSize)
        val lastThird = concentricMetrics.takeLast(thirdSize)

        val avgFirst = firstThird.map { abs(it.velocityA) }.average()
        val avgSecond = secondThird.map { abs(it.velocityA) }.average()
        val avgLast = lastThird.map { abs(it.velocityA) }.average()

        // Classification logic
        // EXPLOSIVE_START: first third significantly higher than others
        // DECELERATING: last third significantly lower than first
        // LINEAR: roughly equal throughout

        val threshold = 0.15 // 15% difference threshold

        return when {
            // First third is highest (at least 15% higher than both others)
            avgFirst > avgSecond * (1 + threshold) && avgFirst > avgLast * (1 + threshold) ->
                VelocityShape.EXPLOSIVE_START

            // Last third is lowest (at least 15% lower than first)
            avgLast < avgFirst * (1 - threshold) && avgLast < avgSecond * (1 - threshold) ->
                VelocityShape.DECELERATING

            // Roughly equal throughout
            else -> VelocityShape.LINEAR
        }
    }
}

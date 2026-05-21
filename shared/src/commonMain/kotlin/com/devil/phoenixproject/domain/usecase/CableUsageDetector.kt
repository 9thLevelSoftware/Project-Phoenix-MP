package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.WorkoutMetric

/**
 * Detects when a set was performed on a single cable despite dual-cable metadata or
 * both cables being physically connected (Issue #340).
 */
object CableUsageDetector {
    private const val LOAD_IMBALANCE_RATIO = 3f
    private const val ROM_IMBALANCE_RATIO = 3f
    private const val MIN_MEANINGFUL_ROM_MM = 20f
    private const val CONCENTRIC_VELOCITY_THRESHOLD = 10.0
    private const val VELOCITY_DOMINANCE_RATIO = 1.5
    private const val VELOCITY_DOMINANCE_FRACTION = 0.7f

    /**
     * Returns true when telemetry indicates a single active cable for the set.
     */
    fun isSingleCableUsage(metrics: List<WorkoutMetric>): Boolean {
        if (metrics.isEmpty()) return false

        if (isLoadImbalanced(metrics)) return true
        if (isRomImbalanced(metrics)) return true
        if (hasDominantConcentricCable(metrics)) return true

        return false
    }

    private fun isLoadImbalanced(metrics: List<WorkoutMetric>): Boolean {
        val peakCableA = metrics.maxOf { it.loadA }
        val peakCableB = metrics.maxOf { it.loadB }

        return (
            peakCableA > 0f && peakCableB > 0f &&
                (peakCableA / peakCableB > LOAD_IMBALANCE_RATIO || peakCableB / peakCableA > LOAD_IMBALANCE_RATIO)
            ) ||
            (peakCableA > 0f && peakCableB == 0f) ||
            (peakCableB > 0f && peakCableA == 0f)
    }

    private fun isRomImbalanced(metrics: List<WorkoutMetric>): Boolean {
        if (metrics.size < 2) return false

        var romA = 0f
        var romB = 0f
        for (index in 1 until metrics.size) {
            romA += kotlin.math.abs(metrics[index].positionA - metrics[index - 1].positionA)
            romB += kotlin.math.abs(metrics[index].positionB - metrics[index - 1].positionB)
        }

        val maxRom = maxOf(romA, romB)
        val minRom = minOf(romA, romB).coerceAtLeast(0.1f)
        return maxRom >= MIN_MEANINGFUL_ROM_MM && maxRom / minRom >= ROM_IMBALANCE_RATIO
    }

    private fun hasDominantConcentricCable(metrics: List<WorkoutMetric>): Boolean {
        val concentric = metrics.filter {
            it.velocityA > CONCENTRIC_VELOCITY_THRESHOLD || it.velocityB > CONCENTRIC_VELOCITY_THRESHOLD
        }
        if (concentric.isEmpty()) return false

        val dominantA = concentric.count { sample ->
            sample.velocityA > CONCENTRIC_VELOCITY_THRESHOLD &&
                (
                    sample.velocityB <= CONCENTRIC_VELOCITY_THRESHOLD ||
                        sample.velocityA >= sample.velocityB * VELOCITY_DOMINANCE_RATIO
                    )
        }
        val dominantB = concentric.count { sample ->
            sample.velocityB > CONCENTRIC_VELOCITY_THRESHOLD &&
                (
                    sample.velocityA <= CONCENTRIC_VELOCITY_THRESHOLD ||
                        sample.velocityB >= sample.velocityA * VELOCITY_DOMINANCE_RATIO
                    )
        }

        val threshold = (concentric.size * VELOCITY_DOMINANCE_FRACTION).toInt().coerceAtLeast(1)
        return dominantA >= threshold || dominantB >= threshold
    }
}

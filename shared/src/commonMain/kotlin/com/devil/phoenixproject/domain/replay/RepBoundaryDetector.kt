package com.devil.phoenixproject.domain.replay

/**
 * Represents the detected boundaries of a single rep within a position time-series.
 *
 * Used by replay visualization to isolate and animate individual reps.
 */
data class RepBoundary(
    val repNumber: Int,              // 1-indexed
    val startIndex: Int,             // Valley start index in position array
    val peakIndex: Int,              // Peak position index (concentric -> eccentric transition)
    val endIndex: Int,               // Valley end index (next rep start or array end)
    val concentricIndices: IntRange, // startIndex until peakIndex
    val eccentricIndices: IntRange   // peakIndex until endIndex
)

/**
 * Detects rep boundaries from position time-series data using valley detection.
 *
 * Algorithm:
 * 1. Apply 5-sample moving average smoothing to reduce noise
 * 2. Detect valleys using local minima detection (+/- 2 sample window)
 * 3. Valleys must have minimum prominence (10mm below surrounding peaks)
 * 4. Minimum 8 samples between valleys to prevent false positives
 * 5. Find peak between each valley pair for concentric/eccentric split
 */
class RepBoundaryDetector {

    companion object {
        /** Minimum samples required for valid detection */
        private const val MIN_SAMPLES = 15

        /** Minimum position difference to consider as a valley (in mm) */
        private const val VALLEY_THRESHOLD_MM = 10f

        /** Moving average window size for smoothing */
        private const val SMOOTHING_WINDOW = 5

        /** Minimum samples between valleys to prevent false positives */
        private const val MIN_VALLEY_SEPARATION = 8
    }

    /**
     * Detect rep boundaries from position data.
     *
     * @param positions Array of position values in mm (from MetricSample data)
     * @return List of RepBoundary objects, one per detected rep
     */
    fun detectBoundaries(positions: FloatArray): List<RepBoundary> {
        // Minimum data check
        if (positions.size < MIN_SAMPLES) return emptyList()

        // Apply moving average smoothing
        val smoothed = smoothPositions(positions)

        // Detect valleys (local minima)
        val valleys = detectValleys(smoothed)

        // Need at least 2 valleys for 1 rep
        if (valleys.size < 2) return emptyList()

        // Build rep boundaries from valley pairs
        return buildRepBoundaries(positions, smoothed, valleys)
    }

    /**
     * Apply 5-sample moving average smoothing to position data.
     */
    private fun smoothPositions(positions: FloatArray): FloatArray {
        if (positions.size < SMOOTHING_WINDOW) {
            return positions.copyOf()
        }

        return FloatArray(positions.size) { i ->
            val start = maxOf(0, i - SMOOTHING_WINDOW / 2)
            val end = minOf(positions.size - 1, i + SMOOTHING_WINDOW / 2)
            var sum = 0f
            for (j in start..end) {
                sum += positions[j]
            }
            sum / (end - start + 1)
        }
    }

    /**
     * Detect valleys (local minima) in smoothed position data.
     * A valley is a local minimum where position is lower than nearby samples.
     */
    private fun detectValleys(smoothed: FloatArray): List<Int> {
        if (smoothed.size < 5) return emptyList()

        val valleys = mutableListOf<Int>()

        // Find all local minima with prominence check
        for (i in 2 until smoothed.size - 2) {
            val current = smoothed[i]

            // Check if current is local minimum in +/- 2 window
            var isMinimum = true
            var maxInWindow = current
            for (j in -2..2) {
                val value = smoothed[i + j]
                if (value < current) {
                    isMinimum = false
                    break
                }
                if (value > maxInWindow) {
                    maxInWindow = value
                }
            }

            // Must be minimum and have sufficient prominence (10mm below peak in window)
            if (isMinimum && maxInWindow - current >= VALLEY_THRESHOLD_MM) {
                // Enforce minimum separation between valleys
                if (valleys.isEmpty() || i - valleys.last() >= MIN_VALLEY_SEPARATION) {
                    valleys.add(i)
                }
            }
        }

        // Check edge case: first sample might be a valley
        if (smoothed.size > 10) {
            val firstFew = smoothed.take(5).minOrNull() ?: smoothed[0]
            val restAvg = smoothed.drop(5).take(10).average().toFloat()
            if (firstFew < restAvg - VALLEY_THRESHOLD_MM) {
                // Find the actual minimum in the first few samples
                val minIndex = (0 until minOf(5, smoothed.size)).minByOrNull { smoothed[it] } ?: 0
                if (valleys.isEmpty() || valleys.first() - minIndex >= MIN_VALLEY_SEPARATION) {
                    valleys.add(0, minIndex)
                }
            }
        }

        // Check edge case: last samples might contain a valley
        if (smoothed.size > 10) {
            val lastFewMin = smoothed.takeLast(5).minOrNull() ?: smoothed.last()
            val beforeLastAvg = smoothed.dropLast(5).takeLast(10).average().toFloat()
            if (lastFewMin < beforeLastAvg - VALLEY_THRESHOLD_MM) {
                val baseIndex = smoothed.size - 5
                val minOffset = (0 until 5).minByOrNull { smoothed[baseIndex + it] } ?: 4
                val minIndex = baseIndex + minOffset
                if (valleys.isEmpty() || minIndex - valleys.last() >= MIN_VALLEY_SEPARATION) {
                    valleys.add(minIndex)
                }
            }
        }

        return valleys.sorted()
    }

    /**
     * Build RepBoundary objects from detected valleys.
     */
    private fun buildRepBoundaries(
        rawPositions: FloatArray,
        smoothed: FloatArray,
        valleys: List<Int>
    ): List<RepBoundary> {
        val boundaries = mutableListOf<RepBoundary>()

        for (i in 0 until valleys.size - 1) {
            val startIndex = valleys[i]
            val endIndex = valleys[i + 1]

            // Find peak position between valleys using raw positions for accuracy
            val peakIndex = findPeakBetween(rawPositions, startIndex, endIndex)

            // Build concentric and eccentric ranges
            val concentricIndices = startIndex until peakIndex
            val eccentricIndices = peakIndex..endIndex

            boundaries.add(
                RepBoundary(
                    repNumber = i + 1,
                    startIndex = startIndex,
                    peakIndex = peakIndex,
                    endIndex = endIndex,
                    concentricIndices = concentricIndices,
                    eccentricIndices = eccentricIndices
                )
            )
        }

        return boundaries
    }

    /**
     * Find the index of maximum position between two indices.
     */
    private fun findPeakBetween(positions: FloatArray, start: Int, end: Int): Int {
        var peakIndex = start
        var peakValue = positions[start]

        for (i in start..end) {
            if (positions[i] > peakValue) {
                peakValue = positions[i]
                peakIndex = i
            }
        }

        return peakIndex
    }
}

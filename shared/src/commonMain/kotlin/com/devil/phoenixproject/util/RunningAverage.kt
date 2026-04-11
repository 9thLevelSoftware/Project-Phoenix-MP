package com.devil.phoenixproject.util

/**
 * Simple running average accumulator.
 *
 * Tracks a running sum and count for computing the arithmetic mean
 * of a stream of values. Used by RepQualityScorer for ROM and velocity
 * consistency scoring across reps in a set.
 *
 * Thread safety: Not thread-safe. Callers must synchronize externally
 * if accessed from multiple coroutines.
 */
class RunningAverage {
    private var sum = 0.0
    private var count = 0

    /**
     * Add a value to the running average.
     */
    fun add(value: Float) {
        sum += value
        count++
    }

    /**
     * Returns the current average, or 0f if no values have been added.
     */
    fun average(): Float {
        if (count == 0) return 0f
        return (sum / count).toFloat()
    }

    /**
     * Reset the accumulator to its initial state.
     */
    fun reset() {
        sum = 0.0
        count = 0
    }

    /**
     * Returns the number of values added.
     */
    fun count(): Int = count
}

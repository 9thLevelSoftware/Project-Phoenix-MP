package com.devil.phoenixproject.data.repository.simulator

import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Physics engine for realistic workout simulation.
 *
 * Generates position curves, detects rep completions, and calculates load/velocity
 * that mimics real Vitruvian machine behavior. Position follows a sinusoidal curve
 * for natural movement patterns, and reps are detected by threshold crossings.
 *
 * Usage:
 * ```
 * val engine = WorkoutSimulationEngine()
 * engine.startWorkout(weightPerCableKg = 50f)
 *
 * // In your update loop:
 * val (posA, posB) = engine.calculatePosition(currentTimeMs)
 * val velocity = engine.calculateVelocity(posA, currentTimeMs)
 * val (loadA, loadB) = engine.calculateLoad()
 * val repCompletion = engine.checkRepCompletion(posA)
 * ```
 *
 * @property config Simulation parameters for timing, ranges, and load
 */
class WorkoutSimulationEngine(
    private val config: SimulatorConfig = SimulatorConfig()
) {
    // Current workout state
    private var workoutStartTimeMs: Long = 0
    private var lastPositionA: Float = 0f
    private var lastTimestamp: Long = 0
    private var topReachedCount: Int = 0
    private var bottomReachedCount: Int = 0

    // Current weight setting
    private var currentWeightKg: Float = 0f

    // Rep detection state
    private var wasAboveTopThreshold: Boolean = false
    private var wasAboveBottomThreshold: Boolean = false

    // Threshold percentages for rep detection
    private val topThresholdPercent = 0.95f
    private val bottomThresholdPercent = 0.05f

    /**
     * Start a new workout simulation.
     *
     * @param weightPerCableKg Weight setting per cable in kilograms
     */
    fun startWorkout(weightPerCableKg: Float) {
        reset()
        currentWeightKg = weightPerCableKg
        workoutStartTimeMs = currentTimeMillis()
        lastTimestamp = workoutStartTimeMs
    }

    /**
     * Stop the current simulation.
     */
    fun stopWorkout() {
        // Reset timing but preserve rep counts for reporting
        workoutStartTimeMs = 0
    }

    /**
     * Calculate current simulated position based on elapsed time.
     *
     * Uses a sinusoidal curve: (1 - cos(phase)) / 2 which produces
     * smooth movement from bottom (0) to top (max) and back.
     *
     * @param currentTimeMs Current timestamp in milliseconds
     * @return Pair of (positionA, positionB) - both same for double-cable simulation
     */
    fun calculatePosition(currentTimeMs: Long): Pair<Float, Float> {
        if (workoutStartTimeMs == 0L) {
            return Pair(config.positionRange.start, config.positionRange.start)
        }

        val elapsedMs = currentTimeMs - workoutStartTimeMs

        // Phase goes 0 to 2*PI over repDurationMs
        val phase = ((elapsedMs % config.repDurationMs).toFloat() / config.repDurationMs) * 2 * PI.toFloat()

        // Position: sin curve shifted so 0 phase = bottom position
        // (1 - cos(phase)) / 2 gives us 0 -> 1 -> 0 as phase goes 0 -> PI -> 2PI
        val normalizedPosition = (1 - cos(phase)) / 2f

        val rangeSize = config.positionRange.endInclusive - config.positionRange.start
        val position = config.positionRange.start + normalizedPosition * rangeSize

        // Store for velocity calculation
        lastPositionA = position
        lastTimestamp = currentTimeMs

        // Both cables return same position for symmetric movement
        return Pair(position, position)
    }

    /**
     * Calculate current simulated load with natural variance.
     *
     * Load = base machine tension + user weight + small random variance
     *
     * @return Pair of (loadA, loadB) in kilograms - both same for double-cable
     */
    fun calculateLoad(): Pair<Float, Float> {
        if (currentWeightKg <= 0f) {
            return Pair(config.baseLoadKg, config.baseLoadKg)
        }

        // Add natural variance to simulate real hardware behavior
        val variance = (Random.nextFloat() - 0.5f) * 2 * config.loadVariancePercent / 100f
        val load = config.baseLoadKg + currentWeightKg * (1 + variance)

        return Pair(load, load)
    }

    /**
     * Calculate velocity from position change over time.
     *
     * @param currentPos Current position in mm
     * @param currentTimeMs Current timestamp in milliseconds
     * @return Velocity in mm/s
     */
    fun calculateVelocity(currentPos: Float, currentTimeMs: Long): Double {
        if (lastTimestamp == 0L || lastTimestamp == currentTimeMs) {
            return 0.0
        }

        val deltaTimeMs = currentTimeMs - lastTimestamp
        if (deltaTimeMs <= 0) {
            return 0.0
        }

        val deltaPosition = currentPos - lastPositionA

        // Convert to mm/s (deltaTime is in ms, so multiply by 1000)
        return (deltaPosition.toDouble() / deltaTimeMs) * 1000.0
    }

    /**
     * Check if a rep completion event occurred.
     *
     * Rep detection uses threshold crossings:
     * - TOP_REACHED: Position crosses above 95% of max range
     * - BOTTOM_REACHED: Position drops below 5% of max range after reaching top
     *
     * @param position Current position to check
     * @return RepCompletionType if a threshold was crossed, null otherwise
     */
    fun checkRepCompletion(position: Float): RepCompletionType? {
        val rangeSize = config.positionRange.endInclusive - config.positionRange.start
        val topThreshold = config.positionRange.start + rangeSize * topThresholdPercent
        val bottomThreshold = config.positionRange.start + rangeSize * bottomThresholdPercent

        val isAboveTopThreshold = position >= topThreshold
        val isBelowBottomThreshold = position <= bottomThreshold

        var result: RepCompletionType? = null

        // Check for top reached (crossing up through top threshold)
        if (isAboveTopThreshold && !wasAboveTopThreshold) {
            topReachedCount++
            wasAboveBottomThreshold = true  // We're definitely above bottom now
            result = RepCompletionType.TOP_REACHED
        }

        // Check for bottom reached (crossing down through bottom threshold after top)
        if (isBelowBottomThreshold && wasAboveBottomThreshold && topReachedCount > bottomReachedCount) {
            bottomReachedCount++
            wasAboveBottomThreshold = false  // Reset for next rep
            result = RepCompletionType.BOTTOM_REACHED
        }

        wasAboveTopThreshold = isAboveTopThreshold

        return result
    }

    /**
     * Get current rep counts.
     *
     * @return Pair of (topCounter, bottomCounter) representing movement phases completed
     */
    fun getRepCounts(): Pair<Int, Int> {
        return Pair(topReachedCount, bottomReachedCount)
    }

    /**
     * Reset the engine for a new workout.
     */
    fun reset() {
        workoutStartTimeMs = 0
        lastPositionA = 0f
        lastTimestamp = 0
        topReachedCount = 0
        bottomReachedCount = 0
        currentWeightKg = 0f
        wasAboveTopThreshold = false
        wasAboveBottomThreshold = false
    }

    /**
     * Get current time in milliseconds (abstracted for testability).
     */
    private fun currentTimeMillis(): Long {
        return Clock.System.now().toEpochMilliseconds()
    }
}

/**
 * Represents a rep completion event type.
 */
enum class RepCompletionType {
    /** Position reached the top of the movement range */
    TOP_REACHED,
    /** Position returned to bottom, completing a full rep */
    BOTTOM_REACHED
}

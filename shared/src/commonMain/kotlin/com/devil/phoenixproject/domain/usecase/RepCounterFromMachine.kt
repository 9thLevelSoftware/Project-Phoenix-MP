package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import kotlin.math.max

/**
 * Handles rep counting based on notifications emitted by the Vitruvian machine.
 *
 * REP COUNTING APPROACH (Official App Method with Visual Feedback):
 * Uses machine-provided ROM and Set counters for actual rep counting, PLUS
 * directional counters (up/down) for visual feedback timing:
 *
 * - At TOP (concentric peak): Show PENDING rep (grey number, +1 preview)
 * - During eccentric: Fill animation from top to bottom
 * - At BOTTOM (eccentric valley): Rep CONFIRMED (colored number)
 *
 * This creates the "number rolls up grey, fills with color going down" effect.
 */
class RepCounterFromMachine {

    private val log = Logger.withTag("RepCounterFromMachine")

    private var warmupReps = 0
    private var workingReps = 0
    private var warmupTarget = 3
    private var workingTarget = 0
    private var isJustLift = false
    private var stopAtTop = false
    private var shouldStop = false
    private var isAMRAP = false

    // Pending rep state - true when at TOP, waiting for eccentric completion
    private var hasPendingRep = false
    private var pendingRepProgress = 0f  // 0.0 at TOP, 1.0 at BOTTOM

    // Track directional counters for position calibration AND visual feedback
    private var lastTopCounter: Int? = null
    private var lastCompleteCounter: Int? = null

    private val topPositionsA = mutableListOf<Int>()
    private val topPositionsB = mutableListOf<Int>()
    private val bottomPositionsA = mutableListOf<Int>()
    private val bottomPositionsB = mutableListOf<Int>()

    private var maxRepPosA: Int? = null
    private var minRepPosA: Int? = null
    private var maxRepPosB: Int? = null
    private var minRepPosB: Int? = null

    private var maxRepPosARange: Pair<Int, Int>? = null
    private var minRepPosARange: Pair<Int, Int>? = null
    private var maxRepPosBRange: Pair<Int, Int>? = null
    private var minRepPosBRange: Pair<Int, Int>? = null

    var onRepEvent: ((RepEvent) -> Unit)? = null

    fun configure(
        warmupTarget: Int,
        workingTarget: Int,
        isJustLift: Boolean,
        stopAtTop: Boolean,
        isAMRAP: Boolean = false
    ) {
        this.warmupTarget = warmupTarget
        this.workingTarget = workingTarget
        this.isJustLift = isJustLift
        this.stopAtTop = stopAtTop
        this.isAMRAP = isAMRAP

        // Log RepCounter configuration
        logDebug("🔧 RepCounter.configure() called:")
        logDebug("  warmupTarget: $warmupTarget")
        logDebug("  workingTarget: $workingTarget")
        logDebug("  isJustLift: $isJustLift")
        logDebug("  stopAtTop: $stopAtTop")
        logDebug("  isAMRAP: $isAMRAP")
    }

    fun reset() {
        warmupReps = 0
        workingReps = 0
        shouldStop = false
        hasPendingRep = false
        pendingRepProgress = 0f
        lastTopCounter = null
        lastCompleteCounter = null
        topPositionsA.clear()
        topPositionsB.clear()
        bottomPositionsA.clear()
        bottomPositionsB.clear()
        maxRepPosA = null
        minRepPosA = null
        maxRepPosB = null
        minRepPosB = null
        maxRepPosARange = null
        minRepPosARange = null
        maxRepPosBRange = null
        minRepPosBRange = null
    }

    /**
     * Resets rep counts but PRESERVES position ranges.
     *
     * This is critical for Just Lift mode where we track positions continuously during
     * the handle detection phase (before workout starts). A full reset() would wipe out
     * the position ranges we built up, making hasMeaningfulRange() return false.
     */
    fun resetCountsOnly() {
        warmupReps = 0
        workingReps = 0
        shouldStop = false
        hasPendingRep = false
        pendingRepProgress = 0f
        lastTopCounter = null
        lastCompleteCounter = null
        // NOTE: Do NOT clear position tracking lists or min/max ranges!
        // This preserves hasMeaningfulRange() for auto-stop detection
    }

    /**
     * Sets the initial baseline position when the workout starts (after countdown completes).
     * This calibrates the position bars to the starting rope position, so bars show 0% at
     * the starting position rather than showing raw machine values.
     *
     * The baseline will be refined as reps are performed through the sliding window calibration.
     * Position values are in mm (Issue #197).
     */
    fun setInitialBaseline(posA: Float, posB: Float) {
        // Convert mm to internal Int representation for range tracking
        val posAInt = posA.toInt()
        val posBInt = posB.toInt()
        // Only set initial baseline if positions are valid and not already calibrated
        if (posAInt > 0 && minRepPosA == null) {
            minRepPosA = posAInt
            minRepPosARange = Pair(posAInt, posAInt)
        }
        if (posBInt > 0 && minRepPosB == null) {
            minRepPosB = posBInt
            minRepPosBRange = Pair(posBInt, posBInt)
        }
    }

    /**
     * Continuously update position ranges for Just Lift mode.
     *
     * In Just Lift mode, no rep events fire, so we need to track min/max positions
     * continuously from monitor data to establish meaningful ranges for auto-stop.
     *
     * This should be called on every monitor metric during an active Just Lift workout.
     * Position values are in mm (Issue #197).
     */
    fun updatePositionRangesContinuously(posA: Float, posB: Float) {
        // Convert mm to internal Int representation for range tracking
        val posAInt = posA.toInt()
        val posBInt = posB.toInt()
        if (posAInt <= 0 && posBInt <= 0) return

        // Track minimum positions (cable at rest / bottom of movement)
        if (posAInt > 0) {
            if (minRepPosA == null || posAInt < minRepPosA!!) {
                minRepPosA = posAInt
                minRepPosARange = Pair(posAInt, minRepPosARange?.second ?: posAInt)
            }
            // Track maximum positions (cable extended / top of movement)
            if (maxRepPosA == null || posAInt > maxRepPosA!!) {
                maxRepPosA = posAInt
                maxRepPosARange = Pair(maxRepPosARange?.first ?: posAInt, posAInt)
            }
        }

        if (posBInt > 0) {
            if (minRepPosB == null || posBInt < minRepPosB!!) {
                minRepPosB = posBInt
                minRepPosBRange = Pair(posBInt, minRepPosBRange?.second ?: posBInt)
            }
            if (maxRepPosB == null || posBInt > maxRepPosB!!) {
                maxRepPosB = posBInt
                maxRepPosBRange = Pair(maxRepPosBRange?.first ?: posBInt, posBInt)
            }
        }
    }

    /**
     * Process rep data from machine with visual feedback timing.
     *
     * For WARMUP reps: Uses ROM counter directly (no pending animation)
     * For WORKING reps: Shows pending (grey) at TOP, confirmed (colored) at BOTTOM
     *
     * @param repsRomCount Machine's ROM rep count (warmup reps)
     * @param repsSetCount Machine's set rep count (working reps)
     * @param up Directional counter - increments at TOP (concentric peak)
     * @param down Directional counter - increments at BOTTOM (eccentric valley)
     * @param posA Position A for range calibration (in mm, Issue #197)
     * @param posB Position B for range calibration (in mm, Issue #197)
     */
    fun process(
        repsRomCount: Int,
        repsSetCount: Int,
        up: Int = 0,
        down: Int = 0,
        posA: Float = 0f,
        posB: Float = 0f
    ) {
        // Convert mm to internal Int representation for range tracking
        val posAInt = posA.toInt()
        val posBInt = posB.toInt()
        logDebug("Rep process: ROM=$repsRomCount, Set=$repsSetCount, up=$up, down=$down, pending=$hasPendingRep")

        // Track UP movement - for working reps, show PENDING (grey) at TOP
        if (lastTopCounter != null) {
            val upDelta = calculateDelta(lastTopCounter!!, up)
            if (upDelta > 0) {
                recordTopPosition(posAInt, posBInt)

                // Only show pending for WORKING reps (after warmup complete)
                if (warmupReps >= warmupTarget && !hasPendingRep) {
                    hasPendingRep = true
                    pendingRepProgress = 0f
                    logDebug("📈 TOP - WORKING_PENDING: showing grey rep ${workingReps + 1}")

                    onRepEvent?.invoke(
                        RepEvent(
                            type = RepType.WORKING_PENDING,
                            warmupCount = warmupReps,
                            workingCount = workingReps  // Still the old count, pending shows +1
                        )
                    )
                }
            }
        }

        // Track DOWN movement - for working reps, CONFIRM (colored) at BOTTOM
        if (lastCompleteCounter != null) {
            val downDelta = calculateDelta(lastCompleteCounter!!, down)
            if (downDelta > 0) {
                recordBottomPosition(posAInt, posBInt)

                // Clear pending state when we reach bottom
                if (hasPendingRep) {
                    hasPendingRep = false
                    pendingRepProgress = 1f
                    logDebug("📉 BOTTOM - pending cleared, waiting for machine confirm")
                }
            }
        }

        // Continuous pending rep progress update (Eccentric phase: Top -> Bottom)
        if (hasPendingRep) {
            val minA = minRepPosA ?: 0
            val maxA = maxRepPosA ?: 1000
            val rangeA = maxA - minA

            // Calculate progress for Cable A (1.0 at Top, 0.0 at Bottom -> Invert for fill)
            // We want 0.0 at Top (Start of eccentric) -> 1.0 at Bottom (End of eccentric)
            if (rangeA > 50) { // Ensure meaningful range (50mm threshold)
                val currentPos = posAInt.coerceIn(minA, maxA)
                val fractionFromBottom = (currentPos - minA).toFloat() / rangeA.toFloat()
                // Fraction is 1.0 at Top, 0.0 at Bottom.
                // We want progress 0.0 at Top, 1.0 at Bottom.
                val progress = 1.0f - fractionFromBottom
                pendingRepProgress = progress.coerceIn(0f, 1f)
            }
        }

        // Update tracking counters AFTER position recording
        lastTopCounter = up
        lastCompleteCounter = down

        // Track warmup reps using ROM counter (no pending animation)
        if (repsRomCount > warmupReps && warmupReps < warmupTarget) {
            warmupReps = repsRomCount.coerceAtMost(warmupTarget)

            onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WARMUP_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps
                )
            )

            if (warmupReps >= warmupTarget) {
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps
                    )
                )
            }
        }

        // Track working reps using Set counter - this confirms the rep (colored)
        if (warmupReps >= warmupTarget && repsSetCount > workingReps) {
            workingReps = repsSetCount
            logDebug("💪 WORKING_COMPLETED: rep $workingReps confirmed (colored)")

            onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WORKING_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps
                )
            )

            // Check if target reached (unless AMRAP or Just Lift)
            if (!isJustLift && !isAMRAP && workingTarget > 0 && workingReps >= workingTarget) {
                logDebug("⚠️ shouldStop set to TRUE (target reached)")
                logDebug("  workingTarget=$workingTarget, workingReps=$workingReps")
                shouldStop = true
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WORKOUT_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps
                    )
                )
            }
        }
    }

    private fun calculateDelta(last: Int, current: Int): Int {
        return if (current >= last) {
            current - last
        } else {
            0xFFFF - last + current + 1
        }
    }

    private fun recordTopPosition(posA: Int, posB: Int) {
        if (posA <= 0 && posB <= 0) return

        val window = getWindowSize()
        if (posA > 0) {
            topPositionsA.add(posA)
            if (topPositionsA.size > window) topPositionsA.removeAt(0)
        }
        if (posB > 0) {
            topPositionsB.add(posB)
            if (topPositionsB.size > window) topPositionsB.removeAt(0)
        }

        updateRepRanges()
    }

    private fun recordBottomPosition(posA: Int, posB: Int) {
        if (posA <= 0 && posB <= 0) return

        val window = getWindowSize()
        if (posA > 0) {
            bottomPositionsA.add(posA)
            if (bottomPositionsA.size > window) bottomPositionsA.removeAt(0)
        }
        if (posB > 0) {
            bottomPositionsB.add(posB)
            if (bottomPositionsB.size > window) bottomPositionsB.removeAt(0)
        }

        updateRepRanges()
    }

    private fun updateRepRanges() {
        if (topPositionsA.isNotEmpty()) {
            maxRepPosA = topPositionsA.average().toInt()
            maxRepPosARange = Pair(topPositionsA.minOrNull() ?: 0, topPositionsA.maxOrNull() ?: 0)
        }
        if (bottomPositionsA.isNotEmpty()) {
            minRepPosA = bottomPositionsA.average().toInt()
            minRepPosARange = Pair(bottomPositionsA.minOrNull() ?: 0, bottomPositionsA.maxOrNull() ?: 0)
        }
        if (topPositionsB.isNotEmpty()) {
            maxRepPosB = topPositionsB.average().toInt()
            maxRepPosBRange = Pair(topPositionsB.minOrNull() ?: 0, topPositionsB.maxOrNull() ?: 0)
        }
        if (bottomPositionsB.isNotEmpty()) {
            minRepPosB = bottomPositionsB.average().toInt()
            minRepPosBRange = Pair(bottomPositionsB.minOrNull() ?: 0, bottomPositionsB.maxOrNull() ?: 0)
        }
    }

    private fun getWindowSize(): Int {
        val total = warmupReps + workingReps
        return if (total < warmupTarget) 2 else 3
    }

    fun getRepCount(): RepCount {
        val total = workingReps  // Exclude warm-up reps from total count
        return RepCount(
            warmupReps = warmupReps,
            workingReps = workingReps,
            totalReps = total,
            isWarmupComplete = warmupReps >= warmupTarget,
            hasPendingRep = hasPendingRep,
            pendingRepProgress = pendingRepProgress
        )
    }

    fun shouldStopWorkout(): Boolean = shouldStop

    fun getCurrentRepCount(): RepCount = getRepCount()

    fun getCalibratedTopPosition(): Int? = maxRepPosA

    fun getRepRanges(): RepRanges = RepRanges(
        minPosA = minRepPosA,
        maxPosA = maxRepPosA,
        minPosB = minRepPosB,
        maxPosB = maxRepPosB,
        minRangeA = minRepPosARange,
        maxRangeA = maxRepPosARange,
        minRangeB = minRepPosBRange,
        maxRangeB = maxRepPosBRange
    )

    fun hasMeaningfulRange(minRangeThreshold: Int = 50): Boolean {
        val minA = minRepPosA
        val maxA = maxRepPosA
        val minB = minRepPosB
        val maxB = maxRepPosB
        val rangeA = if (minA != null && maxA != null) maxA - minA else 0
        val rangeB = if (minB != null && maxB != null) maxB - minB else 0
        return rangeA > minRangeThreshold || rangeB > minRangeThreshold
    }

    /**
     * Check if position is in danger zone (within 5% of minimum).
     * Position values are in mm (Issue #197).
     */
    fun isInDangerZone(posA: Float, posB: Float, minRangeThreshold: Int = 50): Boolean {
        // Convert mm to internal Int representation for comparison
        val posAInt = posA.toInt()
        val posBInt = posB.toInt()
        val minA = minRepPosA
        val maxA = maxRepPosA
        val minB = minRepPosB
        val maxB = maxRepPosB

        // Check if position A is in danger zone (within 5% of minimum)
        if (minA != null && maxA != null) {
            val rangeA = maxA - minA
            if (rangeA > minRangeThreshold) {
                val thresholdA = minA + (rangeA * 0.05f).toInt()
                if (posAInt <= thresholdA) return true
            }
        }

        // Check if position B is in danger zone (within 5% of minimum)
        if (minB != null && maxB != null) {
            val rangeB = maxB - minB
            if (rangeB > minRangeThreshold) {
                val thresholdB = minB + (rangeB * 0.05f).toInt()
                if (posBInt <= thresholdB) return true
            }
        }

        return false
    }

    private fun logDebug(message: String) {
        log.d { message }
    }
}

/**
 * Snapshot of the discovered rep ranges for UI/diagnostics.
 */
data class RepRanges(
    val minPosA: Int?,
    val maxPosA: Int?,
    val minPosB: Int?,
    val maxPosB: Int?,
    val minRangeA: Pair<Int, Int>?,
    val maxRangeA: Pair<Int, Int>?,
    val minRangeB: Pair<Int, Int>?,
    val maxRangeB: Pair<Int, Int>?
) {
    val rangeA: Int?
        get() = if (minPosA != null && maxPosA != null) max(maxPosA!! - minPosA!!, 0) else null
    val rangeB: Int?
        get() = if (minPosB != null && maxPosB != null) max(maxPosB!! - minPosB!!, 0) else null
}

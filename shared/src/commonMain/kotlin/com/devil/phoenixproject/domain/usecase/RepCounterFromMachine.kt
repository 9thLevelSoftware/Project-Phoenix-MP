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

    // Position tracking lists - now in mm (Float)
    private val topPositionsA = mutableListOf<Float>()
    private val topPositionsB = mutableListOf<Float>()
    private val bottomPositionsA = mutableListOf<Float>()
    private val bottomPositionsB = mutableListOf<Float>()

    // ROM boundaries in mm
    private var maxRepPosA: Float? = null
    private var minRepPosA: Float? = null
    private var maxRepPosB: Float? = null
    private var minRepPosB: Float? = null

    private var maxRepPosARange: Pair<Float, Float>? = null
    private var minRepPosARange: Pair<Float, Float>? = null
    private var maxRepPosBRange: Pair<Float, Float>? = null
    private var minRepPosBRange: Pair<Float, Float>? = null

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
     */
    fun setInitialBaseline(posA: Float, posB: Float) {
        // Only set initial baseline if positions are valid and not already calibrated
        if (posA > 0f && minRepPosA == null) {
            minRepPosA = posA
            minRepPosARange = Pair(posA, posA)
        }
        if (posB > 0f && minRepPosB == null) {
            minRepPosB = posB
            minRepPosBRange = Pair(posB, posB)
        }
    }

    /**
     * Continuously update position ranges for Just Lift mode.
     *
     * In Just Lift mode, no rep events fire, so we need to track min/max positions
     * continuously from monitor data to establish meaningful ranges for auto-stop.
     *
     * This should be called on every monitor metric during an active Just Lift workout.
     */
    fun updatePositionRangesContinuously(posA: Float, posB: Float) {
        if (posA <= 0f && posB <= 0f) return

        // Track minimum positions (cable at rest / bottom of movement)
        if (posA > 0f) {
            if (minRepPosA == null || posA < minRepPosA!!) {
                minRepPosA = posA
                minRepPosARange = Pair(posA, minRepPosARange?.second ?: posA)
            }
            // Track maximum positions (cable extended / top of movement)
            if (maxRepPosA == null || posA > maxRepPosA!!) {
                maxRepPosA = posA
                maxRepPosARange = Pair(maxRepPosARange?.first ?: posA, posA)
            }
        }

        if (posB > 0f) {
            if (minRepPosB == null || posB < minRepPosB!!) {
                minRepPosB = posB
                minRepPosBRange = Pair(posB, minRepPosBRange?.second ?: posB)
            }
            if (maxRepPosB == null || posB > maxRepPosB!!) {
                maxRepPosB = posB
                maxRepPosBRange = Pair(maxRepPosBRange?.first ?: posB, posB)
            }
        }
    }

    /**
     * Process rep data from machine with visual feedback timing.
     *
     * Supports TWO modes (Issue #187):
     *
     * MODERN MODE (isLegacyFormat=false):
     * - Uses repsRomCount for warmup reps
     * - Uses repsSetCount for working reps
     * - up/down counters for visual pending feedback
     *
     * LEGACY MODE (isLegacyFormat=true, Beta 4 compatible):
     * - Uses topCounter (up) increments to count reps directly
     * - This is the method that worked in Beta 4 and handles Samsung devices
     *
     * @param repsRomCount Machine's ROM rep count (warmup reps) - 0 for legacy
     * @param repsSetCount Machine's set rep count (working reps) - 0 for legacy
     * @param up Directional counter - increments at TOP (concentric peak)
     * @param down Directional counter - increments at BOTTOM (eccentric valley)
     * @param posA Position A for range calibration
     * @param posB Position B for range calibration
     * @param isLegacyFormat True if using 6-byte legacy packet format (Issue #187)
     */
    fun process(
        repsRomCount: Int,
        repsSetCount: Int,
        up: Int = 0,
        down: Int = 0,
        posA: Float = 0f,
        posB: Float = 0f,
        isLegacyFormat: Boolean = false
    ) {
        // DIAGNOSTIC: Log full state for debugging rep counting issues
        val warmupGateOpen = warmupReps >= warmupTarget
        logDebug("Rep process: ROM=$repsRomCount, Set=$repsSetCount, up=$up, down=$down, pending=$hasPendingRep, legacy=$isLegacyFormat")
        logDebug("  Warmup gate: warmupReps=$warmupReps, warmupTarget=$warmupTarget, gate=${if (warmupGateOpen) "OPEN" else "BLOCKED"}")
        logDebug("  Working: workingReps=$workingReps, workingTarget=$workingTarget")

        if (isLegacyFormat) {
            // LEGACY MODE: Count reps based on topCounter increments (Beta 4 method)
            // This is the proven method that works with Samsung devices and older firmware
            processLegacy(up, down, posA, posB)
        } else {
            // MODERN MODE: Use machine-provided repsRomCount/repsSetCount
            processModern(repsRomCount, repsSetCount, up, down, posA, posB)
        }
    }

    /**
     * LEGACY rep counting (Beta 4 method) - counts reps when topCounter increments.
     * Used when machine sends 6-byte packets without repsRomCount/repsSetCount fields.
     */
    private fun processLegacy(up: Int, down: Int, posA: Float, posB: Float) {
        if (lastTopCounter != null) {
            val topDelta = calculateDelta(lastTopCounter!!, up)
            if (topDelta > 0) {
                recordTopPosition(posA, posB)

                // Count the rep at TOP of movement (matches Beta 4 / official app behavior)
                val totalReps = warmupReps + workingReps + 1
                if (totalReps <= warmupTarget) {
                    warmupReps++
                    logDebug("📈 LEGACY: Warmup rep $warmupReps (top counter increment)")
                    onRepEvent?.invoke(
                        RepEvent(
                            type = RepType.WARMUP_COMPLETED,
                            warmupCount = warmupReps,
                            workingCount = workingReps
                        )
                    )
                    if (warmupReps == warmupTarget) {
                        onRepEvent?.invoke(
                            RepEvent(
                                type = RepType.WARMUP_COMPLETE,
                                warmupCount = warmupReps,
                                workingCount = workingReps
                            )
                        )
                    }
                } else {
                    workingReps++
                    logDebug("💪 LEGACY: Working rep $workingReps (top counter increment)")
                    onRepEvent?.invoke(
                        RepEvent(
                            type = RepType.WORKING_COMPLETED,
                            warmupCount = warmupReps,
                            workingCount = workingReps
                        )
                    )

                    // Check if target reached (unless AMRAP or Just Lift)
                    if (!isJustLift && !isAMRAP && workingTarget > 0 && workingReps >= workingTarget) {
                        logDebug("⚠️ LEGACY: shouldStop set to TRUE (target reached)")
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
        }

        // Track bottom position for calibration
        if (lastCompleteCounter != null) {
            val downDelta = calculateDelta(lastCompleteCounter!!, down)
            if (downDelta > 0) {
                recordBottomPosition(posA, posB)
            }
        }

        lastTopCounter = up
        lastCompleteCounter = down
    }

    /**
     * MODERN rep counting - uses machine-provided repsRomCount/repsSetCount.
     * This is the official app method with pending rep visual feedback.
     */
    private fun processModern(repsRomCount: Int, repsSetCount: Int, up: Int, down: Int, posA: Float, posB: Float) {
        // Track UP movement - for working reps, show PENDING (grey) at TOP
        if (lastTopCounter != null) {
            val upDelta = calculateDelta(lastTopCounter!!, up)
            if (upDelta > 0) {
                recordTopPosition(posA, posB)

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
                recordBottomPosition(posA, posB)

                // Clear pending state when we reach bottom
                if (hasPendingRep) {
                    hasPendingRep = false
                    pendingRepProgress = 1f
                    logDebug("📉 BOTTOM - pending cleared, waiting for machine confirm")
                }
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
        // NOTE: The machine handles warmup/working distinction internally.
        // repsSetCount increments for WORKING reps only - trust the machine!
        // We still track warmupReps for UI display, but don't gate on it.
        // The machine won't increment repsSetCount until warmup is complete.
        if (repsSetCount > workingReps) {
            // If machine is reporting working reps but we haven't seen warmup complete,
            // force our warmup tracking to match (machine knows best)
            if (warmupReps < warmupTarget) {
                logDebug("Machine reports working reps (repsSetCount=$repsSetCount) - warmup must be complete")
                warmupReps = warmupTarget
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps
                    )
                )
            }
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

    private fun recordTopPosition(posA: Float, posB: Float) {
        if (posA <= 0f && posB <= 0f) return

        val window = getWindowSize()
        if (posA > 0f) {
            topPositionsA.add(posA)
            if (topPositionsA.size > window) topPositionsA.removeAt(0)
        }
        if (posB > 0f) {
            topPositionsB.add(posB)
            if (topPositionsB.size > window) topPositionsB.removeAt(0)
        }

        updateRepRanges()
    }

    private fun recordBottomPosition(posA: Float, posB: Float) {
        if (posA <= 0f && posB <= 0f) return

        val window = getWindowSize()
        if (posA > 0f) {
            bottomPositionsA.add(posA)
            if (bottomPositionsA.size > window) bottomPositionsA.removeAt(0)
        }
        if (posB > 0f) {
            bottomPositionsB.add(posB)
            if (bottomPositionsB.size > window) bottomPositionsB.removeAt(0)
        }

        updateRepRanges()
    }

    private fun updateRepRanges() {
        if (topPositionsA.isNotEmpty()) {
            maxRepPosA = topPositionsA.average().toFloat()
            maxRepPosARange = Pair(topPositionsA.minOrNull() ?: 0f, topPositionsA.maxOrNull() ?: 0f)
        }
        if (bottomPositionsA.isNotEmpty()) {
            minRepPosA = bottomPositionsA.average().toFloat()
            minRepPosARange = Pair(bottomPositionsA.minOrNull() ?: 0f, bottomPositionsA.maxOrNull() ?: 0f)
        }
        if (topPositionsB.isNotEmpty()) {
            maxRepPosB = topPositionsB.average().toFloat()
            maxRepPosBRange = Pair(topPositionsB.minOrNull() ?: 0f, topPositionsB.maxOrNull() ?: 0f)
        }
        if (bottomPositionsB.isNotEmpty()) {
            minRepPosB = bottomPositionsB.average().toFloat()
            minRepPosBRange = Pair(bottomPositionsB.minOrNull() ?: 0f, bottomPositionsB.maxOrNull() ?: 0f)
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

    fun getCalibratedTopPosition(): Float? = maxRepPosA

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

    fun hasMeaningfulRange(minRangeThreshold: Float = 50f): Boolean {
        val minA = minRepPosA
        val maxA = maxRepPosA
        val minB = minRepPosB
        val maxB = maxRepPosB
        val rangeA = if (minA != null && maxA != null) maxA - minA else 0f
        val rangeB = if (minB != null && maxB != null) maxB - minB else 0f
        return rangeA > minRangeThreshold || rangeB > minRangeThreshold
    }

    fun isInDangerZone(posA: Float, posB: Float, minRangeThreshold: Float = 50f): Boolean {
        val minA = minRepPosA
        val maxA = maxRepPosA
        val minB = minRepPosB
        val maxB = maxRepPosB

        // Check if position A is in danger zone (within 5% of minimum)
        if (minA != null && maxA != null) {
            val rangeA = maxA - minA
            if (rangeA > minRangeThreshold) {
                val thresholdA = minA + (rangeA * 0.05f)
                if (posA <= thresholdA) return true
            }
        }

        // Check if position B is in danger zone (within 5% of minimum)
        if (minB != null && maxB != null) {
            val rangeB = maxB - minB
            if (rangeB > minRangeThreshold) {
                val thresholdB = minB + (rangeB * 0.05f)
                if (posB <= thresholdB) return true
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
 * Position values are in mm (Float).
 */
data class RepRanges(
    val minPosA: Float?,
    val maxPosA: Float?,
    val minPosB: Float?,
    val maxPosB: Float?,
    val minRangeA: Pair<Float, Float>?,
    val maxRangeA: Pair<Float, Float>?,
    val minRangeB: Pair<Float, Float>?,
    val maxRangeB: Pair<Float, Float>?
) {
    val rangeA: Float?
        get() = if (minPosA != null && maxPosA != null) max(maxPosA!! - minPosA!!, 0f) else null
    val rangeB: Float?
        get() = if (minPosB != null && maxPosB != null) max(maxPosB!! - minPosB!!, 0f) else null
}

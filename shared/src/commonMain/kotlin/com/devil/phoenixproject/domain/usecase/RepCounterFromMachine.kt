package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepPhase
import com.devil.phoenixproject.domain.model.RepType

/**
 * Handles rep counting based on notifications emitted by the Vitruvian machine.
 *
 * REP COUNTING APPROACH (Matches Official App):
 * - warmupReps = repsRomCount (directly from machine)
 * - workingReps = down - repsRomCount (down counter minus warmup count)
 *
 * This works for ALL exercises including single-side because the down counter
 * always increments regardless of which cable is used.
 *
 * Visual feedback timing uses directional counters (up/down):
 * - At TOP (concentric peak): Show PENDING rep (grey number, +1 preview)
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

    // Pending rep state - true when at TOP, waiting for machine confirm
    private var hasPendingRep = false
    private var pendingRepProgress = 0f  // 0.0 at TOP, 1.0 at BOTTOM (legacy, kept for compatibility)

    // Issue #163: Phase tracking for animated rep counter
    private var activePhase: RepPhase = RepPhase.IDLE
    private var phaseProgress: Float = 0f  // 0.0 at phase start, 1.0 at phase end
    private var lastTrackedPosition: Float = 0f  // For direction detection
    private var phaseStartPosition: Float = 0f  // Position when phase started
    private var phasePeakPosition: Float = 0f   // Peak position during current rep (for eccentric progress)
    private var positionHistoryForDirection = mutableListOf<Float>()  // Rolling window for smoothing
    private val DIRECTION_WINDOW_SIZE = 3
    private val DIRECTION_THRESHOLD_MM = 5f  // Minimum movement to detect direction change (mm)

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

    // Last rep's raw positions (for ghost indicators) - captured before averaging
    private var lastRepTopA: Float? = null
    private var lastRepTopB: Float? = null
    private var lastRepBottomA: Float? = null
    private var lastRepBottomB: Float? = null

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
        // Issue #163: Reset phase tracking
        activePhase = RepPhase.IDLE
        phaseProgress = 0f
        lastTrackedPosition = 0f
        phaseStartPosition = 0f
        phasePeakPosition = 0f
        positionHistoryForDirection.clear()
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
        // Clear last rep ghost positions
        lastRepTopA = null
        lastRepTopB = null
        lastRepBottomA = null
        lastRepBottomB = null
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
        // Issue #163: Reset phase tracking (but keep position history for direction detection)
        activePhase = RepPhase.IDLE
        phaseProgress = 0f
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
     * MODERN rep counting - matches official app approach.
     *
     * REP COUNTING (Issue #187 fix - matches official app Yj/p.java):
     * - warmupReps = repsRomCount (directly from machine)
     * - workingReps calculation depends on stopAtTop setting:
     *   - stopAtTop=false: down - warmupTarget (rep confirmed at BOTTOM)
     *   - stopAtTop=true: up - warmupTarget (rep confirmed at TOP)
     * - SAFETY NET: If machine's repsSetCount > our calculated workingReps, trust the machine
     *
     * The official app uses repsRomTotal (the configured target) not repsRomCount (the live counter).
     * Using warmupTarget (our equivalent of repsRomTotal) avoids timing issues between counter updates.
     *
     * The repsSetCount fallback ensures we capture the final rep even if the machine deloads
     * before we receive the final down counter increment.
     *
     * VISUAL FEEDBACK:
     * - UP counter: triggers PENDING (grey) preview at top of rep (stopAtTop=false only)
     * - DOWN counter: confirms rep (colored) at bottom (stopAtTop=false only)
     * - For stopAtTop=true: rep confirmed at TOP (no pending state needed)
     */
    private fun processModern(repsRomCount: Int, repsSetCount: Int, up: Int, down: Int, posA: Float, posB: Float) {
        var upDelta = 0
        var downDelta = 0

        // Track UP movement
        if (lastTopCounter != null) {
            upDelta = calculateDelta(lastTopCounter!!, up)
            if (upDelta > 0) {
                recordTopPosition(posA, posB)

                // Warmup is complete when: no warmup configured (warmupTarget=0) OR warmup reps done (repsRomCount >= warmupTarget)
                val warmupComplete = warmupTarget == 0 || repsRomCount >= warmupTarget

                if (stopAtTop && warmupComplete) {
                    // stopAtTop=true: Rep is CONFIRMED at TOP (no pending state)
                    // Use up - warmupTarget since machine releases weight at TOP
                    val calculatedWorking = maxOf(0, up - warmupTarget)
                    if (calculatedWorking > workingReps) {
                        // Emit WARMUP_COMPLETE for warmupTarget=0 case
                        if (warmupTarget == 0 && workingReps == 0 && calculatedWorking > 0) {
                            logDebug("🎯 Warmup complete (stopAtTop) - no warmup configured, starting working reps")
                            onRepEvent?.invoke(
                                RepEvent(
                                    type = RepType.WARMUP_COMPLETE,
                                    warmupCount = 0,
                                    workingCount = 0
                                )
                            )
                        }

                        workingReps = calculatedWorking
                        hasPendingRep = false
                        activePhase = RepPhase.IDLE
                        phaseProgress = 0f

                        logDebug("💪 WORKING_COMPLETED (stopAtTop): rep $workingReps confirmed at TOP (up=$up - warmupTarget=$warmupTarget)")

                        onRepEvent?.invoke(
                            RepEvent(
                                type = RepType.WORKING_COMPLETED,
                                warmupCount = warmupReps,
                                workingCount = workingReps
                            )
                        )

                        // Check if target reached (unless AMRAP or Just Lift)
                        if (!isJustLift && !isAMRAP && workingTarget > 0 && workingReps >= workingTarget) {
                            logDebug("⚠️ shouldStop set to TRUE (stopAtTop target reached)")
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
                } else if (!stopAtTop && warmupComplete && !hasPendingRep) {
                    // stopAtTop=false: Show PENDING (grey) at TOP, confirm at BOTTOM
                    // Use warmupTarget (constant) instead of repsRomCount (live counter) for stability
                    val calculatedWorking = maxOf(0, down - warmupTarget)
                    hasPendingRep = true
                    pendingRepProgress = 0f
                    logDebug("📈 TOP - WORKING_PENDING: showing grey rep ${calculatedWorking + 1}")

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

        // Track DOWN movement - record position at BOTTOM
        if (lastCompleteCounter != null) {
            downDelta = calculateDelta(lastCompleteCounter!!, down)
            if (downDelta > 0) {
                recordBottomPosition(posA, posB)
                logDebug("📉 BOTTOM reached - down=$down, repsRomCount=$repsRomCount")
            }
        }

        // Update tracking counters AFTER position recording
        lastTopCounter = up
        lastCompleteCounter = down

        // WARMUP TRACKING: Use repsRomCount directly from machine
        if (repsRomCount > warmupReps) {
            val oldWarmup = warmupReps
            warmupReps = repsRomCount

            logDebug("🔥 Warmup: $oldWarmup -> $warmupReps (from repsRomCount)")

            onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WARMUP_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps
                )
            )

            // Emit WARMUP_COMPLETE when reaching warmup target (not one rep late!)
            if (warmupTarget > 0 && warmupReps >= warmupTarget && oldWarmup < warmupTarget) {
                logDebug("🎯 Warmup complete - reached target $warmupTarget")
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = 0
                    )
                )
            }
        }

        // WORKING REP TRACKING (stopAtTop=false only - stopAtTop=true handled above)
        // Issue #187 fix: Use warmupTarget (constant) instead of repsRomCount (live counter)
        // This matches official app formula: working = down - repsRomTotal
        if (!stopAtTop) {
            // Primary formula: down - warmupTarget (using constant target for stability)
            val calculatedWorking = maxOf(0, down - warmupTarget)

            // Safety net: Also check machine's repsSetCount (Issue #187)
            // The machine knows the correct rep count even if we miss the final notification
            // Only use this fallback after warmup is complete (repsRomCount >= warmupTarget)
            val machineWorking = if (repsRomCount >= warmupTarget) repsSetCount else 0

            // Use the higher of calculated or machine-reported (catches final rep if down doesn't increment)
            val effectiveWorking = maxOf(calculatedWorking, machineWorking)

            if (effectiveWorking > workingReps) {
                // Emit WARMUP_COMPLETE for warmupTarget=0 case (no warmup configured)
                if (warmupTarget == 0 && workingReps == 0 && effectiveWorking > 0) {
                    logDebug("🎯 Warmup complete - no warmup configured, starting working reps")
                    onRepEvent?.invoke(
                        RepEvent(
                            type = RepType.WARMUP_COMPLETE,
                            warmupCount = 0,
                            workingCount = 0
                        )
                    )
                }

                val usedMachineFallback = machineWorking > calculatedWorking
                workingReps = effectiveWorking

                // Clear pending state when rep is confirmed
                hasPendingRep = false
                activePhase = RepPhase.IDLE
                phaseProgress = 0f

                if (usedMachineFallback) {
                    logDebug("💪 WORKING_COMPLETED: rep $workingReps from machine repsSetCount (fallback)")
                } else {
                    logDebug("💪 WORKING_COMPLETED: rep $workingReps confirmed (down=$down - warmupTarget=$warmupTarget)")
                }

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
            // Capture raw value for ghost indicator before adding to averaging list
            lastRepTopA = posA
            topPositionsA.add(posA)
            if (topPositionsA.size > window) topPositionsA.removeAt(0)
        }
        if (posB > 0f) {
            // Capture raw value for ghost indicator before adding to averaging list
            lastRepTopB = posB
            topPositionsB.add(posB)
            if (topPositionsB.size > window) topPositionsB.removeAt(0)
        }

        updateRepRanges()
    }

    private fun recordBottomPosition(posA: Float, posB: Float) {
        if (posA <= 0f && posB <= 0f) return

        val window = getWindowSize()
        if (posA > 0f) {
            // Capture raw value for ghost indicator before adding to averaging list
            lastRepBottomA = posA
            bottomPositionsA.add(posA)
            if (bottomPositionsA.size > window) bottomPositionsA.removeAt(0)
        }
        if (posB > 0f) {
            // Capture raw value for ghost indicator before adding to averaging list
            lastRepBottomB = posB
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
            pendingRepProgress = pendingRepProgress,
            // Issue #163: Include phase tracking for animated counter
            activeRepPhase = activePhase,
            phaseProgress = phaseProgress
        )
    }

    /**
     * Issue #163: Update phase and progress from continuous position data.
     *
     * Called from handleMonitorMetric() with current cable positions.
     * Detects movement direction to determine CONCENTRIC vs ECCENTRIC phase,
     * and calculates progress within the current phase for animation.
     *
     * @param posA Current position of cable A in mm
     * @param posB Current position of cable B in mm
     */
    fun updatePhaseFromPosition(posA: Float, posB: Float) {
        // Use the max of both positions for direction detection (handles single-cable exercises)
        val currentPos = maxOf(posA, posB)
        if (currentPos <= 0f) return

        // Only track phase after warmup is complete (during working reps)
        if (warmupReps < warmupTarget) {
            activePhase = RepPhase.IDLE
            phaseProgress = 0f
            return
        }

        // Add to position history for smoothed direction detection
        positionHistoryForDirection.add(currentPos)
        if (positionHistoryForDirection.size > DIRECTION_WINDOW_SIZE) {
            positionHistoryForDirection.removeAt(0)
        }

        // Need at least 2 positions to detect direction
        if (positionHistoryForDirection.size < 2) {
            lastTrackedPosition = currentPos
            return
        }

        // Calculate smoothed direction from position history
        val oldestPos = positionHistoryForDirection.first()
        val newestPos = positionHistoryForDirection.last()
        val positionDelta = newestPos - oldestPos

        // Determine phase from direction
        val newPhase = when {
            positionDelta > DIRECTION_THRESHOLD_MM -> RepPhase.CONCENTRIC  // Moving up
            positionDelta < -DIRECTION_THRESHOLD_MM -> RepPhase.ECCENTRIC  // Moving down
            else -> activePhase  // No significant movement, keep current phase
        }

        // Handle phase transitions
        if (newPhase != activePhase && newPhase != RepPhase.IDLE) {
            when (newPhase) {
                RepPhase.CONCENTRIC -> {
                    // Starting concentric - record start position (bottom)
                    phaseStartPosition = currentPos
                    phasePeakPosition = currentPos
                }
                RepPhase.ECCENTRIC -> {
                    // Starting eccentric - use current position as peak, keep start from concentric
                    phasePeakPosition = currentPos
                }
                RepPhase.IDLE -> { /* No action needed */ }
            }
            activePhase = newPhase
        }

        // Calculate progress within phase (0.0 to 1.0)
        when (activePhase) {
            RepPhase.CONCENTRIC -> {
                // Track peak position as we go up
                if (currentPos > phasePeakPosition) {
                    phasePeakPosition = currentPos
                }
                // Progress: 0.0 at bottom, 1.0 at top
                val range = phasePeakPosition - phaseStartPosition
                phaseProgress = if (range > 10f) {
                    ((currentPos - phaseStartPosition) / range).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            RepPhase.ECCENTRIC -> {
                // Progress: 0.0 at top, 1.0 at bottom
                val minPos = minRepPosA ?: minRepPosB ?: phaseStartPosition
                val range = phasePeakPosition - minPos
                phaseProgress = if (range > 10f) {
                    ((phasePeakPosition - currentPos) / range).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            RepPhase.IDLE -> {
                phaseProgress = 0f
            }
        }

        lastTrackedPosition = currentPos
    }

    fun shouldStopWorkout(): Boolean = shouldStop

    fun getRepRanges(): RepRanges = RepRanges(
        minPosA = minRepPosA,
        maxPosA = maxRepPosA,
        minPosB = minRepPosB,
        maxPosB = maxRepPosB,
        minRangeA = minRepPosARange,
        maxRangeA = maxRepPosARange,
        minRangeB = minRepPosBRange,
        maxRangeB = maxRepPosBRange,
        // Last rep's raw positions for ghost indicators
        lastRepTopA = lastRepTopA,
        lastRepTopB = lastRepTopB,
        lastRepBottomA = lastRepBottomA,
        lastRepBottomB = lastRepBottomB
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
        // The rangeA > minRangeThreshold check already ensures only active cables are checked -
        // inactive cables at ~0 won't build meaningful range (see updatePositionRangesContinuously)
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
 *
 * Includes:
 * - ROM boundaries (min/max averaged positions)
 * - Last rep's raw positions (for ghost indicators)
 * - Helper function to check danger zone status
 */
data class RepRanges(
    val minPosA: Float?,
    val maxPosA: Float?,
    val minPosB: Float?,
    val maxPosB: Float?,
    val minRangeA: Pair<Float, Float>?,
    val maxRangeA: Pair<Float, Float>?,
    val minRangeB: Pair<Float, Float>?,
    val maxRangeB: Pair<Float, Float>?,
    // Last rep's raw positions for ghost indicators
    val lastRepTopA: Float? = null,
    val lastRepTopB: Float? = null,
    val lastRepBottomA: Float? = null,
    val lastRepBottomB: Float? = null
) {
    /**
     * Check if cable A is active (has built meaningful range of motion).
     * Used to determine whether to show cable position indicators.
     */
    fun isCableAActive(minRangeThreshold: Float = 50f): Boolean {
        if (minPosA == null || maxPosA == null) return false
        return (maxPosA - minPosA) > minRangeThreshold
    }

    /**
     * Check if cable B is active (has built meaningful range of motion).
     * Used to determine whether to show cable position indicators.
     */
    fun isCableBActive(minRangeThreshold: Float = 50f): Boolean {
        if (minPosB == null || maxPosB == null) return false
        return (maxPosB - minPosB) > minRangeThreshold
    }

    /**
     * Check if position is in danger zone (within 5% of ROM minimum).
     * Used to trigger red color warning on position bars.
     *
     * @param posA Current position A in mm
     * @param posB Current position B in mm
     * @param minRangeThreshold Minimum ROM range required to activate danger zone check
     * @return true if either cable with meaningful range is in danger zone
     */
    fun isInDangerZone(posA: Float, posB: Float, minRangeThreshold: Float = 50f): Boolean {
        // Check if position A is in danger zone (within 5% of minimum)
        // The range > minRangeThreshold check already ensures only active cables are checked -
        // inactive cables at ~0 won't build meaningful range
        if (minPosA != null && maxPosA != null) {
            val range = maxPosA - minPosA
            if (range > minRangeThreshold) {
                val threshold = minPosA + (range * 0.05f)
                if (posA <= threshold) return true
            }
        }

        // Check if position B is in danger zone (within 5% of minimum)
        if (minPosB != null && maxPosB != null) {
            val range = maxPosB - minPosB
            if (range > minRangeThreshold) {
                val threshold = minPosB + (range * 0.05f)
                if (posB <= threshold) return true
            }
        }

        return false
    }
}

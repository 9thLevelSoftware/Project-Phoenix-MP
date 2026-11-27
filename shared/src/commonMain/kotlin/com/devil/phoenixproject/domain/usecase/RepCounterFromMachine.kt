package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepPhase
import com.devil.phoenixproject.domain.model.RepType
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Handles rep counting based on notifications emitted by the Phoenix machine.
 *
 * Modern packets take warmup and working counts from the machine's ROM and set
 * counters:
 * - Warmup steps one integer at a time up to `min(repsRomCount, warmupTarget)`.
 *   When `repsRomCount` and `repsSetCount` are both 0, those steps come from the
 *   up-counter delta instead, still capped at `warmupTarget`.
 * - Working reps become `repsSetCount` when that count is higher than the local
 *   count. A lower or equal machine count leaves the local count unchanged. If
 *   working reps arrive before warmup reaches `warmupTarget`, warmup is forced
 *   complete. [getRepCount] reports `totalReps` as this working count and
 *   excludes warmup reps.
 * - On a modern packet, a positive `repsRomTotal` replaces a positive configured
 *   `warmupTarget` when they differ. `repsSetTotal` is logged and leaves
 *   `workingTarget` unchanged.
 * - After warmup, an up increment shows a pending grey preview and a down
 *   increment clears it. `stopAtTop` can complete the final working rep on that
 *   preview. Otherwise the colored working count updates from `repsSetCount`.
 *
 * Legacy packets count from up-counter increments. The first `warmupTarget`
 * increments are warmup reps; later increments are working reps. The down
 * counter records the bottom position.
 */
class RepCounterFromMachine {

    private val log = Logger.withTag("RepCounterFromMachine")

    // Lock discipline (strictly non-nested leaf locks — NEVER nest any pair of locks and NEVER
    // hold any lock while invoking onRepEvent):
    // - countLock: rep counts, targets, mode flags, pending/carryover flags, directional baselines
    // - phaseLock: animated rep phase, progress, and direction-smoothing window
    // - rangeLock: ROM averaging position lists, min/max bounds, and ghost last-rep positions
    private val countLock = reentrantLock()
    private var warmupReps = 0
    private var workingReps = 0
    private var warmupTarget = 3
    private var workingTarget = 0
    private var isJustLift = false
    private var stopAtTop = false
    @Volatile
    private var shouldStop = false
    private var isAMRAP = false

    // Issue #531: one-shot guard for the directional-counter carryover heuristic. The machine's
    // up/down counters are free-running and can carry over from the prior set; on the first modern
    // packet we detect and absorb stale values once so they don't chirp a phantom warmup rep.
    private var carryoverChecked = false

    // Pending rep state - true when at TOP, waiting for machine confirm
    private var hasPendingRep = false

    // Issue #163: Phase tracking for animated rep counter (guarded by phaseLock for concurrent
    // updatePhaseFromPosition calls from handleMonitorMetric and handleRepNotification)
    private val phaseLock = reentrantLock()
    private var activePhase: RepPhase = RepPhase.IDLE
    private var phaseProgress: Float = 0f // 0.0 at phase start, 1.0 at phase end
    private var phaseStartPosition: Float = 0f // Position when phase started
    private var phasePeakPosition: Float = 0f // Peak position during current rep (for eccentric progress)
    private var positionHistoryForDirection = mutableListOf<Float>() // Rolling window for smoothing
    private val DIRECTION_WINDOW_SIZE = 3
    private val DIRECTION_THRESHOLD_MM = 50f // Minimum movement to detect direction change (mm)

    // Track directional counters for position calibration AND visual feedback
    // Issue #210 FIX: Initialize to 0 instead of null so first rep notification
    // calculates delta = (first_count - 0) = first_count, not skipped for baseline
    private var lastTopCounter: Int = 0
    private var lastCompleteCounter: Int = 0

    // Leaf lock for ROM boundaries, sliding-window position lists, and ghost positions
    private val rangeLock = reentrantLock()

    // Position tracking lists - now in mm (Float)
    private val topPositionsA = mutableListOf<Float>()
    private val topPositionsB = mutableListOf<Float>()
    private val bottomPositionsA = mutableListOf<Float>()
    private val bottomPositionsB = mutableListOf<Float>()

    // ROM boundaries in mm (sliding-window averages)
    private var maxRepPosA: Float? = null
    private var minRepPosA: Float? = null
    private var maxRepPosB: Float? = null
    private var minRepPosB: Float? = null

    // Last rep's raw positions (for ghost indicators) - captured before averaging
    private var lastRepTopA: Float? = null
    private var lastRepTopB: Float? = null
    private var lastRepBottomA: Float? = null
    private var lastRepBottomB: Float? = null

    @Volatile
    var onRepEvent: ((RepEvent) -> Unit)? = null

    fun configure(
        warmupTarget: Int,
        workingTarget: Int,
        isJustLift: Boolean,
        stopAtTop: Boolean,
        isAMRAP: Boolean = false,
    ) {
        countLock.withLock {
            this.warmupTarget = warmupTarget
            this.workingTarget = workingTarget
            this.isJustLift = isJustLift
            this.stopAtTop = stopAtTop
            this.isAMRAP = isAMRAP
        }

        // Log RepCounter configuration
        logDebug("RepCounter.configure() called:")
        logDebug("  warmupTarget: $warmupTarget")
        logDebug("  workingTarget: $workingTarget")
        logDebug("  isJustLift: $isJustLift")
        logDebug("  stopAtTop: $stopAtTop")
        logDebug("  isAMRAP: $isAMRAP")
    }

    fun reset() {
        countLock.withLock {
            warmupReps = 0
            workingReps = 0
            shouldStop = false
            carryoverChecked = false
            hasPendingRep = false
            lastTopCounter = 0
            lastCompleteCounter = 0
        }
        // Issue #163: Reset phase tracking
        phaseLock.withLock {
            activePhase = RepPhase.IDLE
            phaseProgress = 0f
            phaseStartPosition = 0f
            phasePeakPosition = 0f
            positionHistoryForDirection.clear()
        }
        rangeLock.withLock {
            topPositionsA.clear()
            topPositionsB.clear()
            bottomPositionsA.clear()
            bottomPositionsB.clear()
            maxRepPosA = null
            minRepPosA = null
            maxRepPosB = null
            minRepPosB = null
            // Clear last rep ghost positions
            lastRepTopA = null
            lastRepTopB = null
            lastRepBottomA = null
            lastRepBottomB = null
        }
    }

    /**
     * Resets rep counts but PRESERVES position ranges.
     *
     * This is critical for Just Lift mode where we track positions continuously during
     * the handle detection phase (before workout starts). A full reset() would wipe out
     * the position ranges we built up, making hasMeaningfulRange() return false.
     */
    fun resetCountsOnly() {
        countLock.withLock {
            warmupReps = 0
            workingReps = 0
            shouldStop = false
            carryoverChecked = false
            hasPendingRep = false
            lastTopCounter = 0
            lastCompleteCounter = 0
        }
        // Issue #163: Reset phase tracking (but keep position history for direction detection)
        phaseLock.withLock {
            activePhase = RepPhase.IDLE
            phaseProgress = 0f
        }
        // NOTE: Do NOT clear position tracking lists or min/max ranges!
        // This preserves hasMeaningfulRange() for auto-stop detection
    }

    /**
     * Records carried legacy directional counters without producing a rep.
     *
     * The execution freshness gate calls this for the first post-cutover legacy packet. The next
     * accepted packet is then interpreted as a delta from this execution's baseline instead of a
     * delta from zero.
     */
    internal fun establishLegacyCounterBaseline(topCounter: Int, completeCounter: Int) {
        countLock.withLock {
            lastTopCounter = topCounter
            lastCompleteCounter = completeCounter
        }
    }

    /**
     * Sets the initial baseline position when the workout starts (after countdown completes).
     * This calibrates the position bars to the starting rope position, so bars show 0% at
     * the starting position rather than showing raw machine values.
     *
     * The baseline will be refined as reps are performed through the sliding window calibration.
     */
    fun setInitialBaseline(posA: Float, posB: Float) {
        if (posA <= 0f && posB <= 0f) return
        rangeLock.withLock {
            // Only set initial baseline if positions are valid and not already calibrated
            if (posA > 0f && minRepPosA == null) {
                minRepPosA = posA
            }
            if (posB > 0f && minRepPosB == null) {
                minRepPosB = posB
            }
        }
    }

    /**
     * Seed ROM boundaries from machine-provided range values.
     * Only seeds if not already calibrated from actual reps.
     * Called when the first rep notification arrives with valid range data.
     */
    fun seedRomBoundaries(rangeTop: Float, rangeBottom: Float) {
        if (rangeTop <= 0f || rangeBottom < 0f) return
        if (rangeTop == 3000.0f && rangeBottom == 0.0f) return // Default/unset values (3 m rope in mm)

        rangeLock.withLock {
            // Only seed if not already calibrated from actual reps
            if (maxRepPosA == null && topPositionsA.isEmpty()) {
                maxRepPosA = rangeTop
            }
            if (maxRepPosB == null && topPositionsB.isEmpty()) {
                maxRepPosB = rangeTop
            }
            if (minRepPosA == null && bottomPositionsA.isEmpty()) {
                minRepPosA = rangeBottom
            }
            if (minRepPosB == null && bottomPositionsB.isEmpty()) {
                minRepPosB = rangeBottom
            }
        }
        logDebug("ROM seeded from machine: top=${rangeTop}mm, bottom=${rangeBottom}mm")
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

        rangeLock.withLock {
            // Track minimum positions (cable at rest / bottom of movement)
            if (posA > 0f) {
                val curMinA = minRepPosA
                if (curMinA == null || posA < curMinA) {
                    minRepPosA = posA
                }
                // Track maximum positions (cable extended / top of movement)
                val curMaxA = maxRepPosA
                if (curMaxA == null || posA > curMaxA) {
                    maxRepPosA = posA
                }
            }

            if (posB > 0f) {
                val curMinB = minRepPosB
                if (curMinB == null || posB < curMinB) {
                    minRepPosB = posB
                }
                val curMaxB = maxRepPosB
                if (curMaxB == null || posB > curMaxB) {
                    maxRepPosB = posB
                }
            }
        }
    }

    /**
     * Process rep data from machine with visual feedback timing.
     *
     * Supports TWO modes (Issue #187):
     *
     * MODERN MODE (isLegacyFormat=false):
     * - Warmup steps toward `repsRomCount`, capped at `warmupTarget`. When both
     *   `repsRomCount` and `repsSetCount` stay 0, warmup steps from the up counter.
     * - Working reps become `repsSetCount` when that count is higher. [getRepCount]
     *   reports `totalReps` as the working count and excludes warmup.
     * - A positive `repsRomTotal` replaces a positive configured `warmupTarget`
     *   when they differ. `repsSetTotal` is logged and leaves `workingTarget` unchanged.
     * - up/down time the pending preview. `stopAtTop` can complete the final
     *   working rep on the up preview.
     *
     * LEGACY MODE (isLegacyFormat=true, Beta 4 compatible):
     * - Uses topCounter (up) increments to count reps directly
     * - This is the method that worked in Beta 4 and handles Samsung devices
     *
     * @param repsRomCount Machine's ROM rep count (warmup reps) - 0 for legacy
     * @param repsRomTotal Machine warmup target (bytes 18-19). On a modern packet, a
     *   positive value replaces a positive configured warmupTarget when they differ.
     * @param repsSetCount Machine's set rep count (working reps) - 0 for legacy
     * @param repsSetTotal Machine working target (bytes 22-23). Logged only;
     *   workingTarget is not updated from it.
     * @param up Directional counter - increments at TOP (concentric peak)
     * @param down Directional counter - increments at BOTTOM (eccentric valley)
     * @param posA Position A for range calibration
     * @param posB Position B for range calibration
     * @param isLegacyFormat True if using the 6..23 effective-byte legacy packet format (Issue #187)
     */
    private class PendingProcessEffects(
        val topWindow: Int? = null,
        val bottomWindow: Int? = null,
        val resetPhase: Boolean = false,
        val events: List<RepEvent> = emptyList(),
    )

    fun process(
        repsRomCount: Int,
        repsRomTotal: Int = 0, // Issue #210: Machine's warmup target
        repsSetCount: Int,
        repsSetTotal: Int = 0, // Issue #210: Machine's working target
        up: Int = 0,
        down: Int = 0,
        posA: Float = 0f,
        posB: Float = 0f,
        isLegacyFormat: Boolean = false,
    ) {
        val effects = countLock.withLock {
            // Issue #210: Sync warmupTarget with machine's repsRomTotal if provided and different
            // This ensures our warmup tracking matches the machine's expectation
            if (!isLegacyFormat && warmupTarget > 0 && repsRomTotal > 0 && repsRomTotal != warmupTarget) {
                logDebug("⚠️ WARMUP SYNC: Machine's repsRomTotal=$repsRomTotal differs from our warmupTarget=$warmupTarget - syncing")
                warmupTarget = repsRomTotal
            }

            // DIAGNOSTIC: Log full state for debugging rep counting issues
            val warmupGateOpen = warmupReps >= warmupTarget
            logDebug("Rep process: ROM=$repsRomCount/$repsRomTotal, Set=$repsSetCount/$repsSetTotal, up=$up, down=$down, pending=$hasPendingRep, legacy=$isLegacyFormat")
            logDebug("  Warmup gate: warmupReps=$warmupReps, warmupTarget=$warmupTarget, gate=${if (warmupGateOpen) "OPEN" else "BLOCKED"}")
            logDebug("  Working: workingReps=$workingReps, workingTarget=$workingTarget")

            if (isLegacyFormat) {
                // LEGACY MODE: Count reps based on topCounter increments (Beta 4 method)
                // This is the proven method that works with Samsung devices and older firmware
                processLegacyLocked(up, down)
            } else {
                // MODERN MODE: Use machine-provided repsRomCount/repsSetCount
                processModernLocked(repsRomCount, repsSetCount, up, down)
            }
        }

        effects.topWindow?.let { recordTopPosition(posA, posB, it) }
        effects.bottomWindow?.let { recordBottomPosition(posA, posB, it) }
        if (effects.resetPhase) {
            phaseLock.withLock {
                activePhase = RepPhase.IDLE
                phaseProgress = 0f
            }
        }
        val callback = onRepEvent
        if (callback != null) {
            effects.events.forEach { callback(it) }
        }
    }

    /**
     * LEGACY rep counting (Beta 4 method) - counts reps when topCounter increments.
     * Used when machine sends 6..23 effective-byte packets without repsRomCount/repsSetCount fields.
     * Must be called with [countLock] held.
     */
    private fun processLegacyLocked(up: Int, down: Int): PendingProcessEffects {
        val events = mutableListOf<RepEvent>()
        var topWindow: Int? = null
        var bottomWindow: Int? = null

        val topDelta = calculateDelta(lastTopCounter, up)
        if (topDelta > 0) {
            topWindow = getWindowSizeLocked()

            repeat(topDelta) {
                val totalReps = warmupReps + workingReps + 1
                if (totalReps <= warmupTarget) {
                    warmupReps++
                    logDebug("📈 LEGACY: Warmup rep $warmupReps (top counter increment)")
                    events.add(
                        RepEvent(
                            type = RepType.WARMUP_COMPLETED,
                            warmupCount = warmupReps,
                            workingCount = workingReps,
                        ),
                    )
                    if (warmupReps == warmupTarget) {
                        events.add(
                            RepEvent(
                                type = RepType.WARMUP_COMPLETE,
                                warmupCount = warmupReps,
                                workingCount = workingReps,
                            ),
                        )
                    }
                } else {
                    workingReps++
                    logDebug("💪 LEGACY: Working rep $workingReps (top counter increment)")
                    events.add(
                        RepEvent(
                            type = RepType.WORKING_COMPLETED,
                            warmupCount = warmupReps,
                            workingCount = workingReps,
                        ),
                    )

                    if (!isJustLift && !isAMRAP && workingTarget > 0 && workingReps >= workingTarget) {
                        logDebug("⚠️ LEGACY: shouldStop set to TRUE (target reached)")
                        shouldStop = true
                        events.add(
                            RepEvent(
                                type = RepType.WORKOUT_COMPLETE,
                                warmupCount = warmupReps,
                                workingCount = workingReps,
                            ),
                        )
                    }
                }
            }
        }

        val downDelta = calculateDelta(lastCompleteCounter, down)
        if (downDelta > 0) {
            bottomWindow = getWindowSizeLocked()
        }

        lastTopCounter = up
        lastCompleteCounter = down
        return PendingProcessEffects(
            topWindow = topWindow,
            bottomWindow = bottomWindow,
            events = events,
        )
    }

    /**
     * MODERN rep counting trusts the machine's ROM and set counters.
     *
     * The machine distinguishes warmup from working:
     * - warmupReps steps to `min(repsRomCount, warmupTarget)`, one event per integer.
     *   When `repsRomCount` and `repsSetCount` are both 0, the same step comes from
     *   the up-counter delta, still capped at `warmupTarget`.
     * - workingReps becomes `repsSetCount` when `repsSetCount` is greater. The machine
     *   increments that field for working reps only. If it arrives before warmup is
     *   complete, warmup is forced to `warmupTarget`.
     *
     * VISUAL FEEDBACK (the working total comes from `repsSetCount`):
     * - UP counter: pending (grey) preview at the top, after warmup. `stopAtTop` can
     *   treat that preview as the final working rep.
     * - DOWN counter: clears the pending preview at the bottom.
     *
     * Must be called with [countLock] held.
     */
    private fun processModernLocked(repsRomCount: Int, repsSetCount: Int, up: Int, down: Int): PendingProcessEffects {
        // Issue #531 (symptom 1, heuristic): suppress a phantom warmup chirp caused by the
        // machine's free-running directional counters carrying over from the prior set. On the
        // first modern packet of a set, if BOTH up and down are already past the warmup target
        // while the machine reports zero ROM/set reps, treat them as stale and re-baseline once so
        // the first real movement yields delta ~0. Deliberately narrow to preserve:
        //   - Issue #210: a real first rep arrives as repsRomCount=1 -> fails repsRomCount==0.
        //   - Issue #553: requires BOTH up AND down past the target. A completed prior set leaves
        //     both directional counters elevated (up ~= down), so real carryover is caught. But an
        //     Echo set whose first packet is top-only (up advanced because earlier notifications
        //     dropped, down still at baseline = genuine setup reps, not carryover) keeps down at 0
        //     -> fails down>warmupTarget, so the fallback branch counts those reps instead of this
        //     guard silently dropping them. (Codex PR #596 review.)
        //   - Issue #411: variable-warmup sets use warmupTarget=0 -> fails warmupTarget>0.
        if (!carryoverChecked) {
            carryoverChecked = true
            if (warmupTarget > 0 && warmupReps == 0 &&
                repsRomCount == 0 && repsSetCount == 0 &&
                up > warmupTarget && down > warmupTarget
            ) {
                logDebug("🩹 Issue #531: directional carryover (up=$up, down=$down) — re-baselining, suppressing phantom warmup")
                lastTopCounter = up
                lastCompleteCounter = down
                return PendingProcessEffects()
            }
        }

        val events = mutableListOf<RepEvent>()
        var topWindow: Int? = null
        var bottomWindow: Int? = null
        var resetPhase = false

        // Track UP movement - for working reps, show PENDING (grey) at TOP
        // Issue #210 FIX: No null check needed - lastTopCounter initialized to 0
        val upDelta = calculateDelta(lastTopCounter, up)
        if (upDelta > 0) {
            topWindow = getWindowSizeLocked()

            // Only show pending for WORKING reps (after warmup complete)
            if (warmupReps >= warmupTarget && !hasPendingRep) {
                hasPendingRep = true
                logDebug("📈 TOP - WORKING_PENDING: showing grey rep ${workingReps + 1}")

                events.add(
                    RepEvent(
                        type = RepType.WORKING_PENDING,
                        warmupCount = warmupReps,
                        workingCount = workingReps, // Still the old count, pending shows +1
                    ),
                )

                // stopAtTop mode: Complete the set at concentric peak on the final rep.
                // We treat the pending preview as the final rep confirmation in this mode.
                if (stopAtTop &&
                    !isJustLift &&
                    !isAMRAP &&
                    workingTarget > 0 &&
                    (workingReps + 1) >= workingTarget
                ) {
                    workingReps = maxOf(workingReps, workingTarget)
                    hasPendingRep = false
                    resetPhase = true

                    if (!shouldStop) {
                        logDebug("🛑 STOP_AT_TOP: target reached at TOP, completing set")
                        shouldStop = true
                        events.add(
                            RepEvent(
                                type = RepType.WORKOUT_COMPLETE,
                                warmupCount = warmupReps,
                                workingCount = workingReps,
                            ),
                        )
                    }
                }
            }
        }

        // Track DOWN movement - for working reps, CONFIRM (colored) at BOTTOM
        // Issue #210 FIX: No null check needed - lastCompleteCounter initialized to 0
        val downDelta = calculateDelta(lastCompleteCounter, down)
        if (downDelta > 0) {
            bottomWindow = getWindowSizeLocked()

            // Clear pending state when we reach bottom
            if (hasPendingRep) {
                hasPendingRep = false
                logDebug("📉 BOTTOM - pending cleared, waiting for machine confirm")
            }
        }

        // Update tracking counters AFTER position recording
        lastTopCounter = up
        lastCompleteCounter = down

        // WARMUP TRACKING: Use repsRomCount directly from machine (no pending animation).
        // Issue #531: emit one WARMUP_COMPLETED per integer step so a batched/dropped ROM
        // notification (count jumps 0 -> 2 in a single packet) doesn't drop setup-rep chirps.
        if (repsRomCount > warmupReps && warmupReps < warmupTarget) {
            val warmupRepTarget = repsRomCount.coerceAtMost(warmupTarget)
            while (warmupReps < warmupRepTarget) {
                warmupReps++
                events.add(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETED,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }

            if (warmupReps >= warmupTarget) {
                events.add(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }
        // FALLBACK WARMUP: When machine doesn't report repsRomCount (0xFF/unlimited mode),
        // count warmup reps from directional up counter (matches processLegacy approach).
        // Issue #553: this is the path that fixes the Echo Mode "stuck on Warm Up 1/3"
        // regression — it is mode-agnostic, so when PR #474's HARD EchoLevel causes the
        // V-Form firmware to silently drop repsRomCount events, the up counter still
        // advances and this branch advances warmupReps from it. The Echo-specific
        // escape hatch (added in the first iteration of this fix) turned out to be
        // unreachable because this branch already fires first when repsRomCount==0
        // and repsSetCount==0 — see PR #554 review (gemini-code-assist) feedback.
        else if (repsSetCount == 0 && repsRomCount == 0 && warmupReps < warmupTarget && upDelta > 0) {
            // Issue #531: per-increment emission for the up-counter fallback too.
            val warmupRepTarget = (warmupReps + upDelta).coerceAtMost(warmupTarget)
            while (warmupReps < warmupRepTarget) {
                warmupReps++
                logDebug("📈 MODERN FALLBACK: Warmup rep $warmupReps (from up counter, repsRomCount=0)")
                events.add(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETED,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }

            if (warmupReps >= warmupTarget) {
                events.add(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }

        // WORKING REP TRACKING: Use repsSetCount directly from machine
        // The machine handles warmup/working distinction internally.
        // repsSetCount increments for WORKING reps only - trust the machine!
        if (repsSetCount > workingReps) {
            // If machine is reporting working reps but we haven't seen warmup complete,
            // force our warmup tracking to match (machine knows best)
            if (warmupReps < warmupTarget) {
                logDebug("Machine reports working reps (repsSetCount=$repsSetCount) - warmup must be complete")
                warmupReps = warmupTarget
                events.add(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
            workingReps = repsSetCount

            // Clear pending/phase state when rep confirmed
            hasPendingRep = false
            resetPhase = true

            logDebug("WORKING_COMPLETED: rep $workingReps confirmed at eccentric valley")

            events.add(
                RepEvent(
                    type = RepType.WORKING_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps,
                ),
            )

            // Check if target reached (unless AMRAP or Just Lift)
            if (!isJustLift && !isAMRAP && workingTarget > 0 && workingReps >= workingTarget) {
                logDebug("shouldStop set to TRUE (target reached)")
                logDebug("  workingTarget=$workingTarget, workingReps=$workingReps")
                shouldStop = true
                events.add(
                    RepEvent(
                        type = RepType.WORKOUT_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }

        return PendingProcessEffects(
            topWindow = topWindow,
            bottomWindow = bottomWindow,
            resetPhase = resetPhase,
            events = events,
        )
    }

    private fun calculateDelta(last: Int, current: Int): Int = if (current >= last) {
        (current - last).coerceAtMost(100_000)
    } else {
        // current < last. A genuine 16-bit counter wrap only happens at the
        // boundary (last near 0xFFFF, current near 0) and advances by a small
        // amount. Any other backward jump — a counter reset, reconnect, or stale
        // baseline such as last=500/current=0 — must be treated as a re-baseline
        // (delta 0), NOT the ~65000 delta the old code returned, which
        // repeat(topDelta) would turn into thousands of phantom reps and could
        // lock up the workout flow. Callers re-sync their baseline to `current`
        // after this returns, so 0 here cleanly re-bases (audit F030).
        val wrapHighThreshold = 0xFF00 // last must be within 255 of the 16-bit max
        val wrapLowThreshold = 0x00FF // current must be within 255 of zero
        val wrap = 0xFFFF - last + current + 1
        if (last >= wrapHighThreshold && current <= wrapLowThreshold && wrap in 1..1_000) {
            wrap
        } else {
            0
        }
    }

    private fun recordTopPosition(posA: Float, posB: Float, window: Int) {
        if (posA <= 0f && posB <= 0f) return

        rangeLock.withLock {
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

            updateRepRangesLocked()
        }
    }

    private fun recordBottomPosition(posA: Float, posB: Float, window: Int) {
        if (posA <= 0f && posB <= 0f) return

        rangeLock.withLock {
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

            updateRepRangesLocked()
        }
    }

    private fun updateRepRangesLocked() {
        if (topPositionsA.isNotEmpty()) {
            maxRepPosA = topPositionsA.average().toFloat()
        }
        if (bottomPositionsA.isNotEmpty()) {
            minRepPosA = bottomPositionsA.average().toFloat()
        }
        if (topPositionsB.isNotEmpty()) {
            maxRepPosB = topPositionsB.average().toFloat()
        }
        if (bottomPositionsB.isNotEmpty()) {
            minRepPosB = bottomPositionsB.average().toFloat()
        }
    }

    private fun getWindowSizeLocked(): Int {
        val total = warmupReps + workingReps
        return if (total < warmupTarget) 2 else 3
    }

    fun getRepCount(): RepCount {
        var warmup = 0
        var working = 0
        var target = 0
        var pending = false
        countLock.withLock {
            warmup = warmupReps
            working = workingReps
            target = warmupTarget
            pending = hasPendingRep
        }
        val (phase, progress) = phaseLock.withLock { activePhase to phaseProgress }
        return RepCount(
            warmupReps = warmup,
            workingReps = working,
            totalReps = working, // Exclude warm-up reps from total count
            isWarmupComplete = warmup >= target,
            hasPendingRep = pending,
            // Issue #163: Include phase tracking for animated counter
            activeRepPhase = phase,
            phaseProgress = progress,
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

        // Snapshot counter & ROM bounds via sequential non-nested leaf locks before acquiring phaseLock.
        val isWarmupComplete = countLock.withLock { warmupReps >= warmupTarget }
        if (!isWarmupComplete) {
            phaseLock.withLock {
                activePhase = RepPhase.IDLE
                phaseProgress = 0f
            }
            return
        }

        var minA: Float? = null
        var minB: Float? = null
        rangeLock.withLock {
            minA = minRepPosA
            minB = minRepPosB
        }

        phaseLock.withLock {
            // Add to position history for smoothed direction detection
            positionHistoryForDirection.add(currentPos)
            if (positionHistoryForDirection.size > DIRECTION_WINDOW_SIZE) {
                positionHistoryForDirection.removeAt(0)
            }

            // Need at least 2 positions to detect direction
            if (positionHistoryForDirection.size < 2) {
                return
            }

            // Calculate smoothed direction from position history
            val oldestPos = positionHistoryForDirection.first()
            val newestPos = positionHistoryForDirection.last()
            val positionDelta = newestPos - oldestPos

            // Determine phase from direction
            val newPhase = when {
                positionDelta > DIRECTION_THRESHOLD_MM -> RepPhase.CONCENTRIC

                // Moving up
                positionDelta < -DIRECTION_THRESHOLD_MM -> RepPhase.ECCENTRIC

                // Moving down
                else -> activePhase // No significant movement, keep current phase
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
                    phaseProgress = if (range > 100f) {
                        ((currentPos - phaseStartPosition) / range).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }

                RepPhase.ECCENTRIC -> {
                    // Progress: 0.0 at top, 1.0 at bottom
                    val minPos = minA ?: minB ?: phaseStartPosition
                    val range = phasePeakPosition - minPos
                    phaseProgress = if (range > 100f) {
                        ((phasePeakPosition - currentPos) / range).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }

                RepPhase.IDLE -> {
                    phaseProgress = 0f
                }
            }
        }
    }

    fun shouldStopWorkout(): Boolean = countLock.withLock { shouldStop }

    fun getRepRanges(): RepRanges = rangeLock.withLock {
        RepRanges(
            minPosA = minRepPosA,
            maxPosA = maxRepPosA,
            minPosB = minRepPosB,
            maxPosB = maxRepPosB,
            // Last rep's raw positions for ghost indicators
            lastRepTopA = lastRepTopA,
            lastRepTopB = lastRepTopB,
            lastRepBottomA = lastRepBottomA,
            lastRepBottomB = lastRepBottomB,
        )
    }

    fun hasMeaningfulRange(minRangeThreshold: Float = 500f): Boolean = rangeLock.withLock {
        val minA = minRepPosA
        val maxA = maxRepPosA
        val minB = minRepPosB
        val maxB = maxRepPosB
        val rangeA = if (minA != null && maxA != null) maxA - minA else 0f
        val rangeB = if (minB != null && maxB != null) maxB - minB else 0f
        rangeA > minRangeThreshold || rangeB > minRangeThreshold
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
    // Last rep's raw positions for ghost indicators
    val lastRepTopA: Float? = null,
    val lastRepTopB: Float? = null,
    val lastRepBottomA: Float? = null,
    val lastRepBottomB: Float? = null,
) {
    /**
     * Check if cable A is active (has built meaningful range of motion).
     * Used to determine whether to show cable position indicators.
     */
    fun isCableAActive(minRangeThreshold: Float = 500f): Boolean {
        return !(minPosA == null || maxPosA == null) && (maxPosA - minPosA) > minRangeThreshold
    }

    /**
     * Check if cable B is active (has built meaningful range of motion).
     * Used to determine whether to show cable position indicators.
     */
    fun isCableBActive(minRangeThreshold: Float = 500f): Boolean {
        return !(minPosB == null || maxPosB == null) && (maxPosB - minPosB) > minRangeThreshold
    }

    /**
     * True when either cable with a meaningful range is within 5% of its own ROM minimum.
     *
     * Auto-stop keeps this combined OR. The HUD colors each bar from
     * [isCableAInDangerZone] and [isCableBInDangerZone] so one low cable does not
     * turn the other bar red.
     *
     * @param posA Current position A in mm
     * @param posB Current position B in mm
     * @param minRangeThreshold Minimum ROM range required before that cable can be in the danger zone
     * @return true if either cable with meaningful range is in danger zone
     */
    fun isInDangerZone(posA: Float, posB: Float, minRangeThreshold: Float = 500f): Boolean {
        return isCableAInDangerZone(posA, minRangeThreshold) ||
            isCableBInDangerZone(posB, minRangeThreshold)
    }

    /**
     * True when cable A has built a meaningful range and [posA] is within 5% of its minimum.
     */
    fun isCableAInDangerZone(posA: Float, minRangeThreshold: Float = 500f): Boolean {
        return isCableInDangerZone(posA, minPosA, maxPosA, minRangeThreshold)
    }

    /**
     * True when cable B has built a meaningful range and [posB] is within 5% of its minimum.
     */
    fun isCableBInDangerZone(posB: Float, minRangeThreshold: Float = 500f): Boolean {
        return isCableInDangerZone(posB, minPosB, maxPosB, minRangeThreshold)
    }

    private fun isCableInDangerZone(
        position: Float,
        minPos: Float?,
        maxPos: Float?,
        minRangeThreshold: Float,
    ): Boolean {
        // range > minRangeThreshold keeps inactive cables (stuck near 0, no real ROM) out.
        if (minPos != null && maxPos != null) {
            val range = maxPos - minPos
            if (range > minRangeThreshold) {
                val threshold = minPos + (range * 0.05f)
                if (position <= threshold) return true
            }
        }
        return false
    }
}

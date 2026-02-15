package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.util.BleConstants
import kotlin.math.abs

/**
 * Synchronous processing pipeline for BLE monitor packets.
 *
 * Takes a raw [MonitorPacket] (from [parseMonitorPacket]) and returns a validated [WorkoutMetric],
 * or null if the sample is rejected by validation. Fires callbacks for status flag events
 * (deload, ROM violation) so the caller can emit them via coroutines.
 *
 * Pipeline stages:
 * 1. Position clamping (last-good fallback for out-of-range values)
 * 2. Status flag processing (deload debounce, ROM violation callbacks)
 * 3. Issue #210: Position tracking update BEFORE jump validation (cascade prevention)
 * 4. Sample validation (range, load, jump filter)
 * 5. Velocity calculation (raw position delta / time delta)
 * 6. EMA smoothing (with cold-start seeding and filtered-sample edge case)
 * 7. WorkoutMetric construction
 *
 * Latency budget: <5ms per call. No suspend functions, no coroutines, no Flow.
 * All event emission is callback-based; the caller (KableBleRepository) decides
 * how to dispatch events.
 *
 * **Issue #210 invariant:** `lastPositionA/B` are updated BEFORE `validateSample()`.
 * This prevents a single position spike from causing every subsequent sample to also
 * be filtered (cascading failure). The fix limits cascading to at most 1 extra filtered
 * sample, after which the tracking position matches the incoming data and deltas normalize.
 *
 * Extracted from KableBleRepository (Phase 10). Every line of logic was mechanical extraction.
 *
 * @param onDeloadOccurred Callback fired when DELOAD_OCCURRED status flag is set (debounced to 2s)
 * @param onRomViolation Callback fired when ROM_OUTSIDE_HIGH or ROM_OUTSIDE_LOW is detected
 * @param timeProvider Injectable time source for deterministic testing (default: currentTimeMillis())
 */
class MonitorDataProcessor(
    private val onDeloadOccurred: () -> Unit = {},
    private val onRomViolation: (RomViolationType) -> Unit = {},
    private val timeProvider: () -> Long = { currentTimeMillis() }
) {
    private val log = Logger.withTag("MonitorDataProcessor")

    /** Types of Range-of-Motion violations detected from machine status flags. */
    enum class RomViolationType { OUTSIDE_HIGH, OUTSIDE_LOW }

    companion object {
        /** Valid position range boundaries (mm), pre-computed as Float for hot-path comparison. */
        private val MIN_POS = BleConstants.Thresholds.MIN_POSITION.toFloat()
        private val MAX_POS = BleConstants.Thresholds.MAX_POSITION.toFloat()
    }

    // -------------------------------------------------------------------------
    // Position tracking - clamping (last-good fallback)
    // -------------------------------------------------------------------------
    private var lastGoodPosA = 0.0f
    private var lastGoodPosB = 0.0f

    // -------------------------------------------------------------------------
    // Position tracking - velocity calculation
    // -------------------------------------------------------------------------
    private var lastPositionA = 0.0f
    private var lastPositionB = 0.0f
    private var lastTimestamp = 0L

    // -------------------------------------------------------------------------
    // Velocity EMA smoothing (Issue #204, #214)
    // -------------------------------------------------------------------------
    private var smoothedVelocityA = 0.0
    private var smoothedVelocityB = 0.0
    private var isFirstVelocitySample = true

    // -------------------------------------------------------------------------
    // Filter edge case tracking
    // -------------------------------------------------------------------------
    private var lastSampleWasFiltered = false

    // -------------------------------------------------------------------------
    // Deload debouncing
    // -------------------------------------------------------------------------
    private var lastDeloadEventTime = 0L

    // -------------------------------------------------------------------------
    // Poll rate diagnostics
    // -------------------------------------------------------------------------
    private var pollIntervalSum = 0L
    private var pollIntervalCount = 0L
    private var maxPollInterval = 0L
    private var minPollInterval = Long.MAX_VALUE

    // -------------------------------------------------------------------------
    // Monitor notification counter
    // -------------------------------------------------------------------------
    private var monitorNotificationCount = 0L

    // -------------------------------------------------------------------------
    // Public properties
    // -------------------------------------------------------------------------
    var strictValidationEnabled: Boolean = true
    val notificationCount: Long get() = monitorNotificationCount

    /**
     * Process a raw monitor packet through the full validation and velocity pipeline.
     *
     * @param packet Parsed monitor packet from [parseMonitorPacket]
     * @return Validated [WorkoutMetric] with smoothed velocities, or null if sample rejected
     */
    fun process(packet: MonitorPacket): WorkoutMetric? {
        // Increment counter for diagnostic logging
        monitorNotificationCount++
        if (monitorNotificationCount % 100 == 0L) {
            log.i { "MONITOR NOTIFICATION #$monitorNotificationCount" }
        }

        // Extract values from parsed packet
        var posA = packet.posA
        var posB = packet.posB
        val loadA = packet.loadA
        val loadB = packet.loadB
        val ticks = packet.ticks

        // ===== STAGE 1: POSITION CLAMPING (last-good fallback) =====
        // Replace out-of-range positions with last known good values (BLE noise recovery).
        // Valid range: -1000 to +1000 mm per official app documentation.
        if (posA !in MIN_POS..MAX_POS) {
            log.w { "Position A out of range: $posA, using last good: $lastGoodPosA" }
            posA = lastGoodPosA
        } else {
            lastGoodPosA = posA
        }
        if (posB !in MIN_POS..MAX_POS) {
            log.w { "Position B out of range: $posB, using last good: $lastGoodPosB" }
            posB = lastGoodPosB
        } else {
            lastGoodPosB = posB
        }

        // ===== STAGE 2: STATUS FLAG PROCESSING =====
        if (packet.status != 0) {
            processStatusFlags(packet.status)
        }

        // ===== STAGE 3: ISSUE #210 - Update tracking BEFORE validation =====
        // CRITICAL: Store previous positions and update tracking BEFORE validation.
        // This prevents cascading filter failures where every sample after one jump
        // is filtered because lastPositionA/B were never updated (early return skipped
        // the update in the original parent repo code).
        val previousPosA = lastPositionA
        val previousPosB = lastPositionB
        lastPositionA = posA
        lastPositionB = posB

        // ===== STAGE 4: SAMPLE VALIDATION =====
        if (!validateSample(posA, loadA, posB, loadB, previousPosA, previousPosB)) {
            lastSampleWasFiltered = true  // Mark for velocity reset on next valid sample
            return null  // Skip invalid sample, but position tracking is updated for next sample
        }

        // ===== STAGE 5: VELOCITY CALCULATION =====
        val currentTime = timeProvider()
        val pollIntervalMs = if (lastTimestamp > 0L) currentTime - lastTimestamp else 0L

        // Poll rate diagnostics
        if (pollIntervalMs > 0) {
            pollIntervalSum += pollIntervalMs
            pollIntervalCount++
            if (pollIntervalMs > maxPollInterval) maxPollInterval = pollIntervalMs
            if (pollIntervalMs < minPollInterval) minPollInterval = pollIntervalMs

            // Log every 100 samples with statistics
            if (pollIntervalCount % 100 == 0L) {
                val avgInterval = pollIntervalSum / pollIntervalCount
                log.i { "POLL RATE: avg=${avgInterval}ms, min=${minPollInterval}ms, max=${maxPollInterval}ms, count=$pollIntervalCount" }
                if (avgInterval > 30) {
                    log.w { "SLOW POLL RATE: ${avgInterval}ms avg (expected <20ms). Check connection priority!" }
                }
            }
        }

        // Calculate raw velocity (SIGNED for proper EMA smoothing - Issue #204, #214)
        // Using signed velocity allows jitter oscillations (+2, -3, +1mm) to average toward 0
        // Issue #210: Use previousPosA/B since lastPositionA/B was updated before validateSample
        val rawVelocityA = calculateRawVelocity(posA, previousPosA, currentTime)
        val rawVelocityB = calculateRawVelocity(posB, previousPosB, currentTime)

        // ===== STAGE 6: EMA SMOOTHING =====
        // Apply Exponential Moving Average smoothing (Issue #204, #214)
        // Task 10: Initialize EMA with first raw sample to prevent cold start lag
        // Velocity edge case fix: If previous sample was filtered due to position jump,
        // the raw velocity calculation used a bad reference position. Skip this sample's
        // velocity update to avoid propagating the error through the EMA.
        //
        // Only update EMA when we have a real velocity (lastTimestamp was > 0, meaning
        // this is not the very first sample). The first sample establishes position and
        // timestamp but produces zero velocity -- don't seed EMA with that zero.
        if (lastSampleWasFiltered) {
            // Don't update smoothed velocity - keep previous value
            // The raw velocity is calculated against the filtered position which is wrong
            // Next sample will have correct reference since lastPositionA/B were updated
            lastSampleWasFiltered = false
            log.d { "Velocity update skipped - previous sample was filtered" }
        } else if (lastTimestamp > 0L) {
            // We have a real velocity (not the first-ever sample)
            if (isFirstVelocitySample) {
                // Seed EMA with first real velocity to prevent cold start lag (Task 10)
                smoothedVelocityA = rawVelocityA
                smoothedVelocityB = rawVelocityB
                isFirstVelocitySample = false
            } else {
                val alpha = BleConstants.Thresholds.VELOCITY_SMOOTHING_ALPHA
                smoothedVelocityA = alpha * rawVelocityA + (1 - alpha) * smoothedVelocityA
                smoothedVelocityB = alpha * rawVelocityB + (1 - alpha) * smoothedVelocityB
            }
        }
        // else: first-ever sample (lastTimestamp == 0), skip EMA entirely

        // Update timestamp for next velocity calculation
        lastTimestamp = currentTime

        // ===== STAGE 7: BUILD METRIC =====
        return WorkoutMetric(
            timestamp = currentTime,
            loadA = loadA,
            loadB = loadB,
            positionA = posA,
            positionB = posB,
            ticks = ticks,
            velocityA = smoothedVelocityA,
            velocityB = smoothedVelocityB,
            status = packet.status
        )
    }

    /**
     * Calculate raw velocity from position delta and time delta.
     * Returns 0 if this is the first-ever sample (no previous timestamp) or time delta is zero.
     *
     * @param currentPos Current position (mm)
     * @param previousPos Previous position (mm)
     * @param currentTime Current timestamp (ms)
     * @return Raw velocity in mm/s (signed: positive = extending, negative = retracting)
     */
    private fun calculateRawVelocity(currentPos: Float, previousPos: Float, currentTime: Long): Double {
        if (lastTimestamp <= 0L) return 0.0
        val deltaTimeSeconds = (currentTime - lastTimestamp) / 1000.0
        if (deltaTimeSeconds <= 0.0) return 0.0
        return (currentPos - previousPos) / deltaTimeSeconds
    }

    /**
     * Process status flags from bytes 16-17 of monitor data.
     * Handles deload detection (debounced) and ROM violation events.
     */
    private fun processStatusFlags(status: Int) {
        if (status == 0) return

        val sampleStatus = SampleStatus(status)

        // ROM violations -> callback immediately
        if (sampleStatus.isRomOutsideHigh()) {
            log.w { "SAFETY: ROM_OUTSIDE_HIGH detected - Status: 0x${status.toString(16)}" }
            onRomViolation(RomViolationType.OUTSIDE_HIGH)
        }
        if (sampleStatus.isRomOutsideLow()) {
            log.w { "SAFETY: ROM_OUTSIDE_LOW detected - Status: 0x${status.toString(16)}" }
            onRomViolation(RomViolationType.OUTSIDE_LOW)
        }

        // Deload -> debounced callback
        if (sampleStatus.isDeloadOccurred()) {
            log.w { "MACHINE STATUS: DELOAD_OCCURRED flag set - Status: 0x${status.toString(16)}" }
            val now = timeProvider()
            if (now - lastDeloadEventTime > BleConstants.Timing.DELOAD_EVENT_DEBOUNCE_MS) {
                lastDeloadEventTime = now
                log.d { "DELOAD_OCCURRED: Firing callback" }
                onDeloadOccurred()
            }
        }

        if (sampleStatus.isDeloadWarn()) {
            log.w { "MACHINE STATUS: DELOAD_WARN - Status: 0x${status.toString(16)}" }
        }

        if (sampleStatus.isSpotterActive()) {
            log.d { "MACHINE STATUS: SPOTTER_ACTIVE - Status: 0x${status.toString(16)}" }
        }
    }

    /**
     * Validate sample data is within acceptable ranges.
     *
     * Issue #210: previousPosA/B are passed in explicitly for jump detection since
     * lastPositionA/B are now updated BEFORE calling this function to prevent
     * cascading filter failures.
     *
     * @return true if sample is valid and should be processed
     */
    private fun validateSample(
        posA: Float, loadA: Float, posB: Float, loadB: Float,
        previousPosA: Float, previousPosB: Float
    ): Boolean {
        // Position range check (redundant after clamping in process(), but defensive)
        if (posA !in MIN_POS..MAX_POS || posB !in MIN_POS..MAX_POS) {
            log.w { "Position out of range: posA=$posA, posB=$posB (valid: ${BleConstants.Thresholds.MIN_POSITION} to ${BleConstants.Thresholds.MAX_POSITION} mm)" }
            return false
        }

        // Load validation - check against hardware max weight
        if (loadA < 0f || loadA > BleConstants.Thresholds.MAX_WEIGHT_KG ||
            loadB < 0f || loadB > BleConstants.Thresholds.MAX_WEIGHT_KG) {
            log.w { "Load out of range: loadA=$loadA, loadB=$loadB (max=${BleConstants.Thresholds.MAX_WEIGHT_KG})" }
            return false
        }

        // STRICT VALIDATION: Filter >20mm jumps between samples (matching parent repo)
        // This catches BLE glitches that produce sudden position changes.
        // Skip first sample (lastTimestamp == 0) since there's no previous reference.
        if (strictValidationEnabled && lastTimestamp > 0L) {
            val jumpA = abs(posA - previousPosA)
            val jumpB = abs(posB - previousPosB)
            if (jumpA > BleConstants.Thresholds.POSITION_JUMP_THRESHOLD ||
                jumpB > BleConstants.Thresholds.POSITION_JUMP_THRESHOLD) {
                log.w { "Position jump filtered: jumpA=${jumpA}mm, jumpB=${jumpB}mm (threshold: ${BleConstants.Thresholds.POSITION_JUMP_THRESHOLD}mm)" }
                return false
            }
        }

        return true
    }

    /**
     * Reset processing state for a new workout session.
     *
     * Clears: position tracking, velocity EMA, poll rate diagnostics, notification counter.
     * Preserves: lastGoodPosA/B (reasonable fallbacks even across sessions),
     * lastDeloadEventTime, strictValidationEnabled.
     */
    fun resetForNewSession() {
        lastTimestamp = 0L
        pollIntervalSum = 0L
        pollIntervalCount = 0L
        minPollInterval = Long.MAX_VALUE
        maxPollInterval = 0L
        lastPositionA = 0.0f
        lastPositionB = 0.0f
        smoothedVelocityA = 0.0
        smoothedVelocityB = 0.0
        isFirstVelocitySample = true
        lastSampleWasFiltered = false
        monitorNotificationCount = 0L
        // NOTE: lastGoodPosA/B, lastDeloadEventTime, strictValidationEnabled
        // are NOT reset between sessions (matching current behavior)
    }

    /**
     * Get diagnostic summary of poll rate statistics.
     *
     * @return Formatted string with avg/min/max/count poll intervals
     */
    fun getPollRateStats(): String {
        if (pollIntervalCount == 0L) return "No poll data"
        val avgInterval = pollIntervalSum / pollIntervalCount
        return "avg=${avgInterval}ms, min=${minPollInterval}ms, max=${maxPollInterval}ms, count=$pollIntervalCount, notifications=$monitorNotificationCount"
    }
}

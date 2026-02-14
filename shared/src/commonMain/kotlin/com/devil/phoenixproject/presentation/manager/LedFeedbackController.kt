package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.domain.model.LedFeedbackMode
import com.devil.phoenixproject.domain.model.RepPhase
import com.devil.phoenixproject.domain.model.VelocityZone
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * LED biofeedback controller for real-time workout feedback via machine LEDs.
 *
 * Maps workout metrics (velocity, tempo compliance, load matching) to LED color
 * changes using the existing 8-scheme color command protocol. Implements two
 * layers of flicker prevention:
 *
 * 1. Upstream velocity EMA smoothing (already in KableBleRepository)
 * 2. Zone stability hysteresis (3 consecutive samples required)
 *
 * Simplified 4-zone color mapping (calibrated from real hardware testing):
 * - OFF = stationary (<5 mm/s)
 * - GREEN = slow/controlled (5-30 mm/s)
 * - BLUE = normal tempo (30-60 mm/s)
 * - RED = fast/explosive (>=60 mm/s)
 *
 * Supports three feedback modes:
 * - VELOCITY_ZONE: Standard velocity-to-color mapping (all workout modes)
 * - TEMPO_GUIDE: Tempo compliance for TUT/TUT Beast
 * - AUTO: Mode-dependent selection (TUT/TUTBeast -> tempo, Echo -> load match, else -> velocity)
 *
 * @param bleRepository BLE repository for sending color commands
 * @param scope Coroutine scope for fire-and-forget BLE writes and celebration animation
 * @param timeProvider Injectable time source for testing (defaults to system clock)
 */
class LedFeedbackController(
    private val bleRepository: BleRepository,
    private val scope: CoroutineScope,
    private val timeProvider: () -> Long = { currentTimeMillis() }
) {
    // Hysteresis: require consecutive samples in same zone before switching (prevents flicker)
    internal companion object {
        const val ZONE_STABILITY_THRESHOLD = 3
    }

    // Core state
    private var lastSentSchemeIndex: Int = -1
    private var currentZone: VelocityZone = VelocityZone.REST
    private var zoneStabilityCount: Int = 0
    private var feedbackSuspended: Boolean = false
    private var celebrationJob: Job? = null

    // Configuration
    var enabled: Boolean = false
        private set
    var currentMode: LedFeedbackMode = LedFeedbackMode.AUTO
        private set
    private var userColorSchemeIndex: Int = 0
    private var inRestPeriod: Boolean = false

    // ========== Public API ==========

    /**
     * Master toggle for LED biofeedback.
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Set the feedback mode (velocity zone, tempo guide, or auto).
     */
    fun setMode(mode: LedFeedbackMode) {
        this.currentMode = mode
    }

    /**
     * Remember the user's static color scheme index so we can restore it after workout.
     */
    fun setUserColorScheme(index: Int) {
        this.userColorSchemeIndex = index
    }

    /**
     * Main update call -- invoked from the metric flow on each MONITOR sample.
     *
     * Determines the effective feedback mode and resolves the target zone based on:
     * - Echo mode: load ratio matching
     * - TEMPO_GUIDE (or AUTO + TUT/TUTBeast): tempo compliance
     * - Otherwise: velocity zone mapping
     *
     * Applies hysteresis and throttling before sending the BLE color command.
     */
    fun updateMetrics(
        velocity: Double,
        repPhase: RepPhase,
        workoutMode: WorkoutMode,
        echoLoadRatio: Float = 0f
    ) {
        if (!enabled) return
        if (feedbackSuspended) return
        if (inRestPeriod) return
        if (bleRepository.discoModeActive.value) return

        val targetZone = when {
            // Echo mode always uses load matching regardless of feedback mode setting
            workoutMode is WorkoutMode.Echo -> resolveEchoZone(echoLoadRatio)
            // Explicit tempo guide or auto-resolved to tempo
            resolveEffectiveMode(workoutMode) == LedFeedbackMode.TEMPO_GUIDE -> {
                resolveTempoZone(velocity, workoutMode)
            }
            // Default: velocity zone mapping
            else -> resolveVelocityZone(velocity, repPhase)
        }

        applyZoneWithHysteresis(targetZone)
    }

    /**
     * Set LED to blue (index 0) for rest period calming visual.
     */
    fun onRestPeriodStart() {
        if (!enabled) return
        inRestPeriod = true
        sendColorForced(VelocityZone.REST.schemeIndex) // Blue = index 0
    }

    /**
     * Resume normal velocity-driven feedback after rest.
     */
    fun onRestPeriodEnd() {
        inRestPeriod = false
    }

    /**
     * Fire rapid color cycle for PR celebration (3 cycles x 6 colors x 200ms = 3.6s).
     *
     * Suspends normal feedback during the celebration, then restores the current zone color.
     */
    fun triggerPRCelebration() {
        if (!enabled) return
        feedbackSuspended = true

        celebrationJob?.cancel()
        celebrationJob = scope.launch {
            // Purple(6), Yellow(3), Green(1), Pink(4), Red(5), Teal(2)
            val celebrationColors = listOf(6, 3, 1, 4, 5, 2)
            repeat(3) {
                for (colorIndex in celebrationColors) {
                    bleRepository.setColorScheme(colorIndex)
                    delay(200L)
                }
            }
            // Restore normal feedback
            feedbackSuspended = false
            // Force-send current zone color to resume feedback
            sendColorForced(currentZone.schemeIndex)
        }
    }

    /**
     * Restore user's static color scheme and reset all state after workout ends.
     */
    fun onWorkoutEnd() {
        celebrationJob?.cancel()
        feedbackSuspended = false
        inRestPeriod = false
        sendColorForced(userColorSchemeIndex)
        resetZoneState()
    }

    /**
     * Reset lastSentSchemeIndex on disconnect so next color is sent fresh on reconnect.
     */
    fun onDisconnect() {
        lastSentSchemeIndex = -1
    }

    /**
     * Full state reset -- used when cleaning up the controller.
     */
    fun reset() {
        celebrationJob?.cancel()
        enabled = false
        feedbackSuspended = false
        inRestPeriod = false
        currentMode = LedFeedbackMode.AUTO
        userColorSchemeIndex = 0
        resetZoneState()
    }

    // ========== Internal resolvers ==========

    /**
     * Standard velocity zone mapping. Returns REST if idle and below threshold.
     */
    internal fun resolveVelocityZone(velocity: Double, repPhase: RepPhase): VelocityZone {
        val absVel = abs(velocity)
        if (repPhase == RepPhase.IDLE && absVel < 5) return VelocityZone.REST
        return VelocityZone.fromVelocity(absVel)
    }

    /**
     * Tempo guide for TUT/TUT Beast modes.
     *
     * Thresholds calibrated from real workout data (2026-02-14):
     * - TUT target: 50-70 mm/s (controlled tempo)
     * - TUT Beast target: 30-50 mm/s (slow/deliberate)
     *
     * Returns:
     * - CONTROLLED (green) if in target range
     * - MODERATE (teal) if too slow
     * - FAST (yellow) if slightly above
     * - EXPLOSIVE (red) if well above target
     */
    internal fun resolveTempoZone(velocity: Double, workoutMode: WorkoutMode): VelocityZone {
        val absVel = abs(velocity)
        val (targetLow, targetHigh) = when (workoutMode) {
            is WorkoutMode.TUT -> 50.0 to 70.0
            is WorkoutMode.TUTBeast -> 30.0 to 50.0
            else -> return VelocityZone.fromVelocity(absVel) // Fallback for non-TUT modes
        }

        val tolerance = (targetHigh - targetLow) * 0.25 // 25% tolerance band
        return when {
            absVel < targetLow - tolerance -> VelocityZone.MODERATE   // Teal: too slow
            absVel <= targetHigh           -> VelocityZone.CONTROLLED // Green: in range
            absVel <= targetHigh * 1.5     -> VelocityZone.FAST       // Yellow: a bit fast
            else                           -> VelocityZone.EXPLOSIVE  // Red: too fast
        }
    }

    /**
     * Echo mode load-matching feedback.
     *
     * @param echoLoadRatio actual/target load ratio
     * @return CONTROLLED (green) for 0.90-1.10, FAST (yellow) for 0.75-1.25, EXPLOSIVE (red) otherwise
     */
    internal fun resolveEchoZone(echoLoadRatio: Float): VelocityZone = when {
        echoLoadRatio in 0.90f..1.10f -> VelocityZone.CONTROLLED  // Green: matching target
        echoLoadRatio in 0.75f..1.25f -> VelocityZone.FAST        // Yellow: slightly off
        else                          -> VelocityZone.EXPLOSIVE   // Red: significant mismatch
    }

    /**
     * Resolve effective feedback mode based on AUTO logic.
     *
     * When mode is AUTO:
     * - TUT/TUTBeast -> TEMPO_GUIDE
     * - Everything else -> VELOCITY_ZONE
     *
     * When mode is explicit, use it directly.
     */
    internal fun resolveEffectiveMode(workoutMode: WorkoutMode): LedFeedbackMode {
        if (currentMode != LedFeedbackMode.AUTO) return currentMode
        return when (workoutMode) {
            is WorkoutMode.TUT, is WorkoutMode.TUTBeast -> LedFeedbackMode.TEMPO_GUIDE
            else -> LedFeedbackMode.VELOCITY_ZONE
        }
    }

    // ========== Hysteresis and throttling ==========

    /**
     * Apply zone stability hysteresis: require [ZONE_STABILITY_THRESHOLD] consecutive
     * samples in a new zone before switching. Prevents LED flicker from velocity noise.
     */
    private fun applyZoneWithHysteresis(targetZone: VelocityZone) {
        if (targetZone == currentZone) {
            zoneStabilityCount = 0
            return
        }
        zoneStabilityCount++
        if (zoneStabilityCount < ZONE_STABILITY_THRESHOLD) return

        currentZone = targetZone
        zoneStabilityCount = 0
        sendColorIfChanged(targetZone.schemeIndex)
    }

    /**
     * Send color command if different from last sent (dedup).
     * Uses fire-and-forget coroutine launch so caller is never blocked.
     */
    private fun sendColorIfChanged(schemeIndex: Int) {
        if (schemeIndex == lastSentSchemeIndex) return
        lastSentSchemeIndex = schemeIndex
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
        }
    }

    /**
     * Send color command bypassing dedup (used for rest, workout end, celebration restore).
     */
    private fun sendColorForced(schemeIndex: Int) {
        lastSentSchemeIndex = schemeIndex
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
        }
    }

    private fun resetZoneState() {
        lastSentSchemeIndex = -1
        currentZone = VelocityZone.REST
        zoneStabilityCount = 0
    }
}

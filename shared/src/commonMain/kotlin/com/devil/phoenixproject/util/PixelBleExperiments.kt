package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pixel 6/7 BLE diagnostic experiment flags.
 *
 * These flags isolate potential root causes for the GATT_ERROR(133) disconnect
 * that occurs on Pixel 6/7 (BCM4389, BT 5.0) when sending the workout CONFIG packet.
 *
 * Issue #333 / #170: Connection succeeds, but the 96-byte CONFIG write at workout
 * start triggers GATT_ERROR(133) exclusively on Pixel 6 and Pixel 7 devices.
 *
 * Hypothesis ranking:
 *   A (65%) - MTU 517 forced by Android 14+ changes write behavior on BCM4389
 *   B (25%) - Heartbeat/polling contention at the moment of CONFIG write
 *   C (15%) - Post-config diagnostic read creates a race condition
 *   D (10%) - CONFIG→START timing too aggressive for BCM4389
 *   E (10%) - MTU negotiation needs stabilization time before first write
 *
 * Test methodology: Enable one flag at a time on a Pixel 6/7, attempt workout.
 * If single flag fixes it, that's the root cause. If only all flags together
 * fix it, it's a combination.
 */
object PixelBleExperiments {

    private val log = Logger.withTag("PixelBleExperiments")

    // ── Experiment Flags ──────────────────────────────────────────────────

    /**
     * Flag A: Skip MTU negotiation entirely on affected devices.
     *
     * Rationale: Android 14+ forces MTU to 517 regardless of requested value.
     * With MTU 517, the 96-byte CONFIG is sent as a single ATT Write Request.
     * With default MTU 23, it uses Long Write (Prepare+Execute) which the
     * Vitruvian firmware was designed for. The official app does NOT call
     * requestMtu() in its connection flow.
     */
    private val _skipMtu = MutableStateFlow(false)
    val skipMtu: StateFlow<Boolean> = _skipMtu

    /**
     * Flag B: Pause all polling/heartbeat before sending CONFIG write.
     *
     * Rationale: The heartbeat no-op write uses the same TX characteristic
     * as the CONFIG packet. If a heartbeat write just completed when CONFIG
     * is attempted, the BCM4389 may need more recovery time between
     * consecutive writes to the same characteristic.
     */
    private val _pausePollingBeforeConfig = MutableStateFlow(false)
    val pausePollingBeforeConfig: StateFlow<Boolean> = _pausePollingBeforeConfig

    /**
     * Flag C: Disable the post-CONFIG diagnostic read.
     *
     * Rationale: After sending CONFIG, the app launches a fire-and-forget
     * diagnostic read with 350ms delay. This could create a mutex contention
     * race with the 100ms-delayed START command that follows.
     */
    private val _disablePostConfigDiagnostic = MutableStateFlow(false)
    val disablePostConfigDiagnostic: StateFlow<Boolean> = _disablePostConfigDiagnostic

    /**
     * Flag D: Increase delay between CONFIG and START commands.
     *
     * Rationale: The current 100ms gap may be too tight for BCM4389.
     * The official app may use longer inter-command timing.
     */
    private val _extendedConfigStartDelay = MutableStateFlow(false)
    val extendedConfigStartDelay: StateFlow<Boolean> = _extendedConfigStartDelay

    /**
     * Flag E: Add stabilization delay after MTU negotiation.
     *
     * Rationale: MTU negotiation changes ATT layer parameters. The BCM4389
     * may need time to stabilize before accepting writes at the new MTU.
     * Only applies when MTU negotiation is NOT skipped (Flag A off).
     */
    private val _postMtuStabilizationDelay = MutableStateFlow(false)
    val postMtuStabilizationDelay: StateFlow<Boolean> = _postMtuStabilizationDelay

    // ── Timing Constants ──────────────────────────────────────────────────

    /** How long to pause polling before CONFIG (Flag B). */
    const val POLLING_PAUSE_BEFORE_CONFIG_MS = 500L

    /** Extended delay between CONFIG and START (Flag D). Default is 100ms. */
    const val EXTENDED_CONFIG_START_DELAY_MS = 1000L

    /** Stabilization delay after MTU negotiation (Flag E). */
    const val POST_MTU_STABILIZATION_MS = 1000L

    // ── Device Detection ──────────────────────────────────────────────────

    /**
     * Pixel models with Broadcom BCM4389 (Bluetooth 5.0) that exhibit
     * the GATT_ERROR(133) on workout CONFIG writes.
     *
     * BCM4389 devices: Pixel 6, 6 Pro, 6a, 7, 7 Pro, 7a, Pixel Fold
     * BCM4398 devices (NOT affected): Pixel 8, 8 Pro, 8a, 9, 10, etc.
     */
    private val BCM4389_MODELS = listOf(
        "pixel 6", "pixel 6 pro", "pixel 6a",
        "pixel 7", "pixel 7 pro", "pixel 7a",
        "pixel fold",
    )

    /**
     * Check if the current device is a Pixel with the BCM4389 Bluetooth chip.
     * Safe to call from commonMain — returns false on iOS.
     */
    fun isAffectedPixel(): Boolean {
        val model = DeviceInfo.model.lowercase()
        return BCM4389_MODELS.any { model.contains(it) }
    }

    // ── Flag Mutation ─────────────────────────────────────────────────────

    fun setSkipMtu(enabled: Boolean) {
        _skipMtu.value = enabled
        log.i { "Flag A (Skip MTU): $enabled" }
    }

    fun setPausePollingBeforeConfig(enabled: Boolean) {
        _pausePollingBeforeConfig.value = enabled
        log.i { "Flag B (Pause Polling): $enabled" }
    }

    fun setDisablePostConfigDiagnostic(enabled: Boolean) {
        _disablePostConfigDiagnostic.value = enabled
        log.i { "Flag C (No Post-Config Diagnostic): $enabled" }
    }

    fun setExtendedConfigStartDelay(enabled: Boolean) {
        _extendedConfigStartDelay.value = enabled
        log.i { "Flag D (Extended CONFIG→START Delay): $enabled" }
    }

    fun setPostMtuStabilizationDelay(enabled: Boolean) {
        _postMtuStabilizationDelay.value = enabled
        log.i { "Flag E (Post-MTU Stabilization): $enabled" }
    }

    /** Enable all experiment flags at once. */
    fun enableAll() {
        setSkipMtu(true)
        setPausePollingBeforeConfig(true)
        setDisablePostConfigDiagnostic(true)
        setExtendedConfigStartDelay(true)
        setPostMtuStabilizationDelay(true)
    }

    /** Disable all experiment flags. */
    fun disableAll() {
        setSkipMtu(false)
        setPausePollingBeforeConfig(false)
        setDisablePostConfigDiagnostic(false)
        setExtendedConfigStartDelay(false)
        setPostMtuStabilizationDelay(false)
    }

    /** Get a summary string for logging at connection time. */
    fun getSummary(): String = buildString {
        appendLine("Pixel BLE Experiment Flags:")
        appendLine("  Affected device: ${isAffectedPixel()} (${DeviceInfo.model})")
        appendLine("  A - Skip MTU: ${_skipMtu.value}")
        appendLine("  B - Pause Polling: ${_pausePollingBeforeConfig.value}")
        appendLine("  C - No Post-Config Diag: ${_disablePostConfigDiagnostic.value}")
        appendLine("  D - Extended Delay: ${_extendedConfigStartDelay.value}")
        appendLine("  E - Post-MTU Stab: ${_postMtuStabilizationDelay.value}")
    }
}

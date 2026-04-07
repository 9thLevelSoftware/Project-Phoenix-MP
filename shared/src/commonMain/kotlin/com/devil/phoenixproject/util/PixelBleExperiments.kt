package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger

/**
 * Pixel 6/7 BCM4389 BLE workaround (Issue #333 / #170).
 *
 * The Broadcom BCM4389 Bluetooth chip (Pixel 6, 6 Pro, 6a, 7, 7 Pro, 7a, Pixel Fold)
 * triggers GATT_ERROR(133) when a large CONFIG write (96 bytes) hits the BLE bus while
 * concurrent polling operations are in-flight. This has been a persistent issue since
 * the original Vitruvian app (VitruvianProjectPhoenix#170).
 *
 * Root cause (confirmed via diagnostic build, tested by StuGotz on Pixel 6 Pro):
 *   The BCM4389's internal GATT queue overflows when a 96-byte CONFIG write competes
 *   with ongoing characteristic reads (monitor, diagnostic, heuristic polling) and
 *   heartbeat writes on the same connection. The firmware returns GATT_ERROR(133) —
 *   Android's generic "something went wrong" status — and the connection drops.
 *
 * Mitigations applied automatically on affected devices:
 *   B — Pause all 4 polling loops 500ms before CONFIG write. Drains the BCM4389's
 *       internal GATT queue so the 96-byte CONFIG hits a clean bus. Happens during
 *       countdown — no UX impact. Polling resumes after CONFIG+START completes.
 *       StuGotz's Pixel 6 Pro survived without B (C+D alone sufficed), but the
 *       original reporter's Pixel 6 needs the bus fully clear for CONFIG to land.
 *   C — Skip the optional post-CONFIG diagnostic read (removes a BLE operation that
 *       can contend with the CONFIG→START command sequence). Fault detection continues
 *       via the regular 1Hz diagnostic polling loop — this only skips the one-shot
 *       immediate probe. Does NOT affect Issue #222 fix (BleOperationQueue mutex).
 *   D — Extend CONFIG→START delay from 100ms to 1000ms (gives the BCM4389 time to
 *       fully process the CONFIG write before the START command arrives).
 *   F — Throttle monitor polling with 25ms inter-read delay. The monitor loop runs
 *       back-to-back reads (~20Hz). Combined with diagnostic (2Hz), heuristic (4Hz),
 *       and heartbeat (0.5Hz), this generates ~27 GATT ops/sec. After ~19 seconds the
 *       BCM4389's internal queue overflows → GATT_ERROR(133) even during idle polling.
 *       The 25ms delay reduces monitor rate to ~14Hz (total ~21 ops/sec).
 *   G — Skip heartbeat read on write-only TX characteristic. The read always fails
 *       (TX is write-only), generating a GATT Error Response round-trip for nothing.
 *       Go straight to the no-op write, halving heartbeat GATT traffic.
 *
 * Ruled out by testing:
 *   A (Skip MTU) — Made things WORSE. With default 23-byte MTU, the 96-byte CONFIG
 *       becomes a Long Write (6+ ATT segments), generating MORE GATT traffic.
 *   E (Post-MTU delay) — Works, but adds 1s to every connection. Overkill.
 */
object PixelBleExperiments {

    private val log = Logger.withTag("PixelBleExperiments")

    // ── Timing Constants ──────────────────────────────────────────────────

    /** How long to pause polling before CONFIG write on BCM4389 (mitigation B). */
    const val POLLING_PAUSE_BEFORE_CONFIG_MS = 500L

    /** Extended delay between CONFIG and START commands on BCM4389 (mitigation D). Default is 100ms. */
    const val EXTENDED_CONFIG_START_DELAY_MS = 1000L

    /** Inter-read delay for monitor polling on BCM4389 (mitigation F). Reduces ~20Hz to ~14Hz. */
    const val MONITOR_POLL_THROTTLE_MS = 25L

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

    // ── Logging ───────────────────────────────────────────────────────────

    /** Log active mitigations at connection time. */
    fun logConnectionSummary() {
        val affected = isAffectedPixel()
        if (affected) {
            log.i { "BCM4389 detected (${DeviceInfo.model}): auto-applying mitigations B+C+D+F+G for Issue #333" }
            log.i { "  B — Pause polling ${POLLING_PAUSE_BEFORE_CONFIG_MS}ms before CONFIG write" }
            log.i { "  C — Skip post-CONFIG diagnostic read (regular 1Hz polling still active)" }
            log.i { "  D — Extended CONFIG→START delay: ${EXTENDED_CONFIG_START_DELAY_MS}ms" }
            log.i { "  F — Monitor poll throttle: ${MONITOR_POLL_THROTTLE_MS}ms inter-read delay (~14Hz vs ~20Hz)" }
            log.i { "  G — Skip heartbeat read on write-only TX (go straight to no-op write)" }
        } else {
            log.d { "Device: ${DeviceInfo.model} — not a BCM4389 Pixel, no BLE mitigations needed" }
        }
    }
}

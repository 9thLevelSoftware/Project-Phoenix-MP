package com.devil.phoenixproject.data.ble

/**
 * Runtime flags for Issue #333 (Pixel 6/7 GATT_ERROR(133)) testing.
 *
 * Toggled from Settings → Developer Tools. Memory-only (reset on app restart).
 *
 * Flag A: Kill heartbeat entirely (startHeartbeat becomes no-op)
 * Flag B: Kill ALL polling (startAll only launches notification observers)
 * Flag C: Explicit LE 1M PHY + Transport.Le in Peripheral builder
 */
object PixelTestFlags {
    /** A: Disable heartbeat loop entirely. */
    @Volatile
    var killHeartbeat: Boolean = false

    /** B: Disable ALL polling loops (monitor, diagnostic, heuristic, heartbeat). */
    @Volatile
    var killAllPolling: Boolean = false

    /** C: Pass explicit phy = Phy.Le1M + transport = Transport.Le to Peripheral() builder. */
    @Volatile
    var explicitLe1mPhy: Boolean = false

    /** Summary string for connection log entries. */
    fun activeFlagsSummary(): String {
        val flags = mutableListOf<String>()
        if (killHeartbeat) flags.add("A:KillHeartbeat")
        if (killAllPolling) flags.add("B:KillAllPolling")
        if (explicitLe1mPhy) flags.add("C:ExplicitLE1M")
        return if (flags.isEmpty()) "None" else flags.joinToString("+")
    }
}

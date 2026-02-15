package com.devil.phoenixproject.data.ble

/**
 * Raw parsed monitor data before validation/processing.
 * Position in mm (raw / 10.0f), load in kg (raw / 100.0f).
 * Created by parseMonitorPacket().
 */
data class MonitorPacket(
    val ticks: Int,
    val posA: Float,    // mm
    val posB: Float,    // mm
    val loadA: Float,   // kg
    val loadB: Float,   // kg
    val status: Int     // Status flags (0 if not present)
)

/**
 * Raw parsed diagnostic data.
 * Created by parseDiagnosticPacket().
 */
data class DiagnosticPacket(
    val seconds: Int,
    val faults: List<Short>,    // 4 fault codes
    val temps: List<Byte>,      // 8 temperature readings
    val hasFaults: Boolean
)

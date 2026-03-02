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
    val status: Int,    // Status flags (0 if not present)
    val firmwareVelA: Int = 0,  // Raw firmware velocity A (bytes 6-7, signed)
    val firmwareVelB: Int = 0,  // Raw firmware velocity B (bytes 12-13, signed)
    val extraBytes: ByteArray? = null  // Bytes 18+ for investigation (null if packet <= 18 bytes)
) {
    override fun equals(other: Any?): Boolean =
        other is MonitorPacket && ticks == other.ticks && posA == other.posA &&
        posB == other.posB && loadA == other.loadA && loadB == other.loadB &&
        status == other.status && firmwareVelA == other.firmwareVelA &&
        firmwareVelB == other.firmwareVelB
    override fun hashCode(): Int = ticks.hashCode()
}

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

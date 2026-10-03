package com.devil.phoenixproject.data.ble

/**
 * Raw parsed monitor data before validation/processing.
 * Position in mm (raw / 10.0f), load in kg (raw / 100.0f).
 * Created by parseMonitorPacket().
 */
data class MonitorPacket(
    val ticks: Long,
    val posA: Float, // mm
    val posB: Float, // mm
    val loadA: Float, // kg
    val loadB: Float, // kg
    val status: Int, // Status flags (0 if not present)
    val firmwareVelA: Int = 0, // Raw firmware velocity A (bytes 6-7, signed)
    val firmwareVelB: Int = 0, // Raw firmware velocity B (bytes 12-13, signed)
)

/**
 * Raw parsed diagnostic data.
 * Created by parseDiagnosticPacket().
 */
data class DiagnosticPacket(
    val runtimeSeconds: Long,
    val faultWords: List<Int>, // up to 4 unsigned 16-bit fault words
    val temperatures: List<Int>, // up to 8 unsigned 8-bit temperature readings
    val hasFaults: Boolean,
    val crash: DiagnosticCrash? = null,
    val warnings: Long? = null,
    val receivedAtMillis: Long = 0L,
)

/**
 * Optional crash details from extended diagnostics payloads.
 */
data class DiagnosticCrash(
    val seconds: Long,
    val stackBase64: String,
)

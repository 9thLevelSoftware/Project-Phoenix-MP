package com.phoenix.vendor.template

/**
 * Converts raw packets from vendor hardware into canonical telemetry.
 */
interface TelemetryDecoder {
    fun decode(packet: ByteArray): TelemetryFrame?
}

data class TelemetryFrame(
    val reps: Int,
    val forceNewtons: Float,
    val velocityMps: Float,
    val rangeOfMotionMm: Int,
    val timestampMs: Long
)

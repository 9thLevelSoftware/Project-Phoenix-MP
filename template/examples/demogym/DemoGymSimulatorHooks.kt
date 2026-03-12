package com.phoenix.vendor.demogym

import com.phoenix.vendor.template.TelemetryFrame
import kotlin.math.sin

/**
 * Local simulator helpers for quickly validating adapter integrations.
 */
class DemoGymSimulatorHooks {
    fun nextFrame(rep: Int, elapsedMs: Long): TelemetryFrame {
        val normalized = (sin(elapsedMs / 450.0) + 1.0) / 2.0
        return TelemetryFrame(
            reps = rep,
            forceNewtons = (180 + normalized * 220).toFloat(),
            velocityMps = (0.35 + normalized * 0.75).toFloat(),
            rangeOfMotionMm = (200 + normalized * 500).toInt(),
            timestampMs = elapsedMs
        )
    }

    fun encodedPacket(frame: TelemetryFrame): ByteArray {
        val force = (frame.forceNewtons / 5f).toInt().coerceIn(0, 255)
        val velocity = (frame.velocityMps * 100).toInt().coerceIn(0, 255)
        val rom = frame.rangeOfMotionMm.coerceIn(0, 65535)
        val time = (frame.timestampMs / 100).coerceIn(0, 0xFFFFFF)
        return byteArrayOf(
            0x33,
            frame.reps.toByte(),
            force.toByte(),
            velocity.toByte(),
            ((rom shr 8) and 0xFF).toByte(),
            (rom and 0xFF).toByte(),
            ((time shr 16) and 0xFF).toByte(),
            ((time shr 8) and 0xFF).toByte(),
            (time and 0xFF).toByte()
        )
    }
}

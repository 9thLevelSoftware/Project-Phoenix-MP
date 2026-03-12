package com.phoenix.vendor.demogym

import com.phoenix.vendor.template.CommandEncoder
import com.phoenix.vendor.template.MachineProfile
import com.phoenix.vendor.template.TelemetryDecoder
import com.phoenix.vendor.template.TelemetryFrame
import com.phoenix.vendor.template.VendorPlugin

class DemoGymPlugin : VendorPlugin {
    override val id: String = "demogym"
    override val displayName: String = "DemoGym"

    private val v1Profile = MachineProfile(
        model = "DemoGym-Rack-1",
        protocolVersion = 1,
        supportsEccentricControl = true,
        supportsLiveTelemetry = true,
        maxResistance = 400
    )

    override val supportedProfiles: Set<MachineProfile> = setOf(v1Profile)

    override fun encoder(profile: MachineProfile): CommandEncoder = DemoGymCommandEncoder()

    override fun decoder(profile: MachineProfile): TelemetryDecoder = DemoGymTelemetryDecoder()
}

class DemoGymCommandEncoder : CommandEncoder {
    override fun startSession(profile: MachineProfile): ByteArray = byteArrayOf(0x55, 0x01, profile.protocolVersion.toByte())

    override fun setResistance(level: Int): ByteArray {
        val clamped = level.coerceIn(0, 400)
        return byteArrayOf(0x55, 0x10, clamped.toByte())
    }

    override fun stopSession(): ByteArray = byteArrayOf(0x55, 0x02)
}

class DemoGymTelemetryDecoder : TelemetryDecoder {
    override fun decode(packet: ByteArray): TelemetryFrame? {
        if (packet.size < 9 || packet[0] != 0x33.toByte()) return null

        val reps = packet[1].toInt() and 0xFF
        val forceRaw = packet[2].toInt() and 0xFF
        val velocityRaw = packet[3].toInt() and 0xFF
        val romRaw = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val timeRaw = ((packet[6].toLong() and 0xFF) shl 16) or
            ((packet[7].toLong() and 0xFF) shl 8) or
            (packet[8].toLong() and 0xFF)

        return TelemetryFrame(
            reps = reps,
            forceNewtons = forceRaw * 5f,
            velocityMps = velocityRaw / 100f,
            rangeOfMotionMm = romRaw,
            timestampMs = timeRaw * 100L
        )
    }
}

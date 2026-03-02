package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlin.concurrent.Volatile

/**
 * BLE Packet Capture Utility for Hardware Validation
 *
 * Captures raw BLE bytes from the monitor characteristic and logs them
 * in a format that can be pasted directly into HardwareValidationTest.
 *
 * Usage in debug builds:
 * ```
 * // Before connecting to trainer:
 * BlePacketCapture.startCapture(knownWeightKg = 50.0f)
 *
 * // ... perform workout at known weight ...
 *
 * // When done:
 * val packets = BlePacketCapture.stopCapture()
 * // Packets are also logged to Logcat with tag "BlePacketCapture"
 * ```
 *
 * Then grep Logcat for "CAPTURE_HEX" and paste into HardwareValidationTest.
 *
 * To hook this into the polling loop, add ONE line in MetricPollingEngine.parseMonitorData():
 * ```
 * private fun parseMonitorData(data: ByteArray) {
 *     BlePacketCapture.onPacket(data)     // <-- Add this line
 *     val packet = parseMonitorPacket(data)
 *     ...
 * }
 * ```
 */
object BlePacketCapture {

    private val log = Logger.withTag("BlePacketCapture")

    data class CapturedPacket(
        val timestampMs: Long,
        val rawBytes: ByteArray,
        val hex: String,
        val size: Int
    ) {
        override fun equals(other: Any?): Boolean =
            other is CapturedPacket && timestampMs == other.timestampMs && hex == other.hex
        override fun hashCode(): Int = 31 * timestampMs.hashCode() + hex.hashCode()
    }

    @Volatile
    private var capturing = false
    private var knownWeight = 0.0f
    private var description = ""
    private val packets = mutableListOf<CapturedPacket>()
    private var maxPackets = 500  // Safety limit
    private var startTime = 0L

    /**
     * Start capturing BLE packets.
     *
     * @param knownWeightKg The weight set on the machine (for validation tests)
     * @param desc Description of the capture session (e.g., "50kg static hold")
     * @param maxCapture Maximum packets to capture before auto-stopping
     */
    fun startCapture(knownWeightKg: Float = 0f, desc: String = "", maxCapture: Int = 500) {
        packets.clear()
        knownWeight = knownWeightKg
        description = desc
        maxPackets = maxCapture
        startTime = currentTimeMillis()
        capturing = true
        log.i { "=== CAPTURE STARTED === weight=${knownWeightKg}kg desc='$desc' max=$maxCapture" }
    }

    /**
     * Stop capturing and return all captured packets.
     * Also logs a summary and copyable test data to Logcat.
     */
    fun stopCapture(): List<CapturedPacket> {
        capturing = false
        val elapsed = currentTimeMillis() - startTime
        val result = packets.toList()

        log.i { "=== CAPTURE STOPPED === ${result.size} packets in ${elapsed}ms" }
        log.i { "Average rate: ${if (elapsed > 0) result.size * 1000 / elapsed else 0} packets/sec" }

        // Log size distribution
        val sizes = result.groupBy { it.size }.mapValues { it.value.size }
        log.i { "Packet sizes: $sizes" }

        // Log copyable test data
        log.i { "=== COPY BELOW INTO HardwareValidationTest.CAPTURED_PACKETS ===" }
        for ((index, pkt) in result.withIndex()) {
            val relTime = pkt.timestampMs - startTime
            log.i { "CAPTURE_HEX[$index] t=${relTime}ms size=${pkt.size}: ${pkt.hex}" }
            log.i { "CapturedSample(\"${pkt.hex}\", ${knownWeight}f, \"$description #$index at ${relTime}ms\")," }
        }
        log.i { "=== END CAPTURE DATA ===" }

        return result
    }

    /**
     * Called from MetricPollingEngine.parseMonitorData() on every raw packet.
     * No-op when not capturing. Minimal overhead (~1us when not capturing).
     */
    fun onPacket(data: ByteArray) {
        if (!capturing) return

        if (packets.size >= maxPackets) {
            capturing = false
            log.w { "Auto-stopped: reached max capture limit ($maxPackets)" }
            return
        }

        val hex = data.toHex()
        val packet = CapturedPacket(
            timestampMs = currentTimeMillis(),
            rawBytes = data.copyOf(),
            hex = hex,
            size = data.size
        )
        packets.add(packet)

        // Log every packet with dual interpretation for real-time monitoring
        if (packets.size <= 10 || packets.size % 50 == 0) {
            logDualInterpretation(data, hex, packets.size)
        }
    }

    /**
     * Log a single packet interpreted both ways for real-time monitoring.
     */
    private fun logDualInterpretation(data: ByteArray, hex: String, index: Int) {
        if (data.size < 16) {
            log.d { "CAPTURE[$index] ${data.size}B: $hex (too short for dual parse)" }
            return
        }

        // Hardware-validated packet layout (2026-02-17)
        val ticksLow = getUInt16LE(data, 0)
        val ticksHigh = getUInt16LE(data, 2)
        val ticks = ticksLow + (ticksHigh shl 16)
        val pPosA = getInt16LE(data, 4) / 10.0f
        val skippedA = getInt16LE(data, 6)   // Firmware velocity A
        val pLoadA = getUInt16LE(data, 8) / 100.0f
        val skippedB = getInt16LE(data, 12)  // Firmware velocity B

        // Extra bytes (18+) — unknown, log for investigation
        val extraHex = if (data.size > 18) {
            data.copyOfRange(18, data.size).toHex(" ")
        } else "none"

        log.d {
            "CAPTURE[$index] ${data.size}B | " +
            "t=$ticks posA=${pPosA}mm velA=$skippedA velB=$skippedB loadA=${pLoadA}kg | " +
            "extra=[${extraHex}]"
        }
    }

    /** Check if currently capturing. */
    val isCapturing: Boolean get() = capturing

    /** Number of packets captured so far. */
    val packetCount: Int get() = packets.size

    /** KMP-compatible hex conversion for ByteArray. */
    private fun ByteArray.toHex(separator: String = ""): String =
        joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}

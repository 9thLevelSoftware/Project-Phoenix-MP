package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement

/**
 * Pure byte parsing utility functions for Polar H10 telemetry.
 *
 * Implements decoders for:
 * - Heart Rate & RR Intervals (Standard Bluetooth SIG 0x2A37)
 * - Battery Level (Standard Bluetooth SIG 0x2A19)
 * - Polar Measurement Data / PMD ECG & ACC (Polar Proprietary FB005C82)
 *
 * All functions are stateless and thread-safe.
 */

/**
 * Read signed 64-bit integer in LITTLE-ENDIAN format (LSB first).
 * Used for sensor timestamps in PMD packets.
 */
fun getInt64LE(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xFFL) or
    ((data[offset + 1].toLong() and 0xFFL) shl 8) or
    ((data[offset + 2].toLong() and 0xFFL) shl 16) or
    ((data[offset + 3].toLong() and 0xFFL) shl 24) or
    ((data[offset + 4].toLong() and 0xFFL) shl 32) or
    ((data[offset + 5].toLong() and 0xFFL) shl 40) or
    ((data[offset + 6].toLong() and 0xFFL) shl 48) or
    ((data[offset + 7].toLong() and 0xFFL) shl 56)

/**
 * Read signed bit-packed value of length [bitCount] (1..16 bits) from [data]
 * starting at [startByte] and [bitOffset].
 */
internal fun readSignedBits(data: ByteArray, startByte: Int, bitOffset: Int, bitCount: Int): Int {
    if (bitCount <= 0 || bitCount > 16) return 0
    val byteIdx = startByte + (bitOffset / 8)
    val bitInByte = bitOffset % 8
    if (byteIdx >= data.size) return 0

    val b0 = data[byteIdx].toInt() and 0xFF
    val b1 = if (byteIdx + 1 < data.size) data[byteIdx + 1].toInt() and 0xFF else 0
    val b2 = if (byteIdx + 2 < data.size) data[byteIdx + 2].toInt() and 0xFF else 0
    val raw24 = b0 or (b1 shl 8) or (b2 shl 16)

    val unsigned = (raw24 ushr bitInByte) and ((1 shl bitCount) - 1)
    val signBit = 1 shl (bitCount - 1)
    return if ((unsigned and signBit) != 0) unsigned - (1 shl bitCount) else unsigned
}

/**
 * Parse GATT Heart Rate Measurement (0x2A37) payload into [PolarHrMeasurement].
 *
 * Layout:
 * - Flags (byte 0):
 *   - bit 0: HR format (0 = uint8, 1 = uint16)
 *   - bits 1-2: Sensor contact status (11b = contact detected, 10b = not detected)
 *   - bit 3: Energy expended present (0 = false, 1 = true)
 *   - bit 4: RR intervals present (0 = false, 1 = true)
 * - HR: 1 byte or 2 bytes LE depending on bit 0
 * - Energy expended (optional): 2 bytes uint16 LE (kiloJoules)
 * - RR intervals (optional): pairs of uint16 LE, units of 1/1024s (converted to ms)
 *
 * @param data The raw byte array received from characteristic 0x2A37.
 * @return Parsed [PolarHrMeasurement] or null if packet is truncated or invalid.
 */
fun parsePolarHrMeasurement(data: ByteArray): PolarHrMeasurement? {
    if (data.isEmpty()) return null

    val flags = data[0].toInt() and 0xFF
    val hrFormat16Bit = (flags and 0x01) != 0
    val energyPresent = (flags and 0x08) != 0
    val rrPresent = (flags and 0x10) != 0

    var offset = 1
    val hr: Int
    if (hrFormat16Bit) {
        if (data.size < offset + 2) return null
        hr = getUInt16LE(data, offset)
        offset += 2
    } else {
        if (data.size < offset + 1) return null
        hr = data[offset].toInt() and 0xFF
        offset += 1
    }

    // Bits 1..2 of flags:
    // 0 or 1: Contact sensor not supported -> treated as contact OK (true) if hr > 0
    // 2 (0b10): Contact supported, NOT detected -> false (poor skin contact)
    // 3 (0b11): Contact supported and detected -> true (good skin contact)
    val contactStatus = (flags shr 1) and 0x03
    val contactDetected = when (contactStatus) {
        2 -> false
        3 -> true
        else -> hr > 0
    }

    var energyExpendedKj: Int? = null
    if (energyPresent) {
        if (data.size < offset + 2) return null
        energyExpendedKj = getUInt16LE(data, offset)
        offset += 2
    }

    val rrIntervalsMs = mutableListOf<Double>()
    if (rrPresent) {
        while (offset + 2 <= data.size) {
            val rawRr = getUInt16LE(data, offset)
            offset += 2
            val ms = (rawRr * 1000.0) / 1024.0
            rrIntervalsMs.add(ms)
        }
    }

    return PolarHrMeasurement(
        hr = hr,
        contactDetected = contactDetected,
        energyExpendedKj = energyExpendedKj,
        rrIntervalsMs = rrIntervalsMs,
    )
}

/**
 * Parse Battery Level (0x2A19) payload into percentage (0..100).
 */
fun parsePolarBatteryLevel(data: ByteArray): Int? {
    if (data.isEmpty()) return null
    return (data[0].toInt() and 0xFF).coerceIn(0, 100)
}

/**
 * Parse Polar Measurement Data (PMD) stream packet (FB005C82) into [PolarPmdMeasurement].
 *
 * Supports:
 * - ECG (type 0x00, uncompressed 24-bit signed LE samples in microvolts)
 * - ACC (type 0x02, uncompressed 8/16-bit signed triplets or delta-compressed bit-packed frames)
 *
 * @param data The raw byte array received from characteristic FB005C82.
 * @return [PolarPmdMeasurement.Ecg], [PolarPmdMeasurement.Acc], or null if packet is invalid.
 */
fun parsePolarPmdData(data: ByteArray): PolarPmdMeasurement? {
    if (data.size < 10) return null

    val measurementType = data[0].toInt() and 0xFF
    val sensorTimestampNs = getInt64LE(data, 1)
    val frameType = data[9].toInt() and 0xFF

    return when (measurementType) {
        0x00 -> parseEcgFrame(data, sensorTimestampNs)
        0x02 -> parseAccFrame(data, sensorTimestampNs, frameType)
        else -> null
    }
}

private fun parseEcgFrame(data: ByteArray, timestampNs: Long): PolarPmdMeasurement.Ecg {
    val samples = mutableListOf<Int>()
    var offset = 10
    while (offset + 3 <= data.size) {
        var uv = (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
        if ((uv and 0x800000) != 0) {
            uv -= 0x1000000
        }
        samples.add(uv)
        offset += 3
    }
    return PolarPmdMeasurement.Ecg(
        sensorTimestampNs = timestampNs,
        samplesMicrovolts = samples,
    )
}

private fun parseAccFrame(data: ByteArray, timestampNs: Long, frameType: Int): PolarPmdMeasurement.Acc? {
    val isCompressed = (frameType and 0x80) != 0
    return if (!isCompressed) {
        val axisByteCount = when (frameType) {
            0x00 -> 1
            0x01 -> 2
            else -> return null
        }
        val samples = mutableListOf<PolarAccSample>()
        var offset = 10
        while (offset + axisByteCount * 3 <= data.size) {
            val x = if (axisByteCount == 1) data[offset].toInt() else getInt16LE(data, offset)
            val y = if (axisByteCount == 1) data[offset + 1].toInt() else getInt16LE(data, offset + 2)
            val z = if (axisByteCount == 1) data[offset + 2].toInt() else getInt16LE(data, offset + 4)
            samples.add(PolarAccSample(x, y, z))
            offset += axisByteCount * 3
        }
        PolarPmdMeasurement.Acc(
            sensorTimestampNs = timestampNs,
            samples = samples,
        )
    } else {
        if (frameType != 0x80 && frameType != 0x81) return null
        // Delta-compressed frame - frameType 0x80, 0x81
        if (data.size < 16) return null
        var currentX = getInt16LE(data, 10)
        var currentY = getInt16LE(data, 12)
        var currentZ = getInt16LE(data, 14)
        val samples = mutableListOf<PolarAccSample>()
        // The baseline sample at index 10..15 is the initial reference sample
        samples.add(PolarAccSample(currentX, currentY, currentZ))

        var offset = 16
        while (offset < data.size) {
            if (data.size - offset < 2) return null
            val deltaSizeBits = data[offset].toInt() and 0xFF
            val sampleCount = data[offset + 1].toInt() and 0xFF
            offset += 2
            if (deltaSizeBits > 16 || (deltaSizeBits == 0 && sampleCount > 0)) return null
            val blockByteCount = (sampleCount * deltaSizeBits * 3 + 7) / 8
            if (data.size - offset < blockByteCount) return null
            var bitPos = 0
            repeat(sampleCount) {
                currentX += readSignedBits(data, offset, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits
                currentY += readSignedBits(data, offset, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits
                currentZ += readSignedBits(data, offset, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits
                samples.add(PolarAccSample(currentX, currentY, currentZ))
            }
            offset += blockByteCount
        }

        PolarPmdMeasurement.Acc(
            sensorTimestampNs = timestampNs,
            samples = samples,
        )
    }
}

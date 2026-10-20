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
    val contactDetected = (flags and 0x06) == 0x06
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
 * - ACC (type 0x02, uncompressed 16-bit signed LE triplets or delta-compressed bit-packed frames)
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
    return when (frameType) {
        0x00 -> {
            // Uncompressed 16-bit signed LE samples (6 bytes per sample)
            val samples = mutableListOf<PolarAccSample>()
            var offset = 10
            while (offset + 6 <= data.size) {
                val x = getInt16LE(data, offset)
                val y = getInt16LE(data, offset + 2)
                val z = getInt16LE(data, offset + 4)
                samples.add(PolarAccSample(x, y, z))
                offset += 6
            }
            PolarPmdMeasurement.Acc(
                sensorTimestampNs = timestampNs,
                samples = samples,
            )
        }
        0x80 -> {
            // Delta-compressed frame
            if (data.size < 18) return null
            var currentX = getInt16LE(data, 10)
            var currentY = getInt16LE(data, 12)
            var currentZ = getInt16LE(data, 14)
            val deltaSizeBits = data[16].toInt() and 0xFF
            val sampleCount = data[17].toInt() and 0xFF

            val samples = mutableListOf<PolarAccSample>()
            // The baseline sample at index 10..15 is the initial reference sample
            samples.add(PolarAccSample(currentX, currentY, currentZ))

            if (deltaSizeBits == 0 || sampleCount == 0 || deltaSizeBits > 16) {
                return PolarPmdMeasurement.Acc(timestampNs, samples)
            }

            val totalAvailableBits = (data.size - 18) * 8
            var bitPos = 0
            for (i in 0 until sampleCount) {
                if (bitPos + (deltaSizeBits * 3) > totalAvailableBits) break
                val deltaX = readSignedBits(data, 18, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits
                val deltaY = readSignedBits(data, 18, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits
                val deltaZ = readSignedBits(data, 18, bitPos, deltaSizeBits)
                bitPos += deltaSizeBits

                currentX += deltaX
                currentY += deltaY
                currentZ += deltaZ
                samples.add(PolarAccSample(currentX, currentY, currentZ))
            }

            PolarPmdMeasurement.Acc(
                sensorTimestampNs = timestampNs,
                samples = samples,
            )
        }
        else -> null
    }
}

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.domain.model.HeuristicPhaseStatistics
import com.devil.phoenixproject.domain.model.HeuristicStatistics
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure byte parsing utility functions for the Vitruvian BLE protocol.
 *
 * These functions extract integers and floats from ByteArray data received
 * from the trainer hardware. All functions are stateless and handle endianness
 * and sign extension correctly.
 *
 * CRITICAL: All byte-to-int conversions MUST mask with `and 0xFF` to prevent
 * sign extension bugs (Kotlin bytes are signed, so 0xFF becomes -1 without masking).
 */

/**
 * Read unsigned 16-bit integer in LITTLE-ENDIAN format (LSB first).
 * This is the primary format used by the Vitruvian BLE protocol.
 *
 * @param data The byte array to read from
 * @param offset The starting position in the array
 * @return Unsigned value in range [0, 65535]
 */
fun getUInt16LE(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or
    ((data[offset + 1].toInt() and 0xFF) shl 8)

/**
 * Read signed 16-bit integer in LITTLE-ENDIAN format (LSB first).
 * Used for position values which can be negative (Issue #197).
 *
 * @param data The byte array to read from
 * @param offset The starting position in the array
 * @return Signed value in range [-32768, 32767]
 */
fun getInt16LE(data: ByteArray, offset: Int): Int {
    val unsigned = (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8)
    // Sign-extend from 16-bit to 32-bit
    return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
}

/**
 * Read unsigned 16-bit integer in BIG-ENDIAN format (MSB first).
 * Used for some packet types in the protocol.
 *
 * @param data The byte array to read from
 * @param offset The starting position in the array
 * @return Unsigned value in range [0, 65535]
 */
fun getUInt16BE(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or
    (data[offset + 1].toInt() and 0xFF)

/**
 * Read signed 32-bit integer in LITTLE-ENDIAN format.
 * Used for rep counters in 24-byte packets.
 *
 * @param data The byte array to read from
 * @param offset The starting position in the array
 * @return Signed 32-bit value
 */
fun getInt32LE(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or
    ((data[offset + 1].toInt() and 0xFF) shl 8) or
    ((data[offset + 2].toInt() and 0xFF) shl 16) or
    ((data[offset + 3].toInt() and 0xFF) shl 24)

/**
 * Read unsigned 32-bit integer in LITTLE-ENDIAN format.
 * Returned as Long because Kotlin Int cannot represent the full UInt range.
 */
fun getUInt32LE(data: ByteArray, offset: Int): Long = (data[offset].toLong() and 0xFFL) or
    ((data[offset + 1].toLong() and 0xFFL) shl 8) or
    ((data[offset + 2].toLong() and 0xFFL) shl 16) or
    ((data[offset + 3].toLong() and 0xFFL) shl 24)

/**
 * Read 32-bit float in LITTLE-ENDIAN format (IEEE 754).
 * Used for ROM boundaries in 24-byte rep packets.
 *
 * @param data The byte array to read from
 * @param offset The starting position in the array
 * @return Float value
 */
fun getFloatLE(data: ByteArray, offset: Int): Float {
    val bits = getInt32LE(data, offset)
    return Float.fromBits(bits)
}

/**
 * Convert a Byte to a two-character uppercase hex string.
 * KMP-compatible implementation (no Java dependencies).
 *
 * @return Two-character uppercase hex string (e.g., "FF", "0A", "00")
 */
fun Byte.toVitruvianHex(): String {
    val hex = "0123456789ABCDEF"
    val value = this.toInt() and 0xFF
    return "${hex[value shr 4]}${hex[value and 0x0F]}"
}

// ==================== PACKET PARSING FUNCTIONS ====================

/**
 * Parse rep packet data into RepNotification.
 *
 * Supports TWO formats for backwards compatibility (parent repo PR #190, Issue #187):
 *
 * LEGACY FORMAT (any 6..23 effective bytes — V-Form Trainer / Beta 4 / older firmware):
 * - Bytes 0-1: topCounter (u16 LE) — concentric completions
 * - Bytes 2-3: padding/unused
 * - Bytes 4-5: completeCounter (u16 LE) — eccentric completions
 *
 * MODERN FORMAT (>=24 effective bytes — Trainer+ / current firmware):
 * - Bytes 0-3:   up (u32 LE)        — concentric completions
 * - Bytes 4-7:   down (u32 LE)      — eccentric completions
 * - Bytes 8-11:  rangeTop (float LE)
 * - Bytes 12-15: rangeBottom (float LE)
 * - Bytes 16-17: repsRomCount (u16 LE) — warmup with proper ROM
 * - Bytes 18-19: repsRomTotal (u16 LE)
 * - Bytes 20-21: repsSetCount (u16 LE) — working set rep count
 * - Bytes 22-23: repsSetTotal (u16 LE)
 *
 * Issue #388 / #174 / #187: V-Form ("Vee_*") firmware sends rep packets in the 6..23 byte
 * range (most likely 16 bytes per official `Reps.read()` spec). Earlier Phoenix MP code
 * accepted only `==6` or `>=24` and returned null for everything else, regressing the
 * parent repo fix (VitruvianRedux PR #190 / commit 980df08) that timuh60/IshyEvenTrying
 * confirmed working in v0.6.2-beta. This restores the catch-all legacy branch.
 *
 * @param data The raw byte array
 * @param hasOpcodePrefix If true, data[0] is opcode and rep data starts at index 1
 * @param timestamp Timestamp to assign to the notification
 * @return RepNotification or null if data too short (<6 effective bytes)
 */
fun parseRepPacket(data: ByteArray, hasOpcodePrefix: Boolean, timestamp: Long): RepNotification? {
    val offset = if (hasOpcodePrefix) 1 else 0
    val effectiveSize = data.size - offset

    if (effectiveSize < 6) return null

    return if (effectiveSize >= 24) {
        // MODERN 24-byte format - parse all 8 fields
        val upCounter = getInt32LE(data, offset + 0)
        val downCounter = getInt32LE(data, offset + 4)
        val rangeTop = getFloatLE(data, offset + 8)
        val rangeBottom = getFloatLE(data, offset + 12)
        val repsRomCount = getUInt16LE(data, offset + 16)
        val repsRomTotal = getUInt16LE(data, offset + 18)
        val repsSetCount = getUInt16LE(data, offset + 20)
        val repsSetTotal = getUInt16LE(data, offset + 22)

        RepNotification(
            topCounter = upCounter,
            completeCounter = downCounter,
            repsRomCount = repsRomCount,
            repsRomTotal = repsRomTotal,
            repsSetCount = repsSetCount,
            repsSetTotal = repsSetTotal,
            rangeTop = rangeTop,
            rangeBottom = rangeBottom,
            rawData = data,
            timestamp = timestamp,
            isLegacyFormat = false,
        )
    } else {
        // LEGACY format — any 6..23 byte size from older firmware (V-Form / Beta 4).
        // topCounter at bytes 0-1, completeCounter at bytes 4-5; bytes 2-3 are padding.
        // Matches VitruvianRedux/VitruvianBleManager.kt handleRepNotification (PR #190).
        val topCounter = getUInt16LE(data, offset + 0)
        val completeCounter = getUInt16LE(data, offset + 4)

        RepNotification(
            topCounter = topCounter,
            completeCounter = completeCounter,
            repsRomCount = 0,
            repsRomTotal = 0,
            repsSetCount = 0,
            repsSetTotal = 0,
            rangeTop = 0f,
            rangeBottom = 0f,
            rawData = data,
            timestamp = timestamp,
            isLegacyFormat = true,
        )
    }
}

/**
 * Parse monitor characteristic data into MonitorPacket.
 *
 * Hardware-validated format (26 bytes, Little Endian) — confirmed 2026-02-17:
 * - (0-1):   ticks low (uint16)
 * - (2-3):   ticks high (uint16)
 * - (4-5):   posA (signed int16, /10.0f for mm)
 * - (6-7):   velA (signed int16, firmware velocity for cable A)
 * - (8-9):   loadA (uint16, /100.0f for kg)
 * - (10-11): posB (signed int16, /10.0f for mm)
 * - (12-13): velB (signed int16, firmware velocity for cable B)
 * - (14-15): loadB (uint16, /100.0f for kg)
 * - (16-17): status flags (uint16, optional)
 * - (18-25): unknown (8 bytes, captured for investigation)
 *
 * @param data The raw byte array
 * @return MonitorPacket or null if data too short
 */
fun parseMonitorPacket(data: ByteArray): MonitorPacket? {
    if (data.size < 16) return null

    val f0 = getUInt16LE(data, 0) // ticks low
    val f1 = getUInt16LE(data, 2) // ticks high
    val posARaw = getInt16LE(data, 4) // Signed 16-bit for position
    val velARaw = if (data.size >= 8) getInt16LE(data, 6) else 0 // Firmware velocity A
    val loadARaw = getUInt16LE(data, 8)
    val posBRaw = getInt16LE(data, 10) // Signed 16-bit for position
    val velBRaw = if (data.size >= 14) getInt16LE(data, 12) else 0 // Firmware velocity B
    val loadBRaw = getUInt16LE(data, 14)

    // Reconstruct 32-bit tick counter
    val ticks = f0 + (f1 shl 16)

    // Position values scaled to millimeters
    val posA = posARaw / 10.0f
    val posB = posBRaw / 10.0f

    // Load in kg (device sends kg * 100)
    val loadA = loadARaw / 100.0f
    val loadB = loadBRaw / 100.0f

    // Status flags (optional)
    val status = if (data.size >= 18) getUInt16LE(data, 16) else 0

    // Capture extra bytes beyond 18 for investigation
    val extra = if (data.size > 18) data.copyOfRange(18, data.size) else null

    return MonitorPacket(
        ticks = ticks,
        posA = posA,
        posB = posB,
        loadA = loadA,
        loadB = loadB,
        status = status,
        firmwareVelA = velARaw,
        firmwareVelB = velBRaw,
        extraBytes = extra,
    )
}

/**
 * Parse diagnostic characteristic data into DiagnosticPacket.
 *
 * Official app format (Little Endian):
 * - Empty payload: default/zero diagnostic snapshot
 * - Bytes 0-3: uptime seconds (uint32)
 * - Bytes 4-11: 4 fault codes (uint16)
 * - Bytes 12-17: 6 temperature readings (uint8)
 * - Bytes 18-19: optional 2 more temperature readings when total length implies a 20-byte base
 * - Next 52 bytes: optional crash seconds (uint32) + 48-byte crash stack payload
 * - Next 4 bytes: optional warnings (uint32)
 *
 * @param data The raw byte array
 * @return DiagnosticPacket or null if data too short
 */
@OptIn(ExperimentalEncodingApi::class)
fun parseDiagnosticPacket(data: ByteArray): DiagnosticPacket? {
    if (data.isEmpty()) {
        return DiagnosticPacket(
            runtimeSeconds = 0L,
            faultWords = emptyList(),
            temperatures = emptyList(),
            hasFaults = false,
        )
    }
    if (data.size < 18) return null

    val seconds = getUInt32LE(data, 0)

    val faultWords = mutableListOf<Int>()
    for (i in 0 until 4) {
        faultWords.add(getUInt16LE(data, 4 + (i * 2)))
    }

    val temperatures = mutableListOf<Int>()
    for (i in 0 until 6) {
        temperatures.add(data[12 + i].toInt() and 0xFF)
    }

    var offset = 18
    if (data.size >= 20 && data.size % 4 == 0) {
        temperatures.add(data[offset].toInt() and 0xFF)
        temperatures.add(data[offset + 1].toInt() and 0xFF)
        offset += 2
    }

    val crash = if (data.size - offset >= 52) {
        val crashSeconds = getUInt32LE(data, offset)
        val crashStack = data.copyOfRange(offset + 4, offset + 52)
        offset += 52
        DiagnosticCrash(
            seconds = crashSeconds,
            stackBase64 = Base64.Default.encode(crashStack),
        )
    } else {
        null
    }

    val warnings = if (data.size - offset >= 4) getUInt32LE(data, offset) else null

    val hasFaults = faultWords.any { it != 0 }

    return DiagnosticPacket(
        runtimeSeconds = seconds,
        faultWords = faultWords.toList(),
        temperatures = temperatures.toList(),
        hasFaults = hasFaults,
        crash = crash,
        warnings = warnings,
    )
}

/**
 * Parse heuristic characteristic data into HeuristicStatistics.
 *
 * Format (48 bytes, Little Endian):
 * - Bytes 0-23: 6 floats for concentric stats (kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax)
 * - Bytes 24-47: 6 floats for eccentric stats (same order)
 *
 * @param data The raw byte array
 * @param timestamp Timestamp to assign to the statistics
 * @return HeuristicStatistics or null if data too short
 */
fun parseHeuristicPacket(data: ByteArray, timestamp: Long): HeuristicStatistics? {
    if (data.size < 48) return null

    // Parse 6 floats for concentric stats (24 bytes)
    val concentric = HeuristicPhaseStatistics(
        kgAvg = getFloatLE(data, 0),
        kgMax = getFloatLE(data, 4),
        velAvg = getFloatLE(data, 8),
        velMax = getFloatLE(data, 12),
        wattAvg = getFloatLE(data, 16),
        wattMax = getFloatLE(data, 20),
    )

    // Parse 6 floats for eccentric stats (24 bytes)
    val eccentric = HeuristicPhaseStatistics(
        kgAvg = getFloatLE(data, 24),
        kgMax = getFloatLE(data, 28),
        velAvg = getFloatLE(data, 32),
        velMax = getFloatLE(data, 36),
        wattAvg = getFloatLE(data, 40),
        wattMax = getFloatLE(data, 44),
    )

    return HeuristicStatistics(
        concentric = concentric,
        eccentric = eccentric,
        timestamp = timestamp,
    )
}

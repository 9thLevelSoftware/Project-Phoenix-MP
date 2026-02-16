package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for ProtocolParser byte utility functions.
 *
 * These tests verify correct byte parsing for the Vitruvian BLE protocol,
 * including proper handling of endianness and sign extension.
 */
class ProtocolParserTest {

    // ========== getUInt16LE Tests ==========

    @Test
    fun `getUInt16LE parses basic little-endian value`() {
        // [0x01, 0x02] in LE = 0x0201 = 513
        val data = byteArrayOf(0x01, 0x02)
        assertEquals(513, getUInt16LE(data, 0))
    }

    @Test
    fun `getUInt16LE returns max unsigned value not negative`() {
        // [0xFF, 0xFF] should be 65535, not -1
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(65535, getUInt16LE(data, 0))
    }

    @Test
    fun `getUInt16LE handles offset correctly`() {
        // Skip first byte, read [0x34, 0x12] = 0x1234 = 4660
        val data = byteArrayOf(0x00, 0x34, 0x12)
        assertEquals(4660, getUInt16LE(data, 1))
    }

    // ========== getInt16LE Tests ==========

    @Test
    fun `getInt16LE parses positive value`() {
        // [0x00, 0x10] = 0x1000 = 4096
        val data = byteArrayOf(0x00, 0x10)
        assertEquals(4096, getInt16LE(data, 0))
    }

    @Test
    fun `getInt16LE returns negative one for all ones`() {
        // [0xFF, 0xFF] = -1 (sign extended)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, getInt16LE(data, 0))
    }

    @Test
    fun `getInt16LE returns min signed value`() {
        // [0x00, 0x80] = 0x8000 = -32768
        val data = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(-32768, getInt16LE(data, 0))
    }

    @Test
    fun `getInt16LE returns max signed value`() {
        // [0xFF, 0x7F] = 0x7FFF = 32767
        val data = byteArrayOf(0xFF.toByte(), 0x7F)
        assertEquals(32767, getInt16LE(data, 0))
    }

    // ========== getUInt16BE Tests ==========

    @Test
    fun `getUInt16BE parses basic big-endian value`() {
        // [0x01, 0x02] in BE = 0x0102 = 258
        val data = byteArrayOf(0x01, 0x02)
        assertEquals(258, getUInt16BE(data, 0))
    }

    @Test
    fun `getUInt16BE returns max unsigned value not negative`() {
        // [0xFF, 0xFF] should be 65535, not -1
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(65535, getUInt16BE(data, 0))
    }

    // ========== getInt32LE Tests ==========

    @Test
    fun `getInt32LE parses basic little-endian value`() {
        // [0x01, 0x02, 0x03, 0x04] = 0x04030201 = 67305985
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(67305985, getInt32LE(data, 0))
    }

    @Test
    fun `getInt32LE returns negative one for all ones`() {
        // [0xFF, 0xFF, 0xFF, 0xFF] = -1
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, getInt32LE(data, 0))
    }

    @Test
    fun `getInt32LE handles offset correctly`() {
        // Skip 2 bytes, read [0x78, 0x56, 0x34, 0x12] = 0x12345678 = 305419896
        val data = byteArrayOf(0x00, 0x00, 0x78, 0x56, 0x34, 0x12)
        assertEquals(305419896, getInt32LE(data, 2))
    }

    // ========== getFloatLE Tests ==========

    @Test
    fun `getFloatLE parses one point zero`() {
        // IEEE 754: 1.0f = 0x3F800000
        // Little-endian bytes: [0x00, 0x00, 0x80, 0x3F]
        val data = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F)
        assertEquals(1.0f, getFloatLE(data, 0))
    }

    @Test
    fun `getFloatLE parses three hundred`() {
        // IEEE 754: 300.0f = 0x43960000
        // Little-endian bytes: [0x00, 0x00, 0x96, 0x43]
        val data = byteArrayOf(0x00, 0x00, 0x96.toByte(), 0x43)
        assertEquals(300.0f, getFloatLE(data, 0))
    }

    @Test
    fun `getFloatLE parses negative value`() {
        // IEEE 754: -1.0f = 0xBF800000
        // Little-endian bytes: [0x00, 0x00, 0x80, 0xBF]
        val data = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0xBF.toByte())
        assertEquals(-1.0f, getFloatLE(data, 0))
    }

    // ========== toVitruvianHex Tests ==========

    @Test
    fun `toVitruvianHex formats max byte value`() {
        assertEquals("FF", 0xFF.toByte().toVitruvianHex())
    }

    @Test
    fun `toVitruvianHex pads single digit with zero`() {
        assertEquals("0A", 0x0A.toByte().toVitruvianHex())
    }

    @Test
    fun `toVitruvianHex formats zero`() {
        assertEquals("00", 0x00.toByte().toVitruvianHex())
    }

    @Test
    fun `toVitruvianHex uses uppercase letters`() {
        assertEquals("AB", 0xAB.toByte().toVitruvianHex())
    }

    // ==================== parseRepPacket Tests (Issue #210 critical) ====================

    @Test
    fun `parseRepPacket returns null for short data`() {
        // Less than 6 bytes should return null
        val data = byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x03)  // Only 5 bytes
        assertNull(parseRepPacket(data, hasOpcodePrefix = false, timestamp = 0L))
    }

    @Test
    fun `parseRepPacket returns null for short data with opcode prefix`() {
        // 6 bytes total but first is opcode, so only 5 bytes effective
        val data = byteArrayOf(0x02, 0x05, 0x00, 0x00, 0x00, 0x03)  // 6 bytes, first is opcode
        assertNull(parseRepPacket(data, hasOpcodePrefix = true, timestamp = 0L))
    }

    @Test
    fun `parseRepPacket parses legacy 6-byte format`() {
        // Legacy format: top=5, complete=3
        // [0x05, 0x00, 0x00, 0x00, 0x03, 0x00]
        val data = byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x03, 0x00)
        val result = parseRepPacket(data, hasOpcodePrefix = false, timestamp = 1000L)

        assertNotNull(result)
        assertTrue(result.isLegacyFormat)
        assertEquals(5, result.topCounter)
        assertEquals(3, result.completeCounter)
        assertEquals(0, result.repsRomCount)
        assertEquals(0, result.repsSetCount)
        assertEquals(1000L, result.timestamp)
    }

    @Test
    fun `parseRepPacket parses modern 24-byte format`() {
        // Modern 24-byte format with all fields populated
        // up=10, down=8, rangeTop=300.0f, rangeBottom=0.0f, repsRomCount=3, repsRomTotal=5, repsSetCount=7, repsSetTotal=10
        val data = byteArrayOf(
            // up (u32 LE): 10 = 0x0000000A
            0x0A, 0x00, 0x00, 0x00,
            // down (u32 LE): 8 = 0x00000008
            0x08, 0x00, 0x00, 0x00,
            // rangeTop (float LE): 300.0f = 0x43960000
            0x00, 0x00, 0x96.toByte(), 0x43,
            // rangeBottom (float LE): 0.0f = 0x00000000
            0x00, 0x00, 0x00, 0x00,
            // repsRomCount (u16 LE): 3
            0x03, 0x00,
            // repsRomTotal (u16 LE): 5
            0x05, 0x00,
            // repsSetCount (u16 LE): 7
            0x07, 0x00,
            // repsSetTotal (u16 LE): 10
            0x0A, 0x00
        )

        val result = parseRepPacket(data, hasOpcodePrefix = false, timestamp = 2000L)

        assertNotNull(result)
        assertFalse(result.isLegacyFormat)
        assertEquals(10, result.topCounter)
        assertEquals(8, result.completeCounter)
        assertEquals(300.0f, result.rangeTop)
        assertEquals(0.0f, result.rangeBottom)
        assertEquals(3, result.repsRomCount)
        assertEquals(5, result.repsRomTotal)
        assertEquals(7, result.repsSetCount)
        assertEquals(10, result.repsSetTotal)
        assertEquals(2000L, result.timestamp)
    }

    @Test
    fun `parseRepPacket handles opcode prefix correctly`() {
        // With opcode prefix (0x02), the rep data starts at index 1
        // 25 bytes total: 1 opcode + 24 rep data
        val data = byteArrayOf(
            0x02,  // opcode
            // up (u32 LE): 5
            0x05, 0x00, 0x00, 0x00,
            // down (u32 LE): 4
            0x04, 0x00, 0x00, 0x00,
            // rangeTop (float LE): 1.0f = 0x3F800000
            0x00, 0x00, 0x80.toByte(), 0x3F,
            // rangeBottom (float LE): 0.0f
            0x00, 0x00, 0x00, 0x00,
            // repsRomCount: 1
            0x01, 0x00,
            // repsRomTotal: 2
            0x02, 0x00,
            // repsSetCount: 3
            0x03, 0x00,
            // repsSetTotal: 4
            0x04, 0x00
        )

        val result = parseRepPacket(data, hasOpcodePrefix = true, timestamp = 3000L)

        assertNotNull(result)
        assertFalse(result.isLegacyFormat)
        assertEquals(5, result.topCounter)
        assertEquals(4, result.completeCounter)
        assertEquals(1.0f, result.rangeTop)
        assertEquals(1, result.repsRomCount)
        assertEquals(3, result.repsSetCount)
    }

    // ==================== parseMonitorPacket Tests ====================

    @Test
    fun `parseMonitorPacket returns null for short data`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)  // 15 bytes
        assertNull(parseMonitorPacket(data))
    }

    @Test
    fun `parseMonitorPacket parses position correctly`() {
        // 16-byte packet with positions
        // posA at offset 4-5 (signed), posB at offset 10-11 (signed)
        // posA raw = 1234 -> 123.4mm, posB raw = -567 -> -56.7mm
        val data = ByteArray(16)
        // posA = 1234 = 0x04D2 LE
        data[4] = 0xD2.toByte()
        data[5] = 0x04
        // posB = -567 = 0xFDC9 LE (two's complement)
        data[10] = 0xC9.toByte()
        data[11] = 0xFD.toByte()

        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(123.4f, result.posA, 0.01f)
        assertEquals(-56.7f, result.posB, 0.01f)
    }

    @Test
    fun `parseMonitorPacket parses load correctly`() {
        // loadA at offset 8-9 (unsigned), loadB at offset 14-15 (unsigned)
        // loadA raw = 5000 -> 50.0kg, loadB raw = 10000 -> 100.0kg
        val data = ByteArray(16)
        // loadA = 5000 = 0x1388 LE
        data[8] = 0x88.toByte()
        data[9] = 0x13
        // loadB = 10000 = 0x2710 LE
        data[14] = 0x10
        data[15] = 0x27

        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(50.0f, result.loadA, 0.01f)
        assertEquals(100.0f, result.loadB, 0.01f)
    }

    @Test
    fun `parseMonitorPacket parses status flags`() {
        // 18-byte packet with status at offset 16-17
        val data = ByteArray(18)
        // status = 0x00FF
        data[16] = 0xFF.toByte()
        data[17] = 0x00

        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(255, result.status)
    }

    @Test
    fun `parseMonitorPacket returns zero status for 16-byte packet`() {
        val data = ByteArray(16)  // No status bytes

        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(0, result.status)
    }

    @Test
    fun `parseMonitorPacket parses ticks correctly`() {
        // ticks = ticksLow + (ticksHigh << 16)
        // ticksLow at 0-1, ticksHigh at 2-3
        val data = ByteArray(16)
        // ticksLow = 0x1234
        data[0] = 0x34
        data[1] = 0x12
        // ticksHigh = 0x0001
        data[2] = 0x01
        data[3] = 0x00
        // Combined: 0x00011234 = 70196

        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(70196, result.ticks)
    }

    // ==================== parseDiagnosticPacket Tests ====================

    @Test
    fun `parseDiagnosticPacket returns null for short data`() {
        val data = ByteArray(19)  // Need 20 bytes minimum
        assertNull(parseDiagnosticPacket(data))
    }

    @Test
    fun `parseDiagnosticPacket detects faults`() {
        val data = ByteArray(20)
        // Set second fault code to non-zero: offset 6-7
        data[6] = 0x01
        data[7] = 0x00

        val result = parseDiagnosticPacket(data)

        assertNotNull(result)
        assertTrue(result.hasFaults)
        assertEquals(4, result.faults.size)
        assertEquals(0.toShort(), result.faults[0])
        assertEquals(1.toShort(), result.faults[1])
    }

    @Test
    fun `parseDiagnosticPacket parses no faults`() {
        val data = ByteArray(20)  // All zeros

        val result = parseDiagnosticPacket(data)

        assertNotNull(result)
        assertFalse(result.hasFaults)
        assertTrue(result.faults.all { it == 0.toShort() })
    }

    @Test
    fun `parseDiagnosticPacket parses temps`() {
        val data = ByteArray(20)
        // Set temperature readings at offsets 12-19
        data[12] = 25
        data[13] = 30
        data[14] = 35
        data[15] = 40
        data[16] = 45
        data[17] = 50
        data[18] = 55
        data[19] = 60

        val result = parseDiagnosticPacket(data)

        assertNotNull(result)
        assertEquals(8, result.temps.size)
        assertEquals(25.toByte(), result.temps[0])
        assertEquals(60.toByte(), result.temps[7])
    }

    @Test
    fun `parseDiagnosticPacket parses seconds`() {
        val data = ByteArray(20)
        // seconds = 3600 = 0x00000E10 LE
        data[0] = 0x10
        data[1] = 0x0E
        data[2] = 0x00
        data[3] = 0x00

        val result = parseDiagnosticPacket(data)

        assertNotNull(result)
        assertEquals(3600, result.seconds)
    }

    // ==================== parseHeuristicPacket Tests ====================

    @Test
    fun `parseHeuristicPacket returns null for short data`() {
        val data = ByteArray(47)  // Need 48 bytes minimum
        assertNull(parseHeuristicPacket(data, timestamp = 0L))
    }

    @Test
    fun `parseHeuristicPacket parses concentric stats`() {
        val data = ByteArray(48)
        // kgAvg at 0-3: 50.0f = 0x42480000
        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x48
        data[3] = 0x42
        // kgMax at 4-7: 80.0f = 0x42A00000
        data[4] = 0x00
        data[5] = 0x00
        data[6] = 0xA0.toByte()
        data[7] = 0x42

        val result = parseHeuristicPacket(data, timestamp = 5000L)

        assertNotNull(result)
        assertEquals(50.0f, result.concentric.kgAvg, 0.01f)
        assertEquals(80.0f, result.concentric.kgMax, 0.01f)
        assertEquals(5000L, result.timestamp)
    }

    @Test
    fun `parseHeuristicPacket parses eccentric stats`() {
        val data = ByteArray(48)
        // eccentric kgAvg at 24-27: 40.0f = 0x42200000
        data[24] = 0x00
        data[25] = 0x00
        data[26] = 0x20
        data[27] = 0x42
        // eccentric kgMax at 28-31: 60.0f = 0x42700000
        data[28] = 0x00
        data[29] = 0x00
        data[30] = 0x70
        data[31] = 0x42

        val result = parseHeuristicPacket(data, timestamp = 6000L)

        assertNotNull(result)
        assertEquals(40.0f, result.eccentric.kgAvg, 0.01f)
        assertEquals(60.0f, result.eccentric.kgMax, 0.01f)
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `handles exactly minimum size rep packet`() {
        // Exactly 6 bytes - should parse as legacy
        val data = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x00)
        val result = parseRepPacket(data, hasOpcodePrefix = false, timestamp = 0L)

        assertNotNull(result)
        assertTrue(result.isLegacyFormat)
    }

    @Test
    fun `handles exactly minimum size monitor packet`() {
        // Exactly 16 bytes
        val data = ByteArray(16)
        val result = parseMonitorPacket(data)

        assertNotNull(result)
        assertEquals(0, result.status)  // No status bytes
    }

    @Test
    fun `handles exactly minimum size diagnostic packet`() {
        // Exactly 20 bytes
        val data = ByteArray(20)
        val result = parseDiagnosticPacket(data)

        assertNotNull(result)
        assertEquals(4, result.faults.size)
        assertEquals(8, result.temps.size)
    }

    @Test
    fun `handles exactly minimum size heuristic packet`() {
        // Exactly 48 bytes
        val data = ByteArray(48)
        val result = parseHeuristicPacket(data, timestamp = 0L)

        assertNotNull(result)
    }
}

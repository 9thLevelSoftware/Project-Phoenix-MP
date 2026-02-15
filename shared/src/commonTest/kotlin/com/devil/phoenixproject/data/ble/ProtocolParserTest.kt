package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

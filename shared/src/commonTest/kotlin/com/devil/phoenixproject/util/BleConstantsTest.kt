package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BLE Constants - validates protocol values and configuration constants.
 */
class BleConstantsTest {

    // ========== Service UUID Tests ==========

    @Test
    fun `NUS service UUID follows Nordic format`() {
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BleConstants.NUS_SERVICE_UUID_STRING
        )
    }

    @Test
    fun `NUS RX characteristic UUID is correct`() {
        assertEquals(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            BleConstants.NUS_RX_CHAR_UUID_STRING
        )
    }

    // ========== Command ID Tests ==========

    @Test
    fun `STOP_COMMAND is 0x50`() {
        assertEquals(0x50.toByte(), BleConstants.Commands.STOP_COMMAND)
    }

    @Test
    fun `RESET_COMMAND is 0x0A`() {
        assertEquals(0x0A.toByte(), BleConstants.Commands.RESET_COMMAND)
    }

    @Test
    fun `REGULAR_COMMAND is 0x4F`() {
        assertEquals(0x4F.toByte(), BleConstants.Commands.REGULAR_COMMAND)
    }

    @Test
    fun `ECHO_COMMAND is 0x4E`() {
        assertEquals(0x4E.toByte(), BleConstants.Commands.ECHO_COMMAND)
    }

    @Test
    fun `ACTIVATION_COMMAND is 0x04`() {
        assertEquals(0x04.toByte(), BleConstants.Commands.ACTIVATION_COMMAND)
    }

    @Test
    fun `DEFAULT_ROM_REP_COUNT is 3`() {
        assertEquals(3.toByte(), BleConstants.Commands.DEFAULT_ROM_REP_COUNT)
    }

    // ========== Data Protocol Tests ==========

    @Test
    fun `POSITION_SCALE is 10`() {
        assertEquals(10.0, BleConstants.DataProtocol.POSITION_SCALE)
    }

    @Test
    fun `VELOCITY_SCALE is 10`() {
        assertEquals(10.0, BleConstants.DataProtocol.VELOCITY_SCALE)
    }

    @Test
    fun `FORCE_SCALE is 100`() {
        assertEquals(100.0, BleConstants.DataProtocol.FORCE_SCALE)
    }

    @Test
    fun `CABLE_DATA_SIZE is 6 bytes`() {
        assertEquals(6, BleConstants.DataProtocol.CABLE_DATA_SIZE)
    }

    @Test
    fun `SAMPLE_DATA_SIZE is 28 bytes`() {
        assertEquals(28, BleConstants.DataProtocol.SAMPLE_DATA_SIZE)
    }

    @Test
    fun `REPS_DATA_SIZE is 24 bytes`() {
        assertEquals(24, BleConstants.DataProtocol.REPS_DATA_SIZE)
    }

    // ========== Timeout Tests ==========

    @Test
    fun `CONNECTION_TIMEOUT_MS is 15 seconds`() {
        assertEquals(15000L, BleConstants.CONNECTION_TIMEOUT_MS)
    }

    @Test
    fun `GATT_OPERATION_TIMEOUT_MS is 5 seconds`() {
        assertEquals(5000L, BleConstants.GATT_OPERATION_TIMEOUT_MS)
    }

    @Test
    fun `SCAN_TIMEOUT_MS is 30 seconds`() {
        assertEquals(30000L, BleConstants.SCAN_TIMEOUT_MS)
    }

    // ========== Device Name Tests ==========

    @Test
    fun `DEVICE_NAME_PREFIX is Vee`() {
        assertEquals("Vee", BleConstants.DEVICE_NAME_PREFIX)
    }

    // ========== Notification Characteristics Tests ==========

    @Test
    fun `NOTIFY_CHAR_UUID_STRINGS contains expected characteristics`() {
        val notifyChars = BleConstants.NOTIFY_CHAR_UUID_STRINGS

        assertTrue(notifyChars.contains(BleConstants.MODE_CHAR_UUID_STRING))
        assertTrue(notifyChars.contains(BleConstants.REPS_CHAR_UUID_STRING))
        assertTrue(notifyChars.contains(BleConstants.VERSION_CHAR_UUID_STRING))
        assertTrue(notifyChars.contains(BleConstants.HEURISTIC_CHAR_UUID_STRING))
    }

    @Test
    fun `NOTIFY_CHAR_UUID_STRINGS has correct count`() {
        assertEquals(7, BleConstants.NOTIFY_CHAR_UUID_STRINGS.size)
    }
}

package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for BLE Constants - validates protocol values and configuration constants.
 */
class BleConstantsTest {

    // ========== Service UUID Tests ==========

    @Test
    fun `NUS service UUID follows Nordic format`() {
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BleConstants.NUS_SERVICE_UUID_STRING,
        )
    }

    @Test
    fun `NUS RX characteristic UUID is correct`() {
        assertEquals(
            "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
            BleConstants.NUS_RX_CHAR_UUID_STRING,
        )
    }

    // ========== Command ID Tests ==========

    @Test
    fun `RESET_COMMAND is 0x0A`() {
        assertEquals(0x0A.toByte(), BleConstants.Commands.RESET_COMMAND)
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

    @Test
    fun `activation packet force config offsets match firmware layout`() {
        // Eccentric-up profile tail (firmware layout).
        assertEquals(0x48, BleConstants.ActivationPacket.OFFSET_ECC_UP_MIN_MMS)
        assertEquals(0x4A, BleConstants.ActivationPacket.OFFSET_ECC_UP_MAX_MMS)
        assertEquals(0x4C, BleConstants.ActivationPacket.OFFSET_ECC_UP_RAMP)

        // Force config block (firmware layout).
        assertEquals(0x50, BleConstants.ActivationPacket.OFFSET_FORCE_MIN)
        assertEquals(0x54, BleConstants.ActivationPacket.OFFSET_FORCE_MAX)
        assertEquals(0x58, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT)
        assertEquals(0x5C, BleConstants.ActivationPacket.OFFSET_PROGRESSION)
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
}

package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticPacketStampTest {

    @Test
    fun `stampReceivedAt sets the receive time and leaves the parsed fields`() {
        val packet = DiagnosticPacket(
            runtimeSeconds = 42L,
            faultWords = listOf(4, 0, 0, 0),
            temperatures = listOf(25),
            hasFaults = true,
            crash = DiagnosticCrash(seconds = 3L, stackBase64 = "abc"),
            warnings = 7L,
        )

        val stamped = packet.stampReceivedAt(1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, stamped.receivedAtMillis)
        assertEquals(packet.copy(receivedAtMillis = 1_700_000_000_000L), stamped)
        assertEquals(0L, packet.receivedAtMillis)
    }

    @Test
    fun `stampReceivedAt replaces an existing receive time`() {
        val packet = DiagnosticPacket(
            runtimeSeconds = 1L,
            faultWords = emptyList(),
            temperatures = emptyList(),
            hasFaults = false,
            receivedAtMillis = 10L,
        )

        assertEquals(20L, packet.stampReceivedAt(20L).receivedAtMillis)
    }
}

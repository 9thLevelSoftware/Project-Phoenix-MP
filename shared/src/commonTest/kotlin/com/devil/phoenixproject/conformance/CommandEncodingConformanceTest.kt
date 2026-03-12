package com.devil.phoenixproject.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandEncodingConformanceTest {

    @Test
    fun `start and stop commands keep invariant framing`() {
        VendorConformanceTargets.selected().forEach { target ->
            val start = target.encodeStartCommand()
            val stop = target.encodeStopCommand()

            assertEquals(4, start.size, "${target.id}: start command must be 4 bytes")
            assertEquals(0x03.toByte(), start[0], "${target.id}: start opcode must be 0x03")
            assertEquals(4, stop.size, "${target.id}: stop command must be 4 bytes")
            assertEquals(0x05.toByte(), stop[0], "${target.id}: stop opcode must be 0x05")
        }
    }

    @Test
    fun `activation command keeps 96-byte layout invariant`() {
        VendorConformanceTargets.selected().forEach { target ->
            val activation = target.encodeActivationCommand()

            assertEquals(96, activation.size, "${target.id}: activation packet must be 96 bytes")
            assertEquals(0x04.toByte(), activation[0], "${target.id}: activation opcode must be 0x04")
            assertTrue(activation.any { it != 0.toByte() }, "${target.id}: activation packet should not be all zeros")
        }
    }
}

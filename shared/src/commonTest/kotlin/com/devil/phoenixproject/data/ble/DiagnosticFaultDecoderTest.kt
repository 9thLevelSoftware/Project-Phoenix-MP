package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticFaultDecoderTest {

    @Test
    fun `decodes controller fault labels`() {
        val expected = mapOf(
            0 to "None",
            1 to "Communication lost",
            2 to "Initialisation failure",
            4 to "Controller restarted",
            8 to "RX message failure",
            16 to "TX message failure",
            32 to "Firmware update failure",
            64 to "Over-temperature",
        )

        expected.forEach { (code, label) ->
            val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.CONTROLLER, code)
            assertEquals(label, decoded.label, "code=$code")
            assertEquals(formatDiagnosticFaultCode(code), decoded.rawHex)
        }
    }

    @Test
    fun `decodes combined controller fault bit flags`() {
        val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.CONTROLLER, 0x0043)
        val duplicateMessageFailureBits = decodeDiagnosticFault(DiagnosticFaultCategory.CONTROLLER, 0x0018)

        assertEquals("Communication lost, Initialisation failure, Over-temperature", decoded.label)
        assertEquals("0x0043", decoded.rawHex)
        assertTrue(decoded.hasFault)
        assertEquals("RX message failure, TX message failure", duplicateMessageFailureBits.label)
    }

    @Test
    fun `decodes motor fault labels`() {
        val expected = mapOf(
            0 to "None",
            1 to "Hardware overcurrent",
            2 to "Software overcurrent",
            4 to "Overvoltage",
            8 to "Undervoltage",
            16 to "Power module temperature",
            32 to "Gate driver fault",
            64 to "Board temperature",
            128 to "Kill switch",
            256 to "Alignment fault",
            512 to "Encoder fault",
            1024 to "Hardware/firmware mismatch",
            2048 to "EEPROM fault",
            4096 to "Motor over-temperature",
        )

        expected.forEach { (code, label) ->
            val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_B, code)
            assertEquals(label, decoded.label, "code=$code")
            assertEquals(formatDiagnosticFaultCode(code), decoded.rawHex)
        }
    }

    @Test
    fun `decodes combined motor fault bit flags`() {
        val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_A, 0x1005)

        assertEquals("Hardware overcurrent, Overvoltage, Motor over-temperature", decoded.label)
        assertEquals("0x1005", decoded.rawHex)
        assertTrue(decoded.hasFault)
    }

    @Test
    fun `decodes other fault labels`() {
        val none = decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, 0)
        val other = decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, 7)

        assertEquals("None", none.label)
        assertEquals("Other", other.label)
        assertFalse(none.hasFault)
        assertTrue(other.hasFault)
    }

    @Test
    fun `decodeDiagnosticFaults assigns categories by word index`() {
        val packet = DiagnosticPacket(
            runtimeSeconds = 1L,
            faultWords = listOf(4, 7, 4, 64),
            temperatures = emptyList(),
            hasFaults = true,
        )

        val faults = decodeDiagnosticFaults(packet)

        assertEquals(DiagnosticFaultCategory.CONTROLLER, faults[0].category)
        assertEquals("Controller restarted", faults[0].label)
        assertEquals(DiagnosticFaultCategory.OTHER, faults[1].category)
        assertEquals("Other", faults[1].label)
        assertEquals(DiagnosticFaultCategory.MOTOR_A, faults[2].category)
        assertEquals("Overvoltage", faults[2].label)
        assertEquals(DiagnosticFaultCategory.MOTOR_B, faults[3].category)
        assertEquals("Board temperature", faults[3].label)
    }
}

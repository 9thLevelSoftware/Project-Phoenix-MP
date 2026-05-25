package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticFaultDecoderTest {

    @Test
    fun `decodes official Vitruvian fault labels`() {
        val expected = mapOf(
            0 to "None",
            1 to "No comms",
            2 to "Init failure",
            4 to "TI restarted",
            8 to "Message failure",
            16 to "Message failure",
            32 to "Firmware update failure",
            64 to "Overtemp failure",
        )

        expected.forEach { (code, label) ->
            val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.VITRUVIAN, code)
            assertEquals(label, decoded.label, "code=$code")
            assertEquals(formatDiagnosticFaultCode(code), decoded.rawHex)
        }
    }

    @Test
    fun `decodes combined Vitruvian fault bitfields`() {
        val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.VITRUVIAN, 0x0005)
        val duplicateMessageFailureBits = decodeDiagnosticFault(DiagnosticFaultCategory.VITRUVIAN, 0x0018)

        assertEquals("No comms, TI restarted", decoded.label)
        assertEquals("0x0005", decoded.rawHex)
        assertEquals("Message failure", duplicateMessageFailureBits.label)
    }

    @Test
    fun `decodes official motor fault labels`() {
        val expected = mapOf(
            0 to "None",
            1 to "HW Overcurrent",
            2 to "SW Overcurrent",
            4 to "Over voltage",
            8 to "Under voltage",
            16 to "PIM temp",
            32 to "Gate driver",
            64 to "Bord Temp",
            128 to "Kill switch",
            256 to "Alignment",
            512 to "Encoder",
            1024 to "HW/FW mismatch",
            2048 to "EEPROM",
            4096 to "Motor overtemp",
        )

        expected.forEach { (code, label) ->
            val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_B, code)
            assertEquals(label, decoded.label, "code=$code")
            assertEquals(formatDiagnosticFaultCode(code), decoded.rawHex)
        }
    }

    @Test
    fun `decodes combined motor fault bitfields`() {
        val decoded = decodeDiagnosticFault(DiagnosticFaultCategory.MOTOR_A, 0x1005)

        assertEquals("HW Overcurrent, Over voltage, Motor overtemp", decoded.label)
        assertEquals("0x1005", decoded.rawHex)
    }

    @Test
    fun `decodes official other fault labels`() {
        val none = decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, 0)
        val other = decodeDiagnosticFault(DiagnosticFaultCategory.OTHER, 7)

        assertEquals("None", none.label)
        assertEquals("Other", other.label)
        assertFalse(none.hasFault)
        assertTrue(other.hasFault)
    }

    @Test
    fun `decodeDiagnosticFaults assigns official categories by word index`() {
        val packet = DiagnosticPacket(
            runtimeSeconds = 1L,
            faultWords = listOf(4, 7, 4, 64),
            temperatures = emptyList(),
            hasFaults = true,
        )

        val faults = decodeDiagnosticFaults(packet)

        assertEquals(DiagnosticFaultCategory.VITRUVIAN, faults[0].category)
        assertEquals("TI restarted", faults[0].label)
        assertEquals(DiagnosticFaultCategory.OTHER, faults[1].category)
        assertEquals("Other", faults[1].label)
        assertEquals(DiagnosticFaultCategory.MOTOR_A, faults[2].category)
        assertEquals("Over voltage", faults[2].label)
        assertEquals(DiagnosticFaultCategory.MOTOR_B, faults[3].category)
        assertEquals("Bord Temp", faults[3].label)
    }
}

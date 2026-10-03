package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlePacketCaptureTest {

    @Test
    fun `formatDualInterpretation logs both cables and hex of bytes 18 plus`() {
        val data = ByteArray(26)
        putUInt16LE(data, 0, 0x1234)
        putUInt16LE(data, 2, 0x0001)
        putInt16LE(data, 4, 1234) // posA 123.4 mm
        putInt16LE(data, 6, 7) // velA
        putUInt16LE(data, 8, 5000) // loadA 50.0 kg
        putInt16LE(data, 10, 2000) // posB 200.0 mm
        putInt16LE(data, 12, -3) // velB
        putUInt16LE(data, 14, 2500) // loadB 25.0 kg
        data[18] = 0xAA.toByte()
        data[19] = 0xBB.toByte()
        data[20] = 0xCC.toByte()
        data[21] = 0xDD.toByte()
        data[22] = 0xEE.toByte()
        data[23] = 0xFF.toByte()
        data[24] = 0x01
        data[25] = 0x02

        val line = BlePacketCapture.formatDualInterpretation(data, "IGNORED", 3)

        val packet = parseMonitorPacket(data)
        assertTrue(packet != null)
        assertEquals(123.4f, packet.posA, 0.01f)
        assertEquals(200.0f, packet.posB, 0.01f)
        assertEquals(50.0f, packet.loadA, 0.01f)
        assertEquals(25.0f, packet.loadB, 0.01f)
        assertEquals(
            "CAPTURE[3] 26B | " +
                "t=${packet.ticks} " +
                "posA=${packet.posA}mm velA=${packet.firmwareVelA} loadA=${packet.loadA}kg " +
                "posB=${packet.posB}mm velB=${packet.firmwareVelB} loadB=${packet.loadB}kg | " +
                "extra=[AA BB CC DD EE FF 01 02]",
            line,
        )
    }

    @Test
    fun `formatDualInterpretation logs both cables when bytes 18 plus are absent`() {
        val data = ByteArray(16)
        putInt16LE(data, 4, 100) // posA 10.0 mm
        putUInt16LE(data, 8, 1500) // loadA 15.0 kg
        putInt16LE(data, 10, -50) // posB -5.0 mm
        putUInt16LE(data, 14, 800) // loadB 8.0 kg

        val line = BlePacketCapture.formatDualInterpretation(data, "IGNORED", 1)
        val packet = parseMonitorPacket(data)
        assertTrue(packet != null)
        assertEquals(
            "CAPTURE[1] 16B | " +
                "t=${packet.ticks} " +
                "posA=${packet.posA}mm velA=${packet.firmwareVelA} loadA=${packet.loadA}kg " +
                "posB=${packet.posB}mm velB=${packet.firmwareVelB} loadB=${packet.loadB}kg | " +
                "extra=[none]",
            line,
        )
        assertTrue(line.contains("posB="))
        assertTrue(line.contains("loadB="))
    }

    @Test
    fun `formatDualInterpretation starts extra hex at byte 18`() {
        val eighteen = ByteArray(18) { 0x11 }
        val eighteenLine = BlePacketCapture.formatDualInterpretation(eighteen, "IGNORED", 2)
        assertTrue(eighteenLine.endsWith("extra=[none]"))

        val nineteen = ByteArray(19)
        nineteen[18] = 0x0F
        val nineteenLine = BlePacketCapture.formatDualInterpretation(nineteen, "IGNORED", 2)
        assertTrue(nineteenLine.endsWith("extra=[0F]"))
        assertTrue(nineteenLine.contains("posB="))
        assertTrue(nineteenLine.contains("loadB="))
    }

    @Test
    fun `formatDualInterpretation keeps the short packet message`() {
        val data = ByteArray(15) { 0x0A }
        val line = BlePacketCapture.formatDualInterpretation(data, "0A 0A", 4)

        assertEquals("CAPTURE[4] 15B: 0A 0A (too short for dual parse)", line)
    }

    private fun putUInt16LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putInt16LE(data: ByteArray, offset: Int, value: Int) = putUInt16LE(data, offset, value)
}

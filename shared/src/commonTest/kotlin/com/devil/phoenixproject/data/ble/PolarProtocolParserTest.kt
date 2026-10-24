package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.PolarAccSample
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolarProtocolParserTest {

    // ==================== Heart Rate (0x2A37) Tests ====================

    @Test
    fun `parsePolarHrMeasurement returns null for empty or truncated payload`() {
        assertNull(parsePolarHrMeasurement(byteArrayOf()))
        // Flag claims 16-bit HR (bit 0 = 1), but payload is only 2 bytes total
        assertNull(parsePolarHrMeasurement(byteArrayOf(0x01, 0x48)))
        // Flag claims energy present (bit 3 = 1), but energy bytes missing
        assertNull(parsePolarHrMeasurement(byteArrayOf(0x08, 0x48)))
    }

    @Test
    fun `parsePolarHrMeasurement parses basic 8-bit HR with contact detected`() {
        // Flags: 0x06 (bit 1=1, bit 2=1 -> contact detected), HR: 72 bpm
        val data = byteArrayOf(0x06, 72)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(72, result.hr)
        assertTrue(result.contactDetected)
        assertNull(result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parsePolarHrMeasurement handles contact status correctly`() {
        // Flags: 0x04 (contact supported but NOT detected)
        val notDetected = parsePolarHrMeasurement(byteArrayOf(0x04, 60))
        assertNotNull(notDetected)
        assertFalse(notDetected.contactDetected)

        // Flags: 0x00 (contact not supported)
        val notSupported = parsePolarHrMeasurement(byteArrayOf(0x00, 60))
        assertNotNull(notSupported)
        assertFalse(notSupported.contactDetected)
    }

    @Test
    fun `parsePolarHrMeasurement parses 16-bit HR`() {
        // Flags: 0x01 (16-bit HR format) | 0x06 (contact detected) = 0x07
        // HR: 280 bpm (0x0118 -> LE [0x18, 0x01])
        val data = byteArrayOf(0x07, 0x18, 0x01)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(280, result.hr)
        assertTrue(result.contactDetected)
    }

    @Test
    fun `parsePolarHrMeasurement parses energy expended`() {
        // Flags: 0x08 (energy present) | 0x06 (contact detected) = 0x0E
        // HR: 80 bpm, Energy: 1500 kJ (0x05DC -> LE [0xDC, 0x05])
        val data = byteArrayOf(0x0E, 80, 0xDC.toByte(), 0x05)
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(80, result.hr)
        assertEquals(1500, result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parsePolarHrMeasurement parses RR intervals accurately in milliseconds`() {
        // Flags: 0x10 (RR present) | 0x06 (contact detected) = 0x16
        // HR: 60 bpm
        // RR 1: 1024 raw units = exactly 1000.0 ms (0x0400 -> LE [0x00, 0x04])
        // RR 2: 819 raw units = (819 * 1000.0) / 1024.0 ≈ 799.8046875 ms (0x0333 -> LE [0x33, 0x03])
        val data = byteArrayOf(
            0x16,
            60,
            0x00, 0x04,
            0x33, 0x03,
        )
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(60, result.hr)
        assertEquals(2, result.rrIntervalsMs.size)
        assertEquals(1000.0, result.rrIntervalsMs[0], 0.001)
        assertEquals((819 * 1000.0) / 1024.0, result.rrIntervalsMs[1], 0.001)
    }

    @Test
    fun `parsePolarHrMeasurement parses complete packet with 16-bit HR, energy, and multiple RR intervals`() {
        // Flags: 0x01 (16-bit HR) | 0x06 (contact detected) | 0x08 (energy) | 0x10 (RR) = 0x1F
        // HR: 120 bpm (0x0078 -> [0x78, 0x00])
        // Energy: 250 kJ (0x00FA -> [0xFA, 0x00])
        // RR 1: 512 raw = 500.0 ms (0x0200 -> [0x00, 0x02])
        val data = byteArrayOf(
            0x1F,
            0x78, 0x00,
            0xFA.toByte(), 0x00,
            0x00, 0x02,
        )
        val result = parsePolarHrMeasurement(data)

        assertNotNull(result)
        assertEquals(120, result.hr)
        assertTrue(result.contactDetected)
        assertEquals(250, result.energyExpendedKj)
        assertEquals(1, result.rrIntervalsMs.size)
        assertEquals(500.0, result.rrIntervalsMs[0], 0.001)
    }

    // ==================== Battery Level (0x2A19) Tests ====================

    @Test
    fun `parsePolarBatteryLevel parses valid percentage`() {
        assertNull(parsePolarBatteryLevel(byteArrayOf()))
        assertEquals(100, parsePolarBatteryLevel(byteArrayOf(100)))
        assertEquals(85, parsePolarBatteryLevel(byteArrayOf(85)))
        assertEquals(0, parsePolarBatteryLevel(byteArrayOf(0)))
        // Clamping out-of-range values
        assertEquals(100, parsePolarBatteryLevel(byteArrayOf(120)))
    }

    // ==================== Bit Unpacking & 64-bit LE Tests ====================

    @Test
    fun `getInt64LE parses 64-bit little endian timestamp`() {
        val data = byteArrayOf(
            0x00, // offset padding
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        val expected = 0x0807060504030201L
        assertEquals(expected, getInt64LE(data, 1))
    }

    @Test
    fun `readSignedBits parses positive and negative signed integers across bit boundaries`() {
        // Byte 0: 0xFA (1111 1010)
        // 4-bit signed:
        // bits 0..3: 1010b = -6
        // bits 4..7: 1111b = -1
        val data = byteArrayOf(0xFA.toByte())
        assertEquals(-6, readSignedBits(data, 0, 0, 4))
        assertEquals(-1, readSignedBits(data, 0, 4, 4))

        // 8-bit signed:
        assertEquals(-6, readSignedBits(byteArrayOf(0xFA.toByte()), 0, 0, 8))
        assertEquals(125, readSignedBits(byteArrayOf(125), 0, 0, 8))

        // 10-bit signed spanning across byte boundaries:
        // Value: -5 (10-bit: 0x3FB -> 11 1111 1011b)
        // LE bytes: Byte 0 = 0xFB (1111 1011b), Byte 1 = 0x03 (0000 0011b)
        val tenBitData = byteArrayOf(0xFB.toByte(), 0x03)
        assertEquals(-5, readSignedBits(tenBitData, 0, 0, 10))
    }

    // ==================== PMD ECG (FB005C82) Tests ====================

    @Test
    fun `parsePolarPmdData rejects packets with invalid length or unknown type`() {
        // Less than 10 bytes
        assertNull(parsePolarPmdData(ByteArray(9)))
        // Unknown measurement type 0xFF
        val unknownType = ByteArray(10).apply { this[0] = 0xFF.toByte() }
        assertNull(parsePolarPmdData(unknownType))
    }

    @Test
    fun `parsePolarPmdData parses uncompressed 24-bit ECG samples in microvolts`() {
        // Type 0x00 (ECG), Timestamp: 1000_000_000 ns (0x3B9ACA00), FrameType: 0x00
        // Samples:
        // Sample 0: 0 µV ([0x00, 0x00, 0x00])
        // Sample 1: +1000 µV (0x0003E8 -> [0xE8, 0x03, 0x00])
        // Sample 2: -1000 µV (24-bit two's complement: 0x1000000 - 1000 = 0xFFFF18 -> [0x18, 0xFF, 0xFF])
        val bytes = ByteArray(10 + 9).apply {
            this[0] = 0x00 // ECG
            // Timestamp ns: 1000 ns (0x00000000000003E8)
            this[1] = 0xE8.toByte()
            this[2] = 0x03
            // FrameType: 0x00 (uncompressed)
            this[9] = 0x00

            // Sample 0: 0 µV
            this[10] = 0x00
            this[11] = 0x00
            this[12] = 0x00

            // Sample 1: +1000 µV
            this[13] = 0xE8.toByte()
            this[14] = 0x03
            this[15] = 0x00

            // Sample 2: -1000 µV
            this[16] = 0x18
            this[17] = 0xFC.toByte()
            this[18] = 0xFF.toByte()
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val ecg = assertIs<PolarPmdMeasurement.Ecg>(result)
        assertEquals(1000L, ecg.sensorTimestampNs)
        assertEquals(3, ecg.samplesMicrovolts.size)
        assertEquals(0, ecg.samplesMicrovolts[0])
        assertEquals(1000, ecg.samplesMicrovolts[1])
        assertEquals(-1000, ecg.samplesMicrovolts[2])
    }

    // ==================== PMD ACC (FB005C82) Tests ====================

    @Test
    fun `parsePolarPmdData parses uncompressed 16-bit ACC samples`() {
        // Type 0x02 (ACC), Timestamp: 5000 ns, FrameType: 0x00
        // Sample 0: X=100, Y=-200, Z=980 (1g down)
        val bytes = ByteArray(10 + 6).apply {
            this[0] = 0x02 // ACC
            this[1] = 0x88.toByte()
            this[2] = 0x13 // 5000L = 0x1388
            this[9] = 0x00 // uncompressed

            // X = 100 (0x0064)
            this[10] = 0x64
            this[11] = 0x00
            // Y = -200 (0xFF38)
            this[12] = 0x38
            this[13] = 0xFF.toByte()
            // Z = 980 (0x03D4)
            this[14] = 0xD4.toByte()
            this[15] = 0x03
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(5000L, acc.sensorTimestampNs)
        assertEquals(1, acc.samples.size)
        assertEquals(PolarAccSample(xMg = 100, yMg = -200, zMg = 980), acc.samples[0])
    }

    @Test
    fun `parsePolarPmdData parses delta-compressed ACC samples`() {
        // Type 0x02 (ACC), Timestamp: 2000L, FrameType: 0x80 (delta-compressed)
        // Reference sample: X=1000, Y=0, Z=-1000
        // Delta size: 8 bits
        // Sample count: 2 delta samples
        // Delta 1: dX = 5, dY = -10, dZ = 20 -> sample 1: (1005, -10, -980)
        // Delta 2: dX = -5, dY = 15, dZ = -20 -> sample 2: (1000, 5, -1000)
        val bytes = ByteArray(18 + 6).apply {
            this[0] = 0x02
            this[1] = 0xD0.toByte()
            this[2] = 0x07 // 2000L = 0x07D0
            this[9] = 0x80.toByte() // delta-compressed

            // Ref X = 1000 (0x03E8)
            this[10] = 0xE8.toByte()
            this[11] = 0x03
            // Ref Y = 0 (0x0000)
            this[12] = 0x00
            this[13] = 0x00
            // Ref Z = -1000 (0xFC18)
            this[14] = 0x18
            this[15] = 0xFC.toByte()

            this[16] = 8 // deltaSizeBits = 8
            this[17] = 2 // sampleCount = 2

            // Deltas sample 1: dX=5, dY=-10, dZ=20
            this[18] = 5
            this[19] = (-10).toByte()
            this[20] = 20

            // Deltas sample 2: dX=-5, dY=15, dZ=-20
            this[21] = (-5).toByte()
            this[22] = 15
            this[23] = (-20).toByte()
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(2000L, acc.sensorTimestampNs)
        // Base sample + 2 delta samples = 3 samples total
        assertEquals(3, acc.samples.size)
        assertEquals(PolarAccSample(1000, 0, -1000), acc.samples[0])
        assertEquals(PolarAccSample(1005, -10, -980), acc.samples[1])
        assertEquals(PolarAccSample(1000, 5, -1000), acc.samples[2])
    }

    @Test
    fun `parsePolarPmdData handles delta-compressed ACC with zero deltas`() {
        val bytes = ByteArray(18).apply {
            this[0] = 0x02
            this[9] = 0x80.toByte()
            // Ref X=50, Y=50, Z=50
            this[10] = 50; this[11] = 0
            this[12] = 50; this[13] = 0
            this[14] = 50; this[15] = 0
            this[16] = 0 // deltaSizeBits = 0
            this[17] = 0 // sampleCount = 0
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(1, acc.samples.size)
        assertEquals(PolarAccSample(50, 50, 50), acc.samples[0])
    }

    @Test
    fun `parsePolarPmdData parses uncompressed 16-bit ACC samples with frameType 0x01`() {
        // Polar H10 16-bit ACC frames use subType 0x01 (bit 7 = 0 for uncompressed)
        val bytes = ByteArray(10 + 6).apply {
            this[0] = 0x02 // ACC
            this[1] = 0x88.toByte()
            this[2] = 0x13 // 5000L
            this[9] = 0x01 // uncompressed with subType 0x01

            // X = 100 (0x0064)
            this[10] = 0x64
            this[11] = 0x00
            // Y = -200 (0xFF38)
            this[12] = 0x38
            this[13] = 0xFF.toByte()
            // Z = 980 (0x03D4)
            this[14] = 0xD4.toByte()
            this[15] = 0x03
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(5000L, acc.sensorTimestampNs)
        assertEquals(1, acc.samples.size)
        assertEquals(PolarAccSample(xMg = 100, yMg = -200, zMg = 980), acc.samples[0])
    }

    @Test
    fun `parsePolarPmdData parses delta-compressed ACC samples with frameType 0x81`() {
        // Polar H10 delta-compressed 16-bit ACC frames use subType 0x01 with bit 7 = 1 -> 0x81
        val bytes = ByteArray(18 + 6).apply {
            this[0] = 0x02 // ACC
            this[1] = 0xD0.toByte()
            this[2] = 0x07 // 2000L
            this[9] = 0x81.toByte() // delta-compressed (0x80 | 0x01)

            // Ref X = 1000 (0x03E8)
            this[10] = 0xE8.toByte()
            this[11] = 0x03
            // Ref Y = 0 (0x0000)
            this[12] = 0x00
            this[13] = 0x00
            // Ref Z = -1000 (0xFC18)
            this[14] = 0x18
            this[15] = 0xFC.toByte()

            this[16] = 8 // deltaSizeBits = 8
            this[17] = 2 // sampleCount = 2

            // Deltas sample 1: dX=5, dY=-10, dZ=20
            this[18] = 5
            this[19] = (-10).toByte()
            this[20] = 20

            // Deltas sample 2: dX=-5, dY=15, dZ=-20
            this[21] = (-5).toByte()
            this[22] = 15
            this[23] = (-20).toByte()
        }

        val result = parsePolarPmdData(bytes)
        assertNotNull(result)
        val acc = assertIs<PolarPmdMeasurement.Acc>(result)
        assertEquals(2000L, acc.sensorTimestampNs)
        assertEquals(3, acc.samples.size)
        assertEquals(PolarAccSample(1000, 0, -1000), acc.samples[0])
        assertEquals(PolarAccSample(1005, -10, -980), acc.samples[1])
        assertEquals(PolarAccSample(1000, 5, -1000), acc.samples[2])
    }
}

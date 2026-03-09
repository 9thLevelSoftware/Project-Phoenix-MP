package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BLE Packet Factory - validates binary protocol frame construction
 * for Vitruvian device communication.
 */
class BlePacketFactoryTest {

    // ========== Helpers ==========

    /** Read a little-endian float from a byte array at the given offset. */
    private fun readFloatLE(buffer: ByteArray, offset: Int): Float {
        val bits = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    // ========== Init Command Tests ==========

    @Test
    fun `createInitCommand returns 4-byte init packet`() {
        val packet = BlePacketFactory.createInitCommand()

        assertEquals(4, packet.size)
        assertEquals(0x0A.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
    }

    @Test
    fun `createInitPreset returns 34-byte preset packet`() {
        val packet = BlePacketFactory.createInitPreset()

        assertEquals(34, packet.size)
        assertEquals(0x11.toByte(), packet[0])
    }

    // ========== Control Command Tests ==========

    @Test
    fun `createStartCommand returns 4-byte start packet`() {
        val packet = BlePacketFactory.createStartCommand()

        assertEquals(4, packet.size)
        assertEquals(0x03.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
    }

    @Test
    fun `createStopCommand returns 4-byte stop packet`() {
        val packet = BlePacketFactory.createStopCommand()

        assertEquals(4, packet.size)
        assertEquals(0x05.toByte(), packet[0])
    }

    @Test
    fun `createOfficialStopPacket returns 2-byte soft stop`() {
        val packet = BlePacketFactory.createOfficialStopPacket()

        assertEquals(2, packet.size)
        assertEquals(0x50.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
    }

    @Test
    fun `createResetCommand returns 4-byte reset packet`() {
        val packet = BlePacketFactory.createResetCommand()

        assertEquals(4, packet.size)
        assertEquals(0x0A.toByte(), packet[0])
        assertContentEquals(BlePacketFactory.createInitCommand(), packet)
    }

    // ========== Legacy Workout Command Tests ==========

    @Test
    fun `createWorkoutCommand returns 25-byte packet with mode and weight`() {
        val packet = BlePacketFactory.createWorkoutCommand(
            programMode = ProgramMode.OldSchool,
            weightPerCableKg = 20f,
            targetReps = 10
        )

        assertEquals(25, packet.size)
        assertEquals(BleConstants.Commands.REGULAR_COMMAND, packet[0])
        assertEquals(ProgramMode.OldSchool.modeValue.toByte(), packet[1])
        assertEquals(10.toByte(), packet[4])
    }

    @Test
    fun `createWorkoutCommand encodes weight in little-endian format`() {
        val packet = BlePacketFactory.createWorkoutCommand(
            programMode = ProgramMode.Pump,
            weightPerCableKg = 25.5f,
            targetReps = 12
        )

        val weightScaled = (25.5f * 100).toInt()
        assertEquals((weightScaled and 0xFF).toByte(), packet[2])
        assertEquals(((weightScaled shr 8) and 0xFF).toByte(), packet[3])
    }

    // ========== Program Parameters Tests ==========

    @Test
    fun `createProgramParams returns 96-byte frame`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(96, packet.size)
    }

    @Test
    fun `createProgramParams has command 0x04 at header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0x04.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
    }

    @Test
    fun `createProgramParams encodes reps at offset 0x04`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 12,
            warmupReps = 3,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(15.toByte(), packet[0x04])
    }

    @Test
    fun `createProgramParams uses 0xFF for Just Lift mode reps`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f,
            isJustLift = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0xFF.toByte(), packet[0x04])
    }

    @Test
    fun `createProgramParams uses 0xFF for AMRAP mode reps`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f,
            isAMRAP = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0xFF.toByte(), packet[0x04])
    }

    @Test
    fun `createProgramParams includes mode profile at offset 0x30`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Pump mode profile has non-zero values at offset 0x30
        assertTrue(packet[0x30] != 0.toByte() || packet[0x31] != 0.toByte())
    }

    // ========== Issue #262: softMax and increment at correct offsets ==========

    @Test
    fun `createProgramParams writes softMax at offset 0x48`() {
        val weight = 50f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(weight, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
    }

    @Test
    fun `createProgramParams writes increment at offset 0x4C`() {
        val progression = 2.5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(progression, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_INCREMENT))
        // Verify LE encoding: 2.5f = 0x40200000 → LE bytes [0x00, 0x00, 0x20, 0x40]
        assertEquals(0x00.toByte(), packet[0x4C])
        assertEquals(0x00.toByte(), packet[0x4D])
        assertEquals(0x20.toByte(), packet[0x4E])
        assertEquals(0x40.toByte(), packet[0x4F])
    }

    @Test
    fun `createProgramParams softMax and increment overwrite mode profile tail`() {
        // Verify that softMax/increment are written AFTER the mode profile copy,
        // overwriting the eccentric phase's last 8 bytes (0x48-0x4F)
        val weight = 35f
        val progression = 1.5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // softMax should be the target weight, NOT the OldSchool eccentric shorts (-260, -110)
        assertEquals(weight, readFloatLE(packet, 0x48))
        // increment should be progression, NOT the OldSchool eccentric smoothing (0.0f)
        assertEquals(progression, readFloatLE(packet, 0x4C))
    }

    @Test
    fun `createProgramParams AMRAP sets softMax to machine max`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isAMRAP = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
    }

    @Test
    fun `createProgramParams Just Lift sets softMax to machine max`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 25f,
            isJustLift = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
    }

    @Test
    fun `createProgramParams zero progression writes zero increment`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f,
            progressionRegressionKg = 0f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_INCREMENT))
    }

    @Test
    fun `createProgramParams still writes weight at legacy offsets`() {
        val weight = 40f
        val progression = 3f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        val adjustedWeight = weight - progression
        // effectiveKg at 0x54
        assertEquals(adjustedWeight + 10.0f, readFloatLE(packet, 0x54))
        // totalWeightKg at 0x58
        assertEquals(adjustedWeight, readFloatLE(packet, 0x58))
        // progression at 0x5C
        assertEquals(progression, readFloatLE(packet, 0x5C))
    }

    // ========== Echo Mode Tests ==========

    @Test
    fun `createEchoControl returns 32-byte frame`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.HARD)

        assertEquals(32, packet.size)
    }

    @Test
    fun `createEchoControl has command 0x4E at header`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.HARD)

        assertEquals(0x4E.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
    }

    @Test
    fun `createEchoControl encodes warmup reps at offset 0x04`() {
        val packet = BlePacketFactory.createEchoControl(
            level = EchoLevel.HARD,
            warmupReps = 5,
            targetReps = 8
        )

        assertEquals(5.toByte(), packet[0x04])
    }

    @Test
    fun `createEchoControl encodes target reps at offset 0x05`() {
        val packet = BlePacketFactory.createEchoControl(
            level = EchoLevel.HARD,
            warmupReps = 3,
            targetReps = 8
        )

        assertEquals(8.toByte(), packet[0x05])
    }

    @Test
    fun `createEchoControl uses 0xFF for Just Lift mode`() {
        val packet = BlePacketFactory.createEchoControl(
            level = EchoLevel.HARD,
            isJustLift = true
        )

        assertEquals(0xFF.toByte(), packet[0x05])
    }

    @Test
    fun `createEchoControl uses 0xFF for AMRAP mode`() {
        val packet = BlePacketFactory.createEchoControl(
            level = EchoLevel.HARD,
            isAMRAP = true
        )

        assertEquals(0xFF.toByte(), packet[0x05])
    }

    @Test
    fun `createEchoCommand delegates to createEchoControl`() {
        val packet = BlePacketFactory.createEchoCommand(
            level = EchoLevel.HARDER.levelValue,
            eccentricLoad = 75
        )

        assertEquals(32, packet.size)
        assertEquals(0x4E.toByte(), packet[0])
    }

    // ========== Color Scheme Tests ==========

    @Test
    fun `createColorScheme returns 34-byte frame`() {
        val colors = listOf(
            RGBColor(255, 0, 0),
            RGBColor(0, 255, 0),
            RGBColor(0, 0, 255)
        )

        val packet = BlePacketFactory.createColorScheme(0.4f, colors)

        assertEquals(34, packet.size)
    }

    @Test
    fun `createColorScheme has command 0x11 at header`() {
        val colors = listOf(
            RGBColor(255, 0, 0),
            RGBColor(0, 255, 0),
            RGBColor(0, 0, 255)
        )

        val packet = BlePacketFactory.createColorScheme(0.4f, colors)

        assertEquals(0x11.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
        assertEquals(0x00.toByte(), packet[2])
        assertEquals(0x00.toByte(), packet[3])
    }

    @Test
    fun `createColorScheme encodes colors at correct offsets`() {
        val colors = listOf(
            RGBColor(0xAA, 0xBB, 0xCC),
            RGBColor(0x11, 0x22, 0x33),
            RGBColor(0x44, 0x55, 0x66)
        )

        val packet = BlePacketFactory.createColorScheme(0.4f, colors)

        assertEquals(0xAA.toByte(), packet[16])
        assertEquals(0xBB.toByte(), packet[17])
        assertEquals(0xCC.toByte(), packet[18])
        assertEquals(0x11.toByte(), packet[19])
        assertEquals(0x22.toByte(), packet[20])
        assertEquals(0x33.toByte(), packet[21])
        assertEquals(0x44.toByte(), packet[22])
        assertEquals(0x55.toByte(), packet[23])
        assertEquals(0x66.toByte(), packet[24])
    }

    @Test
    fun `createColorSchemeCommand returns valid packet for scheme index`() {
        val packet = BlePacketFactory.createColorSchemeCommand(0)

        assertEquals(34, packet.size)
        assertEquals(0x11.toByte(), packet[0])
    }

    @Test
    fun `createColorSchemeCommand uses fallback for invalid index`() {
        val packet = BlePacketFactory.createColorSchemeCommand(999)

        assertEquals(34, packet.size)
        assertEquals(0x11.toByte(), packet[0])
    }

    // ========== Workout Mode Tests ==========

    @Test
    fun `createProgramParams handles all program modes`() {
        val modes = listOf(
            ProgramMode.OldSchool,
            ProgramMode.Pump,
            ProgramMode.TUT,
            ProgramMode.TUTBeast,
            ProgramMode.EccentricOnly
        )

        for (mode in modes) {
            val params = WorkoutParameters(
                programMode = mode,
                reps = 10,
                weightPerCableKg = 20f
            )

            val packet = BlePacketFactory.createProgramParams(params)

            assertEquals(96, packet.size, "Packet size should be 96 for mode $mode")
            assertEquals(0x04.toByte(), packet[0], "Command should be 0x04 for mode $mode")
        }
    }

    @Test
    fun `createEchoControl handles all echo levels`() {
        val levels = EchoLevel.entries

        for (level in levels) {
            val packet = BlePacketFactory.createEchoControl(level)

            assertEquals(32, packet.size, "Packet size should be 32 for level $level")
            assertEquals(0x4E.toByte(), packet[0], "Command should be 0x4E for level $level")
        }
    }
}

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

    /** Read a little-endian signed short from a byte array at the given offset. */
    private fun readShortLE(buffer: ByteArray, offset: Int): Short {
        val value = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8)
        return value.toShort()
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

    // ========== Activation force config offsets ==========

    @Test
    fun `createProgramParams writes forceMin at offset 0x50`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 50f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_FORCE_MIN))
    }

    @Test
    fun `createProgramParams writes forceMax at offset 0x54`() {
        val weight = 50f
        val progression = 2.5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(weight - progression + 10.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_FORCE_MAX))
    }

    @Test
    fun `createProgramParams writes softMax at firmware offset 0x48`() {
        val weight = 50f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Firmware reads softMax from 0x48 (Issue #262)
        assertEquals(weight, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
    }

    @Test
    fun `createProgramParams writes increment at firmware offset 0x4C`() {
        val progression = 2.5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Firmware reads increment from 0x4C (Issue #262)
        assertEquals(progression, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_INCREMENT))
    }

    @Test
    fun `createProgramParams writes target weight at offset 0x58`() {
        val weight = 35f
        val progression = 1.5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = progression
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // 0x58 must contain the actual target weight (adjustedWeight), not softMax
        assertEquals(weight - progression, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
    }

    @Test
    fun `createProgramParams Just Lift writes correct target weight at 0x58`() {
        val weight = 5f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            isJustLift = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Critical: 0x58 must have the actual weight, NOT softMax (100.0f)
        // This bug caused the machine to apply weight+10kg instead of the set weight
        assertEquals(weight, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
        // softMax goes to 0x48 for firmware
        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
    }

    @Test
    fun `createProgramParams AMRAP sets softMax to machine max at 0x48`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isAMRAP = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
        // Target weight at 0x58 must be the actual weight, not softMax
        assertEquals(30.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
    }

    @Test
    fun `createProgramParams Just Lift sets softMax to machine max at 0x48`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 25f,
            isJustLift = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
        // Target weight must be at 0x58, not softMax
        assertEquals(25.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
    }

    @Test
    fun `Issue #267 Just Lift packet preserves target and activation tail contract`() {
        val targetWeight = 42.5f
        val progression = 2.0f
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 8,
            warmupReps = 3,
            weightPerCableKg = targetWeight,
            progressionRegressionKg = progression,
            isJustLift = true
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Just Lift must carry the actual operating target at 0x58.
        assertEquals(targetWeight - progression, readFloatLE(packet, 0x58))

        // Protocol force/progression block must remain fully populated.
        assertEquals(0.0f, readFloatLE(packet, 0x50))
        assertEquals(targetWeight - progression + 10.0f, readFloatLE(packet, 0x54))
        assertEquals(targetWeight - progression, readFloatLE(packet, 0x58))
        assertEquals(progression, readFloatLE(packet, 0x5C))

        // For Just Lift, profile contract is OldSchool and tail bytes 0x48..0x4F are firmware force config.
        assertEquals(100.0f, readFloatLE(packet, 0x48))
        assertEquals(progression, readFloatLE(packet, 0x4C))
        assertEquals((-1300).toShort(), readShortLE(packet, 0x40))
        assertEquals((-1200).toShort(), readShortLE(packet, 0x42))
        assertEquals(100.0f, readFloatLE(packet, 0x44))
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
        assertEquals(0.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_PROGRESSION))
    }

    @Test
    fun `createProgramParams writes force config to correct offsets`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 40f,
            progressionRegressionKg = 3f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Firmware force config (0x48-0x4F)
        assertEquals(40.0f, readFloatLE(packet, 0x48))  // softMax = weightPerCableKg
        assertEquals(3.0f, readFloatLE(packet, 0x4C))   // increment = progression

        // Protocol force config (0x50-0x5F)
        assertEquals(0.0f, readFloatLE(packet, 0x50))   // forceMin
        assertEquals(47.0f, readFloatLE(packet, 0x54))   // forceMax = 40-3+10
        assertEquals(37.0f, readFloatLE(packet, 0x58))   // targetWeight = 40-3
        assertEquals(3.0f, readFloatLE(packet, 0x5C))   // progression
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

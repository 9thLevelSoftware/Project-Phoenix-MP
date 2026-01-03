package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BLE Packet Factory - validates binary protocol frame construction
 * for Vitruvian device communication.
 */
class BlePacketFactoryTest {

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
        // Reset is same as init
        assertContentEquals(BlePacketFactory.createInitCommand(), packet)
    }

    // ========== Legacy Workout Command Tests ==========

    @Test
    fun `createWorkoutCommand returns 25-byte packet with mode and weight`() {
        val packet = BlePacketFactory.createWorkoutCommand(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
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
            workoutType = WorkoutType.Program(ProgramMode.Pump),
            weightPerCableKg = 25.5f, // 2550 when scaled by 100
            targetReps = 12
        )

        // Weight 25.5kg * 100 = 2550 = 0x09F6 in LE: [0xF6, 0x09]
        val weightScaled = (25.5f * 100).toInt()
        assertEquals((weightScaled and 0xFF).toByte(), packet[2])
        assertEquals(((weightScaled shr 8) and 0xFF).toByte(), packet[3])
    }

    // ========== Program Parameters Tests ==========

    @Test
    fun `createProgramParams returns 96-byte frame`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(96, packet.size)
    }

    @Test
    fun `createProgramParams has command 0x04 at header`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
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
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 12,
            warmupReps = 3,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Total reps = working reps + warmup reps = 12 + 3 = 15
        assertEquals(15.toByte(), packet[0x04])
    }

    @Test
    fun `createProgramParams uses 0xFF for Just Lift mode reps`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
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
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
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
            workoutType = WorkoutType.Program(ProgramMode.Pump),
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Pump mode profile has non-zero values at offset 0x30
        // Mode profile is 32 bytes from 0x30 to 0x4F
        assertTrue(packet[0x30] != 0.toByte() || packet[0x31] != 0.toByte())
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

        // Command ID is stored as u32 LE = 0x0000004E
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
        // Legacy API test
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

        // Command ID stored as u32 LE = 0x00000011
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

        // Colors start at offset 16, each color is 3 bytes (RGB)
        // First set of 3 colors
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

        // Should fall back to first scheme
        assertEquals(34, packet.size)
        assertEquals(0x11.toByte(), packet[0])
    }

    // ========== Little-Endian Encoding Tests ==========

    @Test
    fun `program params encodes floats in little-endian format`() {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            weightPerCableKg = 20f,
            progressionRegressionKg = 2.5f
        )

        val packet = BlePacketFactory.createProgramParams(params)

        // Progression/regression is at offset 0x5c
        // 2.5f as IEEE 754 = 0x40200000, in LE: [0x00, 0x00, 0x20, 0x40]
        assertEquals(0x00.toByte(), packet[0x5c])
        assertEquals(0x00.toByte(), packet[0x5d])
        assertEquals(0x20.toByte(), packet[0x5e])
        assertEquals(0x40.toByte(), packet[0x5f])
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
                workoutType = WorkoutType.Program(mode),
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

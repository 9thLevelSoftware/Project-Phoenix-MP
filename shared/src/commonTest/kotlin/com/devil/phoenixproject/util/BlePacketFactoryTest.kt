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

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        assertEquals(96, packet.size)
    }

    @Test
    fun `createProgramParams has command 0x04 at header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 20f
        )

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

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

    // ========== Activation force config offsets (Issue #262) ==========

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

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

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

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        // Firmware reads increment from 0x4C (Issue #262)
        assertEquals(progression, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_INCREMENT))
        // Verify LE encoding: 2.5f = 0x40200000 -> LE bytes [0x00, 0x00, 0x20, 0x40]
        assertEquals(0x00.toByte(), packet[0x4C])
        assertEquals(0x00.toByte(), packet[0x4D])
        assertEquals(0x20.toByte(), packet[0x4E])
        assertEquals(0x40.toByte(), packet[0x4F])
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
        assertEquals(15.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_FORCE_MAX))
    }

    @Test
    fun `createProgramParams AMRAP sets softMax to machine max at 0x48`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isAMRAP = true
        )

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

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

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        assertEquals(100.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_SOFT_MAX))
        // Target weight must be at 0x58, not softMax
        assertEquals(25.0f, readFloatLE(packet, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT))
    }

    @Test
    fun `Issue 267 Just Lift packet preserves target and activation tail contract`() {
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

        // Test with OVERLAP variant (legacy firmware layout where force config overlaps profile)
        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        // Just Lift must carry the actual operating target at 0x58.
        assertEquals(targetWeight - progression, readFloatLE(packet, 0x58))

        // Protocol force/progression block must remain fully populated.
        assertEquals(0.0f, readFloatLE(packet, 0x50))
        assertEquals(targetWeight - progression + 10.0f, readFloatLE(packet, 0x54))
        assertEquals(targetWeight - progression, readFloatLE(packet, 0x58))
        assertEquals(progression, readFloatLE(packet, 0x5C))

        // For Just Lift OVERLAP variant, tail bytes 0x48..0x4F are firmware force config.
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

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        // Firmware force config (0x48-0x4F)
        assertEquals(40.0f, readFloatLE(packet, 0x48))  // softMax = weightPerCableKg
        assertEquals(3.0f, readFloatLE(packet, 0x4C))   // increment = progression

        // Protocol force config (0x50-0x5F)
        assertEquals(0.0f, readFloatLE(packet, 0x50))   // forceMin
        assertEquals(47.0f, readFloatLE(packet, 0x54))   // forceMax = 40-3+10
        assertEquals(37.0f, readFloatLE(packet, 0x58))   // targetWeight = 40-3
        assertEquals(3.0f, readFloatLE(packet, 0x5C))   // progression
    }

    @Test
    fun `createProgramParams non-overlap keeps profile tail bytes unchanged`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            weightPerCableKg = 40f,
            progressionRegressionKg = 3f
        )

        val packet = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.NON_OVERLAP
        )

        val expectedTail = byteArrayOf(
            0x9C.toByte(), 0xFF.toByte(),
            0xCE.toByte(), 0xFF.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x3F.toByte()
        )
        assertContentEquals(expectedTail, packet.copyOfRange(0x48, 0x50))
    }

    @Test
    fun `createProgramParams variant selection yields expected overlap and non-overlap layouts`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            weightPerCableKg = 40f,
            progressionRegressionKg = 3f
        )

        val nonOverlapPacket = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.NON_OVERLAP
        )
        val overlapPacket = BlePacketFactory.createProgramParams(
            params,
            variant = BlePacketFactory.ForceConfigVariant.OVERLAP
        )

        assertEquals(40.0f, readFloatLE(overlapPacket, 0x48))
        assertEquals(3.0f, readFloatLE(overlapPacket, 0x4C))

        assertEquals(37.0f, readFloatLE(nonOverlapPacket, 0x58))
        assertEquals(3.0f, readFloatLE(nonOverlapPacket, 0x5C))
        assertEquals(37.0f, readFloatLE(overlapPacket, 0x58))
        assertEquals(3.0f, readFloatLE(overlapPacket, 0x5C))

        assertTrue(nonOverlapPacket.copyOfRange(0x48, 0x50).contentEquals(overlapPacket.copyOfRange(0x48, 0x50)).not())
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

    // ========== Echo Mode: Official App Byte Parity Tests ==========
    // These tests verify Phoenix Echo packets match the official Vitruvian app byte-for-byte.
    // Reference: VitruvianDeobfuscated Yj/d.java (EchoConfiguration), Ek/C1516m.java (EchoForceConfig),
    //            Ek/C1517n.java (EchoPhase), Ek/P.java (CommandId.ECHO = 78 = 0x4E)

    /** Helper: read a little-endian unsigned short from a byte array */
    private fun readUShortLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `Echo HARD packet matches official app byte layout`() {
        val packet = BlePacketFactory.createEchoControl(
            level = EchoLevel.HARD,
            warmupReps = 3,
            targetReps = 2,
            eccentricPct = 75
        )

        // Header
        assertEquals(0x4E, packet[0].toInt() and 0xFF, "Command ID")
        assertEquals(0x00, packet[1].toInt() and 0xFF)
        assertEquals(0x00, packet[2].toInt() and 0xFF)
        assertEquals(0x00, packet[3].toInt() and 0xFF)

        // Rep counts
        assertEquals(3, packet[0x04].toInt(), "warmupReps / romRepCount")
        assertEquals(2, packet[0x05].toInt(), "targetReps / repCount")

        // EchoForceConfig fields (matching official C1516m serialization order)
        assertEquals(0, readUShortLE(packet, 0x06), "spotter (always 0)")
        assertEquals(75, readUShortLE(packet, 0x08), "eccentricOverload")
        assertEquals(50, readUShortLE(packet, 0x0A), "referenceMapBlend (always 50)")
        assertEquals(0.1f, readFloatLE(packet, 0x0C), "concentricDelayS (always 0.1)")

        // Concentric EchoPhase: HARD = velocity 50, duration = 50/50 = 1.0s
        assertEquals(1.0f, readFloatLE(packet, 0x10), "concentricDurationSeconds (50/50)")
        assertEquals(50.0f, readFloatLE(packet, 0x14), "concentricMaxVelocity (HARD=50)")

        // Eccentric EchoPhase: fixed in official app
        assertEquals(0.0f, readFloatLE(packet, 0x18), "eccentricDurationSeconds (always 0.0)")
        assertEquals(-200.0f, readFloatLE(packet, 0x1C), "eccentricMaxVelocity (official=-200.0)")
    }

    @Test
    fun `Echo HARDER packet has correct velocity parameters`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.HARDER, eccentricPct = 100)

        // HARDER = velocity 40, duration = 50/40 = 1.25s
        assertEquals(1.25f, readFloatLE(packet, 0x10), "concentricDurationSeconds (50/40)")
        assertEquals(40.0f, readFloatLE(packet, 0x14), "concentricMaxVelocity (HARDER=40)")
        assertEquals(-200.0f, readFloatLE(packet, 0x1C), "eccentricMaxVelocity")
    }

    @Test
    fun `Echo HARDEST packet has correct velocity parameters`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.HARDEST, eccentricPct = 100)

        // HARDEST = velocity 30, duration = 50/30 = 1.667s
        assertEquals(1.667f, readFloatLE(packet, 0x10), "concentricDurationSeconds (50/30)")
        assertEquals(30.0f, readFloatLE(packet, 0x14), "concentricMaxVelocity (HARDEST=30)")
        assertEquals(-200.0f, readFloatLE(packet, 0x1C), "eccentricMaxVelocity")
    }

    @Test
    fun `Echo EPIC packet has correct velocity parameters`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.EPIC, eccentricPct = 100)

        // EPIC = velocity 15, duration = 50/15 = 3.333s
        assertEquals(3.333f, readFloatLE(packet, 0x10), "concentricDurationSeconds (50/15)")
        assertEquals(15.0f, readFloatLE(packet, 0x14), "concentricMaxVelocity (EPIC=15)")
        assertEquals(-200.0f, readFloatLE(packet, 0x1C), "eccentricMaxVelocity")
    }

    @Test
    fun `Echo eccentric load clamped at 150 percent`() {
        val packet = BlePacketFactory.createEchoControl(EchoLevel.HARD, eccentricPct = 200)

        // Hardware safety: should clamp to 150%
        assertEquals(150, readUShortLE(packet, 0x08), "eccentricOverload clamped to 150%")
    }

    @Test
    fun `Echo all fixed fields match official app defaults`() {
        // Verify the fixed protocol values that never change across any Echo level
        for (level in EchoLevel.entries) {
            val packet = BlePacketFactory.createEchoControl(level, eccentricPct = 100)

            assertEquals(0, readUShortLE(packet, 0x06), "spotter should be 0 for $level")
            assertEquals(50, readUShortLE(packet, 0x0A), "referenceMapBlend should be 50 for $level")
            assertEquals(0.1f, readFloatLE(packet, 0x0C), "concentricDelayS should be 0.1 for $level")
            assertEquals(0.0f, readFloatLE(packet, 0x18), "eccentricDurationSeconds should be 0.0 for $level")
            assertEquals(-200.0f, readFloatLE(packet, 0x1C), "eccentricMaxVelocity should be -200.0 for $level")
        }
    }

    // ========== Old School Mode: Official App Byte Parity Tests ==========
    // Reference: VitruvianDeobfuscated Dk/e.java ordinal 4 (STATIC)
    // Official mode mapping: Phoenix OldSchool = Official STATIC

    @Test
    fun `Old School packet matches official app RepConfig header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            warmupReps = 3,
            weightPerCableKg = 50f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Command ID
        assertEquals(0x04.toByte(), packet[0x00], "command byte 0")
        assertEquals(0x00.toByte(), packet[0x01], "command byte 1")
        assertEquals(0x00.toByte(), packet[0x02], "command byte 2")
        assertEquals(0x00.toByte(), packet[0x03], "command byte 3")

        // RepCounts: total=reps+warmup=13, baseline=3, adaptive=3, pad=0
        assertEquals(13.toByte(), packet[0x04], "RepCounts.total")
        assertEquals(3.toByte(), packet[0x05], "RepCounts.baseline")
        assertEquals(3.toByte(), packet[0x06], "RepCounts.adaptive")
        assertEquals(0.toByte(), packet[0x07], "RepCounts.padding")

        // seedRange = 5.0f
        assertEquals(5.0f, readFloatLE(packet, 0x08), "seedRange")

        // top RepBound: threshold=5.0, drift=0.0
        assertEquals(5.0f, readFloatLE(packet, 0x0C), "top.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x10), "top.drift")

        // top.inner = L(250, 250), top.outer = L(200, 30)
        assertEquals(250.toShort(), readShortLE(packet, 0x14), "top.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x16), "top.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x18), "top.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x1A), "top.outer.mmMax")

        // bottom RepBound: threshold=5.0, drift=0.0
        assertEquals(5.0f, readFloatLE(packet, 0x1C), "bottom.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x20), "bottom.drift")

        // bottom.inner = L(250, 250), bottom.outer = L(200, 30) — default
        assertEquals(250.toShort(), readShortLE(packet, 0x24), "bottom.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x26), "bottom.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x28), "bottom.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x2A), "bottom.outer.mmMax")

        // safety = L(250, 80)
        assertEquals(250.toShort(), readShortLE(packet, 0x2C), "safety.mmPerM")
        assertEquals(80.toShort(), readShortLE(packet, 0x2E), "safety.mmMax")
    }

    @Test
    fun `Old School packet matches official app mode profile`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 50f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Concentric down ramp: C1507d(0, 20, 3.0f)
        assertEquals(0.toShort(), readShortLE(packet, 0x30), "conc.down.minMmS")
        assertEquals(20.toShort(), readShortLE(packet, 0x32), "conc.down.maxMmS")
        assertEquals(3.0f, readFloatLE(packet, 0x34), "conc.down.ramp")

        // Concentric up ramp: C1507d(75, 600, 50.0f)
        assertEquals(75.toShort(), readShortLE(packet, 0x38), "conc.up.minMmS")
        assertEquals(600.toShort(), readShortLE(packet, 0x3A), "conc.up.maxMmS")
        assertEquals(50.0f, readFloatLE(packet, 0x3C), "conc.up.ramp")

        // Eccentric down ramp: C1507d(-1300, -1200, 100.0f)
        assertEquals((-1300).toShort(), readShortLE(packet, 0x40), "ecc.down.minMmS")
        assertEquals((-1200).toShort(), readShortLE(packet, 0x42), "ecc.down.maxMmS")
        assertEquals(100.0f, readFloatLE(packet, 0x44), "ecc.down.ramp")

        // Eccentric up ramp: C1507d(-260, -110, 0.0f)
        assertEquals((-260).toShort(), readShortLE(packet, 0x48), "ecc.up.minMmS")
        assertEquals((-110).toShort(), readShortLE(packet, 0x4A), "ecc.up.maxMmS")
        assertEquals(0.0f, readFloatLE(packet, 0x4C), "ecc.up.ramp")
    }

    @Test
    fun `Old School packet matches official app force config`() {
        val weight = 50f
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = 0f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Force config: Gm.e(0.0f, 10.0f + weight), softMax=weight, increment=0
        assertEquals(0.0f, readFloatLE(packet, 0x50), "forces.min")
        assertEquals(60.0f, readFloatLE(packet, 0x54), "forces.max (10+weight)")
        assertEquals(50.0f, readFloatLE(packet, 0x58), "softMax (=weight)")
        assertEquals(0.0f, readFloatLE(packet, 0x5C), "increment (=progression)")
    }

    // ========== Pump Mode: Official App Byte Parity Tests ==========
    // Reference: VitruvianDeobfuscated Dk/e.java ordinal 3 (PUMP)

    @Test
    fun `Pump packet matches official app RepConfig header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            warmupReps = 3,
            weightPerCableKg = 30f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Command ID
        assertEquals(0x04.toByte(), packet[0x00], "command byte 0")

        // RepCounts: total=13, baseline=3, adaptive=3, pad=0 (default repConfig)
        assertEquals(13.toByte(), packet[0x04], "RepCounts.total")
        assertEquals(3.toByte(), packet[0x05], "RepCounts.baseline")
        assertEquals(3.toByte(), packet[0x06], "RepCounts.adaptive")
        assertEquals(0.toByte(), packet[0x07], "RepCounts.padding")

        // seedRange, thresholds, boundaries — all default values
        assertEquals(5.0f, readFloatLE(packet, 0x08), "seedRange")
        assertEquals(5.0f, readFloatLE(packet, 0x0C), "top.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x10), "top.drift")
        assertEquals(250.toShort(), readShortLE(packet, 0x14), "top.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x16), "top.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x18), "top.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x1A), "top.outer.mmMax")
        assertEquals(5.0f, readFloatLE(packet, 0x1C), "bottom.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x20), "bottom.drift")
        assertEquals(250.toShort(), readShortLE(packet, 0x24), "bottom.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x26), "bottom.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x28), "bottom.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x2A), "bottom.outer.mmMax")
        assertEquals(250.toShort(), readShortLE(packet, 0x2C), "safety.mmPerM")
        assertEquals(80.toShort(), readShortLE(packet, 0x2E), "safety.mmMax")
    }

    @Test
    fun `Pump packet matches official app mode profile`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            weightPerCableKg = 30f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Concentric down ramp: C1507d(50, 450, 10.0f)
        assertEquals(50.toShort(), readShortLE(packet, 0x30), "conc.down.minMmS")
        assertEquals(450.toShort(), readShortLE(packet, 0x32), "conc.down.maxMmS")
        assertEquals(10.0f, readFloatLE(packet, 0x34), "conc.down.ramp")

        // Concentric up ramp: C1507d(500, 600, 50.0f)
        assertEquals(500.toShort(), readShortLE(packet, 0x38), "conc.up.minMmS")
        assertEquals(600.toShort(), readShortLE(packet, 0x3A), "conc.up.maxMmS")
        assertEquals(50.0f, readFloatLE(packet, 0x3C), "conc.up.ramp")

        // Eccentric down ramp: C1507d(-700, -550, 1.0f)
        assertEquals((-700).toShort(), readShortLE(packet, 0x40), "ecc.down.minMmS")
        assertEquals((-550).toShort(), readShortLE(packet, 0x42), "ecc.down.maxMmS")
        assertEquals(1.0f, readFloatLE(packet, 0x44), "ecc.down.ramp")

        // Eccentric up ramp: C1507d(-100, -50, 1.0f)
        assertEquals((-100).toShort(), readShortLE(packet, 0x48), "ecc.up.minMmS")
        assertEquals((-50).toShort(), readShortLE(packet, 0x4A), "ecc.up.maxMmS")
        assertEquals(1.0f, readFloatLE(packet, 0x4C), "ecc.up.ramp")
    }

    @Test
    fun `Pump packet matches official app force config`() {
        val weight = 30f
        val params = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 10,
            weightPerCableKg = weight,
            progressionRegressionKg = 0f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0.0f, readFloatLE(packet, 0x50), "forces.min")
        assertEquals(40.0f, readFloatLE(packet, 0x54), "forces.max (10+weight)")
        assertEquals(30.0f, readFloatLE(packet, 0x58), "softMax (=weight)")
        assertEquals(0.0f, readFloatLE(packet, 0x5C), "increment (=progression)")
    }

    // ========== TUT Mode: Official App Byte Parity Tests ==========
    // Reference: VitruvianDeobfuscated Dk/e.java ordinals 0,1,2 (EXTERNAL/FOCUSED/PROGRESSION)
    // Phoenix TUT maps to official FOCUSED mode — identical activation profile values

    @Test
    fun `TUT packet matches official app RepConfig header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.TUT,
            reps = 8,
            warmupReps = 3,
            weightPerCableKg = 40f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0x04.toByte(), packet[0x00], "command byte 0")

        // RepCounts: total=11 (8+3), baseline=3, adaptive=3, pad=0
        assertEquals(11.toByte(), packet[0x04], "RepCounts.total")
        assertEquals(3.toByte(), packet[0x05], "RepCounts.baseline")
        assertEquals(3.toByte(), packet[0x06], "RepCounts.adaptive")
        assertEquals(0.toByte(), packet[0x07], "RepCounts.padding")

        // All default RepConfig values (TUT uses standard repConfig)
        assertEquals(5.0f, readFloatLE(packet, 0x08), "seedRange")
        assertEquals(5.0f, readFloatLE(packet, 0x0C), "top.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x10), "top.drift")
        assertEquals(250.toShort(), readShortLE(packet, 0x14), "top.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x16), "top.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x18), "top.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x1A), "top.outer.mmMax")
        assertEquals(5.0f, readFloatLE(packet, 0x1C), "bottom.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x20), "bottom.drift")
        assertEquals(250.toShort(), readShortLE(packet, 0x24), "bottom.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x26), "bottom.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x28), "bottom.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x2A), "bottom.outer.mmMax")
        assertEquals(250.toShort(), readShortLE(packet, 0x2C), "safety.mmPerM")
        assertEquals(80.toShort(), readShortLE(packet, 0x2E), "safety.mmMax")
    }

    @Test
    fun `TUT packet matches official app mode profile`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.TUT,
            reps = 8,
            weightPerCableKg = 40f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Concentric down ramp: C1507d(250, 350, 7.0f)
        assertEquals(250.toShort(), readShortLE(packet, 0x30), "conc.down.minMmS")
        assertEquals(350.toShort(), readShortLE(packet, 0x32), "conc.down.maxMmS")
        assertEquals(7.0f, readFloatLE(packet, 0x34), "conc.down.ramp")

        // Concentric up ramp: C1507d(450, 600, 50.0f)
        assertEquals(450.toShort(), readShortLE(packet, 0x38), "conc.up.minMmS")
        assertEquals(600.toShort(), readShortLE(packet, 0x3A), "conc.up.maxMmS")
        assertEquals(50.0f, readFloatLE(packet, 0x3C), "conc.up.ramp")

        // Eccentric down ramp: C1507d(-900, -700, 70.0f)
        assertEquals((-900).toShort(), readShortLE(packet, 0x40), "ecc.down.minMmS")
        assertEquals((-700).toShort(), readShortLE(packet, 0x42), "ecc.down.maxMmS")
        assertEquals(70.0f, readFloatLE(packet, 0x44), "ecc.down.ramp")

        // Eccentric up ramp: C1507d(-100, -50, 14.0f)
        assertEquals((-100).toShort(), readShortLE(packet, 0x48), "ecc.up.minMmS")
        assertEquals((-50).toShort(), readShortLE(packet, 0x4A), "ecc.up.maxMmS")
        assertEquals(14.0f, readFloatLE(packet, 0x4C), "ecc.up.ramp")
    }

    @Test
    fun `TUT packet matches official app force config`() {
        val weight = 40f
        val params = WorkoutParameters(
            programMode = ProgramMode.TUT,
            reps = 8,
            weightPerCableKg = weight,
            progressionRegressionKg = 0f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0.0f, readFloatLE(packet, 0x50), "forces.min")
        assertEquals(50.0f, readFloatLE(packet, 0x54), "forces.max (10+weight)")
        assertEquals(40.0f, readFloatLE(packet, 0x58), "softMax (=weight)")
        assertEquals(0.0f, readFloatLE(packet, 0x5C), "increment (=progression)")
    }

    // ========== Eccentric Only Mode: Official App Byte Parity Tests ==========
    // Reference: VitruvianDeobfuscated Dk/e.java ordinal 5 (ECCENTRIC)
    //
    // IMPORTANT: Eccentric mode uses a DIFFERENT RepConfig than other modes.
    // The official app overrides bottom.inner.mmPerM from 250 → 50, making
    // bottom-of-rep detection more sensitive for eccentric-focused training.

    @Test
    fun `EccentricOnly packet matches official app RepConfig header`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.EccentricOnly,
            reps = 6,
            warmupReps = 3,
            weightPerCableKg = 60f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0x04.toByte(), packet[0x00], "command byte 0")

        // RepCounts: total=9 (6+3), baseline=3, adaptive=3, pad=0
        assertEquals(9.toByte(), packet[0x04], "RepCounts.total")
        assertEquals(3.toByte(), packet[0x05], "RepCounts.baseline")
        assertEquals(3.toByte(), packet[0x06], "RepCounts.adaptive")
        assertEquals(0.toByte(), packet[0x07], "RepCounts.padding")

        // Standard header fields (same as default)
        assertEquals(5.0f, readFloatLE(packet, 0x08), "seedRange")
        assertEquals(5.0f, readFloatLE(packet, 0x0C), "top.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x10), "top.drift")
        assertEquals(250.toShort(), readShortLE(packet, 0x14), "top.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(packet, 0x16), "top.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x18), "top.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x1A), "top.outer.mmMax")

        // bottom RepBound — ECCENTRIC-SPECIFIC OVERRIDE
        assertEquals(5.0f, readFloatLE(packet, 0x1C), "bottom.threshold")
        assertEquals(0.0f, readFloatLE(packet, 0x20), "bottom.drift")
        // Official: bottom.inner = L(50, 250) — NOT the default L(250, 250)
        assertEquals(50.toShort(), readShortLE(packet, 0x24), "bottom.inner.mmPerM (ECCENTRIC=50)")
        assertEquals(250.toShort(), readShortLE(packet, 0x26), "bottom.inner.mmMax")
        assertEquals(200.toShort(), readShortLE(packet, 0x28), "bottom.outer.mmPerM")
        assertEquals(30.toShort(), readShortLE(packet, 0x2A), "bottom.outer.mmMax")

        assertEquals(250.toShort(), readShortLE(packet, 0x2C), "safety.mmPerM")
        assertEquals(80.toShort(), readShortLE(packet, 0x2E), "safety.mmMax")
    }

    @Test
    fun `EccentricOnly packet matches official app mode profile`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.EccentricOnly,
            reps = 6,
            weightPerCableKg = 60f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        // Concentric down ramp: C1507d(50, 550, 50.0f)
        assertEquals(50.toShort(), readShortLE(packet, 0x30), "conc.down.minMmS")
        assertEquals(550.toShort(), readShortLE(packet, 0x32), "conc.down.maxMmS")
        assertEquals(50.0f, readFloatLE(packet, 0x34), "conc.down.ramp")

        // Concentric up ramp: C1507d(650, 750, 10.0f)
        assertEquals(650.toShort(), readShortLE(packet, 0x38), "conc.up.minMmS")
        assertEquals(750.toShort(), readShortLE(packet, 0x3A), "conc.up.maxMmS")
        assertEquals(10.0f, readFloatLE(packet, 0x3C), "conc.up.ramp")

        // Eccentric down ramp: C1507d(-900, -700, 70.0f)
        assertEquals((-900).toShort(), readShortLE(packet, 0x40), "ecc.down.minMmS")
        assertEquals((-700).toShort(), readShortLE(packet, 0x42), "ecc.down.maxMmS")
        assertEquals(70.0f, readFloatLE(packet, 0x44), "ecc.down.ramp")

        // Eccentric up ramp: C1507d(-100, -50, 20.0f)
        assertEquals((-100).toShort(), readShortLE(packet, 0x48), "ecc.up.minMmS")
        assertEquals((-50).toShort(), readShortLE(packet, 0x4A), "ecc.up.maxMmS")
        assertEquals(20.0f, readFloatLE(packet, 0x4C), "ecc.up.ramp")
    }

    @Test
    fun `EccentricOnly packet matches official app force config`() {
        val weight = 60f
        val params = WorkoutParameters(
            programMode = ProgramMode.EccentricOnly,
            reps = 6,
            weightPerCableKg = weight,
            progressionRegressionKg = 0f
        )
        val packet = BlePacketFactory.createProgramParams(params)

        assertEquals(0.0f, readFloatLE(packet, 0x50), "forces.min")
        assertEquals(70.0f, readFloatLE(packet, 0x54), "forces.max (10+weight)")
        assertEquals(60.0f, readFloatLE(packet, 0x58), "softMax (=weight)")
        assertEquals(0.0f, readFloatLE(packet, 0x5C), "increment (=progression)")
    }

    // ========== Cross-Mode Regression Tests ==========

    @Test
    fun `all non-Echo modes produce 96-byte packets with correct command and RepConfig`() {
        val modes = listOf(
            ProgramMode.OldSchool to "OldSchool",
            ProgramMode.Pump to "Pump",
            ProgramMode.TUT to "TUT",
            ProgramMode.TUTBeast to "TUTBeast",
            ProgramMode.EccentricOnly to "EccentricOnly"
        )

        for ((mode, name) in modes) {
            val params = WorkoutParameters(
                programMode = mode,
                reps = 10,
                warmupReps = 3,
                weightPerCableKg = 50f
            )
            val packet = BlePacketFactory.createProgramParams(params)

            assertEquals(96, packet.size, "$name: packet size")
            assertEquals(0x04.toByte(), packet[0x00], "$name: command ID")
            assertEquals(13.toByte(), packet[0x04], "$name: reps total (10+3)")
            assertEquals(3.toByte(), packet[0x05], "$name: baseline")
            assertEquals(3.toByte(), packet[0x06], "$name: adaptive")
            assertEquals(5.0f, readFloatLE(packet, 0x08), "$name: seedRange")

            // All modes except Eccentric use default bottom.inner.mmPerM = 250
            val expectedBottomInnerMmPerM: Short = if (mode is ProgramMode.EccentricOnly) 50 else 250
            assertEquals(
                expectedBottomInnerMmPerM,
                readShortLE(packet, 0x24),
                "$name: bottom.inner.mmPerM"
            )
        }
    }

    @Test
    fun `Eccentric bottom RepBound differs from other modes`() {
        val eccentricPacket = BlePacketFactory.createProgramParams(
            WorkoutParameters(ProgramMode.EccentricOnly, reps = 10, weightPerCableKg = 50f)
        )
        val oldSchoolPacket = BlePacketFactory.createProgramParams(
            WorkoutParameters(ProgramMode.OldSchool, reps = 10, weightPerCableKg = 50f)
        )

        // Eccentric has mmPerM=50, OldSchool has mmPerM=250 at offset 0x24
        assertEquals(50.toShort(), readShortLE(eccentricPacket, 0x24), "Eccentric bottom.inner.mmPerM")
        assertEquals(250.toShort(), readShortLE(oldSchoolPacket, 0x24), "OldSchool bottom.inner.mmPerM")

        // All other bottom boundary fields should be identical
        assertEquals(
            readShortLE(eccentricPacket, 0x26),
            readShortLE(oldSchoolPacket, 0x26),
            "bottom.inner.mmMax should be same"
        )
        assertEquals(
            readShortLE(eccentricPacket, 0x28),
            readShortLE(oldSchoolPacket, 0x28),
            "bottom.outer.mmPerM should be same"
        )
        assertEquals(
            readShortLE(eccentricPacket, 0x2A),
            readShortLE(oldSchoolPacket, 0x2A),
            "bottom.outer.mmMax should be same"
        )
    }

    // ========== Force Config with Progression Tests ==========

    @Test
    fun `force config with zero progression matches official app for all modes`() {
        val modes = listOf(
            ProgramMode.OldSchool,
            ProgramMode.Pump,
            ProgramMode.TUT,
            ProgramMode.EccentricOnly
        )

        for (mode in modes) {
            val weight = 45f
            val packet = BlePacketFactory.createProgramParams(
                WorkoutParameters(mode, reps = 10, weightPerCableKg = weight)
            )

            assertEquals(0.0f, readFloatLE(packet, 0x50), "$mode: forces.min")
            assertEquals(55.0f, readFloatLE(packet, 0x54), "$mode: forces.max (10+weight)")
            assertEquals(45.0f, readFloatLE(packet, 0x58), "$mode: softMax (=weight)")
            assertEquals(0.0f, readFloatLE(packet, 0x5C), "$mode: increment (=0)")
        }
    }

}

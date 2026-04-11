package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.concurrent.Volatile

/**
 * BLE Packet Factory - Builds binary protocol frames for Vitruvian device communication
 * Ported from protocol.js and modes.js in the reference web application
 *
 * KMP-compatible version using manual byte manipulation (no java.nio.ByteBuffer)
 */
object BlePacketFactory {

    enum class ForceConfigVariant {
        NON_OVERLAP,
        OVERLAP,
    }

    @Volatile
    var defaultForceConfigVariant: ForceConfigVariant = ForceConfigVariant.NON_OVERLAP

    // ========== Little-Endian Byte Helpers ==========

    private fun putIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putShortLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putFloatLE(buffer: ByteArray, offset: Int, value: Float) {
        val bits = value.toRawBits()
        putIntLE(buffer, offset, bits)
    }

    // ========== Init Commands ==========

    /**
     * Creates an INIT/Reset command (0x0A) - 4 bytes.
     * Used to initialize or reset the device state.
     */
    fun createInitCommand(): ByteArray = byteArrayOf(0x0A, 0x00, 0x00, 0x00)

    /**
     * Build the INIT preset frame with coefficient table (34 bytes)
     */
    fun createInitPreset(): ByteArray = byteArrayOf(
        0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0xCD.toByte(), 0xCC.toByte(), 0xCC.toByte(), 0x3E.toByte(), // 0.4 as float32 LE
        0xFF.toByte(), 0x00, 0x4C, 0xFF.toByte(),
        0x23, 0x8C.toByte(), 0xFF.toByte(), 0x8C.toByte(),
        0x8C.toByte(), 0xFF.toByte(), 0x00, 0x4C,
        0xFF.toByte(), 0x23, 0x8C.toByte(), 0xFF.toByte(),
        0x8C.toByte(), 0x8C.toByte(),
    )

    // ========== Control Commands ==========

    /**
     * Creates a START command (4 bytes).
     * Signals the device to begin the configured workout.
     */
    fun createStartCommand(): ByteArray = byteArrayOf(0x03, 0x00, 0x00, 0x00)

    /**
     * Creates the primary STOP command (4 bytes).
     * NOTE: v0.5.0-beta used 0x05 and it WORKED for Just Lift autostop
     */
    fun createStopCommand(): ByteArray = byteArrayOf(0x05, 0x00, 0x00, 0x00)

    /**
     * Creates the Official App STOP_PACKET command (2 bytes).
     * Per official app analysis:
     * - Uses StopPacket (0x50 0x00) to end sessions and CLEAR FAULTS
     * - This is a "soft stop" that releases tension and clears the blinking red light fault state
     */
    fun createOfficialStopPacket(): ByteArray = byteArrayOf(0x50, 0x00)

    /**
     * Creates the RESET command (4 bytes).
     * This is what web apps use for stop (0x0A) - same as init command.
     * Use for recovery if device gets stuck.
     */
    fun createResetCommand(): ByteArray = byteArrayOf(0x0A, 0x00, 0x00, 0x00)

    // ========== Legacy Workout Command (backward compatibility) ==========

    /**
     * Creates a simplified workout command for backward compatibility.
     * For full protocol support, use createProgramParams() instead.
     */
    fun createWorkoutCommand(programMode: ProgramMode, weightPerCableKg: Float, targetReps: Int): ByteArray {
        val buffer = ByteArray(25)
        buffer[0] = BleConstants.Commands.REGULAR_COMMAND
        buffer[1] = programMode.modeValue.toByte()

        val weightScaled = (weightPerCableKg * 100).toInt()
        buffer[2] = (weightScaled and 0xFF).toByte()
        buffer[3] = ((weightScaled shr 8) and 0xFF).toByte()
        buffer[4] = targetReps.toByte()

        return buffer
    }

    // ========== Full Protocol: Program Mode ==========

    /**
     * Build the 96-byte activation/program parameters frame.
     *
     * Activation modes serialize a 32-byte mode profile at 0x30-0x4F, followed by
     * the force config block at 0x50-0x5F.
     * - NON_OVERLAP keeps 0x48-0x4F untouched so profile bytes are preserved.
     * - OVERLAP writes legacy softMax/increment at 0x48/0x4C when required.
     *
     * Issue #262: Firmware reads softMax (weight ceiling) at 0x48 and increment
     * (per-rep progression) at 0x4C. These offsets fall within the mode profile
     * block (0x30-0x4F), so we overwrite the last 8 bytes of the eccentric phase
     * with the correct force config values after copying the profile.
     */
    fun createProgramParams(params: WorkoutParameters, variant: ForceConfigVariant = defaultForceConfigVariant): ByteArray {
        val frame = ByteArray(96)

        // Header section - Command 0x04 for PROGRAM mode
        frame[0] = 0x04
        frame[1] = 0x00
        frame[2] = 0x00
        frame[3] = 0x00

        // Reps field at offset 0x04
        frame[0x04] =
            if (params.isJustLift ||
                params.isAMRAP
            ) {
                0xFF.toByte()
            } else {
                (params.reps + params.warmupReps).toByte()
            }

        frame[5] = 0x03
        frame[6] = 0x03
        frame[7] = 0x00

        putFloatLE(frame, 0x08, 5.0f)
        putFloatLE(frame, 0x0c, 5.0f)
        putFloatLE(frame, 0x1c, 5.0f)

        frame[0x14] = 0xFA.toByte()
        frame[0x15] = 0x00
        frame[0x16] = 0xFA.toByte()
        frame[0x17] = 0x00
        frame[0x18] = 0xC8.toByte()
        frame[0x19] = 0x00
        frame[0x1a] = 0x1E
        frame[0x1b] = 0x00

        frame[0x24] = 0xFA.toByte()
        frame[0x25] = 0x00
        frame[0x26] = 0xFA.toByte()
        frame[0x27] = 0x00
        frame[0x28] = 0xC8.toByte()
        frame[0x29] = 0x00
        frame[0x2a] = 0x1E
        frame[0x2b] = 0x00

        // Eccentric-specific RepConfig override (official app: Dk/e.java ordinal 5)
        // Eccentric mode uses bottom.inner.mmPerM = 50 (vs default 250) for
        // more sensitive bottom-of-rep detection during eccentric-focused training.
        if (params.programMode is ProgramMode.EccentricOnly) {
            putShortLE(frame, 0x24, 50) // bottom.inner.mmPerM = 50
        }

        frame[0x2c] = 0xFA.toByte()
        frame[0x2d] = 0x00
        frame[0x2e] = 0x50
        frame[0x2f] = 0x00

        // Get the mode profile block (32 bytes for offsets 0x30-0x4F)
        val profileMode = if (params.isJustLift ||
            params.isEchoMode
        ) {
            ProgramMode.OldSchool
        } else {
            params.programMode
        }
        val profile = getActivationPhases(profileMode)
        profile.copyInto(frame, 0x30)

        // Calculate weights
        val adjustedWeightPerCable = if (params.progressionRegressionKg != 0f) {
            params.weightPerCableKg - params.progressionRegressionKg
        } else {
            params.weightPerCableKg
        }

        val effectiveKg = adjustedWeightPerCable + 10.0f

        val softMax = if (params.isAMRAP || params.isJustLift) 100.0f else params.weightPerCableKg

        if (variant == ForceConfigVariant.OVERLAP) {
            // Issue #262: Firmware reads softMax at 0x48 and increment at 0x4C.
            // These overlap the last 8 bytes of the mode profile, but the firmware
            // interprets them as force config, not mode data. Write them AFTER the
            // profile copy so they take priority.
            putFloatLE(frame, BleConstants.ActivationPacket.OFFSET_SOFT_MAX, softMax)
            putFloatLE(
                frame,
                BleConstants.ActivationPacket.OFFSET_INCREMENT,
                params.progressionRegressionKg,
            )
        }

        // Force config block at 0x50-0x5F
        putFloatLE(frame, BleConstants.ActivationPacket.OFFSET_FORCE_MIN, 0.0f)
        putFloatLE(frame, BleConstants.ActivationPacket.OFFSET_FORCE_MAX, effectiveKg)
        putFloatLE(
            frame,
            BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
            adjustedWeightPerCable,
        )
        putFloatLE(
            frame,
            BleConstants.ActivationPacket.OFFSET_PROGRESSION,
            params.progressionRegressionKg,
        )

        // Diagnostic logging
        Logger.d("BlePacket") {
            "=== MODE: ${params.programMode}, Weight: ${params.weightPerCableKg}kg ==="
        }
        Logger.d("BlePacket") {
            "adjustedWeight=${adjustedWeightPerCable}kg, effectiveKg=$effectiveKg"
        }
        if (variant == ForceConfigVariant.OVERLAP) {
            Logger.d("BlePacket") {
                "softMax[0x48]=${readFloatLE(
                    frame,
                    BleConstants.ActivationPacket.OFFSET_SOFT_MAX,
                )}kg, " +
                    "increment[0x4C]=${readFloatLE(
                        frame,
                        BleConstants.ActivationPacket.OFFSET_INCREMENT,
                    )}kg/rep"
            }
        } else {
            Logger.d("BlePacket") {
                "non-overlap layout active (0x48..0x4F preserved as profile bytes)"
            }
        }
        Logger.d("BlePacket") {
            "forceMin[0x50]=${readFloatLE(
                frame,
                BleConstants.ActivationPacket.OFFSET_FORCE_MIN,
            )}kg, " +
                "forceMax[0x54]=${readFloatLE(
                    frame,
                    BleConstants.ActivationPacket.OFFSET_FORCE_MAX,
                )}kg"
        }
        Logger.d("BlePacket") {
            "targetWeight[0x58]=${readFloatLE(
                frame,
                BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
            )}kg, " +
                "progression[0x5C]=${readFloatLE(
                    frame,
                    BleConstants.ActivationPacket.OFFSET_PROGRESSION,
                )}kg/rep"
        }
        val repsHex = frame[0x04].toUByte().toString(16).padStart(2, '0').uppercase()
        Logger.d("BlePacket") {
            "reps[0x04]=0x$repsHex (isAMRAP=${params.isAMRAP}, isJustLift=${params.isJustLift})"
        }

        return frame
    }

    // ========== Full Protocol: Echo Mode ==========

    /**
     * Creates a simplified Echo command for backward compatibility.
     * For full protocol support, use createEchoControl() instead.
     */
    fun createEchoCommand(level: Int, eccentricLoad: Int): ByteArray {
        val echoLevel = EchoLevel.entries.find { it.levelValue == level } ?: EchoLevel.HARD
        return createEchoControl(echoLevel, eccentricPct = eccentricLoad)
    }

    /**
     * Build Echo mode control frame (32 bytes) with full parameters.
     *
     * @param eccentricPct Eccentric load percentage (0-150%). Values outside this range
     *                     are clamped for safety - machine hardware limit is 150%.
     */
    fun createEchoControl(
        level: EchoLevel,
        warmupReps: Int = 3,
        targetReps: Int = 2,
        isJustLift: Boolean = false,
        isAMRAP: Boolean = false,
        eccentricPct: Int = 75,
    ): ByteArray {
        // Defensive clamping: Machine hardware limit is 150% eccentric load
        // Values > 150% can cause machine faults (yellow light)
        val safeEccentricPct = eccentricPct.coerceIn(0, 150)
        if (eccentricPct != safeEccentricPct) {
            Logger.w("BlePacket") {
                "Eccentric load $eccentricPct% CLAMPED to $safeEccentricPct% (hardware limit 150%)"
            }
        }

        val frame = ByteArray(32)

        // Command ID at 0x00 (u32) = 0x4E (78 decimal)
        putIntLE(frame, 0x00, 0x0000004E)

        frame[0x04] = warmupReps.toByte()
        frame[0x05] = if (isJustLift || isAMRAP) 0xFF.toByte() else targetReps.toByte()

        putShortLE(frame, 0x06, 0)

        val echoParams = getEchoParams(level, safeEccentricPct)

        Logger.d("BlePacketFactory") {
            "=== ECHO: ${level.displayName}, eccentric: $safeEccentricPct% ==="
        }

        putShortLE(frame, 0x08, echoParams.eccentricOverload)
        putShortLE(frame, 0x0a, echoParams.referenceMapBlend)
        putFloatLE(frame, 0x0c, echoParams.concentricDelayS)
        putFloatLE(frame, 0x10, echoParams.concentricDurationSeconds)
        putFloatLE(frame, 0x14, echoParams.concentricMaxVelocity)
        putFloatLE(frame, 0x18, echoParams.eccentricDurationSeconds)
        putFloatLE(frame, 0x1c, echoParams.eccentricMaxVelocity)

        return frame
    }

    // ========== Color Commands ==========

    /**
     * Build a 34-byte color scheme packet.
     */
    fun createColorScheme(brightness: Float, colors: List<RGBColor>): ByteArray {
        require(colors.size == 3) { "Color scheme must have exactly 3 colors" }

        val frame = ByteArray(34)
        putIntLE(frame, 0, 0x00000011)
        putIntLE(frame, 4, 0)
        putIntLE(frame, 8, 0)
        putFloatLE(frame, 12, brightness)

        var offset = 16
        repeat(2) {
            for (color in colors) {
                frame[offset++] = color.r.toByte()
                frame[offset++] = color.g.toByte()
                frame[offset++] = color.b.toByte()
            }
        }

        return frame
    }

    /**
     * Build a color scheme command using predefined schemes.
     */
    fun createColorSchemeCommand(schemeIndex: Int): ByteArray {
        val schemes = ColorSchemes.ALL
        val scheme = schemes.getOrElse(schemeIndex) { schemes[0] }
        return createColorScheme(scheme.brightness, scheme.colors)
    }

    // ========== Read Helpers (for diagnostic logging) ==========

    private fun readFloatLE(buffer: ByteArray, offset: Int): Float {
        val bits = (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    // ========== Activation Phases (Mode Profiles) ==========

    /**
     * Returns 32 bytes: 16 bytes concentric phase + 16 bytes eccentric phase.
     * Each phase contains velocity ramp shorts and smoothing floats matching the official app.
     */
    private fun getActivationPhases(mode: ProgramMode): ByteArray {
        val buffer = ByteArray(32)

        when (mode) {
            is ProgramMode.OldSchool -> {
                putShortLE(buffer, 0x00, 0)
                putShortLE(buffer, 0x02, 20)
                putFloatLE(buffer, 0x04, 3.0f)
                putShortLE(buffer, 0x08, 75)
                putShortLE(buffer, 0x0a, 600)
                putFloatLE(buffer, 0x0c, 50.0f)
                putShortLE(buffer, 0x10, -1300)
                putShortLE(buffer, 0x12, -1200)
                putFloatLE(buffer, 0x14, 100.0f)
                putShortLE(buffer, 0x18, -260)
                putShortLE(buffer, 0x1a, -110)
                putFloatLE(buffer, 0x1c, 0.0f)
            }

            is ProgramMode.Pump -> {
                putShortLE(buffer, 0x00, 50)
                putShortLE(buffer, 0x02, 450)
                putFloatLE(buffer, 0x04, 10.0f)
                putShortLE(buffer, 0x08, 500)
                putShortLE(buffer, 0x0a, 600)
                putFloatLE(buffer, 0x0c, 50.0f)
                putShortLE(buffer, 0x10, -700)
                putShortLE(buffer, 0x12, -550)
                putFloatLE(buffer, 0x14, 1.0f)
                putShortLE(buffer, 0x18, -100)
                putShortLE(buffer, 0x1a, -50)
                putFloatLE(buffer, 0x1c, 1.0f)
            }

            is ProgramMode.TUT -> {
                putShortLE(buffer, 0x00, 250)
                putShortLE(buffer, 0x02, 350)
                putFloatLE(buffer, 0x04, 7.0f)
                putShortLE(buffer, 0x08, 450)
                putShortLE(buffer, 0x0a, 600)
                putFloatLE(buffer, 0x0c, 50.0f)
                putShortLE(buffer, 0x10, -900)
                putShortLE(buffer, 0x12, -700)
                putFloatLE(buffer, 0x14, 70.0f)
                putShortLE(buffer, 0x18, -100)
                putShortLE(buffer, 0x1a, -50)
                putFloatLE(buffer, 0x1c, 14.0f)
            }

            is ProgramMode.TUTBeast -> {
                putShortLE(buffer, 0x00, 150)
                putShortLE(buffer, 0x02, 250)
                putFloatLE(buffer, 0x04, 7.0f)
                putShortLE(buffer, 0x08, 350)
                putShortLE(buffer, 0x0a, 450)
                putFloatLE(buffer, 0x0c, 50.0f)
                putShortLE(buffer, 0x10, -900)
                putShortLE(buffer, 0x12, -700)
                putFloatLE(buffer, 0x14, 70.0f)
                putShortLE(buffer, 0x18, -100)
                putShortLE(buffer, 0x1a, -50)
                putFloatLE(buffer, 0x1c, 28.0f)
            }

            is ProgramMode.EccentricOnly -> {
                putShortLE(buffer, 0x00, 50)
                putShortLE(buffer, 0x02, 550)
                putFloatLE(buffer, 0x04, 50.0f)
                putShortLE(buffer, 0x08, 650)
                putShortLE(buffer, 0x0a, 750)
                putFloatLE(buffer, 0x0c, 10.0f)
                putShortLE(buffer, 0x10, -900)
                putShortLE(buffer, 0x12, -700)
                putFloatLE(buffer, 0x14, 70.0f)
                putShortLE(buffer, 0x18, -100)
                putShortLE(buffer, 0x1a, -50)
                putFloatLE(buffer, 0x1c, 20.0f)
            }

            // Echo mode uses a different BLE command (0x4E), so this profile is never used.
            // But we need to handle it for exhaustive when expression.
            is ProgramMode.Echo -> {
                // Use OldSchool profile as fallback (Echo uses createEchoControl() instead)
                putShortLE(buffer, 0x00, 0)
                putShortLE(buffer, 0x02, 20)
                putFloatLE(buffer, 0x04, 3.0f)
                putShortLE(buffer, 0x08, 75)
                putShortLE(buffer, 0x0a, 600)
                putFloatLE(buffer, 0x0c, 50.0f)
                putShortLE(buffer, 0x10, -1300)
                putShortLE(buffer, 0x12, -1200)
                putFloatLE(buffer, 0x14, 100.0f)
                putShortLE(buffer, 0x18, -260)
                putShortLE(buffer, 0x1a, -110)
                putFloatLE(buffer, 0x1c, 0.0f)
            }
        }

        return buffer
    }

    // ========== Echo Parameters ==========

    private fun getEchoParams(level: EchoLevel, eccentricPct: Int): EchoParams {
        // Official app reference: Yj/d.java (EchoConfiguration) + dk/d.java (EchoVelocity)
        // concentricDurationSeconds = 50.0 / velocity
        // concentricMaxVelocity = velocity (raw EchoVelocity enum value)
        // eccentricMaxVelocity = -200.0 (fixed in official app constructor)
        val params = EchoParams(
            eccentricOverload = eccentricPct,
            referenceMapBlend = 50,
            concentricDelayS = 0.1f,
            eccentricDurationSeconds = 0.0f,
            eccentricMaxVelocity = -200.0f,
            concentricDurationSeconds = 1.0f,
            concentricMaxVelocity = 50.0f,
        )

        return when (level) {
            EchoLevel.HARD -> params.copy(
                concentricDurationSeconds = 1.0f,
                concentricMaxVelocity = 50.0f,
            )

            EchoLevel.HARDER -> params.copy(
                concentricDurationSeconds = 1.25f,
                concentricMaxVelocity = 40.0f,
            )

            EchoLevel.HARDEST -> params.copy(
                concentricDurationSeconds = 1.667f,
                concentricMaxVelocity = 30.0f,
            )

            EchoLevel.EPIC -> params.copy(
                concentricDurationSeconds = 3.333f,
                concentricMaxVelocity = 15.0f,
            )
        }
    }
}

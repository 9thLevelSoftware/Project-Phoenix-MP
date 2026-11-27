package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.ble.getFloatLE
import com.devil.phoenixproject.data.ble.getInt16LE
import com.devil.phoenixproject.data.ble.getInt32LE
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.math.abs

/**
 * The backstop for machine commands (KD-9).
 *
 * Bounds are [CommandLimits]. The primary defense is [CommandLimits.resolve] at
 * command-resolution time, which clamps and tells the user; this validator rejects, and
 * is only reachable by bypassing that clamp. Callers pass the connected model's ceiling:
 * a command builder that does not know the model gets the absolute hardware maximum.
 */
object WorkoutCommandValidator {
    // Byte value 0xFF (255) is reserved on the wire as the unlimited / Just Lift /
    // AMRAP sentinel (see BlePacketFactory). A finite rep count that serializes to
    // 0xFF would be indistinguishable from an unlimited workout and could defeat
    // automatic stop, so finite rep bytes are capped at 254 (audit F069/F070).
    private const val MAX_FINITE_REP_BYTE = 254
    private const val MAX_PACKET_REPS = MAX_FINITE_REP_BYTE
    private const val MAX_ECHO_ECCENTRIC_PERCENT = 150
    private const val MAX_ECHO_REP_BYTE = MAX_FINITE_REP_BYTE

    fun validateProgramParams(params: WorkoutParameters, maxWeightPerCableKg: Float): Result<Unit> {
        if (params.isEchoMode) {
            return failure("Program parameter packet must not be used for Echo mode")
        }
        validateFiniteWeight(params.weightPerCableKg).onFailure { return Result.failure(it) }
        validateFiniteWeight(params.progressionRegressionKg, field = "progressionRegressionKg")
            .onFailure { return Result.failure(it) }
        // F-020/F-044: the per-rep increment reaches the machine at OFFSET_PROGRESSION and
        // is applied to every rep, so an unbounded value from a backup, CSV or portal pull
        // must never reach the frame.
        if (abs(params.progressionRegressionKg) > CommandLimits.MAX_PROGRESSION_KG) {
            return failure(
                "progressionRegressionKg must be within " +
                    "±${CommandLimits.MAX_PROGRESSION_KG}kg/rep, got ${params.progressionRegressionKg}",
            )
        }

        if (params.isJustLift && params.weightPerCableKg < Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG) {
            return failure(
                "Just Lift requires at least ${Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG}kg per cable before sending BLE commands",
            )
        }
        validateWeightRange(
            params.weightPerCableKg,
            allowZero = params.isAMRAP && !params.isJustLift,
            maxWeightPerCableKg = maxWeightPerCableKg,
        ).onFailure { return Result.failure(it) }

        validateRepByte("warmupReps", params.warmupReps, allowZero = true)
            .onFailure { return Result.failure(it) }
        if (!params.isJustLift && !params.isAMRAP) {
            validateRepByte("reps", params.reps, allowZero = false).onFailure { return Result.failure(it) }
            val totalReps = params.reps + params.warmupReps
            if (totalReps !in 1..MAX_PACKET_REPS) {
                return failure("reps + warmupReps must fit in one byte (1..$MAX_PACKET_REPS), got $totalReps")
            }
        }

        return Result.success(Unit)
    }

    fun validateEchoControl(
        level: EchoLevel,
        warmupReps: Int,
        targetReps: Int,
        isJustLift: Boolean,
        isAMRAP: Boolean,
        eccentricPct: Int,
    ): Result<Unit> {
        // Touch level so the compiler keeps this validator exhaustive if EchoLevel changes.
        when (level) {
            EchoLevel.HARD,
            EchoLevel.HARDER,
            EchoLevel.HARDEST,
            EchoLevel.EPIC,
            -> Unit
        }

        validateRepByte("warmupReps", warmupReps, allowZero = true)
            .onFailure { return Result.failure(it) }
        if (!isJustLift && !isAMRAP) {
            validateRepByte("targetReps", targetReps, allowZero = false)
                .onFailure { return Result.failure(it) }
        }
        if (eccentricPct !in 0..MAX_ECHO_ECCENTRIC_PERCENT) {
            return failure("eccentricPct must be 0..$MAX_ECHO_ECCENTRIC_PERCENT, got $eccentricPct")
        }
        return Result.success(Unit)
    }

    fun validateColorScheme(brightness: Float, colors: List<RGBColor>): Result<Unit> {
        if (!brightness.isFinite()) return failure("brightness must be finite")
        if (brightness !in 0f..1f) return failure("brightness must be 0.0..1.0, got $brightness")
        if (colors.size != 3) return failure("Color scheme must have exactly 3 colors")
        return Result.success(Unit)
    }

    /**
     * Validate a raw outbound BLE command frame before GATT write (AUD-020 backstop).
     * Ensures no caller can bypass domain validation with malformed, non-finite, or
     * over-limit frames.
     */
    fun validateFrame(command: ByteArray, maxWeightPerCableKg: Float): Result<Unit> {
        if (command.size < 4) {
            return failure("Malformed BLE command frame: expected at least 4 bytes, got ${command.size}")
        }
        val commandId = getInt32LE(command, 0)
        return when (commandId) {
            0x04 -> validateActivationFrame(command, maxWeightPerCableKg)
            0x4E -> validateEchoFrame(command)
            0x11 -> validateColorSchemeFrame(command)
            0x0A -> {
                if (command.size != 4) {
                    failure("Malformed RESET frame: expected 4 bytes, got ${command.size}")
                } else {
                    Result.success(Unit)
                }
            }
            else -> failure(
                "Unsupported or malformed BLE command frame (size=${command.size}, commandId=0x${commandId.toUInt().toString(16)})",
            )
        }
    }

    private fun validateActivationFrame(command: ByteArray, maxWeightPerCableKg: Float): Result<Unit> {
        if (command.size != BleConstants.ActivationPacket.SIZE) {
            return failure(
                "Malformed activation frame: expected ${BleConstants.ActivationPacket.SIZE} bytes, got ${command.size}",
            )
        }
        val repsByte = command[0x04].toInt() and 0xFF
        if (repsByte !in 1..MAX_FINITE_REP_BYTE && repsByte != 0xFF) {
            return failure("Invalid activation frame reps byte: $repsByte")
        }
        val forceMin = getFloatLE(command, BleConstants.ActivationPacket.OFFSET_FORCE_MIN)
        if (!forceMin.isFinite() || forceMin < Constants.MIN_WEIGHT_KG ||
            forceMin > maxWeightPerCableKg + CommandLimits.WEIGHT_TOLERANCE_KG
        ) {
            return failure("Invalid activation frame forceMin: $forceMin (max=${maxWeightPerCableKg}kg)")
        }
        val targetWeight = getFloatLE(command, BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT)
        if (!targetWeight.isFinite() || targetWeight < Constants.MIN_WEIGHT_KG ||
            targetWeight > maxWeightPerCableKg + CommandLimits.WEIGHT_TOLERANCE_KG
        ) {
            return failure("Invalid activation frame targetWeight: $targetWeight (max=${maxWeightPerCableKg}kg)")
        }
        val forceMax = getFloatLE(command, BleConstants.ActivationPacket.OFFSET_FORCE_MAX)
        if (!forceMax.isFinite() || forceMax < Constants.MIN_WEIGHT_KG ||
            forceMax > maxWeightPerCableKg + 10.0f + CommandLimits.WEIGHT_TOLERANCE_KG
        ) {
            return failure("Invalid activation frame forceMax: $forceMax (max=${maxWeightPerCableKg + 10.0f}kg)")
        }
        val progression = getFloatLE(command, BleConstants.ActivationPacket.OFFSET_PROGRESSION)
        if (!progression.isFinite() || abs(progression) > CommandLimits.MAX_PROGRESSION_KG) {
            return failure(
                "Invalid activation frame progression: $progression (must be within ±${CommandLimits.MAX_PROGRESSION_KG}kg/rep)",
            )
        }
        return Result.success(Unit)
    }

    private fun validateEchoFrame(command: ByteArray): Result<Unit> {
        if (command.size != 32) {
            return failure("Malformed Echo frame: expected 32 bytes, got ${command.size}")
        }
        val warmupReps = command[0x04].toInt() and 0xFF
        if (warmupReps !in 0..MAX_FINITE_REP_BYTE) {
            return failure("Invalid Echo frame warmupReps: $warmupReps")
        }
        val targetReps = command[0x05].toInt() and 0xFF
        if (targetReps !in 1..MAX_FINITE_REP_BYTE && targetReps != 0xFF) {
            return failure("Invalid Echo frame targetReps: $targetReps")
        }
        val eccentricOverload = getInt16LE(command, 0x08)
        if (eccentricOverload !in 0..MAX_ECHO_ECCENTRIC_PERCENT) {
            return failure("Invalid Echo frame eccentricOverload: $eccentricOverload (must be 0..$MAX_ECHO_ECCENTRIC_PERCENT)")
        }
        val floatOffsets = intArrayOf(0x0C, 0x10, 0x14, 0x18, 0x1C)
        for (offset in floatOffsets) {
            val value = getFloatLE(command, offset)
            if (!value.isFinite()) {
                return failure("Echo frame float at offset 0x${offset.toString(16)} must be finite, got $value")
            }
        }
        return Result.success(Unit)
    }

    private fun validateColorSchemeFrame(command: ByteArray): Result<Unit> {
        if (command.size != 34) {
            return failure("Malformed color scheme frame: expected 34 bytes, got ${command.size}")
        }
        val brightness = getFloatLE(command, 12)
        if (!brightness.isFinite() || brightness !in 0f..1f) {
            return failure("Invalid color scheme brightness: $brightness (must be 0.0..1.0)")
        }
        return Result.success(Unit)
    }

    private fun validateFiniteWeight(value: Float, field: String = "weightPerCableKg"): Result<Unit> = if (value.isFinite()) Result.success(Unit) else failure("$field must be finite")

    private fun validateWeightRange(weightPerCableKg: Float, allowZero: Boolean, maxWeightPerCableKg: Float): Result<Unit> {
        if (!allowZero && weightPerCableKg <= Constants.MIN_WEIGHT_KG) {
            return failure("weightPerCableKg must be greater than ${Constants.MIN_WEIGHT_KG}kg, got $weightPerCableKg")
        }
        // F-009: the ceiling is the CONNECTED model's, not a model-agnostic constant.
        // The tolerance absorbs float/lb rounding (220.5 lb is 100.017 kg on a 100 kg cable).
        if (weightPerCableKg < Constants.MIN_WEIGHT_KG ||
            weightPerCableKg > maxWeightPerCableKg + CommandLimits.WEIGHT_TOLERANCE_KG
        ) {
            return failure(
                "weightPerCableKg must be ${Constants.MIN_WEIGHT_KG}..${maxWeightPerCableKg}kg, got $weightPerCableKg",
            )
        }
        return Result.success(Unit)
    }

    private fun validateRepByte(field: String, value: Int, allowZero: Boolean): Result<Unit> {
        val min = if (allowZero) 0 else 1
        if (value !in min..MAX_ECHO_REP_BYTE) {
            return failure("$field must be $min..$MAX_ECHO_REP_BYTE, got $value")
        }
        return Result.success(Unit)
    }

    private fun failure(message: String): Result<Unit> = Result.failure(IllegalArgumentException(message))
}

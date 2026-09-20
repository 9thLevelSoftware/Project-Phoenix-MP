package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
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

    fun validateLegacyWorkoutCommand(
        programMode: ProgramMode,
        weightPerCableKg: Float,
        targetReps: Int,
        maxWeightPerCableKg: Float,
    ): Result<Unit> {
        validateFiniteWeight(weightPerCableKg).onFailure { return Result.failure(it) }
        validateWeightRange(weightPerCableKg, allowZero = false, maxWeightPerCableKg)
            .onFailure { return Result.failure(it) }
        validateRepByte("targetReps", targetReps, allowZero = false).onFailure { return Result.failure(it) }
        if (programMode == ProgramMode.Echo) {
            return failure("Legacy workout command must not be used for Echo mode")
        }
        return Result.success(Unit)
    }

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
        if (!isFinite(brightness)) return failure("brightness must be finite")
        if (brightness !in 0f..1f) return failure("brightness must be 0.0..1.0, got $brightness")
        if (colors.size != 3) return failure("Color scheme must have exactly 3 colors")
        return Result.success(Unit)
    }

    private fun validateFiniteWeight(value: Float, field: String = "weightPerCableKg"): Result<Unit> = if (isFinite(value)) Result.success(Unit) else failure("$field must be finite")

    private fun isFinite(value: Float): Boolean = !value.isNaN() && !value.isInfinite()

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

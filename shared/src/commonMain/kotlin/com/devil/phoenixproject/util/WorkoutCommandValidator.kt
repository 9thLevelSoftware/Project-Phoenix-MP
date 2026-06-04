package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters

object WorkoutCommandValidator {
    private const val MAX_PACKET_REPS = 255
    private const val MAX_ECHO_ECCENTRIC_PERCENT = 150
    private const val MAX_ECHO_REP_BYTE = 255

    fun validateLegacyWorkoutCommand(
        programMode: ProgramMode,
        weightPerCableKg: Float,
        targetReps: Int,
    ): Result<Unit> {
        validateFiniteWeight(weightPerCableKg).onFailure { return Result.failure(it) }
        validateWeightRange(weightPerCableKg, allowZero = false).onFailure { return Result.failure(it) }
        validateRepByte("targetReps", targetReps, allowZero = false).onFailure { return Result.failure(it) }
        if (programMode == ProgramMode.Echo) {
            return failure("Legacy workout command must not be used for Echo mode")
        }
        return Result.success(Unit)
    }

    fun validateProgramParams(params: WorkoutParameters): Result<Unit> {
        if (params.isEchoMode) {
            return failure("Program parameter packet must not be used for Echo mode")
        }
        validateFiniteWeight(params.weightPerCableKg).onFailure { return Result.failure(it) }
        validateFiniteWeight(params.progressionRegressionKg, field = "progressionRegressionKg")
            .onFailure { return Result.failure(it) }

        if (params.isJustLift && params.weightPerCableKg < Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG) {
            return failure(
                "Just Lift requires at least ${Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG}kg per cable before sending BLE commands",
            )
        }
        validateWeightRange(
            params.weightPerCableKg,
            allowZero = params.isAMRAP && !params.isJustLift,
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

    private fun validateWeightRange(weightPerCableKg: Float, allowZero: Boolean): Result<Unit> {
        val min = if (allowZero) Constants.MIN_WEIGHT_KG else Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG
        if (weightPerCableKg < min || weightPerCableKg > Constants.MAX_WEIGHT_PER_CABLE_KG) {
            return failure(
                "weightPerCableKg must be $min..${Constants.MAX_WEIGHT_PER_CABLE_KG}kg, got $weightPerCableKg",
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

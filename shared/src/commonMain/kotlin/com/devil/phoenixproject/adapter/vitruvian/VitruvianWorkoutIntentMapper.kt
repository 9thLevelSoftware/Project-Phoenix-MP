package com.devil.phoenixproject.adapter.vitruvian

import com.devil.phoenixproject.domain.model.*

/**
 * Protocol mapping layer: generic [WorkoutIntent] -> Vitruvian mode payload.
 */
data class VitruvianModePayload(
    val programMode: ProgramMode,
    val echoLevel: EchoLevel,
    val eccentricLoad: EccentricLoad,
    val targetReps: Int,
    val isAmrap: Boolean
)

object VitruvianProtocolCapabilities {
    val descriptor = ProtocolCapabilityDescriptor(
        supportedStrengthProfiles = setOf(
            StrengthProfile.STANDARD,
            StrengthProfile.PUMP,
            StrengthProfile.TIME_UNDER_TENSION,
            StrengthProfile.TIME_UNDER_TENSION_HIGH,
            StrengthProfile.ECHO
        ),
        supportedTempoProfiles = setOf(TempoProfile.NORMAL),
        supportsAssistedMode = false,
        supportsEccentricBias = true,
        supportsRepTarget = true,
        supportsTimeTarget = true,
        supportsRomConstraints = false,
        maxEccentricPercent = 150
    )
}

object VitruvianWorkoutIntentMapper {
    fun map(intent: WorkoutIntent): VitruvianModePayload {
        val validationErrors = WorkoutIntentValidator.validate(intent, VitruvianProtocolCapabilities.descriptor)
        require(validationErrors.isEmpty()) {
            "Workout intent is not supported by Vitruvian protocol: ${validationErrors.joinToString()}"
        }

        val mode = when (intent.strengthProfile) {
            StrengthProfile.STANDARD -> ProgramMode.OldSchool
            StrengthProfile.PUMP -> ProgramMode.Pump
            StrengthProfile.TIME_UNDER_TENSION -> ProgramMode.TUT
            StrengthProfile.TIME_UNDER_TENSION_HIGH -> ProgramMode.TUTBeast
            StrengthProfile.ECHO -> ProgramMode.Echo
        }

        val reps = when (val target = intent.target) {
            is WorkoutTarget.Repetitions -> target.reps
            is WorkoutTarget.DurationSeconds -> 0
        }

        val eccentric = when (val bias = intent.eccentricBias) {
            EccentricBias.NONE -> EccentricLoad.LOAD_100
            is EccentricBias.Percent -> EccentricLoad.entries
                .firstOrNull { it.percentage == bias.value }
                ?: EccentricLoad.LOAD_100
        }

        return VitruvianModePayload(
            programMode = mode,
            echoLevel = EchoLevel.HARD,
            eccentricLoad = eccentric,
            targetReps = reps,
            isAmrap = intent.target is WorkoutTarget.DurationSeconds
        )
    }
}

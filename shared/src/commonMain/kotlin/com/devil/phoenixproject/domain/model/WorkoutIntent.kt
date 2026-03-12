package com.devil.phoenixproject.domain.model

/**
 * Vendor-agnostic workout intent model.
 *
 * This captures what the user wants to train without tying the domain layer
 * to a specific machine protocol mode id.
 */
data class WorkoutIntent(
    val strengthProfile: StrengthProfile = StrengthProfile.STANDARD,
    val tempo: TempoProfile = TempoProfile.NORMAL,
    val assistance: AssistanceProfile = AssistanceProfile.NONE,
    val eccentricBias: EccentricBias = EccentricBias.NONE,
    val target: WorkoutTarget = WorkoutTarget.Repetitions(10),
    val romConstraint: RomConstraint = RomConstraint.Unrestricted
)

enum class StrengthProfile {
    STANDARD,
    PUMP,
    TIME_UNDER_TENSION,
    TIME_UNDER_TENSION_HIGH,
    ECHO
}

enum class TempoProfile {
    NORMAL,
    CONTROLLED,
    EXPLOSIVE
}

enum class AssistanceProfile {
    NONE,
    ASSISTED
}

sealed class EccentricBias {
    data object NONE : EccentricBias()
    data class Percent(val value: Int) : EccentricBias()
}

sealed class WorkoutTarget {
    data class Repetitions(val reps: Int) : WorkoutTarget()
    data class DurationSeconds(val seconds: Int) : WorkoutTarget()
}

sealed class RomConstraint {
    data object Unrestricted : RomConstraint()
    data class MinRangeMm(val minRangeMm: Int) : RomConstraint()
}

data class ProtocolCapabilityDescriptor(
    val supportedStrengthProfiles: Set<StrengthProfile>,
    val supportedTempoProfiles: Set<TempoProfile>,
    val supportsAssistedMode: Boolean,
    val supportsEccentricBias: Boolean,
    val supportsRepTarget: Boolean,
    val supportsTimeTarget: Boolean,
    val supportsRomConstraints: Boolean,
    val maxEccentricPercent: Int = 150
)

object WorkoutIntentValidator {
    fun validate(intent: WorkoutIntent, capability: ProtocolCapabilityDescriptor): List<String> {
        val errors = mutableListOf<String>()
        if (intent.strengthProfile !in capability.supportedStrengthProfiles) {
            errors += "Unsupported strength profile: ${intent.strengthProfile}"
        }
        if (intent.tempo !in capability.supportedTempoProfiles) {
            errors += "Unsupported tempo profile: ${intent.tempo}"
        }
        if (intent.assistance == AssistanceProfile.ASSISTED && !capability.supportsAssistedMode) {
            errors += "Assisted mode is not supported by current protocol"
        }
        if (intent.eccentricBias is EccentricBias.Percent) {
            if (!capability.supportsEccentricBias) {
                errors += "Eccentric bias is not supported by current protocol"
            } else if (intent.eccentricBias.value !in 0..capability.maxEccentricPercent) {
                errors += "Eccentric bias must be between 0 and ${capability.maxEccentricPercent}%"
            }
        }
        when (intent.target) {
            is WorkoutTarget.Repetitions -> if (!capability.supportsRepTarget) {
                errors += "Rep-based targets are not supported by current protocol"
            }
            is WorkoutTarget.DurationSeconds -> if (!capability.supportsTimeTarget) {
                errors += "Timed targets are not supported by current protocol"
            }
        }
        if (intent.romConstraint is RomConstraint.MinRangeMm && !capability.supportsRomConstraints) {
            errors += "ROM constraints are not supported by current protocol"
        }
        return errors
    }
}


fun ProgramMode.toWorkoutIntent(
    echoLevel: EchoLevel = EchoLevel.HARD,
    eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
    isAmrap: Boolean = false,
    reps: Int = 10
): WorkoutIntent {
    val profile = when (this) {
        ProgramMode.OldSchool -> StrengthProfile.STANDARD
        ProgramMode.Pump -> StrengthProfile.PUMP
        ProgramMode.TUT -> StrengthProfile.TIME_UNDER_TENSION
        ProgramMode.TUTBeast -> StrengthProfile.TIME_UNDER_TENSION_HIGH
        ProgramMode.EccentricOnly -> StrengthProfile.STANDARD
        ProgramMode.Echo -> StrengthProfile.ECHO
    }

    val eccentricBias = if (this == ProgramMode.EccentricOnly || this == ProgramMode.Echo) {
        EccentricBias.Percent(eccentricLoad.percentage)
    } else {
        EccentricBias.NONE
    }

    return WorkoutIntent(
        strengthProfile = profile,
        target = if (isAmrap) WorkoutTarget.DurationSeconds(0) else WorkoutTarget.Repetitions(reps),
        eccentricBias = eccentricBias,
        tempo = if (echoLevel == EchoLevel.EPIC) TempoProfile.EXPLOSIVE else TempoProfile.NORMAL
    )
}

package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.getBestVolumePRForWorkoutMode
import com.devil.phoenixproject.data.repository.getBestWeightPRForWorkoutMode
import com.devil.phoenixproject.data.repository.preferredPRPhaseForWorkoutMode
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WorkoutPhase

/**
 * Shared resolver for routine percent-of-PR baseline lookup.
 *
 * Keeps routine editor preview and workout-start resolution on the same order:
 * current mode first, same-profile cross-mode fallback second, then no-baseline
 * fallback handled by callers.
 */
class ResolveRoutineScalingBaselineUseCase(
    private val prRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
) {
    suspend operator fun invoke(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String = "default",
        basis: ScalingBasis,
    ): RoutineScalingBaseline? = when (basis) {
        ScalingBasis.MAX_WEIGHT_PR -> resolveMaxWeight(exerciseId, mode, profileId, basis)
        ScalingBasis.MAX_VOLUME_PR -> resolveMaxVolume(exerciseId, mode, profileId, basis)
        ScalingBasis.ESTIMATED_1RM -> resolveEstimatedOneRepMax(exerciseId, mode, profileId, basis)
    }

    private suspend fun resolveMaxWeight(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? {
        currentModeWeightPR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CURRENT_MODE_PR)
        }
        crossModeWeightPR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CROSS_MODE_PR)
        }
        return storedOneRepMaxBaseline(exerciseId, basis)
    }

    private suspend fun resolveMaxVolume(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? {
        currentModeVolumePR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CURRENT_MODE_PR)
        }
        crossModeVolumePR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CROSS_MODE_PR)
        }
        return storedOneRepMaxBaseline(exerciseId, basis)
    }

    private suspend fun resolveEstimatedOneRepMax(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? {
        velocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)
            ?.estimatedPerCableKg
            ?.takeIf { it > 0 }
            ?.let { estimate ->
                return RoutineScalingBaseline(
                    weightPerCableKg = estimate,
                    basis = basis,
                    sourceMode = null,
                    source = RoutineScalingBaselineSource.VELOCITY_ESTIMATE,
                )
            }

        storedOneRepMaxBaseline(exerciseId, basis)?.let { return it }

        currentModeWeightPR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CURRENT_MODE_PR)
        }
        crossModeWeightPR(exerciseId, mode, profileId)?.let { record ->
            return record.toBaseline(basis, RoutineScalingBaselineSource.CROSS_MODE_PR)
        }
        return null
    }

    private suspend fun currentModeWeightPR(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
    ): PersonalRecord? = prRepository.getBestWeightPRForWorkoutMode(exerciseId, mode.displayName, profileId)
        ?.takeIf { it.weightPerCableKg > 0 }

    private suspend fun storedOneRepMaxBaseline(
        exerciseId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? = exerciseRepository.getExerciseById(exerciseId)
        ?.oneRepMaxKg
        ?.takeIf { it > 0 }
        ?.let { storedOneRepMax ->
            RoutineScalingBaseline(
                weightPerCableKg = storedOneRepMax,
                basis = basis,
                sourceMode = null,
                source = RoutineScalingBaselineSource.STORED_ONE_REP_MAX,
            )
        }

    private suspend fun currentModeVolumePR(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
    ): PersonalRecord? = prRepository.getBestVolumePRForWorkoutMode(exerciseId, mode.displayName, profileId)
        ?.takeIf { it.weightPerCableKg > 0 }

    private suspend fun crossModeWeightPR(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
    ): PersonalRecord? = bestCrossModeRecord(
        preferredPhase = preferredPRPhaseForWorkoutMode(mode.displayName),
        combinedLookup = { prRepository.getBestWeightPR(exerciseId, profileId, WorkoutPhase.COMBINED) },
        phaseLookup = { phase -> prRepository.getBestWeightPR(exerciseId, profileId, phase) },
    )

    private suspend fun crossModeVolumePR(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
    ): PersonalRecord? = bestCrossModeRecord(
        preferredPhase = preferredPRPhaseForWorkoutMode(mode.displayName),
        combinedLookup = { prRepository.getBestVolumePR(exerciseId, profileId, WorkoutPhase.COMBINED) },
        phaseLookup = { phase -> prRepository.getBestVolumePR(exerciseId, profileId, phase) },
    )

    private suspend fun bestCrossModeRecord(
        preferredPhase: WorkoutPhase,
        phaseLookup: suspend (WorkoutPhase) -> PersonalRecord?,
        combinedLookup: suspend () -> PersonalRecord?,
    ): PersonalRecord? {
        phaseLookup(preferredPhase)?.takeIf { it.weightPerCableKg > 0 }?.let { return it }
        if (preferredPhase != WorkoutPhase.COMBINED) {
            combinedLookup()?.takeIf { it.weightPerCableKg > 0 }?.let { return it }
        }
        return null
    }

    private fun PersonalRecord.toBaseline(
        basis: ScalingBasis,
        source: RoutineScalingBaselineSource,
    ): RoutineScalingBaseline = RoutineScalingBaseline(
        weightPerCableKg = weightPerCableKg,
        basis = basis,
        sourceMode = workoutMode,
        source = source,
    )
}

data class RoutineScalingBaseline(
    val weightPerCableKg: Float,
    val basis: ScalingBasis,
    val sourceMode: String?,
    val source: RoutineScalingBaselineSource,
)

enum class RoutineScalingBaselineSource {
    CURRENT_MODE_PR,
    CROSS_MODE_PR,
    VELOCITY_ESTIMATE,
    STORED_ONE_REP_MAX,
}

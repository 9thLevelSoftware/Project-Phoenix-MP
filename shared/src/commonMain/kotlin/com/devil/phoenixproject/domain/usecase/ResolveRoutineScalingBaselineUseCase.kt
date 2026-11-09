package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.getBestVolumePRForWorkoutMode
import com.devil.phoenixproject.data.repository.getBestWeightPRForWorkoutMode
import com.devil.phoenixproject.data.repository.preferredPRPhaseForWorkoutMode
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.util.OneRepMaxCalculator

/**
 * Shared resolver for routine percent-of-PR baseline lookup.
 *
 * Keeps routine editor preview and workout-start resolution on the same order:
 * current mode first, same-profile cross-mode fallback second, then no-baseline
 * fallback handled by callers.
 */
class ResolveRoutineScalingBaselineUseCase(
    private val prRepository: PersonalRecordRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
    private val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val nowMillis: () -> Long = ::currentTimeMillis,
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
        return storedOneRepMaxBaseline(profileId, exerciseId, basis)
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
        return storedOneRepMaxBaseline(profileId, exerciseId, basis)
    }

    private suspend fun resolveEstimatedOneRepMax(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? {
        val now = nowMillis()
        val completedWorkingSets = if (mode == ProgramMode.Echo) {
            emptyList()
        } else {
            completedSetRepository.getEligibleWorkingSetsForOneRepMax(
                exerciseId = exerciseId,
                profileId = profileId,
                mode = mode.displayName,
                completedAtSince = now - MAX_VELOCITY_AGE_MS,
                completedAtUntil = now,
            ).filter(::isValidCompletedWorkingSet)
        }
        val repEstimates = completedWorkingSets.mapNotNull { set ->
            OneRepMaxCalculator.estimate(set.actualWeightKg, set.actualReps)
                .takeIf { it.isFinite() && it > 0f }
                ?.let { estimate -> set to estimate }
        }
        val highestRepEstimate = repEstimates.maxWithOrNull(
            compareBy<Pair<CompletedSet, Float>> { it.second }.thenBy { it.first.completedAt },
        )

        val velocityEstimate = velocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)
            ?.takeIf { estimate ->
                estimate.exerciseId == exerciseId &&
                    estimate.profileId == profileId &&
                    estimate.passedQualityGate &&
                    estimate.estimatedPerCableKg.isFinite() &&
                    VelocityOneRepMaxEstimator.isUsableEstimate(estimate.estimatedPerCableKg) &&
                    estimate.computedAt <= now &&
                    estimate.computedAt >= now - MAX_VELOCITY_AGE_MS
            }
        val contradictedByNewerWorkingLoad = velocityEstimate != null && completedWorkingSets.any { set ->
            set.completedAt > velocityEstimate.computedAt &&
                set.actualWeightKg.isFinite() &&
                set.actualWeightKg > velocityEstimate.estimatedPerCableKg
        }
        if (velocityEstimate != null && !contradictedByNewerWorkingLoad) {
            return RoutineScalingBaseline(
                weightPerCableKg = velocityEstimate.estimatedPerCableKg,
                basis = basis,
                sourceMode = null,
                source = RoutineScalingBaselineSource.VELOCITY_ESTIMATE,
                sourceTimestamp = velocityEstimate.computedAt,
                sourceAgeDays = ageInDays(now, velocityEstimate.computedAt),
                resolutionReason = "Current quality-passing velocity estimate within its 28-day validity window.",
            )
        }

        highestRepEstimate?.let { (set, estimate) ->
            return RoutineScalingBaseline(
                weightPerCableKg = estimate,
                basis = basis,
                sourceMode = mode.displayName,
                source = RoutineScalingBaselineSource.REP_DERIVED_ESTIMATE,
                sourceTimestamp = set.completedAt,
                sourceAgeDays = ageInDays(now, set.completedAt),
                resolutionReason = if (contradictedByNewerWorkingLoad) {
                    "A newer completed working load exceeded the velocity estimate; using the highest eligible rep-derived estimate."
                } else {
                    "Velocity estimate unavailable or expired; using the highest eligible completed working-set estimate."
                },
            )
        }

        // Stored baseline provenance is uncertain; preserve it as a separately labeled
        // stored value. A raw max-weight PR is not interchangeable with an estimated 1RM.
        return storedOneRepMaxBaseline(profileId, exerciseId, basis)
            ?.copy(resolutionReason = "No current velocity or eligible completed working-set estimate is available.")
    }

    private fun isValidCompletedWorkingSet(set: CompletedSet): Boolean =
        set.setType == SetType.STANDARD &&
            set.setEndReason == SetEndReason.TARGET_REPS_REACHED &&
            set.actualReps > 0 &&
            set.actualWeightKg.isFinite() &&
            set.actualWeightKg > 0f &&
            set.completedAt > 0L

    private suspend fun currentModeWeightPR(
        exerciseId: String,
        mode: ProgramMode,
        profileId: String,
    ): PersonalRecord? = prRepository.getBestWeightPRForWorkoutMode(exerciseId, mode.displayName, profileId)
        ?.takeIf { it.weightPerCableKg > 0 }

    private suspend fun storedOneRepMaxBaseline(
        profileId: String,
        exerciseId: String,
        basis: ScalingBasis,
    ): RoutineScalingBaseline? {
        val stored = baselineRepository.get(profileId, exerciseId) ?: return null
        val weightPerCableKg = stored.oneRepMaxPerCableKg?.takeIf { it.isFinite() && it > 0f } ?: return null
        return RoutineScalingBaseline(
            weightPerCableKg = weightPerCableKg,
            basis = basis,
            sourceMode = null,
            source = RoutineScalingBaselineSource.STORED_ONE_REP_MAX,
            sourceTimestamp = stored.updatedAt,
            sourceAgeDays = ageInDays(nowMillis(), stored.updatedAt),
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
        sourceTimestamp = timestamp,
    )
}

data class RoutineScalingBaseline(
    val weightPerCableKg: Float,
    val basis: ScalingBasis,
    val sourceMode: String?,
    val source: RoutineScalingBaselineSource,
    val sourceTimestamp: Long? = null,
    val sourceAgeDays: Long? = null,
    val resolutionReason: String? = null,
)


enum class RoutineScalingBaselineSource(val displayLabel: String) {
    CURRENT_MODE_PR("Current-mode PR"),
    CROSS_MODE_PR("Cross-mode PR"),
    VELOCITY_ESTIMATE("Velocity 1RM estimate"),
    REP_DERIVED_ESTIMATE("Completed working-set estimate"),
    STORED_ONE_REP_MAX("Stored 1RM"),
}

private const val MAX_VELOCITY_AGE_MS = 28L * 24L * 60L * 60L * 1_000L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

private fun ageInDays(now: Long, timestamp: Long?): Long? =
    timestamp?.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) / MILLIS_PER_DAY }

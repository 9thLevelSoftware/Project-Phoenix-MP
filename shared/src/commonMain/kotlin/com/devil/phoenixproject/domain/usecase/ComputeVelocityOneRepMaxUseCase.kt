package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.onerepmax.MvtProvider
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.domain.onerepmax.WorkoutVelocityPoint

/** Minimal view of an exercise needed for MVT resolution. */
interface MvtExerciseView { val name: String; val muscleGroups: String; val mvtOverrideMs: Float? }

class ComputeVelocityOneRepMaxUseCase(
    private val workoutPoints: suspend (exerciseId: String, profileId: String, sinceMs: Long) -> List<WorkoutVelocityPoint>,
    private val exerciseLookup: suspend (exerciseId: String) -> MvtExerciseView?,
    private val personalMvtLookup: suspend (exerciseId: String, profileId: String) -> Pair<Float, Int>?, // (mvt, sampleCount)
    private val mvtProvider: MvtProvider,
    private val estimator: VelocityOneRepMaxEstimator,
    private val persist: suspend (result: VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) -> Unit,
) {
    suspend operator fun invoke(exerciseId: String, profileId: String, nowMs: Long, windowDays: Int = WINDOW_DAYS): VelocityOneRepMaxResult? {
        val exercise = exerciseLookup(exerciseId) ?: return null
        val sinceMs = nowMs - windowDays.toLong() * DAY_MS
        val points = workoutPoints(exerciseId, profileId, sinceMs)
        val personal = personalMvtLookup(exerciseId, profileId)
        val mvt = mvtProvider.resolve(
            exerciseName = exercise.name,
            muscleGroups = exercise.muscleGroups,
            userOverrideMs = exercise.mvtOverrideMs,
            personalMvtMs = personal?.first,
            personalSampleCount = personal?.second ?: 0,
        )
        val result = estimator.estimate(points, mvt) ?: return null
        // Issue #644: never persist a passing velocity-1RM row that sits at the 1.0 kg
        // hardware floor — the regression didn't actually reach the 1RM velocity, and
        // persisting it would let the resolver short-circuit stored-1RM / max-weight PR
        // fallback. Returning null here keeps the call sites' null-handling intact and
        // mirrors what a row with r2<threshold or distinctLoads<3 already does.
        if (!VelocityOneRepMaxEstimator.isUsableEstimate(result.estimatedPerCableKg)) return null
        persist(result, exerciseId, nowMs, profileId)
        return result
    }

    companion object {
        const val WINDOW_DAYS = 28
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

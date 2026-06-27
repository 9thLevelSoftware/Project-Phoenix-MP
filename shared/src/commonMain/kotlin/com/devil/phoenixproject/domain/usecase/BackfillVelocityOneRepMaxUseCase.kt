package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult

/**
 * Backfills velocity-based 1RM estimates for exercises that have no existing estimate.
 *
 * For each exercise belonging to the profile: if an estimate already exists (`hasEstimates`
 * returns true) the exercise is skipped. Otherwise `computeAllTime` is called, which both
 * computes and persists the estimate using the full historical window. The use case returns
 * the count of newly created estimates (non-null `computeAllTime` results).
 *
 * Designed to run once at startup (guarded by the `velocityOneRepMaxBackfillDone` preference).
 */
class BackfillVelocityOneRepMaxUseCase(
    private val exerciseIds: suspend (profileId: String) -> List<String>,
    private val hasEstimates: suspend (exerciseId: String, profileId: String) -> Boolean,
    private val computeAllTime: suspend (exerciseId: String, profileId: String, nowMs: Long) -> VelocityOneRepMaxResult?,
) {
    suspend operator fun invoke(profileId: String, nowMs: Long): Int {
        var created = 0
        for (id in exerciseIds(profileId)) {
            if (hasEstimates(id, profileId)) continue
            if (computeAllTime(id, profileId, nowMs) != null) created++
        }
        return created
    }
}

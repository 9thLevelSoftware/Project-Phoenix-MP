package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlinx.serialization.Serializable

@Serializable
data class ActiveWorkoutRuntimeDocument(
    val version: Int = CURRENT_VERSION,
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val sourceExecutionId: String,
    val sourceStableSessionId: String,
    val sourceAttemptNumber: Int,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String? = null,
    val sourceExerciseIndex: Int,
    val sourceSetIndex: Int,
    val exerciseLoadOverlays: List<ExerciseLoadOverlay> = emptyList(),
    val attemptStates: List<PlannedSetAttemptState> = emptyList(),
    val restTransitionPlan: RestTransitionPlan? = null,
    val restDeadlineEpochMs: Long? = null,
    val pausedRestRemainingSeconds: Int? = null,
    val isRestPaused: Boolean = false,
    val originalRestDurationSeconds: Int,
) {
    init {
        require(version == CURRENT_VERSION)
        require(profileId.isNotBlank())
        require(routineId.isNotBlank())
        require(routineSessionId.isNotBlank())
        require(routineExerciseId.isNotBlank())
        require(sourceExecutionId.isNotBlank())
        require(sourceStableSessionId.isNotBlank())
        require(sourceAttemptNumber > 0)
        require(plannedSetId == null || plannedSetId.isNotBlank())
        require(sourceExerciseIndex >= 0)
        require(sourceSetIndex >= 0)
        require(logicalSetKey.routineSessionId == routineSessionId)
        require(logicalSetKey.routineExerciseId == routineExerciseId)
        require(logicalSetKey.setIndex == sourceSetIndex)
        require(logicalSetKey.setIndex >= 0)
        require(exerciseLoadOverlays.all { it.routineExerciseId.isNotBlank() && it.multiplier.isFinite() && it.multiplier > 0f })
        require(
            attemptStates.all {
                it.logicalSetKey.routineSessionId == routineSessionId &&
                    it.logicalSetKey.routineExerciseId.isNotBlank() &&
                    it.logicalSetKey.setIndex >= 0 &&
                    it.nextAttemptNumber > 0
            },
        )
        require(originalRestDurationSeconds >= 0)
        require(restDeadlineEpochMs == null || pausedRestRemainingSeconds == null)
        if (isRestPaused) {
            require(restDeadlineEpochMs == null)
            require(pausedRestRemainingSeconds != null)
            require(pausedRestRemainingSeconds in 0..originalRestDurationSeconds)
        } else {
            require(pausedRestRemainingSeconds == null)
        }
        restTransitionPlan?.let { plan ->
            require(plan.sourceExecutionId == sourceExecutionId)
            require(plan.logicalSetKey == logicalSetKey)
            when (plan) {
                is RestTransitionPlan.AcceptedRetry -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.sourceCoordinates == sourceCoordinates)
                }
                is RestTransitionPlan.NormalAdvance -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.sourceCoordinates == sourceCoordinates)
                }
                is RestTransitionPlan.UnresolvedDropOffer -> {
                    require(plan.plannedSetId == plannedSetId)
                    require(plan.normalAdvance.sourceCoordinates == sourceCoordinates)
                }
                is RestTransitionPlan.Declined -> {
                    require(plan.normalAdvance.plannedSetId == plannedSetId)
                    require(plan.normalAdvance.sourceCoordinates == sourceCoordinates)
                }
            }
        }
    }

    private val sourceCoordinates: RestTransitionPlan.Coordinates
        get() = RestTransitionPlan.Coordinates(sourceExerciseIndex, sourceSetIndex)

    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

interface ActiveWorkoutRuntimeRepository {
    suspend fun load(profileId: String, routineSessionId: String): ActiveWorkoutRuntimeLoadResult
    suspend fun replace(profileId: String, routineSessionId: String, document: ActiveWorkoutRuntimeDocument)
    suspend fun delete(profileId: String, routineSessionId: String)
}

sealed interface ActiveWorkoutRuntimeLoadResult {
    data object Missing : ActiveWorkoutRuntimeLoadResult
    data class Loaded(val document: ActiveWorkoutRuntimeDocument) : ActiveWorkoutRuntimeLoadResult
    data class Rejected(val reason: ActiveWorkoutRuntimeRejection) : ActiveWorkoutRuntimeLoadResult
}

enum class ActiveWorkoutRuntimeRejection {
    CORRUPT_JSON,
    UNSUPPORTED_VERSION,
    IDENTITY_MISMATCH,
}

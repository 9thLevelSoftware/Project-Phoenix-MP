package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.LogicalSetKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface RestTransitionPlan {
    val transitionId: String
    val sourceExecutionId: String
    val logicalSetKey: LogicalSetKey

    @Serializable
    data class Coordinates(
        val exerciseIndex: Int,
        val setIndex: Int,
    ) {
        init {
            require(exerciseIndex >= 0)
            require(setIndex >= 0)
        }
    }

    @Serializable
    data class NormalAdvance(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val sourceCoordinates: Coordinates,
        val plannedSetId: String?,
        val restDurationSeconds: Int,
    ) : RestTransitionPlan {
        init {
            validateIdentity(transitionId, sourceExecutionId, logicalSetKey, plannedSetId)
            require(sourceCoordinates.setIndex == logicalSetKey.setIndex)
            require(restDurationSeconds >= 0)
        }
    }

    @Serializable
    data class UnresolvedDropOffer(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val plannedSetId: String?,
        val candidates: List<DropSetCandidate>,
        val normalAdvance: NormalAdvance,
    ) : RestTransitionPlan {
        init {
            validateIdentity(transitionId, sourceExecutionId, logicalSetKey, plannedSetId)
            require(offerId.isNotBlank())
            require(candidates.isNotEmpty())
            require(normalAdvance.transitionId == transitionId)
            require(normalAdvance.sourceExecutionId == sourceExecutionId)
            require(normalAdvance.logicalSetKey == logicalSetKey)
            require(normalAdvance.plannedSetId == plannedSetId)
        }
    }

    @Serializable
    data class Declined(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val normalAdvance: NormalAdvance,
    ) : RestTransitionPlan {
        init {
            validateIdentity(transitionId, sourceExecutionId, logicalSetKey, normalAdvance.plannedSetId)
            require(offerId.isNotBlank())
            require(normalAdvance.transitionId == transitionId)
            require(normalAdvance.sourceExecutionId == sourceExecutionId)
            require(normalAdvance.logicalSetKey == logicalSetKey)
        }
    }

    @Serializable
    data class AcceptedRetry(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val sourceCoordinates: Coordinates,
        val plannedSetId: String?,
        val percentage: DropPercentage,
        val resolvedWeightPerCableKg: Float,
        val resultingExerciseMultiplier: Float,
        val nextAttemptNumber: Int,
    ) : RestTransitionPlan {
        init {
            validateIdentity(transitionId, sourceExecutionId, logicalSetKey, plannedSetId)
            require(offerId.isNotBlank())
            require(sourceCoordinates.setIndex == logicalSetKey.setIndex)
            require(resolvedWeightPerCableKg.isFinite() && resolvedWeightPerCableKg > 0f)
            require(resultingExerciseMultiplier.isFinite() && resultingExerciseMultiplier > 0f)
            require(nextAttemptNumber > 0)
        }
    }

    companion object {
        private fun validateIdentity(
            transitionId: String,
            sourceExecutionId: String,
            logicalSetKey: LogicalSetKey,
            plannedSetId: String?,
        ) {
            require(transitionId.isNotBlank())
            require(sourceExecutionId.isNotBlank())
            require(logicalSetKey.routineSessionId.isNotBlank())
            require(logicalSetKey.routineExerciseId.isNotBlank())
            require(logicalSetKey.setIndex >= 0)
            require(plannedSetId == null || plannedSetId.isNotBlank())
        }
    }
}

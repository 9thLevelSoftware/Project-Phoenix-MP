package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.consumeRepeat
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

@Serializable
data class RestActionIdentity(
    val transitionId: String,
    val offerId: String?,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
)

sealed interface RestTransitionCommand {
    val identity: RestActionIdentity

    data class Accept(
        override val identity: RestActionIdentity,
        val percentage: DropPercentage,
    ) : RestTransitionCommand

    data class Decline(
        override val identity: RestActionIdentity,
    ) : RestTransitionCommand

    data class SkipRest(
        override val identity: RestActionIdentity,
    ) : RestTransitionCommand
}

data class RestTransitionReducerState(
    val plan: RestTransitionPlan?,
    val currentSourceExecutionId: String,
    val attemptStates: List<PlannedSetAttemptState>,
) {
    constructor(
        plan: RestTransitionPlan?,
        currentSourceExecutionId: String,
        nextAttemptNumber: Int,
    ) : this(
        plan = plan,
        currentSourceExecutionId = currentSourceExecutionId,
        attemptStates = plan?.let {
            listOf(
                PlannedSetAttemptState(
                    logicalSetKey = it.logicalSetKey,
                    nextAttemptNumber = nextAttemptNumber,
                ),
            )
        }.orEmpty(),
    )

    init {
        require(currentSourceExecutionId.isNotBlank())
    }
}

enum class RestTransitionNoOpReason {
    NO_CURRENT_PLAN,
    SOURCE_EXECUTION_MISMATCH,
    TRANSITION_ID_MISMATCH,
    OFFER_ID_MISMATCH,
    LOGICAL_SET_KEY_MISMATCH,
    PLANNED_SET_ID_MISMATCH,
    DUPLICATE_COMMAND,
    COMMAND_STATE_MISMATCH,
    PERCENTAGE_NOT_OFFERED,
    UNRESOLVED_OFFER,
    ATTEMPT_STATE_MISSING,
    ATTEMPT_STATE_DUPLICATE,
    ATTEMPT_STATE_MISMATCH,
    ATTEMPT_STATE_INVALID,
    DROP_LIMIT_REACHED,
    PERSISTENCE_FAILURE,
    AUTHORITY_CHANGED,
    LIVE_IDENTITY_MISMATCH,
}

sealed interface RestTransitionReduction {
    data class Changed(
        val plan: RestTransitionPlan,
        val attemptStates: List<PlannedSetAttemptState>? = null,
    ) : RestTransitionReduction
    data class DispatchNormal(val plan: RestTransitionPlan.NormalAdvance) : RestTransitionReduction
    data class PendingAcceptedRetry(val plan: RestTransitionPlan.AcceptedRetry) : RestTransitionReduction
    data class NoOp(val reason: RestTransitionNoOpReason) : RestTransitionReduction
}

fun RestTransitionPlan.actionIdentity(): RestActionIdentity = RestActionIdentity(
    transitionId = transitionId,
    offerId = when (this) {
        is RestTransitionPlan.NormalAdvance -> null
        is RestTransitionPlan.UnresolvedDropOffer -> offerId
        is RestTransitionPlan.Declined -> offerId
        is RestTransitionPlan.AcceptedRetry -> offerId
    },
    logicalSetKey = logicalSetKey,
    plannedSetId = when (this) {
        is RestTransitionPlan.NormalAdvance -> plannedSetId
        is RestTransitionPlan.UnresolvedDropOffer -> plannedSetId
        is RestTransitionPlan.Declined -> normalAdvance.plannedSetId
        is RestTransitionPlan.AcceptedRetry -> plannedSetId
    },
)

fun buildRestTransitionPlan(
    normalAdvance: RestTransitionPlan.NormalAdvance,
    eligibility: DropSetEligibilityResult,
): RestTransitionPlan = when (eligibility) {
    is DropSetEligibilityResult.Ineligible -> normalAdvance

    is DropSetEligibilityResult.Eligible -> {
        val offer = eligibility.offer
        require(offer.routineIdentity.logicalSetKey == normalAdvance.logicalSetKey)
        require(offer.routineIdentity.plannedSetId == normalAdvance.plannedSetId)
        require(
            RestTransitionPlan.Coordinates(
                offer.routineIdentity.exerciseIndex,
                offer.routineIdentity.setIndex,
            ) == normalAdvance.sourceCoordinates,
        )
        RestTransitionPlan.UnresolvedDropOffer(
            transitionId = normalAdvance.transitionId,
            sourceExecutionId = normalAdvance.sourceExecutionId,
            logicalSetKey = normalAdvance.logicalSetKey,
            offerId = offer.offerId,
            plannedSetId = normalAdvance.plannedSetId,
            candidates = offer.candidates,
            normalAdvance = normalAdvance,
        )
    }
}

fun reduceRestTransition(
    state: RestTransitionReducerState,
    command: RestTransitionCommand,
): RestTransitionReduction {
    val plan = state.plan ?: return RestTransitionReduction.NoOp(RestTransitionNoOpReason.NO_CURRENT_PLAN)
    if (plan.sourceExecutionId != state.currentSourceExecutionId) {
        return RestTransitionReduction.NoOp(RestTransitionNoOpReason.SOURCE_EXECUTION_MISMATCH)
    }

    val expectedIdentity = plan.actionIdentity()
    val identity = command.identity
    if (identity.transitionId != expectedIdentity.transitionId) {
        return RestTransitionReduction.NoOp(RestTransitionNoOpReason.TRANSITION_ID_MISMATCH)
    }
    if (identity.offerId != expectedIdentity.offerId) {
        return RestTransitionReduction.NoOp(RestTransitionNoOpReason.OFFER_ID_MISMATCH)
    }
    if (identity.logicalSetKey != expectedIdentity.logicalSetKey) {
        return RestTransitionReduction.NoOp(RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH)
    }
    if (identity.plannedSetId != expectedIdentity.plannedSetId) {
        return RestTransitionReduction.NoOp(RestTransitionNoOpReason.PLANNED_SET_ID_MISMATCH)
    }

    return when (command) {
        is RestTransitionCommand.Accept -> when (plan) {
            is RestTransitionPlan.UnresolvedDropOffer -> {
                val selectedCandidate = plan.candidates.firstOrNull { it.percentage == command.percentage }
                    ?: return RestTransitionReduction.NoOp(RestTransitionNoOpReason.PERCENTAGE_NOT_OFFERED)
                val matchingAttemptStates = state.attemptStates.filter { it.logicalSetKey == plan.logicalSetKey }
                if (matchingAttemptStates.isEmpty()) {
                    val reason = if (state.attemptStates.isEmpty()) {
                        RestTransitionNoOpReason.ATTEMPT_STATE_MISSING
                    } else {
                        RestTransitionNoOpReason.ATTEMPT_STATE_MISMATCH
                    }
                    return RestTransitionReduction.NoOp(reason)
                }
                if (matchingAttemptStates.size != 1) {
                    return RestTransitionReduction.NoOp(RestTransitionNoOpReason.ATTEMPT_STATE_DUPLICATE)
                }
                val currentAttemptState = matchingAttemptStates.single()
                if (currentAttemptState.nextAttemptNumber <= 0) {
                    return RestTransitionReduction.NoOp(RestTransitionNoOpReason.ATTEMPT_STATE_INVALID)
                }
                if (currentAttemptState.acceptedDropCount >= 2) {
                    return RestTransitionReduction.NoOp(RestTransitionNoOpReason.DROP_LIMIT_REACHED)
                }
                val consumption = currentAttemptState.consumeRepeat(acceptedDrop = true)
                RestTransitionReduction.Changed(
                    plan = RestTransitionPlan.AcceptedRetry(
                        transitionId = plan.transitionId,
                        sourceExecutionId = plan.sourceExecutionId,
                        logicalSetKey = plan.logicalSetKey,
                        offerId = plan.offerId,
                        sourceCoordinates = plan.normalAdvance.sourceCoordinates,
                        plannedSetId = plan.plannedSetId,
                        percentage = selectedCandidate.percentage,
                        resolvedWeightPerCableKg = selectedCandidate.resolvedWeightPerCableKg,
                        resultingExerciseMultiplier = selectedCandidate.resultingExerciseMultiplier,
                        nextAttemptNumber = consumption.attemptNumber,
                    ),
                    attemptStates = state.attemptStates.map { existing ->
                        if (existing === currentAttemptState) consumption.nextState else existing
                    },
                )
            }

            is RestTransitionPlan.AcceptedRetry ->
                RestTransitionReduction.NoOp(RestTransitionNoOpReason.DUPLICATE_COMMAND)

            is RestTransitionPlan.NormalAdvance,
            is RestTransitionPlan.Declined,
            -> RestTransitionReduction.NoOp(RestTransitionNoOpReason.COMMAND_STATE_MISMATCH)
        }

        is RestTransitionCommand.Decline -> when (plan) {
            is RestTransitionPlan.UnresolvedDropOffer -> RestTransitionReduction.Changed(
                RestTransitionPlan.Declined(
                    transitionId = plan.transitionId,
                    sourceExecutionId = plan.sourceExecutionId,
                    logicalSetKey = plan.logicalSetKey,
                    offerId = plan.offerId,
                    normalAdvance = plan.normalAdvance,
                ),
            )

            is RestTransitionPlan.Declined ->
                RestTransitionReduction.NoOp(RestTransitionNoOpReason.DUPLICATE_COMMAND)

            is RestTransitionPlan.NormalAdvance,
            is RestTransitionPlan.AcceptedRetry,
            -> RestTransitionReduction.NoOp(RestTransitionNoOpReason.COMMAND_STATE_MISMATCH)
        }

        is RestTransitionCommand.SkipRest -> when (plan) {
            is RestTransitionPlan.NormalAdvance -> RestTransitionReduction.DispatchNormal(plan)

            is RestTransitionPlan.Declined -> RestTransitionReduction.DispatchNormal(plan.normalAdvance)

            is RestTransitionPlan.AcceptedRetry -> RestTransitionReduction.PendingAcceptedRetry(plan)

            is RestTransitionPlan.UnresolvedDropOffer ->
                RestTransitionReduction.NoOp(RestTransitionNoOpReason.UNRESOLVED_OFFER)
        }
    }
}

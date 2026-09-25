package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.manager.RestActionIdentity
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import com.devil.phoenixproject.presentation.manager.actionIdentity

data class DropSetOfferContext(
    val identity: RestActionIdentity,
    val exerciseDisplayName: String,
    val failedSetNumber: Int,
    val failedConfiguredWeightPerCableKg: Float,
    val minimumWeightPerCableKg: Float,
)

data class DropSetCandidateUiState(
    val percentage: DropPercentage,
    val weightPerCableKg: Float,
    val enabled: Boolean,
)

enum class DropSetRetryWaitState {
    SAVING_FAILED_ATTEMPT,
    PREPARING_TRAINER,
    READY_TO_RETRY,
}

sealed interface DropSetOfferUiState {
    val context: DropSetOfferContext

    data class Unresolved(
        override val context: DropSetOfferContext,
        val candidates: List<DropSetCandidateUiState>,
        val remainingDrops: Int,
    ) : DropSetOfferUiState

    data class AcceptedWaiting(
        override val context: DropSetOfferContext,
        val acceptedCandidate: DropSetCandidateUiState,
        val waitState: DropSetRetryWaitState,
    ) : DropSetOfferUiState

    data class RecoveryRequired(
        override val context: DropSetOfferContext,
        val acceptedCandidate: DropSetCandidateUiState?,
    ) : DropSetOfferUiState
}

data class DropSetOfferSelection(
    val offerId: String,
    val percentage: DropPercentage? = null,
)

fun selectionForDropSetOffer(
    previous: DropSetOfferSelection?,
    offerId: String,
): DropSetOfferSelection = if (previous?.offerId == offerId) {
    previous
} else {
    DropSetOfferSelection(offerId)
}

fun canRetryDropSet(
    offer: DropSetOfferUiState.Unresolved,
    selection: DropSetOfferSelection?,
): Boolean {
    val percentage = selection?.percentage ?: return false
    if (selection.offerId != offer.context.identity.offerId) return false
    return offer.candidates.any { it.percentage == percentage && it.enabled }
}

fun dropSetOfferUiState(
    plan: RestTransitionPlan?,
    teardown: MachineTeardownState,
    exerciseDisplayName: String,
    failedSetNumber: Int,
    failedConfiguredWeightPerCableKg: Float,
    minimumWeightPerCableKg: Float,
): DropSetOfferUiState? {
    val contextPlan = when (plan) {
        is RestTransitionPlan.UnresolvedDropOffer,
        is RestTransitionPlan.AcceptedRetry,
        -> plan

        is RestTransitionPlan.NormalAdvance,
        is RestTransitionPlan.Declined,
        null,
        -> return null
    }
    val context = DropSetOfferContext(
        identity = contextPlan.actionIdentity(),
        exerciseDisplayName = exerciseDisplayName,
        failedSetNumber = failedSetNumber,
        failedConfiguredWeightPerCableKg = failedConfiguredWeightPerCableKg,
        minimumWeightPerCableKg = minimumWeightPerCableKg,
    )
    return when (contextPlan) {
        is RestTransitionPlan.UnresolvedDropOffer -> DropSetOfferUiState.Unresolved(
            context = context,
            candidates = dropSetCandidateUiStates(contextPlan.candidates),
            remainingDrops = contextPlan.remainingDrops,
        )

        is RestTransitionPlan.AcceptedRetry -> {
            val accepted = DropSetCandidateUiState(
                percentage = contextPlan.percentage,
                weightPerCableKg = contextPlan.resolvedWeightPerCableKg,
                enabled = true,
            )
            when (teardown) {
                is MachineTeardownState.RecoveryRequired -> DropSetOfferUiState.RecoveryRequired(
                    context = context,
                    acceptedCandidate = accepted,
                )

                is MachineTeardownState.TearingDown -> DropSetOfferUiState.AcceptedWaiting(
                    context = context,
                    acceptedCandidate = accepted,
                    waitState = DropSetRetryWaitState.PREPARING_TRAINER,
                )

                MachineTeardownState.Ready -> DropSetOfferUiState.AcceptedWaiting(
                    context = context,
                    acceptedCandidate = accepted,
                    waitState = DropSetRetryWaitState.READY_TO_RETRY,
                )
            }
        }

        is RestTransitionPlan.NormalAdvance,
        is RestTransitionPlan.Declined,
        -> null
    }
}

private fun dropSetCandidateUiStates(
    offered: List<DropSetCandidate>,
): List<DropSetCandidateUiState> {
    val byPercentage = offered.associateBy { it.percentage }
    return DropPercentage.entries.map { percentage ->
        val candidate = byPercentage[percentage]
        DropSetCandidateUiState(
            percentage = percentage,
            weightPerCableKg = candidate?.resolvedWeightPerCableKg ?: 0f,
            enabled = candidate != null,
        )
    }
}

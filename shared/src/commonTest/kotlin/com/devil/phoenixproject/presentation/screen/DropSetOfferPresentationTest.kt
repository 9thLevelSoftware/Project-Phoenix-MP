package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropSetOfferPresentationTest {
    @Test
    fun unresolvedOfferHasNoPreselectionAndRequiresExplicitChoice() {
        val ui = dropSetOfferUiState(unresolvedPlan())
        val unresolved = assertIs<DropSetOfferUiState.Unresolved>(ui)
        assertEquals(3, unresolved.candidates.size)
        assertTrue(unresolved.candidates.all { it.enabled })
        assertFalse(canRetryDropSet(unresolved, DropSetOfferSelection("offer")))
        assertTrue(
            canRetryDropSet(
                unresolved,
                DropSetOfferSelection("offer", DropPercentage.TWENTY),
            ),
        )
    }

    @Test
    fun omittedCandidateIsDisabledAndCannotBeRetried() {
        val ui = dropSetOfferUiState(
            unresolvedPlan(
                candidates = listOf(
                    DropSetCandidate(DropPercentage.TEN, 45f, 0.9f),
                    DropSetCandidate(DropPercentage.TWENTY, 40f, 0.8f),
                ),
            ),
        )
        val unresolved = assertIs<DropSetOfferUiState.Unresolved>(ui)
        val thirty = unresolved.candidates.single { it.percentage == DropPercentage.THIRTY }
        assertFalse(thirty.enabled)
        assertFalse(
            canRetryDropSet(
                unresolved,
                DropSetOfferSelection("offer", DropPercentage.THIRTY),
            ),
        )
    }

    @Test
    fun newOfferIdClearsPreviousSelection() {
        val previous = DropSetOfferSelection("offer-a", DropPercentage.TEN)
        val next = selectionForDropSetOffer(previous, "offer-b")
        assertEquals("offer-b", next.offerId)
        assertNull(next.percentage)
        assertEquals(previous, selectionForDropSetOffer(previous, "offer-a"))
    }

    @Test
    fun declinedAndNormalPlansAreNotPresented() {
        assertNull(dropSetOfferUiState(unresolvedPlan().normalAdvance))
        assertNull(
            dropSetOfferUiState(
                RestTransitionPlan.Declined(
                    transitionId = "transition",
                    sourceExecutionId = "source",
                    logicalSetKey = logicalSetKey(),
                    offerId = "offer",
                    normalAdvance = unresolvedPlan().normalAdvance,
                ),
            ),
        )
    }

    @Test
    fun acceptedRetryMapsWaitingAndRecoveryStates() {
        val accepted = acceptedPlan()
        val waiting = assertIs<DropSetOfferUiState.AcceptedWaiting>(
            dropSetOfferUiState(accepted, MachineTeardownState.Ready),
        )
        assertEquals(DropPercentage.TWENTY, waiting.acceptedCandidate.percentage)
        assertEquals(40f, waiting.acceptedCandidate.weightPerCableKg)
        assertEquals(DropSetRetryWaitState.READY_TO_RETRY, waiting.waitState)

        val preparing = assertIs<DropSetOfferUiState.AcceptedWaiting>(
            dropSetOfferUiState(accepted, MachineTeardownState.TearingDown(1, 1)),
        )
        assertEquals(DropSetRetryWaitState.PREPARING_TRAINER, preparing.waitState)

        assertIs<DropSetOfferUiState.RecoveryRequired>(
            dropSetOfferUiState(accepted, MachineTeardownState.RecoveryRequired(1)),
        )
    }

    private fun dropSetOfferUiState(
        plan: RestTransitionPlan?,
        teardown: MachineTeardownState = MachineTeardownState.Ready,
    ) = dropSetOfferUiState(
        plan = plan,
        teardown = teardown,
        exerciseDisplayName = "Bench Press",
        failedSetNumber = 2,
        failedConfiguredWeightPerCableKg = 50f,
        minimumWeightPerCableKg = 5f,
    )

    private fun logicalSetKey() = LogicalSetKey("routine-session", "routine-exercise", 1, SetType.STANDARD)

    private fun unresolvedPlan(
        candidates: List<DropSetCandidate> = listOf(
            DropSetCandidate(DropPercentage.TEN, 45f, 0.9f),
            DropSetCandidate(DropPercentage.TWENTY, 40f, 0.8f),
            DropSetCandidate(DropPercentage.THIRTY, 35f, 0.7f),
        ),
    ): RestTransitionPlan.UnresolvedDropOffer {
        val normal = RestTransitionPlan.NormalAdvance(
            transitionId = "transition",
            sourceExecutionId = "source",
            logicalSetKey = logicalSetKey(),
            sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
            plannedSetId = "planned",
            restDurationSeconds = 45,
        )
        return RestTransitionPlan.UnresolvedDropOffer(
            transitionId = "transition",
            sourceExecutionId = "source",
            logicalSetKey = logicalSetKey(),
            offerId = "offer",
            plannedSetId = "planned",
            candidates = candidates,
            remainingDrops = 2,
            normalAdvance = normal,
        )
    }

    private fun acceptedPlan() = RestTransitionPlan.AcceptedRetry(
        transitionId = "transition",
        sourceExecutionId = "source",
        logicalSetKey = logicalSetKey(),
        offerId = "offer",
        sourceCoordinates = RestTransitionPlan.Coordinates(3, 1),
        plannedSetId = "planned",
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 40f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 2,
    )
}

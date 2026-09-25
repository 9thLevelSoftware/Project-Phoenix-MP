package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.DropSetIneligibleReason
import com.devil.phoenixproject.domain.model.DropSetOffer
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DropSetRestTransitionTest {
    @Test
    fun `normal and eligible plans share the captured source transition`() {
        val normal = normalPlan()

        assertEquals(
            normal,
            buildRestTransitionPlan(
                normal,
                DropSetEligibilityResult.Ineligible(DropSetIneligibleReason.NOT_STALL_FAILURE),
            ),
        )

        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
            buildRestTransitionPlan(
                normal,
                DropSetEligibilityResult.Eligible(
                    DropSetOffer(
                        offerId = OFFER_ID,
                        routineIdentity = RoutineExecutionIdentity(
                            profileId = "profile",
                            routineId = "routine",
                            routineSessionId = logicalSetKey().routineSessionId,
                            routineExerciseId = logicalSetKey().routineExerciseId,
                            logicalSetKey = logicalSetKey(),
                            plannedSetId = PLANNED_SET_ID,
                            exerciseIndex = coordinates().exerciseIndex,
                            setIndex = coordinates().setIndex,
                        ),
                        candidates = listOf(candidate(DropPercentage.TWENTY, 40f, 0.8f)),
                        remainingDrops = 2,
                    ),
                ),
            ),
        )
        assertEquals(normal.transitionId, unresolved.transitionId)
        assertEquals(normal.sourceExecutionId, unresolved.sourceExecutionId)
        assertEquals(normal.logicalSetKey, unresolved.logicalSetKey)
        assertEquals(normal.sourceCoordinates, unresolved.normalAdvance.sourceCoordinates)
        assertEquals(normal, unresolved.normalAdvance)
    }

    @Test
    fun `accept resolves each captured percentage without changing source identity`() {
        val unresolved = unresolvedPlan()

        val expected = listOf(
            DropPercentage.TEN to (45f to 0.9f),
            DropPercentage.TWENTY to (40f to 0.8f),
            DropPercentage.THIRTY to (35f to 0.7f),
        )

        expected.forEach { (percentage, values) ->
            val result = reduceRestTransition(
                state = RestTransitionReducerState(
                    plan = unresolved,
                    currentSourceExecutionId = SOURCE_EXECUTION_ID,
                    nextAttemptNumber = 2,
                ),
                command = RestTransitionCommand.Accept(unresolved.actionIdentity(), percentage),
            )

            val accepted = assertIs<RestTransitionReduction.Changed>(result).plan
            assertEquals(
                RestTransitionPlan.AcceptedRetry(
                    transitionId = TRANSITION_ID,
                    sourceExecutionId = SOURCE_EXECUTION_ID,
                    logicalSetKey = logicalSetKey(),
                    offerId = OFFER_ID,
                    sourceCoordinates = coordinates(),
                    plannedSetId = PLANNED_SET_ID,
                    percentage = percentage,
                    resolvedWeightPerCableKg = values.first,
                    resultingExerciseMultiplier = values.second,
                    nextAttemptNumber = 2,
                ),
                accepted,
            )
        }
    }

    @Test
    fun `accept consumes the one matching attempt state and enforces the drop cap`() {
        val unresolved = unresolvedPlan()
        val otherState = attemptState(
            key = logicalSetKey().copy(setIndex = 0),
            nextAttemptNumber = 7,
            acceptedDropCount = 1,
        )

        val first = assertIs<RestTransitionReduction.Changed>(
            reduceRestTransition(
                RestTransitionReducerState(
                    plan = unresolved,
                    currentSourceExecutionId = SOURCE_EXECUTION_ID,
                    attemptStates = listOf(otherState, attemptState(nextAttemptNumber = 2, acceptedDropCount = 0)),
                ),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        assertEquals(2, assertIs<RestTransitionPlan.AcceptedRetry>(first.plan).nextAttemptNumber)
        assertEquals(
            listOf(otherState, attemptState(nextAttemptNumber = 3, acceptedDropCount = 1)),
            first.attemptStates,
        )

        val second = assertIs<RestTransitionReduction.Changed>(
            reduceRestTransition(
                RestTransitionReducerState(
                    plan = unresolved,
                    currentSourceExecutionId = SOURCE_EXECUTION_ID,
                    attemptStates = listOf(attemptState(nextAttemptNumber = 3, acceptedDropCount = 1)),
                ),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        assertEquals(3, assertIs<RestTransitionPlan.AcceptedRetry>(second.plan).nextAttemptNumber)
        assertEquals(listOf(attemptState(nextAttemptNumber = 4, acceptedDropCount = 2)), second.attemptStates)

        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.DROP_LIMIT_REACHED),
            reduceRestTransition(
                RestTransitionReducerState(
                    plan = unresolved,
                    currentSourceExecutionId = SOURCE_EXECUTION_ID,
                    attemptStates = listOf(attemptState(nextAttemptNumber = 4, acceptedDropCount = 2)),
                ),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
    }

    @Test
    fun `accept rejects missing duplicate mismatched and invalid attempt state`() {
        val unresolved = unresolvedPlan()
        val cases = listOf(
            emptyList<PlannedSetAttemptState>() to RestTransitionNoOpReason.ATTEMPT_STATE_MISSING,
            listOf(
                attemptState(nextAttemptNumber = 2),
                attemptState(nextAttemptNumber = 3),
            ) to RestTransitionNoOpReason.ATTEMPT_STATE_DUPLICATE,
            listOf(
                attemptState(key = logicalSetKey().copy(routineExerciseId = "other-occurrence")),
            ) to RestTransitionNoOpReason.ATTEMPT_STATE_MISMATCH,
            listOf(
                attemptState(nextAttemptNumber = 0),
            ) to RestTransitionNoOpReason.ATTEMPT_STATE_INVALID,
        )

        cases.forEach { (attemptStates, reason) ->
            assertEquals(
                RestTransitionReduction.NoOp(reason),
                reduceRestTransition(
                    RestTransitionReducerState(
                        plan = unresolved,
                        currentSourceExecutionId = SOURCE_EXECUTION_ID,
                        attemptStates = attemptStates,
                    ),
                    RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
                ),
            )
        }
    }

    @Test
    fun `decline preserves the exact nested normal transition and does not dispatch it`() {
        val unresolved = unresolvedPlan()

        val result = reduceRestTransition(
            RestTransitionReducerState(unresolved, SOURCE_EXECUTION_ID, nextAttemptNumber = 2),
            RestTransitionCommand.Decline(unresolved.actionIdentity()),
        )

        assertEquals(
            RestTransitionReduction.Changed(
                RestTransitionPlan.Declined(
                    transitionId = TRANSITION_ID,
                    sourceExecutionId = SOURCE_EXECUTION_ID,
                    logicalSetKey = logicalSetKey(),
                    offerId = OFFER_ID,
                    normalAdvance = unresolved.normalAdvance,
                ),
            ),
            result,
        )
    }

    @Test
    fun `skip dispatches normal and declined transitions but blocks unresolved offer`() {
        val normal = normalPlan()
        val declined = RestTransitionPlan.Declined(
            transitionId = TRANSITION_ID,
            sourceExecutionId = SOURCE_EXECUTION_ID,
            logicalSetKey = logicalSetKey(),
            offerId = OFFER_ID,
            normalAdvance = normal,
        )
        val unresolved = unresolvedPlan()

        assertEquals(
            RestTransitionReduction.DispatchNormal(normal),
            reduceRestTransition(
                RestTransitionReducerState(normal, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.SkipRest(normal.actionIdentity()),
            ),
        )
        assertEquals(
            RestTransitionReduction.DispatchNormal(normal),
            reduceRestTransition(
                RestTransitionReducerState(declined, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.SkipRest(declined.actionIdentity()),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.UNRESOLVED_OFFER),
            reduceRestTransition(
                RestTransitionReducerState(unresolved, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.SkipRest(unresolved.actionIdentity()),
            ),
        )
    }

    @Test
    fun `skip of accepted retry reports pending without effects`() {
        val accepted = acceptedPlan()

        assertEquals(
            RestTransitionReduction.PendingAcceptedRetry(accepted),
            reduceRestTransition(
                RestTransitionReducerState(accepted, SOURCE_EXECUTION_ID, 3),
                RestTransitionCommand.SkipRest(accepted.actionIdentity()),
            ),
        )
    }

    @Test
    fun `each identity mutation is rejected before command semantics`() {
        val unresolved = unresolvedPlan()
        val identity = unresolved.actionIdentity()
        val cases = listOf(
            identity.copy(transitionId = "other-transition") to RestTransitionNoOpReason.TRANSITION_ID_MISMATCH,
            identity.copy(offerId = "other-offer") to RestTransitionNoOpReason.OFFER_ID_MISMATCH,
            identity.copy(
                logicalSetKey = identity.logicalSetKey.copy(routineSessionId = "other-routine-session"),
            ) to RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH,
            identity.copy(
                logicalSetKey = identity.logicalSetKey.copy(routineExerciseId = "other-occurrence"),
            ) to RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH,
            identity.copy(
                logicalSetKey = identity.logicalSetKey.copy(setIndex = 2),
            ) to RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH,
            identity.copy(
                logicalSetKey = identity.logicalSetKey.copy(setKind = SetType.AMRAP),
            ) to RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH,
            identity.copy(plannedSetId = "other-planned-set") to RestTransitionNoOpReason.PLANNED_SET_ID_MISMATCH,
        )

        cases.forEach { (mutatedIdentity, expectedReason) ->
            assertEquals(
                RestTransitionReduction.NoOp(expectedReason),
                reduceRestTransition(
                    RestTransitionReducerState(unresolved, SOURCE_EXECUTION_ID, 2),
                    RestTransitionCommand.Accept(mutatedIdentity, DropPercentage.TWENTY),
                ),
            )
        }
    }

    @Test
    fun `planned set identity requires exact null symmetry`() {
        val withoutPlannedSet = unresolvedPlan(plannedSetId = null)

        assertIs<RestTransitionReduction.Changed>(
            reduceRestTransition(
                RestTransitionReducerState(withoutPlannedSet, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(withoutPlannedSet.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.PLANNED_SET_ID_MISMATCH),
            reduceRestTransition(
                RestTransitionReducerState(withoutPlannedSet, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(
                    withoutPlannedSet.actionIdentity().copy(plannedSetId = PLANNED_SET_ID),
                    DropPercentage.TWENTY,
                ),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.PLANNED_SET_ID_MISMATCH),
            reduceRestTransition(
                RestTransitionReducerState(unresolvedPlan(), SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(
                    unresolvedPlan().actionIdentity().copy(plannedSetId = null),
                    DropPercentage.TWENTY,
                ),
            ),
        )
    }

    @Test
    fun `stale execution and missing plan are typed no ops`() {
        val unresolved = unresolvedPlan()

        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.SOURCE_EXECUTION_MISMATCH),
            reduceRestTransition(
                RestTransitionReducerState(unresolved, "newer-execution", 2),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.NO_CURRENT_PLAN),
            reduceRestTransition(
                RestTransitionReducerState(null, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
    }

    @Test
    fun `duplicate accept and decline are typed separately from command state mismatch`() {
        val accepted = acceptedPlan()
        val declined = RestTransitionPlan.Declined(
            transitionId = TRANSITION_ID,
            sourceExecutionId = SOURCE_EXECUTION_ID,
            logicalSetKey = logicalSetKey(),
            offerId = OFFER_ID,
            normalAdvance = normalPlan(),
        )

        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.DUPLICATE_COMMAND),
            reduceRestTransition(
                RestTransitionReducerState(accepted, SOURCE_EXECUTION_ID, 3),
                RestTransitionCommand.Accept(accepted.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.DUPLICATE_COMMAND),
            reduceRestTransition(
                RestTransitionReducerState(declined, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Decline(declined.actionIdentity()),
            ),
        )
        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.COMMAND_STATE_MISMATCH),
            reduceRestTransition(
                RestTransitionReducerState(declined, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(declined.actionIdentity(), DropPercentage.TWENTY),
            ),
        )
    }

    @Test
    fun `percentage absent from captured candidates is rejected`() {
        val unresolved = unresolvedPlan().copy(
            candidates = listOf(candidate(DropPercentage.TWENTY, 40f, 0.8f)),
        )

        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.PERCENTAGE_NOT_OFFERED),
            reduceRestTransition(
                RestTransitionReducerState(unresolved, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TEN),
            ),
        )
    }

    @Test
    fun `identity validation wins before a percentage is evaluated`() {
        val unresolved = unresolvedPlan().copy(
            candidates = listOf(candidate(DropPercentage.TWENTY, 40f, 0.8f)),
        )

        assertEquals(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.OFFER_ID_MISMATCH),
            reduceRestTransition(
                RestTransitionReducerState(unresolved, SOURCE_EXECUTION_ID, 2),
                RestTransitionCommand.Accept(
                    identity = unresolved.actionIdentity().copy(offerId = "stale-offer"),
                    percentage = DropPercentage.TEN,
                ),
            ),
        )
    }

    private fun logicalSetKey() = LogicalSetKey(
        routineSessionId = "routine-session",
        routineExerciseId = "routine-exercise",
        setIndex = 1,
        setKind = SetType.STANDARD,
    )

    private fun coordinates() = RestTransitionPlan.Coordinates(exerciseIndex = 3, setIndex = 1)

    private fun normalPlan(plannedSetId: String? = PLANNED_SET_ID) = RestTransitionPlan.NormalAdvance(
        transitionId = TRANSITION_ID,
        sourceExecutionId = SOURCE_EXECUTION_ID,
        logicalSetKey = logicalSetKey(),
        sourceCoordinates = coordinates(),
        plannedSetId = plannedSetId,
        restDurationSeconds = 45,
    )

    private fun unresolvedPlan(plannedSetId: String? = PLANNED_SET_ID): RestTransitionPlan.UnresolvedDropOffer {
        val normal = normalPlan(plannedSetId)
        return RestTransitionPlan.UnresolvedDropOffer(
            transitionId = TRANSITION_ID,
            sourceExecutionId = SOURCE_EXECUTION_ID,
            logicalSetKey = logicalSetKey(),
            offerId = OFFER_ID,
            plannedSetId = plannedSetId,
            candidates = listOf(
                candidate(DropPercentage.TEN, 45f, 0.9f),
                candidate(DropPercentage.TWENTY, 40f, 0.8f),
                candidate(DropPercentage.THIRTY, 35f, 0.7f),
            ),
            normalAdvance = normal,
        )
    }

    private fun acceptedPlan() = RestTransitionPlan.AcceptedRetry(
        transitionId = TRANSITION_ID,
        sourceExecutionId = SOURCE_EXECUTION_ID,
        logicalSetKey = logicalSetKey(),
        offerId = OFFER_ID,
        sourceCoordinates = coordinates(),
        plannedSetId = PLANNED_SET_ID,
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 40f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 2,
    )

    private fun candidate(
        percentage: DropPercentage,
        weight: Float,
        multiplier: Float,
    ) = DropSetCandidate(
        percentage = percentage,
        resolvedWeightPerCableKg = weight,
        resultingExerciseMultiplier = multiplier,
    )

    private fun attemptState(
        key: LogicalSetKey = logicalSetKey(),
        nextAttemptNumber: Int = 2,
        acceptedDropCount: Int = 0,
    ) = PlannedSetAttemptState(
        logicalSetKey = key,
        nextAttemptNumber = nextAttemptNumber,
        acceptedDropCount = acceptedDropCount,
    )

    private companion object {
        const val TRANSITION_ID = "transition-1"
        const val SOURCE_EXECUTION_ID = "101"
        const val OFFER_ID = "offer-1"
        const val PLANNED_SET_ID = "planned-set-1"
    }
}

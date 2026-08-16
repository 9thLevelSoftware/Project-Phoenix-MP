package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Post-commit race coverage for restored actions.
 *
 * The repository hook runs after the replacement is visible from the fake's committed-document view. A cancellation
 * or authority change at that point must therefore reconcile the committed state (or durably clear it
 * into manual presentation); retaining the previous actionable plan in memory is never a valid result.
 */
class RestoredActionPersistenceRaceTest {
    private enum class Decision { ACCEPT, DECLINE }

    private enum class InitialPlan { UNRESOLVED, NORMAL, DECLINED, ACCEPTED }

    private enum class NavigationPlan { NORMAL, DECLINED }

    private enum class NormalPublication { SET_READY, COMPLETE }

    private enum class RestoredPlanCommit { DECISION, NORMAL_CLEAR, ACCEPTED_CLEAR }

    private enum class ManualRecoveryMutationPoint { BEFORE_CLAIM, AFTER_CLAIM }

    private enum class ManualRecoveryAuthorityMutation { REVOKE_OWNER, CONFIGURATION_INPUT }

    private enum class RestoredExternalAuthorityMutation { PROFILE, RACK }

    private enum class AcceptedIdentityMutation(
        val expectedReason: RestTransitionNoOpReason?,
    ) {
        SOURCE_EXECUTION(RestTransitionNoOpReason.SOURCE_EXECUTION_MISMATCH),
        SELECTED_PERCENTAGE(RestTransitionNoOpReason.SELECTED_PERCENTAGE_MISMATCH),
        TRANSITION_ID(RestTransitionNoOpReason.TRANSITION_ID_MISMATCH),
        ROUTINE_SESSION(RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH),
        ROUTINE_EXERCISE(RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH),
        SET_INDEX(RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH),
        SET_KIND(RestTransitionNoOpReason.LOGICAL_SET_KEY_MISMATCH),
        PLANNED_SET_ID(RestTransitionNoOpReason.PLANNED_SET_ID_MISMATCH),
        OFFER_ID(RestTransitionNoOpReason.OFFER_ID_MISMATCH),
    }

    private enum class UnresolvedAcceptMutation(
        val expectedReason: RestTransitionNoOpReason,
    ) {
        OFFER_ID(RestTransitionNoOpReason.OFFER_ID_MISMATCH),
    }

    private data class InstalledRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    @Test
    fun `rest action identities carry exact source and Accepted-only selected percentage`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "full-rest-action-identity",
                initialPlan = InitialPlan.UNRESOLVED,
            )
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(installed.document.restTransitionPlan)
            val normal = unresolved.normalAdvance
            val declined = RestTransitionPlan.Declined(
                transitionId = unresolved.transitionId,
                sourceExecutionId = unresolved.sourceExecutionId,
                logicalSetKey = unresolved.logicalSetKey,
                offerId = unresolved.offerId,
                normalAdvance = normal,
            )
            val accepted = acceptedPlan(unresolved)

            listOf(
                unresolved to null,
                normal to null,
                declined to null,
                accepted to DropPercentage.TWENTY,
            ).forEach { (plan, expectedPercentage) ->
                val identity = plan.actionIdentity()
                assertEquals(plan.sourceExecutionId, identity.sourceExecutionId)
                assertEquals(expectedPercentage, identity.selectedPercentage)
            }
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accept and Decline reconcile a committed decision before cancellation rethrows`() = runTest {
        enumValues<Decision>().forEach { decision ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "post-commit-cancel-${decision.name.lowercase()}",
                    initialPlan = InitialPlan.UNRESOLVED,
                )
                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
                val expectedDocument = decidedDocument(installed.document, decision)
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                var cancellationInjected = false
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!cancellationInjected && committed == expectedDocument) {
                        cancellationInjected = true
                        throw CancellationException("cancel after restored decision commit")
                    }
                }

                assertFailsWith<CancellationException> {
                    harness.dwsm.applyRestTransitionAwait(decision.command(unresolved))
                }

                assertTrue(cancellationInjected, decision.name)
                assertDecisionCommitWasNotLost(harness, expectedDocument)
                assertExactDecisionReconciled(
                    harness = harness,
                    expectedDocument = expectedDocument,
                )
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, decision.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), decision.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Accept and Decline fail closed when authority is superseded after commit`() = runTest {
        enumValues<Decision>().forEach { decision ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "post-commit-supersede-${decision.name.lowercase()}",
                    initialPlan = InitialPlan.UNRESOLVED,
                )
                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
                val expectedDocument = decidedDocument(installed.document, decision)
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                var authorityRevoked = false
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!authorityRevoked && committed == expectedDocument) {
                        authorityRevoked = harness.activeSessionEngine.executionGuard.revokeRestoredRuntime()
                    }
                }

                harness.dwsm.applyRestTransitionAwait(decision.command(unresolved))

                assertTrue(authorityRevoked, decision.name)
                assertDecisionCommitWasNotLost(harness, expectedDocument)
                assertSupersededCommitIsInert(harness, expectedDocument)
                assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest, decision.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, decision.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), decision.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Normal and Declined Skip finish manual navigation before post-commit cancellation rethrows`() = runTest {
        enumValues<NavigationPlan>().forEach { planCase ->
            val harness = enabledHarness()
            try {
                harness.setActiveSummaryCountdownSeconds(-1)
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "${planCase.name.lowercase()}-clear-post-commit-cancel",
                    initialPlan = planCase.initialPlan(),
                )
                val plan = assertNotNull(harness.restTransitionPlan.value)
                val cleared = installed.document.copy(restTransitionPlan = null)
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                var cancellationInjected = false
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!cancellationInjected && committed == cleared) {
                        cancellationInjected = true
                        throw CancellationException("cancel after restored navigation clear")
                    }
                }

                assertFailsWith<CancellationException> {
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }

                assertTrue(cancellationInjected, planCase.name)
                assertDurablyClearedAndManual(harness, installed.document, expectedExerciseIndex = 0, expectedSetIndex = 1)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, planCase.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), planCase.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Normal and Declined Skip fail closed when authority is superseded after clear commits`() = runTest {
        enumValues<NavigationPlan>().forEach { planCase ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "${planCase.name.lowercase()}-clear-post-commit-supersede",
                    initialPlan = planCase.initialPlan(),
                )
                val plan = assertNotNull(harness.restTransitionPlan.value)
                val cleared = installed.document.copy(restTransitionPlan = null)
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                var authorityRevoked = false
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!authorityRevoked && committed == cleared) {
                        authorityRevoked = harness.activeSessionEngine.executionGuard.revokeRestoredRuntime()
                    }
                }

                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity()))

                assertTrue(authorityRevoked, planCase.name)
                assertSupersededCommitIsInert(harness, cleared)
                assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest, planCase.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, planCase.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), planCase.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Normal owner is inert at SetReady and Complete publication`() = runTest {
        enumValues<NormalPublication>().forEach { publication ->
            val harness = enabledHarness()
            var observerJob: kotlinx.coroutines.Job? = null
            try {
                harness.setActiveSummaryCountdownSeconds(-1)
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "normal-owner-inert-${publication.name.lowercase()}",
                    initialPlan = InitialPlan.NORMAL,
                    setsPerExercise = if (publication == NormalPublication.COMPLETE) 1 else 2,
                )
                val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
                val exactOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
                val guardAdmission = CompletableDeferred<Result<ExecutionLease>>()
                val callbackAction = CompletableDeferred<RestTransitionReduction>()
                observerJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    harness.coordinator.routineFlowState
                        .filter { state ->
                            when (publication) {
                                NormalPublication.SET_READY ->
                                    state is RoutineFlowState.SetReady && state.exerciseIndex == 0 && state.setIndex == 1

                                NormalPublication.COMPLETE -> state is RoutineFlowState.Complete
                            }
                        }
                        .first()
                    guardAdmission.complete(
                        harness.activeSessionEngine.executionGuard.beginRestoredSuccessorExecution(
                            owner = exactOwner,
                            seed = ExecutionSeed(
                                sessionId = "forbidden-${publication.name.lowercase()}",
                                profileId = installed.document.profileId,
                                requiresMachine = true,
                                workingRepTarget = 10,
                            ),
                        ) { true },
                    )
                    callbackAction.complete(
                        harness.dwsm.applyRestTransitionAwait(
                            RestTransitionCommand.SkipRest(normal.actionIdentity()),
                        ),
                    )
                }
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

                assertIs<RestTransitionReduction.DispatchNormal>(
                    harness.dwsm.applyRestTransitionAwait(
                        RestTransitionCommand.SkipRest(normal.actionIdentity()),
                    ),
                )
                val admission = withTimeout(1_000) { guardAdmission.await() }
                val callbackReduction = withTimeout(1_000) { callbackAction.await() }

                assertTrue(admission.isFailure, publication.name)
                val noOp = assertIs<RestTransitionReduction.NoOp>(callbackReduction)
                assertEquals(RestTransitionNoOpReason.AUTHORITY_CHANGED, noOp.reason, publication.name)
                assertNull(harness.restTransitionPlan.value, publication.name)
                when (publication) {
                    NormalPublication.SET_READY -> {
                        val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
                        assertEquals(0, setReady.exerciseIndex)
                        assertEquals(1, setReady.setIndex)
                    }

                    NormalPublication.COMPLETE ->
                        assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
                }
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, publication.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), publication.name)
            } finally {
                observerJob?.cancel()
                harness.cleanup()
            }
        }
    }

    @Test
    fun `duplicate restored Accepted Skips at committed clear create one successor`() = runTest {
        val harness = enabledHarness()
        val firstClearCommitted = CompletableDeferred<Unit>()
        val releaseFirstClear = CompletableDeferred<Unit>()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-duplicate-at-committed-clear",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            var clearCommits = 0
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (committed == cleared) {
                    clearCommits++
                    if (clearCommits == 1) {
                        firstClearCommitted.complete(Unit)
                        releaseFirstClear.await()
                    }
                }
            }

            val first = async {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                )
            }
            runCurrent()
            withTimeout(1_000) { firstClearCommitted.await() }
            val duplicate = async {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                )
            }
            runCurrent()
            assertFalse(duplicate.isCompleted, "the duplicate must contend at the first committed-clear boundary")

            releaseFirstClear.complete(Unit)
            val reductions = listOf(first.await(), duplicate.await())
            runCurrent()

            assertEquals(1, clearCommits)
            assertEquals(
                1,
                harness.fakeActiveWorkoutRuntimeRepository.replacements
                    .drop(replacementsBefore)
                    .count { it.document == cleared },
            )
            assertEquals(1, reductions.count { it is RestTransitionReduction.PendingAcceptedRetry })
            assertEquals(1, reductions.count { it is RestTransitionReduction.NoOp })
            assertEquals(configurationsBefore + 1, harness.fakeBleRepo.commandsReceived.size)
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
        } finally {
            releaseFirstClear.complete(Unit)
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accepted rejects every represented non-exact Skip identity without granting permission`() = runTest {
        enumValues<AcceptedIdentityMutation>().forEach { mutation ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "accepted-wrong-${mutation.name.lowercase()}",
                    initialPlan = InitialPlan.ACCEPTED,
                )
                val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
                val identity = mutateAcceptedIdentity(accepted.actionIdentity(), mutation)
                val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

                val reduction = assertIs<RestTransitionReduction.NoOp>(
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(identity)),
                )
                mutation.expectedReason?.let { expectedReason ->
                    assertEquals(expectedReason, reduction.reason, mutation.name)
                }
                val startedWithoutPermission = harness.activeSessionEngine.tryStartAcceptedRetry(
                    installed.restoredGate(accepted),
                )

                assertFalse(startedWithoutPermission, mutation.name)
                assertEquals(
                    installed.document,
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                )
                assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
                assertEquals(accepted, harness.restTransitionPlan.value, mutation.name)
                assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size, mutation.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, mutation.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), mutation.name)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Unresolved rejects non-exact offer identity without persistence`() = runTest {
        enumValues<UnresolvedAcceptMutation>().forEach { mutation ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "unresolved-wrong-${mutation.name.lowercase()}",
                    initialPlan = InitialPlan.UNRESOLVED,
                )
                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
                val command = when (mutation) {
                    UnresolvedAcceptMutation.OFFER_ID ->
                        RestTransitionCommand.Accept(
                            unresolved.actionIdentity().copy(offerId = "wrong-offer"),
                            DropPercentage.TWENTY,
                        )
                }
                val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

                val reduction = assertIs<RestTransitionReduction.NoOp>(
                    harness.dwsm.applyRestTransitionAwait(command),
                )

                assertEquals(mutation.expectedReason, reduction.reason, mutation.name)
                assertEquals(
                    installed.document,
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                )
                assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
                assertEquals(unresolved, harness.restTransitionPlan.value, mutation.name)
                assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size, mutation.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, mutation.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), mutation.name)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Accepted Skip preserves exact permission when plan clear is cancelled before commit`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-clear-pre-commit-cancel",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace =
                CancellationException("cancel before restored accepted clear commit")

            assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            }

            assertEquals(
                installed.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(accepted, harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            val startedWithoutAnotherSkip = harness.activeSessionEngine.tryStartAcceptedRetry(
                RetryPersistenceGate.Restored(
                    sourceStableSessionId = installed.document.sourceStableSessionId,
                    actionIdentity = accepted.actionIdentity(),
                    sourceContext = installed.document.sourceAuthority.toRestoredRetrySourceContext(),
                ),
            )
            runCurrent()

            assertTrue(startedWithoutAnotherSkip, "the exact pre-commit Skip permission must be retained")
            assertEquals(configurationsBefore + 1, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accepted fail closed rebuilds manual state from immutable command and rack snapshots`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-fail-closed-immutable-authority",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val immutableTemplate = installed.document.sourceAuthority.toRestoredRetrySourceContext().commandTemplate
            val expectedManualParameters = immutableTemplate.copy(
                weightPerCableKg = accepted.resolvedWeightPerCableKg,
            )
            val immutableRackOverrides = harness.coordinator.activeRackBehaviorOverrides.value.toMap()
            assertEquals(immutableTemplate, harness.coordinator.workoutParameters.value)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

            harness.coordinator._workoutParameters.value = immutableTemplate.copy(
                programMode = ProgramMode.Echo,
                reps = 99,
                weightPerCableKg = 77f,
            )
            harness.coordinator._activeRackBehaviorOverrides.value = mapOf(
                "poison-rack-item" to RackItemBehavior.COUNTERWEIGHT,
            )
            var committedManualParameters: WorkoutParameters? = null
            var committedManualRackOverrides: Map<String, RackItemBehavior>? = null
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = { params, overrides ->
                committedManualParameters = params
                committedManualRackOverrides = overrides
            }
            harness.fakeCompletedSetRepo.softDeleteSession(installed.document.sourceStableSessionId)

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                ),
            )

            assertDurablyClearedAndManual(harness, installed.document)
            assertEquals(expectedManualParameters, committedManualParameters)
            assertEquals(immutableRackOverrides, committedManualRackOverrides)
            assertEquals(expectedManualParameters, harness.coordinator.workoutParameters.value)
            assertEquals(immutableRackOverrides, harness.coordinator.activeRackBehaviorOverrides.value)
            val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(expectedManualParameters.weightPerCableKg, setReady.adjustedWeight)
            assertEquals(expectedManualParameters.reps, setReady.adjustedReps)
            assertEquals(expectedManualParameters.progressionRegressionKg, setReady.adjustedProgressionKg)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accepted Skip fails closed before post-clear cancellation rethrows`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-clear-post-commit-cancel",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            var cancellationInjected = false
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!cancellationInjected && committed == cleared) {
                    cancellationInjected = true
                    throw CancellationException("cancel after restored accepted clear")
                }
            }

            assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            }

            assertTrue(cancellationInjected)
            assertDurablyClearedAndManual(harness, installed.document)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accepted Skip fails closed when authority is superseded after its clear commits`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-clear-post-commit-supersede",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            var authorityRevoked = false
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!authorityRevoked && committed == cleared) {
                    authorityRevoked = harness.activeSessionEngine.executionGuard.revokeRestoredRuntime()
                }
            }

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))

            assertTrue(authorityRevoked)
            assertSupersededCommitIsInert(harness, cleared)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Accepted post-commit cancellation plus supersession mirrors the clear and stays inert`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-clear-post-commit-cancel-and-supersede",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            var combinedRaceInjected = false
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!combinedRaceInjected && committed == cleared) {
                    combinedRaceInjected = true
                    assertTrue(harness.activeSessionEngine.executionGuard.revokeRestoredRuntime())
                    throw CancellationException("original accepted clear cancellation")
                }
            }

            val error = assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            }

            assertEquals("original accepted clear cancellation", error.message)
            assertTrue(combinedRaceInjected)
            assertSupersededCommitIsInert(harness, cleared)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                ExecutionSeed(
                    sessionId = "fresh-after-combined-race",
                    profileId = installed.document.profileId,
                    requiresMachine = false,
                    workingRepTarget = 0,
                    isBodyweight = true,
                ),
            )
            assertTrue(fresh.isSuccess, "a superseded Ready restored record must not block fresh execution")
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored manual recovery claim is owner and configuration bound at every publication seam`() = runTest {
        enumValues<ManualRecoveryMutationPoint>().forEach { point ->
            enumValues<ManualRecoveryAuthorityMutation>().forEach { mutation ->
                val harness = enabledHarness()
                try {
                    val installed = installRestoredRuntime(
                        harness = harness,
                        routineSessionId = "manual-claim-${point.name.lowercase()}-${mutation.name.lowercase()}",
                        initialPlan = InitialPlan.ACCEPTED,
                    )
                    val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
                    val cleared = installed.document.copy(restTransitionPlan = null)
                    val exactOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
                    val poisonParameters = harness.coordinator.workoutParameters.value.copy(
                        programMode = ProgramMode.Echo,
                        reps = 77,
                        weightPerCableKg = 66f,
                    )
                    val poisonRackOverrides = mapOf("post-claim-poison" to RackItemBehavior.COUNTERWEIGHT)
                    var manualCommitObserved = false
                    var mutationApplied = false
                    val mutateAuthority = {
                        if (!mutationApplied) {
                            mutationApplied = true
                            when (mutation) {
                                ManualRecoveryAuthorityMutation.REVOKE_OWNER -> {
                                    assertTrue(
                                        harness.activeSessionEngine.executionGuard.revokeRestoredRuntime(exactOwner),
                                    )
                                    harness.coordinator._workoutParameters.value = poisonParameters
                                    harness.coordinator._activeRackBehaviorOverrides.value = poisonRackOverrides
                                }

                                ManualRecoveryAuthorityMutation.CONFIGURATION_INPUT -> {
                                    harness.activeSessionEngine.mutateConfigurationInputs {
                                        harness.coordinator._workoutParameters.value = poisonParameters
                                        harness.coordinator._activeRackBehaviorOverrides.value = poisonRackOverrides
                                    }
                                }
                            }
                        }
                    }
                    when (point) {
                        ManualRecoveryMutationPoint.BEFORE_CLAIM ->
                            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = mutateAuthority

                        ManualRecoveryMutationPoint.AFTER_CLAIM ->
                            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = mutateAuthority
                    }
                    harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = { _, _ ->
                        manualCommitObserved = true
                    }
                    harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                    harness.fakeCompletedSetRepo.softDeleteSession(installed.document.sourceStableSessionId)
                    val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

                    assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                        harness.dwsm.applyRestTransitionAwait(
                            RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                        ),
                    )

                    assertTrue(mutationApplied, "${point.name}/${mutation.name}")
                    assertFalse(manualCommitObserved, "${point.name}/${mutation.name}")
                    assertSupersededCommitIsInert(harness, cleared)
                    assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
                    assertEquals(poisonParameters, harness.coordinator.workoutParameters.value)
                    assertEquals(poisonRackOverrides, harness.coordinator.activeRackBehaviorOverrides.value)
                    assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
                    assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
                    assertNull(
                        harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(),
                        "${point.name}/${mutation.name}",
                    )
                    val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                        ExecutionSeed(
                            sessionId = "fresh-manual-${point.name.lowercase()}-${mutation.name.lowercase()}",
                            profileId = installed.document.profileId,
                            requiresMachine = false,
                            workingRepTarget = 0,
                            isBodyweight = true,
                        ),
                    )
                    assertTrue(fresh.isSuccess, "${point.name}/${mutation.name}")
                } finally {
                    harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = null
                    harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = null
                    harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = null
                    harness.cleanup()
                }
            }
        }
    }

    @Test
    fun `restored manual recovery does not overwrite a superseding workout state`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "manual-recovery-workout-state-superseded",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val poisonParameters = harness.coordinator.workoutParameters.value.copy(reps = 88, weightPerCableKg = 55f)
            val poisonRackOverrides = mapOf("state-supersession" to RackItemBehavior.COUNTERWEIGHT)
            var mutationApplied = false
            var manualCommitObserved = false
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = {
                mutationApplied = true
                harness.coordinator._workoutState.value = WorkoutState.Active
                harness.coordinator._workoutParameters.value = poisonParameters
                harness.coordinator._activeRackBehaviorOverrides.value = poisonRackOverrides
            }
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = { _, _ ->
                manualCommitObserved = true
            }
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.fakeCompletedSetRepo.softDeleteSession(installed.document.sourceStableSessionId)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                ),
            )

            assertTrue(mutationApplied)
            assertFalse(manualCommitObserved)
            assertEquals(
                cleared,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    cleared.profileId,
                    cleared.routineSessionId,
                ),
            )
            assertEquals(cleared, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(poisonParameters, harness.coordinator.workoutParameters.value)
            assertEquals(poisonRackOverrides, harness.coordinator.activeRackBehaviorOverrides.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored manual recovery cannot publish after its accepted start claim is consumed`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "manual-recovery-start-claim-consumed",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            var claimCleared = false
            var manualCommitObserved = false
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                claimCleared = harness.activeSessionEngine.clearAcceptedRetryStartClaimForTest()
            }
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = { _, _ ->
                manualCommitObserved = true
            }
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(accepted.actionIdentity()),
                ),
            )

            assertTrue(claimCleared)
            assertFalse(manualCommitObserved)
            assertEquals(
                cleared,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    cleared.profileId,
                    cleared.routineSessionId,
                ),
            )
            assertEquals(cleared, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertNull(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `profile switch after every restored commit reconciles inert without stale publication`() = runTest {
        enumValues<RestoredPlanCommit>().forEach { commitCase ->
            val harness = enabledHarness()
            try {
                val initialPlan = when (commitCase) {
                    RestoredPlanCommit.DECISION -> InitialPlan.UNRESOLVED
                    RestoredPlanCommit.NORMAL_CLEAR -> InitialPlan.NORMAL
                    RestoredPlanCommit.ACCEPTED_CLEAR -> InitialPlan.ACCEPTED
                }
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "profile-after-${commitCase.name.lowercase()}",
                    initialPlan = initialPlan,
                )
                val plan = assertNotNull(harness.restTransitionPlan.value)
                val committedDocument = when (commitCase) {
                    RestoredPlanCommit.DECISION -> decidedDocument(installed.document, Decision.ACCEPT)

                    RestoredPlanCommit.NORMAL_CLEAR,
                    RestoredPlanCommit.ACCEPTED_CLEAR,
                    -> installed.document.copy(restTransitionPlan = null)
                }
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                var profileSwitched = false
                harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!profileSwitched && committed == committedDocument) {
                        profileSwitched = true
                        harness.fakeUserProfileRepo.setActiveProfileForTest(
                            id = "other-profile-${commitCase.name.lowercase()}",
                        )
                    }
                }

                when (commitCase) {
                    RestoredPlanCommit.DECISION ->
                        harness.dwsm.applyRestTransitionAwait(
                            RestTransitionCommand.Accept(
                                assertIs<RestTransitionPlan.UnresolvedDropOffer>(plan).actionIdentity(),
                                DropPercentage.TWENTY,
                            ),
                        )

                    RestoredPlanCommit.NORMAL_CLEAR ->
                        harness.dwsm.applyRestTransitionAwait(
                            RestTransitionCommand.SkipRest(plan.actionIdentity()),
                        )

                    RestoredPlanCommit.ACCEPTED_CLEAR ->
                        harness.dwsm.applyRestTransitionAwait(
                            RestTransitionCommand.SkipRest(plan.actionIdentity()),
                        )
                }

                assertTrue(profileSwitched, commitCase.name)
                assertSupersededCommitIsInert(harness, committedDocument)
                assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
                assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest, commitCase.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, commitCase.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), commitCase.name)
                val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                    ExecutionSeed(
                        sessionId = "fresh-${commitCase.name.lowercase()}",
                        profileId = "other-profile-${commitCase.name.lowercase()}",
                        requiresMachine = false,
                        workingRepTarget = 0,
                        isBodyweight = true,
                    ),
                )
                assertTrue(fresh.isSuccess, commitCase.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `restored Normal revalidates owner after navigation lookup before publishing`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "normal-post-navigation-revalidation",
                initialPlan = InitialPlan.NORMAL,
            )
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            var profileSwitched = false
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.dwsm.restTransitionNavigationLookupObserverForTest = {
                if (!profileSwitched) {
                    profileSwitched = true
                    harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-after-lookup")
                }
            }

            assertIs<RestTransitionReduction.DispatchNormal>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(normal.actionIdentity()),
                ),
            )

            assertTrue(profileSwitched)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertSupersededCommitIsInert(harness, cleared)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.dwsm.restTransitionNavigationLookupObserverForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Normal revokes its committed-clear owner when navigation lookup throws`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "normal-navigation-lookup-throws",
                initialPlan = InitialPlan.NORMAL,
            )
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.dwsm.restTransitionNavigationLookupObserverForTest = {
                throw IllegalStateException("navigation lookup failed")
            }

            val error = assertFailsWith<IllegalStateException> {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(normal.actionIdentity()),
                )
            }

            assertEquals("navigation lookup failed", error.message)
            assertSupersededCommitIsInert(harness, cleared)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                ExecutionSeed(
                    sessionId = "fresh-after-navigation-failure",
                    profileId = installed.document.profileId,
                    requiresMachine = false,
                    workingRepTarget = 0,
                    isBodyweight = true,
                ),
            )
            assertTrue(fresh.isSuccess)
        } finally {
            harness.dwsm.restTransitionNavigationLookupObserverForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `unreadable committed action verification retires authority and never masks cancellation`() = runTest {
        enumValues<RestoredPlanCommit>().forEach { commitCase ->
            listOf<Throwable>(
                IllegalStateException("verification probe failed"),
                AssertionError("verification probe invariant failed"),
            ).forEachIndexed { probeIndex, probeFailure ->
                val harness = enabledHarness()
                try {
                    val initialPlan = when (commitCase) {
                        RestoredPlanCommit.DECISION -> InitialPlan.UNRESOLVED
                        RestoredPlanCommit.NORMAL_CLEAR -> InitialPlan.NORMAL
                        RestoredPlanCommit.ACCEPTED_CLEAR -> InitialPlan.ACCEPTED
                    }
                    val installed = installRestoredRuntime(
                        harness = harness,
                        routineSessionId = "${commitCase.name.lowercase()}-probe-failure-$probeIndex",
                        initialPlan = initialPlan,
                    )
                    val plan = assertNotNull(harness.restTransitionPlan.value)
                    val expectedDocument = when (commitCase) {
                        RestoredPlanCommit.DECISION -> decidedDocument(installed.document, Decision.ACCEPT)

                        RestoredPlanCommit.NORMAL_CLEAR,
                        RestoredPlanCommit.ACCEPTED_CLEAR,
                        -> installed.document.copy(restTransitionPlan = null)
                    }
                    val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
                    val timerJob = harness.activeSessionEngine.currentRestoredRestTimerJobForTest()
                    val causalMessage = "original-${commitCase.name.lowercase()}-cancellation-$probeIndex"
                    val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
                    val commandsBefore = harness.fakeBleRepo.commandsReceived.size
                    val configurationsBefore = harness.fakeBleRepo.workoutParameters.size
                    var committed = false
                    harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { document ->
                        if (!committed && document == expectedDocument) {
                            committed = true
                            throw CancellationException(causalMessage)
                        }
                    }
                    harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { _, _, _ ->
                        if (committed) throw probeFailure
                    }

                    val error = assertFailsWith<CancellationException> {
                        when (commitCase) {
                            RestoredPlanCommit.DECISION -> harness.dwsm.applyRestTransitionAwait(
                                RestTransitionCommand.Accept(
                                    assertIs<RestTransitionPlan.UnresolvedDropOffer>(plan).actionIdentity(),
                                    DropPercentage.TWENTY,
                                ),
                            )

                            RestoredPlanCommit.NORMAL_CLEAR,
                            RestoredPlanCommit.ACCEPTED_CLEAR,
                            -> harness.dwsm.applyRestTransitionAwait(
                                RestTransitionCommand.SkipRest(plan.actionIdentity()),
                            )
                        }
                    }

                    assertTrue(committed, commitCase.name)
                    assertEquals(causalMessage, error.message, commitCase.name)
                    assertEquals(
                        expectedDocument,
                        harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                            installed.document.profileId,
                            installed.document.routineSessionId,
                        ),
                        commitCase.name,
                    )
                    assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(), commitCase.name)
                    assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), commitCase.name)
                    assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest(), commitCase.name)
                    assertFalse(timerJob?.isActive ?: false, commitCase.name)
                    assertFalse(harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(owner), commitCase.name)
                    assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest, commitCase.name)
                    assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size, commitCase.name)
                    assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size, commitCase.name)
                    assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), commitCase.name)
                    assertTrue(
                        harness.activeSessionEngine.executionGuard.beginExecution(
                            ExecutionSeed(
                                sessionId = "fresh-after-${commitCase.name.lowercase()}-$probeIndex",
                                profileId = installed.document.profileId,
                                requiresMachine = false,
                                workingRepTarget = 0,
                                isBodyweight = true,
                            ),
                        ).isSuccess,
                        commitCase.name,
                    )
                } finally {
                    harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                    harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = null
                    harness.cleanup()
                }
            }
        }
    }

    @Test
    fun `profile and rack drift before a restored action release the exact old owner`() = runTest {
        enumValues<RestoredExternalAuthorityMutation>().forEach { mutation ->
            val harness = enabledHarness()
            try {
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "pre-action-${mutation.name.lowercase()}",
                    initialPlan = InitialPlan.NORMAL,
                )
                val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
                val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

                when (mutation) {
                    RestoredExternalAuthorityMutation.PROFILE ->
                        harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-pre-action")

                    RestoredExternalAuthorityMutation.RACK ->
                        harness.fakeEquipmentRackRepo.saveItems(
                            listOf(RackItem(id = "changed-rack", name = "Changed rack", weightKg = 2f)),
                        )
                }

                val result = assertIs<RestTransitionReduction.NoOp>(
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity())),
                )

                assertEquals(RestTransitionNoOpReason.AUTHORITY_CHANGED, result.reason, mutation.name)
                assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size, mutation.name)
                assertEquals(normal, harness.restTransitionPlan.value, mutation.name)
                assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest, mutation.name)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, mutation.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), mutation.name)
                assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(), mutation.name)
                val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                    ExecutionSeed(
                        sessionId = "fresh-pre-action-${mutation.name.lowercase()}",
                        profileId = harness.activeProfileId(),
                        requiresMachine = false,
                        workingRepTarget = 0,
                        isBodyweight = true,
                    ),
                )
                assertTrue(fresh.isSuccess, mutation.name)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `profile switch after Accepted clear releases the owner without manual publication`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "accepted-profile-after-consume",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-after-consume")
            }

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity())),
            )

            assertSupersededCommitIsInert(harness, cleared)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                ExecutionSeed(
                    sessionId = "fresh-after-consume-profile-switch",
                    profileId = "other-profile-after-consume",
                    requiresMachine = false,
                    workingRepTarget = 0,
                    isBodyweight = true,
                ),
            )
            assertTrue(fresh.isSuccess)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored Normal initial dispatch authority loss releases its committed-clear owner`() = runTest {
        val harness = enabledHarness()
        try {
            val installed = installRestoredRuntime(
                harness = harness,
                routineSessionId = "normal-initial-dispatch-authority-loss",
                initialPlan = InitialPlan.NORMAL,
            )
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val cleared = installed.document.copy(restTransitionPlan = null)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            harness.activeSessionEngine.beforeRestoredNormalDispatchForTest = {
                harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-before-normal-dispatch")
            }

            assertIs<RestTransitionReduction.DispatchNormal>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity())),
            )

            assertSupersededCommitIsInert(harness, cleared)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                ExecutionSeed(
                    sessionId = "fresh-after-initial-dispatch-loss",
                    profileId = "other-profile-before-normal-dispatch",
                    requiresMachine = false,
                    workingRepTarget = 0,
                    isBodyweight = true,
                ),
            )
            assertTrue(fresh.isSuccess)
        } finally {
            harness.activeSessionEngine.beforeRestoredNormalDispatchForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored cancellation remains primary when committed reconciliation also fails`() = runTest {
        enumValues<NavigationPlan>().forEach { planCase ->
            val harness = enabledHarness()
            try {
                val initialPlan = when (planCase) {
                    NavigationPlan.NORMAL -> InitialPlan.NORMAL
                    NavigationPlan.DECLINED -> InitialPlan.ACCEPTED
                }
                val installed = installRestoredRuntime(
                    harness = harness,
                    routineSessionId = "cleanup-failure-${planCase.name.lowercase()}",
                    initialPlan = initialPlan,
                )
                val plan = assertNotNull(harness.restTransitionPlan.value)
                val cleared = installed.document.copy(restTransitionPlan = null)
                val causalMessage = "original-${planCase.name.lowercase()}-cancellation"
                val configurationsBefore = harness.fakeBleRepo.commandsReceived.size
                harness.coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (committed == cleared) throw CancellationException(causalMessage)
                }
                when (planCase) {
                    NavigationPlan.NORMAL ->
                        harness.dwsm.restTransitionNavigationLookupObserverForTest = {
                            throw IllegalStateException("normal reconciliation failed")
                        }

                    NavigationPlan.DECLINED ->
                        harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = { _, _ ->
                            throw IllegalStateException("accepted reconciliation failed")
                        }
                }

                val error = assertFailsWith<CancellationException> {
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }

                assertEquals(causalMessage, error.message, planCase.name)
                assertSupersededCommitIsInert(harness, cleared)
                assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
                assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size, planCase.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), planCase.name)
                assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(), planCase.name)
                val fresh = harness.activeSessionEngine.executionGuard.beginExecution(
                    ExecutionSeed(
                        sessionId = "fresh-cleanup-failure-${planCase.name.lowercase()}",
                        profileId = installed.document.profileId,
                        requiresMachine = false,
                        workingRepTarget = 0,
                        isBodyweight = true,
                    ),
                )
                assertTrue(fresh.isSuccess, planCase.name)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.dwsm.restTransitionNavigationLookupObserverForTest = null
                harness.activeSessionEngine.beforeManualRetryRecoveryCommitForTest = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `stale restored cleanup cannot clear a newer owner or its retained permission`() = runTest {
        val harness = enabledHarness()
        val staleClearEntered = CompletableDeferred<Unit>()
        val releaseStaleClear = CompletableDeferred<Unit>()
        val newerStopEntered = CompletableDeferred<Unit>()
        val releaseNewerStop = CompletableDeferred<Result<Unit>>()
        val newerDurabilityEntered = CompletableDeferred<Unit>()
        val releaseNewerDurability = CompletableDeferred<Unit>()
        try {
            val stale = installRestoredRuntime(
                harness = harness,
                routineSessionId = "stale-owner-clear-a",
                initialPlan = InitialPlan.ACCEPTED,
            )
            val stalePlan = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val staleOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            var intercepted = false
            harness.activeSessionEngine.beforeRestoredOwnerCompareAndClearForTest = { expectedOwner ->
                if (!intercepted && expectedOwner == staleOwner) {
                    intercepted = true
                    staleClearEntered.complete(Unit)
                    runBlocking { releaseStaleClear.await() }
                }
            }
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                assertTrue(harness.activeSessionEngine.executionGuard.revokeRestoredRuntime(staleOwner))
            }

            val staleAction = async(Dispatchers.Default) {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(stalePlan.actionIdentity()))
            }
            withContext(Dispatchers.Default) {
                withTimeout(2_000) { staleClearEntered.await() }
            }

            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.fakeBleRepo.stopWorkoutBlock = {
                newerStopEntered.complete(Unit)
                releaseNewerStop.await()
            }
            val newer = installRestoredRuntime(
                harness = harness,
                routineSessionId = "newer-owner-clear-b",
                initialPlan = InitialPlan.ACCEPTED,
                awaitTeardownReady = false,
            )
            withTimeout(2_000) { newerStopEntered.await() }
            val newerPlan = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val newerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertTrue(newerOwner != staleOwner)
            val configurationsBefore = harness.fakeBleRepo.commandsReceived.size

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(newerPlan.actionIdentity())),
            )
            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredAcceptedRetryPermissionOwnerForTest())
            assertEquals(newer.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(newerPlan, harness.restTransitionPlan.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            assertIs<ConnectionState.Connected>(harness.fakeBleRepo.connectionState.value)
            releaseNewerStop.complete(Result.success(Unit))
            runCurrent()
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)

            releaseStaleClear.complete(Unit)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                withContext(Dispatchers.Default) {
                    withTimeout(2_000) { staleAction.await() }
                },
            )

            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredAcceptedRetryPermissionOwnerForTest())
            assertEquals(newer.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(newerPlan, harness.restTransitionPlan.value)
            assertEquals(configurationsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            var blockedNewerDurability = false
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {
                if (!blockedNewerDurability) {
                    blockedNewerDurability = true
                    newerDurabilityEntered.complete(Unit)
                    releaseNewerDurability.await()
                }
            }
            val newerAction = async {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(newerPlan.actionIdentity()))
            }
            runCurrent()
            withTimeout(2_000) { newerDurabilityEntered.await() }
            releaseNewerDurability.complete(Unit)
            assertIs<RestTransitionReduction.PendingAcceptedRetry>(newerAction.await())
            runCurrent()

            assertEquals(configurationsBefore + 1, harness.fakeBleRepo.commandsReceived.size)
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.restTransitionPlan.value)
        } finally {
            releaseStaleClear.complete(Unit)
            releaseNewerStop.complete(Result.success(Unit))
            releaseNewerDurability.complete(Unit)
            harness.activeSessionEngine.beforeRestoredOwnerCompareAndClearForTest = null
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.fakeCompletedSetRepo.beforeAttemptDurabilityRead = {}
            harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }
            harness.cleanup()
        }
    }

    private fun assertDecisionCommitWasNotLost(
        harness: DWSMTestHarness,
        expectedDocument: ActiveWorkoutRuntimeDocument,
    ) {
        assertTrue(
            harness.fakeActiveWorkoutRuntimeRepository.replacements.any { replacement ->
                replacement.profileId == expectedDocument.profileId &&
                    replacement.routineSessionId == expectedDocument.routineSessionId &&
                    replacement.document == expectedDocument
            },
            "the exact restored decision must reach the repository before the injected race",
        )
    }

    private fun assertExactDecisionReconciled(
        harness: DWSMTestHarness,
        expectedDocument: ActiveWorkoutRuntimeDocument,
    ) {
        assertEquals(
            expectedDocument,
            harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                expectedDocument.profileId,
                expectedDocument.routineSessionId,
            ),
        )
        assertEquals(expectedDocument, harness.activeSessionEngine.activeRuntimeDocumentForTest())
        assertEquals(expectedDocument.restTransitionPlan, harness.restTransitionPlan.value)
        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
    }

    private fun assertSupersededCommitIsInert(
        harness: DWSMTestHarness,
        expectedDocument: ActiveWorkoutRuntimeDocument,
    ) {
        assertEquals(
            expectedDocument,
            harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                expectedDocument.profileId,
                expectedDocument.routineSessionId,
            ),
        )
        assertEquals(expectedDocument, harness.activeSessionEngine.activeRuntimeDocumentForTest())
        assertEquals(expectedDocument.restTransitionPlan, harness.restTransitionPlan.value)
        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
    }

    private fun assertDurablyClearedAndManual(
        harness: DWSMTestHarness,
        original: ActiveWorkoutRuntimeDocument,
        expectedExerciseIndex: Int = 0,
        expectedSetIndex: Int = 0,
    ) {
        val durable = assertNotNull(
            harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                original.profileId,
                original.routineSessionId,
            ),
        )
        assertNull(durable.restTransitionPlan)
        assertEquals(durable, harness.activeSessionEngine.activeRuntimeDocumentForTest())
        assertNull(harness.restTransitionPlan.value)
        val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        assertEquals(expectedExerciseIndex, setReady.exerciseIndex)
        assertEquals(expectedSetIndex, setReady.setIndex)
        assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
    }

    private fun DWSMTestHarness.activeProfileId(): String = requireNotNull(fakeUserProfileRepo.activeProfile.value).id

    private fun Decision.command(plan: RestTransitionPlan.UnresolvedDropOffer): RestTransitionCommand = when (this) {
        Decision.ACCEPT -> RestTransitionCommand.Accept(plan.actionIdentity(), DropPercentage.TWENTY)
        Decision.DECLINE -> RestTransitionCommand.Decline(plan.actionIdentity())
    }

    private fun NavigationPlan.initialPlan(): InitialPlan = when (this) {
        NavigationPlan.NORMAL -> InitialPlan.NORMAL
        NavigationPlan.DECLINED -> InitialPlan.DECLINED
    }

    private fun mutateAcceptedIdentity(
        identity: RestActionIdentity,
        mutation: AcceptedIdentityMutation,
    ): RestActionIdentity = when (mutation) {
        AcceptedIdentityMutation.SOURCE_EXECUTION -> identity.copy(
            sourceExecutionId = "wrong-source-execution",
        )

        AcceptedIdentityMutation.SELECTED_PERCENTAGE -> identity.copy(
            selectedPercentage = DropPercentage.TEN,
        )

        AcceptedIdentityMutation.TRANSITION_ID -> identity.copy(transitionId = "wrong-transition")

        AcceptedIdentityMutation.ROUTINE_SESSION -> identity.copy(
            logicalSetKey = identity.logicalSetKey.copy(routineSessionId = "wrong-routine-session"),
        )

        AcceptedIdentityMutation.ROUTINE_EXERCISE -> identity.copy(
            logicalSetKey = identity.logicalSetKey.copy(routineExerciseId = "wrong-routine-exercise"),
        )

        AcceptedIdentityMutation.SET_INDEX -> identity.copy(
            logicalSetKey = identity.logicalSetKey.copy(setIndex = identity.logicalSetKey.setIndex + 1),
        )

        AcceptedIdentityMutation.SET_KIND -> identity.copy(
            logicalSetKey = identity.logicalSetKey.copy(setKind = SetType.AMRAP),
        )

        AcceptedIdentityMutation.PLANNED_SET_ID -> identity.copy(plannedSetId = "wrong-planned-set")

        AcceptedIdentityMutation.OFFER_ID -> identity.copy(offerId = "wrong-offer")
    }

    private fun InstalledRuntime.restoredGate(accepted: RestTransitionPlan.AcceptedRetry) = RetryPersistenceGate.Restored(
        sourceStableSessionId = document.sourceStableSessionId,
        actionIdentity = accepted.actionIdentity(),
        sourceContext = document.sourceAuthority.toRestoredRetrySourceContext(),
    )

    private fun decidedDocument(
        document: ActiveWorkoutRuntimeDocument,
        decision: Decision,
    ): ActiveWorkoutRuntimeDocument {
        val unresolved = requireNotNull(document.restTransitionPlan) as RestTransitionPlan.UnresolvedDropOffer
        return when (decision) {
            Decision.ACCEPT -> {
                val accepted = acceptedPlan(unresolved)
                document.copy(
                    restTransitionPlan = accepted,
                    attemptStates = listOf(
                        PlannedSetAttemptState(
                            logicalSetKey = document.logicalSetKey,
                            nextAttemptNumber = 3,
                            acceptedDropCount = 1,
                        ),
                    ),
                    exerciseLoadOverlays = listOf(
                        ExerciseLoadOverlay(
                            routineExerciseId = document.routineExerciseId,
                            multiplier = 0.8f,
                        ),
                    ),
                )
            }

            Decision.DECLINE -> document.copy(
                restTransitionPlan = RestTransitionPlan.Declined(
                    transitionId = unresolved.transitionId,
                    sourceExecutionId = unresolved.sourceExecutionId,
                    logicalSetKey = unresolved.logicalSetKey,
                    offerId = unresolved.offerId,
                    normalAdvance = unresolved.normalAdvance,
                ),
            )
        }
    }

    private fun acceptedPlan(unresolved: RestTransitionPlan.UnresolvedDropOffer) = RestTransitionPlan.AcceptedRetry(
        transitionId = unresolved.transitionId,
        sourceExecutionId = unresolved.sourceExecutionId,
        logicalSetKey = unresolved.logicalSetKey,
        offerId = unresolved.offerId,
        sourceCoordinates = unresolved.normalAdvance.sourceCoordinates,
        plannedSetId = unresolved.plannedSetId,
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 20f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 2,
    )

    private fun TestScope.enabledHarness(): DWSMTestHarness = DWSMTestHarness(
        testScope = this,
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(
            DropSetFeatureGate { true },
            DropSetCandidateResolver(),
        ),
        dropSetConfigurationProvider = {
            DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 5f)
        },
    )

    private suspend fun TestScope.installRestoredRuntime(
        harness: DWSMTestHarness,
        routineSessionId: String,
        initialPlan: InitialPlan,
        setsPerExercise: Int = 2,
        awaitTeardownReady: Boolean = true,
    ): InstalledRuntime {
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = setsPerExercise,
            weightKg = 25f,
            repsPerSet = 10,
        )
        val profileId = harness.activeProfileId()
        val exercise = routine.exercises.single()
        val logicalKey = LogicalSetKey(routineSessionId, exercise.id, 0, SetType.STANDARD)
        val sourceStableSessionId = "source-$routineSessionId"
        val unresolvedDocument = unresolvedDocument(
            profileId = profileId,
            routine = routine,
            routineSessionId = routineSessionId,
            sourceStableSessionId = sourceStableSessionId,
            sourceExercise = exercise,
            logicalKey = logicalKey,
        )
        val unresolved = unresolvedDocument.restTransitionPlan as RestTransitionPlan.UnresolvedDropOffer
        val document = when (initialPlan) {
            InitialPlan.UNRESOLVED -> unresolvedDocument

            InitialPlan.NORMAL -> unresolvedDocument.copy(restTransitionPlan = unresolved.normalAdvance)

            InitialPlan.DECLINED -> unresolvedDocument.copy(
                restTransitionPlan = RestTransitionPlan.Declined(
                    transitionId = unresolved.transitionId,
                    sourceExecutionId = unresolved.sourceExecutionId,
                    logicalSetKey = unresolved.logicalSetKey,
                    offerId = unresolved.offerId,
                    normalAdvance = unresolved.normalAdvance,
                ),
            )

            InitialPlan.ACCEPTED -> decidedDocument(unresolvedDocument, Decision.ACCEPT)
        }

        harness.fakeCompletedSetRepo.setSessionRoutine(sourceStableSessionId, routineSessionId)
        harness.fakeCompletedSetRepo.saveCompletedSet(
            CompletedSet(
                id = "durable-$routineSessionId",
                sessionId = sourceStableSessionId,
                plannedSetId = null,
                setNumber = 0,
                setType = SetType.STANDARD,
                actualReps = 6,
                actualWeightKg = 25f,
                loggedRpe = null,
                isPr = false,
                completedAt = 1L,
                setEndReason = SetEndReason.STALL_FAILURE,
                routineExerciseId = exercise.id,
                attemptNumber = 1,
            ),
        )
        harness.fakeActiveWorkoutRuntimeRepository.replace(profileId, routineSessionId, document)
        val handle = assertIs<RoutineResumeHandle.Persisted>(
            assertIs<RoutineResumeDiscovery.Candidate>(
                harness.dwsm.discoverRoutineResume(routine, RoutineLaunchOrigin.DAILY_ROUTINES),
            ).handle,
        )
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
            harness.dwsm.resumeRoutine(handle),
            routineSessionId,
        )
        runCurrent()
        if (awaitTeardownReady) {
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
        } else {
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
        }
        assertEquals(document.restTransitionPlan, harness.restTransitionPlan.value)
        assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        return InstalledRuntime(document, handle)
    }

    private fun unresolvedDocument(
        profileId: String,
        routine: Routine,
        routineSessionId: String,
        sourceStableSessionId: String,
        sourceExercise: RoutineExercise,
        logicalKey: LogicalSetKey,
    ): ActiveWorkoutRuntimeDocument {
        val normal = RestTransitionPlan.NormalAdvance(
            transitionId = "transition-$routineSessionId",
            sourceExecutionId = "42",
            logicalSetKey = logicalKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(0, 0),
            plannedSetId = null,
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routine.id,
            routineSessionId = routineSessionId,
            routineExerciseId = sourceExercise.id,
            sourceExecutionId = "42",
            sourceStableSessionId = sourceStableSessionId,
            sourceAttemptNumber = 1,
            logicalSetKey = logicalKey,
            plannedSetId = null,
            sourceExerciseIndex = 0,
            sourceSetIndex = 0,
            attemptStates = listOf(
                PlannedSetAttemptState(
                    logicalSetKey = logicalKey,
                    nextAttemptNumber = 2,
                    acceptedDropCount = 0,
                ),
            ),
            restTransitionPlan = RestTransitionPlan.UnresolvedDropOffer(
                transitionId = normal.transitionId,
                sourceExecutionId = normal.sourceExecutionId,
                logicalSetKey = logicalKey,
                offerId = "offer-$routineSessionId",
                plannedSetId = null,
                candidates = listOf(
                    DropSetCandidate(
                        percentage = DropPercentage.TEN,
                        resolvedWeightPerCableKg = 22.5f,
                        resultingExerciseMultiplier = 0.9f,
                    ),
                    DropSetCandidate(
                        percentage = DropPercentage.TWENTY,
                        resolvedWeightPerCableKg = 20f,
                        resultingExerciseMultiplier = 0.8f,
                    ),
                    DropSetCandidate(
                        percentage = DropPercentage.THIRTY,
                        resolvedWeightPerCableKg = 17.5f,
                        resultingExerciseMultiplier = 0.7f,
                    ),
                ),
                normalAdvance = normal,
            ),
            restDeadlineEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS + 30_000L,
            originalRestDurationSeconds = 60,
            sourceAuthority = sourceAuthority(
                profileId = profileId,
                routine = routine,
                routineSessionId = routineSessionId,
                sourceStableSessionId = sourceStableSessionId,
                sourceExercise = sourceExercise,
                logicalKey = logicalKey,
            ),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = 42L,
                sourceStableSessionId = sourceStableSessionId,
                profileId = profileId,
                requiresMachine = true,
            ),
        )
    }

    private fun sourceAuthority(
        profileId: String,
        routine: Routine,
        routineSessionId: String,
        sourceStableSessionId: String,
        sourceExercise: RoutineExercise,
        logicalKey: LogicalSetKey,
    ): RestoredRetrySourceAuthoritySnapshot {
        val command = WorkoutParameters(
            programMode = sourceExercise.programMode,
            reps = 10,
            weightPerCableKg = 25f,
            progressionRegressionKg = sourceExercise.progressionKg,
            stopAtTop = sourceExercise.stopAtTop,
            warmupReps = 3,
            selectedExerciseId = sourceExercise.exercise.id,
            isAMRAP = false,
            stallDetectionEnabled = sourceExercise.stallDetectionEnabled,
            repCountTiming = sourceExercise.repCountTiming,
            echoLevel = sourceExercise.getEchoLevelForSet(0),
            eccentricLoad = sourceExercise.eccentricLoad,
        )
        return RestoredRetrySourceAuthoritySnapshot(
            sourceStableSessionId = sourceStableSessionId,
            sourceExecutionId = "42",
            profileId = profileId,
            routineIdentity = RoutineExecutionIdentity(
                profileId = profileId,
                routineId = routine.id,
                routineSessionId = routineSessionId,
                routineExerciseId = sourceExercise.id,
                logicalSetKey = logicalKey,
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = SetType.STANDARD.name,
            programModeName = "OLD_SCHOOL",
            programmedBaseWeightPerCableKg = 25f,
            configuredStartWeightPerCableKg = 25f,
            progressionKg = sourceExercise.progressionKg,
            actualReps = 6,
            targetReps = 10,
            isWarmup = false,
            isEcho = sourceExercise.programMode == ProgramMode.Echo,
            isJustLift = false,
            isBodyweight = false,
            isTimed = false,
            isAmrap = false,
            isCableExercise = true,
            physicalCableCount = sourceExercise.exercise.preferredCableCount,
            commandTemplate = RestoredWorkoutCommandTemplateSnapshot.from(command),
        )
    }
}

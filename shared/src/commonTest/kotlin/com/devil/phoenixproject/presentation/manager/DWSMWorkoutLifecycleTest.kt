package com.devil.phoenixproject.presentation.manager

import app.cash.turbine.test
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import com.devil.phoenixproject.domain.model.BadgeTier
import com.devil.phoenixproject.domain.model.BodyweightVariantOption
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ExerciseCableIntent
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.PlannedSet
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.activeDWSM
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.Constants
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Characterization tests for DefaultWorkoutSessionManager workout lifecycle.
 *
 * These tests lock in EXISTING behavior. If behavior is surprising,
 * we document it with a "Characterization:" comment rather than changing it.
 *
 * Each test calls harness.cleanup() before exiting to cancel DWSM's long-running
 * init collectors and prevent UncompletedCoroutinesError.
 */
class DWSMWorkoutLifecycleTest {

    private class RetryReadFailureRepository : FakeCompletedSetRepository() {
        var failPlannedSetReads: Boolean = false

        override suspend fun getPlannedSets(routineExerciseId: String) = if (failPlannedSetReads) {
            error("planned-set read failed")
        } else {
            super.getPlannedSets(routineExerciseId)
        }
    }

    private data class DurableAcceptedRetryFixture(
        val sourceLease: ExecutionLease,
        val accepted: RestTransitionPlan.AcceptedRetry,
        val sourceContext: RestoredRetrySourceContext,
        val commandCount: Int,
    )

    private data class DurableDropOfferFixture(
        val sourceLease: ExecutionLease,
        val unresolved: RestTransitionPlan.UnresolvedDropOffer,
        val commandCount: Int,
    )

    private data class HydratedAcceptedRetryFixture(
        val harness: DWSMTestHarness,
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
        val accepted: RestTransitionPlan.AcceptedRetry,
        val sourceContext: RestoredRetrySourceContext,
        val sourceStableSessionId: String,
        val completedSets: FakeCompletedSetRepository,
        val commandCount: Int,
    )

    private data class ExhaustedDropOverlayFixture(
        val normalAdvance: RestTransitionPlan.NormalAdvance,
        val routine: Routine,
        val routineExerciseId: String,
    )

    private fun executionSeedForRetryTest(sessionId: String) = ExecutionSeed(
        sessionId = sessionId,
        profileId = "default",
        requiresMachine = true,
        workingRepTarget = 8,
    )

    private fun readFloatLe(packet: ByteArray, offset: Int): Float {
        val bits = (packet[offset].toInt() and 0xFF) or
            ((packet[offset + 1].toInt() and 0xFF) shl 8) or
            ((packet[offset + 2].toInt() and 0xFF) shl 16) or
            ((packet[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    @Test
    fun `successor claim requires the exact source and cannot replace a newer execution`() {
        val guard = WorkoutExecutionGuard()
        val source = guard.beginExecution(executionSeedForRetryTest("source")).getOrThrow()

        val wrongSource = source.copy(sessionId = "wrong-source")
        assertTrue(
            guard.beginSuccessorExecution(
                expectedSource = wrongSource,
                seed = executionSeedForRetryTest("rejected"),
            ).isFailure,
        )
        assertEquals(source, guard.currentLease)

        val successor = guard.beginSuccessorExecution(
            expectedSource = source,
            seed = executionSeedForRetryTest("successor"),
        ).getOrThrow()
        assertEquals(successor, guard.currentLease)
        assertTrue(
            guard.beginSuccessorExecution(
                expectedSource = source,
                seed = executionSeedForRetryTest("duplicate"),
            ).isFailure,
        )
        assertEquals(successor, guard.currentLease)
    }

    @Test
    fun `accepted retry waits for durable persistence and completed teardown then starts once`() = runTest {
        val persistenceRelease = CompletableDeferred<Unit>()
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        val executionIds = mutableListOf<Long>()
        val harness = DWSMTestHarness(
            this,
            afterExecutionBegin = { _, executionId -> executionIds += executionId },
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = {
                DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f)
            },
        )
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }
            harness.fakeCompletedSetRepo.beforeSaveCompletedSet = { persistenceRelease.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val sourceCommandCount = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                sourceLease.sessionId,
                assertNotNull(harness.coordinator.currentRoutineSessionId),
            )

            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            runCurrent()

            assertEquals(sourceCommandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(listOf(sourceLease.executionId), executionIds)

            persistenceRelease.complete(Unit)
            runCurrent()
            assertEquals(PersistenceClaimStatus.PERSISTED, harness.activeSessionEngine.executionGuard.persistenceClaimStatus(sourceLease.sessionId))
            assertEquals(sourceCommandCount, harness.fakeBleRepo.commandsReceived.size)

            teardownRelease.complete(Result.success(Unit))
            runCurrent()

            val retryLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(retryLease.executionId > sourceLease.executionId)
            assertEquals(sourceCommandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(2, executionIds.size)
            assertEquals(
                1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val stopCompletedIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            val retryCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            assertTrue(stopCompletedIndex in 0 until retryCommandIndex)

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            runCurrent()
            assertEquals(sourceCommandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(2, executionIds.size)
        } finally {
            if (!persistenceRelease.isCompleted) persistenceRelease.complete(Unit)
            if (!teardownRelease.isCompleted) teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retries consume attempts and absolute occurrence overlays through the two drop budget`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f).let { source ->
                source.copy(
                    exercises = source.exercises.map { exercise ->
                        exercise.copy(
                            progressionKg = 1.5f,
                            setWeightsPerCableKg = listOf(25f, 50f),
                        )
                    },
                )
            }
            harness.fakeWorkoutRepo.addRoutine(routine)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            suspend fun completePositiveStall(lease: ExecutionLease) {
                harness.coordinator._repCount.value = RepCount(
                    warmupReps = 0,
                    workingReps = 3,
                    totalReps = 3,
                    isWarmupComplete = true,
                )
                harness.fakeCompletedSetRepo.setSessionRoutine(
                    lease.sessionId,
                    assertNotNull(harness.coordinator.currentRoutineSessionId),
                )
                harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
                advanceTimeBy(1_000)
                runCurrent()
            }

            val attempt1Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(attempt1Lease)
            val attempt1Document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val logicalSetKey = attempt1Document.logicalSetKey
            assertEquals(1, attempt1Document.sourceAttemptNumber)
            assertEquals(
                2 to 0,
                attempt1Document.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertTrue(attempt1Document.exerciseLoadOverlays.isEmpty())

            val offer1 = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val candidate1 = offer1.candidates.single { it.percentage == DropPercentage.TWENTY }
            assertEquals(20f, candidate1.resolvedWeightPerCableKg)
            assertEquals(0.8f, candidate1.resultingExerciseMultiplier)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(offer1.actionIdentity(), DropPercentage.TWENTY),
            )
            val accepted1 = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val accepted1Document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(2, accepted1.nextAttemptNumber)
            assertEquals(
                3 to 1,
                accepted1Document.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                0.8f,
                accepted1Document.exerciseLoadOverlays.single {
                    it.routineExerciseId == logicalSetKey.routineExerciseId
                }.multiplier,
            )

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted1.actionIdentity()))
            runCurrent()
            val attempt2Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(attempt2Lease.executionId > attempt1Lease.executionId)
            assertEquals(1.5f, harness.coordinator.workoutParameters.value.progressionRegressionKg)
            assertEquals(
                20f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            completePositiveStall(attempt2Lease)
            val attempt2Completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(attempt2Lease))
            assertEquals(2, attempt2Completion.attemptNumber)
            assertEquals(1, attempt2Completion.acceptedDropCount)
            val attempt2Document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(2, attempt2Document.sourceAttemptNumber)
            assertEquals(
                3 to 1,
                attempt2Document.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )

            val offer2 = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val candidate2 = offer2.candidates.single { it.percentage == DropPercentage.TWENTY }
            assertEquals(16f, candidate2.resolvedWeightPerCableKg)
            assertEquals(0.64f, candidate2.resultingExerciseMultiplier)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(offer2.actionIdentity(), DropPercentage.TWENTY),
            )
            val accepted2 = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            val accepted2Document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(3, accepted2.nextAttemptNumber)
            assertEquals(
                4 to 2,
                accepted2Document.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                0.64f,
                accepted2Document.exerciseLoadOverlays.single {
                    it.routineExerciseId == logicalSetKey.routineExerciseId
                }.multiplier,
            )

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted2.actionIdentity()))
            runCurrent()
            val attempt3Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(attempt3Lease.executionId > attempt2Lease.executionId)
            assertEquals(1.5f, harness.coordinator.workoutParameters.value.progressionRegressionKg)
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            completePositiveStall(attempt3Lease)
            val attempt3Completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(attempt3Lease))
            assertEquals(3, attempt3Completion.attemptNumber)
            assertEquals(2, attempt3Completion.acceptedDropCount)
            assertEquals(
                4 to 2,
                assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
                    .attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertFalse(harness.restTransitionPlan.value is RestTransitionPlan.UnresolvedDropOffer)

            val exhaustedPlan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            assertEquals(
                1,
                harness.dwsm.restTransitionNavigationLookupsForTest,
                "accepted same-set retries must not resolve routine navigation",
            )
            harness.setActiveSummaryCountdownSeconds(0)
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(exhaustedPlan.actionIdentity()),
            )
            runCurrent()

            assertEquals(1, harness.coordinator.currentSetIndex.value)
            val nextSetReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(32f, nextSetReady.adjustedWeight)

            harness.dwsm.setReadyPrev()
            val manualRepeatReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(0, manualRepeatReady.exerciseIndex)
            assertEquals(0, manualRepeatReady.setIndex)
            assertEquals(16f, manualRepeatReady.adjustedWeight)

            val commandsBeforeManualRepeat = harness.fakeBleRepo.commandsReceived.size
            harness.dwsm.startSetFromReady()
            runCurrent()
            val attempt4Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(attempt4Lease.executionId > attempt3Lease.executionId)
            assertEquals(1.5f, harness.coordinator.workoutParameters.value.progressionRegressionKg)
            assertEquals(commandsBeforeManualRepeat + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            harness.setActiveSummaryCountdownSeconds(-1)
            runCurrent()
            completePositiveStall(attempt4Lease)
            val attempt4Completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(attempt4Lease))
            assertEquals(4, attempt4Completion.attemptNumber)
            assertEquals(2, attempt4Completion.acceptedDropCount)
            val attempt4Document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                5 to 2,
                attempt4Document.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertFalse(harness.restTransitionPlan.value is RestTransitionPlan.UnresolvedDropOffer)
            val manualRepeatPlan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            assertEquals(2, harness.dwsm.restTransitionNavigationLookupsForTest)

            val commandsBeforeLaterSet = harness.fakeBleRepo.commandsReceived.size
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(manualRepeatPlan.actionIdentity()),
            )
            runCurrent()

            assertEquals(1, harness.coordinator.currentSetIndex.value)
            assertEquals(commandsBeforeLaterSet + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                32f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            val laterSetLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(laterSetLease)
            val laterSetCompletion = assertNotNull(harness.activeSessionEngine.claimedCompletion(laterSetLease))
            assertEquals(1, laterSetCompletion.attemptNumber)
            assertEquals(0, laterSetCompletion.acceptedDropCount)
            val laterDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(1, laterDocument.logicalSetKey.setIndex)
            assertEquals(
                2 to 0,
                laterDocument.attemptStates.single { it.logicalSetKey == laterDocument.logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                5 to 2,
                laterDocument.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                0.64f,
                laterDocument.exerciseLoadOverlays.single {
                    it.routineExerciseId == logicalSetKey.routineExerciseId
                }.multiplier,
            )
            val laterOffer1 = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertEquals(
                25.5f,
                laterOffer1.candidates.single { it.percentage == DropPercentage.TWENTY }.resolvedWeightPerCableKg,
            )
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(laterOffer1.actionIdentity(), DropPercentage.TWENTY),
            )
            val laterAccepted1 = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertEquals(2, laterAccepted1.nextAttemptNumber)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(laterAccepted1.actionIdentity()))
            runCurrent()
            assertEquals(
                25.5f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            val laterAttempt2Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(laterAttempt2Lease)
            val laterAttempt2Completion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(laterAttempt2Lease),
            )
            assertEquals(2, laterAttempt2Completion.attemptNumber)
            assertEquals(1, laterAttempt2Completion.acceptedDropCount)
            val laterOffer2 = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertEquals(
                20.5f,
                laterOffer2.candidates.single { it.percentage == DropPercentage.TWENTY }.resolvedWeightPerCableKg,
            )
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(laterOffer2.actionIdentity(), DropPercentage.TWENTY),
            )
            val laterAccepted2 = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertEquals(3, laterAccepted2.nextAttemptNumber)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(laterAccepted2.actionIdentity()))
            runCurrent()
            assertEquals(
                20.5f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            val laterAttempt3Lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(laterAttempt3Lease)
            val laterAttempt3Completion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(laterAttempt3Lease),
            )
            assertEquals(3, laterAttempt3Completion.attemptNumber)
            assertEquals(2, laterAttempt3Completion.acceptedDropCount)
            assertFalse(harness.restTransitionPlan.value is RestTransitionPlan.UnresolvedDropOffer)
            val finalDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                4 to 2,
                finalDocument.attemptStates.single { it.logicalSetKey == laterDocument.logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                5 to 2,
                finalDocument.attemptStates.single { it.logicalSetKey == logicalSetKey }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(routine, harness.fakeWorkoutRepo.getRoutineById(routine.id))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `superset occurrence with the same library exercise keeps an independent drop budget and load`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val base = WorkoutStateFixtures.createSupersetRoutine()
            val first = base.exercises[0].copy(setReps = listOf(10), weightPerCableKg = 25f)
            val second = base.exercises[1].copy(
                exercise = first.exercise,
                setReps = listOf(10),
                weightPerCableKg = 25f,
            )
            val routine = base.copy(exercises = listOf(first, second))
            val firstAccepted = prepareDurableAcceptedRetry(
                harness = harness,
                routineOverride = routine,
                acceptedPercentage = DropPercentage.TWENTY,
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(firstAccepted.accepted.actionIdentity()),
            )
            runCurrent()
            val attempt2 = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(harness, attempt2)

            val offer2 = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(offer2.actionIdentity(), DropPercentage.TWENTY),
            )
            val secondAccepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(secondAccepted.actionIdentity()),
            )
            runCurrent()
            val attempt3 = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(harness, attempt3)

            val exhausted = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val firstDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                0.64f,
                firstDocument.exerciseLoadOverlays.single { it.routineExerciseId == first.id }.multiplier,
            )
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(exhausted.actionIdentity()))
            runCurrent()

            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(
                25f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            val secondOccurrenceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            completePositiveStall(harness, secondOccurrenceLease)
            val secondCompletion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(secondOccurrenceLease),
            )
            assertEquals(1, secondCompletion.attemptNumber)
            assertEquals(0, secondCompletion.acceptedDropCount)
            val secondDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                2 to 0,
                secondDocument.attemptStates.single { it.logicalSetKey.routineExerciseId == second.id }
                    .let { it.nextAttemptNumber to it.acceptedDropCount },
            )
            assertEquals(
                0.64f,
                secondDocument.exerciseLoadOverlays.single { it.routineExerciseId == first.id }.multiplier,
            )
            assertTrue(secondDocument.exerciseLoadOverlays.none { it.routineExerciseId == second.id })
            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `plan owned rest preview and edits preserve the absolute occurrence overlay`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareExhaustedDropOverlay(harness)

            assertEquals(16f, harness.coordinator.workoutParameters.value.weightPerCableKg)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(
                    reps = harness.coordinator.workoutParameters.value.reps + 1,
                ),
            )
            harness.setActiveSummaryCountdownSeconds(0)
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.normalAdvance.actionIdentity()),
            )
            runCurrent()

            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(16f, ready.adjustedWeight)
            harness.dwsm.startSetFromReady()
            runCurrent()
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            assertEquals(fixture.routine, harness.fakeWorkoutRepo.getRoutineById(fixture.routine.id))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `exercise jump back reapplies only the selected occurrence overlay`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val base = createTestRoutine(exerciseCount = 2, setsPerExercise = 1, weightKg = 25f)
            val routine = base.copy(
                exercises = listOf(
                    base.exercises[0],
                    base.exercises[1].copy(exercise = base.exercises[0].exercise),
                ),
            )
            val fixture = prepareExhaustedDropOverlay(harness, routine)
            harness.setActiveSummaryCountdownSeconds(0)
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.normalAdvance.actionIdentity()),
            )
            runCurrent()

            val partnerReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(1, partnerReady.exerciseIndex)
            assertEquals(25f, partnerReady.adjustedWeight)
            assertEquals(0.64f, harness.activeSessionEngine.occurrenceLoadMultiplier(fixture.routineExerciseId))

            val commandsBeforeJump = harness.fakeBleRepo.commandsReceived.size
            harness.dwsm.jumpToExercise(0)
            advanceUntilIdle()

            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(commandsBeforeJump, harness.fakeBleRepo.commandsReceived.size)

            harness.dwsm.startSetFromReady()
            advanceUntilIdle()
            assertEquals(commandsBeforeJump + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            assertEquals(fixture.routine, harness.fakeWorkoutRepo.getRoutineById(fixture.routine.id))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `interrupted retry reconnect preserves the absolute occurrence overlay`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val first = prepareDurableAcceptedRetry(
                harness = harness,
                acceptedPercentage = DropPercentage.TWENTY,
            )
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(first.accepted.actionIdentity()))
            runCurrent()
            completePositiveStall(harness, harness.activeSessionEngine.currentExecutionLeaseForTest())
            val secondOffer = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(secondOffer.actionIdentity(), DropPercentage.TWENTY),
            )
            val second = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(second.actionIdentity()))
            runCurrent()
            assertEquals(16f, harness.coordinator.workoutParameters.value.weightPerCableKg)

            harness.coordinator._repCount.value = RepCount(
                warmupReps = Constants.DEFAULT_WARMUP_REPS,
                workingReps = 1,
                totalReps = Constants.DEFAULT_WARMUP_REPS + 1,
                isWarmupComplete = true,
            )
            val commandsBeforeReconnect = harness.fakeBleRepo.commandsReceived.size
            harness.activeSessionEngine.captureInterruptedWorkoutForRecovery()
            harness.dwsm.reconnectInterruptedWorkout()
            advanceUntilIdle()

            assertEquals(commandsBeforeReconnect + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `variable warmup returns to the overlaid working load after jump back`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            prepareOverlaidWarmupJump(
                harness,
                listOf(WarmupSet(reps = 2, percentOfWorking = 50)),
            )
            assertEquals(
                8f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            assertEquals(-1, harness.coordinator.currentWarmupSetIndex.value)
            assertEquals(16f, harness.coordinator.workoutParameters.value.weightPerCableKg)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `variable warmup successor retains the overlaid working load between warmups`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            prepareOverlaidWarmupJump(
                harness,
                listOf(
                    WarmupSet(reps = 2, percentOfWorking = 50),
                    WarmupSet(reps = 2, percentOfWorking = 75),
                ),
            )
            assertEquals(
                8f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            val commandsBeforeWarmupTransitions = harness.fakeBleRepo.commandsReceived.size
            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            assertEquals(1, harness.coordinator.currentWarmupSetIndex.value)
            assertEquals(commandsBeforeWarmupTransitions + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                12f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )

            harness.activeSessionEngine.handleSetCompletion(
                harness.activeSessionEngine.currentExecutionLeaseForTest(),
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            assertEquals(-1, harness.coordinator.currentWarmupSetIndex.value)
            assertEquals(commandsBeforeWarmupTransitions + 2, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                16f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `occurrence overlay requires full profile routine and session identity`() = runTest {
        listOf("profile", "routine", "session").forEach { mismatch ->
            val harness = enabledDropSetHarness(this)
            try {
                val fixture = prepareExhaustedDropOverlay(harness)
                if (mismatch == "session") {
                    harness.coordinator.currentRoutineSessionId = "stale-session"
                } else {
                    harness.activeSessionEngine.mutateActiveRuntimeDocumentForTest { document ->
                        when (mismatch) {
                            "profile" -> document.copy(
                                profileId = "stale-profile",
                                sourceAuthority = document.sourceAuthority.copy(
                                    profileId = "stale-profile",
                                    routineIdentity = document.sourceAuthority.routineIdentity.copy(
                                        profileId = "stale-profile",
                                    ),
                                ),
                                teardownSeed = document.teardownSeed.copy(profileId = "stale-profile"),
                            )

                            else -> document.copy(
                                routineId = "stale-routine",
                                sourceAuthority = document.sourceAuthority.copy(
                                    routineIdentity = document.sourceAuthority.routineIdentity.copy(
                                        routineId = "stale-routine",
                                    ),
                                ),
                            )
                        }
                    }
                }

                assertEquals(
                    1f,
                    harness.activeSessionEngine.occurrenceLoadMultiplier(fixture.routineExerciseId),
                    mismatch,
                )
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `accepting a retry rejects duplicate attempt or overlay authority instead of repairing it`() = runTest {
        listOf("attempt state", "occurrence overlay").forEach { duplicateKind ->
            val harness = enabledDropSetHarness(this)
            try {
                val fixture = prepareDurableDropOffer(harness)
                harness.activeSessionEngine.mutateActiveRuntimeDocumentForTest { document ->
                    when (duplicateKind) {
                        "attempt state" -> {
                            val unrelatedKey = document.logicalSetKey.copy(
                                setIndex = document.logicalSetKey.setIndex + 1,
                            )
                            val duplicate = PlannedSetAttemptState(unrelatedKey)
                            document.copy(attemptStates = document.attemptStates + duplicate + duplicate)
                        }

                        else -> {
                            val duplicate = ExerciseLoadOverlay("unrelated-occurrence", 1f)
                            document.copy(
                                exerciseLoadOverlays = document.exerciseLoadOverlays + duplicate + duplicate,
                            )
                        }
                    }
                }

                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        fixture.unresolved.actionIdentity(),
                        fixture.unresolved.candidates.first().percentage,
                    ),
                )
                runCurrent()

                assertEquals(
                    fixture.unresolved,
                    harness.restTransitionPlan.value,
                    "$duplicateKind must not be canonicalized into accepted retry authority",
                )
                assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `retry dispatch failure after durable save preserves the persisted claim`() = runTest {
        val persistenceRelease = CompletableDeferred<Unit>()
        val harness = enabledDropSetHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeCompletedSetRepo.beforeSaveCompletedSet = { persistenceRelease.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val sourceCommandCount = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                sourceLease.sessionId,
                assertNotNull(harness.coordinator.currentRoutineSessionId),
            )

            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            runCurrent()
            assertEquals(
                PersistenceClaimStatus.IN_PROGRESS,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus(sourceLease.sessionId),
            )

            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                assertFalse(
                    harness.activeSessionEngine.hasRetainedWorkoutExitSnapshotForTest(sourceLease.sessionId),
                )
                harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
                throw IllegalStateException("retry dispatch failed after persistence")
            }
            persistenceRelease.complete(Unit)
            runCurrent()

            assertEquals(
                PersistenceClaimStatus.PERSISTED,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus(sourceLease.sessionId),
            )
            assertEquals(sourceCommandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            if (!persistenceRelease.isCompleted) persistenceRelease.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `failed execution claim preserves an interrupted workout start override for retry`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val initialCommandCount = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 1,
                workingReps = 0,
                totalReps = 1,
                isWarmupComplete = false,
            )
            harness.activeSessionEngine.captureInterruptedWorkoutForRecovery()
            harness.activeSessionEngine.beforeExecutionBeginForTest = {
                assertTrue(harness.activeSessionEngine.executionGuard.beginTeardown(sourceLease))
            }

            harness.dwsm.reconnectInterruptedWorkout()
            runCurrent()

            assertEquals(sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)
            assertEquals(initialCommandCount, harness.fakeBleRepo.commandsReceived.size)

            harness.activeSessionEngine.beforeExecutionBeginForTest = null
            assertTrue(harness.activeSessionEngine.executionGuard.markTeardownReady(sourceLease))
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertEquals(initialCommandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                Constants.DEFAULT_WARMUP_REPS - 1,
                harness.coordinator._workoutParameters.value.warmupReps,
            )
        } finally {
            harness.activeSessionEngine.beforeExecutionBeginForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `live retry never falls back to durable only authority after its claim is pruned`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.executionGuard.prunePersistedClaims(retainNewest = 0)

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(
                PersistenceClaimStatus.UNCLAIMED,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus(fixture.sourceLease.sessionId),
            )
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry uses the exact durable attempt with an empty live claim map`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            assertTrue(harness.activeSessionEngine.executionGuard.persistenceClaimsSnapshot().isEmpty())

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
                ),
            )
            runCurrent()

            assertEquals(restored.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(
                PersistenceClaimStatus.UNCLAIMED,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus(restored.sourceStableSessionId),
            )
            assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry ignores unrelated in-memory persistence claims`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            assertIs<PersistenceClaimResult.Claimed>(
                harness.activeSessionEngine.executionGuard.claimPersistence(
                    sessionId = "unrelated-persistence",
                    path = TerminalPath.MANUAL_STOP,
                ),
            )

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
                ),
            )
            runCurrent()

            assertEquals(restored.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                PersistenceClaimStatus.IN_PROGRESS,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus("unrelated-persistence"),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry on a fresh engine uses explicit captured command authority`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            harness.coordinator._workoutParameters.value = restored.sourceContext.commandTemplate.copy(
                programMode = ProgramMode.Pump,
                reps = 99,
                weightPerCableKg = 2f,
                progressionRegressionKg = 9f,
            )

            assertIs<RestTransitionReduction.PendingAcceptedRetry>(
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
                ),
            )
            runCurrent()

            assertEquals(restored.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                restored.accepted.resolvedWeightPerCableKg,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            assertEquals(
                restored.sourceContext.commandTemplate.progressionRegressionKg,
                harness.coordinator.workoutParameters.value.progressionRegressionKg,
            )
            assertTrue(harness.activeSessionEngine.executionGuard.persistenceClaimsSnapshot().isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry rejects a captured programmed base that does not match the routine`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            val loadedRoutine = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.coordinator._loadedRoutine.value = loadedRoutine.copy(
                exercises = loadedRoutine.exercises.mapIndexed { index, occurrence ->
                    if (index == restored.accepted.sourceCoordinates.exerciseIndex) {
                        occurrence.copy(
                            weightPerCableKg = restored.sourceContext.programmedBaseWeightPerCableKg + 1f,
                        )
                    } else {
                        occurrence
                    }
                },
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(restored.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry rejects loaded routine drift outside drop-set eligibility`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            val routine = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.coordinator._loadedRoutine.value = routine.copy(
                exercises = routine.exercises.mapIndexed { index, occurrence ->
                    if (index == restored.accepted.sourceCoordinates.exerciseIndex) {
                        occurrence.copy(programMode = ProgramMode.Pump)
                    } else {
                        occurrence
                    }
                },
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(restored.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertNull(harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry with a missing durable attempt fails closed to manual set ready`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        try {
            restored.completedSets.deleteCompletedSetsForSession(restored.sourceStableSessionId)

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(restored.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(0, setReady.exerciseIndex)
            assertEquals(0, setReady.setIndex)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `retry preview mismatch consumes the accepted plan and enters manual set ready`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val routine = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.coordinator._loadedRoutine.value = routine.copy(
                exercises = routine.exercises.mapIndexed { index, exercise ->
                    if (index == 0) exercise.copy(weightPerCableKg = 30f) else exercise
                },
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(0, setReady.exerciseIndex)
            assertEquals(0, setReady.setIndex)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects a self-consistent candidate that was not resolved from the failed load`() = runTest {
        val mutations = listOf(
            "increased load" to Triple(DropPercentage.TWENTY, 30f, 1.2f),
            "wrong percentage" to Triple(DropPercentage.THIRTY, 20f, 0.8f),
        )
        mutations.forEach { (label, candidate) ->
            val harness = enabledDropSetHarness(this)
            try {
                val fixture = prepareDurableAcceptedRetry(harness, acceptedPercentage = DropPercentage.TWENTY)
                harness.activeSessionEngine.mutateActiveRuntimeDocumentForTest { document ->
                    val tampered = fixture.accepted.copy(
                        percentage = candidate.first,
                        resolvedWeightPerCableKg = candidate.second,
                        resultingExerciseMultiplier = candidate.third,
                    )
                    document.copy(
                        restTransitionPlan = tampered,
                        exerciseLoadOverlays = document.exerciseLoadOverlays.map { overlay ->
                            if (overlay.routineExerciseId == document.routineExerciseId) {
                                overlay.copy(multiplier = candidate.third)
                            } else {
                                overlay
                            }
                        },
                    )
                }
                val tampered = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)

                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(tampered.actionIdentity()))
                runCurrent()

                assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size, label)
                assertEquals(null, harness.restTransitionPlan.value, label)
                val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value, label)
                assertEquals(candidate.second, setReady.adjustedWeight, 0.0001f, label)
                assertEquals(
                    candidate.second,
                    harness.coordinator.workoutParameters.value.weightPerCableKg,
                    0.0001f,
                    label,
                )
                assertEquals(
                    candidate.third,
                    harness.activeSessionEngine.occurrenceLoadMultiplier(tampered.logicalSetKey.routineExerciseId),
                    0.0001f,
                    label,
                )
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `accepted retry rechecks enabled configuration and minimum at command boundary`() = runTest {
        val configurations = listOf(
            "disabled" to DropSetConfiguration(enabled = false, minimumWeightPerCableKg = 1f),
            "raised minimum" to DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 21f),
        )
        configurations.forEach { (label, changedConfiguration) ->
            var configuration = DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f)
            val harness = enabledDropSetHarness(this) { configuration }
            try {
                val fixture = prepareDurableAcceptedRetry(harness, acceptedPercentage = DropPercentage.TWENTY)
                harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                    configuration = changedConfiguration
                }

                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
                )
                runCurrent()

                assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size, label)
                assertEquals(null, harness.restTransitionPlan.value, label)
                assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value, label)
            } finally {
                harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
                harness.cleanup()
            }
        }
    }

    @Test
    fun `accepted retry rejects command input mutation after final authority validation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = {
                harness.dwsm.updateWorkoutParameters(
                    harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 35f),
                )
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            advanceUntilIdle()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects an external active profile change before config claim`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest("external-profile", name = "External Profile")
            harness.fakeUserProfileRepo.emitReadyForTest("default")
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = {
                harness.fakeUserProfileRepo.emitReadyForTest("external-profile")
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            advanceUntilIdle()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry external rack publication during config tears down before recovery`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            var publishRackDuringConfig = true
            harness.fakeBleRepo.afterWorkoutCommand = {
                if (publishRackDuringConfig) {
                    publishRackDuringConfig = false
                    harness.fakeEquipmentRackRepo.saveItems(
                        listOf(
                            RackItem(
                                id = "externally-published-rack-item",
                                name = "Externally published rack item",
                                category = RackItemCategory.OTHER,
                                weightKg = 5f,
                            ),
                        ),
                    )
                }
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            advanceUntilIdle()

            assertFalse(publishRackDuringConfig)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects a routine load intent before asynchronous resolution`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val replacement = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 35f)
                .copy(id = "retry-replacement-routine", name = "Retry Replacement Routine")
            replacement.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = {
                harness.dwsm.loadRoutine(replacement)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            advanceUntilIdle()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(replacement.id, harness.coordinator.loadedRoutine.value?.id)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects atomic loaded routine and rack override publication`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val overrides = mapOf("rack-item" to RackItemBehavior.COUNTERWEIGHT)
            val updatedRoutine = assertNotNull(harness.coordinator.loadedRoutine.value).let { routine ->
                routine.copy(
                    exercises = routine.exercises.mapIndexed { index, exercise ->
                        if (index == harness.coordinator.currentExerciseIndex.value) {
                            exercise.copy(rackBehaviorOverrides = overrides)
                        } else {
                            exercise
                        }
                    },
                )
            }
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = {
                harness.dwsm.updateLoadedRoutineRackBehaviorOverrides(updatedRoutine, overrides)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            advanceUntilIdle()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(updatedRoutine, harness.coordinator.loadedRoutine.value)
            assertEquals(overrides, harness.coordinator.activeRackBehaviorOverrides.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects every mutable routine field that would change its captured command`() = runTest {
        val mutations = listOf<Pair<String, (RoutineExercise) -> RoutineExercise>>(
            "program mode" to { it.copy(programMode = ProgramMode.Pump) },
            "reps" to { exercise ->
                exercise.copy(setReps = exercise.setReps.mapIndexed { index, reps -> if (index == 0) 9 else reps })
            },
            "echo level" to { it.copy(setEchoLevels = listOf(EchoLevel.HARDEST)) },
            "eccentric load" to { it.copy(eccentricLoad = EccentricLoad.LOAD_120) },
            "progression" to { it.copy(progressionKg = it.progressionKg + 1f) },
            "stop at top" to { it.copy(stopAtTop = !it.stopAtTop) },
            "stall detection" to { it.copy(stallDetectionEnabled = !it.stallDetectionEnabled) },
            "duration mode" to { it.copy(duration = 30) },
            "bodyweight mode" to {
                it.copy(exercise = it.exercise.copy(isBodyweightOverride = true))
            },
            "physical cable count" to {
                val changedIntent = if (it.exercise.cableIntent == ExerciseCableIntent.SINGLE) {
                    ExerciseCableIntent.DUAL
                } else {
                    ExerciseCableIntent.SINGLE
                }
                it.copy(exercise = it.exercise.copy(cableIntent = changedIntent))
            },
            "rep timing" to {
                it.copy(
                    repCountTiming = if (it.repCountTiming == RepCountTiming.TOP) {
                        RepCountTiming.BOTTOM
                    } else {
                        RepCountTiming.TOP
                    },
                )
            },
        )

        mutations.forEach { (label, mutate) ->
            val harness = enabledDropSetHarness(this)
            try {
                val fixture = prepareDurableAcceptedRetry(harness)
                val routine = assertNotNull(harness.coordinator.loadedRoutine.value)
                harness.coordinator._loadedRoutine.value = routine.copy(
                    exercises = routine.exercises.mapIndexed { index, exercise ->
                        if (index == fixture.accepted.sourceCoordinates.exerciseIndex) mutate(exercise) else exercise
                    },
                )

                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
                )
                runCurrent()

                assertEquals(
                    fixture.commandCount,
                    harness.fakeBleRepo.commandsReceived.size,
                    "$label mutation must not reach BLE",
                )
                assertEquals(null, harness.restTransitionPlan.value, "$label mutation must fail closed")
                assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value, label)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun `accepted AMRAP retry rejects a changed routine fallback rep target`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f).let { source ->
                source.copy(
                    exercises = source.exercises.map { exercise ->
                        exercise.copy(setReps = listOf(8, null), isAMRAP = true)
                    },
                )
            }
            val fixture = prepareDurableAcceptedRetry(
                harness = harness,
                routineOverride = routine,
                setIndex = 1,
            )
            val loaded = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.coordinator._loadedRoutine.value = loaded.copy(
                exercises = loaded.exercises.map { exercise ->
                    exercise.copy(setReps = listOf(9, null), isAMRAP = true)
                },
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry compares rack adjusted command weight like for like with its preview`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeEquipmentRackRepo.saveItems(
                listOf(
                    RackItem(
                        id = "assist",
                        name = "assist",
                        category = RackItemCategory.OTHER,
                        weightKg = 10f,
                        behavior = RackItemBehavior.COUNTERWEIGHT,
                    ),
                ),
            )
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.updateActiveRackSelection(listOf("assist"))
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val sourceCommandCount = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                sourceLease.sessionId,
                assertNotNull(harness.coordinator.currentRoutineSessionId),
            )
            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(1_000)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val candidate = unresolved.candidates.first()
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), candidate.percentage),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            runCurrent()

            assertEquals(sourceCommandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                candidate.resolvedWeightPerCableKg - 10f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects a rack selection changed immediately before config`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeEquipmentRackRepo.saveItems(
                listOf(
                    RackItem(
                        id = "late-assist",
                        name = "late-assist",
                        category = RackItemCategory.OTHER,
                        weightKg = 5f,
                        behavior = RackItemBehavior.COUNTERWEIGHT,
                    ),
                ),
            )
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.dwsm.updateActiveRackSelection(listOf("late-assist"))
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects rack adjustment that reaches the candidate only through clamping`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeEquipmentRackRepo.saveItems(
                listOf(
                    RackItem(
                        id = "oversized-assist",
                        name = "oversized-assist",
                        category = RackItemCategory.OTHER,
                        weightKg = 50f,
                        behavior = RackItemBehavior.COUNTERWEIGHT,
                    ),
                ),
            )
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.updateActiveRackSelection(listOf("oversized-assist"))
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val sourceCommandCount = harness.fakeBleRepo.commandsReceived.size
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                sourceLease.sessionId,
                assertNotNull(harness.coordinator.currentRoutineSessionId),
            )
            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(1_000)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(accepted.actionIdentity()))
            runCurrent()

            assertEquals(sourceCommandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry without a planned row rejects a changed semantic set kind`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val routine = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.coordinator._loadedRoutine.value = routine.copy(
                exercises = routine.exercises.mapIndexed { exerciseIndex, exercise ->
                    if (exerciseIndex == fixture.accepted.sourceCoordinates.exerciseIndex) {
                        exercise.copy(
                            setReps = exercise.setReps.mapIndexed { setIndex, reps ->
                                if (setIndex == fixture.accepted.sourceCoordinates.setIndex) null else reps
                            },
                        )
                    } else {
                        exercise
                    }
                },
            )

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rejects a mismatched live routine occurrence without BLE`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 2, setsPerExercise = 1, weightKg = 25f)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.coordinator._currentExerciseIndex.value = 1
            harness.coordinator._currentSetIndex.value = 0

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(fixture.accepted.sourceCoordinates.exerciseIndex, setReady.exerciseIndex)
            assertEquals(fixture.accepted.sourceCoordinates.setIndex, setReady.setIndex)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rereads non-null planned-set identity immediately before config`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            assertEquals(planned.id, fixture.accepted.plannedSetId)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(planned.copy(id = "planned-replacement"))
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rereads same-id planned-set targets immediately before config`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-target-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.updatePlannedSet(
                    planned.copy(targetReps = 9, targetWeightKg = 30f),
                )
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `manual retry recovery cannot overwrite a successor begun at publication`() = runTest {
        val harness = enabledDropSetHarness(this)
        var newerLease: ExecutionLease? = null
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(planned.copy(id = "planned-recovery-replacement"))
            }
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = {
                newerLease = harness.activeSessionEngine.executionGuard.beginExecution(
                    executionSeedForRetryTest("recovery-successor"),
                ).getOrThrow()
                harness.coordinator._workoutState.value = WorkoutState.Active
                harness.coordinator._routineFlowState.value = RoutineFlowState.Overview(routine)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(newerLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.Overview>(harness.coordinator.routineFlowState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `reset before retry recovery claim prevents stale SetReady publication`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-preclaim-reset-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-preclaim-reset-replacement"),
                )
            }
            val resetParameters = harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 99f)
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = {
                harness.dwsm.resetForNewWorkout()
                harness.coordinator._workoutParameters.value = resetParameters
                harness.coordinator._routineFlowState.value = RoutineFlowState.Overview(routine)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(resetParameters, harness.coordinator.workoutParameters.value)
            assertIs<RoutineFlowState.Overview>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `end before retry recovery claim never publishes stale SetReady`() = runTest {
        val harness = enabledDropSetHarness(this)
        val observedFlowStates = mutableListOf<RoutineFlowState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.routineFlowState.collect { observedFlowStates += it }
        }
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-preclaim-end-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-preclaim-end-replacement"),
                )
            }
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = {
                observedFlowStates.clear()
                harness.dwsm.stopWorkout(exitingWorkout = true)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(observedFlowStates.any { it is RoutineFlowState.SetReady })
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `cleanup before retry recovery claim never publishes stale SetReady`() = runTest {
        val harness = enabledDropSetHarness(this)
        val observedFlowStates = mutableListOf<RoutineFlowState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.routineFlowState.collect { observedFlowStates += it }
        }
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-preclaim-cleanup-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-preclaim-cleanup-replacement"),
                )
            }
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = {
                observedFlowStates.clear()
                harness.dwsm.cleanup()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(observedFlowStates.any { it is RoutineFlowState.SetReady })
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.beforeManualRetryRecoveryPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `reset after retry recovery claim prevents stale SetReady publication`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-reset-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-reset-replacement"),
                )
            }
            val resetParameters = harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 99f)
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = {
                harness.dwsm.resetForNewWorkout()
                harness.coordinator._workoutParameters.value = resetParameters
                harness.coordinator._routineFlowState.value = RoutineFlowState.Overview(routine)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(resetParameters, harness.coordinator.workoutParameters.value)
            assertIs<RoutineFlowState.Overview>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `end after retry recovery claim never publishes stale SetReady`() = runTest {
        val harness = enabledDropSetHarness(this)
        val observedFlowStates = mutableListOf<RoutineFlowState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.routineFlowState.collect { observedFlowStates += it }
        }
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-end-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-end-replacement"),
                )
            }
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = {
                observedFlowStates.clear()
                harness.dwsm.stopWorkout(exitingWorkout = true)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(observedFlowStates.any { it is RoutineFlowState.SetReady })
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `cleanup after retry recovery claim never publishes stale SetReady`() = runTest {
        val harness = enabledDropSetHarness(this)
        val observedFlowStates = mutableListOf<RoutineFlowState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.routineFlowState.collect { observedFlowStates += it }
        }
        try {
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val occurrence = routine.exercises.single()
            val planned = PlannedSet.standard(
                id = "planned-recovery-claimed-cleanup-source",
                routineExerciseId = occurrence.id,
                setNumber = 0,
                targetReps = 10,
                targetWeightKg = 25f,
            )
            harness.fakeCompletedSetRepo.savePlannedSet(planned)
            val fixture = prepareDurableAcceptedRetry(harness, routineOverride = routine)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.fakeCompletedSetRepo.deletePlannedSet(planned.id)
                harness.fakeCompletedSetRepo.savePlannedSet(
                    planned.copy(id = "planned-recovery-claimed-cleanup-replacement"),
                )
            }
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = {
                observedFlowStates.clear()
                harness.dwsm.cleanup()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(observedFlowStates.any { it is RoutineFlowState.SetReady })
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.activeSessionEngine.afterManualRetryRecoveryClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `restored retry cannot overwrite a newer execution after accepted plan consumption`() = runTest {
        val restored = prepareHydratedAcceptedRetry()
        val harness = restored.harness
        var newerLease: ExecutionLease? = null
        try {
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                harness.activeSessionEngine.executionGuard.revokeRestoredRuntime(owner)
                newerLease = harness.activeSessionEngine.executionGuard.beginExecution(
                    executionSeedForRetryTest("newer-execution"),
                ).getOrThrow()
                harness.coordinator._workoutState.value = WorkoutState.Active
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(restored.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(newerLease, harness.activeSessionEngine.executionGuard.currentLease)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(restored.commandCount, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `live accepted retry missing its source remains available for restored validation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.beforeAcceptedRetryGateCaptureForTest = {
                assertTrue(
                    harness.activeSessionEngine.executionGuard.invalidate(
                        fixture.sourceLease,
                        ExecutionInvalidationReason.CLEANUP,
                    ),
                )
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryGateCaptureForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `live accepted retry never adopts a newer execution as recovery authority`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            assertTrue(
                harness.activeSessionEngine.executionGuard.invalidate(
                    fixture.sourceLease,
                    ExecutionInvalidationReason.CLEANUP,
                ),
            )
            val newerLease = harness.activeSessionEngine.executionGuard.beginExecution(
                executionSeedForRetryTest("newer-live-execution"),
            ).getOrThrow()
            harness.coordinator._workoutState.value = WorkoutState.Active

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
            assertEquals(newerLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `autoplay expiry grants exact accepted retry permission and starts once`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertTrue(resting.restSecondsRemaining > 0)
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)

            advanceTimeBy((resting.restSecondsRemaining + 1L) * 1_000L)
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertTrue(harness.activeSessionEngine.currentExecutionLeaseForTest().executionId > fixture.sourceLease.executionId)
            assertEquals(null, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `live retry losing durable authority after successor claim fails closed before BLE`() = runTest {
        lateinit var harness: DWSMTestHarness
        var sourceSessionId: String? = null
        harness = enabledDropSetHarness(
            testScope = this,
            afterExecutionBegin = { outgoingExecutionId, _ ->
                if (outgoingExecutionId != null) {
                    harness.activeSessionEngine.executionGuard.prunePersistedClaims(retainNewest = 0)
                    sourceSessionId?.let(harness.fakeCompletedSetRepo::softDeleteSession)
                }
            },
        )
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            sourceSessionId = fixture.sourceLease.sessionId

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                PersistenceClaimStatus.UNCLAIMED,
                harness.activeSessionEngine.executionGuard.persistenceClaimStatus(fixture.sourceLease.sessionId),
            )
            assertEquals(null, harness.restTransitionPlan.value)
            val setReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(0, setReady.exerciseIndex)
            assertEquals(0, setReady.setIndex)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rechecks the durable source immediately before plan consumption`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.beforeAcceptedRetryPlanConsumeForTest = {
                harness.fakeCompletedSetRepo.softDeleteSession(fixture.sourceLease.sessionId)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryPlanConsumeForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rechecks the durable source immediately before BLE config`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                harness.activeSessionEngine.executionGuard.prunePersistedClaims(retainNewest = 0)
                harness.fakeCompletedSetRepo.softDeleteSession(fixture.sourceLease.sessionId)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry rechecks the durable source after plan consumption before claiming a successor`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                harness.activeSessionEngine.executionGuard.prunePersistedClaims(retainNewest = 0)
                harness.fakeCompletedSetRepo.softDeleteSession(fixture.sourceLease.sessionId)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `generic start cannot enter between accepted plan clear and retry successor claim`() = runTest {
        val harness = enabledDropSetHarness(this)
        var leaseAfterGenericStart: ExecutionLease? = null
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                harness.dwsm.startWorkout(skipCountdown = true)
                leaseAfterGenericStart = harness.activeSessionEngine.currentExecutionLeaseOrNull()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.sourceLease, leaseAfterGenericStart)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertTrue(
                harness.activeSessionEngine.currentExecutionLeaseForTest().executionId >
                    fixture.sourceLease.executionId,
            )
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `generic start cannot supersede retry successor during config preparation`() = runTest {
        val harness = enabledDropSetHarness(this)
        var retryLeaseBeforeGenericStart: ExecutionLease? = null
        var leaseAfterGenericStart: ExecutionLease? = null
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = {
                retryLeaseBeforeGenericStart = harness.activeSessionEngine.currentExecutionLeaseOrNull()
                harness.dwsm.startWorkout(skipCountdown = true)
                leaseAfterGenericStart = harness.activeSessionEngine.currentExecutionLeaseOrNull()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(retryLeaseBeforeGenericStart, leaseAfterGenericStart)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeAcceptedRetryConfigAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation after durable accepted plan clear reconciles to manual set ready`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (committed.restTransitionPlan == null) {
                    harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                    throw CancellationException("cancel after accepted plan clear commit")
                }
            }

            assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
                )
            }
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    fixture.sourceLease.profileId,
                    fixture.accepted.logicalSetKey.routineSessionId,
                )?.restTransitionPlan,
            )
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            val recoveryReady = assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertEquals(fixture.accepted.resolvedWeightPerCableKg, recoveryReady.adjustedWeight)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)

            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.dwsm.startSetFromReady()
            runCurrent()

            val recoveredLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(recoveredLease.executionId > fixture.sourceLease.executionId)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            completePositiveStall(harness, recoveredLease)
            val recoveredCompletion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(recoveredLease),
            )
            assertEquals(fixture.accepted.nextAttemptNumber, recoveredCompletion.attemptNumber)
            assertEquals(1, recoveredCompletion.acceptedDropCount)
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation after accepted plan consumption enters manual set ready before propagating`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                throw CancellationException("cancel after accepted plan consumption")
            }

            assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
                )
            }
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `retry cancellation after config commit tears down before manual recovery`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                throw CancellationException("cancel after retry config commit")
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val retryCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            val recoveryStopIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            assertTrue(retryCommandIndex in 0 until recoveryStopIndex)
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `retry command input mutation during config tears down before manual recovery`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                harness.dwsm.adjustWeight(newWeightKg = 35f, sendToMachine = false)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val retryCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            val recoveryStopIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            assertTrue(retryCommandIndex in 0 until recoveryStopIndex)
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `reset after retry config commit tears down and releases the start claim`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.activeSessionEngine.afterAcceptedRetryConfigSentForTest = {
                harness.dwsm.resetForNewWorkout()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val retryCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            val recoveryStopIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            assertTrue(retryCommandIndex in 0 until recoveryStopIndex)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value)

            harness.activeSessionEngine.afterAcceptedRetryConfigSentForTest = null
            harness.dwsm.startSetFromReady()
            runCurrent()

            assertEquals(fixture.commandCount + 2, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryConfigSentForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `teardown beginning after final retry authority prevents BLE config`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = {
                val retryLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
                assertTrue(
                    harness.activeSessionEngine.requestTeardownForTransition(
                        retryLease,
                        TeardownReason.EXERCISE_JUMP,
                    ) {},
                )
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryFinalAuthorityForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `teardown requested while retry config is claimed runs before activation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            var transitionAccepted = false
            var transitionCompleted = false
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                val retryLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
                transitionAccepted = harness.activeSessionEngine.requestTeardownForTransition(
                    retryLease,
                    TeardownReason.EXERCISE_JUMP,
                ) {
                    transitionCompleted = true
                    harness.coordinator._workoutState.value = WorkoutState.Idle
                }
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertTrue(transitionAccepted)
            assertTrue(transitionCompleted)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val retryCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            val transitionStopIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            assertTrue(retryCommandIndex in 0 until transitionStopIndex)
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `ordinary stop requested while config is claimed completes after config`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val completedStopsBeforeStart = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                harness.dwsm.stopWorkout(exitingWorkout = false)
            }

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeStart + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `ordinary end requested while config is claimed still resets the machine`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val completedStopsBeforeStart = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                harness.dwsm.stopWorkout(exitingWorkout = true)
            }

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeStart + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `ordinary reset requested while config is claimed still resets the machine`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val completedStopsBeforeStart = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                harness.dwsm.resetForNewWorkout()
            }

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeStart + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation after accepted retry activation does not run failed start recovery`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = {
                throw CancellationException("cancel after retry activation")
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val activeLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertNotNull(activeLease.activationCutoverTimestampMs)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `exception after accepted retry activation does not fail the active execution`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = {
                error("failure after retry activation")
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val activeLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertNotNull(activeLease.activationCutoverTimestampMs)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `ordinary stop after accepted retry activation keeps its summary continuation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            runCurrent()

            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reset after accepted retry activation stops the configured machine`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val completedStopsBeforeRetry = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = {
                harness.dwsm.resetForNewWorkout()
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeRetry + 1,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryActivatedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry recovery cannot resurrect an invalidated source after plan consumption`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            val routine = assertNotNull(harness.coordinator.loadedRoutine.value)
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = {
                assertTrue(
                    harness.activeSessionEngine.executionGuard.invalidate(
                        fixture.sourceLease,
                        ExecutionInvalidationReason.CLEANUP,
                    ),
                )
                harness.coordinator._workoutState.value = WorkoutState.Idle
                harness.coordinator._routineFlowState.value = RoutineFlowState.Overview(routine)
            }

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertIs<RoutineFlowState.Overview>(harness.coordinator.routineFlowState.value)
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            harness.activeSessionEngine.afterAcceptedRetryPlanConsumedForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry repository read failure keeps the exact plan visible and retryable`() = runTest {
        val completedSets = RetryReadFailureRepository()
        val harness = enabledDropSetHarness(
            testScope = this,
            completedSetRepositoryOverride = completedSets,
        )
        try {
            val fixture = prepareDurableAcceptedRetry(harness, completedSets)
            completedSets.failPlannedSetReads = true

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(fixture.sourceLease, harness.activeSessionEngine.currentExecutionLeaseForTest())

            completedSets.failPlannedSetReads = false
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale accepted permission after rest revision waits for a fresh exact skip`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val fixture = prepareDurableAcceptedRetry(harness)
            assertTrue(harness.activeSessionEngine.executionGuard.beginTeardown(fixture.sourceLease))

            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()
            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)

            harness.dwsm.toggleRestPause()
            runCurrent()
            assertTrue(assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest()).isRestPaused)
            assertTrue(harness.activeSessionEngine.executionGuard.markTeardownReady(fixture.sourceLease))

            assertFalse(
                harness.activeSessionEngine.tryStartAcceptedRetry(
                    RetryPersistenceGate.Live(fixture.sourceLease.sessionId),
                ),
            )
            runCurrent()
            assertEquals(fixture.accepted, harness.restTransitionPlan.value)
            assertEquals(fixture.commandCount, harness.fakeBleRepo.commandsReceived.size)

            harness.dwsm.toggleRestPause()
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(fixture.accepted.actionIdentity()),
            )
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(fixture.commandCount + 1, harness.fakeBleRepo.commandsReceived.size)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun prepareDurableAcceptedRetry(
        harness: DWSMTestHarness,
        completedSets: FakeCompletedSetRepository = harness.fakeCompletedSetRepo,
        routineOverride: Routine? = null,
        exerciseIndex: Int = 0,
        setIndex: Int = 0,
        acceptedPercentage: DropPercentage? = null,
    ): DurableAcceptedRetryFixture {
        val offerFixture = prepareDurableDropOffer(
            harness = harness,
            completedSets = completedSets,
            routineOverride = routineOverride,
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
        )
        harness.dwsm.applyRestTransitionAwait(
            RestTransitionCommand.Accept(
                offerFixture.unresolved.actionIdentity(),
                acceptedPercentage ?: offerFixture.unresolved.candidates.first().percentage,
            ),
        )
        return DurableAcceptedRetryFixture(
            sourceLease = offerFixture.sourceLease,
            accepted = assertIs(harness.restTransitionPlan.value),
            sourceContext = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(offerFixture.sourceLease),
            ).toRestoredRetrySourceContext(),
            commandCount = offerFixture.commandCount,
        )
    }

    private suspend fun TestScope.prepareHydratedAcceptedRetry(
        routineOverride: Routine? = null,
    ): HydratedAcceptedRetryFixture {
        val completedSets = FakeCompletedSetRepository()
        val sourceHarness = enabledDropSetHarness(
            testScope = this,
            completedSetRepositoryOverride = completedSets,
        )
        var targetHarness: DWSMTestHarness? = null
        try {
            val source = prepareDurableAcceptedRetry(
                harness = sourceHarness,
                completedSets = completedSets,
                routineOverride = routineOverride,
            )
            val document = assertNotNull(sourceHarness.activeSessionEngine.activeRuntimeDocumentForTest())
            val routine = assertNotNull(sourceHarness.coordinator.loadedRoutine.value)

            targetHarness = enabledDropSetHarness(
                testScope = this,
                completedSetRepositoryOverride = completedSets,
            )
            targetHarness.fakeBleRepo.simulateConnect("Vee_Test")
            routine.exercises.forEach { targetHarness.fakeExerciseRepo.addExercise(it.exercise) }
            targetHarness.fakeActiveWorkoutRuntimeRepository.replace(
                profileId = document.profileId,
                routineSessionId = document.routineSessionId,
                document = document,
            )
            val handle = assertIs<RoutineResumeHandle.Persisted>(
                assertIs<RoutineResumeDiscovery.Candidate>(
                    targetHarness.dwsm.discoverRoutineResume(
                        routine = routine,
                        launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
                    ),
                ).handle,
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                targetHarness.dwsm.resumeRoutine(handle),
            )
            runCurrent()
            assertIs<MachineTeardownState.Ready>(targetHarness.dwsm.machineTeardownState.value)
            val restoredAccepted = assertIs<RestTransitionPlan.AcceptedRetry>(
                targetHarness.restTransitionPlan.value,
            )
            return HydratedAcceptedRetryFixture(
                harness = targetHarness,
                document = document,
                handle = handle,
                accepted = restoredAccepted,
                sourceContext = source.sourceContext,
                sourceStableSessionId = source.sourceLease.sessionId,
                completedSets = completedSets,
                commandCount = targetHarness.fakeBleRepo.commandsReceived.size,
            )
        } catch (error: Throwable) {
            targetHarness?.cleanup()
            throw error
        } finally {
            sourceHarness.cleanup()
        }
    }

    private suspend fun prepareExhaustedDropOverlay(
        harness: DWSMTestHarness,
        routineOverride: Routine? = null,
    ): ExhaustedDropOverlayFixture {
        val routine = routineOverride ?: createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 25f,
        )
        harness.fakeWorkoutRepo.addRoutine(routine)
        val first = prepareDurableAcceptedRetry(
            harness = harness,
            routineOverride = routine,
            acceptedPercentage = DropPercentage.TWENTY,
        )
        harness.dwsm.applyRestTransitionAwait(
            RestTransitionCommand.SkipRest(first.accepted.actionIdentity()),
        )
        harness.testScope.runCurrent()
        completePositiveStall(harness, harness.activeSessionEngine.currentExecutionLeaseForTest())

        val offer = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
        harness.dwsm.applyRestTransitionAwait(
            RestTransitionCommand.Accept(offer.actionIdentity(), DropPercentage.TWENTY),
        )
        val second = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
        harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(second.actionIdentity()))
        harness.testScope.runCurrent()
        completePositiveStall(harness, harness.activeSessionEngine.currentExecutionLeaseForTest())

        return ExhaustedDropOverlayFixture(
            normalAdvance = assertIs(harness.restTransitionPlan.value),
            routine = routine,
            routineExerciseId = routine.exercises.first().id,
        )
    }

    private suspend fun prepareOverlaidWarmupJump(
        harness: DWSMTestHarness,
        warmupSets: List<WarmupSet>,
    ) {
        val routine = createTestRoutine(exerciseCount = 2, setsPerExercise = 1, weightKg = 25f)
        val fixture = prepareExhaustedDropOverlay(harness, routine)
        val loaded = assertNotNull(harness.coordinator.loadedRoutine.value)
        harness.coordinator._loadedRoutine.value = loaded.copy(
            exercises = loaded.exercises.mapIndexed { index, exercise ->
                if (index == 0) exercise.copy(warmupSets = warmupSets) else exercise
            },
        )
        harness.setActiveSummaryCountdownSeconds(0)
        harness.testScope.runCurrent()
        harness.dwsm.applyRestTransitionAwait(
            RestTransitionCommand.SkipRest(fixture.normalAdvance.actionIdentity()),
        )
        harness.testScope.runCurrent()
        assertEquals(
            1,
            assertIs<RoutineFlowState.SetReady>(harness.coordinator.routineFlowState.value).exerciseIndex,
        )

        harness.dwsm.jumpToExercise(0)
        harness.testScope.advanceTimeBy(7_000)
        harness.testScope.runCurrent()
        assertEquals(0, harness.coordinator.currentWarmupSetIndex.value)
    }

    private suspend fun prepareDurableDropOffer(
        harness: DWSMTestHarness,
        completedSets: FakeCompletedSetRepository = harness.fakeCompletedSetRepo,
        routineOverride: Routine? = null,
        exerciseIndex: Int = 0,
        setIndex: Int = 0,
    ): DurableDropOfferFixture {
        harness.setActiveSummaryCountdownSeconds(-1)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = routineOverride ?: createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.advanceUntilIdle()
        harness.dwsm.enterSetReady(exerciseIndex, setIndex)
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.advanceUntilIdle()
        val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )
        completedSets.setSessionRoutine(
            sourceLease.sessionId,
            assertNotNull(harness.coordinator.currentRoutineSessionId),
        )
        harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
        harness.testScope.advanceTimeBy(1_000)
        harness.testScope.runCurrent()
        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
        assertEquals(PersistenceClaimStatus.PERSISTED, harness.activeSessionEngine.executionGuard.persistenceClaimStatus(sourceLease.sessionId))
        assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
        return DurableDropOfferFixture(
            sourceLease = sourceLease,
            unresolved = unresolved,
            commandCount = harness.fakeBleRepo.commandsReceived.size,
        )
    }

    private suspend fun completePositiveStall(
        harness: DWSMTestHarness,
        lease: ExecutionLease,
        completedSets: FakeCompletedSetRepository = harness.fakeCompletedSetRepo,
    ) {
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )
        completedSets.setSessionRoutine(
            lease.sessionId,
            assertNotNull(harness.coordinator.currentRoutineSessionId),
        )
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
        harness.testScope.advanceTimeBy(1_000)
        harness.testScope.runCurrent()
    }

    @Test
    fun `just lift presentation defaults use issue 553 Echo level`() {
        val defaults = JustLiftDefaults(
            weightPerCableKg = 20f,
            weightChangePerRep = 0,
            workoutModeId = 10,
        )

        assertEquals(1, defaults.echoLevelValue)
        assertEquals(EchoLevel.HARDER, defaults.getEchoLevel())
    }

    // ===== A. startWorkout transitions =====

    @Test
    fun `startWorkout sets Initializing state immediately before coroutine launch`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        // startWorkout sets Initializing synchronously before launching the coroutine
        harness.dwsm.startWorkout(skipCountdown = true)

        // Before advancing, state should be Initializing (set synchronously in startWorkout)
        assertIs<WorkoutState.Initializing>(
            harness.dwsm.coordinator.workoutState.value,
            "State should be Initializing immediately after startWorkout call",
        )
        harness.cleanup()
    }

    @Test
    fun `startWorkout transitions to Active after countdown skipped`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertIs<WorkoutState.Active>(
            harness.dwsm.coordinator.workoutState.value,
            "State should be Active after skipCountdown=true and coroutine completes",
        )
        harness.cleanup()
    }

    @Test
    fun `startWorkout countdown emits 5-4-3-2-1 then Active`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.coordinator.workoutState.test {
            // Initial state
            assertEquals(WorkoutState.Idle, awaitItem())

            harness.dwsm.startWorkout(skipCountdown = false)

            // Initializing is set synchronously
            assertEquals(WorkoutState.Initializing, awaitItem())

            // Countdown states: 5, 4, 3, 2, 1
            for (i in 5 downTo 1) {
                advanceTimeBy(1000)
                val state = awaitItem()
                assertIs<WorkoutState.Countdown>(state, "Expected Countdown($i)")
                assertEquals(i, state.secondsRemaining, "Countdown should be $i")
            }

            // After last countdown tick, advance to get Active
            advanceTimeBy(1100) // Extra margin for BLE command delays
            // There may be intermediate emissions; skip to Active
            val finalStates = cancelAndConsumeRemainingEvents()
            val hasActive = finalStates.any {
                it is app.cash.turbine.Event.Item && it.value is WorkoutState.Active
            }
            assertTrue(hasActive, "Should eventually reach Active state after countdown")
        }
        harness.cleanup()
    }

    @Test
    fun `startWorkout sends BLE workout command`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        // DWSM sends the trainer configuration command.
        assertTrue(
            harness.fakeBleRepo.commandsReceived.isNotEmpty(),
            "Should have sent at least one BLE command (CONFIG)",
        )
        harness.cleanup()
    }

    @Test
    fun `startWorkout uses single activation config for non-Echo activation modes`() = runTest {
        val modes = listOf(
            ProgramMode.OldSchool,
            ProgramMode.Pump,
            ProgramMode.TUT,
            ProgramMode.TUTBeast,
            ProgramMode.EccentricOnly,
        )
        for (mode in modes) {
            val harness = DWSMTestHarness(this)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = mode,
                    reps = 8,
                    weightPerCableKg = 30f,
                    progressionRegressionKg = 2f,
                ),
            )

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertEquals(1, harness.fakeBleRepo.commandsReceived.size, "Expected only activation config for $mode")
            assertEquals(0x04.toByte(), harness.fakeBleRepo.commandsReceived[0][0], "Expected activation packet for $mode")
            assertFalse(
                harness.fakeBleRepo.commandsReceived.any { it.firstOrNull() == 0x4F.toByte() },
                "Workout start should not send a regular packet for $mode",
            )
            assertFalse(
                harness.fakeBleRepo.commandsReceived.any { it.firstOrNull() == 0x03.toByte() },
                "Activation workout start should not send legacy 0x03 start packet for $mode",
            )
            harness.cleanup()
        }
    }

    @Test
    fun `Issue687 Just Lift direct start creates a machine execution lease`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(TestFixtures.justLiftParams)

            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(lease.requiresMachine)
            assertTrue(lease.isJustLift)
            assertFalse(lease.isBodyweight)
            assertFalse(lease.isTimedCable)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Just Lift reset publishes Idle and egg timer before releasing reset claim`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveProfilePreferences(
                UserPreferences(autoStartCountdownSeconds = 2),
            )
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                TestFixtures.justLiftParams.copy(justLiftRestSeconds = 30),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 1, isWarmupComplete = true)

            var publicationObserved = false
            harness.activeSessionEngine.afterJustLiftResetPresentationForTest = {
                publicationObserved = true
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
                assertEquals(30, harness.coordinator.justLiftRestCountdown.value)

                // A queued handle-grab/auto-start must not cross the reset claim while
                // the post-reset Idle and egg-timer writes are being published.
                harness.fakeBleRepo.setHandleState(HandleState.Grabbed)
                advanceTimeBy(2_000)
                runCurrent()
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            }

            harness.activeSessionEngine.handleSetCompletion(
                leaseA,
                SetEndReason.TARGET_REPS_REACHED,
            )
            runCurrent()

            assertTrue(publicationObserved)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(28, harness.coordinator.justLiftRestCountdown.value)
        } finally {
            harness.activeSessionEngine.afterJustLiftResetPresentationForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `Just Lift reset releases its claim when cleanup throws`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(TestFixtures.justLiftParams)
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(workingReps = 1, isWarmupComplete = true)
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = {
                error("test reset cleanup failure")
            }

            assertFailsWith<IllegalStateException> {
                harness.activeSessionEngine.resetForNewWorkoutForTest(leaseA)
            }
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = null

            // The failed reset must not wedge the guard and prevent all future starts.
            harness.dwsm.updateWorkoutParameters(TestFixtures.justLiftParams)
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            assertNotEquals(leaseA, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `Issue687 bodyweight direct start creates a machine independent execution lease`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 30)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertFalse(lease.requiresMachine)
            assertTrue(lease.isBodyweight)
            assertFalse(lease.isJustLift)
            assertFalse(lease.isTimedCable)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertTrue(harness.fakeBleRepo.commandsReceived.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `terminal actions supersede a machine start queued behind reset teardown`() = runTest {
        for (cleanup in listOf(false, true)) {
            val teardownRelease = CompletableDeferred<Result<Unit>>()
            lateinit var harness: DWSMTestHarness
            var queueStartAfterInvalidation = false
            harness = DWSMTestHarness(
                testScope = this,
                afterResetInvalidation = { _, _ ->
                    if (queueStartAfterInvalidation) {
                        queueStartAfterInvalidation = false
                        harness.dwsm.startWorkout(skipCountdown = true)
                    }
                },
            )
            try {
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                harness.dwsm.startWorkout(skipCountdown = true)
                advanceUntilIdle()
                val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
                val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
                harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

                queueStartAfterInvalidation = true
                harness.dwsm.resetForNewWorkout()
                runCurrent()
                assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))

                if (cleanup) {
                    harness.dwsm.cleanup()
                } else {
                    harness.dwsm.stopWorkout(exitingWorkout = true)
                }
                teardownRelease.complete(Result.success(Unit))
                runCurrent()

                assertEquals(commandCountBeforeReset, harness.fakeBleRepo.commandsReceived.size)
                assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            } finally {
                teardownRelease.complete(Result.success(Unit))
                harness.cleanup()
            }
        }
    }

    @Test
    fun `bodyweight successor supersedes a machine start queued behind reset teardown`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        harness = DWSMTestHarness(
            testScope = this,
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))

            val bodyweightRoutine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
            bodyweightRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.coordinator._loadedRoutine.value = bodyweightRoutine
            harness.coordinator._currentExerciseIndex.value = 0
            harness.coordinator._currentSetIndex.value = 0
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val bodyweightLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(bodyweightLease.isBodyweight)

            teardownRelease.complete(Result.success(Unit))
            runCurrent()

            assertEquals(bodyweightLease, harness.activeSessionEngine.currentExecutionLeaseForTest())
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start rejects a changed exercise before bodyweight countdown and stop`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        harness = DWSMTestHarness(
            testScope = this,
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = false)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))

            val bodyweightRoutine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
            bodyweightRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.coordinator._loadedRoutine.value = bodyweightRoutine
            harness.coordinator._currentExerciseIndex.value = 0
            harness.coordinator._currentSetIndex.value = 0
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())

            harness.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)
            val countdownLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.dwsm.stopWorkout()
            advanceUntilIdle()

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            val laterLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(laterLease.executionId != countdownLease.executionId)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start revalidates its candidate at the guarded begin boundary`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        harness = DWSMTestHarness(
            testScope = this,
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))

            val bodyweightRoutine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
            bodyweightRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            var mutateAtGuardBegin = true
            harness.activeSessionEngine.beforeExecutionBeginForTest = {
                if (mutateAtGuardBegin) {
                    mutateAtGuardBegin = false
                    harness.coordinator._loadedRoutine.value = bodyweightRoutine
                    harness.coordinator._currentExerciseIndex.value = 0
                    harness.coordinator._currentSetIndex.value = 0
                }
            }
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertFalse(mutateAtGuardBegin)
            assertEquals(commandCountBeforeReset, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.beforeExecutionBeginForTest = null
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start cannot persist a newly loaded cable routine under the prior routine session`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routineA = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
                .copy(id = "queued-routine-a", name = "Queued Routine A")
            routineA.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routineA)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val sourceRoutineId = requireNotNull(harness.coordinator.currentRoutineId)
            val sourceRoutineSessionId = requireNotNull(harness.coordinator.currentRoutineSessionId)
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            val routineB = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 35f)
                .copy(
                    id = "queued-routine-b",
                    name = "Queued Routine B",
                    exercises = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 35f)
                        .exercises
                        .map { it.copy(id = "queued-routine-b-exercise") },
                )
            routineB.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routineB)
            runCurrent()
            assertEquals(routineB.id, harness.coordinator.loadedRoutine.value?.id)
            assertEquals(sourceRoutineId, harness.coordinator.currentRoutineId)
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.dwsm.startWorkout(skipCountdown = true)
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))
            assertEquals(routineB.id, harness.coordinator.currentRoutineId)
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(routineB.id, harness.coordinator.currentRoutineId)
            assertEquals(routineB.name, harness.coordinator.currentRoutineName)
            val routineBSessionId = requireNotNull(harness.coordinator.currentRoutineSessionId)
            assertTrue(routineBSessionId != sourceRoutineSessionId)
            assertEquals(
                35f,
                readFloatLe(
                    harness.fakeBleRepo.commandsReceived.last(),
                    BleConstants.ActivationPacket.OFFSET_TARGET_WEIGHT,
                ),
            )
            val routineBLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 1,
                totalReps = 1,
                isWarmupComplete = true,
            )
            harness.activeSessionEngine.handleSetCompletion(
                routineBLease,
                SetEndReason.TARGET_REPS_REACHED,
            )
            advanceUntilIdle()

            val savedRoutineBSession = harness.fakeWorkoutRepo
                .getAllSessions("default")
                .first()
                .single { it.id == routineBLease.sessionId }
            assertEquals(routineB.id, savedRoutineBSession.routineId)
            assertEquals(routineB.name, savedRoutineBSession.routineName)
            assertEquals(routineBSessionId, savedRoutineBSession.routineSessionId)
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start rejects a loaded routine owned by the prior profile`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest(
                "queued-profile-b",
                name = "Queued Profile B",
            )
            harness.fakeUserProfileRepo.emitReadyForTest("default")
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
                .copy(id = "queued-profile-a-routine", profileId = "default")
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.fakeUserProfileRepo.emitReadyForTest("queued-profile-b")
            harness.dwsm.startWorkout(skipCountdown = true)

            assertFalse(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start permits a temporary exercise routine for the active custom profile`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest(
                "queued-temp-profile",
                name = "Queued Temp Profile",
            )
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val temporaryRoutine = createTestRoutine(
                exerciseCount = 1,
                setsPerExercise = 1,
                weightKg = 25f,
            ).copy(
                id = "${DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX}queued-profile",
                profileId = "default",
            )
            temporaryRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(temporaryRoutine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.dwsm.startWorkout(skipCountdown = true)

            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset + 1, harness.fakeBleRepo.commandsReceived.size)
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertEquals("queued-temp-profile", successor.profileId)
            assertEquals(null, harness.coordinator.currentRoutineId)
            assertEquals(null, harness.coordinator.currentRoutineSessionId)
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start rejects command input mutation after final identity validation`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        harness = DWSMTestHarness(
            testScope = this,
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 25f),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))

            var mutateAtConfigurationBoundary = true
            harness.activeSessionEngine.beforeQueuedSuccessorMachineConfigurationForTest = {
                if (mutateAtConfigurationBoundary) {
                    mutateAtConfigurationBoundary = false
                    harness.dwsm.adjustWeight(newWeightKg = 35f, sendToMachine = false)
                }
            }
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertFalse(mutateAtConfigurationBoundary)
            assertEquals(commandCountBeforeReset, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
        } finally {
            harness.activeSessionEngine.beforeQueuedSuccessorMachineConfigurationForTest = null
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset start mutation during config tears down before a later clean start`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        harness = DWSMTestHarness(
            testScope = this,
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 25f),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            val completedStopsBeforeReset = harness.fakeBleRepo.events.count {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))
            harness.fakeBleRepo.afterWorkoutCommand = {
                harness.fakeBleRepo.afterWorkoutCommand = {}
                harness.dwsm.adjustWeight(newWeightKg = 35f, sendToMachine = false)
            }

            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(
                completedStopsBeforeReset + 2,
                harness.fakeBleRepo.events.count { it is FakeBleRepository.Event.StopWorkoutCompleted },
            )
            val queuedCommandIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.WorkoutCommand
            }
            val recoveryStopIndex = harness.fakeBleRepo.events.indexOfLast {
                it is FakeBleRepository.Event.StopWorkoutCompleted
            }
            assertTrue(queuedCommandIndex in 0 until recoveryStopIndex)
            assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertEquals(commandCountBeforeReset + 2, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.fakeBleRepo.afterWorkoutCommand = {}
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `ordinary start rejects command input mutation before config claim`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(weightPerCableKg = 25f),
            )
            var mutateAtConfigurationClaim = true
            harness.activeSessionEngine.beforeMachineConfigurationClaimForTest = {
                if (mutateAtConfigurationClaim) {
                    mutateAtConfigurationClaim = false
                    harness.dwsm.adjustWeight(newWeightKg = 35f, sendToMachine = false)
                }
            }

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertFalse(mutateAtConfigurationClaim)
            assertEquals(0, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())

            harness.activeSessionEngine.beforeMachineConfigurationClaimForTest = null
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertEquals(1, harness.fakeBleRepo.commandsReceived.size)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.beforeMachineConfigurationClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `bodyweight start rejects command input mutation during countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)

            harness.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)
            harness.dwsm.updateWorkoutParameters(
                harness.coordinator.workoutParameters.value.copy(reps = 7),
            )
            advanceTimeBy(5_100)
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Active)

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `bodyweight start rejects an external active profile change during countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeUserProfileRepo.seedReadyProfileForTest("bodyweight-profile-b", name = "Profile B")
            harness.fakeUserProfileRepo.emitReadyForTest("default")
            val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)

            harness.dwsm.startWorkout(skipCountdown = false)
            runCurrent()
            assertIs<WorkoutState.Countdown>(harness.coordinator.workoutState.value)
            harness.fakeUserProfileRepo.emitReadyForTest("bodyweight-profile-b")
            advanceTimeBy(5_100)
            runCurrent()

            assertEquals(null, harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Active)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `concurrent reset loser cannot clear the owner that queues exactly one successor`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        val harness = DWSMTestHarness(this)
        var invokeNestedReset = true
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = {
                if (invokeNestedReset) {
                    invokeNestedReset = false
                    harness.dwsm.resetForNewWorkout()
                }
            }

            harness.dwsm.resetForNewWorkout()
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = null
            runCurrent()
            assertIs<MachineTeardownState.TearingDown>(harness.dwsm.machineTeardownState.value)

            harness.dwsm.startWorkout(skipCountdown = true)
            assertTrue(harness.activeSessionEngine.hasPendingTeardownReadyContinuationForTest(sourceLease))
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset + 1, harness.fakeBleRepo.commandsReceived.size)
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(successor.executionId != sourceLease.executionId)
        } finally {
            harness.activeSessionEngine.afterResetCleanupTokenCaptureForTest = null
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `public start intent supersedes a reset queue already taken for callback`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        val begunExecutionIds = mutableListOf<Long>()
        harness = DWSMTestHarness(
            testScope = this,
            afterExecutionBegin = { _, executionId -> begunExecutionIds += executionId },
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        var resumeTakenStart: (() -> Unit)? = null
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            val executionCountBeforeReset = begunExecutionIds.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            harness.activeSessionEngine.pendingResetStartInterceptorForTest = { resume ->
                resumeTakenStart = resume
            }
            teardownRelease.complete(Result.success(Unit))
            runCurrent()
            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertNotNull(resumeTakenStart)

            var resumeInsidePublicStart = true
            harness.activeSessionEngine.beforeExecutionBeginForTest = {
                if (resumeInsidePublicStart) {
                    resumeInsidePublicStart = false
                    requireNotNull(resumeTakenStart).invoke()
                    testScheduler.runCurrent()
                }
            }
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(executionCountBeforeReset + 1, begunExecutionIds.size)
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(successor.executionId != sourceLease.executionId)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.activeSessionEngine.pendingResetStartInterceptorForTest = null
            harness.activeSessionEngine.beforeExecutionBeginForTest = null
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `queued reset successor owns setup when public start arrives after its guard begin`() = runTest {
        val teardownRelease = CompletableDeferred<Result<Unit>>()
        lateinit var harness: DWSMTestHarness
        var queueStartAfterInvalidation = false
        var startPublicAfterQueuedBegin = false
        val begunExecutionIds = mutableListOf<Long>()
        harness = DWSMTestHarness(
            testScope = this,
            afterExecutionBegin = { _, executionId ->
                begunExecutionIds += executionId
                if (startPublicAfterQueuedBegin) {
                    startPublicAfterQueuedBegin = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
            afterResetInvalidation = { _, _ ->
                if (queueStartAfterInvalidation) {
                    queueStartAfterInvalidation = false
                    harness.dwsm.startWorkout(skipCountdown = true)
                }
            },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandCountBeforeReset = harness.fakeBleRepo.commandsReceived.size
            val executionCountBeforeReset = begunExecutionIds.size
            harness.fakeBleRepo.stopWorkoutBlock = { teardownRelease.await() }

            queueStartAfterInvalidation = true
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            startPublicAfterQueuedBegin = true
            teardownRelease.complete(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(commandCountBeforeReset + 1, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(executionCountBeforeReset + 1, begunExecutionIds.size)
            val successor = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(successor.executionId != sourceLease.executionId)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            teardownRelease.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `Issue687 timed cable direct start creates a timed machine execution lease`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTimedCableRoutine(durationSeconds = 30)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            advanceUntilIdle()

            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(lease.requiresMachine)
            assertTrue(lease.isTimedCable)
            assertFalse(lease.isBodyweight)
            assertFalse(lease.isJustLift)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    // ===== B. stopWorkout transitions =====

    @Test
    fun `stopWorkout with exitingWorkout true transitions to Idle`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        assertIs<WorkoutState.Idle>(
            harness.dwsm.coordinator.workoutState.value,
            "stopWorkout(exitingWorkout=true) should transition to Idle",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout with exitingWorkout false transitions to SetSummary`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "stopWorkout(exitingWorkout=false) should transition to SetSummary",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout guard flag prevents double stop`() = runTest {
        val harness = activeDWSM()

        // First stop should work
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()
        val firstState = harness.dwsm.coordinator.workoutState.value
        assertIs<WorkoutState.SetSummary>(firstState)

        // Second stop should be a no-op (guard flag set)
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Characterization: The second stopWorkout is silently ignored due to
        // stopWorkoutInProgress guard flag. State remains SetSummary.
        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "Second stopWorkout should be silently ignored (guard flag)",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout calls bleRepository stopWorkout for cable exercises`() = runTest {
        val harness = activeDWSM()

        // Track that BLE stop is called by checking no crash occurs
        // FakeBleRepository.stopWorkout() returns Result.success(Unit)
        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Verify state transition completed (which means BLE stop was called successfully)
        assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `stopAndSkipCurrentExercise advances routine without ending workout session`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val routine = createTestRoutine(exerciseCount = 3, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        harness.dwsm.stopAndSkipCurrentExercise()
        advanceUntilIdle()

        val flowState = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(
            flowState,
            "stopAndSkipCurrentExercise should return to SetReady instead of ending the routine",
        )
        assertEquals(1, flowState.exerciseIndex, "Should advance to the next exercise")
        assertEquals(0, flowState.setIndex, "Next exercise should start at set 1")
        assertTrue(0 in harness.dwsm.coordinator.skippedExercises.value)
        assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    // ===== C. resetForNewWorkout =====

    @Test
    fun `resetForNewWorkout clears rep count and resets to Idle`() = runTest {
        val harness = activeDWSM()

        // Stop workout first to get to SetSummary
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()
        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)

        // Now reset
        harness.dwsm.resetForNewWorkout()

        assertEquals(
            WorkoutState.Idle,
            harness.dwsm.coordinator.workoutState.value,
            "resetForNewWorkout should set state to Idle",
        )
        assertEquals(
            RepCount(),
            harness.dwsm.coordinator.repCount.value,
            "resetForNewWorkout should reset rep count to default",
        )
        harness.cleanup()
    }

    @Test
    fun `resetForNewWorkout clears rep ranges`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.resetForNewWorkout()

        // repRanges should be cleared to null
        assertEquals(
            null,
            harness.dwsm.coordinator.repRanges.value,
            "resetForNewWorkout should clear repRanges to null",
        )
        harness.cleanup()
    }

    @Test
    fun `resetForNewWorkout clears retained session scope and metrics`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.coordinator.currentSessionId = "stale-session-id"
        harness.dwsm.coordinator.workoutStartTime = 1700000000000L
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = harness.nowMs + 100L,
                loadA = 25f,
                loadB = 25f,
                positionA = 120f,
                positionB = 120f,
                velocityA = 40.0,
                velocityB = 40.0,
            ),
        )

        harness.dwsm.resetForNewWorkout()

        assertEquals(
            null,
            harness.dwsm.coordinator.currentSessionId,
            "resetForNewWorkout should clear stale session id",
        )
        assertEquals(
            0L,
            harness.dwsm.coordinator.workoutStartTime,
            "resetForNewWorkout should zero stale workout start time",
        )
        assertTrue(
            harness.dwsm.coordinator.collectedMetrics.value.isEmpty(),
            "resetForNewWorkout should clear collected metrics",
        )
        harness.cleanup()
    }

    // ===== D. updateWorkoutParameters =====

    @Test
    fun `updateWorkoutParameters updates the workoutParameters flow`() = runTest {
        val harness = DWSMTestHarness(this)

        val newParams = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 12,
            weightPerCableKg = 30f,
            progressionRegressionKg = 0.5f,
        )
        harness.dwsm.updateWorkoutParameters(newParams)

        val current = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(ProgramMode.Pump, current.programMode)
        assertEquals(12, current.reps)
        assertEquals(30f, current.weightPerCableKg)
        assertEquals(0.5f, current.progressionRegressionKg)
        harness.cleanup()
    }

    @Test
    fun `updateWorkoutParameters during Idle does not crash`() = runTest {
        val harness = DWSMTestHarness(this)

        // Update while Idle should work without issues
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 8,
            weightPerCableKg = 20f,
        )
        harness.dwsm.updateWorkoutParameters(params)
        advanceUntilIdle()

        assertEquals(8, harness.dwsm.coordinator.workoutParameters.value.reps)
        harness.cleanup()
    }

    @Test
    fun `routine next-set weight applies when user did not manually edit`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        val routine = base.copy(
            exercises = listOf(
                base.exercises.first().copy(
                    setReps = listOf(8, 6),
                    setWeightsPerCableKg = listOf(20f, 30f),
                    weightPerCableKg = 20f,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = "",
            isLastExercise = true,
            currentSet = 1,
            totalSets = 2,
        )
        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(30f, params.weightPerCableKg)
        assertEquals(6, params.reps)
        harness.cleanup()
    }

    @Test
    fun `manual rest-screen edits are preserved across transition`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        val routine = base.copy(
            exercises = listOf(
                base.exercises.first().copy(
                    setReps = listOf(8, 6),
                    setWeightsPerCableKg = listOf(20f, 30f),
                    weightPerCableKg = 20f,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = "",
            isLastExercise = true,
            currentSet = 1,
            totalSets = 2,
        )
        harness.dwsm.updateWorkoutParameters(
            harness.dwsm.coordinator.workoutParameters.value.copy(
                weightPerCableKg = 42f,
                reps = 11,
            ),
        )

        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(42f, params.weightPerCableKg)
        assertEquals(11, params.reps)
        harness.cleanup()
    }

    @Test
    fun `previous exercise weight does not leak into next exercise`() = runTest {
        val harness = DWSMTestHarness(this)
        val base = createTestRoutine(exerciseCount = 2, setsPerExercise = 1)
        val routine = base.copy(
            exercises = listOf(
                base.exercises[0].copy(
                    setReps = listOf(8),
                    setWeightsPerCableKg = listOf(20f),
                    weightPerCableKg = 20f,
                ),
                base.exercises[1].copy(
                    setReps = listOf(10),
                    setWeightsPerCableKg = listOf(55f),
                    weightPerCableKg = 55f,
                ),
            ),
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        // Disable autoplay so startNextSet() enters SetReady instead of launching
        // startWorkout() which creates infinite delay loops (rest timer, metrics, etc.)
        harness.setActiveSummaryCountdownSeconds(0)
        advanceUntilIdle()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 0,
            nextExerciseName = routine.exercises[1].exercise.displayName,
            isLastExercise = false,
            currentSet = 1,
            totalSets = 1,
        )
        harness.dwsm.startNextSet()
        advanceUntilIdle()

        val params = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(55f, params.weightPerCableKg)
        assertEquals(10, params.reps)
        assertEquals(routine.exercises[1].exercise.id, params.selectedExerciseId)
        harness.cleanup()
    }

    @Test
    fun `rest timer catches up after background delay shorter than rest duration`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.activeSessionEngine.startRestTimer()
        advanceTimeBy(30_000)
        runCurrent()

        val state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(30, state.restSecondsRemaining)
        harness.cleanup()
    }

    @Test
    fun `rest timer clamps to zero after background delay longer than rest duration without autoplay`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.activeSessionEngine.startRestTimer()
        advanceTimeBy(70_000)
        runCurrent()

        val state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(0, state.restSecondsRemaining)
        harness.cleanup()
    }

    @Test
    fun `rest timer emits countdown ticks through five seconds then rest ending at four seconds`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)
        val events = mutableListOf<HapticEvent>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                events.add(event)
            }
        }

        try {
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            harness.activeSessionEngine.startRestTimer()
            advanceTimeBy(56_000)
            runCurrent()

            assertEquals(
                (10 downTo 5).toList(),
                events.filterIsInstance<HapticEvent.COUNTDOWN_TICK>().map { it.secondsRemaining },
            )
            assertEquals(1, events.filterIsInstance<HapticEvent.REST_ENDING>().size)
            val state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(4, state.restSecondsRemaining)

            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(
                (10 downTo 5).toList(),
                events.filterIsInstance<HapticEvent.COUNTDOWN_TICK>().map { it.secondsRemaining },
            )
            assertEquals(1, events.filterIsInstance<HapticEvent.REST_ENDING>().size)
        } finally {
            hapticJob.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun `rest timer suppresses rest ending warning when countdown beeps are disabled`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)
        harness.setActiveCountdownBeepsEnabled(false)
        val events = mutableListOf<HapticEvent>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                events.add(event)
            }
        }

        try {
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            harness.activeSessionEngine.startRestTimer()
            advanceTimeBy(56_000)
            runCurrent()

            assertEquals(emptyList(), events.filterIsInstance<HapticEvent.REST_ENDING>())
        } finally {
            hapticJob.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun `rest timer pause resume preserves remaining time across elapsed time`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.activeSessionEngine.startRestTimer()
        advanceTimeBy(15_000)
        runCurrent()

        var state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(45, state.restSecondsRemaining)

        harness.dwsm.toggleRestPause()
        advanceTimeBy(10_000)
        runCurrent()
        state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(45, state.restSecondsRemaining)
        assertTrue(harness.dwsm.coordinator.isRestPaused.value)

        harness.dwsm.toggleRestPause()
        advanceTimeBy(10_000)
        runCurrent()
        state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(35, state.restSecondsRemaining)
        assertFalse(harness.dwsm.coordinator.isRestPaused.value)
        harness.cleanup()
    }

    @Test
    fun `rest timer extend and reset operate on deadline based state`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.setActiveSummaryCountdownSeconds(0)

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.activeSessionEngine.startRestTimer()
        advanceTimeBy(30_000)
        runCurrent()

        harness.dwsm.extendRestTime(30)
        advanceTimeBy(100)
        runCurrent()
        var state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(60, state.restSecondsRemaining)

        advanceTimeBy(10_000)
        runCurrent()
        state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(50, state.restSecondsRemaining)

        harness.dwsm.resetRestTimer()
        advanceTimeBy(100)
        runCurrent()
        state = assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(90, state.restSecondsRemaining)
        harness.cleanup()
    }

    @Test
    fun `just lift rest countdown catches up after elapsed time`() = runTest {
        val harness = DWSMTestHarness(this)

        harness.activeSessionEngine.startJustLiftEggTimer(60)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(30, harness.dwsm.coordinator.justLiftRestCountdown.value)

        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(0, harness.dwsm.coordinator.justLiftRestCountdown.value)
        harness.cleanup()
    }

    @Test
    fun `tagJustLiftSessionExercise updates session and creates one completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        val session = WorkoutSession(
            id = "just-lift-session",
            timestamp = 1_000L,
            mode = "OldSchool",
            reps = 0,
            weightPerCableKg = 30f,
            duration = 12_000L,
            totalReps = 7,
            workingReps = 7,
            isJustLift = true,
            rpe = 8,
        )
        harness.fakeWorkoutRepo.addSession(session)
        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 30f,
            avgLoadKgPerCable = 25f,
            repCount = 7,
            sessionId = session.id,
            isAmrap = true,
        )

        harness.dwsm.tagJustLiftSessionExercise(session.id, TestFixtures.deadlift, isAmrap = true)
        advanceUntilIdle()

        val updatedSession = harness.fakeWorkoutRepo.getSession(session.id)
        assertEquals(TestFixtures.deadlift.id, updatedSession?.exerciseId)
        assertEquals(TestFixtures.deadlift.name, updatedSession?.exerciseName)

        val completedSets = harness.fakeCompletedSetRepo.getCompletedSets(session.id)
        assertEquals(1, completedSets.size)
        val completedSet = completedSets.single()
        assertEquals(0, completedSet.setNumber)
        assertEquals(7, completedSet.actualReps)
        assertEquals(30f, completedSet.actualWeightKg)
        assertEquals(8, completedSet.loggedRpe)
        assertEquals(SetType.AMRAP, completedSet.setType)
        assertFalse(completedSet.isPr)

        val summary = assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)
        assertEquals(TestFixtures.deadlift.id, summary.taggedExerciseId)
        assertEquals(TestFixtures.deadlift.name, summary.taggedExerciseName)
        harness.cleanup()
    }

    @Test
    fun `retagging Just Lift session updates tag without duplicating completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        val session = WorkoutSession(
            id = "just-lift-retag-session",
            timestamp = 1_000L,
            mode = "OldSchool",
            reps = 0,
            weightPerCableKg = 20f,
            duration = 10_000L,
            totalReps = 5,
            workingReps = 5,
            isJustLift = true,
        )
        harness.fakeWorkoutRepo.addSession(session)
        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20f,
            avgLoadKgPerCable = 18f,
            repCount = 5,
            sessionId = session.id,
        )

        harness.dwsm.tagJustLiftSessionExercise(session.id, TestFixtures.squat, isAmrap = false)
        advanceUntilIdle()
        val firstSetId = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single().id
        assertEquals(
            listOf(TestFixtures.squat.id),
            harness.fakePRRepo.updateCalls.map { it.exerciseId },
        )

        harness.dwsm.tagJustLiftSessionExercise(session.id, TestFixtures.deadlift, isAmrap = false)
        advanceUntilIdle()

        val updatedSession = harness.fakeWorkoutRepo.getSession(session.id)
        assertEquals(TestFixtures.deadlift.id, updatedSession?.exerciseId)
        assertEquals(TestFixtures.deadlift.name, updatedSession?.exerciseName)

        val completedSets = harness.fakeCompletedSetRepo.getCompletedSets(session.id)
        assertEquals(1, completedSets.size)
        assertEquals(firstSetId, completedSets.single().id)
        assertEquals(1, harness.fakeCompletedSetRepo.getCompletedSetsForExercise(TestFixtures.deadlift.id!!).size)
        assertEquals(0, harness.fakeCompletedSetRepo.getCompletedSetsForExercise(TestFixtures.squat.id!!).size)
        assertEquals(
            listOf(TestFixtures.squat.id),
            harness.fakePRRepo.updateCalls.map { it.exerciseId },
        )
        harness.cleanup()
    }

    @Test
    fun `workout service snapshot follows workout phases and stops when idle`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.fakeWorkoutServiceController.reset()

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Active
        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )
        advanceUntilIdle()
        assertEquals(WorkoutServicePhase.ACTIVE, harness.fakeWorkoutServiceController.snapshots.last().phase)
        assertEquals(3, harness.fakeWorkoutServiceController.snapshots.last().completedReps)

        harness.dwsm.coordinator._workoutState.value = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 20f,
            avgLoadKgPerCable = 15f,
            repCount = 3,
        )
        advanceUntilIdle()
        assertEquals(WorkoutServicePhase.SET_SUMMARY, harness.fakeWorkoutServiceController.snapshots.last().phase)

        harness.dwsm.coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = 42,
            nextExerciseName = "Next Exercise",
            isLastExercise = false,
            currentSet = 1,
            totalSets = 2,
        )
        advanceUntilIdle()
        assertEquals(WorkoutServicePhase.RESTING, harness.fakeWorkoutServiceController.snapshots.last().phase)
        assertEquals(42, harness.fakeWorkoutServiceController.snapshots.last().secondsRemaining)

        harness.dwsm.coordinator._justLiftRestCountdown.value = 18
        harness.dwsm.coordinator._workoutState.value = WorkoutState.Idle
        advanceUntilIdle()
        assertEquals(WorkoutServicePhase.JUST_LIFT_REST, harness.fakeWorkoutServiceController.snapshots.last().phase)
        assertEquals(18, harness.fakeWorkoutServiceController.snapshots.last().secondsRemaining)

        harness.dwsm.coordinator._justLiftRestCountdown.value = null
        advanceUntilIdle()
        assertTrue(harness.fakeWorkoutServiceController.stopCount >= 1)
        harness.cleanup()
    }

    // ===== E. Auto-stop behavior (indirect) =====

    @Test
    fun `autoStopState starts with default values`() = runTest {
        val harness = DWSMTestHarness(this)

        val autoStop = harness.dwsm.coordinator.autoStopState.value
        // Characterization: AutoStopUiState default is not counting down
        assertNotNull(autoStop, "autoStopState should never be null")
        harness.cleanup()
    }

    @Test
    fun `startWorkout resets autoStop state`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        // Characterization: startWorkout always calls resetAutoStopState() which
        // clears any previous auto-stop timers and resets the UI state
        val autoStop = harness.dwsm.coordinator.autoStopState.value
        assertNotNull(autoStop, "autoStopState should be reset after startWorkout")
        harness.cleanup()
    }

    @Test
    fun `deload does not start stall timer before warmup is complete`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        assertFalse(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should be ignored until warmup reps are complete",
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `deload does not start stall timer before first working rep even after warmup`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should be ignored until at least one working rep is confirmed",
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `deload starts stall timer after first working rep`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertEquals(1, harness.dwsm.coordinator.repCount.value.workingReps)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "DELOAD should start stall timer once working reps are in progress",
        )
        assertTrue(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `standard set ignores position-based auto-stop countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Regression guard: regular sets should not use the 2.5s "handles at rest" path.
        harness.dwsm.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 0f,
                positionB = 0f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 0f,
                loadB = 0f,
            ),
        )
        advanceUntilIdle()

        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        assertFalse(harness.dwsm.coordinator.autoStopTriggered)
        harness.cleanup()
    }

    @Test
    fun `standard set auto-stops after stalled deload timer expires`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()
        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L

        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 10f,
                loadB = 10f,
            ),
        )
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `Issue 256 - deload starts stall timer even with pending rep`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Complete warmup
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)
        assertEquals(0, harness.dwsm.coordinator.repCount.value.workingReps)

        // Simulate starting the first rep (pending at TOP = failed bench press scenario)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = 4, // warmup(3) + working(1) = 4th up counter
                completeCounter = 3, // Only 3 downs (first working rep not completed)
                repsRomCount = 3,
                repsRomTotal = 3,
                repsSetCount = 0, // Still 0 completed working reps
                repsSetTotal = 8,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = harness.nowMs + 100L,
            ),
        )
        advanceUntilIdle()
        assertTrue(
            harness.dwsm.coordinator.repCount.value.hasPendingRep,
            "Rep should be pending at TOP (failed lift scenario)",
        )

        // Emit DELOAD_OCCURRED while pending - previously this was ignored (Issue #256)
        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "Issue #256: DELOAD should start stall timer even with a pending rep",
        )
        assertTrue(harness.dwsm.coordinator.isCurrentlyStalled)
        harness.cleanup()
    }

    @Test
    fun `Issue 256 - velocity stall auto-stops with pending rep after timer expires`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Simulate pending first rep (stalled mid-concentric)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = 4,
                completeCounter = 3,
                repsRomCount = 3,
                repsRomTotal = 3,
                repsSetCount = 0,
                repsSetTotal = 8,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = harness.nowMs + 100L,
            ),
        )
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.hasPendingRep)

        // Backdate stall timer to simulate 6 seconds elapsed
        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L
        harness.dwsm.coordinator.isCurrentlyStalled = true

        // Emit a stalled metric (near-zero velocity, position elevated = mid-rep)
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 10f,
                loadB = 10f,
            ),
        )
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "Issue #256: Velocity stall should auto-stop even with a pending rep",
        )
        harness.cleanup()
    }

    @Test
    fun `F1 - Echo mode deload event does not arm stall timer`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.Echo,
                echoLevel = EchoLevel.HARDER,
                reps = 10,
                warmupReps = 0,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 10)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 10)
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.repCount.value.isWarmupComplete)

        // In Echo mode DELOAD_OCCURRED fires routinely as the athlete fatigues
        // (Echo levels are defined by the firmware's deload window) — it must not
        // arm the auto-stop stall countdown.
        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "Echo-mode DELOAD_OCCURRED is routine firmware behavior and must not arm the stall timer",
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `F1 - Echo mode velocity stall still arms mid-rep`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.Echo,
                echoLevel = EchoLevel.HARDER,
                reps = 10,
                warmupReps = 0,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 10)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 10)
        advanceUntilIdle()

        // Genuinely stalled mid-rep: handles extended, no movement.
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 10f,
                loadB = 10f,
            ),
        )
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "Echo mode must keep the velocity-based stall protection (only the deload arm is suppressed)",
        )
        harness.cleanup()
    }

    @Test
    fun `Issue 652 - completed Echo rep cancels an armed stall timer`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 10)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 10)
        advanceUntilIdle()

        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()
        assertNotNull(harness.dwsm.coordinator.stallStartTime)

        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = 5,
                completeCounter = 5,
                repsRomCount = 3,
                repsRomTotal = 3,
                repsSetCount = 2,
                repsSetTotal = 10,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = harness.nowMs + 5L,
            ),
        )
        advanceUntilIdle()

        assertEquals(2, harness.dwsm.coordinator.repCount.value.workingReps)
        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "A completed working rep proves motion resumed and must cancel the stale stall countdown",
        )
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `F3 - startWorkout for next set clears rep boundary timestamps and biomech state`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Seed biomech/VBT state deterministically (processRep is synchronous),
        // simulating a completed warm-up set on the Phase 35C fast path.
        val repMetric = WorkoutMetric(
            positionA = 100f,
            positionB = 100f,
            velocityA = 500.0,
            velocityB = 500.0,
            loadA = 10f,
            loadB = 10f,
        )
        harness.dwsm.coordinator.repBoundaryTimestamps.value = listOf(currentTimeMillis())
        harness.dwsm.coordinator.biomechanicsEngine.processRep(
            repNumber = 1,
            concentricMetrics = listOf(repMetric),
            allRepMetrics = listOf(repMetric),
            timestamp = currentTimeMillis(),
        )
        assertNotNull(harness.dwsm.coordinator.biomechanicsEngine.latestRepResult.value)

        // The Phase 35C variable warm-up fast path advances via startWorkout(skipCountdown = true)
        // without running handleSetCompletion's biomech/VBT reset block.
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.biomechanicsEngine.latestRepResult.value,
            "A new set must not inherit the previous set's biomechanics state (VBT baseline leak)",
        )
        assertTrue(
            harness.dwsm.coordinator.repBoundaryTimestamps.value.isEmpty(),
            "Rep boundary timestamps must reset at set start",
        )
        harness.cleanup()
    }

    @Test
    fun `F4 - racked handles reset a velocity-armed stall countdown in standard set`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Velocity-arm the stall countdown mid-rep (handles extended, no movement)
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 120f, positionB = 120f, velocityA = 0.0, velocityB = 0.0, loadA = 10f, loadB = 10f),
        )
        advanceUntilIdle()
        assertNotNull(harness.dwsm.coordinator.stallStartTime)
        assertFalse(harness.dwsm.coordinator.stallArmedByDeload)

        // Rack the handles and let the (backdated) countdown "expire" — a racked
        // pause between reps must cancel the velocity-armed countdown, not end the set.
        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 0f, positionB = 0f, velocityA = 0.0, velocityB = 0.0, loadA = 0f, loadB = 0f),
        )
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "Racked handles must cancel a velocity-armed stall countdown (resting between reps is not a stall)",
        )
        assertFalse(harness.dwsm.coordinator.autoStopTriggered)
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `F4 - deload-armed stall survives racked handles and auto-stops`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Genuine cable release: firmware deload arms the countdown; the cables
        // retract to ~0mm afterwards, which must NOT cancel it.
        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()
        assertNotNull(harness.dwsm.coordinator.stallStartTime)
        assertTrue(harness.dwsm.coordinator.stallArmedByDeload)

        harness.dwsm.coordinator.stallStartTime = currentTimeMillis() - 6_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 0f, positionB = 0f, velocityA = 0.0, velocityB = 0.0, loadA = 0f, loadB = 0f),
        )
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "A deload-armed stall countdown must fire even with the cables retracted (genuine release)",
        )
        harness.cleanup()
    }

    @Test
    fun `F4 - velocity stall does not arm at rest despite established range`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Handles fully at rest between reps. Before the F4 fix, the
        // hasMeaningfulRange latch made this arm the 5s countdown.
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 0f, positionB = 0f, velocityA = 0.0, velocityB = 0.0, loadA = 0f, loadB = 0f),
        )
        advanceUntilIdle()

        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "Handles at rest must not arm the velocity stall countdown in a standard set",
        )
        harness.cleanup()
    }

    @Test
    fun `F4 - deload event upgrades a velocity-armed stall so racking does not cancel it`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Velocity-arm first (stalled mid-rep) ...
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 120f, positionB = 120f, velocityA = 0.0, velocityB = 0.0, loadA = 10f, loadB = 10f),
        )
        advanceUntilIdle()
        assertNotNull(harness.dwsm.coordinator.stallStartTime)
        assertFalse(harness.dwsm.coordinator.stallArmedByDeload)

        // ... then the machine detects the release: the countdown must be upgraded
        // so the retracting cables can't cancel it.
        harness.fakeBleRepo.emitDeloadOccurred()
        advanceUntilIdle()
        assertTrue(harness.dwsm.coordinator.stallArmedByDeload)

        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 0f, positionB = 0f, velocityA = 0.0, velocityB = 0.0, loadA = 0f, loadB = 0f),
        )
        advanceUntilIdle()

        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "A deload-upgraded countdown must survive the cables retracting to rest",
        )
        harness.cleanup()
    }

    @Test
    fun `F8 - handle release clears verbal cue defer deadline`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 35f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = false,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        // Verbal-cue defer active: the stall path must not arm.
        harness.dwsm.coordinator.deferAutoStopDeadlineMs = currentTimeMillis() + 30_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 120f, positionB = 120f, velocityA = 0.0, velocityB = 0.0, loadA = 10f, loadB = 10f),
        )
        advanceUntilIdle()
        assertEquals(
            null,
            harness.dwsm.coordinator.stallStartTime,
            "Stall must stay deferred while the verbal-cue window is active",
        )

        // Releasing the handles proves the set is over — the defer must clear ...
        harness.fakeBleRepo.setHandleState(HandleState.Released)
        advanceUntilIdle()
        assertEquals(
            0L,
            harness.dwsm.coordinator.deferAutoStopDeadlineMs,
            "Handle release must clear the verbal-cue defer deadline",
        )

        // ... and auto-stop paths resume immediately.
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(positionA = 120f, positionB = 120f, velocityA = 0.0, velocityB = 0.0, loadA = 10f, loadB = 10f),
        )
        advanceUntilIdle()
        assertNotNull(
            harness.dwsm.coordinator.stallStartTime,
            "Auto-stop must resume once the defer is cleared by handle release",
        )
        harness.cleanup()
    }

    @Test
    fun `Issue 267 Just Lift warmup to working rep transitions without failed stall state`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 20f,
                progressionRegressionKg = 0f,
                stallDetectionEnabled = true,
                isAMRAP = false,
                isJustLift = true,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        completeWarmupReps(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        val afterWarmup = harness.dwsm.coordinator.repCount.value
        assertTrue(afterWarmup.isWarmupComplete)
        assertEquals(0, afterWarmup.workingReps)

        completeFirstWorkingRep(harness, warmupTarget = 3, workingTarget = 8)
        advanceUntilIdle()

        val afterWorkingRep = harness.dwsm.coordinator.repCount.value
        assertEquals(1, afterWorkingRep.workingReps)
        assertFalse(afterWorkingRep.hasPendingRep)
        assertEquals(null, harness.dwsm.coordinator.stallStartTime)
        assertFalse(harness.dwsm.coordinator.isCurrentlyStalled)
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
        harness.cleanup()
    }

    @Test
    fun `set summary volume uses configured load for fixed weight workouts`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
            metrics = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 22f,
                    loadB = 26f,
                    positionA = 120f,
                    positionB = 120f,
                    velocityA = 80.0,
                    velocityB = 80.0,
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 18f,
                    loadB = 20f,
                    positionA = 100f,
                    positionB = 100f,
                    velocityA = -60.0,
                    velocityB = -60.0,
                ),
            ),
            repCount = 8,
            fallbackWeightKg = 12f,
            configuredWeightKgPerCable = 12f,
            isEchoMode = false,
        )

        assertEquals(24f, summary.heaviestLiftKgPerCable)
        assertEquals(192f, summary.totalVolumeKg)
        assertEquals(2, summary.cableCount)
        harness.cleanup()
    }

    @Test
    fun `set summary honors unilateral cable hint when inactive side has noisy load`() = runTest {
        val harness = DWSMTestHarness(this)

        val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
            metrics = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 30f,
                    loadB = 10f,
                    positionA = 180f,
                    positionB = 110f,
                    velocityA = 90.0,
                    velocityB = 45.0,
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 24f,
                    loadB = 8f,
                    positionA = 120f,
                    positionB = 90f,
                    velocityA = -70.0,
                    velocityB = -30.0,
                ),
            ),
            repCount = 10,
            fallbackWeightKg = 30f,
            configuredWeightKgPerCable = 30f,
            isEchoMode = false,
            cableCountHint = 1,
        )

        assertEquals(30f, summary.heaviestLiftKgPerCable)
        assertEquals(300f, summary.totalVolumeKg)
        assertEquals(1, summary.cableCount)
        harness.cleanup()
    }

    @Test
    fun `Issue 358 - calorie calculation caps position deltas to prevent BLE glitch inflation`() = runTest {
        val harness = DWSMTestHarness(this)

        // Create metrics with a HUGE position jump (500mm) that simulates a BLE glitch.
        // Without the fix, this would inflate calories by ~25x.
        // With the fix, the delta is capped to 20mm (POSITION_JUMP_THRESHOLD).
        val summary = harness.activeSessionEngine.calculateSetSummaryMetrics(
            metrics = listOf(
                WorkoutMetric(
                    timestamp = 100L,
                    loadA = 80f,
                    loadB = 80f,
                    positionA = 100f, // Starting position
                    positionB = 100f,
                    velocityA = 50.0,
                    velocityB = 50.0,
                ),
                WorkoutMetric(
                    timestamp = 200L,
                    loadA = 80f,
                    loadB = 80f,
                    positionA = 600f, // HUGE 500mm jump - should be capped to 20mm
                    positionB = 600f,
                    velocityA = 50.0,
                    velocityB = 50.0,
                ),
                WorkoutMetric(
                    timestamp = 300L,
                    loadA = 80f,
                    loadB = 80f,
                    positionA = 620f, // Normal 20mm movement
                    positionB = 620f,
                    velocityA = -50.0,
                    velocityB = -50.0,
                ),
            ),
            repCount = 1,
            fallbackWeightKg = 80f,
            configuredWeightKgPerCable = 80f,
            isEchoMode = false,
        )

        // Expected calories with capped deltas:
        // Force = 160kg * 9.81 = 1569.6N
        // Delta per pair = 20mm (capped) = 0.02m
        // 2 pairs = 0.04m total movement
        // Work = 1569.6 * 0.04 = 62.8 J
        // Calories = (62.8 / 4184) * 5 = ~0.075 kcal, coerced to min 1.0
        //
        // WITHOUT the fix (uncapped 500mm jump):
        // Delta pair 1 = 500mm = 0.5m
        // Delta pair 2 = 20mm = 0.02m
        // Total = 0.52m
        // Work = 1569.6 * 0.52 = 816 J
        // Calories = (816 / 4184) * 5 = ~0.98 kcal
        //
        // The fix ensures calories are bounded by capping deltas.
        assertTrue(
            summary.estimatedCalories <= 2f,
            "Calorie estimate should be bounded when position deltas are capped. " +
                "Got: ${summary.estimatedCalories}",
        )

        harness.cleanup()
    }

    // ===== F. saveWorkoutSession side effects =====

    @Test
    fun `auto completed fixed weight session records achieved load for weight PR and configured load for volume PR`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 8,
                warmupReps = 0,
                weightPerCableKg = 12f,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 8,
            totalReps = 8,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 22f,
                loadB = 26f,
                positionA = 120f,
                positionB = 120f,
                velocityA = 80.0,
                velocityB = 80.0,
            ),
            WorkoutMetric(
                timestamp = 200L,
                loadA = 18f,
                loadB = 20f,
                positionA = 100f,
                positionB = 100f,
                velocityA = -60.0,
                velocityB = -60.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion(

            harness.activeSessionEngine.currentExecutionLeaseForTest(),

            com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

        )
        advanceUntilIdle()

        val prUpdate = harness.fakePRRepo.updateCalls.single()
        assertEquals(24f, prUpdate.weightPRWeightPerCableKg)
        assertEquals(12f, prUpdate.volumePRWeightPerCableKg)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(24f, session.heaviestLiftKg)
        assertEquals(192f, session.totalVolumeKg)
        assertEquals(2, session.cableCount)
        harness.cleanup()
    }

    @Test
    fun `fixed weight unilateral session does not double volume when selected exercise metadata is explicit`() = runTest {
        val harness = DWSMTestHarness(this)
        val singleCableRow = Exercise(
            name = "Bent Over Row (SC)",
            muscleGroup = "Back",
            muscleGroups = "Back,Biceps",
            equipment = "HANDLES",
            id = "single-row-001",
            cableIntent = ExerciseCableIntent.SINGLE,
        )

        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeExerciseRepo.addExercise(singleCableRow)

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 30f,
                selectedExerciseId = singleCableRow.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 30f,
                loadB = 10f,
                positionA = 180f,
                positionB = 120f,
                velocityA = 90.0,
                velocityB = 45.0,
            ),
            WorkoutMetric(
                timestamp = 200L,
                loadA = 25f,
                loadB = 9f,
                positionA = 130f,
                positionB = 100f,
                velocityA = -70.0,
                velocityB = -25.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion(

            harness.activeSessionEngine.currentExecutionLeaseForTest(),

            com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

        )
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(30f, session.heaviestLiftKg)
        assertEquals(300f, session.totalVolumeKg)
        assertEquals(1, session.cableCount)
        harness.cleanup()
    }

    @Test
    fun `fixed weight session uses measured peak to beat existing weight PR in normalized mode bucket`() = runTest {
        val harness = DWSMTestHarness(this)
        val deadliftId = requireNotNull(TestFixtures.deadlift.id)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeExerciseRepo.addExercise(TestFixtures.deadlift)
        harness.fakePRRepo.addRecord(
            PersonalRecord(
                id = 1L,
                exerciseId = deadliftId,
                exerciseName = "Conventional Deadlift",
                weightPerCableKg = 55.73f,
                reps = 10,
                oneRepMax = 74.31f,
                timestamp = currentTimeMillis() - 10_000L,
                workoutMode = "OldSchool",
                prType = PRType.MAX_WEIGHT,
                volume = 557.3f,
            ),
        )

        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 50f,
                selectedExerciseId = deadliftId,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.dwsm.coordinator.collectedMetrics.value = listOf(
            WorkoutMetric(
                timestamp = 100L,
                loadA = 60f,
                loadB = 60f,
                positionA = 120f,
                positionB = 120f,
                velocityA = 80.0,
                velocityB = 80.0,
            ),
            WorkoutMetric(
                timestamp = 200L,
                loadA = 54f,
                loadB = 56f,
                positionA = 90f,
                positionB = 90f,
                velocityA = -60.0,
                velocityB = -60.0,
            ),
        )

        harness.activeSessionEngine.handleSetCompletion(

            harness.activeSessionEngine.currentExecutionLeaseForTest(),

            com.devil.phoenixproject.domain.model.SetEndReason.TARGET_REPS_REACHED,

        )
        advanceUntilIdle()

        val prUpdate = harness.fakePRRepo.updateCalls.single()
        assertEquals(60f, prUpdate.weightPRWeightPerCableKg)
        assertEquals(50f, prUpdate.volumePRWeightPerCableKg)

        val updatedPr = harness.fakePRRepo.getWeightPR(deadliftId, "Old School", "default")
        assertEquals(60f, updatedPr?.weightPerCableKg)
        assertEquals(updatedPr?.id, harness.fakePRRepo.getWeightPR(deadliftId, "OldSchool", "default")?.id)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(60f, session.heaviestLiftKg)
        harness.cleanup()
    }

    @Test
    fun `stopWorkout saves session to workout repository`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        // Check that a session was saved to the fake workout repository
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()

        // Characterization: stopWorkout always saves a session even with 0 reps
        assertTrue(
            sessions.isNotEmpty(),
            "stopWorkout should save a workout session to the repository",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout with exitingWorkout true also saves session`() = runTest {
        val harness = activeDWSM()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        // Verify session was saved even when exiting
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()

        // Characterization: stopWorkout(exitingWorkout=true) saves session THEN sets Idle
        assertTrue(
            sessions.isNotEmpty(),
            "stopWorkout(exitingWorkout=true) should still save a session before going to Idle",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout in routine flow saves session with routine metadata`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertTrue(
            session.routineSessionId?.isNotBlank() == true,
            "Routine workout sessions should include a non-empty routineSessionId",
        )
        assertEquals(
            routine.name,
            session.routineName,
            "Routine workout sessions should include the routine name",
        )
        harness.cleanup()
    }

    @Test
    fun `stopWorkout in temp single-exercise flow keeps routine metadata null`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        val tempRoutine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1).copy(
            id = "${DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX}test",
        )
        tempRoutine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(tempRoutine)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.dwsm.stopWorkout(exitingWorkout = false)
        advanceUntilIdle()

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().first()
        assertEquals(
            null,
            session.routineSessionId,
            "Single-exercise temp routines should not set routineSessionId",
        )
        assertEquals(
            null,
            session.routineName,
            "Single-exercise temp routines should not set routineName",
        )
        harness.cleanup()
    }

    @Test
    fun `gamification skips invalid completions with no working reps`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeGamificationRepo.pendingBadges += Badge(
            id = "test_invalid_completion",
            name = "Invalid Completion",
            description = "Should not be awarded for skipped sets",
            category = BadgeCategory.DEDICATION,
            iconResource = "test",
            tier = BadgeTier.BRONZE,
            requirement = BadgeRequirement.TotalWorkouts(1),
        )

        val result = harness.gamificationManager.processPostSaveEvents(
            exerciseId = TestFixtures.benchPress.id,
            workingReps = 0,
            achievedWeightKg = 50f,
            volumeWeightKg = 50f,
            programMode = ProgramMode.OldSchool,
            isJustLift = false,
            isEchoMode = false,
        )

        assertFalse(result)
        assertEquals(0, harness.fakePRRepo.updateCalls.size, "Invalid completions should not update PRs")
        assertEquals(0, harness.fakeGamificationRepo.updateStatsCallCount, "Invalid completions should not update gamification stats")
        assertEquals(0, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount, "Invalid completions should not award badges")
        harness.cleanup()
    }

    @Test
    fun `gamification updates stats for valid completions without exercise id`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeGamificationRepo.pendingBadges += Badge(
            id = "test_untagged_completion",
            name = "Untagged Completion",
            description = "Should be awarded for a valid untagged workout",
            category = BadgeCategory.DEDICATION,
            iconResource = "test",
            tier = BadgeTier.BRONZE,
            requirement = BadgeRequirement.TotalWorkouts(1),
        )

        val result = harness.gamificationManager.processPostSaveEvents(
            exerciseId = null,
            workingReps = 8,
            achievedWeightKg = 50f,
            volumeWeightKg = 50f,
            programMode = ProgramMode.OldSchool,
            isJustLift = false,
            isEchoMode = false,
        )

        assertFalse(result)
        assertEquals(0, harness.fakePRRepo.updateCalls.size, "Untagged completions should not update PRs")
        assertEquals(1, harness.fakeGamificationRepo.updateStatsCallCount, "Valid untagged completions should update gamification stats")
        assertEquals(1, harness.fakeGamificationRepo.checkAndAwardBadgesCallCount, "Valid untagged completions should award stat badges")
        harness.cleanup()
    }

    private suspend fun completeWarmupReps(harness: DWSMTestHarness, warmupTarget: Int = 3, workingTarget: Int = 8) {
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f,
        )

        for (warmupRep in 1..warmupTarget) {
            harness.fakeBleRepo.emitMetric(activeMetric)
            harness.fakeBleRepo.emitRepNotification(
                RepNotification(
                    topCounter = warmupRep,
                    completeCounter = warmupRep,
                    repsRomCount = warmupRep,
                    repsRomTotal = warmupTarget,
                    repsSetCount = 0,
                    repsSetTotal = workingTarget,
                    rangeTop = 800f,
                    rangeBottom = 0f,
                    rawData = ByteArray(24),
                    timestamp = harness.nowMs + warmupRep,
                ),
            )
        }
    }

    // ===== Issue #427: Timed bodyweight reps and variant selection =====

    @Test
    fun `Issue 427 - timed bodyweight set prompts for reps instead of saving zero volume`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 3, repsPerSet = 10, durationSeconds = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        advanceTimeBy(1_100)
        runCurrent()

        val entry = assertIs<WorkoutState.BodyweightRepEntry>(
            harness.dwsm.coordinator.workoutState.value,
            "Timed bodyweight completion should ask for performed reps before saving",
        )
        assertEquals(10, entry.plannedReps)
        assertEquals("Standard Push-Up", entry.selectedVariant.label)
        assertEquals(0, harness.fakeWorkoutRepo.getAllSessions("default").first().size)

        harness.cleanup()
    }

    @Test
    fun `Issue 427 - untimed bodyweight set prompts for manual reps after 30s fallback timer - Fixes 593`() = runTest {
        // Issue #593 regression: pre-fix, an untimed routine-bodyweight
        // set fell through `handleSetCompletion` to `saveWorkoutSession()`
        // with `workingReps=0`, causing Analytics to drop the entire
        // routine while Recent Activity showed misleading "0 reps" rows.
        // The fix extends the rep-entry gate to any routine-bodyweight
        // set whose reps have not been confirmed, so the user now
        // gets a rep-entry prompt even when `RoutineExercise.duration`
        // is null or 0. The 30s fallback timer still auto-completes
        // the set; the prompt fires afterwards.
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 0)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Drive the 30s fallback timer to completion; the rep-entry
        // prompt must fire on the routine-bodyweight path.
        advanceTimeBy(30_100)
        runCurrent()

        val state = harness.dwsm.coordinator.workoutState.value
        assertTrue(
            state is WorkoutState.BodyweightRepEntry,
            "Issue #593 fix: untimed bodyweight completion must prompt for manual reps. Got: $state",
        )
        assertEquals(
            0,
            harness.fakeWorkoutRepo.getAllSessions("default").first().size,
            "No session may be persisted before the user confirms reps",
        )

        harness.cleanup()
    }

    @Test
    fun `Issue 427 - confirming bodyweight reps saves selected variant volume`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 3, repsPerSet = 10, durationSeconds = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceTimeBy(1_100)
        runCurrent()

        val entry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
        val decline18 = entry.variants.first { it.label == "Decline 18\"" }

        harness.dwsm.confirmBodyweightSetResult(reps = 12, variant = decline18)
        advanceTimeBy(1_000)
        runCurrent()

        val summary = assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "Confirmed bodyweight reps should use the normal summary flow",
        )
        assertEquals(12, summary.repCount)
        assertEquals(12, summary.workingReps)
        assertFloatEquals(58.4f, summary.heaviestLiftKgPerCable)
        assertFloatEquals(700.8f, summary.totalVolumeKg)

        val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
        assertEquals(12, session.workingReps)
        assertFloatEquals(58.4f, session.heaviestLiftKg ?: 0f)
        assertFloatEquals(700.8f, session.totalVolumeKg ?: 0f)

        val completedSet = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
        assertEquals(12, completedSet.actualReps)
        assertFloatEquals(58.4f, completedSet.actualWeightKg)

        harness.cleanup()
    }

    @Test
    fun `Issue 427 - saved SetReady variant is used when bodyweight completion has no explicit override`() = runTest {
        val harness = DWSMTestHarness(this)
        val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 1)
        val exercise = routine.exercises.single()
        val decline24 = BodyweightVariantOption("Decline 24\"", 0.75f)

        harness.dwsm.selectBodyweightVariant(
            exerciseKey = harness.dwsm.bodyweightVariantKey(exercise),
            variant = decline24,
        )

        val summary = WorkoutState.SetSummary(
            metrics = emptyList(),
            peakLoadKgPerCable = 0f,
            avgLoadKgPerCable = 0f,
            repCount = 10,
        )
        val bodyweightSummary = harness.activeSessionEngine.applyBodyweightVolume(
            summary = summary,
            currentExercise = exercise,
            bodyWeightKg = 80f,
        )

        assertFloatEquals(60f, bodyweightSummary.heaviestLiftKgPerCable)
        assertFloatEquals(600f, bodyweightSummary.totalVolumeKg)

        harness.cleanup()
    }

    @Test
    fun `Issue 427 - bodyweight variant carries into later set prompt`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 3, repsPerSet = 10, durationSeconds = 1)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceTimeBy(1_100)
        runCurrent()

        val firstEntry = assertIs<WorkoutState.BodyweightRepEntry>(harness.dwsm.coordinator.workoutState.value)
        val decline18 = firstEntry.variants.first { it.label == "Decline 18\"" }
        harness.dwsm.confirmBodyweightSetResult(reps = 12, variant = decline18)
        advanceTimeBy(1_000)
        runCurrent()

        harness.dwsm.enterSetReady(0, 1)
        runCurrent()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceTimeBy(1_100)
        runCurrent()

        val secondEntry = assertIs<WorkoutState.BodyweightRepEntry>(
            harness.dwsm.coordinator.workoutState.value,
            "Later timed bodyweight sets should prompt again",
        )
        assertEquals(2, secondEntry.currentSet)
        assertEquals("Decline 18\"", secondEntry.selectedVariant.label)
        assertEquals(0.73f, secondEntry.selectedVariant.percentage)

        harness.cleanup()
    }

    @Test
    fun `Issue 490 - timed bodyweight set emits final countdown ticks`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 12)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        val ticks = mutableListOf<Int>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                if (event is HapticEvent.COUNTDOWN_TICK) {
                    ticks.add(event.secondsRemaining)
                }
            }
        }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()

        advanceTimeBy(12_100)
        runCurrent()

        assertEquals((10 downTo 1).toList(), ticks)

        hapticJob.cancel()
        harness.cleanup()
    }

    @Test
    fun `Issue 490 - short timed bodyweight set includes initial countdown tick`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 10)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        val ticks = mutableListOf<Int>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                if (event is HapticEvent.COUNTDOWN_TICK) {
                    ticks.add(event.secondsRemaining)
                }
            }
        }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()

        advanceTimeBy(10_100)
        runCurrent()

        assertEquals((10 downTo 1).toList(), ticks)

        hapticJob.cancel()
        harness.cleanup()
    }

    @Test
    fun `Issue 490 - short timed cable set includes initial countdown tick`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTimedCableRoutine(durationSeconds = 10)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        val ticks = mutableListOf<Int>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                if (event is HapticEvent.COUNTDOWN_TICK) {
                    ticks.add(event.secondsRemaining)
                }
            }
        }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()

        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )
        runCurrent()
        advanceTimeBy(10_100)
        runCurrent()

        assertEquals((10 downTo 1).toList(), ticks)

        hapticJob.cancel()
        harness.cleanup()
    }

    @Test
    fun `Issue 490 - disabled countdown beeps suppress timed bodyweight ticks`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveBodyWeightKg(80f)
        harness.setActiveCountdownBeepsEnabled(false)
        val routine = createBodyweightRoutine(sets = 1, repsPerSet = 10, durationSeconds = 12)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        val ticks = mutableListOf<Int>()
        val hapticJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.dwsm.coordinator.hapticEvents.collect { event ->
                if (event is HapticEvent.COUNTDOWN_TICK) {
                    ticks.add(event.secondsRemaining)
                }
            }
        }

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        runCurrent()

        advanceTimeBy(12_100)
        runCurrent()

        assertEquals(emptyList(), ticks)

        hapticJob.cancel()
        harness.cleanup()
    }

    // ===== Issue #320: Stall/stop saves partial reps in routine mode =====

    @Test
    fun `Issue 320 - routine set auto-advances from summary to rest timer after stall`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 3,
            repsPerSet = 10,
            weightKg = 25f,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        // Start the first set
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Simulate 5 completed working reps (partial set)
        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 5,
            totalReps = 5,
            isWarmupComplete = true,
        )

        // Trigger handleSetCompletion (stall auto-stop path)
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            com.devil.phoenixproject.domain.model.SetEndReason.STALL_FAILURE,
        )
        // Use advanceTimeBy (not advanceUntilIdle) — the handleSetCompletion coroutine
        // does delay(summaryDelayMs) then startRestTimer() which has an infinite tick loop.
        // 1s is enough for BLE stop + session save + summary state transition.
        advanceTimeBy(1_000)

        // Summary should be shown first
        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "Issue #320: Set should show summary with partial reps after stall completion",
        )

        // Advance past summary countdown (default 10s) into rest timer territory
        advanceTimeBy(11_000)

        // Should have auto-advanced to Resting (rest timer between sets)
        assertIs<WorkoutState.Resting>(
            harness.dwsm.coordinator.workoutState.value,
            "Issue #320: Routine set should auto-advance from summary to rest timer",
        )

        // Verify session was saved with the partial reps
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()
        assertTrue(sessions.isNotEmpty(), "Session should have been saved")

        harness.cleanup()
    }

    @Test
    fun `routine stall persists its captured normal plan before resting`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

        harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
        advanceTimeBy(11_000)

        val plan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
        assertEquals(sourceLease.executionId.toString(), plan.sourceExecutionId)
        assertEquals(plan, harness.fakeActiveWorkoutRuntimeRepository.replacements.first().document.restTransitionPlan)
        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

        harness.cleanup()
    }

    @Test
    fun `eligible routine stall persists unresolved offer before resting without advancing`() = runTest {
        val harness = DWSMTestHarness(
            this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = { DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f) },
            transitionIdGenerator = { "transition-stall" },
            offerIdGenerator = { "offer-stall" },
        )
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        val sourceParameters = harness.coordinator.workoutParameters.value

        harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
        advanceTimeBy(11_000)

        val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
        assertEquals("transition-stall", unresolved.transitionId)
        assertEquals("offer-stall", unresolved.offerId)
        assertEquals(unresolved, harness.fakeActiveWorkoutRuntimeRepository.replacements.first().document.restTransitionPlan)
        assertEquals(0, harness.coordinator.currentExerciseIndex.value)
        assertEquals(0, harness.coordinator.currentSetIndex.value)
        assertEquals(sourceParameters.weightPerCableKg, harness.coordinator.workoutParameters.value.weightPerCableKg)
        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

        harness.cleanup()
    }

    @Test
    fun `manual summary progression cannot bypass an eligible stall offer`() = runTest {
        val harness = DWSMTestHarness(
            this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = { DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f) },
            transitionIdGenerator = { "transition-manual" },
            offerIdGenerator = { "offer-manual" },
        )
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(1_000)
            if (harness.coordinator.workoutState.value is WorkoutState.SetSummary) {
                harness.dwsm.proceedFromSummary()
            }
            advanceTimeBy(1_000)

            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `autoplay off routine normal completion durably dispatches and preserves summary bookkeeping`() = runTest {
        val harness = DWSMTestHarness(this)
        val observedStates = mutableListOf<WorkoutState>()
        val stateObserver = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.workoutState.collect { observedStates += it }
        }
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 8,
                totalReps = 8,
                isWarmupComplete = true,
            )
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            harness.coordinator._currentSetRpe.value = 8

            harness.dwsm.proceedFromSummary()
            advanceUntilIdle()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)
            assertFalse(observedStates.any { it is WorkoutState.Resting })
            assertTrue(0 in harness.coordinator._completedExercises.value)
            assertFalse(0 in harness.coordinator._skippedExercises.value)
            assertEquals(null, harness.coordinator._currentSetRpe.value)
            assertIs<RoutineFlowState.Complete>(harness.coordinator.routineFlowState.value)
        } finally {
            stateObserver.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun `accepting an offer persists accepted retry without a new lease or BLE command`() = runTest {
        val harness = DWSMTestHarness(
            this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = { DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f) },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val commandsBeforeAcceptance = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.applyRestTransition(
                RestTransitionCommand.Accept(
                    identity = unresolved.actionIdentity(),
                    percentage = unresolved.candidates.first().percentage,
                ),
            )
            runCurrent()

            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertEquals(unresolved.transitionId, accepted.transitionId)
            assertEquals(sourceLease.executionId, harness.activeSessionEngine.currentExecutionLeaseForTest().executionId)
            assertEquals(commandsBeforeAcceptance, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(1, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.attemptStates.single().acceptedDropCount)
            assertEquals(accepted, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `structured rest command returns its durable outcome and publishes the plan flow`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)

            val outcome = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )

            val changed = assertIs<RestTransitionReduction.Changed>(outcome)
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(changed.plan)
            assertEquals(accepted, harness.dwsm.restTransitionPlan.value)
            assertEquals(accepted, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `declining an offer persists the decision but leaves the captured rest pending`() = runTest {
        val harness = DWSMTestHarness(
            this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = { DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f) },
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)

            harness.dwsm.applyRestTransition(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()

            assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `accepted retry ignores legacy skip and a racing decline`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            val replacementsBeforeDecision = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.applyRestTransition(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            harness.dwsm.applyRestTransition(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            val accepted = assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)

            harness.dwsm.skipRest()
            runCurrent()

            assertEquals(accepted, harness.restTransitionPlan.value)
            assertEquals(replacementsBeforeDecision + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `plan owned rest extension persists before the visible timer changes`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)
            assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            harness.dwsm.toggleRestPause()
            runCurrent()
            val originalDuration = harness.coordinator.restOriginalDuration.value
            val originalRemaining = harness.coordinator._restSecondsRemaining.value

            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.dwsm.extendRestTime(15)
            runCurrent()

            assertEquals(originalDuration, harness.coordinator.restOriginalDuration.value)
            assertEquals(originalRemaining, harness.coordinator._restSecondsRemaining.value)

            harness.dwsm.extendRestTime(15)
            runCurrent()
            assertEquals(originalDuration + 15, harness.coordinator.restOriginalDuration.value)
            assertEquals(
                originalDuration + 15,
                harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.originalRestDurationSeconds,
            )
            assertEquals(
                originalDuration + 15,
                assertIs<RestTransitionPlan.NormalAdvance>(
                    harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan,
                ).restDurationSeconds,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `plan owned pause resume and reset persist before each visible timer change`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareNormalRest(harness)

            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.dwsm.toggleRestPause()
            runCurrent()
            assertFalse(harness.coordinator.isRestPaused.value)

            harness.dwsm.toggleRestPause()
            runCurrent()
            assertTrue(harness.coordinator.isRestPaused.value)
            assertTrue(harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.isRestPaused)

            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.dwsm.toggleRestPause()
            runCurrent()
            assertTrue(harness.coordinator.isRestPaused.value)

            harness.dwsm.toggleRestPause()
            runCurrent()
            assertFalse(harness.coordinator.isRestPaused.value)
            assertFalse(harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.isRestPaused)

            harness.dwsm.toggleRestPause()
            runCurrent()
            val pausedRemaining = harness.coordinator._restSecondsRemaining.value
            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.dwsm.resetRestTimer()
            runCurrent()
            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(pausedRemaining, harness.coordinator._restSecondsRemaining.value)

            harness.dwsm.resetRestTimer()
            runCurrent()
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(
                harness.coordinator.restOriginalDuration.value,
                harness.coordinator._restSecondsRemaining.value,
            )
            assertFalse(harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.isRestPaused)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.pausedRestRemainingSeconds)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `rest command propagates repository cancellation without publishing a decision`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace =
                CancellationException("rest-command-cancelled")

            assertFailsWith<CancellationException> {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
                )
            }

            assertEquals(unresolved, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `plan owned rest action does not publish its extension when repository cancellation stops mutation`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareNormalRest(harness)
            val originalDuration = harness.coordinator.restOriginalDuration.value
            val originalPaused = harness.coordinator.isRestPaused.value
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace =
                CancellationException("rest-timer-cancelled")

            harness.dwsm.extendRestTime(15)
            runCurrent()

            assertEquals(originalDuration, harness.coordinator.restOriginalDuration.value)
            assertEquals(originalPaused, harness.coordinator.isRestPaused.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `legacy skip fails closed for unresolved rest plans`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)

            harness.dwsm.skipRest()
            runCurrent()

            assertEquals(unresolved, harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `identity skip clears a declined plan durably before advancing once`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.dwsm.applyRestTransition(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val replacementCountBeforeSkip = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(declined.actionIdentity()))
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)
            assertEquals(replacementCountBeforeSkip + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation after durable normal clear fails closed before successor dispatch`() = runTest {
        val harness = DWSMTestHarness(this)
        val clearCommitted = CompletableDeferred<Unit>()
        val releaseCommittedClear = CompletableDeferred<Unit>()
        try {
            val normal = prepareNormalRest(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val key = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { document ->
                if (document.restTransitionPlan == null) {
                    clearCommitted.complete(Unit)
                    releaseCommittedClear.await()
                }
            }

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()
            assertTrue(clearCommitted.isCompleted)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(key.profileId, key.routineSessionId)
                    ?.restTransitionPlan,
            )
            assertEquals(normal, harness.restTransitionPlan.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)

            harness.dwsm.resetForNewWorkout()
            releaseCommittedClear.complete(Unit)
            runCurrent()

            assertFalse(harness.activeSessionEngine.isCurrentExecution(lease))
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(key.profileId, key.routineSessionId)
                    ?.restTransitionPlan,
            )
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            if (!releaseCommittedClear.isCompleted) releaseCommittedClear.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `cancellation in the post clear pre side effect window fails closed`() = runTest {
        val harness = DWSMTestHarness(this)
        val enteredPostClearWindow = CompletableDeferred<Unit>()
        val releasePostClearWindow = CompletableDeferred<Unit>()
        try {
            val normal = prepareNormalRest(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.afterDurableRestPlanClearForTest = {
                enteredPostClearWindow.complete(Unit)
                releasePostClearWindow.await()
            }

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()
            assertTrue(enteredPostClearWindow.isCompleted)
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)

            harness.dwsm.resetForNewWorkout()
            releasePostClearWindow.complete(Unit)
            runCurrent()

            assertFalse(harness.activeSessionEngine.isCurrentExecution(lease))
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.activeSessionEngine.afterDurableRestPlanClearForTest = null
            if (!releasePostClearWindow.isCompleted) releasePostClearWindow.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `legacy skip retains plain normal rest compatibility through a durable clear`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val normal = prepareNormalRest(harness)
            val replacementCountBeforeSkip = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.skipRest()
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)
            assertEquals(replacementCountBeforeSkip + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `legacy start next set at zero fails closed for declined rest`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val lastCommittedKey = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            val persistedBeforeCallback = requireNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    lastCommittedKey.profileId,
                    lastCommittedKey.routineSessionId,
                ),
            )
            val replacementCountBeforeCallback = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val lookupCountBeforeCallback = harness.dwsm.restTransitionNavigationLookupsForTest
            val sourceCoordinates = harness.coordinator.currentExerciseIndex.value to harness.coordinator.currentSetIndex.value
            harness.coordinator._workoutState.value = WorkoutState.Resting(
                restSecondsRemaining = 0,
                nextExerciseName = "",
                isLastExercise = false,
                currentSet = 1,
                totalSets = 2,
            )

            harness.dwsm.startNextSet()
            runCurrent()

            assertEquals(declined, harness.restTransitionPlan.value)
            assertEquals(
                persistedBeforeCallback,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    lastCommittedKey.profileId,
                    lastCommittedKey.routineSessionId,
                ),
            )
            assertEquals(replacementCountBeforeCallback, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(lookupCountBeforeCallback, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(sourceCoordinates.first, harness.coordinator.currentExerciseIndex.value)
            assertEquals(sourceCoordinates.second, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `legacy start next set at zero consumes a plain normal rest once`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareNormalRest(harness)
            val replacementCountBeforeCallback = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            harness.coordinator._workoutState.value = WorkoutState.Resting(
                restSecondsRemaining = 0,
                nextExerciseName = "",
                isLastExercise = false,
                currentSet = 1,
                totalSets = 2,
            )

            harness.dwsm.startNextSet()
            runCurrent()
            harness.dwsm.startNextSet()
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            val lastCommittedKey = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(lastCommittedKey.profileId, lastCommittedKey.routineSessionId)
                    ?.restTransitionPlan,
            )
            assertEquals(replacementCountBeforeCallback + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `blocked teardown still publishes a durable unresolved decision without a retry`() = runTest {
        val harness = enabledDropSetHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.STALL_FAILURE)
            runCurrent()

            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(sourceLease.executionId, harness.activeSessionEngine.currentExecutionLeaseForTest().executionId)
            val replacements = harness.fakeActiveWorkoutRuntimeRepository.replacements
            assertEquals(2, replacements.size)
            assertTrue(replacements.all { it.document.restTransitionPlan == harness.restTransitionPlan.value })
            assertEquals(null, replacements.first().document.restDeadlineEpochMs)
            assertNotNull(replacements.last().document.restDeadlineEpochMs)
        } finally {
            teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `teardown readiness never overwrites an unresolved resting offer`() = runTest {
        val harness = enabledDropSetHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            teardownBarrier.complete(Result.success(Unit))
            runCurrent()

            assertEquals(unresolved, harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `runtime installation and teardown progress independently with one completion job`() = runTest {
        val harness = enabledDropSetHarness(this)
        val persistenceBarrier = CompletableDeferred<Unit>()
        try {
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = { persistenceBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()

            assertEquals(1, harness.activeSessionEngine.completionJobAttachCountForTest)
            assertEquals(1, harness.fakeBleRepo.stopWorkoutCallCount)
            assertEquals(null, harness.restTransitionPlan.value)

            persistenceBarrier.complete(Unit)
            runCurrent()

            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            if (!persistenceBarrier.isCompleted) persistenceBarrier.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `unresolved action persists while teardown is not ready without retry effects`() = runTest {
        val harness = enabledDropSetHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val commandsBeforeAccept = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.applyRestTransition(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            runCurrent()

            assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertEquals(lease, harness.activeSessionEngine.currentExecutionLeaseForTest())
            assertEquals(commandsBeforeAccept, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(1, harness.activeSessionEngine.completionJobAttachCountForTest)

            teardownBarrier.complete(Result.success(Unit))
            runCurrent()

            assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `declining while teardown is blocked remains resting after ready`() = runTest {
        val harness = enabledDropSetHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)

            harness.dwsm.applyRestTransition(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)

            teardownBarrier.complete(Result.success(Unit))
            runCurrent()

            assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `successful teardown retry resumes the original completion waiter`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("initial RESET failed"))
            }
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()

            assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.SetSummary)

            harness.fakeBleRepo.stopWorkoutBlock = { Result.success(Unit) }
            harness.dwsm.retryMachineTeardown()
            runCurrent()

            assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(1, harness.activeSessionEngine.completionJobAttachCountForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reset failure retains the durable unresolved resting decision`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeBleRepo.stopWorkoutBlock = {
                Result.failure(IllegalStateException("RESET failed"))
            }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(1_000)

            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertIs<MachineTeardownState.RecoveryRequired>(harness.dwsm.machineTeardownState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `reset invalidation prevents a blocked stale installation from publishing`() = runTest {
        val harness = enabledDropSetHarness(this)
        val persistenceBarrier = CompletableDeferred<Unit>()
        try {
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = {
                withContext(NonCancellable) { persistenceBarrier.await() }
            }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            harness.dwsm.resetForNewWorkout()
            runCurrent()
            persistenceBarrier.complete(Unit)
            advanceUntilIdle()

            assertEquals(null, harness.restTransitionPlan.value)
            assertFalse(harness.activeSessionEngine.isCurrentExecution(lease))
            val staleDocument = harness.fakeActiveWorkoutRuntimeRepository.replacements.single().document
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    staleDocument.profileId,
                    staleDocument.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
        } finally {
            if (!persistenceBarrier.isCompleted) persistenceBarrier.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `replacement execution B wins when stale A installation resumes`() = runTest {
        val harness = enabledDropSetHarness(this)
        val persistenceBarrier = CompletableDeferred<Unit>()
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = { persistenceBarrier.await() }
            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.STALL_FAILURE)
            runCurrent()
            advanceTimeBy(1_000)

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(leaseB.executionId != leaseA.executionId)

            harness.activeSessionEngine.handleSetCompletion(leaseB, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()
            persistenceBarrier.complete(Unit)
            runCurrent()

            assertFalse(harness.activeSessionEngine.isCurrentExecution(leaseA))
            val finalDocument = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertEquals(leaseB.executionId.toString(), finalDocument.sourceExecutionId)
            assertEquals(leaseB.executionId.toString(), assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value).sourceExecutionId)
        } finally {
            if (!persistenceBarrier.isCompleted) persistenceBarrier.complete(Unit)
            harness.cleanup()
        }
    }

    @Test
    fun `zero rest normal transition clears durably before advancing`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f).copy(
                exercises = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f).exercises.map {
                    it.copy(setRestSeconds = listOf(0, 0))
                },
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sourceLease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(sourceLease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `zero rest unresolved remains resting and accepted retry starts after autoplay expiry`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f).let { source ->
                source.copy(exercises = source.exercises.map { it.copy(setRestSeconds = listOf(0, 0)) })
            }
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 3,
                totalReps = 3,
                isWarmupComplete = true,
            )
            harness.fakeCompletedSetRepo.setSessionRoutine(
                lease.sessionId,
                assertNotNull(harness.coordinator.currentRoutineSessionId),
            )

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertEquals(0, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
            val commandsBeforeAccept = harness.fakeBleRepo.commandsReceived.size

            harness.dwsm.applyRestTransition(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(commandsBeforeAccept + 1, harness.fakeBleRepo.commandsReceived.size)
            assertTrue(harness.activeSessionEngine.currentExecutionLeaseForTest().executionId > lease.executionId)
            assertTrue(harness.coordinator.restTimerJob?.isActive != true)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `unresolved and accepted rest do not perform navigation lookups during timer ticks`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            advanceTimeBy(5_000)

            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)

            harness.dwsm.applyRestTransition(
                RestTransitionCommand.Accept(unresolved.actionIdentity(), unresolved.candidates.first().percentage),
            )
            runCurrent()
            advanceTimeBy(5_000)

            assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `normal plan caches an adjacent same exercise successor across repeated skip callbacks`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            val first = source.exercises.single()
            val routine = source.copy(
                exercises = listOf(
                    first.copy(id = "same-exercise-a", setReps = listOf(10), programMode = ProgramMode.OldSchool),
                    first.copy(id = "same-exercise-b", setReps = listOf(10), programMode = ProgramMode.TUT),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()

            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `normal plan caches a superset successor across repeated skip callbacks`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = WorkoutStateFixtures.createSupersetRoutine()
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()

            assertEquals(1, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `eligible final set stall offers rest without final navigation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)

            assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `resolved normal and declined transitions navigate exactly once`() = runTest {
        val normalHarness = DWSMTestHarness(this)
        val declinedHarness = enabledDropSetHarness(this)
        try {
            prepareNormalRest(normalHarness)
            normalHarness.dwsm.skipRest()
            runCurrent()
            assertEquals(1, normalHarness.dwsm.restTransitionNavigationLookupsForTest)

            val unresolved = prepareEligibleStallRest(declinedHarness)
            declinedHarness.dwsm.applyRestTransition(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            declinedHarness.dwsm.applyRestTransition(
                RestTransitionCommand.SkipRest(assertIs<RestTransitionPlan.Declined>(declinedHarness.restTransitionPlan.value).actionIdentity()),
            )
            runCurrent()
            assertEquals(1, declinedHarness.dwsm.restTransitionNavigationLookupsForTest)
        } finally {
            normalHarness.cleanup()
            declinedHarness.cleanup()
        }
    }

    @Test
    fun `decline and teardown resolution share one in flight navigation lookup`() = runTest {
        val harness = enabledDropSetHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        val firstResolverEntered = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        var resolverOwnerCount = 0
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.activeSessionEngine.beforeRestTransitionNavigationResolutionForTest = {
                resolverOwnerCount++
                firstResolverEntered.complete(Unit)
                releaseResolver.await()
            }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val declineJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            }
            firstResolverEntered.await()

            teardownBarrier.complete(Result.success(Unit))
            runCurrent()

            assertEquals(1, resolverOwnerCount)
            releaseResolver.complete(Unit)
            declineJob.join()
            runCurrent()
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.SkipRest(declined.actionIdentity()))
            runCurrent()

            assertEquals(1, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            if (!releaseResolver.isCompleted) releaseResolver.complete(Unit)
            harness.activeSessionEngine.beforeRestTransitionNavigationResolutionForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `cancelling navigation owner after lookup releases followers and permits retry`() = runTest {
        val harness = enabledDropSetHarness(this)
        val resolverPublished = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = {
                resolverPublished.complete(Unit)
                releaseResolver.await()
            }

            val owner = launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            }
            resolverPublished.await()
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val followerFailure = CompletableDeferred<Throwable?>()
            val follower = launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    harness.activeSessionEngine.resolveRestTransitionNavigationForTest(declined.normalAdvance)
                    followerFailure.complete(null)
                } catch (error: Throwable) {
                    followerFailure.complete(error)
                }
            }
            runCurrent()
            owner.cancel()
            runCurrent()

            assertTrue(owner.isCompleted)
            assertTrue(follower.isCompleted)
            assertIs<CancellationException>(followerFailure.await())
            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = null
            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(declined.actionIdentity()))
            runCurrent()

            assertEquals(2, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            if (!releaseResolver.isCompleted) releaseResolver.complete(Unit)
            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `exception after navigation lookup releases followers and permits retry`() = runTest {
        val harness = enabledDropSetHarness(this)
        val resolverPublished = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        val ownerFailure = CompletableDeferred<Throwable>()
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = {
                resolverPublished.complete(Unit)
                releaseResolver.await()
                error("post-lookup failure")
            }

            launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
                } catch (error: Throwable) {
                    ownerFailure.complete(error)
                }
            }
            resolverPublished.await()
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val followerFailure = CompletableDeferred<Throwable?>()
            val follower = launch(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    harness.activeSessionEngine.resolveRestTransitionNavigationForTest(declined.normalAdvance)
                    followerFailure.complete(null)
                } catch (error: Throwable) {
                    followerFailure.complete(error)
                }
            }
            runCurrent()

            releaseResolver.complete(Unit)
            runCurrent()
            assertIs<IllegalStateException>(ownerFailure.await())
            assertTrue(follower.isCompleted)
            assertIs<IllegalStateException>(followerFailure.await())

            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = null
            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(declined.actionIdentity()))
            runCurrent()

            assertEquals(2, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
        } finally {
            if (!releaseResolver.isCompleted) releaseResolver.complete(Unit)
            harness.activeSessionEngine.afterRestTransitionNavigationResolutionForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `queued normal dispatch invalidated before resolution reads no navigation state`() = runTest {
        val harness = DWSMTestHarness(this)
        val resolverScheduled = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        var cacheReads = 0
        var contextReads = 0
        try {
            val normal = prepareNormalRest(harness)
            val lookupsBeforeCommand = harness.dwsm.restTransitionNavigationLookupsForTest
            harness.activeSessionEngine.beforeRestTransitionNavigationClaimForTest = {
                resolverScheduled.complete(Unit)
                releaseResolver.await()
            }
            harness.activeSessionEngine.onRestTransitionNavigationCacheReadForTest = { cacheReads++ }
            harness.activeSessionEngine.onRestTransitionNavigationContextReadForTest = { contextReads++ }

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            resolverScheduled.await()
            harness.dwsm.resetForNewWorkout()
            releaseResolver.complete(Unit)
            runCurrent()

            assertEquals(lookupsBeforeCommand, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(0, cacheReads)
            assertEquals(0, contextReads)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
        } finally {
            if (!releaseResolver.isCompleted) releaseResolver.complete(Unit)
            harness.activeSessionEngine.beforeRestTransitionNavigationClaimForTest = null
            harness.activeSessionEngine.onRestTransitionNavigationCacheReadForTest = null
            harness.activeSessionEngine.onRestTransitionNavigationContextReadForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `final normal transition advances its captured cycle after coordinator replacement`() = runTest {
        val harness = DWSMTestHarness(this)
        val releaseClear = CompletableDeferred<Unit>()
        try {
            val cycleA = trainingCycle("cycle-a")
            val cycleB = trainingCycle("cycle-b")
            harness.fakeTrainingCycleRepo.addCycle(cycleA)
            harness.fakeTrainingCycleRepo.addCycle(cycleB)
            harness.fakeTrainingCycleRepo.setActiveCycle(cycleA.id, "default")
            harness.coordinator.activeCycleId = cycleA.id
            harness.coordinator.activeCycleDayNumber = 1
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 1, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(
                warmupReps = 0,
                workingReps = 8,
                totalReps = 8,
                isWarmupComplete = true,
            )
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            harness.dwsm.proceedFromSummary()
            runCurrent()
            val normal = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = { releaseClear.await() }

            harness.dwsm.applyRestTransition(RestTransitionCommand.SkipRest(normal.actionIdentity()))
            runCurrent()
            assertEquals(null, harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document.restTransitionPlan)

            harness.fakeTrainingCycleRepo.setActiveCycle(cycleB.id, "default")
            harness.coordinator.activeCycleId = cycleB.id
            harness.coordinator.activeCycleDayNumber = 1
            releaseClear.complete(Unit)
            advanceUntilIdle()

            assertEquals(setOf(1), harness.fakeTrainingCycleRepo.getCycleProgress(cycleA.id)?.completedDays)
            assertTrue(harness.fakeTrainingCycleRepo.getCycleProgress(cycleB.id)?.completedDays.orEmpty().isEmpty())
            assertEquals(cycleB.id, harness.coordinator.activeCycleId)
            assertEquals(1, harness.coordinator.activeCycleDayNumber)
        } finally {
            if (!releaseClear.isCompleted) releaseClear.complete(Unit)
            harness.cleanup()
        }
    }

    private suspend fun prepareEligibleStallRest(harness: DWSMTestHarness): RestTransitionPlan.UnresolvedDropOffer {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.advanceUntilIdle()
        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
        harness.testScope.advanceTimeBy(11_000)
        return assertIs(harness.restTransitionPlan.value)
    }

    private suspend fun prepareNormalRest(harness: DWSMTestHarness): RestTransitionPlan.NormalAdvance {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.advanceUntilIdle()
        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        harness.testScope.advanceTimeBy(11_000)
        return assertIs(harness.restTransitionPlan.value)
    }

    private fun enabledDropSetHarness(
        testScope: TestScope,
        afterExecutionBegin: (outgoingExecutionId: Long?, executionId: Long) -> Unit = { _, _ -> },
        completedSetRepositoryOverride: CompletedSetRepository? = null,
        dropSetConfigurationProvider: (RoutineExercise) -> DropSetConfiguration = {
            DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f)
        },
    ) = DWSMTestHarness(
        testScope,
        completedSetRepositoryOverride = completedSetRepositoryOverride,
        afterExecutionBegin = afterExecutionBegin,
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(DropSetFeatureGate { true }, DropSetCandidateResolver()),
        dropSetConfigurationProvider = dropSetConfigurationProvider,
    )

    private fun trainingCycle(id: String) = TrainingCycle.create(
        id = id,
        name = id,
        days = listOf(
            CycleDay.create(id = "$id-day-1", cycleId = id, dayNumber = 1, routineId = "routine-1"),
        ),
    )

    @Test
    fun `initial plan persistence failure is typed sanitized and retries exact identities after cancellation`() = runTest {
        var transitionCalls = 0
        var offerCalls = 0
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        val connectionLogs = ConnectionLogRepository.instance
        val harness = DWSMTestHarness(
            this,
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(
                DropSetFeatureGate { true },
                DropSetCandidateResolver(),
            ),
            dropSetConfigurationProvider = { DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f) },
            transitionIdGenerator = { "transition-${++transitionCalls}" },
            offerIdGenerator = { "offer-${++offerCalls}" },
        )
        try {
            connectionLogs.clearAll()
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()
            val firstAttempt = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertIs<InitialRestPlanInstallResult.PersistenceFailure>(
                harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest,
            )
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(0, harness.coordinator._currentExerciseIndex.value)
            assertEquals(0, harness.coordinator._currentSetIndex.value)
            val diagnostic = connectionLogs.logs.value.single {
                it.eventType == LogEventType.WORKOUT_PERSISTENCE &&
                    it.message == "Rest transition install failed"
            }
            assertEquals("reason=PERSISTENCE_FAILURE", diagnostic.details)

            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            val restDuration = assertNotNull(firstAttempt.restTransitionPlan)
                .let { plan ->
                    when (plan) {
                        is RestTransitionPlan.NormalAdvance -> plan.restDurationSeconds
                        is RestTransitionPlan.UnresolvedDropOffer -> plan.normalAdvance.restDurationSeconds
                        is RestTransitionPlan.Declined -> plan.normalAdvance.restDurationSeconds
                        is RestTransitionPlan.AcceptedRetry -> error("initial plan cannot be accepted")
                    }
                }
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace =
                CancellationException("cancel initial install retry")
            assertFailsWith<CancellationException> {
                harness.activeSessionEngine.installInitialRestPlanForTest(completion, restDuration)
            }
            val cancellationAttempt = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertEquals(firstAttempt.restTransitionPlan, cancellationAttempt.restTransitionPlan)
            assertIs<InitialRestPlanInstallResult.PersistenceFailure>(
                harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest,
            )

            val retryResult = harness.activeSessionEngine.installInitialRestPlanForTest(completion, restDuration)

            val retryAttempt = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertIs<InitialRestPlanInstallResult.Installed>(retryResult)
            assertEquals(firstAttempt.restTransitionPlan, retryAttempt.restTransitionPlan)
            assertEquals(1, transitionCalls)
            assertEquals(1, offerCalls)
            assertEquals(firstAttempt.restTransitionPlan, harness.restTransitionPlan.value)
            assertEquals(1, connectionLogs.logs.value.count { it.details == "reason=PERSISTENCE_FAILURE" })
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            connectionLogs.clearAll()
            harness.cleanup()
        }
    }

    @Test
    fun `persisted rest deadline and visible timer share one delayed start instant`() = runTest {
        val harness = DWSMTestHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.setActiveSummaryCountdownSeconds(5)
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()
            advanceTimeBy(3_000)
            teardownBarrier.complete(Result.success(Unit))
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            advanceTimeBy(5_000)
            runCurrent()

            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val key = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            val document = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(key.profileId, key.routineSessionId),
            )
            val deadline = assertNotNull(document.restDeadlineEpochMs)
            val persistedRemaining =
                ((deadline - harness.nowMs).coerceAtLeast(0L) + 999L).div(1_000L).toInt()
            assertEquals(persistedRemaining, resting.restSecondsRemaining)
            assertEquals(document.originalRestDurationSeconds, resting.restSecondsRemaining)
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `repeated plan timer starts keep the persisted deadline and elapsed remaining`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val plan = prepareNormalRest(harness)
            val completion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(harness.activeSessionEngine.currentExecutionLeaseForTest()),
            )
            advanceTimeBy(2_200)
            runCurrent()
            val beforeRepeat = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val key = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            val deadlineBefore = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(key.profileId, key.routineSessionId)
                    ?.restDeadlineEpochMs,
            )

            harness.activeSessionEngine.startRestTimer(completion)
            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()

            val afterRepeat = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val documentAfter = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(key.profileId, key.routineSessionId),
            )
            assertEquals(plan, documentAfter.restTransitionPlan)
            assertEquals(deadlineBefore, documentAfter.restDeadlineEpochMs)
            assertTrue(afterRepeat.restSecondsRemaining <= beforeRepeat.restSecondsRemaining)
            val persistedRemaining =
                ((deadlineBefore - harness.nowMs).coerceAtLeast(0L) + 999L).div(1_000L).toInt()
            assertEquals(persistedRemaining, afterRepeat.restSecondsRemaining)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `plan timer persistence failure and cancellation never publish divergent timer state`() = runTest {
        val harness = DWSMTestHarness(this)
        val teardownBarrier = CompletableDeferred<Result<Unit>>()
        try {
            harness.fakeBleRepo.stopWorkoutBlock = { teardownBarrier.await() }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            runCurrent()
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            val key = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()

            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Resting)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(key.profileId, key.routineSessionId)
                    ?.restDeadlineEpochMs,
            )

            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace =
                CancellationException("cancel timer start persistence")
            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()
            assertFalse(harness.coordinator.workoutState.value is WorkoutState.Resting)
            assertEquals(null, harness.coordinator.restDeadlineElapsedRealtimeMs)

            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository
                    .committedDocument(key.profileId, key.routineSessionId)
                    ?.restDeadlineEpochMs,
            )
        } finally {
            if (!teardownBarrier.isCompleted) teardownBarrier.complete(Result.success(Unit))
            harness.cleanup()
        }
    }

    @Test
    fun `repeated plan timer start preserves a persisted paused invariant`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareNormalRest(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            harness.dwsm.toggleRestPause()
            runCurrent()
            val remainingBefore = harness.coordinator._restSecondsRemaining.value
            val key = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            val pausedDocument = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(key.profileId, key.routineSessionId),
            )
            assertTrue(pausedDocument.isRestPaused)

            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()

            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(remainingBefore, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                pausedDocument,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(key.profileId, key.routineSessionId),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `declining an active unresolved timer refreshes its owned navigation presentation`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            val beforeDecline = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals("", beforeDecline.nextExerciseName)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()

            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val exercise = assertNotNull(harness.coordinator.loadedRoutine.value).exercises.single()
            assertEquals(exercise.exercise.displayName, resting.nextExerciseName)
            assertEquals(1, resting.currentSet)
            assertEquals(2, resting.totalSets)
            assertFalse(resting.isLastExercise)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `decline refresh preserves a user weight edit instead of preloading stale navigation params`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.dwsm.adjustWeight(newWeightKg = 37f, sendToMachine = false)

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()

            assertEquals(37f, harness.coordinator.workoutParameters.value.weightPerCableKg)
            assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `stale timer owner cannot publish captured parameters after reset and replacement`() = runTest {
        val harness = DWSMTestHarness(this)
        val timerClaimed = CompletableDeferred<Unit>()
        val releaseTimer = CompletableDeferred<Unit>()
        try {
            harness.activeSessionEngine.afterPersistedRestTimerClaimForTest = {
                timerClaimed.complete(Unit)
                releaseTimer.await()
            }
            prepareNormalRest(harness)
            timerClaimed.await()

            harness.dwsm.resetForNewWorkout()
            val replacement = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 55f).copy(
                id = "replacement-routine",
                name = "Replacement Routine",
            )
            replacement.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(replacement)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            val replacementWeight = harness.coordinator.workoutParameters.value.weightPerCableKg

            releaseTimer.complete(Unit)
            runCurrent()

            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(replacementWeight, harness.coordinator.workoutParameters.value.weightPerCableKg)
            assertEquals("replacement-routine", harness.coordinator.loadedRoutine.value?.id)
        } finally {
            if (!releaseTimer.isCompleted) releaseTimer.complete(Unit)
            harness.activeSessionEngine.afterPersistedRestTimerClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `stale zero rest timer cannot consume a replacement transition`() = runTest {
        assertStaleTimerCannotConsumeReplacement(restDurationSeconds = 0)
    }

    @Test
    fun `stale terminal autoplay timer cannot consume a replacement transition`() = runTest {
        assertStaleTimerCannotConsumeReplacement(restDurationSeconds = 1)
    }

    @Test
    fun `stale manual timer dispatch cannot consume a replacement transition`() = runTest {
        assertStaleTimerCannotConsumeReplacement(restDurationSeconds = 60, manualDispatch = true)
    }

    private suspend fun TestScope.assertStaleTimerCannotConsumeReplacement(
        restDurationSeconds: Int,
        manualDispatch: Boolean = false,
    ) {
        var transitionNumber = 0
        val harness = DWSMTestHarness(
            this,
            transitionIdGenerator = { "timer-transition-${++transitionNumber}" },
        )
        val actionReached = CompletableDeferred<Unit>()
        val releaseAction = CompletableDeferred<Unit>()
        var blockFirstAction = true
        try {
            harness.activeSessionEngine.beforePersistedRestTimerActionForTest = {
                if (blockFirstAction) {
                    blockFirstAction = false
                    actionReached.complete(Unit)
                    withContext(NonCancellable) {
                        releaseAction.await()
                    }
                }
            }
            if (manualDispatch) harness.setActiveSummaryCountdownSeconds(0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val sourceA = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            val routineA = sourceA.copy(
                id = "timer-routine-a-$restDurationSeconds",
                name = "Timer Routine A",
                exercises = sourceA.exercises.map {
                    it.copy(setRestSeconds = listOf(restDurationSeconds, restDurationSeconds))
                },
            )
            routineA.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routineA)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(leaseA, SetEndReason.TARGET_REPS_REACHED)
            if (manualDispatch) {
                advanceTimeBy(1_000)
                runCurrent()
                harness.dwsm.proceedFromSummary()
            } else {
                advanceTimeBy(11_000)
            }
            runCurrent()
            assertTrue(actionReached.isCompleted)

            harness.dwsm.resetForNewWorkout()
            if (manualDispatch) harness.setActiveSummaryCountdownSeconds(10)
            val sourceB = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 55f)
            val routineB = sourceB.copy(
                id = "timer-routine-b-$restDurationSeconds",
                name = "Timer Routine B",
                exercises = sourceB.exercises.map { it.copy(setRestSeconds = listOf(60, 60)) },
            )
            routineB.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routineB)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val leaseB = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(leaseB.executionId != leaseA.executionId)
            harness.activeSessionEngine.handleSetCompletion(leaseB, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)
            runCurrent()

            val planB = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val documentB = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val stateB = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val exerciseIndexB = harness.coordinator.currentExerciseIndex.value
            val setIndexB = harness.coordinator.currentSetIndex.value
            val navigationLookupsB = harness.dwsm.restTransitionNavigationLookupsForTest
            val startsB = harness.fakeBleRepo.workoutParameters.size
            val keyB = harness.fakeActiveWorkoutRuntimeRepository.replacements.last()
            assertEquals(planB, documentB.restTransitionPlan)

            releaseAction.complete(Unit)
            runCurrent()

            assertEquals(planB, harness.restTransitionPlan.value)
            assertEquals(documentB, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                documentB,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(keyB.profileId, keyB.routineSessionId),
            )
            assertEquals(stateB, harness.coordinator.workoutState.value)
            assertEquals(exerciseIndexB, harness.coordinator.currentExerciseIndex.value)
            assertEquals(setIndexB, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookupsB, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(startsB, harness.fakeBleRepo.workoutParameters.size)
        } finally {
            if (!releaseAction.isCompleted) releaseAction.complete(Unit)
            harness.activeSessionEngine.beforePersistedRestTimerActionForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `terminal timer dispatches the same owner declined transition exactly once`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            val unresolved = prepareEligibleStallRest(harness)
            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val replacements = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            advanceTimeBy(61_000)
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.activeSessionEngine.activeRuntimeDocumentForTest()?.restTransitionPlan)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(replacements + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            runCurrent()
            assertEquals(replacements + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timer cancellation after durable clear commit cannot publish or dispatch successor`() = runTest {
        val harness = DWSMTestHarness(this)
        var cancellationFired = false
        try {
            val plan = prepareExpiringNormalTimer(harness)
            val activeDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val starts = harness.fakeBleRepo.workoutParameters.size

            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!cancellationFired && committed.restTransitionPlan == null) {
                    cancellationFired = true
                    val timerJob = assertNotNull(harness.coordinator.restTimerJob)
                    timerJob.cancel()
                    if (harness.coordinator.restTimerJob === timerJob) {
                        harness.coordinator.restTimerJob = null
                    }
                }
            }

            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(cancellationFired)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    activeDocument.profileId,
                    activeDocument.routineSessionId,
                )?.restTransitionPlan,
            )
            assertEquals(plan, harness.restTransitionPlan.value)
            assertEquals(activeDocument, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(0, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun `timer cancellation after active clear cannot dispatch successor side effects`() = runTest {
        val harness = DWSMTestHarness(this)
        var cancellationFired = false
        try {
            prepareExpiringNormalTimer(harness)
            val activeDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val starts = harness.fakeBleRepo.workoutParameters.size

            harness.activeSessionEngine.afterDurableRestPlanClearForTest = {
                cancellationFired = true
                val timerJob = assertNotNull(harness.coordinator.restTimerJob)
                timerJob.cancel()
                if (harness.coordinator.restTimerJob === timerJob) {
                    harness.coordinator.restTimerJob = null
                }
            }

            advanceTimeBy(1_000)
            runCurrent()

            assertTrue(cancellationFired)
            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.activeSessionEngine.activeRuntimeDocumentForTest()?.restTransitionPlan)
            assertEquals(
                null,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    activeDocument.profileId,
                    activeDocument.routineSessionId,
                )?.restTransitionPlan,
            )
            assertEquals(0, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)
        } finally {
            harness.activeSessionEngine.afterDurableRestPlanClearForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `zero rest declined restart with autoplay off waits for identity skip`() = runTest {
        val harness = enabledDropSetHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            val routine = source.copy(
                exercises = source.exercises.map { it.copy(setRestSeconds = listOf(0, 0)) },
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            advanceTimeBy(11_000)
            runCurrent()
            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))

            harness.dwsm.applyRestTransitionAwait(RestTransitionCommand.Decline(unresolved.actionIdentity()))
            runCurrent()
            val declined = assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
            val timerJob = assertNotNull(harness.coordinator.restTimerJob)
            timerJob.cancel()
            if (harness.coordinator.restTimerJob === timerJob) {
                harness.coordinator.restTimerJob = null
            }
            runCurrent()
            val activeDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val starts = harness.fakeBleRepo.workoutParameters.size

            harness.activeSessionEngine.startRestTimer(completion)
            runCurrent()

            assertEquals(declined, harness.restTransitionPlan.value)
            assertEquals(declined, harness.activeSessionEngine.activeRuntimeDocumentForTest()?.restTransitionPlan)
            assertEquals(
                declined,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    activeDocument.profileId,
                    activeDocument.routineSessionId,
                )?.restTransitionPlan,
            )
            assertEquals(0, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)

            val replacementsBeforeSkip = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val outcome = harness.dwsm.applyRestTransitionAwait(
                RestTransitionCommand.SkipRest(declined.actionIdentity()),
            )
            assertIs<RestTransitionReduction.DispatchNormal>(outcome)
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.activeSessionEngine.activeRuntimeDocumentForTest()?.restTransitionPlan)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
            assertEquals(replacementsBeforeSkip + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun TestScope.prepareExpiringNormalTimer(
        harness: DWSMTestHarness,
    ): RestTransitionPlan.NormalAdvance {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
        val routine = source.copy(
            exercises = source.exercises.map { it.copy(setRestSeconds = listOf(1, 1)) },
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        advanceTimeBy(10_000)
        runCurrent()
        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        return assertIs(harness.restTransitionPlan.value)
    }

    @Test
    fun `terminal timer cannot consume after extend and pause between reducer and durable clear`() = runTest {
        val harness = DWSMTestHarness(this)
        val consumeReached = CompletableDeferred<Unit>()
        val releaseConsume = CompletableDeferred<Unit>()
        var navigationClaims = 0
        try {
            harness.activeSessionEngine.beforeRestTransitionNavigationClaimForTest = {
                navigationClaims += 1
                if (navigationClaims == 3) {
                    consumeReached.complete(Unit)
                    releaseConsume.await()
                }
            }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            val routine = source.copy(
                exercises = source.exercises.map { it.copy(setRestSeconds = listOf(1, 1)) },
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)
            runCurrent()
            consumeReached.await()

            harness.dwsm.extendRestTime(30)
            runCurrent()
            harness.dwsm.toggleRestPause()
            runCurrent()
            val plan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val state = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val starts = harness.fakeBleRepo.workoutParameters.size
            assertTrue(document.isRestPaused)
            assertTrue(state.restSecondsRemaining > 0)

            releaseConsume.complete(Unit)
            runCurrent()

            assertEquals(plan, harness.restTransitionPlan.value)
            assertEquals(document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(state, harness.coordinator.workoutState.value)
            assertTrue(harness.coordinator._isRestPaused.value)
            assertTrue(harness.coordinator.restTimerJob?.isActive == true)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)
        } finally {
            if (!releaseConsume.isCompleted) releaseConsume.complete(Unit)
            harness.activeSessionEngine.beforeRestTransitionNavigationClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `terminal timer expiry cannot skip after a concurrent extend and pause`() = runTest {
        val harness = DWSMTestHarness(this)
        val actionReached = CompletableDeferred<Unit>()
        val releaseAction = CompletableDeferred<Unit>()
        try {
            harness.activeSessionEngine.afterPersistedRestTimerActionAuthorizationForTest = {
                actionReached.complete(Unit)
                releaseAction.await()
            }
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 2, weightKg = 25f)
            val routine = source.copy(
                exercises = source.exercises.map { it.copy(setRestSeconds = listOf(1, 1)) },
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(11_000)
            runCurrent()
            assertTrue(actionReached.isCompleted)

            harness.dwsm.extendRestTime(30)
            runCurrent()
            harness.dwsm.toggleRestPause()
            runCurrent()
            val plan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val state = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            val navigationLookups = harness.dwsm.restTransitionNavigationLookupsForTest
            val starts = harness.fakeBleRepo.workoutParameters.size
            assertTrue(document.isRestPaused)
            assertTrue(state.restSecondsRemaining > 0)

            releaseAction.complete(Unit)
            runCurrent()

            assertEquals(plan, harness.restTransitionPlan.value)
            assertEquals(document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(state, harness.coordinator.workoutState.value)
            assertTrue(harness.coordinator._isRestPaused.value)
            assertTrue(harness.coordinator.restTimerJob?.isActive == true)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)

            val replacementsBeforeResume = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            harness.dwsm.toggleRestPause()
            runCurrent()
            assertFalse(harness.coordinator._isRestPaused.value)
            assertTrue(harness.coordinator.restTimerJob?.isActive == true)

            advanceTimeBy(31_000)
            runCurrent()

            assertEquals(null, harness.restTransitionPlan.value)
            assertEquals(null, harness.activeSessionEngine.activeRuntimeDocumentForTest()?.restTransitionPlan)
            assertEquals(1, harness.coordinator.currentSetIndex.value)
            assertEquals(navigationLookups, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(starts, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(replacementsBeforeResume + 2, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            runCurrent()
            assertEquals(replacementsBeforeResume + 2, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
        } finally {
            if (!releaseAction.isCompleted) releaseAction.complete(Unit)
            harness.activeSessionEngine.afterPersistedRestTimerActionAuthorizationForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `timer tick captured before extension cannot overwrite the newer persisted version`() = runTest {
        val harness = DWSMTestHarness(this)
        val tickCaptured = CompletableDeferred<Unit>()
        val releaseTick = CompletableDeferred<Unit>()
        var blockNextTick = true
        try {
            prepareNormalRest(harness)
            harness.activeSessionEngine.beforePersistedRestTimerTickPublishForTest = {
                if (blockNextTick) {
                    blockNextTick = false
                    tickCaptured.complete(Unit)
                    releaseTick.await()
                }
            }
            advanceTimeBy(100)
            tickCaptured.await()

            harness.dwsm.extendRestTime(15)
            runCurrent()
            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val authoritativeRemaining = RestDeadlineCalculator.remainingSeconds(document, harness.nowMs)
            assertEquals(authoritativeRemaining, harness.coordinator._restSecondsRemaining.value)

            releaseTick.complete(Unit)
            runCurrent()

            assertEquals(authoritativeRemaining, harness.coordinator._restSecondsRemaining.value)
            assertEquals(authoritativeRemaining, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
        } finally {
            if (!releaseTick.isCompleted) releaseTick.complete(Unit)
            harness.activeSessionEngine.beforePersistedRestTimerTickPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `extreme plan owned extension saturates without breaking timer invariants`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareNormalRest(harness)
            harness.dwsm.toggleRestPause()
            runCurrent()

            harness.dwsm.extendRestTime(Int.MAX_VALUE)
            runCurrent()

            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(Int.MAX_VALUE, document.originalRestDurationSeconds)
            assertEquals(Int.MAX_VALUE, document.pausedRestRemainingSeconds)
            assertTrue(document.isRestPaused)
            assertEquals(Int.MAX_VALUE, harness.coordinator._restOriginalDuration.value)
            assertEquals(Int.MAX_VALUE, harness.coordinator._restSecondsRemaining.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timer tick captured before pause cannot overwrite the newer persisted version`() = runTest {
        val harness = DWSMTestHarness(this)
        val tickCaptured = CompletableDeferred<Unit>()
        val releaseTick = CompletableDeferred<Unit>()
        var blockNextTick = true
        try {
            prepareNormalRest(harness)
            harness.activeSessionEngine.beforePersistedRestTimerTickPublishForTest = {
                if (blockNextTick) {
                    blockNextTick = false
                    tickCaptured.complete(Unit)
                    releaseTick.await()
                }
            }
            advanceTimeBy(100)
            tickCaptured.await()
            advanceTimeBy(2_000)

            harness.dwsm.toggleRestPause()
            runCurrent()
            val pausedDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val authoritativeRemaining = assertNotNull(pausedDocument.pausedRestRemainingSeconds)
            assertTrue(pausedDocument.isRestPaused)

            releaseTick.complete(Unit)
            runCurrent()

            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(authoritativeRemaining, harness.coordinator._restSecondsRemaining.value)
            assertEquals(authoritativeRemaining, assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining)
        } finally {
            if (!releaseTick.isCompleted) releaseTick.complete(Unit)
            harness.activeSessionEngine.beforePersistedRestTimerTickPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `backward wall clock during timer publication cannot exceed original duration`() = runTest {
        var wallClockMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { wallClockMs })
        try {
            harness.activeSessionEngine.afterPersistedRestTimerClaimForTest = {
                wallClockMs -= 3_600_000L
            }

            prepareNormalRest(harness)

            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(document.originalRestDurationSeconds, resting.restSecondsRemaining)
            assertEquals(document.originalRestDurationSeconds, harness.coordinator._restSecondsRemaining.value)
        } finally {
            harness.activeSessionEngine.afterPersistedRestTimerClaimForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `near maximum wall clock creates an overflow safe persisted timer`() = runTest {
        var wallClockMs = Long.MAX_VALUE - 500L
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { wallClockMs })
        try {
            prepareNormalRest(harness)

            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val resting = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            assertEquals(Long.MAX_VALUE, document.restDeadlineEpochMs)
            assertEquals(1, resting.restSecondsRemaining)
            wallClockMs = Long.MIN_VALUE
            assertEquals(
                document.originalRestDurationSeconds,
                RestDeadlineCalculator.remainingSeconds(document, wallClockMs),
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `a failed normal replace retries the exact transition for both next and final sets`() = runTest {
        assertFailedNormalPlanRetries(setsPerExercise = 2)
        assertFailedNormalPlanRetries(setsPerExercise = 1)
    }

    private suspend fun TestScope.assertFailedNormalPlanRetries(setsPerExercise: Int) {
        var transitionCalls = 0
        val harness = DWSMTestHarness(
            this,
            transitionIdGenerator = { "normal-$setsPerExercise-${++transitionCalls}" },
        )
        try {
            harness.setActiveSummaryCountdownSeconds(-1)
            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val routine = createTestRoutine(exerciseCount = 1, setsPerExercise = setsPerExercise, weightKg = 25f)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceTimeBy(1_000)
            // The completion job makes the first durable attempt before summary work;
            // summary-skipped flow retries it automatically. Both writes must retain
            // the single generated transition identity.
            val firstAttempt = harness.fakeActiveWorkoutRuntimeRepository.replacements.first().document
            val retryAttempt = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertEquals(firstAttempt.restTransitionPlan, retryAttempt.restTransitionPlan)
            assertEquals(1, transitionCalls)
            assertEquals(firstAttempt.restTransitionPlan, harness.restTransitionPlan.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `Issue 320 - stopAndReturnToSetReady saves reps when workingReps greater than 0 in routine`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 3,
            repsPerSet = 10,
            weightKg = 25f,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // Simulate 3 completed working reps
        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 3,
            totalReps = 3,
            isWarmupComplete = true,
        )

        // Manual stop (covers back button, stop dialog, and voice safe word)
        harness.dwsm.stopAndReturnToSetReady()
        // Use advanceTimeBy to avoid infinite re-dispatch from rest timer tick loop.
        // 1s is enough for handleSetCompletion to save session and show SetSummary.
        advanceTimeBy(1_000)

        // Should route through handleSetCompletion → SetSummary (not back to SetReady)
        val state = harness.dwsm.coordinator.workoutState.value
        assertIs<WorkoutState.SetSummary>(
            state,
            "Issue #320: Manual stop with completed reps should save reps and show summary, got: $state",
        )

        // Verify session was saved
        val sessions = harness.fakeWorkoutRepo.getAllSessions("default").first()
        assertTrue(sessions.isNotEmpty(), "Issue #320: Session with partial reps should have been saved")

        harness.cleanup()
    }

    @Test
    fun `Issue 320 - stopAndReturnToSetReady discards when workingReps is 0 in routine`() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 3,
            repsPerSet = 10,
            weightKg = 25f,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

        // No reps completed (workingReps = 0)

        // Manual stop
        harness.dwsm.stopAndReturnToSetReady()
        advanceTimeBy(1_000)

        // Should use old path: discard and return to SetReady for same set
        val flowState = harness.dwsm.coordinator.routineFlowState.value
        assertIs<RoutineFlowState.SetReady>(
            flowState,
            "Issue #320: Manual stop with 0 reps should return to SetReady (existing behavior), got: $flowState",
        )

        // Set index should remain at 0 (not advanced)
        assertEquals(
            0,
            harness.dwsm.coordinator.currentSetIndex.value,
            "Issue #320: Set index should remain at 0 when discarding",
        )

        harness.cleanup()
    }

    private suspend fun completeFirstWorkingRep(harness: DWSMTestHarness, warmupTarget: Int = 3, workingTarget: Int = 8) {
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f,
        )

        harness.fakeBleRepo.emitMetric(activeMetric)
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = warmupTarget + 1,
                completeCounter = warmupTarget + 1,
                repsRomCount = warmupTarget,
                repsRomTotal = warmupTarget,
                repsSetCount = 1,
                repsSetTotal = workingTarget,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = harness.nowMs + warmupTarget + 1L,
            ),
        )
    }

    private fun createTimedCableRoutine(durationSeconds: Int): Routine = Routine(
        id = "timed-cable-routine",
        name = "Timed Cable Routine",
        exercises = listOf(
            RoutineExercise(
                id = "timed-cable-bench-press",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(10),
                weightPerCableKg = 25f,
                duration = durationSeconds,
                setRestSeconds = listOf(0),
            ),
        ),
    )

    private fun createBodyweightRoutine(sets: Int, repsPerSet: Int, durationSeconds: Int): Routine {
        val pushUp = Exercise(
            name = "Push Up",
            muscleGroup = "Chest",
            muscleGroups = "Chest,Triceps,Shoulders",
            equipment = "",
            id = "push-up-001",
        )
        return Routine(
            id = "bodyweight-routine",
            name = "Bodyweight Routine",
            exercises = listOf(
                RoutineExercise(
                    id = "bodyweight-push-up",
                    exercise = pushUp,
                    orderIndex = 0,
                    setReps = List(sets) { repsPerSet },
                    weightPerCableKg = 0f,
                    duration = durationSeconds,
                    setRestSeconds = List(sets) { 0 },
                ),
            ),
        )
    }

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float = 0.1f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "Expected $expected ± $tolerance, got $actual",
        )
    }
}

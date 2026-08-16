package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeAttributionEnvelope
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRejection
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RuntimeCleanupLiveLeaseRaceTest {
    @Test
    fun `End deletes a blocked initial runtime commit without replaying claimed history`() = runTest {
        val harness = enabledHarness(this)
        val replaceEntered = CompletableDeferred<Unit>()
        val releaseReplace = CompletableDeferred<Unit>()
        try {
            val lease = startEligibleRoutine(harness)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = {
                replaceEntered.complete(Unit)
                withContext(NonCancellable) { releaseReplace.await() }
            }

            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()

            assertTrue(replaceEntered.isCompleted)
            val committedAfterRelease = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.replacements.lastOrNull()?.document,
            )
            assertNull(harness.restTransitionPlan.value)
            val sessionAttemptsBeforeEnd = harness.fakeWorkoutRepo.saveSessionAttempts.count {
                it.id == lease.sessionId
            }
            val completedSetAttemptsBeforeEnd = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count {
                it.sessionId == lease.sessionId
            }
            assertEquals(1, sessionAttemptsBeforeEnd)
            assertEquals(1, completedSetAttemptsBeforeEnd)

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()

            assertNull(harness.restTransitionPlan.value)
            assertNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    committedAfterRelease.profileId,
                    committedAfterRelease.routineSessionId,
                ),
            )

            releaseReplace.complete(Unit)
            advanceUntilIdle()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    committedAfterRelease.profileId,
                    committedAfterRelease.routineSessionId,
                ),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(
                sessionAttemptsBeforeEnd,
                harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId },
            )
            assertEquals(
                completedSetAttemptsBeforeEnd,
                harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count { it.sessionId == lease.sessionId },
            )
        } finally {
            if (!releaseReplace.isCompleted) releaseReplace.complete(Unit)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = null
            harness.cleanup()
        }
    }

    @Test
    fun `End deletes a blocked live Accept commit without successor side effects`() = runTest {
        val harness = enabledHarness(this)
        val replaceEntered = CompletableDeferred<Unit>()
        val releaseReplace = CompletableDeferred<Unit>()
        try {
            val lease = startEligibleRoutine(harness)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()

            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val documentA = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(
                documentA,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    documentA.profileId,
                    documentA.routineSessionId,
                ),
            )
            val replacementsBeforeAction = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            val commandsBeforeAction = harness.fakeBleRepo.commandsReceived.size
            val startsBeforeAction = harness.fakeBleRepo.workoutParameters.size
            val sessionAttemptsBeforeAction = harness.fakeWorkoutRepo.saveSessionAttempts.count {
                it.id == lease.sessionId
            }
            val completedSetAttemptsBeforeAction = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count {
                it.sessionId == lease.sessionId
            }
            assertEquals(1, sessionAttemptsBeforeAction)
            assertEquals(1, completedSetAttemptsBeforeAction)

            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = {
                replaceEntered.complete(Unit)
                withContext(NonCancellable) { releaseReplace.await() }
            }
            val action = async {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                )
            }
            runCurrent()

            assertTrue(replaceEntered.isCompleted)
            assertEquals(
                replacementsBeforeAction + 1,
                harness.fakeActiveWorkoutRuntimeRepository.replacements.size,
            )
            val documentB = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertTrue(documentB != documentA)
            assertIs<RestTransitionPlan.AcceptedRetry>(documentB.restTransitionPlan)
            assertEquals(unresolved, harness.restTransitionPlan.value)
            assertEquals(
                documentA,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    documentA.profileId,
                    documentA.routineSessionId,
                ),
            )

            harness.dwsm.stopWorkout(exitingWorkout = true)
            runCurrent()
            assertTrue(harness.coordinator.stopWorkoutInProgress.value)

            releaseReplace.complete(Unit)
            advanceUntilIdle()
            action.await()

            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    documentB.profileId,
                    documentB.routineSessionId,
                ),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
            assertEquals(commandsBeforeAction, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startsBeforeAction, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(
                sessionAttemptsBeforeAction,
                harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId },
            )
            assertEquals(
                completedSetAttemptsBeforeAction,
                harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count { it.sessionId == lease.sessionId },
            )

            val loadCallsAfterCleanup = harness.fakeActiveWorkoutRuntimeRepository.loadCalls
            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            advanceUntilIdle()

            assertEquals(
                loadCallsAfterCleanup,
                harness.fakeActiveWorkoutRuntimeRepository.loadCalls,
                "a completed successor cleanup must retire its stale tracked predecessor",
            )
        } finally {
            if (!releaseReplace.isCompleted) releaseReplace.complete(Unit)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = null
            harness.cleanup()
        }
    }

    @Test
    fun `same profile abandonment fences a blocked live Accept before it can publish`() = runTest {
        val harness = enabledHarness(this)
        val replaceEntered = CompletableDeferred<Unit>()
        val releaseReplace = CompletableDeferred<Unit>()
        try {
            val lease = startEligibleRoutine(harness)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()

            val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
            val documentA = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val commandsBeforeAction = harness.fakeBleRepo.commandsReceived.size
            val startsBeforeAction = harness.fakeBleRepo.workoutParameters.size
            val navigationBeforeAction = harness.dwsm.restTransitionNavigationLookupsForTest
            val sessionAttemptsBeforeAction = harness.fakeWorkoutRepo.saveSessionAttempts.count {
                it.id == lease.sessionId
            }
            val completedSetAttemptsBeforeAction = harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count {
                it.sessionId == lease.sessionId
            }

            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = {
                replaceEntered.complete(Unit)
                withContext(NonCancellable) { releaseReplace.await() }
            }
            val action = async {
                harness.dwsm.applyRestTransitionAwait(
                    RestTransitionCommand.Accept(
                        unresolved.actionIdentity(),
                        unresolved.candidates.first().percentage,
                    ),
                )
            }
            runCurrent()

            assertTrue(replaceEntered.isCompleted)
            val documentB = harness.fakeActiveWorkoutRuntimeRepository.replacements.last().document
            assertTrue(documentB != documentA)
            assertIs<RestTransitionPlan.AcceptedRetry>(documentB.restTransitionPlan)
            assertEquals(unresolved, harness.restTransitionPlan.value)

            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            runCurrent()

            assertEquals(
                RuntimeCleanupReason.EXPLICIT_RESTART,
                harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest(),
            )
            assertEquals(unresolved, harness.restTransitionPlan.value)
            assertTrue(harness.activeSessionEngine.currentExecutionLeaseOrNull() === lease)

            releaseReplace.complete(Unit)
            advanceUntilIdle()

            assertIs<RestTransitionReduction.NoOp>(action.await())
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    documentB.profileId,
                    documentB.routineSessionId,
                ),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(commandsBeforeAction, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startsBeforeAction, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(navigationBeforeAction, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(
                sessionAttemptsBeforeAction,
                harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == lease.sessionId },
            )
            assertEquals(
                completedSetAttemptsBeforeAction,
                harness.fakeCompletedSetRepo.saveCompletedSetAttempts.count { it.sessionId == lease.sessionId },
            )

            val loadCallsAfterCleanup = harness.fakeActiveWorkoutRuntimeRepository.loadCalls
            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            advanceUntilIdle()

            assertEquals(loadCallsAfterCleanup, harness.fakeActiveWorkoutRuntimeRepository.loadCalls)
        } finally {
            if (!releaseReplace.isCompleted) releaseReplace.complete(Unit)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = null
            harness.cleanup()
        }
    }

    @Test
    fun `rejected same key replacement retires the tracked cleanup candidate without deleting the row`() = runTest {
        val harness = enabledHarness(this)
        try {
            val lease = startEligibleRoutine(harness)
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.STALL_FAILURE)
            runCurrent()

            val document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())
            val rejected = harness.fakeActiveWorkoutRuntimeRepository.installRejected(
                profileId = document.profileId,
                routineSessionId = document.routineSessionId,
                reason = ActiveWorkoutRuntimeRejection.CORRUPT_JSON,
                attribution = ActiveWorkoutRuntimeAttributionEnvelope(
                    profileId = document.profileId,
                    routineId = document.routineId,
                    routineSessionId = document.routineSessionId,
                    routineExerciseId = document.routineExerciseId,
                    sourceExerciseIndex = document.sourceExerciseIndex,
                    sourceSetIndex = document.sourceSetIndex,
                ),
            )
            val commandsBeforeCleanup = harness.fakeBleRepo.commandsReceived.size
            val startsBeforeCleanup = harness.fakeBleRepo.workoutParameters.size
            val navigationBeforeCleanup = harness.dwsm.restTransitionNavigationLookupsForTest

            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            advanceUntilIdle()

            assertEquals(
                rejected,
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    document.profileId,
                    document.routineSessionId,
                ),
            )
            assertNull(harness.restTransitionPlan.value)
            assertNull(harness.activeSessionEngine.pendingRuntimeCleanupReasonForTest())
            assertEquals(commandsBeforeCleanup, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(startsBeforeCleanup, harness.fakeBleRepo.workoutParameters.size)
            assertEquals(navigationBeforeCleanup, harness.dwsm.restTransitionNavigationLookupsForTest)

            val loadCallsAfterCleanup = harness.fakeActiveWorkoutRuntimeRepository.loadCalls
            harness.activeSessionEngine.beginRoutineAbandonmentRuntimeCleanup()
            advanceUntilIdle()

            assertEquals(loadCallsAfterCleanup, harness.fakeActiveWorkoutRuntimeRepository.loadCalls)
            assertEquals(
                rejected,
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    document.profileId,
                    document.routineSessionId,
                ),
            )
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun startEligibleRoutine(harness: DWSMTestHarness): ExecutionLease {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 25f,
            repsPerSet = 10,
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        harness.testScope.advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 6,
            totalReps = 6,
            isWarmupComplete = true,
        )
        return harness.activeSessionEngine.currentExecutionLeaseForTest()
    }

    private fun enabledHarness(testScope: TestScope) = DWSMTestHarness(
        testScope = testScope,
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(
            DropSetFeatureGate { true },
            DropSetCandidateResolver(),
        ),
        dropSetConfigurationProvider = { _: RoutineExercise ->
            DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 1f)
        },
    )
}

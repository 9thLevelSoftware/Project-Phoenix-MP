package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutPhase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FP-6 / F-072: the manual-stop path that runs when no execution lease is
 * current still writes a WorkoutSession, a CompletedSet and PRs. It used to
 * stamp them with `userProfileRepository.activeProfile`, so a profile switch
 * between the set and the Stop press filed one member's work under whichever
 * profile happens to be on screen. The write must follow the profile that
 * OWNED the execution.
 *
 * Reaching that branch takes a real sequence, because a live lease always
 * produces a completion and therefore takes the snapshot path instead: run a
 * routine set to completion, end the workout (which invalidates the lease and,
 * through the runtime cleanup, releases the stop guard), then press Stop again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualStopProfileAttributionTest {
    private fun routine() = Routine(
        id = "attribution-routine",
        name = "Attribution",
        exercises = listOf(
            RoutineExercise(
                id = "rex-1",
                exercise = Exercise(
                    id = "ex-1",
                    name = "Cable Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8, 8),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    /**
     * Leaves the engine with no current lease, the stop guard released and the
     * completed set's execution context still in place — the exact state the
     * no-completion manual-stop branch runs in.
     */
    private suspend fun TestScope.runASetThenEndTheWorkout(harness: DWSMTestHarness) {
        harness.fakeUserProfileRepo.seedReadyProfileForTest(OWNER, name = "Owner")
        harness.fakeUserProfileRepo.seedReadyProfileForTest(OTHER, name = "Other")
        harness.fakeUserProfileRepo.emitReadyForTest(OWNER)
        advanceUntilIdle()

        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutineAsync(routine())
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        harness.coordinator._repCount.value = RepCount(workingReps = 8, totalReps = 8, isWarmupComplete = true)
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        advanceUntilIdle()

        harness.dwsm.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()

        assertTrue(
            runCatching { harness.activeSessionEngine.currentExecutionLeaseForTest() }.isFailure,
            "the no-completion branch only runs without a current lease",
        )
        assertEquals(false, harness.coordinator.stopWorkoutInProgress.value)
    }

    private fun TestScope.stopAgainUnderAnotherProfile(harness: DWSMTestHarness) {
        harness.coordinator._repCount.value = RepCount(
            workingReps = LEGACY_REPS,
            totalReps = LEGACY_REPS,
            isWarmupComplete = true,
        )
        harness.coordinator.workoutStartTime = harness.nowMs - 10_000
        // The profile switch the finding describes: it lands between the set and Stop.
        harness.fakeUserProfileRepo.emitReadyForTest(OTHER)
        advanceUntilIdle()
        assertEquals(OTHER, harness.fakeUserProfileRepo.activeProfile.value?.id)

        harness.dwsm.stopWorkout()
        advanceUntilIdle()
    }

    @Test
    fun manualStopWithoutALeaseFilesTheSessionSetAndPrsUnderTheOwningProfile() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            runASetThenEndTheWorkout(harness)
            stopAgainUnderAnotherProfile(harness)

            val legacySession = harness.fakeWorkoutRepo.allSessions().single { it.totalReps == LEGACY_REPS }
            assertEquals(OWNER, legacySession.profileId, "the session must follow the profile that owned the set")

            val legacySet = harness.fakeCompletedSetRepo.saved.single { it.actualReps == LEGACY_REPS }
            assertEquals(legacySession.id, legacySet.sessionId)

            val ownerPrs = harness.fakePRRepo.getAllPRsForExercise("ex-1", OWNER)
            val otherPrs = harness.fakePRRepo.getAllPRsForExercise("ex-1", OTHER)
            assertTrue(
                ownerPrs.any { it.prType == PRType.MAX_WEIGHT && it.phase == WorkoutPhase.COMBINED },
                "the PR must be filed under the owning profile",
            )
            assertTrue(otherPrs.isEmpty(), "no PR may leak into the profile that was merely on screen")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun manualStopWithoutALeaseCommitsTheSessionAndItsSetInOneTransaction() = runTest {
        val inner = FakeWorkoutRepository()
        val recorder = RecordingWorkoutRepository(inner)
        val harness = DWSMTestHarness(this, workoutRepositoryOverride = recorder)
        try {
            inner.completedSetRepository = harness.fakeCompletedSetRepo
            inner.repMetricRepository = harness.fakeRepMetricRepo
            inner.biomechanicsRepository = harness.fakeBiomechanicsRepo

            runASetThenEndTheWorkout(harness)
            recorder.saveSessionCalls.clear()
            recorder.commits.clear()

            stopAgainUnderAnotherProfile(harness)

            val commit = recorder.commits.singleOrNull { it.first.totalReps == LEGACY_REPS }
            assertNotNull(commit, "the manual-stop save must go through commitCompletedSet")
            assertNotNull(commit.second, "the CompletedSet must ride in the same transaction as its session")
            assertEquals(commit.first.id, commit.second?.sessionId)
            assertTrue(
                recorder.saveSessionCalls.none { it.totalReps == LEGACY_REPS },
                "the session must not be written by a separate saveSession transaction",
            )
        } finally {
            harness.cleanup()
        }
    }

    // ===== Issue #1391 (packet B1): phone-equivalent save/discard attribution =====

    private fun timedRoutine() = Routine(
        id = "attribution-timed-routine",
        name = "Timed Attribution",
        exercises = listOf(
            RoutineExercise(
                id = "rex-timed",
                exercise = Exercise(
                    id = "ex-timed",
                    name = "Timed Hold",
                    muscleGroup = "Core",
                    muscleGroups = "Core",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                // Two sets: the seam must save set 1's timed work AND prepare the exact
                // next set. (A one-set fixture would legitimately end COMPLETE — the run
                // completed — and could not exercise "advance to the next set".)
                setReps = listOf(3, 3),
                weightPerCableKg = 20f,
                programMode = ProgramMode.OldSchool,
                duration = 45,
            ),
        ),
    )

    private fun tempSingleRoutine() = Routine(
        id = DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX + "cable-row",
        name = "Single Exercise",
        exercises = listOf(
            RoutineExercise(
                id = "rex-temp",
                exercise = Exercise(
                    id = "ex-temp",
                    name = "Cable Row",
                    muscleGroup = "Back",
                    muscleGroups = "Back",
                    equipment = "Cable",
                ),
                orderIndex = 0,
                setReps = listOf(8),
                weightPerCableKg = 40f,
                programMode = ProgramMode.OldSchool,
            ),
        ),
    )

    private suspend fun TestScope.startAttributionSet(
        harness: DWSMTestHarness,
        routine: Routine,
        workingReps: Int,
    ) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.loadRoutineAsync(routine)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(
            workingReps = workingReps,
            totalReps = workingReps,
            isWarmupComplete = true,
        )
    }

    @Test
    fun remoteSetControlSavesPartialWorkingRepsAndAdvancesToTheExactNextSet() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, routine(), workingReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            // Phone-equivalent save-and-advance attribution for a real routine with
            // partial working reps.
            assertEquals(RemoteCompletionDecision.SAVED_AND_ADVANCED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.SUCCEEDED, receipt.persistence)
            assertEquals(RemoteTeardownState.READY, receipt.teardownState)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            val saved = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            assertEquals(3, saved.workingReps, "the partial working reps must be the saved set")
            assertEquals(
                harness.fakeUserProfileRepo.activeProfile.value?.id,
                saved.profileId,
                "the save must follow the owning profile",
            )
            // Correct next target prepared and NOT started.
            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(0, ready.exerciseIndex)
            assertEquals(1, ready.setIndex, "set 0 of 2 must prepare set 1")
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(
                commandsBefore,
                harness.fakeBleRepo.commandsReceived.size,
                "preparing the successor must not command the machine to start",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun remoteSetControlSavesFullWorkingRepsExactlyOnce() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, routine(), workingReps = 8)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteCompletionDecision.SAVED_AND_ADVANCED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.SUCCEEDED, receipt.persistence)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun remoteSetControlDiscardsAndRetriesTempSingleExercises() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            // temp_single_* prepared exercises never save-and-advance: the phone's
            // original discard-and-retry behavior applies even with working reps.
            startAttributionSet(harness, tempSingleRoutine(), workingReps = 5)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteCompletionDecision.DISCARDED_AND_RETRIED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.NOT_REQUIRED, receipt.persistence)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            assertTrue(harness.fakeWorkoutRepo.allSessions().isEmpty(), "temp_single_* sets are never saved here")
            assertTrue(harness.fakeWorkoutRepo.saveSessionAttempts.isEmpty(), "no save may even be attempted")
            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(0, ready.exerciseIndex)
            assertEquals(0, ready.setIndex, "the discarded set is prepared for retry")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun remoteSetControlDiscardsAndRetriesZeroWorkingReps() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, routine(), workingReps = 0)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteCompletionDecision.DISCARDED_AND_RETRIED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.NOT_REQUIRED, receipt.persistence)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            assertTrue(harness.fakeWorkoutRepo.allSessions().isEmpty(), "a zero-working-rep set is a no-save case")
            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(0, ready.setIndex, "the same set is prepared for retry")
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun remoteSetControlSavesTimedSetsWithTheSameAttribution() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, timedRoutine(), workingReps = 4)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteCompletionDecision.SAVED_AND_ADVANCED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.SUCCEEDED, receipt.persistence)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            val saved = harness.fakeWorkoutRepo.allSessions().single { it.id == lease.sessionId }
            assertEquals(4, saved.workingReps)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun remoteSetControlDiscardsWarmupSetsAndRetriesTheSameWarmupStep() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val warmupRoutine = Routine(
                id = "attribution-warmup-routine",
                name = "Warmup Attribution",
                exercises = listOf(
                    RoutineExercise(
                        id = "rex-warmup",
                        exercise = Exercise(
                            id = "ex-warmup",
                            name = "Cable Row",
                            muscleGroup = "Back",
                            muscleGroups = "Back",
                            equipment = "Cable",
                        ),
                        orderIndex = 0,
                        setReps = listOf(8, 8),
                        weightPerCableKg = 40f,
                        programMode = ProgramMode.OldSchool,
                        warmupSets = listOf(WarmupSet(reps = 5, percentOfWorking = 50)),
                    ),
                ),
            )
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.loadRoutineAsync(warmupRoutine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0, reinitializeWarmups = true)
            advanceUntilIdle()
            assertEquals(0, harness.coordinator._currentWarmupSetIndex.value, "warmup phase must be armed")
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            // Warmup reps are counted separately; the set has no working reps.
            harness.coordinator._repCount.value = RepCount(warmupReps = 3, totalReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(executionIdentityOf(lease))

            assertEquals(RemoteCompletionDecision.DISCARDED_AND_RETRIED, receipt.completionDecision)
            assertEquals(RemotePersistenceOutcome.NOT_REQUIRED, receipt.persistence)
            assertEquals(RemoteSetControlTerminal.PREPARED, receipt.terminal)
            assertTrue(harness.fakeWorkoutRepo.allSessions().isEmpty(), "a warmup set is a no-save case")
            // The retry is the SAME warmup step, and no successor warmup was started.
            assertEquals(0, harness.coordinator._currentWarmupSetIndex.value)
            val leaseAfter = harness.activeSessionEngine.currentExecutionLeaseOrNull()
            assertTrue(
                leaseAfter == null || leaseAfter.executionId == lease.executionId,
                "no successor execution may be started",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun anAutoCompletedSetIsNeverCompletedTwiceByTheWatchPress() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, routine(), workingReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val identity = executionIdentityOf(lease)

            // The autoplay/manual-completion race: auto-complete wins first.
            harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
            advanceUntilIdle()

            // Auto-completion auto-started the successor execution (the "extra"
            // execution). The pressed identity is now stale; the receipt must report
            // the LIVE execution identity honestly (same semantics as
            // WorkoutTeardownRecoveryTest: "the observed identity is reported honestly").
            val successor = requireNotNull(
                harness.activeSessionEngine.currentExecutionLeaseOrNull()?.let(::executionIdentityOf),
            ) { "autoplay must have started the successor execution" }
            assertTrue(successor != identity, "the live execution must differ from the completed one")

            val receipt = harness.dwsm.endCurrentSetAndPrepareNext(identity)

            assertEquals(RemoteSetControlTerminal.SUPERSEDED, receipt.terminal)
            assertEquals(RemoteCompletionDecision.NO_COMPLETION, receipt.completionDecision)
            assertEquals(successor, receipt.observedExecutionIdentity, "the live execution is reported honestly")
            // One transition, never two.
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun aSecondWatchPressAfterTheOperationNeverTransitionsAgain() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startAttributionSet(harness, routine(), workingReps = 3)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val identity = executionIdentityOf(lease)

            val first = harness.dwsm.endCurrentSetAndPrepareNext(identity)
            assertEquals(RemoteSetControlTerminal.PREPARED, first.terminal)

            val second = harness.dwsm.endCurrentSetAndPrepareNext(identity)

            assertEquals(RemoteSetControlTerminal.SUPERSEDED, second.terminal)
            assertEquals(RemoteCompletionDecision.NO_COMPLETION, second.completionDecision)
            assertEquals(1, harness.fakeWorkoutRepo.allSessions().count { it.id == lease.sessionId })
            val ready = assertIs<RoutineFlowState.SetReady>(harness.coordinator._routineFlowState.value)
            assertEquals(1, ready.setIndex, "the prepared successor must be untouched by the duplicate press")
        } finally {
            harness.cleanup()
        }
    }

    private class RecordingWorkoutRepository(
        private val delegate: FakeWorkoutRepository,
    ) : WorkoutRepository by delegate {
        val saveSessionCalls = mutableListOf<WorkoutSession>()
        val commits = mutableListOf<Pair<WorkoutSession, CompletedSet?>>()

        override suspend fun saveSession(session: WorkoutSession) {
            saveSessionCalls += session
            delegate.saveSession(session)
        }

        override suspend fun commitCompletedSet(
            session: WorkoutSession,
            metrics: List<WorkoutMetric>,
            completedSet: CompletedSet?,
            repMetrics: List<RepMetricData>,
            repBiomechanics: List<BiomechanicsRepResult>,
        ) {
            commits += session to completedSet
            delegate.commitCompletedSet(session, metrics, completedSet, repMetrics, repBiomechanics)
        }
    }

    private companion object {
        const val OWNER = "owner"
        const val OTHER = "other"
        const val LEGACY_REPS = 7
    }
}

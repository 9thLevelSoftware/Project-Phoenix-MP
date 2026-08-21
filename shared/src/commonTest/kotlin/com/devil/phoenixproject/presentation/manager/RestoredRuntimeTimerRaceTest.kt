package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RestoredRuntimeTimerRaceTest {
    @Test
    fun `captured restored tick cannot republish after its exact owner is superseded`() = runTest {
        val harness = DWSMTestHarness(this)
        val tickCaptured = CompletableDeferred<Pair<RestoredRuntimeOwnerToken, Int>>()
        val releaseTick = CompletableDeferred<Unit>()
        try {
            val installed = installActiveTimerRuntime(harness, "captured-tick-a")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            val restoredOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = { owner, remainingSeconds ->
                tickCaptured.complete(owner to remainingSeconds)
                releaseTick.await()
            }

            advanceTimeBy(101L)
            val (capturedOwner, capturedRemaining) = withTimeout(2_000L) { tickCaptured.await() }
            assertEquals(restoredOwner, capturedOwner)
            assertEquals(30, capturedRemaining)

            harness.activeSessionEngine.executionGuard.supersedeRecoveryPublication()
            harness.coordinator._restSecondsRemaining.value = 7
            harness.coordinator._workoutState.value = WorkoutState.Idle
            releaseTick.complete(Unit)
            runCurrent()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(7, harness.coordinator._restSecondsRemaining.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(timerJob.isActive)
        } finally {
            releaseTick.complete(Unit)
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `stale reset exact timer detach cannot clear a newer restored owner or job`() = runTest {
        val harness = DWSMTestHarness(this)
        val staleDetachEntered = CompletableDeferred<Unit>()
        val releaseStaleDetach = CompletableDeferred<Unit>()
        try {
            val stale = installActiveTimerRuntime(harness, "stale-reset-a")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(stale.handle),
            )
            runCurrent()
            val staleOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val staleJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            var intercepted = false
            harness.activeSessionEngine.beforeRestoredRestTimerOwnerCompareAndClearForTest = { expectedOwner ->
                if (!intercepted && expectedOwner == staleOwner) {
                    intercepted = true
                    staleDetachEntered.complete(Unit)
                    runBlocking { releaseStaleDetach.await() }
                }
            }

            val staleReset = async(Dispatchers.Default) {
                harness.dwsm.resetForNewWorkout()
            }
            withContext(Dispatchers.IO) {
                withTimeout(10_000L) { staleDetachEntered.await() }
            }

            val newer = installActiveTimerRuntime(harness, "newer-resume-b")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(newer.handle),
            )
            runCurrent()
            val newerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val newerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertTrue(newerOwner != staleOwner)
            assertTrue(newerJob !== staleJob)
            assertFalse(staleJob.isActive)
            assertTrue(newerJob.isActive)

            releaseStaleDetach.complete(Unit)
            withContext(Dispatchers.IO) {
                withTimeout(10_000L) { staleReset.await() }
            }

            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === newerJob)
            assertTrue(newerJob.isActive)
            assertEquals(newer.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(newer.document.restTransitionPlan, harness.restTransitionPlan.value)
            val remainingAfterStaleReset =
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining
            assertTrue(remainingAfterStaleReset in 1..30)

            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(newerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === newerJob)
            assertTrue(
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining <
                    remainingAfterStaleReset,
            )
        } finally {
            releaseStaleDetach.complete(Unit)
            harness.activeSessionEngine.beforeRestoredRestTimerOwnerCompareAndClearForTest = null
            harness.cleanup()
        }
    }

    @Test
    fun `reset for new workout immediately detaches and cancels the exact restored timer`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val installed = installActiveTimerRuntime(harness, "reset-current")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())

            harness.dwsm.resetForNewWorkout()

            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(timerJob.isActive)
            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator._restSecondsRemaining.value)

            advanceTimeBy(1_100L)
            runCurrent()

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            assertEquals(0, harness.coordinator._restSecondsRemaining.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cleanup immediately detaches and cancels the exact restored timer without a stale tick`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val installed = installActiveTimerRuntime(harness, "cleanup-current")
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())

            harness.dwsm.cleanup()
            val stateAfterCleanup = harness.coordinator.workoutState.value
            val remainingAfterCleanup = harness.coordinator._restSecondsRemaining.value

            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(timerJob.isActive)

            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(stateAfterCleanup, harness.coordinator.workoutState.value)
            assertEquals(remainingAfterCleanup, harness.coordinator._restSecondsRemaining.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
        } finally {
            harness.cleanup()
        }
    }

    private data class InstalledRaceTimerRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    private suspend fun installActiveTimerRuntime(
        harness: DWSMTestHarness,
        routineSessionId: String,
    ): InstalledRaceTimerRuntime {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        val routine = WorkoutStateFixtures.createTestRoutine(
            exerciseCount = 1,
            setsPerExercise = 2,
            weightKg = 25f,
        )
        val exercise = routine.exercises.single()
        val profileId = harness.fakeUserProfileRepo.activeProfile.value?.id ?: "default"
        val sourceStableSessionId = "source-$routineSessionId"
        val logicalSetKey = LogicalSetKey(
            routineSessionId = routineSessionId,
            routineExerciseId = exercise.id,
            setIndex = 0,
            setKind = SetType.STANDARD,
        )
        val document = timerDocument(
            profileId = profileId,
            routine = routine,
            sourceExercise = exercise,
            sourceStableSessionId = sourceStableSessionId,
            logicalSetKey = logicalSetKey,
            restDeadlineEpochMs = harness.nowMs + 30_000L,
        )
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
        return InstalledRaceTimerRuntime(document, handle)
    }

    private fun timerDocument(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        logicalSetKey: LogicalSetKey,
        restDeadlineEpochMs: Long,
    ): ActiveWorkoutRuntimeDocument {
        val sourceExecutionId = "42"
        val plan = RestTransitionPlan.NormalAdvance(
            transitionId = "timer-transition-${logicalSetKey.routineSessionId}",
            sourceExecutionId = sourceExecutionId,
            logicalSetKey = logicalSetKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(0, 0),
            plannedSetId = null,
            restDurationSeconds = 60,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = profileId,
            routineId = routine.id,
            routineSessionId = logicalSetKey.routineSessionId,
            routineExerciseId = sourceExercise.id,
            sourceExecutionId = sourceExecutionId,
            sourceStableSessionId = sourceStableSessionId,
            sourceAttemptNumber = 1,
            logicalSetKey = logicalSetKey,
            plannedSetId = null,
            sourceExerciseIndex = 0,
            sourceSetIndex = 0,
            sourceAuthority = sourceAuthority(
                profileId = profileId,
                routine = routine,
                sourceExercise = sourceExercise,
                sourceStableSessionId = sourceStableSessionId,
                sourceExecutionId = sourceExecutionId,
                logicalSetKey = logicalSetKey,
            ),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = sourceExecutionId.toLong(),
                sourceStableSessionId = sourceStableSessionId,
                profileId = profileId,
                requiresMachine = true,
            ),
            attemptStates = listOf(
                PlannedSetAttemptState(
                    logicalSetKey = logicalSetKey,
                    nextAttemptNumber = 2,
                    acceptedDropCount = 0,
                ),
            ),
            restTransitionPlan = plan,
            restDeadlineEpochMs = restDeadlineEpochMs,
            pausedRestRemainingSeconds = null,
            isRestPaused = false,
            originalRestDurationSeconds = 60,
        )
    }

    private fun sourceAuthority(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        sourceExecutionId: String,
        logicalSetKey: LogicalSetKey,
    ): RestoredRetrySourceAuthoritySnapshot {
        val targetReps = requireNotNull(sourceExercise.setReps.first())
        val command = WorkoutParameters(
            programMode = sourceExercise.programMode,
            reps = targetReps,
            weightPerCableKg = sourceExercise.weightPerCableKg,
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
            sourceExecutionId = sourceExecutionId,
            profileId = profileId,
            routineIdentity = RoutineExecutionIdentity(
                profileId = profileId,
                routineId = routine.id,
                routineSessionId = logicalSetKey.routineSessionId,
                routineExerciseId = sourceExercise.id,
                logicalSetKey = logicalSetKey,
                plannedSetId = null,
                exerciseIndex = 0,
                setIndex = 0,
            ),
            reasonName = SetEndReason.STALL_FAILURE.name,
            attemptNumber = 1,
            acceptedDropCount = 0,
            plannedSetTypeName = SetType.STANDARD.name,
            programModeName = "OLD_SCHOOL",
            programmedBaseWeightPerCableKg = sourceExercise.weightPerCableKg,
            configuredStartWeightPerCableKg = sourceExercise.weightPerCableKg,
            progressionKg = sourceExercise.progressionKg,
            actualReps = 6,
            targetReps = targetReps,
            isWarmup = false,
            isEcho = false,
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

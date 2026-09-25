package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class RestoredRuntimeTimerAuthorityLifecycleRaceTest {
    private enum class LifecycleCutover { RESET, CLEANUP }

    @Test
    fun `captured restored tick after active profile switch preserves sentinel and retires old authority`() = runTest {
        assertCapturedTickRetiresAfterAuthorityDrift(
            harness = DWSMTestHarness(this),
            routineSessionId = "tick-profile-drift",
            expectsTerminalPresentationExit = true,
        ) { harness ->
            harness.fakeUserProfileRepo.setActiveProfileForTest(id = "other-profile-after-tick-capture")
        }
    }

    @Test
    fun `captured restored tick after external rack stamp mutation preserves sentinel and retires old authority`() = runTest {
        assertCapturedTickRetiresAfterAuthorityDrift(
            harness = DWSMTestHarness(this),
            routineSessionId = "tick-rack-drift",
            expectsTerminalPresentationExit = false,
        ) { harness ->
            harness.fakeEquipmentRackRepo.saveItems(
                listOf(RackItem(id = "rack-after-tick-capture", name = "Changed rack", weightKg = 2f)),
            )
        }
    }

    @Test
    fun `reset during zero publication cutover leaves no restored timer or stale tick`() = runTest {
        assertLifecycleDuringZeroPublicationCutover(
            harness = DWSMTestHarness(this),
            lifecycle = LifecycleCutover.RESET,
        )
    }

    @Test
    fun `cleanup during zero publication cutover leaves no restored timer or stale tick`() = runTest {
        assertLifecycleDuringZeroPublicationCutover(
            harness = DWSMTestHarness(this),
            lifecycle = LifecycleCutover.CLEANUP,
        )
    }

    @Test
    fun `cleanup closes the disposed manager before a newer runtime can resume and retains its row`() = runTest {
        val harness = DWSMTestHarness(this)
        val cleanupBoundaryEntered = CompletableDeferred<Unit>()
        val releaseCleanupBoundary = CompletableDeferred<Unit>()
        try {
            installAndResumeActiveTimer(
                harness = harness,
                routineSessionId = "cleanup-captured-a",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            val jobA = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            harness.activeSessionEngine.afterCleanupDisposalBoundaryForTest = {
                cleanupBoundaryEntered.complete(Unit)
                runBlocking { releaseCleanupBoundary.await() }
            }

            val staleCleanup = async(Dispatchers.Default) {
                harness.dwsm.cleanup()
            }
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) { cleanupBoundaryEntered.await() }
            }

            val installedB = installTimerRuntime(
                harness = harness,
                routineSessionId = "cleanup-resumed-b",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.Superseded>(
                harness.dwsm.resumeRoutine(installedB.handle),
            )
            assertEquals(
                installedB.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installedB.document.profileId,
                    installedB.document.routineSessionId,
                ),
            )

            releaseCleanupBoundary.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) { staleCleanup.await() }
            }

            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertFalse(jobA.isActive)
            assertEquals(
                installedB.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installedB.document.profileId,
                    installedB.document.routineSessionId,
                ),
            )
        } finally {
            releaseCleanupBoundary.complete(Unit)
            harness.activeSessionEngine.afterCleanupDisposalBoundaryForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `cleanup drains a runtime that publishes between its preclose boundary and guard close`() = runTest {
        val harness = DWSMTestHarness(this)
        val bPublicationEntered = CompletableDeferred<Unit>()
        val releaseBPublication = CompletableDeferred<Unit>()
        val cleanupPrecloseEntered = CompletableDeferred<Unit>()
        val releaseCleanupPreclose = CompletableDeferred<Unit>()
        try {
            installAndResumeActiveTimer(
                harness = harness,
                routineSessionId = "cleanup-preclose-a",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            val ownerA = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            val installedB = installTimerRuntime(
                harness = harness,
                routineSessionId = "cleanup-preclose-b",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            assertTrue(harness.activeSessionEngine.executionGuard.revokeRestoredRuntime(ownerA))
            assertEquals(ownerA, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            harness.activeSessionEngine.beforeRestoredRuntimeOwnerPublicationForTest = {
                bPublicationEntered.complete(Unit)
                runBlocking { releaseBPublication.await() }
            }
            val resumeB = async(Dispatchers.Default) {
                harness.dwsm.resumeRoutine(installedB.handle)
            }
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) { bPublicationEntered.await() }
            }

            harness.activeSessionEngine.beforeCleanupGuardCloseForTest = {
                cleanupPrecloseEntered.complete(Unit)
                runBlocking { releaseCleanupPreclose.await() }
            }
            val cleanup = async(Dispatchers.Default) {
                harness.dwsm.cleanup()
            }
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) { cleanupPrecloseEntered.await() }
            }

            releaseBPublication.complete(Unit)
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                withContext(Dispatchers.Default) { withTimeout(2_000L) { resumeB.await() } },
            )
            releaseCleanupPreclose.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(2_000L) { cleanup.await() } }

            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertEquals(
                installedB.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installedB.document.profileId,
                    installedB.document.routineSessionId,
                ),
            )
        } finally {
            releaseBPublication.complete(Unit)
            releaseCleanupPreclose.complete(Unit)
            harness.activeSessionEngine.beforeRestoredRuntimeOwnerPublicationForTest = null
            harness.activeSessionEngine.beforeCleanupGuardCloseForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    private suspend fun TestScope.assertCapturedTickRetiresAfterAuthorityDrift(
        harness: DWSMTestHarness,
        routineSessionId: String,
        expectsTerminalPresentationExit: Boolean,
        mutateAuthority: suspend (DWSMTestHarness) -> Unit,
    ) {
        val tickCaptured = CompletableDeferred<Int>()
        val releaseTick = CompletableDeferred<Unit>()
        try {
            val installed = installAndResumeActiveTimer(
                harness = harness,
                routineSessionId = routineSessionId,
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            var interceptNextTick = true
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = { tickOwner, remaining ->
                if (interceptNextTick && tickOwner == owner) {
                    interceptNextTick = false
                    tickCaptured.complete(remaining)
                    releaseTick.await()
                }
            }

            advanceTimeBy(101L)
            runCurrent()
            assertTrue(tickCaptured.isCompleted)

            mutateAuthority(harness)
            val sentinelSeconds = 7
            val restingBeforeRelease = assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
            harness.coordinator._restSecondsRemaining.value = sentinelSeconds
            harness.coordinator._workoutState.value = restingBeforeRelease.copy(
                restSecondsRemaining = sentinelSeconds,
            )
            assertTrue(tickCaptured.await() != sentinelSeconds)

            releaseTick.complete(Unit)
            runCurrent()

            if (expectsTerminalPresentationExit) {
                assertEquals(0, harness.coordinator._restSecondsRemaining.value)
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
                assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
                assertNull(harness.coordinator.loadedRoutine.value)
                assertNull(harness.restTransitionPlan.value)
                assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                    harness.fakeActiveWorkoutRuntimeRepository.load(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                )
            } else {
                assertEquals(sentinelSeconds, harness.coordinator._restSecondsRemaining.value)
                assertEquals(
                    sentinelSeconds,
                    assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                )
                assertEquals(installed.document.restTransitionPlan, harness.restTransitionPlan.value)
            }
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(timerJob.isActive)
            assertFalse(harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(owner))

            advanceTimeBy(1_100L)
            runCurrent()

            if (expectsTerminalPresentationExit) {
                assertEquals(0, harness.coordinator._restSecondsRemaining.value)
                assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
            } else {
                assertEquals(sentinelSeconds, harness.coordinator._restSecondsRemaining.value)
                assertEquals(
                    sentinelSeconds,
                    assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                )
            }
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
        } finally {
            releaseTick.complete(Unit)
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    private suspend fun TestScope.assertLifecycleDuringZeroPublicationCutover(
        harness: DWSMTestHarness,
        lifecycle: LifecycleCutover,
    ) {
        val zeroPublished = CompletableDeferred<Unit>()
        val releaseZeroDetach = CompletableDeferred<Unit>()
        try {
            installAndResumeActiveTimer(
                harness = harness,
                routineSessionId = "zero-lifecycle-${lifecycle.name.lowercase()}",
                restDeadlineEpochMs = harness.nowMs + 1_000L,
            )
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            var interceptZero = true
            harness.activeSessionEngine.afterRestoredRestTimerZeroPublishForTest = { tickOwner ->
                if (interceptZero && tickOwner == owner) {
                    interceptZero = false
                    zeroPublished.complete(Unit)
                    releaseZeroDetach.await()
                }
            }

            advanceTimeBy(1_001L)
            runCurrent()
            assertTrue(zeroPublished.isCompleted, lifecycle.name)
            assertEquals(0, harness.coordinator._restSecondsRemaining.value, lifecycle.name)
            assertEquals(
                0,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                lifecycle.name,
            )

            when (lifecycle) {
                LifecycleCutover.RESET -> harness.dwsm.resetForNewWorkout()
                LifecycleCutover.CLEANUP -> harness.dwsm.cleanup()
            }
            val stateAfterLifecycle = harness.coordinator.workoutState.value
            val remainingAfterLifecycle = harness.coordinator._restSecondsRemaining.value

            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), lifecycle.name)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest(), lifecycle.name)
            assertNull(
                harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                lifecycle.name,
            )
            assertFalse(timerJob.isActive, lifecycle.name)

            releaseZeroDetach.complete(Unit)
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(stateAfterLifecycle, harness.coordinator.workoutState.value, lifecycle.name)
            assertEquals(remainingAfterLifecycle, harness.coordinator._restSecondsRemaining.value, lifecycle.name)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), lifecycle.name)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest(), lifecycle.name)
            assertNull(
                harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                lifecycle.name,
            )
        } finally {
            releaseZeroDetach.complete(Unit)
            harness.activeSessionEngine.afterRestoredRestTimerZeroPublishForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    private data class InstalledTimerRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    private suspend fun TestScope.installAndResumeActiveTimer(
        harness: DWSMTestHarness,
        routineSessionId: String,
        restDeadlineEpochMs: Long,
    ): InstalledTimerRuntime {
        val installed = installTimerRuntime(harness, routineSessionId, restDeadlineEpochMs)
        assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(installed.handle))
        runCurrent()
        assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
        return installed
    }

    private suspend fun TestScope.installTimerRuntime(
        harness: DWSMTestHarness,
        routineSessionId: String,
        restDeadlineEpochMs: Long,
    ): InstalledTimerRuntime {
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
            restDeadlineEpochMs = restDeadlineEpochMs,
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
        return InstalledTimerRuntime(document, handle)
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

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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RestoredRuntimeTimerControlRaceTest {
    private enum class PostCommitSupersession { CONFIGURATION, OWNER }

    @Test
    fun `extend cancellation before repository commit retains exact restored timer and presentation`() = runTest {
        val harness = DWSMTestHarness(this)
        val cancellation = CancellationException("cancel before restored timer extend commit")
        var controlJob: Job? = null
        try {
            val installed = installAndResumeActiveTimer(harness, "extend-pre-commit-cancel")
            val before = captureTimerSnapshot(harness)
            val replaceEventsBefore = harness.fakeActiveWorkoutRuntimeRepository.replaceEvents.size
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                controlJob = currentCoroutineContext()[Job]
            }
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace = cancellation

            harness.dwsm.extendRestTime(15)
            runCurrent()

            assertExactTimerSnapshot(harness, before)
            assertEquals(
                installed.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(
                listOf("entered"),
                harness.fakeActiveWorkoutRuntimeRepository.replaceEvents.drop(replaceEventsBefore),
            )
            assertNull(harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace)
            assertTrue(assertNotNull(before.job).isActive)
            assertControlCompletedWith(controlJob, cancellation)
        } finally {
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
            harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `pause or resume cancellation before repository commit retains the exact timer without a leaked job`() = runTest {
        listOf(false, true).forEach { startingPaused ->
            val harness = DWSMTestHarness(this)
            val cancellation = CancellationException(
                "cancel before restored timer ${if (startingPaused) "resume" else "pause"} commit",
            )
            var controlJob: Job? = null
            try {
                val installed = installAndResumeActiveTimer(
                    harness,
                    "toggle-pre-commit-${if (startingPaused) "resume" else "pause"}",
                )
                if (startingPaused) {
                    harness.dwsm.toggleRestPause()
                    runCurrent()
                    assertTrue(harness.coordinator.isRestPaused.value)
                    assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
                }
                val before = captureTimerSnapshot(harness)
                val parentJob = assertNotNull(coroutineContext[Job])
                val childrenBefore = parentJob.children.toSet()
                harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                    controlJob = currentCoroutineContext()[Job]
                }
                harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace = cancellation

                harness.dwsm.toggleRestPause()
                runCurrent()

                assertExactTimerSnapshot(harness, before)
                assertEquals(
                    before.document,
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                    "startingPaused=$startingPaused",
                )
                assertControlCompletedWith(controlJob, cancellation)
                val leakedChildren = parentJob.children.filter { child ->
                    child !in childrenBefore && child !== controlJob && !child.isCompleted
                }.toList()
                assertTrue(leakedChildren.isEmpty(), "startingPaused=$startingPaused leaked=$leakedChildren")
                if (startingPaused) {
                    assertNull(before.job)
                } else {
                    assertTrue(assertNotNull(before.job).isActive)
                }
            } finally {
                harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
                harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace = null
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `pause cancellation after repository commit reconciles paused timer then rethrows exact cancellation`() = runTest {
        val harness = DWSMTestHarness(this)
        val cancellation = CancellationException("cancel after restored timer pause commit")
        var controlJob: Job? = null
        var cancellationInjected = false
        try {
            val installed = installAndResumeActiveTimer(harness, "pause-post-commit-cancel")
            val before = captureTimerSnapshot(harness)
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                controlJob = currentCoroutineContext()[Job]
            }
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!cancellationInjected && committed.isRestPaused) {
                    cancellationInjected = true
                    throw cancellation
                }
            }

            harness.dwsm.toggleRestPause()
            runCurrent()

            assertTrue(cancellationInjected)
            val committed = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertTrue(committed.isRestPaused)
            assertNull(committed.restDeadlineEpochMs)
            assertEquals(before.remainingSeconds, committed.pausedRestRemainingSeconds)
            assertEquals(before.document.originalRestDurationSeconds, committed.originalRestDurationSeconds)
            assertEquals(committed, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(before.plan, harness.restTransitionPlan.value)
            assertEquals(before.owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(before.owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertTrue(assertNotNull(before.job).isCancelled)
            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(before.remainingSeconds, harness.coordinator._restSecondsRemaining.value)
            assertEquals(before.originalDurationSeconds, harness.coordinator._restOriginalDuration.value)
            assertEquals(
                before.remainingSeconds,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
            assertTrue(harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(before.owner))
            assertControlCompletedWith(controlJob, cancellation)
        } finally {
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `resume cancellation after repository commit reconciles exactly one live timer then rethrows`() = runTest {
        val harness = DWSMTestHarness(this)
        val cancellation = CancellationException("cancel after restored timer resume commit")
        var controlJob: Job? = null
        var cancellationInjected = false
        try {
            val installed = installAndResumeActiveTimer(harness, "resume-post-commit-cancel")
            harness.dwsm.toggleRestPause()
            runCurrent()
            val before = captureTimerSnapshot(harness)
            assertTrue(before.isPaused)
            assertNull(before.job)
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                controlJob = currentCoroutineContext()[Job]
            }
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!cancellationInjected && !committed.isRestPaused && committed.restDeadlineEpochMs != null) {
                    cancellationInjected = true
                    throw cancellation
                }
            }

            harness.dwsm.toggleRestPause()
            runCurrent()

            assertTrue(cancellationInjected)
            val committed = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertFalse(committed.isRestPaused)
            assertNull(committed.pausedRestRemainingSeconds)
            assertNotNull(committed.restDeadlineEpochMs)
            assertEquals(committed, harness.activeSessionEngine.activeRuntimeDocumentForTest())
            assertEquals(before.plan, harness.restTransitionPlan.value)
            assertEquals(before.owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(before.owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val resumedJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertTrue(resumedJob.isActive)
            assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(before.remainingSeconds, harness.coordinator._restSecondsRemaining.value)
            assertEquals(before.originalDurationSeconds, harness.coordinator._restOriginalDuration.value)
            assertEquals(
                before.remainingSeconds,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
            assertControlCompletedWith(controlJob, cancellation)
        } finally {
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `postcommit timer cancellation with an unreadable commit retires authority and preserves the original cancellation`() = runTest {
        listOf<Throwable>(
            IllegalStateException("commit probe unavailable"),
            AssertionError("commit probe invariant failure"),
        ).forEachIndexed { index, probeFailure ->
            val harness = DWSMTestHarness(this)
            val cancellation = CancellationException("original timer cancellation $index")
            var controlJob: Job? = null
            var cancellationInjected = false
            try {
                val installed = installAndResumeActiveTimer(harness, "timer-unknown-commit-$index")
                val before = captureTimerSnapshot(harness)
                val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
                val commandsBefore = harness.fakeBleRepo.commandsReceived.size
                val configurationsBefore = harness.fakeBleRepo.workoutParameters.size
                harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                    controlJob = currentCoroutineContext()[Job]
                }
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!cancellationInjected && committed.originalRestDurationSeconds == 75) {
                        cancellationInjected = true
                        throw cancellation
                    }
                }
                harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = { _, _, _ ->
                    if (cancellationInjected) throw probeFailure
                }

                harness.dwsm.extendRestTime(15)
                runCurrent()

                assertTrue(cancellationInjected, probeFailure::class.simpleName)
                val committed = assertNotNull(
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                )
                assertEquals(75, committed.originalRestDurationSeconds)
                assertControlCompletedWith(controlJob, cancellation)
                assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
                assertFalse(assertNotNull(before.job).isActive)
                assertFalse(harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(before.owner))
                assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest)
                assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
                assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.fakeActiveWorkoutRuntimeRepository.afterLoadSnapshot = null
                harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `timer cancellation that discovers a divergent replacement retires only the stale owner`() = runTest {
        val harness = DWSMTestHarness(this)
        val cancellation = CancellationException("lost acknowledgement after divergent replacement")
        var controlJob: Job? = null
        var replacement: ActiveWorkoutRuntimeDocument? = null
        var cancellationInjected = false
        try {
            val installed = installAndResumeActiveTimer(harness, "timer-divergent-replacement")
            val before = captureTimerSnapshot(harness)
            val navigationBefore = harness.dwsm.restTransitionNavigationLookupsForTest
            val commandsBefore = harness.fakeBleRepo.commandsReceived.size
            val configurationsBefore = harness.fakeBleRepo.workoutParameters.size
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                controlJob = currentCoroutineContext()[Job]
            }
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                if (!cancellationInjected && committed.originalRestDurationSeconds == 75) {
                    cancellationInjected = true
                    replacement = committed.copy(
                        restTransitionPlan = assertNotNull(committed.restTransitionPlan)
                            .withRestDurationSeconds(90),
                        originalRestDurationSeconds = 90,
                        restDeadlineEpochMs = harness.nowMs + 45_000L,
                    )
                    harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                        profileId = committed.profileId,
                        routineSessionId = committed.routineSessionId,
                        document = assertNotNull(replacement),
                    )
                    throw cancellation
                }
            }

            harness.dwsm.extendRestTime(15)
            runCurrent()

            val installedReplacement = assertNotNull(replacement)
            assertTrue(cancellationInjected)
            assertControlCompletedWith(controlJob, cancellation)
            assertEquals(
                installedReplacement,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertFalse(assertNotNull(before.job).isActive)
            assertFalse(harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(before.owner))
            assertEquals(navigationBefore, harness.dwsm.restTransitionNavigationLookupsForTest)
            assertEquals(commandsBefore, harness.fakeBleRepo.commandsReceived.size)
            assertEquals(configurationsBefore, harness.fakeBleRepo.workoutParameters.size)
            assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())

            val replacementsAfterDivergence = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            harness.dwsm.extendRestTime(5)
            runCurrent()
            assertEquals(replacementsAfterDivergence, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(
                installedReplacement,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `control captured from A before mutex cannot persist publish or clear resumed B`() = runTest {
        val harness = DWSMTestHarness(this)
        val controlCaptured = CompletableDeferred<Unit>()
        val releaseControl = CompletableDeferred<Unit>()
        var blockFirstControl = true
        try {
            val installedA = installAndResumeActiveTimer(harness, "blocked-control-a")
            val timerA = captureTimerSnapshot(harness)
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = {
                if (blockFirstControl) {
                    blockFirstControl = false
                    controlCaptured.complete(Unit)
                    releaseControl.await()
                }
            }

            harness.dwsm.extendRestTime(15)
            runCurrent()
            assertTrue(controlCaptured.isCompleted)

            harness.dwsm.resetForNewWorkout()
            runCurrent()
            assertFalse(assertNotNull(timerA.job).isActive)
            val installedB = installAndResumeActiveTimer(harness, "resumed-runtime-b")
            val timerB = captureTimerSnapshot(harness)
            assertTrue(timerB.owner != timerA.owner)
            val replacementsBeforeRelease = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            releaseControl.complete(Unit)
            runCurrent()

            assertExactTimerSnapshot(harness, timerB)
            assertEquals(replacementsBeforeRelease, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertIs<ActiveWorkoutRuntimeLoadResult.Missing>(
                harness.fakeActiveWorkoutRuntimeRepository.load(
                    installedA.document.profileId,
                    installedA.document.routineSessionId,
                ),
            )
            assertEquals(
                installedB.document,
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installedB.document.profileId,
                    installedB.document.routineSessionId,
                ),
            )
        } finally {
            releaseControl.complete(Unit)
            harness.activeSessionEngine.afterRestoredRestTimerControlCaptureForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `supersession after durable extend makes committed mutation inert and removes old owner`() = runTest {
        enumValues<PostCommitSupersession>().forEach { supersession ->
            val harness = DWSMTestHarness(this)
            var superseded = false
            try {
                val installed = installAndResumeActiveTimer(
                    harness,
                    "extend-post-commit-${supersession.name.lowercase()}",
                )
                val before = captureTimerSnapshot(harness)
                val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { committed ->
                    if (!superseded && committed.originalRestDurationSeconds == 75) {
                        superseded = when (supersession) {
                            PostCommitSupersession.CONFIGURATION -> {
                                harness.activeSessionEngine.executionGuard.mutateConfigurationInputs {}
                                true
                            }

                            PostCommitSupersession.OWNER ->
                                harness.activeSessionEngine.executionGuard.revokeRestoredRuntime(before.owner)
                        }
                    }
                }

                harness.dwsm.extendRestTime(15)
                runCurrent()

                assertTrue(superseded, supersession.name)
                val committed = assertNotNull(
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                    supersession.name,
                )
                assertEquals(75, committed.originalRestDurationSeconds, supersession.name)
                assertEquals(45, RestDeadlineCalculator.remainingSeconds(committed, harness.nowMs), supersession.name)
                assertEquals(committed, harness.activeSessionEngine.activeRuntimeDocumentForTest(), supersession.name)
                assertEquals(before.plan, harness.restTransitionPlan.value, supersession.name)
                assertNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(), supersession.name)
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), supersession.name)
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest(), supersession.name)
                assertNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                    supersession.name,
                )
                assertTrue(assertNotNull(before.job).isCancelled, supersession.name)
                assertFalse(
                    harness.activeSessionEngine.executionGuard.isRestoredRuntimeCurrent(before.owner),
                    supersession.name,
                )
                assertEquals(before.remainingSeconds, harness.coordinator._restSecondsRemaining.value, supersession.name)
                assertEquals(before.originalDurationSeconds, harness.coordinator._restOriginalDuration.value, supersession.name)
                assertEquals(before.isPaused, harness.coordinator.isRestPaused.value, supersession.name)
                assertEquals(before.workoutState, harness.coordinator.workoutState.value, supersession.name)
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), supersession.name)
                assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            } finally {
                harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                harness.cleanup()
                runCurrent()
            }
        }
    }

    private data class InstalledTimerRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
    )

    private data class TimerSnapshot(
        val document: ActiveWorkoutRuntimeDocument,
        val plan: RestTransitionPlan,
        val owner: RestoredRuntimeOwnerToken,
        val job: Job?,
        val deadlineElapsedRealtimeMs: Long?,
        val remainingSeconds: Int,
        val originalDurationSeconds: Int,
        val isPaused: Boolean,
        val workoutState: WorkoutState,
    )

    private suspend fun TestScope.installAndResumeActiveTimer(
        harness: DWSMTestHarness,
        routineSessionId: String,
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
        assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(harness.dwsm.resumeRoutine(handle))
        runCurrent()
        assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value)
        return InstalledTimerRuntime(document, handle)
    }

    private fun captureTimerSnapshot(harness: DWSMTestHarness) = TimerSnapshot(
        document = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest()),
        plan = assertNotNull(harness.restTransitionPlan.value),
        owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest()),
        job = harness.activeSessionEngine.currentRestoredRestTimerJobForTest(),
        deadlineElapsedRealtimeMs =
            harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
        remainingSeconds = harness.coordinator._restSecondsRemaining.value,
        originalDurationSeconds = harness.coordinator._restOriginalDuration.value,
        isPaused = harness.coordinator.isRestPaused.value,
        workoutState = harness.coordinator.workoutState.value,
    )

    private fun assertExactTimerSnapshot(
        harness: DWSMTestHarness,
        expected: TimerSnapshot,
    ) {
        assertEquals(expected.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
        assertEquals(expected.plan, harness.restTransitionPlan.value)
        assertEquals(expected.owner, harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
        assertEquals(expected.owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
        assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === expected.job)
        assertEquals(
            expected.deadlineElapsedRealtimeMs,
            harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
        )
        assertEquals(expected.remainingSeconds, harness.coordinator._restSecondsRemaining.value)
        assertEquals(expected.originalDurationSeconds, harness.coordinator._restOriginalDuration.value)
        assertEquals(expected.isPaused, harness.coordinator.isRestPaused.value)
        assertEquals(expected.workoutState, harness.coordinator.workoutState.value)
    }

    private fun assertControlCompletedWith(job: Job?, expected: CancellationException) {
        val completed = assertNotNull(job)
        assertTrue(completed.isCompleted)
        var completionCause: Throwable? = null
        completed.invokeOnCompletion { cause -> completionCause = cause }
        assertTrue(completionCause === expected)
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
        val normalPlan = RestTransitionPlan.NormalAdvance(
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
            restTransitionPlan = normalPlan,
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

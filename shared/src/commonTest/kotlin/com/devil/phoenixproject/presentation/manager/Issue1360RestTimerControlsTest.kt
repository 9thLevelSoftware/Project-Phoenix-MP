package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures.createTestRoutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class Issue1360RestTimerControlsTest {
    @Test
    fun failedRestControlPublishesFeedbackForCurrentOwner() = runTest {
        val harness = DWSMTestHarness(this)
        val messages = mutableListOf<String>()
        val receipts = mutableListOf<RestControlReceipt>()
        val feedbackCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.userFeedbackEvents.toList(messages)
        }
        val receiptCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            prepareTutRest(harness, nextExercise = false)
            val persistedBefore = runtime(harness)
            val remainingBefore = resting(harness).restSecondsRemaining
            var outcome: RestControlOutcome? = null
            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1

            val request = harness.dwsm.beginRestControlTap(RestControlAction.EXTEND, seconds = 30)
            harness.dwsm.extendRestTimeWithOutcome(30, request) { outcome = it }
            assertEquals(null, outcome, "Plan-owned completion must not run when the action is only enqueued")
            runCurrent()

            assertEquals(RestControlOutcome.Rejected(RestControlRejectionReason.PERSISTENCE_FAILED), outcome)
            assertEquals(persistedBefore, runtime(harness))
            assertEquals(remainingBefore, resting(harness).restSecondsRemaining)
            assertEquals(listOf(REST_CONTROL_FAILURE_FEEDBACK_KEY), messages)
            assertEquals(
                listOf(
                    RestControlReceiptStage.TAP_ENTRY,
                    RestControlReceiptStage.ENGINE_DISPATCH,
                    RestControlReceiptStage.AUTHORITY_ACCEPTED,
                    RestControlReceiptStage.PERSISTENCE_STARTED,
                    RestControlReceiptStage.PERSISTENCE_FAILED,
                    RestControlReceiptStage.REJECTED,
                ),
                receipts.map { it.stage },
            )
            assertTrue(receipts.all { it.actionId == receipts.first().actionId })
            assertTrue(receipts.all { it.action == RestControlAction.EXTEND })
            assertTrue(receipts.all { it.ownerGeneration == receipts.first().ownerGeneration && it.ownerGeneration != null })
            assertEquals(RestControlRejectionReason.PERSISTENCE_FAILED, receipts.last().reason)
        } finally {
            feedbackCollector.cancel()
            receiptCollector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun planOwnedCompletionIsAppliedOnlyAfterDurablePublication() = runTest {
        val harness = DWSMTestHarness(this)
        val receipts = mutableListOf<RestControlReceipt>()
        val receiptCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            prepareTutRest(harness, nextExercise = false)
            val before = runtime(harness)
            val remainingBefore = resting(harness).restSecondsRemaining
            var outcome: RestControlOutcome? = null

            val request = harness.dwsm.beginRestControlTap(RestControlAction.EXTEND, seconds = 30)
            harness.dwsm.extendRestTimeWithOutcome(30, request) { completed ->
                outcome = completed
                val committed = runtime(harness)
                assertEquals(before.originalRestDurationSeconds + 30, committed.originalRestDurationSeconds)
                assertEquals(committed, committedRuntime(harness, committed))
                assertEquals(remainingBefore + 30, resting(harness).restSecondsRemaining)
            }

            assertEquals(null, outcome, "Completion must not be delivered on enqueue")
            runCurrent()

            assertEquals(RestControlOutcome.Applied, outcome)
            assertEquals(
                listOf(
                    RestControlReceiptStage.TAP_ENTRY,
                    RestControlReceiptStage.ENGINE_DISPATCH,
                    RestControlReceiptStage.AUTHORITY_ACCEPTED,
                    RestControlReceiptStage.PERSISTENCE_STARTED,
                    RestControlReceiptStage.PERSISTENCE_CONFIRMED,
                    RestControlReceiptStage.PUBLICATION,
                ),
                receipts.map { it.stage },
            )
            assertTrue(receipts.all { it.actionId == receipts.first().actionId })
            assertTrue(receipts.all { it.action == RestControlAction.EXTEND })
            assertTrue(receipts.all { it.ownerGeneration == receipts.first().ownerGeneration && it.ownerGeneration != null })
            assertEquals(null, receipts.last().reason)
        } finally {
            receiptCollector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun divergedPlanOwnedCommitReportsPostWriteDriftWithoutPersistenceFeedback() = runTest {
        val harness = DWSMTestHarness(this)
        val messages = mutableListOf<String>()
        val receipts = mutableListOf<RestControlReceipt>()
        val feedbackCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.userFeedbackEvents.toList(messages)
        }
        val receiptCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            prepareTutRest(harness, nextExercise = false)
            val prior = runtime(harness)
            val diverged = prior.copy(originalRestDurationSeconds = prior.originalRestDurationSeconds + 1)
            harness.fakeActiveWorkoutRuntimeRepository.replaceBlock = {
                harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                    prior.profileId,
                    prior.routineSessionId,
                    diverged,
                )
            }
            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            var outcome: RestControlOutcome? = null

            harness.dwsm.extendRestTimeWithOutcome(30) { outcome = it }
            assertEquals(null, outcome, "The divergence result is asynchronous")
            runCurrent()

            assertEquals(RestControlOutcome.Rejected(RestControlRejectionReason.POST_WRITE_DRIFT), outcome)
            assertEquals(diverged, harness.fakeActiveWorkoutRuntimeRepository.committedDocument(prior.profileId, prior.routineSessionId))
            assertEquals(prior, runtime(harness), "The unconfirmed candidate must not be published")
            assertTrue(messages.isEmpty(), "A diverged owner must not receive persistence-failure feedback")
            assertFalse(receipts.any { it.stage == RestControlReceiptStage.PERSISTENCE_FAILED })
            assertEquals(
                RestControlRejectionReason.POST_WRITE_DRIFT,
                receipts.single { it.stage == RestControlReceiptStage.REJECTED }.reason,
            )
        } finally {
            feedbackCollector.cancel()
            receiptCollector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun tapEntryPrecedesEngineDispatchWithSameActionAndOwnerGeneration() = runTest {
        val harness = DWSMTestHarness(this)
        val receipts = mutableListOf<RestControlReceipt>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            prepareTutRest(harness, nextExercise = false)
            val request = harness.dwsm.beginRestControlTap(RestControlAction.EXTEND, seconds = 30)

            assertEquals(listOf(RestControlReceiptStage.TAP_ENTRY), receipts.map { it.stage })
            assertEquals(request.actionId, receipts.single().actionId)
            assertEquals(request.ownerGeneration, receipts.single().ownerGeneration)
            assertTrue(request.ownerGeneration != null)

            var outcome: RestControlOutcome? = null
            harness.dwsm.extendRestTimeWithOutcome(30, request) { outcome = it }

            assertEquals(
                listOf(RestControlReceiptStage.TAP_ENTRY, RestControlReceiptStage.ENGINE_DISPATCH),
                receipts.take(2).map { it.stage },
            )
            assertEquals(setOf(request.actionId), receipts.map { it.actionId }.toSet())
            assertEquals(setOf(request.ownerGeneration), receipts.map { it.ownerGeneration }.toSet())
            assertEquals(null, outcome, "Engine completion remains asynchronous")
            runCurrent()
            assertEquals(RestControlOutcome.Applied, outcome)
        } finally {
            collector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun directEngineControlDoesNotFabricateTapEntry() = runTest {
        val harness = DWSMTestHarness(this)
        val receipts = mutableListOf<RestControlReceipt>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            harness.coordinator._workoutState.value = WorkoutState.Resting(
                restSecondsRemaining = 60,
                nextExerciseName = "Next",
                isLastExercise = false,
                currentSet = 1,
                totalSets = 2,
            )
            harness.coordinator._restSecondsRemaining.value = 60
            harness.coordinator._restOriginalDuration.value = 60

            harness.dwsm.extendRestTimeWithOutcome(30)

            assertTrue(receipts.isNotEmpty())
            assertEquals(RestControlReceiptStage.ENGINE_DISPATCH, receipts.first().stage)
            assertFalse(receipts.any { it.stage == RestControlReceiptStage.TAP_ENTRY })
        } finally {
            collector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun nonRestingControlReportsNotRestingWithoutFeedback() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            var outcome: RestControlOutcome? = null

            harness.dwsm.extendRestTimeWithOutcome(30) { outcome = it }

            assertEquals(RestControlOutcome.Rejected(RestControlRejectionReason.NOT_RESTING), outcome)
            assertTrue(harness.coordinator.userFeedbackEvents.replayCache.isEmpty())
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun noPlanFallbackPublishesPrivacySafeReceiptsAndAppliedOutcome() = runTest {
        val harness = DWSMTestHarness(this)
        val receipts = mutableListOf<RestControlReceipt>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.restControlReceipts.toList(receipts)
        }
        try {
            harness.coordinator._workoutState.value = WorkoutState.Resting(
                restSecondsRemaining = 60,
                nextExerciseName = "Next",
                isLastExercise = false,
                currentSet = 1,
                totalSets = 2,
            )
            harness.coordinator._restSecondsRemaining.value = 60
            harness.coordinator._restOriginalDuration.value = 60
            var outcome: RestControlOutcome? = null

            val request = harness.dwsm.beginRestControlTap(RestControlAction.EXTEND, seconds = 30)
            harness.dwsm.extendRestTimeWithOutcome(30, request) { outcome = it }

            assertEquals(RestControlOutcome.Applied, outcome)
            assertEquals(90, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                listOf(
                    RestControlReceiptStage.TAP_ENTRY,
                    RestControlReceiptStage.ENGINE_DISPATCH,
                    RestControlReceiptStage.PERSISTENCE_NOT_REQUIRED,
                    RestControlReceiptStage.PUBLICATION,
                ),
                receipts.map { it.stage },
            )
            assertTrue(receipts.all { it.actionId == receipts.first().actionId && it.ownerGeneration == null })
            assertTrue(receipts.all { it.action == RestControlAction.EXTEND && it.reason == null })
            assertEquals(30, receipts.first().seconds)
            assertEquals(90, receipts.last().seconds)
        } finally {
            collector.cancel()
            harness.cleanup()
        }
    }

    @Test
    fun extendTutRestBeforeNextSetSurvivesTimerTicks() = runTest {
        verifyExtension(nextExercise = false)
    }

    @Test
    fun extendTutRestBeforeNextExerciseSurvivesTimerTicks() = runTest {
        verifyExtension(nextExercise = true)
    }

    @Test
    fun pauseAndResumeTutRestBeforeNextSetControlsCountdown() = runTest {
        verifyPauseAndResume(nextExercise = false)
    }

    @Test
    fun pauseAndResumeTutRestBeforeNextExerciseControlsCountdown() = runTest {
        verifyPauseAndResume(nextExercise = true)
    }

    private suspend fun TestScope.verifyExtension(nextExercise: Boolean) {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness, nextExercise)
            val before = runtime(harness)
            val remaining = resting(harness).restSecondsRemaining
            val starts = harness.fakeBleRepo.programCommands.size

            harness.dwsm.extendRestTime(30)
            runCurrent()

            val extended = runtime(harness)
            assertEquals(before.originalRestDurationSeconds + 30, extended.originalRestDurationSeconds)
            assertEquals(remaining + 30, resting(harness).restSecondsRemaining)
            assertEquals(extended.originalRestDurationSeconds, harness.coordinator.restOriginalDuration.value)
            assertEquals(extended, committedRuntime(harness, extended))
            assertEquals(
                extended.originalRestDurationSeconds,
                assertIs<RestTransitionPlan.NormalAdvance>(extended.restTransitionPlan).restDurationSeconds,
            )

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(remaining + 25, resting(harness).restSecondsRemaining)
            assertEquals(remaining + 25, harness.coordinator._restSecondsRemaining.value)
            assertEquals(extended, committedRuntime(harness, extended))
            assertEquals(starts, harness.fakeBleRepo.programCommands.size)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun TestScope.verifyPauseAndResume(nextExercise: Boolean) {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness, nextExercise)
            val remaining = resting(harness).restSecondsRemaining
            val starts = harness.fakeBleRepo.programCommands.size

            harness.dwsm.toggleRestPause()
            runCurrent()

            val paused = runtime(harness)
            assertTrue(paused.isRestPaused)
            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(remaining, paused.pausedRestRemainingSeconds)
            assertEquals(null, paused.restDeadlineEpochMs)
            assertEquals(paused, committedRuntime(harness, paused))

            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(remaining, resting(harness).restSecondsRemaining)
            assertEquals(remaining, harness.coordinator._restSecondsRemaining.value)

            harness.dwsm.toggleRestPause()
            runCurrent()

            val resumed = runtime(harness)
            assertFalse(resumed.isRestPaused)
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(null, resumed.pausedRestRemainingSeconds)
            assertEquals(harness.nowMs + remaining * 1_000L, resumed.restDeadlineEpochMs)
            assertEquals(resumed, committedRuntime(harness, resumed))

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(remaining - 5, resting(harness).restSecondsRemaining)
            assertEquals(remaining - 5, harness.coordinator._restSecondsRemaining.value)
            assertEquals(starts, harness.fakeBleRepo.programCommands.size)
            assertEquals(0, harness.coordinator.currentExerciseIndex.value)
            assertEquals(0, harness.coordinator.currentSetIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun TestScope.prepareTutRest(harness: DWSMTestHarness, nextExercise: Boolean) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveSummaryCountdownSeconds(-1)
        val source = createTestRoutine(
            exerciseCount = if (nextExercise) 2 else 1,
            setsPerExercise = if (nextExercise) 1 else 2,
        )
        val routine = source.copy(
            exercises = source.exercises.map { exercise ->
                exercise.copy(
                    programMode = ProgramMode.TUT,
                    setRestSeconds = List(exercise.setReps.size) { 60 },
                )
            },
        )
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(WorkoutState.Active, harness.coordinator.workoutState.value)
        assertEquals(ProgramMode.TUT, harness.coordinator.workoutParameters.value.programMode)

        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        advanceTimeBy(1_000)
        runCurrent()

        val rest = resting(harness)
        assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
        assertEquals(routine.exercises[if (nextExercise) 1 else 0].exercise.displayName, rest.nextExerciseName)
        assertTrue(rest.restSecondsRemaining > 0)
        assertTrue(harness.coordinator.restTimerJob?.isActive == true)
    }

    private fun resting(harness: DWSMTestHarness): WorkoutState.Resting = assertIs(harness.coordinator.workoutState.value)

    private fun runtime(harness: DWSMTestHarness): ActiveWorkoutRuntimeDocument = assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())

    private fun committedRuntime(
        harness: DWSMTestHarness,
        document: ActiveWorkoutRuntimeDocument,
    ): ActiveWorkoutRuntimeDocument? = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
        document.profileId,
        document.routineSessionId,
    )
}

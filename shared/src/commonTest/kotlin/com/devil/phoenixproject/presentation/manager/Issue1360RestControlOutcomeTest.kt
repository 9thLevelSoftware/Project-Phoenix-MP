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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1360: observability/error-contract tests for the rest-control outcome contract.
 *
 * These tests verify that every rest-control command (+30s, pause, reset) delivers exactly one
 * typed Applied/AppliedLocally/Rejected completion: plan-owned Applied follows durable commit,
 * revalidation and publication, while the no-plan fallback is explicitly non-durable. Rejections
 * surface feedback instead of looking like dead buttons, and committed-but-drifted writes remain
 * distinct from persistence failures that never landed. They do NOT claim native acceptance for
 * the reported defects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1360RestControlOutcomeTest {

    @Test
    fun extendRest_appliedCompletionFollowsDurableCommitAndPublication() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val before = runtime(harness)
            val remaining = resting(harness).restSecondsRemaining

            harness.dwsm.extendRestTime(30)
            runCurrent()

            assertEquals(1, completions.size)
            val completion = completions.single()
            assertEquals(RestControlActions.EXTEND_REST, completion.action)
            assertEquals(null, completion.rejectedAtPhase)
            assertEquals(RestControlOutcome.Applied, completion.outcome)
            assertEquals(emptyList(), feedback)

            val extended = runtime(harness)
            assertEquals(before.originalRestDurationSeconds + 30, extended.originalRestDurationSeconds)
            assertEquals(remaining + 30, resting(harness).restSecondsRemaining)
            assertEquals(extended, committedRuntime(harness, extended))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun toggleRestPause_appliedCompletion() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)

            harness.dwsm.toggleRestPause()
            runCurrent()

            assertEquals(1, completions.size)
            val completion = completions.single()
            assertEquals(RestControlActions.TOGGLE_REST_PAUSE, completion.action)
            assertEquals(RestControlOutcome.Applied, completion.outcome)
            assertEquals(emptyList(), feedback)

            val paused = runtime(harness)
            assertTrue(paused.isRestPaused)
            assertEquals(paused, committedRuntime(harness, paused))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun fallbackExtend_reportsLocalApplicationWithoutDurableCommit() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val before = runtime(harness)
            val remainingBefore = harness.coordinator._restSecondsRemaining.value
            harness.coordinator._restTransitionPlan.value = null
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.extendRestTime(15)
            runCurrent()

            assertEquals(1, completions.size)
            assertEquals(RestControlActions.EXTEND_REST, completions.single().action)
            assertEquals(RestControlOutcome.AppliedLocally, completions.single().outcome)
            assertEquals(emptyList(), feedback)
            assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(before, committedRuntime(harness, before))
            assertEquals(before.originalRestDurationSeconds + 15, harness.coordinator._restOriginalDuration.value)
            assertEquals(remainingBefore + 15, harness.coordinator._restSecondsRemaining.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun fallbackPause_reportsLocalApplicationWithoutDurableCommit() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val before = runtime(harness)
            harness.coordinator._restTransitionPlan.value = null
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.toggleRestPause()
            runCurrent()

            assertEquals(1, completions.size)
            assertEquals(RestControlActions.TOGGLE_REST_PAUSE, completions.single().action)
            assertEquals(RestControlOutcome.AppliedLocally, completions.single().outcome)
            assertEquals(emptyList(), feedback)
            assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(before, committedRuntime(harness, before))
            assertTrue(harness.coordinator.isRestPaused.value)
            assertTrue(resting(harness).restSecondsRemaining >= 0)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun fallbackReset_reportsLocalApplicationWithoutDurableCommit() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val before = runtime(harness)
            harness.coordinator._restTransitionPlan.value = null
            harness.dwsm.extendRestTime(15)
            harness.dwsm.toggleRestPause()
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size

            harness.dwsm.resetRestTimer()
            runCurrent()

            assertEquals(1, completions.size)
            assertEquals(RestControlActions.RESET_REST_TIMER, completions.single().action)
            assertEquals(RestControlOutcome.AppliedLocally, completions.single().outcome)
            assertEquals(emptyList(), feedback)
            assertEquals(replacementsBefore, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(before, committedRuntime(harness, before))
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(before.originalRestDurationSeconds + 15, harness.coordinator._restSecondsRemaining.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun extendRest_divergedAfterFailedReplace_reportsUncertainPostWriteDrift() = runTest {
        val harness = DWSMTestHarness(this)
        var divergedDocument: ActiveWorkoutRuntimeDocument? = null
        try {
            prepareTutRest(harness)
            val before = runtime(harness)
            val remainingBefore = resting(harness).restSecondsRemaining
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val replacementsBefore = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { candidate ->
                val divergedDuration = candidate.originalRestDurationSeconds + 1
                divergedDocument = candidate.copy(
                    restTransitionPlan = assertNotNull(candidate.restTransitionPlan)
                        .withRestDurationSeconds(divergedDuration),
                    originalRestDurationSeconds = divergedDuration,
                )
                harness.fakeActiveWorkoutRuntimeRepository.replacePreservingRevisionTimestamp(
                    profileId = candidate.profileId,
                    routineSessionId = candidate.routineSessionId,
                    document = assertNotNull(divergedDocument),
                )
                throw IllegalStateException("test replacement changed after commit")
            }

            harness.dwsm.extendRestTime(15)
            runCurrent()

            assertEquals(1, completions.size)
            val rejection = assertIs<RestControlOutcome.Rejected>(completions.single().outcome)
            assertEquals(RestControlRejection.PostWriteDrift, rejection.reason)
            assertEquals("persist", completions.single().rejectedAtPhase)
            assertEquals(listOf(RestControlFeedback.POST_WRITE_DRIFT), feedback)
            assertEquals(replacementsBefore + 1, harness.fakeActiveWorkoutRuntimeRepository.replacements.size)
            assertEquals(assertNotNull(divergedDocument), committedRuntime(harness, before))
            assertEquals(before, runtime(harness))
            assertEquals(remainingBefore, resting(harness).restSecondsRemaining)
        } finally {
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
            harness.cleanup()
        }
    }

    @Test
    fun extendRest_persistenceFailure_rejectsWithFeedbackAndNoOptimisticDisplay() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val before = runtime(harness)
            val remainingBefore = resting(harness).restSecondsRemaining

            harness.fakeActiveWorkoutRuntimeRepository.failingReplaceCallsRemaining = 1
            harness.dwsm.extendRestTime(30)
            runCurrent()

            assertEquals(1, completions.size)
            val completion = completions.single()
            val rejection = assertIs<RestControlOutcome.Rejected>(completion.outcome)
            assertEquals(RestControlRejection.PersistenceFailed, rejection.reason)
            assertEquals("persist", completion.rejectedAtPhase)
            assertEquals(listOf(RestControlFeedback.PERSISTENCE_FAILED), feedback)

            // The write never landed and nothing was displayed optimistically.
            assertEquals(before, committedRuntime(harness, before))
            assertEquals(before, runtime(harness))
            assertEquals(remainingBefore, resting(harness).restSecondsRemaining)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun extendRest_commitLandedButReplaceFailed_reportsDriftNotPersistenceFailure() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val before = runtime(harness)

            // The fake persists the document and only then throws, so the write landed while
            // replaceRuntimeDocument reports failure: this must be distinguishable from a
            // persistence failure that never landed.
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = {
                throw IllegalStateException("test post-commit failure")
            }
            harness.dwsm.extendRestTime(30)
            runCurrent()

            assertEquals(1, completions.size)
            val completion = completions.single()
            val rejection = assertIs<RestControlOutcome.Rejected>(completion.outcome)
            assertEquals(RestControlRejection.PostWriteDrift, rejection.reason)
            assertEquals("persist", completion.rejectedAtPhase)
            assertEquals(listOf(RestControlFeedback.POST_WRITE_DRIFT), feedback)

            // The durable write DID land (commit status COMMITTED) — reported as drift, never
            // replayed, rolled back, or reported as a persistence failure.
            val committed = committedRuntime(harness, before)
            assertNotNull(committed)
            assertEquals(before.originalRestDurationSeconds + 30, committed.originalRestDurationSeconds)
            assertTrue(
                completions.none {
                    (it.outcome as? RestControlOutcome.Rejected)?.reason == RestControlRejection.PersistenceFailed
                },
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun extendRest_postWriteDriftOnRevalidation_reportsDriftWithoutPublication() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            prepareTutRest(harness)
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)
            val before = runtime(harness)
            val remainingBefore = resting(harness).restSecondsRemaining

            // The write commits, then owned state moves on before revalidated publication.
            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = {
                harness.coordinator._currentSetIndex.value = 99
            }
            harness.dwsm.extendRestTime(30)
            runCurrent()

            assertEquals(1, completions.size)
            val completion = completions.single()
            val rejection = assertIs<RestControlOutcome.Rejected>(completion.outcome)
            assertEquals(RestControlRejection.PostWriteDrift, rejection.reason)
            assertEquals("revalidate", completion.rejectedAtPhase)
            assertEquals(listOf(RestControlFeedback.POST_WRITE_DRIFT), feedback)

            // Committed durably but publication was refused: display state is not optimistically
            // updated even though the write landed.
            val committed = committedRuntime(harness, before)
            assertNotNull(committed)
            assertEquals(before.originalRestDurationSeconds + 30, committed.originalRestDurationSeconds)
            assertEquals(remainingBefore, harness.coordinator._restSecondsRemaining.value)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun restControls_notResting_rejectWithFeedback() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val completions = collectCompletions(harness)
            val feedback = collectFeedback(harness)

            harness.dwsm.extendRestTime(30)
            harness.dwsm.toggleRestPause()
            harness.dwsm.resetRestTimer()
            runCurrent()

            assertEquals(3, completions.size)
            assertTrue(completions.all { it.rejectedAtPhase == "dispatch" })
            assertEquals(
                listOf(
                    RestControlActions.EXTEND_REST,
                    RestControlActions.TOGGLE_REST_PAUSE,
                    RestControlActions.RESET_REST_TIMER,
                ),
                completions.map { it.action },
            )
            completions.forEach { completion ->
                val rejection = assertIs<RestControlOutcome.Rejected>(completion.outcome)
                assertEquals(RestControlRejection.NotResting, rejection.reason)
            }
            assertEquals(
                listOf(
                    RestControlFeedback.NOT_RESTING,
                    RestControlFeedback.NOT_RESTING,
                    RestControlFeedback.NOT_RESTING,
                ),
                feedback,
            )
        } finally {
            harness.cleanup()
        }
    }

    private fun TestScope.collectCompletions(harness: DWSMTestHarness): List<RestControlCompletion> {
        val completions = mutableListOf<RestControlCompletion>()
        backgroundScope.launch {
            harness.coordinator._restControlOutcomes.collect { completions += it }
        }
        runCurrent()
        return completions
    }

    private fun TestScope.collectFeedback(harness: DWSMTestHarness): List<String> {
        val feedback = mutableListOf<String>()
        backgroundScope.launch {
            harness.coordinator._userFeedbackEvents.collect { feedback += it }
        }
        runCurrent()
        return feedback
    }

    private suspend fun TestScope.prepareTutRest(harness: DWSMTestHarness) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.setActiveSummaryCountdownSeconds(-1)
        val source = createTestRoutine(exerciseCount = 1, setsPerExercise = 2)
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

        val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
        harness.activeSessionEngine.handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
        advanceTimeBy(1_000)
        runCurrent()

        assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value)
        assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
    }

    private fun resting(harness: DWSMTestHarness): WorkoutState.Resting = assertIs(harness.coordinator.workoutState.value)

    private fun runtime(harness: DWSMTestHarness): ActiveWorkoutRuntimeDocument =
        assertNotNull(harness.activeSessionEngine.activeRuntimeDocumentForTest())

    private fun committedRuntime(
        harness: DWSMTestHarness,
        document: ActiveWorkoutRuntimeDocument,
    ): ActiveWorkoutRuntimeDocument? = harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
        document.profileId,
        document.routineSessionId,
    )
}

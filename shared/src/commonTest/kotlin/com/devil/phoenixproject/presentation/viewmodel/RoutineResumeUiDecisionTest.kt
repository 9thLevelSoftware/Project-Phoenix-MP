package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLookupKey
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRowRevision
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscardResult
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.manager.RoutineResumeManagerGeneration
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class RoutineResumeUiDecisionTest {
    private data class CompletionCase(
        val tokenCurrent: Boolean,
        val contextCurrent: Boolean,
        val outcome: RoutineResumeUiOutcome,
        val expected: RoutineResumeCompletionDisposition,
        val label: String,
    )

    private val routine = WorkoutStateFixtures.createTestRoutine(
        exerciseCount = 1,
        setsPerExercise = 2,
    )
    private val progressInfo = ResumableProgressInfo(
        exerciseName = "Exercise",
        currentSet = 2,
        totalSets = 2,
        currentExercise = 1,
        totalExercises = 1,
    )
    private val generation = RoutineResumeManagerGeneration(
        configurationInputEpoch = 11L,
        recoveryPublicationEpoch = 12L,
    )

    @Test
    fun `persisted resume maps every engine result to an explicit UI decision`() {
        val handle = persistedHandle()
        val cases = listOf(
            ActiveWorkoutRuntimeResumeResult.RestoredRest to
                RoutineResumeUiDecision.NavigateActiveWorkout,
            ActiveWorkoutRuntimeResumeResult.ManualSetReady(exerciseIndex = 1, setIndex = 2) to
                RoutineResumeUiDecision.NavigateManualSetReady(exerciseIndex = 1, setIndex = 2),
            ActiveWorkoutRuntimeResumeResult.FreshStart to
                RoutineResumeUiDecision.EnterFreshRoutine,
            ActiveWorkoutRuntimeResumeResult.Missing to
                RoutineResumeUiDecision.EnterFreshRoutine,
            ActiveWorkoutRuntimeResumeResult.RetryableFailure to
                RoutineResumeUiDecision.RetainDialog(RoutineResumeRetryAction.RESUME),
            ActiveWorkoutRuntimeResumeResult.Superseded to
                RoutineResumeUiDecision.DismissDialog,
        )

        cases.forEach { (result, expected) ->
            assertEquals(expected, routineResumeUiDecision(handle, result), "result=$result")
        }
    }

    @Test
    fun `in memory resume recognizes only the exact missing sentinel`() {
        val handle = inMemoryHandle()
        val cases = listOf(
            ActiveWorkoutRuntimeResumeResult.Missing to
                RoutineResumeUiDecision.ResumeInMemory,
            ActiveWorkoutRuntimeResumeResult.RetryableFailure to
                RoutineResumeUiDecision.RetainDialog(RoutineResumeRetryAction.RESUME),
            ActiveWorkoutRuntimeResumeResult.Superseded to
                RoutineResumeUiDecision.DismissDialog,
            ActiveWorkoutRuntimeResumeResult.RestoredRest to
                RoutineResumeUiDecision.DismissDialog,
            ActiveWorkoutRuntimeResumeResult.ManualSetReady(exerciseIndex = 0, setIndex = 0) to
                RoutineResumeUiDecision.DismissDialog,
            ActiveWorkoutRuntimeResumeResult.FreshStart to
                RoutineResumeUiDecision.DismissDialog,
        )

        cases.forEach { (result, expected) ->
            assertEquals(expected, routineResumeUiDecision(handle, result), "result=$result")
        }
    }

    @Test
    fun `discard maps cleanup success retry and stale results without a resume escape`() {
        val cases = listOf(
            RoutineResumeDiscardResult.Discarded to
                RoutineResumeUiDecision.EnterFreshRoutine,
            RoutineResumeDiscardResult.Missing to
                RoutineResumeUiDecision.EnterFreshRoutine,
            RoutineResumeDiscardResult.RetryableFailure to
                RoutineResumeUiDecision.RetainDialog(RoutineResumeRetryAction.DISCARD),
            RoutineResumeDiscardResult.Superseded to
                RoutineResumeUiDecision.DismissDialog,
        )

        cases.forEach { (result, expected) ->
            assertEquals(expected, routineResumeDiscardUiDecision(result), "result=$result")
        }
    }

    @Test
    fun `all three entry points reject a token that changes while routine loading is suspended`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            var currentToken = 41
            val loadEntered = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val authority = RoutineResumeActionAuthority(
                entryPoint = entryPoint,
                actionToken = currentToken,
                currentToken = { currentToken },
                contextIsCurrent = { true },
            )
            val result = async {
                authority.awaitCurrentPublication { publicationStillCurrent ->
                    loadEntered.complete(Unit)
                    releaseLoad.await()
                    assertFalse(publicationStillCurrent(), entryPoint.name)
                    true
                }
            }

            loadEntered.await()
            currentToken += 1
            releaseLoad.complete(Unit)

            assertFalse(result.await(), entryPoint.name)
            assertFalse(authority.mayCommitInMemory(handleStillCurrent = true), entryPoint.name)
        }
    }

    @Test
    fun `a stale cycle callback cannot dismiss the newer candidate`() {
        listOf(
            RoutineResumeEntryPoint.HOME_CYCLE,
            RoutineResumeEntryPoint.TRAINING_CYCLES,
        ).forEach { entryPoint ->
            var currentToken = 8
            var invalidCurrentContextDismissals = 0
            val authority = RoutineResumeActionAuthority(
                entryPoint = entryPoint,
                actionToken = currentToken,
                currentToken = { currentToken },
                contextIsCurrent = { true },
            )
            currentToken += 1

            assertFalse(
                authority.validateCurrentContext(
                    contextIsValid = false,
                    onCurrentInvalid = { invalidCurrentContextDismissals += 1 },
                ),
                entryPoint.name,
            )
            assertEquals(0, invalidCurrentContextDismissals, entryPoint.name)
        }
    }

    @Test
    fun `action authority distinguishes a newer token from transient profile context loss`() {
        var currentToken = 12
        var readyContext = true
        val authority = RoutineResumeActionAuthority(
            entryPoint = RoutineResumeEntryPoint.DAILY_ROUTINES,
            actionToken = currentToken,
            currentToken = { currentToken },
            contextIsCurrent = { readyContext },
        )

        assertTrue(authority.tokenIsCurrent())
        assertTrue(authority.contextIsCurrent())
        readyContext = false
        assertTrue(authority.tokenIsCurrent())
        assertFalse(authority.contextIsCurrent())
        assertFalse(authority.isCurrent())

        currentToken += 1
        readyContext = true
        assertFalse(authority.tokenIsCurrent())
        assertTrue(authority.contextIsCurrent())
        assertFalse(authority.isCurrent())
    }

    @Test
    fun `completion classification never lets an old callback mutate the retained dialog`() {
        val currentOutcome = RoutineResumeUiOutcome.EnterSetReady(exerciseIndex = 1, setIndex = 2)
        val cases = listOf(
            CompletionCase(
                tokenCurrent = false,
                contextCurrent = false,
                outcome = currentOutcome,
                expected = RoutineResumeCompletionDisposition.IgnoreStaleToken,
                label = "stale token and context",
            ),
            CompletionCase(
                tokenCurrent = false,
                contextCurrent = true,
                outcome = currentOutcome,
                expected = RoutineResumeCompletionDisposition.IgnoreStaleToken,
                label = "stale token with current context",
            ),
            CompletionCase(
                tokenCurrent = true,
                contextCurrent = false,
                outcome = currentOutcome,
                expected = RoutineResumeCompletionDisposition.UnlockRetainedDialog,
                label = "transient profile context loss",
            ),
            CompletionCase(
                tokenCurrent = true,
                contextCurrent = true,
                outcome = RoutineResumeUiOutcome.StaleNoOp,
                expected = RoutineResumeCompletionDisposition.UnlockRetainedDialog,
                label = "same-token stale operation",
            ),
            CompletionCase(
                tokenCurrent = true,
                contextCurrent = true,
                outcome = currentOutcome,
                expected = RoutineResumeCompletionDisposition.Apply(currentOutcome),
                label = "current completion",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                classifyRoutineResumeCompletion(
                    tokenCurrent = case.tokenCurrent,
                    contextCurrent = case.contextCurrent,
                    outcome = case.outcome,
                ),
                case.label,
            )
        }
    }

    @Test
    fun `the exact current entry point can commit load callback and invalid-context dismissal`() = runTest {
        RoutineResumeEntryPoint.entries.forEach { entryPoint ->
            var invalidCurrentContextDismissals = 0
            val authority = RoutineResumeActionAuthority(
                entryPoint = entryPoint,
                actionToken = 3,
                currentToken = { 3 },
                contextIsCurrent = { true },
            )

            assertTrue(authority.awaitCurrentPublication { stillCurrent -> stillCurrent() }, entryPoint.name)
            assertTrue(authority.mayCommitInMemory(handleStillCurrent = true), entryPoint.name)
            assertFalse(authority.mayCommitInMemory(handleStillCurrent = false), entryPoint.name)
            assertFalse(
                authority.validateCurrentContext(
                    contextIsValid = false,
                    onCurrentInvalid = { invalidCurrentContextDismissals += 1 },
                ),
                entryPoint.name,
            )
            assertEquals(1, invalidCurrentContextDismissals, entryPoint.name)
        }
    }

    private fun inMemoryHandle() = RoutineResumeHandle.InMemory(
        selectedProfileId = routine.profileId,
        selectedRoutine = routine,
        activeRoutineSnapshot = routine,
        progressInfo = progressInfo,
        launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
        cycleId = null,
        cycleDayNumber = null,
        managerGeneration = generation,
        exerciseIndex = 0,
        setIndex = 1,
        routineSessionId = "routine-session",
        activeLaunchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
        activeCycleId = null,
        activeCycleDayNumber = null,
    )

    private fun persistedHandle() = RoutineResumeHandle.Persisted(
        selectedProfileId = routine.profileId,
        selectedRoutine = routine,
        lookupKey = ActiveWorkoutRuntimeLookupKey(
            profileId = routine.profileId,
            routineSessionId = "routine-session",
        ),
        rowRevision = ActiveWorkoutRuntimeRowRevision(
            documentVersion = 2L,
            updatedAtEpochMs = 3L,
            encodedPayloadIdentity = "payload-identity",
        ),
        progressInfo = progressInfo,
        launchOrigin = RoutineLaunchOrigin.DAILY_ROUTINES,
        cycleId = null,
        cycleDayNumber = null,
        managerGeneration = generation,
        manualRecoveryCoordinates = null,
    )
}

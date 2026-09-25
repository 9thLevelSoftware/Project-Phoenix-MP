package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.RestoredRetrySourceAuthoritySnapshot
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.RestoredWorkoutCommandTemplateSnapshot
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
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
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RestoredRuntimeTimerMigrationRaceTest {
    private enum class ZeroCutoverMutation { RESET, EXTEND }

    @Test
    fun `captured tick cannot clear the same timer job after extend migrates its document`() = runTest {
        val harness = DWSMTestHarness(this)
        val tickCaptured = CompletableDeferred<Unit>()
        val releaseTick = CompletableDeferred<Unit>()
        var interceptNextTick = true
        try {
            installAndResumeTimer(
                harness = harness,
                routineSessionId = "tick-extend-migration",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = { tickOwner, _ ->
                if (interceptNextTick && tickOwner == owner) {
                    interceptNextTick = false
                    tickCaptured.complete(Unit)
                    releaseTick.await()
                }
            }

            advanceTimeBy(101L)
            runCurrent()
            assertTrue(tickCaptured.isCompleted)

            harness.dwsm.extendRestTime(10)
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
            assertEquals(40, harness.coordinator._restSecondsRemaining.value)
            assertEquals(70, harness.coordinator._restOriginalDuration.value)

            releaseTick.complete(Unit)
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
            assertTrue(timerJob.isActive)
            val remainingAfterMigration = harness.coordinator._restSecondsRemaining.value

            advanceTimeBy(1_100L)
            runCurrent()

            assertTrue(harness.coordinator._restSecondsRemaining.value < remainingAfterMigration)
            assertEquals(
                harness.coordinator._restSecondsRemaining.value,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
        } finally {
            releaseTick.complete(Unit)
            harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = null
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `captured tick cannot clear the same timer job after accept or decline migrates its plan`() = runTest {
        listOf(false, true).forEach { accept ->
            val harness = enabledTimerHarness()
            val tickCaptured = CompletableDeferred<Unit>()
            val releaseTick = CompletableDeferred<Unit>()
            var interceptNextTick = true
            try {
                installAndResumeTimer(
                    harness = harness,
                    routineSessionId = if (accept) "tick-accept-migration" else "tick-decline-migration",
                    restDeadlineEpochMs = harness.nowMs + 30_000L,
                    unresolved = true,
                )
                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(
                    harness.restTransitionPlan.value,
                )
                val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
                val timerDeadline = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = { tickOwner, _ ->
                    if (interceptNextTick && tickOwner == owner) {
                        interceptNextTick = false
                        tickCaptured.complete(Unit)
                        releaseTick.await()
                    }
                }

                advanceTimeBy(101L)
                runCurrent()
                assertTrue(tickCaptured.isCompleted, "accept=$accept")

                val command = if (accept) {
                    RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY)
                } else {
                    RestTransitionCommand.Decline(unresolved.actionIdentity())
                }
                assertIs<RestTransitionReduction.Changed>(
                    harness.activeSessionEngine.applyRestTransitionAwait(command),
                )

                assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
                assertEquals(
                    timerDeadline,
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                if (accept) {
                    assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
                } else {
                    assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
                }

                releaseTick.complete(Unit)
                runCurrent()

                assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
                assertTrue(timerJob.isActive, "accept=$accept")
                val remainingAfterMigration = harness.coordinator._restSecondsRemaining.value

                advanceTimeBy(1_100L)
                runCurrent()

                assertTrue(
                    harness.coordinator._restSecondsRemaining.value < remainingAfterMigration,
                    "accept=$accept",
                )
            } finally {
                releaseTick.complete(Unit)
                harness.activeSessionEngine.beforeRestoredRestTimerTickPublishForTest = null
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `reset or extend at zero publication cutover leaves a live timer that keeps counting`() = runTest {
        enumValues<ZeroCutoverMutation>().forEach { mutation ->
            val harness = DWSMTestHarness(this)
            val zeroPublished = CompletableDeferred<Unit>()
            val releaseExpiryDetach = CompletableDeferred<Unit>()
            var interceptZero = true
            try {
                installAndResumeTimer(
                    harness = harness,
                    routineSessionId = "zero-cutover-${mutation.name.lowercase()}",
                    restDeadlineEpochMs = harness.nowMs + 1_000L,
                )
                val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                harness.activeSessionEngine.afterRestoredRestTimerZeroPublishForTest = { tickOwner ->
                    if (interceptZero && tickOwner == owner) {
                        interceptZero = false
                        zeroPublished.complete(Unit)
                        releaseExpiryDetach.await()
                    }
                }

                advanceTimeBy(1_001L)
                runCurrent()
                assertTrue(zeroPublished.isCompleted, mutation.name)
                assertEquals(0, harness.coordinator._restSecondsRemaining.value, mutation.name)
                assertEquals(
                    0,
                    assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                    mutation.name,
                )

                when (mutation) {
                    ZeroCutoverMutation.RESET -> harness.dwsm.resetRestTimer()
                    ZeroCutoverMutation.EXTEND -> harness.dwsm.extendRestTime(10)
                }
                runCurrent()

                val expectedRemaining = when (mutation) {
                    ZeroCutoverMutation.RESET -> 60
                    ZeroCutoverMutation.EXTEND -> 10
                }
                val expectedOriginal = when (mutation) {
                    ZeroCutoverMutation.RESET -> 60
                    ZeroCutoverMutation.EXTEND -> 70
                }
                assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), mutation.name)
                assertEquals(expectedRemaining, harness.coordinator._restSecondsRemaining.value, mutation.name)
                assertEquals(expectedOriginal, harness.coordinator._restOriginalDuration.value, mutation.name)

                releaseExpiryDetach.complete(Unit)
                runCurrent()

                val liveTimerJob = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerJobForTest(),
                    mutation.name,
                )
                assertTrue(liveTimerJob.isActive, mutation.name)
                assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(), mutation.name)

                advanceTimeBy(1_100L)
                runCurrent()

                assertTrue(
                    harness.coordinator._restSecondsRemaining.value < expectedRemaining,
                    mutation.name,
                )
                assertEquals(
                    harness.coordinator._restSecondsRemaining.value,
                    assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                    mutation.name,
                )
            } finally {
                releaseExpiryDetach.complete(Unit)
                harness.activeSessionEngine.afterRestoredRestTimerZeroPublishForTest = null
                harness.cleanup()
                runCurrent()
            }
        }
    }

    private suspend fun TestScope.installAndResumeTimer(
        harness: DWSMTestHarness,
        routineSessionId: String,
        restDeadlineEpochMs: Long,
        unresolved: Boolean = false,
    ) {
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
            unresolved = unresolved,
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
    }

    private fun timerDocument(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        logicalSetKey: LogicalSetKey,
        restDeadlineEpochMs: Long,
        unresolved: Boolean,
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
        val plan = if (unresolved) {
            RestTransitionPlan.UnresolvedDropOffer(
                transitionId = normalPlan.transitionId,
                sourceExecutionId = normalPlan.sourceExecutionId,
                logicalSetKey = normalPlan.logicalSetKey,
                offerId = "offer-${logicalSetKey.routineSessionId}",
                plannedSetId = normalPlan.plannedSetId,
                candidates = listOf(
                    DropSetCandidate(DropPercentage.TEN, 22.5f, 0.9f),
                    DropSetCandidate(DropPercentage.TWENTY, 20f, 0.8f),
                    DropSetCandidate(DropPercentage.THIRTY, 17.5f, 0.7f),
                ),
                normalAdvance = normalPlan,
            )
        } else {
            normalPlan
        }
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

    private fun TestScope.enabledTimerHarness() = DWSMTestHarness(
        testScope = this,
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(
            DropSetFeatureGate { true },
            DropSetCandidateResolver(),
        ),
        dropSetConfigurationProvider = {
            DropSetConfiguration(enabled = true, minimumWeightPerCableKg = 5f)
        },
    )

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

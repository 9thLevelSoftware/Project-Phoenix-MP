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
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RestoredRuntimeTimerTest {
    private enum class TimerPlanCase { NORMAL, DECLINED, UNRESOLVED, ACCEPTED }

    @Test
    fun `active persisted rest owns one timer job and counts down from a monotonic deadline`() = runTest {
        val wallClockEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { wallClockEpochMs })
        try {
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "active-timer",
                restDeadlineEpochMs = wallClockEpochMs + 30_000L,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            val restoredOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(restoredOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertTrue(timerJob.isActive)
            assertEquals(30, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                30,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
            assertFalse(harness.coordinator.isRestPaused.value)
            assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertNull(harness.coordinator.restTimerJob)
            assertNull(harness.coordinator.restDeadlineElapsedRealtimeMs)

            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(29, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                29,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
            assertTrue(timerJob.isActive)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `backward wall clock clamps once then the restored monotonic timer decreases`() = runTest {
        val frozenWallClockEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { frozenWallClockEpochMs })
        try {
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "backward-clock",
                restDeadlineEpochMs = frozenWallClockEpochMs + 3_600_000L,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            assertEquals(60, harness.coordinator._restSecondsRemaining.value)
            assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())

            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(59, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                59,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `paused restored rest retains owner and exact remaining time without a job`() = runTest {
        val wallClockEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { wallClockEpochMs })
        try {
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "paused-timer",
                restDeadlineEpochMs = null,
                pausedRestRemainingSeconds = 17,
                isRestPaused = true,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            val restoredOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest())
            assertEquals(restoredOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertEquals(17, harness.coordinator._restSecondsRemaining.value)
            assertEquals(
                17,
                assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
            )
            assertTrue(harness.coordinator.isRestPaused.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertNull(harness.coordinator.restTimerJob)
            assertNull(harness.coordinator.restDeadlineElapsedRealtimeMs)

            advanceTimeBy(5_000L)
            runCurrent()

            assertEquals(17, harness.coordinator._restSecondsRemaining.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.coordinator.restTimerJob)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `forward expired and missing deadlines retain owner at zero without a job`() = runTest {
        val epochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val cases = listOf(
            ZeroDeadlineCase(
                label = "forward wall clock",
                nowEpochMs = epochMs + 3_600_000L,
                deadlineEpochMs = epochMs + 30_000L,
            ),
            ZeroDeadlineCase(
                label = "expired deadline",
                nowEpochMs = epochMs,
                deadlineEpochMs = epochMs,
            ),
            ZeroDeadlineCase(
                label = "missing deadline",
                nowEpochMs = epochMs,
                deadlineEpochMs = null,
            ),
        )

        cases.forEachIndexed { index, case ->
            val harness = DWSMTestHarness(this, wallClockMillisProvider = { case.nowEpochMs })
            try {
                val installed = installTimerRuntime(
                    harness = harness,
                    routineSessionId = "zero-deadline-$index",
                    restDeadlineEpochMs = case.deadlineEpochMs,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(installed.handle),
                    case.label,
                )
                runCurrent()

                val restoredOwner = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRuntimeOwnerForTest(),
                    case.label,
                )
                assertEquals(
                    restoredOwner,
                    harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(),
                    case.label,
                )
                assertEquals(0, harness.coordinator._restSecondsRemaining.value, case.label)
                assertEquals(
                    0,
                    assertIs<WorkoutState.Resting>(harness.coordinator.workoutState.value).restSecondsRemaining,
                    case.label,
                )
                assertNull(harness.coordinator.restTimerJob, case.label)
                assertNull(harness.coordinator.restDeadlineElapsedRealtimeMs, case.label)
                assertNull(
                    harness.activeSessionEngine.currentRestoredRestTimerJobForTest(),
                    case.label,
                )
                assertNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                    case.label,
                )
                assertEquals(installed.document.restTransitionPlan, harness.restTransitionPlan.value, case.label)
            } finally {
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `duplicate exact Resume retains identical restored timer owner and job`() = runTest {
        val wallClockEpochMs = DWSMTestHarness.TEST_WALL_CLOCK_EPOCH_MS
        val harness = DWSMTestHarness(this, wallClockMillisProvider = { wallClockEpochMs })
        try {
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "duplicate-resume",
                restDeadlineEpochMs = wallClockEpochMs + 30_000L,
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val firstOwner = assertNotNull(
                harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(),
            )
            val firstJob: Job = assertNotNull(
                harness.activeSessionEngine.currentRestoredRestTimerJobForTest(),
            )

            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            assertEquals(firstOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === firstJob)
            assertNull(harness.coordinator.restTimerJob)
            assertNull(harness.coordinator.restDeadlineElapsedRealtimeMs)
            assertTrue(firstJob.isActive)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `restored active timer expiry stays manual for every durable plan even with autoplay enabled`() = runTest {
        enumValues<TimerPlanCase>().forEach { planCase ->
            val harness = enabledTimerHarness()
            try {
                harness.setActiveSummaryCountdownSeconds(10)
                runCurrent()
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                val installed = installTimerRuntime(
                    harness = harness,
                    routineSessionId = "manual-expiry-${planCase.name.lowercase()}",
                    restDeadlineEpochMs = harness.nowMs + 2_000L,
                    planCase = planCase,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(installed.handle),
                    planCase.name,
                )
                runCurrent()
                assertIs<MachineTeardownState.Ready>(harness.dwsm.machineTeardownState.value, planCase.name)
                val timerOwner = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(),
                    planCase.name,
                )
                val timerJob = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerJobForTest(),
                    planCase.name,
                )
                val timerDeadline = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                    planCase.name,
                )
                val durablePlan = assertNotNull(installed.document.restTransitionPlan, planCase.name)
                val replacementsBeforeExpiry = harness.fakeActiveWorkoutRuntimeRepository.replacements.size
                val navigationLookupsBeforeExpiry = harness.dwsm.restTransitionNavigationLookupsForTest
                val commandsBeforeExpiry = harness.fakeBleRepo.commandsReceived.size
                val configurationsBeforeExpiry = harness.fakeBleRepo.workoutParameters.size

                assertTrue(timerJob.isActive, planCase.name)
                assertTrue(timerDeadline > 0L, planCase.name)
                assertNull(
                    harness.activeSessionEngine.currentRestoredAcceptedRetryPermissionOwnerForTest(),
                    planCase.name,
                )
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), planCase.name)

                advanceTimeBy(2_100L)
                runCurrent()

                assertEquals(
                    0,
                    assertIs<WorkoutState.Resting>(
                        harness.coordinator.workoutState.value,
                        planCase.name,
                    ).restSecondsRemaining,
                    planCase.name,
                )
                assertEquals(
                    timerOwner,
                    harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest(),
                    planCase.name,
                )
                assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest(), planCase.name)
                assertNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                    planCase.name,
                )
                assertFalse(timerJob.isActive, planCase.name)
                assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest(), planCase.name)
                assertEquals(durablePlan, harness.restTransitionPlan.value, planCase.name)
                assertEquals(
                    installed.document,
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                    planCase.name,
                )
                assertEquals(
                    replacementsBeforeExpiry,
                    harness.fakeActiveWorkoutRuntimeRepository.replacements.size,
                    planCase.name,
                )
                assertEquals(navigationLookupsBeforeExpiry, harness.dwsm.restTransitionNavigationLookupsForTest, planCase.name)
                assertNull(
                    harness.activeSessionEngine.currentRestoredAcceptedRetryPermissionOwnerForTest(),
                    planCase.name,
                )
                assertNull(harness.activeSessionEngine.currentExecutionLeaseOrNull(), planCase.name)
                assertEquals(commandsBeforeExpiry, harness.fakeBleRepo.commandsReceived.size, planCase.name)
                assertEquals(configurationsBeforeExpiry, harness.fakeBleRepo.workoutParameters.size, planCase.name)
            } finally {
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `restored unresolved decisions migrate the exact active timer without restarting it`() = runTest {
        listOf(false, true).forEach { accept ->
            val harness = enabledTimerHarness()
            try {
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                val installed = installTimerRuntime(
                    harness = harness,
                    routineSessionId = if (accept) "timer-decision-accept" else "timer-decision-decline",
                    restDeadlineEpochMs = harness.nowMs + 30_000L,
                    planCase = TimerPlanCase.UNRESOLVED,
                )
                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(installed.handle),
                )
                runCurrent()

                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
                val timerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
                val timerDeadline = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                val initialRemaining = harness.coordinator._restSecondsRemaining.value
                val command = if (accept) {
                    RestTransitionCommand.Accept(unresolved.actionIdentity(), DropPercentage.TWENTY)
                } else {
                    RestTransitionCommand.Decline(unresolved.actionIdentity())
                }

                assertIs<RestTransitionReduction.Changed>(
                    harness.activeSessionEngine.applyRestTransitionAwait(command),
                )

                assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
                assertEquals(
                    timerDeadline,
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                assertTrue(timerJob.isActive)
                if (accept) {
                    assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
                } else {
                    assertIs<RestTransitionPlan.Declined>(harness.restTransitionPlan.value)
                }

                advanceTimeBy(1_100L)
                runCurrent()

                assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
                assertTrue(harness.coordinator._restSecondsRemaining.value < initialRemaining)
            } finally {
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `restored decision timer preserves precommit cancellation and reconciles postcommit cancellation`() = runTest {
        listOf(false, true).forEach { committed ->
            val harness = enabledTimerHarness()
            try {
                harness.fakeBleRepo.simulateConnect("Vee_Test")
                val installed = installTimerRuntime(
                    harness = harness,
                    routineSessionId = if (committed) {
                        "timer-decision-postcommit-cancel"
                    } else {
                        "timer-decision-precommit-cancel"
                    },
                    restDeadlineEpochMs = harness.nowMs + 30_000L,
                    planCase = TimerPlanCase.UNRESOLVED,
                )
                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    harness.dwsm.resumeRoutine(installed.handle),
                )
                runCurrent()

                val unresolved = assertIs<RestTransitionPlan.UnresolvedDropOffer>(harness.restTransitionPlan.value)
                val timerOwner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
                val timerDeadline = assertNotNull(
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                val cancellation = CancellationException(
                    if (committed) "decision-postcommit" else "decision-precommit",
                )
                if (committed) {
                    harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = { document ->
                        if (document.restTransitionPlan is RestTransitionPlan.AcceptedRetry) {
                            harness.fakeActiveWorkoutRuntimeRepository.afterReplaceCommit = null
                            throw cancellation
                        }
                    }
                } else {
                    harness.fakeActiveWorkoutRuntimeRepository.cancellationOnNextReplace = cancellation
                }

                val thrown = assertFailsWith<CancellationException> {
                    harness.activeSessionEngine.applyRestTransitionAwait(
                        RestTransitionCommand.Accept(
                            identity = unresolved.actionIdentity(),
                            percentage = DropPercentage.TWENTY,
                        ),
                    )
                }

                assertEquals(cancellation.message, thrown.message)
                assertEquals(timerOwner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
                assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === timerJob)
                assertEquals(
                    timerDeadline,
                    harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
                )
                assertTrue(timerJob.isActive)
                val durable = assertNotNull(
                    harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                )
                if (committed) {
                    assertIs<RestTransitionPlan.AcceptedRetry>(durable.restTransitionPlan)
                    assertIs<RestTransitionPlan.AcceptedRetry>(harness.restTransitionPlan.value)
                    assertEquals(durable, harness.activeSessionEngine.activeRuntimeDocumentForTest())
                } else {
                    assertEquals(installed.document, durable)
                    assertEquals(unresolved, harness.restTransitionPlan.value)
                    assertEquals(installed.document, harness.activeSessionEngine.activeRuntimeDocumentForTest())
                }
            } finally {
                harness.cleanup()
                runCurrent()
            }
        }
    }

    @Test
    fun `durable restored normal clear immediately detaches only its exact timer`() = runTest {
        val harness = enabledTimerHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "timer-normal-clear",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()
            val plan = assertIs<RestTransitionPlan.NormalAdvance>(harness.restTransitionPlan.value)
            val timerJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())

            assertIs<RestTransitionReduction.DispatchNormal>(
                harness.activeSessionEngine.applyRestTransitionAwait(
                    RestTransitionCommand.SkipRest(plan.actionIdentity()),
                ),
            )
            runCurrent()

            assertNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(timerJob.isActive)
            assertNull(harness.restTransitionPlan.value)
            assertNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                )?.restTransitionPlan,
            )
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `restored timer controls persist and retain exactly one monotonic owner`() = runTest {
        val harness = enabledTimerHarness()
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            val installed = installTimerRuntime(
                harness = harness,
                routineSessionId = "timer-controls",
                restDeadlineEpochMs = harness.nowMs + 30_000L,
            )
            assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                harness.dwsm.resumeRoutine(installed.handle),
            )
            runCurrent()

            val owner = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            val initialJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            val initialDeadline = assertNotNull(
                harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(),
            )

            harness.dwsm.extendRestTime(10)
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === initialJob)
            assertEquals(initialDeadline + 10_000L, harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertEquals(40, harness.coordinator._restSecondsRemaining.value)
            assertEquals(70, harness.coordinator._restOriginalDuration.value)
            val extended = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertEquals(70, extended.originalRestDurationSeconds)
            assertFalse(extended.isRestPaused)
            assertNull(extended.pausedRestRemainingSeconds)

            harness.dwsm.toggleRestPause()
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.coordinator.isRestPaused.value)
            assertEquals(40, harness.coordinator._restSecondsRemaining.value)
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertNull(harness.activeSessionEngine.currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest())
            assertFalse(initialJob.isActive)
            val paused = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertTrue(paused.isRestPaused)
            assertEquals(40, paused.pausedRestRemainingSeconds)
            assertNull(paused.restDeadlineEpochMs)

            harness.dwsm.toggleRestPause()
            runCurrent()

            val resumedJob = assertNotNull(harness.activeSessionEngine.currentRestoredRestTimerJobForTest())
            assertTrue(resumedJob !== initialJob)
            assertTrue(resumedJob.isActive)
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(40, harness.coordinator._restSecondsRemaining.value)

            advanceTimeBy(1_100L)
            runCurrent()
            assertEquals(39, harness.coordinator._restSecondsRemaining.value)

            harness.dwsm.resetRestTimer()
            runCurrent()

            assertEquals(owner, harness.activeSessionEngine.currentRestoredRestTimerOwnerForTest())
            assertTrue(harness.activeSessionEngine.currentRestoredRestTimerJobForTest() === resumedJob)
            assertTrue(resumedJob.isActive)
            assertFalse(harness.coordinator.isRestPaused.value)
            assertEquals(70, harness.coordinator._restSecondsRemaining.value)
            assertEquals(70, harness.coordinator._restOriginalDuration.value)
            val reset = assertNotNull(
                harness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                    installed.document.profileId,
                    installed.document.routineSessionId,
                ),
            )
            assertFalse(reset.isRestPaused)
            assertEquals(70, reset.originalRestDurationSeconds)
            assertNull(reset.pausedRestRemainingSeconds)
        } finally {
            harness.cleanup()
            runCurrent()
        }
    }

    @Test
    fun `extended normal unresolved and declined rests remain cold resumable`() = runTest {
        listOf(TimerPlanCase.NORMAL, TimerPlanCase.UNRESOLVED, TimerPlanCase.DECLINED).forEach { planCase ->
            val sourceHarness = enabledTimerHarness()
            var freshHarness: DWSMTestHarness? = null
            try {
                sourceHarness.fakeBleRepo.simulateConnect("Vee_Test")
                val installed = installTimerRuntime(
                    harness = sourceHarness,
                    routineSessionId = "cold-extended-${planCase.name.lowercase()}",
                    restDeadlineEpochMs = sourceHarness.nowMs + 30_000L,
                    planCase = planCase,
                )
                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    sourceHarness.dwsm.resumeRoutine(installed.handle),
                    planCase.name,
                )
                runCurrent()

                sourceHarness.dwsm.extendRestTime(10)
                runCurrent()

                val extended = assertNotNull(
                    sourceHarness.fakeActiveWorkoutRuntimeRepository.committedDocument(
                        installed.document.profileId,
                        installed.document.routineSessionId,
                    ),
                    planCase.name,
                )
                assertEquals(70, extended.originalRestDurationSeconds, planCase.name)
                assertEquals(70, embeddedNormalPlan(extended.restTransitionPlan).restDurationSeconds, planCase.name)

                sourceHarness.cleanup()
                runCurrent()

                freshHarness = enabledTimerHarness()
                installExistingTimerRuntime(
                    harness = freshHarness,
                    routine = installed.routine,
                    document = extended,
                )
                val freshHandle = assertIs<RoutineResumeHandle.Persisted>(
                    assertIs<RoutineResumeDiscovery.Candidate>(
                        freshHarness.dwsm.discoverRoutineResume(
                            installed.routine,
                            RoutineLaunchOrigin.DAILY_ROUTINES,
                        ),
                        planCase.name,
                    ).handle,
                    planCase.name,
                )

                assertIs<ActiveWorkoutRuntimeResumeResult.RestoredRest>(
                    freshHarness.dwsm.resumeRoutine(freshHandle),
                    planCase.name,
                )
                runCurrent()
                assertEquals(70, freshHarness.coordinator._restOriginalDuration.value, planCase.name)
                assertEquals(extended.restTransitionPlan, freshHarness.restTransitionPlan.value, planCase.name)
            } finally {
                freshHarness?.cleanup()
                sourceHarness.cleanup()
                runCurrent()
            }
        }
    }

    private data class ZeroDeadlineCase(
        val label: String,
        val nowEpochMs: Long,
        val deadlineEpochMs: Long?,
    )

    private data class InstalledTimerRuntime(
        val document: ActiveWorkoutRuntimeDocument,
        val handle: RoutineResumeHandle.Persisted,
        val routine: Routine,
    )

    private suspend fun installTimerRuntime(
        harness: DWSMTestHarness,
        routineSessionId: String,
        restDeadlineEpochMs: Long?,
        pausedRestRemainingSeconds: Int? = null,
        isRestPaused: Boolean = false,
        planCase: TimerPlanCase = TimerPlanCase.NORMAL,
    ): InstalledTimerRuntime {
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
            pausedRestRemainingSeconds = pausedRestRemainingSeconds,
            isRestPaused = isRestPaused,
            planCase = planCase,
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
        return InstalledTimerRuntime(document, handle, routine)
    }

    private suspend fun installExistingTimerRuntime(
        harness: DWSMTestHarness,
        routine: Routine,
        document: ActiveWorkoutRuntimeDocument,
    ) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.fakeCompletedSetRepo.setSessionRoutine(
            document.sourceStableSessionId,
            document.routineSessionId,
        )
        harness.fakeCompletedSetRepo.saveCompletedSet(
            CompletedSet(
                id = "durable-${document.routineSessionId}",
                sessionId = document.sourceStableSessionId,
                plannedSetId = document.plannedSetId,
                setNumber = document.sourceSetIndex,
                setType = document.logicalSetKey.setKind,
                actualReps = document.sourceAuthority.actualReps,
                actualWeightKg = 25f,
                loggedRpe = null,
                isPr = false,
                completedAt = 1L,
                setEndReason = SetEndReason.STALL_FAILURE,
                routineExerciseId = document.routineExerciseId,
                attemptNumber = document.sourceAttemptNumber,
            ),
        )
        harness.fakeActiveWorkoutRuntimeRepository.replace(
            document.profileId,
            document.routineSessionId,
            document,
        )
        check(routine.id == document.routineId)
    }

    private fun embeddedNormalPlan(plan: RestTransitionPlan?): RestTransitionPlan.NormalAdvance = when (plan) {
        is RestTransitionPlan.NormalAdvance -> plan
        is RestTransitionPlan.UnresolvedDropOffer -> plan.normalAdvance
        is RestTransitionPlan.Declined -> plan.normalAdvance
        is RestTransitionPlan.AcceptedRetry, null -> error("Plan has no embedded normal advance")
    }

    private fun timerDocument(
        profileId: String,
        routine: Routine,
        sourceExercise: RoutineExercise,
        sourceStableSessionId: String,
        logicalSetKey: LogicalSetKey,
        restDeadlineEpochMs: Long?,
        pausedRestRemainingSeconds: Int?,
        isRestPaused: Boolean,
        planCase: TimerPlanCase,
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
        val sourceAuthority = sourceAuthority(
            profileId = profileId,
            routine = routine,
            sourceExercise = sourceExercise,
            sourceStableSessionId = sourceStableSessionId,
            sourceExecutionId = sourceExecutionId,
            logicalSetKey = logicalSetKey,
        )
        val baseDocument = ActiveWorkoutRuntimeDocument(
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
            sourceAuthority = sourceAuthority,
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
            pausedRestRemainingSeconds = pausedRestRemainingSeconds,
            isRestPaused = isRestPaused,
            originalRestDurationSeconds = 60,
        )
        return documentWithPlan(baseDocument, normalPlan, planCase)
    }

    private fun documentWithPlan(
        document: ActiveWorkoutRuntimeDocument,
        normal: RestTransitionPlan.NormalAdvance,
        planCase: TimerPlanCase,
    ): ActiveWorkoutRuntimeDocument {
        val unresolved = RestTransitionPlan.UnresolvedDropOffer(
            transitionId = normal.transitionId,
            sourceExecutionId = normal.sourceExecutionId,
            logicalSetKey = normal.logicalSetKey,
            offerId = "offer-${normal.logicalSetKey.routineSessionId}",
            plannedSetId = normal.plannedSetId,
            candidates = listOf(
                DropSetCandidate(DropPercentage.TEN, 22.5f, 0.9f),
                DropSetCandidate(DropPercentage.TWENTY, 20f, 0.8f),
                DropSetCandidate(DropPercentage.THIRTY, 17.5f, 0.7f),
            ),
            normalAdvance = normal,
        )
        return when (planCase) {
            TimerPlanCase.NORMAL -> document

            TimerPlanCase.DECLINED -> document.copy(
                restTransitionPlan = RestTransitionPlan.Declined(
                    transitionId = unresolved.transitionId,
                    sourceExecutionId = unresolved.sourceExecutionId,
                    logicalSetKey = unresolved.logicalSetKey,
                    offerId = unresolved.offerId,
                    normalAdvance = normal,
                ),
            )

            TimerPlanCase.UNRESOLVED -> document.copy(restTransitionPlan = unresolved)

            TimerPlanCase.ACCEPTED -> document.copy(
                restTransitionPlan = acceptedPlan(unresolved),
                attemptStates = listOf(
                    PlannedSetAttemptState(
                        logicalSetKey = document.logicalSetKey,
                        nextAttemptNumber = 3,
                        acceptedDropCount = 1,
                    ),
                ),
                exerciseLoadOverlays = listOf(
                    ExerciseLoadOverlay(
                        routineExerciseId = document.routineExerciseId,
                        multiplier = 0.8f,
                    ),
                ),
            )
        }
    }

    private fun acceptedPlan(unresolved: RestTransitionPlan.UnresolvedDropOffer) = RestTransitionPlan.AcceptedRetry(
        transitionId = unresolved.transitionId,
        sourceExecutionId = unresolved.sourceExecutionId,
        logicalSetKey = unresolved.logicalSetKey,
        offerId = unresolved.offerId,
        sourceCoordinates = unresolved.normalAdvance.sourceCoordinates,
        plannedSetId = unresolved.plannedSetId,
        percentage = DropPercentage.TWENTY,
        resolvedWeightPerCableKg = 20f,
        resultingExerciseMultiplier = 0.8f,
        nextAttemptNumber = 2,
    )

    private fun kotlinx.coroutines.test.TestScope.enabledTimerHarness() = DWSMTestHarness(
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

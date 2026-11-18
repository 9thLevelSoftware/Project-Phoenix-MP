package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1413: manual "Next Set" on a held Set Summary (Set summary duration = Manual)
 * is rejected with `Rest transition install failed reason=AUTHORITY_REJECTED` once the
 * tap lands after the machine teardown window. The engine contract test
 * `autoplay off keeps manual readiness at duplicate boundary` (Issue1394) taps at +1 s
 * and passes; the in-app taps land 20 s+ after set completion and every one is rejected,
 * freezing the workout on the summary.
 *
 * These tests drive the REAL production path (handleSetCompletion -> SetSummary ->
 * explicit proceedFromSummary() tap -> rest transition install) with fake BLE, delaying
 * the tap past the teardown window exactly as the simulator capture observed.
 */
class Issue1413ManualSummaryDelayedContinueTest {

    private fun entry(
        id: String,
        order: Int,
        exercise: Exercise = TestFixtures.singleArmRow,
        programMode: ProgramMode = ProgramMode.OldSchool,
        echoLevel: EchoLevel = EchoLevel.HARDER,
        eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
        setReps: List<Int?> = listOf(10),
        warmupSets: List<WarmupSet> = emptyList(),
    ): RoutineExercise = RoutineExercise(
        id = id,
        // Force a cable accessory: TestFixtures rows with non-cable equipment
        // classify as bodyweight and route startWorkout to BodyweightRepEntry,
        // which is not the reported machine-connected routine path.
        exercise = exercise.copy(equipment = "CABLE"),
        orderIndex = order,
        setReps = setReps,
        weightPerCableKg = 25f,
        setRestSeconds = listOf(2),
        programMode = programMode,
        echoLevel = echoLevel,
        eccentricLoad = eccentricLoad,
        warmupSets = warmupSets,
    )

    private fun routine(id: String, exercises: List<RoutineExercise>): Routine =
        Routine(id = id, name = id, exercises = exercises)

    private suspend fun TestScope.startFirstSet(harness: DWSMTestHarness) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
    }

    private suspend fun TestScope.completeActiveSetToHeldSummary(harness: DWSMTestHarness) {
        harness.dwsm.coordinator._repCount.value = RepCount(
            warmupReps = 0,
            workingReps = 10,
            totalReps = 10,
            isWarmupComplete = true,
        )
        harness.activeSessionEngine.handleSetCompletion(
            harness.activeSessionEngine.currentExecutionLeaseForTest(),
            SetEndReason.TARGET_REPS_REACHED,
        )
        // handleSetCompletion does delay(summaryDelayMs) then startRestTimer(); use
        // advanceTimeBy, never advanceUntilIdle (rest timer has an infinite tick loop).
        advanceTimeBy(1_000)
        assertIs<WorkoutState.SetSummary>(
            harness.dwsm.coordinator.workoutState.value,
            "completed set must show its summary",
        )
    }

    /**
     * RED/GREEN regression for #1413: the user's explicit "Next Set" tap must install
     * the rest transition from the claimed completion and proceed to the successor's
     * manual readiness — even when the tap lands long after the machine teardown
     * window (the engine contract test taps at +1 s; the in-app taps land 20 s+ after
     * set completion and were all rejected with AUTHORITY_REJECTED, freezing the
     * workout on the summary).
     */
    @Test
    fun `manual Next Set tap after the machine teardown window installs the rest plan`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual; summary holds
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1413-manual-delayed-continue",
                listOf(
                    entry("e1", 0, row),
                    entry("e2", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            // The defect window: the tap lands well after the machine teardown
            // (teardown completes within the first second; the simulator capture's
            // first genuine tap landed ~20 s after set completion). Nothing may
            // drift the completion's identity coordinates during the hold.
            advanceTimeBy(30_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(
                harness.dwsm.coordinator.workoutState.value,
                "the Manual summary must keep holding while the user delays",
            )

            val lease = assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            val completion = assertNotNull(
                harness.activeSessionEngine.claimedCompletion(lease),
                "the claimed set completion must survive the hold for the manual continue",
            )
            val identity = assertNotNull(completion.routineIdentity)

            // Manual summary: the user continues to rest explicitly.
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<InitialRestPlanInstallResult.Installed>(
                harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest,
                "the explicit continue must install the rest transition plan " +
                    "(identity=$identity, " +
                    "currentExerciseIndex=${harness.dwsm.coordinator._currentExerciseIndex.value}, " +
                    "currentSetIndex=${harness.dwsm.coordinator._currentSetIndex.value}, " +
                    "currentRoutineId=${harness.dwsm.coordinator.currentRoutineId}, " +
                    "currentRoutineSessionId=${harness.dwsm.coordinator.currentRoutineSessionId}, " +
                    "loadedRoutineExerciseIds=${harness.dwsm.coordinator._loadedRoutine.value?.exercises?.map { it.id }})",
            )

            // Manual readiness at the entry boundary: Idle + SetReady(1, 0), and no
            // unsolicited successor CONFIG before the explicit Start (issue #1394's
            // autoplay-off contract must survive the delayed tap).
            val state = harness.dwsm.coordinator.workoutState.value
            assertIs<WorkoutState.Idle>(
                state,
                "the delayed manual continue must advance off the held summary",
            )
            val ready = assertIs<RoutineFlowState.SetReady>(
                harness.dwsm.coordinator.routineFlowState.value,
                "the successor entry must be ready for the explicit Start",
            )
            assertEquals(1, ready.exerciseIndex)
            assertEquals(0, ready.setIndex)
            assertEquals(
                1,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG before explicit Start",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * RED/GREEN regression for #1413's observed failure state. The simulator capture
     * proved the tap-time rejection is an identity-coordinate conjunct drift in
     * [ActiveSessionEngine.hasCompletionRestAuthority]: the same check PASSED when the
     * completion job installed the rest plan at set-completion time (zero install
     * warnings in the capture log), and every later tap on the still-displayed summary
     * was rejected after a silent drift during the hold (zero connection-log entries
     * between teardown-ready and the first rejection; the lease gates all passed — no
     * `transition=suppressed` entries). The drift writer is app-side and is named by
     * the #1413 instrumentation (`conjunct=...` connection-log line) on the next
     * simulator run; delaying the tap alone does NOT drift engine state (the sibling
     * test above holds green at +30 s), so this test drives the observed end state
     * directly: the completion's rest plan is already installed, the summary is still
     * displayed, and one live identity coordinate no longer matches the completion.
     *
     * Contract under test: the user's explicit continue on the still-displayed summary
     * of a completion whose rest plan is already installed must install (return
     * `Installed(existing)`) and advance — the plan's source-execution + logical-set-key
     * match is the stale-completion guard, not live coordinator coordinates. A
     * completion whose plan is NOT installed must still be rejected on drift (see
     * `stale completion without an installed plan is still rejected after drift`).
     */
    @Test
    fun `manual continue proceeds when a live coordinate drifted after the plan was installed (setIndex)`() =
        assertManualContinueSurvivesDrift { harness, identity ->
            harness.dwsm.coordinator._currentSetIndex.value = identity.setIndex + 1
        }

    @Test
    fun `manual continue proceeds when a live coordinate drifted after the plan was installed (exerciseIndex)`() =
        assertManualContinueSurvivesDrift { harness, identity ->
            harness.dwsm.coordinator._currentExerciseIndex.value = identity.exerciseIndex + 1
        }

    @Test
    fun `manual continue proceeds when a live coordinate drifted after the plan was installed (routineSessionId)`() =
        assertManualContinueSurvivesDrift { harness, _ ->
            harness.dwsm.coordinator.currentRoutineSessionId = "drifted-routine-session"
        }

    @Test
    fun `manual continue proceeds when a live coordinate drifted after the plan was installed (routineId)`() =
        assertManualContinueSurvivesDrift { harness, _ ->
            harness.dwsm.coordinator.currentRoutineId = "drifted-routine"
        }

    private fun assertManualContinueSurvivesDrift(
        drift: (DWSMTestHarness, com.devil.phoenixproject.domain.model.RoutineExecutionIdentity) -> Unit,
    ) = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual; summary holds
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1413-drifted-continue",
                listOf(
                    entry("e1", 0, row),
                    entry("e2", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)

            // Past the machine teardown window (the capture's taps landed 20 s+
            // after set completion), the plan already installed by the completion
            // job under full authority.
            advanceTimeBy(30_000)
            runCurrent()
            assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)

            val lease = assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            val identity = assertNotNull(completion.routineIdentity)
            val installedPlan = assertNotNull(
                harness.dwsm.coordinator.restTransitionPlan.value,
                "the completion job must have installed the rest plan before the summary showed",
            )

            drift(harness, identity)

            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            val result = harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest
            assertIs<InitialRestPlanInstallResult.Installed>(
                result,
                "the still-displayed summary's own completion must install despite the drifted " +
                    "live coordinate (identity=$identity, drift result observed at tap)",
            )
            assertEquals(
                installedPlan,
                (result as InitialRestPlanInstallResult.Installed).plan,
                "the already-installed plan must be returned unchanged",
            )
            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "the manual continue must advance off the held summary",
            )
            assertEquals(
                1,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG before explicit Start",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Stale-completion guard preservation (the "narrow fix keeps stale-completion
     * guards" requirement): a genuine successor transition — the workout re-enters
     * SetReady at the successor coordinates exactly as startNextSetOrExercise does,
     * moving the index flows AND the routine-flow
     * position atomically — must still reject the superseded completion's rest
     * install. Only the silent mirror drift is tolerated; a real position transition
     * is not. Lease currency and the claim/plan-source guards are unchanged.
     */
    @Test
    fun `genuine successor transition still rejects the superseded completion`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // Manual; summary holds
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1413-guard-preservation",
                listOf(
                    entry("e1", 0, row),
                    entry("e2", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
            completeActiveSetToHeldSummary(harness)
            advanceTimeBy(30_000)
            runCurrent()

            val lease = assertNotNull(harness.activeSessionEngine.currentExecutionLeaseOrNull())
            val completion = assertNotNull(harness.activeSessionEngine.claimedCompletion(lease))
            assertNotNull(completion.routineIdentity)

            // The workout genuinely moved on to the successor entry's set.
            harness.dwsm.enterSetReady(1, 0)
            advanceUntilIdle()

            harness.activeSessionEngine.installInitialRestPlanForTest(completion, 60)
            assertIs<InitialRestPlanInstallResult.AuthorityRejected>(
                harness.activeSessionEngine.lastInitialRestPlanInstallResultForTest,
                "the superseded completion must not install over the successor's position",
            )
            assertEquals(
                "live_routine_position",
                harness.activeSessionEngine.lastCompletionRestAuthorityFailureForTest,
                "the rejection must be attributed to the live-position conjunct",
            )
        } finally {
            harness.cleanup()
        }
    }
}

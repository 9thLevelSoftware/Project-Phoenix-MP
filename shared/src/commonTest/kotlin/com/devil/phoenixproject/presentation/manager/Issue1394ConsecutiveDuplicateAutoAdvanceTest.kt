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
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Issue #1394: routine auto-advance must be consistent between distinct lifts and
 * consecutive identical lifts (the standard unilateral left/right pattern).
 *
 * Reported behavior: working through a Routine, the flow normally moves on from
 * lift-to-lift automatically. But when two of the same lift appear back-to-back
 * (e.g. one-arm rows left then right), playback stops and forces a manual tap on
 * Start to continue.
 *
 * Expected behavior (fix contract from the RCA + architecture review):
 * - Adjacent duplicate identity is NOT itself a safety gate. When the outgoing
 *   execution's applied machine configuration and the fully resolved successor
 *   configuration are equivalent, the boundary uses the existing authorized
 *   autoplay start path — exactly like a distinct-lift boundary, standard CONFIG
 *   emission included.
 * - The manual Start gate is preserved for real configuration transitions:
 *   autoplay off, programMode / Echo level / eccentric-load changes between the
 *   same-exercise entries, and unknown outgoing applied configuration. No
 *   unsolicited successor CONFIG is sent before explicit Start.
 *
 * These tests drive the REAL production path (handleSetCompletion -> SetSummary ->
 * summary auto-advance -> rest timer -> startNextSetOrExerciseFor) with fake BLE.
 * Fake-BLE success is characterization only — it is not physical machine-safety
 * acceptance (native iPad + V-Form/Trainer+ QA remains outstanding).
 */
class Issue1394ConsecutiveDuplicateAutoAdvanceTest {

    private fun entry(
        id: String,
        order: Int,
        exercise: Exercise = TestFixtures.singleArmRow,
        programMode: ProgramMode = ProgramMode.OldSchool,
        echoLevel: EchoLevel = EchoLevel.HARDER,
        eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
        setEchoLevels: List<EchoLevel?> = emptyList(),
        setReps: List<Int?> = listOf(10),
        warmupSets: List<WarmupSet> = emptyList(),
        supersetId: String? = null,
        orderInSuperset: Int = 0,
        defaultRackItemIds: List<String> = emptyList(),
        progressionKg: Float = 0f,
        durationSeconds: Int? = null,
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
        setEchoLevels = setEchoLevels,
        warmupSets = warmupSets,
        supersetId = supersetId,
        orderInSuperset = orderInSuperset,
        defaultRackItemIds = defaultRackItemIds,
        progressionKg = progressionKg,
        duration = durationSeconds,
    )

    private fun routine(id: String, exercises: List<RoutineExercise>): Routine =
        Routine(id = id, name = id, exercises = exercises)

    // ==== Encoded CONFIG frame assertions (merge-gate rework) ====
    // The fake records every raw frame handed to sendWorkoutCommand. These helpers
    // assert the ENCODED bytes (not decoded command objects): the 96-byte 0x04
    // program/activation CONFIG frame and the 32-byte 0x4E Echo control frame.

    private fun programFrames(harness: DWSMTestHarness): List<ByteArray> =
        harness.fakeBleRepo.commandsReceived.filter {
            it.size == BleConstants.ActivationPacket.SIZE && it[0] == 0x04.toByte()
        }

    private fun echoFrames(harness: DWSMTestHarness): List<ByteArray> =
        harness.fakeBleRepo.commandsReceived.filter {
            it.size == 32 && (it[0].toInt() and 0xFF) == 0x4E
        }

    private fun floatLe(value: Float): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(4) { i -> ((bits shr (8 * i)) and 0xFF).toByte() }
    }

    private fun int16Le(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private suspend fun TestScope.startFirstSet(harness: DWSMTestHarness) {
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
    }

    /**
     * Complete the active set through the real summary/rest pipeline and let the
     * successor transition run. With the default 10s summary countdown (autoplay ON)
     * the summary auto-advances into the rest timer; the 2s rest then elapses and the
     * entry-boundary successor runs.
     */
    private suspend fun TestScope.completeActiveSetAndAdvance(harness: DWSMTestHarness) {
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
        // Summary countdown (default 10s, autoplay ON) auto-advances into the rest timer.
        advanceTimeBy(11_000)
        assertIs<WorkoutState.Resting>(
            harness.dwsm.coordinator.workoutState.value,
            "summary must auto-advance into the rest timer between entries",
        )
        // Rest (2s per fixture) elapses; the successor transition runs here.
        advanceTimeBy(4_000)
        runCurrent()
    }

    private suspend fun TestScope.driveFirstSetToRestEnd(harness: DWSMTestHarness) {
        startFirstSet(harness)
        completeActiveSetAndAdvance(harness)
    }

    /**
     * RED/GREEN regression for #1394: [Single Arm Row] -> [Single Arm Row] (same name +
     * same exercise id, the unilateral left/right pattern) -> [Bench Press]. With
     * autoplay ON and equivalent program/Echo/eccentric settings, the boundary between
     * the two identical entries must auto-continue like every other lift-to-lift
     * transition — entering entry 2 / set 0 automatically after the real summary/rest
     * transitions, with a standard successor CONFIG (equal settings do not suppress
     * CONFIG emission) and no manual Start.
     */
    @Test
    fun `equivalent duplicate lift auto-continues without manual Start`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-duplicate",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                    entry("re-bench", 2, TestFixtures.benchPress),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "Issue #1394: consecutive duplicate lift must auto-start after rest, " +
                    "no manual Start required",
            )
            assertEquals(
                1,
                harness.dwsm.coordinator._currentExerciseIndex.value,
                "must have advanced to the duplicate entry",
            )
            assertEquals(
                0,
                harness.dwsm.coordinator._currentSetIndex.value,
                "must have entered the duplicate entry's first set",
            )
            // Exactly one standard CONFIG per start: the equal-settings successor start
            // still emits its program CONFIG (no no-CONFIG shortcut), and there is no
            // stale or double start.
            assertEquals(
                2,
                harness.fakeBleRepo.programCommands.size,
                "one CONFIG per set start expected (initial + auto-continued successor)",
            )
            assertEquals(
                25f,
                harness.fakeBleRepo.programCommands.last().weightPerCableKg,
                "successor CONFIG must carry the resolved destination weight",
            )
            // The duplicate entry has no warmups: the warmup phase must be re-initialized
            // deliberately (-1), never inherited stale from the outgoing entry.
            assertEquals(
                -1,
                harness.dwsm.coordinator._currentWarmupSetIndex.value,
                "no stale warmup index may leak across the duplicate boundary",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Control: [Single Arm Row] -> [Bench Press]. Distinct lifts auto-advance
     * into the next exercise without any manual Start, exactly as reported.
     */
    @Test
    fun `distinct next lift auto-continues without manual Start`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = routine(
                "issue-1394-control",
                listOf(
                    entry("re-row", 0, TestFixtures.singleArmRow),
                    entry("re-bench", 1, TestFixtures.benchPress),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "Control (distinct lifts): the next lift must auto-start after rest, " +
                    "no manual Start required",
            )
            assertEquals(
                1,
                harness.dwsm.coordinator._currentExerciseIndex.value,
                "control must have advanced to the second entry",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Boundary scope check: within a single entry, set-to-set progression is
     * unaffected (the fix is specific to the entry boundary between identical
     * consecutive exercises).
     */
    @Test
    fun `within-entry set-to-set auto-advance is unaffected`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = routine(
                "issue-1394-within-entry",
                listOf(
                    entry("re-row-2sets", 0, setReps = listOf(10, 10)),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "same-exercise WITHIN one entry (next set) must auto-continue",
            )
            assertEquals(
                0,
                harness.dwsm.coordinator._currentExerciseIndex.value,
                "still on the same entry",
            )
            assertEquals(
                1,
                harness.dwsm.coordinator._currentSetIndex.value,
                "advanced to the second set of the entry",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Autoplay OFF keeps manual readiness at an equivalent duplicate boundary:
     * SetReady + Idle, no unsolicited successor CONFIG until the user explicitly
     * starts the set.
     */
    @Test
    fun `autoplay off keeps manual readiness at duplicate boundary`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.setActiveSummaryCountdownSeconds(0) // autoplay OFF; summary holds
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-autoplay-off",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(1_000)
            assertIs<WorkoutState.SetSummary>(harness.dwsm.coordinator.workoutState.value)
            // Manual summary: the user continues to rest explicitly.
            harness.dwsm.proceedFromSummary()
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            val state = harness.dwsm.coordinator.workoutState.value
            assertIs<WorkoutState.Idle>(
                state,
                "autoplay OFF: the duplicate boundary must stay on manual readiness",
            )
            val ready = assertIs<RoutineFlowState.SetReady>(
                harness.dwsm.coordinator.routineFlowState.value,
                "autoplay OFF: the duplicate boundary must show SetReady",
            )
            assertEquals(1, ready.exerciseIndex)
            assertEquals(0, ready.setIndex)
            assertEquals(
                1,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG before explicit Start",
            )

            // Explicit Start configures the successor with the standard machinery.
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(2, harness.fakeBleRepo.programCommands.size)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A real program-mode change between same-exercise entries (the Issue #572
     * OldSchool -> TUT finisher pattern) must keep the manual Start gate and defer
     * the successor CONFIG until explicit Start.
     */
    @Test
    fun `changed program mode keeps manual readiness and defers successor CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-mode-change",
                listOf(
                    entry("re-row-main", 0, row, programMode = ProgramMode.TUT),
                    entry("re-row-finisher", 1, row, programMode = ProgramMode.OldSchool),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            val state = harness.dwsm.coordinator.workoutState.value
            assertIs<WorkoutState.Idle>(
                state,
                "genuine programMode change must stay manual-ready",
            )
            assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
            assertEquals(
                1,
                harness.fakeBleRepo.programCommands.size,
                "no unsolicited successor CONFIG across a mode change",
            )

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(2, harness.fakeBleRepo.programCommands.size)
            assertEquals(
                ProgramMode.OldSchool,
                harness.dwsm.coordinator._workoutParameters.value.programMode,
                "explicit Start must configure the destination entry's mode",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A genuine Echo-level change between same-exercise Echo entries keeps the
     * manual Start gate and defers the successor Echo CONFIG.
     */
    @Test
    fun `changed Echo level keeps manual readiness and defers successor CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-echo-change",
                listOf(
                    entry("re-row-left", 0, row, programMode = ProgramMode.Echo, echoLevel = EchoLevel.HARDER),
                    entry("re-row-right", 1, row, programMode = ProgramMode.Echo, echoLevel = EchoLevel.HARDEST),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value)
            assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
            assertEquals(
                1,
                harness.fakeBleRepo.echoCommands.size,
                "no unsolicited successor Echo CONFIG across an Echo-level change",
            )

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(2, harness.fakeBleRepo.echoCommands.size)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A genuine eccentric-load change between same-exercise Echo entries keeps the
     * manual Start gate; explicit Start then sends the destination eccentric load.
     */
    @Test
    fun `changed eccentric load keeps manual readiness and defers successor CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-eccentric-change",
                listOf(
                    entry("re-row-left", 0, row, programMode = ProgramMode.Echo, eccentricLoad = EccentricLoad.LOAD_100),
                    entry("re-row-right", 1, row, programMode = ProgramMode.Echo, eccentricLoad = EccentricLoad.LOAD_150),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Idle>(harness.dwsm.coordinator.workoutState.value)
            assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
            assertEquals(
                1,
                harness.fakeBleRepo.echoCommands.size,
                "no unsolicited successor Echo CONFIG across an eccentric-load change",
            )

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(2, harness.fakeBleRepo.echoCommands.size)
            assertEquals(
                150,
                harness.fakeBleRepo.echoCommands.last().eccentricOverload,
                "explicit Start must send the destination eccentric load",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Equivalent Echo duplicates auto-continue through the standard Echo CONFIG path
     * (equal settings still emit their CONFIG).
     */
    @Test
    fun `equivalent Echo duplicate auto-continues with standard Echo CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-echo-equivalent",
                listOf(
                    entry("re-row-left", 0, row, programMode = ProgramMode.Echo),
                    entry("re-row-right", 1, row, programMode = ProgramMode.Echo),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                2,
                harness.fakeBleRepo.echoCommands.size,
                "equal-settings Echo duplicate must auto-start with a standard Echo CONFIG",
            )
            assertEquals(
                100,
                harness.fakeBleRepo.echoCommands.last().eccentricOverload,
                "successor Echo CONFIG must carry the resolved destination settings",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Rest-screen weight edits are preserved across an equivalent duplicate boundary,
     * which still auto-continues (a weight edit is not a machine-configuration change).
     */
    @Test
    fun `rest-edited weight is preserved and the duplicate still auto-continues`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-rest-weight",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            // The user edits the weight on the rest screen for the upcoming set.
            harness.activeSessionEngine.adjustWeight(30f)
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "a rest weight edit is not a configuration change; the duplicate must auto-continue",
            )
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                30f,
                harness.dwsm.coordinator._workoutParameters.value.weightPerCableKg,
                "the rest edit must be preserved on the successor set",
            )
            assertEquals(
                30f,
                harness.fakeBleRepo.programCommands.last().weightPerCableKg,
                "the successor CONFIG must carry the preserved rest edit",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Rest edits must not mask a real configuration change: a rest-screen Echo-level
     * edit changes the resolved successor configuration, so the boundary stays
     * manual-ready even though the two entries carry identical fields.
     */
    @Test
    fun `rest-edited Echo level is a real change and keeps manual readiness`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-rest-echo",
                listOf(
                    entry("re-row-left", 0, row, programMode = ProgramMode.Echo),
                    entry("re-row-right", 1, row, programMode = ProgramMode.Echo),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            harness.activeSessionEngine.updateWorkoutParameters(
                harness.dwsm.coordinator._workoutParameters.value.copy(echoLevel = EchoLevel.HARDEST),
            )
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "the rest-edited Echo level is a real configuration change; manual Start required",
            )
            assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
            assertEquals(
                1,
                harness.fakeBleRepo.echoCommands.size,
                "no unsolicited successor Echo CONFIG before explicit Start",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Rest edits must not mask a real configuration change in the other direction
     * either: when the entries genuinely change mode, a coordinator value overwritten
     * during rest must not make the transition look equivalent. The decision keys off
     * the outgoing applied configuration and the resolved destination entry, not the
     * mutable coordinator block.
     */
    @Test
    fun `rest edits cannot mask a genuine mode change`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-rest-mask",
                listOf(
                    entry("re-row-main", 0, row, programMode = ProgramMode.TUT),
                    entry("re-row-finisher", 1, row, programMode = ProgramMode.OldSchool),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            // The rest screen writes coordinator params to the DESTINATION mode
            // (OldSchool). A naive coordinator-vs-destination comparison would call this
            // equivalent and auto-start — but the trainer is still configured for TUT, so
            // the frozen outgoing applied configuration keeps the manual gate.
            harness.activeSessionEngine.updateWorkoutParameters(
                harness.dwsm.coordinator._workoutParameters.value.copy(programMode = ProgramMode.OldSchool),
            )
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "the genuine TUT -> OldSchool change must stay manual-ready despite the rest edit",
            )
            assertIs<RoutineFlowState.SetReady>(harness.dwsm.coordinator.routineFlowState.value)
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
     * Null-id entries fall back to name matching (legacy / unlinked exercises): the
     * equivalent duplicate boundary auto-continues like the id-matched case.
     */
    @Test
    fun `null-id duplicate entries match by name and auto-continue`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow.copy(id = null)
            val routine = routine(
                "issue-1394-null-id",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Same name with DIFFERENT exercise ids is not a same-exercise continuation
     * (distinct IDs), so it follows the ordinary distinct-lift auto path.
     */
    @Test
    fun `same name with distinct ids auto-continues like a distinct lift`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = routine(
                "issue-1394-distinct-ids",
                listOf(
                    entry("re-row-left", 0, TestFixtures.singleArmRow.copy(id = "single-arm-row-L")),
                    entry("re-row-right", 1, TestFixtures.singleArmRow.copy(id = "single-arm-row-R")),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Non-adjacent duplicates (separated by another lift) are ordinary distinct-lift
     * boundaries and must auto-continue — the duplicate gate only ever applies to
     * adjacent entries.
     */
    @Test
    fun `nonadjacent duplicate entries auto-continue like distinct lifts`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-nonadjacent",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-bench", 1, TestFixtures.benchPress),
                    entry("re-row-right", 2, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)

            // Second boundary: bench -> the second row entry (same lift as entry 0,
            // but non-adjacent) must also auto-continue.
            completeActiveSetAndAdvance(harness)
            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "non-adjacent duplicate must auto-continue like a distinct lift",
            )
            assertEquals(2, harness.dwsm.coordinator._currentExerciseIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Skipped entries keep linear navigation: when the duplicate entry between two
     * different lifts is skipped, the boundary crosses to the next non-skipped entry
     * and must auto-continue.
     */
    @Test
    fun `skipped duplicate entry does not gate the boundary`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-skipped",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                    entry("re-bench", 2, TestFixtures.benchPress),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.coordinator._skippedExercises.value = setOf(1)

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "the boundary across a skipped duplicate must auto-continue",
            )
            assertEquals(2, harness.dwsm.coordinator._currentExerciseIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Same-exercise adjacent entries inside a superset keep superset interleaving;
     * the duplicate gate never applies inside supersets and progression is unchanged.
     */
    @Test
    fun `superset duplicates keep superset progression and auto-continue`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val rowLeft = entry("re-row-left", 0, row, supersetId = "s1", orderInSuperset = 0)
            val rowRight = entry("re-row-right", 1, row, supersetId = "s1", orderInSuperset = 1)
            val routine = Routine(
                id = "issue-1394-superset",
                name = "issue-1394-superset",
                exercises = listOf(rowLeft, rowRight),
                supersets = listOf(
                    Superset(
                        id = "s1",
                        routineId = "issue-1394-superset",
                        name = "s1",
                        exercises = listOf(rowLeft, rowRight),
                    ),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(
                harness.dwsm.coordinator.workoutState.value,
                "superset interleaving must keep auto-advancing",
            )
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
        } finally {
            harness.cleanup()
        }
    }

    /**
     * A duplicate entry with configured warmups must enter its warmup phase
     * deliberately at the boundary (index 0), while the successor still auto-starts.
     */
    @Test
    fun `duplicate boundary initializes the destination warmup phase`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-warmup",
                listOf(
                    entry("re-row-left", 0, row),
                    entry(
                        "re-row-right",
                        1,
                        row,
                        warmupSets = listOf(WarmupSet(reps = 8, percentOfWorking = 50)),
                    ),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                0,
                harness.dwsm.coordinator._currentWarmupSetIndex.value,
                "the duplicate entry's warmup phase must start at index 0",
            )
            assertEquals(
                12.5f,
                harness.fakeBleRepo.programCommands.last().weightPerCableKg,
                "the warmup CONFIG must carry the warmup-set weight (50% of 25kg)",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Destination rack inputs are fully resolved before command capture: the entry's
     * rack defaults are seeded first, then the exact-target pending rack edit from the
     * rest screen is consumed once.
     */
    @Test
    fun `duplicate boundary seeds rack defaults then consumes the exact-target rack edit`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-rack",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row, defaultRackItemIds = listOf("rack-default")),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            // The rest screen targets the successor entry (1, 0) with a rack edit.
            harness.activeSessionEngine.updateActiveRackSelection(listOf("rack-9"))
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                listOf("rack-9"),
                harness.dwsm.coordinator._activeRackItemIds.value.toList(),
                "destination rack defaults must be seeded BEFORE the exact-target " +
                    "pending rack edit is consumed once",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * The configuration-transition gate fails conservatively: when the outgoing
     * applied configuration cannot be established, the boundary must require the
     * explicit manual Start (today's behavior) rather than auto-starting.
     */
    @Test
    fun `unknown outgoing configuration fails conservatively to manual readiness`() {
        val destination = AppliedMachineConfiguration(
            programMode = ProgramMode.TUT,
            echoLevel = EchoLevel.HARDER,
            eccentricLoad = EccentricLoad.LOAD_100,
        )
        assertTrue(
            requiresExplicitConfigurationTransition(outgoingApplied = null, destination = destination),
            "unknown outgoing applied configuration must keep the manual gate",
        )
    }

    /**
     * Gate semantics: equivalent resolved settings do not require a deferred manual
     * configuration transition; real programMode / Echo-level / eccentric-load
     * changes do. Echo-level and eccentric-load are machine-configuration settings
     * carried by the Echo CONFIG, so any difference keeps the conservative manual gate.
     */
    @Test
    fun `configuration gate separates identity from real configuration transitions`() {
        val base = AppliedMachineConfiguration(
            programMode = ProgramMode.TUT,
            echoLevel = EchoLevel.HARDER,
            eccentricLoad = EccentricLoad.LOAD_100,
        )
        assertFalse(requiresExplicitConfigurationTransition(base, base.copy()))
        assertTrue(
            requiresExplicitConfigurationTransition(
                base,
                base.copy(programMode = ProgramMode.Echo),
            ),
        )
        assertTrue(
            requiresExplicitConfigurationTransition(
                base.copy(programMode = ProgramMode.Echo),
                base.copy(programMode = ProgramMode.Echo, echoLevel = EchoLevel.HARDEST),
            ),
        )
        assertTrue(
            requiresExplicitConfigurationTransition(
                base.copy(programMode = ProgramMode.Echo),
                base.copy(programMode = ProgramMode.Echo, eccentricLoad = EccentricLoad.LOAD_150),
            ),
        )
    }

    // ==== Merge-gate rework: encoded CONFIG bytes and destination command inputs ====
    //
    // Binding acceptance criterion 2 maps to these tests:
    //   "Equivalent-settings duplicates auto-start with standard program/Echo CONFIG;
    //    assert command bytes/count, destination index and reps/weights/progression/
    //    rack/warmup/timed/AMRAP parameters."
    // - standard program CONFIG bytes + exact count + destination index/weight:
    //   `equivalent duplicate successor CONFIG is the standard byte-exact program frame`
    // - standard Echo CONFIG bytes + exact count:
    //   `equivalent Echo duplicate successor CONFIG is the standard byte-exact Echo frame`
    // - nondefault destination reps/weights/progression:
    //   `duplicate boundary captures nondefault destination reps and progression into the successor CONFIG`
    // - AMRAP parameters: `duplicate boundary AMRAP destination encodes the unlimited rep sentinel`
    // - timed parameters: `duplicate boundary timed destination starts its duration countdown`
    // - rack-adjusted command inputs: `duplicate boundary rack edit is captured into the successor command inputs`
    // - warmup parameters: `duplicate boundary initializes the destination warmup phase` (above)
    //
    // Binding acceptance criterion 3's unknown-outgoing wiring proof maps to
    // `missing outgoing execution facts fail conservatively through the production transition`.

    /**
     * Merge-gate acceptance (program CONFIG): the equivalent-settings duplicate
     * boundary auto-starts with the STANDARD byte-exact 0x04 program CONFIG —
     * exactly one frame per set start (exact count), with the resolved destination
     * reps, weight, force ceiling and progression encoded at their documented
     * offsets. The equal-settings successor CONFIG is byte-identical to the initial
     * CONFIG (no no-CONFIG shortcut, no divergent encoding).
     */
    @Test
    fun `equivalent duplicate successor CONFIG is the standard byte-exact program frame`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-program-bytes",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

            val frames = programFrames(harness)
            assertEquals(2, frames.size, "exactly one 0x04 CONFIG per set start (initial + successor)")
            for (frame in frames) {
                assertEquals(96, frame.size, "standard program CONFIG frame size")
                assertContentEquals(
                    byteArrayOf(0x04, 0x00, 0x00, 0x00),
                    frame.copyOfRange(0, 4),
                    "standard 0x04 CONFIG header",
                )
                assertEquals(
                    13,
                    frame[0x04].toInt() and 0xFF,
                    "encoded reps byte = resolved 10 reps + 3 warmup reps",
                )
                assertContentEquals(
                    floatLe(25f),
                    frame.copyOfRange(0x58, 0x5C),
                    "encoded target weight = resolved destination 25kg/cable",
                )
                assertContentEquals(
                    floatLe(35f),
                    frame.copyOfRange(0x54, 0x58),
                    "encoded force ceiling = target weight + 10kg headroom",
                )
                assertContentEquals(
                    floatLe(0f),
                    frame.copyOfRange(0x5C, 0x60),
                    "encoded per-rep progression = 0",
                )
            }
            assertContentEquals(
                frames[0],
                frames[1],
                "equal-settings successor CONFIG must be byte-identical to the initial CONFIG",
            )

            val facts = requireNotNull(harness.activeSessionEngine.executionFactsForTest()) {
                "the auto-started successor must have frozen command-capture facts"
            }
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value, "destination index")
            assertEquals(0, harness.dwsm.coordinator._currentSetIndex.value, "destination set index")
            assertEquals(10, facts.logicalPreRackCommandTemplate.reps, "destination reps command input")
            assertEquals(25f, facts.logicalPreRackCommandTemplate.weightPerCableKg, "destination weight command input")
            assertEquals(0f, facts.logicalPreRackCommandTemplate.progressionRegressionKg, "destination progression command input")
            assertEquals(3, facts.logicalPreRackCommandTemplate.warmupReps, "destination warmup-rep command input")
            assertTrue(facts.logicalPreRackCommandTemplate.activeRackItemIds.isEmpty(), "no rack selection expected")
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (Echo CONFIG): the equivalent-settings Echo duplicate
     * boundary auto-starts with the STANDARD byte-exact 0x4E Echo CONFIG — exact
     * count, warmup/target rep bytes and eccentric-overload bytes at their
     * documented offsets — and the equal-settings successor frame is byte-identical
     * to the initial frame.
     */
    @Test
    fun `equivalent Echo duplicate successor CONFIG is the standard byte-exact Echo frame`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-echo-bytes",
                listOf(
                    entry("re-row-left", 0, row, programMode = ProgramMode.Echo),
                    entry("re-row-right", 1, row, programMode = ProgramMode.Echo),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)

            val frames = echoFrames(harness)
            assertEquals(2, frames.size, "exactly one 0x4E Echo CONFIG per set start")
            for (frame in frames) {
                assertEquals(32, frame.size, "standard Echo CONFIG frame size")
                assertContentEquals(
                    byteArrayOf(0x4E, 0x00, 0x00, 0x00),
                    frame.copyOfRange(0, 4),
                    "standard Echo CONFIG header",
                )
                assertEquals(3, frame[0x04].toInt() and 0xFF, "encoded warmup reps")
                assertEquals(10, frame[0x05].toInt() and 0xFF, "encoded target reps")
                assertContentEquals(
                    int16Le(100),
                    frame.copyOfRange(0x08, 0x0A),
                    "encoded eccentric overload = 100%",
                )
            }
            assertContentEquals(
                frames[0],
                frames[1],
                "equal-settings successor Echo CONFIG must be byte-identical to the initial frame",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (destination parameters): a duplicate destination with
     * nondefault reps and per-rep progression has BOTH captured into the successor
     * command inputs and encoded into the successor CONFIG frame bytes.
     */
    @Test
    fun `duplicate boundary captures nondefault destination reps and progression into the successor CONFIG`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-destination-params",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row, setReps = listOf(12), progressionKg = 2.5f),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(0, harness.dwsm.coordinator._currentSetIndex.value)

            val facts = requireNotNull(harness.activeSessionEngine.executionFactsForTest()) {
                "the auto-started successor must have frozen command-capture facts"
            }
            assertEquals(
                12,
                facts.logicalPreRackCommandTemplate.reps,
                "destination reps must be captured into the successor command inputs",
            )
            assertEquals(
                2.5f,
                facts.logicalPreRackCommandTemplate.progressionRegressionKg,
                "destination progression must be captured into the successor command inputs",
            )

            val frames = programFrames(harness)
            assertEquals(2, frames.size, "exactly one 0x04 CONFIG per set start")
            val successor = frames.last()
            assertEquals(
                15,
                successor[0x04].toInt() and 0xFF,
                "encoded reps byte = resolved 12 reps + 3 warmup reps",
            )
            assertContentEquals(
                floatLe(25f),
                successor.copyOfRange(0x58, 0x5C),
                "encoded destination weight",
            )
            assertContentEquals(
                floatLe(2.5f),
                successor.copyOfRange(0x5C, 0x60),
                "encoded destination progression",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (AMRAP parameters): a per-set AMRAP duplicate
     * destination captures isAmrap into the command inputs and encodes the 0xFF
     * unlimited-rep sentinel in the successor CONFIG frame.
     */
    @Test
    fun `duplicate boundary AMRAP destination encodes the unlimited rep sentinel`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-amrap",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row, setReps = listOf(null)),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertTrue(
                harness.dwsm.coordinator._workoutParameters.value.isAMRAP,
                "the destination AMRAP flag must be published",
            )

            val facts = requireNotNull(harness.activeSessionEngine.executionFactsForTest()) {
                "the auto-started successor must have frozen command-capture facts"
            }
            assertTrue(facts.isAmrap, "AMRAP must be captured into the successor command inputs")

            val frames = programFrames(harness)
            assertEquals(2, frames.size, "exactly one 0x04 CONFIG per set start")
            assertEquals(
                0xFF,
                frames.last()[0x04].toInt() and 0xFF,
                "AMRAP destination must encode the 0xFF unlimited-rep sentinel",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (timed parameters): a timed duplicate destination
     * captures its duration at command time and arms the real duration countdown
     * once warmup reps complete, while still emitting its standard CONFIG.
     */
    @Test
    fun `duplicate boundary timed destination starts its duration countdown`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-timed",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row, durationSeconds = 30),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            driveFirstSetToRestEnd(harness)
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertTrue(
                harness.dwsm.coordinator.isCurrentWorkoutTimed,
                "the auto-started successor must run as the timed exercise",
            )
            assertEquals(
                30,
                harness.dwsm.coordinator.exerciseTimerOriginalDuration,
                "the destination duration must be captured at command time",
            )
            assertEquals(2, programFrames(harness).size, "the timed destination still gets its standard CONFIG")

            // Warmup reps complete on the real timer path; the countdown starts at
            // the captured duration.
            harness.dwsm.coordinator._repCount.value = RepCount(
                warmupReps = 3,
                workingReps = 0,
                totalReps = 3,
                isWarmupComplete = true,
            )
            runCurrent()
            assertEquals(
                30,
                harness.dwsm.coordinator._timedExerciseRemainingSeconds.value,
                "the timed destination countdown must start at the captured duration",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (rack-adjusted command inputs): the exact-target rack
     * edit from the rest screen is consumed once at the duplicate boundary and the
     * resolved rack selection is captured into the successor execution's frozen
     * command inputs, while the command itself retains the pre-rack programmed
     * weight (the machine-safety rack snapshot contract).
     */
    @Test
    fun `duplicate boundary rack edit is captured into the successor command inputs`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-rack-command-inputs",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row, defaultRackItemIds = listOf("rack-default")),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)
            // The rest screen targets the successor entry (1, 0) with a rack edit.
            harness.activeSessionEngine.updateActiveRackSelection(listOf("rack-9"))
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(1, harness.dwsm.coordinator._currentExerciseIndex.value)
            assertEquals(
                listOf("rack-9"),
                harness.dwsm.coordinator._activeRackItemIds.value.toList(),
                "the exact-target rack edit must be consumed once at the boundary",
            )

            val facts = requireNotNull(harness.activeSessionEngine.executionFactsForTest()) {
                "the auto-started successor must have frozen command-capture facts"
            }
            assertEquals(
                listOf("rack-9"),
                facts.logicalPreRackCommandTemplate.activeRackItemIds,
                "the rack-adjusted selection must be captured into the successor command inputs",
            )
            assertEquals(
                25f,
                facts.logicalPreRackCommandTemplate.weightPerCableKg,
                "the command must retain the pre-rack programmed weight",
            )
        } finally {
            harness.cleanup()
        }
    }

    /**
     * Merge-gate acceptance (unknown outgoing configuration, production wiring):
     * when the outgoing execution's applied configuration cannot be established at
     * the duplicate boundary (missing execution facts — the production invalidation
     * path after teardown/reset), the REAL summary/rest transition must fail
     * conservatively to manual readiness: SetReady + Idle and no successor CONFIG
     * until explicit Start. The predicate-only test above is not a substitute for
     * this wiring proof.
     */
    @Test
    fun `missing outgoing execution facts fail conservatively through the production transition`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val row = TestFixtures.singleArmRow
            val routine = routine(
                "issue-1394-missing-facts",
                listOf(
                    entry("re-row-left", 0, row),
                    entry("re-row-right", 1, row),
                ),
            )
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            advanceUntilIdle()
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()

            startFirstSet(harness)
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
            advanceTimeBy(12_000)
            assertIs<WorkoutState.Resting>(harness.dwsm.coordinator.workoutState.value)

            // The outgoing execution facts go missing before the successor transition
            // runs (the production invalidation path: teardown/reset drops the
            // execution context, as in clearExecutionFactsForTest's mirror).
            harness.activeSessionEngine.clearExecutionFactsForTest()
            advanceTimeBy(4_000)
            runCurrent()

            assertIs<WorkoutState.Idle>(
                harness.dwsm.coordinator.workoutState.value,
                "unknown outgoing applied configuration must fail conservatively to manual readiness",
            )
            val ready = assertIs<RoutineFlowState.SetReady>(
                harness.dwsm.coordinator.routineFlowState.value,
                "the duplicate boundary must show SetReady when the outgoing configuration is unknown",
            )
            assertEquals(1, ready.exerciseIndex)
            assertEquals(0, ready.setIndex)
            assertEquals(
                1,
                programFrames(harness).size,
                "no successor CONFIG may be sent before explicit Start",
            )

            // Explicit Start configures the successor with the standard machinery.
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.dwsm.coordinator.workoutState.value)
            assertEquals(
                2,
                programFrames(harness).size,
                "explicit Start sends the deferred standard CONFIG",
            )
        } finally {
            harness.cleanup()
        }
    }
}

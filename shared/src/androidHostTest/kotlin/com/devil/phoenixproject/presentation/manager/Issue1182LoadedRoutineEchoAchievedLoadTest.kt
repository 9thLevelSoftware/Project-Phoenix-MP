package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #1182 loaded-routine acceptance (merge-gate R5b).
 *
 * The earlier tests drove the engine with `updateWorkoutParameters(...)` and only tested the
 * editor seed / resolver in isolation, so nothing proved the fix works for a set that came
 * from an actually LOADED routine - which is how the reporter hit it (a routine exercise that
 * was never weight-edited still carries the 5 kg editor seed).
 *
 * These tests load a real [Routine] into the engine so the configured weight comes FROM the
 * routine, then drive the Echo set to completion and assert:
 *  - the achieved load is the measured working PEAK per cable, not the working average;
 *  - the routine's configured seed stays configured metadata and is never reported as achieved;
 *  - both terminal persistence sites agree (auto-release snapshot and the manual/legacy stop);
 *  - rack and counterweight stay their own fields and never inflate the per-cable achieved peak.
 *
 * IMPORTANT: an [advanceUntilIdle] MUST follow harness construction and precede
 * `loadRoutine`, exactly as in `DWSMRoutineFlowTest`, or the init collectors and the load
 * interleave into a re-dispatch loop. The helpers are `TestScope` suspend extensions for that
 * reason: they need the test scheduler for [advanceUntilIdle] and a coroutine context for the
 * fake BLE emitters.
 */
class Issue1182LoadedRoutineEchoAchievedLoadTest {

    /**
     * A real routine exercise that was never weight-edited: `weightPerCableKg = 5f` is the
     * routine editor's default seed - the reporter's 11.02 lb.
     */
    private fun echoRoutineExercise(
        configuredSeedKg: Float = 5f,
        rackItemIds: List<String> = emptyList(),
    ) = RoutineExercise(
        id = "re-echo",
        exercise = TestFixtures.squat,
        orderIndex = 0,
        setReps = listOf(null),
        weightPerCableKg = configuredSeedKg,
        programMode = ProgramMode.Echo,
        echoLevel = EchoLevel.EPIC,
        eccentricLoad = EccentricLoad.LOAD_130,
        isAMRAP = true,
        stallDetectionEnabled = false,
        defaultRackItemIds = rackItemIds,
    )

    private fun echoRoutine(
        configuredSeedKg: Float = 5f,
        rackItemIds: List<String> = emptyList(),
    ) = Routine(
        id = "routine-1182",
        name = "Big Barbell",
        exercises = listOf(echoRoutineExercise(configuredSeedKg = configuredSeedKg, rackItemIds = rackItemIds)),
    )

    /**
     * Loads the routine into the engine and starts the Echo set from the routine flow, so the
     * configured weight is the routine's - never a hand-written engine parameter.
     */
    private suspend fun TestScope.startEchoSetFromLoadedRoutine(
        harness: DWSMTestHarness,
        routine: Routine = echoRoutine(),
    ): String {
        routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
        advanceUntilIdle() // let DWSM's init collectors settle BEFORE loadRoutine

        // The trainer must be connected before a set can start (SetReady only offers
        // "start" once connected).
        harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        harness.dwsm.loadRoutine(routine)
        advanceUntilIdle()

        val loaded = harness.dwsm.coordinator.workoutParameters.value
        assertEquals(
            5f,
            loaded.weightPerCableKg,
            "the configured weight must come from the loaded routine's 5 kg editor seed",
        )
        assertEquals(ProgramMode.Echo, loaded.programMode, "the loaded routine exercise is an Echo set")

        harness.dwsm.enterSetReady(0, 0)
        advanceUntilIdle()
        assertTrue(
            harness.coordinator.routineFlowState.value is com.devil.phoenixproject.domain.model.RoutineFlowState.SetReady,
            "the loaded routine must reach SetReady before the set can start",
        )
        harness.dwsm.startSetFromReady()
        advanceUntilIdle()
        return harness.activeSessionEngine.currentExecutionLeaseForTest().sessionId
    }

    /** Telemetry whose working average is deliberately NOT its peak (70/75/80 -> avg 75, peak 80). */
    private suspend fun TestScope.emitAverageNotPeakTelemetry(harness: DWSMTestHarness) {
        listOf(70f, 75f, 80f).forEachIndexed { index, load ->
            harness.fakeBleRepo.emitMetric(
                WorkoutMetric(
                    timestamp = 1_000L + index * 100L,
                    positionA = 600f + index,
                    positionB = 600f + index,
                    velocityA = 200.0,
                    velocityB = 200.0,
                    loadA = load,
                    loadB = load,
                ),
            )
        }
        advanceUntilIdle()
    }

    /** Completes the set with 9 working reps on the AMRAP packet. */
    private suspend fun TestScope.completeNineWorkingReps(harness: DWSMTestHarness) {
        harness.fakeBleRepo.emitRepNotification(
            harness.modernRepPacket(
                repsSetCount = 9,
                repsSetTotal = 252,
                timestamp = harness.nowMs + 1L,
                topCounter = 12,
                completeCounter = 12,
                repsRomCount = 3,
            ),
        )
        advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 9, totalReps = 9)
        advanceUntilIdle()
    }

    /** Handles back on the rack: the completed set auto-ends (the snapshot persistence site). */
    private suspend fun TestScope.autoReleaseTheSet(harness: DWSMTestHarness) {
        harness.coordinator.workoutStartTime = currentTimeMillis() - 10_000L
        harness.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                timestamp = 5_000L,
                positionA = 0f,
                positionB = 0f,
                velocityA = 0.0,
                velocityB = 0.0,
                loadA = 0f,
                loadB = 0f,
            ),
        )
        advanceUntilIdle()
    }

    // ===== (1) auto-release snapshot persistence site =====

    @Test
    fun `a loaded routine echo set records the achieved peak not the working average or the seed`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val sessionId = startEchoSetFromLoadedRoutine(harness)
            emitAverageNotPeakTelemetry(harness)
            assertTrue(harness.coordinator.observedSetMovement, "fixture must credit movement in this set")
            completeNineWorkingReps(harness)
            autoReleaseTheSet(harness)

            val session = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == sessionId }
            val completed = harness.fakeCompletedSetRepo.getCompletedSets(sessionId).single()

            assertEquals(80f, completed.actualWeightKg, "achieved load is the measured working PEAK per cable")
            assertEquals(SetEndReason.CABLE_RELEASED, completed.setEndReason, "the set auto-completed")

            // Configured metadata comes from the loaded routine and is preserved untouched.
            assertEquals(5f, session.weightPerCableKg, "the routine's 5 kg seed stays configured metadata")
            assertNotEquals(5f, completed.actualWeightKg, "the seed must never be recorded as achieved")

            // Average is deliberately distinct from peak: achieved must use peak, not average.
            val average = assertNotNull(session.workingAvgWeightKg, "the working average is recorded separately")
            assertTrue(average < 80f, "the working average is not the peak")
            assertEquals(80f, session.heaviestLiftKg, "the measured column holds the peak")
            assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(session), "reports show the achieved peak")
        } finally {
            harness.cleanup()
        }
    }

    // ===== (2) manual / secondary legacy-stop persistence site =====

    @Test
    fun `the manual stop path records the same achieved peak and keeps the routine seed configured`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val sessionId = startEchoSetFromLoadedRoutine(harness)
            emitAverageNotPeakTelemetry(harness)
            completeNineWorkingReps(harness)

            // The genuinely secondary legacy-stop path: a manual stop rather than auto-release.
            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == sessionId }
            val completed = harness.fakeCompletedSetRepo.getCompletedSets(sessionId).single()

            assertEquals(80f, completed.actualWeightKg, "the manual-stop site records the same achieved peak")
            assertEquals(5f, session.weightPerCableKg, "the loaded routine's configured seed is preserved")
            assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(session), "reports show the achieved peak")
            val average = assertNotNull(session.workingAvgWeightKg)
            assertTrue(average < 80f, "achieved is the peak, never the working average")
        } finally {
            harness.cleanup()
        }
    }

    // ===== (3) rack / counterweight never inflate the per-cable achieved peak =====

    @Test
    fun `rack and counterweight do not participate in the achieved per-cable load`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = echoRoutine(rackItemIds = listOf("rack-plate-25"))
            val sessionId = startEchoSetFromLoadedRoutine(harness, routine)

            // Equipment rack context is present for the set but is NOT part of the per-cable peak.
            harness.dwsm.updateActiveRackSelection(listOf("rack-plate-25"))
            advanceUntilIdle()

            emitAverageNotPeakTelemetry(harness)
            completeNineWorkingReps(harness)
            autoReleaseTheSet(harness)

            val session = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == sessionId }
            val completed = harness.fakeCompletedSetRepo.getCompletedSets(sessionId).single()

            // The achieved Echo load is the per-cable measured peak: 80, never 80 + rack - counterweight.
            assertEquals(80f, completed.actualWeightKg, "the achieved load is the per-cable measured peak only")
            assertEquals(80f, session.heaviestLiftKg, "the measured peak is rack/counterweight independent")
            assertEquals(5f, session.weightPerCableKg, "the routine's configured seed is still metadata")

            // Characterization: achieved-load resolution takes NO input from the rack/counterweight
            // fields. Whichever way the equipment-rack pipeline populates them, the reported Echo
            // load must stay the measured per-cable peak. (Populating those fields end to end is the
            // equipment-rack pipeline's contract, not issue #1182's.)
            val withRackContext = session.copy(externalAddedLoadKg = 20f, counterweightKg = 10f)
            assertEquals(
                80f,
                EchoAchievedLoadResolver.primaryLoadKg(withRackContext),
                "external added load and counterweight must never be added into the achieved Echo load",
            )
            assertEquals(
                80f,
                EchoAchievedLoadResolver.completedSetLoadKg(completed, withRackContext),
                "set rows resolve to the same per-cable peak regardless of rack/counterweight",
            )
        } finally {
            harness.cleanup()
        }
    }
}

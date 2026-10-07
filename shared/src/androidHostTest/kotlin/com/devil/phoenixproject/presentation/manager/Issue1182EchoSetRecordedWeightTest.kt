package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.domain.usecase.RoutineSetWeightRequest
import com.devil.phoenixproject.domain.usecase.RoutineSetWeightResolver
import com.devil.phoenixproject.presentation.routine.buildDefaultRoutineExerciseForEditor
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #1182: a completed Echo set records the load the user ACHIEVED (the machine-measured
 * peak per cable), not the configured placeholder. The routine editor's 5 kg default seed is
 * still the configured/command metadata (it is what the machine was told to hold) and is
 * preserved on `WorkoutSession.weightPerCableKg`; only `CompletedSet.actualWeightKg` and the
 * reporting surfaces switch to the measured peak.
 *
 * These tests pin the production chain end to end and assert the FIXED behavior:
 *   1. the routine editor seeds a new routine exercise with weightPerCableKg = 5f (configured);
 *   2. a completed Echo set with telemetry measured at 80 kg/cable records
 *      CompletedSet.actualWeightKg = 80f (achieved), while WorkoutSession.weightPerCableKg
 *      stays 5f (configured metadata);
 *   3. the achieved-load resolver reports 80f for the session, so reports/summaries no longer
 *      render the 5 kg seed (11.02 lb) as the achieved load.
 */
class Issue1182EchoSetRecordedWeightTest {

    // ===== (a) the seed is configured metadata, resolved to the programmed set weight =====

    @Test
    fun `the editor 5 kg seed is configured metadata and resolves to the programmed set weight`() {
        val seeded = buildDefaultRoutineExerciseForEditor(
            id = "routine-ex-1182",
            selectedExercise = TestFixtures.squat,
            orderIndex = 0,
            userPreferences = UserPreferences(
                defaultRoutineExerciseUsePercentOfPR = false,
                defaultRoutineExerciseWeightPercentOfPR = 90,
                defaultScalingBasis = ScalingBasis.MAX_VOLUME_PR,
            ),
        )
        assertEquals(5f, seeded.weightPerCableKg, "the routine editor seeds every new routine exercise with 5 kg")

        assertEquals(
            5f,
            RoutineSetWeightResolver(
                RoutineSetWeightRequest(exercise = seeded, setIndex = 0, currentPrKg = null),
            ),
            "the programmed set weight for the seeded exercise is the 5 kg seed itself (configured metadata)",
        )

        val managerScope = CoroutineScope(SupervisorJob())
        try {
            val manager = SettingsManager(FakePreferencesManager(), FakeUserProfileRepository().apply { setActiveProfileForTest() }, managerScope)
            // The seed is still formatted as 11.02 lb, but that is the CONFIGURED figure and
            // must never be presented as an Echo set's achieved load (see test (b)).
            assertEquals("11.02 lb", manager.formatWeight(5f, WeightUnit.LB), "the 5 kg configured seed formats as 11.02 lb")
        } finally {
            managerScope.cancel()
        }
    }

    // ===== (b) a completed Echo set records the ACHIEVED measured peak, not the placeholder =====

    @Test
    fun `a completed Echo set records the achieved measured peak and keeps the configured seed as metadata`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeExerciseRepo.addExercise(TestFixtures.squat)
            harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
            // The reporter's configuration: Barbell Squat, Echo, Epic, 130% eccentric, AMRAP,
            // with the routine exercise never weight-edited (the 5 kg editor seed).
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.Echo,
                    echoLevel = EchoLevel.EPIC,
                    eccentricLoad = EccentricLoad.LOAD_130,
                    reps = 0,
                    warmupReps = 3,
                    weightPerCableKg = 5f,
                    isAMRAP = true,
                    selectedExerciseId = TestFixtures.squat.id,
                    stallDetectionEnabled = false,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(harness.coordinator.workoutState.value is WorkoutState.Active, "the Echo set must start")

            // The machine measures the real lift: 80 kg per cable through the set.
            repeat(15) { index ->
                harness.fakeBleRepo.emitMetric(
                    metric(timestamp = 1_000L + index * 100L, position = 600f + index, velocity = 200.0, load = 80f),
                )
            }
            advanceUntilIdle()
            assertTrue(harness.coordinator.observedSetMovement, "fixture must credit movement in this set")

            // The set completes successfully: 3 ROM warm-up reps plus 9 working reps,
            // on an unlimited (AMRAP) packet - the reporter's 9-rep completed set.
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
            assertEquals(9, harness.coordinator.repCount.value.workingReps, "fixture must count 9 working reps")

            // Handles back on the rack: the completed set auto-ends (CABLE_RELEASED).
            harness.coordinator.workoutStartTime = currentTimeMillis() - 10_000L
            harness.coordinator.autoStopStartTime = currentTimeMillis() - 10_000L
            harness.fakeBleRepo.emitMetric(metric(timestamp = 5_000L, position = 0f, velocity = 0.0, load = 0f))
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.saveSessionAttempts.single { it.id == lease.sessionId }
            val completed = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId).single()

            // The ACHIEVED measured peak lands on the CompletedSet row: 80 kg/cable, not the seed.
            assertEquals(
                80f,
                completed.actualWeightKg,
                "a completed Echo set records the achieved measured peak, not the configured placeholder",
            )
            assertNotEquals(5f, completed.actualWeightKg, "the 5 kg configured seed must never be recorded as achieved load")

            // The configured/command metadata is preserved on the session row.
            assertEquals(
                5f,
                session.weightPerCableKg,
                "WorkoutSession.weightPerCableKg keeps the configured/command weight (the 5 kg seed)",
            )
            assertEquals(SetEndReason.CABLE_RELEASED, completed.setEndReason, "the set completed successfully")

            // The measured telemetry is still captured separately and unchanged.
            val measured = kotlin.test.assertNotNull(session.workingAvgWeightKg, "Echo stores its measured working load")
            assertEquals(80f, measured, "the telemetry measured 80 kg per cable through the working window")
            assertEquals(80f, session.heaviestLiftKg, "the heaviest lift is also the measured 80 kg")

            // The achieved-load resolver reports the measured peak, so reports/summaries render
            // 80 kg, never the 5 kg configured seed (11.02 lb).
            assertEquals(80f, EchoAchievedLoadResolver.fromSession(session), "the resolver reports the measured peak as achieved load")
            assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(session), "primary report load is the achieved peak")
            val managerScope = CoroutineScope(SupervisorJob())
            try {
                val manager = SettingsManager(FakePreferencesManager(), FakeUserProfileRepository().apply { setActiveProfileForTest() }, managerScope)
                val achievedLb = manager.formatWeight(EchoAchievedLoadResolver.primaryLoadKg(session)!!, WeightUnit.LB)
                assertNotEquals("11.02 lb", achievedLb, "the achieved load must never render as the configured 11.02 lb seed")
                assertEquals(manager.formatWeight(80f, WeightUnit.LB), achievedLb, "the achieved load renders as the measured 80 kg")
            } finally {
                managerScope.cancel()
            }
        } finally {
            harness.cleanup()
        }
    }

    // ===== fixtures =====

    private fun metric(
        timestamp: Long,
        position: Float,
        velocity: Double = 0.0,
        load: Float = 25f,
    ) = WorkoutMetric(
        timestamp = timestamp,
        positionA = position,
        positionB = position,
        velocityA = velocity,
        velocityB = velocity,
        loadA = load,
        loadB = load,
    )
}

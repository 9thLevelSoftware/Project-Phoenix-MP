package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.SqlDelightCompletedSetRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Issue #1182 acceptance matrix at the persistence boundary (merge-gate R5): both terminal
 * persistence sites (manual stop and auto-release), the configured-versus-achieved
 * distinction, the unmeasured sentinel, SQLDelight read-back/reopen and profile/session
 * ownership - and the conservative read-time resolution of legacy placeholder rows (never
 * the configured 11.02 lb).
 */
class Issue1182SqlAchievedLoadPersistenceTest {

    @Test
    fun `manual-stop Echo set with no telemetry persists the unmeasured sentinel not the seed`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val harness = DWSMTestHarness(
            testScope = this,
            workoutRepositoryOverride = workoutRepository,
            completedSetRepositoryOverride = completedSetRepository,
        )
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.Echo,
                    echoLevel = EchoLevel.EPIC,
                    eccentricLoad = EccentricLoad.LOAD_130,
                    reps = 0,
                    warmupReps = 0,
                    weightPerCableKg = 5f,
                    isAMRAP = true,
                    selectedExerciseId = TestFixtures.squat.id,
                    stallDetectionEnabled = false,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val sessionId = harness.activeSessionEngine.currentExecutionLeaseForTest().sessionId
            // Reps reported but NO telemetry accepted: no working sample exists.
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)
            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            // The session row and its CompletedSet land in ONE transaction (F-012); wait for
            // the write instead of racing the IO dispatcher.
            val set = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    completedSetRepository.getCompletedSetsFlow(sessionId).first { it.size == 1 }.single()
                }
            }
            val session = workoutRepository.getSession(sessionId)!!
            assertEquals(0f, set.actualWeightKg, "no accepted working telemetry -> the non-null 0 sentinel, never the 5 kg seed")
            assertEquals(5f, session.weightPerCableKg, "configured/command metadata is preserved on the session")
            assertEquals(0f, session.heaviestLiftKg, "the measured column carries the unmeasured sentinel, never the fallback")
            assertNull(EchoAchievedLoadResolver.fromSession(session), "unmeasured Echo resolves unavailable")
            assertNull(EchoAchievedLoadResolver.completedSetLoadKg(set, session), "no measured load -> Load unavailable")

            // SQLDelight read-back/reopen: the same facts survive a fresh repository, and
            // profile/session ownership is intact.
            val reopenedSession = SqlDelightWorkoutRepository(database, FakeExerciseRepository()).getSession(sessionId)!!
            val reopenedSet = SqlDelightCompletedSetRepository(database).getCompletedSets(sessionId).single()
            assertEquals(session.profileId, reopenedSession.profileId, "profile ownership survives reopen")
            assertEquals(sessionId, reopenedSet.sessionId, "the set row stays owned by its session")
            assertEquals(0f, reopenedSet.actualWeightKg)
            assertNull(EchoAchievedLoadResolver.completedSetLoadKg(reopenedSet, reopenedSession))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `completed Echo set with measured load equal to configured persists the measurement`() = runTest {
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val completedSetRepository = SqlDelightCompletedSetRepository(database)
        val harness = DWSMTestHarness(
            testScope = this,
            workoutRepositoryOverride = workoutRepository,
            completedSetRepositoryOverride = completedSetRepository,
        )
        try {
            harness.fakeExerciseRepo.addExercise(TestFixtures.squat)
            harness.fakeBleRepo.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.Echo,
                    echoLevel = EchoLevel.EPIC,
                    eccentricLoad = EccentricLoad.LOAD_130,
                    reps = 0,
                    warmupReps = 0,
                    weightPerCableKg = 5f,
                    isAMRAP = true,
                    selectedExerciseId = TestFixtures.squat.id,
                    stallDetectionEnabled = false,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()

            // The machine measures a real lift that happens to equal the configured weight.
            // A real Echo rep loads both phases: the lift, then the lowering, so the set
            // records the eccentric peak that is the locally captured evidence.
            repeat(15) { index ->
                val velocity = if (index < 8) 200.0 else -200.0
                harness.fakeBleRepo.emitMetric(
                    WorkoutMetric(
                        timestamp = 1_000L + index * 100L,
                        positionA = 600f + index,
                        positionB = 600f + index,
                        velocityA = velocity,
                        velocityB = velocity,
                        loadA = 5f,
                        loadB = 5f,
                    ),
                )
            }
            advanceUntilIdle()
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

            val set = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    completedSetRepository.getCompletedSetsFlow(lease.sessionId).first { it.size == 1 }.single()
                }
            }
            val session = workoutRepository.getSession(lease.sessionId)!!
            assertEquals(5f, set.actualWeightKg, "a measured 5 kg lift is recorded as 5 kg even though configured is also 5 kg")
            assertEquals(5f, session.weightPerCableKg, "configured metadata is unchanged")
            assertEquals(5f, session.heaviestLiftKg, "the measured column records the measurement")
            assertEquals(5f, EchoAchievedLoadResolver.fromSession(session), "telemetry evidence preserves measured == configured")
            assertEquals(5f, EchoAchievedLoadResolver.completedSetLoadKg(set, session))

            // Reopen: the configured-versus-achieved distinction survives.
            val reopenedSession = SqlDelightWorkoutRepository(database, FakeExerciseRepository()).getSession(lease.sessionId)!!
            val reopenedSet = SqlDelightCompletedSetRepository(database).getCompletedSets(lease.sessionId).single()
            assertEquals(5f, reopenedSet.actualWeightKg)
            assertEquals(5f, EchoAchievedLoadResolver.completedSetLoadKg(reopenedSet, reopenedSession))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `legacy Echo rows never re-render the configured seed as achievement`() = runTest {
        // The reporter's historical row shape: empty telemetry window recorded the fallback
        // (= configured seed) with zero forces. Read-time resolution is conservative and
        // non-destructive: "Load unavailable", never the configured 11.02 lb, no rewrite.
        val database = createTestDatabase()
        val workoutRepository = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
        val legacy = WorkoutSession(
            timestamp = currentTimeMillis(),
            mode = "Echo",
            weightPerCableKg = 5f,
            heaviestLiftKg = 5f,
            exerciseName = "Barbell Squat",
            totalReps = 9,
            workingReps = 9,
        )
        workoutRepository.saveSession(legacy)
        val reopened = workoutRepository.getSession(legacy.id)!!
        assertEquals(5f, reopened.weightPerCableKg, "the legacy row is not rewritten")
        assertNull(EchoAchievedLoadResolver.fromSession(reopened), "never the configured 11.02 lb")
    }
}

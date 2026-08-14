package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.SqlDelightCompletedSetRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.TestFixtures
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class Issue673SetEndReasonSqlIntegrationTest {

    @Test
    fun `late Just Lift tagging retains captured reason in reopened SQL repository`() = runTest {
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
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 25f,
                    isJustLift = true,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true, isJustLiftMode = true)
            advanceUntilIdle()
            val sessionId = harness.activeSessionEngine.currentExecutionLeaseForTest().sessionId
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val captured = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    completedSetRepository.getCompletedSetsFlow(sessionId).first { it.size == 1 }.single()
                }
            }
            assertEquals(SetEndReason.USER_STOPPED, captured.setEndReason)

            harness.dwsm.tagJustLiftSessionExercise(sessionId, TestFixtures.deadlift, isAmrap = false)
            advanceUntilIdle()

            assertEquals(TestFixtures.deadlift.id, workoutRepository.getSession(sessionId)?.exerciseId)
            val reopenedRepository = SqlDelightCompletedSetRepository(database)
            val persisted = reopenedRepository.getCompletedSets(sessionId).single()
            assertEquals(captured.id, persisted.id)
            assertEquals(SetEndReason.USER_STOPPED, persisted.setEndReason)
            assertEquals(
                "USER_STOPPED",
                database.vitruvianDatabaseQueries.selectCompletedSetById(captured.id).executeAsOne().set_end_reason,
            )
        } finally {
            harness.cleanup()
        }
    }
}

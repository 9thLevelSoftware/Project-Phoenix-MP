package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class Issue673SetEndReasonLifecycleTest {

    @Test
    fun `untagged positive-rep non Just Lift completion does not persist a completed set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.updateWorkoutParameters(
                WorkoutParameters(
                    programMode = ProgramMode.OldSchool,
                    reps = 8,
                    warmupReps = 0,
                    weightPerCableKg = 25f,
                    isJustLift = false,
                    selectedExerciseId = null,
                ),
            )
            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
            assertNull(session.exerciseId)
            assertEquals(emptyList(), harness.fakeCompletedSetRepo.getCompletedSets(session.id))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `untagged positive-rep Just Lift completion persists completed set before later tagging`() = runTest {
        val harness = DWSMTestHarness(this)
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
            harness.coordinator._repCount.value = RepCount(workingReps = 3, totalReps = 3)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            val session = harness.fakeWorkoutRepo.getAllSessions("default").first().single()
            assertNull(session.exerciseId)
            val captured = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
            assertEquals(3, captured.actualReps)
            assertEquals(SetEndReason.USER_STOPPED, captured.setEndReason)

            harness.dwsm.tagJustLiftSessionExercise(session.id, TestFixtures.deadlift, isAmrap = false)
            advanceUntilIdle()

            val afterTagging = harness.fakeCompletedSetRepo.getCompletedSets(session.id).single()
            assertEquals(captured.id, afterTagging.id)
            assertEquals(SetEndReason.USER_STOPPED, afterTagging.setEndReason)
        } finally {
            harness.cleanup()
        }
    }
}

package com.devil.phoenixproject.conformance

import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class WorkoutLifecycleConformanceTest {

    @Test
    fun `workout transitions Idle to Active to SetSummary`() = runTest {
        VendorConformanceTargets.selected().forEach { _ ->
            val harness = DWSMTestHarness(this)
            harness.fakeBleRepo.simulateConnect("Vee_Test")

            assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)

            harness.dwsm.startWorkout(skipCountdown = true)
            advanceUntilIdle()
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()
            assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)

            harness.cleanup()
        }
    }
}

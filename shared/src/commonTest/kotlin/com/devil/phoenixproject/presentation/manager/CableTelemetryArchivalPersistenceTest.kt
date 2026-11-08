package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class CableTelemetryArchivalPersistenceTest {

    @Test
    fun `manual stop trims idle prefix and preserves partial telemetry and summary input`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val samples = telemetryWithMovement()
            samples.forEach(harness.coordinator.collectedMetrics::append)

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(
                samples.drop(4).map { it.timestamp },
                harness.fakeWorkoutRepo.metricsForSession(lease.sessionId).map { it.timestamp },
            )
            val summary = assertIs<WorkoutState.SetSummary>(harness.coordinator.workoutState.value)
            assertEquals(samples, summary.metrics)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `idle-only cancellation retains the complete telemetry payload`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val samples = (0..8).map { index ->
                WorkoutMetric(
                    timestamp = index * SAMPLE_INTERVAL_MS,
                    loadA = 20f,
                    loadB = 18f,
                    positionA = 100f,
                    positionB = 200f,
                )
            }
            samples.forEach(harness.coordinator.collectedMetrics::append)
            harness.coordinator._repCount.value = RepCount()

            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(samples, harness.fakeWorkoutRepo.metricsForSession(lease.sessionId))
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `cancel and restart isolate the next sets motion baseline`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            startTrackedCableSet(harness)
            val firstLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val firstSamples = telemetryWithMovement()
            firstSamples.forEach(harness.coordinator.collectedMetrics::append)
            harness.dwsm.stopWorkout(exitingWorkout = true)
            advanceUntilIdle()
            assertEquals(
                firstSamples.drop(4).map { it.timestamp },
                harness.fakeWorkoutRepo.metricsForSession(firstLease.sessionId).map { it.timestamp },
            )

            startTrackedCableSet(harness)
            val nextLease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            val nextSamples = (0..8).map { index ->
                WorkoutMetric(
                    timestamp = index * SAMPLE_INTERVAL_MS,
                    loadA = 20f,
                    loadB = 18f,
                    positionA = 100f,
                    positionB = 200f,
                )
            }
            nextSamples.forEach(harness.coordinator.collectedMetrics::append)
            harness.coordinator._repCount.value = RepCount()
            harness.dwsm.stopWorkout(exitingWorkout = false)
            advanceUntilIdle()

            assertEquals(nextSamples, harness.fakeWorkoutRepo.metricsForSession(nextLease.sessionId))
        } finally {
            harness.cleanup()
        }
    }

    private fun startTrackedCableSet(harness: DWSMTestHarness) {
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 3,
                warmupReps = 0,
                weightPerCableKg = 25f,
                selectedExerciseId = TestFixtures.benchPress.id,
            ),
        )
        harness.dwsm.startWorkout(skipCountdown = true)
        harness.testScope.testScheduler.advanceUntilIdle()
        harness.coordinator._repCount.value = RepCount(workingReps = 2)
    }

    private fun telemetryWithMovement(): List<WorkoutMetric> = (0..16).map { index ->
        val positionA = when (index) {
            in 0..7 -> 100f
            8 -> 101.5f
            9 -> 103f
            10 -> 105.5f
            11 -> 108f
            else -> 111f
        }
        WorkoutMetric(
            timestamp = index * SAMPLE_INTERVAL_MS,
            loadA = 20f,
            loadB = 18f,
            positionA = positionA,
            positionB = 200f,
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 250L
    }
}

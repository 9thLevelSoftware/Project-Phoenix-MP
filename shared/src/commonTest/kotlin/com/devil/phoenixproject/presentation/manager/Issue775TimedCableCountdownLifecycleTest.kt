package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.testutil.DWSMTestHarness
import com.devil.phoenixproject.testutil.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class Issue775TimedCableCountdownLifecycleTest {

    @Test
    fun `duration above supported range cannot enable timed unlimited BLE mode`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = timedCableRoutine(durationSeconds = 301)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertFalse(lease.isTimedCable)
            assertEquals(10, lease.workingRepTarget)
            assertFalse(harness.coordinator.isCurrentTimedCableExercise)
            assertTrue(
                harness.fakeBleRepo.commandsReceived.last()[0x04].toInt() and 0xFF != 0xFF,
                "An invalid duration must not turn a fixed-rep set into an unlimited BLE command",
            )
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `invalid duration does not disable an explicitly configured AMRAP set`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = timedCableRoutine(durationSeconds = Int.MAX_VALUE, isAmrap = true)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertFalse(lease.isTimedCable)
            assertTrue(lease.isAmrap)
            assertEquals(0xFF, harness.fakeBleRepo.commandsReceived.last()[0x04].toInt() and 0xFF)
        } finally {
            harness.cleanup()
        }
    }

    @Test
    fun `timed cable target 252 warmup starts countdown and persists timer expired`() = runTest {
        val harness = DWSMTestHarness(this)
        try {
            val routine = timedCableRoutine(durationSeconds = 30)
            routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }
            harness.dwsm.loadRoutine(routine)
            advanceUntilIdle()
            harness.dwsm.enterSetReady(0, 0)
            harness.fakeBleRepo.simulateConnect("Vee_Test")
            harness.dwsm.startWorkout(skipCountdown = true)
            runCurrent()

            val lease = harness.activeSessionEngine.currentExecutionLeaseForTest()
            assertTrue(lease.isTimedCable)
            assertFalse(lease.usesUnlimitedRepTarget)
            assertEquals(0, lease.workingRepTarget)
            assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
            assertEquals(
                0xFF,
                harness.fakeBleRepo.commandsReceived.last()[0x04].toInt() and 0xFF,
            )
            assertNull(harness.coordinator.timedExerciseRemainingSeconds.value)
            assertFalse(harness.coordinator.repCount.value.isWarmupComplete)

            val cutover = lease.activationCutoverTimestampMs
                ?: error("timed cable lease was not activated")
            emitWarmupRom(harness, romCount = 1, timestamp = cutover + 1)
            runCurrent()
            assertEquals(1, harness.coordinator.repCount.value.warmupReps)
            assertFalse(harness.coordinator.repCount.value.isWarmupComplete)
            assertNull(harness.coordinator.timedExerciseRemainingSeconds.value)

            emitWarmupRom(harness, romCount = 2, timestamp = cutover + 2)
            runCurrent()
            assertEquals(2, harness.coordinator.repCount.value.warmupReps)
            assertFalse(harness.coordinator.repCount.value.isWarmupComplete)
            assertNull(harness.coordinator.timedExerciseRemainingSeconds.value)

            emitWarmupRom(harness, romCount = 3, timestamp = cutover + 3)
            runCurrent()
            assertEquals(3, harness.coordinator.repCount.value.warmupReps)
            assertTrue(harness.coordinator.repCount.value.isWarmupComplete)
            assertEquals(0, harness.coordinator.repCount.value.workingReps)
            assertEquals(30, harness.coordinator.timedExerciseRemainingSeconds.value)

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(29, harness.coordinator.timedExerciseRemainingSeconds.value)

            advanceTimeBy(29_000L)
            advanceUntilIdle()

            // handleSetCompletion clears the published remaining value after expiry.
            assertTrue(
                harness.coordinator.timedExerciseRemainingSeconds.value == 0 ||
                    harness.coordinator.timedExerciseRemainingSeconds.value == null,
            )
            val completed = harness.fakeCompletedSetRepo.getCompletedSets(lease.sessionId)
            assertEquals(1, completed.size)
            assertEquals(SetEndReason.TIMER_EXPIRED, completed.single().setEndReason)
        } finally {
            harness.cleanup()
        }
    }

    private suspend fun emitWarmupRom(
        harness: DWSMTestHarness,
        romCount: Int,
        timestamp: Long,
    ) {
        harness.fakeBleRepo.emitMetric(
            WorkoutMetric(
                positionA = 120f,
                positionB = 120f,
                velocityA = 80.0,
                velocityB = 80.0,
                loadA = 10f,
                loadB = 10f,
            ),
        )
        harness.fakeBleRepo.emitRepNotification(
            RepNotification(
                topCounter = romCount,
                completeCounter = romCount,
                repsRomCount = romCount,
                repsRomTotal = 3,
                repsSetCount = 0,
                repsSetTotal = 252,
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = timestamp,
            ),
        )
    }

    private fun timedCableRoutine(durationSeconds: Int, isAmrap: Boolean = false) = Routine(
        id = "issue-775-timed-cable",
        name = "Issue 775 Timed Cable",
        exercises = listOf(
            RoutineExercise(
                id = "issue-775-timed-bench",
                exercise = TestFixtures.benchPress,
                orderIndex = 0,
                setReps = listOf(if (isAmrap) null else 10),
                weightPerCableKg = 25f,
                duration = durationSeconds,
                isAMRAP = isAmrap,
                setRestSeconds = listOf(0),
            ),
        ),
    )
}

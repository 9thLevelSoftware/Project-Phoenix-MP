package com.devil.phoenixproject.e2e.robot

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.WorkoutType
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.testutil.FakeBleRepository
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Robot pattern for workout E2E tests.
 * Separates "what" (user actions) from "how" (implementation details).
 *
 * Usage:
 * ```
 * workoutRobot {
 *     connectToDevice("Vee_MyTrainer")
 *     configureWorkout(weight = 20f, reps = 10, mode = ProgramMode.Pump)
 *     startWorkout()
 *     simulateReps(5)
 *     verifyRepCount(5)
 *     completeWorkout()
 * }
 * ```
 */
class WorkoutRobot(
    private val viewModel: MainViewModel,
    private val bleRepository: FakeBleRepository
) {

    // ========== Connection Actions ==========

    fun connectToDevice(deviceName: String = "Vee_TestDevice"): WorkoutRobot {
        bleRepository.simulateConnect(deviceName, "AA:BB:CC:DD:EE:FF")
        return this
    }

    fun disconnect(): WorkoutRobot {
        bleRepository.simulateDisconnect()
        return this
    }

    fun simulateConnectionError(message: String = "Connection failed"): WorkoutRobot {
        bleRepository.simulateError(message)
        return this
    }

    // ========== Configuration Actions ==========

    fun configureWorkout(
        weight: Float = 20f,
        reps: Int = 10,
        mode: ProgramMode = ProgramMode.OldSchool,
        warmupReps: Int = 3,
        isJustLift: Boolean = false
    ): WorkoutRobot {
        val params = WorkoutParameters(
            workoutType = WorkoutType.Program(mode),
            weightPerCableKg = weight,
            reps = reps,
            warmupReps = warmupReps,
            isJustLift = isJustLift
        )
        viewModel.updateWorkoutParameters(params)
        return this
    }

    fun setWeight(weight: Float): WorkoutRobot {
        val current = viewModel.workoutParameters.value
        viewModel.updateWorkoutParameters(current.copy(weightPerCableKg = weight))
        return this
    }

    fun setReps(reps: Int): WorkoutRobot {
        val current = viewModel.workoutParameters.value
        viewModel.updateWorkoutParameters(current.copy(reps = reps))
        return this
    }

    fun setMode(mode: ProgramMode): WorkoutRobot {
        val current = viewModel.workoutParameters.value
        viewModel.updateWorkoutParameters(current.copy(workoutType = WorkoutType.Program(mode)))
        return this
    }

    fun enableJustLift(): WorkoutRobot {
        val current = viewModel.workoutParameters.value
        viewModel.updateWorkoutParameters(current.copy(isJustLift = true))
        return this
    }

    // ========== Workout Simulation Actions ==========

    suspend fun simulateMetric(
        positionA: Float = 500f,
        positionB: Float = 500f,
        velocityA: Double = 100.0,
        velocityB: Double = 100.0,
        loadA: Float = 20f,
        loadB: Float = 20f
    ): WorkoutRobot {
        val metric = WorkoutMetric(
            positionA = positionA,
            positionB = positionB,
            velocityA = velocityA,
            velocityB = velocityB,
            loadA = loadA,
            loadB = loadB
        )
        bleRepository.emitMetric(metric)
        return this
    }

    suspend fun simulateReps(count: Int): WorkoutRobot {
        repeat(count) {
            // Simulate concentric phase (extension)
            simulateMetric(positionA = 0f, positionB = 0f, velocityA = 200.0, velocityB = 200.0)
            // Simulate eccentric phase (return)
            simulateMetric(positionA = 1000f, positionB = 1000f, velocityA = -150.0, velocityB = -150.0)
        }
        return this
    }

    // ========== Assertions ==========

    fun verifyConnected(): WorkoutRobot {
        assertIs<ConnectionState.Connected>(viewModel.connectionState.value)
        return this
    }

    fun verifyConnectedTo(deviceName: String): WorkoutRobot {
        val state = viewModel.connectionState.value
        assertIs<ConnectionState.Connected>(state)
        assertEquals(deviceName, state.deviceName)
        return this
    }

    fun verifyDisconnected(): WorkoutRobot {
        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
        return this
    }

    fun verifyConnectionError(): WorkoutRobot {
        assertIs<ConnectionState.Error>(viewModel.connectionState.value)
        return this
    }

    fun verifyWorkoutIdle(): WorkoutRobot {
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)
        return this
    }

    fun verifyWeight(expectedWeight: Float): WorkoutRobot {
        assertEquals(expectedWeight, viewModel.workoutParameters.value.weightPerCableKg)
        return this
    }

    fun verifyReps(expectedReps: Int): WorkoutRobot {
        assertEquals(expectedReps, viewModel.workoutParameters.value.reps)
        return this
    }

    fun verifyMode(expectedMode: ProgramMode): WorkoutRobot {
        val workoutType = viewModel.workoutParameters.value.workoutType
        assertIs<WorkoutType.Program>(workoutType)
        assertEquals(expectedMode, workoutType.mode)
        return this
    }

    fun verifyJustLiftEnabled(): WorkoutRobot {
        assertTrue(viewModel.workoutParameters.value.isJustLift)
        return this
    }

    fun verifyWarmupReps(expectedWarmupReps: Int): WorkoutRobot {
        assertEquals(expectedWarmupReps, viewModel.workoutParameters.value.warmupReps)
        return this
    }

    fun verifyTotalReps(expectedTotal: Int): WorkoutRobot {
        val params = viewModel.workoutParameters.value
        assertEquals(expectedTotal, params.reps + params.warmupReps)
        return this
    }
}

/**
 * DSL function to create and use a WorkoutRobot.
 */
inline fun workoutRobot(
    viewModel: MainViewModel,
    bleRepository: FakeBleRepository,
    block: WorkoutRobot.() -> Unit
): WorkoutRobot {
    return WorkoutRobot(viewModel, bleRepository).apply(block)
}

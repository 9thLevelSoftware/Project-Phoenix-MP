package com.devil.phoenixproject.presentation.viewmodel

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var viewModel: MainViewModel
    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var fakeWorkoutRepository: FakeWorkoutRepository
    private lateinit var fakeExerciseRepository: FakeExerciseRepository
    private lateinit var fakePersonalRecordRepository: FakePersonalRecordRepository
    private lateinit var fakePreferencesManager: FakePreferencesManager
    private lateinit var fakeGamificationRepository: FakeGamificationRepository
    private lateinit var fakeTrainingCycleRepository: FakeTrainingCycleRepository
    private lateinit var fakeCompletedSetRepository: FakeCompletedSetRepository
    private lateinit var repCounter: RepCounterFromMachine
    private lateinit var resolveWeightsUseCase: ResolveRoutineWeightsUseCase

    @Before
    fun setup() {
        fakeBleRepository = FakeBleRepository()
        fakeWorkoutRepository = FakeWorkoutRepository()
        fakeExerciseRepository = FakeExerciseRepository()
        fakePersonalRecordRepository = FakePersonalRecordRepository()
        fakePreferencesManager = FakePreferencesManager()
        fakeGamificationRepository = FakeGamificationRepository()
        fakeTrainingCycleRepository = FakeTrainingCycleRepository()
        fakeCompletedSetRepository = FakeCompletedSetRepository()
        repCounter = RepCounterFromMachine()
        resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePersonalRecordRepository)

        viewModel = MainViewModel(
            bleRepository = fakeBleRepository,
            workoutRepository = fakeWorkoutRepository,
            exerciseRepository = fakeExerciseRepository,
            personalRecordRepository = fakePersonalRecordRepository,
            repCounter = repCounter,
            preferencesManager = fakePreferencesManager,
            gamificationRepository = fakeGamificationRepository,
            trainingCycleRepository = fakeTrainingCycleRepository,
            completedSetRepository = fakeCompletedSetRepository,
            resolveWeightsUseCase = resolveWeightsUseCase
        )
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is disconnected and idle`() = runTest {
        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)
        assertNull(viewModel.currentMetric.value)
        assertEquals(0, viewModel.repCount.value.warmupReps)
        assertEquals(0, viewModel.repCount.value.workingReps)
    }

    @Test
    fun `initial workout parameters are default values`() = runTest {
        val params = viewModel.workoutParameters.value
        assertEquals(ProgramMode.OldSchool, params.programMode)
        assertEquals(10, params.reps)
        assertEquals(10f, params.weightPerCableKg)
        assertFalse(params.isJustLift)
        assertEquals(3, params.warmupReps)
    }

    // ========== Connection State Tests ==========

    @Test
    fun `connectionState reflects BLE repository state`() = runTest {
        viewModel.connectionState.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())

            fakeBleRepository.simulateScanning()
            assertEquals(ConnectionState.Scanning, awaitItem())

            fakeBleRepository.simulateConnecting()
            assertEquals(ConnectionState.Connecting, awaitItem())

            fakeBleRepository.simulateConnect("Vee_Test123", "AA:BB:CC:DD:EE:FF")
            val connected = awaitItem()
            assertIs<ConnectionState.Connected>(connected)
            assertEquals("Vee_Test123", connected.deviceName)

            fakeBleRepository.simulateDisconnect()
            assertEquals(ConnectionState.Disconnected, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectionState reflects error state`() = runTest {
        viewModel.connectionState.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())

            fakeBleRepository.simulateError("Connection timeout")
            val error = awaitItem()
            assertIs<ConnectionState.Error>(error)
            assertEquals("Connection timeout", error.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Workout Parameters Tests ==========

    @Test
    fun `updateWorkoutParameters updates state`() = runTest {
        val newParams = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 15,
            weightPerCableKg = 25f,
            progressionRegressionKg = 2.5f,
            isJustLift = false,
            stopAtTop = true,
            warmupReps = 5
        )

        viewModel.updateWorkoutParameters(newParams)

        assertEquals(15, viewModel.workoutParameters.value.reps)
        assertEquals(25f, viewModel.workoutParameters.value.weightPerCableKg)
        assertEquals(2.5f, viewModel.workoutParameters.value.progressionRegressionKg)
        assertTrue(viewModel.workoutParameters.value.stopAtTop)
        assertEquals(5, viewModel.workoutParameters.value.warmupReps)
    }

    // ========== Disconnect Tests ==========

    @Test
    fun `disconnect calls BLE repository disconnect`() = runTest {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
    }

    // ========== User Preferences Tests ==========

    @Test
    fun `userPreferences reflects preferences manager state`() = runTest {
        advanceUntilIdle()

        val preferences = viewModel.userPreferences.value
        assertNotNull(preferences)

        // Verify default values - default is LB per UserPreferences definition
        assertEquals(WeightUnit.LB, preferences.weightUnit)
    }

    @Test
    fun `userPreferences updates when preferences change`() = runTest {
        viewModel.userPreferences.test {
            awaitItem() // Initial value

            fakePreferencesManager.setPreferences(
                UserPreferences(
                    weightUnit = WeightUnit.LB,
                    stopAtTop = true
                )
            )

            val updated = awaitItem()
            assertEquals(WeightUnit.LB, updated.weightUnit)
            assertTrue(updated.stopAtTop)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Top Bar State Tests ==========

    @Test
    fun `updateTopBarTitle updates title`() = runTest {
        assertEquals("Project Phoenix", viewModel.topBarTitle.value)

        viewModel.updateTopBarTitle("Active Workout")

        assertEquals("Active Workout", viewModel.topBarTitle.value)
    }

    @Test
    fun `clearTopBarActions clears actions`() = runTest {
        // Set some actions first using reflection or public method if available
        viewModel.clearTopBarActions()

        assertTrue(viewModel.topBarActions.value.isEmpty())
    }

    @Test
    fun `setTopBarBackAction sets back action`() = runTest {
        assertNull(viewModel.topBarBackAction.value)

        var backPressed = false
        viewModel.setTopBarBackAction { backPressed = true }

        assertNotNull(viewModel.topBarBackAction.value)
        viewModel.topBarBackAction.value?.invoke()
        assertTrue(backPressed)
    }

    @Test
    fun `clearTopBarBackAction clears back action`() = runTest {
        viewModel.setTopBarBackAction { }

        viewModel.clearTopBarBackAction()

        assertNull(viewModel.topBarBackAction.value)
    }

    // ========== Routines Tests ==========

    @Test
    fun `routines reflects workout repository state`() = runTest {
        viewModel.routines.test {
            // Initial empty list
            assertEquals(emptyList(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRoutineById returns routine when exists`() = runTest {
        val routine = Routine(
            id = "routine-1",
            name = "Push Day",
            exercises = emptyList()
        )
        fakeWorkoutRepository.addRoutine(routine)
        advanceUntilIdle()

        val found = viewModel.getRoutineById("routine-1")

        assertNotNull(found)
        assertEquals("Push Day", found.name)
    }

    @Test
    fun `getRoutineById returns null when not found`() = runTest {
        val found = viewModel.getRoutineById("non-existent")

        assertNull(found)
    }

    // ========== Workout History Tests ==========

    @Test
    fun `workoutHistory reflects repository sessions`() = runTest {
        viewModel.workoutHistory.test {
            assertEquals(emptyList(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Connection Error Tests ==========

    @Test
    fun `connectionError is initially null`() = runTest {
        assertNull(viewModel.connectionError.value)
    }

    // ========== Auto-Start Tests ==========

    @Test
    fun `autoStartCountdown is initially null`() = runTest {
        assertNull(viewModel.autoStartCountdown.value)
    }

    // ========== Auto-Stop State Tests ==========

    @Test
    fun `autoStopState has default values`() = runTest {
        val state = viewModel.autoStopState.value
        assertFalse(state.isActive)
        assertEquals(0f, state.progress)
    }

    // ========== Scanned Devices Tests ==========

    @Test
    fun `scannedDevices is initially empty`() = runTest {
        // ViewModel maintains its own scannedDevices state (initialized empty)
        assertEquals(emptyList<ScannedDevice>(), viewModel.scannedDevices.value)
    }

    @Test
    fun `startWorkout sends commands and moves to active`() = runTest {
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 2,
                warmupReps = 0,
                weightPerCableKg = 20f
            )
        )

        fakeBleRepository.emitMetric(
            WorkoutMetric(
                positionA = 100f,
                positionB = 100f,
                loadA = 10f,
                loadB = 10f
            )
        )

        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertEquals(WorkoutState.Active, viewModel.workoutState.value)
        // Issue #222: INIT command removed - now only CONFIG (0x04) + START (0x03)
        assertEquals(2, fakeBleRepository.commandsReceived.size)
    }

    @Test
    fun `rep events update counts and stop workout at target`() = runTest {
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 2,
                warmupReps = 0,
                weightPerCableKg = 20f
            )
        )

        val metric = WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f)
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        emitRepNotification(repIndex = 1, metric = metric)
        emitRepNotification(repIndex = 2, metric = metric)
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(viewModel.workoutState.value)
        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync(10).size)
        assertEquals(2, viewModel.repCount.value.workingReps)
    }

    @Test
    fun `disconnect during workout sets connection lost flag`() = runTest {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 1,
                warmupReps = 0,
                weightPerCableKg = 20f
            )
        )
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        fakeBleRepository.simulateDisconnect()
        advanceUntilIdle()

        assertTrue(viewModel.connectionLostDuringWorkout.value)
    }

    @Test
    fun `timed cable exercise blocks auto stop before warmup and allows it after warmup`() = runTest {
        val timedCableExercise = RoutineExercise(
            id = "timed-cable-1",
            exercise = Exercise(
                name = "Bench Press",
                muscleGroup = "Chest",
                equipment = "HANDLES"
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            duration = 60
        )
        viewModel.loadRoutine(
            Routine(
                id = "routine-timed-1",
                name = "Timed Cable Warmup Regression",
                exercises = listOf(timedCableExercise)
            )
        )
        advanceUntilIdle()

        val restingMetric = WorkoutMetric(
            positionA = 0f,
            positionB = 0f,
            velocityA = 0.0,
            velocityB = 0.0,
            loadA = 0f,
            loadB = 0f
        )
        fakeBleRepository.emitMetric(restingMetric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertEquals(WorkoutState.Active, viewModel.workoutState.value)
        assertFalse(viewModel.repCount.value.isWarmupComplete)

        // Reproduce "expired timer while warmup incomplete" path deterministically.
        // If warmup gate regresses, this immediately auto-completes the set.
        forceAutoStopTimerElapsed()
        fakeBleRepository.emitMetric(restingMetric)
        runCurrent()

        assertEquals(WorkoutState.Active, viewModel.workoutState.value)
        assertEquals(0, fakeWorkoutRepository.getRecentSessionsSync(10).size)

        // Complete warmup and verify auto-stop can now trigger for timed cable.
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f
        )
        for (warmupRep in 1..3) {
            fakeBleRepository.emitMetric(activeMetric)
            fakeBleRepository.emitRepNotification(
                RepNotification(
                    topCounter = warmupRep,
                    completeCounter = warmupRep,
                    repsRomCount = warmupRep,
                    repsRomTotal = 3,
                    repsSetCount = 0,
                    repsSetTotal = 10,
                    rangeTop = 800f,
                    rangeBottom = 0f,
                    rawData = ByteArray(24),
                    timestamp = warmupRep.toLong()
                )
            )
            runCurrent()
        }

        assertTrue(viewModel.repCount.value.isWarmupComplete)
        assertEquals(WorkoutState.Active, viewModel.workoutState.value)

        // Once warmup is complete, the same expired timer should allow auto-stop.
        forceAutoStopTimerElapsed()
        fakeBleRepository.emitMetric(restingMetric)
        runCurrent()
        runCurrent()

        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync(10).size)
        assertIs<WorkoutState.SetSummary>(viewModel.workoutState.value)
        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync(10).size)
    }

    private fun forceAutoStopTimerElapsed() {
        val coordinator = viewModel.workoutSessionManager.coordinator
        val field = coordinator::class.java.getDeclaredField("autoStopStartTime")
        field.isAccessible = true
        field.set(coordinator, System.currentTimeMillis() - 10_000L)
    }

    private suspend fun emitRepNotification(repIndex: Int, metric: WorkoutMetric, warmupCount: Int = 0, warmupTarget: Int = 3, workingTarget: Int = 10) {
        // Issue #210: Include machine's warmup/working targets for sync verification
        // For working reps: repsRomCount stays at warmupCount, completeCounter (down) increments
        fakeBleRepository.emitMetric(metric)
        fakeBleRepository.emitRepNotification(
            com.devil.phoenixproject.data.repository.RepNotification(
                topCounter = repIndex + warmupCount,
                completeCounter = repIndex + warmupCount,  // down counter
                repsRomCount = warmupCount,  // warmup count from machine
                repsRomTotal = warmupTarget, // Issue #210: Machine's warmup target
                repsSetCount = repIndex,     // working reps from machine
                repsSetTotal = workingTarget, // Issue #210: Machine's working target
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = repIndex.toLong()
            )
        )
        fakeBleRepository.emitMetric(metric)
    }
}

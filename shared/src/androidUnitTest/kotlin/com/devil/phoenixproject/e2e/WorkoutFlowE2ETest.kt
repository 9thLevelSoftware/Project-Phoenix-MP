package com.devil.phoenixproject.e2e

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.e2e.robot.WorkoutRobot
import com.devil.phoenixproject.e2e.robot.workoutRobot
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.domain.detection.ExerciseClassifier
import com.devil.phoenixproject.domain.detection.ExerciseSignature
import com.devil.phoenixproject.domain.detection.SignatureExtractor
import com.devil.phoenixproject.data.repository.ExerciseSignatureRepository
import com.devil.phoenixproject.presentation.manager.ExerciseDetectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * End-to-End tests for complete workout flows.
 * Uses Robot pattern to make tests readable and maintainable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutFlowE2ETest {

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
    private lateinit var fakeRepMetricRepository: FakeRepMetricRepository
    private lateinit var repCounter: RepCounterFromMachine
    private lateinit var resolveWeightsUseCase: ResolveRoutineWeightsUseCase
    private lateinit var robot: WorkoutRobot

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
        fakeRepMetricRepository = FakeRepMetricRepository()
        repCounter = RepCounterFromMachine()
        resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePersonalRecordRepository)

        val fakeSignatureRepo = object : ExerciseSignatureRepository {
            override suspend fun getSignaturesByExercise(exerciseId: String): List<ExerciseSignature> = emptyList()
            override suspend fun getAllSignaturesAsMap(): Map<String, ExerciseSignature> = emptyMap()
            override suspend fun saveSignature(exerciseId: String, signature: ExerciseSignature) {}
            override suspend fun updateSignature(id: Long, signature: ExerciseSignature) {}
            override suspend fun deleteSignaturesByExercise(exerciseId: String) {}
        }
        val detectionManager = ExerciseDetectionManager(
            signatureExtractor = SignatureExtractor(),
            exerciseClassifier = ExerciseClassifier(),
            signatureRepository = fakeSignatureRepo,
            exerciseRepository = fakeExerciseRepository
        )

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
            repMetricRepository = fakeRepMetricRepository,
            resolveWeightsUseCase = resolveWeightsUseCase,
            detectionManager = detectionManager
        )

        robot = WorkoutRobot(viewModel, fakeBleRepository)
    }

    // ========== Connection Flow Tests ==========

    @Test
    fun `complete connection flow - scan, connect, verify`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            // Initially disconnected
            verifyDisconnected()

            // Connect to device
            connectToDevice("Vee_MyTrainer")
            advanceUntilIdle()

            // Verify connection
            verifyConnected()
            verifyConnectedTo("Vee_MyTrainer")
        }
    }

    @Test
    fun `connection error flow - handle gracefully`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            verifyDisconnected()

            // Simulate connection error
            simulateConnectionError("Bluetooth unavailable")
            advanceUntilIdle()

            // Verify error state
            verifyConnectionError()
        }
    }

    @Test
    fun `disconnect and reconnect flow`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            // Connect
            connectToDevice("Vee_Device1")
            advanceUntilIdle()
            verifyConnectedTo("Vee_Device1")

            // Disconnect
            disconnect()
            advanceUntilIdle()
            verifyDisconnected()

            // Reconnect to different device
            connectToDevice("Vee_Device2")
            advanceUntilIdle()
            verifyConnectedTo("Vee_Device2")
        }
    }

    // ========== Workout Configuration Flow Tests ==========

    @Test
    fun `configure standard workout - OldSchool mode`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 25f,
                reps = 12,
                mode = ProgramMode.OldSchool,
                warmupReps = 3
            )

            verifyWeight(25f)
            verifyReps(12)
            verifyMode(ProgramMode.OldSchool)
            verifyWarmupReps(3)
            verifyTotalReps(15) // 12 + 3 warmup
        }
    }

    @Test
    fun `configure Pump mode workout`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 15f,
                reps = 20,
                mode = ProgramMode.Pump,
                warmupReps = 5
            )

            verifyWeight(15f)
            verifyReps(20)
            verifyMode(ProgramMode.Pump)
        }
    }

    @Test
    fun `configure TUT mode workout`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 30f,
                reps = 8,
                mode = ProgramMode.TUT
            )

            verifyWeight(30f)
            verifyReps(8)
            verifyMode(ProgramMode.TUT)
        }
    }

    @Test
    fun `configure Just Lift mode`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 20f,
                reps = 10,
                mode = ProgramMode.OldSchool,
                isJustLift = true
            )

            verifyJustLiftEnabled()
        }
    }

    @Test
    fun `modify workout parameters incrementally`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            // Start with defaults
            configureWorkout(weight = 20f, reps = 10)

            // Modify weight
            setWeight(25f)
            verifyWeight(25f)
            verifyReps(10) // Reps unchanged

            // Modify reps
            setReps(15)
            verifyReps(15)
            verifyWeight(25f) // Weight unchanged

            // Change mode
            setMode(ProgramMode.Pump)
            verifyMode(ProgramMode.Pump)
            verifyWeight(25f)
            verifyReps(15)
        }
    }

    // ========== Complete Workout Flow Tests ==========

    @Test
    fun `complete workout session flow`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            // Setup
            connectToDevice("Vee_Trainer")
            advanceUntilIdle()
            verifyConnected()

            // Configure workout
            configureWorkout(
                weight = 22.5f,
                reps = 10,
                mode = ProgramMode.OldSchool,
                warmupReps = 3
            )

            // Verify configuration
            verifyWeight(22.5f)
            verifyTotalReps(13)

            // Workout ends, still connected
            verifyConnected()
        }
    }

    @Test
    fun `start workout sends commands and sets active state`() = runTest {
        val baselineMetric = com.devil.phoenixproject.domain.model.WorkoutMetric(
            positionA = 100f,
            positionB = 100f,
            loadA = 10f,
            loadB = 10f
        )

        val localRobot = WorkoutRobot(viewModel, fakeBleRepository)
        localRobot.connectToDevice()
        advanceUntilIdle()
        localRobot.configureWorkout(weight = 20f, reps = 2, warmupReps = 0)

        fakeBleRepository.emitMetric(baselineMetric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        localRobot.verifyWorkoutActive()
        // Issue #222: INIT command removed - now only CONFIG (0x04) + START (0x03)
        kotlin.test.assertEquals(2, fakeBleRepository.commandsReceived.size)
    }

    @Test
    fun `rep notifications update counts and stop workout`() = runTest {
        val metric = com.devil.phoenixproject.domain.model.WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            loadA = 10f,
            loadB = 10f
        )

        val localRobot = WorkoutRobot(viewModel, fakeBleRepository)
        localRobot.connectToDevice()
        advanceUntilIdle()
        // Issue #222: Cable exercises force warmupReps=3 (DEFAULT_WARMUP_REPS) regardless of input
        // The app enforces this to prevent issues with missing warmup reps
        localRobot.configureWorkout(weight = 20f, reps = 2, warmupReps = 3)

        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        // Issue #210/#222: Pass correct warmup/working targets to match actual app behavior
        // The app forces warmupReps=3 for cable exercises, and when machine reports working reps,
        // it infers that warmup is complete and sets warmupReps to the configured target (3)
        localRobot.simulateRepNotification(1, metric, warmupCount = 3, warmupTarget = 3, workingTarget = 2)
        localRobot.simulateRepNotification(2, metric, warmupCount = 3, warmupTarget = 3, workingTarget = 2)
        advanceUntilIdle()

        localRobot.verifyWorkoutSummary()
        localRobot.verifyRepCount(expectedWorking = 2, expectedWarmup = 3)
        kotlin.test.assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync(5).size)
    }

    @Test
    fun `switch between workout modes`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            // Start with OldSchool
            configureWorkout(weight = 20f, reps = 10, mode = ProgramMode.OldSchool)
            verifyMode(ProgramMode.OldSchool)

            // Switch to Pump
            setMode(ProgramMode.Pump)
            verifyMode(ProgramMode.Pump)

            // Switch to TUT
            setMode(ProgramMode.TUT)
            verifyMode(ProgramMode.TUT)

            // Switch to TUTBeast
            setMode(ProgramMode.TUTBeast)
            verifyMode(ProgramMode.TUTBeast)

            // Switch to EccentricOnly
            setMode(ProgramMode.EccentricOnly)
            verifyMode(ProgramMode.EccentricOnly)
        }
    }

    // ========== Edge Case Tests ==========

    @Test
    fun `workout configuration with minimum values`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 0f,
                reps = 1,
                warmupReps = 0
            )

            verifyWeight(0f)
            verifyReps(1)
            verifyWarmupReps(0)
        }
    }

    @Test
    fun `workout configuration with maximum values`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            connectToDevice()
            advanceUntilIdle()

            configureWorkout(
                weight = 220f, // Max weight
                reps = 100,
                warmupReps = 10
            )

            verifyWeight(220f)
            verifyReps(100)
            verifyWarmupReps(10)
        }
    }

    @Test
    fun `connection lost during idle state`() = runTest {
        workoutRobot(viewModel, fakeBleRepository) {
            // Connect and configure
            connectToDevice()
            advanceUntilIdle()
            configureWorkout(weight = 20f, reps = 10)

            // Connection lost
            disconnect()
            advanceUntilIdle()

            // Verify disconnected state
            verifyDisconnected()
            verifyWorkoutIdle()
        }
    }
}

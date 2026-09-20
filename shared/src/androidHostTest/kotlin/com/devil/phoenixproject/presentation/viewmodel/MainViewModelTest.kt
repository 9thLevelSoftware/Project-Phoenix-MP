package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.devil.phoenixproject.data.repository.ProfileEquipmentRackRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.CountVelocityOneRepMaxImprovementsUseCase
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyPhysicalRelease
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.MachineSafetyUiState
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.testutil.FakeActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.testutil.FakeBiomechanicsRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeDataBackupManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeRepMetricRepository
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var viewModel: MainViewModel
    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var fakeWorkoutRepository: FakeWorkoutRepository
    private lateinit var fakeExerciseRepository: FakeExerciseRepository
    private lateinit var fakePersonalRecordRepository: FakePersonalRecordRepository
    private lateinit var fakeBaselineRepository: FakeProfileExerciseBaselineRepository
    private lateinit var fakePreferencesManager: FakePreferencesManager
    private lateinit var fakeGamificationRepository: FakeGamificationRepository
    private lateinit var fakeTrainingCycleRepository: FakeTrainingCycleRepository
    private lateinit var fakeCompletedSetRepository: FakeCompletedSetRepository
    private lateinit var fakeRepMetricRepository: FakeRepMetricRepository
    private lateinit var repCounter: RepCounterFromMachine
    private lateinit var resolveWeightsUseCase: ResolveRoutineWeightsUseCase
    private lateinit var fakeUserProfileRepository: FakeUserProfileRepository
    private lateinit var profileEquipmentRackRepository: ProfileEquipmentRackRepository
    private lateinit var safetyStore: com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository

    @Before
    fun setup() {
        fakeBleRepository = FakeBleRepository()
        fakeWorkoutRepository = FakeWorkoutRepository()
        fakeExerciseRepository = FakeExerciseRepository()
        fakePersonalRecordRepository = FakePersonalRecordRepository()
        fakeBaselineRepository = FakeProfileExerciseBaselineRepository()
        fakePreferencesManager = FakePreferencesManager()
        fakeGamificationRepository = FakeGamificationRepository()
        fakeTrainingCycleRepository = FakeTrainingCycleRepository()
        fakeCompletedSetRepository = FakeCompletedSetRepository()
        fakeRepMetricRepository = FakeRepMetricRepository()
        repCounter = RepCounterFromMachine()
        resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePersonalRecordRepository, fakeBaselineRepository, FakeVelocityOneRepMaxRepository())
        fakeUserProfileRepository = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        profileEquipmentRackRepository = ProfileEquipmentRackRepository(
            fakeUserProfileRepository,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
        )
        safetyStore = com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository()

        viewModel = MainViewModel(
            bleRepository = fakeBleRepository,
            workoutRepository = fakeWorkoutRepository,
            exerciseRepository = fakeExerciseRepository,
            personalRecordRepository = fakePersonalRecordRepository,
            profileExerciseBaselineRepository = fakeBaselineRepository,
            repCounter = repCounter,
            preferencesManager = fakePreferencesManager,
            gamificationRepository = fakeGamificationRepository,
            trainingCycleRepository = fakeTrainingCycleRepository,
            completedSetRepository = fakeCompletedSetRepository,
            activeWorkoutRuntimeRepository = FakeActiveWorkoutRuntimeRepository(),
            dropSetEligibilityPolicy = DropSetEligibilityPolicy(DropSetFeatureGate { false }, DropSetCandidateResolver()),
            repMetricRepository = fakeRepMetricRepository,
            biomechanicsRepository = FakeBiomechanicsRepository(),
            resolveWeightsUseCase = resolveWeightsUseCase,
            recommendWeightAdjustmentUseCase = RecommendWeightAdjustmentUseCase(),
            equipmentRackRepository = profileEquipmentRackRepository,
            applyEquipmentRackLoadUseCase = ApplyEquipmentRackLoadUseCase(),
            dataBackupManager = FakeDataBackupManager(),
            userProfileRepository = fakeUserProfileRepository,
            workoutServiceController = NoOpWorkoutServiceController,
            computeVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase(
                workoutPoints = { _, _, _ -> emptyList() },
                exerciseLookup = { null },
                personalMvtLookup = { _, _ -> null },
                mvtProvider = com.devil.phoenixproject.domain.onerepmax.MvtProvider(),
                estimator = com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator(
                    com.devil.phoenixproject.domain.assessment.AssessmentEngine(),
                ),
                persist = { _, _, _, _ -> },
            ),
            recordPersonalMvtSampleUseCase = com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase(
                object : com.devil.phoenixproject.data.repository.PersonalMvtRepository {
                    override suspend fun get(exerciseId: String, profileId: String) = null
                    override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) {}
                },
            ),
            velocityOneRepMaxRepository = object : com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository {
                override suspend fun insert(result: com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult, exerciseId: String, computedAt: Long, profileId: String) {}
                override suspend fun getLatestPassing(exerciseId: String, profileId: String): com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity? = null
                override suspend fun getAllPassing(profileId: String): List<com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity> = emptyList()
                override fun getHistory(exerciseId: String, profileId: String): kotlinx.coroutines.flow.Flow<List<com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
                override suspend fun hasEstimates(exerciseId: String, profileId: String): Boolean = false
            },
            countVelocityOneRepMaxImprovementsUseCase = CountVelocityOneRepMaxImprovementsUseCase(),
            backfillVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.BackfillVelocityOneRepMaxUseCase(
                exerciseIds = { emptyList() },
                hasEstimates = { _, _ -> false },
                computeAllTime = { _, _, _ -> null },
            ),
            machineSafetyCoordinator = MachineSafetyCoordinator(
                repository = safetyStore,
                transport = FakeBleMachineSafetyTransport(fakeBleRepository),
                scope = kotlinx.coroutines.CoroutineScope(testCoroutineRule.dispatcher),
                nowEpochMs = { testCoroutineRule.dispatcher.scheduler.currentTime },
            ),
        )
        val deterministicElapsedRealtime: () -> Long = { testCoroutineRule.dispatcher.scheduler.currentTime }
        viewModel.workoutSessionManager.activeSessionEngine.javaClass
            .getDeclaredField("elapsedRealtimeProvider")
            .apply { isAccessible = true }
            .set(viewModel.workoutSessionManager.activeSessionEngine, deterministicElapsedRealtime)
        viewModel.workoutSessionManager.activeSessionEngine.javaClass
            .getDeclaredField("wallClockMillisProvider")
            .apply { isAccessible = true }
            .set(viewModel.workoutSessionManager.activeSessionEngine, deterministicElapsedRealtime)
    }

    @After
    fun tearDown() {
        // Cancel viewModelScope directly. In lifecycle 2.5+, viewModelScope is cancelled
        // in clear() — not in onCleared(). Calling onCleared() via reflection skips that
        // cancellation, leaving all ViewModel coroutines alive and polluting the test
        // dispatcher's scheduler for subsequent tests (UncaughtExceptionsBeforeTest).
        viewModel.viewModelScope.cancel()
        profileEquipmentRackRepository.close()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state is disconnected and idle`() = runTest(testCoroutineRule.dispatcher) {
        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)
        assertNull(viewModel.currentMetric.value)
        assertEquals(0, viewModel.repCount.value.warmupReps)
        assertEquals(0, viewModel.repCount.value.workingReps)
    }

    @Test
    fun `initial workout parameters are default values`() = runTest(testCoroutineRule.dispatcher) {
        val params = viewModel.workoutParameters.value
        assertEquals(ProgramMode.OldSchool, params.programMode)
        assertEquals(10, params.reps)
        assertEquals(10f, params.weightPerCableKg)
        assertFalse(params.isJustLift)
        assertEquals(3, params.warmupReps)
    }

    @Test
    fun `unexpected idle disconnect does not create a machine safety hazard`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Idle", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        fakeBleRepository.simulateDisconnect()
        fakeBleRepository.emitReconnectionRequest(
            ReconnectionRequest("Vee_Idle", "AA:BB:CC:DD:EE:FF", "idle loss", 1L),
        )
        advanceUntilIdle()

        assertIs<MachineSafetyUiState.Hidden>(viewModel.machineSafetyUiState.value)
        assertTrue(safetyStore.loadAll().isEmpty())
    }

    @Test
    fun `production workout graph preserves durable safety barrier after dismissal`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        fakeBleRepository.emitMetric(WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f))
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(1, fakeBleRepository.commandsReceived.size)

        // Lose the link while this execution is still armed. A successful terminal
        // RESET would resolve the exact arm and make a later idle disconnect harmless.
        fakeBleRepository.simulateDisconnect()
        fakeBleRepository.emitReconnectionRequest(
            ReconnectionRequest("Vee_Test", "AA:BB:CC:DD:EE:FF", "test loss", 1L),
        )
        advanceUntilIdle()
        assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.stopWorkout(exitingWorkout = true)
        advanceUntilIdle()
        viewModel.dismissMachineSafetyWarning()
        advanceUntilIdle()

        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
        assertIs<MachineSafetyUiState.Hidden>(viewModel.machineSafetyUiState.value)

        fakeBleRepository.emitMetric(WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f))
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(1, fakeBleRepository.commandsReceived.size)
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)
    }

    @Test
    fun `fresh production graph restores loss barrier and does not resume routine`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        fakeBleRepository.emitMetric(WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f))
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        fakeBleRepository.simulateDisconnect()
        fakeBleRepository.emitReconnectionRequest(
            ReconnectionRequest("Vee_Test", "AA:BB:CC:DD:EE:FF", "unexpected disconnect", 1L),
        )
        advanceUntilIdle()
        val firstState = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.viewModelScope.cancel()

        val secondBle = FakeBleRepository()
        val second = newSafetyGraph(secondBle)
        advanceUntilIdle()
        assertIs<MachineSafetyUiState.Visible>(second.machineSafetyUiState.value)
        second.dismissMachineSafetyWarning()
        advanceUntilIdle()
        second.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertTrue(secondBle.commandsReceived.isEmpty())
        assertIs<MachineSafetyUiState.Hidden>(second.machineSafetyUiState.value)
        assertEquals(
            firstState.document.generation,
            safetyStore.loadAll().filterIsInstance<com.devil.phoenixproject.data.repository.MachineSafetyLoadResult.Loaded>()
                .maxBy { it.document.generation }.document.generation,
        )
        second.viewModelScope.cancel()
    }

    @Test
    fun `production graph recovery rejects wrong trainer and failed reset without clearing durable hazard`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Wrong", "11:22:33:44:55:66")
        advanceUntilIdle()
        assertTrue(
            recordTestConnectionLost(
                viewModel.machineSafetyCoordinator,
                trainerAddress = "AA:BB:CC:DD:EE:FF",
                trainerName = "Vee_Target",
            ),
        )

        val visible = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.requestMachineSafetyRecovery(visible.identity)
        advanceUntilIdle()
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_FAILED, (viewModel.machineSafetyUiState.value as MachineSafetyUiState.Visible).document.phase)
        assertEquals(0, fakeBleRepository.stopWorkoutCallCount)
        assertEquals(visible.document.generation, (safetyStore.loadAll().first() as com.devil.phoenixproject.data.repository.MachineSafetyLoadResult.Loaded).document.generation)

        fakeBleRepository.simulateConnect("Vee_Target", "AA:BB:CC:DD:EE:FF")
        fakeBleRepository.stopWorkoutBlock = { Result.failure(IllegalStateException("write failed")) }
        viewModel.requestMachineSafetyRecovery(visible.identity)
        advanceUntilIdle()
        val failedWrite = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_FAILED, failedWrite.document.phase)
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount)
        assertTrue(safetyStore.loadAll().isNotEmpty())
    }

    @Test
    fun `production graph duplicate recovery request and cancellation never authorize a new machine start`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Target", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        assertTrue(
            recordTestConnectionLost(
                viewModel.machineSafetyCoordinator,
                trainerAddress = "AA:BB:CC:DD:EE:FF",
                trainerName = "Vee_Target",
            ),
        )
        val pending = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        fakeBleRepository.stopWorkoutBlock = { pending.await() }

        val visible = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.requestMachineSafetyRecovery(visible.identity)
        viewModel.requestMachineSafetyRecovery(visible.identity)
        runCurrent()
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount)
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
        viewModel.dismissMachineSafetyWarning()
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
        pending.cancel()
        advanceUntilIdle()
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
    }

    @Test
    fun `production graph matching trainer reset records transport ack but keeps durable hazard`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Target", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        assertTrue(
            recordTestConnectionLost(
                viewModel.machineSafetyCoordinator,
                trainerAddress = "AA:BB:CC:DD:EE:FF",
                trainerName = "Vee_Target",
            ),
        )

        val visible = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.requestMachineSafetyRecovery(visible.identity)
        advanceUntilIdle()

        val recovered = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_SENT, recovered.document.phase)
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount)
        assertTrue(safetyStore.loadAll().isNotEmpty(), "transport ACK is not physical acknowledgement")
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
    }

    @Test
    fun `hidden safety recovery button resurfaces the durable hazard and sends reset`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Target", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        assertTrue(
            recordTestConnectionLost(
                viewModel.machineSafetyCoordinator,
                trainerAddress = "AA:BB:CC:DD:EE:FF",
                trainerName = "Vee_Target",
            ),
        )
        viewModel.dismissMachineSafetyWarning()
        advanceUntilIdle()
        assertEquals(MachineSafetyUiState.Hidden, viewModel.machineSafetyUiState.value)

        viewModel.requestMachineSafetyRecovery(identity = null)
        advanceUntilIdle()

        val recovered = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        assertEquals(MachineSafetyPhase.RELEASE_REQUEST_SENT, recovered.document.phase)
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount)
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
    }

    @Test
    fun `recovery button explains when no durable safety recovery exists`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.userFeedbackEvents.test {
            viewModel.requestMachineSafetyRecovery(identity = null)
            advanceUntilIdle()

            assertTrue(awaitItem().contains("No stored machine recovery"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `production graph disconnect during reset write leaves hazard and ignores stale completion`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Target", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        assertTrue(
            recordTestConnectionLost(
                viewModel.machineSafetyCoordinator,
                trainerAddress = "AA:BB:CC:DD:EE:FF",
                trainerName = "Vee_Target",
            ),
        )

        val write = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        fakeBleRepository.stopWorkoutBlock = {
            fakeBleRepository.simulateDisconnect()
            write.await()
        }
        val visible = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        viewModel.requestMachineSafetyRecovery(visible.identity)
        runCurrent()
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount)

        write.complete(Result.success(Unit))
        advanceUntilIdle()

        val state = assertIs<MachineSafetyUiState.Visible>(viewModel.machineSafetyUiState.value)
        assertTrue(state.document.phase != MachineSafetyPhase.RELEASE_REQUEST_SENT)
        assertTrue(safetyStore.loadAll().isNotEmpty())
        assertFalse(viewModel.machineSafetyCoordinator.canStartMachine())
    }

    @Test
    fun `production graph preserves bodyweight control without safety arm or absent link write`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Bodyweight", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        viewModel.loadRoutine(
            Routine(
                id = "routine-production-bodyweight",
                name = "Production Bodyweight",
                exercises = listOf(
                    RoutineExercise(
                        id = "routine-production-bodyweight-exercise",
                        exercise = Exercise(
                            id = "production-pullup",
                            name = "Pull-Up",
                            muscleGroup = "Back",
                            equipment = "",
                            isBodyweightOverride = true,
                        ),
                        orderIndex = 0,
                        setReps = listOf(5),
                        weightPerCableKg = 0f,
                        warmupSets = emptyList(),
                    ),
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.enterSetReady(0, 0)
        advanceUntilIdle()
        fakeBleRepository.emitMetric(WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 0f, loadB = 0f))
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertIs<WorkoutState.BodyweightRepEntry>(viewModel.workoutState.value)
        assertTrue(fakeBleRepository.commandsReceived.none { it.isEmpty() })
        assertTrue(safetyStore.loadAll().isEmpty(), "bodyweight control must not persist a machine safety obligation: ${safetyStore.loadAll()}")
    }

    // ========== Connection State Tests ==========

    @Test
    fun `connectionState reflects BLE repository state`() = runTest(testCoroutineRule.dispatcher) {
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
    fun `connectionState reflects error state`() = runTest(testCoroutineRule.dispatcher) {
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
    fun `updateWorkoutParameters updates state`() = runTest(testCoroutineRule.dispatcher) {
        val newParams = WorkoutParameters(
            programMode = ProgramMode.Pump,
            reps = 15,
            weightPerCableKg = 25f,
            progressionRegressionKg = 2.5f,
            isJustLift = false,
            stopAtTop = true,
            warmupReps = 5,
        )

        viewModel.updateWorkoutParameters(newParams)

        assertEquals(15, viewModel.workoutParameters.value.reps)
        assertEquals(25f, viewModel.workoutParameters.value.weightPerCableKg)
        assertEquals(2.5f, viewModel.workoutParameters.value.progressionRegressionKg)
        assertTrue(viewModel.workoutParameters.value.stopAtTop)
        assertEquals(5, viewModel.workoutParameters.value.warmupReps)
    }

    @Test
    fun `updateWorkoutParameters rejects near-zero weight in Just Lift mode`() = runTest(testCoroutineRule.dispatcher) {
        // Set up Just Lift state with known weight
        val initialParams = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isJustLift = true,
        )
        viewModel.updateWorkoutParameters(initialParams)
        assertEquals(
            30f,
            viewModel.workoutParameters.value.weightPerCableKg,
            "Precondition: weight should be 30f after initial set",
        )

        // Attempt to write near-zero weight (simulates the Compose race condition
        // where param-sync LaunchedEffect fires with the hardcoded initial 0.453592f)
        viewModel.updateWorkoutParameters(initialParams.copy(weightPerCableKg = 0.453592f))

        // The coordinator should still have 30f, not 0.453592f
        assertEquals(
            30f,
            viewModel.workoutParameters.value.weightPerCableKg,
            "Near-zero weight should be rejected when isJustLift is true",
        )
    }

    @Test
    fun `updateWorkoutParameters accepts valid weight in Just Lift mode`() = runTest(testCoroutineRule.dispatcher) {
        val initialParams = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isJustLift = true,
        )
        viewModel.updateWorkoutParameters(initialParams)

        viewModel.updateWorkoutParameters(initialParams.copy(weightPerCableKg = 35f))

        assertEquals(
            35f,
            viewModel.workoutParameters.value.weightPerCableKg,
            "Valid weight should be accepted in Just Lift mode",
        )
    }

    @Test
    fun `updateWorkoutParameters allows near-zero weight in non-Just-Lift mode`() = runTest(testCoroutineRule.dispatcher) {
        // Non-Just-Lift mode should still allow low weights (e.g. for warm-up configs)
        val initialParams = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 30f,
            isJustLift = false,
        )
        viewModel.updateWorkoutParameters(initialParams)

        viewModel.updateWorkoutParameters(initialParams.copy(weightPerCableKg = 0.453592f))

        assertEquals(
            0.453592f,
            viewModel.workoutParameters.value.weightPerCableKg,
            "Near-zero weight should be allowed in non-Just-Lift mode",
        )
    }

    // ========== Disconnect Tests ==========

    @Test
    fun `disconnect calls BLE repository disconnect`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
    }

    // ========== User Preferences Tests ==========

    @Test
    fun `userPreferences reflects preferences manager state`() = runTest(testCoroutineRule.dispatcher) {
        advanceUntilIdle()

        val preferences = viewModel.userPreferences.value
        assertNotNull(preferences)

        // Verify default values - default is LB per UserPreferences definition
        assertEquals(WeightUnit.LB, preferences.weightUnit)
    }

    @Test
    fun `userPreferences updates when preferences change`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.userPreferences.test {
            awaitItem() // Initial value

            val profileId = assertNotNull(fakeUserProfileRepository.activeProfile.value).id
            fakeUserProfileRepository.updateWorkout(
                profileId,
                WorkoutPreferences(stopAtTop = true),
            )

            var updated = awaitItem()
            while (!updated.stopAtTop) {
                updated = awaitItem()
            }
            assertEquals(WeightUnit.LB, updated.weightUnit)
            assertTrue(updated.stopAtTop)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `globalSettings ignores profile updates and emits global updates`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.globalSettings.test {
            val initial = awaitItem()
            assertEquals(initial, viewModel.globalSettings.value)

            val profileId = assertNotNull(fakeUserProfileRepository.activeProfile.value).id
            fakeUserProfileRepository.updateWorkout(
                profileId,
                WorkoutPreferences(stopAtTop = true),
            )
            advanceUntilIdle()

            expectNoEvents()
            assertEquals(initial, viewModel.globalSettings.value)

            val updatedVideoPlayback = !initial.enableVideoPlayback
            viewModel.setEnableVideoPlayback(updatedVideoPlayback)

            val updated = awaitItem()
            assertEquals(
                initial.copy(enableVideoPlayback = updatedVideoPlayback),
                updated,
            )
            assertEquals(updated, viewModel.globalSettings.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Top Bar State Tests ==========

    @Test
    fun `updateTopBarTitle updates title`() = runTest(testCoroutineRule.dispatcher) {
        assertEquals("Project Phoenix", viewModel.topBarTitle.value)

        viewModel.updateTopBarTitle("Active Workout")

        assertEquals("Active Workout", viewModel.topBarTitle.value)
    }

    @Test
    fun `clearTopBarActions clears actions`() = runTest(testCoroutineRule.dispatcher) {
        // Set some actions first using reflection or public method if available
        viewModel.clearTopBarActions()

        assertTrue(viewModel.topBarActions.value.isEmpty())
    }

    @Test
    fun `setTopBarBackAction sets back action`() = runTest(testCoroutineRule.dispatcher) {
        assertNull(viewModel.topBarBackAction.value)

        var backPressed = false
        viewModel.setTopBarBackAction { backPressed = true }

        assertNotNull(viewModel.topBarBackAction.value)
        viewModel.topBarBackAction.value?.invoke()
        assertTrue(backPressed)
    }

    @Test
    fun `clearTopBarBackAction clears back action`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.setTopBarBackAction { }

        viewModel.clearTopBarBackAction()

        assertNull(viewModel.topBarBackAction.value)
    }

    // ========== Routines Tests ==========

    @Test
    fun `routines reflects workout repository state`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.routines.test {
            // Initial empty list
            assertEquals(emptyList(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRoutineById returns routine when exists`() = runTest(testCoroutineRule.dispatcher) {
        val routine = Routine(
            id = "routine-1",
            name = "Push Day",
            exercises = emptyList(),
        )
        fakeWorkoutRepository.addRoutine(routine)
        advanceUntilIdle()

        val found = viewModel.getRoutineById("routine-1")

        assertNotNull(found)
        assertEquals("Push Day", found.name)
    }

    @Test
    fun `getRoutineById returns null when not found`() = runTest(testCoroutineRule.dispatcher) {
        val found = viewModel.getRoutineById("non-existent")

        assertNull(found)
    }

    @Test
    fun `saveRackBehaviorOverridesForExercise does not persist launch-time routine modifiers`() = runTest(testCoroutineRule.dispatcher) {
        val storedExercise = RoutineExercise(
            id = "routine-ex-1",
            exercise = Exercise(
                id = "bench",
                name = "Bench Press",
                muscleGroup = "Chest",
                equipment = "HANDLES",
            ),
            orderIndex = 0,
            setReps = listOf(8),
            weightPerCableKg = 40f,
        )
        val storedRoutine = Routine(
            id = "routine-modifier-safe-rack-save",
            name = "Modifier Safe Rack Save",
            exercises = listOf(storedExercise),
        )
        val launchRoutine = storedRoutine.copy(
            exercises = listOf(
                storedExercise.copy(
                    setReps = listOf(4),
                    weightPerCableKg = 20f,
                ),
            ),
        )
        fakeWorkoutRepository.addRoutine(storedRoutine)
        viewModel.loadRoutine(launchRoutine)
        advanceUntilIdle()

        val overrides = mapOf("vest" to RackItemBehavior.COUNTERWEIGHT)
        viewModel.saveRackBehaviorOverridesForExercise(0, overrides)
        advanceUntilIdle()

        val persisted = fakeWorkoutRepository.getRoutineById(storedRoutine.id)!!
        assertEquals(40f, persisted.exercises.single().weightPerCableKg)
        assertEquals(listOf(8), persisted.exercises.single().setReps)
        assertEquals(overrides, persisted.exercises.single().rackBehaviorOverrides)

        val activeRoutine = viewModel.loadedRoutine.value!!
        assertEquals(20f, activeRoutine.exercises.single().weightPerCableKg)
        assertEquals(listOf(4), activeRoutine.exercises.single().setReps)
        assertEquals(overrides, activeRoutine.exercises.single().rackBehaviorOverrides)
    }

    @Test
    fun `rack catalog save intent prevents a stale ordinary config command`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test")
        viewModel.updateWorkoutParameters(
            viewModel.workoutParameters.value.copy(weightPerCableKg = 25f),
        )
        var saveAtClaim = true
        viewModel.workoutSessionManager.activeSessionEngine.beforeMachineConfigurationClaimForTest = {
            if (saveAtClaim) {
                saveAtClaim = false
                viewModel.saveRackItem(
                    RackItem(
                        id = "claim-boundary-rack-item",
                        name = "Claim boundary rack item",
                        category = RackItemCategory.OTHER,
                        weightKg = 5f,
                    ),
                )
            }
        }

        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertFalse(saveAtClaim)
        assertEquals(0, fakeBleRepository.commandsReceived.size)
        assertEquals(null, viewModel.workoutSessionManager.activeSessionEngine.currentExecutionLeaseOrNull())

        viewModel.workoutSessionManager.activeSessionEngine.beforeMachineConfigurationClaimForTest = null
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(1, fakeBleRepository.commandsReceived.size)
        assertIs<WorkoutState.Active>(viewModel.workoutState.value)
    }

    // ========== Workout History Tests ==========

    @Test
    fun `workoutHistory reflects repository sessions`() = runTest(testCoroutineRule.dispatcher) {
        viewModel.workoutHistory.test {
            assertEquals(emptyList(), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Connection Error Tests ==========

    @Test
    fun `connectionError is initially null`() = runTest(testCoroutineRule.dispatcher) {
        assertNull(viewModel.connectionError.value)
    }

    // ========== Auto-Start Tests ==========

    @Test
    fun `autoStartCountdown is initially null`() = runTest(testCoroutineRule.dispatcher) {
        assertNull(viewModel.autoStartCountdown.value)
    }

    // ========== Auto-Stop State Tests ==========

    @Test
    fun `autoStopState has default values`() = runTest(testCoroutineRule.dispatcher) {
        val state = viewModel.autoStopState.value
        assertFalse(state.isActive)
        assertEquals(0f, state.progress)
    }

    // ========== Scanned Devices Tests ==========

    @Test
    fun `scannedDevices is initially empty`() = runTest(testCoroutineRule.dispatcher) {
        // ViewModel maintains its own scannedDevices state (initialized empty)
        assertEquals(emptyList<ScannedDevice>(), viewModel.scannedDevices.value)
    }

    @Test
    fun `startWorkout sends commands and moves to active`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 2,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )

        fakeBleRepository.emitMetric(
            WorkoutMetric(
                positionA = 100f,
                positionB = 100f,
                loadA = 10f,
                loadB = 10f,
            ),
        )

        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertEquals(WorkoutState.Active, viewModel.workoutState.value)
        // Activation sends CONFIG (0x04) only; the legacy START (0x03) command is not sent.
        assertEquals(1, fakeBleRepository.commandsReceived.size)
        assertEquals(0x04.toByte(), fakeBleRepository.commandsReceived[0][0])
        assertFalse(fakeBleRepository.commandsReceived.any { it.firstOrNull() == 0x03.toByte() })
    }

    @Test
    fun `rep events update counts and stop workout at target`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 2,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )

        val metric = WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f)
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        emitRepNotification(repIndex = 1, metric = metric)
        emitRepNotification(repIndex = 2, metric = metric)
        advanceUntilIdle()

        assertIs<WorkoutState.SetSummary>(viewModel.workoutState.value)
        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync("default", 10).size)
        assertEquals(2, viewModel.repCount.value.workingReps)
    }

    @Test
    fun `disconnect during bodyweight-only workout does not show connection lost alert`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        val bodyweightRoutine = Routine(
            id = "routine-bodyweight-test",
            name = "Bodyweight Test",
            exercises = listOf(
                RoutineExercise(
                    id = "routine-ex-bw-1",
                    exercise = Exercise(
                        id = "pullup",
                        name = "Pull-Up",
                        muscleGroup = "Back",
                        equipment = "",
                        isBodyweightOverride = true,
                    ),
                    orderIndex = 0,
                    setReps = listOf(10),
                    weightPerCableKg = 0f,
                    warmupSets = emptyList(),
                ),
            ),
        )
        viewModel.loadRoutine(bodyweightRoutine)
        advanceUntilIdle()
        viewModel.enterSetReady(0, 0)
        advanceUntilIdle()
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        fakeBleRepository.simulateDisconnect()
        advanceUntilIdle()

        assertFalse(viewModel.connectionLostDuringWorkout.value)
    }

    @Test
    fun `disconnect during workout sets connection lost flag`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 1,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        fakeBleRepository.simulateDisconnect()
        advanceUntilIdle()

        assertTrue(viewModel.connectionLostDuringWorkout.value)
    }

    @Test
    fun `timed cable exercise blocks auto stop before warmup and allows it after warmup`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        val timedCableExercise = RoutineExercise(
            id = "timed-cable-1",
            exercise = Exercise(
                name = "Bench Press",
                muscleGroup = "Chest",
                equipment = "HANDLES",
            ),
            orderIndex = 0,
            setReps = listOf(10),
            weightPerCableKg = 20f,
            duration = 60,
        )
        viewModel.loadRoutine(
            Routine(
                id = "routine-timed-1",
                name = "Timed Cable Warmup Regression",
                exercises = listOf(timedCableExercise),
            ),
        )
        advanceUntilIdle()

        val restingMetric = WorkoutMetric(
            positionA = 0f,
            positionB = 0f,
            velocityA = 0.0,
            velocityB = 0.0,
            loadA = 0f,
            loadB = 0f,
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
        assertEquals(0, fakeWorkoutRepository.getRecentSessionsSync("default", 10).size)

        // Complete warmup and verify auto-stop can now trigger for timed cable.
        val activeMetric = WorkoutMetric(
            positionA = 120f,
            positionB = 120f,
            velocityA = 80.0,
            velocityB = 80.0,
            loadA = 10f,
            loadB = 10f,
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
                    repsSetTotal = activeWorkingRepTarget(),
                    rangeTop = 800f,
                    rangeBottom = 0f,
                    rawData = ByteArray(24),
                    timestamp = activeCutoverTimestamp() + warmupRep,
                ),
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

        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync("default", 10).size)
        assertIs<WorkoutState.SetSummary>(viewModel.workoutState.value)
        assertEquals(1, fakeWorkoutRepository.getRecentSessionsSync("default", 10).size)
    }

    // ========== Issue #627: isStoppingWorkout flag lifecycle ==========

    @Test
    fun `isStoppingWorkout is false before stop, true immediately on stopWorkout, persists after teardown, resets on next startWorkout`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        // Precondition: flag starts false
        assertFalse(viewModel.isStoppingWorkout(), "Flag must be false before any workout begins")

        // Start a workout and reach Active state
        val metric = WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f)
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(WorkoutState.Active, viewModel.workoutState.value)
        assertFalse(viewModel.isStoppingWorkout(), "Flag must be false while workout is active")

        // stopWorkout(exitingWorkout=true) sets the flag synchronously (compareAndSet before
        // scope.launch — ActiveSessionEngine:2908), before the async teardown coroutine starts.
        viewModel.stopWorkout(exitingWorkout = true)
        assertTrue(
            viewModel.isStoppingWorkout(),
            "Flag must be true immediately after stopWorkout() — before teardown coroutine runs",
        )
        // Let the teardown coroutine complete; workoutState becomes Idle.
        advanceUntilIdle()
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount, "RESET must run exactly once")
        assertEquals(MachineTeardownState.Ready, viewModel.machineTeardownState.value)
        assertIs<ConnectionState.Connected>(viewModel.connectionState.value)
        // The flag is NOT reset inside stopWorkout(). It persists until the next
        // startWorkout() call resets it (ActiveSessionEngine:2352). The navigation guard in
        // EnhancedMainScreen is fine with this: once workoutState is Idle,
        // shouldResumeActiveWorkout() returns false, so the observer condition is false
        // regardless of the flag.
        assertTrue(
            viewModel.isStoppingWorkout(),
            "Flag must still be true after teardown coroutine completes — reset only on next startWorkout",
        )

        // The next startWorkout() synchronously resets the flag (line 2352) before launching
        // its own coroutine. This matches the legitimate-resume path: a fresh set start always
        // clears the guard so normal navigation can proceed.
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        assertFalse(
            viewModel.isStoppingWorkout(),
            "Flag must be false immediately after startWorkout() resets it",
        )
    }

    @Test
    fun `isStoppingWorkout exitingWorkout=false — flag held and workoutState is SetSummary after teardown`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        // exitingWorkout=false is the Just Lift path: stopWorkout lands on SetSummary
        // (resumable), not Idle. The guard must stay load-bearing for the full window
        // between the pop and the next startWorkout() reset.
        val metric = WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f)
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(WorkoutState.Active, viewModel.workoutState.value)

        viewModel.stopWorkout(exitingWorkout = false)
        assertTrue(viewModel.isStoppingWorkout(), "Flag must be true immediately after stopWorkout(exitingWorkout=false)")

        // Let teardown coroutine complete: state lands on SetSummary, not Idle.
        advanceUntilIdle()
        assertEquals(1, fakeBleRepository.stopWorkoutCallCount, "RESET must run exactly once")
        assertEquals(MachineTeardownState.Ready, viewModel.machineTeardownState.value)
        assertIs<ConnectionState.Connected>(viewModel.connectionState.value)
        assertIs<WorkoutState.SetSummary>(viewModel.workoutState.value)
        // Flag is still true — SetSummary is resumable so shouldResumeActiveWorkout()
        // returns true, and the guard remains the only bounce suppressor in this window.
        assertTrue(
            viewModel.isStoppingWorkout(),
            "Flag must persist after teardown when workoutState is SetSummary (resumable) — guard stays load-bearing",
        )
    }

    @Test
    fun `resumeWorkout is refused while stop teardown in flight — refuse-guard contract`() = runTest(testCoroutineRule.dispatcher) {
        fakeBleRepository.simulateConnect("Vee_Test", "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()
        // Part (a): resume during an in-flight stop teardown is a NO-OP. Issue #687
        // synchronously publishes Idle at the End boundary while the CAS guard
        // (stopWorkoutInProgress) prevents a concurrent duplicate teardown.
        val metric = WorkoutMetric(positionA = 100f, positionB = 100f, loadA = 10f, loadB = 10f)
        viewModel.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = 10,
                warmupReps = 0,
                weightPerCableKg = 20f,
            ),
        )
        fakeBleRepository.emitMetric(metric)
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertEquals(WorkoutState.Active, viewModel.workoutState.value)

        // pauseWorkout() sets workoutState = Paused synchronously (BLE stop is async).
        viewModel.pauseWorkout()
        assertIs<WorkoutState.Paused>(viewModel.workoutState.value)

        // stopWorkout() arms the CAS guard synchronously; teardown coroutine is queued but not yet run.
        viewModel.stopWorkout(exitingWorkout = true)
        assertTrue(viewModel.isStoppingWorkout(), "Flag armed by stopWorkout() while paused")
        assertIs<WorkoutState.Idle>(viewModel.workoutState.value)

        // resumeWorkout() must be refused: state, guard, and command counts stay unchanged.
        val workoutCommandCount = fakeBleRepository.commandsReceived.size
        val resetCommandCount = fakeBleRepository.stopWorkoutCallCount
        viewModel.resumeWorkout()
        assertIs<WorkoutState.Idle>(
            viewModel.workoutState.value,
            "State must stay Idle — resume refused while End teardown is in flight",
        )
        assertEquals(workoutCommandCount, fakeBleRepository.commandsReceived.size)
        assertEquals(resetCommandCount, fakeBleRepository.stopWorkoutCallCount)
        assertTrue(
            viewModel.isStoppingWorkout(),
            "CAS guard must stay true — resumeWorkout() must not clear it while teardown is in flight (#627)",
        )

        // Part (b): after teardown completes, startWorkout() resets the guard and resume behaves normally.
        // exitingWorkout=true → state transitions to Idle; flag stays true until startWorkout() resets it.
        advanceUntilIdle()
        assertEquals(WorkoutState.Idle, viewModel.workoutState.value)

        // startWorkout() unconditionally resets stopWorkoutInProgress (line 2352).
        // The successful teardown RESET resolves this process's exact hidden arm, so
        // the next connected start can reopen the guard without an acknowledgement.
        viewModel.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        assertFalse(
            viewModel.isStoppingWorkout(),
            "startWorkout() must reset the stop guard — gate reopens for next session (#627)",
        )
        assertEquals(WorkoutState.Active, viewModel.workoutState.value)

        // pause then resume must now succeed normally.
        viewModel.pauseWorkout()
        assertIs<WorkoutState.Paused>(viewModel.workoutState.value)
        viewModel.resumeWorkout()
        assertEquals(WorkoutState.Active, viewModel.workoutState.value, "Resume must succeed after guard is reset by startWorkout()")
    }

    private fun forceAutoStopTimerElapsed() {
        val coordinator = viewModel.workoutSessionManager.coordinator
        val field = coordinator::class.java.getDeclaredField("autoStopStartTime")
        field.isAccessible = true
        field.set(coordinator, System.currentTimeMillis() - 10_000L)
    }

    private suspend fun emitRepNotification(
        repIndex: Int,
        metric: WorkoutMetric,
        warmupCount: Int = 0,
        warmupTarget: Int = 3,
        workingTarget: Int? = null,
    ) {
        // Issue #210: Include machine's warmup/working targets for sync verification
        // For working reps: repsRomCount stays at warmupCount, completeCounter (down) increments
        fakeBleRepository.emitMetric(metric)
        fakeBleRepository.emitRepNotification(
            com.devil.phoenixproject.data.repository.RepNotification(
                topCounter = repIndex + warmupCount,
                completeCounter = repIndex + warmupCount, // down counter
                repsRomCount = warmupCount, // warmup count from machine
                repsRomTotal = warmupTarget, // Issue #210: Machine's warmup target
                repsSetCount = repIndex, // working reps from machine
                repsSetTotal = workingTarget ?: activeWorkingRepTarget(), // Issue #210: Machine's working target
                rangeTop = 800f,
                rangeBottom = 0f,
                rawData = ByteArray(24),
                timestamp = activeCutoverTimestamp() + repIndex,
            ),
        )
        fakeBleRepository.emitMetric(metric)
    }

    private suspend fun recordTestConnectionLost(
        coordinator: MachineSafetyCoordinator,
        trainerAddress: String,
        trainerName: String? = null,
    ): Boolean {
        val now = testCoroutineRule.dispatcher.scheduler.currentTime
        return coordinator.recordMachineSessionArmed(
            MachineSafetyHazardDocument(
                generation = 1L,
                trainerAddress = trainerAddress,
                trainerName = trainerName,
                sessionId = "test-loss-$trainerAddress",
                workoutKind = MachineSafetyWorkoutKind.UNKNOWN,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                phase = MachineSafetyPhase.UNRESOLVED,
                physicalRelease = MachineSafetyPhysicalRelease.UNKNOWN,
            ),
            showRecoveryUi = true,
        )
    }

    private fun newSafetyGraph(ble: FakeBleRepository): MainViewModel = MainViewModel(
        bleRepository = ble,
        workoutRepository = fakeWorkoutRepository,
        exerciseRepository = fakeExerciseRepository,
        personalRecordRepository = fakePersonalRecordRepository,
        profileExerciseBaselineRepository = fakeBaselineRepository,
        repCounter = RepCounterFromMachine(),
        preferencesManager = fakePreferencesManager,
        gamificationRepository = fakeGamificationRepository,
        trainingCycleRepository = fakeTrainingCycleRepository,
        completedSetRepository = fakeCompletedSetRepository,
        activeWorkoutRuntimeRepository = FakeActiveWorkoutRuntimeRepository(),
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(DropSetFeatureGate { false }, DropSetCandidateResolver()),
        repMetricRepository = fakeRepMetricRepository,
        biomechanicsRepository = FakeBiomechanicsRepository(),
        resolveWeightsUseCase = resolveWeightsUseCase,
        recommendWeightAdjustmentUseCase = RecommendWeightAdjustmentUseCase(),
        equipmentRackRepository = profileEquipmentRackRepository,
        applyEquipmentRackLoadUseCase = ApplyEquipmentRackLoadUseCase(),
        dataBackupManager = FakeDataBackupManager(),
        userProfileRepository = fakeUserProfileRepository,
        workoutServiceController = NoOpWorkoutServiceController,
        computeVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> emptyList() }, exerciseLookup = { null }, personalMvtLookup = { _, _ -> null },
            mvtProvider = com.devil.phoenixproject.domain.onerepmax.MvtProvider(),
            estimator = com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator(com.devil.phoenixproject.domain.assessment.AssessmentEngine()),
            persist = { _, _, _, _ -> },
        ),
        recordPersonalMvtSampleUseCase = com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase(
            object : com.devil.phoenixproject.data.repository.PersonalMvtRepository {
                override suspend fun get(exerciseId: String, profileId: String) = null
                override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) {}
            },
        ),
        velocityOneRepMaxRepository = FakeVelocityOneRepMaxRepository(),
        countVelocityOneRepMaxImprovementsUseCase = CountVelocityOneRepMaxImprovementsUseCase(),
        backfillVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.BackfillVelocityOneRepMaxUseCase(
            exerciseIds = { emptyList() }, hasEstimates = { _, _ -> false }, computeAllTime = { _, _, _ -> null },
        ),
        machineSafetyCoordinator = MachineSafetyCoordinator(
            repository = safetyStore,
            transport = FakeBleMachineSafetyTransport(ble),
            scope = kotlinx.coroutines.CoroutineScope(testCoroutineRule.dispatcher),
            nowEpochMs = { testCoroutineRule.dispatcher.scheduler.currentTime },
        ),
    )

    private fun activeCutoverTimestamp(): Long = requireNotNull(
        viewModel.workoutSessionManager.activeSessionEngine
            .currentExecutionLeaseOrNull()
            ?.activationCutoverTimestampMs,
    ) { "MainViewModel rep fixture requires an active execution cutover" }

    private fun activeWorkingRepTarget(): Int = requireNotNull(viewModel.workoutSessionManager.activeSessionEngine.currentExecutionLeaseOrNull()) {
        "MainViewModel rep fixture requires a current execution lease"
    }.workingRepTarget
}

private class FakeBleMachineSafetyTransport(
    private val bleRepository: FakeBleRepository,
) : MachineSafetyTransport {
    override val connectedTrainerAddress: String?
        get() = (bleRepository.connectionState.value as? ConnectionState.Connected)?.deviceAddress

    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> =
        if (connectedTrainerAddress == trainerAddress) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("matching trainer unavailable"))
        }

    override suspend fun stopWorkout(): Result<Unit> = bleRepository.stopWorkout()
}

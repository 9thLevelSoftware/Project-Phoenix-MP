package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.SettingsEquipmentRackRepository
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceSnapshot
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Test harness for constructing DefaultWorkoutSessionManager with all dependencies wired via fakes.
 *
 * MUST be constructed inside runTest {} — the harness needs the TestScope's [kotlinx.coroutines.test.TestCoroutineScheduler]
 * so advanceUntilIdle()/advanceTimeBy() control DWSM's virtual time.
 *
 * DWSM's init block launches long-running collectors (getAllRoutines, handleState, metricsFlow, etc.)
 * that never complete. To prevent [kotlinx.coroutines.test.UncompletedCoroutinesError], call [cleanup]
 * at the end of each test, or use the extension functions on [WorkoutStateFixtures] which handle this.
 *
 * The harness scope deliberately does NOT inherit the TestScope's full coroutineContext: it shares only
 * the scheduler (via [StandardTestDispatcher]) and parents its [kotlinx.coroutines.Job] to the test's root
 * job. Sharing the scheduler keeps virtual time coupled; dropping the rest of the context keeps runTest's
 * internal completion tracking from counting DWSM's never-ending collectors as pending test work — full
 * context inheritance caused order-dependent advanceUntilIdle() behavior (flaky in the full suite,
 * green in isolation). [cleanup] cancels all DWSM coroutines without affecting the parent TestScope.
 */
class FakeWorkoutServiceController : WorkoutServiceController {
    val snapshots = mutableListOf<WorkoutServiceSnapshot>()
    var stopCount = 0

    override fun showOrUpdate(snapshot: WorkoutServiceSnapshot) {
        snapshots += snapshot
    }

    override fun stop() {
        stopCount++
    }

    fun reset() {
        snapshots.clear()
        stopCount = 0
    }
}

class DWSMTestHarness(val testScope: TestScope) {
    val fakeBleRepo = FakeBleRepository()
    val fakeWorkoutRepo = FakeWorkoutRepository()
    val fakeExerciseRepo = FakeExerciseRepository()
    val fakePRRepo = FakePersonalRecordRepository()
    val fakePrefsManager = FakePreferencesManager()
    val fakeGamificationRepo = FakeGamificationRepository()
    val fakeCompletedSetRepo = FakeCompletedSetRepository()
    val fakeTrainingCycleRepo = FakeTrainingCycleRepository()
    val fakeRepMetricRepo = FakeRepMetricRepository()
    val fakeBiomechanicsRepo = FakeBiomechanicsRepository()
    val fakeWorkoutServiceController = FakeWorkoutServiceController()
    val fakeEquipmentRackRepo = SettingsEquipmentRackRepository(MapSettings())
    val fakeUserProfileRepo = FakeUserProfileRepository()

    val repCounter = RepCounterFromMachine()
    val resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePRRepo, fakeExerciseRepo, FakeVelocityOneRepMaxRepository())
    val applyRoutineModifierUseCase = ApplyRoutineModifierUseCase(fakePRRepo, fakeExerciseRepo)
    val recommendWeightAdjustmentUseCase = RecommendWeightAdjustmentUseCase()
    val applyEquipmentRackLoadUseCase = ApplyEquipmentRackLoadUseCase()

    // Child job of testScope so cleanup() cancels DWSM without affecting the parent TestScope.
    // dwsmScope uses StandardTestDispatcher(testScope.testScheduler) directly — NOT the full
    // testScope.coroutineContext — so that TestScopeElement is NOT inherited. Inheriting
    // TestScopeElement caused runTest's internal completion tracking to see dwsmScope's
    // long-running init collectors as "pending", interleaving teardown ordering in the
    // full test suite and making advanceUntilIdle() return before init work was truly settled.
    private val dwsmJob = Job(testScope.coroutineContext[Job])
    private val dwsmScope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + dwsmJob)
    val workoutScope: CoroutineScope get() = dwsmScope

    val settingsManager = SettingsManager(fakePrefsManager, fakeBleRepo, dwsmScope)
    val gamificationManager = GamificationManager(
        fakeGamificationRepo,
        fakePRRepo,
        fakeExerciseRepo,
        MutableSharedFlow<HapticEvent>(extraBufferCapacity = 10),
        dwsmScope,
        settingsManager.gamificationEnabled,
    )

    val dwsm = DefaultWorkoutSessionManager(
        bleRepository = fakeBleRepo,
        workoutRepository = fakeWorkoutRepo,
        exerciseRepository = fakeExerciseRepo,
        personalRecordRepository = fakePRRepo,
        repCounter = repCounter,
        preferencesManager = fakePrefsManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = fakeTrainingCycleRepo,
        completedSetRepository = fakeCompletedSetRepo,
        syncTriggerManager = null,
        repMetricRepository = fakeRepMetricRepo,
        biomechanicsRepository = fakeBiomechanicsRepo,
        resolveWeightsUseCase = resolveWeightsUseCase,
        applyRoutineModifierUseCase = applyRoutineModifierUseCase,
        recommendWeightAdjustmentUseCase = recommendWeightAdjustmentUseCase,
        equipmentRackRepository = fakeEquipmentRackRepo,
        applyEquipmentRackLoadUseCase = applyEquipmentRackLoadUseCase,
        settingsManager = settingsManager,
        userProfileRepository = fakeUserProfileRepo,
        workoutServiceController = fakeWorkoutServiceController,
        scope = dwsmScope,
        elapsedRealtimeProvider = { testScope.testScheduler.currentTime },
    )

    // BleConnectionManager receives errors via coordinator.bleErrorEvents (no circular dependency)
    val bleConnectionManager = BleConnectionManager(
        fakeBleRepo,
        settingsManager,
        dwsm,
        dwsm.coordinator.bleErrorEvents,
        dwsmScope,
    )

    /** Convenience accessor for the coordinator (shared state bus) */
    val coordinator get() = dwsm.coordinator

    /** Convenience accessor for the routine flow manager (routine CRUD, navigation, supersets) */
    val routineFlowManager get() = dwsm.routineFlowManager

    /** Convenience accessor for the active session engine (workout lifecycle, BLE, auto-stop, rest timer) */
    val activeSessionEngine get() = dwsm.activeSessionEngine

    /**
     * Cancel all DWSM coroutines to prevent UncompletedCoroutinesError.
     * Call this at the end of each test after assertions are complete.
     */
    fun cleanup() {
        dwsm.cleanup()
        dwsmJob.cancel()
    }
}

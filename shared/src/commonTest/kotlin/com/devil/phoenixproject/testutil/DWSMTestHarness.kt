package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope

/**
 * Test harness for constructing DefaultWorkoutSessionManager with all dependencies wired via fakes.
 *
 * MUST be constructed inside runTest {} so TestScope captures DWSM's init block coroutines.
 *
 * DWSM's init block launches long-running collectors (getAllRoutines, handleState, metricsFlow, etc.)
 * that never complete. To prevent [kotlinx.coroutines.test.UncompletedCoroutinesError], call [cleanup]
 * at the end of each test, or use the extension functions on [WorkoutStateFixtures] which handle this.
 *
 * The harness creates a child [CoroutineScope] of the TestScope so that advanceUntilIdle() and
 * advanceTimeBy() properly control virtual time for DWSM's coroutines, while [cleanup] can cancel
 * all DWSM coroutines without affecting the parent TestScope.
 */
class DWSMTestHarness(val testScope: TestScope) {
    val fakeBleRepo = FakeBleRepository()
    val fakeWorkoutRepo = FakeWorkoutRepository()
    val fakeExerciseRepo = FakeExerciseRepository()
    val fakePRRepo = FakePersonalRecordRepository()
    val fakePrefsManager = FakePreferencesManager()
    val fakeGamificationRepo = FakeGamificationRepository()
    val fakeCompletedSetRepo = FakeCompletedSetRepository()
    val fakeTrainingCycleRepo = FakeTrainingCycleRepository()

    val repCounter = RepCounterFromMachine()
    val resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePRRepo)

    // Child scope of testScope: shares TestCoroutineScheduler so advanceUntilIdle() works,
    // but can be cancelled independently via cleanup() to prevent UncompletedCoroutinesError.
    private val dwsmJob = Job(testScope.coroutineContext[Job])
    private val dwsmScope = CoroutineScope(testScope.coroutineContext + dwsmJob)

    val settingsManager = SettingsManager(fakePrefsManager, fakeBleRepo, dwsmScope)
    val gamificationManager = GamificationManager(
        fakeGamificationRepo, fakePRRepo, fakeExerciseRepo,
        MutableSharedFlow<HapticEvent>(extraBufferCapacity = 10), dwsmScope
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
        resolveWeightsUseCase = resolveWeightsUseCase,
        settingsManager = settingsManager,
        scope = dwsmScope
    )

    // BleConnectionManager receives errors via coordinator.bleErrorEvents (no circular dependency)
    val bleConnectionManager = BleConnectionManager(
        fakeBleRepo, settingsManager, dwsm, dwsm.coordinator.bleErrorEvents, dwsmScope
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
        dwsmJob.cancel()
    }
}

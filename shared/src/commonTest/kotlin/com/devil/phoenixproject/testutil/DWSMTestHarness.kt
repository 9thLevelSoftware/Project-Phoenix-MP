package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ProfileEquipmentRackRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.BiomechanicsRepProcessor
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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

class DWSMTestHarness(
    val testScope: TestScope,
    workoutRepositoryOverride: WorkoutRepository? = null,
    completedSetRepositoryOverride: CompletedSetRepository? = null,
    biomechanicsDispatcher: CoroutineDispatcher = Dispatchers.Default,
    biomechanicsRepProcessor: BiomechanicsRepProcessor = BiomechanicsRepProcessor.Default,
    beforeVbtCommit: (executionId: Long, sessionId: String, repNumber: Int) -> Unit = { _, _, _ -> },
    afterVbtDecisionCommit: (executionId: Long, sessionId: String, repNumber: Int) -> Unit = { _, _, _ -> },
    beforeBodyweightCompletionClaim: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    afterBodyweightCompletionConsume: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    afterResetInvalidation: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    afterExecutionBegin: (outgoingExecutionId: Long?, executionId: Long) -> Unit = { _, _ -> },
    hapticEvents: MutableSharedFlow<HapticEvent> = MutableSharedFlow(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.SUSPEND,
    ),
    onPostSaveComputed: suspend (exerciseId: String, profileId: String, sessionMcvMmS: Float?) -> Unit = { _, _, _ -> },
) {
    companion object {
        const val TEST_WALL_CLOCK_EPOCH_MS = 1_800_000_000_000L
    }

    val nowMs: Long
        get() = TEST_WALL_CLOCK_EPOCH_MS + testScope.testScheduler.currentTime

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
    val fakeUserProfileRepo = FakeUserProfileRepository().apply { setActiveProfileForTest() }
    private val workoutRepository = workoutRepositoryOverride ?: fakeWorkoutRepo
    private val completedSetRepository = completedSetRepositoryOverride ?: fakeCompletedSetRepo

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

    val fakeEquipmentRackRepo = ProfileEquipmentRackRepository(fakeUserProfileRepo, dwsmScope)
    val settingsManager = SettingsManager(fakePrefsManager, fakeUserProfileRepo, dwsmScope)
    val gamificationManager = GamificationManager(
        fakeGamificationRepo,
        fakePRRepo,
        fakeExerciseRepo,
        MutableSharedFlow<HapticEvent>(extraBufferCapacity = 10),
        dwsmScope,
        settingsManager.gamificationEnabled,
        onPostSaveComputed,
    )

    val dwsm = DefaultWorkoutSessionManager(
        bleRepository = fakeBleRepo,
        workoutRepository = workoutRepository,
        exerciseRepository = fakeExerciseRepo,
        personalRecordRepository = fakePRRepo,
        repCounter = repCounter,
        preferencesManager = fakePrefsManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = fakeTrainingCycleRepo,
        completedSetRepository = completedSetRepository,
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
        biomechanicsDispatcher = biomechanicsDispatcher,
        biomechanicsRepProcessor = biomechanicsRepProcessor,
        beforeVbtCommit = beforeVbtCommit,
        afterVbtDecisionCommit = afterVbtDecisionCommit,
        beforeBodyweightCompletionClaim = beforeBodyweightCompletionClaim,
        afterBodyweightCompletionConsume = afterBodyweightCompletionConsume,
        afterResetInvalidation = afterResetInvalidation,
        afterExecutionBegin = afterExecutionBegin,
        _hapticEvents = hapticEvents,
        elapsedRealtimeProvider = { testScope.testScheduler.currentTime },
        wallClockMillisProvider = { nowMs },
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

    fun startCableSet(targetReps: Int) {
        dwsm.updateWorkoutParameters(
            WorkoutParameters(
                programMode = ProgramMode.OldSchool,
                reps = targetReps,
                warmupReps = 0,
                weightPerCableKg = 25f,
            ),
        )
        dwsm.startWorkout(skipCountdown = true)
        testScope.testScheduler.advanceUntilIdle()
    }

    fun modernRepPacket(
        repsSetCount: Int,
        repsSetTotal: Int,
        timestamp: Long,
        topCounter: Int = repsSetCount,
        completeCounter: Int = repsSetCount,
        repsRomCount: Int = 0,
        repsRomTotal: Int = 3,
    ) = RepNotification(
        topCounter = topCounter,
        completeCounter = completeCounter,
        repsRomCount = repsRomCount,
        repsRomTotal = repsRomTotal,
        repsSetCount = repsSetCount,
        repsSetTotal = repsSetTotal,
        rangeTop = 800f,
        rangeBottom = 0f,
        rawData = ByteArray(24),
        timestamp = timestamp,
    )

    fun legacyRepPacket(
        topCounter: Int,
        completeCounter: Int,
        timestamp: Long,
    ) = RepNotification(
        topCounter = topCounter,
        completeCounter = completeCounter,
        repsRomCount = 0,
        repsRomTotal = 0,
        repsSetCount = 0,
        repsSetTotal = 0,
        rawData = ByteArray(6),
        timestamp = timestamp,
        isLegacyFormat = true,
    )

    private fun readyProfile(): ActiveProfileContext.Ready = fakeUserProfileRepo.activeProfileContext.value as ActiveProfileContext.Ready

    suspend fun setActiveBodyWeightKg(value: Float) {
        val ready = readyProfile()
        fakeUserProfileRepo.updateCore(
            ready.profile.id,
            ready.preferences.core.value.copy(bodyWeightKg = value),
        )
    }

    suspend fun setActiveSummaryCountdownSeconds(value: Int) {
        val ready = readyProfile()
        fakeUserProfileRepo.updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(summaryCountdownSeconds = value),
        )
    }

    suspend fun setActiveCountdownBeepsEnabled(value: Boolean) {
        val ready = readyProfile()
        fakeUserProfileRepo.updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(countdownBeepsEnabled = value),
        )
    }

    suspend fun setActiveProfilePreferences(value: UserPreferences) {
        val initial = readyProfile()
        fakeUserProfileRepo.updateCore(
            initial.profile.id,
            initial.preferences.core.value.copy(
                bodyWeightKg = value.bodyWeightKg,
                weightUnit = value.weightUnit,
                weightIncrement = value.weightIncrement,
            ),
        )
        var ready = readyProfile()
        fakeUserProfileRepo.updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(
                stopAtTop = value.stopAtTop,
                beepsEnabled = value.beepsEnabled,
                audioRepCountEnabled = value.audioRepCountEnabled,
                repCountTiming = value.repCountTiming,
                summaryCountdownSeconds = value.summaryCountdownSeconds,
                autoStartCountdownSeconds = value.autoStartCountdownSeconds,
                gamificationEnabled = value.gamificationEnabled,
                autoStartRoutine = value.autoStartRoutine,
                countdownBeepsEnabled = value.countdownBeepsEnabled,
                repSoundEnabled = value.repSoundEnabled,
                motionStartEnabled = value.motionStartEnabled,
                weightSuggestionsEnabled = value.weightSuggestionsEnabled,
                defaultRoutineExerciseUsePercentOfPR = value.defaultRoutineExerciseUsePercentOfPR,
                defaultRoutineExerciseWeightPercentOfPR = value.defaultRoutineExerciseWeightPercentOfPR,
                voiceStopEnabled = value.voiceStopEnabled,
            ),
        )
        ready = readyProfile()
        fakeUserProfileRepo.updateLed(
            ready.profile.id,
            ready.preferences.led.value.copy(
                colorScheme = value.colorScheme,
                discoModeUnlocked = value.discoModeUnlocked,
            ),
        )
        ready = readyProfile()
        fakeUserProfileRepo.updateVbt(
            ready.profile.id,
            ready.preferences.vbt.value.copy(
                enabled = value.vbtEnabled,
                velocityLossThresholdPercent = value.velocityLossThresholdPercent,
                autoEndOnVelocityLoss = value.autoEndOnVelocityLoss,
                defaultScalingBasis = value.defaultScalingBasis,
                verbalEncouragementEnabled = value.verbalEncouragementEnabled,
                vulgarModeEnabled = value.vulgarModeEnabled,
                vulgarTier = value.vulgarTier,
                dominatrixModeUnlocked = value.dominatrixModeUnlocked,
                dominatrixModeActive = value.dominatrixModeActive,
            ),
        )
        ready = readyProfile()
        fakeUserProfileRepo.updateLocalSafety(
            ready.profile.id,
            ready.localSafety.copy(
                safeWord = value.safeWord,
                safeWordCalibrated = value.safeWordCalibrated,
                adultsOnlyConfirmed = value.adultsOnlyConfirmed,
                adultsOnlyPrompted = value.adultsOnlyPrompted,
            ),
        )
        fakePrefsManager.setPreferences(
            fakePrefsManager.preferencesFlow.value.copy(
                enableVideoPlayback = value.enableVideoPlayback,
                autoBackupEnabled = value.autoBackupEnabled,
                backupDestination = value.backupDestination,
                language = value.language,
                velocityOneRepMaxBackfillDone = value.velocityOneRepMaxBackfillDone,
                bleCompatibilityMode = value.bleCompatibilityMode,
            ),
        )
    }

    /**
     * Cancel all DWSM coroutines to prevent UncompletedCoroutinesError.
     * Call this at the end of each test after assertions are complete.
     */
    fun cleanup() {
        dwsm.cleanup()
        dwsmJob.cancel()
    }
}

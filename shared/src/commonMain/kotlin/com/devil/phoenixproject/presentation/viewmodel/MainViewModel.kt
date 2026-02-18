package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import com.devil.phoenixproject.presentation.manager.HistoryManager
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.JustLiftDefaults
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo

// HistoryItem, SingleSessionHistoryItem, GroupedRoutineHistoryItem moved to
// com.devil.phoenixproject.presentation.manager.HistoryManager

/**
 * Represents a dynamic action for the top app bar.
 */
data class TopBarAction(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit
)

class MainViewModel constructor(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    val exerciseRepository: ExerciseRepository,
    val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationRepository: GamificationRepository,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val syncTriggerManager: SyncTriggerManager? = null,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase
) : ViewModel() {

    // Shared haptic events flow - created here, passed to both GamificationManager and WorkoutSessionManager
    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // === Phase 1b: SettingsManager (extracted from this class) ===
    val settingsManager = SettingsManager(preferencesManager, bleRepository, viewModelScope)

    // === Phase 1a: HistoryManager (extracted from this class) ===
    val historyManager = HistoryManager(workoutRepository, personalRecordRepository, viewModelScope)

    // === Phase 2b: GamificationManager (extracted from this class) ===
    val gamificationManager = GamificationManager(
        gamificationRepository, personalRecordRepository, exerciseRepository,
        _hapticEvents, viewModelScope, settingsManager.gamificationEnabled
    )

    // === Phase 3: WorkoutSessionManager (extracted from this class) ===
    val workoutSessionManager = DefaultWorkoutSessionManager(
        bleRepository = bleRepository,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        personalRecordRepository = personalRecordRepository,
        repCounter = repCounter,
        preferencesManager = preferencesManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = trainingCycleRepository,
        completedSetRepository = completedSetRepository,
        syncTriggerManager = syncTriggerManager,
        resolveWeightsUseCase = resolveWeightsUseCase,
        settingsManager = settingsManager,
        scope = viewModelScope,
        _hapticEvents = _hapticEvents
    )

    // === Phase 2a: BleConnectionManager (extracted from this class) ===
    // Must be after workoutSessionManager since it implements WorkoutStateProvider
    // BLE errors flow one-way via coordinator.bleErrorEvents (no circular dependency)
    val bleConnectionManager = BleConnectionManager(
        bleRepository, settingsManager, workoutSessionManager,
        workoutSessionManager.coordinator.bleErrorEvents, viewModelScope
    )

    // ===== Workout State Delegation =====

    val workoutState: StateFlow<WorkoutState> get() = workoutSessionManager.coordinator.workoutState
    val isWorkoutActive: Boolean get() = workoutSessionManager.coordinator.isWorkoutActive
    val routineFlowState: StateFlow<RoutineFlowState> get() = workoutSessionManager.coordinator.routineFlowState
    val currentMetric: StateFlow<WorkoutMetric?> get() = workoutSessionManager.coordinator.currentMetric
    val currentHeuristicKgMax: StateFlow<Float> get() = workoutSessionManager.coordinator.currentHeuristicKgMax
    val loadBaselineA: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineA
    val loadBaselineB: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineB
    val workoutParameters: StateFlow<WorkoutParameters> get() = workoutSessionManager.coordinator.workoutParameters
    val repCount: StateFlow<RepCount> get() = workoutSessionManager.coordinator.repCount
    val timedExerciseRemainingSeconds: StateFlow<Int?> get() = workoutSessionManager.coordinator.timedExerciseRemainingSeconds
    val repRanges: StateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?> get() = workoutSessionManager.coordinator.repRanges
    val autoStopState: StateFlow<AutoStopUiState> get() = workoutSessionManager.coordinator.autoStopState
    val autoStartCountdown: StateFlow<Int?> get() = workoutSessionManager.coordinator.autoStartCountdown
    val hapticEvents: SharedFlow<HapticEvent> get() = workoutSessionManager.coordinator.hapticEvents
    val userFeedbackEvents: SharedFlow<String> get() = workoutSessionManager.coordinator.userFeedbackEvents
    val routines: StateFlow<List<Routine>> get() = workoutSessionManager.coordinator.routines
    val loadedRoutine: StateFlow<Routine?> get() = workoutSessionManager.coordinator.loadedRoutine
    val currentExerciseIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentExerciseIndex
    val currentSetIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentSetIndex
    val skippedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.skippedExercises
    val completedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.completedExercises
    val currentSetRpe: StateFlow<Int?> get() = workoutSessionManager.coordinator.currentSetRpe
    val isCurrentExerciseBodyweight: StateFlow<Boolean> get() = workoutSessionManager.coordinator.isCurrentExerciseBodyweight
    val cycleDayCompletionEvent get() = workoutSessionManager.coordinator.cycleDayCompletionEvent
    fun clearCycleDayCompletionEvent() = workoutSessionManager.clearCycleDayCompletionEvent()

    // ===== BLE Connection Delegation =====

    val connectionState: StateFlow<ConnectionState> get() = bleConnectionManager.connectionState
    val scannedDevices: StateFlow<List<ScannedDevice>> get() = bleConnectionManager.scannedDevices
    val isAutoConnecting: StateFlow<Boolean> get() = bleConnectionManager.isAutoConnecting
    val connectionError: StateFlow<String?> get() = bleConnectionManager.connectionError
    val connectionLostDuringWorkout: StateFlow<Boolean> get() = bleConnectionManager.connectionLostDuringWorkout

    fun startScanning() = bleConnectionManager.startScanning()
    fun stopScanning() = bleConnectionManager.stopScanning()
    fun cancelScanOrConnection() = bleConnectionManager.cancelScanOrConnection()
    fun connectToDevice(deviceAddress: String) = bleConnectionManager.connectToDevice(deviceAddress)
    fun disconnect() = bleConnectionManager.disconnect()
    fun clearConnectionError() = bleConnectionManager.clearConnectionError()
    fun dismissConnectionLostAlert() = bleConnectionManager.dismissConnectionLostAlert()
    fun cancelAutoConnecting() = bleConnectionManager.cancelAutoConnecting()
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) =
        bleConnectionManager.ensureConnection(onConnected, onFailed)
    fun cancelConnection() = bleConnectionManager.cancelConnection()

    // ===== History Delegation =====

    val workoutHistory: StateFlow<List<WorkoutSession>> get() = historyManager.workoutHistory
    val allWorkoutSessions: StateFlow<List<WorkoutSession>> get() = historyManager.allWorkoutSessions
    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> get() = historyManager.groupedWorkoutHistory
    val allPersonalRecords: StateFlow<List<PersonalRecord>> get() = historyManager.allPersonalRecords
    @Suppress("unused")
    val personalBests: StateFlow<List<com.devil.phoenixproject.data.repository.PersonalRecordEntity>>
        get() = historyManager.personalBests
    val completedWorkouts: StateFlow<Int?> get() = historyManager.completedWorkouts
    val workoutStreak: StateFlow<Int?> get() = historyManager.workoutStreak
    val progressPercentage: StateFlow<Int?> get() = historyManager.progressPercentage
    fun deleteWorkout(sessionId: String) = historyManager.deleteWorkout(sessionId)
    fun deleteAllWorkouts() = historyManager.deleteAllWorkouts()

    // ===== Settings Delegation =====

    val userPreferences: StateFlow<UserPreferences> get() = settingsManager.userPreferences
    val weightUnit: StateFlow<WeightUnit> get() = settingsManager.weightUnit
    val enableVideoPlayback: StateFlow<Boolean> get() = settingsManager.enableVideoPlayback
    val autoplayEnabled: StateFlow<Boolean> get() = settingsManager.autoplayEnabled

    fun setWeightUnit(unit: WeightUnit) = settingsManager.setWeightUnit(unit)
    fun setStopAtTop(enabled: Boolean) = settingsManager.setStopAtTop(enabled)
    fun setEnableVideoPlayback(enabled: Boolean) = settingsManager.setEnableVideoPlayback(enabled)
    fun setStallDetectionEnabled(enabled: Boolean) = settingsManager.setStallDetectionEnabled(enabled)
    fun setAudioRepCountEnabled(enabled: Boolean) = settingsManager.setAudioRepCountEnabled(enabled)
    fun setRepCountTiming(timing: RepCountTiming) = settingsManager.setRepCountTiming(timing)
    fun setSummaryCountdownSeconds(seconds: Int) = settingsManager.setSummaryCountdownSeconds(seconds)
    fun setAutoStartCountdownSeconds(seconds: Int) = settingsManager.setAutoStartCountdownSeconds(seconds)
    fun setColorScheme(schemeIndex: Int) = settingsManager.setColorScheme(schemeIndex)
    fun setGamificationEnabled(enabled: Boolean) = settingsManager.setGamificationEnabled(enabled)
    fun kgToDisplay(kg: Float, unit: WeightUnit) = settingsManager.kgToDisplay(kg, unit)
    fun displayToKg(display: Float, unit: WeightUnit) = settingsManager.displayToKg(display, unit)
    fun formatWeight(kg: Float, unit: WeightUnit) = settingsManager.formatWeight(kg, unit)

    // ===== Gamification Delegation =====

    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> get() = gamificationManager.prCelebrationEvent
    val badgeEarnedEvents: SharedFlow<List<Badge>> get() = gamificationManager.badgeEarnedEvents
    fun emitBadgeSound() = gamificationManager.emitBadgeSound()
    fun emitPRSound() = gamificationManager.emitPRSound()

    // ===== Workout Lifecycle Delegation =====

    fun updateWorkoutParameters(params: WorkoutParameters) = workoutSessionManager.updateWorkoutParameters(params)
    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) =
        workoutSessionManager.startWorkout(skipCountdown, isJustLiftMode)
    fun stopWorkout(exitingWorkout: Boolean = false) = workoutSessionManager.stopWorkout(exitingWorkout)
    fun stopAndReturnToSetReady() = workoutSessionManager.stopAndReturnToSetReady()
    fun pauseWorkout() = workoutSessionManager.pauseWorkout()
    fun resumeWorkout() = workoutSessionManager.resumeWorkout()
    fun skipCountdown() = workoutSessionManager.skipCountdown()
    fun resetForNewWorkout() = workoutSessionManager.resetForNewWorkout()
    fun recaptureLoadBaseline() = workoutSessionManager.recaptureLoadBaseline()
    fun resetLoadBaseline() = workoutSessionManager.resetLoadBaseline()
    fun proceedFromSummary() = workoutSessionManager.proceedFromSummary()
    fun skipRest() = workoutSessionManager.skipRest()
    fun startNextSet() = workoutSessionManager.startNextSet()
    fun logRpeForCurrentSet(rpe: Int) = workoutSessionManager.logRpeForCurrentSet(rpe)

    // ===== Routine Management Delegation =====

    fun getRoutineById(routineId: String): Routine? = workoutSessionManager.getRoutineById(routineId)
    fun saveRoutine(routine: Routine) = workoutSessionManager.saveRoutine(routine)
    fun updateRoutine(routine: Routine) = workoutSessionManager.updateRoutine(routine)
    fun deleteRoutine(routineId: String) = workoutSessionManager.deleteRoutine(routineId)
    fun deleteRoutines(routineIds: Set<String>) = workoutSessionManager.deleteRoutines(routineIds)
    fun loadRoutine(routine: Routine) = workoutSessionManager.loadRoutine(routine)
    /** Issue #2 Fix: Suspend version that completes after routine is fully loaded (including PR weight resolution) */
    suspend fun loadRoutineAsync(routine: Routine) = workoutSessionManager.loadRoutineAsync(routine)
    fun loadRoutineById(routineId: String) = workoutSessionManager.loadRoutineById(routineId)
    fun enterRoutineOverview(routine: Routine) = workoutSessionManager.enterRoutineOverview(routine)
    fun selectExerciseInOverview(index: Int) = workoutSessionManager.selectExerciseInOverview(index)
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) = workoutSessionManager.enterSetReady(exerciseIndex, setIndex)
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) =
        workoutSessionManager.enterSetReadyWithAdjustments(exerciseIndex, setIndex, adjustedWeight, adjustedReps)
    fun updateSetReadyWeight(weight: Float) = workoutSessionManager.updateSetReadyWeight(weight)
    fun updateSetReadyReps(reps: Int) = workoutSessionManager.updateSetReadyReps(reps)
    fun updateSetReadyEchoLevel(level: EchoLevel) = workoutSessionManager.updateSetReadyEchoLevel(level)
    fun updateSetReadyEccentricLoad(percent: Int) = workoutSessionManager.updateSetReadyEccentricLoad(percent)
    fun startSetFromReady() = workoutSessionManager.startSetFromReady()
    fun returnToOverview() = workoutSessionManager.returnToOverview()
    fun exitRoutineFlow() = workoutSessionManager.exitRoutineFlow()
    fun showRoutineComplete() = workoutSessionManager.showRoutineComplete()
    fun clearLoadedRoutine() = workoutSessionManager.clearLoadedRoutine()
    fun getCurrentExercise(): RoutineExercise? = workoutSessionManager.getCurrentExercise()
    fun hasResumableProgress(routineId: String): Boolean = workoutSessionManager.hasResumableProgress(routineId)
    fun getResumableProgressInfo(): ResumableProgressInfo? = workoutSessionManager.getResumableProgressInfo()
    fun hasNextStep(exerciseIndex: Int, setIndex: Int): Boolean = workoutSessionManager.hasNextStep(exerciseIndex, setIndex)
    fun hasPreviousStep(exerciseIndex: Int, setIndex: Int): Boolean = workoutSessionManager.hasPreviousStep(exerciseIndex, setIndex)
    fun setReadyPrev() = workoutSessionManager.setReadyPrev()
    fun setReadySkip() = workoutSessionManager.setReadySkip()

    // ===== Exercise Navigation Delegation =====

    fun advanceToNextExercise() = workoutSessionManager.advanceToNextExercise()
    fun jumpToExercise(index: Int) = workoutSessionManager.jumpToExercise(index)
    fun skipCurrentExercise() = workoutSessionManager.skipCurrentExercise()
    fun goToPreviousExercise() = workoutSessionManager.goToPreviousExercise()
    fun canGoBack(): Boolean = workoutSessionManager.canGoBack()
    fun canSkipForward(): Boolean = workoutSessionManager.canSkipForward()
    fun getRoutineExerciseNames(): List<String> = workoutSessionManager.getRoutineExerciseNames()

    // ===== Weight Adjustment Delegation =====

    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) =
        workoutSessionManager.adjustWeight(newWeightKg, sendToMachine)
    fun incrementWeight(amount: Float = 0.5f) = workoutSessionManager.incrementWeight(amount)
    fun decrementWeight(amount: Float = 0.5f) = workoutSessionManager.decrementWeight(amount)
    fun setWeightPreset(presetWeightKg: Float) = workoutSessionManager.setWeightPreset(presetWeightKg)
    suspend fun getLastWeightForExercise(exerciseId: String): Float? =
        workoutSessionManager.getLastWeightForExercise(exerciseId)
    suspend fun getPrWeightForExercise(exerciseId: String): Float? =
        workoutSessionManager.getPrWeightForExercise(exerciseId)

    // ===== Just Lift / Handle Detection Delegation =====

    fun enableHandleDetection() = workoutSessionManager.enableHandleDetection()
    fun disableHandleDetection() = workoutSessionManager.disableHandleDetection()
    fun prepareForJustLift() = workoutSessionManager.prepareForJustLift()
    suspend fun getJustLiftDefaults(): JustLiftDefaults = workoutSessionManager.getJustLiftDefaults()
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) = workoutSessionManager.saveJustLiftDefaults(defaults)
    suspend fun getSingleExerciseDefaults(exerciseId: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? =
        workoutSessionManager.getSingleExerciseDefaults(exerciseId)
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) =
        workoutSessionManager.saveSingleExerciseDefaults(defaults)

    // ===== Superset CRUD Delegation =====

    suspend fun createSuperset(
        routineId: String,
        name: String? = null,
        exercises: List<RoutineExercise> = emptyList()
    ) = workoutSessionManager.createSuperset(routineId, name, exercises)
    suspend fun updateSuperset(routineId: String, superset: Superset) =
        workoutSessionManager.updateSuperset(routineId, superset)
    suspend fun deleteSuperset(routineId: String, supersetId: String) =
        workoutSessionManager.deleteSuperset(routineId, supersetId)
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) =
        workoutSessionManager.addExerciseToSuperset(routineId, exerciseId, supersetId)
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) =
        workoutSessionManager.removeExerciseFromSuperset(routineId, exerciseId)

    // ===== Training Cycle Delegation =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) =
        workoutSessionManager.loadRoutineFromCycle(routineId, cycleId, dayNumber)
    fun clearCycleContext() = workoutSessionManager.clearCycleContext()

    // ===== Top Bar State (stays here - pure UI scaffolding) =====

    private val _topBarTitle = MutableStateFlow("Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()

    fun updateTopBarTitle(title: String) {
        _topBarTitle.value = title
    }

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarActions(actions: List<TopBarAction>) {
        _topBarActions.value = actions
    }

    fun clearTopBarActions() {
        _topBarActions.value = emptyList()
    }

    private val _topBarBackAction = MutableStateFlow<(() -> Unit)?>(null)
    val topBarBackAction: StateFlow<(() -> Unit)?> = _topBarBackAction.asStateFlow()

    fun setTopBarBackAction(action: () -> Unit) {
        _topBarBackAction.value = action
    }

    fun clearTopBarBackAction() {
        _topBarBackAction.value = null
    }

    // ===== Workout Setup Dialog (stays here - pure UI state) =====

    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible: StateFlow<Boolean> = _isWorkoutSetupDialogVisible.asStateFlow()

    // ===== Disco Mode (Easter Egg - stays here) =====

    val discoModeActive: StateFlow<Boolean> = bleRepository.discoModeActive

    fun unlockDiscoMode() {
        viewModelScope.launch {
            preferencesManager.setDiscoModeUnlocked(true)
            Logger.i { "DISCO MODE UNLOCKED!" }
        }
    }

    fun toggleDiscoMode(enabled: Boolean) {
        if (enabled) {
            bleRepository.startDiscoMode()
        } else {
            bleRepository.stopDiscoMode()
        }
    }

    fun emitDiscoSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.DISCO_MODE_UNLOCKED)
        }
    }

    // ===== Test Sounds (stays here - developer utility) =====

    fun testSounds() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.REP_COMPLETED)
            kotlinx.coroutines.delay(800)
            _hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
            kotlinx.coroutines.delay(1000)
            _hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(5))
            kotlinx.coroutines.delay(1000)
            _hapticEvents.emit(HapticEvent.WORKOUT_COMPLETE)
        }
    }

    // ===== Simulator Mode (Easter Egg - stays here) =====

    val simulatorModeUnlocked: StateFlow<Boolean> get() = settingsManager.simulatorModeUnlocked
    val simulatorModeEnabled: StateFlow<Boolean> get() = settingsManager.simulatorModeEnabled

    fun unlockSimulatorMode() {
        settingsManager.setSimulatorModeUnlocked(true)
        Logger.i { "SIMULATOR MODE UNLOCKED!" }
    }

    fun toggleSimulatorMode(enabled: Boolean) {
        settingsManager.setSimulatorModeEnabled(enabled)
    }

    // ===== Cleanup =====

    override fun onCleared() {
        super.onCleared()
        workoutSessionManager.cleanup()
        bleConnectionManager.cancelConnectionJob()

        // Issue: BLE resource leak - Disconnect BLE when ViewModel is cleared
        // to prevent battery drain and orphaned connections.
        // Use NonCancellable context since viewModelScope may be cancelled during onCleared
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                bleRepository.disconnect()
                Logger.i { "BLE disconnected during ViewModel cleanup" }
            } catch (e: Exception) {
                Logger.e { "Failed to disconnect BLE during cleanup: ${e.message}" }
            }
        }

        Logger.i { "MainViewModel cleared, all jobs cancelled" }
    }
}

package com.devil.phoenixproject.presentation.viewmodel

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.EquipmentRackRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BodyweightVariantOption
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SessionBodyweightState
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.BackfillVelocityOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.CountVelocityOneRepMaxImprovementsUseCase
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryManager
import com.devil.phoenixproject.presentation.manager.JustLiftDefaults
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.util.BackupStats
import com.devil.phoenixproject.util.DataBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// HistoryItem, SingleSessionHistoryItem, GroupedRoutineHistoryItem moved to
// com.devil.phoenixproject.presentation.manager.HistoryManager

/**
 * Represents a dynamic action for the top app bar.
 */
data class TopBarAction(val icon: ImageVector, val description: String, val onClick: () -> Unit)

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
    private val repMetricRepository: RepMetricRepository,
    private val biomechanicsRepository: BiomechanicsRepository,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val applyRoutineModifierUseCase: ApplyRoutineModifierUseCase = ApplyRoutineModifierUseCase(personalRecordRepository, exerciseRepository),
    private val recommendWeightAdjustmentUseCase: RecommendWeightAdjustmentUseCase,
    private val equipmentRackRepository: EquipmentRackRepository,
    private val applyEquipmentRackLoadUseCase: ApplyEquipmentRackLoadUseCase,
    private val dataBackupManager: DataBackupManager,
    private val userProfileRepository: UserProfileRepository,
    private val healthIntegration: HealthIntegration? = null,
    private val externalActivityRepository: ExternalActivityRepository? = null,
    private val workoutServiceController: WorkoutServiceController,
    private val healthExportCursorRepository: IntegrationSyncCursorRepository? = null,
    // Velocity-based 1RM (issue #517): computed via GamificationManager's post-save hook.
    private val computeVelocityOneRepMaxUseCase: ComputeVelocityOneRepMaxUseCase,
    private val recordPersonalMvtSampleUseCase: RecordPersonalMvtSampleUseCase,
    // Exposed as a public val so ExerciseDetailScreen can query the latest passing estimate.
    val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val countVelocityOneRepMaxImprovementsUseCase: CountVelocityOneRepMaxImprovementsUseCase,
    // Issue #517: one-time startup backfill of velocity-1RM estimates for historical data.
    private val backfillVelocityOneRepMaxUseCase: BackfillVelocityOneRepMaxUseCase,
) : ViewModel() {

    // Shared haptic events flow - created here, passed to both GamificationManager and WorkoutSessionManager
    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )

    // === Phase 1b: SettingsManager (extracted from this class) ===
    val settingsManager = SettingsManager(preferencesManager, bleRepository, viewModelScope)

    // === Phase 1a: HistoryManager (extracted from this class) ===
    val historyManager = HistoryManager(workoutRepository, personalRecordRepository, userProfileRepository, viewModelScope)

    // Active profile id, exposed publicly so profile-scoped reads (e.g. velocity-1RM on
    // ExerciseDetailScreen) query the correct profile instead of a hardcoded "default".
    val activeProfileId: StateFlow<String> =
        userProfileRepository.activeProfile
            .map { it?.id ?: "default" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    // === Phase 2b: GamificationManager (extracted from this class) ===
    val gamificationManager: GamificationManager = GamificationManager(
        gamificationRepository,
        personalRecordRepository,
        exerciseRepository,
        _hapticEvents,
        viewModelScope,
        settingsManager.gamificationEnabled,
        // Velocity-based 1RM post-save hook (issue #517): capture personalized-MVT sample then
        // recompute the velocity-1RM estimate for the just-saved exercise.
        onPostSaveComputed = { exId, profile, mcv ->
            mcv?.let { v ->
                exerciseRepository.getExerciseById(exId)?.let { ex ->
                    recordPersonalMvtSampleUseCase(exId, profile, ex.name, ex.muscleGroups, v)
                }
            }
            computeVelocityOneRepMaxUseCase(exId, profile, com.devil.phoenixproject.domain.model.currentTimeMillis())
            val improvements = countVelocityOneRepMaxImprovementsUseCase(
                velocityOneRepMaxRepository.getAllPassing(profile),
            )
            gamificationManager.checkVelocityOneRepMaxBadges(improvements, profile)
        },
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
        repMetricRepository = repMetricRepository,
        biomechanicsRepository = biomechanicsRepository,
        resolveWeightsUseCase = resolveWeightsUseCase,
        applyRoutineModifierUseCase = applyRoutineModifierUseCase,
        recommendWeightAdjustmentUseCase = recommendWeightAdjustmentUseCase,
        equipmentRackRepository = equipmentRackRepository,
        applyEquipmentRackLoadUseCase = applyEquipmentRackLoadUseCase,
        settingsManager = settingsManager,
        dataBackupManager = dataBackupManager,
        userProfileRepository = userProfileRepository,
        healthIntegration = healthIntegration,
        externalActivityRepository = externalActivityRepository,
        healthExportCursorRepository = healthExportCursorRepository,
        workoutServiceController = workoutServiceController,
        scope = viewModelScope,
        _hapticEvents = _hapticEvents,
    )

    // === Phase 2a: BleConnectionManager (extracted from this class) ===
    // Must be after workoutSessionManager since it implements WorkoutStateProvider
    // BLE errors flow one-way via coordinator.bleErrorEvents (no circular dependency)
    val bleConnectionManager = BleConnectionManager(
        bleRepository,
        settingsManager,
        workoutSessionManager,
        workoutSessionManager.coordinator.bleErrorEvents,
        viewModelScope,
    )

    // ===== Workout State Delegation =====

    val workoutState: StateFlow<WorkoutState> get() = workoutSessionManager.coordinator.workoutState
    val isWorkoutActive: Boolean get() = workoutSessionManager.coordinator.isWorkoutActive
    val routineFlowState: StateFlow<RoutineFlowState> get() = workoutSessionManager.coordinator.routineFlowState

    /** Issue #348: Session-scoped flag covering active sets AND between-set routine screens */
    val isInWorkoutSession get() = workoutSessionManager.coordinator.isInWorkoutSession
    val currentMetric: StateFlow<WorkoutMetric?> get() = workoutSessionManager.coordinator.currentMetric
    val currentHeuristicKgMax: StateFlow<Float> get() = workoutSessionManager.coordinator.currentHeuristicKgMax
    val loadBaselineA: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineA
    val loadBaselineB: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineB
    val workoutParameters: StateFlow<WorkoutParameters> get() = workoutSessionManager.coordinator.workoutParameters
    val rackItems get() = equipmentRackRepository.rackItems
    val activeRackItemIds: StateFlow<List<String>> get() = workoutSessionManager.coordinator.activeRackItemIds
    val activeRackBehaviorOverrides: StateFlow<Map<String, RackItemBehavior>> get() = workoutSessionManager.coordinator.activeRackBehaviorOverrides
    val currentRackLoadAdjustment: StateFlow<RackLoadAdjustment> get() = workoutSessionManager.coordinator.currentRackLoadAdjustment
    val repCount: StateFlow<RepCount> get() = workoutSessionManager.coordinator.repCount
    val timedExerciseRemainingSeconds: StateFlow<Int?> get() = workoutSessionManager.coordinator.timedExerciseRemainingSeconds
    val repRanges: StateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?> get() = workoutSessionManager.coordinator.repRanges
    val autoStopState: StateFlow<AutoStopUiState> get() = workoutSessionManager.coordinator.autoStopState
    val autoStartCountdown: StateFlow<Int?> get() = workoutSessionManager.coordinator.autoStartCountdown
    val hapticEvents: SharedFlow<HapticEvent> get() = workoutSessionManager.coordinator.hapticEvents
    val userFeedbackEvents: SharedFlow<String> get() = workoutSessionManager.coordinator.userFeedbackEvents
    val routines: StateFlow<List<Routine>> get() = workoutSessionManager.coordinator.routines
    val routineGroups: StateFlow<List<RoutineGroup>> get() = workoutSessionManager.coordinator.routineGroups
    val loadedRoutine: StateFlow<Routine?> get() = workoutSessionManager.coordinator.loadedRoutine
    val currentExerciseIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentExerciseIndex
    val currentSetIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentSetIndex
    val skippedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.skippedExercises
    val completedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.completedExercises
    val currentSetRpe: StateFlow<Int?> get() = workoutSessionManager.coordinator.currentSetRpe
    val isCurrentExerciseBodyweight: StateFlow<Boolean> get() = workoutSessionManager.coordinator.isCurrentExerciseBodyweight
    val selectedBodyweightVariants: StateFlow<Map<String, BodyweightVariantOption>> get() = workoutSessionManager.selectedBodyweightVariants
    val sessionBodyweightState: StateFlow<SessionBodyweightState> get() = workoutSessionManager.sessionBodyweightState
    val resolvedBodyWeightKg: Float get() = workoutSessionManager.resolvedBodyWeightKg()
    val latestRepQuality get() = workoutSessionManager.coordinator.latestRepQuality
    val latestBiomechanicsResult get() = workoutSessionManager.coordinator.latestBiomechanicsResult
    val motionStartHoldProgress: StateFlow<Float?> get() = workoutSessionManager.coordinator.motionStartHoldProgress
    val justLiftRestCountdown: StateFlow<Int?> get() = workoutSessionManager.coordinator.justLiftRestCountdown
    val cycleDayCompletionEvent get() = workoutSessionManager.coordinator.cycleDayCompletionEvent
    fun clearCycleDayCompletionEvent() = workoutSessionManager.clearCycleDayCompletionEvent()

    suspend fun tagJustLiftSessionExercise(sessionId: String, exercise: Exercise, isAmrap: Boolean) = workoutSessionManager.tagJustLiftSessionExercise(sessionId, exercise, isAmrap)

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
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) = bleConnectionManager.ensureConnection(onConnected, onFailed)
    fun reconnectInterruptedWorkout() {
        bleConnectionManager.dismissConnectionLostAlert()
        bleConnectionManager.ensureConnection(
            onConnected = { workoutSessionManager.reconnectInterruptedWorkout() },
            onFailed = {},
        )
    }
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

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): route the
     * History "Delete All Sets" group action through the HistoryManager
     * so zero-rep ghost rows hidden by `getHistoryVisibleSessions` are
     * soft-deleted along with the visible sets.
     */
    fun deleteRoutineWorkouts(routineSessionId: String) = historyManager.deleteRoutineWorkouts(routineSessionId)

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
    fun setWeightIncrement(increment: Float) = settingsManager.setWeightIncrement(increment)
    fun setAutoStartRoutine(enabled: Boolean) = settingsManager.setAutoStartRoutine(enabled)
    fun setBodyWeightKg(weightKg: Float) = settingsManager.setBodyWeightKg(weightKg)
    fun setGamificationEnabled(enabled: Boolean) = settingsManager.setGamificationEnabled(enabled)
    fun setCountdownBeepsEnabled(enabled: Boolean) = settingsManager.setCountdownBeepsEnabled(enabled)
    fun setRepSoundEnabled(enabled: Boolean) = settingsManager.setRepSoundEnabled(enabled)
    fun setMotionStartEnabled(enabled: Boolean) = settingsManager.setMotionStartEnabled(enabled)
    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsManager.setAutoBackupEnabled(enabled)
        refreshBackupStats()
    }

    fun setBackupDestination(destination: com.devil.phoenixproject.util.BackupDestination) {
        settingsManager.setBackupDestination(destination)
    }

    fun setLanguage(language: String) {
        settingsManager.setLanguage(language)
    }

    // Issue #141: Voice-activated emergency stop
    fun setVoiceStopEnabled(enabled: Boolean) = settingsManager.setVoiceStopEnabled(enabled)
    fun setSafeWord(word: String?) = settingsManager.setSafeWord(word)
    fun setSafeWordCalibrated(calibrated: Boolean) = settingsManager.setSafeWordCalibrated(calibrated)
    fun setVelocityLossThreshold(percent: Int) = settingsManager.setVelocityLossThreshold(percent)
    fun setAutoEndOnVelocityLoss(enabled: Boolean) = settingsManager.setAutoEndOnVelocityLoss(enabled)
    fun setWeightSuggestionsEnabled(enabled: Boolean) = settingsManager.setWeightSuggestionsEnabled(enabled)

    // Issue #517: system-wide default scaling basis
    fun setDefaultScalingBasis(basis: ScalingBasis) = settingsManager.setDefaultScalingBasis(basis)

    // Backup stats for Settings UI
    private val _backupStats = kotlinx.coroutines.flow.MutableStateFlow<BackupStats?>(null)
    val backupStats: kotlinx.coroutines.flow.StateFlow<BackupStats?> = _backupStats

    fun refreshBackupStats() {
        viewModelScope.launch {
            _backupStats.value = dataBackupManager.getBackupStats()
        }
    }

    fun openBackupFolder() {
        dataBackupManager.openBackupFolder()
    }

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
    fun updateActiveRackSelection(itemIds: List<String>) = workoutSessionManager.updateActiveRackSelection(itemIds)
    fun updateActiveRackBehaviorOverrides(overrides: Map<String, RackItemBehavior>) = workoutSessionManager.updateActiveRackBehaviorOverrides(overrides)
    fun clearActiveRackSelection() = workoutSessionManager.clearActiveRackSelection()
    fun saveRackItem(item: RackItem) {
        viewModelScope.launch {
            equipmentRackRepository.upsert(item)
        }
    }

    fun deleteRackItem(id: String) {
        viewModelScope.launch {
            equipmentRackRepository.delete(id)
            val activeIds = activeRackItemIds.value
            val remainingActiveIds = activeIds.filterNot { it == id }
            if (remainingActiveIds.size != activeIds.size) {
                updateActiveRackSelection(remainingActiveIds)
            }
        }
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) = workoutSessionManager.startWorkout(skipCountdown, isJustLiftMode)
    fun stopWorkout(exitingWorkout: Boolean = false) = workoutSessionManager.stopWorkout(exitingWorkout)
    fun stopAndReturnToSetReady() = workoutSessionManager.stopAndReturnToSetReady()
    fun stopAndSkipCurrentExercise() = workoutSessionManager.stopAndSkipCurrentExercise()
    fun pauseWorkout() = workoutSessionManager.pauseWorkout()
    fun resumeWorkout() = workoutSessionManager.resumeWorkout()
    fun skipCountdown() = workoutSessionManager.skipCountdown()
    fun resetForNewWorkout() = workoutSessionManager.resetForNewWorkout()
    fun recaptureLoadBaseline() = workoutSessionManager.recaptureLoadBaseline()
    fun resetLoadBaseline() = workoutSessionManager.resetLoadBaseline()
    fun proceedFromSummary() = workoutSessionManager.proceedFromSummary()
    fun skipRest() = workoutSessionManager.skipRest()
    fun extendRestTime(seconds: Int) = workoutSessionManager.extendRestTime(seconds)
    fun toggleRestPause() = workoutSessionManager.toggleRestPause()
    fun resetRestTimer() = workoutSessionManager.resetRestTimer()
    val isRestPaused get() = workoutSessionManager.coordinator.isRestPaused

    // Issue #190: Exercise timer controls for timed exercises (TUT/Echo/bodyweight)
    fun pauseExerciseTimer() = workoutSessionManager.pauseExerciseTimer()
    fun resumeExerciseTimer() = workoutSessionManager.resumeExerciseTimer()
    fun resetExerciseTimer() = workoutSessionManager.resetExerciseTimer()
    val isExerciseTimerPaused get() = workoutSessionManager.coordinator.isExerciseTimerPaused

    // Phase 35C: Variable warm-up set state
    val currentWarmupSetIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentWarmupSetIndex
    val totalWarmupSets: StateFlow<Int> get() = workoutSessionManager.coordinator.totalWarmupSets
    val weightAdjustmentRecommendation get() = workoutSessionManager.coordinator.weightAdjustmentRecommendation
    fun startNextSet() = workoutSessionManager.startNextSet()
    fun logRpeForCurrentSet(rpe: Int) = workoutSessionManager.logRpeForCurrentSet(rpe)

    // ===== Routine Management Delegation =====

    fun getRoutineById(routineId: String): Routine? = workoutSessionManager.getRoutineById(routineId)
    fun saveRoutine(routine: Routine) = workoutSessionManager.saveRoutine(routine)
    fun updateRoutine(routine: Routine) = workoutSessionManager.updateRoutine(routine)
    fun saveRackBehaviorOverridesForExercise(
        exerciseIndex: Int,
        overrides: Map<String, RackItemBehavior>,
    ) {
        val routine = loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return
        val updatedActiveRoutine = routine.withRackBehaviorOverrides(
            exerciseIndex = exerciseIndex,
            exerciseId = exercise.id,
            overrides = overrides,
        ) ?: return
        workoutSessionManager.coordinator._loadedRoutine.value = updatedActiveRoutine
        updateActiveRackBehaviorOverrides(overrides)
        viewModelScope.launch {
            try {
                val storedRoutine = workoutRepository.getRoutineById(routine.id)
                if (storedRoutine == null) {
                    Logger.w { "Rack override save skipped: stored routine not found for id=${routine.id}" }
                    return@launch
                }
                val updatedStoredRoutine = storedRoutine.withRackBehaviorOverrides(
                    exerciseIndex = exerciseIndex,
                    exerciseId = exercise.id,
                    overrides = overrides,
                )
                if (updatedStoredRoutine == null) {
                    Logger.w { "Rack override save skipped: exercise id=${exercise.id} not found in stored routine id=${routine.id}" }
                    return@launch
                }
                workoutRepository.updateRoutine(updatedStoredRoutine)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(e) { "Failed to save rack behavior overrides for routine id=${routine.id}, exercise id=${exercise.id}" }
            }
        }
    }
    private fun Routine.withRackBehaviorOverrides(
        exerciseIndex: Int,
        exerciseId: String,
        overrides: Map<String, RackItemBehavior>,
    ): Routine? {
        val targetIndex = exercises.indexOfFirst { it.id == exerciseId }
            .takeIf { it >= 0 }
            ?: if (exerciseId.isBlank()) {
                exerciseIndex.takeIf { it in exercises.indices }
            } else {
                null
            }
            ?: return null

        return copy(
            exercises = exercises.mapIndexed { index, routineExercise ->
                if (index == targetIndex) {
                    routineExercise.copy(rackBehaviorOverrides = overrides)
                } else {
                    routineExercise
                }
            },
        )
    }

    fun deleteRoutine(routineId: String) = workoutSessionManager.deleteRoutine(routineId)
    fun deleteRoutines(routineIds: Set<String>) = workoutSessionManager.deleteRoutines(routineIds)
    fun moveRoutinesToProfile(routineIds: Set<String>, targetProfileId: String) = workoutSessionManager.moveRoutinesToProfile(routineIds, targetProfileId)
    fun saveRoutineToProfile(routine: Routine, targetProfileId: String) = workoutSessionManager.saveRoutineToProfile(routine, targetProfileId)

    // Routine Group CRUD
    fun createGroup(name: String) = workoutSessionManager.createGroup(name)
    fun renameGroup(groupId: String, newName: String) = workoutSessionManager.renameGroup(groupId, newName)
    fun deleteGroup(groupId: String) = workoutSessionManager.deleteGroup(groupId)
    fun moveRoutinesToGroup(routineIds: Set<String>, groupId: String?) = workoutSessionManager.moveRoutinesToGroup(routineIds, groupId)

    fun loadRoutine(routine: Routine) = workoutSessionManager.loadRoutine(routine)

    /** Issue #2 Fix: Suspend version that completes after routine is fully loaded (including PR weight resolution) */
    suspend fun loadRoutineAsync(routine: Routine) = workoutSessionManager.loadRoutineAsync(routine)
    fun loadRoutineById(routineId: String) = workoutSessionManager.loadRoutineById(routineId)
    fun enterRoutineOverview(routine: Routine) = workoutSessionManager.enterRoutineOverview(routine)
    fun enterRoutineOverview(routine: Routine, modifier: AppliedRoutineModifier) = workoutSessionManager.enterRoutineOverview(routine, modifier)
    fun selectExerciseInOverview(index: Int) = workoutSessionManager.selectExerciseInOverview(index)
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) = workoutSessionManager.enterSetReady(exerciseIndex, setIndex)
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) = workoutSessionManager.enterSetReadyWithAdjustments(exerciseIndex, setIndex, adjustedWeight, adjustedReps)
    fun updateSetReadyWeight(weight: Float) = workoutSessionManager.updateSetReadyWeight(weight)
    fun updateSetReadyReps(reps: Int) = workoutSessionManager.updateSetReadyReps(reps)
    fun updateSetReadyProgressionKg(valueKg: Float) = workoutSessionManager.updateSetReadyProgressionKg(valueKg)
    fun updateSetReadyEchoLevel(level: EchoLevel) = workoutSessionManager.updateSetReadyEchoLevel(level)
    fun updateSetReadyEccentricLoad(percent: Int) = workoutSessionManager.updateSetReadyEccentricLoad(percent)
    fun startSetFromReady() = workoutSessionManager.startSetFromReady()
    fun applyWeightRecommendation() = workoutSessionManager.applyWeightRecommendation()
    fun dismissWeightRecommendation() = workoutSessionManager.dismissWeightRecommendation()
    fun bodyweightVariantKey(exercise: RoutineExercise): String = workoutSessionManager.bodyweightVariantKey(exercise)
    fun selectBodyweightVariant(exerciseKey: String, variant: BodyweightVariantOption) = workoutSessionManager.selectBodyweightVariant(exerciseKey, variant)
    fun confirmBodyweightSetResult(reps: Int, variant: BodyweightVariantOption) = workoutSessionManager.confirmBodyweightSetResult(reps, variant)
    fun confirmSessionBodyWeight(weightKg: Float?, saveToProfile: Boolean) = workoutSessionManager.confirmSessionBodyWeight(weightKg, saveToProfile)
    fun skipSessionBodyWeightPrompt() = workoutSessionManager.skipSessionBodyWeightPrompt()
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

    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) = workoutSessionManager.adjustWeight(newWeightKg, sendToMachine)
    fun incrementWeight(amount: Float = 0.5f) = workoutSessionManager.incrementWeight(amount)
    fun decrementWeight(amount: Float = 0.5f) = workoutSessionManager.decrementWeight(amount)
    fun setWeightPreset(presetWeightKg: Float) = workoutSessionManager.setWeightPreset(presetWeightKg)
    suspend fun getLastWeightForExercise(exerciseId: String): Float? = workoutSessionManager.getLastWeightForExercise(exerciseId)
    suspend fun getPrWeightForExercise(exerciseId: String): Float? = workoutSessionManager.getPrWeightForExercise(exerciseId)

    // ===== Just Lift / Handle Detection Delegation =====

    fun enableHandleDetection() = workoutSessionManager.enableHandleDetection()
    fun disableHandleDetection() = workoutSessionManager.disableHandleDetection()
    fun prepareForJustLift() = workoutSessionManager.prepareForJustLift()
    suspend fun getJustLiftDefaults(): JustLiftDefaults = workoutSessionManager.getJustLiftDefaults()
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) = workoutSessionManager.saveJustLiftDefaults(defaults)
    suspend fun getSingleExerciseDefaults(exerciseId: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? = workoutSessionManager.getSingleExerciseDefaults(exerciseId)
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) = workoutSessionManager.saveSingleExerciseDefaults(defaults)

    // ===== Superset CRUD Delegation =====

    suspend fun createSuperset(routineId: String, name: String? = null, exercises: List<RoutineExercise> = emptyList()) = workoutSessionManager.createSuperset(routineId, name, exercises)
    suspend fun updateSuperset(routineId: String, superset: Superset) = workoutSessionManager.updateSuperset(routineId, superset)
    suspend fun deleteSuperset(routineId: String, supersetId: String) = workoutSessionManager.deleteSuperset(routineId, supersetId)
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) = workoutSessionManager.addExerciseToSuperset(routineId, exerciseId, supersetId)
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) = workoutSessionManager.removeExerciseFromSuperset(routineId, exerciseId)

    // ===== Training Cycle Delegation =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) = workoutSessionManager.loadRoutineFromCycle(routineId, cycleId, dayNumber)
    suspend fun loadRoutineFromCycleAsync(routineId: String, cycleId: String, dayNumber: Int) = workoutSessionManager.loadRoutineFromCycleAsync(routineId, cycleId, dayNumber)
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

    // ===== Velocity-1RM Backfill (Issue #517) =====

    init {
        // Run once at startup: backfill velocity-1RM estimates for historical sets.
        // Gated by a run-once preference flag so it never re-runs after the first successful pass.
        //
        // F050: launch as a normal child of viewModelScope (NOT NonCancellable).
        // Passing NonCancellable as the launch context replaces structured
        // cancellation, so this long DB backfill would keep running after the
        // ViewModel is cleared and hold repository/ViewModel references past the
        // lifecycle. The work is idempotent (run-once flag set only on success,
        // per-profile try/catch), so cancelling on clear is safe — it resumes on
        // the next launch.
        viewModelScope.launch {
            try {
                if (!settingsManager.velocityOneRepMaxBackfillDone.value) {
                    // Backfill EVERY profile's history (not just the active one): the run-once flag
                    // is global, so a profile skipped here would never be backfilled. Await the
                    // loaded profile list (10s timeout fallback to the active profile id for genuine
                    // single-profile installs whose list stays empty).
                    val profileIds = (
                        kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            userProfileRepository.allProfiles.first { it.isNotEmpty() }
                        } ?: userProfileRepository.allProfiles.value
                        ).map { it.id }.ifEmpty { listOf(activeProfileId.value) }
                    val now = com.devil.phoenixproject.domain.model.currentTimeMillis()
                    // Per-profile try/catch so one profile's failure can't abort the rest, and the
                    // run-once flag is still set afterwards (a failed profile is covered later by its
                    // own new workouts / hasEstimates idempotency rather than blocking every launch).
                    for (profileId in profileIds) {
                        try {
                            backfillVelocityOneRepMaxUseCase(profileId, now)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Logger.w(e) { "VELOCITY_1RM: backfill failed for profile=$profileId" }
                        }
                    }
                    preferencesManager.setVelocityOneRepMaxBackfillDone(true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.w(e) { "VELOCITY_1RM: backfill failed" }
            }
        }
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

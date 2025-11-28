package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.UserPreferences
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.HandleActivityState
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.WorkoutRepository
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.KmpLocalDate
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.ceil
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sealed class hierarchy for workout history items
 */
sealed class HistoryItem {
    abstract val timestamp: Long
}

data class SingleSessionHistoryItem(val session: WorkoutSession) : HistoryItem() {
    override val timestamp: Long = session.timestamp
}

data class GroupedRoutineHistoryItem(
    val routineSessionId: String,
    val routineName: String,
    val sessions: List<WorkoutSession>,
    val totalDuration: Long,
    val totalReps: Int,
    val exerciseCount: Int,
    override val timestamp: Long
) : HistoryItem()

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
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    companion object {
        /** Prefix for temporary single exercise routines to identify them for cleanup */
        const val TEMP_SINGLE_EXERCISE_PREFIX = "temp_single_"

        /** Auto-stop duration in seconds (time handles must be released before auto-stop triggers) */
        const val AUTO_STOP_DURATION_SECONDS = 3f

        /** Position threshold to consider handle at rest */
        const val HANDLE_REST_THRESHOLD = 2.5

        /** Minimum position range to consider "meaningful" for auto-stop detection */
        const val MIN_RANGE_THRESHOLD = 50
    }

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    private val _currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric.asStateFlow()

    private val _workoutParameters = MutableStateFlow(
        WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            weightPerCableKg = 10f,
            progressionRegressionKg = 0f,
            isJustLift = false,
            stopAtTop = false,
            warmupReps = 3
        )
    )
    val workoutParameters: StateFlow<WorkoutParameters> = _workoutParameters.asStateFlow()

    private val _repCount = MutableStateFlow(RepCount())
    val repCount: StateFlow<RepCount> = _repCount.asStateFlow()

    private val _repRanges = MutableStateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?>(null)
    val repRanges: StateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?> = _repRanges.asStateFlow()

    private val _autoStopState = MutableStateFlow(AutoStopUiState())
    val autoStopState: StateFlow<AutoStopUiState> = _autoStopState.asStateFlow()

    private val _autoStartCountdown = MutableStateFlow<Int?>(null)
    val autoStartCountdown: StateFlow<Int?> = _autoStartCountdown.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()

    // Top Bar Title State
    private val _topBarTitle = MutableStateFlow("Vitruvian Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()

    fun updateTopBarTitle(title: String) {
        _topBarTitle.value = title
    }

    // Top Bar Actions State
    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarActions(actions: List<TopBarAction>) {
        _topBarActions.value = actions
    }

    fun clearTopBarActions() {
        _topBarActions.value = emptyList()
    }

    // Top Bar Back Action Override
    private val _topBarBackAction = MutableStateFlow<(() -> Unit)?>(null)
    val topBarBackAction: StateFlow<(() -> Unit)?> = _topBarBackAction.asStateFlow()

    fun setTopBarBackAction(action: () -> Unit) {
        _topBarBackAction.value = action
    }

    fun clearTopBarBackAction() {
        _topBarBackAction.value = null
    }

    // PR Celebration Events
    private val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    // User preferences
    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeightUnit.KG)

    val stopAtTop: StateFlow<Boolean> = userPreferences
        .map { it.stopAtTop }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.autoplayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Feature 4: Routine Management
    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private val _loadedRoutine = MutableStateFlow<Routine?>(null)
    val loadedRoutine: StateFlow<Routine?> = _loadedRoutine.asStateFlow()

    private val _currentExerciseIndex = MutableStateFlow(0)
    val currentExerciseIndex: StateFlow<Int> = _currentExerciseIndex.asStateFlow()

    private val _currentSetIndex = MutableStateFlow(0)
    val currentSetIndex: StateFlow<Int> = _currentSetIndex.asStateFlow()

    // Weekly Programs
    val weeklyPrograms: StateFlow<List<com.devil.phoenixproject.data.local.WeeklyProgramWithDays>> =
        workoutRepository.getAllPrograms()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val activeProgram: StateFlow<com.devil.phoenixproject.data.local.WeeklyProgramWithDays?> =
        workoutRepository.getActiveProgram()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )

    // Personal Records
    @Suppress("unused")
    val personalBests: StateFlow<List<com.devil.phoenixproject.data.repository.PersonalRecordEntity>> =
        workoutRepository.getAllPersonalRecords()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // ========== Stats for HomeScreen ==========

    val allWorkoutSessions: StateFlow<List<WorkoutSession>> =
        workoutRepository.getAllSessions()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { sessions ->
        val groupedByRoutine = sessions.filter { it.routineSessionId != null }
            .groupBy { it.routineSessionId!! }
            .map { (id, sessionList) ->
                GroupedRoutineHistoryItem(
                    routineSessionId = id,
                    routineName = sessionList.first().routineName ?: "Unnamed Routine",
                    sessions = sessionList.sortedBy { it.timestamp },
                    totalDuration = sessionList.sumOf { it.duration },
                    totalReps = sessionList.sumOf { it.totalReps },
                    exerciseCount = sessionList.mapNotNull { it.exerciseId }.distinct().count(),
                    timestamp = sessionList.minOf { it.timestamp }
                )
            }
        val singleSessions = sessions.filter { it.routineSessionId == null }
            .map { SingleSessionHistoryItem(it) }

        (groupedByRoutine + singleSessions).sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPersonalRecords: StateFlow<List<PersonalRecord>> =
        personalRecordRepository.getAllPRsGrouped()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val completedWorkouts: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        sessions.size.takeIf { it > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Calculate current workout streak (consecutive days with workouts).
     * Returns null if no workouts or streak is broken.
     */
    val workoutStreak: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        if (sessions.isEmpty()) {
            return@map null
        }

        // Get unique workout dates, sorted descending (most recent first)
        val workoutDates = sessions
            .map { KmpLocalDate.fromTimestamp(it.timestamp) }
            .distinctBy { it.toKey() }
            .sortedDescending()

        val today = KmpLocalDate.today()
        val lastWorkoutDate = workoutDates.first()

        // Check if streak is current (workout today or yesterday)
        if (lastWorkoutDate.isBefore(today.minusDays(1))) {
            return@map null // Streak broken - no workout today or yesterday
        }

        // Count consecutive days
        var streak = 1
        for (i in 1 until workoutDates.size) {
            val expected = workoutDates[i - 1].minusDays(1)
            if (workoutDates[i] == expected) {
                streak++
            } else {
                break // Found a gap
            }
        }
        streak
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val progressPercentage: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        if (sessions.size < 2) return@map null
        val latest = sessions[0]
        val previous = sessions[1]
        val latestVol = (latest.weightPerCableKg * 2) * latest.totalReps
        val prevVol = (previous.weightPerCableKg * 2) * previous.totalReps
        if (prevVol <= 0f) return@map null
        ((latestVol - prevVol) / prevVol * 100).toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible: StateFlow<Boolean> = _isWorkoutSetupDialogVisible.asStateFlow()

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var _pendingConnectionCallback: (() -> Unit)? = null

    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val hapticEvents: SharedFlow<HapticEvent> = _hapticEvents.asSharedFlow()

    private val _connectionLostDuringWorkout = MutableStateFlow(false)
    val connectionLostDuringWorkout: StateFlow<Boolean> = _connectionLostDuringWorkout.asStateFlow()

    private var currentSessionId: String? = null
    private var workoutStartTime: Long = 0
    private val collectedMetrics = mutableListOf<WorkoutMetric>()

    private var currentRoutineSessionId: String? = null
    private var currentRoutineName: String? = null

    private var autoStopStartTime: Long? = null
    private var autoStopTriggered = false
    private var autoStopStopRequested = false
    private var currentHandleState: HandleActivityState = HandleActivityState.WaitingForRest

    private var connectionJob: Job? = null
    private var monitorDataCollectionJob: Job? = null
    private var autoStartJob: Job? = null
    private var restTimerJob: Job? = null
    private var bodyweightTimerJob: Job? = null
    private var repEventsCollectionJob: Job? = null

    // Session-level peak loads (for analytics/debugging)
    private var maxConcentricPerCableKgThisSession: Float = 0f
    private var maxEccentricPerCableKgThisSession: Float = 0f

    init {
        Logger.d("MainViewModel initialized")

        // Load recent history
        viewModelScope.launch {
            workoutRepository.getAllSessions().collect { sessions ->
                _workoutHistory.value = sessions.take(20)
            }
        }

        // Load routines
        viewModelScope.launch {
            workoutRepository.getAllRoutines().collect { routinesList ->
                _routines.value = routinesList
            }
        }

        // Import exercises if not already imported
        viewModelScope.launch {
            try {
                val result = exerciseRepository.importExercises()
                if (result.isSuccess) {
                    Logger.d { "Exercise library initialized" }
                } else {
                    Logger.e { "Failed to initialize exercise library: ${result.exceptionOrNull()?.message}" }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error initializing exercise library" }
            }
        }

        // Hook up RepCounter
        repCounter.onRepEvent = { event ->
             viewModelScope.launch {
                 when (event.type) {
                     RepType.WORKING_COMPLETED -> _hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETED -> _hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETE -> _hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                     RepType.WORKOUT_COMPLETE -> _hapticEvents.emit(HapticEvent.WORKOUT_COMPLETE)
                     else -> {}
                 }
             }
        }

        // Handle state collector for auto-start functionality
        viewModelScope.launch {
            bleRepository.handleState.collect { handleState ->
                val handlesDetected = handleState.leftDetected || handleState.rightDetected
                val params = _workoutParameters.value
                val currentState = _workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Auto-start logic: when handles are grabbed in idle state
                if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                    if (handlesDetected) {
                        startAutoStartTimer()
                    } else {
                        cancelAutoStartTimer()
                    }
                }

                // Track handle activity state
                currentHandleState = when {
                    currentState is WorkoutState.Active && handlesDetected -> HandleActivityState.Active
                    currentState is WorkoutState.SetSummary -> HandleActivityState.SetComplete
                    else -> HandleActivityState.WaitingForRest
                }
            }
        }

        // Rep events collector for handling machine rep notifications
        repEventsCollectionJob = viewModelScope.launch {
            bleRepository.repEvents.collect { notification ->
                val state = _workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }
    }

    /**
     * Handle rep notification from the machine.
     * Updates rep counter and position ranges for visualization.
     */
    private fun handleRepNotification(notification: RepNotification) {
        val currentPositions = _currentMetric.value

        // Use machine's ROM and Set counters directly (official app method)
        repCounter.process(
            repsRomCount = notification.repsRomCount,
            repsSetCount = notification.repsSetCount,
            up = notification.topCounter,
            down = notification.completeCounter,
            posA = currentPositions?.positionA ?: 0,
            posB = currentPositions?.positionB ?: 0
        )

        // Update rep count and ranges for UI
        _repCount.value = repCounter.getRepCount()
        _repRanges.value = repCounter.getRepRanges()
    }

    fun startScanning() {
        viewModelScope.launch { bleRepository.startScanning() }
    }

    fun stopScanning() {
        viewModelScope.launch { bleRepository.stopScanning() }
    }

    fun connectToDevice(deviceAddress: String) {
        // Handled by ensureConnection mostly, but direct connect stub
    }

    fun disconnect() {
        viewModelScope.launch { bleRepository.disconnect() }
    }

    fun updateWorkoutParameters(params: WorkoutParameters) {
        _workoutParameters.value = params
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        val connection = connectionState.value
        if (connection !is ConnectionState.Connected) {
            _connectionError.value = "Not connected to device"
            return
        }

        viewModelScope.launch {
            val params = _workoutParameters.value

            // Check for bodyweight exercise
            val currentExercise = _loadedRoutine.value?.exercises?.getOrNull(_currentExerciseIndex.value)
            val isBodyweight = isBodyweightExercise(currentExercise)
            val bodyweightDuration = if (isBodyweight) currentExercise?.duration else null

            // For bodyweight exercises with duration, skip machine commands
            if (isBodyweight && bodyweightDuration != null) {
                // Bodyweight duration-based exercise (e.g., plank, wall sit)
                Logger.d("Starting bodyweight exercise: ${currentExercise?.exercise?.name} for ${bodyweightDuration}s")

                // Countdown
                if (!skipCountdown) {
                    for (i in 5 downTo 1) {
                        _workoutState.value = WorkoutState.Countdown(i)
                        delay(1000)
                    }
                }

                // Start timer
                _workoutState.value = WorkoutState.Active
                workoutStartTime = currentTimeMillis()
                currentSessionId = KmpUtils.randomUUID()
                _hapticEvents.emit(HapticEvent.WORKOUT_START)

                // Bodyweight timer - auto-complete after duration
                bodyweightTimerJob?.cancel()
                bodyweightTimerJob = viewModelScope.launch {
                    delay(bodyweightDuration * 1000L)
                    handleSetCompletion()
                }

                return@launch
            }

            // Normal cable-based exercise

            // 1. Build Command
            val command = if (params.workoutType is WorkoutType.Program) {
                BlePacketFactory.createWorkoutCommand(
                    params.workoutType,
                    params.weightPerCableKg,
                    params.reps
                )
            } else {
                 val echo = params.workoutType as WorkoutType.Echo
                 BlePacketFactory.createEchoCommand(echo.level.levelValue, echo.eccentricLoad.percentage)
            }

            // 2. Send Command
            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.d { "Workout command sent: $params" }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send command" }
                _connectionError.value = "Failed to send command: ${e.message}"
                return@launch
            }

            // 3. Reset State
            currentSessionId = KmpUtils.randomUUID()
            _repCount.value = RepCount()
            repCounter.reset()
            repCounter.configure(
                warmupTarget = params.warmupReps,
                workingTarget = params.reps,
                isJustLift = isJustLiftMode,
                stopAtTop = params.stopAtTop,
                isAMRAP = params.isAMRAP
            )

            // 4. Countdown
            if (!skipCountdown && !isJustLiftMode) {
                for (i in 5 downTo 1) {
                    _workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
            }

            // 5. Start Monitoring
            _workoutState.value = WorkoutState.Active
            workoutStartTime = currentTimeMillis()
            _hapticEvents.emit(HapticEvent.WORKOUT_START)
            monitorWorkout(isJustLiftMode)
        }
    }

    private fun monitorWorkout(isJustLift: Boolean) {
        monitorDataCollectionJob?.cancel()
        monitorDataCollectionJob = viewModelScope.launch {
             bleRepository.metricsFlow.collect { metric ->
                 _currentMetric.value = metric
                 
                 repCounter.process(
                     repsRomCount = 0, // TODO: Extract from packet
                     repsSetCount = 0, // TODO: Extract from packet
                     posA = metric.positionA,
                     posB = metric.positionB
                 )
                 
                 _repCount.value = repCounter.getRepCount()

                 // Update position ranges continuously for Just Lift mode
                 if (isJustLift) {
                     repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
                 }

                 // Just Lift Auto-Stop (danger zone detection)
                 // Note: AMRAP mode explicitly disables auto-stop
                 val params = _workoutParameters.value
                 if (params.isJustLift && !params.isAMRAP) {
                     checkAutoStop(metric)
                 }

                 // Standard Auto-Stop (rep target reached)
                 if (repCounter.shouldStopWorkout()) {
                     stopWorkout()
                 }
             }
        }
    }

    fun stopWorkout() {
        viewModelScope.launch {
             bleRepository.sendWorkoutCommand(BlePacketFactory.createStopCommand())
             monitorDataCollectionJob?.cancel()
             _hapticEvents.emit(HapticEvent.WORKOUT_END)
             
             val params = _workoutParameters.value
             val repCount = _repCount.value
             
             val session = WorkoutSession(
                 timestamp = workoutStartTime,
                 mode = params.workoutType.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - workoutStartTime,
                 isJustLift = params.isJustLift
             )
             workoutRepository.saveSession(session)
             
             // Show Summary
             val metrics = emptyList<WorkoutMetric>() // TODO: collectedMetrics
             _workoutState.value = WorkoutState.SetSummary(
                 metrics = metrics,
                 peakPower = 0f,
                 averagePower = 0f,
                 repCount = repCount.totalReps
             )
        }
    }

    fun pauseWorkout() {
        _workoutState.value = WorkoutState.Paused
    }

    fun resumeWorkout() {
        _workoutState.value = WorkoutState.Active
    }

    fun setWeightUnit(unit: WeightUnit) {
        viewModelScope.launch { preferencesManager.setWeightUnit(unit) }
    }

    fun setStopAtTop(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setStopAtTop(enabled) }
    }

    fun setEnableVideoPlayback(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setEnableVideoPlayback(enabled) }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setAutoplayEnabled(enabled) }
    }

    fun setColorScheme(schemeIndex: Int) {
        viewModelScope.launch { bleRepository.setColorScheme(schemeIndex) }
    }

    fun deleteAllWorkouts() {
        viewModelScope.launch { workoutRepository.deleteAllSessions() }
    }

    fun clearConnectionError() {
        _connectionError.value = null
    }

    fun dismissConnectionLostAlert() {
        _connectionLostDuringWorkout.value = false
    }

    fun cancelAutoConnecting() {
        _isAutoConnecting.value = false
        _connectionError.value = null
        connectionJob?.cancel()
        connectionJob = null
        viewModelScope.launch { bleRepository.stopScanning() }
    }

    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            try {
                if (connectionState.value is ConnectionState.Connected) {
                    onConnected()
                    return@launch
                }
                
                _isAutoConnecting.value = true
                _connectionError.value = null
                bleRepository.startScanning()
                
                // Wait for device scan (Simplified auto-connect to first device for V1)
                val device = withTimeoutOrNull(10000) {
                    bleRepository.scannedDevices.filter { it.isNotEmpty() }.map { it.first() }.firstOrNull()
                }
                
                if (device != null) {
                    bleRepository.connect(device)
                    // Wait for connection
                    val connected = withTimeoutOrNull(10000) {
                        connectionState.filter { it is ConnectionState.Connected }.firstOrNull()
                    }
                    
                    if (connected != null) {
                        _isAutoConnecting.value = false
                        onConnected()
                    } else {
                        _connectionError.value = "Connection failed"
                        _isAutoConnecting.value = false
                    }
                } else {
                    _connectionError.value = "No device found"
                    _isAutoConnecting.value = false
                    bleRepository.stopScanning()
                }
            } catch (e: Exception) {
                _connectionError.value = "Error: ${e.message}"
                _isAutoConnecting.value = false
            }
        }
    }

    fun proceedFromSummary() {
        _workoutState.value = WorkoutState.Idle
    }

    fun resetForNewWorkout() {
        _workoutState.value = WorkoutState.Idle
        _repCount.value = RepCount()
    }

    fun advanceToNextExercise() {
        // TODO
    }

    fun deleteWorkout(sessionId: String) {
        viewModelScope.launch { workoutRepository.deleteSession(sessionId) }
    }

    fun kgToDisplay(kg: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> kg
            WeightUnit.LB -> kg * 2.20462f
        }

    fun displayToKg(display: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> display
            WeightUnit.LB -> display / 2.20462f
        }

    fun formatWeight(kg: Float, unit: WeightUnit): String {
        val value = kgToDisplay(kg, unit)
        return value.format(1)
    }

    fun saveRoutine(routine: Routine) {
        viewModelScope.launch { workoutRepository.saveRoutine(routine) }
    }

    fun updateRoutine(routine: Routine) {
        viewModelScope.launch { workoutRepository.updateRoutine(routine) }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch { workoutRepository.deleteRoutine(routineId) }
    }

    fun loadRoutine(routine: Routine) {
        _loadedRoutine.value = routine
        _currentExerciseIndex.value = 0
        _currentSetIndex.value = 0
    }

    fun loadRoutineById(routineId: String) {
        val routine = _routines.value.find { it.id == routineId }
        if (routine != null) {
            loadRoutine(routine)
        }
    }

    fun clearLoadedRoutine() {
        _loadedRoutine.value = null
    }

    // ========== Weekly Program Functions ==========

    fun saveProgram(program: com.devil.phoenixproject.data.local.WeeklyProgramWithDays) {
        viewModelScope.launch { workoutRepository.saveProgram(program) }
    }

    fun deleteProgram(programId: String) {
        viewModelScope.launch { workoutRepository.deleteProgram(programId) }
    }

    fun activateProgram(programId: String) {
        viewModelScope.launch { workoutRepository.activateProgram(programId) }
    }

    fun getProgramById(programId: String): kotlinx.coroutines.flow.Flow<com.devil.phoenixproject.data.local.WeeklyProgramWithDays?> {
        return workoutRepository.getProgramById(programId)
    }

    fun getCurrentExercise(): RoutineExercise? {
        val routine = _loadedRoutine.value ?: return null
        return routine.exercises.getOrNull(_currentExerciseIndex.value)
    }

    // ========== Just Lift Features ==========

    /**
     * Enable handle detection for auto-start functionality.
     * When connected, the machine monitors handle grip to auto-start workout.
     */
    fun enableHandleDetection() {
        Logger.d("MainViewModel: Enabling handle detection for auto-start")
        bleRepository.enableHandleDetection(true)
    }

    /**
     * Disable handle detection.
     * Called when leaving Just Lift mode or when handle detection is no longer needed.
     */
    fun disableHandleDetection() {
        Logger.d("MainViewModel: Disabling handle detection")
        bleRepository.enableHandleDetection(false)
    }

    /**
     * Prepare for Just Lift mode by resetting workout state while preserving weight.
     * Called when entering Just Lift screen with non-Idle state.
     */
    fun prepareForJustLift() {
        viewModelScope.launch {
            val currentWeight = _workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: Resetting state, preserving weight=$currentWeight kg")

            // Reset workout state to Idle
            _workoutState.value = WorkoutState.Idle
            _repCount.value = RepCount()

            // Preserve weight in parameters
            _workoutParameters.value = _workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true
            )
        }
    }

    /**
     * Get saved Single Exercise defaults for a specific exercise and cable configuration.
     * Returns null if no defaults have been saved yet.
     */
    suspend fun getSingleExerciseDefaults(exerciseId: String, cableConfig: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? {
        return preferencesManager.getSingleExerciseDefaults(exerciseId, cableConfig)
    }

    /**
     * Save Single Exercise defaults for a specific exercise and cable configuration.
     */
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) {
        viewModelScope.launch {
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d("saveSingleExerciseDefaults: exerciseId=${defaults.exerciseId}, cableConfig=${defaults.cableConfig}")
        }
    }

    /**
     * Get saved Just Lift defaults.
     * Returns null if no defaults have been saved yet.
     */
    suspend fun getJustLiftDefaults(): JustLiftDefaults? {
        // TODO: Implement preferences storage for Just Lift defaults
        // return preferencesManager.getJustLiftDefaults()
        return null
    }

    /**
     * Save Just Lift defaults for next session.
     */
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        viewModelScope.launch {
            // TODO: Implement preferences storage for Just Lift defaults
            // preferencesManager.saveJustLiftDefaults(defaults)
            Logger.d("saveJustLiftDefaults: weight=${defaults.weightPerCableKg}kg, mode=${defaults.workoutModeId}")
        }
    }

    // ========== Auto-Stop Helper Functions ==========

    // ==================== AUTO-START FUNCTIONS ====================

    /**
     * Start the auto-start countdown timer (5 seconds).
     * When user grabs handles while in Idle or SetSummary state, this starts
     * a countdown and automatically begins the workout.
     */
    private fun startAutoStartTimer() {
        // Don't start if already running or not in appropriate state
        if (autoStartJob != null) return
        val currentState = _workoutState.value
        if (currentState !is WorkoutState.Idle && currentState !is WorkoutState.SetSummary) {
            return
        }

        autoStartJob = viewModelScope.launch {
            // 5-second countdown with visible progress
            for (i in 5 downTo 1) {
                _autoStartCountdown.value = i
                delay(1000)
            }
            _autoStartCountdown.value = null

            // Auto-start the workout in Just Lift mode
            if (_workoutParameters.value.isJustLift) {
                startWorkout(skipCountdown = true, isJustLiftMode = true)
            } else {
                startWorkout(skipCountdown = false, isJustLiftMode = false)
            }
        }
    }

    /**
     * Cancel the auto-start countdown timer.
     * Called when user releases handles before countdown completes.
     */
    private fun cancelAutoStartTimer() {
        autoStartJob?.cancel()
        autoStartJob = null
        _autoStartCountdown.value = null
    }

    // ==================== AUTO-STOP FUNCTIONS ====================

    /**
     * Check if auto-stop should be triggered based on position and danger zone detection.
     * Called on every metric update during Just Lift mode.
     *
     * Auto-stop triggers when:
     * 1. Meaningful position range has been established (user has performed movements)
     * 2. Handles are in the danger zone (near minimum position)
     * 3. Handles appear to be released (velocity low or position matches rest)
     * 4. This condition persists for AUTO_STOP_DURATION_SECONDS
     */
    private fun checkAutoStop(metric: WorkoutMetric) {
        // Don't check if workout isn't active
        if (_workoutState.value !is WorkoutState.Active) {
            resetAutoStopTimer()
            return
        }

        // Need meaningful range to detect danger zone
        if (!repCounter.hasMeaningfulRange(MIN_RANGE_THRESHOLD)) {
            resetAutoStopTimer()
            return
        }

        val inDangerZone = repCounter.isInDangerZone(metric.positionA, metric.positionB, MIN_RANGE_THRESHOLD)
        val repRanges = repCounter.getRepRanges()

        // Check if cable appears to be released (position near minimum with low velocity)
        var cableAppearsReleased = false

        // Check cable A
        repRanges.minPosA?.let { minA ->
            repRanges.maxPosA?.let { maxA ->
                val rangeA = maxA - minA
                if (rangeA > MIN_RANGE_THRESHOLD) {
                    val thresholdA = minA + (rangeA * 0.05f).toInt()
                    val cableAInDanger = metric.positionA <= thresholdA
                    // Consider released if position is very close to minimum
                    val cableAReleased = (metric.positionA - minA) < 10 ||
                            kotlin.math.abs(metric.velocityA) < HANDLE_REST_THRESHOLD
                    if (cableAInDanger && cableAReleased) {
                        cableAppearsReleased = true
                    }
                }
            }
        }

        // Check cable B (if not already released)
        if (!cableAppearsReleased) {
            repRanges.minPosB?.let { minB ->
                repRanges.maxPosB?.let { maxB ->
                    val rangeB = maxB - minB
                    if (rangeB > MIN_RANGE_THRESHOLD) {
                        val thresholdB = minB + (rangeB * 0.05f).toInt()
                        val cableBInDanger = metric.positionB <= thresholdB
                        val cableBReleased = (metric.positionB - minB) < 10 ||
                                kotlin.math.abs(metric.velocityB) < HANDLE_REST_THRESHOLD
                        if (cableBInDanger && cableBReleased) {
                            cableAppearsReleased = true
                        }
                    }
                }
            }
        }

        // Trigger auto-stop countdown if in danger zone AND cable appears released
        if (inDangerZone && cableAppearsReleased) {
            val startTime = autoStopStartTime ?: run {
                autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f
            val progress = (elapsed / AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
            val remaining = (AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

            _autoStopState.value = AutoStopUiState(
                isActive = true,
                progress = progress,
                secondsRemaining = ceil(remaining).toInt()
            )

            // Trigger auto-stop if timer expired
            if (elapsed >= AUTO_STOP_DURATION_SECONDS && !autoStopTriggered) {
                requestAutoStop()
            }
        } else {
            // User resumed activity, reset timer
            resetAutoStopTimer()
        }
    }

    /**
     * Reset auto-stop timer without resetting the triggered flag.
     * Call this when user activity resumes (handles moved, etc.)
     */
    private fun resetAutoStopTimer() {
        autoStopStartTime = null
        if (!autoStopTriggered) {
            _autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Fully reset auto-stop state for a new workout/set.
     * Call this when starting a new workout or set.
     */
    private fun resetAutoStopState() {
        autoStopStartTime = null
        autoStopTriggered = false
        autoStopStopRequested = false
        _autoStopState.value = AutoStopUiState()
    }

    /**
     * Request auto-stop (thread-safe, only triggers once).
     */
    private fun requestAutoStop() {
        if (autoStopStopRequested) return
        autoStopStopRequested = true
        triggerAutoStop()
    }

    /**
     * Trigger auto-stop and handle set completion.
     */
    private fun triggerAutoStop() {
        Logger.d("triggerAutoStop() called")
        autoStopTriggered = true

        // Update UI state
        if (_workoutParameters.value.isJustLift || _workoutParameters.value.isAMRAP) {
            _autoStopState.value = _autoStopState.value.copy(
                progress = 1f,
                secondsRemaining = 0,
                isActive = true
            )
        } else {
            _autoStopState.value = AutoStopUiState()
        }

        // Handle set completion
        handleSetCompletion()
    }

    /**
     * Handle automatic set completion (when rep target is reached via auto-stop).
     * This is DIFFERENT from user manually stopping.
     */
    private fun handleSetCompletion() {
        viewModelScope.launch {
            val params = _workoutParameters.value
            val isJustLift = params.isJustLift

            Logger.d("handleSetCompletion: isJustLift=$isJustLift")

            // Stop hardware
            bleRepository.sendWorkoutCommand(BlePacketFactory.createStopCommand())
            _hapticEvents.emit(HapticEvent.WORKOUT_END)

            // Save session
            saveWorkoutSession()

            // Calculate metrics for summary
            val peakPerCableKg = if (collectedMetrics.isNotEmpty()) {
                collectedMetrics.maxOf { it.totalLoad } / 2f
            } else {
                params.weightPerCableKg
            }

            val averagePerCableKg = if (collectedMetrics.isNotEmpty()) {
                collectedMetrics.map { it.totalLoad / 2f }.average().toFloat()
            } else {
                params.weightPerCableKg
            }

            val completedReps = _repCount.value.workingReps

            // Show set summary
            _workoutState.value = WorkoutState.SetSummary(
                metrics = collectedMetrics.toList(),
                peakPower = peakPerCableKg,
                averagePower = averagePerCableKg,
                repCount = completedReps
            )

            Logger.d("Set summary: peakPerCableKg=$peakPerCableKg, avgPerCableKg=$averagePerCableKg, reps=$completedReps")

            // Handle based on workout mode
            if (isJustLift) {
                // Just Lift mode: Auto-advance to next set after showing summary
                repCounter.reset()
                resetAutoStopState()
                enableHandleDetection()

                delay(5000) // Show summary for 5 seconds

                if (_workoutState.value is WorkoutState.SetSummary) {
                    resetForNewWorkout()
                    _workoutState.value = WorkoutState.Idle
                }
            } else {
                // Routine/Program mode: Start rest timer
                delay(2000) // Brief summary display

                if (_workoutState.value is WorkoutState.SetSummary) {
                    repCounter.resetCountsOnly()
                    resetAutoStopState()
                    startRestTimer()
                }
            }
        }
    }

    /**
     * Save workout session to database and check for personal records.
     */
    private suspend fun saveWorkoutSession() {
        val sessionId = currentSessionId ?: return
        val params = _workoutParameters.value
        val warmup = _repCount.value.warmupReps
        val working = _repCount.value.workingReps
        val duration = currentTimeMillis() - workoutStartTime

        // Calculate actual measured weight from metrics (if available)
        val measuredPerCableKg = if (collectedMetrics.isNotEmpty()) {
            collectedMetrics.maxOf { it.totalLoad } / 2f
        } else {
            params.weightPerCableKg
        }

        val session = WorkoutSession(
            id = sessionId,
            timestamp = workoutStartTime,
            mode = params.workoutType.displayName,
            reps = params.reps,
            weightPerCableKg = measuredPerCableKg,
            progressionKg = params.progressionRegressionKg,
            duration = duration,
            totalReps = working,
            warmupReps = warmup,
            workingReps = working,
            isJustLift = params.isJustLift,
            stopAtTop = params.stopAtTop,
            exerciseId = params.selectedExerciseId,
            routineSessionId = currentRoutineSessionId,
            routineName = currentRoutineName
        )

        workoutRepository.saveSession(session)

        if (collectedMetrics.isNotEmpty()) {
            workoutRepository.saveMetrics(sessionId, collectedMetrics)
        }

        Logger.d("Saved workout session: $sessionId with ${collectedMetrics.size} metrics")

        // Check for personal record (skip for Just Lift and Echo modes)
        params.selectedExerciseId?.let { exerciseId ->
            val isEchoMode = params.workoutType is WorkoutType.Echo
            if (working > 0 && !params.isJustLift && !isEchoMode) {
                try {
                    workoutRepository.updatePRIfBetter(
                        exerciseId = exerciseId,
                        weightKg = measuredPerCableKg,
                        reps = working,
                        mode = params.workoutType.displayName
                    )

                    // Check if this was a new PR by querying existing records
                    // For now, emit celebration event optimistically for good performance
                    if (measuredPerCableKg >= params.weightPerCableKg && working >= params.reps) {
                        val exercise = exerciseRepository.getExerciseById(exerciseId)
                        _prCelebrationEvent.emit(
                            PRCelebrationEvent(
                                exerciseName = exercise?.name ?: "Unknown Exercise",
                                weightPerCableKg = measuredPerCableKg,
                                reps = working,
                                workoutMode = params.workoutType.displayName
                            )
                        )
                        Logger.d("Potential PR: ${exercise?.name} - $measuredPerCableKg kg x $working reps")
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error checking PR: ${e.message}" }
                }
            }
        }
    }

    /**
     * Check if current workout is in single exercise mode.
     */
    private fun isSingleExerciseMode(): Boolean {
        val routine = _loadedRoutine.value
        return routine == null || routine.id.startsWith(TEMP_SINGLE_EXERCISE_PREFIX)
    }

    /**
     * Check if the given exercise is a bodyweight exercise.
     */
    private fun isBodyweightExercise(exercise: RoutineExercise?): Boolean {
        return exercise?.let {
            val equipment = it.exercise.equipment
            equipment.isEmpty() || equipment.equals("bodyweight", ignoreCase = true)
        } ?: false
    }

    // ==================== REST TIMER & SET PROGRESSION ====================

    /**
     * Start the rest timer between sets.
     * Counts down and either auto-starts next set (if autoplay enabled) or waits for user.
     */
    private fun startRestTimer() {
        restTimerJob?.cancel()

        restTimerJob = viewModelScope.launch {
            val routine = _loadedRoutine.value
            val currentExercise = routine?.exercises?.getOrNull(_currentExerciseIndex.value)

            val completedSetIndex = _currentSetIndex.value
            val restDuration = currentExercise?.getRestForSet(completedSetIndex)?.takeIf { it > 0 } ?: 90
            val autoplay = autoplayEnabled.value

            val isSingleExercise = isSingleExerciseMode()

            // Countdown
            for (i in restDuration downTo 1) {
                val nextName = calculateNextExerciseName(isSingleExercise, currentExercise, routine)

                _workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = i,
                    nextExerciseName = nextName,
                    isLastExercise = calculateIsLastExercise(isSingleExercise, currentExercise, routine),
                    currentSet = _currentSetIndex.value + 1,
                    totalSets = currentExercise?.setReps?.size ?: 0
                )
                delay(1000)
            }

            if (autoplay) {
                if (isSingleExercise) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
            } else {
                // Stay in resting state with 0 seconds - user must manually start
                _workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = 0,
                    nextExerciseName = calculateNextExerciseName(isSingleExercise, currentExercise, routine),
                    isLastExercise = calculateIsLastExercise(isSingleExercise, currentExercise, routine),
                    currentSet = _currentSetIndex.value + 1,
                    totalSets = currentExercise?.setReps?.size ?: 0
                )
            }
        }
    }

    /**
     * Calculate the name of the next exercise/set for display during rest.
     */
    private fun calculateNextExerciseName(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): String {
        if (isSingleExercise || currentExercise == null) {
            return currentExercise?.exercise?.name ?: "Next Set"
        }

        // Check if more sets in current exercise
        if (_currentSetIndex.value < (currentExercise.setReps.size - 1)) {
            return "${currentExercise.exercise.name} - Set ${_currentSetIndex.value + 2}"
        }

        // Moving to next exercise
        val nextExercise = routine?.exercises?.getOrNull(_currentExerciseIndex.value + 1)
        return nextExercise?.exercise?.name ?: "Routine Complete"
    }

    /**
     * Check if current exercise is the last one in the routine.
     */
    private fun calculateIsLastExercise(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): Boolean {
        if (isSingleExercise) {
            // For single exercise, check if this is the last set
            return _currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
        }

        // Check if last exercise in routine
        val isLastExerciseInRoutine = _currentExerciseIndex.value >= (routine?.exercises?.size ?: 1) - 1
        val isLastSetInExercise = _currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1

        return isLastExerciseInRoutine && isLastSetInExercise
    }

    /**
     * Advance to the next set within a single exercise (non-routine mode).
     */
    private fun advanceToNextSetInSingleExercise() {
        val routine = _loadedRoutine.value ?: return
        val currentExercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return

        if (_currentSetIndex.value < currentExercise.setReps.size - 1) {
            _currentSetIndex.value++
            val targetReps = currentExercise.setReps[_currentSetIndex.value]
            val setWeight = currentExercise.setWeightsPerCableKg.getOrNull(_currentSetIndex.value)
                ?: currentExercise.weightPerCableKg

            _workoutParameters.value = _workoutParameters.value.copy(
                reps = targetReps ?: 0,
                weightPerCableKg = setWeight,
                isAMRAP = targetReps == null
            )

            repCounter.resetCountsOnly()
            resetAutoStopState()
            startWorkout(skipCountdown = true)
        } else {
            // All sets complete
            _workoutState.value = WorkoutState.Completed
            _loadedRoutine.value = null
            _currentSetIndex.value = 0
            _currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
        }
    }

    /**
     * Progress to the next set or exercise in a routine.
     */
    private fun startNextSetOrExercise() {
        val currentState = _workoutState.value
        if (currentState is WorkoutState.Completed) return
        if (currentState !is WorkoutState.Resting) return

        val routine = _loadedRoutine.value ?: return
        val currentExercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return

        if (_currentSetIndex.value < currentExercise.setReps.size - 1) {
            // More sets in current exercise
            _currentSetIndex.value++
            val targetReps = currentExercise.setReps[_currentSetIndex.value]
            val setWeight = currentExercise.setWeightsPerCableKg.getOrNull(_currentSetIndex.value)
                ?: currentExercise.weightPerCableKg

            _workoutParameters.value = _workoutParameters.value.copy(
                reps = targetReps ?: 0,
                weightPerCableKg = setWeight,
                isAMRAP = targetReps == null
            )

            repCounter.resetCountsOnly()
            resetAutoStopState()
            startWorkout(skipCountdown = true)
        } else {
            // Move to next exercise
            if (_currentExerciseIndex.value < routine.exercises.size - 1) {
                _currentExerciseIndex.value++
                _currentSetIndex.value = 0

                val nextExercise = routine.exercises[_currentExerciseIndex.value]
                val nextSetReps = nextExercise.setReps.getOrNull(0)
                val nextSetWeight = nextExercise.setWeightsPerCableKg.getOrNull(0)
                    ?: nextExercise.weightPerCableKg

                _workoutParameters.value = _workoutParameters.value.copy(
                    weightPerCableKg = nextSetWeight,
                    reps = nextSetReps ?: 0,
                    workoutType = nextExercise.workoutType,
                    progressionRegressionKg = nextExercise.progressionKg,
                    selectedExerciseId = nextExercise.exercise.id,
                    isAMRAP = nextSetReps == null
                )

                repCounter.reset()
                resetAutoStopState()
                startWorkout(skipCountdown = true)
            } else {
                // Routine complete
                _workoutState.value = WorkoutState.Completed
                _loadedRoutine.value = null
                _currentSetIndex.value = 0
                _currentExerciseIndex.value = 0
                currentRoutineSessionId = null
                currentRoutineName = null
                repCounter.reset()
                resetAutoStopState()
            }
        }
    }

    /**
     * Skip the current rest timer and immediately start the next set/exercise.
     */
    fun skipRest() {
        if (_workoutState.value is WorkoutState.Resting) {
            restTimerJob?.cancel()
            restTimerJob = null

            if (isSingleExerciseMode()) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }

    /**
     * Manually trigger starting the next set when autoplay is disabled.
     * Called from UI when user taps "Start Next Set" button.
     */
    fun startNextSet() {
        val state = _workoutState.value
        if (state is WorkoutState.Resting && state.restSecondsRemaining == 0) {
            if (isSingleExerciseMode()) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }
}

/**
 * Data class for storing Just Lift session defaults.
 */
data class JustLiftDefaults(
    val weightPerCableKg: Float,
    val weightChangePerRep: Int, // In display units (kg or lbs based on user preference)
    val workoutModeId: Int, // 0=OldSchool, 1=Pump, 2=Echo
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1 // 0=Hard, 1=Harder, 2=Hardest, 3=Epic
) {
    /**
     * Convert stored mode ID to WorkoutType
     */
    fun toWorkoutType(): WorkoutType = when (workoutModeId) {
        0 -> WorkoutType.Program(ProgramMode.OldSchool)
        1 -> WorkoutType.Program(ProgramMode.Pump)
        2 -> WorkoutType.Echo(
            level = EchoLevel.entries.getOrElse(echoLevelValue) { EchoLevel.HARDER },
            eccentricLoad = getEccentricLoad()
        )
        else -> WorkoutType.Program(ProgramMode.OldSchool)
    }

    /**
     * Get EccentricLoad from stored percentage
     */
    fun getEccentricLoad(): EccentricLoad = when (eccentricLoadPercentage) {
        0 -> EccentricLoad.LOAD_0
        50 -> EccentricLoad.LOAD_50
        75 -> EccentricLoad.LOAD_75
        100 -> EccentricLoad.LOAD_100
        125 -> EccentricLoad.LOAD_125
        150 -> EccentricLoad.LOAD_150
        else -> EccentricLoad.LOAD_100
    }

    /**
     * Get EchoLevel from stored value
     */
    fun getEchoLevel(): EchoLevel = EchoLevel.entries.getOrElse(echoLevelValue) { EchoLevel.HARDER }
}

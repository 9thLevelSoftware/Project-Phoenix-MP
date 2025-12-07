package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.UserPreferences
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val preferencesManager: PreferencesManager,
    private val gamificationRepository: GamificationRepository
) : ViewModel() {

    companion object {
        /** Prefix for temporary single exercise routines to identify them for cleanup */
        const val TEMP_SINGLE_EXERCISE_PREFIX = "temp_single_"

        /** Position-based auto-stop duration in seconds (handles in danger zone and released) */
        const val AUTO_STOP_DURATION_SECONDS = 2.5f

        /** Velocity-based stall detection duration in seconds (Issue #204, #214) */
        const val STALL_DURATION_SECONDS = 5.0f

        /**
         * Two-tier velocity hysteresis for stall detection (Issue #204, #216)
         * Matches official app behavior to prevent timer toggling near threshold:
         * - Below LOW (<2.5): start/continue stall timer (user is stopped)
         * - Above HIGH (>10): reset stall timer (user is clearly moving)
         * - Between LOW and HIGH (≥2.5 and ≤10): maintain current state (hysteresis band)
         */
        const val STALL_VELOCITY_LOW = 2.5    // Below this = definitely stalled (mm/s)
        const val STALL_VELOCITY_HIGH = 10.0  // Above this = definitely moving (mm/s)

        /** Minimum position to consider handles "in use" for stall detection (mm) */
        const val STALL_MIN_POSITION = 10.0

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

    // Badge Earned Events
    private val _badgeEarnedEvents = MutableSharedFlow<List<Badge>>()
    val badgeEarnedEvents: SharedFlow<List<Badge>> = _badgeEarnedEvents.asSharedFlow()

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

    fun getRoutineById(routineId: String): Routine? {
        return routines.value.find { it.id == routineId }
    }

    private val _currentExerciseIndex = MutableStateFlow(0)
    val currentExerciseIndex: StateFlow<Int> = _currentExerciseIndex.asStateFlow()

    private val _currentSetIndex = MutableStateFlow(0)
    val currentSetIndex: StateFlow<Int> = _currentSetIndex.asStateFlow()

    // Track skipped and completed exercise indices for routine navigation
    private val _skippedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val skippedExercises: StateFlow<Set<Int>> = _skippedExercises.asStateFlow()

    private val _completedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val completedExercises: StateFlow<Set<Int>> = _completedExercises.asStateFlow()

    // RPE tracking for current set (Phase 2: Training Cycles)
    private val _currentSetRpe = MutableStateFlow<Int?>(null)
    val currentSetRpe: StateFlow<Int?> = _currentSetRpe.asStateFlow()

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

    // Velocity-based stall detection state (Issue #204, #214)
    private var stallStartTime: Long? = null
    private var isCurrentlyStalled = false

    private var connectionJob: Job? = null
    private var monitorDataCollectionJob: Job? = null
    private var autoStartJob: Job? = null
    private var restTimerJob: Job? = null
    private var bodyweightTimerJob: Job? = null
    private var repEventsCollectionJob: Job? = null

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

        // Handle activity state collector for auto-start functionality
        // Uses 4-state machine from BLE repo (matches parent repo v0.5.1-beta):
        // WaitingForRest -> SetComplete (armed) -> Moving (intermediate) -> Active (grabbed with velocity)
        viewModelScope.launch {
            bleRepository.handleActivityState.collect { activityState ->
                val params = _workoutParameters.value
                val currentState = _workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Auto-start logic: when handles transition to Active state
                // Active = position > 8mm AND velocity > 100mm/s (matches parent repo)
                if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                    when (activityState) {
                        HandleActivityState.Active -> {
                            startAutoStartTimer()
                        }
                        HandleActivityState.Moving -> {
                            // Moving = position extended but no velocity yet
                            // Don't start countdown yet, but also don't cancel if already running
                            // This allows user to slowly pick up handles without false trigger
                        }
                        HandleActivityState.SetComplete, HandleActivityState.WaitingForRest -> {
                            cancelAutoStartTimer()
                        }
                    }
                }

                // Track handle activity state for UI
                currentHandleState = activityState
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
        // Position values are in mm (Issue #197)
        repCounter.process(
            repsRomCount = notification.repsRomCount,
            repsSetCount = notification.repsSetCount,
            up = notification.topCounter,
            down = notification.completeCounter,
            posA = currentPositions?.positionA ?: 0f,
            posB = currentPositions?.positionB ?: 0f
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

            // 1. Build Command - Use full 96-byte PROGRAM params (matches parent repo)
            val command = when (val workoutType = params.workoutType) {
                is WorkoutType.Program -> {
                    // Full 96-byte program frame with mode profile, weight, progression
                    BlePacketFactory.createProgramParams(params)
                }
                is WorkoutType.Echo -> {
                    // 32-byte Echo control frame
                    BlePacketFactory.createEchoControl(
                        level = workoutType.level,
                        warmupReps = params.warmupReps,
                        targetReps = params.reps,
                        isJustLift = isJustLiftMode || params.isJustLift,
                        isAMRAP = params.isAMRAP,
                        eccentricPct = workoutType.eccentricLoad.percentage
                    )
                }
            }
            Logger.d { "Built ${command.size}-byte workout command for ${params.workoutType}" }

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
             val isJustLift = params.isJustLift

             // CRITICAL: Just Lift mode - immediately restart polling to clear machine fault state
             // The machine needs active polling to process the stop command and reset quickly.
             // Without this, the machine stays in fault state (red lights) until polling resumes.
             if (isJustLift) {
                 Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                 bleRepository.restartMonitorPolling()
             }

             val session = WorkoutSession(
                 timestamp = workoutStartTime,
                 mode = params.workoutType.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - workoutStartTime,
                 isJustLift = isJustLift
             )
             workoutRepository.saveSession(session)

             // Show Summary
             val metrics = collectedMetrics.toList()
             val isEcho = params.workoutType is WorkoutType.Echo
             val summary = calculateSetSummaryMetrics(
                 metrics = metrics,
                 repCount = repCount.totalReps,
                 fallbackWeightKg = params.weightPerCableKg,
                 isEchoMode = isEcho,
                 warmupRepsCount = repCount.warmupReps,
                 workingRepsCount = repCount.workingReps
             )
             _workoutState.value = summary
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
        // Clear RPE for next set
        _currentSetRpe.value = null
    }

    /**
     * Log RPE (Rate of Perceived Exertion) for the current set.
     * Called from the SetSummary screen when user logs their perceived effort.
     * RPE value is stored and will be saved with the completed set data.
     */
    fun logRpeForCurrentSet(rpe: Int) {
        _currentSetRpe.value = rpe
        Logger.d("MainViewModel") { "RPE logged for current set: $rpe" }
    }

    fun resetForNewWorkout() {
        _workoutState.value = WorkoutState.Idle
        _repCount.value = RepCount()
    }

    fun advanceToNextExercise() {
        val routine = _loadedRoutine.value ?: return
        val nextIndex = _currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Navigate to a specific exercise in the routine.
     * Saves progress of current exercise if any reps completed.
     */
    fun jumpToExercise(index: Int) {
        val routine = _loadedRoutine.value ?: return
        if (index < 0 || index >= routine.exercises.size) return

        // Save current exercise progress if we have any reps
        val currentRepCount = _repCount.value
        if (currentRepCount.workingReps > 0 && _workoutState.value !is WorkoutState.Completed) {
            // Mark as completed if we did some work
            _completedExercises.update { it + _currentExerciseIndex.value }
            Logger.d("MainViewModel") { "Saving progress for exercise ${_currentExerciseIndex.value}: ${currentRepCount.workingReps} reps" }
        } else if (_workoutState.value !is WorkoutState.Completed) {
            // Mark as skipped if no reps done
            _skippedExercises.update { it + _currentExerciseIndex.value }
            Logger.d("MainViewModel") { "Skipping exercise ${_currentExerciseIndex.value}" }
        }

        // Cancel any active timers
        restTimerJob?.cancel()
        bodyweightTimerJob?.cancel()
        resetAutoStopState()

        // Navigate to new exercise
        _currentExerciseIndex.value = index
        _currentSetIndex.value = 0

        // Load new exercise parameters
        val exercise = routine.exercises[index]
        val setReps = exercise.setReps.getOrNull(0)
        val setWeight = exercise.setWeightsPerCableKg.getOrNull(0) ?: exercise.weightPerCableKg

        _workoutParameters.update { params ->
            params.copy(
                workoutType = exercise.workoutType,
                reps = setReps ?: exercise.reps,
                weightPerCableKg = setWeight,
                progressionRegressionKg = exercise.progressionKg,
                warmupReps = 0,  // Routines don't track warmup reps per exercise
                selectedExerciseId = exercise.exercise.id
            )
        }

        // Reset workout state
        _workoutState.value = WorkoutState.Idle
        _repCount.value = RepCount()
        repCounter.reset()

        Logger.i("MainViewModel") { "Jumped to exercise $index: ${exercise.exercise.name}" }
    }

    /**
     * Skip the current exercise and move to the next one.
     */
    fun skipCurrentExercise() {
        val routine = _loadedRoutine.value ?: return
        val nextIndex = _currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            // Mark current as skipped (even if we had some progress)
            _skippedExercises.update { it + _currentExerciseIndex.value }
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Go back to the previous exercise in the routine.
     */
    fun goToPreviousExercise() {
        val prevIndex = _currentExerciseIndex.value - 1
        if (prevIndex >= 0) {
            jumpToExercise(prevIndex)
        }
    }

    /**
     * Check if current exercise can go back (not first in routine).
     */
    fun canGoBack(): Boolean {
        return _loadedRoutine.value != null && _currentExerciseIndex.value > 0
    }

    /**
     * Check if current exercise can skip forward (not last in routine).
     */
    fun canSkipForward(): Boolean {
        val routine = _loadedRoutine.value ?: return false
        return _currentExerciseIndex.value < routine.exercises.size - 1
    }

    /**
     * Get list of exercise names in current routine (for navigation display).
     */
    fun getRoutineExerciseNames(): List<String> {
        return _loadedRoutine.value?.exercises?.map { it.exercise.name } ?: emptyList()
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
        _skippedExercises.value = emptySet()
        _completedExercises.value = emptySet()
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

    // ========== Superset Support ==========

    /**
     * Get all exercises in the same superset as the current exercise.
     * Returns empty list if current exercise is not in a superset.
     */
    private fun getCurrentSupersetExercises(): List<RoutineExercise> {
        val routine = _loadedRoutine.value ?: return emptyList()
        val currentExercise = getCurrentExercise() ?: return emptyList()
        val supersetId = currentExercise.supersetGroupId ?: return emptyList()

        return routine.exercises
            .filter { it.supersetGroupId == supersetId }
            .sortedBy { it.supersetOrder }
    }

    /**
     * Check if the current exercise is part of a superset.
     */
    private fun isInSuperset(): Boolean {
        return getCurrentExercise()?.supersetGroupId != null
    }

    /**
     * Get the next exercise index in the superset rotation.
     * Returns null if we've completed the superset cycle for the current set.
     */
    private fun getNextSupersetExerciseIndex(): Int? {
        val routine = _loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val supersetId = currentExercise.supersetGroupId ?: return null

        val supersetExercises = getCurrentSupersetExercises()
        val currentPositionInSuperset = supersetExercises.indexOf(currentExercise)

        if (currentPositionInSuperset < supersetExercises.size - 1) {
            // More exercises in this superset cycle
            val nextSupersetExercise = supersetExercises[currentPositionInSuperset + 1]
            return routine.exercises.indexOf(nextSupersetExercise)
        }

        return null // End of superset cycle
    }

    /**
     * Get the first exercise in the current superset.
     */
    private fun getFirstSupersetExerciseIndex(): Int? {
        val routine = _loadedRoutine.value ?: return null
        val supersetExercises = getCurrentSupersetExercises()
        if (supersetExercises.isEmpty()) return null

        return routine.exercises.indexOf(supersetExercises.first())
    }

    /**
     * Check if we're at the end of a superset cycle (last exercise in superset for current set).
     */
    private fun isAtEndOfSupersetCycle(): Boolean {
        val currentExercise = getCurrentExercise() ?: return false
        if (currentExercise.supersetGroupId == null) return false

        val supersetExercises = getCurrentSupersetExercises()
        return currentExercise == supersetExercises.lastOrNull()
    }

    /**
     * Get the superset rest time (short rest between superset exercises).
     */
    private fun getSupersetRestSeconds(): Int {
        return getCurrentExercise()?.supersetRestSeconds ?: 10
    }

    /**
     * Find the next exercise after the current one (or after the current superset).
     * Skips over exercises that are part of the current superset.
     */
    private fun findNextExerciseAfterCurrent(): Int? {
        val routine = _loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val currentSupersetId = currentExercise.supersetGroupId

        // If in a superset, find the first exercise after the superset
        if (currentSupersetId != null) {
            val supersetExerciseIndices = routine.exercises
                .mapIndexedNotNull { index, ex ->
                    if (ex.supersetGroupId == currentSupersetId) index else null
                }
            val lastSupersetIndex = supersetExerciseIndices.maxOrNull() ?: _currentExerciseIndex.value
            val nextIndex = lastSupersetIndex + 1
            return if (nextIndex < routine.exercises.size) nextIndex else null
        }

        // Not in a superset - just go to next index
        val nextIndex = _currentExerciseIndex.value + 1
        return if (nextIndex < routine.exercises.size) nextIndex else null
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

    // ========== Weight Adjustment Functions ==========

    /**
     * Adjust the weight during an active workout or rest period.
     * This updates the workout parameters and optionally sends the updated weight to the machine.
     *
     * @param newWeightKg The new weight per cable in kg
     * @param sendToMachine Whether to send the command to the machine immediately
     */
    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) {
        val clampedWeight = newWeightKg.coerceIn(0f, 110f) // Max 110kg per cable (220kg total)

        Logger.d("MainViewModel: Adjusting weight to $clampedWeight kg (sendToMachine=$sendToMachine)")

        // Update workout parameters
        _workoutParameters.update { params ->
            params.copy(weightPerCableKg = clampedWeight)
        }

        // If workout is active, send updated weight to machine
        if (sendToMachine && _workoutState.value is WorkoutState.Active) {
            viewModelScope.launch {
                sendWeightUpdateToMachine(clampedWeight)
            }
        }
    }

    /**
     * Increment weight by a specific amount.
     */
    fun incrementWeight(amount: Float = 0.5f) {
        val currentWeight = _workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight + amount)
    }

    /**
     * Decrement weight by a specific amount.
     */
    fun decrementWeight(amount: Float = 0.5f) {
        val currentWeight = _workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight - amount)
    }

    /**
     * Set weight to a specific preset value.
     */
    fun setWeightPreset(presetWeightKg: Float) {
        adjustWeight(presetWeightKg)
    }

    /**
     * Get the last used weight for a specific exercise.
     */
    suspend fun getLastWeightForExercise(exerciseId: String): Float? {
        return workoutRepository.getAllSessions()
            .first()
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            ?.weightPerCableKg
    }

    /**
     * Get the PR weight for a specific exercise.
     */
    suspend fun getPrWeightForExercise(exerciseId: String): Float? {
        return workoutRepository.getAllPersonalRecords()
            .first()
            .filter { it.exerciseId == exerciseId }
            .maxOfOrNull { it.weightPerCableKg }
    }

    /**
     * Send weight update command to the machine.
     * This resends the workout command with updated weight.
     */
    private suspend fun sendWeightUpdateToMachine(weightKg: Float) {
        try {
            val params = _workoutParameters.value

            // Create and send updated workout command
            val command = if (params.workoutType is WorkoutType.Program) {
                BlePacketFactory.createWorkoutCommand(
                    params.workoutType,
                    weightKg,
                    params.reps
                )
            } else {
                // For Echo mode, weight is dynamic based on position - no update needed
                return
            }

            bleRepository.sendWorkoutCommand(command)
            Logger.d("Weight update sent to machine: $weightKg kg")
        } catch (e: Exception) {
            Logger.e(e) { "Failed to send weight update: ${e.message}" }
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
     * Check if auto-stop should be triggered based on velocity stall detection OR position-based detection.
     * Called on every metric update during workout.
     *
     * Two detection methods (Issue #204, #214):
     *
     * 1. VELOCITY-BASED STALL DETECTION (primary):
     *    - Triggers when velocity < 25 mm/s for 5 seconds while handles are in use
     *    - Prevents false triggers during controlled eccentric movements
     *
     * 2. POSITION-BASED DETECTION (secondary):
     *    - Triggers when handles in danger zone AND appear released for 2.5 seconds
     *    - Original logic kept as safety backup
     */
    private fun checkAutoStop(metric: WorkoutMetric) {
        // Don't check if workout isn't active
        if (_workoutState.value !is WorkoutState.Active) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        val hasMeaningfulRange = repCounter.hasMeaningfulRange(MIN_RANGE_THRESHOLD)

        // ===== 1. VELOCITY-BASED STALL DETECTION (Issue #204, #214, #216) =====
        // Two-tier hysteresis matching official app (<2.5 stalled, >10 moving):
        // - Below LOW threshold (<2.5): start/continue stall timer
        // - Above HIGH threshold (>10): reset stall timer (clear movement)
        // - Between LOW and HIGH (≥2.5 and ≤10): maintain current state (prevents toggling)

        // Get max velocity (use absolute values for comparison)
        val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
        val isDefinitelyStalled = maxVelocity < STALL_VELOCITY_LOW
        val isDefinitelyMoving = maxVelocity > STALL_VELOCITY_HIGH

        // Check if handles are actively being used (position > 10mm OR meaningful range achieved)
        val maxPosition = maxOf(metric.positionA, metric.positionB)
        val isActivelyUsing = maxPosition > STALL_MIN_POSITION || hasMeaningfulRange

        // Hysteresis state machine:
        // - Definitely stalled (< LOW): start timer if not already running
        // - Definitely moving (> HIGH): reset timer
        // - Hysteresis band (LOW to HIGH): maintain current state, keep timer running if active
        if (isDefinitelyStalled && isActivelyUsing && stallStartTime == null) {
            // Velocity below LOW threshold - start stall timer
            stallStartTime = currentTimeMillis()
            isCurrentlyStalled = true
        } else if (isDefinitelyMoving && stallStartTime != null) {
            // Velocity above HIGH threshold - clear movement detected, reset timer
            resetStallTimer()
        }
        // else: velocity in hysteresis band (2.5-10.0) - maintain current timer state

        // If timer is running (regardless of current velocity zone), check progress and update UI
        val startTime = stallStartTime
        if (startTime != null) {
            val stallElapsed = (currentTimeMillis() - startTime) / 1000f

            // Trigger auto-stop after 5 seconds of no movement
            if (stallElapsed >= STALL_DURATION_SECONDS && !autoStopTriggered) {
                requestAutoStop()
                return
            }

            // Update UI with stall progress (always update when timer is active)
            if (stallElapsed >= 1.0f) { // Only show after 1 second of stall
                val progress = (stallElapsed / STALL_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (STALL_DURATION_SECONDS - stallElapsed).coerceAtLeast(0f)

                _autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }
        }

        // ===== 2. POSITION-BASED DETECTION (secondary/backup) =====
        // Only check if we have meaningful range established
        if (!hasMeaningfulRange) {
            resetAutoStopTimer()
            return
        }

        val inDangerZone = repCounter.isInDangerZone(metric.positionA, metric.positionB, MIN_RANGE_THRESHOLD)
        val repRanges = repCounter.getRepRanges()

        // Check if cable appears to be released (position at rest OR near minimum)
        var cableAppearsReleased = false

        // Check cable A
        repRanges.minPosA?.let { minA ->
            repRanges.maxPosA?.let { maxA ->
                val rangeA = maxA - minA
                if (rangeA > MIN_RANGE_THRESHOLD) {
                    val thresholdA = minA + (rangeA * 0.05f).toInt()
                    val cableAInDanger = metric.positionA <= thresholdA
                    val cableAReleased = metric.positionA < HANDLE_REST_THRESHOLD ||
                            (metric.positionA - minA) < 10
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
                        val cableBReleased = metric.positionB < HANDLE_REST_THRESHOLD ||
                                (metric.positionB - minB) < 10
                        if (cableBInDanger && cableBReleased) {
                            cableAppearsReleased = true
                        }
                    }
                }
            }
        }

        // Trigger position-based auto-stop countdown if in danger zone AND cable appears released
        if (inDangerZone && cableAppearsReleased) {
            val startTime = autoStopStartTime ?: run {
                autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            // Only update UI if stall detection isn't already showing (stall takes priority)
            if (!isCurrentlyStalled) {
                val progress = (elapsed / AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                _autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }

            // Trigger auto-stop if timer expired
            if (elapsed >= AUTO_STOP_DURATION_SECONDS && !autoStopTriggered) {
                requestAutoStop()
            }
        } else {
            // User resumed activity, reset position-based timer
            resetAutoStopTimer()
        }
    }

    /**
     * Reset auto-stop timer without resetting the triggered flag.
     * Call this when user activity resumes (handles moved, etc.)
     */
    private fun resetAutoStopTimer() {
        autoStopStartTime = null
        if (!autoStopTriggered && !isCurrentlyStalled) {
            _autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Reset stall detection timer.
     * Call this when movement is detected.
     */
    private fun resetStallTimer() {
        stallStartTime = null
        isCurrentlyStalled = false
        // Only reset UI if position-based detection isn't active
        if (autoStopStartTime == null && !autoStopTriggered) {
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
        stallStartTime = null
        isCurrentlyStalled = false
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
            val completedReps = _repCount.value.workingReps
            val warmupReps = _repCount.value.warmupReps
            val metricsList = collectedMetrics.toList()

            // Calculate enhanced metrics for summary
            val isEcho = params.workoutType is WorkoutType.Echo
            val summary = calculateSetSummaryMetrics(
                metrics = metricsList,
                repCount = completedReps,
                fallbackWeightKg = params.weightPerCableKg,
                isEchoMode = isEcho,
                warmupRepsCount = warmupReps,
                workingRepsCount = completedReps
            )

            // Show set summary
            _workoutState.value = summary

            Logger.d("Set summary: heaviest=${summary.heaviestLiftKgPerCable}kg, reps=$completedReps, duration=${summary.durationMs}ms")

            // Handle based on workout mode
            if (isJustLift) {
                // Just Lift mode: Auto-advance to next set after showing summary
                repCounter.reset()
                resetAutoStopState()

                // Enable handle detection AND Just Lift waiting mode for next set
                // This arms the state machine to detect when user grabs handles again
                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()
                Logger.d("Just Lift: Enabled waiting mode for next auto-start")

                delay(5000) // Show summary for 5 seconds

                if (_workoutState.value is WorkoutState.SetSummary) {
                    resetForNewWorkout()
                    _workoutState.value = WorkoutState.Idle
                }
            } else if (params.isAMRAP) {
                // AMRAP mode: Auto-advance to rest timer and next set (like Just Lift)
                // Parent repo Issue #208 - enhance AMRAP mode to auto-advance
                Logger.d("AMRAP: Auto-advancing to rest timer")

                // Reset logical state for next set
                repCounter.reset()
                resetAutoStopState()

                // Restart monitor polling to clear machine fault state (red lights)
                bleRepository.restartMonitorPolling()

                // Enable handle detection for auto-start during rest
                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("AMRAP: Machine armed & ready. Showing summary for 5s...")

                delay(5000) // Show summary for 5 seconds

                // Auto-start rest timer if we haven't already started a new set
                if (_workoutState.value is WorkoutState.SetSummary) {
                    startRestTimer()
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

        // Update gamification stats and check for badges
        try {
            gamificationRepository.updateStats()
            val newBadges = gamificationRepository.checkAndAwardBadges()
            if (newBadges.isNotEmpty()) {
                _badgeEarnedEvents.emit(newBadges)
                Logger.d("New badges earned: ${newBadges.map { it.name }}")
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating gamification: ${e.message}" }
        }
    }

    /**
     * Calculate enhanced metrics for the set summary display.
     * Separates concentric (lifting) vs eccentric (lowering) phases based on velocity.
     * For Echo mode, also calculates phase-aware metrics (warmup, working, burnout).
     */
    private fun calculateSetSummaryMetrics(
        metrics: List<WorkoutMetric>,
        repCount: Int,
        fallbackWeightKg: Float,
        isEchoMode: Boolean = false,
        warmupRepsCount: Int = 0,
        workingRepsCount: Int = 0
    ): WorkoutState.SetSummary {
        if (metrics.isEmpty()) {
            return WorkoutState.SetSummary(
                metrics = metrics,
                peakPower = 0f,
                averagePower = 0f,
                repCount = repCount,
                heaviestLiftKgPerCable = fallbackWeightKg
            )
        }

        // Duration from first to last metric
        val durationMs = metrics.last().timestamp - metrics.first().timestamp

        // Heaviest lift (max load per cable)
        val heaviestLiftKgPerCable = metrics.maxOf { maxOf(it.loadA, it.loadB) }

        // Total volume = average load × reps
        val avgTotalLoad = metrics.map { it.totalLoad }.average().toFloat()
        val totalVolumeKg = avgTotalLoad * repCount

        // Separate concentric (velocity > 0) and eccentric (velocity < 0) phases
        val concentricMetrics = metrics.filter { it.velocityA > 10 || it.velocityB > 10 }
        val eccentricMetrics = metrics.filter { it.velocityA < -10 || it.velocityB < -10 }

        // Peak forces per phase
        val peakConcentricA = concentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakConcentricB = concentricMetrics.maxOfOrNull { it.loadB } ?: 0f
        val peakEccentricA = eccentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakEccentricB = eccentricMetrics.maxOfOrNull { it.loadB } ?: 0f

        // Average forces per phase
        val avgConcentricA = if (concentricMetrics.isNotEmpty())
            concentricMetrics.map { it.loadA }.average().toFloat() else 0f
        val avgConcentricB = if (concentricMetrics.isNotEmpty())
            concentricMetrics.map { it.loadB }.average().toFloat() else 0f
        val avgEccentricA = if (eccentricMetrics.isNotEmpty())
            eccentricMetrics.map { it.loadA }.average().toFloat() else 0f
        val avgEccentricB = if (eccentricMetrics.isNotEmpty())
            eccentricMetrics.map { it.loadB }.average().toFloat() else 0f

        // Estimate calories: Work = Force × Distance, roughly 4.184 J per calorie
        // Simplified estimate: totalVolume (kg) × reps × 0.5m ROM × 9.81 / 4184
        val estimatedCalories = (totalVolumeKg * 0.5f * 9.81f / 4184f).coerceAtLeast(1f)

        // Legacy power values (average load per cable)
        val peakPower = heaviestLiftKgPerCable
        val averagePower = metrics.map { it.totalLoad / 2f }.average().toFloat()

        // Echo Mode Phase-Aware Metrics
        var warmupAvgWeightKg = 0f
        var workingAvgWeightKg = 0f
        var burnoutAvgWeightKg = 0f
        var peakWeightKg = 0f
        var burnoutReps = 0

        if (isEchoMode && metrics.size > 10) {
            // Detect phases by analyzing weight progression
            // Echo mode: weight increases (warmup) -> stabilizes (working) -> decreases (burnout)
            val weightSamples = metrics.map { maxOf(it.loadA, it.loadB) }
            peakWeightKg = weightSamples.maxOrNull() ?: 0f
            val peakThreshold = peakWeightKg * 0.9f  // Within 90% of peak is "working" phase

            // Find peak indices
            val peakIndices = weightSamples.indices.filter { weightSamples[it] >= peakThreshold }

            if (peakIndices.isNotEmpty()) {
                val firstPeakIndex = peakIndices.first()
                val lastPeakIndex = peakIndices.last()

                // Warmup phase: samples before first peak
                val warmupSamples = weightSamples.take(firstPeakIndex)
                warmupAvgWeightKg = if (warmupSamples.isNotEmpty())
                    warmupSamples.average().toFloat() else 0f

                // Working phase: samples around peak
                val workingSamples = weightSamples.subList(firstPeakIndex, (lastPeakIndex + 1).coerceAtMost(weightSamples.size))
                workingAvgWeightKg = if (workingSamples.isNotEmpty())
                    workingSamples.average().toFloat() else peakWeightKg

                // Burnout phase: samples after last peak
                val burnoutSamples = if (lastPeakIndex < weightSamples.lastIndex)
                    weightSamples.drop(lastPeakIndex + 1) else emptyList()
                burnoutAvgWeightKg = if (burnoutSamples.isNotEmpty())
                    burnoutSamples.average().toFloat() else 0f

                // Estimate burnout reps based on weight decline pattern
                // Total reps = warmup + working + burnout
                val totalReps = warmupRepsCount + workingRepsCount
                if (burnoutSamples.isNotEmpty() && totalReps > 0) {
                    // Estimate burnout reps proportionally based on samples
                    val burnoutRatio = burnoutSamples.size.toFloat() / weightSamples.size.toFloat()
                    burnoutReps = (totalReps * burnoutRatio).toInt().coerceAtLeast(0)
                }
            } else {
                // No clear peak - treat all as working phase
                workingAvgWeightKg = weightSamples.average().toFloat()
                peakWeightKg = workingAvgWeightKg
            }
        }

        return WorkoutState.SetSummary(
            metrics = metrics,
            peakPower = peakPower,
            averagePower = averagePower,
            repCount = repCount,
            durationMs = durationMs,
            totalVolumeKg = totalVolumeKg,
            heaviestLiftKgPerCable = heaviestLiftKgPerCable,
            peakForceConcentricA = peakConcentricA,
            peakForceConcentricB = peakConcentricB,
            peakForceEccentricA = peakEccentricA,
            peakForceEccentricB = peakEccentricB,
            avgForceConcentricA = avgConcentricA,
            avgForceConcentricB = avgConcentricB,
            avgForceEccentricA = avgEccentricA,
            avgForceEccentricB = avgEccentricB,
            estimatedCalories = estimatedCalories,
            // Echo Mode Phase Metrics
            isEchoMode = isEchoMode,
            warmupReps = warmupRepsCount,
            workingReps = workingRepsCount,
            burnoutReps = burnoutReps,
            warmupAvgWeightKg = warmupAvgWeightKg,
            workingAvgWeightKg = workingAvgWeightKg,
            burnoutAvgWeightKg = burnoutAvgWeightKg,
            peakWeightKg = peakWeightKg
        )
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

            // Load preset weights for the current exercise
            val exerciseId = currentExercise?.exercise?.id ?: _workoutParameters.value.selectedExerciseId
            if (exerciseId != null) {
                val lastWeight = getLastWeightForExercise(exerciseId)
                val prWeight = getPrWeightForExercise(exerciseId)
                _workoutParameters.value = _workoutParameters.value.copy(
                    lastUsedWeightKg = lastWeight,
                    prWeightKg = prWeight
                )
            }

            val completedSetIndex = _currentSetIndex.value

            // Determine rest duration:
            // - If in a superset and NOT at the end of the cycle, use short superset rest
            // - Otherwise, use the normal per-set rest time
            val isInSupersetTransition = isInSuperset() && !isAtEndOfSupersetCycle()
            val restDuration = if (isInSupersetTransition) {
                getSupersetRestSeconds().coerceAtLeast(5) // Min 5s for superset transitions
            } else {
                currentExercise?.getRestForSet(completedSetIndex)?.takeIf { it > 0 } ?: 90
            }
            val autoplay = autoplayEnabled.value

            val isSingleExercise = isSingleExerciseMode()

            // Determine superset label for display
            val supersetLabel = if (isInSupersetTransition) {
                val supersetExercises = getCurrentSupersetExercises()
                val supersetGroupIds = routine?.getSupersetGroupIds()?.toList() ?: emptyList()
                val groupIndex = supersetGroupIds.indexOf(currentExercise?.supersetGroupId)
                if (groupIndex >= 0) "Superset ${('A' + groupIndex)}" else "Superset"
            } else null

            // Countdown
            for (i in restDuration downTo 1) {
                val nextName = calculateNextExerciseName(isSingleExercise, currentExercise, routine)

                _workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = i,
                    nextExerciseName = nextName,
                    isLastExercise = calculateIsLastExercise(isSingleExercise, currentExercise, routine),
                    currentSet = _currentSetIndex.value + 1,
                    totalSets = currentExercise?.setReps?.size ?: 0,
                    isSupersetTransition = isInSupersetTransition,
                    supersetLabel = supersetLabel
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
                    totalSets = currentExercise?.setReps?.size ?: 0,
                    isSupersetTransition = isInSupersetTransition,
                    supersetLabel = supersetLabel
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
     * Handles superset progression: cycles through superset exercises before advancing sets.
     */
    private fun startNextSetOrExercise() {
        val currentState = _workoutState.value
        if (currentState is WorkoutState.Completed) return
        if (currentState !is WorkoutState.Resting) return

        val routine = _loadedRoutine.value ?: return
        val currentExercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return

        // Check for superset progression
        if (isInSuperset()) {
            val nextSupersetIndex = getNextSupersetExerciseIndex()

            if (nextSupersetIndex != null) {
                // More exercises in this superset cycle - move to next superset exercise (same set)
                _currentExerciseIndex.value = nextSupersetIndex
                // Don't change set index - we're cycling through the superset

                val nextExercise = routine.exercises[nextSupersetIndex]
                val nextSetReps = nextExercise.setReps.getOrNull(_currentSetIndex.value)
                val nextSetWeight = nextExercise.setWeightsPerCableKg.getOrNull(_currentSetIndex.value)
                    ?: nextExercise.weightPerCableKg

                _workoutParameters.value = _workoutParameters.value.copy(
                    weightPerCableKg = nextSetWeight,
                    reps = nextSetReps ?: 0,
                    workoutType = nextExercise.workoutType,
                    progressionRegressionKg = nextExercise.progressionKg,
                    selectedExerciseId = nextExercise.exercise.id,
                    isAMRAP = nextSetReps == null
                )

                repCounter.resetCountsOnly()
                resetAutoStopState()
                startWorkout(skipCountdown = true)
                return
            } else {
                // End of superset cycle - check if more sets in superset
                val supersetExercises = getCurrentSupersetExercises()
                val minSetsInSuperset = supersetExercises.minOfOrNull { it.setReps.size } ?: 0

                if (_currentSetIndex.value < minSetsInSuperset - 1) {
                    // More sets - go back to first exercise in superset
                    val firstSupersetIndex = getFirstSupersetExerciseIndex() ?: return
                    _currentSetIndex.value++
                    _currentExerciseIndex.value = firstSupersetIndex

                    val nextExercise = routine.exercises[firstSupersetIndex]
                    val nextSetReps = nextExercise.setReps.getOrNull(_currentSetIndex.value)
                    val nextSetWeight = nextExercise.setWeightsPerCableKg.getOrNull(_currentSetIndex.value)
                        ?: nextExercise.weightPerCableKg

                    _workoutParameters.value = _workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        workoutType = nextExercise.workoutType,
                        progressionRegressionKg = nextExercise.progressionKg,
                        selectedExerciseId = nextExercise.exercise.id,
                        isAMRAP = nextSetReps == null
                    )

                    repCounter.resetCountsOnly()
                    resetAutoStopState()
                    startWorkout(skipCountdown = true)
                    return
                }
                // Superset complete - fall through to move to next exercise after superset
            }
        }

        // Normal (non-superset) progression
        if (_currentSetIndex.value < currentExercise.setReps.size - 1 && !isInSuperset()) {
            // More sets in current exercise (non-superset)
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
            // Move to next exercise (or find next after superset)
            val nextExerciseIndex = findNextExerciseAfterCurrent()

            if (nextExerciseIndex != null && nextExerciseIndex < routine.exercises.size) {
                _currentExerciseIndex.value = nextExerciseIndex
                _currentSetIndex.value = 0

                val nextExercise = routine.exercises[nextExerciseIndex]
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

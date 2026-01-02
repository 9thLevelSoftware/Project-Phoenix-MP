package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.UserPreferences
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
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
    private val gamificationRepository: GamificationRepository,
    private val trainingCycleRepository: TrainingCycleRepository
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

        /** Minimum position range to consider "meaningful" for auto-stop detection (in mm) */
        const val MIN_RANGE_THRESHOLD = 50f
    }

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    private val _currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric.asStateFlow()

    // Current heuristic force (kgMax per cable) for Echo mode live display
    // This is the actual measured force from the device's force telemetry
    private val _currentHeuristicKgMax = MutableStateFlow(0f)
    val currentHeuristicKgMax: StateFlow<Float> = _currentHeuristicKgMax.asStateFlow()
    private var maxHeuristicKgMax = 0f // Track session maximum for history recording

    // Load baseline tracking (Issue: Base tension subtraction)
    // The machine exerts ~4kg base tension on cables even at rest. This baseline is
    // captured when workout starts (handles at rest) and subtracted to show actual user effort.
    private val _loadBaselineA = MutableStateFlow(0f)
    private val _loadBaselineB = MutableStateFlow(0f)
    val loadBaselineA: StateFlow<Float> = _loadBaselineA.asStateFlow()
    val loadBaselineB: StateFlow<Float> = _loadBaselineB.asStateFlow()

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
    private val _topBarTitle = MutableStateFlow("Project Phoenix")
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

    // Training Cycle context for tracking cycle progress when workout completes
    private var activeCycleId: String? = null
    private var activeCycleDayNumber: Int? = null

    private var autoStopStartTime: Long? = null
    private var autoStopTriggered = false
    private var autoStopStopRequested = false
    private var currentHandleState: HandleState = HandleState.WaitingForRest

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
                     RepType.WORKING_COMPLETED -> {
                         // Check if audio rep count is enabled and rep is within announcement range (1-25)
                         // Use event.workingCount (not _repCount.value) - the state hasn't been updated yet
                         val prefs = userPreferences.value
                         if (prefs.audioRepCountEnabled && event.workingCount in 1..25) {
                             _hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(event.workingCount))
                         } else {
                             _hapticEvents.emit(HapticEvent.REP_COMPLETED)
                         }
                     }
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
            bleRepository.handleState.collect { activityState ->
                val params = _workoutParameters.value
                val currentState = _workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Handle auto-START when Idle and waiting for handles
                // Also allow auto-start from SetSummary if in Just Lift mode (interrupting the summary)
                if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                    when (activityState) {
                        HandleState.Grabbed -> {
                            Logger.d("Handles grabbed! Starting auto-start timer (State: ${_workoutState.value})")
                            startAutoStartTimer()
                        }
                        HandleState.Moving -> {
                            // Moving = position extended but no velocity yet
                            // Don't start countdown yet, but also don't cancel if already running
                            // This allows user to slowly pick up handles without false trigger
                        }
                        HandleState.Released -> {
                            Logger.d("Handles released! Canceling auto-start timer")
                            cancelAutoStartTimer()
                        }
                        HandleState.WaitingForRest -> {
                            cancelAutoStartTimer()
                        }
                    }
                }

                // Handle auto-STOP when Active in Just Lift mode and handles released
                // This starts the countdown timer via checkAutoStop logic (triggered by handle state)
                if (params.isJustLift && currentState is WorkoutState.Active) {
                    if (activityState == HandleState.Released) {
                        Logger.d("🛑 Just Lift: Handles RELEASED - starting auto-stop timer")
                        // Do NOT trigger immediately. Let checkAutoStop handle the timer.
                        // We ensure autoStopStartTime is set to start the countdown.
                        if (autoStopStartTime == null) {
                            autoStopStartTime = currentTimeMillis()
                            Logger.d("🛑 Auto-stop timer STARTED (Just Lift) - handles released")
                        }
                    } else if (activityState == HandleState.Grabbed || activityState == HandleState.Moving) {
                        // User resumed activity, reset auto-stop timer
                        resetAutoStopTimer()
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

        // CRITICAL: Global metricsFlow collection (matches parent repo)
        // This runs continuously regardless of workout state, enabling:
        // - Position tracking during handle detection phase (before workout starts)
        // - Position bars to update immediately when connected
        // - Continuous position range calibration for auto-stop detection
        monitorDataCollectionJob = viewModelScope.launch {
            Logger.d("MainViewModel") { "Starting global metricsFlow collection..." }
            bleRepository.metricsFlow.collect { metric ->
                _currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        // Heuristic data collection for Echo mode force feedback (matching parent repo)
        viewModelScope.launch {
            bleRepository.heuristicData.collect { stats ->
                if (stats != null && _workoutState.value is WorkoutState.Active) {
                    // Track maximum force (kgMax) across both phases for Echo mode
                    // kgMax is per-cable force in kg
                    val concentricMax = stats.concentric.kgMax
                    val eccentricMax = stats.eccentric.kgMax
                    val currentMax = maxOf(concentricMax, eccentricMax)

                    // Update live display value for Echo mode
                    _currentHeuristicKgMax.value = currentMax

                    // Track session maximum for history recording
                    if (currentMax > maxHeuristicKgMax) {
                        maxHeuristicKgMax = currentMax
                        Logger.v("MainViewModel") { "Echo force telemetry: kgMax=$currentMax (concentric=$concentricMax, eccentric=$eccentricMax)" }
                    }
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

    /**
     * Handle monitor metric data (matches parent repo logic).
     *
     * This is called on every metric from the machine, regardless of workout state.
     * It handles:
     * - Pre-workout position tracking (during handle detection phase)
     * - Active workout position tracking for Just Lift and AMRAP modes
     * - Auto-stop detection for Just Lift and AMRAP modes
     */
    private fun handleMonitorMetric(metric: WorkoutMetric) {
        val params = _workoutParameters.value
        val state = _workoutState.value

        // CRITICAL: Track positions during handle detection phase (before workout starts)
        // This builds up min/max ranges for hasMeaningfulRange() auto-stop detection
        // useAutoStart is true when in Just Lift mode and waiting for handles
        if (params.useAutoStart && state is WorkoutState.Idle) {
            repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            _repRanges.value = repCounter.getRepRanges()
        }

        if (state is WorkoutState.Active) {
            // Collect metrics for history (moved from monitorWorkout)
            collectMetricForHistory(metric)

            // CRITICAL: In Just Lift/AMRAP modes, we must track positions continuously
            // because no rep events fire to establish min/max ranges.
            // This enables hasMeaningfulRange() to return true for auto-stop detection.
            // For standard workouts, we rely on rep-based tracking (recordTopPosition/recordBottomPosition)
            // which uses sliding window averaging for better accuracy (matches parent repo).
            if (params.isJustLift || params.isAMRAP) {
                repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            }

            // Update rep ranges for position bar ROM visualization
            _repRanges.value = repCounter.getRepRanges()

            // Just Lift / AMRAP Auto-Stop
            // Always call checkAutoStop for position-based detection.
            // Stall (velocity) detection inside is gated by stallDetectionEnabled.
            if (params.isJustLift || params.isAMRAP) {
                checkAutoStop(metric)
            } else {
                resetAutoStopTimer()
            }

            // Standard Auto-Stop (rep target reached)
            if (repCounter.shouldStopWorkout()) {
                stopWorkout()
            }
        } else {
            resetAutoStopTimer()
        }
    }

    /**
     * Collect metric for history recording.
     */
    private fun collectMetricForHistory(metric: WorkoutMetric) {
        collectedMetrics.add(metric)
    }

    fun startScanning() {
        viewModelScope.launch { bleRepository.startScanning() }
    }

    fun stopScanning() {
        viewModelScope.launch { bleRepository.stopScanning() }
    }

    fun cancelScanOrConnection() {
        viewModelScope.launch {
            bleRepository.stopScanning()
            // Only cancel connection if we're actually connecting
            val state = connectionState.value
            if (state is ConnectionState.Connecting) {
                bleRepository.cancelConnection()
            }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        viewModelScope.launch {
            val device = scannedDevices.value.find { it.address == deviceAddress }
            if (device != null) {
                bleRepository.connect(device)
            } else {
                Logger.e { "Device not found in scanned devices: $deviceAddress" }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { bleRepository.disconnect() }
    }

    fun updateWorkoutParameters(params: WorkoutParameters) {
        _workoutParameters.value = params
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        Logger.d { "startWorkout called: skipCountdown=$skipCountdown, isJustLiftMode=$isJustLiftMode" }
        Logger.d { "startWorkout: loadedRoutine=${_loadedRoutine.value?.name}, params=${_workoutParameters.value}" }

        // NOTE: No connection guard here - caller (ensureConnection) ensures connection
        // Parent repo doesn't check connection in startWorkout()

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
                collectedMetrics.clear()  // Clear metrics from previous workout
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

            // 2. Send INIT Command (0x0A) - ensures clean state
            // Per parent repo protocol: "Sometimes sent before start to ensure clean state"
            try {
                val initCommand = BlePacketFactory.createInitCommand()
                bleRepository.sendWorkoutCommand(initCommand)
                Logger.i { "INIT command sent (0x0A) - reset machine state" }
            } catch (e: Exception) {
                Logger.w(e) { "INIT command failed (non-fatal): ${e.message}" }
                // Continue anyway - init is optional
            }

            // 3. Send Configuration Command (0x04 header, 96 bytes)
            // This sets the workout parameters but does NOT engage the motors
            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.i { "CONFIG command sent (0x04): ${command.size} bytes for ${params.workoutType}" }
                // Log first 16 bytes for debugging
                val preview = command.take(16).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                Logger.d { "Config preview: $preview ..." }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send config command" }
                _connectionError.value = "Failed to send command: ${e.message}"
                return@launch
            }

            // 4. Send START Command (0x03) - ENABLES THE MOTORS!
            // Per parent repo protocol analysis: Config (0x04) sets params, START (0x03) engages
            try {
                val startCommand = BlePacketFactory.createStartCommand()
                bleRepository.sendWorkoutCommand(startCommand)
                Logger.i { "START command sent (0x03) - motors should engage" }

                // Start active workout polling
                bleRepository.startActiveWorkoutPolling()
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send START command" }
                _connectionError.value = "Failed to start workout: ${e.message}"
                return@launch
            }

            // 5. Reset State
            currentSessionId = KmpUtils.randomUUID()
            _repCount.value = RepCount()
            // For Just Lift mode, preserve position ranges built during handle detection
            // A full reset() would wipe out hasMeaningfulRange() data needed for auto-stop
            if (isJustLiftMode) {
                repCounter.resetCountsOnly()
            } else {
                repCounter.reset()
            }
            repCounter.configure(
                warmupTarget = params.warmupReps,
                workingTarget = params.reps,
                isJustLift = isJustLiftMode,
                stopAtTop = params.stopAtTop,
                isAMRAP = params.isAMRAP
            )

            // 6. Countdown (skipped for Just Lift auto-start)
            if (!skipCountdown && !isJustLiftMode) {
                for (i in 5 downTo 1) {
                    _workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
            }

            // 7. Start Monitoring
            _workoutState.value = WorkoutState.Active
            workoutStartTime = currentTimeMillis()
            collectedMetrics.clear()  // Clear metrics from previous workout
            _hapticEvents.emit(HapticEvent.WORKOUT_START)

            // Set initial baseline position for position bars calibration
            // This ensures bars start at 0% relative to the starting rope position
            _currentMetric.value?.let { metric ->
                repCounter.setInitialBaseline(metric.positionA, metric.positionB)
                _repRanges.value = repCounter.getRepRanges()
                Logger.d("MainViewModel") { "POSITION BASELINE: Set initial baseline posA=${metric.positionA}, posB=${metric.positionB}" }

                // Capture load baseline for base tension subtraction
                // The machine exerts ~4kg base tension per cable even at rest.
                // By capturing this at workout start (handles at rest), we can subtract it
                // to show the user's actual effort during the workout.
                _loadBaselineA.value = metric.loadA
                _loadBaselineB.value = metric.loadB
                Logger.d("MainViewModel") { "LOAD BASELINE: Set initial baseline loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
            }

            // Note: Metric collection is handled globally in init via handleMonitorMetric()
            // No need for separate monitorWorkout() - this ensures consistent position tracking
            // before, during, and after workouts (matching parent repo behavior)
        }
    }

    fun stopWorkout() {
        viewModelScope.launch {
             // Send RESET command (0x0A) to fully stop workout on machine
             // This matches parent repo and web app behavior
             bleRepository.stopWorkout()
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

             // Get exercise name for display (avoids DB lookups when viewing history)
             val exerciseName = params.selectedExerciseId?.let { exerciseId ->
                 exerciseRepository.getExerciseById(exerciseId)?.name
             }

             // Calculate summary metrics for persistence and display
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

             val session = WorkoutSession(
                 timestamp = workoutStartTime,
                 mode = params.workoutType.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - workoutStartTime,
                 isJustLift = isJustLift,
                 exerciseId = params.selectedExerciseId,
                 exerciseName = exerciseName,
                 routineSessionId = currentRoutineSessionId,
                 routineName = currentRoutineName,
                 // Set Summary Metrics (v0.2.1+)
                 peakForceConcentricA = summary.peakForceConcentricA,
                 peakForceConcentricB = summary.peakForceConcentricB,
                 peakForceEccentricA = summary.peakForceEccentricA,
                 peakForceEccentricB = summary.peakForceEccentricB,
                 avgForceConcentricA = summary.avgForceConcentricA,
                 avgForceConcentricB = summary.avgForceConcentricB,
                 avgForceEccentricA = summary.avgForceEccentricA,
                 avgForceEccentricB = summary.avgForceEccentricB,
                 heaviestLiftKg = summary.heaviestLiftKgPerCable,
                 totalVolumeKg = summary.totalVolumeKg,
                 estimatedCalories = summary.estimatedCalories,
                 warmupAvgWeightKg = if (isEcho) summary.warmupAvgWeightKg else null,
                 workingAvgWeightKg = if (isEcho) summary.workingAvgWeightKg else null,
                 burnoutAvgWeightKg = if (isEcho) summary.burnoutAvgWeightKg else null,
                 peakWeightKg = if (isEcho) summary.peakWeightKg else null,
                 rpe = _currentSetRpe.value
             )
             workoutRepository.saveSession(session)

             // Save exercise defaults for next time (only for Just Lift and Single Exercise modes)
             // This mirrors the logic in saveWorkoutSession() to ensure defaults are saved
             // whether the workout is manually stopped or auto-completed
             if (isJustLift) {
                 saveJustLiftDefaultsFromWorkout()
             } else if (isSingleExerciseMode()) {
                 saveSingleExerciseDefaultsFromWorkout()
             }

             // Show Summary
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
    fun setStallDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setStallDetectionEnabled(enabled) }
    }

    fun setAudioRepCountEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setAudioRepCountEnabled(enabled) }
    }

    fun setColorScheme(schemeIndex: Int) {
        viewModelScope.launch {
            bleRepository.setColorScheme(schemeIndex)
            preferencesManager.setColorScheme(schemeIndex)
            // Update disco mode's restore color index
            (bleRepository as? com.devil.phoenixproject.data.repository.KableBleRepository)?.setLastColorSchemeIndex(schemeIndex)
        }
    }

    // ========== Disco Mode (Easter Egg) ==========

    val discoModeActive: StateFlow<Boolean> = bleRepository.discoModeActive

    fun unlockDiscoMode() {
        viewModelScope.launch {
            preferencesManager.setDiscoModeUnlocked(true)
            Logger.i { "🕺🪩 DISCO MODE UNLOCKED! 🪩🕺" }
        }
    }

    fun toggleDiscoMode(enabled: Boolean) {
        if (enabled) {
            bleRepository.startDiscoMode()
        } else {
            bleRepository.stopDiscoMode()
        }
    }

    /**
     * Emit disco mode unlock sound event for the celebration popup
     */
    fun emitDiscoSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.DISCO_MODE_UNLOCKED)
        }
    }

    /**
     * Emit badge earned sound event for badge celebration
     */
    fun emitBadgeSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.BADGE_EARNED)
        }
    }

    /**
     * Emit personal record sound event for PR celebration
     */
    fun emitPRSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.PERSONAL_RECORD)
        }
    }

    /**
     * Test sound playback - plays a sequence of sounds for testing audio configuration.
     * Useful for verifying sounds play through DND and use correct volume stream.
     */
    fun testSounds() {
        viewModelScope.launch {
            // Play a variety of sounds with delays so user can hear each one
            _hapticEvents.emit(HapticEvent.REP_COMPLETED)
            kotlinx.coroutines.delay(800)
            _hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
            kotlinx.coroutines.delay(1000)
            _hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(5))
            kotlinx.coroutines.delay(1000)
            _hapticEvents.emit(HapticEvent.WORKOUT_COMPLETE)
        }
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

    /**
     * Ensures connection to a Vitruvian device.
     * If already connected, immediately calls onConnected.
     * If not connected, starts scan and auto-connects to first device found.
     * Matches parent repo behavior with proper timeouts and cleanup.
     */
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) {
        // If already connected, just call the callback
        if (connectionState.value is ConnectionState.Connected) {
            Logger.d { "ensureConnection: Already connected, calling onConnected()" }
            onConnected()
            return
        }

        // If already connecting/scanning, cancel and return to disconnected
        if (connectionState.value is ConnectionState.Connecting ||
            connectionState.value is ConnectionState.Scanning) {
            Logger.d { "ensureConnection: Cancelling in-progress connection" }
            cancelConnection()
            return
        }

        // Start new connection
        connectionJob?.cancel()
        connectionJob = null

        connectionJob = viewModelScope.launch {
            try {
                _isAutoConnecting.value = true
                _connectionError.value = null
                _pendingConnectionCallback = onConnected

                // Simple scan-and-connect matching parent repo behavior
                Logger.d { "ensureConnection: Starting scanAndConnect..." }
                val result = bleRepository.scanAndConnect(timeoutMs = 30000L)

                _isAutoConnecting.value = false

                if (result.isSuccess) {
                    // Wait briefly for connection state to propagate
                    delay(500)

                    // Check if we're actually connected
                    if (connectionState.value is ConnectionState.Connected) {
                        Logger.d { "ensureConnection: Connected successfully" }
                        onConnected()
                    } else {
                        // Wait a bit more for Connected state
                        val connected = withTimeoutOrNull(15000) {
                            connectionState
                                .filter { it is ConnectionState.Connected }
                                .first()
                        }
                        if (connected != null) {
                            Logger.d { "ensureConnection: Connected after waiting" }
                            onConnected()
                        } else {
                            Logger.w { "ensureConnection: Connection didn't complete" }
                            _connectionError.value = "Connection timeout"
                            _pendingConnectionCallback = null
                            onFailed()
                        }
                    }
                } else {
                    Logger.w { "ensureConnection: scanAndConnect failed: ${result.exceptionOrNull()?.message}" }
                    _connectionError.value = result.exceptionOrNull()?.message ?: "Connection failed"
                    _pendingConnectionCallback = null
                    onFailed()
                }
            } catch (e: Exception) {
                Logger.e { "ensureConnection error: ${e.message}" }
                bleRepository.cancelConnection()
                _pendingConnectionCallback = null
                _isAutoConnecting.value = false
                _connectionError.value = "Error: ${e.message}"
                onFailed()
            }
        }
    }

    /**
     * Cancel any in-progress connection attempt and return to disconnected state.
     */
    fun cancelConnection() {
        Logger.d { "cancelConnection: Cancelling connection attempt" }
        connectionJob?.cancel()
        connectionJob = null
        _pendingConnectionCallback = null
        _isAutoConnecting.value = false
        viewModelScope.launch {
            bleRepository.stopScanning()
            bleRepository.cancelConnection()
        }
    }

    /**
     * Proceed from set summary to rest timer or completion.
     * Called when user clicks "Continue" button on set summary screen,
     * or when autoplay countdown finishes.
     *
     * Parity with parent repo: MainViewModel.kt lines 1562-1653
     */
    fun proceedFromSummary() {
        viewModelScope.launch {
            val routine = _loadedRoutine.value
            val isJustLift = _workoutParameters.value.isJustLift

            Logger.d { "proceedFromSummary: routine=${routine?.name ?: "NULL"}, isJustLift=$isJustLift" }
            Logger.d { "  currentExerciseIndex=${_currentExerciseIndex.value}, currentSetIndex=${_currentSetIndex.value}" }

            // Check if there are more sets or exercises remaining
            val hasMoreSets = routine?.let {
                val currentExercise = it.exercises.getOrNull(_currentExerciseIndex.value)
                val isAMRAPExercise = currentExercise?.isAMRAP == true

                if (isAMRAPExercise) {
                    true // AMRAP always has "more sets" - user decides when to move on
                } else {
                    currentExercise != null && _currentSetIndex.value < currentExercise.setReps.size - 1
                }
            } ?: false

            val hasMoreExercises = routine?.let {
                _currentExerciseIndex.value < it.exercises.size - 1
            } ?: false

            // Single Exercise mode (not Just Lift, includes temp routines from SingleExerciseScreen)
            val isSingleExercise = isSingleExerciseMode() && !isJustLift
            // Only show rest timer if there are actually more sets/exercises remaining
            val shouldShowRestTimer = (hasMoreSets || hasMoreExercises) && !isJustLift

            Logger.d { "proceedFromSummary: hasMoreSets=$hasMoreSets, hasMoreExercises=$hasMoreExercises" }
            Logger.d { "  isSingleExercise=$isSingleExercise, shouldShowRestTimer=$shouldShowRestTimer" }

            // Clear RPE for next set
            _currentSetRpe.value = null

            // Show rest timer if there are more sets/exercises
            // (regardless of autoplay preference - autoplay only controls auto-advance after rest)
            if (shouldShowRestTimer) {
                Logger.d { "proceedFromSummary: Starting rest timer..." }
                startRestTimer()
            } else {
                Logger.d { "proceedFromSummary: No rest timer - marking as completed/idle" }
                repCounter.reset()
                resetAutoStopState()

                // Auto-reset for Just Lift mode to enable immediate restart
                if (isJustLift) {
                    Logger.d { "Just Lift mode: Auto-resetting to Idle" }
                    resetForNewWorkout()
                    _workoutState.value = WorkoutState.Idle
                    enableHandleDetection()
                    bleRepository.enableJustLiftWaitingMode()
                    Logger.d { "Just Lift mode: Ready for next exercise" }
                } else {
                    _workoutState.value = WorkoutState.Completed
                }
            }
        }
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
        _repRanges.value = null  // Clear ROM calibration for new workout
        // Note: Load baseline is NOT reset here - it persists across sets in the same workout session
        // This is intentional since the base tension doesn't change between sets
    }

    /**
     * Manually recapture load baseline (tare function).
     * Call this when handles are at rest to zero out the displayed load.
     * Useful if baseline drifted or user wants to recalibrate mid-workout.
     */
    fun recaptureLoadBaseline() {
        _currentMetric.value?.let { metric ->
            _loadBaselineA.value = metric.loadA
            _loadBaselineB.value = metric.loadB
            Logger.d("MainViewModel") { "LOAD BASELINE: Manually recaptured loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
        }
    }

    /**
     * Reset load baseline to zero (disable baseline subtraction).
     * Useful for debugging or when raw values are desired.
     */
    fun resetLoadBaseline() {
        _loadBaselineA.value = 0f
        _loadBaselineB.value = 0f
        Logger.d("MainViewModel") { "LOAD BASELINE: Reset to 0 (disabled)" }
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
        return "${value.format(1)} ${unit.name.lowercase()}"
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
        if (routine.exercises.isEmpty()) {
            Logger.w { "Cannot load routine with no exercises" }
            return
        }

        _loadedRoutine.value = routine
        _currentExerciseIndex.value = 0
        _currentSetIndex.value = 0
        _skippedExercises.value = emptySet()
        _completedExercises.value = emptySet()

        // Load parameters from first exercise (matching parent repo behavior)
        val firstExercise = routine.exercises[0]
        val firstSetReps = firstExercise.setReps.firstOrNull() // Can be null for AMRAP sets
        // Get per-set weight for first set, falling back to exercise default
        val firstSetWeight = firstExercise.setWeightsPerCableKg.getOrNull(0)
            ?: firstExercise.weightPerCableKg

        Logger.d { "Loading routine: ${routine.name}" }
        Logger.d { "  First exercise: ${firstExercise.exercise.displayName}" }
        Logger.d { "  First set weight: ${firstSetWeight}kg, reps: $firstSetReps" }
        Logger.d { "  Workout type: ${firstExercise.workoutType.displayName}" }

        val params = WorkoutParameters(
            workoutType = firstExercise.workoutType,
            reps = firstSetReps ?: 0, // AMRAP sets have null reps, use 0 as placeholder
            weightPerCableKg = firstSetWeight,
            progressionRegressionKg = firstExercise.progressionKg,
            isJustLift = false,  // CRITICAL: Routines are NOT just lift mode
            useAutoStart = false,
            stopAtTop = stopAtTop.value,
            warmupReps = _workoutParameters.value.warmupReps,
            isAMRAP = firstSetReps == null, // This SET is AMRAP if its reps is null
            selectedExerciseId = firstExercise.exercise.id,
            stallDetectionEnabled = firstExercise.stallDetectionEnabled
        )

        Logger.d { "Created WorkoutParameters: isAMRAP=${params.isAMRAP}, isJustLift=${params.isJustLift}, stallDetection=${params.stallDetectionEnabled}" }
        updateWorkoutParameters(params)
    }

    fun loadRoutineById(routineId: String) {
        val routine = _routines.value.find { it.id == routineId }
        if (routine != null) {
            clearCycleContext()  // Ensure non-cycle workouts don't update cycle progress
            loadRoutine(routine)
        }
    }

    /**
     * Load a routine from a training cycle context.
     * This tracks the cycle and day so we can mark the day as completed when the workout finishes.
     */
    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) {
        val routine = _routines.value.find { it.id == routineId }
        if (routine != null) {
            activeCycleId = cycleId
            activeCycleDayNumber = dayNumber
            Logger.d { "Loading routine from cycle: cycleId=$cycleId, dayNumber=$dayNumber" }
            loadRoutine(routine)
        }
    }

    /**
     * Clear the active cycle context (e.g., when starting a non-cycle workout).
     */
    fun clearCycleContext() {
        activeCycleId = null
        activeCycleDayNumber = null
    }

    fun clearLoadedRoutine() {
        _loadedRoutine.value = null
        clearCycleContext()
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
        val supersetId = currentExercise.supersetId ?: return emptyList()

        return routine.exercises
            .filter { it.supersetId == supersetId }
            .sortedBy { it.orderInSuperset }
    }

    /**
     * Check if the current exercise is part of a superset.
     */
    private fun isInSuperset(): Boolean {
        return getCurrentExercise()?.supersetId != null
    }

    /**
     * Get the next exercise index in the superset rotation.
     * Returns null if we've completed the superset cycle for the current set.
     */
    private fun getNextSupersetExerciseIndex(): Int? {
        val routine = _loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val supersetId = currentExercise.supersetId ?: return null

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
        if (currentExercise.supersetId == null) return false

        val supersetExercises = getCurrentSupersetExercises()
        return currentExercise == supersetExercises.lastOrNull()
    }

    /**
     * Get the superset rest time (short rest between superset exercises).
     */
    private fun getSupersetRestSeconds(): Int {
        val routine = _loadedRoutine.value ?: return 10
        val supersetId = getCurrentExercise()?.supersetId ?: return 10
        return routine.supersets.find { it.id == supersetId }?.restBetweenSeconds ?: 10
    }

    /**
     * Find the next exercise after the current one (or after the current superset).
     * Skips over exercises that are part of the current superset.
     */
    private fun findNextExerciseAfterCurrent(): Int? {
        val routine = _loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val currentSupersetId = currentExercise.supersetId

        // If in a superset, find the first exercise after the superset
        if (currentSupersetId != null) {
            val supersetExerciseIndices = routine.exercises
                .mapIndexedNotNull { index, ex ->
                    if (ex.supersetId == currentSupersetId) index else null
                }
            val lastSupersetIndex = supersetExerciseIndices.maxOrNull() ?: _currentExerciseIndex.value
            val nextIndex = lastSupersetIndex + 1
            return if (nextIndex < routine.exercises.size) nextIndex else null
        }

        // Not in a superset - just go to next index
        val nextIndex = _currentExerciseIndex.value + 1
        return if (nextIndex < routine.exercises.size) nextIndex else null
    }

    // ========== Superset CRUD Operations ==========

    /**
     * Create a new superset in a routine.
     * Auto-assigns next available color and generates name.
     */
    suspend fun createSuperset(
        routineId: String,
        name: String? = null,
        exercises: List<RoutineExercise> = emptyList()
    ): Superset {
        val routine = getRoutineById(routineId) ?: throw IllegalArgumentException("Routine not found")
        val existingColors = routine.supersets.map { it.colorIndex }.toSet()
        val colorIndex = SupersetColors.next(existingColors)
        val supersetCount = routine.supersets.size
        val autoName = name ?: "Superset ${'A' + supersetCount}"
        val orderIndex = routine.getItems().maxOfOrNull { it.orderIndex }?.plus(1) ?: 0

        val superset = Superset(
            id = generateSupersetId(),
            routineId = routineId,
            name = autoName,
            colorIndex = colorIndex,
            restBetweenSeconds = 10,
            orderIndex = orderIndex
        )

        // Save routine with new superset
        val updatedSupersets = routine.supersets + superset
        val updatedExercises = exercises.mapIndexed { index, exercise ->
            exercise.copy(supersetId = superset.id, orderInSuperset = index)
        } + routine.exercises.filter { it.id !in exercises.map { e -> e.id } }

        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)

        return superset
    }

    /**
     * Update superset properties (name, rest time, color).
     */
    suspend fun updateSuperset(routineId: String, superset: Superset) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.map {
            if (it.id == superset.id) superset else it
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Delete a superset. Exercises become standalone.
     */
    suspend fun deleteSuperset(routineId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.filter { it.id != supersetId }
        // Clear superset reference from exercises
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.supersetId == supersetId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Move an exercise into a superset.
     */
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val superset = routine.supersets.find { it.id == supersetId } ?: return
        val currentExercisesInSuperset = routine.exercises.filter { it.supersetId == supersetId }
        val newOrderInSuperset = currentExercisesInSuperset.maxOfOrNull { it.orderInSuperset }?.plus(1) ?: 0

        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = supersetId, orderInSuperset = newOrderInSuperset)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Remove an exercise from a superset (becomes standalone).
     */
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
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
     * Matches parent repo behavior: resets state if needed, sets parameters, enables handle detection.
     */
    fun prepareForJustLift() {
        viewModelScope.launch {
            val currentState = _workoutState.value
            val currentWeight = _workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: BEFORE - weight=$currentWeight kg")

            if (currentState !is WorkoutState.Idle) {
                Logger.d("Preparing for Just Lift: Resetting from ${currentState::class.simpleName} to Idle")
                resetForNewWorkout()
                _workoutState.value = WorkoutState.Idle
            } else {
                Logger.d("Just Lift already in Idle state, ensuring auto-start is enabled")
            }

            // Set parameters first before enabling handle detection
            _workoutParameters.value = _workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true,
                selectedExerciseId = null  // Clear exercise selection for Just Lift
            )

            // Enable handle detection - auto-start triggers when user grabs handles
            enableHandleDetection()
            val newWeight = _workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: AFTER - weight=$newWeight kg")
            Logger.d("Just Lift ready: State=Idle, AutoStart=enabled, waiting for handle grab")
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
     * Get saved Single Exercise defaults for an exercise, trying the preferred cable config first,
     * then falling back to other cable configs if not found.
     * This handles cases where user changed cable config in a previous session.
     */
    suspend fun getSingleExerciseDefaultsWithFallback(
        exerciseId: String,
        preferredCableConfig: String
    ): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? {
        // Try preferred config first
        preferencesManager.getSingleExerciseDefaults(exerciseId, preferredCableConfig)?.let {
            return it
        }

        // Try other cable configs as fallback
        val allConfigs = listOf("DOUBLE", "SINGLE", "EITHER")
        for (config in allConfigs) {
            if (config != preferredCableConfig) {
                preferencesManager.getSingleExerciseDefaults(exerciseId, config)?.let {
                    return it
                }
            }
        }

        return null
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
     * Returns default object if none saved. Converts from preferences format to viewmodel format.
     */
    suspend fun getJustLiftDefaults(): JustLiftDefaults {
        val prefsDefaults = preferencesManager.getJustLiftDefaults()
        // Convert from preferences format (Float weightChangePerRep) to viewmodel format (Int)
        return JustLiftDefaults(
            weightPerCableKg = prefsDefaults.weightPerCableKg,
            weightChangePerRep = kotlin.math.round(prefsDefaults.weightChangePerRep).toInt(),
            workoutModeId = prefsDefaults.workoutModeId,
            eccentricLoadPercentage = prefsDefaults.eccentricLoadPercentage,
            echoLevelValue = prefsDefaults.echoLevelValue,
            stallDetectionEnabled = prefsDefaults.stallDetectionEnabled
        )
    }

    /**
     * Save Just Lift defaults for next session.
     * Converts from viewmodel format to preferences format.
     */
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        viewModelScope.launch {
            // Convert from viewmodel format (Int weightChangePerRep) to preferences format (Float)
            val prefsDefaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                weightPerCableKg = defaults.weightPerCableKg,
                weightChangePerRep = defaults.weightChangePerRep.toFloat(),
                workoutModeId = defaults.workoutModeId,
                eccentricLoadPercentage = defaults.eccentricLoadPercentage,
                echoLevelValue = defaults.echoLevelValue,
                stallDetectionEnabled = defaults.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(prefsDefaults)
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
        val params = _workoutParameters.value

        // ===== 1. VELOCITY-BASED STALL DETECTION (Issue #204, #214, #216) =====
        // Only run if stallDetectionEnabled is true (user preference in Settings)
        if (params.stallDetectionEnabled) {
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
        } else {
            // Stall detection disabled - reset stall timer to avoid stale state
            resetStallTimer()
        }

        // ===== 2. POSITION-BASED DETECTION (always active) =====
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
                    val thresholdA = minA + (rangeA * 0.05f)
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
                        val thresholdB = minB + (rangeB * 0.05f)
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

            // Stop hardware - use stopWorkout() which sends RESET command (0x0A), delays 50ms, and STOPS polling
            // This matches parent repo behavior - polling must be fully stopped before restarting
            // to properly clear the machine's internal state (prevents red light mode on 2nd+ sets)
            bleRepository.stopWorkout()
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
                Logger.d("⏱️ Just Lift: IMMEDIATE reset for next set (while showing summary)")

                // 1. Reset logical state immediately
                repCounter.reset()
                resetAutoStopState()

                // 2. Restart monitor polling to clear machine fault state (red lights)
                bleRepository.restartMonitorPolling()

                // 3. Re-enable machine detection (enables auto-start for next set)
                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("⏱️ Just Lift: Machine armed & ready. Showing summary for 5s...")

                // 4. Show summary for 5 seconds (User preference)
                // Note: If user grabs handles during this delay, auto-start logic in handleState collector
                // will interrupt this and start the next set immediately.
                delay(5000)

                // 4. Transition UI to Idle (only if we haven't already started a new set)
                if (_workoutState.value is WorkoutState.SetSummary) {
                    Logger.d("⏱️ Just Lift: Summary complete, UI transitioning to Idle")
                    resetForNewWorkout() // Ensures clean state
                    _workoutState.value = WorkoutState.Idle
                } else {
                    Logger.d("⏱️ Just Lift: Summary interrupted by user action (state is ${_workoutState.value})")
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

        // Take a snapshot of metrics to avoid ConcurrentModificationException
        // (metrics are being collected on another coroutine)
        val metricsSnapshot = collectedMetrics.toList()

        // Calculate actual measured weight from metrics (if available)
        val measuredPerCableKg = if (metricsSnapshot.isNotEmpty()) {
            metricsSnapshot.maxOf { it.totalLoad } / 2f
        } else {
            params.weightPerCableKg
        }

        // Get exercise name for display (avoids DB lookups when viewing history)
        val exerciseName = params.selectedExerciseId?.let { exerciseId ->
            exerciseRepository.getExerciseById(exerciseId)?.name
        }

        // Calculate summary metrics for persistence
        val isEchoMode = params.workoutType is WorkoutType.Echo
        val summary = calculateSetSummaryMetrics(
            metrics = metricsSnapshot,
            repCount = working,
            fallbackWeightKg = params.weightPerCableKg,
            isEchoMode = isEchoMode,
            warmupRepsCount = warmup,
            workingRepsCount = working
        )

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
            exerciseName = exerciseName,
            routineSessionId = currentRoutineSessionId,
            routineName = currentRoutineName,
            // Set Summary Metrics (v0.2.1+)
            peakForceConcentricA = summary.peakForceConcentricA,
            peakForceConcentricB = summary.peakForceConcentricB,
            peakForceEccentricA = summary.peakForceEccentricA,
            peakForceEccentricB = summary.peakForceEccentricB,
            avgForceConcentricA = summary.avgForceConcentricA,
            avgForceConcentricB = summary.avgForceConcentricB,
            avgForceEccentricA = summary.avgForceEccentricA,
            avgForceEccentricB = summary.avgForceEccentricB,
            heaviestLiftKg = summary.heaviestLiftKgPerCable,
            totalVolumeKg = summary.totalVolumeKg,
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (isEchoMode) summary.peakWeightKg else null,
            rpe = _currentSetRpe.value
        )

        workoutRepository.saveSession(session)

        if (metricsSnapshot.isNotEmpty()) {
            workoutRepository.saveMetrics(sessionId, metricsSnapshot)
        }

        Logger.d("Saved workout session: $sessionId with ${metricsSnapshot.size} metrics")

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

        // Save exercise defaults for next time (only for Just Lift and Single Exercise modes)
        // Routines have their own saved configuration and should not interfere with these defaults
        if (params.isJustLift) {
            saveJustLiftDefaultsFromWorkout()
        } else if (isSingleExerciseMode()) {
            saveSingleExerciseDefaultsFromWorkout()
        }

        // Update training cycle progress if this workout was started from a cycle
        updateCycleProgressIfNeeded()
    }

    /**
     * Update cycle progress when a workout is completed from a training cycle.
     * Marks the day as completed and advances to the next day.
     * If the user completes a day ahead of the current day, marks skipped days as missed.
     */
    private suspend fun updateCycleProgressIfNeeded() {
        val cycleId = activeCycleId ?: return
        val dayNumber = activeCycleDayNumber ?: return

        // Clear cycle context immediately to prevent race conditions
        activeCycleId = null
        activeCycleDayNumber = null

        try {
            val cycle = trainingCycleRepository.getCycleById(cycleId)
            val progress = trainingCycleRepository.getCycleProgress(cycleId)

            if (cycle != null && progress != null) {
                // Use the CycleProgress model method which handles:
                // - Adding the day to completedDays set
                // - Marking any skipped days as missed (in missedDays set)
                // - Advancing to the next day
                // - Handling rotation (reset sets when cycling back to Day 1)
                val updated = progress.markDayCompleted(dayNumber, cycle.days.size)
                trainingCycleRepository.updateCycleProgress(updated)

                Logger.d { "Cycle progress updated: day $dayNumber completed, now on day ${updated.currentDayNumber}" }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating cycle progress: ${e.message}" }
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
     * Save Just Lift defaults after completing a Just Lift workout.
     * Called from saveWorkoutSession when isJustLift is true.
     */
    private suspend fun saveJustLiftDefaultsFromWorkout() {
        val params = _workoutParameters.value
        if (!params.isJustLift) return

        val (eccentricLoad, echoLevel) = when (val wt = params.workoutType) {
            is WorkoutType.Echo -> wt.eccentricLoad.percentage to wt.level.levelValue
            is WorkoutType.Program -> 100 to 2
        }

        // Convert WorkoutType to workoutModeId (Int)
        val workoutModeId = when (val wt = params.workoutType) {
            is WorkoutType.Program -> wt.mode.modeValue
            is WorkoutType.Echo -> 10
        }

        try {
            val defaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                workoutModeId = workoutModeId,
                weightPerCableKg = params.weightPerCableKg.coerceAtLeast(0.1f),
                weightChangePerRep = params.progressionRegressionKg,
                eccentricLoadPercentage = eccentricLoad,
                echoLevelValue = echoLevel,
                stallDetectionEnabled = params.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(defaults)
            Logger.d { "Saved Just Lift defaults: mode=$workoutModeId, weight=${params.weightPerCableKg}kg" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Just Lift defaults: ${e.message}" }
        }
    }

    /**
     * Save Single Exercise defaults after completing a single exercise workout
     * Called from saveWorkoutSession when in Single Exercise mode (temp routine)
     */
    private suspend fun saveSingleExerciseDefaultsFromWorkout() {
        val routine = _loadedRoutine.value ?: return

        // Only save for temp single exercise routines, not for regular routines
        if (!routine.id.startsWith(TEMP_SINGLE_EXERCISE_PREFIX)) return

        val currentExercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return
        val exerciseId = currentExercise.exercise.id ?: return

        val (eccentricLoad, echoLevel) = when (val wt = currentExercise.workoutType) {
            is WorkoutType.Echo -> wt.eccentricLoad.percentage to wt.level.levelValue
            is WorkoutType.Program -> 100 to 1
        }

        try {
            val setReps = currentExercise.setReps.ifEmpty { listOf(10) }
            val numSets = setReps.size

            // Normalize setWeightsPerCableKg to match setReps size (or be empty)
            val normalizedSetWeights = when {
                currentExercise.setWeightsPerCableKg.isEmpty() -> emptyList()
                currentExercise.setWeightsPerCableKg.size == numSets -> currentExercise.setWeightsPerCableKg
                else -> emptyList() // Reset if invalid size
            }

            // Normalize setRestSeconds to match setReps size (or be empty)
            val normalizedSetRest = when {
                currentExercise.setRestSeconds.isEmpty() -> emptyList()
                currentExercise.setRestSeconds.size == numSets -> currentExercise.setRestSeconds
                else -> emptyList() // Reset if invalid size
            }

            // Convert WorkoutType to workoutModeId (Int)
            val workoutModeId = when (val wt = currentExercise.workoutType) {
                is WorkoutType.Program -> wt.mode.modeValue
                is WorkoutType.Echo -> 10
            }

            val defaults = com.devil.phoenixproject.data.preferences.SingleExerciseDefaults(
                exerciseId = exerciseId,
                cableConfig = currentExercise.cableConfig.name,
                setReps = setReps,
                weightPerCableKg = currentExercise.weightPerCableKg.coerceAtLeast(0f),
                setWeightsPerCableKg = normalizedSetWeights,
                progressionKg = currentExercise.progressionKg.coerceIn(-50f, 50f),
                setRestSeconds = normalizedSetRest,
                workoutModeId = workoutModeId,
                eccentricLoadPercentage = eccentricLoad,
                echoLevelValue = echoLevel,
                duration = currentExercise.duration?.takeIf { it > 0 } ?: 0,
                isAMRAP = currentExercise.isAMRAP,
                perSetRestTime = currentExercise.perSetRestTime
            )
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d { "Saved Single Exercise defaults for ${currentExercise.exercise.name} (${currentExercise.cableConfig})" }
        } catch (e: IllegalArgumentException) {
            Logger.e(e) { "Failed to save Single Exercise defaults - validation error" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Single Exercise defaults: ${e.message}" }
        }
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
                val supersetIds = routine?.supersets?.map { it.id } ?: emptyList()
                val groupIndex = supersetIds.indexOf(currentExercise?.supersetId)
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
        val routine = _loadedRoutine.value
        if (routine == null) {
            // No routine loaded - complete the workout
            _workoutState.value = WorkoutState.Completed
            _currentSetIndex.value = 0
            _currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
            return
        }
        val currentExercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return

        if (_currentSetIndex.value < currentExercise.setReps.size - 1) {
            _currentSetIndex.value++
            val targetReps = currentExercise.setReps[_currentSetIndex.value]
            val setWeight = currentExercise.setWeightsPerCableKg.getOrNull(_currentSetIndex.value)
                ?: currentExercise.weightPerCableKg

            _workoutParameters.value = _workoutParameters.value.copy(
                reps = targetReps ?: 0,
                weightPerCableKg = setWeight,
                isAMRAP = targetReps == null,
                stallDetectionEnabled = currentExercise.stallDetectionEnabled
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
                    isAMRAP = nextSetReps == null,
                    stallDetectionEnabled = nextExercise.stallDetectionEnabled
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
                        isAMRAP = nextSetReps == null,
                        stallDetectionEnabled = nextExercise.stallDetectionEnabled
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
                isAMRAP = targetReps == null,
                stallDetectionEnabled = currentExercise.stallDetectionEnabled
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
                    isAMRAP = nextSetReps == null,
                    stallDetectionEnabled = nextExercise.stallDetectionEnabled
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

    override fun onCleared() {
        super.onCleared()
        connectionJob?.cancel()
        monitorDataCollectionJob?.cancel()
        autoStartJob?.cancel()
        restTimerJob?.cancel()
        bodyweightTimerJob?.cancel()
        repEventsCollectionJob?.cancel()
        Logger.i { "MainViewModel cleared, all jobs cancelled" }
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
    val echoLevelValue: Int = 1, // 0=Hard, 1=Harder, 2=Hardest, 3=Epic
    val stallDetectionEnabled: Boolean = true // Stall detection auto-stop toggle
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
        110 -> EccentricLoad.LOAD_110
        120 -> EccentricLoad.LOAD_120
        130 -> EccentricLoad.LOAD_130
        140 -> EccentricLoad.LOAD_140
        150 -> EccentricLoad.LOAD_150
        else -> EccentricLoad.LOAD_100
    }

    /**
     * Get EchoLevel from stored value
     */
    fun getEchoLevel(): EchoLevel = EchoLevel.entries.getOrElse(echoLevelValue) { EchoLevel.HARDER }
}

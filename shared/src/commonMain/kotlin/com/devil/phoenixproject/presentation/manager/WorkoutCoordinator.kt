package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state bus for all workout state. Contains zero business logic methods —
 * only state fields, public getters, and companion constants.
 *
 * Sub-managers (RoutineFlowManager, ActiveSessionEngine) will read/write state
 * through this coordinator's `internal` fields. The ViewModel layer reads state
 * through the public `StateFlow` getters.
 *
 * Created during Phase 2 (Manager Decomposition) Plan 01.
 */
class WorkoutCoordinator(
    internal val _hapticEvents: MutableSharedFlow<HapticEvent> = MutableSharedFlow(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
) {
    companion object {
        /** Position-based auto-stop duration in seconds (handles in danger zone and released) */
        const val AUTO_STOP_DURATION_SECONDS = 2.5f

        /** Velocity-based stall detection duration in seconds (Issue #204, #214) */
        const val STALL_DURATION_SECONDS = 5.0f

        /**
         * Two-tier velocity hysteresis for stall detection (Issue #204, #216)
         * Matches official app behavior to prevent timer toggling near threshold:
         * - Below LOW (<2.5): start/continue stall timer (user is stopped)
         * - Above HIGH (>10): reset stall timer (user is clearly moving)
         * - Between LOW and HIGH (>=2.5 and <=10): maintain current state (hysteresis band)
         */
        const val STALL_VELOCITY_LOW = 2.5    // Below this = definitely stalled (mm/s)
        const val STALL_VELOCITY_HIGH = 10.0  // Above this = definitely moving (mm/s)

        /** Minimum position to consider handles "in use" for stall detection (mm) */
        const val STALL_MIN_POSITION = 10.0

        /** Position threshold to consider handle at rest */
        const val HANDLE_REST_THRESHOLD = 2.5

        /** Minimum position range to consider "meaningful" for auto-stop detection (in mm) */
        const val MIN_RANGE_THRESHOLD = 50f

        /** Issue #204: Startup grace period for AMRAP exercises (ms)
         * Prevents auto-stop from triggering before user has time to grab handles
         * when transitioning from a normal rep-based exercise to an AMRAP exercise.
         */
        const val AMRAP_STARTUP_GRACE_MS = 8000L
    }

    // ===== BLE Error Events =====
    // One-way error flow: DWSM/ActiveSessionEngine emits, BleConnectionManager collects.
    // Replaces the circular lateinit var dependency between DWSM and BleConnectionManager.
    internal val _bleErrorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bleErrorEvents: SharedFlow<String> = _bleErrorEvents.asSharedFlow()

    // ===== Haptic & Feedback Events =====

    val hapticEvents: SharedFlow<HapticEvent> = _hapticEvents.asSharedFlow()

    // Issue #172: User feedback events for navigation/UI messages
    internal val _userFeedbackEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val userFeedbackEvents: SharedFlow<String> = _userFeedbackEvents.asSharedFlow()

    // ===== Workout State =====

    internal val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    /**
     * Returns true if a workout is currently in progress (not idle or completed).
     * Use this to prevent actions that shouldn't happen during active workouts,
     * such as editing routines (Issue #130).
     */
    val isWorkoutActive: Boolean
        get() {
            val state = _workoutState.value
            return state !is WorkoutState.Idle && state !is WorkoutState.Completed
        }

    // ===== Routine Flow State =====

    internal val _routineFlowState = MutableStateFlow<RoutineFlowState>(RoutineFlowState.NotInRoutine)
    val routineFlowState: StateFlow<RoutineFlowState> = _routineFlowState.asStateFlow()

    // ===== Metrics State =====

    internal val _currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric.asStateFlow()

    // Current heuristic force (kgMax per cable) for Echo mode live display
    // This is the actual measured force from the device's force telemetry
    internal val _currentHeuristicKgMax = MutableStateFlow(0f)
    val currentHeuristicKgMax: StateFlow<Float> = _currentHeuristicKgMax.asStateFlow()
    internal var maxHeuristicKgMax = 0f // Track session maximum for history recording

    // Load baseline tracking (Issue: Base tension subtraction)
    // The machine exerts ~4kg base tension on cables even at rest. This baseline is
    // captured when workout starts (handles at rest) and subtracted to show actual user effort.
    internal val _loadBaselineA = MutableStateFlow(0f)
    internal val _loadBaselineB = MutableStateFlow(0f)
    val loadBaselineA: StateFlow<Float> = _loadBaselineA.asStateFlow()
    val loadBaselineB: StateFlow<Float> = _loadBaselineB.asStateFlow()

    // ===== Workout Parameters =====

    internal val _workoutParameters = MutableStateFlow(
        WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            reps = 10,
            weightPerCableKg = 10f,
            progressionRegressionKg = 0f,
            isJustLift = false,
            stopAtTop = false,
            warmupReps = 3
        )
    )
    val workoutParameters: StateFlow<WorkoutParameters> = _workoutParameters.asStateFlow()

    // Issue #108: Track if user manually adjusted weight during rest period
    // When true, preserve user's weight instead of reloading from exercise preset
    internal var _userAdjustedWeightDuringRest = false

    // ===== Rep Counting =====

    internal val _repCount = MutableStateFlow(RepCount())
    val repCount: StateFlow<RepCount> = _repCount.asStateFlow()

    // Issue #192: Timed exercise countdown - remaining seconds for duration-based exercises
    // null = not a timed exercise or timer not running, positive = seconds remaining
    internal val _timedExerciseRemainingSeconds = MutableStateFlow<Int?>(null)
    val timedExerciseRemainingSeconds: StateFlow<Int?> = _timedExerciseRemainingSeconds.asStateFlow()

    internal val _repRanges = MutableStateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?>(null)
    val repRanges: StateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?> = _repRanges.asStateFlow()

    // ===== Auto-Stop State =====

    internal val _autoStopState = MutableStateFlow(AutoStopUiState())
    val autoStopState: StateFlow<AutoStopUiState> = _autoStopState.asStateFlow()

    internal val _autoStartCountdown = MutableStateFlow<Int?>(null)
    val autoStartCountdown: StateFlow<Int?> = _autoStartCountdown.asStateFlow()

    // ===== Routine State =====

    internal val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    internal val _loadedRoutine = MutableStateFlow<Routine?>(null)
    val loadedRoutine: StateFlow<Routine?> = _loadedRoutine.asStateFlow()

    internal val _currentExerciseIndex = MutableStateFlow(0)
    val currentExerciseIndex: StateFlow<Int> = _currentExerciseIndex.asStateFlow()

    internal val _currentSetIndex = MutableStateFlow(0)
    val currentSetIndex: StateFlow<Int> = _currentSetIndex.asStateFlow()

    // Track skipped and completed exercise indices for routine navigation
    internal val _skippedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val skippedExercises: StateFlow<Set<Int>> = _skippedExercises.asStateFlow()

    internal val _completedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val completedExercises: StateFlow<Set<Int>> = _completedExercises.asStateFlow()

    // RPE tracking for current set (Phase 2: Training Cycles)
    internal val _currentSetRpe = MutableStateFlow<Int?>(null)
    val currentSetRpe: StateFlow<Int?> = _currentSetRpe.asStateFlow()

    // ===== Session Tracking =====

    internal var currentSessionId: String? = null
    internal var workoutStartTime: Long = 0
    internal var routineStartTime: Long = 0  // Issue #195: Track routine start separately from per-set start
    internal val collectedMetrics = mutableListOf<WorkoutMetric>()

    internal var currentRoutineSessionId: String? = null
    internal var currentRoutineName: String? = null

    // Training Cycle context for tracking cycle progress when workout completes
    internal var activeCycleId: String? = null
    internal var activeCycleDayNumber: Int? = null

    // Cycle day completion event for UI feedback
    internal val _cycleDayCompletionEvent = MutableStateFlow<CycleDayCompletionEvent?>(null)
    val cycleDayCompletionEvent: StateFlow<CycleDayCompletionEvent?> = _cycleDayCompletionEvent.asStateFlow()

    // ===== Auto-Stop Internal State =====

    internal var autoStopStartTime: Long? = null
    internal var autoStopTriggered = false
    internal var autoStopStopRequested = false
    // Guard to prevent race condition where multiple stopWorkout() calls create duplicate sessions
    // Issue #97: handleMonitorMetric() can call stopWorkout() multiple times before state changes
    internal var stopWorkoutInProgress = false
    // Guard to prevent duplicate auto-completion when rep target is reached
    internal var setCompletionInProgress = false
    internal var currentHandleState: HandleState = HandleState.WaitingForRest

    // Velocity-based stall detection state (Issue #204, #214)
    internal var stallStartTime: Long? = null
    internal var isCurrentlyStalled = false

    // ===== Job Tracking =====

    internal var monitorDataCollectionJob: Job? = null
    internal var autoStartJob: Job? = null
    internal var restTimerJob: Job? = null
    internal var bodyweightTimerJob: Job? = null

    // Issue #222 diagnostic: Track bodyweight sets completed in this routine
    internal var bodyweightSetsCompletedInRoutine: Int = 0
    // Issue #222 v8: Track if previous exercise was bodyweight (for StopPacket on transition)
    internal var previousExerciseWasBodyweight: Boolean = false
    internal var repEventsCollectionJob: Job? = null
    internal var workoutJob: Job? = null
    // Flag to skip countdown - checked in countdown loop
    internal var skipCountdownRequested: Boolean = false
    // Track if current workout is duration-based (timed exercise) to show countdown timer
    internal var isCurrentWorkoutTimed: Boolean = false
    // Track if current exercise is a timed CABLE exercise (not bodyweight) for auto-stop via handle release.
    // Kept in StateFlow-backed storage for consistent visibility across collectors/coroutines.
    internal val _isCurrentTimedCableExercise = MutableStateFlow(false)
    internal var isCurrentTimedCableExercise: Boolean
        get() = _isCurrentTimedCableExercise.value
        set(value) {
            _isCurrentTimedCableExercise.value = value
        }
    // Track if current exercise is bodyweight to skip rep processing (no cable engagement)
    internal val _isCurrentExerciseBodyweight = MutableStateFlow(false)
    val isCurrentExerciseBodyweight: StateFlow<Boolean> = _isCurrentExerciseBodyweight.asStateFlow()

    // Idempotency tracking for handle detection (iOS autostart race condition fix)
    // Prevents duplicate enableHandleDetection() calls from resetting state machine mid-grab
    internal var handleDetectionEnabledTimestamp: Long = 0L
    internal val HANDLE_DETECTION_DEBOUNCE_MS = 500L
}

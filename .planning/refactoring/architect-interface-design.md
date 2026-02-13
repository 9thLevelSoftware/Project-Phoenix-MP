# MainViewModel Refactoring: Interface Design & Architecture Plan

## Architecture Overview

```
BEFORE (monolith):
+------------------------------------------------------------------+
|                        MainViewModel                              |
|  10 constructor deps, ~45 state vars, ~75 functions, 4771 lines  |
+------------------------------------------------------------------+

AFTER (delegated managers):
+------------------------------------------------------------------+
|                       MainViewModel (facade)                      |
|  Delegates to managers, exposes combined StateFlows to UI         |
|  ~200 lines: constructor wiring, delegation, onCleared()         |
+------------------------------------------------------------------+
     |          |            |            |             |
     v          v            v            v             v
+---------+ +----------+ +----------+ +-----------+ +---------------------+
| History | | Settings | | Gamifi-  | | BleCon-   | | WorkoutSession-     |
| Manager | | Manager  | | cation   | | nection   | | Manager             |
|  ~130L  | |  ~80L    | | Manager  | | Manager   | |  ~3000L             |
|         | |          | |  ~40L    | |  ~200L    | |                     |
+---------+ +----------+ +----------+ +-----------+ |  +---------------+  |
                                                    |  | Routine-      |  |
                                                    |  | FlowManager   |  |
                                                    |  |  ~1200L       |  |
                                                    |  +---------------+  |
                                                    |         ^           |
                                                    |         |           |
                                                    |  WorkoutCoordinator |
                                                    |  (shared state bus) |
                                                    +---------------------+
```

### Design Principles

1. **Each manager owns its MutableStateFlows** -- it creates them and exposes read-only StateFlows
2. **MainViewModel delegates by re-exposing** manager flows as its own properties (preserves UI API)
3. **CoroutineScope is injected** via interface parameter, not tied to ViewModel lifecycle
4. **Koin wiring is straightforward** -- managers are `single` (same lifecycle as MainViewModel)
5. **Cross-cluster reads use interfaces** -- e.g., WorkoutSessionManager reads settings via SettingsManager interface, not directly from PreferencesManager

---

## Phase 1: Clean Extractions (Zero Cross-Cluster Entanglement)

### 1.1 HistoryManager

**Source**: Cluster_History (~130 lines, L307-L391, L483-L487, L1581, L2037)
**Risk**: Trivial -- 100% read-only computed flows, zero mutations to shared state

#### Interface

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/HistoryManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.coroutines.flow.StateFlow

/**
 * History items for grouped display.
 * Moved from MainViewModel to live alongside the manager.
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
 * Manages workout history state -- read-only views over persisted data.
 * No mutations to workout state; purely computed flows.
 */
interface HistoryManager {
    /** Raw list of all workout sessions */
    val allWorkoutSessions: StateFlow<List<WorkoutSession>>

    /** Recent 20 sessions for quick display */
    val workoutHistory: StateFlow<List<WorkoutSession>>

    /** Sessions grouped by routine session ID */
    val groupedWorkoutHistory: StateFlow<List<HistoryItem>>

    /** All personal records grouped by exercise */
    val allPersonalRecords: StateFlow<List<PersonalRecord>>

    /** All personal record entities (raw DB format) */
    val personalBests: StateFlow<List<com.devil.phoenixproject.data.repository.PersonalRecordEntity>>

    /** Total completed workouts (null if zero) */
    val completedWorkouts: StateFlow<Int?>

    /** Consecutive workout day streak (null if broken or no workouts) */
    val workoutStreak: StateFlow<Int?>

    /** Volume progress percentage vs previous workout (null if < 2 workouts) */
    val progressPercentage: StateFlow<Int?>

    /** Delete a single workout session */
    fun deleteWorkout(sessionId: String)

    /** Delete all workout sessions */
    fun deleteAllWorkouts()
}
```

#### Implementation

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultHistoryManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.PersonalRecordEntity
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.KmpLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DefaultHistoryManager(
    private val workoutRepository: WorkoutRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val scope: CoroutineScope
) : HistoryManager {

    private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
    override val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()

    override val allWorkoutSessions: StateFlow<List<WorkoutSession>> =
        workoutRepository.getAllSessions()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    override val groupedWorkoutHistory: StateFlow<List<HistoryItem>> =
        allWorkoutSessions.map { sessions ->
            val grouped = sessions.filter { it.routineSessionId != null }
                .groupBy { it.routineSessionId!! }
                .map { (id, list) ->
                    GroupedRoutineHistoryItem(
                        routineSessionId = id,
                        routineName = list.first().routineName ?: "Unnamed Routine",
                        sessions = list.sortedBy { it.timestamp },
                        totalDuration = list.sumOf { it.duration },
                        totalReps = list.sumOf { it.totalReps },
                        exerciseCount = list.mapNotNull { it.exerciseId }.distinct().count(),
                        timestamp = list.minOf { it.timestamp }
                    )
                }
            val singles = sessions.filter { it.routineSessionId == null }
                .map { SingleSessionHistoryItem(it) }
            (grouped + singles).sortedByDescending { it.timestamp }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    override val allPersonalRecords: StateFlow<List<PersonalRecord>> =
        personalRecordRepository.getAllPRsGrouped()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    override val personalBests: StateFlow<List<PersonalRecordEntity>> =
        workoutRepository.getAllPersonalRecords()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    override val completedWorkouts: StateFlow<Int?> =
        allWorkoutSessions.map { it.size.takeIf { s -> s > 0 } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    override val workoutStreak: StateFlow<Int?> =
        allWorkoutSessions.map { sessions ->
            if (sessions.isEmpty()) return@map null
            val dates = sessions.map { KmpLocalDate.fromTimestamp(it.timestamp) }
                .distinctBy { it.toKey() }.sortedDescending()
            val today = KmpLocalDate.today()
            if (dates.first().isBefore(today.minusDays(1))) return@map null
            var streak = 1
            for (i in 1 until dates.size) {
                if (dates[i] == dates[i - 1].minusDays(1)) streak++ else break
            }
            streak
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    override val progressPercentage: StateFlow<Int?> =
        allWorkoutSessions.map { sessions ->
            if (sessions.size < 2) return@map null
            val latest = sessions[0]; val previous = sessions[1]
            val latestVol = (latest.weightPerCableKg * 2) * latest.totalReps
            val prevVol = (previous.weightPerCableKg * 2) * previous.totalReps
            if (prevVol <= 0f) return@map null
            ((latestVol - prevVol) / prevVol * 100).toInt()
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    init {
        scope.launch {
            workoutRepository.getAllSessions().collect { _workoutHistory.value = it.take(20) }
        }
    }

    override fun deleteWorkout(sessionId: String) {
        scope.launch { workoutRepository.deleteSession(sessionId) }
    }

    override fun deleteAllWorkouts() {
        scope.launch { workoutRepository.deleteAllSessions() }
    }
}
```

#### MainViewModel Delegation (Before/After)

```kotlin
// BEFORE (in MainViewModel):
val allWorkoutSessions: StateFlow<List<WorkoutSession>> = workoutRepository.getAllSessions()...
val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { ... }
fun deleteAllWorkouts() { viewModelScope.launch { workoutRepository.deleteAllSessions() } }

// AFTER (in MainViewModel):
val historyManager: HistoryManager  // injected or created in constructor
val allWorkoutSessions: StateFlow<List<WorkoutSession>> get() = historyManager.allWorkoutSessions
val groupedWorkoutHistory: StateFlow<List<HistoryItem>> get() = historyManager.groupedWorkoutHistory
fun deleteAllWorkouts() = historyManager.deleteAllWorkouts()
```

#### Koin Registration

```kotlin
single<HistoryManager> { DefaultHistoryManager(get(), get(), get<MainViewModel>().viewModelScope) }
// OR better: inject scope explicitly
factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

> **Note on scope**: Since managers need a CoroutineScope, and the natural scope is `viewModelScope`, the cleanest approach is for MainViewModel to create managers in its constructor body, passing `viewModelScope`. Koin registers the managers as `single` beans OR MainViewModel creates them inline. The inline approach is simpler and avoids circular dependency:

```kotlin
// In MainViewModel constructor body:
val historyManager: HistoryManager = DefaultHistoryManager(
    workoutRepository, personalRecordRepository, viewModelScope
)
```

---

### 1.2 SettingsManager

**Source**: Cluster_Settings (~80 lines, L244-L266, L1459-L1497, L2041-L2062)
**Risk**: Low -- consumers need to observe from new source, but all are read-only flows

#### Interface

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages user preferences and settings.
 * Thin wrapper around PreferencesManager that exposes derived StateFlows.
 */
interface SettingsManager {
    /** Full user preferences */
    val userPreferences: StateFlow<UserPreferences>

    /** Derived: current weight unit */
    val weightUnit: StateFlow<WeightUnit>

    /** Derived: stop at top preference */
    val stopAtTop: StateFlow<Boolean>

    /** Derived: video playback enabled */
    val enableVideoPlayback: StateFlow<Boolean>

    /** Derived: autoplay enabled (summaryCountdownSeconds != 0) */
    val autoplayEnabled: StateFlow<Boolean>

    // Setters
    fun setWeightUnit(unit: WeightUnit)
    fun setStopAtTop(enabled: Boolean)
    fun setEnableVideoPlayback(enabled: Boolean)
    fun setStallDetectionEnabled(enabled: Boolean)
    fun setAudioRepCountEnabled(enabled: Boolean)
    fun setSummaryCountdownSeconds(seconds: Int)
    fun setAutoStartCountdownSeconds(seconds: Int)
    fun setColorScheme(schemeIndex: Int)

    // Weight conversion utilities
    fun kgToDisplay(kg: Float): Float
    fun displayToKg(displayWeight: Float): Float
    fun formatWeight(kg: Float): String
}
```

#### Implementation

```kotlin
class DefaultSettingsManager(
    private val preferencesManager: PreferencesManager,
    private val bleRepository: BleRepository,
    private val scope: CoroutineScope
) : SettingsManager {

    override val userPreferences: StateFlow<UserPreferences> =
        preferencesManager.preferencesFlow
            .stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    override val weightUnit: StateFlow<WeightUnit> =
        userPreferences.map { it.weightUnit }
            .stateIn(scope, SharingStarted.Eagerly, WeightUnit.KG)

    override val stopAtTop: StateFlow<Boolean> =
        userPreferences.map { it.stopAtTop }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val enableVideoPlayback: StateFlow<Boolean> =
        userPreferences.map { it.enableVideoPlayback }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override val autoplayEnabled: StateFlow<Boolean> =
        userPreferences.map { it.summaryCountdownSeconds != 0 }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun setWeightUnit(unit: WeightUnit) {
        scope.launch { preferencesManager.setWeightUnit(unit) }
    }

    override fun setStopAtTop(enabled: Boolean) {
        scope.launch { preferencesManager.setStopAtTop(enabled) }
    }

    // ... (other setters follow same pattern)

    override fun setColorScheme(schemeIndex: Int) {
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
            preferencesManager.setColorScheme(schemeIndex)
            (bleRepository as? KableBleRepository)?.setLastColorSchemeIndex(schemeIndex)
        }
    }

    override fun kgToDisplay(kg: Float): Float {
        return if (weightUnit.value == WeightUnit.LB) kg * 2.20462f else kg
    }

    override fun displayToKg(displayWeight: Float): Float {
        return if (weightUnit.value == WeightUnit.LB) displayWeight / 2.20462f else displayWeight
    }

    override fun formatWeight(kg: Float): String {
        val unit = weightUnit.value
        val value = kgToDisplay(kg)
        val formatted = if (value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            String.format("%.1f", value)
        }
        return "$formatted ${unit.name.lowercase()}"
    }
}
```

#### Migration

```kotlin
// MainViewModel BEFORE:
val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow...
fun setWeightUnit(unit: WeightUnit) { viewModelScope.launch { preferencesManager.setWeightUnit(unit) } }

// MainViewModel AFTER:
val settingsManager: SettingsManager = DefaultSettingsManager(preferencesManager, bleRepository, viewModelScope)
val userPreferences: StateFlow<UserPreferences> get() = settingsManager.userPreferences
val weightUnit: StateFlow<WeightUnit> get() = settingsManager.weightUnit
fun setWeightUnit(unit: WeightUnit) = settingsManager.setWeightUnit(unit)
```

---

## Phase 2: Moderate Extractions (Some Shared State)

### 2.1 GamificationManager

**Source**: Cluster_Gamification (~40 lines, but logic embedded in saveWorkoutSession L3877-L3895)
**Risk**: Medium -- must receive events FROM ActiveSession (post-save callback pattern)

#### Interface

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.PRType
import kotlinx.coroutines.flow.SharedFlow

/**
 * Result of checking PRs and badges after a workout session is saved.
 * The WorkoutSessionManager calls processPostSaveEvents() and receives this.
 */
data class PostSaveResult(
    val prEvent: PRCelebrationEvent? = null,
    val newBadges: List<Badge> = emptyList(),
    /** True if a PR celebration sound will play (to avoid badge sound stacking) */
    val hasCelebrationSound: Boolean = false
)

/**
 * Manages gamification: badge checks, PR detection, and celebration events.
 * Called by WorkoutSessionManager after saving a workout session.
 */
interface GamificationManager {
    /** PR celebration events for UI */
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent>

    /** Badge earned events for UI */
    val badgeEarnedEvents: SharedFlow<List<Badge>>

    /**
     * Process post-save events: check for PRs and badges.
     * Called after workoutRepository.saveSession() completes.
     *
     * @param exerciseId The exercise that was performed (null for Just Lift)
     * @param workingReps Working reps completed
     * @param measuredWeightKg Actual measured weight per cable
     * @param workoutMode Program mode display name
     * @param isJustLift Whether this was Just Lift mode
     * @param isEchoMode Whether this was Echo mode
     * @return PostSaveResult with any celebrations to emit
     */
    suspend fun processPostSaveEvents(
        exerciseId: String?,
        workingReps: Int,
        measuredWeightKg: Float,
        workoutMode: String,
        isJustLift: Boolean,
        isEchoMode: Boolean
    ): PostSaveResult

    /** Emit badge earned sound (called from UI callback) */
    fun emitBadgeSound()

    /** Emit PR sound (called from UI callback) */
    fun emitPRSound()
}
```

#### Implementation Dependencies

```kotlin
class DefaultGamificationManager(
    private val gamificationRepository: GamificationRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val hapticEmitter: HapticEmitter, // shared haptic event bus
    private val scope: CoroutineScope
) : GamificationManager
```

> **Key Decision**: The `HapticEmitter` is a shared interface that both GamificationManager and WorkoutSessionManager use to emit `HapticEvent`s. MainViewModel owns the `MutableSharedFlow<HapticEvent>` and passes a thin wrapper.

---

### 2.2 BleConnectionManager

**Source**: Cluster_BleConnection (~200 lines, L684-L723, L847-L879, L1585-L1684)
**Risk**: Medium -- reads workoutState for connection-loss detection

#### Interface

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides read-only access to workout state for connection-loss detection.
 * This is the narrow interface that BleConnectionManager needs from the workout layer.
 */
interface WorkoutStateProvider {
    /** Current workout state for connection-loss alerting */
    val isWorkoutActiveForConnectionAlert: Boolean
}

/**
 * Manages BLE device scanning, connection lifecycle, and auto-connect.
 */
interface BleConnectionManager {
    /** BLE connection state (delegated from BleRepository) */
    val connectionState: StateFlow<ConnectionState>

    /** Discovered devices during scanning */
    val scannedDevices: StateFlow<List<ScannedDevice>>

    /** Whether auto-connect is in progress */
    val isAutoConnecting: StateFlow<Boolean>

    /** Connection error message (null when no error) */
    val connectionError: StateFlow<String?>

    /** True when connection was lost during an active workout */
    val connectionLostDuringWorkout: StateFlow<Boolean>

    fun startScanning()
    fun stopScanning()
    fun cancelScanOrConnection()
    fun connectToDevice(deviceAddress: String)
    fun disconnect()
    fun clearConnectionError()
    fun dismissConnectionLostAlert()
    fun cancelAutoConnecting()

    /**
     * Ensure device is connected before proceeding.
     * If already connected, calls onConnected immediately.
     * If not, initiates scan-and-connect with timeout.
     */
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {})

    /** Cancel an in-progress connection */
    fun cancelConnection()
}
```

#### Implementation Dependencies

```kotlin
class DefaultBleConnectionManager(
    private val bleRepository: BleRepository,
    private val settingsManager: SettingsManager,       // for LED color on connect
    private val workoutStateProvider: WorkoutStateProvider, // narrow interface for alerting
    private val scope: CoroutineScope
) : BleConnectionManager
```

> **Key Design**: The `WorkoutStateProvider` is a narrow interface (SAM-like) implemented by `WorkoutSessionManager`. This breaks the circular dependency: BleConnectionManager needs to know "is a workout active?" but doesn't need the full WorkoutSessionManager API.

---

## Phase 3: The Big One -- WorkoutSessionManager

### 3.1 Architecture: The Coordinator Pattern

The critical entanglement between ActiveSession and RoutineFlow is resolved with a **WorkoutCoordinator** -- a shared mutable state container that both sub-managers read and write through defined access points.

```
+--------------------------------------------------+
|            WorkoutSessionManager                  |
|  (public API - implements full workout contract)  |
+--------------------------------------------------+
         |                        |
         v                        v
+------------------+    +-------------------+
| ActiveSession-   |    | RoutineFlow-      |
| Engine           |    | Navigator         |
| (workout exec)   |    | (routine nav)     |
+------------------+    +-------------------+
         |                        |
         +----------+-------------+
                    |
         +---------------------+
         | WorkoutCoordinator  |
         | (shared state bus)  |
         +---------------------+
         | _workoutState       |
         | _workoutParameters  |
         | _currentExerciseIdx |
         | _currentSetIndex    |
         | _loadedRoutine      |
         | _repCount           |
         | _repRanges          |
         | collectedMetrics    |
         | currentSessionId    |
         | workoutStartTime    |
         | routineStartTime    |
         +---------------------+
```

### 3.2 WorkoutCoordinator (Shared State)

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepRanges
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared mutable state between ActiveSessionEngine and RoutineFlowNavigator.
 *
 * This is NOT an interface -- it's a concrete container that both engines reference.
 * Only WorkoutSessionManager creates it; sub-engines receive it as a constructor param.
 *
 * Convention: Properties prefixed with `_` are mutable and written by engines.
 * Properties without prefix are read-only StateFlows exposed to UI.
 */
class WorkoutCoordinator {
    // === Workout execution state ===
    val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    val _workoutParameters = MutableStateFlow(WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 10, weightPerCableKg = 10f, progressionRegressionKg = 0f,
        isJustLift = false, stopAtTop = false, warmupReps = 3
    ))
    val workoutParameters: StateFlow<WorkoutParameters> = _workoutParameters.asStateFlow()

    val _repCount = MutableStateFlow(RepCount())
    val repCount: StateFlow<RepCount> = _repCount.asStateFlow()

    val _repRanges = MutableStateFlow<RepRanges?>(null)
    val repRanges: StateFlow<RepRanges?> = _repRanges.asStateFlow()

    val _currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric.asStateFlow()

    // === Load baseline ===
    val _loadBaselineA = MutableStateFlow(0f)
    val _loadBaselineB = MutableStateFlow(0f)
    val loadBaselineA: StateFlow<Float> = _loadBaselineA.asStateFlow()
    val loadBaselineB: StateFlow<Float> = _loadBaselineB.asStateFlow()

    // === Heuristic force tracking (Echo mode) ===
    val _currentHeuristicKgMax = MutableStateFlow(0f)
    val currentHeuristicKgMax: StateFlow<Float> = _currentHeuristicKgMax.asStateFlow()
    var maxHeuristicKgMax = 0f

    // === Routine navigation state ===
    val _routineFlowState = MutableStateFlow<RoutineFlowState>(RoutineFlowState.NotInRoutine)
    val routineFlowState: StateFlow<RoutineFlowState> = _routineFlowState.asStateFlow()

    val _loadedRoutine = MutableStateFlow<Routine?>(null)
    val loadedRoutine: StateFlow<Routine?> = _loadedRoutine.asStateFlow()

    val _currentExerciseIndex = MutableStateFlow(0)
    val currentExerciseIndex: StateFlow<Int> = _currentExerciseIndex.asStateFlow()

    val _currentSetIndex = MutableStateFlow(0)
    val currentSetIndex: StateFlow<Int> = _currentSetIndex.asStateFlow()

    val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    val _skippedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val skippedExercises: StateFlow<Set<Int>> = _skippedExercises.asStateFlow()

    val _completedExercises = MutableStateFlow<Set<Int>>(emptySet())
    val completedExercises: StateFlow<Set<Int>> = _completedExercises.asStateFlow()

    val _currentSetRpe = MutableStateFlow<Int?>(null)
    val currentSetRpe: StateFlow<Int?> = _currentSetRpe.asStateFlow()

    // === Auto-stop UI state ===
    val _autoStopState = MutableStateFlow(com.devil.phoenixproject.data.repository.AutoStopUiState())
    val autoStopState: StateFlow<com.devil.phoenixproject.data.repository.AutoStopUiState> = _autoStopState.asStateFlow()

    val _autoStartCountdown = MutableStateFlow<Int?>(null)
    val autoStartCountdown: StateFlow<Int?> = _autoStartCountdown.asStateFlow()

    // === Timed exercise state ===
    val _timedExerciseRemainingSeconds = MutableStateFlow<Int?>(null)
    val timedExerciseRemainingSeconds: StateFlow<Int?> = _timedExerciseRemainingSeconds.asStateFlow()

    val _isCurrentExerciseBodyweight = MutableStateFlow(false)
    val isCurrentExerciseBodyweight: StateFlow<Boolean> = _isCurrentExerciseBodyweight.asStateFlow()

    // === Event buses (SharedFlows) ===
    val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val hapticEvents: SharedFlow<HapticEvent> = _hapticEvents.asSharedFlow()

    val _userFeedbackEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val userFeedbackEvents: SharedFlow<String> = _userFeedbackEvents.asSharedFlow()

    val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    val _badgeEarnedEvents = MutableSharedFlow<List<Badge>>()
    val badgeEarnedEvents: SharedFlow<List<Badge>> = _badgeEarnedEvents.asSharedFlow()

    // === Mutable internal state (not exposed as flows) ===
    var currentSessionId: String? = null
    var workoutStartTime: Long = 0
    var routineStartTime: Long = 0
    val collectedMetrics = mutableListOf<WorkoutMetric>()
    var currentRoutineSessionId: String? = null
    var currentRoutineName: String? = null
    var userAdjustedWeightDuringRest = false

    // Computed helpers
    val isWorkoutActive: Boolean
        get() {
            val state = _workoutState.value
            return state !is WorkoutState.Idle && state !is WorkoutState.Completed
        }
}
```

### 3.3 WorkoutSessionManager (Public API)

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutSessionManager.kt
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepRanges
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Complete workout session management -- the primary extraction from MainViewModel.
 * Encapsulates ActiveSession + RoutineFlow clusters and their critical coupling.
 *
 * This is the single interface that MainViewModel delegates to for all workout operations.
 * UI screens observe its StateFlows; MainViewModel re-exposes them for backward compatibility.
 */
interface WorkoutSessionManager {

    // =========================================================================
    // STATE FLOWS (read by UI)
    // =========================================================================

    val workoutState: StateFlow<WorkoutState>
    val workoutParameters: StateFlow<WorkoutParameters>
    val isWorkoutActive: Boolean
    val currentMetric: StateFlow<WorkoutMetric?>
    val repCount: StateFlow<RepCount>
    val repRanges: StateFlow<RepRanges?>
    val autoStopState: StateFlow<AutoStopUiState>
    val autoStartCountdown: StateFlow<Int?>
    val timedExerciseRemainingSeconds: StateFlow<Int?>
    val isCurrentExerciseBodyweight: StateFlow<Boolean>
    val currentHeuristicKgMax: StateFlow<Float>
    val loadBaselineA: StateFlow<Float>
    val loadBaselineB: StateFlow<Float>

    // Routine flow state
    val routineFlowState: StateFlow<RoutineFlowState>
    val routines: StateFlow<List<Routine>>
    val loadedRoutine: StateFlow<Routine?>
    val currentExerciseIndex: StateFlow<Int>
    val currentSetIndex: StateFlow<Int>
    val skippedExercises: StateFlow<Set<Int>>
    val completedExercises: StateFlow<Set<Int>>
    val currentSetRpe: StateFlow<Int?>

    // Event streams
    val hapticEvents: SharedFlow<HapticEvent>
    val userFeedbackEvents: SharedFlow<String>
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent>
    val badgeEarnedEvents: SharedFlow<List<Badge>>

    // =========================================================================
    // WORKOUT LIFECYCLE
    // =========================================================================

    fun updateWorkoutParameters(params: WorkoutParameters)
    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false)
    fun stopWorkout(exitingWorkout: Boolean = false)
    fun stopAndReturnToSetReady()
    fun pauseWorkout()
    fun resumeWorkout()
    fun skipCountdown()
    fun resetForNewWorkout()
    fun recaptureLoadBaseline()
    fun resetLoadBaseline()

    // =========================================================================
    // ROUTINE MANAGEMENT
    // =========================================================================

    fun saveRoutine(routine: Routine)
    fun updateRoutine(routine: Routine)
    fun deleteRoutine(routineId: String)
    fun deleteRoutines(routineIds: Set<String>)
    fun getRoutineById(routineId: String): Routine?

    // Routine loading & navigation
    fun loadRoutine(routine: Routine)
    fun loadRoutineById(routineId: String)
    fun clearLoadedRoutine()
    fun getCurrentExercise(): RoutineExercise?
    fun enterRoutineOverview(routine: Routine)
    fun selectExerciseInOverview(index: Int)
    fun enterSetReady(exerciseIndex: Int, setIndex: Int)
    fun updateSetReadyWeight(weight: Float)
    fun updateSetReadyReps(reps: Int)
    fun updateSetReadyEchoLevel(level: EchoLevel)
    fun updateSetReadyEccentricLoad(percent: Int)
    fun startSetFromReady()
    fun returnToOverview()
    fun exitRoutineFlow()
    fun showRoutineComplete()

    // Exercise navigation
    fun advanceToNextExercise()
    fun jumpToExercise(index: Int)
    fun skipCurrentExercise()
    fun goToPreviousExercise()
    fun canGoBack(): Boolean
    fun canSkipForward(): Boolean
    fun getRoutineExerciseNames(): List<String>

    // Set progression
    fun proceedFromSummary()
    fun logRpeForCurrentSet(rpe: Int)
    fun skipRest()
    fun startNextSet()

    // Superset navigation
    fun setReadyPrev()
    fun setReadySkip()
    fun getCurrentSupersetExercises(): List<RoutineExercise>
    fun isInSuperset(): Boolean
    fun hasResumableProgress(): Boolean
    fun getResumableProgressInfo(): ResumableProgressInfo?

    // Superset CRUD
    fun createSuperset(routineId: String, name: String, exerciseIndices: List<Int>)
    fun updateSuperset(superset: Superset)
    fun deleteSuperset(supersetId: String, routineId: String)
    fun addExerciseToSuperset(supersetId: String, exerciseIndex: Int)
    fun removeExerciseFromSuperset(supersetId: String, exerciseIndex: Int)

    // =========================================================================
    // WEIGHT ADJUSTMENT (during workout)
    // =========================================================================

    fun adjustWeight(newWeightKg: Float)
    fun incrementWeight()
    fun decrementWeight()
    fun setWeightPreset(presetKg: Float)
    suspend fun getLastWeightForExercise(exerciseId: String): Float?
    suspend fun getPrWeightForExercise(exerciseId: String): Float?

    // =========================================================================
    // JUST LIFT MODE
    // =========================================================================

    fun prepareForJustLift()
    fun enableHandleDetection()
    fun disableHandleDetection()
    suspend fun getJustLiftDefaults(): JustLiftDefaults
    fun saveJustLiftDefaults(defaults: JustLiftDefaults)
    suspend fun getSingleExerciseDefaults(exerciseId: String): Any? // SingleExerciseDefaults from PreferencesManager
    fun saveSingleExerciseDefaults(defaults: Any) // SingleExerciseDefaults

    // =========================================================================
    // TRAINING CYCLES
    // =========================================================================

    fun loadRoutineFromCycle(cycleId: String, dayNumber: Int, routine: Routine)
    fun clearCycleContext()

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    /** Cancel all jobs -- called from MainViewModel.onCleared() */
    fun cleanup()
}
```

### 3.4 Implementation Class Signature

```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
package com.devil.phoenixproject.presentation.manager

class DefaultWorkoutSessionManager(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationRepository: GamificationRepository,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val syncTriggerManager: SyncTriggerManager?,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val settingsManager: SettingsManager,  // for reading autoplay, stopAtTop, etc.
    private val scope: CoroutineScope
) : WorkoutSessionManager, WorkoutStateProvider {

    // Internal coordinator holds all shared mutable state
    private val coordinator = WorkoutCoordinator()

    // Delegate all StateFlow properties to coordinator
    override val workoutState get() = coordinator.workoutState
    override val workoutParameters get() = coordinator.workoutParameters
    override val isWorkoutActive get() = coordinator.isWorkoutActive
    // ... etc

    // WorkoutStateProvider for BleConnectionManager
    override val isWorkoutActiveForConnectionAlert: Boolean
        get() = when (coordinator._workoutState.value) {
            is WorkoutState.Active, is WorkoutState.Countdown, is WorkoutState.Resting -> true
            else -> false
        }

    // All ~75 functions from ActiveSession + RoutineFlow clusters are implemented here.
    // The 4771-line monolith becomes a ~3000-line focused workout manager.

    // Companion object with all workout constants
    companion object {
        const val TEMP_SINGLE_EXERCISE_PREFIX = "temp_single_"
        const val AUTO_STOP_DURATION_SECONDS = 2.5f
        const val STALL_DURATION_SECONDS = 5.0f
        const val STALL_VELOCITY_LOW = 2.5
        const val STALL_VELOCITY_HIGH = 10.0
        const val STALL_MIN_POSITION = 10.0
        const val HANDLE_REST_THRESHOLD = 2.5
        const val MIN_RANGE_THRESHOLD = 50f
        const val AMRAP_STARTUP_GRACE_MS = 8000L
    }
}
```

### 3.5 How MainViewModel Delegates

```kotlin
// AFTER refactoring, MainViewModel becomes a thin facade:

class MainViewModel constructor(
    // Phase 1+2 managers created here
    bleRepository: BleRepository,
    workoutRepository: WorkoutRepository,
    exerciseRepository: ExerciseRepository,
    personalRecordRepository: PersonalRecordRepository,
    repCounter: RepCounterFromMachine,
    preferencesManager: PreferencesManager,
    gamificationRepository: GamificationRepository,
    trainingCycleRepository: TrainingCycleRepository,
    syncTriggerManager: SyncTriggerManager? = null,
    resolveWeightsUseCase: ResolveRoutineWeightsUseCase
) : ViewModel() {

    // ===== Create managers =====
    val settingsManager: SettingsManager = DefaultSettingsManager(
        preferencesManager, bleRepository, viewModelScope
    )

    val historyManager: HistoryManager = DefaultHistoryManager(
        workoutRepository, personalRecordRepository, viewModelScope
    )

    val workoutSessionManager: WorkoutSessionManager = DefaultWorkoutSessionManager(
        bleRepository, workoutRepository, exerciseRepository,
        personalRecordRepository, repCounter, preferencesManager,
        gamificationRepository, trainingCycleRepository, syncTriggerManager,
        resolveWeightsUseCase, settingsManager, viewModelScope
    )

    val bleConnectionManager: BleConnectionManager = DefaultBleConnectionManager(
        bleRepository, settingsManager,
        workoutSessionManager as WorkoutStateProvider,
        viewModelScope
    )

    // ===== Backward-compatible UI API (delegates) =====

    // Connection
    val connectionState get() = bleConnectionManager.connectionState
    val scannedDevices get() = bleConnectionManager.scannedDevices
    val isAutoConnecting get() = bleConnectionManager.isAutoConnecting
    val connectionError get() = bleConnectionManager.connectionError
    val connectionLostDuringWorkout get() = bleConnectionManager.connectionLostDuringWorkout
    fun startScanning() = bleConnectionManager.startScanning()
    fun connectToDevice(addr: String) = bleConnectionManager.connectToDevice(addr)
    fun disconnect() = bleConnectionManager.disconnect()
    fun ensureConnection(onOk: () -> Unit, onFail: () -> Unit = {}) =
        bleConnectionManager.ensureConnection(onOk, onFail)

    // Settings
    val userPreferences get() = settingsManager.userPreferences
    val weightUnit get() = settingsManager.weightUnit
    val stopAtTop get() = settingsManager.stopAtTop
    val autoplayEnabled get() = settingsManager.autoplayEnabled
    fun setWeightUnit(u: WeightUnit) = settingsManager.setWeightUnit(u)
    // ... etc

    // History
    val allWorkoutSessions get() = historyManager.allWorkoutSessions
    val groupedWorkoutHistory get() = historyManager.groupedWorkoutHistory
    val completedWorkouts get() = historyManager.completedWorkouts
    val workoutStreak get() = historyManager.workoutStreak
    fun deleteAllWorkouts() = historyManager.deleteAllWorkouts()

    // Workout
    val workoutState get() = workoutSessionManager.workoutState
    val workoutParameters get() = workoutSessionManager.workoutParameters
    val currentMetric get() = workoutSessionManager.currentMetric
    val repCount get() = workoutSessionManager.repCount
    val hapticEvents get() = workoutSessionManager.hapticEvents
    fun startWorkout(skip: Boolean = false, jl: Boolean = false) =
        workoutSessionManager.startWorkout(skip, jl)
    fun stopWorkout(exit: Boolean = false) = workoutSessionManager.stopWorkout(exit)
    // ... (all 75+ functions delegated)

    // Routines
    val routines get() = workoutSessionManager.routines
    val loadedRoutine get() = workoutSessionManager.loadedRoutine
    fun loadRoutine(r: Routine) = workoutSessionManager.loadRoutine(r)
    // ... etc

    // ===== UI Scaffolding (stays in MainViewModel - too thin to extract) =====
    private val _topBarTitle = MutableStateFlow("Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()
    fun updateTopBarTitle(title: String) { _topBarTitle.value = title }
    // ... topBarActions, topBarBackAction (total ~30 lines)

    // ===== Easter Eggs (stays in MainViewModel) =====
    val discoModeActive = bleRepository.discoModeActive
    fun toggleDiscoMode(enabled: Boolean) { /* 5 lines */ }
    // ... disco/simulator mode (~40 lines)

    // ===== Repositories exposed for other ViewModels =====
    val exerciseRepository = exerciseRepository
    val personalRecordRepository = personalRecordRepository

    // ===== Workout Setup Dialog =====
    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible = _isWorkoutSetupDialogVisible.asStateFlow()

    override fun onCleared() {
        super.onCleared()
        workoutSessionManager.cleanup()
        bleConnectionManager.let { /* cancel connection job if exposed */ }
    }
}
```

### 3.6 Constructor Progression

| Phase | Constructor Params | Lines |
|-------|-------------------|-------|
| Current | 10 repositories + use cases | 4771 |
| After Phase 1 | Same 10 (managers created inline) | ~4500 (-270) |
| After Phase 2 | Same 10 (more managers inline) | ~4250 (-520) |
| After Phase 3 | Same 10 (workout manager inline) | ~300 (facade) |

> **Note**: The constructor params don't change because MainViewModel creates managers internally using its own deps. However, if desired, Koin can create the managers separately and inject them, reducing MainViewModel's constructor to:
> ```kotlin
> class MainViewModel(
>     val settingsManager: SettingsManager,
>     val historyManager: HistoryManager,
>     val workoutSessionManager: WorkoutSessionManager,
>     val bleConnectionManager: BleConnectionManager,
>     val exerciseRepository: ExerciseRepository,  // still needed by some screens
>     val personalRecordRepository: PersonalRecordRepository  // still needed by some screens
> ) : ViewModel()
> ```

---

## Koin DI Configuration

### Phase 1-2: Managers Created Inline (Simplest)

No Koin changes needed. MainViewModel creates managers in its constructor body. This avoids circular dependencies and scope issues.

### Phase 3 (Optional): Full DI

```kotlin
val appModule = module {
    // Repositories (unchanged)
    single<BleRepository> { KableBleRepository(get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get()) }
    // ... etc

    // Use Cases (unchanged)
    single { RepCounterFromMachine() }
    factory { ResolveRoutineWeightsUseCase(get()) }

    // Managers (new)
    // Note: These need viewModelScope, so they're created by MainViewModel
    // OR use a custom scope. For now, MainViewModel creates them inline.

    // ViewModels
    factory {
        MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}
```

---

## Risk Assessment

| Phase | Component | Risk | Mitigation |
|-------|-----------|------|------------|
| 1 | HistoryManager | **Trivial** | Pure read-only flows, no behavior change possible |
| 1 | SettingsManager | **Low** | Simple setters, derived flows. Test: verify pref changes propagate |
| 2 | GamificationManager | **Medium** | Embedded in saveWorkoutSession. Must extract callback cleanly. Test: verify PR/badge detection |
| 2 | BleConnectionManager | **Medium** | Connection-loss detection needs WorkoutStateProvider. Test: verify alerts fire during active workout |
| 3 | WorkoutSessionManager | **HIGH** | ~3000 lines of tightly coupled logic with race conditions, guards, and timing. **Must have characterization tests first.** |
| 3 | WorkoutCoordinator | **Medium** | Shared mutable state -- must ensure thread safety (existing synchronized patterns). Risk of subtle ordering bugs |

### Critical Risks for Phase 3

1. **Job lifecycle**: All workout Jobs (workoutJob, restTimerJob, bodyweightTimerJob, etc.) must use the injected `scope`, not `viewModelScope`. Verify `scope.launch {}` behaves identically to `viewModelScope.launch {}`.

2. **Race conditions**: The existing guards (`stopWorkoutInProgress`, `setCompletionInProgress`, `handleDetectionEnabledTimestamp`) rely on being in a single class. Moving to WorkoutCoordinator preserves this since both engines reference the same coordinator instance.

3. **Init block collectors**: The 8 coroutine collectors in MainViewModel's init block must move to WorkoutSessionManager's initialization. They must start in the same order with the same scope.

4. **viewModelScope.launch in callbacks**: Several functions use `viewModelScope.launch` inside callbacks or in deeply nested code. All of these must be changed to `scope.launch`.

### Recommended Execution Order

1. Extract HistoryManager (prove the pattern works, build confidence)
2. Extract SettingsManager (similar pattern, adds cross-manager dependency)
3. Write characterization tests for workout flows (Safety Inspector work)
4. Extract BleConnectionManager (introduces WorkoutStateProvider pattern)
5. Extract GamificationManager (introduces post-save callback pattern)
6. Extract WorkoutSessionManager (the big move, with all patterns proven)

---

## File Locations Summary

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── presentation/
│   ├── viewmodel/
│   │   └── MainViewModel.kt           (facade, ~300 lines after Phase 3)
│   └── manager/                        (NEW directory)
│       ├── HistoryManager.kt           (interface + data classes)
│       ├── DefaultHistoryManager.kt    (implementation)
│       ├── SettingsManager.kt          (interface)
│       ├── DefaultSettingsManager.kt   (implementation)
│       ├── GamificationManager.kt      (interface + PostSaveResult)
│       ├── DefaultGamificationManager.kt
│       ├── BleConnectionManager.kt     (interface + WorkoutStateProvider)
│       ├── DefaultBleConnectionManager.kt
│       ├── WorkoutCoordinator.kt       (shared state container)
│       ├── WorkoutSessionManager.kt    (interface -- the big one)
│       └── DefaultWorkoutSessionManager.kt  (~3000 lines)
└── domain/model/
    └── (unchanged -- all models stay here)
```

Data classes that currently live in MainViewModel.kt move to their managers:
- `HistoryItem`, `SingleSessionHistoryItem`, `GroupedRoutineHistoryItem` -> `HistoryManager.kt`
- `TopBarAction` -> stays in `MainViewModel.kt` (UI scaffolding stays)
- `JustLiftDefaults` -> stays in `MainViewModel.kt` or moves to `WorkoutSessionManager.kt`
- `ResumableProgressInfo` -> `WorkoutSessionManager.kt`

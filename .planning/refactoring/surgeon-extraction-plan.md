# Surgeon's Extraction Plan: MainViewModel Decomposition

## Overview

This document provides a precise, step-by-step extraction plan for decomposing `MainViewModel.kt` (4,771 lines) into focused manager classes. Each phase includes exact line numbers, functions, state variables, import changes, before/after code snippets, and risk mitigation.

**Source file**: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
**Target directory**: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/`

---

## Phase 1a: HistoryManager Extraction

**Risk**: Trivial | **Lines removed from MainViewModel**: ~130 | **New file size**: ~170 lines

### Data Classes to Move

| Class | Current Location | Target |
|-------|-----------------|--------|
| `HistoryItem` (sealed) | L52-54 | `HistoryManager.kt` |
| `SingleSessionHistoryItem` | L56-58 | `HistoryManager.kt` |
| `GroupedRoutineHistoryItem` | L60-68 | `HistoryManager.kt` |

### State Variables to Move

| Variable | Current Location | Type |
|----------|-----------------|------|
| `_workoutHistory` / `workoutHistory` | L201-202 | `MutableStateFlow<List<WorkoutSession>>` |
| `allWorkoutSessions` | L307-313 | `StateFlow<List<WorkoutSession>>` (computed) |
| `groupedWorkoutHistory` | L315-333 | `StateFlow<List<HistoryItem>>` (computed) |
| `allPersonalRecords` | L335-341 | `StateFlow<List<PersonalRecord>>` (computed) |
| `personalBests` | L297-303 | `StateFlow<List<PersonalRecordEntity>>` (computed) |
| `completedWorkouts` | L343-345 | `StateFlow<Int?>` (computed) |
| `workoutStreak` | L351-381 | `StateFlow<Int?>` (computed) |
| `progressPercentage` | L383-391 | `StateFlow<Int?>` (computed) |

### Functions to Move

| Function | Current Location | Signature |
|----------|-----------------|-----------|
| `deleteWorkout()` | L2037-2038 | `fun deleteWorkout(sessionId: String)` |
| `deleteAllWorkouts()` | L1581-1582 | `fun deleteAllWorkouts()` |

### Init Block Collector to Move

```kotlin
// L483-487: Load recent history
viewModelScope.launch {
    workoutRepository.getAllSessions().collect { sessions ->
        _workoutHistory.value = sessions.take(20)
    }
}
```

### New File: `HistoryManager.kt`

```kotlin
// shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/HistoryManager.kt
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

// --- Data classes moved from MainViewModel ---

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

// --- Manager ---

class HistoryManager(
    private val workoutRepository: WorkoutRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val scope: CoroutineScope
) {
    private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()

    val allWorkoutSessions: StateFlow<List<WorkoutSession>> =
        workoutRepository.getAllSessions()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { sessions ->
        // Exact copy of L315-333 logic
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
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPersonalRecords: StateFlow<List<PersonalRecord>> =
        personalRecordRepository.getAllPRsGrouped()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personalBests: StateFlow<List<PersonalRecordEntity>> =
        workoutRepository.getAllPersonalRecords()
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedWorkouts: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        sessions.size.takeIf { it > 0 }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    val workoutStreak: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        // Exact copy of L351-381 logic
        if (sessions.isEmpty()) return@map null
        val workoutDates = sessions
            .map { KmpLocalDate.fromTimestamp(it.timestamp) }
            .distinctBy { it.toKey() }
            .sortedDescending()
        val today = KmpLocalDate.today()
        val lastWorkoutDate = workoutDates.first()
        if (lastWorkoutDate.isBefore(today.minusDays(1))) return@map null
        var streak = 1
        for (i in 1 until workoutDates.size) {
            val expected = workoutDates[i - 1].minusDays(1)
            if (workoutDates[i] == expected) streak++ else break
        }
        streak
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    val progressPercentage: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        // Exact copy of L383-391 logic
        if (sessions.size < 2) return@map null
        val latest = sessions[0]
        val previous = sessions[1]
        val latestVol = (latest.weightPerCableKg * 2) * latest.totalReps
        val prevVol = (previous.weightPerCableKg * 2) * previous.totalReps
        if (prevVol <= 0f) return@map null
        ((latestVol - prevVol) / prevVol * 100).toInt()
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Moved from MainViewModel init L483-487
        scope.launch {
            workoutRepository.getAllSessions().collect { sessions ->
                _workoutHistory.value = sessions.take(20)
            }
        }
    }

    fun deleteWorkout(sessionId: String) {
        scope.launch { workoutRepository.deleteSession(sessionId) }
    }

    fun deleteAllWorkouts() {
        scope.launch { workoutRepository.deleteAllSessions() }
    }
}
```

### MainViewModel Changes (Before/After)

**Before** (declarations, L52-68, L201-202, L297-391):
```kotlin
// 17 lines of data classes
sealed class HistoryItem { ... }
data class SingleSessionHistoryItem(...) : HistoryItem() { ... }
data class GroupedRoutineHistoryItem(...) : HistoryItem()

// In class body:
private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()
val allWorkoutSessions: StateFlow<List<WorkoutSession>> = workoutRepository.getAllSessions()...
val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { ... }
val allPersonalRecords: StateFlow<List<PersonalRecord>> = personalRecordRepository.getAllPRsGrouped()...
val personalBests: StateFlow<List<PersonalRecordEntity>> = workoutRepository.getAllPersonalRecords()...
val completedWorkouts: StateFlow<Int?> = allWorkoutSessions.map { ... }
val workoutStreak: StateFlow<Int?> = allWorkoutSessions.map { ... }
val progressPercentage: StateFlow<Int?> = allWorkoutSessions.map { ... }
fun deleteWorkout(sessionId: String) { viewModelScope.launch { workoutRepository.deleteSession(sessionId) } }
fun deleteAllWorkouts() { viewModelScope.launch { workoutRepository.deleteAllSessions() } }
```

**After** (delegation):
```kotlin
// At top of file, add import:
import com.devil.phoenixproject.presentation.manager.HistoryManager
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem

// In class body, early in constructor:
val historyManager = HistoryManager(workoutRepository, personalRecordRepository, viewModelScope)

// Delegation properties (one-liners):
val workoutHistory: StateFlow<List<WorkoutSession>> get() = historyManager.workoutHistory
val allWorkoutSessions: StateFlow<List<WorkoutSession>> get() = historyManager.allWorkoutSessions
val groupedWorkoutHistory: StateFlow<List<HistoryItem>> get() = historyManager.groupedWorkoutHistory
val allPersonalRecords: StateFlow<List<PersonalRecord>> get() = historyManager.allPersonalRecords
@Suppress("unused")
val personalBests: StateFlow<List<PersonalRecordEntity>> get() = historyManager.personalBests
val completedWorkouts: StateFlow<Int?> get() = historyManager.completedWorkouts
val workoutStreak: StateFlow<Int?> get() = historyManager.workoutStreak
val progressPercentage: StateFlow<Int?> get() = historyManager.progressPercentage
fun deleteWorkout(sessionId: String) = historyManager.deleteWorkout(sessionId)
fun deleteAllWorkouts() = historyManager.deleteAllWorkouts()
```

### Import Changes in MainViewModel

**Remove** (if unused after extraction - verify):
- `com.devil.phoenixproject.util.KmpLocalDate` (only used in workoutStreak, which moves)

**Add**:
- `com.devil.phoenixproject.presentation.manager.HistoryManager`
- `com.devil.phoenixproject.presentation.manager.HistoryItem` (if referenced by other code in MainViewModel)

### Verification Steps

1. Build: `./gradlew :shared:compileKotlinJvm` (fast check for compilation)
2. Run existing tests: `./gradlew :androidApp:testDebugUnitTest`
3. Manual smoke test: Open app, check History tab shows workout data
4. Verify: `groupedWorkoutHistory` still correctly groups routine sessions

---

## Phase 1b: SettingsManager Extraction

**Risk**: Low | **Lines removed from MainViewModel**: ~80 | **New file size**: ~120 lines

### State Variables to Move

| Variable | Current Location | Type |
|----------|-----------------|------|
| `userPreferences` | L245-246 | `StateFlow<UserPreferences>` (computed from preferencesManager) |
| `weightUnit` | L248-250 | `StateFlow<WeightUnit>` (derived) |
| `stopAtTop` | L252-254 | `StateFlow<Boolean>` (derived) |
| `enableVideoPlayback` | L256-258 | `StateFlow<Boolean>` (derived) |
| `autoplayEnabled` | L263-265 | `StateFlow<Boolean>` (derived) |

### Functions to Move

| Function | Current Location | Notes |
|----------|-----------------|-------|
| `setWeightUnit()` | L1459-1461 | |
| `setStopAtTop()` | L1463-1465 | |
| `setEnableVideoPlayback()` | L1467-1469 | |
| `setStallDetectionEnabled()` | L1473-1475 | |
| `setAudioRepCountEnabled()` | L1477-1479 | |
| `setSummaryCountdownSeconds()` | L1481-1484 | |
| `setAutoStartCountdownSeconds()` | L1486-1488 | |
| `setColorScheme()` | L1490-1497 | Uses both `bleRepository` and `preferencesManager` |
| `kgToDisplay()` | L2041-2045 | Takes `unit` parameter - signature differs from Architect's |
| `displayToKg()` | L2047-2051 | Takes `unit` parameter |
| `formatWeight()` | L2053-2062 | Takes `unit` parameter |

### New File: `SettingsManager.kt`

```kotlin
// shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsManager(
    private val preferencesManager: PreferencesManager,
    private val bleRepository: BleRepository,
    private val scope: CoroutineScope
) {
    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(scope, SharingStarted.Eagerly, UserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(scope, SharingStarted.Eagerly, WeightUnit.KG)

    val stopAtTop: StateFlow<Boolean> = userPreferences
        .map { it.stopAtTop }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.summaryCountdownSeconds != 0 }
        .stateIn(scope, SharingStarted.Eagerly, true)

    fun setWeightUnit(unit: WeightUnit) {
        scope.launch { preferencesManager.setWeightUnit(unit) }
    }

    fun setStopAtTop(enabled: Boolean) {
        scope.launch { preferencesManager.setStopAtTop(enabled) }
    }

    fun setEnableVideoPlayback(enabled: Boolean) {
        scope.launch { preferencesManager.setEnableVideoPlayback(enabled) }
    }

    fun setStallDetectionEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setStallDetectionEnabled(enabled) }
    }

    fun setAudioRepCountEnabled(enabled: Boolean) {
        scope.launch { preferencesManager.setAudioRepCountEnabled(enabled) }
    }

    fun setSummaryCountdownSeconds(seconds: Int) {
        Logger.d("setSummaryCountdownSeconds: Setting value to $seconds")
        scope.launch { preferencesManager.setSummaryCountdownSeconds(seconds) }
    }

    fun setAutoStartCountdownSeconds(seconds: Int) {
        scope.launch { preferencesManager.setAutoStartCountdownSeconds(seconds) }
    }

    fun setColorScheme(schemeIndex: Int) {
        scope.launch {
            bleRepository.setColorScheme(schemeIndex)
            preferencesManager.setColorScheme(schemeIndex)
            (bleRepository as? KableBleRepository)?.setLastColorSchemeIndex(schemeIndex)
        }
    }

    // NOTE: Weight conversion functions keep their original signatures with explicit unit parameter.
    // This preserves backward compatibility with all call sites.
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
        val formatted = if (value % 1 == 0f) {
            value.toInt().toString()
        } else {
            value.format(2).trimEnd('0').trimEnd('.')
        }
        return "$formatted ${unit.name.lowercase()}"
    }
}
```

### MainViewModel Delegation

```kotlin
// In constructor body:
val settingsManager = SettingsManager(preferencesManager, bleRepository, viewModelScope)

// Delegation:
val userPreferences: StateFlow<UserPreferences> get() = settingsManager.userPreferences
val weightUnit: StateFlow<WeightUnit> get() = settingsManager.weightUnit
val stopAtTop: StateFlow<Boolean> get() = settingsManager.stopAtTop
val enableVideoPlayback: StateFlow<Boolean> get() = settingsManager.enableVideoPlayback
val autoplayEnabled: StateFlow<Boolean> get() = settingsManager.autoplayEnabled
fun setWeightUnit(unit: WeightUnit) = settingsManager.setWeightUnit(unit)
fun setStopAtTop(enabled: Boolean) = settingsManager.setStopAtTop(enabled)
fun setEnableVideoPlayback(enabled: Boolean) = settingsManager.setEnableVideoPlayback(enabled)
fun setStallDetectionEnabled(enabled: Boolean) = settingsManager.setStallDetectionEnabled(enabled)
fun setAudioRepCountEnabled(enabled: Boolean) = settingsManager.setAudioRepCountEnabled(enabled)
fun setSummaryCountdownSeconds(seconds: Int) = settingsManager.setSummaryCountdownSeconds(seconds)
fun setAutoStartCountdownSeconds(seconds: Int) = settingsManager.setAutoStartCountdownSeconds(seconds)
fun setColorScheme(schemeIndex: Int) = settingsManager.setColorScheme(schemeIndex)
fun kgToDisplay(kg: Float, unit: WeightUnit) = settingsManager.kgToDisplay(kg, unit)
fun displayToKg(display: Float, unit: WeightUnit) = settingsManager.displayToKg(display, unit)
fun formatWeight(kg: Float, unit: WeightUnit) = settingsManager.formatWeight(kg, unit)
```

### Cross-Cluster Impact

The `userPreferences`, `autoplayEnabled`, and `stopAtTop` flows are READ by the ActiveSession and RoutineFlow clusters. After extraction, these clusters must read from `settingsManager` instead of direct `_workoutParameters` references. Since `settingsManager` is a field on MainViewModel, the internal code can reference `settingsManager.userPreferences.value` wherever it currently reads `userPreferences.value`.

**IMPORTANT**: Do NOT break internal references. The workout code reads `userPreferences.value` directly (e.g., L518, L611, L695, L3611). Since the delegation property `val userPreferences get() = settingsManager.userPreferences` preserves the same accessor, all existing call sites continue to work unchanged.

### Verification Steps

1. Build: `./gradlew :shared:compileKotlinJvm`
2. Run tests: `./gradlew :androidApp:testDebugUnitTest`
3. Manual: Change weight unit in Settings, verify it propagates to all screens
4. Manual: Change color scheme, verify LED updates on device

---

## Phase 2a: BleConnectionManager Extraction

**Risk**: Medium | **Lines removed**: ~200 | **New file size**: ~220 lines

### State Variables to Move

| Variable | Current Location | Type |
|----------|-----------------|------|
| `connectionState` | L128 | `StateFlow<ConnectionState>` (delegated from bleRepo) |
| `_scannedDevices` / `scannedDevices` | L198-199 | `MutableStateFlow<List<ScannedDevice>>` |
| `_isAutoConnecting` / `isAutoConnecting` | L396-397 | `MutableStateFlow<Boolean>` |
| `_connectionError` / `connectionError` | L399-400 | `MutableStateFlow<String?>` |
| `_pendingConnectionCallback` | L402 | `(() -> Unit)?` |
| `_connectionLostDuringWorkout` / `connectionLostDuringWorkout` | L417-418 | `MutableStateFlow<Boolean>` |
| `connectionJob` | L446 | `Job?` |

### Functions to Move

| Function | Current Location | Lines |
|----------|-----------------|-------|
| `startScanning()` | L847-849 | 3 |
| `stopScanning()` | L851-853 | 3 |
| `cancelScanOrConnection()` | L855-864 | 10 |
| `connectToDevice()` | L866-875 | 10 |
| `disconnect()` | L877-879 | 3 |
| `clearConnectionError()` | L1585-1587 | 3 |
| `dismissConnectionLostAlert()` | L1589-1591 | 3 |
| `cancelAutoConnecting()` | L1593-1599 | 7 |
| `ensureConnection()` | L1607-1683 | 77 |
| `cancelConnection()` | L1684-? | ~10 |

### Init Block Collector to Move

```kotlin
// L684-723: Connection state observer
viewModelScope.launch {
    var wasConnected = false
    bleRepository.connectionState.collect { state ->
        when (state) {
            is ConnectionState.Connected -> {
                wasConnected = true
                _connectionLostDuringWorkout.value = false
                // LED color scheme initialization uses settingsManager
                val savedColorScheme = userPreferences.value.colorScheme
                bleRepository.setColorScheme(savedColorScheme)
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                if (wasConnected) {
                    // CROSS-CLUSTER: reads _workoutState to determine if alert needed
                    val workoutActive = when (_workoutState.value) {
                        is WorkoutState.Active,
                        is WorkoutState.Countdown,
                        is WorkoutState.Resting -> true
                        else -> false
                    }
                    if (workoutActive) {
                        _connectionLostDuringWorkout.value = true
                    }
                }
                wasConnected = false
            }
            else -> {}
        }
    }
}
```

### WorkoutStateProvider Interface

The connection state observer at L705-711 reads `_workoutState.value` to determine if connection loss should trigger an alert. This is resolved via a narrow interface:

```kotlin
/**
 * Narrow interface that BleConnectionManager uses to check workout state.
 * Implemented by MainViewModel (for now) or WorkoutSessionManager (Phase 3).
 */
interface WorkoutStateProvider {
    val isWorkoutActiveForConnectionAlert: Boolean
}
```

### New File: `BleConnectionManager.kt`

```kotlin
// shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt
package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface WorkoutStateProvider {
    val isWorkoutActiveForConnectionAlert: Boolean
}

class BleConnectionManager(
    private val bleRepository: BleRepository,
    private val settingsManager: SettingsManager,
    private val workoutStateProvider: WorkoutStateProvider,
    private val scope: CoroutineScope
) {
    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var _pendingConnectionCallback: (() -> Unit)? = null

    private val _connectionLostDuringWorkout = MutableStateFlow(false)
    val connectionLostDuringWorkout: StateFlow<Boolean> = _connectionLostDuringWorkout.asStateFlow()

    private var connectionJob: Job? = null

    init {
        // Connection state observer - moved from MainViewModel L684-723
        scope.launch {
            var wasConnected = false
            bleRepository.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        wasConnected = true
                        _connectionLostDuringWorkout.value = false
                        val savedColorScheme = settingsManager.userPreferences.value.colorScheme
                        bleRepository.setColorScheme(savedColorScheme)
                        Logger.d { "Initialized LED color scheme to saved preference: $savedColorScheme" }
                    }
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        if (wasConnected && workoutStateProvider.isWorkoutActiveForConnectionAlert) {
                            Logger.w { "Connection lost during active workout! Showing reconnection dialog." }
                            _connectionLostDuringWorkout.value = true
                        }
                        wasConnected = false
                    }
                    else -> {}
                }
            }
        }
    }

    fun startScanning() { scope.launch { bleRepository.startScanning() } }
    fun stopScanning() { scope.launch { bleRepository.stopScanning() } }

    fun cancelScanOrConnection() {
        scope.launch {
            bleRepository.stopScanning()
            if (connectionState.value is ConnectionState.Connecting) {
                bleRepository.cancelConnection()
            }
        }
    }

    fun connectToDevice(deviceAddress: String) {
        scope.launch {
            val device = scannedDevices.value.find { it.address == deviceAddress }
            if (device != null) {
                bleRepository.connect(device)
            } else {
                Logger.e { "Device not found in scanned devices: $deviceAddress" }
            }
        }
    }

    fun disconnect() { scope.launch { bleRepository.disconnect() } }
    fun clearConnectionError() { _connectionError.value = null }
    fun dismissConnectionLostAlert() { _connectionLostDuringWorkout.value = false }

    fun cancelAutoConnecting() {
        _isAutoConnecting.value = false
        _connectionError.value = null
        connectionJob?.cancel()
        connectionJob = null
        scope.launch { bleRepository.stopScanning() }
    }

    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) {
        // Exact copy of L1607-1683 logic, using scope instead of viewModelScope
        // ... (full implementation copied verbatim, replacing viewModelScope with scope)
    }

    fun cancelConnection() {
        // Exact copy of L1684+ logic
    }

    fun cancelConnectionJob() {
        connectionJob?.cancel()
    }
}
```

### MainViewModel WorkoutStateProvider Implementation

**During Phase 2a**, MainViewModel itself implements `WorkoutStateProvider`:

```kotlin
class MainViewModel constructor(...) : ViewModel(), WorkoutStateProvider {
    // ...
    override val isWorkoutActiveForConnectionAlert: Boolean
        get() = when (_workoutState.value) {
            is WorkoutState.Active, is WorkoutState.Countdown, is WorkoutState.Resting -> true
            else -> false
        }

    // Create BleConnectionManager
    val bleConnectionManager = BleConnectionManager(
        bleRepository, settingsManager, this, viewModelScope
    )
}
```

**In Phase 3**, the `WorkoutStateProvider` implementation moves to `DefaultWorkoutSessionManager`, and BleConnectionManager receives it through that instead.

### Verification Steps

1. Build
2. Run tests
3. Manual: Scan for device, connect, verify connection state updates
4. Manual: Disconnect during workout, verify ConnectionLostDialog appears
5. Manual: ensureConnection auto-reconnect flow

---

## Phase 2b: GamificationManager Extraction

**Risk**: Medium | **Lines removed**: ~25 (but disentangles from saveWorkoutSession) | **New file size**: ~80 lines

### What Moves

The gamification logic is **embedded** inside `saveWorkoutSession()` at L3877-3895 and L3828-3875. Rather than extracting the full `saveWorkoutSession()` function (which belongs to Phase 3), we extract the **post-save** logic into a callable manager.

### State Variables to Move

| Variable | Currently Owned By | Notes |
|----------|-------------------|-------|
| `_prCelebrationEvent` / `prCelebrationEvent` | L237-238 | `MutableSharedFlow<PRCelebrationEvent>` |
| `_badgeEarnedEvents` / `badgeEarnedEvents` | L241-242 | `MutableSharedFlow<List<Badge>>` |

### Functions to Move

| Function | Current Location |
|----------|-----------------|
| `emitBadgeSound()` | L1530-1534 |
| `emitPRSound()` | L1539-1543 |

### New Function to Create

```kotlin
/**
 * Process post-save gamification events.
 * Extracted from saveWorkoutSession() L3828-3895.
 * Returns whether a celebration sound was emitted (for sound stacking prevention).
 */
suspend fun processPostSaveEvents(
    exerciseId: String?,
    workingReps: Int,
    measuredWeightKg: Float,
    programMode: ProgramMode,
    isJustLift: Boolean,
    isEchoMode: Boolean
): Boolean  // returns hasCelebrationSound
```

### New File: `GamificationManager.kt`

```kotlin
// shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt
package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class GamificationManager(
    private val gamificationRepository: GamificationRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val hapticEvents: MutableSharedFlow<HapticEvent>,
    private val scope: CoroutineScope
) {
    private val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    private val _badgeEarnedEvents = MutableSharedFlow<List<Badge>>()
    val badgeEarnedEvents: SharedFlow<List<Badge>> = _badgeEarnedEvents.asSharedFlow()

    /**
     * Check for PRs and badges after a workout session is saved.
     * Extracted from MainViewModel.saveWorkoutSession() L3828-3895.
     * @return true if a celebration sound will play (to avoid sound stacking)
     */
    suspend fun processPostSaveEvents(
        exerciseId: String?,
        workingReps: Int,
        measuredWeightKg: Float,
        programMode: ProgramMode,
        isJustLift: Boolean,
        isEchoMode: Boolean
    ): Boolean {
        var hasCelebrationSound = false

        // PR check (L3830-3875 logic)
        exerciseId?.let { exId ->
            if (workingReps > 0 && !isJustLift && !isEchoMode) {
                try {
                    val workoutMode = programMode.displayName
                    val timestamp = currentTimeMillis()
                    val result = personalRecordRepository.updatePRsIfBetter(
                        exerciseId = exId,
                        weightPerCableKg = measuredWeightKg,
                        reps = workingReps,
                        workoutMode = workoutMode,
                        timestamp = timestamp
                    )
                    result.onSuccess { brokenPRs ->
                        if (brokenPRs.isNotEmpty()) {
                            hasCelebrationSound = true
                            val exercise = exerciseRepository.getExerciseById(exId)
                            _prCelebrationEvent.emit(
                                PRCelebrationEvent(
                                    exerciseName = exercise?.name ?: "Unknown Exercise",
                                    weightPerCableKg = measuredWeightKg,
                                    reps = workingReps,
                                    workoutMode = workoutMode,
                                    brokenPRTypes = brokenPRs
                                )
                            )
                        }
                    }.onFailure { e ->
                        Logger.e(e) { "Error updating PR: ${e.message}" }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error checking PR: ${e.message}" }
                }
            }
        }

        // Badge check (L3877-3895 logic)
        try {
            gamificationRepository.updateStats()
            val newBadges = gamificationRepository.checkAndAwardBadges()
            if (newBadges.isNotEmpty()) {
                if (!hasCelebrationSound) {
                    hapticEvents.emit(HapticEvent.BADGE_EARNED)
                }
                _badgeEarnedEvents.emit(newBadges)
                Logger.d("New badges earned: ${newBadges.map { it.name }}")
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating gamification: ${e.message}" }
        }

        return hasCelebrationSound
    }

    fun emitBadgeSound() {
        scope.launch { hapticEvents.emit(HapticEvent.BADGE_EARNED) }
    }

    fun emitPRSound() {
        scope.launch { hapticEvents.emit(HapticEvent.PERSONAL_RECORD) }
    }
}
```

### saveWorkoutSession() Refactoring (in MainViewModel for now, moves to Phase 3)

Replace L3828-3895 in `saveWorkoutSession()` with:

```kotlin
// BEFORE (L3828-3895): ~67 lines of inline PR checking and badge checking
// AFTER: 10 lines
val hasCelebrationSound = gamificationManager.processPostSaveEvents(
    exerciseId = params.selectedExerciseId,
    workingReps = working,
    measuredWeightKg = measuredPerCableKg,
    programMode = params.programMode,
    isJustLift = params.isJustLift,
    isEchoMode = params.isEchoMode
)
```

### MainViewModel Wiring

```kotlin
// GamificationManager needs access to _hapticEvents (which stays in MainViewModel for now)
val gamificationManager = GamificationManager(
    gamificationRepository, personalRecordRepository, exerciseRepository,
    _hapticEvents, viewModelScope
)

// Delegation
val prCelebrationEvent: SharedFlow<PRCelebrationEvent> get() = gamificationManager.prCelebrationEvent
val badgeEarnedEvents: SharedFlow<List<Badge>> get() = gamificationManager.badgeEarnedEvents
fun emitBadgeSound() = gamificationManager.emitBadgeSound()
fun emitPRSound() = gamificationManager.emitPRSound()
```

### Verification Steps

1. Build
2. Run tests
3. Manual: Complete a workout that breaks a PR, verify celebration dialog
4. Manual: Complete a workout that earns a badge, verify badge popup
5. Verify sound stacking prevention: PR + badge in same session should only play PR sound

---

## Phase 3: WorkoutSessionManager Extraction (THE BIG ONE)

**Risk**: HIGH | **Lines removed**: ~3,600 | **New file size**: ~3,200 lines | **MainViewModel after**: ~300 lines

### Strategy: Single Class, Not Coordinator Split

After analyzing the actual code, the Architect's Coordinator/ActiveSessionEngine/RoutineFlowNavigator split adds unnecessary complexity for the initial extraction. The two clusters are too tightly interleaved (sharing 30+ variables, calling each other's functions directly) to split cleanly without introducing bugs.

**Decision**: Extract everything into a single `DefaultWorkoutSessionManager` class first. The coordinator split can be a FUTURE refactoring once the extraction is stable and well-tested.

### Step-by-Step Migration Sequence

#### Step 3.1: Create the Shell (empty class with all interfaces)

Create `DefaultWorkoutSessionManager.kt` with:
- All state variables declared
- All function stubs that `throw NotImplementedError()`
- Constructor with all dependencies
- Implement `WorkoutStateProvider`

Build to verify compilation.

#### Step 3.2: Move companion object constants

Move from MainViewModel L92-126 to `DefaultWorkoutSessionManager.companion`:

```kotlin
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
```

#### Step 3.3: Move state variables (ALL at once)

Move all state variables from MainViewModel to DefaultWorkoutSessionManager. This is a bulk move that includes:

**ActiveSession state** (from MainViewModel body):
- `_workoutState` / `workoutState` / `isWorkoutActive` (L130-142)
- `_currentMetric` / `currentMetric` (L147-148)
- `_currentHeuristicKgMax` / `currentHeuristicKgMax` / `maxHeuristicKgMax` (L152-154)
- `_loadBaselineA` / `_loadBaselineB` / `loadBaselineA` / `loadBaselineB` (L159-162)
- `_workoutParameters` / `workoutParameters` (L164-175)
- `_userAdjustedWeightDuringRest` (L179)
- `_repCount` / `repCount` (L181-182)
- `_timedExerciseRemainingSeconds` / `timedExerciseRemainingSeconds` (L186-187)
- `_repRanges` / `repRanges` (L189-190)
- `_autoStopState` / `autoStopState` (L192-193)
- `_autoStartCountdown` / `autoStartCountdown` (L195-196)
- `_hapticEvents` / `hapticEvents` (L404-408)
- `_userFeedbackEvents` / `userFeedbackEvents` (L411-415)
- `currentSessionId` (L420)
- `workoutStartTime` / `routineStartTime` (L421-422)
- `collectedMetrics` (L423)
- `currentRoutineSessionId` / `currentRoutineName` (L425-426)
- `autoStopStartTime` / `autoStopTriggered` / `autoStopStopRequested` (L432-434)
- `stopWorkoutInProgress` / `setCompletionInProgress` (L437-439)
- `currentHandleState` (L440)
- `stallStartTime` / `isCurrentlyStalled` (L443-444)
- `monitorDataCollectionJob` / `autoStartJob` / `restTimerJob` / `bodyweightTimerJob` (L447-450)
- `bodyweightSetsCompletedInRoutine` / `previousExerciseWasBodyweight` (L453-455)
- `repEventsCollectionJob` / `workoutJob` (L456-457)
- `skipCountdownRequested` / `isCurrentWorkoutTimed` (L459-461)
- `_isCurrentTimedCableExercise` / `isCurrentTimedCableExercise` (L464-469)
- `_isCurrentExerciseBodyweight` / `isCurrentExerciseBodyweight` (L471-472)
- `handleDetectionEnabledTimestamp` / `HANDLE_DETECTION_DEBOUNCE_MS` (L476-477)

**RoutineFlow state**:
- `_routineFlowState` / `routineFlowState` (L144-145)
- `_routines` / `routines` (L268-269)
- `_loadedRoutine` / `loadedRoutine` (L271-272)
- `_currentExerciseIndex` / `currentExerciseIndex` (L278-279)
- `_currentSetIndex` / `currentSetIndex` (L281-282)
- `_skippedExercises` / `skippedExercises` (L285-286)
- `_completedExercises` / `completedExercises` (L288-289)
- `_currentSetRpe` / `currentSetRpe` (L292-293)

**TrainingCycle state**:
- `activeCycleId` / `activeCycleDayNumber` (L429-430)

**JustLift data class** (L4729-4770): `JustLiftDefaults` stays at file level or moves here.

**ResumableProgressInfo** (L2645-2651): Moves into the manager file.

#### Step 3.4: Move init block collectors (6 of 8)

These collectors move from MainViewModel's init block into DefaultWorkoutSessionManager's init block:

1. **L490-495**: Routine loading collector
2. **L498-509**: Exercise import
3. **L512-545**: RepCounter onRepEvent callback
4. **L550-599**: Handle state collector
5. **L602-637**: Deload event collector
6. **L640-647**: Rep events collector
7. **L649-660**: Global metricsFlow collector
8. **L662-682**: Heuristic data collector

**IMPORTANT**: All `viewModelScope.launch` calls become `scope.launch`.

#### Step 3.5: Move functions in dependency order

Move functions bottom-up (helpers first, callers last) to maintain compilability:

**Round 1 - Pure helpers (no cross-dependencies)**:
- `isBodyweightExercise()` (L4210)
- `isSingleExerciseMode()` (L4114)
- `calculateSetSummaryMetrics()` (L3947)
- `collectMetricForHistory()` (L843)
- `resetForNewWorkout()` (L1851)
- `recaptureLoadBaseline()` (L1864)
- `resetLoadBaseline()` (L1876)
- `resetAutoStopTimer()` (L3437)
- `resetStallTimer()` (L3448)
- `resetAutoStopState()` (L3461)
- `isInAmrapStartupGrace()` (L3485)
- `shouldEnableAutoStop()` (L3200)
- `requestAutoStop()` (L3506)
- `triggerAutoStop()` (L3515)

**Round 2 - Superset navigation helpers**:
- `getCurrentSupersetExercises()` (L2691)
- `isInSuperset()` (L2704)
- `getNextSupersetExerciseIndex()` (L2712)
- `getFirstSupersetExerciseIndex()` (L2732)
- `isAtEndOfSupersetCycle()` (L2743)
- `getSupersetRestSeconds()` (L2754)
- `findNextExerciseAfterCurrent()` (L2764)
- `getNextStep()` (L2376)
- `getPreviousStep()` (L2429)
- `hasNextStep()` (L2485)
- `hasPreviousStep()` (L2493)
- `calculateNextExerciseName()` (L4410)
- `calculateIsLastExercise()` (L4440)

**Round 3 - Routine CRUD and navigation**:
- `saveRoutine()` (L2064)
- `updateRoutine()` (L2068)
- `deleteRoutine()` (L2072)
- `deleteRoutines()` (L2079)
- `getRoutineById()` (L274)
- `resolveRoutineWeights()` (L2104)
- `loadRoutineInternal()` (L2127)
- `loadRoutine()` (L2087)
- `loadRoutineById()` (L2598)
- `enterRoutineOverview()` (L2199)
- `selectExerciseInOverview()` (L2218)
- `enterSetReady()` (L2229)
- `enterSetReadyWithAdjustments()` (L2278)
- `updateSetReadyWeight()` (L2322)
- `updateSetReadyReps()` (L2334)
- `updateSetReadyEchoLevel()` (L2345)
- `updateSetReadyEccentricLoad()` (L2357)
- `startSetFromReady()` (L2531)
- `returnToOverview()` (L2559)
- `exitRoutineFlow()` (L2570)
- `showRoutineComplete()` (L2581)
- `clearLoadedRoutine()` (L2628)
- `getCurrentExercise()` (L2634)
- `hasResumableProgress()` (L2657)
- `getResumableProgressInfo()` (L2673)
- `navigateToExerciseInternal()` (L1961)
- `advanceToNextExercise()` (L1882)
- `jumpToExercise()` (L1896)
- `skipCurrentExercise()` (L1995)
- `goToPreviousExercise()` (L2008)
- `canGoBack()` (L2018)
- `canSkipForward()` (L2025)
- `getRoutineExerciseNames()` (L2033)
- `logRpeForCurrentSet()` (L1846)

**Round 4 - Superset CRUD**:
- `createSuperset()` (L2791)
- `updateSuperset()` (L2827)
- `deleteSuperset()` (L2839)
- `addExerciseToSuperset()` (L2857)
- `removeExerciseFromSuperset()` (L2877)

**Round 5 - Weight adjustment**:
- `sendWeightUpdateToMachine()` (L3093)
- `adjustWeight()` (L3017)
- `incrementWeight()` (L3047)
- `decrementWeight()` (L3055)
- `setWeightPreset()` (L3063)
- `getLastWeightForExercise()` (L3070)
- `getPrWeightForExercise()` (L3082)

**Round 6 - Just Lift**:
- `enableHandleDetection()` (L2899)
- `disableHandleDetection()` (L2914)
- `prepareForJustLift()` (L2924)
- `getJustLiftDefaults()` (L2975)
- `saveJustLiftDefaults()` (L2992)
- `saveJustLiftDefaultsFromWorkout()` (L4123)
- `getSingleExerciseDefaults()` (L2957)
- `saveSingleExerciseDefaults()` (L2964)
- `saveSingleExerciseDefaultsFromWorkout()` (L4150)

**Round 7 - Training cycles**:
- `loadRoutineFromCycle()` (L2610)
- `clearCycleContext()` (L2623)
- `updateCycleProgressIfNeeded()` (L3914)

**Round 8 - Core workout lifecycle (HIGHEST RISK)**:
- `handleRepNotification()` (L733)
- `handleMonitorMetric()` (L784)
- `checkAutoStop()` (L3219)
- `startAutoStartTimer()` (L3125)
- `cancelAutoStartTimer()` (L3186)
- `saveWorkoutSession()` (L3739) -- with gamificationManager.processPostSaveEvents() call
- `handleSetCompletion()` (L3540)
- `updateWorkoutParameters()` (L881)
- `startWorkout()` (L897) -- 325 lines, THE function
- `stopWorkout()` (L1241)
- `stopAndReturnToSetReady()` (L1386)
- `pauseWorkout()` (L1417)
- `resumeWorkout()` (L1428)
- `restartCollectionJobs()` (L1438)
- `skipCountdown()` (L1228)
- `startRestTimer()` (L4226)
- `advanceToNextSetInSingleExercise()` (L4460)
- `startWorkoutOrSetReady()` (L4526)
- `startNextSetOrExercise()` (L4542)
- `skipRest()` (L4664)
- `startNextSet()` (L4686)
- `proceedFromSummary()` (L1705)
- `setReadyPrev()` (L2504)
- `setReadySkip()` (L2518)

#### Step 3.6: Update all `viewModelScope` to `scope`

Every `viewModelScope.launch` in the moved code becomes `scope.launch`. This is a simple search-and-replace within the new file.

#### Step 3.7: Wire up in MainViewModel

```kotlin
class MainViewModel constructor(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    val exerciseRepository: ExerciseRepository,
    val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationRepository: GamificationRepository,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val syncTriggerManager: SyncTriggerManager? = null,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase
) : ViewModel() {

    // Phase 1 managers
    val settingsManager = SettingsManager(preferencesManager, bleRepository, viewModelScope)
    val historyManager = HistoryManager(workoutRepository, personalRecordRepository, viewModelScope)

    // Phase 2 managers
    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val gamificationManager = GamificationManager(
        gamificationRepository, personalRecordRepository, exerciseRepository,
        _hapticEvents, viewModelScope
    )

    // Phase 3 manager
    val workoutSessionManager = DefaultWorkoutSessionManager(
        bleRepository = bleRepository,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        personalRecordRepository = personalRecordRepository,
        repCounter = repCounter,
        preferencesManager = preferencesManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = trainingCycleRepository,
        syncTriggerManager = syncTriggerManager,
        resolveWeightsUseCase = resolveWeightsUseCase,
        settingsManager = settingsManager,
        scope = viewModelScope
    )

    val bleConnectionManager = BleConnectionManager(
        bleRepository, settingsManager,
        workoutSessionManager, // implements WorkoutStateProvider
        viewModelScope
    )

    // ===== Backward-compatible delegation =====

    // Workout state
    val workoutState get() = workoutSessionManager.workoutState
    val workoutParameters get() = workoutSessionManager.workoutParameters
    val isWorkoutActive get() = workoutSessionManager.isWorkoutActive
    val currentMetric get() = workoutSessionManager.currentMetric
    val repCount get() = workoutSessionManager.repCount
    val repRanges get() = workoutSessionManager.repRanges
    val autoStopState get() = workoutSessionManager.autoStopState
    val autoStartCountdown get() = workoutSessionManager.autoStartCountdown
    val timedExerciseRemainingSeconds get() = workoutSessionManager.timedExerciseRemainingSeconds
    val isCurrentExerciseBodyweight get() = workoutSessionManager.isCurrentExerciseBodyweight
    val currentHeuristicKgMax get() = workoutSessionManager.currentHeuristicKgMax
    val loadBaselineA get() = workoutSessionManager.loadBaselineA
    val loadBaselineB get() = workoutSessionManager.loadBaselineB
    val hapticEvents get() = workoutSessionManager.hapticEvents
    val userFeedbackEvents get() = workoutSessionManager.userFeedbackEvents
    val prCelebrationEvent get() = gamificationManager.prCelebrationEvent
    val badgeEarnedEvents get() = gamificationManager.badgeEarnedEvents

    // Routine state
    val routineFlowState get() = workoutSessionManager.routineFlowState
    val routines get() = workoutSessionManager.routines
    val loadedRoutine get() = workoutSessionManager.loadedRoutine
    val currentExerciseIndex get() = workoutSessionManager.currentExerciseIndex
    val currentSetIndex get() = workoutSessionManager.currentSetIndex
    val skippedExercises get() = workoutSessionManager.skippedExercises
    val completedExercises get() = workoutSessionManager.completedExercises
    val currentSetRpe get() = workoutSessionManager.currentSetRpe

    // Workout lifecycle
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
    // ... (all ~75 functions delegated, one line each)

    // ===== UI Scaffolding (stays here) =====
    private val _topBarTitle = MutableStateFlow("Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()
    fun updateTopBarTitle(title: String) { _topBarTitle.value = title }

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()
    fun setTopBarActions(actions: List<TopBarAction>) { _topBarActions.value = actions }
    fun clearTopBarActions() { _topBarActions.value = emptyList() }

    private val _topBarBackAction = MutableStateFlow<(() -> Unit)?>(null)
    val topBarBackAction: StateFlow<(() -> Unit)?> = _topBarBackAction.asStateFlow()
    fun setTopBarBackAction(action: () -> Unit) { _topBarBackAction.value = action }
    fun clearTopBarBackAction() { _topBarBackAction.value = null }

    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible: StateFlow<Boolean> = _isWorkoutSetupDialogVisible.asStateFlow()

    // ===== Easter Eggs (stays here) =====
    val discoModeActive: StateFlow<Boolean> = bleRepository.discoModeActive
    fun unlockDiscoMode() { /* L1503-1508 */ }
    fun toggleDiscoMode(enabled: Boolean) { /* L1510-1516 */ }
    fun emitDiscoSound() { /* L1521-1525 */ }
    fun unlockSimulatorMode() { /* L1564-1568 */ }
    fun toggleSimulatorMode(enabled: Boolean) { /* L1571-1574 */ }
    fun isSimulatorModeUnlocked(): Boolean = preferencesManager.isSimulatorModeUnlocked()
    fun testSounds() { /* L1549-1560 */ }

    override fun onCleared() {
        super.onCleared()
        workoutSessionManager.cleanup()
        bleConnectionManager.cancelConnectionJob()
    }
}
```

### How the Safety Inspector's 32 Tests Map to the New Structure

| Test Group | Tests | Original Target | New Target | Migration |
|-----------|-------|----------------|------------|-----------|
| P1: resetForNewWorkout | T1.1-T1.3 | MainViewModel | MainViewModel delegates to workoutSessionManager | Tests call `viewModel.resetForNewWorkout()` unchanged |
| P2: Just Lift auto-reset | T2.1-T2.3 | MainViewModel | workoutSessionManager.proceedFromSummary() path | Tests call `viewModel` methods unchanged |
| P3: RPE logging | T3.1-T3.3 | MainViewModel | workoutSessionManager | Tests unchanged |
| P4: Pause/Resume | T4.1-T4.4 | MainViewModel | workoutSessionManager | Tests unchanged |
| P5: Handle detection | T5.1-T5.3 | MainViewModel | workoutSessionManager | Tests unchanged |
| P6: Stop guards | T6.1-T6.3 | MainViewModel | workoutSessionManager | Tests unchanged |
| P7: stopAndReturnToSetReady | T7.1 | MainViewModel | workoutSessionManager | Tests unchanged |
| P8: Weight tracking | T8.1-T8.2 | MainViewModel | workoutSessionManager | Tests unchanged |
| P9: Rep counting | T9.1-T9.2 | MainViewModel | workoutSessionManager | Tests unchanged |
| P10: Start workout | T10.1-T10.3 | MainViewModel | workoutSessionManager | Tests unchanged |
| P11: isWorkoutActive | T11.1-T11.5 | MainViewModel | workoutSessionManager | Tests unchanged |

**Key insight**: Because MainViewModel retains delegation properties with identical signatures, ALL 32 tests continue to work unchanged against `MainViewModel`. The tests are behavioral contracts on the PUBLIC API, which doesn't change. After extraction, we can optionally write unit tests against `DefaultWorkoutSessionManager` directly for faster test execution.

### Rollback Strategy

1. **Git branch**: Create `refactoring/phase-3-workout-session-manager` branch
2. **Commit per round**: Each round of function moves gets its own commit
3. **Build after each round**: `./gradlew :shared:compileKotlinJvm`
4. **Test after each round**: `./gradlew :androidApp:testDebugUnitTest`
5. **If build breaks**: `git diff` to see what broke, fix it
6. **If tests fail**: Revert the last round's commit, investigate
7. **Nuclear option**: `git checkout main -- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` to restore original

### KMP Considerations

1. **`currentTimeMillis()`**: This is an `expect fun` in the same package. Works identically in the new file since it's in the same module. No import needed.
2. **`KmpUtils.randomUUID()`**: Same situation - `expect fun` in util package. Import from `com.devil.phoenixproject.util.KmpUtils`.
3. **`viewModelScope` -> `scope`**: The injected `CoroutineScope` parameter replaces all `viewModelScope` references. This is the single most common change.
4. **`import kotlinx.coroutines.IO`**: Valid in commonMain, needed if any function uses `Dispatchers.IO`.
5. **`synchronized(lock)`**: Used for thread safety in this codebase. The pattern works in KMP commonMain.

### Riskiest Extraction Steps

| Risk | Step | Mitigation |
|------|------|------------|
| **CRITICAL** | Moving `startWorkout()` (325 lines) | Move last, after all its dependencies. Test with a real device immediately after. |
| **HIGH** | Init block collectors (8 coroutines) | Move all 6 workout-related collectors together. Order matters - keep same order. |
| **HIGH** | `handleSetCompletion()` -> `saveWorkoutSession()` chain | These call `gamificationManager.processPostSaveEvents()`, `syncTriggerManager`, and training cycle update. Test the full save-celebrate-badge flow. |
| **MEDIUM** | Job lifecycle (`workoutJob`, `restTimerJob`, etc.) | All jobs use `scope.launch` instead of `viewModelScope.launch`. Verify cancellation in `cleanup()`. |
| **MEDIUM** | `ensureConnection()` usage in `startSetFromReady()` | This BLE function stays in `BleConnectionManager`. The workout manager must call through MainViewModel or receive BleConnectionManager as a dependency. |
| **LOW** | `_hapticEvents` shared reference | GamificationManager and DefaultWorkoutSessionManager both emit to the same `MutableSharedFlow`. Pass it as constructor parameter to both. |

### Estimated MainViewModel Size After All Extractions

| Section | Lines |
|---------|-------|
| Package + imports | 15 |
| TopBarAction data class | 6 |
| Class declaration + constructor | 15 |
| Manager instantiation | 25 |
| History delegation | 12 |
| Settings delegation | 18 |
| Connection delegation | 12 |
| Gamification delegation | 6 |
| Workout delegation (~75 functions) | 80 |
| UI scaffolding (top bar, dialog) | 25 |
| Easter eggs (disco, simulator, sounds) | 45 |
| Repositories (exerciseRepository, personalRecordRepository) | 4 |
| onCleared() | 8 |
| **TOTAL** | **~271 lines** |

---

## Final Checklist

### Before Starting
- [ ] Create branch `refactoring/extract-managers` from `refactoring_monoliths`
- [ ] Ensure all existing tests pass: `./gradlew :androidApp:testDebugUnitTest`
- [ ] Create `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/` directory

### Phase 1a: HistoryManager
- [ ] Create `HistoryManager.kt` with data classes + implementation
- [ ] Move `HistoryItem`, `SingleSessionHistoryItem`, `GroupedRoutineHistoryItem` from MainViewModel
- [ ] Replace MainViewModel state with delegation to HistoryManager
- [ ] Remove moved init block collector
- [ ] Build succeeds
- [ ] Tests pass
- [ ] Commit: "refactor: extract HistoryManager from MainViewModel"

### Phase 1b: SettingsManager
- [ ] Create `SettingsManager.kt`
- [ ] Replace MainViewModel state/functions with delegation
- [ ] Build succeeds
- [ ] Tests pass
- [ ] Commit: "refactor: extract SettingsManager from MainViewModel"

### Phase 2a: BleConnectionManager
- [ ] Create `BleConnectionManager.kt` with `WorkoutStateProvider` interface
- [ ] MainViewModel implements `WorkoutStateProvider`
- [ ] Move connection state, functions, and init collector
- [ ] Build succeeds
- [ ] Tests pass
- [ ] Manual test: device connection flow
- [ ] Commit: "refactor: extract BleConnectionManager from MainViewModel"

### Phase 2b: GamificationManager
- [ ] Create `GamificationManager.kt`
- [ ] Extract PR/badge logic from `saveWorkoutSession()` into `processPostSaveEvents()`
- [ ] Replace inline logic with manager call
- [ ] Build succeeds
- [ ] Tests pass
- [ ] Manual test: PR celebration, badge popup
- [ ] Commit: "refactor: extract GamificationManager from MainViewModel"

### Phase 3: WorkoutSessionManager
- [ ] Create `DefaultWorkoutSessionManager.kt` shell
- [ ] Move constants (companion object)
- [ ] Move all state variables
- [ ] Move init block collectors (6 of 8)
- [ ] Move functions in 8 rounds (build after each)
- [ ] Replace all `viewModelScope` with `scope`
- [ ] Wire up delegation in MainViewModel
- [ ] Move `JustLiftDefaults` and `ResumableProgressInfo`
- [ ] Build succeeds
- [ ] ALL tests pass
- [ ] Manual test: full workout lifecycle (Just Lift, Routine, Single Exercise)
- [ ] Manual test: superset navigation
- [ ] Manual test: auto-stop, auto-start
- [ ] Manual test: pause/resume
- [ ] Commit: "refactor: extract WorkoutSessionManager from MainViewModel"

### After All Phases
- [ ] Verify MainViewModel is ~300 lines
- [ ] Verify no functionality regression
- [ ] Update any Compose Preview functions that reference moved types
- [ ] Clean up unused imports in MainViewModel
- [ ] Run full build: `./gradlew build`
- [ ] Squash or organize commits for PR

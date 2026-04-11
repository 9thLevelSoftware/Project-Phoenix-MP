## State Management Audit Results

### 🔴 Critical State Issues (Block Release)

| File | Issue | Impact |
|------|-------|--------|
| `WorkoutCoordinator.kt` | Mixed @Volatile fields with StateFlow - `autoStopStartTime`, `autoStopTriggered`, `stallStartTime` use @Volatile while `_workoutState` is StateFlow | Inconsistent state reads between volatile fields and StateFlow values during rapid state transitions; potential for race conditions in auto-stop detection |
| `ActiveSessionEngine.kt` | `handleDetectionEnabledTimestamp` debounce checked without atomic operations (line ~1444) | Idempotency failure under rapid enable/disable calls from BLE layer; could reset state machine mid-grab |
| `DefaultWorkoutSessionManager.kt` | `proceedFromSummary()` launches new coroutine on every call without in-progress guard | Multiple rapid calls could spawn concurrent state transition coroutines causing duplicate navigation or state corruption |
| `ActiveSessionEngine.kt` | `stopWorkoutInProgress.compareAndSet()` pattern used but state can be left stuck if exception thrown before finally block | If `saveWorkoutSession()` throws, `stopWorkoutInProgress` remains true, permanently blocking future stop attempts |
| `SyncManager.kt` | Batched push updates `lastSyncTime` after each batch but failure mid-sequence leaves partial sync committed | Partial sync state corruption - already-pushed batches stamped but not all data synced, causing missed deltas on retry |
| `RoutineFlowManager.kt` | No guard against rapid navigation calls (`jumpToExercise`, `skipCurrentExercise`) | Concurrent navigation calls during BLE command delays could corrupt `currentExerciseIndex` state |

### 🟠 State Warnings

| File | Concern | Risk |
|------|---------|------|
| `WorkoutCoordinator.kt` | No `distinctUntilChanged()` on high-frequency flows (`currentMetric`, `repCount`, `autoStopState`) | Unnecessary UI recompositions during rapid metric updates (100Hz+ BLE data), battery drain |
| `MainViewModel.kt` | All coordinator StateFlows exposed directly without `distinctUntilChanged()` wrapper | View layer receives duplicate emissions during rapid workout state changes |
| `ActiveSessionEngine.kt` | `motionStartDetector.events` collected without buffer/backpressure handling | Motion start events could be lost during rapid metric updates if detector emits faster than collection |
| `BleConnectionManager.kt` | Connection state observer checks `isWorkoutActiveForConnectionAlert` outside of synchronized block | Race condition: connection lost during workout check could miss alert if state changes between read and emission |
| `ActiveSessionEngine.kt` | `pendingWeightChangeKg` is @Volatile but set/read from multiple coroutines without atomic compare-and-set | TOCTOU race: weight change could be applied twice or lost if mid-set adjustment happens during state transition |
| `SyncTriggerManager.kt` | `lastSyncAttemptMillis` stored in memory only (not persisted) | App restart resets throttle, potentially allowing immediate re-sync after crash/force-stop |
| `ActiveSessionEngine.kt` | Session persistence (`saveWorkoutSession`) is fire-and-forget launched in separate coroutine | State transition to SetSummary occurs before persistence confirmed; app termination mid-save loses session data |
| `RoutineFlowManager.kt` | Superset navigation (`getNextStep`, `getPreviousStep`) reads from `coordinator._skippedExercises.value` without snapshot isolation | Concurrent modification of skipped exercises during navigation could yield incorrect step calculation |

### 🟡 Recommendations

1. **StateFlow Atomicity:** Replace @Volatile fields in WorkoutCoordinator with `MutableStateFlow` and use `compareAndSet` pattern consistently:
   ```kotlin
   // Instead of @Volatile autoStopStartTime
   val autoStopStartTime = MutableStateFlow<Long?>(null)
   // Use compareAndSet for atomic updates
   autoStopStartTime.compareAndSet(null, currentTimeMillis())
   ```

2. **Deduplication Operators:** Add `distinctUntilChanged()` to high-frequency flows:
   ```kotlin
   val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric
       .distinctUntilChanged { old, new -> old?.timestamp == new?.timestamp }
       .asStateFlow()
   ```

3. **Coroutine Guards:** Wrap `proceedFromSummary()` and navigation methods with atomic in-progress flags:
   ```kotlin
   private val transitionInProgress = MutableStateFlow(false)
   fun proceedFromSummary() {
       if (!transitionInProgress.compareAndSet(false, true)) return
       // ... existing code with finally { transitionInProgress.value = false }
   }
   ```

4. **Persistence Barrier:** Make session persistence synchronous or add barrier before state transitions:
   ```kotlin
   // In handleSetCompletion, wait for persistence
   saveWorkoutSession() // suspend call
   coordinator._workoutState.value = summary // only after persistence
   ```

5. **Sync State Recovery:** Persist `lastSyncAttemptMillis` and `consecutiveFailures` to preferences for crash recovery.

6. **Rest Timer State:** `restSecondsRemaining` uses custom 100ms tick loop reading StateFlow - consider using `kotlinx.coroutines.flow.flow` with `transformLatest` for cleaner pause/extend semantics.

7. **Buffer Configuration:** `MutableSharedFlow` for haptic events uses `extraBufferCapacity = 10` with `DROP_OLDEST` - this is good practice, but consider if buffer should be larger for burst scenarios (PR celebration + badge + rep count).

8. **Scope Isolation:** Consider using structured concurrency with child scopes for sub-managers to enable independent cancellation:
   ```kotlin
   // In DefaultWorkoutSessionManager
   private val routineScope = scope + SupervisorJob()
   // Pass routineScope to RoutineFlowManager for isolated lifecycle
   ```

### 🟢 State Management Strengths

- **Clean Architecture:** State centralized in `WorkoutCoordinator` with manager decomposition pattern; all business logic isolated from UI layer
- **Sharing Strategies:** Proper `SharingStarted.Eagerly` usage in `SettingsManager` for preference flows
- **Compare-and-Set Guards:** `stopWorkoutInProgress.compareAndSet()` and `setCompletionInProgress.compareAndSet()` prevent duplicate critical operations
- **Buffer Overflow Handling:** `DROP_OLDEST` policy on SharedFlows prevents memory pressure during rapid BLE data
- **Exception Safety:** All scope.launch collectors in managers have try-catch blocks to prevent iOS SIGABRT crashes
- **StateFlow Immutability:** Public exposes use `.asStateFlow()` to prevent external mutation
- **Testability:** `DWSMTestHarness` provides comprehensive state testing with Turbine for flow assertions
- **Lifecycle Awareness:** `onCleared()` in MainViewModel properly cancels all jobs with NonCancellable context for BLE disconnect
- **Thread-Safe Collections:** `collectedMetrics` uses `MutableStateFlow<List<WorkoutMetric>>` with snapshot semantics rather than mutable list

### 📋 Files Audited

| File | Lines | Domain |
|------|-------|--------|
| `shared/src/commonMain/kotlin/.../DefaultWorkoutSessionManager.kt` | ~730 | Workout lifecycle orchestration |
| `shared/src/commonMain/kotlin/.../WorkoutCoordinator.kt` | ~290 | Shared state bus |
| `shared/src/commonMain/kotlin/.../ActiveSessionEngine.kt` | ~3240 | Real-time metrics, auto-stop, session persistence |
| `shared/src/commonMain/kotlin/.../RoutineFlowManager.kt` | ~890 | Routine navigation, superset logic |
| `shared/src/commonMain/kotlin/.../BleConnectionManager.kt` | ~270 | Hardware connection state |
| `shared/src/commonMain/kotlin/.../SyncManager.kt` | ~450 | Cloud sync state |
| `shared/src/commonMain/kotlin/.../SyncTriggerManager.kt` | ~90 | Sync throttling |
| `shared/src/commonMain/kotlin/.../GamificationManager.kt` | ~200 | PR/badge state |
| `shared/src/commonMain/kotlin/.../SettingsManager.kt` | ~130 | User preferences |
| `shared/src/commonMain/kotlin/.../MainViewModel.kt` | ~630 | UI state aggregation |

---
*Audit completed: 2026-03-28*  
*Scope: State consistency, reactivity, persistence, synchronization, lifecycle*

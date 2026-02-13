# MainViewModel Archaeologist Context Map

## Executive Summary

`MainViewModel.kt` is a **4,771-line monolith** with **10 constructor-injected dependencies**, **~45 state variables** (MutableStateFlow/MutableState/private vars), and **~75 public/internal functions**. It is the central hub for the entire application, handling everything from BLE device scanning to workout execution, gamification, routine management, superset navigation, personal records, and user preferences.

The file naturally clusters into **8 distinct responsibility domains**. The most tangled areas are the Active Workout Session cluster (which touches nearly everything) and the Routine Flow/Navigation cluster (which shares state with both workout execution and UI scaffolding). The cleanest extraction targets are **BLE Connection**, **Gamification**, **Settings/Preferences**, and **History/Stats** -- these have minimal cross-cluster coupling and can be extracted with surgical precision.

---

## Constructor Dependencies

```
1. bleRepository: BleRepository           -- BLE device communication
2. workoutRepository: WorkoutRepository   -- Workout/routine/PR persistence
3. exerciseRepository: ExerciseRepository -- Exercise library (public val)
4. personalRecordRepository: PersonalRecordRepository -- PR tracking (public val)
5. repCounter: RepCounterFromMachine      -- Rep counting state machine
6. preferencesManager: PreferencesManager -- User settings persistence
7. gamificationRepository: GamificationRepository -- XP, badges, stats
8. trainingCycleRepository: TrainingCycleRepository -- Training cycle progress
9. syncTriggerManager: SyncTriggerManager? -- Cloud sync (optional)
10. resolveWeightsUseCase: ResolveRoutineWeightsUseCase -- PR% weight resolution
```

---

## Cluster Analysis

### Cluster_BleConnection
**Purpose**: Device scanning, connection lifecycle, auto-connect, disconnect, connection error handling.

**Dependencies used**:
- `bleRepository` (scan, connect, disconnect, cancelConnection, stopScanning, scanAndConnect)
- `preferencesManager` (LED color scheme on connect)

**State variables**:
- `connectionState` (delegated from bleRepository)
- `_scannedDevices` / `scannedDevices`
- `_isAutoConnecting` / `isAutoConnecting`
- `_connectionError` / `connectionError`
- `_connectionLostDuringWorkout` / `connectionLostDuringWorkout`
- `_pendingConnectionCallback`
- `connectionJob`

**Functions**:
- `startScanning()` (L847)
- `stopScanning()` (L851)
- `cancelScanOrConnection()` (L855)
- `connectToDevice()` (L866)
- `disconnect()` (L877)
- `ensureConnection()` (L1607)
- `cancelConnection()` (L1684)
- `cancelAutoConnecting()` (L1593)
- `clearConnectionError()` (L1585)
- `dismissConnectionLostAlert()` (L1589)
- Connection state observer in `init` block (L684-L723)

**Cross-cluster coupling**: LOW
- Reads `_workoutState` to determine if connection loss warrants an alert (L705-L712)
- Reads `userPreferences` for LED color scheme on connect (L695-L697)

**Lines**: ~200 lines

---

### Cluster_ActiveSession
**Purpose**: Core workout execution engine -- starting, stopping, pausing, resuming workouts, BLE command construction, rep counting, metric collection, auto-start, auto-stop, stall detection, bodyweight/timed exercise handling, set completion.

**Dependencies used**:
- `bleRepository` (sendWorkoutCommand, startActiveWorkoutPolling, stopWorkout, restartMonitorPolling, enableHandleDetection, enableJustLiftWaitingMode, sendStopCommand, metricsFlow, heuristicData, repEvents, deloadOccurredEvents, handleState)
- `repCounter` (process, configure, reset, resetCountsOnly, updatePhaseFromPosition, updatePositionRangesContinuously, setInitialBaseline, hasMeaningfulRange, isInDangerZone, shouldStopWorkout, getRepCount, getRepRanges)
- `exerciseRepository` (getExerciseById for exercise name)
- `workoutRepository` (saveSession, saveMetrics)
- `personalRecordRepository` (updatePRsIfBetter)
- `gamificationRepository` (updateStats, checkAndAwardBadges)
- `syncTriggerManager` (onWorkoutCompleted)

**State variables**:
- `_workoutState` / `workoutState` / `isWorkoutActive`
- `_currentMetric` / `currentMetric`
- `_currentHeuristicKgMax` / `currentHeuristicKgMax` / `maxHeuristicKgMax`
- `_loadBaselineA` / `_loadBaselineB` / `loadBaselineA` / `loadBaselineB`
- `_workoutParameters` / `workoutParameters`
- `_userAdjustedWeightDuringRest`
- `_repCount` / `repCount`
- `_timedExerciseRemainingSeconds` / `timedExerciseRemainingSeconds`
- `_repRanges` / `repRanges`
- `_autoStopState` / `autoStopState`
- `_autoStartCountdown` / `autoStartCountdown`
- `_hapticEvents` / `hapticEvents`
- `_prCelebrationEvent` / `prCelebrationEvent`
- `_badgeEarnedEvents` / `badgeEarnedEvents`
- `_isCurrentTimedCableExercise` / `isCurrentTimedCableExercise`
- `_isCurrentExerciseBodyweight` / `isCurrentExerciseBodyweight`
- `currentSessionId`
- `workoutStartTime` / `routineStartTime`
- `collectedMetrics`
- `currentRoutineSessionId` / `currentRoutineName`
- `autoStopStartTime` / `autoStopTriggered` / `autoStopStopRequested`
- `currentHandleState`
- `stallStartTime` / `isCurrentlyStalled`
- `stopWorkoutInProgress` / `setCompletionInProgress`
- `monitorDataCollectionJob` / `autoStartJob` / `restTimerJob` / `bodyweightTimerJob`
- `repEventsCollectionJob` / `workoutJob`
- `skipCountdownRequested` / `isCurrentWorkoutTimed`
- `bodyweightSetsCompletedInRoutine` / `previousExerciseWasBodyweight`
- `handleDetectionEnabledTimestamp` / `HANDLE_DETECTION_DEBOUNCE_MS`

**Functions**:
- `startWorkout()` (L897) -- 325 lines, the largest single function
- `stopWorkout()` (L1241)
- `stopAndReturnToSetReady()` (L1386)
- `pauseWorkout()` (L1417)
- `resumeWorkout()` (L1428)
- `restartCollectionJobs()` (L1438)
- `skipCountdown()` (L1228)
- `updateWorkoutParameters()` (L881)
- `handleRepNotification()` (L733)
- `handleMonitorMetric()` (L784)
- `collectMetricForHistory()` (L843)
- `handleSetCompletion()` (L3540) -- 194 lines, another giant
- `saveWorkoutSession()` (L3739) -- 168 lines
- `calculateSetSummaryMetrics()` (L3947) -- 162 lines
- `checkAutoStop()` (L3219) -- 212 lines
- `resetAutoStopTimer()` (L3437)
- `resetStallTimer()` (L3448)
- `resetAutoStopState()` (L3461)
- `isInAmrapStartupGrace()` (L3485)
- `requestAutoStop()` (L3506)
- `triggerAutoStop()` (L3515)
- `shouldEnableAutoStop()` (L3200)
- `startAutoStartTimer()` (L3125)
- `cancelAutoStartTimer()` (L3186)
- `enableHandleDetection()` (L2899)
- `disableHandleDetection()` (L2914)
- `isBodyweightExercise()` (L4210)
- `resetForNewWorkout()` (L1851)
- `recaptureLoadBaseline()` (L1864)
- `resetLoadBaseline()` (L1876)
- Handle state collector in `init` (L550-L599)
- Deload event collector in `init` (L602-L637)
- Rep events collector in `init` (L640-L647)
- Global metricsFlow collector in `init` (L649-L660)
- Heuristic data collector in `init` (L662-L682)
- RepCounter onRepEvent callback in `init` (L512-L545)

**Cross-cluster coupling**: VERY HIGH
- Reads/writes `_currentExerciseIndex`, `_currentSetIndex` (shared with Cluster_RoutineFlow)
- Reads `_loadedRoutine` (shared with Cluster_RoutineFlow)
- Reads `userPreferences` for summaryCountdownSeconds, autoStartCountdownSeconds (shared with Cluster_Settings)
- Reads `autoplayEnabled` (derived from Cluster_Settings)
- Calls `gamificationRepository.updateStats/checkAndAwardBadges` (shared with Cluster_Gamification)
- Calls `personalRecordRepository.updatePRsIfBetter` (shared with Cluster_History)
- Calls `syncTriggerManager?.onWorkoutCompleted()` (shared with Cluster_Sync)
- Emits `_prCelebrationEvent`, `_badgeEarnedEvents` (shared with Cluster_Gamification/History)
- Calls `startRestTimer()`, `advanceToNextSetInSingleExercise()`, `startNextSetOrExercise()` (shared with Cluster_RoutineFlow)

**Lines**: ~2,800 lines (the vast majority of the file)

---

### Cluster_RoutineFlow
**Purpose**: Routine loading, exercise navigation, set progression, rest timer, superset navigation, routine overview/set-ready state machine, routine CRUD.

**Dependencies used**:
- `workoutRepository` (getAllRoutines, saveRoutine, updateRoutine, deleteRoutine)
- `resolveWeightsUseCase` (PR% weight resolution)

**State variables**:
- `_routineFlowState` / `routineFlowState`
- `_routines` / `routines`
- `_loadedRoutine` / `loadedRoutine`
- `_currentExerciseIndex` / `currentExerciseIndex`
- `_currentSetIndex` / `currentSetIndex`
- `_skippedExercises` / `skippedExercises`
- `_completedExercises` / `completedExercises`
- `_currentSetRpe` / `currentSetRpe`

**Functions**:
- `loadRoutine()` (L2087)
- `loadRoutineInternal()` (L2127)
- `resolveRoutineWeights()` (L2104)
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
- `getRoutineById()` (L274)
- `loadRoutineById()` (L2598)
- `clearLoadedRoutine()` (L2628)
- `getCurrentExercise()` (L2634)
- `saveRoutine()` (L2064)
- `updateRoutine()` (L2068)
- `deleteRoutine()` (L2072)
- `deleteRoutines()` (L2079)
- `advanceToNextExercise()` (L1882)
- `jumpToExercise()` (L1896)
- `navigateToExerciseInternal()` (L1961)
- `skipCurrentExercise()` (L1995)
- `goToPreviousExercise()` (L2008)
- `canGoBack()` (L2018)
- `canSkipForward()` (L2025)
- `getRoutineExerciseNames()` (L2033)
- `proceedFromSummary()` (L1705)
- `logRpeForCurrentSet()` (L1846)
- `startRestTimer()` (L4226) -- 178 lines
- `advanceToNextSetInSingleExercise()` (L4460)
- `startWorkoutOrSetReady()` (L4526)
- `startNextSetOrExercise()` (L4542) -- 116 lines
- `skipRest()` (L4664)
- `startNextSet()` (L4686)
- `calculateNextExerciseName()` (L4410)
- `calculateIsLastExercise()` (L4440)
- Superset navigation: `getNextStep()` (L2376), `getPreviousStep()` (L2429), `hasNextStep()` (L2485), `hasPreviousStep()` (L2493), `setReadyPrev()` (L2504), `setReadySkip()` (L2518)
- Superset helpers: `getCurrentSupersetExercises()` (L2691), `isInSuperset()` (L2704), `getNextSupersetExerciseIndex()` (L2712), `getFirstSupersetExerciseIndex()` (L2732), `isAtEndOfSupersetCycle()` (L2743), `getSupersetRestSeconds()` (L2754), `findNextExerciseAfterCurrent()` (L2764)
- Superset CRUD: `createSuperset()` (L2791), `updateSuperset()` (L2827), `deleteSuperset()` (L2839), `addExerciseToSuperset()` (L2857), `removeExerciseFromSuperset()` (L2877)
- Resume: `hasResumableProgress()` (L2657), `getResumableProgressInfo()` (L2673)
- Routine loading in `init` (L490-L495)

**Cross-cluster coupling**: HIGH
- Reads/writes `_workoutState` (shared with Cluster_ActiveSession)
- Reads/writes `_workoutParameters` (shared with Cluster_ActiveSession)
- Calls `startWorkout()` from multiple paths (shared with Cluster_ActiveSession)
- Calls `bleRepository.stopWorkout/sendStopCommand` in jumpToExercise (shared with Cluster_BleConnection)
- Reads `autoplayEnabled` (shared with Cluster_Settings)
- Reads `_userAdjustedWeightDuringRest` (shared with Cluster_ActiveSession)

**Lines**: ~1,200 lines

---

### Cluster_History
**Purpose**: Workout history, personal records, stats computation (completed workouts, workout streak, progress percentage, grouped history).

**Dependencies used**:
- `workoutRepository` (getAllSessions, getAllPersonalRecords, deleteSession, deleteAllSessions)
- `personalRecordRepository` (getAllPRsGrouped)

**State variables**:
- `_workoutHistory` / `workoutHistory`
- `allWorkoutSessions`
- `groupedWorkoutHistory`
- `allPersonalRecords`
- `personalBests`
- `completedWorkouts`
- `workoutStreak`
- `progressPercentage`

**Functions**:
- `deleteWorkout()` (L2037)
- `deleteAllWorkouts()` (L1581)
- History loading in `init` (L483-L487)
- All computed StateFlows (L307-L391)

**Cross-cluster coupling**: LOW
- None -- these are read-only computed views of persisted data

**Lines**: ~130 lines

---

### Cluster_Settings
**Purpose**: User preferences (weight unit, stop at top, video playback, stall detection, audio rep count, summary countdown, auto-start countdown, color scheme).

**Dependencies used**:
- `preferencesManager` (all setXxx methods)
- `bleRepository` (setColorScheme)

**State variables**:
- `userPreferences`
- `weightUnit`
- `stopAtTop`
- `enableVideoPlayback`
- `autoplayEnabled`

**Functions**:
- `setWeightUnit()` (L1459)
- `setStopAtTop()` (L1463)
- `setEnableVideoPlayback()` (L1467)
- `setStallDetectionEnabled()` (L1473)
- `setAudioRepCountEnabled()` (L1477)
- `setSummaryCountdownSeconds()` (L1481)
- `setAutoStartCountdownSeconds()` (L1486)
- `setColorScheme()` (L1490)
- `kgToDisplay()` (L2041)
- `displayToKg()` (L2047)
- `formatWeight()` (L2053)

**Cross-cluster coupling**: LOW
- `autoplayEnabled` is read by Cluster_RoutineFlow and Cluster_ActiveSession
- `userPreferences` is read by Cluster_ActiveSession for countdown durations and audio settings

**Lines**: ~80 lines

---

### Cluster_Gamification
**Purpose**: XP, achievements, badges, PR celebrations.

**Dependencies used**:
- `gamificationRepository` (updateStats, checkAndAwardBadges)

**State variables**:
- `_prCelebrationEvent` / `prCelebrationEvent`
- `_badgeEarnedEvents` / `badgeEarnedEvents`

**Functions**:
- `emitBadgeSound()` (L1530)
- `emitPRSound()` (L1539)
- Award/check logic embedded in `saveWorkoutSession()` (L3877-L3895)

**Cross-cluster coupling**: MEDIUM
- Called from within `saveWorkoutSession()` in Cluster_ActiveSession
- PR checking also embedded in `saveWorkoutSession()` (L3830-L3875)

**Lines**: ~40 lines (but logic is embedded in ActiveSession)

---

### Cluster_JustLift
**Purpose**: Just Lift mode preparation, handle detection, defaults saving/loading.

**Dependencies used**:
- `bleRepository` (enableHandleDetection, enableJustLiftWaitingMode)
- `preferencesManager` (getJustLiftDefaults, saveJustLiftDefaults, getSingleExerciseDefaults, saveSingleExerciseDefaults)

**State variables**:
- (Shares `_workoutParameters`, `_workoutState` with Cluster_ActiveSession)

**Functions**:
- `prepareForJustLift()` (L2924)
- `enableHandleDetection()` (L2899)
- `disableHandleDetection()` (L2914)
- `getJustLiftDefaults()` (L2975)
- `saveJustLiftDefaults()` (L2992)
- `saveJustLiftDefaultsFromWorkout()` (L4123)
- `getSingleExerciseDefaults()` (L2957)
- `saveSingleExerciseDefaults()` (L2964)
- `saveSingleExerciseDefaultsFromWorkout()` (L4150)
- `isSingleExerciseMode()` (L4114)

**Cross-cluster coupling**: MEDIUM
- Shares `_workoutParameters` and `_workoutState` with Cluster_ActiveSession
- `saveJustLiftDefaultsFromWorkout` and `saveSingleExerciseDefaultsFromWorkout` called from `saveWorkoutSession()`

**Lines**: ~150 lines

---

### Cluster_WeightAdjustment
**Purpose**: Runtime weight adjustment during workout, weight presets.

**Dependencies used**:
- `bleRepository` (sendWorkoutCommand)
- `workoutRepository` (getAllSessions, getAllPersonalRecords for weight lookup)

**State variables**:
- (Shares `_workoutParameters` with Cluster_ActiveSession)

**Functions**:
- `adjustWeight()` (L3017)
- `incrementWeight()` (L3047)
- `decrementWeight()` (L3055)
- `setWeightPreset()` (L3063)
- `getLastWeightForExercise()` (L3070)
- `getPrWeightForExercise()` (L3082)
- `sendWeightUpdateToMachine()` (L3093)

**Cross-cluster coupling**: LOW
- Reads `_workoutState` to decide whether to send BLE commands
- Reads/writes `_workoutParameters`

**Lines**: ~100 lines

---

### Cluster_UIScaffolding
**Purpose**: Top bar title/actions/back button, haptic/sound events, disco mode, simulator mode, user feedback events.

**Dependencies used**:
- `bleRepository` (discoModeActive, startDiscoMode, stopDiscoMode)
- `preferencesManager` (setDiscoModeUnlocked, setSimulatorModeUnlocked, isSimulatorModeUnlocked)

**State variables**:
- `_topBarTitle` / `topBarTitle`
- `_topBarActions` / `topBarActions`
- `_topBarBackAction` / `topBarBackAction`
- `_hapticEvents` / `hapticEvents`
- `_userFeedbackEvents` / `userFeedbackEvents`
- `_isWorkoutSetupDialogVisible` / `isWorkoutSetupDialogVisible`
- `discoModeActive`

**Functions**:
- `updateTopBarTitle()` (L208)
- `setTopBarActions()` (L216)
- `clearTopBarActions()` (L220)
- `setTopBarBackAction()` (L228)
- `clearTopBarBackAction()` (L232)
- `emitDiscoSound()` (L1521)
- `unlockDiscoMode()` (L1503)
- `toggleDiscoMode()` (L1510)
- `unlockSimulatorMode()` (L1564)
- `toggleSimulatorMode()` (L1571)
- `isSimulatorModeUnlocked()` (L1577)
- `testSounds()` (L1549)

**Cross-cluster coupling**: LOW
- `_hapticEvents` is emitted from Cluster_ActiveSession extensively
- `_userFeedbackEvents` emitted from `jumpToExercise()` in Cluster_RoutineFlow

**Lines**: ~100 lines

---

### Cluster_TrainingCycles
**Purpose**: Training cycle context tracking and progress updates.

**Dependencies used**:
- `trainingCycleRepository` (getCycleById, getCycleProgress, updateCycleProgress)

**State variables**:
- `activeCycleId`
- `activeCycleDayNumber`

**Functions**:
- `loadRoutineFromCycle()` (L2610)
- `clearCycleContext()` (L2623)
- `updateCycleProgressIfNeeded()` (L3914)

**Cross-cluster coupling**: LOW
- Called from `saveWorkoutSession()` (Cluster_ActiveSession)
- Calls `loadRoutine()` (Cluster_RoutineFlow)

**Lines**: ~50 lines

---

## Cross-Cluster Coupling Matrix

| Source Cluster | Target Cluster | Coupling Description | Severity |
|---|---|---|---|
| ActiveSession | RoutineFlow | Reads _loadedRoutine, _currentExerciseIndex, _currentSetIndex. Calls startRestTimer, advanceToNextSet, startNextSetOrExercise | **CRITICAL** |
| ActiveSession | Settings | Reads userPreferences for audio, countdown, stall detection | LOW |
| ActiveSession | Gamification | Calls gamificationRepository in saveWorkoutSession | MEDIUM |
| ActiveSession | History | Calls personalRecordRepository.updatePRsIfBetter in saveWorkoutSession | MEDIUM |
| ActiveSession | TrainingCycles | Calls updateCycleProgressIfNeeded in saveWorkoutSession | LOW |
| ActiveSession | JustLift | Calls saveJustLiftDefaultsFromWorkout / saveSingleExerciseDefaultsFromWorkout | LOW |
| ActiveSession | UIScaffolding | Emits _hapticEvents, _prCelebrationEvent, _badgeEarnedEvents | LOW |
| RoutineFlow | ActiveSession | Calls startWorkout, reads/writes _workoutState, _workoutParameters | **CRITICAL** |
| RoutineFlow | BleConnection | Calls bleRepository.stopWorkout/sendStopCommand in jumpToExercise | MEDIUM |
| RoutineFlow | Settings | Reads autoplayEnabled, stopAtTop | LOW |
| BleConnection | ActiveSession | Reads _workoutState to determine connection-loss alert | LOW |
| BleConnection | Settings | Reads userPreferences for LED color scheme | LOW |
| JustLift | ActiveSession | Shares _workoutParameters and _workoutState | MEDIUM |
| WeightAdjustment | ActiveSession | Reads _workoutState, writes _workoutParameters | LOW |

---

## Recommended Split Order

Extract clusters **from least coupled to most coupled**:

### Phase 1: Clean Extractions (no cross-cluster entanglement)

1. **Cluster_UIScaffolding** -> `TopBarViewModel` or move to `UiScaffoldingState`
   - Reason: Zero domain logic, pure UI state, no business dependencies
   - Risk: Trivial
   - Effort: Small

2. **Cluster_History** -> `WorkoutHistoryViewModel` or `HistoryStateHolder`
   - Reason: Read-only computed flows, no mutations, no coupling
   - Risk: Trivial
   - Effort: Small

3. **Cluster_Settings** -> `SettingsViewModel` or `UserPreferencesManager`
   - Reason: Simple preference setters, derived flows consumed as read-only
   - Risk: Low -- consumers need to be updated to observe from new source
   - Effort: Small

4. **Cluster_TrainingCycles** -> `TrainingCycleManager`
   - Reason: ~50 lines, isolated lifecycle, called from a single point
   - Risk: Trivial
   - Effort: Trivial

### Phase 2: Moderate Extractions (some shared state)

5. **Cluster_Gamification** -> `GamificationManager`
   - Reason: Logic is embedded in saveWorkoutSession; extraction requires passing events OUT
   - Risk: Medium -- need a callback/event bus from ActiveSession
   - Effort: Medium

6. **Cluster_BleConnection** -> `BleConnectionViewModel` or `ConnectionManager`
   - Reason: Mostly self-contained but reads workoutState for connection-loss detection
   - Risk: Medium -- connection loss alert needs a flag or event from ActiveSession
   - Effort: Medium

7. **Cluster_WeightAdjustment** -> `WeightAdjustmentUseCase`
   - Reason: Reads workoutState and writes workoutParameters; needs shared parameter state
   - Risk: Low-Medium
   - Effort: Small

8. **Cluster_JustLift** -> `JustLiftSessionManager`
   - Reason: Shares workoutParameters and workoutState; defaults saving interleaved with session save
   - Risk: Medium
   - Effort: Medium

### Phase 3: The Big One (requires careful interface design)

9. **Cluster_ActiveSession + Cluster_RoutineFlow** -> `WorkoutSessionManager` + `RoutineFlowManager`
   - Reason: These two are **critically coupled** -- RoutineFlow calls startWorkout, ActiveSession calls startRestTimer/advanceToNextSet, both read/write _workoutState and _workoutParameters
   - Strategy: Extract a **shared `WorkoutCoordinator` interface** that both managers communicate through, with a clear event bus for state transitions
   - Risk: HIGH -- this is the core of the application, ~4,000 lines of interdependent logic
   - Effort: Large -- requires careful characterization tests first

### Suggested First Move

Extract **Cluster_History** first. It is:
- 100% read-only (no side effects)
- Zero cross-cluster dependencies
- Contains clearly self-contained computed StateFlows
- A quick win that reduces MainViewModel by ~130 lines and proves the extraction pattern works

---

## Constants (Companion Object)

All constants are used exclusively by Cluster_ActiveSession:

```
TEMP_SINGLE_EXERCISE_PREFIX  -- Used by isSingleExerciseMode() and saveSingleExerciseDefaultsFromWorkout()
AUTO_STOP_DURATION_SECONDS   -- Used by checkAutoStop() position-based timer
STALL_DURATION_SECONDS       -- Used by checkAutoStop() velocity-based timer
STALL_VELOCITY_LOW           -- Used by checkAutoStop() hysteresis
STALL_VELOCITY_HIGH          -- Used by checkAutoStop() hysteresis
STALL_MIN_POSITION           -- Used by checkAutoStop()
HANDLE_REST_THRESHOLD        -- Used by checkAutoStop()
MIN_RANGE_THRESHOLD          -- Used by checkAutoStop(), handleMonitorMetric()
AMRAP_STARTUP_GRACE_MS       -- Used by isInAmrapStartupGrace()
```

These should migrate with Cluster_ActiveSession.

---

## Data Classes Defined in File

- `HistoryItem` (sealed class, L52) -> Move with Cluster_History
- `SingleSessionHistoryItem` (L56) -> Move with Cluster_History
- `GroupedRoutineHistoryItem` (L60) -> Move with Cluster_History
- `TopBarAction` (L73) -> Move with Cluster_UIScaffolding
- `JustLiftDefaults` (L4729) -> Move with Cluster_JustLift
- `ResumableProgressInfo` (L2645) -> Move with Cluster_RoutineFlow

---

## Init Block Analysis (L479-L724)

The `init` block sets up **8 coroutine collectors** and **1 callback**:

| Collector | Cluster | Lines |
|---|---|---|
| workoutRepository.getAllSessions() | History | L483-L487 |
| workoutRepository.getAllRoutines() | RoutineFlow | L490-L495 |
| exerciseRepository.importExercises() | RoutineFlow | L498-L509 |
| repCounter.onRepEvent | ActiveSession | L512-L545 |
| bleRepository.handleState | ActiveSession | L550-L599 |
| bleRepository.deloadOccurredEvents | ActiveSession | L602-L637 |
| bleRepository.repEvents | ActiveSession | L640-L647 |
| bleRepository.metricsFlow | ActiveSession | L649-L660 |
| bleRepository.heuristicData | ActiveSession | L662-L682 |
| bleRepository.connectionState | BleConnection | L684-L723 |

Each collector should move with its respective cluster.

---

## Job Tracking

| Job Variable | Cluster | Purpose |
|---|---|---|
| `connectionJob` | BleConnection | Scan & connect lifecycle |
| `monitorDataCollectionJob` | ActiveSession | Global metrics collection |
| `autoStartJob` | ActiveSession | Auto-start countdown |
| `restTimerJob` | RoutineFlow/ActiveSession | Rest timer between sets |
| `bodyweightTimerJob` | ActiveSession | Bodyweight/timed exercise duration |
| `repEventsCollectionJob` | ActiveSession | Rep event processing |
| `workoutJob` | ActiveSession | Main workout coroutine (countdown + BLE commands) |

All jobs are cancelled in `onCleared()` (L4701-L4708).

---

## Parent Repo Comparison

**File**: `.tmp_parent_repo/app/src/main/java/com/example/vitruvianredux/presentation/viewmodel/MainViewModel.kt`

| Metric | Parent Repo | Our Fork |
|---|---|---|
| Total lines | 2,904 | 4,771 (+64%) |
| Constructor deps | 8 | 10 |
| DI framework | Hilt (`@HiltViewModel`) | Koin (manual) |
| Base class | `AndroidViewModel(application)` | `ViewModel()` (KMP) |
| Logging | Timber | Kermit (KMP) |
| Platform | Android-only | Kotlin Multiplatform |

**Dependencies the parent does NOT have** (added by our fork):
- `gamificationRepository` -- XP, badges, stats (Cluster_Gamification)
- `trainingCycleRepository` -- Training cycle tracking (Cluster_TrainingCycles)
- `syncTriggerManager` -- Cloud sync trigger (called in saveWorkoutSession)
- `resolveWeightsUseCase` -- PR percentage weight resolution

**Dependencies the parent HAS that we replaced**:
- `application: Application` -- Android context for foreground service, file sharing
- `dataBackupManager: DataBackupManager` -- Data export/import (not in our KMP version)

**Key structural differences**:
- Parent uses `WorkoutForegroundService` (Android service for background workout tracking) -- not applicable in KMP
- Parent has `DataBackupManager` for JSON export/import -- not yet ported
- Parent does NOT have superset support, training cycles, gamification, or cloud sync
- Parent does NOT have the routine overview/set-ready flow state machine
- Our fork added ~1,867 lines primarily for: supersets (~300 lines), gamification (~40 inline), training cycles (~50), routine flow state machine (~400), set-ready/overview navigation (~300), enhanced auto-stop with velocity stall detection (~200), bodyweight exercise support (~200), timed cable exercises (~150), and extensive diagnostic logging (~200)

**Implication for refactoring**: The parent repo's simpler structure confirms that the clusters we identified as "added by fork" (Gamification, TrainingCycles, Superset navigation, RoutineFlow state machine) are the best candidates for extraction since they were bolted on top of the original architecture and don't have deep roots in the core workout loop.

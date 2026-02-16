# Phase 2: Manager Decomposition - Research

**Researched:** 2026-02-13
**Domain:** Kotlin class decomposition / Extract Class refactoring
**Confidence:** HIGH

## Summary

DefaultWorkoutSessionManager (DWSM) is a 4,024-line god class containing all workout lifecycle logic. This research analyzes the complete method inventory, state fields, inter-method dependencies, and init block structure to inform a safe decomposition into WorkoutCoordinator (state bus), RoutineFlowManager (~1,200 lines), and ActiveSessionEngine (~1,800 lines), with DWSM remaining as an ~800-line orchestration layer.

The decomposition is a pure refactoring exercise -- no new libraries, no architecture changes, no UI modifications. The primary risk is breaking the 38 existing characterization tests (16 lifecycle + 22 routine flow) during extraction. The existing code is well-organized with `// ===== Round N` section markers that provide natural extraction boundaries.

**Primary recommendation:** Extract WorkoutCoordinator first (state-only, zero risk), then resolve the circular dependency (SharedFlow events), then extract RoutineFlowManager (lower coupling), then ActiveSessionEngine (highest coupling). Test after every individual method move.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **Coordinator-only communication**: RoutineFlowManager and ActiveSessionEngine communicate exclusively through WorkoutCoordinator. They must never hold references to each other.
- **No UI changes is the real goal** -- not a frozen API. DWSM method signatures can be cleaned up/simplified during decomposition as long as MainViewModel is updated to match and no UI screens change.
- **WorkoutSessionManager interface gets updated too** -- keep the abstraction honest when methods change.
- **MainViewModel talks to DWSM only** -- sub-managers are internal implementation details, never exposed to the ViewModel layer.
- **Test after every method move** -- run all 38 characterization tests after each individual method extraction. Maximum paranoia.
- **Revert on failure** -- if a test fails after a move, immediately revert and retry differently. Never proceed with failing tests. Never fix forward.
- **Atomic commits per move** -- each method/group extraction gets its own git commit. Full git history for bisecting.
- Managers stay OUT of Koin (manual construction in MainViewModel)
- Concrete classes for sub-managers, not interfaces
- WorkoutCoordinator is a dumb state bus with zero methods
- BLE commands stay co-located with state transitions in ActiveSessionEngine

### Claude's Discretion
- Cross-manager triggering pattern (SharedFlow events vs DWSM orchestration)
- WorkoutCoordinator state scope (all state vs cross-cutting only)
- BLE circular dependency event direction
- Ambiguous method ownership assignments
- handleMonitorMetric() placement
- BLE command co-location interpretation
- DWSM final size (let code tell the story)
- Init block ordering documentation timing

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

## Standard Stack

No new libraries needed. This is a pure refactoring of existing Kotlin code.

### Core (Already in Project)
| Library | Version | Purpose | Relevance to Phase |
|---------|---------|---------|-------------------|
| Kotlin | 2.0.21 | Language | Class extraction uses standard Kotlin patterns |
| Coroutines | 1.9.0 | Async | CoroutineScope sharing between managers |
| Flow | (coroutines) | Reactive state | MutableStateFlow/SharedFlow extraction |
| Turbine | (test dep) | Flow testing | Characterization tests use turbine |

### Supporting
| Library | Version | Purpose | Relevance |
|---------|---------|---------|-----------|
| kotlinx-coroutines-test | 1.9.0 | Test scheduling | TestScope/advanceUntilIdle in harness |

## Architecture Patterns

### Current DWSM Structure (4,024 lines)

The file is organized with clear section markers that map naturally to extraction targets:

```
Lines 1-112:     Data classes (JustLiftDefaults, ResumableProgressInfo, CycleDayCompletionEvent)
Lines 113-180:   Class declaration, constructor params, companion constants
Lines 181-375:   State fields (MutableStateFlows, private vars, guard flags)
Lines 376-561:   Init block (7 collector coroutines)
Lines 563-598:   Round 1: Pure helpers (getRoutineById, isBodyweightExercise, isSingleExerciseMode)
Lines 600-870:   Calculation helpers (calculateSetSummaryMetrics, resetAutoStop*, isInAmrapStartupGrace)
Lines 899-1101:  Round 2: Superset navigation (getCurrentSupersetExercises, getNextStep, getPreviousStep)
Lines 1103-1167: Navigation helpers (hasNextStep, hasPreviousStep, calculateNextExerciseName)
Lines 1169-1770: Round 3: Routine CRUD and navigation (save/update/delete/load/enterSetReady/jump...)
Lines 1773-1875: Round 4: Superset CRUD (createSuperset, updateSuperset, deleteSuperset...)
Lines 1877-1962: Round 5: Weight adjustment (adjustWeight, incrementWeight, decrementWeight...)
Lines 1985-2186: Round 6: Just Lift (enableHandleDetection, prepareForJustLift, saveJustLiftDefaults...)
Lines 2188-2254: Round 7: Training cycles (loadRoutineFromCycle, updateCycleProgressIfNeeded)
Lines 2256-3241: Round 8: Core workout lifecycle (handleRepNotification, handleMonitorMetric,
                 checkAutoStop, startAutoStartTimer, saveWorkoutSession, handleSetCompletion,
                 startWorkout, stopWorkout)
Lines 3242-4024: Rest/navigation flow (startRestTimer, advanceToNextSetInSingleExercise,
                 startNextSetOrExercise, proceedFromSummary, cleanup)
```

### Recommended Target Structure

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/
  DefaultWorkoutSessionManager.kt     # ~800 lines: orchestration, delegation, DWSM API
  WorkoutCoordinator.kt               # ~300 lines: all MutableStateFlows + guard flags
  RoutineFlowManager.kt               # ~1,200 lines: routine CRUD, navigation, supersets
  ActiveSessionEngine.kt              # ~1,800 lines: workout lifecycle, BLE, auto-stop, rest
  BleConnectionManager.kt             # (unchanged - already extracted)
```

### Pattern 1: WorkoutCoordinator as Shared State Bus

**What:** A data-holder class that owns all MutableStateFlows and guard flags. Zero business logic methods. Sub-managers read/write state through the coordinator.

**When to use:** When multiple classes need to share mutable state without coupling to each other.

**Recommendation for state scope:** ALL state should live in WorkoutCoordinator, not just cross-cutting state. Reasoning:
1. The coordinator-only communication constraint means RoutineFlowManager cannot read ActiveSessionEngine's state directly.
2. Placing domain-specific state in the owning manager creates hidden coupling -- the other manager might need to read it for guard conditions.
3. A single state bus simplifies the test harness (one place to mock/inspect state).
4. The coordinator already has zero methods; making it hold all state keeps this constraint clean.

**Example:**
```kotlin
class WorkoutCoordinator(scope: CoroutineScope) {
    // ===== Workout State =====
    val workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val routineFlowState = MutableStateFlow<RoutineFlowState>(RoutineFlowState.NotInRoutine)

    // ===== Metrics =====
    val currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentHeuristicKgMax = MutableStateFlow(0f)
    // ... all other MutableStateFlows

    // ===== Guard Flags =====
    var stopWorkoutInProgress = false
    var setCompletionInProgress = false
    // ... all other mutable flags

    // ===== Job Tracking =====
    var workoutJob: Job? = null
    var restTimerJob: Job? = null
    // ... all other Job references
}
```

### Pattern 2: Cross-Manager Triggering via DWSM Orchestration (Recommended)

**What:** DWSM calls methods on sub-managers directly rather than using SharedFlow events. Sub-managers never trigger each other; DWSM coordinates the sequence.

**Why recommended over SharedFlow events:**
1. **Simpler**: No event classes, no subscriptions, no emission ordering concerns.
2. **Debuggable**: Stack traces show direct call chains, not flow collection callbacks.
3. **Existing pattern**: DWSM already orchestrates method calls in sequence (handleSetCompletion -> saveWorkoutSession -> startRestTimer -> startNextSetOrExercise).
4. **Test-friendly**: Characterization tests already test through DWSM's public API; adding event indirection creates new failure modes.

The only place SharedFlow events ARE needed is the BLE circular dependency (Plan 02-02), because that crosses a genuine architectural boundary.

**Example:**
```kotlin
// In DWSM (orchestration layer):
private fun onSetCompleted() {
    activeSessionEngine.handleSetCompletion()  // saves session, emits haptics
    // After set completion, DWSM decides what happens next:
    if (shouldStartRestTimer()) {
        activeSessionEngine.startRestTimer()
    }
}
```

### Pattern 3: BLE Circular Dependency Resolution via SharedFlow

**What:** Replace `lateinit var bleConnectionManager: BleConnectionManager` with a SharedFlow that DWSM/ActiveSessionEngine emits connection error events into, and BleConnectionManager collects from.

**Current problem (line 143 of DWSM):**
```kotlin
lateinit var bleConnectionManager: BleConnectionManager
```
Used in exactly 2 places:
- `startWorkout()` line 3176: `bleConnectionManager.setConnectionError("Failed to send command: ${e.message}")`
- `startWorkout()` line 3189: `bleConnectionManager.setConnectionError("Failed to start workout: ${e.message}")`

**Recommendation: BLE error events flow FROM ActiveSessionEngine TO BleConnectionManager.**

```kotlin
// In WorkoutCoordinator (or ActiveSessionEngine):
val bleErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 5)

// In ActiveSessionEngine.startWorkout():
coordinator.bleErrorEvents.emit("Failed to send command: ${e.message}")

// In BleConnectionManager.init:
scope.launch {
    bleErrorEvents.collect { message ->
        _connectionError.value = message
    }
}
```

This eliminates the `lateinit var` entirely. The flow direction is ActiveSessionEngine -> BleConnectionManager (one-way).

### Anti-Patterns to Avoid
- **Sub-manager cross-references**: RoutineFlowManager must NEVER hold a reference to ActiveSessionEngine or vice versa. All coordination flows through DWSM.
- **Moving state to sub-managers**: All MutableStateFlows stay in WorkoutCoordinator. Sub-managers read/write through it.
- **Extracting init block collectors to sub-managers prematurely**: The init block's 7 collectors have complex cross-dependencies. Move them only after understanding which sub-manager each belongs to.
- **Fixing forward on test failures**: If a test fails after a move, IMMEDIATELY revert. Do not try to fix the test or the code.

## Complete Method Inventory and Ownership Analysis

### WorkoutCoordinator (State Bus Only)
All 48 MutableStateFlows, ~25 private vars/flags, ~7 Job references extracted verbatim. No methods.

**State fields (MutableStateFlows):**
- _workoutState, _routineFlowState, _currentMetric, _currentHeuristicKgMax
- _loadBaselineA, _loadBaselineB, _workoutParameters, _repCount
- _timedExerciseRemainingSeconds, _repRanges, _autoStopState, _autoStartCountdown
- _routines, _loadedRoutine, _currentExerciseIndex, _currentSetIndex
- _skippedExercises, _completedExercises, _currentSetRpe
- _cycleDayCompletionEvent, _hapticEvents, _userFeedbackEvents
- _isCurrentTimedCableExercise, _isCurrentExerciseBodyweight

**Private mutable vars:**
- maxHeuristicKgMax, _userAdjustedWeightDuringRest, currentSessionId
- workoutStartTime, routineStartTime, collectedMetrics (mutableListOf)
- currentRoutineSessionId, currentRoutineName
- activeCycleId, activeCycleDayNumber
- autoStopStartTime, autoStopTriggered, autoStopStopRequested
- stopWorkoutInProgress, setCompletionInProgress, currentHandleState
- stallStartTime, isCurrentlyStalled
- bodyweightSetsCompletedInRoutine, previousExerciseWasBodyweight
- skipCountdownRequested, isCurrentWorkoutTimed, handleDetectionEnabledTimestamp

**Job references:**
- monitorDataCollectionJob, autoStartJob, restTimerJob
- bodyweightTimerJob, repEventsCollectionJob, workoutJob

### RoutineFlowManager (~1,200 lines)

**Routine CRUD:**
- saveRoutine(routine) -- simple delegation
- updateRoutine(routine) -- simple delegation
- deleteRoutine(routineId) -- simple delegation
- deleteRoutines(routineIds) -- batch delete

**Routine Loading:**
- resolveRoutineWeights(routine) -- PR weight resolution (suspend)
- loadRoutineInternal(routine) -- sets state from first exercise
- loadRoutine(routine) -- launches coroutine for resolve then load
- loadRoutineById(routineId) -- lookup + load
- enterRoutineOverview(routine) -- sets Overview state

**Routine Navigation (SetReady flow):**
- enterSetReady(exerciseIndex, setIndex) -- sets SetReady state + params
- enterSetReadyWithAdjustments(...) -- SetReady with pre-adjusted values
- updateSetReadyWeight(weight) -- updates weight in SetReady
- updateSetReadyReps(reps) -- updates reps in SetReady
- updateSetReadyEchoLevel(level) -- updates echo level in SetReady
- updateSetReadyEccentricLoad(percent) -- updates eccentric load in SetReady
- startSetFromReady() -- triggers startWorkout from SetReady (CROSS-MANAGER: calls DWSM)
- returnToOverview() -- back to Overview from SetReady
- exitRoutineFlow() -- exit to NotInRoutine
- showRoutineComplete() -- show completion screen
- clearLoadedRoutine() -- clear loaded routine

**Exercise Navigation:**
- selectExerciseInOverview(index) -- carousel navigation
- navigateToExerciseInternal(routine, index) -- actual navigation logic
- advanceToNextExercise() -- calls jumpToExercise
- jumpToExercise(index) -- async navigation with BLE cleanup (CROSS-MANAGER: calls BLE + startWorkout)
- skipCurrentExercise() -- skip + jumpToExercise
- goToPreviousExercise() -- previous + jumpToExercise
- canGoBack() / canSkipForward() -- navigation guards
- getRoutineExerciseNames() -- display helper
- setReadyPrev() / setReadySkip() -- SetReady navigation

**Superset Navigation Helpers (private):**
- getCurrentSupersetExercises() -- filter by superset ID
- isInSuperset() -- check superset membership
- getNextSupersetExerciseIndex() -- next in superset rotation
- getFirstSupersetExerciseIndex() -- first in superset
- isAtEndOfSupersetCycle() -- end of cycle check
- getSupersetRestSeconds() -- rest time lookup
- findNextExerciseAfterCurrent() -- next after superset

**Unified Navigation Logic (private):**
- getNextStep(routine, exIndex, setIndex) -- superset-aware next
- getPreviousStep(routine, exIndex, setIndex) -- superset-aware previous
- hasNextStep() / hasPreviousStep() -- public wrappers
- calculateNextExerciseName(...) -- display string
- calculateIsLastExercise(...) -- completion check

**Superset CRUD:**
- createSuperset(...) -- suspend
- updateSuperset(...) -- suspend
- deleteSuperset(...) -- suspend
- addExerciseToSuperset(...) -- suspend
- removeExerciseFromSuperset(...) -- suspend

**State Query:**
- getRoutineById(routineId) -- lookup
- getCurrentExercise() -- current RoutineExercise
- hasResumableProgress(routineId) -- resume check
- getResumableProgressInfo() -- resume display data
- logRpeForCurrentSet(rpe) -- RPE tracking

**Boundary note:** `jumpToExercise()` sends BLE commands (sendStopCommand, stopWorkout) and then calls `startWorkout()`. This method is the biggest cross-boundary method. Options:
1. Keep in RoutineFlowManager, call DWSM methods for BLE/startWorkout
2. Keep in DWSM orchestration layer

**Recommendation:** Keep `jumpToExercise()` in RoutineFlowManager but have it call through the coordinator/DWSM for BLE operations and startWorkout. The navigation decision logic belongs to RoutineFlowManager; the BLE execution belongs to ActiveSessionEngine.

### ActiveSessionEngine (~1,800 lines)

**Core Workout Lifecycle:**
- startWorkout(skipCountdown, isJustLiftMode) -- THE big method (~250 lines)
- stopWorkout(exitingWorkout) -- manual stop (~170 lines)
- stopAndReturnToSetReady() -- back button during workout
- pauseWorkout() / resumeWorkout() -- pause/resume
- skipCountdown() -- skip countdown flag
- resetForNewWorkout() -- reset to Idle
- updateWorkoutParameters(params) -- parameter updates

**Auto-Stop System:**
- handleMonitorMetric(metric) -- hot path, every BLE metric (~60 lines)
- checkAutoStop(metric) -- velocity + position detection (~190 lines)
- resetAutoStopTimer() / resetStallTimer() / resetAutoStopState()
- isInAmrapStartupGrace(hasMeaningfulRange)
- shouldEnableAutoStop(params)
- requestAutoStop() / triggerAutoStop()

**Rep Processing:**
- handleRepNotification(notification) -- rep counter processing
- handleSetCompletion() -- auto-complete flow (~180 lines)

**Session Persistence:**
- saveWorkoutSession() -- full session save (~140 lines)
- calculateSetSummaryMetrics(...) -- enhanced metrics calculation (~170 lines)
- collectMetricForHistory(metric) -- metric accumulation

**Rest Timer:**
- startRestTimer() -- rest countdown with display (~150 lines)
- startNextSetOrExercise() -- advance after rest (~100 lines)
- advanceToNextSetInSingleExercise() -- single exercise advance
- startWorkoutOrSetReady() -- autoplay decision
- skipRest() / startNextSet() -- user skip actions
- proceedFromSummary() -- summary -> next step (~130 lines)

**Auto-Start:**
- startAutoStartTimer() -- handle grab countdown
- cancelAutoStartTimer() -- cancel countdown

**Weight Adjustment:**
- adjustWeight(newWeightKg, sendToMachine)
- sendWeightUpdateToMachine(weightKg) -- BLE weight command
- incrementWeight() / decrementWeight() / setWeightPreset()
- getLastWeightForExercise(exerciseId) -- suspend
- getPrWeightForExercise(exerciseId) -- suspend

**Just Lift:**
- enableHandleDetection() / disableHandleDetection()
- prepareForJustLift()
- getJustLiftDefaults() / saveJustLiftDefaults(defaults)
- saveJustLiftDefaultsFromWorkout() -- private
- getSingleExerciseDefaults(exerciseId) / saveSingleExerciseDefaults(defaults)
- saveSingleExerciseDefaultsFromWorkout() -- private

**Training Cycles:**
- loadRoutineFromCycle(routineId, cycleId, dayNumber) -- CROSS-MANAGER: calls loadRoutine
- clearCycleContext()
- updateCycleProgressIfNeeded() -- private suspend

**Load Baseline:**
- recaptureLoadBaseline() / resetLoadBaseline()

**Helper:**
- isBodyweightExercise(exercise) -- needed by both managers
- isSingleExerciseMode() -- needed by both managers
- findPlannedSetId(setIndex) -- private suspend

### DWSM Orchestration Layer (~800 lines, estimated)
What remains in DWSM after extraction:
- Constructor (receives all dependencies, creates sub-managers)
- Public API delegation methods (thin wrappers calling sub-managers)
- Init block collectors (orchestrate between sub-managers)
- WorkoutStateProvider implementation
- cleanup() method
- Cross-cutting coordination that touches both sub-managers

### Init Block Collector Analysis (7 collectors)

The init block launches 7 long-running coroutines. Each needs to be assigned to the correct owner:

1. **Routines loader** (lines 383-388): `workoutRepository.getAllRoutines().collect` -> Writes `_routines`
   - **Owner:** RoutineFlowManager (routine domain)

2. **Exercise importer** (lines 391-402): `exerciseRepository.importExercises()`
   - **Owner:** RoutineFlowManager (exercise library management)

3. **RepCounter onRepEvent** (lines 405-433): Emits haptics, triggers handleSetCompletion
   - **Owner:** ActiveSessionEngine (workout lifecycle)

4. **HandleState collector** (lines 438-488): Auto-start/auto-stop based on handle state
   - **Owner:** ActiveSessionEngine (auto-start/auto-stop domain)

5. **Deload events collector** (lines 491-515): Firmware-based auto-stop
   - **Owner:** ActiveSessionEngine (auto-stop domain)

6. **Rep events collector** (lines 518-525): `bleRepository.repEvents.collect`
   - **Owner:** ActiveSessionEngine (rep processing)

7. **Metrics flow collector** (lines 532-538): `bleRepository.metricsFlow.collect`
   - **Owner:** ActiveSessionEngine (metrics/auto-stop)

8. **Heuristic data collector** (lines 541-560): Echo mode force feedback
   - **Owner:** ActiveSessionEngine (metrics domain)

**Key insight:** Collectors 1-2 belong to RoutineFlowManager; collectors 3-8 belong to ActiveSessionEngine. This is a clean split.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Cross-manager events | Custom event bus | DWSM direct method calls | Already works this way; event bus adds complexity |
| State synchronization | Manual lock/sync | Single WorkoutCoordinator state bus | Centralizes all mutable state |
| Test verification | New test framework | Existing 38 characterization tests | Tests already verify exact behavior |

**Key insight:** This is not a "build something new" phase. It's "move existing code without breaking anything." The less invention, the better.

## Common Pitfalls

### Pitfall 1: State Access After Extraction
**What goes wrong:** Method A in RoutineFlowManager reads `_workoutState.value` which was moved to WorkoutCoordinator, but the reference wasn't updated.
**Why it happens:** Kotlin doesn't error on this until the field is physically removed from the source class.
**How to avoid:** Before moving any method, grep for ALL state fields it reads/writes. Ensure every field access is redirected to coordinator.
**Warning signs:** `Unresolved reference` compiler errors after extraction.

### Pitfall 2: Init Block Ordering Dependencies
**What goes wrong:** Moving a collector to a sub-manager changes initialization order, causing a collector to miss early emissions.
**Why it happens:** The init block runs sequentially in declaration order. Moving collectors to sub-manager init blocks changes when they start relative to other collectors.
**How to avoid:** Document the current init block execution order BEFORE any extraction. Verify that sub-manager construction order in DWSM matches the original collector launch order.
**Warning signs:** Tests that worked before extraction start timing out or missing state transitions.

### Pitfall 3: Scope Sharing Between Sub-Managers
**What goes wrong:** Sub-manager launches a coroutine in its own scope, but the test's `advanceUntilIdle()` doesn't advance that scope.
**Why it happens:** If sub-managers create their own CoroutineScope, they're no longer controlled by the test's TestScope.
**How to avoid:** ALL sub-managers MUST receive the same CoroutineScope (from MainViewModel or from TestScope via harness). No sub-manager creates its own scope.
**Warning signs:** Tests hang on `advanceUntilIdle()` or miss state transitions that used to work.

### Pitfall 4: DWSMTestHarness Breakage
**What goes wrong:** Test harness constructs DWSM but doesn't construct/wire sub-managers, causing null references.
**Why it happens:** Harness was written for monolithic DWSM; decomposition adds new construction steps.
**How to avoid:** Update DWSMTestHarness ATOMICALLY with the first extraction step. The harness must construct WorkoutCoordinator + sub-managers and pass them to DWSM.
**Warning signs:** All 38 tests fail with initialization errors after the first extraction.

### Pitfall 5: handleSetCompletion Cross-Cutting Complexity
**What goes wrong:** handleSetCompletion() is ~180 lines that touches BOTH routine navigation (advance to next set) AND workout lifecycle (save session, BLE stop). Putting it entirely in one sub-manager creates a dependency on the other.
**Why it happens:** It's genuinely cross-cutting. It: (1) stops BLE, (2) saves session, (3) emits haptics, (4) shows summary, (5) starts rest timer, (6) advances to next exercise.
**How to avoid:** Split into two phases: ActiveSessionEngine handles steps 1-4 (stop, save, haptics, summary). DWSM orchestrates step 5-6 (rest timer, navigation advancement). The "what to do after the set" decision is orchestration, not engine logic.
**Warning signs:** Circular calls between sub-managers.

### Pitfall 6: BLE Command Co-Location Misunderstanding
**What goes wrong:** BLE commands in `jumpToExercise()` (sendStopCommand, stopWorkout) get separated from BLE commands in `startWorkout()` because they're in different sub-managers.
**Why it happens:** "BLE commands co-located with state transitions" means BLE commands should be NEAR the state transition code, not necessarily all in one class.
**How to avoid:** `jumpToExercise()` contains a BLE stop sequence + navigation + startWorkout call. The BLE stop is a precondition for navigation, not a state transition. Keep the stop-then-navigate sequence in RoutineFlowManager, with BLE calls delegated through the coordinator or DWSM.
**Warning signs:** Machine enters fault state (blinking orange/red) during exercise navigation.

### Pitfall 7: proceedFromSummary Mega-Method
**What goes wrong:** `proceedFromSummary()` (~130 lines) decides between Just Lift reset, routine next-step, rest timer, or completion. It reads routine state AND workout state.
**Why it happens:** Summary -> next action is genuinely an orchestration decision.
**How to avoid:** Keep `proceedFromSummary()` in DWSM as orchestration. It reads state from coordinator and delegates to the appropriate sub-manager.
**Warning signs:** If placed in either sub-manager, it needs references to the other.

## Code Examples

### WorkoutCoordinator Construction
```kotlin
// Source: Analysis of DWSM state fields (lines 181-375)
class WorkoutCoordinator(
    hapticEvents: MutableSharedFlow<HapticEvent>
) {
    // Public read-only StateFlow accessors (for MainViewModel delegation)
    val workoutState: StateFlow<WorkoutState> get() = _workoutState.asStateFlow()
    // ... etc.

    // Internal mutable state (accessed by sub-managers)
    internal val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    internal val _routineFlowState = MutableStateFlow<RoutineFlowState>(RoutineFlowState.NotInRoutine)
    // ... all 25+ MutableStateFlows

    // Guard flags
    internal var stopWorkoutInProgress = false
    internal var setCompletionInProgress = false
    // ... all mutable vars

    // Job tracking
    internal var workoutJob: Job? = null
    internal var restTimerJob: Job? = null
    // ... all Job references

    // BLE error events (replaces lateinit var bleConnectionManager)
    val bleErrorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
}
```

### DWSM After Decomposition (Skeleton)
```kotlin
// Source: Analysis of MainViewModel delegation pattern (lines 84-303)
class DefaultWorkoutSessionManager(
    // ... all existing constructor params
) : WorkoutStateProvider {

    val coordinator = WorkoutCoordinator(_hapticEvents)

    val routineFlowManager = RoutineFlowManager(
        coordinator = coordinator,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        resolveWeightsUseCase = resolveWeightsUseCase,
        completedSetRepository = completedSetRepository,
        scope = scope
    )

    val activeSessionEngine = ActiveSessionEngine(
        coordinator = coordinator,
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
        settingsManager = settingsManager,
        scope = scope
    )

    // Public API: delegate to coordinator's public StateFlows
    val workoutState: StateFlow<WorkoutState> get() = coordinator.workoutState
    // ...

    // Public API: delegate methods to appropriate sub-manager
    fun startWorkout(...) = activeSessionEngine.startWorkout(...)
    fun loadRoutine(routine: Routine) = routineFlowManager.loadRoutine(routine)
    // ...

    // Orchestration: cross-cutting methods stay here
    fun proceedFromSummary() { ... }  // reads both routine + workout state
}
```

### Test Harness Update
```kotlin
// Source: Analysis of DWSMTestHarness (shared/src/commonTest/.../DWSMTestHarness.kt)
// The harness must be updated to construct sub-managers
class DWSMTestHarness(val testScope: TestScope) {
    // ... existing fakes unchanged ...

    val dwsm = DefaultWorkoutSessionManager(
        // ... same constructor params ...
    ).also {
        it.bleConnectionManager = BleConnectionManager(
            fakeBleRepo, settingsManager, it, dwsmScope
        )
    }

    // Test access to coordinator for state inspection
    val coordinator get() = dwsm.coordinator

    // cleanup unchanged - dwsmJob.cancel() still cancels everything
    fun cleanup() { dwsmJob.cancel() }
}
```

## handleMonitorMetric() Hot Path Analysis

**Recommendation: Place in ActiveSessionEngine.**

Reasoning:
1. Called on every BLE metric (~10-50Hz). Performance critical.
2. Reads: `_workoutParameters`, `_workoutState`, `_currentMetric`, `_repCount`, `_repRanges`, `_autoStopState`, auto-stop flags
3. Writes: `_currentMetric`, `_repCount`, `_repRanges`, `_autoStopState`, `collectedMetrics`
4. Calls: `collectMetricForHistory()`, `checkAutoStop()`, `handleSetCompletion()`, `repCounter.*`
5. ALL dependencies are workout-lifecycle (ActiveSessionEngine domain)
6. ZERO dependencies on routine navigation (RoutineFlowManager domain)

## Circular Dependency Analysis

**Current:** `lateinit var bleConnectionManager: BleConnectionManager` on DWSM

**Usage:** Only `setConnectionError()` -- called from `startWorkout()` catch blocks (2 places).

**Resolution:** SharedFlow `bleErrorEvents` on WorkoutCoordinator. ActiveSessionEngine emits errors; BleConnectionManager collects them in its init block.

**Impact:** Eliminates the lateinit var entirely. BleConnectionManager receives the SharedFlow in its constructor. No more circular wiring step in MainViewModel's init.

## Open Questions

1. **`startRestTimer()` ownership** -- starts rest countdown AND advances to next set/exercise. The rest timer is workout lifecycle, but "advance to next" is navigation.
   - **Recommendation:** Keep in ActiveSessionEngine. The advancement at the end is a simple call to DWSM orchestration (`startNextSetOrExercise` or `advanceToNextSetInSingleExercise`).

2. **Helper methods shared between managers** -- `isBodyweightExercise()` and `isSingleExerciseMode()` are used by both RoutineFlowManager and ActiveSessionEngine.
   - **Recommendation:** Place on WorkoutCoordinator as utility methods, or make them top-level package functions. They only read state from the coordinator.

3. **Data classes at top of file** -- JustLiftDefaults, ResumableProgressInfo, CycleDayCompletionEvent.
   - **Recommendation:** Keep in DWSM file or move to a separate `WorkoutModels.kt` in the same package. Not blocking.

## Sources

### Primary (HIGH confidence)
- Direct code analysis of `DefaultWorkoutSessionManager.kt` (4,024 lines, read in full)
- Direct code analysis of `BleConnectionManager.kt` (253 lines, read in full)
- Direct code analysis of `MainViewModel.kt` (construction + delegation, ~303 lines)
- Direct code analysis of `DWSMTestHarness.kt` (80 lines)
- Direct code analysis of `DWSMWorkoutLifecycleTest.kt` (302 lines, 16 tests)
- Direct code analysis of `DWSMRoutineFlowTest.kt` (537 lines, 22 tests)

### Secondary (MEDIUM confidence)
- Phase context (CONTEXT.md) decisions from user discussion

## Metadata

**Confidence breakdown:**
- Method inventory: HIGH -- complete line-by-line analysis of all 4,024 lines
- Ownership assignments: HIGH -- based on dependency analysis (reads/writes/calls)
- Init block analysis: HIGH -- each collector's dependencies fully traced
- handleMonitorMetric placement: HIGH -- zero routine dependencies confirmed
- BLE circular dependency resolution: HIGH -- only 2 usage points, clean SharedFlow pattern
- DWSM final size: MEDIUM -- depends on how much orchestration logic stays vs moves
- Test harness changes: HIGH -- minimal changes needed (construction wiring)

**Research date:** 2026-02-13
**Valid until:** No expiry (pure refactoring of stable code, no external dependencies)

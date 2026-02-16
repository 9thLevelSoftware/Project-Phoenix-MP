# Architecture Patterns: DefaultWorkoutSessionManager Decomposition

**Domain:** KMP fitness app - architectural cleanup of 4,024-line manager class
**Researched:** 2026-02-12
**Overall confidence:** HIGH (based on direct codebase analysis, existing design docs, established patterns)

---

## Current State Analysis

### What Exists Today

```
MainViewModel (420L, facade)
  |-- SettingsManager (concrete class, ~100L) -- DONE
  |-- HistoryManager (concrete class, ~120L) -- DONE
  |-- GamificationManager (concrete class, ~130L) -- DONE
  |-- BleConnectionManager (concrete class, ~200L) -- DONE
  |-- DefaultWorkoutSessionManager (4,024L) -- TARGET
        implements WorkoutStateProvider (narrow interface for BLE circular dep)
        lateinit var bleConnectionManager -- POST-INIT SETTER (the smell)
```

**Key observation:** Phases 1-2 of the architect's plan are COMPLETE. The 5 managers exist as concrete classes (not interfaces). MainViewModel is already a thin facade. The remaining work is decomposing DefaultWorkoutSessionManager and resolving the circular dependency properly.

### DefaultWorkoutSessionManager Responsibility Map

From the section headers and function analysis (4,024 lines):

| Section | Lines | Responsibility | Coupling |
|---------|-------|---------------|----------|
| State declarations | 1-349 | 30+ MutableStateFlows, 10+ private vars | Shared across all sections |
| Init block collectors | 377-562 | 8 coroutine collectors (routines, exercises, rep events, handle state, deload, metrics) | Reads/writes nearly all state |
| Round 1: Pure helpers | 563-898 | Bodyweight detection, single-exercise mode, set summary metrics, auto-stop logic | Reads workout state + params |
| Round 2: Superset navigation | 899-1168 | Superset flow, next/prev step calculation | Reads routine + exercise index state |
| Round 3: Routine CRUD + nav | 1169-1772 | saveRoutine, loadRoutine, enterSetReady, exercise navigation, RPE | Writes routine flow state + workout params |
| Round 4: Superset CRUD | 1773-1876 | Create/update/delete supersets | Writes routine state |
| Round 5: Weight adjustment | 1877-1984 | adjustWeight, increment/decrement, BLE packet sending | Writes workout params + BLE commands |
| Round 6: Just Lift | 1985-2187 | Handle detection, Just Lift defaults, single exercise defaults | Writes workout state + BLE commands |
| Round 7: Training cycles | 2188-2255 | Cycle context, progress tracking | Reads routine state, writes cycle state |
| Round 8: Core lifecycle | 2256-4024 | startWorkout, stopWorkout, handleRepNotification, handleMonitorMetric, checkAutoStop, saveWorkoutSession, restTimer, proceedFromSummary | Reads/writes EVERYTHING |

**The critical insight:** Round 8 (core lifecycle) at ~1,770 lines is the hardest to decompose because it touches ALL state. Rounds 2-4 (superset/routine navigation) at ~970 lines are the cleanest extraction candidates because they mostly read shared state and write to routine flow state.

---

## Recommended Architecture

### Target Structure

```
DefaultWorkoutSessionManager (~800L, coordinator + delegation)
  |
  |-- WorkoutCoordinator (shared state bus, ~200L)
  |       All MutableStateFlows live here
  |       Both sub-managers get a reference to this
  |
  |-- RoutineFlowManager (~1,200L)
  |       Rounds 2, 3, 4: superset nav, routine CRUD, exercise navigation
  |       Reads coordinator state, writes routine flow state
  |
  |-- ActiveSessionEngine (~1,800L)
  |       Rounds 1, 5, 6, 7, 8: helpers, weight, just lift, cycles, core lifecycle
  |       Reads/writes coordinator state, BLE commands
  |
  +-- BleConnectionManager (unchanged, external)
        Still receives WorkoutStateProvider interface
```

### Why This Structure (Not Deeper Decomposition)

The architect's original design doc proposed exactly this split. Having analyzed the actual code, I confirm it is the right cut because:

1. **RoutineFlowManager is the natural seam.** Routine navigation (which exercise, which set, superset cycling) is conceptually separate from workout execution (start, stop, rep counting, auto-stop). They share state (current exercise index, workout parameters) but have distinct write domains.

2. **Deeper decomposition (e.g., separate AutoStopManager, WeightManager) would create more coupling, not less.** Weight adjustment needs BLE commands + workout parameters + auto-stop state. Auto-stop needs metrics + rep counter + workout state. Splitting these creates more cross-references than the current structure.

3. **800L coordinator class is manageable.** The coordinator mostly delegates to RoutineFlowManager or ActiveSessionEngine, wires init block collectors, and implements the public interface.

### Component Boundaries

| Component | Responsibility | Reads From | Writes To |
|-----------|---------------|------------|-----------|
| WorkoutCoordinator | Holds ALL MutableStateFlows, exposes read-only StateFlows | N/A (data store) | N/A (data store) |
| DefaultWorkoutSessionManager | Public API, init block wiring, delegates to sub-managers | Coordinator | Coordinator (via sub-managers) |
| RoutineFlowManager | Routine CRUD, exercise/set/superset navigation, set-ready flow | Coordinator (routineFlowState, loadedRoutine, exerciseIndex, setIndex, workoutParameters) | Coordinator (routineFlowState, exerciseIndex, setIndex, skippedExercises, completedExercises, workoutParameters) |
| ActiveSessionEngine | Workout start/stop, rep processing, auto-stop, weight adjustment, Just Lift, training cycles, save session | Coordinator (workoutState, workoutParameters, repCount, metrics, etc.) | Coordinator (workoutState, repCount, currentMetric, autoStopState, etc.) |
| BleConnectionManager | Connection lifecycle, connection-loss detection | WorkoutStateProvider (narrow interface) | Own state only |

### Data Flow

```
UI Screen
  |
  v
MainViewModel (facade)
  |
  v
DefaultWorkoutSessionManager (public API)
  |
  +---> RoutineFlowManager.loadRoutine(routine)
  |       |-- Resolves weights
  |       |-- Sets coordinator._loadedRoutine
  |       |-- Sets coordinator._routineFlowState = Overview
  |
  +---> ActiveSessionEngine.startWorkout()
  |       |-- Reads coordinator._workoutParameters
  |       |-- Sends BLE start packet
  |       |-- Sets coordinator._workoutState = Countdown/Active
  |       |-- Starts monitorDataCollectionJob
  |
  +---> ActiveSessionEngine.handleSetCompletion() [internal]
  |       |-- Calls saveWorkoutSession()
  |       |-- Calls gamificationManager.processPostSaveEvents()
  |       |-- Sets coordinator._workoutState = SetSummary
  |
  +---> DefaultWorkoutSessionManager.proceedFromSummary()
          |-- If routine has next step:
          |     ActiveSessionEngine checks autoplay
          |     RoutineFlowManager.enterSetReady(nextEx, nextSet)
          |-- If routine complete:
          |     RoutineFlowManager.showRoutineComplete()
```

**Critical coupling point:** `proceedFromSummary()` is where ActiveSession and RoutineFlow meet. This function stays in DefaultWorkoutSessionManager (the coordinator layer) because it reads from both domains and decides the flow.

---

## Resolving the Circular Dependency

### Current State (The Smell)

```kotlin
// In MainViewModel:
val workoutSessionManager = DefaultWorkoutSessionManager(...)
val bleConnectionManager = BleConnectionManager(..., workoutSessionManager, ...)
init {
    workoutSessionManager.bleConnectionManager = bleConnectionManager  // POST-INIT SETTER
}
```

`DefaultWorkoutSessionManager` uses `bleConnectionManager` for:
1. `setConnectionError(message)` -- when BLE send fails during workout
2. That's it. One method call.

`BleConnectionManager` uses `WorkoutStateProvider` for:
1. `isWorkoutActiveForConnectionAlert` -- to decide if connection loss should show alert

### Recommended Resolution: Event-Based Decoupling

**Do NOT use the WorkoutCoordinator proposed in the architect doc for this.** The coordinator is for internal DWSM decomposition. The BLE circular dep is a separate concern.

**Option chosen: Eliminate the lateinit entirely.**

```kotlin
// Step 1: DWSM no longer references BleConnectionManager directly
// Instead, it emits connection errors through an event flow

class DefaultWorkoutSessionManager(...) : WorkoutStateProvider {
    // Replace: lateinit var bleConnectionManager
    // With: event-driven error reporting
    private val _connectionErrors = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val connectionErrors: SharedFlow<String> = _connectionErrors.asSharedFlow()

    // In BLE send failure handlers, instead of:
    //   bleConnectionManager.setConnectionError(message)
    // Do:
    //   _connectionErrors.emit(message)
}

// Step 2: MainViewModel wires the event to BleConnectionManager
init {
    viewModelScope.launch {
        workoutSessionManager.connectionErrors.collect { error ->
            bleConnectionManager.setConnectionError(error)
        }
    }
}
```

**Why this is better:**
- No `lateinit var` (eliminates potential UninitializedPropertyAccessException)
- No circular reference (DWSM does not know BleConnectionManager exists)
- WorkoutStateProvider interface stays (it's already clean -- read-only, one property)
- MainViewModel already does coordination wiring; one more collector is natural

**Confidence:** HIGH. This is a well-established pattern. The existing code only calls one method on bleConnectionManager from DWSM, making this trivial.

---

## Koin Module Structure for 8+ Managers

### Current Problem

All DI is in a single `commonModule` (92 lines). Managers are NOT in Koin -- they're created inline in MainViewModel's constructor body, receiving `viewModelScope`.

### Recommended: Keep Managers Out of Koin (For Now)

**Do not put managers into Koin.** Here is why:

1. **Scope lifecycle.** Managers need `viewModelScope` from MainViewModel. Koin `single` beans outlive viewModelScope. Koin `factory` beans create new instances each time. Neither matches "same lifecycle as the ViewModel that owns them."

2. **Koin `viewModel` scoped providers** exist but add complexity for no gain. The managers are implementation details of MainViewModel's decomposition, not independently injectable services.

3. **No screen needs to inject a manager directly.** All 20+ screens take `viewModel: MainViewModel` as a parameter (verified by grepping screen signatures). Screens access managers through the facade.

### When to Move to Koin (Future Phase)

Move managers to Koin ONLY when:
- Screens start accessing managers directly (UI decoupling phase)
- Multiple ViewModels need the same manager instance
- Test setup becomes painful without DI

### Recommended Koin Structure for When That Day Comes

Split the single `commonModule` into feature modules:

```kotlin
// di/DataModule.kt
val dataModule = module {
    single { DatabaseFactory(get()).createDatabase() }
    single { ExerciseImporter(get()) }
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ProgressionRepository> { SqlDelightProgressionRepository(get()) }
    single<UserProfileRepository> { SqlDelightUserProfileRepository(get()) }
}

// di/SyncModule.kt
val syncModule = module {
    single { PortalTokenStorage(get()) }
    single { PortalApiClient(tokenProvider = { get<PortalTokenStorage>().getToken() }) }
    single<SyncRepository> { SqlDelightSyncRepository(get()) }
    single { SyncManager(get(), get(), get()) }
    single { SyncTriggerManager(get(), get()) }
    single<AuthRepository> { PortalAuthRepository(get(), get()) }
    single { SubscriptionManager(get()) }
}

// di/DomainModule.kt
val domainModule = module {
    single { RepCounterFromMachine() }
    single { ProgressionUseCase(get(), get()) }
    factory { ResolveRoutineWeightsUseCase(get()) }
    single { TemplateConverter(get()) }
}

// di/PresentationModule.kt
val presentationModule = module {
    single<PreferencesManager> { SettingsPreferencesManager(get()) }
    single { MigrationManager() }

    factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { ConnectionLogsViewModel() }
    factory { CycleEditorViewModel(get()) }
    factory { GamificationViewModel(get()) }
    single { ThemeViewModel(get()) }
    single { EulaViewModel(get()) }
    factory { LinkAccountViewModel(get()) }
}

// di/KoinInit.kt
fun initKoin() {
    startKoin {
        modules(platformModule, dataModule, syncModule, domainModule, presentationModule)
    }
}
```

**Confidence:** HIGH. This is standard Koin structuring for apps of this size.

---

## UI Screen Access Pattern

### Current Pattern (Keep It)

All screens receive `viewModel: MainViewModel` as a Compose function parameter. This is passed down from the navigation graph. Screens observe `viewModel.workoutState`, `viewModel.routines`, etc.

```kotlin
// Example: ActiveWorkoutScreen
fun ActiveWorkoutScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val workoutState by viewModel.workoutState.collectAsState()
    val repCount by viewModel.repCount.collectAsState()
    // ...
}
```

### Why NOT to Change This Now

1. **20+ screens** take MainViewModel. Changing signatures is a massive diff with no behavioral change.
2. The facade pattern works. Screens don't care that workoutState comes from DWSM internally.
3. The UI decoupler analysis (`.planning/refactoring/ui-decoupler-analysis.md`) already documents the future plan for screens to take narrower interfaces.

### Future Pattern (When UI Decoupling Happens)

```kotlin
// Future: screens take narrow interfaces, not the god-ViewModel
fun ActiveWorkoutScreen(
    workoutState: StateFlow<WorkoutState>,
    repCount: StateFlow<RepCount>,
    onStopWorkout: () -> Unit,
    onAdjustWeight: (Float) -> Unit,
    // ...
)
```

This is a separate milestone. Do NOT mix it with the DWSM decomposition.

---

## Patterns to Follow

### Pattern 1: Coordinator as Shared State Bus

**What:** A concrete class holding all MutableStateFlows, referenced by both RoutineFlowManager and ActiveSessionEngine.

**When:** When two components need read/write access to the same state and splitting ownership would create excessive eventing.

**Implementation:**

```kotlin
class WorkoutCoordinator {
    // Workout execution state
    val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    val _workoutParameters = MutableStateFlow(WorkoutParameters.DEFAULT)
    val workoutParameters: StateFlow<WorkoutParameters> = _workoutParameters.asStateFlow()

    // Routine navigation state
    val _routineFlowState = MutableStateFlow<RoutineFlowState>(RoutineFlowState.NotInRoutine)
    val _currentExerciseIndex = MutableStateFlow(0)
    val _currentSetIndex = MutableStateFlow(0)
    val _loadedRoutine = MutableStateFlow<Routine?>(null)

    // Non-flow mutable state
    var currentSessionId: String? = null
    var workoutStartTime: Long = 0
    var stopWorkoutInProgress = false
    var setCompletionInProgress = false
    // ... etc
}
```

**Convention:** `_` prefix properties are mutable, used by sub-managers. Non-prefixed are read-only, exposed to UI.

### Pattern 2: Narrow Interface for Cross-Component Communication

**What:** Instead of passing full manager references, pass a minimal interface with only the needed property/method.

**Already in use:** `WorkoutStateProvider` with single property `isWorkoutActiveForConnectionAlert`.

**Apply to:** The event-based connection error pattern replaces the need for DWSM to reference BleConnectionManager at all.

### Pattern 3: Init Block Collector Ownership

**What:** Each coroutine collector in the init block should live in the component that acts on its data.

**Current:** All 8 collectors are in DWSM's init block.

**After decomposition:**

| Collector | Owner | Why |
|-----------|-------|-----|
| Routines list (workoutRepository.getAllRoutines()) | DefaultWorkoutSessionManager | Initializes coordinator._routines |
| Exercise import | DefaultWorkoutSessionManager | One-time initialization |
| RepCounter onRepEvent | ActiveSessionEngine | Processes rep events, updates state |
| HandleState collector | ActiveSessionEngine | Auto-start/stop logic |
| Deload event collector | ActiveSessionEngine | Firmware auto-stop |
| Monitor metrics collector | ActiveSessionEngine | Real-time BLE data processing |
| Rep events collection | ActiveSessionEngine | Connects to BLE repo |
| Workout data collection | ActiveSessionEngine | Metric history collection |

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Over-Decomposition

**What:** Splitting DWSM into 6+ tiny managers (AutoStopManager, WeightManager, RestTimerManager, etc.)

**Why bad:** Auto-stop reads metrics, workout state, rep counter, and workout parameters. Weight adjustment reads workout parameters and sends BLE commands. Rest timer reads workout state and transitions to next set via routine navigation. Each "small manager" would need references to 3-4 others, creating a web of dependencies worse than the current monolith.

**Instead:** Two sub-managers (RoutineFlow + ActiveSession) with a shared coordinator. This is the minimum viable decomposition.

### Anti-Pattern 2: Making Managers Koin Singletons

**What:** Registering SettingsManager, HistoryManager, etc. as `single` in Koin and injecting them into screens.

**Why bad:** Managers share `viewModelScope`. If a screen gets a Koin-scoped SettingsManager, it outlives the ViewModel, leading to scope cancellation bugs. Additionally, screens would need to import 3-4 managers instead of one ViewModel, increasing coupling.

**Instead:** Keep managers as ViewModel-owned. Screens access through MainViewModel facade.

### Anti-Pattern 3: Using Coordinator as a God Object

**What:** Putting business logic IN the WorkoutCoordinator (e.g., validation, state transition rules).

**Why bad:** Coordinator becomes the new monolith. It should be a dumb data container.

**Instead:** Coordinator holds state. Sub-managers hold logic. Coordinator has zero methods (only properties).

### Anti-Pattern 4: Premature Interface Extraction

**What:** Creating interfaces for RoutineFlowManager and ActiveSessionEngine.

**Why bad:** These are internal implementation details of DWSM. No external consumer needs to swap implementations. Interfaces add indirection with no testability benefit (test the integration, not mocked internals).

**Instead:** Concrete classes. If testing is needed, test through DWSM's public API with fake repositories.

---

## Build Order (Dependency-Aware)

### Phase 1: WorkoutCoordinator (No Behavioral Change)

**New file:** `WorkoutCoordinator.kt` (~200L)
**Modifies:** `DefaultWorkoutSessionManager.kt`

Extract all MutableStateFlow declarations and shared mutable vars into WorkoutCoordinator. DWSM creates it internally and delegates all StateFlow properties to it. Zero behavioral change, zero API change.

**Verification:** All existing tests pass. App behavior identical.

### Phase 2: Resolve Circular Dependency (Small Behavioral Change)

**Modifies:** `DefaultWorkoutSessionManager.kt`, `MainViewModel.kt`

Replace `lateinit var bleConnectionManager` with `connectionErrors: SharedFlow<String>`. Wire in MainViewModel's init block.

**Verification:** Test that BLE send failures still surface as connection errors in the UI.

### Phase 3: Extract RoutineFlowManager

**New file:** `RoutineFlowManager.kt` (~1,200L)
**Modifies:** `DefaultWorkoutSessionManager.kt` (removes ~1,200L)

Move Rounds 2, 3, 4 (superset navigation, routine CRUD + navigation, superset CRUD). RoutineFlowManager takes WorkoutCoordinator + repositories as constructor params.

DWSM creates RoutineFlowManager in constructor and delegates routine operations to it.

**Critical integration point:** `proceedFromSummary()` stays in DWSM because it orchestrates between ActiveSession and RoutineFlow.

**Verification:** Full routine flow E2E test: load routine -> navigate exercises -> complete sets -> rest timer -> routine complete.

### Phase 4: Extract ActiveSessionEngine

**New file:** `ActiveSessionEngine.kt` (~1,800L)
**Modifies:** `DefaultWorkoutSessionManager.kt` (reduces to ~800L)

Move Rounds 1, 5, 6, 7, 8 (helpers, weight, just lift, cycles, core lifecycle). ActiveSessionEngine takes WorkoutCoordinator + repositories + BLE + rep counter as constructor params.

**Critical functions that stay in DWSM** (orchestration between both sub-managers):
- `proceedFromSummary()` -- decides rest timer vs next set vs routine complete
- `startSetFromReady()` -- transitions from routine flow (SetReady) to active workout
- Init block collectors that reference both sub-managers

**Verification:** Full workout lifecycle E2E test: start -> active -> auto-stop -> summary -> rest -> next set -> complete.

### Phase 5: Characterization Tests (Parallel With Phases 3-4)

Write tests BEFORE extraction where possible:
- Routine loading with weight resolution
- Superset navigation sequencing
- Auto-stop stall detection state machine
- Just Lift handle detection -> auto-start -> auto-stop flow
- `proceedFromSummary()` branching logic (autoplay on/off, routine vs single exercise)

Use the existing `WorkoutRobot` and fake repositories. The robot currently targets MainViewModel -- after extraction, it should still work identically (DWSM's API hasn't changed, just its internals).

---

## Scalability Considerations

| Concern | Current (4,024L monolith) | After decomposition (4 files) | Future (UI decoupling) |
|---------|---------------------------|-------------------------------|------------------------|
| Developer comprehension | Must understand entire file to change anything | Can focus on RoutineFlow or ActiveSession independently | Screens only know their narrow interface |
| Test isolation | Must construct full DWSM with 13 deps to test anything | Can test RoutineFlowManager with coordinator + exercise repo only | Screen tests with mocked flows |
| Merge conflicts | Any workout change touches same file | Routine changes in RoutineFlow, workout changes in ActiveSession | Minimal |
| New workout mode | Understand all 4,024 lines | Add to ActiveSessionEngine (~1,800L relevant) | Same |
| New exercise type | Understand routine nav + workout logic | Routine type in RoutineFlowManager, execution in ActiveSessionEngine | Same |

---

## Integration Points Summary

### New Components

| Component | Type | Location | Depends On |
|-----------|------|----------|------------|
| `WorkoutCoordinator` | Concrete class | `presentation/manager/WorkoutCoordinator.kt` | Domain models only |
| `RoutineFlowManager` | Concrete class | `presentation/manager/RoutineFlowManager.kt` | WorkoutCoordinator, ExerciseRepository, WorkoutRepository, PreferencesManager, ResolveRoutineWeightsUseCase |
| `ActiveSessionEngine` | Concrete class | `presentation/manager/ActiveSessionEngine.kt` | WorkoutCoordinator, BleRepository, WorkoutRepository, ExerciseRepository, PersonalRecordRepository, RepCounterFromMachine, PreferencesManager, GamificationManager, TrainingCycleRepository, CompletedSetRepository, SyncTriggerManager, SettingsManager |

### Modified Components

| Component | Change | Risk |
|-----------|--------|------|
| `DefaultWorkoutSessionManager` | Reduced from 4,024L to ~800L coordinator | HIGH - must preserve all behavior |
| `MainViewModel` | Add connectionErrors collector, remove lateinit wiring | LOW - small addition |
| `BleConnectionManager` | None (WorkoutStateProvider interface unchanged) | NONE |

### Unchanged Components

All UI screens, all repositories, all other ViewModels, all domain models, Koin module structure.

---

## Sources

- Direct codebase analysis (PRIMARY): `DefaultWorkoutSessionManager.kt` (4,024L), `MainViewModel.kt` (420L), `BleConnectionManager.kt` (~200L), `AppModule.kt` (92L)
- Existing design docs: `.planning/refactoring/architect-interface-design.md`, `.planning/refactoring/surgeon-extraction-plan.md`
- Existing state: `.planning/STATE.md` (confirms Phases 1-2 complete)
- UI screen signature analysis: 20+ screens all take `viewModel: MainViewModel`
- Test infrastructure: `WorkoutRobot.kt`, 10+ fake repositories in `testutil/`

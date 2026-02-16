# Domain Pitfalls: DefaultWorkoutSessionManager Decomposition

**Domain:** KMP workout session manager decomposition (4,024 lines to sub-managers)
**Researched:** 2026-02-12
**Confidence:** HIGH (based on direct codebase analysis and KMP/coroutine ecosystem knowledge)

## Critical Pitfalls

Mistakes that cause broken workouts, data loss, or require reverts.

### Pitfall 1: Split Mutable State Creates Race Conditions Without synchronized()

**What goes wrong:** The current manager has ~30 MutableStateFlow fields and ~15 plain mutable vars (like `stopWorkoutInProgress`, `setCompletionInProgress`, `autoStopStartTime`, `stallStartTime`) that are read and written across multiple coroutines launched on `scope`. When these are split across sub-managers, a single operation like `handleSetCompletion()` that currently atomically reads `_workoutState`, writes `_isCurrentExerciseBodyweight`, resets `isCurrentWorkoutTimed`, and updates `_repCount` now requires cross-manager coordination. Without it, sub-manager A reads stale state from sub-manager B between frames.

**Why it happens:** The current design works because all state lives in one class -- any method can read any field atomically within a single function call. Extraction breaks this implicit atomicity. Notably, this codebase does NOT use `synchronized(lock)` in DefaultWorkoutSessionManager (grep confirms zero hits) -- it relies on single-threaded coroutine dispatch on the shared `scope`. This means the implicit contract is "all mutations happen on the same dispatcher," which is easy to violate when sub-managers start launching their own coroutines.

**Consequences:**
- `handleSetCompletion()` reads `_workoutState` as Active but by the time it calls `bleRepository.stopWorkout()`, another sub-manager has already transitioned to Idle
- `stopWorkoutInProgress` guard flag in one sub-manager is not visible to the auto-stop sub-manager, causing duplicate session saves
- The `previousExerciseWasBodyweight` flag (Issue #222) is set in set-completion but read in next `startWorkout()` -- if these land in different sub-managers, the flag write may not be visible before the read

**Prevention:**
- Keep the workout state machine (`_workoutState`, `_routineFlowState`, guard flags) in a single "WorkoutStateCoordinator" that all sub-managers receive as a dependency
- Use a `StateFlow<WorkoutState>` as the single source of truth, never duplicate it
- All state transitions go through the coordinator, sub-managers request transitions rather than mutating directly
- Design sub-manager boundaries along state *ownership* lines, not functional lines -- whoever owns the state owns all mutations to it

**Detection:**
- Intermittent duplicate session saves in workout history
- "handleSetCompletion: already in progress" log never fires when it should
- Machine stays in fault state (red lights) after workout stops
- Bodyweight exercises incorrectly send BLE stop commands

**Phase:** Address in Phase 1 (architecture design) -- this is the foundational decision

---

### Pitfall 2: Coroutine Scope Sharing Creates Lifecycle Chaos

**What goes wrong:** Currently all 35+ `scope.launch` calls use the same `CoroutineScope` (passed from `viewModelScope`). Sub-managers will either (a) share the same scope, creating ambiguity about who cancels what, or (b) get their own scopes, creating lifecycle divergence where one sub-manager's coroutines outlive another's.

**Why it happens:** The manager has explicit job tracking (`monitorDataCollectionJob`, `autoStartJob`, `restTimerJob`, `bodyweightTimerJob`, `workoutJob`, `repEventsCollectionJob`) and cancels them at specific state transitions. When `stopWorkout()` cancels `workoutJob` and `bodyweightTimerJob`, these jobs MUST be in the same scope hierarchy, or cancellation from one sub-manager won't reach jobs in another.

**Consequences:**
- Rest timer continues counting after workout is stopped (different sub-manager, different scope)
- `bodyweightTimerJob` fires completion after the workout has been manually stopped
- Auto-start countdown triggers a new set after the user already exited the routine
- BLE monitor collection continues after disconnect because the sub-manager scope wasn't cancelled
- Memory leaks from orphaned collectors

**Prevention:**
- All sub-managers MUST share the same parent `CoroutineScope` (the `viewModelScope`)
- Do NOT give sub-managers their own `CoroutineScope` -- pass the shared one
- Job references that need cross-manager cancellation should live in the state coordinator
- Use `SupervisorJob` children so one sub-manager's failure doesn't cascade
- Document which jobs are cancelled by which state transitions (this is currently implicit knowledge spread across `stopWorkout()`, `handleSetCompletion()`, `resetForNewWorkout()`, and `cleanup()`)

**Detection:**
- Logcat shows "already in progress" guards firing unexpectedly
- Timer UI shows stale countdown values after workout ends
- BLE polling continues after disconnect (visible in Nordic nRF logs)

**Phase:** Address in Phase 1 (architecture design) -- scope strategy must be decided before any extraction

---

### Pitfall 3: Breaking the handleMonitorMetric() Hot Path

**What goes wrong:** `handleMonitorMetric()` is called on every BLE metric update (~10-20Hz) and reads/writes state across nearly every concern: rep counting, auto-stop detection, position tracking, phase animation, rep ranges, metrics collection. Splitting these concerns across sub-managers means this hot path now involves multiple method calls, potentially multiple flow emissions, and coordination overhead.

**Why it happens:** This is the core real-time loop. It currently does, in sequence:
1. Updates position ranges on `repCounter` (rep counting concern)
2. Collects metrics for history (persistence concern)
3. Updates phase tracking and rep count flows (UI concern)
4. Checks auto-stop with velocity stall detection (safety concern)
5. Checks rep-target completion (workout logic concern)

Each step depends on state from the previous step or from shared state.

**Consequences:**
- Performance regression: 5 sub-manager calls instead of 1 inlined method adds overhead at 10-20Hz
- State inconsistency: `repCounter.shouldStopWorkout()` is checked AFTER position updates -- if these are in different sub-managers, the check might happen before the update
- Auto-stop false triggers: velocity stall detection uses `_workoutState.value` as a guard -- if the state check and the velocity check are in different managers, the guard may be stale
- Lost workout data: `collectMetricForHistory()` adds to `collectedMetrics` list which is read by `handleSetCompletion()` -- if split, the list reference breaks

**Prevention:**
- Keep `handleMonitorMetric()` as a single orchestration point in the parent manager or state coordinator
- Sub-managers expose processing functions that are called synchronously from this orchestrator
- Do NOT have sub-managers collect from `bleRepository.monitorData` independently -- one collector, dispatches to sub-managers
- Consider a "MetricPipeline" pattern: metric comes in, passes through stages, each stage owned by a sub-manager but executed in sequence

**Detection:**
- Rep count lags behind actual movement
- Auto-stop triggers at wrong times
- Workout history shows fewer metrics than expected
- Position bar visualization jumps or freezes

**Phase:** Address in Phase 2 (extraction of metric processing) -- this is the highest-risk extraction

---

### Pitfall 4: Circular Dependency Proliferation via lateinit

**What goes wrong:** The existing `lateinit var bleConnectionManager` pattern (line 143) already works around a circular dependency. When sub-managers are extracted, new circular dependencies emerge: e.g., RoutineFlowManager needs WorkoutExecutionManager to start sets, but WorkoutExecutionManager needs RoutineFlowManager to know what exercise to load. Each new circular dep gets another `lateinit`, creating a fragile initialization web.

**Why it happens:** The 4,024-line manager is a God object precisely because everything depends on everything. Naive extraction along functional boundaries (routine management, workout execution, auto-stop, metric processing) creates bidirectional dependencies because these concerns are deeply interleaved.

**Consequences:**
- `UninitializedPropertyAccessException` at runtime when a sub-manager method is called before wiring completes
- Koin fails silently or with cryptic errors when circular `single{}` declarations exist
- Order-dependent initialization in `MainViewModel.init{}` becomes a maze
- Testing requires elaborate setup to wire all dependencies

**Prevention:**
- Use an **event bus or callback interface** pattern instead of direct references between sub-managers
- Define narrow interfaces (like `WorkoutStateProvider` already does for BleConnectionManager) for each cross-cutting concern
- Prefer **unidirectional dependencies**: sub-managers depend on the coordinator, never on each other
- If A needs to tell B something, A emits an event, coordinator routes it to B
- In Koin: register sub-managers with `single{}`, wire them in the `MainViewModel` constructor (not in module declarations) -- this keeps Koin's DI graph acyclic

**Detection:**
- `lateinit` count increases during extraction (should decrease or stay flat)
- Koin startup time increases
- Test setup requires >5 lines of wiring boilerplate

**Phase:** Address in Phase 1 (architecture design) -- interface boundaries must be designed before extraction

---

### Pitfall 5: Koin Module Registration Order and Scoping Failures

**What goes wrong:** The current `AppModule.kt` has a comment "Order matters: ExerciseRepository must be created before WorkoutRepository." Adding sub-managers as `single{}` declarations creates new ordering sensitivities. Worse, `DefaultWorkoutSessionManager` is currently NOT registered in Koin at all -- it's manually constructed in `MainViewModel`'s property initializer. If extraction moves sub-managers into Koin, the circular dependency with `BleConnectionManager` must be resolved in the DI graph.

**Why it happens:** Koin resolves dependencies eagerly for `single{}` and lazily for `factory{}`. If sub-manager A is `single{}` and depends on sub-manager B (also `single{}`), and B depends on A, Koin throws `StackOverflowError` during module verification or `NoBeanDefFoundException` at runtime.

**Consequences:**
- App crashes on startup with Koin resolution errors
- Subtle bugs where a `factory{}` sub-manager creates a new instance per injection, losing state
- iOS app crashes separately from Android because platform modules load differently
- Koin `checkModules()` passes in tests but fails at runtime due to platform module differences

**Prevention:**
- Keep manual construction in `MainViewModel` for the sub-manager graph (match existing pattern for `SettingsManager`, `HistoryManager`, `GamificationManager`, `BleConnectionManager`)
- Do NOT move sub-managers into Koin modules unless they need to be injected elsewhere
- If they must be in Koin, use `single{}` with `get()` parameters (not constructor injection) and wire circular deps post-construction
- Run `koinApplication { checkModules() }` in a test after any DI changes
- The `factory { MainViewModel(...) }` pattern means a new VM is created each time -- ensure sub-managers that were previously singletons within the VM aren't accidentally shared across VM instances

**Detection:**
- App crash on launch (immediate feedback, low risk of shipping)
- Sub-manager state resets unexpectedly (factory instead of single)
- iOS-only crashes (platform module ordering differs)

**Phase:** Address in Phase 1 (architecture design) and validate in each extraction phase

## Moderate Pitfalls

### Pitfall 6: Compose Recomposition Cascades from StateFlow Restructuring

**What goes wrong:** Currently, `ActiveWorkoutScreen` collects 21 `StateFlow` properties from `MainViewModel`, each delegating to `workoutSessionManager`. If extraction changes the flow topology (e.g., combining multiple state flows into a single sealed-class state), screens that only need one field recompose when unrelated fields change.

**Why it happens:** Compose skips recomposition when state values are `equals()`-identical. Individual `StateFlow<Int>` for `repCount` and `StateFlow<Float>` for `currentHeuristicKgMax` only trigger their respective collectors. But if these are combined into a `WorkoutUiState` data class, changing reps also triggers recomposition for the force display.

**Prevention:**
- Maintain the same public `StateFlow` signatures during extraction -- sub-managers expose the same individual flows
- Do NOT consolidate flows into aggregate state classes unless wrapping in `derivedStateOf` or `distinctUntilChanged`
- Use `@Stable` annotations on any new state holder classes
- Keep the `MainViewModel` delegation pattern (`val repCount: StateFlow<RepCount> get() = workoutSessionManager.repCount`) -- consumers don't need to know about sub-managers
- Test with Layout Inspector's recomposition counter during active workout

**Detection:**
- UI jank during active workout (dropped frames visible on performance overlay)
- Layout Inspector shows unexpected recomposition counts
- Battery drain increase during workout (continuous unnecessary recomposition at 10-20Hz)

**Phase:** Validate in each extraction phase, not a design-phase concern

---

### Pitfall 7: KMP commonTest Coroutine Testing Gaps

**What goes wrong:** Tests in `commonTest` cannot use JUnit Rules (`TestCoroutineRule` is in `androidUnitTest` only). KMP's `runTest` from `kotlinx-coroutines-test` behaves differently across platforms -- `advanceUntilIdle()` on iOS may not advance virtual time the same way. Sub-manager tests that work on Android fail on iOS test targets.

**Why it happens:** The project currently puts most tests in `androidUnitTest` (30+ files) vs `commonTest` (12 files). `commonTest` tests use `runTest` directly without a Rule. New sub-manager tests should ideally be in `commonTest` (since the code is in `commonMain`), but the testing infrastructure wasn't built for it.

**Prevention:**
- Write sub-manager tests in `commonTest` from the start
- Use `runTest { }` with `StandardTestDispatcher()` explicitly (not via a Rule)
- Pass `TestScope.backgroundScope` as the sub-manager's `CoroutineScope` parameter
- Use `advanceUntilIdle()` after every state mutation that triggers a `scope.launch`
- Avoid `delay()` in production code where possible -- use injectable time sources
- For the `RepCounterFromMachine` callback pattern (`onRepEvent`), test the callback directly rather than through flow collection

**Detection:**
- Tests pass locally (Android) but fail in CI (if running iOS tests)
- Tests hang indefinitely (dispatcher not advancing)
- Tests pass but don't actually test the async behavior (assertions run before coroutines complete)

**Phase:** Address when writing first sub-manager tests (Phase 2)

---

### Pitfall 8: Init Block Collector Ordering After Extraction

**What goes wrong:** The `init` block (lines 379-540+) launches 6+ coroutines that collect from repositories and wire up callbacks. The ordering of these launches matters: the routine loading collector must fire before a workout can start, and the exercise import must complete before exercise lookups work. When sub-managers have their own init blocks, the order becomes non-deterministic.

**Why it happens:** Kotlin constructors and init blocks execute in declaration order. Currently: SettingsManager is constructed first, then HistoryManager, then GamificationManager, then DefaultWorkoutSessionManager. The DWSM init block runs after all dependencies are wired. If sub-managers are constructed in DWSM's constructor, their init blocks fire during DWSM construction, potentially before DWSM's own init is complete.

**Prevention:**
- Prefer explicit `start()` or `initialize()` methods over init blocks for sub-managers
- Call sub-manager `start()` methods in a defined order from the coordinator
- Move repository collection (routines loading, exercise import) into a dedicated "DataLoader" sub-manager that starts first
- Use `stateIn(scope, SharingStarted.Eagerly, ...)` for flows that must be hot immediately
- Document the startup sequence in a comment

**Detection:**
- NPE or stale data on first workout after app launch
- "Exercise not found" errors when starting a routine
- Empty routine list on first navigation to Daily Routines screen

**Phase:** Address in Phase 1 (architecture design) -- init ordering is a design constraint

---

### Pitfall 9: BLE Repository Interaction Points Create Implicit Coupling

**What goes wrong:** `bleRepository` is called from 15+ locations in DefaultWorkoutSessionManager: `startWorkout()`, `stopWorkout()`, `handleSetCompletion()`, `sendWeight()`, `sendProgramMode()`, `restartMonitorPolling()`, `enableHandleDetection()`, etc. These calls are interspersed with state mutations. If BLE command sending is extracted to a sub-manager but workout state lives elsewhere, the BLE sub-manager must query workout state before every command.

**Why it happens:** BLE commands are tightly coupled to workout state transitions. You cannot send a weight command without knowing the current workout mode. You cannot call `stopWorkout()` on BLE without checking if the exercise is bodyweight (Issue #222). The machine's state and the app's state must remain synchronized.

**Prevention:**
- Do NOT extract BLE command sending into a separate sub-manager
- BLE commands should stay co-located with the workout state transitions that trigger them
- Extract read-only BLE data processing (metric collection, position tracking) but keep command sending in the workout execution layer
- Treat `bleRepository.stopWorkout()`, `bleRepository.startWorkout()`, and `bleRepository.sendWeight()` as part of the state transition, not as a separate concern

**Detection:**
- Machine doesn't respond to weight changes
- Machine stays in workout mode after app shows Idle
- Machine shows red fault lights after sets (monitor polling not restarted)
- Issue #222 regression: bodyweight exercise sends BLE stop

**Phase:** Address in Phase 1 (architecture design) -- BLE boundary is a key design decision

## Minor Pitfalls

### Pitfall 10: collectedMetrics List Reference Breaks on Extraction

**What goes wrong:** `collectedMetrics` is a `mutableListOf<WorkoutMetric>()` that is appended to in `handleMonitorMetric()` (via `collectMetricForHistory()`) and read in `handleSetCompletion()` and `stopWorkout()` to build the session summary. If metric collection is in one sub-manager and session saving is in another, they need to share this mutable list reference.

**Prevention:** Make `collectedMetrics` a property of the state coordinator, or have the metric collector expose it as `List<WorkoutMetric>` (immutable view) to the session saver.

---

### Pitfall 11: Guard Flags Lose Effectiveness Across Sub-Managers

**What goes wrong:** `stopWorkoutInProgress` and `setCompletionInProgress` are boolean guard flags preventing re-entrant calls. These work because the check and the set happen in the same synchronous function. If `checkAutoStop()` (in auto-stop sub-manager) triggers `handleSetCompletion()` (in completion sub-manager), the guard flag may not prevent the race because the flag is in a different object.

**Prevention:** Guard flags must live in the same object that checks them. Move all guard flags to the state coordinator, or use `StateFlow<Boolean>` for cross-manager visibility.

---

### Pitfall 12: Test Fakes Multiply Exponentially

**What goes wrong:** Each sub-manager needs its own dependencies mocked. Currently `BleConnectionManagerTest` uses `FakeBleRepository`, `FakePreferencesManager`, `FakeWorkoutStateProvider`. For N sub-managers with M dependencies each, you need N*M fakes. Existing fakes may not cover the new interfaces.

**Prevention:** Define narrow interfaces for sub-manager dependencies. Reuse existing fakes. Create a `TestWorkoutFixture` builder that wires up the full sub-manager graph with defaults, allowing tests to override only what they care about.

---

### Pitfall 13: SharedFlow Event Loss During Extraction

**What goes wrong:** `_hapticEvents` is a `MutableSharedFlow` passed into DWSM from MainViewModel. Both `GamificationManager` and `DefaultWorkoutSessionManager` emit to it. If sub-managers need to emit haptic events, the flow reference must be shared. Creating a new `MutableSharedFlow` in a sub-manager means events are emitted to a flow nobody collects.

**Prevention:** Pass the shared `MutableSharedFlow<HapticEvent>` to any sub-manager that needs to emit haptic/feedback events. Do not create new flow instances. Same applies to `_userFeedbackEvents`.

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Architecture design (Phase 1) | Over-decomposition: splitting into too many sub-managers creates coordination overhead worse than the monolith | Target 3-4 sub-managers max: WorkoutStateCoordinator, RoutineFlowManager, MetricProcessor, AutoStopController |
| WorkoutState extraction | Breaking the state machine transitions that currently prevent re-entrant calls | Keep ALL state transitions in one coordinator, sub-managers request transitions |
| Routine flow extraction | Losing the implicit ordering between exercise index, set index, skipped/completed sets | These 4 flows must stay together -- they form an atomic "routine position" concept |
| Auto-stop extraction | Velocity stall detection depends on workout state guards | Auto-stop controller must receive workout state as input, not query it independently |
| Metric processing extraction | `handleMonitorMetric()` touches every concern | Keep as orchestration point, delegate to sub-managers synchronously |
| Rep counting extraction | `RepCounterFromMachine` callback (`onRepEvent`) triggers state transitions | Callback must route through coordinator, not directly to sub-managers |
| Session saving extraction | `collectedMetrics`, `currentSessionId`, `workoutStartTime` are read during save | These must be accessible to the saver -- pass as parameters or keep in coordinator |
| Testing | Tests in `androidUnitTest` use JUnit Rules unavailable in `commonTest` | Write new sub-manager tests in `commonTest` with `runTest` pattern |
| Koin registration | New sub-managers might be incorrectly registered as `single{}` in AppModule | Keep manual construction in MainViewModel, matching existing pattern |
| Compose UI layer | Changing StateFlow source paths could cause recomposition regressions | Maintain identical public API signatures through delegation |

## Sources

- Direct codebase analysis of `DefaultWorkoutSessionManager.kt` (4,024 lines)
- Direct codebase analysis of `MainViewModel.kt` (420 lines, delegation pattern)
- Direct codebase analysis of `AppModule.kt` (Koin DI registration)
- Direct codebase analysis of `BleConnectionManager.kt` (existing extraction pattern with `WorkoutStateProvider` interface)
- Direct codebase analysis of `BleConnectionManagerTest.kt` (existing test patterns)
- Direct codebase analysis of `TestCoroutineRule.kt` (androidUnitTest-only infrastructure)
- KMP coroutine testing documentation (kotlinx-coroutines-test)
- Compose recomposition behavior (Jetpack Compose stability documentation)
- Koin dependency injection resolution semantics (circular dependency behavior)

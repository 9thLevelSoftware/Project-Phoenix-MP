# Feature Landscape: Architectural Cleanup v0.4.1

**Domain:** KMP app refactoring -- decomposing monolithic managers and UI screens
**Researched:** 2026-02-12

---

## Table Stakes

Features that MUST be present for the refactoring to be considered successful. Missing any of these means the refactoring is incomplete or unsafe.

### Manager Decomposition

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **DefaultWorkoutSessionManager split into sub-managers** | 4,024 lines with 8 identified responsibility clusters is unmaintainable; any bug fix risks collateral damage | High | The archaeologist analysis already identifies Round 1-8 sections as natural cut points |
| **Shared state coordination between sub-managers** | Sub-managers need to read/write overlapping state (workout state, routine state, exercise index) | High | This is the hardest part -- needs a shared state bus or coordinator pattern |
| **Preserved public API surface** | MainViewModel already delegates ~80 functions to DefaultWorkoutSessionManager; UI must not change | Med | MainViewModel facade pattern is already in place; sub-managers just push delegation one level deeper |
| **Zero behavior regression** | Workout lifecycle, BLE commands, rep counting, auto-stop, superset navigation must all work identically | High | Characterization tests are the gating prerequisite before any extraction |
| **Characterization tests for workout lifecycle** | Lock in existing behavior of startWorkout (325 lines), handleSetCompletion (194 lines), checkAutoStop (212 lines), saveWorkoutSession (168 lines) | High | Safety inspector test plan exists; 9 fake repositories already built; test infrastructure ready |
| **Characterization tests for routine flow** | Lock in loadRoutine, enterSetReady, advanceToNextExercise, superset navigation | High | Routine flow touches ~1,200 lines with heavy cross-cluster coupling to ActiveSession |
| **WorkoutTab.kt composable decomposition** | 2,840 lines with 20+ composables in one file; violates single-responsibility at the file level | Med | Many composables are already well-bounded (ConnectionCard, RepCounterCard, LiveMetricsCard); extraction is mechanical |
| **HistoryAndSettingsTabs.kt composable decomposition** | 2,750 lines mixing HistoryTab (history cards, grouped routine cards) with SettingsTab (1,540 lines of settings UI) | Med | HistoryTab and SettingsTab are already separate composable functions sharing a file; splitting is safe |
| **Koin module reorganization** | Current single `commonModule` has 30+ bindings with no structure; adding sub-managers will make it worse | Low | Koin `includes()` hierarchy is the standard pattern |

### Testing Infrastructure

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **FakeWorkoutSessionManager** | Sub-managers will need a fake of the coordinator/parent for isolated testing | Med | Follow existing pattern from FakeBleRepository, FakeWorkoutRepository |
| **TestFixtures for workout states** | Tests need pre-built WorkoutState.Active, WorkoutState.Resting, RoutineFlowState.SetReady etc. | Low | TestFixtures.kt already provides benchPress, oldSchoolParams; extend it |
| **Coroutine test harness for manager tests** | Managers use CoroutineScope directly (not ViewModel); need TestScope injection | Low | Already using kotlinx.coroutines.test pattern in existing tests |

---

## Differentiators

Features that elevate the refactoring beyond "just works" into "genuinely improved codebase." Not required for v0.4.1 but high value.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Interface-backed sub-managers** | Enables swapping implementations in tests without fakes; cleaner dependency graph | Med | HistoryManager was planned as interface in architect-interface-design.md but shipped as concrete class; inconsistent |
| **WorkoutCoordinator shared state bus** | Centralizes the 15+ MutableStateFlows that sub-managers need to share; prevents spaghetti cross-references | Med | Architect plan proposes this; reduces lateinit/circular dependency smell (see bleConnectionManager wiring) |
| **Compose state holder classes (UiState + Actions)** | WorkoutTab already has WorkoutUiState/WorkoutActions wrapper; extend pattern to HistoryTab, SettingsTab | Low | Reduces parameter sprawl in composable signatures; already proven in WorkoutTab |
| **Extracted dialog composables to separate files** | WorkoutSetupDialog (434 lines), ModeSubSelectorDialog (172 lines), ExercisePickerDialog (149 lines) are embedded in WorkoutTab.kt | Low | Purely mechanical extraction; no behavior change |
| **Snapshot/screenshot tests for decomposed composables** | Catch visual regressions from composable extraction; KMP supports Compose Preview testing | Med | Compose Multiplatform 1.10.0 added unified @Preview; could leverage for regression |
| **Turbine-based flow testing for managers** | Cleaner StateFlow assertion API than manual collect/advanceUntilIdle | Low | Well-established library for Kotlin Flow testing; drop-in addition |
| **Koin verification tests** | Ensure all DI bindings resolve correctly after reorganization | Low | `koin.verify()` in test; catches missing bindings before runtime |

---

## Anti-Features

Features to explicitly NOT build during this refactoring milestone.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| **New ViewModel per feature** | Splitting MainViewModel into WorkoutViewModel, HistoryViewModel, SettingsViewModel would require rewriting all UI navigation and state hoisting; massive scope creep | Keep MainViewModel as thin facade; decompose below it into managers |
| **Decompose/Voyager navigation migration** | Replacing current navigation with a new library is a separate concern from manager decomposition | Stay on current navigation; address in a future milestone if needed |
| **Reactive state management rewrite (MVI/Redux)** | Converting from imperative StateFlow updates to a formal MVI pattern would touch every function in the manager | Keep the existing imperative style; the decomposition improves locality without changing paradigm |
| **Moving composables to separate Gradle modules** | Multi-module builds add build complexity; the app is not large enough to benefit | Keep all composables in `shared/src/commonMain`; use package/file organization instead |
| **Full test coverage of all manager functions** | 60+ public functions in DefaultWorkoutSessionManager; covering all is a months-long effort | Write characterization tests for the critical paths (lifecycle, set completion, auto-stop, routine navigation); defer edge cases |
| **Removing MainViewModel delegation layer** | The delegation in MainViewModel (lines 112-304) is verbose but provides a stable API for UI; removing it requires touching every screen | Keep the delegation layer; it serves as an abstraction boundary even if verbose |
| **Automated migration of WorkoutTab parameter sprawl** | WorkoutTab's second overload has 40+ parameters; tempting to collapse but UI depends on both overloads | Leave both overloads; the state-holder overload (WorkoutUiState) is already the preferred API |

---

## Feature Dependencies

```
Characterization Tests (workout lifecycle) --> Manager Decomposition
  |
  +-- Tests MUST pass before any extraction begins
  |
  +-- FakeWorkoutSessionManager needed for sub-manager tests

Characterization Tests (routine flow) --> Manager Decomposition
  |
  +-- Routine flow is the most coupled cluster; tests prevent breakage

Manager Decomposition --> Koin Module Reorganization
  |
  +-- New sub-managers need DI bindings
  |
  +-- Can't reorganize Koin until managers are extracted

Manager Decomposition --> UI Composable Decomposition
  |
  +-- Sub-managers may expose different StateFlows than current monolith
  |
  +-- UI decomposition is safe to do in parallel IF no API changes

WorkoutTab Decomposition =/= HistoryAndSettingsTabs Decomposition
  |
  +-- These are independent; can be done in parallel

WorkoutCoordinator (differentiator) --> Manager Decomposition
  |
  +-- Coordinator design influences how sub-managers share state
  |
  +-- If doing coordinator, design it BEFORE extracting sub-managers
```

---

## MVP Recommendation

### Phase 1: Characterization Tests (prerequisite, do first)

Prioritize:
1. **Workout lifecycle characterization tests** -- startWorkout, stopWorkout, pauseWorkout, resumeWorkout state transitions
2. **Set completion characterization tests** -- handleSetCompletion including PR detection, gamification, session saving
3. **Auto-stop characterization tests** -- velocity stall detection, position-based detection, AMRAP grace period
4. **Routine navigation characterization tests** -- enterSetReady, advanceToNextExercise, superset traversal

Rationale: The safety inspector test plan (`.planning/refactoring/safety-inspector-test-plan.md`) already provides exact test specifications. All 9 fake repositories exist. This is the lowest-risk, highest-value starting point.

### Phase 2: DefaultWorkoutSessionManager Decomposition

Extract in this order based on coupling analysis:

1. **RoutineCrudManager** (~200 lines) -- saveRoutine, updateRoutine, deleteRoutine, deleteRoutines, loadRoutines. Zero coupling to active workout state. Purely CRUD.
2. **WeightAdjustmentManager** (~110 lines) -- adjustWeight, incrementWeight, decrementWeight, setWeightPreset, getLastWeightForExercise, getPrWeightForExercise. Reads workout parameters but does not manage state transitions.
3. **JustLiftManager** (~200 lines) -- prepareForJustLift, getJustLiftDefaults, saveJustLiftDefaults, getSingleExerciseDefaults, saveSingleExerciseDefaults, handle detection. Self-contained with preferences dependency.
4. **SupersetCrudManager** (~100 lines) -- createSuperset, updateSuperset, deleteSuperset, addExerciseToSuperset, removeExerciseFromSuperset. Modifies routine structure but no workout state.
5. **TrainingCycleManager** (~70 lines) -- loadRoutineFromCycle, clearCycleContext, cycle day completion event. Small and isolated.
6. **RoutineNavigationManager** (~500 lines) -- The hard one. enterSetReady, advanceToNextExercise, superset navigation, set-ready state machine. Tightly coupled to workout state. Extract LAST.
7. **WorkoutLifecycleManager** (~1,500+ lines) -- startWorkout, stopWorkout, auto-stop, rep counting, metrics collection. The core. May stay as the "trunk" that the others are extracted from rather than being extracted itself.

### Phase 3: UI Composable Decomposition

1. **Split HistoryAndSettingsTabs.kt** into `HistoryTab.kt` and `SettingsTab.kt` -- they are already separate composable functions in one file
2. **Extract WorkoutTab dialogs** -- WorkoutSetupDialog, ModeSubSelectorDialog, ExercisePickerDialog to `presentation/screen/dialogs/`
3. **Extract WorkoutTab cards** -- ConnectionCard, RepCounterCard, LiveMetricsCard, CurrentExerciseCard, SetSummaryCard to `presentation/screen/workout/` or `presentation/components/workout/`
4. **Extract HistoryTab cards** -- WorkoutHistoryCard, GroupedRoutineCard, WorkoutSessionCard to `presentation/screen/history/`
5. **Extract SettingsTab sections** -- SettingsTab is 1,540 lines; break into SettingsGeneralSection, SettingsAppearanceSection, SettingsAdvancedSection, SettingsAboutSection

### Phase 4: Koin Cleanup

1. Split `commonModule` into: `repositoryModule`, `useCaseModule`, `managerModule`, `viewModelModule`
2. Use Koin `includes()` to compose them
3. Add `koin.verify()` test to catch binding errors

Defer: Interface-backed sub-managers, WorkoutCoordinator shared state bus, snapshot tests. These are v0.5.0 concerns.

---

## Complexity Budget

| Feature | Estimated Effort | Risk |
|---------|-----------------|------|
| Characterization tests (all 4 areas) | 3-5 sessions | Low risk, high value |
| RoutineCrudManager extraction | 1 session | Very low risk |
| WeightAdjustmentManager extraction | 1 session | Low risk |
| JustLiftManager extraction | 1 session | Low risk |
| SupersetCrudManager extraction | 1 session | Low risk |
| TrainingCycleManager extraction | 0.5 session | Very low risk |
| RoutineNavigationManager extraction | 2-3 sessions | Medium risk (coupled state) |
| WorkoutLifecycleManager cleanup | 2-3 sessions | Medium risk (core logic) |
| HistoryAndSettingsTabs split | 1 session | Very low risk |
| WorkoutTab dialog extraction | 1 session | Very low risk |
| WorkoutTab card extraction | 1-2 sessions | Low risk |
| SettingsTab section extraction | 1 session | Low risk |
| Koin module reorganization | 1 session | Low risk |

**Total: ~16-22 sessions** (with characterization tests front-loaded)

---

## Sources

- Codebase analysis of DefaultWorkoutSessionManager.kt (4,024 lines), WorkoutTab.kt (2,840 lines), HistoryAndSettingsTabs.kt (2,750 lines)
- Existing refactoring analysis: `.planning/refactoring/archaeologist-context-map.md`, `architect-interface-design.md`, `surgeon-extraction-plan.md`, `safety-inspector-test-plan.md`, `ui-decoupler-analysis.md`
- [Koin Best Practices](https://insert-koin.io/docs/reference/koin-android/best-practices/) - Module organization with includes()
- [Koin KMP Advanced Patterns](https://insert-koin.io/docs/reference/koin-mp/kmp/) - Multiplatform module structure
- [Characterization Tests (Wikipedia)](https://en.wikipedia.org/wiki/Characterization_test) - Lock-in-behavior-before-refactoring pattern
- [Compose Multiplatform 1.10.0](https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/) - Unified @Preview for potential snapshot testing
- [Understanding Characterization vs Approval Tests](https://understandlegacycode.com/blog/characterization-tests-or-approval-tests/) - When to use golden master vs assertion-based characterization

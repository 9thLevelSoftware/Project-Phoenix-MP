# Roadmap: Project Phoenix MP v0.4.1 — Architectural Cleanup

## Overview

This milestone completes the architectural decomposition started in v0.4.0 by breaking the remaining monoliths: DefaultWorkoutSessionManager (4,024 lines) splits into focused sub-managers coordinated by a shared state bus, oversized UI composable files get mechanically split into focused files, and Koin DI wiring gets reorganized into feature-scoped modules. Characterization tests come first to lock in existing behavior before any extraction begins. BLE (KableBleRepository) is explicitly out of scope.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Characterization Tests** - Lock in current workout and routine behavior before any extraction
- [x] **Phase 2: Manager Decomposition** - Split DefaultWorkoutSessionManager into WorkoutCoordinator + RoutineFlowManager + ActiveSessionEngine
- [x] **Phase 3: UI Composable Decomposition** - Split oversized composable files into focused, single-responsibility files
- [x] **Phase 4: Koin DI Cleanup** - Reorganize single commonModule into feature-scoped Koin modules

## Phase Details

### Phase 1: Characterization Tests
**Goal**: Developers can safely refactor DefaultWorkoutSessionManager knowing that any behavior regression will be caught by tests
**Depends on**: Nothing (first phase)
**Requirements**: TEST-01, TEST-02, TEST-03, TEST-04
**Success Criteria** (what must be TRUE):
  1. Running `./gradlew :shared:testDebugUnitTest` (or commonTest equivalent) executes characterization tests that verify workout lifecycle transitions (start, stop, set completion, auto-stop, save session)
  2. Running the same test suite verifies routine flow behavior (load routine, enter set-ready, advance exercise, superset navigation)
  3. Test fixtures exist that construct pre-built workout states (Active, Resting, SetReady, etc.) in one line, usable by any future test
  4. All characterization tests run in commonTest with no platform-specific dependencies (pure KMP)
  5. All tests pass against the current unmodified DefaultWorkoutSessionManager
**Plans**: 2 plans

Plans:
- [x] 01-01-PLAN.md -- DWSMTestHarness, WorkoutStateFixtures, and workout lifecycle characterization tests
- [x] 01-02-PLAN.md -- Routine flow characterization tests

### Phase 2: Manager Decomposition
**Goal**: DefaultWorkoutSessionManager is decomposed into focused sub-managers while preserving identical behavior and the same public API surface
**Depends on**: Phase 1
**Requirements**: MGR-01, MGR-02, MGR-03, MGR-04, MGR-05, MGR-06, MGR-07
**Success Criteria** (what must be TRUE):
  1. WorkoutCoordinator exists as a shared state bus holding all MutableStateFlows and guard flags, with zero business logic methods
  2. RoutineFlowManager handles all routine CRUD, exercise/set navigation, and superset navigation in its own file (~1,200 lines extracted from DWSM)
  3. ActiveSessionEngine handles workout start/stop, rep processing, auto-stop, weight adjustment, Just Lift, and training cycles in its own file (~1,800 lines extracted from DWSM)
  4. DefaultWorkoutSessionManager is reduced to an ~800-line orchestration layer that delegates to sub-managers
  5. The circular dependency between BleConnectionManager and WorkoutSessionManager is eliminated (no more lateinit var) via SharedFlow event pattern
  6. All Phase 1 characterization tests still pass after each extraction step (zero behavior regression)
  7. MainViewModel public API is unchanged -- no UI screen modifications required
**Plans**: 4 plans, 4 waves (sequential — each builds on previous)

Plans:
- [x] 02-01-PLAN.md -- WorkoutCoordinator extraction (shared state bus)
- [x] 02-02-PLAN.md -- Circular dependency resolution (SharedFlow event pattern)
- [x] 02-03-PLAN.md -- RoutineFlowManager extraction
- [x] 02-04-PLAN.md -- ActiveSessionEngine extraction and DWSM reduction

### Phase 3: UI Composable Decomposition
**Goal**: Oversized composable files are split into focused, navigable files without any visual or behavioral change
**Depends on**: Phase 2 (safer after manager API is stable, though technically independent)
**Requirements**: UI-01, UI-02, UI-03, UI-04
**Success Criteria** (what must be TRUE):
  1. HistoryAndSettingsTabs.kt (2,750 lines) no longer exists -- replaced by separate HistoryTab.kt and SettingsTab.kt files
  2. WorkoutTab.kt (2,840 lines) is decomposed into focused files for cards, dialogs, and core screen logic
  3. Dialog composables (WorkoutSetupDialog, ModeSubSelectorDialog, ExercisePickerDialog) each live in their own file
  4. All composables render identically after extraction -- no visual regression (verified via manual testing or @Preview comparison)
**Plans**: 2 plans, 2 waves (sequential -- both modify WorkoutTab.kt)

Plans:
- [x] 03-01-PLAN.md -- Extract SetSummaryCard, split HistoryAndSettingsTabs into HistoryTab + SettingsTab
- [x] 03-02-PLAN.md -- Extract WorkoutSetupDialog and ModeSubSelectorDialog from WorkoutTab

### Phase 4: Koin DI Cleanup
**Goal**: Koin dependency injection is organized into feature-scoped modules that are verified by automated tests
**Depends on**: Phase 2 (must know final manager structure before reorganizing DI)
**Requirements**: DI-01, DI-02, DI-03
**Success Criteria** (what must be TRUE):
  1. The single commonModule (30+ bindings) is replaced by feature-scoped modules (dataModule, syncModule, domainModule, presentationModule) composed via includes()
  2. A Koin verify() test exists and passes, confirming all DI bindings resolve correctly
  3. The app starts and runs normally on Android after the Koin reorganization (no missing binding crashes)
**Plans**: 2 plans, 2 waves (sequential -- verify test depends on module split)

Plans:
- [x] 04-01-PLAN.md -- Split commonModule into feature-scoped modules (dataModule, syncModule, domainModule, presentationModule) with appModule composition
- [x] 04-02-PLAN.md -- Add Koin Module.verify() test and delete dead androidApp AppModule.kt

## Progress

**Execution Order:**
Phases execute in numeric order: 1 --> 2 --> 3 --> 4

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Characterization Tests | 2/2 | ✓ Complete | 2026-02-13 |
| 2. Manager Decomposition | 4/4 | ✓ Complete | 2026-02-13 |
| 3. UI Composable Decomposition | 2/2 | ✓ Complete | 2026-02-13 |
| 4. Koin DI Cleanup | 2/2 | ✓ Complete | 2026-02-13 |

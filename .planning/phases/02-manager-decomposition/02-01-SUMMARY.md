---
phase: 02-manager-decomposition
plan: 01
subsystem: presentation
tags: [kotlin, stateflow, state-management, refactoring, coordinator-pattern]

# Dependency graph
requires:
  - phase: 01-characterization-tests
    provides: "38 characterization tests covering DWSM workout lifecycle and routine flow"
provides:
  - "WorkoutCoordinator class as shared state bus for all workout state"
  - "DWSM delegates all state to coordinator property"
  - "Foundation for RoutineFlowManager and ActiveSessionEngine extraction"
affects: [02-02, 02-03, 02-04]

# Tech tracking
tech-stack:
  added: []
  patterns: [coordinator-pattern, internal-visibility-for-sub-managers]

key-files:
  created:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt"
  modified:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt"
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/WorkoutStateFixtures.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt"
    - "shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt"

key-decisions:
  - "Removed delegation properties from DWSM to avoid Kotlin overload resolution ambiguity; callers access state via coordinator directly"
  - "MainViewModel delegates state reads through workoutSessionManager.coordinator.* instead of workoutSessionManager.*"
  - "All coordinator fields use internal visibility for future sub-manager access"

patterns-established:
  - "Coordinator pattern: WorkoutCoordinator holds all mutable state, sub-managers read/write via internal fields"
  - "Public API for state: ViewModel reads through coordinator's public StateFlow getters"
  - "Test access: dwsm.coordinator.* for state assertions in tests"

# Metrics
duration: 11min
completed: 2026-02-13
---

# Phase 2 Plan 01: Extract WorkoutCoordinator Summary

**WorkoutCoordinator extracted as zero-method shared state bus holding 23 MutableStateFlows, 25+ mutable vars, 6 Job references, and 8 companion constants from DefaultWorkoutSessionManager**

## Performance

- **Duration:** 11 min
- **Started:** 2026-02-13T20:31:29Z
- **Completed:** 2026-02-13T20:42:38Z
- **Tasks:** 1
- **Files modified:** 8

## Accomplishments
- Created WorkoutCoordinator.kt (~250 lines) with all state fields and zero business logic methods
- DWSM now accesses all state through `coordinator.` prefix -- no local MutableStateFlow declarations remain
- All 358 tests pass (38 Phase 1 characterization tests + 320 existing tests)
- MainViewModel and all test files updated to access state through coordinator

## Task Commits

Each task was committed atomically:

1. **Task 1: Create WorkoutCoordinator and migrate all state fields from DWSM** - `2f65d570` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt` - New shared state bus class with all MutableStateFlows, guard flags, Job references, mutable vars, and companion constants
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt` - State declarations removed, all references updated to use coordinator
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` - State delegation updated to read through coordinator
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt` - Added coordinator convenience accessor
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/WorkoutStateFixtures.kt` - Updated state access to coordinator
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt` - Updated state assertions to coordinator
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt` - Updated state assertions to coordinator
- `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt` - Fixed reflection-based autoStopStartTime access to target coordinator

## Decisions Made
- **No delegation properties on DWSM**: Kotlin's overload resolution creates ambiguity when DWSM declares `val workoutState` that delegates to `coordinator.workoutState`. Resolved by removing DWSM's delegation properties entirely and having callers access `coordinator.*` directly.
- **MainViewModel updated**: Rather than keeping a compatibility layer in DWSM, updated MainViewModel to read through `workoutSessionManager.coordinator.*`. This is cleaner and avoids naming conflicts.
- **Internal visibility for coordinator fields**: All mutable fields use `internal` visibility so future sub-managers (RoutineFlowManager, ActiveSessionEngine) can read/write directly.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Kotlin overload resolution ambiguity with delegation properties**
- **Found during:** Task 1 (updating DWSM to use coordinator)
- **Issue:** Declaring `val hapticEvents get() = coordinator.hapticEvents` in DWSM caused "Overload resolution ambiguity" because Kotlin couldn't resolve `coordinator.hapticEvents` when DWSM also had a `hapticEvents` property
- **Fix:** Removed delegation properties from DWSM; updated MainViewModel and test files to access state via `workoutSessionManager.coordinator.*` directly
- **Files modified:** DefaultWorkoutSessionManager.kt, MainViewModel.kt, all test files
- **Verification:** Compilation succeeds, all 358 tests pass
- **Committed in:** 2f65d570

**2. [Rule 1 - Bug] MainViewModelTest reflection accessing moved field**
- **Found during:** Task 1 (running tests after extraction)
- **Issue:** `MainViewModelTest.forceAutoStopTimerElapsed()` used Java reflection to access `autoStopStartTime` field on DWSM, but field moved to WorkoutCoordinator
- **Fix:** Updated reflection to target `viewModel.workoutSessionManager.coordinator` instead of `viewModel.workoutSessionManager`
- **Files modified:** MainViewModelTest.kt
- **Verification:** Test passes
- **Committed in:** 2f65d570

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both fixes were necessary for compilation and test correctness. No scope creep. The plan acknowledged incremental approach but the Kotlin naming conflict required a different delegation strategy.

## Issues Encountered
- Python regex-based bulk replacement of field references was unreliable due to lookbehind limitations. Used a more targeted approach with explicit patterns that correctly handled the `coordinator.` prefixing.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- WorkoutCoordinator is the foundation that Plans 02-04 depend on
- RoutineFlowManager (Plan 02) and ActiveSessionEngine (Plan 03) can now read/write state through coordinator's internal fields
- All 38 characterization tests continue to pass as the safety net for subsequent extractions

---
*Phase: 02-manager-decomposition*
*Completed: 2026-02-13*

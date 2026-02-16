---
phase: 02-manager-decomposition
plan: 03
subsystem: presentation
tags: [kotlin, refactoring, manager-decomposition, coordinator-pattern, delegation]

# Dependency graph
requires:
  - phase: 02-manager-decomposition
    plan: 01
    provides: "WorkoutCoordinator as shared state bus with internal fields for sub-manager access"
  - phase: 01-characterization-tests
    provides: "38 characterization tests covering DWSM workout lifecycle and routine flow"
provides:
  - "RoutineFlowManager class handling all routine CRUD, navigation, and superset logic"
  - "DWSM routine methods reduced to thin delegation stubs"
  - "WorkoutLifecycleDelegate interface for bridging BLE/workout calls from sub-manager to DWSM"
  - "Top-level isBodyweightExercise() and isSingleExerciseMode() shared helper functions"
affects: [02-04]

# Tech tracking
tech-stack:
  added: []
  patterns: [lifecycle-delegate-pattern, top-level-shared-helpers, sub-manager-extraction]

key-files:
  created:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt"
  modified:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt"
    - "shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt"

key-decisions:
  - "WorkoutLifecycleDelegate interface used to bridge BLE/startWorkout calls from RoutineFlowManager back to DWSM without direct reference"
  - "isBodyweightExercise() and isSingleExerciseMode() promoted to top-level package functions for shared access"
  - "SettingsManager added to RoutineFlowManager constructor for stopAtTop preference in loadRoutineInternal"
  - "Superset navigation helpers (isInSuperset, isAtEndOfSupersetCycle, getSupersetRestSeconds) made internal for DWSM workout lifecycle access"

patterns-established:
  - "Lifecycle delegate: sub-managers bridge back to DWSM for BLE/workout operations via interface, avoiding circular references"
  - "Top-level helpers: shared functions used by multiple managers live at package level, not in any single class"
  - "Internal visibility for cross-manager: navigation helpers on RoutineFlowManager are internal so DWSM can call them for workout flow decisions"

# Metrics
duration: 12min
completed: 2026-02-13
---

# Phase 2 Plan 03: Extract RoutineFlowManager Summary

**RoutineFlowManager extracted from DWSM with ~1,091 lines handling routine CRUD, exercise/set navigation, superset logic, and init block collectors, using WorkoutLifecycleDelegate for BLE bridging**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-13T20:51:52Z
- **Completed:** 2026-02-13T21:04:09Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Created RoutineFlowManager.kt (1,091 lines) with all routine CRUD, navigation, superset CRUD, and related init block collectors
- DWSM reduced from ~3,811 to ~2,871 lines (25% reduction) with routine methods as thin delegation stubs
- WorkoutLifecycleDelegate interface cleanly bridges BLE/startWorkout operations without circular references
- All 38 characterization tests pass without modification (test harness unchanged beyond convenience accessor)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create RoutineFlowManager and extract routine methods from DWSM** - `c7a30c82` (feat)
2. **Task 2: Update DWSMTestHarness for RoutineFlowManager construction** - `333b1a02` (test)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt` - New class with routine CRUD, exercise/set navigation, superset navigation, init block collectors #1-2, WorkoutLifecycleDelegate interface
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt` - Routine methods replaced with delegation stubs, init block collectors removed, internal callers updated to use routineFlowManager
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt` - Added routineFlowManager convenience accessor

## Decisions Made
- **WorkoutLifecycleDelegate interface**: Used to bridge BLE/workout operations (sendStopCommand, stopMachineWorkout, startWorkout, resetRepCounter, updateWorkoutParameters) from RoutineFlowManager back to DWSM. This avoids RoutineFlowManager holding a direct reference to DWSM while keeping BLE command logic co-located with workout lifecycle in DWSM.
- **Top-level shared helpers**: `isBodyweightExercise()` and `isSingleExerciseMode()` promoted from private DWSM methods to package-level internal functions. Both RoutineFlowManager and DWSM call these.
- **Internal visibility for navigation helpers**: `getNextStep`, `getPreviousStep`, `calculateNextExerciseName`, `calculateIsLastExercise`, `isInSuperset`, `isAtEndOfSupersetCycle`, `getSupersetRestSeconds` are internal on RoutineFlowManager so DWSM's workout lifecycle methods (handleSetCompletion, startRestTimer, etc.) can call them.
- **SettingsManager added to RoutineFlowManager**: Required for `loadRoutineInternal` to read `settingsManager.stopAtTop.value` (matching original DWSM behavior). Without this, stale stopAtTop from previous workout parameters would be used.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] SettingsManager dependency for stopAtTop preference**
- **Found during:** Task 1 (extracting loadRoutineInternal)
- **Issue:** Original loadRoutineInternal reads `settingsManager.stopAtTop.value` to set workout parameters. Without settingsManager, RoutineFlowManager would use stale values from coordinator._workoutParameters.
- **Fix:** Added settingsManager to RoutineFlowManager constructor parameters
- **Files modified:** RoutineFlowManager.kt, DefaultWorkoutSessionManager.kt
- **Verification:** Compilation succeeds, all 38 tests pass
- **Committed in:** c7a30c82

**2. [Rule 3 - Blocking] Superset navigation helpers visibility for DWSM callers**
- **Found during:** Task 1 (updating DWSM internal callers)
- **Issue:** DWSM's workout lifecycle methods (handleSetCompletion, startRestTimer, startNextSetOrExercise) call superset helpers (isInSuperset, isAtEndOfSupersetCycle, getSupersetRestSeconds) and navigation helpers (getNextStep, calculateIsLastExercise, calculateNextExerciseName). These were private in the plan but need to be accessible from DWSM.
- **Fix:** Changed visibility from private to internal on these methods in RoutineFlowManager
- **Files modified:** RoutineFlowManager.kt
- **Verification:** Compilation succeeds, all 38 tests pass
- **Committed in:** c7a30c82

---

**Total deviations:** 2 auto-fixed (1 missing critical, 1 blocking)
**Impact on plan:** Both fixes were necessary for correct behavior and compilation. No scope creep. The plan acknowledged "Claude's discretion" for exact mechanism choices.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- RoutineFlowManager extraction complete, DWSM reduced by ~940 lines
- ActiveSessionEngine extraction (Plan 04) can proceed: DWSM's remaining ~2,871 lines are primarily workout lifecycle, BLE, and session management
- WorkoutLifecycleDelegate pattern established and can be reused or evolved for Plan 04
- All 38 characterization tests continue passing as the safety net

---
*Phase: 02-manager-decomposition*
*Completed: 2026-02-13*

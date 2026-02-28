---
phase: 22-ghost-racing
plan: 02
subsystem: presentation
tags: [ghost-racing, vbt, velocity, workout-lifecycle, stateflow]

# Dependency graph
requires:
  - phase: 22-ghost-racing
    provides: "GhostRacingEngine stateless computation, GhostModels domain types, selectBestGhostSession SQL query"
  - phase: 13-biomechanics
    provides: "BiomechanicsRepository.getRepBiomechanics() for ghost rep velocity data"
provides:
  - "WorkoutCoordinator ghost state fields (_ghostSession, _latestGhostVerdict, ghostRepComparisons)"
  - "ActiveSessionEngine ghost lifecycle (pre-load, compare, summarize, reset)"
  - "WorkoutRepository.findBestGhostSession() interface method + SqlDelight implementation"
  - "WorkoutState.SetSummary.ghostSetSummary field for UI consumption"
affects: [22-ghost-racing]

# Tech tracking
tech-stack:
  added: []
  patterns: [ghost-lifecycle-wiring, non-blocking-preload, per-set-ghost-reset]

key-files:
  created: []
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt
    - androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt

key-decisions:
  - "ProgramMode.displayName used for ghost session DB mode matching (sealed class has no .name property)"
  - "Ghost session pre-loaded in separate coroutine scope.launch to avoid blocking workout countdown"
  - "Ghost comparisons reset between sets but ghost session persists across multi-set workout"
  - "50ms delay before ghost comparison allows processBiomechanicsForRep to complete on Default dispatcher"

patterns-established:
  - "Non-blocking pre-load pattern: scope.launch for DB reads during workout initialization (no blocking)"
  - "Ghost state split: session-scoped (_ghostSession) vs set-scoped (ghostRepComparisons, _latestGhostVerdict)"

requirements-completed: [GHOST-01, GHOST-03]

# Metrics
duration: 8min
completed: 2026-02-28
---

# Phase 22 Plan 02: Ghost Racing Workout Wiring Summary

**Ghost session pre-loading, real-time per-rep velocity comparison, and set summary with ghost delta wired into ActiveSessionEngine/WorkoutCoordinator state bus**

## Performance

- **Duration:** 8 min
- **Started:** 2026-02-28T19:19:13Z
- **Completed:** 2026-02-28T19:28:12Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- WorkoutCoordinator ghost state fields (_ghostSession, _latestGhostVerdict, ghostRepComparisons) with public StateFlow getters for UI
- findBestGhostSession() wired from WorkoutRepository interface through SqlDelightWorkoutRepository to selectBestGhostSession SQL query
- ActiveSessionEngine pre-loads ghost session in non-blocking coroutine at workout start, compares each rep against ghost velocity, computes GhostSetSummary at set completion
- WorkoutState.SetSummary.ghostSetSummary field available for Plan 03 UI overlay rendering

## Task Commits

Each task was committed atomically:

1. **Task 1: WorkoutCoordinator ghost state fields + WorkoutRepository.findBestGhostSession** - `ec0abf06` (feat)
2. **Task 2: ActiveSessionEngine ghost lifecycle (pre-load, compare, summarize, reset)** - `52e6d800` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt` - Added _ghostSession, _latestGhostVerdict StateFlows and ghostRepComparisons accumulator
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt` - Ghost pre-loading in startWorkout, rep comparison after biomechanics, set summary computation, reset lifecycle
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt` - Added findBestGhostSession() interface method
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` - Implemented findBestGhostSession() using selectBestGhostSession SQL query with correct SQLDelight parameter mapping
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt` - Added ghostSetSummary field to WorkoutState.SetSummary
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt` - Added stub findBestGhostSession() returning null
- `androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt` - Added stub findBestGhostSession() returning null

## Decisions Made
- Used `ProgramMode.displayName` for ghost session DB mode matching since ProgramMode is a sealed class (no `.name` property) and the mode column stores display names like "Old School", "Echo"
- Ghost session pre-loaded in separate `scope.launch` coroutine to avoid blocking countdown/BLE command sequence (graceful degradation if loading is slow)
- Ghost comparisons (ghostRepComparisons, _latestGhostVerdict) reset between sets but _ghostSession persists across multi-set workout for consistent ghost overlay
- 50ms delay before ghost comparison allows processBiomechanicsForRep to complete on Default dispatcher before reading latestRepResult
- SQLDelight generated parameter name `value_` for the 4th positional parameter (weight tolerance) in selectBestGhostSession query

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed ProgramMode.name to ProgramMode.displayName**
- **Found during:** Task 2 (ghost pre-loading in startWorkout)
- **Issue:** Plan used `params.programMode.name` but ProgramMode is a sealed class without a `.name` property, causing compile error
- **Fix:** Changed to `params.programMode.displayName` which matches how mode is stored in WorkoutSession DB records
- **Files modified:** ActiveSessionEngine.kt
- **Verification:** Build succeeds, mode string matches DB storage format
- **Committed in:** 52e6d800 (Task 2 commit)

**2. [Rule 1 - Bug] Fixed SQLDelight parameter names for selectBestGhostSession**
- **Found during:** Task 1 (SqlDelightWorkoutRepository implementation)
- **Issue:** Plan assumed parameter names `weightPerCableKg_` and `weightToleranceKg` but SQLDelight generates `weightPerCableKg` and `value_` for positional `?` parameters
- **Fix:** Checked generated code, used correct parameter names: `weightPerCableKg` and `value_`
- **Files modified:** SqlDelightWorkoutRepository.kt
- **Verification:** Build succeeds, query maps correctly
- **Committed in:** ec0abf06 (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 bugs)
**Impact on plan:** Both auto-fixes necessary for correct compilation. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Ghost state flows (ghostSession, latestGhostVerdict) publicly readable for Plan 03 UI overlay
- GhostSetSummary available on WorkoutState.SetSummary for set summary card rendering
- All domain types, engine functions, repository methods, and state bus wiring complete
- Plan 03 can build UI overlay consuming ghostSession/latestGhostVerdict StateFlows and ghostSetSummary in SetSummary

## Self-Check: PASSED

All 7 files verified present. Both task commits (ec0abf06, 52e6d800) verified in git log.

---
*Phase: 22-ghost-racing*
*Completed: 2026-02-28*

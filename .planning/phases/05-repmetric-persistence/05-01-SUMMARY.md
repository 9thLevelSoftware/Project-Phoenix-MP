---
phase: 05-repmetric-persistence
plan: 01
subsystem: database
tags: [repmetric, persistence, workout-flow, gap-closure, ble]

# Dependency graph
requires:
  - phase: 01-data-foundation
    provides: "RepMetric table schema, RepMetricRepository, RepMetricData domain model"
provides:
  - "RepMetricRepository wired into ActiveSessionEngine for per-rep persistence"
  - "Per-rep force curve data persisted to RepMetric table at set completion"
  - "FakeRepMetricRepository for test use"
affects: [analytics, force-curve-visualization, training-insights]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Rep metric accumulation via coordinator.setRepMetrics list"
    - "Persist-then-clear pattern at set completion boundary"

key-files:
  created:
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeRepMetricRepository.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/RepMetricPersistenceTest.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt

key-decisions:
  - "RepMetricData accumulated in WorkoutCoordinator.setRepMetrics (internal mutable list) during scoreCurrentRep"
  - "Persistence happens after saveWorkoutSession in handleSetCompletion, with try/catch to prevent data loss from crashing workout flow"
  - "No subscription tier gating on persistence path (GATE-04 compliance)"

patterns-established:
  - "Accumulate-then-persist: collect data in coordinator list during active workout, persist in batch at set boundary"

# Metrics
duration: 6min
completed: 2026-02-14
---

# Phase 5 Plan 1: RepMetric Persistence Wiring Summary

**RepMetricRepository integrated into ActiveSessionEngine to persist per-rep force curve data at set completion via coordinator accumulation list**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-14T23:09:06Z
- **Completed:** 2026-02-14T23:15:00Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- Closed DATA-01 gap: per-rep metric data now persists to RepMetric table during workouts
- RepMetricRepository injected through full DI chain: Koin -> MainViewModel -> DWSM -> ActiveSessionEngine
- RepMetricData accumulated per-rep in scoreCurrentRep() and batch-persisted at set completion
- 3 new persistence tests verify correct session association, empty-list handling, and count accuracy

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire RepMetricRepository and persist rep metrics at set completion** - `32849527` (feat)
2. **Task 2: Add persistence test and update test harness** - `6b0e7553` (test)

## Files Created/Modified
- `shared/.../manager/WorkoutCoordinator.kt` - Added setRepMetrics accumulation list
- `shared/.../manager/ActiveSessionEngine.kt` - Added RepMetricRepository param, accumulation in scoreCurrentRep, persistence in handleSetCompletion, clear in resetForNewWorkout
- `shared/.../manager/DefaultWorkoutSessionManager.kt` - Pass-through RepMetricRepository to ActiveSessionEngine
- `shared/.../viewmodel/MainViewModel.kt` - Added RepMetricRepository constructor parameter
- `shared/.../di/PresentationModule.kt` - Added get() for RepMetricRepository in MainViewModel factory
- `shared/.../testutil/FakeRepMetricRepository.kt` - In-memory fake for test use
- `shared/.../testutil/DWSMTestHarness.kt` - Wired fakeRepMetricRepo into DWSM construction
- `shared/.../manager/RepMetricPersistenceTest.kt` - 3 tests verifying persistence behavior

## Decisions Made
- Accumulated rep metrics in coordinator list (not a separate flow) for simplicity and consistency with collectedMetrics pattern
- Persistence wrapped in try/catch to prevent rep metric save failures from crashing the workout flow
- No tier gating on persistence (GATE-04: data captured for all users, gating at UI layer only)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- DATA-01 gap fully closed
- RepMetric table now receives data during real workouts
- Force curve visualization and training insights can now query persisted per-rep data

## Self-Check: PASSED

- All 8 files verified present on disk
- Commit `32849527` verified in git log
- Commit `6b0e7553` verified in git log
- Android build: BUILD SUCCESSFUL
- Unit tests: BUILD SUCCESSFUL (all tests pass)

---
*Phase: 05-repmetric-persistence*
*Completed: 2026-02-14*

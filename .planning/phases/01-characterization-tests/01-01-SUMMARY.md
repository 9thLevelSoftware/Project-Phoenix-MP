# Plan 01-01 Summary: DWSMTestHarness, WorkoutStateFixtures, Workout Lifecycle Tests

## Result: COMPLETE

**Duration:** Created by GSD executor, fixed manually in follow-up session
**Tests:** 16 passing in DWSMWorkoutLifecycleTest

## What Was Built

### DWSMTestHarness.kt
- Constructs DWSM with all 13+1 dependencies using fakes inside a TestScope
- Uses child scope pattern: `dwsmJob = Job(testScope.coroutineContext[Job])` so `advanceUntilIdle()` controls virtual time while `cleanup()` cancels DWSM coroutines independently
- `cleanup()` cancels `dwsmJob` to prevent UncompletedCoroutinesError

### WorkoutStateFixtures.kt
- `activeDWSM()` — one-liner to get DWSM into Active state (connects BLE, starts workout, skips countdown)
- `setReadyDWSM()` — one-liner to get DWSM into SetReady state (loads routine, enters set ready)
- `createTestRoutine()` — configurable factory (exerciseCount, setsPerExercise, weightKg, repsPerSet)
- `createSupersetRoutine()` — factory with 2 superset exercises + 1 standalone

### DWSMWorkoutLifecycleTest.kt (16 tests)
- **A. startWorkout transitions (4):** Initializing state, Active after skip, countdown 5-4-3-2-1, BLE command sent
- **B. stopWorkout transitions (4):** exitingWorkout=true→Idle, false→SetSummary, guard flag, BLE stop for cable
- **C. resetForNewWorkout (2):** Clears rep count, clears rep ranges
- **D. updateWorkoutParameters (2):** Updates flow, safe during Idle
- **E. Auto-stop behavior (2):** Default values, reset after startWorkout
- **F. saveWorkoutSession (2):** Saves session, saves even with exitingWorkout=true

## Key Discoveries

1. **DWSM init block infinite re-dispatch:** The init block has 6+ `scope.launch` collectors. If `advanceUntilIdle()` processes both init block and `loadRoutine` coroutines simultaneously, they create a feedback loop that hangs forever. Fix: `advanceUntilIdle()` after harness construction, BEFORE `loadRoutine()`.

2. **cleanup() is mandatory:** DWSM's init block collectors never complete. Without `harness.cleanup()`, `runTest` waits forever for them → UncompletedCoroutinesError.

## Artifacts

| File | Lines | Tests |
|------|-------|-------|
| `shared/src/commonTest/.../testutil/DWSMTestHarness.kt` | ~80 | - |
| `shared/src/commonTest/.../testutil/WorkoutStateFixtures.kt` | ~89 | - |
| `shared/src/commonTest/.../manager/DWSMWorkoutLifecycleTest.kt` | ~302 | 16 |

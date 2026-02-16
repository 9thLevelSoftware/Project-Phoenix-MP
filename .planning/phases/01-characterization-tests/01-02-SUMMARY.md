# Plan 01-02 Summary: Routine Flow Characterization Tests

## Result: COMPLETE

**Duration:** Created by GSD executor (with hang bug), fixed manually across 2 sessions
**Tests:** 22 passing in DWSMRoutineFlowTest

## What Was Built

### DWSMRoutineFlowTest.kt (22 tests)
- **A. loadRoutine (4):** Sets first exercise params, async (not immediate), resets to Idle, does NOT set routineFlowState
- **B. enterSetReady (4):** Updates routineFlowState, correct weight/reps, second set increments index, updates workoutParameters
- **C. Navigation (5):** advanceToNextExercise, jumpToExercise, jumpToExercise blocked during Active, skipCurrentExercise, goToPreviousExercise
- **D. Superset navigation (3):** Load superset params, enterSetReady for second exercise, navigate through all 3 exercises
- **E. Overview (4):** enterRoutineOverview, selectExerciseInOverview, out-of-bounds ignored, overview→setReady transition
- **F. Flow transitions (2):** returnToOverview from SetReady, exitRoutineFlow resets to NotInRoutine

## Key Discoveries & Fixes

### 1. Init block settle pattern (showstopper fix)
`advanceUntilIdle()` MUST be called after `DWSMTestHarness(this)` and BEFORE `loadRoutine()`. Without this, init block coroutines and loadRoutine interleave creating an infinite re-dispatch loop. This was the root cause of the 1.5-hour GSD execution hang.

### 2. Navigation methods use `advanceTimeBy(7000)` not `advanceUntilIdle()`
`jumpToExercise()` calls `startWorkout(skipCountdown=false)` internally, which triggers `startActiveWorkoutPolling()` (sets handleState=Grabbed). This re-awakens init block collectors, recreating the infinite re-dispatch. `advanceTimeBy(7000)` processes enough virtual time (BLE 250ms + countdown 5s + START 100ms + margin) without the infinite loop.

### 3. `stopWorkout(exitingWorkout=false)` between navigations
`stopWorkout(exitingWorkout=true)` clears `_loadedRoutine.value = null` (line 3417-3418). Subsequent navigation calls check `_loadedRoutine.value ?: return` and silently bail out. Multi-step navigation tests must use `exitingWorkout=false` to preserve routine context.

### 4. jumpToExercise blocks during Active state (Issue #125)
After navigation auto-starts a workout, further navigation is blocked by the Active state guard. Must stop the workout between navigation calls.

## Characterization Notes
- `loadRoutine` does NOT set `routineFlowState` — only `enterRoutineOverview` does that
- `stopWorkout` guard flag (`stopWorkoutInProgress`) silently ignores second stop call
- `selectExerciseInOverview` with out-of-bounds index is silently ignored
- `exitRoutineFlow` clears loadedRoutine AND resets workoutState to Idle

## Artifacts

| File | Lines | Tests |
|------|-------|-------|
| `shared/src/commonTest/.../manager/DWSMRoutineFlowTest.kt` | ~525 | 22 |

# Safety Inspector: Characterization Test Plan for MainViewModel

## Purpose

These tests serve as **safety nets** during the extraction of workout session logic from `MainViewModel` into a new `WorkoutSessionManager`. They lock in existing behavior so any regressions introduced during refactoring are immediately caught.

The philosophy: **characterize first, refactor second**. Each test documents what the code currently does (not what it should do), creating a contract that the refactored code must honor.

---

## Testing Strategy Overview

### Scope
We focus on the **workout session lifecycle** -- the cluster of state and logic that will move into `WorkoutSessionManager`:
1. **State transitions**: `WorkoutState` flow through Idle, Initializing, Countdown, Active, Paused, SetSummary, Resting, Completed
2. **resetForNewWorkout()**: Correct state reset without losing persistent data
3. **Just Lift auto-reset**: Post-completion returns to Idle (not Completed)
4. **RPE logging**: `logRpeForCurrentSet()` stores and clears RPE correctly
5. **Rep counting integration**: RepCounter state flows through to `_repCount`
6. **Handle detection**: Idempotent enablement with debounce
7. **Set completion guards**: `stopWorkoutInProgress` and `setCompletionInProgress` prevent double-processing
8. **Pause/Resume**: State toggles and collection job management

### Framework
- **kotlin.test**: `@Test`, `@BeforeTest`, `assertEquals`, `assertIs`, `assertTrue`, `assertFalse`
- **kotlinx.coroutines.test**: `runTest`, `StandardTestDispatcher`, `advanceUntilIdle`, `TestScope`
- **No mocking library** -- use existing Fake implementations from `testutil/`
- **KMP commonTest** -- all tests run on JVM, iOS, and JS targets

### Test File Location
```
shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelWorkoutSessionTest.kt
```

---

## Dependencies That Need Faking

### Already Available (in `testutil/`)

| Dependency | Fake | Status |
|---|---|---|
| `BleRepository` | `FakeBleRepository` | Complete -- has `simulateConnect()`, `emitMetric()`, `emitRepNotification()` |
| `WorkoutRepository` | `FakeWorkoutRepository` | Complete -- in-memory sessions, routines, metrics |
| `ExerciseRepository` | `FakeExerciseRepository` | Complete -- in-memory exercises |
| `PersonalRecordRepository` | `FakePersonalRecordRepository` | Complete -- tracks updateCalls |
| `PreferencesManager` | `FakePreferencesManager` | Complete -- in-memory preferences |
| `GamificationRepository` | `FakeGamificationRepository` | Complete -- controllable badges/stats |
| `TrainingCycleRepository` | `FakeTrainingCycleRepository` | Complete -- in-memory cycles |
| `TestFixtures` | N/A | Provides `benchPress`, `oldSchoolParams`, `justLiftParams`, etc. |

### New Fakes Needed

#### 1. `FakeResolveRoutineWeightsUseCase`

```kotlin
class FakeResolveRoutineWeightsUseCase : ResolveRoutineWeightsUseCase(
    personalRecordRepository = FakePersonalRecordRepository(),
    preferencesManager = FakePreferencesManager()
) {
    // The real class is a simple UseCase with no side effects.
    // We can use the real implementation with fake dependencies,
    // or override resolveWeights() if needed.
}
```

Alternatively, since `ResolveRoutineWeightsUseCase` is a concrete class (not interface), we can instantiate it directly with fake dependencies. Check if it has any problematic logic -- if it simply reads from repos, using the real class with fakes is sufficient.

#### 2. `SyncTriggerManager` -- nullable

The constructor already accepts `syncTriggerManager: SyncTriggerManager? = null`. Pass `null` in tests to disable sync behavior. No fake needed.

### ViewModel Factory Helper

```kotlin
// In test file or a shared test helper
private fun createViewModel(
    bleRepository: FakeBleRepository = FakeBleRepository(),
    workoutRepository: FakeWorkoutRepository = FakeWorkoutRepository(),
    exerciseRepository: FakeExerciseRepository = FakeExerciseRepository(),
    personalRecordRepository: FakePersonalRecordRepository = FakePersonalRecordRepository(),
    preferencesManager: FakePreferencesManager = FakePreferencesManager(),
    gamificationRepository: FakeGamificationRepository = FakeGamificationRepository(),
    trainingCycleRepository: FakeTrainingCycleRepository = FakeTrainingCycleRepository()
): MainViewModel {
    return MainViewModel(
        bleRepository = bleRepository,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        personalRecordRepository = personalRecordRepository,
        repCounter = RepCounterFromMachine(), // Real -- it's a pure logic class
        preferencesManager = preferencesManager,
        gamificationRepository = gamificationRepository,
        trainingCycleRepository = trainingCycleRepository,
        syncTriggerManager = null,
        resolveWeightsUseCase = ResolveRoutineWeightsUseCase(
            personalRecordRepository = personalRecordRepository,
            preferencesManager = preferencesManager
        )
    )
}
```

**NOTE**: `MainViewModel` extends `ViewModel()` from `androidx.lifecycle`. In KMP commonTest, this may need a `TestScope`-based coroutine environment. We must ensure `viewModelScope` is properly overridden or that we use `Dispatchers.setMain(StandardTestDispatcher())` in `@BeforeTest`.

### Coroutine Test Setup Pattern

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelWorkoutSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }
}
```

**KMP consideration**: `Dispatchers.setMain()` requires `kotlinx-coroutines-test`. Verify it's in `commonTest` dependencies. If not, add:
```kotlin
commonTest {
    dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    }
}
```

---

## Test Cases

### Priority 1: Core State Transitions (Must Write First)

These tests lock in the most critical behavior that the refactoring will directly touch.

---

#### T1.1: `resetForNewWorkout resets state to Idle and clears rep count`

**Rationale**: This is the central reset function. The extraction must preserve exact reset semantics.

**Setup**:
- Create viewModel
- Set `_workoutState` to Active (by calling `startWorkout` with `skipCountdown=true`)
- Set `_repCount` to non-zero values by processing rep notifications

**Action**:
- Call `viewModel.resetForNewWorkout()`

**Assert**:
- `viewModel.workoutState.value == WorkoutState.Idle`
- `viewModel.repCount.value == RepCount()` (all zeros)
- `viewModel.repRanges.value == null` (ROM calibration cleared)

---

#### T1.2: `resetForNewWorkout preserves load baseline`

**Rationale**: The function comment explicitly states load baseline is NOT reset. This is a critical invariant.

**Setup**:
- Create viewModel
- Connect to device
- Start a workout (to populate load baseline from initial metrics)
- Emit a metric to set `_loadBaselineA` and `_loadBaselineB` to non-zero values
- Advance until baseline capture happens

**Action**:
- Call `viewModel.resetForNewWorkout()`

**Assert**:
- `viewModel.loadBaselineA.value != 0f` (baseline preserved)
- `viewModel.loadBaselineB.value != 0f` (baseline preserved)

---

#### T1.3: `resetForNewWorkout preserves workout parameters`

**Rationale**: Parameters like weight and mode should persist across set resets.

**Setup**:
- Create viewModel
- Update workout parameters with specific weight (30f), mode (Pump), reps (8)

**Action**:
- Call `viewModel.resetForNewWorkout()`

**Assert**:
- `viewModel.workoutParameters.value.weightPerCableKg == 30f`
- `viewModel.workoutParameters.value.programMode == ProgramMode.Pump`
- `viewModel.workoutParameters.value.reps == 8`

---

### Priority 2: Just Lift Auto-Reset Behavior

---

#### T2.1: `Just Lift mode auto-resets to Idle after set completion`

**Rationale**: Just Lift has unique behavior -- it auto-resets to Idle instead of going to Completed, enabling immediate re-start. This is one of the most complex code paths.

**Setup**:
- Create viewModel with connected BLE
- Configure Just Lift parameters: `isJustLift = true, useAutoStart = true, isAMRAP = true`
- Start workout with `skipCountdown = true`
- Advance coroutines until Active state

**Action**:
- Trigger set completion (either via `stopWorkout()` or by emitting rep notifications that trigger `handleSetCompletion`)
- Advance through the summary/proceedFromSummary path

**Assert**:
- Final `workoutState.value == WorkoutState.Idle` (NOT Completed)
- This is the key behavioral difference from non-Just-Lift modes

**Note**: This test is complex because it goes through `handleSetCompletion -> proceedFromSummary -> Just Lift auto-reset`. It may require significant coroutine advancement.

---

#### T2.2: `Non-Just-Lift mode goes to Completed after last set`

**Rationale**: Contrast with T2.1 -- regular mode should transition to Completed.

**Setup**:
- Create viewModel with connected BLE
- Configure OldSchool parameters with no routine loaded (single exercise)
- Start workout, advance to Active

**Action**:
- Call `stopWorkout()`
- Advance coroutines

**Assert**:
- State transitions through SetSummary
- After `proceedFromSummary` (with no more sets), lands in `WorkoutState.Completed`

---

#### T2.3: `prepareForJustLift resets non-idle state and sets parameters`

**Rationale**: `prepareForJustLift()` has specific behavior around state reset and parameter preservation.

**Setup**:
- Create viewModel
- Set workout state to something non-Idle (e.g., Completed)
- Set weight to 25kg

**Action**:
- Call `viewModel.prepareForJustLift()`
- Advance coroutines

**Assert**:
- `workoutState.value == WorkoutState.Idle`
- `workoutParameters.value.isJustLift == true`
- `workoutParameters.value.useAutoStart == true`
- `workoutParameters.value.selectedExerciseId == null`
- `workoutParameters.value.weightPerCableKg == 25f` (preserved)

---

### Priority 3: RPE Logging Flow

---

#### T3.1: `logRpeForCurrentSet stores RPE value`

**Rationale**: Simple but critical -- RPE is stored for session persistence.

**Setup**:
- Create viewModel

**Action**:
- Call `viewModel.logRpeForCurrentSet(7)`

**Assert**:
- `viewModel.currentSetRpe.value == 7`

---

#### T3.2: `RPE is cleared after proceeding from summary`

**Rationale**: RPE must reset between sets to avoid stale values being recorded.

**Setup**:
- Create viewModel
- Log RPE of 8
- Verify `currentSetRpe.value == 8`

**Action**:
- Trigger `proceedFromSummary` flow (this requires being in SetSummary state)
- Advance coroutines

**Assert**:
- `viewModel.currentSetRpe.value == null` (cleared for next set)

---

#### T3.3: `RPE value is included in saved workout session`

**Rationale**: RPE must flow through to `WorkoutSession.rpe` for persistence.

**Setup**:
- Create viewModel with `FakeWorkoutRepository`
- Connect, configure, start workout
- Log RPE of 9
- Stop workout

**Action**:
- Advance coroutines
- Check saved sessions in FakeWorkoutRepository

**Assert**:
- `fakeWorkoutRepository.sessions.last().rpe == 9`

---

### Priority 4: Pause/Resume State Transitions

---

#### T4.1: `pauseWorkout transitions Active to Paused`

**Rationale**: Guard condition -- pause only works from Active.

**Setup**:
- Create viewModel
- Get to Active state (connect + startWorkout with skipCountdown)
- Advance coroutines

**Action**:
- Call `viewModel.pauseWorkout()`

**Assert**:
- `workoutState.value == WorkoutState.Paused`

---

#### T4.2: `pauseWorkout is no-op when not Active`

**Rationale**: Calling pause from Idle or other states should do nothing.

**Setup**:
- Create viewModel (state = Idle)

**Action**:
- Call `viewModel.pauseWorkout()`

**Assert**:
- `workoutState.value == WorkoutState.Idle` (unchanged)

---

#### T4.3: `resumeWorkout transitions Paused back to Active`

**Rationale**: Resume only works from Paused.

**Setup**:
- Get to Paused state (Active -> pause)

**Action**:
- Call `viewModel.resumeWorkout()`

**Assert**:
- `workoutState.value == WorkoutState.Active`

---

#### T4.4: `resumeWorkout is no-op when not Paused`

**Setup**:
- Create viewModel in Idle state

**Action**:
- Call `viewModel.resumeWorkout()`

**Assert**:
- `workoutState.value == WorkoutState.Idle` (unchanged)

---

### Priority 5: Handle Detection Logic

---

#### T5.1: `enableHandleDetection delegates to bleRepository`

**Rationale**: Verify the delegation pattern works.

**Setup**:
- Create viewModel with `FakeBleRepository`

**Action**:
- Call `viewModel.enableHandleDetection()`

**Assert**:
- `fakeBleRepository.handleState.value == HandleState.WaitingForRest` (the fake sets this on enableHandleDetection)

---

#### T5.2: `enableHandleDetection is debounced within 500ms`

**Rationale**: The idempotency guard prevents rapid re-enabling from resetting the BLE state machine.

**Setup**:
- Create viewModel

**Action**:
- Call `viewModel.enableHandleDetection()` at time T
- Immediately call `viewModel.enableHandleDetection()` again (within 500ms)

**Assert**:
- The second call is a no-op (harder to verify directly -- may need to check that `bleRepository.enableHandleDetection` was called only once, which requires enhancing FakeBleRepository with a call counter)

**Enhancement needed**: Add `enableHandleDetectionCallCount` to `FakeBleRepository`.

---

#### T5.3: `disableHandleDetection calls bleRepository`

**Setup**:
- Create viewModel with `FakeBleRepository`

**Action**:
- Call `viewModel.disableHandleDetection()`

**Assert**:
- Verify `bleRepository.enableHandleDetection(false)` was called (need call tracking in fake)

---

### Priority 6: Stop Workout Guards

---

#### T6.1: `stopWorkout guard prevents double invocation`

**Rationale**: The `stopWorkoutInProgress` flag prevents race conditions where `handleMonitorMetric` and `WORKOUT_COMPLETE` both trigger stop.

**Setup**:
- Create viewModel, connect, start workout, advance to Active

**Action**:
- Call `viewModel.stopWorkout()` twice in rapid succession
- Advance coroutines

**Assert**:
- Only ONE session is saved to `FakeWorkoutRepository`
- State ends in SetSummary (not double-processed)

---

#### T6.2: `stopWorkout with exitingWorkout=true goes to Idle`

**Rationale**: Exiting the workout screen should go to Idle, not SetSummary.

**Setup**:
- Create viewModel, connect, start workout, advance to Active

**Action**:
- Call `viewModel.stopWorkout(exitingWorkout = true)`
- Advance coroutines

**Assert**:
- `workoutState.value == WorkoutState.Idle`
- `routineFlowState.value == RoutineFlowState.NotInRoutine`
- `loadedRoutine.value == null`

---

#### T6.3: `stopWorkout with exitingWorkout=false shows SetSummary`

**Setup**:
- Create viewModel, connect, start workout, advance to Active

**Action**:
- Call `viewModel.stopWorkout(exitingWorkout = false)` (default)
- Advance coroutines

**Assert**:
- `workoutState.value is WorkoutState.SetSummary`

---

### Priority 7: stopAndReturnToSetReady

---

#### T7.1: `stopAndReturnToSetReady resets to Idle with clean counters`

**Rationale**: This function allows redoing a set without exiting the routine.

**Setup**:
- Create viewModel, start workout, advance to Active
- Emit some rep notifications to build up rep count

**Action**:
- Call `viewModel.stopAndReturnToSetReady()`
- Advance coroutines

**Assert**:
- `workoutState.value == WorkoutState.Idle`
- `repCount.value == RepCount()` (all zeros)
- `repRanges.value == null`
- Timed exercise countdown cleared: `timedExerciseRemainingSeconds.value == null`

---

### Priority 8: updateWorkoutParameters Weight Tracking

---

#### T8.1: `updateWorkoutParameters during Rest sets user-adjusted flag`

**Rationale**: Issue #108/#170 -- user edits during rest must persist through transitions.

**Setup**:
- Create viewModel
- Get to Resting state

**Action**:
- Call `viewModel.updateWorkoutParameters(params.copy(weightPerCableKg = 50f))`

**Assert**:
- The weight is preserved when transitioning to the next set (would need to trigger `startNextSetOrExercise` and verify weight is not overwritten by routine values)
- At minimum, verify `workoutParameters.value.weightPerCableKg == 50f`

---

#### T8.2: `updateWorkoutParameters during Idle sets user-adjusted flag`

**Rationale**: Issue #180 -- user adjusts weight BEFORE routine starts.

**Setup**:
- Create viewModel (state = Idle)

**Action**:
- Call `viewModel.updateWorkoutParameters(params.copy(weightPerCableKg = 40f))`

**Assert**:
- `workoutParameters.value.weightPerCableKg == 40f`

---

### Priority 9: Rep Counting Integration

---

#### T9.1: `Rep notifications flow through to repCount state`

**Rationale**: The RepCounter -> `_repCount` flow is a core integration point.

**Setup**:
- Create viewModel, connect, start workout with warmupReps=3, workingTarget=10
- Advance to Active

**Action**:
- Emit rep notifications simulating warmup and working reps through `FakeBleRepository`

**Assert**:
- `repCount.value.warmupReps` matches emitted warmup count
- `repCount.value.workingReps` matches emitted working count

**Note**: This requires understanding the RepCounter's `process()` inputs. The rep notifications flow through `handleRepNotification` which calls `repCounter.process()` which updates `getRepCount()`. The ViewModel then copies this to `_repCount`. We need to trace this flow.

---

#### T9.2: `RepCounter WORKOUT_COMPLETE event triggers handleSetCompletion`

**Rationale**: This is a critical integration point documented in the code (Issue #182).

**Setup**:
- Create viewModel, connect, start workout with 2 working reps target
- Advance to Active

**Action**:
- Emit enough rep notifications to trigger WORKOUT_COMPLETE
- Advance coroutines

**Assert**:
- `workoutState.value is WorkoutState.SetSummary` (set completion was triggered)
- A session was saved to `FakeWorkoutRepository`

---

### Priority 10: Start Workout State Transitions

---

#### T10.1: `startWorkout sets Initializing immediately`

**Rationale**: Prevents UI race condition where ActiveWorkoutScreen sees Idle.

**Setup**:
- Create viewModel, connect

**Action**:
- Call `viewModel.startWorkout(skipCountdown = false)`
- Check state BEFORE advancing coroutines

**Assert**:
- `workoutState.value == WorkoutState.Initializing`

---

#### T10.2: `startWorkout with skipCountdown goes directly to Active`

**Setup**:
- Create viewModel, connect

**Action**:
- Call `viewModel.startWorkout(skipCountdown = true)`
- Advance coroutines

**Assert**:
- `workoutState.value == WorkoutState.Active`
- No Countdown states were observed

---

#### T10.3: `startWorkout without skipCountdown goes through Countdown to Active`

**Setup**:
- Create viewModel, connect

**Action**:
- Call `viewModel.startWorkout(skipCountdown = false)`
- Advance time by 5 seconds

**Assert**:
- State passed through `WorkoutState.Countdown(5)` ... `WorkoutState.Countdown(1)`
- Final state is `WorkoutState.Active`

---

### Priority 11: isWorkoutActive Property

---

#### T11.1: `isWorkoutActive is false when Idle`

**Assert**: `viewModel.isWorkoutActive == false`

---

#### T11.2: `isWorkoutActive is false when Completed`

**Setup**: Get to Completed state

**Assert**: `viewModel.isWorkoutActive == false`

---

#### T11.3: `isWorkoutActive is true when Active`

**Setup**: Start workout, advance to Active

**Assert**: `viewModel.isWorkoutActive == true`

---

#### T11.4: `isWorkoutActive is true when Resting`

**Setup**: Get to Resting state

**Assert**: `viewModel.isWorkoutActive == true`

---

#### T11.5: `isWorkoutActive is true when Paused`

**Setup**: Active -> pause

**Assert**: `viewModel.isWorkoutActive == true`

---

## Execution Priority Order

| Priority | Group | Test Count | Risk if Missing |
|---|---|---|---|
| P1 | resetForNewWorkout | 3 | HIGH -- core reset semantics |
| P2 | Just Lift auto-reset | 3 | HIGH -- unique mode behavior |
| P3 | RPE logging | 3 | MEDIUM -- data integrity |
| P4 | Pause/Resume | 4 | MEDIUM -- state guards |
| P5 | Handle detection | 3 | MEDIUM -- BLE integration |
| P6 | Stop workout guards | 3 | HIGH -- race condition prevention |
| P7 | stopAndReturnToSetReady | 1 | MEDIUM -- redo flow |
| P8 | Weight tracking | 2 | MEDIUM -- user edits |
| P9 | Rep counting integration | 2 | HIGH -- core flow |
| P10 | Start workout transitions | 3 | HIGH -- state machine entry |
| P11 | isWorkoutActive | 5 | LOW -- simple property |
| **Total** | | **32** | |

---

## KMP Test Considerations

1. **`viewModelScope` in commonTest**: `MainViewModel` extends `ViewModel()` and uses `viewModelScope`. In commonTest, we need `kotlinx-coroutines-test` and must set the main dispatcher:
   ```kotlin
   Dispatchers.setMain(StandardTestDispatcher())
   ```
   Verify this works on all targets (JVM, iOS, JS). If `Dispatchers.setMain` is not available on all targets, we may need to use `runTest` with a custom `TestCoroutineScheduler`.

2. **`currentTimeMillis()` in tests**: This is an `expect fun` from `PlatformUtils.kt`. It should work in tests since it uses platform-native time. However, if we need deterministic timing for debounce tests (T5.2), we may need to factor time into a testable abstraction.

3. **`generateUUID()` in tests**: Also an `expect fun` -- should work but produces random values. For session ID verification, check existence (not null) rather than exact values.

4. **Large file import in init**: `MainViewModel.init` block calls `exerciseRepository.importExercises()`. The `FakeExerciseRepository` returns `Result.success(Unit)` by default, so this is safe.

5. **StateFlow collection timing**: Many assertions need `advanceUntilIdle()` before checking state values, since state updates happen in `viewModelScope.launch {}` blocks.

6. **RepCounter integration**: Use the REAL `RepCounterFromMachine` in tests (it's a pure logic class with no platform dependencies). This provides true characterization of the integration behavior.

---

## FakeBleRepository Enhancement Needed

For handle detection tests, add a call counter:

```kotlin
// In FakeBleRepository
var enableHandleDetectionCallCount = 0
    private set

override fun enableHandleDetection(enabled: Boolean) {
    enableHandleDetectionCallCount++
    if (enabled) {
        _handleState.value = HandleState.WaitingForRest
    }
}
```

---

## Test Data Recommendations

Use `TestFixtures` for consistency:
- `TestFixtures.benchPress` for exercise data
- `TestFixtures.oldSchoolParams` for standard workout config
- `TestFixtures.justLiftParams` for Just Lift config
- `TestFixtures.createWorkoutMetric()` for BLE metric simulation
- `TestFixtures.createRepNotification()` for rep event simulation

---

## What These Tests Do NOT Cover (Out of Scope)

- BLE command packet formatting (covered by `BlePacketFactoryTest`)
- Rep counting algorithm details (covered by `RepCounterFromMachineTest`)
- Database persistence (covered by repository tests)
- UI rendering (covered by Compose UI tests)
- Auto-stop detection logic (metrics processing -- deep internal state)
- Routine navigation (superset stepping, exercise jumping) -- these are adjacent to but separate from the workout session lifecycle

These exclusions are intentional: we characterize the **boundary** of the workout session cluster, not every internal detail. The refactoring should not change these excluded behaviors since they live outside the extraction boundary.

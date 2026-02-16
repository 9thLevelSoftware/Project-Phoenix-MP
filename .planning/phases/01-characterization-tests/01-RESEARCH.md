# Phase 1: Characterization Tests - Research

**Researched:** 2026-02-12
**Domain:** KMP characterization testing of a 4024-line workout session manager
**Confidence:** HIGH

## Summary

DefaultWorkoutSessionManager (DWSM) is a 4024-line monolith managing the entire workout lifecycle: starting, stopping, rep counting, auto-stop, rest timers, routine navigation, superset flow, weight adjustment, Just Lift mode, and training cycles. It has 13 constructor dependencies plus a `lateinit var bleConnectionManager`.

The test infrastructure is well-established. CommonTest already has 7 fake repositories (BleRepository, WorkoutRepository, ExerciseRepository, PersonalRecordRepository, CompletedSetRepository, TrainingCycleRepository, GamificationRepository), a FakePreferencesManager, and a TestFixtures object with pre-built exercises, workout parameters, sessions, metrics, and rep notifications. The only missing pieces are: (1) fake/stub for SettingsManager and GamificationManager (concrete classes, not interfaces -- must be constructed with fake deps), (2) a BleConnectionManager stub, and (3) a DWSM test factory that wires everything together.

**Primary recommendation:** Build a `DWSMTestHarness` that constructs DWSM with all fakes in a `TestScope`, then write characterization tests that call public methods and assert state flow transitions. Use `runTest` + Turbine for flow assertions. Tests must NOT refactor DWSM -- they lock in current behavior as-is.

## Standard Stack

### Core (already in commonTest dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlin-test | 2.0.21 | Assertions + multiplatform test runner | KMP standard, already configured |
| kotlinx-coroutines-test | 1.9.0 | `runTest`, `TestScope`, `TestDispatcher` | Required for testing coroutine-heavy code |
| turbine | 1.2.1 | StateFlow/SharedFlow testing | Already in deps, essential for flow assertions |

### Supporting (already available)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| koin-test | 4.0.0 | DI testing helpers | NOT needed -- DWSM is manually constructed, not from Koin |
| multiplatform-settings-test | (bundled) | In-memory settings | Already used by FakePreferencesManager |

### Not Needed
| Library | Why Not |
|---------|---------|
| Mockk/Mockito | Prior decision: "test through DWSM public API with fake repos" -- fakes already exist |
| koin-test | DWSM stays out of Koin (prior decision) |

**Installation:** No new dependencies needed. Everything required is already in `shared/build.gradle.kts` commonTest block.

## Architecture Patterns

### Test File Organization
```
shared/src/commonTest/kotlin/com/devil/phoenixproject/
├── testutil/
│   ├── TestFixtures.kt              # EXISTS - exercises, params, sessions, metrics
│   ├── FakeBleRepository.kt         # EXISTS - full fake with simulate* methods
│   ├── FakeWorkoutRepository.kt     # EXISTS
│   ├── FakeExerciseRepository.kt    # EXISTS
│   ├── FakePersonalRecordRepository.kt  # EXISTS
│   ├── FakeCompletedSetRepository.kt    # EXISTS
│   ├── FakeTrainingCycleRepository.kt   # EXISTS
│   ├── FakeGamificationRepository.kt    # EXISTS
│   ├── FakePreferencesManager.kt        # EXISTS
│   ├── DWSMTestHarness.kt           # NEW - factory for constructing DWSM with all fakes
│   └── WorkoutStateFixtures.kt      # NEW - pre-built workout states (Active, Resting, SetReady, etc.)
├── presentation/manager/
│   ├── DWSMWorkoutLifecycleTest.kt   # NEW - Plan 01-01
│   └── DWSMRoutineFlowTest.kt       # NEW - Plan 01-02
└── e2e/robot/
    └── WorkoutRobot.kt              # EXISTS - but targets MainViewModel, not DWSM directly
```

### Pattern 1: DWSMTestHarness (Test Object Builder)
**What:** A class that constructs DWSM with all 13 dependencies using fakes, exposing both the DWSM instance and the fake objects for test control.
**When to use:** Every DWSM characterization test.
**Key insight:** DWSM takes concrete classes (GamificationManager, SettingsManager, BleConnectionManager), not interfaces. These must be constructed with their own fake dependencies.

```kotlin
class DWSMTestHarness(
    val testScope: TestScope
) {
    val fakeBleRepo = FakeBleRepository()
    val fakeWorkoutRepo = FakeWorkoutRepository()
    val fakeExerciseRepo = FakeExerciseRepository()
    val fakePRRepo = FakePersonalRecordRepository()
    val fakePrefsManager = FakePreferencesManager()
    val fakeGamificationRepo = FakeGamificationRepository()
    val fakeCompletedSetRepo = FakeCompletedSetRepository()
    val fakeTrainingCycleRepo = FakeTrainingCycleRepository()

    val repCounter = RepCounterFromMachine()  // Real - stateless utility, no dependencies
    val resolveWeightsUseCase = ResolveRoutineWeightsUseCase(fakePRRepo)  // Real - only needs PR repo

    // Concrete managers constructed with fakes
    val settingsManager = SettingsManager(fakePrefsManager, fakeBleRepo, testScope)
    val gamificationManager = GamificationManager(
        fakeGamificationRepo, fakePRRepo, fakeExerciseRepo,
        MutableSharedFlow(extraBufferCapacity = 10), testScope
    )

    val dwsm = DefaultWorkoutSessionManager(
        bleRepository = fakeBleRepo,
        workoutRepository = fakeWorkoutRepo,
        exerciseRepository = fakeExerciseRepo,
        personalRecordRepository = fakePRRepo,
        repCounter = repCounter,
        preferencesManager = fakePrefsManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = fakeTrainingCycleRepo,
        completedSetRepository = fakeCompletedSetRepo,
        syncTriggerManager = null,  // Nullable in constructor, safe to skip
        resolveWeightsUseCase = resolveWeightsUseCase,
        settingsManager = settingsManager,
        scope = testScope
    ).also {
        // Must set lateinit bleConnectionManager
        it.bleConnectionManager = BleConnectionManager(
            fakeBleRepo, settingsManager, it, testScope
        )
    }
}
```

### Pattern 2: Characterization Test Structure
**What:** Tests that document existing behavior without judging it.
**When to use:** Every test in this phase.
**Key principle:** Characterization tests assert WHAT the code does today, not what it SHOULD do. Even surprising behavior gets locked in.

```kotlin
class DWSMWorkoutLifecycleTest {
    @Test
    fun startWorkout_setsStateToInitializingImmediately() = runTest {
        val harness = DWSMTestHarness(this)
        // Precondition: connected to device
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)

        // DWSM sets Initializing synchronously before launching coroutine
        assertEquals(WorkoutState.Initializing, harness.dwsm.workoutState.value)
    }

    @Test
    fun startWorkout_transitionsToActive_afterCountdownSkipped() = runTest {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")

        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()

        assertEquals(WorkoutState.Active, harness.dwsm.workoutState.value)
    }
}
```

### Pattern 3: Pre-built Workout State Fixtures
**What:** Factory functions that put DWSM into specific states for testing.
**When to use:** Tests that need DWSM in Active, Resting, SetReady, or SetSummary state.

```kotlin
object WorkoutStateFixtures {
    /** Put DWSM into Active workout state (post-countdown) */
    suspend fun TestScope.activeDWSM(): DWSMTestHarness {
        val harness = DWSMTestHarness(this)
        harness.fakeBleRepo.simulateConnect("Vee_Test")
        harness.dwsm.startWorkout(skipCountdown = true)
        advanceUntilIdle()
        return harness
    }

    /** Put DWSM into routine SetReady state */
    suspend fun TestScope.setReadyDWSM(routine: Routine? = null): DWSMTestHarness {
        val harness = DWSMTestHarness(this)
        val r = routine ?: createTestRoutine()
        harness.fakeExerciseRepo.addExercise(TestFixtures.benchPress)
        harness.dwsm.loadRoutine(r)
        advanceUntilIdle()
        harness.dwsm.enterSetReady(0, 0)
        return harness
    }
}
```

### Anti-Patterns to Avoid
- **Testing private methods:** Characterization tests operate through public API only. If you need to verify internal state, check it via public StateFlows.
- **Modifying DWSM during testing:** Phase 1 tests must pass against UNMODIFIED DWSM. Zero changes to production code.
- **Using MainViewModel:** The existing WorkoutRobot targets MainViewModel. These tests must target DWSM directly.
- **Assuming instant state transitions:** DWSM launches coroutines for most operations. Always use `advanceUntilIdle()` or Turbine's `awaitItem()` to wait for state changes.
- **Testing against delays:** DWSM has `delay(100)`, `delay(150)`, `delay(1000)` calls. In `runTest`, time is virtual -- use `advanceTimeBy()` when needed.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Flow testing | Manual collect + channel | Turbine 1.2.1 | Already in deps, handles timeouts, cancellation edge cases |
| Coroutine test scope | Manual dispatcher setup | `runTest` from kotlinx-coroutines-test | Provides TestScope with virtual time, auto-advances |
| Fake repositories | Mock frameworks | Existing fakes in testutil/ | 7 fakes already written and battle-tested |
| Test fixtures | Inline test data | TestFixtures object | Exercises, params, metrics already defined |

**Key insight:** The test infrastructure is already 80% built. The main gap is a DWSM construction helper and workout state fixtures.

## Common Pitfalls

### Pitfall 1: CoroutineScope Lifecycle in Tests
**What goes wrong:** Using `GlobalScope` or creating a plain `CoroutineScope` leads to leaked coroutines and flaky tests.
**Why it happens:** DWSM stores `scope` and launches background jobs (workout monitoring, rest timers, bodyweight timers).
**How to avoid:** Always use `runTest { }` which provides a `TestScope`. Pass `this` (the TestScope) as the `scope` parameter to DWSM.
**Warning signs:** Tests that hang, flaky failures, "Job was not completed" errors.

### Pitfall 2: lateinit bleConnectionManager
**What goes wrong:** Accessing DWSM methods that touch `bleConnectionManager` before it's set causes `UninitializedPropertyAccessException`.
**Why it happens:** DWSM has `lateinit var bleConnectionManager` due to circular dependency with WorkoutStateProvider interface.
**How to avoid:** The DWSMTestHarness pattern sets it immediately after construction via `.also { it.bleConnectionManager = ... }`.
**Warning signs:** `UninitializedPropertyAccessException` in test output.

### Pitfall 3: loadRoutine is Async
**What goes wrong:** Calling `loadRoutine()` and immediately checking state finds stale values.
**Why it happens:** `loadRoutine()` launches a coroutine internally (`scope.launch { resolveRoutineWeights(routine); loadRoutineInternal(resolvedRoutine) }`).
**How to avoid:** Call `advanceUntilIdle()` after `loadRoutine()` before asserting.
**Warning signs:** Tests see `RoutineFlowState.NotInRoutine` when expecting `Overview` or params from the loaded routine.

### Pitfall 4: stopWorkout Guard Flag
**What goes wrong:** Calling `stopWorkout()` twice does nothing the second time.
**Why it happens:** DWSM has a `stopWorkoutInProgress` guard (Issue #97 fix) that prevents re-entrant calls. It's only reset in `startWorkout()`.
**How to avoid:** Always call `startWorkout()` between `stopWorkout()` calls in test sequences.
**Warning signs:** State doesn't transition to Idle/SetSummary on second stop.

### Pitfall 5: handleSetCompletion is Private
**What goes wrong:** Tests cannot directly trigger set completion.
**Why it happens:** `handleSetCompletion()` is `private` -- it's triggered internally by rep counting or timed exercise completion.
**How to avoid:** Trigger set completion by either: (a) calling `stopWorkout()` which handles the completion path, or (b) for auto-stop scenarios, feeding enough metrics via `fakeBleRepo.emitMetric()` / `emitRepNotification()` to trigger the auto-stop logic.
**Warning signs:** Unable to test the "set completed, show summary, advance to rest" flow.

### Pitfall 6: Routine Exercises Must Exist in ExerciseRepository
**What goes wrong:** `saveWorkoutSession()` calls `exerciseRepository.getExerciseById()` for exercise names. If exercises aren't seeded in FakeExerciseRepository, session data may have null exercise names.
**How to avoid:** Seed FakeExerciseRepository with the exercises used in test routines via `fakeExerciseRepo.addExercise()`.
**Warning signs:** Null exercise names in saved sessions or assertion failures on session data.

### Pitfall 7: DWSM Constructor Initializes Flows on Construction
**What goes wrong:** DWSM's `init` block launches coroutines that collect from repositories and BLE flows.
**Why it happens:** Lines 350-560 in DWSM contain `init { }` blocks that start monitoring metrics, handle state, etc.
**How to avoid:** Construct DWSMTestHarness inside `runTest` so the TestScope captures these launched coroutines.
**Warning signs:** Coroutine leak errors, missing flow events.

## Code Examples

### Creating a Test Routine
```kotlin
fun createTestRoutine(
    exerciseCount: Int = 3,
    setsPerExercise: Int = 3,
    weightKg: Float = 25f,
    repsPerSet: Int = 10
): Routine {
    val exercises = (0 until exerciseCount).map { i ->
        RoutineExercise(
            id = "re-$i",
            exercise = TestFixtures.allExercises[i % TestFixtures.allExercises.size],
            orderIndex = i,
            setReps = List(setsPerExercise) { repsPerSet },
            weightPerCableKg = weightKg
        )
    }
    return Routine(
        id = "test-routine",
        name = "Test Routine",
        exercises = exercises
    )
}
```

### Creating a Superset Routine
```kotlin
fun createSupersetRoutine(): Routine {
    val supersetId = "ss-1"
    val exercises = listOf(
        RoutineExercise(
            id = "re-0", exercise = TestFixtures.benchPress,
            orderIndex = 0, setReps = listOf(10, 10, 10),
            weightPerCableKg = 25f,
            supersetId = supersetId, orderInSuperset = 0
        ),
        RoutineExercise(
            id = "re-1", exercise = TestFixtures.bicepCurl,
            orderIndex = 1, setReps = listOf(12, 12, 12),
            weightPerCableKg = 15f,
            supersetId = supersetId, orderInSuperset = 1
        ),
        RoutineExercise(
            id = "re-2", exercise = TestFixtures.squat,
            orderIndex = 2, setReps = listOf(8, 8, 8),
            weightPerCableKg = 40f
        )
    )
    return Routine(
        id = "test-superset-routine",
        name = "Superset Routine",
        exercises = exercises,
        supersets = listOf(
            Superset(id = supersetId, routineId = "test-superset-routine",
                name = "Chest/Arms", restBetweenSeconds = 10, orderIndex = 0)
        )
    )
}
```

### Testing Workout State Transitions with Turbine
```kotlin
@Test
fun startWorkout_countdown_emitsCountdownStates() = runTest {
    val harness = DWSMTestHarness(this)
    harness.fakeBleRepo.simulateConnect("Vee_Test")

    harness.dwsm.workoutState.test {
        assertEquals(WorkoutState.Idle, awaitItem()) // Initial

        harness.dwsm.startWorkout(skipCountdown = false)
        assertEquals(WorkoutState.Initializing, awaitItem())

        // Countdown: 5, 4, 3, 2, 1
        for (i in 5 downTo 1) {
            advanceTimeBy(1000)
            val state = awaitItem()
            assertIs<WorkoutState.Countdown>(state)
            assertEquals(i, state.secondsRemaining)
        }

        advanceTimeBy(1000)
        assertEquals(WorkoutState.Active, awaitItem())

        cancelAndIgnoreRemainingEvents()
    }
}
```

### Testing Routine Flow Transitions
```kotlin
@Test
fun enterSetReady_updatesRoutineFlowStateAndParams() = runTest {
    val harness = DWSMTestHarness(this)
    val routine = createTestRoutine()
    // Seed exercises
    routine.exercises.forEach { harness.fakeExerciseRepo.addExercise(it.exercise) }

    harness.dwsm.loadRoutine(routine)
    advanceUntilIdle()

    harness.dwsm.enterSetReady(exerciseIndex = 0, setIndex = 0)

    val flowState = harness.dwsm.routineFlowState.value
    assertIs<RoutineFlowState.SetReady>(flowState)
    assertEquals(0, flowState.exerciseIndex)
    assertEquals(0, flowState.setIndex)
    assertEquals(25f, flowState.adjustedWeight)
    assertEquals(10, flowState.adjustedReps)
}
```

## DWSM Public API Inventory (Methods to Characterize)

### Plan 01-01: Workout Lifecycle (TEST-01, TEST-03)
| Method | Visibility | Key Behaviors to Lock In |
|--------|-----------|--------------------------|
| `startWorkout(skipCountdown, isJustLiftMode)` | public | Sets Initializing immediately, countdown sequence, BLE command send, transition to Active |
| `stopWorkout(exitingWorkout)` | public | Guard flag, cancel jobs, BLE stop, save session, SetSummary vs Idle transition |
| `skipCountdown()` | public | Sets flag, countdown loop breaks early |
| `handleSetCompletion()` | private | Triggered via stopWorkout/autoStop -- saves session, shows summary, starts rest timer |
| `checkAutoStop(metric)` | private | Position-based auto-stop after 2.5s in danger zone |
| `saveWorkoutSession()` | private | Builds WorkoutSession, saves to repo, triggers gamification |
| `resetForNewWorkout()` | public | Clears rep counter, auto-stop state, metrics |
| `updateWorkoutParameters(params)` | public | Updates params, tracks user edits during rest/summary |

### Plan 01-02: Routine Flow (TEST-02)
| Method | Visibility | Key Behaviors to Lock In |
|--------|-----------|--------------------------|
| `loadRoutine(routine)` | public | Async weight resolution, sets first exercise params, resets indices |
| `enterSetReady(exerciseIndex, setIndex)` | public | Updates RoutineFlowState, sets per-set params including AMRAP detection |
| `advanceToNextExercise()` | public | Delegates to jumpToExercise(nextIndex) |
| `jumpToExercise(index)` | public | BLE stop sequence, navigate, auto-start next exercise |
| `skipCurrentExercise()` | public | Marks current skipped, jumps to next |
| `goToPreviousExercise()` | public | Jump to prev index |
| `enterRoutineOverview(routine)` | public | Sets RoutineFlowState.Overview |
| `selectExerciseInOverview(index)` | public | Updates selectedExerciseIndex |
| `getCurrentSupersetExercises()` | public | Returns exercises in current superset |
| `getNextSupersetExercise()` | public | Returns next exercise in superset chain |

## State Machine Summary

### WorkoutState Transitions
```
Idle -> Initializing -> Countdown(5) -> ... -> Countdown(1) -> Active -> SetSummary -> Resting -> Idle
                                                                    |                              ^
                                                          (exitingWorkout=true)                    |
                                                                    +------> Idle                  |
                                                                                                   |
                         (skipCountdown=true)                                                      |
Idle -> Initializing -> Active -> SetSummary -> Resting ------------------------------------------>+
```

### RoutineFlowState Transitions
```
NotInRoutine -> Overview -> SetReady -> (workout happens) -> SetReady (next set)
                                                          -> Overview (next exercise)
                                                          -> Complete (all done)
```

## Dependency Inventory for DWSM Construction

| Parameter | Type | Interface? | Fake Available? | Notes |
|-----------|------|-----------|-----------------|-------|
| bleRepository | BleRepository | Yes | FakeBleRepository | Full fake with simulate methods |
| workoutRepository | WorkoutRepository | Yes | FakeWorkoutRepository | In-memory sessions/routines |
| exerciseRepository | ExerciseRepository | Yes | FakeExerciseRepository | In-memory exercises |
| personalRecordRepository | PersonalRecordRepository | Yes | FakePersonalRecordRepository | With call tracking |
| repCounter | RepCounterFromMachine | No (concrete) | Use real instance | No-arg constructor, stateless |
| preferencesManager | PreferencesManager | Yes | FakePreferencesManager | In-memory prefs |
| gamificationManager | GamificationManager | No (concrete) | Construct with fakes | Needs GamificationRepo, PRRepo, ExerciseRepo |
| trainingCycleRepository | TrainingCycleRepository | Yes | FakeTrainingCycleRepository | In-memory cycles |
| completedSetRepository | CompletedSetRepository | Yes | FakeCompletedSetRepository | Planned + completed sets |
| syncTriggerManager | SyncTriggerManager? | No (nullable) | Pass null | Safe to skip for tests |
| resolveWeightsUseCase | ResolveRoutineWeightsUseCase | No (concrete) | Construct with FakePRRepo | Only needs PR repo |
| settingsManager | SettingsManager | No (concrete) | Construct with fakes | Needs PrefsManager, BleRepo, scope |
| scope | CoroutineScope | N/A | TestScope from runTest | Virtual time support |
| _hapticEvents | MutableSharedFlow | N/A | Default from constructor | Already has default value |
| **lateinit** bleConnectionManager | BleConnectionManager | No (concrete) | Construct with fakes | Must set after construction |

## Test Run Command

```bash
# Run all shared tests (commonTest compiles into this)
./gradlew :shared:testDebugUnitTest

# Run specific test class
./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.presentation.manager.DWSMWorkoutLifecycleTest"

# Run specific test method
./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.presentation.manager.DWSMWorkoutLifecycleTest.startWorkout_setsStateToInitializingImmediately"
```

## Open Questions

1. **DWSM init block side effects**
   - What we know: DWSM has init blocks that launch coroutines collecting from repos and BLE flows
   - What's unclear: Exact coroutines launched and whether they interfere with test assertions
   - Recommendation: Read lines 350-560 carefully during implementation. May need `advanceUntilIdle()` after harness construction.

2. **Resting state transition**
   - What we know: WorkoutState.Resting exists in the sealed class but the transition path goes through `startRestTimer()` which is private
   - What's unclear: Whether `stopWorkout()` triggers rest timer in routine mode or only `handleSetCompletion()` does
   - Recommendation: Discover empirically during test writing -- this is exactly what characterization tests are for.

3. **KableBleRepository import in SettingsManager**
   - What we know: SettingsManager imports KableBleRepository (line 6), which is Android-specific
   - What's unclear: Whether this causes commonTest compilation issues
   - Recommendation: Check if SettingsManager compiles in commonTest. If not, may need a minimal SettingsManager stub instead of constructing the real one.

## Sources

### Primary (HIGH confidence)
- Direct codebase inspection of DefaultWorkoutSessionManager.kt (4024 lines)
- Direct codebase inspection of all 9 fake test utilities in testutil/
- Direct codebase inspection of shared/build.gradle.kts for test dependencies
- Direct codebase inspection of Routine.kt, Models.kt for domain models
- Direct codebase inspection of SettingsManager.kt, GamificationManager.kt, BleConnectionManager.kt for constructor signatures

### Secondary (MEDIUM confidence)
- Turbine 1.2.1 API patterns (based on training data, version confirmed in libs.versions.toml)
- kotlinx-coroutines-test 1.9.0 runTest patterns (version confirmed in libs.versions.toml)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - all deps already in build.gradle.kts, versions confirmed
- Architecture: HIGH - based on direct codebase inspection of all relevant files
- Pitfalls: HIGH - identified from reading actual DWSM implementation and constructor requirements
- Test fixtures: HIGH - existing TestFixtures.kt and fakes inspected line by line

**Research date:** 2026-02-12
**Valid until:** 2026-03-12 (stable -- KMP test patterns don't change frequently)

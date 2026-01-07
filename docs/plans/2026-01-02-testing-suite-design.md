# Comprehensive Testing Suite Design

**Date:** 2026-01-02
**Status:** Approved
**Goal:** End-to-end testing coverage for refactoring confidence, bug prevention, and CI/CD foundation

## Test Structure & Organization

```
shared/src/
├── commonTest/kotlin/com/devil/phoenixproject/
│   ├── domain/
│   │   ├── model/          # Domain model unit tests
│   │   └── usecase/        # Use case tests
│   ├── data/
│   │   ├── repository/     # Repository tests (in-memory DB)
│   │   └── ble/            # BLE command/response parsing tests
│   └── testutil/           # Shared test utilities, fakes, fixtures
│
├── androidTest/kotlin/com/devil/phoenixproject/
│   ├── database/           # Android SQLite driver tests
│   └── ble/                # Android-specific BLE behavior tests
│
└── iosTest/kotlin/com/devil/phoenixproject/
    ├── database/           # Native SQLite driver tests
    └── ble/                # iOS-specific BLE behavior tests

androidApp/src/
├── test/kotlin/            # ViewModel unit tests (JVM)
│   └── com/devil/phoenixproject/
│       └── viewmodel/      # All ViewModel tests
│
└── androidTest/kotlin/     # Instrumented E2E tests
    └── com/devil/phoenixproject/
        ├── e2e/            # Full user journey tests
        ├── screen/         # Individual screen tests
        └── robot/          # Test robots for screen interactions
```

**Key decisions:**
- **commonTest** for all platform-agnostic logic (80%+ of tests)
- **Robot pattern** for E2E tests - readable, maintainable test code
- **Fakes over mocks** for repositories - more reliable, self-documenting

## Test Dependencies

**shared/build.gradle.kts additions:**
```kotlin
commonTest {
    dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.turbine)  // Flow testing
        implementation(libs.koin.test) // DI testing
    }
}

androidTest {
    dependencies {
        implementation(libs.sqldelight.android.driver)
        implementation(libs.kotlinx.coroutines.test)
    }
}
```

**androidApp test dependencies (already present):**
- `mockk` - Mocking for ViewModels
- `turbine` - StateFlow/SharedFlow assertions
- `truth` - Fluent assertions
- `espresso` - UI interactions
- `compose-ui-test` - Compose semantics testing

## Test Infrastructure Classes

| Class | Purpose |
|-------|---------|
| `TestDatabaseFactory` | Creates in-memory SQLDelight database |
| `FakeBleRepository` | Simulates BLE connection/responses |
| `FakePreferencesManager` | In-memory settings storage |
| `TestFixtures` | Pre-built Exercise, Workout, Routine objects |
| `TestCoroutineRule` | JUnit rule for coroutine test dispatcher |
| `ComposeTestRule` extensions | Helpers for finding Compose elements |

## Domain Model Tests

**Location:** `commonTest/domain/model/`

```kotlin
// ExerciseTest.kt
- resolveDefaultCableConfig() returns SINGLE for single-handle equipment
- resolveDefaultCableConfig() returns DOUBLE for bilateral equipment
- default muscleGroups equals muscleGroup for backward compatibility

// WorkoutStateTest.kt
- SetSummary calculates burnoutReps correctly
- ConnectionState sealed class equality works
- WorkoutMode.toWorkoutType() mappings are bidirectional

// PersonalRecordTest.kt
- volume calculation (weight × reps) is correct
- PRType distinguishes MAX_WEIGHT vs MAX_VOLUME

// RepCountTest.kt
- totalReps excludes warmupReps
- pendingRepProgress clamps to 0.0-1.0 range
```

## Use Case Tests

**Location:** `commonTest/domain/usecase/`

```kotlin
// RepCounterFromMachineTest.kt
- detects rep at position threshold crossing
- ignores micro-movements (noise filtering)
- distinguishes warmup vs working reps
- handles single-cable vs dual-cable exercises

// ProgressionUseCaseTest.kt
- calculates weight progression correctly
- respects min/max weight bounds (0-220kg)
- handles 0.5kg increment rounding

// TrendAnalysisUseCaseTest.kt
- calculates volume trends over time periods
- handles empty workout history gracefully
- identifies PR streaks correctly
```

## Repository Tests

**In-Memory Database Setup:**

```kotlin
// TestDatabaseFactory.kt
expect fun createTestDatabase(): VitruvianDatabase

// androidTest actual
actual fun createTestDatabase() =
    VitruvianDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

// iosTest actual
actual fun createTestDatabase() =
    VitruvianDatabase(NativeSqliteDriver(VitruvianDatabase.Schema, ":memory:"))
```

**Tests:** `commonTest/data/repository/`

```kotlin
// SqlDelightWorkoutRepositoryTest.kt
- insertSession() persists all fields correctly
- getSessionsByDateRange() filters correctly
- getRecentSessions() orders by timestamp DESC
- deleteSession() removes session and related metrics

// SqlDelightExerciseRepositoryTest.kt
- getAllExercises() returns library exercises
- toggleFavorite() updates isFavorite flag
- getExerciseById() returns null for unknown ID
- searchExercises() matches name and muscle group

// SqlDelightPersonalRecordRepositoryTest.kt
- savePR() inserts new record
- savePR() updates existing if new weight higher
- getPRsForExercise() returns both weight and volume PRs
- getAllPRs() orders by timestamp DESC

// SqlDelightGamificationRepositoryTest.kt
- earnBadge() inserts badge with timestamp
- getEarnedBadges() returns all user badges
- updateStats() increments counters correctly
```

## BLE Layer Tests

**Fake BLE Repository:**

```kotlin
// FakeBleRepository.kt
class FakeBleRepository : BleRepository {
    // Controllable state
    var connectionState = MutableStateFlow<ConnectionState>(Disconnected)
    var metricsToEmit = mutableListOf<WorkoutMetric>()
    var commandsReceived = mutableListOf<ByteArray>()

    // Simulate device responses
    fun simulateConnect(deviceName: String)
    fun simulateDisconnect()
    fun emitMetric(metric: WorkoutMetric)
    fun simulateDeloadWarning()
}
```

**Tests:** `commonTest/data/ble/`

```kotlin
// BleCommandBuilderTest.kt
- buildSetWeightCommand() encodes weight correctly (0.5kg increments)
- buildStartWorkoutCommand() sets correct mode byte (0x4F for Program)
- buildEchoModeCommand() encodes level and eccentric load (0x4E)
- buildStopCommand() produces correct stop sequence

// BleResponseParserTest.kt
- parseMetricFrame() extracts loadA, loadB, positionA, positionB
- parseMetricFrame() scales position by 10.0f (Issue #197)
- parseStatusFlags() detects deload warning (0x0040)
- parseStatusFlags() detects deload occurred (0x8000)
- parseDeviceName() identifies VFormTrainer vs TrainerPlus

// BleExtensionsTest.kt
- ByteArray.toHexString() formats correctly
- Float.toWeightBytes() handles edge cases (0, 220, negative)
```

## ViewModel Tests

**Location:** `androidApp/src/test/viewmodel/`

**Test Setup:**
```kotlin
// TestCoroutineRule.kt
class TestCoroutineRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

**Tests:**
```kotlin
// MainViewModelTest.kt
- initial state is Disconnected with empty metrics
- scan() emits Scanning then Connected on success
- scan() emits Error on timeout
- startWorkout() transitions Idle → Countdown → Active
- stopWorkout() calculates SetSummary correctly
- rep events trigger haptic feedback flow
- PR detection emits celebration event
- auto-reconnect attempts on unexpected disconnect

// ExerciseLibraryViewModelTest.kt
- loads exercises on init
- filterByMuscleGroup() updates filtered list
- searchQuery filters by name
- toggleFavorite() updates exercise state
- recently performed exercises appear first

// ExerciseConfigViewModelTest.kt
- loads last used weight for exercise
- validates weight within 0-220kg bounds
- calculates percentage of 1RM correctly
- saves configuration to preferences

// GamificationViewModelTest.kt
- loads earned badges on init
- calculates streak correctly
- unlocks badge when criteria met
- emits badge earned event for haptics
```

**Testing pattern with Turbine:**
```kotlin
@Test
fun `startWorkout transitions through states`() = runTest {
    viewModel.workoutState.test {
        assertEquals(WorkoutState.Idle, awaitItem())
        viewModel.startWorkout(params)
        assertEquals(WorkoutState.Countdown(3), awaitItem())
        // ... advance time, assert Active, etc.
    }
}
```

## End-to-End Tests

**Robot Pattern:** `androidApp/src/androidTest/robot/`

```kotlin
// ConnectionRobot.kt
class ConnectionRobot(private val rule: ComposeTestRule) {
    fun assertDisconnectedState() = apply {
        rule.onNodeWithText("Connect to Trainer").assertIsDisplayed()
    }
    fun tapConnect() = apply {
        rule.onNodeWithText("Connect").performClick()
    }
    fun assertConnected(deviceName: String) = apply {
        rule.onNodeWithText(deviceName).assertIsDisplayed()
    }
}

// WorkoutRobot.kt
class WorkoutRobot(private val rule: ComposeTestRule) {
    fun selectExercise(name: String) = apply { ... }
    fun setWeight(kg: Float) = apply { ... }
    fun tapStartWorkout() = apply { ... }
    fun assertWorkoutActive() = apply { ... }
    fun assertRepCount(count: Int) = apply { ... }
    fun tapStopWorkout() = apply { ... }
    fun assertSetSummaryDisplayed() = apply { ... }
}

// HistoryRobot.kt
class HistoryRobot(private val rule: ComposeTestRule) {
    fun navigateToHistory() = apply { ... }
    fun assertSessionVisible(exerciseName: String) = apply { ... }
    fun tapSession(exerciseName: String) = apply { ... }
    fun assertSessionDetails() = apply { ... }
}
```

**E2E Test Flows:** `androidApp/src/androidTest/e2e/`

```kotlin
// CompleteWorkoutFlowTest.kt
@Test
fun completeWorkoutAndViewHistory() {
    // Inject FakeBleRepository via test Koin module
    ConnectionRobot(rule)
        .assertDisconnectedState()
        .tapConnect()

    // Simulate BLE connection
    fakeBle.simulateConnect("Vee_Test123")

    ConnectionRobot(rule)
        .assertConnected("Vee_Test123")

    WorkoutRobot(rule)
        .selectExercise("Bench Press")
        .setWeight(50f)
        .tapStartWorkout()
        .assertWorkoutActive()

    // Simulate reps via fake
    repeat(10) { fakeBle.emitRepMetric() }

    WorkoutRobot(rule)
        .assertRepCount(10)
        .tapStopWorkout()
        .assertSetSummaryDisplayed()

    HistoryRobot(rule)
        .navigateToHistory()
        .assertSessionVisible("Bench Press")
}
```

## CI/CD Integration

**Gradle Test Tasks:**

```bash
# Run all shared module tests (JVM)
./gradlew :shared:testDebugUnitTest

# Run Android app unit tests (ViewModels)
./gradlew :androidApp:testDebugUnitTest

# Run Android instrumented tests (E2E)
./gradlew :androidApp:connectedDebugAndroidTest

# Run all tests
./gradlew test connectedAndroidTest

# Generate coverage report (add JaCoCo plugin)
./gradlew jacocoTestReport
```

**CI Pipeline Stages:**

| Stage | Command | Duration |
|-------|---------|----------|
| Unit Tests | `./gradlew testDebugUnitTest` | ~2 min |
| Instrumented Tests | `./gradlew connectedDebugAndroidTest` | ~5 min |
| Coverage Report | `./gradlew jacocoTestReport` | ~1 min |

**Test Organization Tags:**

```kotlin
@Tag("fast")      // Unit tests - run on every commit
@Tag("slow")      // Integration tests - run on PR
@Tag("e2e")       // E2E tests - run before release
```

## Test Count Summary

| Layer | Tests | Priority |
|-------|-------|----------|
| Domain Models | ~40 | P0 |
| Repositories | ~50 | P0 |
| BLE Layer | ~35 | P1 |
| ViewModels | ~60 | P0 |
| E2E Flows | ~15 | P1 |
| **Total** | **~200** | |

## Implementation Order

1. **Phase 1 - Infrastructure** (Foundation)
   - Test dependencies in build.gradle.kts
   - TestDatabaseFactory with expect/actual
   - FakeBleRepository
   - TestFixtures and TestCoroutineRule

2. **Phase 2 - Domain Layer** (P0)
   - Domain model tests
   - Use case tests

3. **Phase 3 - Data Layer** (P0)
   - Repository tests with in-memory DB

4. **Phase 4 - Presentation Layer** (P0)
   - ViewModel tests with Turbine

5. **Phase 5 - BLE Layer** (P1)
   - Command/response parsing tests

6. **Phase 6 - E2E Tests** (P1)
   - Robot classes
   - Full flow tests

7. **Phase 7 - CI/CD** (Final)
   - GitHub Actions workflow
   - Coverage reporting

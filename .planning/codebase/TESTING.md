# Testing Patterns

**Analysis Date:** 2026-02-13

## Test Framework

**Runner:**
- JUnit 4 (4.13.2)
- Config: No explicit config files - uses Gradle test source sets

**Assertion Library:**
- Google Truth (`com.google.common.truth:truth`) for fluent assertions
- Kotlin Test (`kotlin.test`) for multiplatform assertions: `assertEquals()`, `assertTrue()`, `assertNotNull()`

**Run Commands:**
```bash
./gradlew :androidApp:testDebugUnitTest       # Android app unit tests
./gradlew :shared:testDebugUnitTest           # Shared module Android unit tests
./gradlew :shared:iosArm64Test                # iOS tests (requires macOS)
./gradlew :androidApp:connectedAndroidTest    # Instrumented tests (requires device/emulator)
./gradlew test                                # All unit tests
```

## Test File Organization

**Location:**
- Unit tests: Co-located in test source sets
  - `androidApp/src/test/kotlin/` - Android app unit tests
  - `shared/src/androidUnitTest/kotlin/` - Shared module Android-specific tests
  - `shared/src/commonTest/kotlin/` - Shared module platform-agnostic tests
  - `shared/src/iosTest/kotlin/` - Shared module iOS-specific tests
- Instrumented/E2E tests: Separate androidTest source set
  - `androidApp/src/androidTest/kotlin/` - Compose UI and integration tests

**Naming:**
- Test files append `Test` suffix: `SqlDelightExerciseRepositoryTest.kt`, `ThemeViewModelTest.kt`
- E2E tests use descriptive names: `AppE2ETest.kt`, `WorkoutFlowE2ETest.kt`
- Test utilities in `testutil/` package: `FakeBleRepository.kt`, `TestCoroutineRule.kt`, `TestDatabaseFactory.kt`

**Structure:**
```
shared/src/
├── androidUnitTest/kotlin/com/devil/phoenixproject/
│   ├── data/repository/SqlDelightExerciseRepositoryTest.kt
│   ├── di/KoinModuleVerifyTest.kt
│   ├── e2e/WorkoutFlowE2ETest.kt
│   └── testutil/TestDatabaseFactory.android.kt
├── commonTest/kotlin/com/devil/phoenixproject/
│   └── util/BlePacketFactoryTest.kt
└── iosTest/kotlin/com/devil/phoenixproject/
    └── testutil/TestDatabaseFactory.ios.kt
```

## Test Structure

**Suite Organization:**
```kotlin
class SqlDelightExerciseRepositoryTest {
    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightExerciseRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightExerciseRepository(database, ExerciseImporter(database))
    }

    @Test
    fun `searchExercises filters by name and muscle group`() = runTest {
        insertExercise(id = "ex-1", name = "Bench Press", muscleGroup = "Chest")

        repository.searchExercises("bench").test {
            val results = awaitItem()
            assertEquals(1, results.size)
            assertEquals("Bench Press", results.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

**Patterns:**
- `@Before` setup creates fresh test instances
- `@After` teardown for cleanup (used in Koin tests: `stopKoin()`)
- Backtick test names with descriptive phrases: `` `complete connection flow - scan, connect, verify` ``
- `runTest` coroutine builder for suspend test functions
- No explicit `@After` cleanup for databases (garbage collected)

## Mocking

**Framework:**
- MockK (`io.mockk:mockk`) for Android unit tests
- MockK Android (`io.mockk:mockk-android`) for instrumented tests

**Patterns:**
- Prefer fakes over mocks for complex dependencies
- Fake repositories in `testutil/` package implement real interfaces
- Fakes use `MutableStateFlow` and `MutableSharedFlow` for controllable state
- No mocking of domain models - use real data classes

**Fake Repository Pattern:**
```kotlin
class FakeBleRepository : BleRepository {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val commandsReceived = mutableListOf<ByteArray>()
    var connectResult: Result<Unit> = Result.success(Unit)

    fun simulateConnect(deviceName: String) {
        _connectionState.value = ConnectionState.Connected(deviceName, "AA:BB:CC:DD:EE:FF")
    }
}
```

**What to Mock:**
- External services and hardware (BLE, file system, network)
- Platform-specific APIs (Android Context, iOS frameworks)

**What NOT to Mock:**
- Domain models (use real instances)
- Repositories (use fakes instead)
- Database (use in-memory SQLite)
- ViewModels (test real instances with fake dependencies)

## Fixtures and Factories

**Test Data:**
```kotlin
// Helper function for test data setup
private fun insertExercise(
    id: String,
    name: String,
    muscleGroup: String,
    equipment: String,
    defaultCableConfig: String = "DOUBLE",
    isFavorite: Long = 0L,
    isCustom: Long = 0L,
    oneRepMaxKg: Double? = null
) {
    database.vitruvianDatabaseQueries.insertExercise(
        id = id,
        name = name,
        // ... all other columns
    )
}
```

**Location:**
- Test helper functions as private methods in test classes
- Shared test utilities in `testutil/` package
- Database factory using expect/actual: `createTestDatabase()` in `TestDatabaseFactory.kt`

**Platform-Specific Test Utilities:**
```kotlin
// shared/src/androidUnitTest/kotlin/.../TestDatabaseFactory.android.kt
actual fun createTestDatabase(): VitruvianDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    VitruvianDatabase.Schema.create(driver)
    return VitruvianDatabase(driver)
}

// shared/src/iosTest/kotlin/.../TestDatabaseFactory.ios.kt
actual fun createTestDatabase(): VitruvianDatabase {
    val driver = NativeSqliteDriver(VitruvianDatabase.Schema, "test.db")
    return VitruvianDatabase(driver)
}
```

## Coverage

**Requirements:** No coverage threshold enforced

**View Coverage:**
```bash
./gradlew :shared:koverHtmlReport    # If Kover plugin added (not currently configured)
./gradlew :androidApp:jacocoTestReport  # If Jacoco configured (not detected)
```

**Current State:** No coverage tooling configured in build files

## Test Types

**Unit Tests:**
- Repository tests verify database operations in isolation
- ViewModel tests use fake repositories and test coroutine dispatchers
- Use case tests verify business logic with minimal dependencies
- Koin module verification: `appModule.verify(extraTypes = listOf(...))`

**Integration Tests:**
- E2E ViewModel tests wire up multiple real components
- Example: `WorkoutFlowE2ETest` uses real `MainViewModel` with all fake repositories

**E2E Tests:**
- Framework: Compose UI Test (`androidx.compose.ui:ui-test-junit4`)
- Uses `createAndroidComposeRule<ComponentActivity>()`
- Robot pattern for readable test scenarios
- Test entire app flows from UI interaction to state changes

**Example E2E Test:**
```kotlin
@Test
fun splashThenHomeContentAppears() {
    launchApp()

    composeRule.onNodeWithText("PROJECT PHOENIX").assertIsDisplayed()
    advancePastSplash()

    composeRule.onNodeWithText("Recent Activity").assertIsDisplayed()
    composeRule.onNodeWithText("Click to Connect").assertIsDisplayed()
}
```

## Common Patterns

**Async Testing:**
```kotlin
@Test
fun `repository query returns flow`() = runTest {
    repository.getAllExercises().test {
        val exercises = awaitItem()
        assertEquals(3, exercises.size)
        cancelAndIgnoreRemainingEvents()
    }
}
```
- Use `runTest` for coroutine-based tests
- Turbine library (`app.cash.turbine:turbine`) for Flow testing: `.test { }`
- `awaitItem()` to consume flow emissions
- `cancelAndIgnoreRemainingEvents()` for cleanup

**Error Testing:**
```kotlin
@Test
fun `connection error flow - handle gracefully`() = runTest {
    workoutRobot(viewModel, fakeBleRepository) {
        verifyDisconnected()

        // Simulate connection error
        simulateConnectionError("Bluetooth disabled")

        verifyErrorState()
        verifyErrorMessage("Bluetooth disabled")
    }
}
```
- Fakes provide controllable error injection
- Sealed class state verification: `ConnectionState.Error`

**Coroutine Testing:**
```kotlin
class TestCoroutineRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

// Usage in tests
@get:Rule
val testCoroutineRule = TestCoroutineRule()

@Test
fun `test viewModel coroutine`() = runTest {
    viewModel.doSomething()
    advanceUntilIdle()  // Process all pending coroutines
    assertEquals(expected, viewModel.state.value)
}
```
- `TestCoroutineRule` replaces `Dispatchers.Main` with `StandardTestDispatcher`
- `advanceUntilIdle()` processes all queued coroutines
- `advanceTimeBy(ms)` for time-based testing

**Compose UI Testing:**
```kotlin
@get:Rule
val composeRule = createAndroidComposeRule<ComponentActivity>()

@Test
fun bottomNavNavigatesToSettings() {
    launchApp()
    advancePastSplash()

    composeRule.onNode(hasText("Settings") and hasClickAction()).performClick()
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Like My Work?").assertIsDisplayed()
}
```
- Manual clock control: `composeRule.mainClock.autoAdvance = false`
- Time advancement for animations: `mainClock.advanceTimeBy(ms)`
- Semantic matchers: `hasText()`, `hasClickAction()`, `assertIsDisplayed()`

**Robot Pattern:**
```kotlin
class WorkoutRobot(
    private val viewModel: MainViewModel,
    private val fakeBleRepository: FakeBleRepository
) {
    fun verifyDisconnected() {
        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
    }

    suspend fun connectToDevice(deviceName: String) {
        fakeBleRepository.simulateConnect(deviceName)
    }
}

fun workoutRobot(
    viewModel: MainViewModel,
    fakeBleRepository: FakeBleRepository,
    block: suspend WorkoutRobot.() -> Unit
): WorkoutRobot {
    return WorkoutRobot(viewModel, fakeBleRepository).apply { runBlocking { block() } }
}

// Usage
@Test
fun `complete connection flow`() = runTest {
    workoutRobot(viewModel, fakeBleRepository) {
        verifyDisconnected()
        connectToDevice("Vee_MyTrainer")
        verifyConnected()
    }
}
```

**Koin Testing:**
```kotlin
@Test
fun verifyAppModule() {
    appModule.verify(
        extraTypes = listOf(
            DriverFactory::class,
            Settings::class,
            BleRepository::class,
            Function0::class  // Lambda types in constructors
        )
    )
}

// Integration test setup
@Before
fun setUp() {
    stopKoin()
    startKoin {
        androidContext(ApplicationProvider.getApplicationContext())
        allowOverride(true)
        modules(appModule, platformModule, testModule)
    }
}

@After
fun tearDown() {
    stopKoin()
}
```
- Module verification catches DI configuration errors at compile time
- Test modules override production bindings for fakes
- Always `stopKoin()` in teardown to prevent state leakage

## Dependencies

**Test Libraries:**
```kotlin
// Unit Testing
testImplementation("junit:junit:4.13.2")
testImplementation("io.mockk:mockk")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
testImplementation("app.cash.turbine:turbine")
testImplementation("com.google.truth:truth")
testImplementation("io.insert-koin:koin-test")
testImplementation("io.insert-koin:koin-test-junit4")

// Instrumented Testing
androidTestImplementation("androidx.test.ext:junit")
androidTestImplementation("androidx.test.espresso:espresso-core")
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("io.mockk:mockk-android")
androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
androidTestImplementation("com.google.truth:truth")
```

---

*Testing analysis: 2026-02-13*

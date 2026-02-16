# Coding Conventions

**Analysis Date:** 2026-02-13

## Naming Patterns

**Files:**
- Kotlin files use PascalCase matching the primary class: `ThemeViewModel.kt`, `BleExceptions.kt`, `PlatformUtils.kt`
- Compose UI components use descriptive names: `CircularForceGauge.kt`, `ConnectionStatusBanner.kt`
- Test files append `Test`: `SqlDelightExerciseRepositoryTest.kt`, `KoinModuleVerifyTest.kt`
- Fake implementations for testing use `Fake` prefix: `FakeBleRepository.kt`, `FakeExerciseRepository.kt`

**Functions:**
- Use camelCase: `currentTimeMillis()`, `toggleFavorite()`, `searchExercises()`
- Boolean functions start with `is/has/should`: `isFavorite`, `hasCableAccessory`, `shouldFailConnect`
- Suspending functions don't use special naming - rely on `suspend` keyword
- Factory functions match return type: `createTestDatabase()`, `mapToExercise()`

**Variables:**
- Private backing fields use underscore prefix: `_connectionState`, `_themeMode`, `_metricsFlow`
- Public exposed properties omit underscore: `connectionState`, `themeMode`, `metricsFlow`
- Constants use UPPER_SNAKE_CASE: `THEME_MODE_KEY`, `SPLASH_DURATION_MS`, `CABLE_ACCESSORIES`
- Parameters and locals use camelCase: `deviceName`, `exerciseId`, `muscleGroup`

**Types:**
- Data classes use PascalCase: `Exercise`, `PersonalRecord`, `WorkoutMetric`
- Sealed classes use PascalCase with nested objects/classes: `ConnectionState.Disconnected`, `WorkoutState.Active`
- Enums use PascalCase with SCREAMING_SNAKE_CASE values: `PRType.MAX_WEIGHT`, `SetType.WORKING_SET`
- Interfaces use noun form: `BleRepository`, `ExerciseRepository`, `PreferencesManager`

## Code Style

**Formatting:**
- Tool: Kotlin official code style (configured via `kotlin.code.style=official` in `gradle.properties`)
- No explicit linter config (no detekt.yml, ktlint, or .editorconfig detected)
- Indentation: 4 spaces (standard Kotlin)
- Line length: Not enforced by tooling, but code observed stays under 120 chars

**Linting:**
- No automated linter detected in build files
- Code follows Kotlin conventions by manual adherence
- `@Suppress` annotations used selectively: `@Suppress("unused")` on test utilities

## Import Organization

**Order:**
1. Android/Compose imports: `androidx.compose.*`, `androidx.lifecycle.*`, `androidx.test.*`
2. Third-party libraries: `app.cash.sqldelight.*`, `co.touchlab.kermit.*`, `org.koin.*`
3. Project imports: `com.devil.phoenixproject.*`
4. Kotlin standard library: `kotlinx.coroutines.*`, `kotlinx.serialization.*`

**Path Aliases:**
- Not used - all imports use full package paths
- No Gradle type-safe accessors for custom modules (uses standard `project(":shared")`)

## Error Handling

**Patterns:**
- Custom exception hierarchy in `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleExceptions.kt`:
  - `BluetoothDisabledException` - BT disabled
  - `ConnectionLostException` - connection dropped
  - `GattRequestRejectedException` - GATT operation rejected
  - `ScanFailedException` - scan failure with error code
- Exceptions include descriptive messages and optional `cause: Throwable?`
- Repository methods return `Result<T>` for expected failures: `repository.createCustomExercise(...): Result<Exercise>`
- Flow-based state for long-running operations: `connectionState: StateFlow<ConnectionState>` with `ConnectionState.Error`
- Documentation comments explain when exceptions are thrown

**Example:**
```kotlin
class GattStatusException(
    message: String,
    val gattStatus: Int,
    cause: Throwable? = null
) : Exception("$message (GATT status: $gattStatus)", cause)
```

## Logging

**Framework:** Kermit (multiplatform logging library)

**Patterns:**
- Logger instances use `Logger.withTag("ClassName")` pattern
- Logging in ViewModels and repositories: `private val log = Logger.withTag("ThemeViewModel")`
- Log levels observed: `log.d { }` (debug), `log.w { }` (warning)
- Lazy message evaluation using lambda syntax: `log.d { "Theme mode changed to: $mode" }`

**Example:**
```kotlin
class ThemeViewModel(private val settings: Settings) : ViewModel() {
    private val log = Logger.withTag("ThemeViewModel")

    fun setThemeMode(mode: ThemeMode) {
        log.d { "Theme mode changed to: $mode" }
    }
}
```

## Comments

**When to Comment:**
- File-level KDocs for complex domain models explaining business logic
- Class-level comments for repositories, exceptions, and ViewModels
- Inline comments for non-obvious expect/actual patterns and KMP quirks
- Migration notes in data classes: "MIGRATION NOTE: This was converted from an enum to a data class"
- Hardware constraints: "NOTES: Vitruvian cables only pull UPWARD from floor platform"

**JSDoc/KDoc:**
- Used extensively on public APIs and domain models
- Parameter descriptions for non-obvious meanings
- `@param` tags rarely used - prefer descriptive parameter names
- Return value documentation when not obvious from type

**Example:**
```kotlin
/**
 * Exercise model - represents any exercise performed on the Vitruvian Trainer
 *
 * MIGRATION NOTE: Converted from enum to data class to support 100+ exercises
 *
 * NOTES:
 * - Vitruvian cables only pull UPWARD from floor platform
 * - Compatible: Rows, presses, curls, squats, deadlifts, raises
 */
data class Exercise(...)
```

## Function Design

**Size:**
- ViewModels: 20-50 lines per function (business logic orchestration)
- Repositories: 5-15 lines per function (single database operations)
- Mappers: 10-30 lines (parameter-heavy due to SQLDelight column mapping)

**Parameters:**
- Prefer named parameters for 3+ arguments
- Use default values for optional parameters: `cause: Throwable? = null`
- SQLDelight mappers receive all columns as individual parameters (not data objects)
- Suspend functions take `Dispatchers.IO` context internally, not as parameter

**Return Values:**
- `Flow<T>` for streams: `getAllExercises(): Flow<List<Exercise>>`
- `StateFlow<T>` for single-value state: `connectionState: StateFlow<ConnectionState>`
- `suspend fun` returns direct value or `Result<T>`: `suspend fun getExerciseById(id: String): Exercise?`
- Nullable returns for "not found" cases: `getExerciseById()` returns `Exercise?`

## Module Design

**Exports:**
- Repositories expose interfaces in commonMain, implementations in platform source sets
- ViewModels extend `androidx.lifecycle.ViewModel` in commonMain (multiplatform)
- Domain models are simple data classes with no platform dependencies
- Expect/actual for platform APIs: `expect fun currentTimeMillis(): Long`

**Barrel Files:**
- Not used - each file explicitly imports what it needs
- No index.kt or re-export patterns

## Multiplatform Patterns

**expect/actual:**
- Defined in same package across source sets: `domain/model/PlatformUtils.kt` (common), `domain/model/PlatformUtils.android.kt` (android)
- No imports needed for same-package expect/actual - visibility is automatic
- Common pattern for platform utilities: `currentTimeMillis()`, `generateUUID()`, database drivers

**Dispatchers.IO:**
- `import kotlinx.coroutines.IO` is VALID in KMP commonMain (extension property)
- Used consistently for database operations: `queries.selectAllExercises(...).asFlow().mapToList(Dispatchers.IO)`

**Thread Safety:**
- Use `synchronized(lock) {}` with `private val lock = Any()` for KMP-compatible locking
- Avoid holding locks across suspend calls - use flag patterns instead

## Dependency Injection

**Framework:** Koin (multiplatform DI)

**Patterns:**
- Modules defined in `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/`
- ViewModels use `koinViewModel()` in Compose: `val viewModel: ThemeViewModel = koinViewModel()`
- Repositories injected via constructor: `class ThemeViewModel(private val settings: Settings)`
- Test modules override production bindings: `startKoin { allowOverride(true); modules(appModule, testModule) }`
- Module verification via Koin test API: `appModule.verify(extraTypes = listOf(...))`

**Example:**
```kotlin
val presentationModule = module {
    viewModel { ThemeViewModel(get()) }
    viewModel { MainViewModel(get(), get(), get(), ...) }
}
```

## Coroutines & Flow

**Usage:**
- ViewModels use `viewModelScope` for lifecycle-aware coroutines
- Repositories use `withContext(Dispatchers.IO)` for database operations
- StateFlow for single-value state with `.stateIn()` for derived flows
- SharedFlow for events without replay: `MutableSharedFlow<RepNotification>(replay = 0)`

**Patterns:**
```kotlin
// ViewModel state
private val _themeMode = MutableStateFlow(loadThemePreference())
val themeMode: StateFlow<ThemeMode> = _themeMode
    .stateIn(viewModelScope, SharingStarted.Eagerly, _themeMode.value)

// Repository query
override fun getAllExercises(): Flow<List<Exercise>> {
    return queries.selectAllExercises(::mapToExercise)
        .asFlow()
        .mapToList(Dispatchers.IO)
}
```

## Database Conventions

**SQLDelight:**
- Schema at `shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq`
- Mappers receive ALL columns as parameters (even if domain model uses subset)
- Use named mapper functions: `::mapToExercise` passed to queries
- Boolean stored as `Long` (0/1): `isFavorite: Long` mapped to `isFavorite == 1L`
- Timestamps as `Long` (epoch millis)

**Migration:**
- Version tracked in `shared/build.gradle.kts`: `version = 11` (initial + 10 migrations)
- UPDATE query changes are safe, CREATE TABLE changes require iOS DriverFactory sync

---

*Convention analysis: 2026-02-13*

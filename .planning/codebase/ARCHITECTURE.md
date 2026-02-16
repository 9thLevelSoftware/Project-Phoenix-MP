# Architecture

**Analysis Date:** 2026-02-13

## Pattern Overview

**Overall:** Kotlin Multiplatform Clean Architecture with platform-specific implementations

**Key Characteristics:**
- Strict separation between cross-platform business logic (commonMain) and platform implementations (androidMain/iosMain)
- Repository pattern with interface-based abstraction for platform-specific features (BLE, database drivers, file I/O)
- Unidirectional data flow using Kotlin Flow and StateFlow for reactive state management
- Dependency injection via Koin with modular organization (data, domain, presentation, sync modules)
- expect/actual mechanism for platform-specific implementations without compromising shared code

## Layers

**Domain Layer:**
- Purpose: Platform-agnostic business logic and models
- Location: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/`
- Contains: Domain models (`model/`), use cases (`usecase/`), subscription interfaces
- Depends on: Nothing (pure Kotlin, no platform dependencies)
- Used by: Presentation layer ViewModels, Repository implementations

**Data Layer:**
- Purpose: Data access, storage, and external communication
- Location: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/`
- Contains: Repository interfaces and implementations, BLE abstractions, local database (SQLDelight), sync logic, preferences
- Depends on: Domain models, platform-specific drivers (via expect/actual)
- Used by: Domain use cases, Presentation ViewModels (via Koin injection)

**Presentation Layer:**
- Purpose: UI state management and user interactions
- Location: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/`
- Contains: ViewModels (state holders), Compose UI screens and components, navigation, presentation managers (BleConnectionManager, WorkoutSessionManager, HistoryManager, SettingsManager, GamificationManager)
- Depends on: Domain models, Data repositories
- Used by: Platform applications (androidApp, iosApp)

**Platform Layer:**
- Purpose: Platform-specific implementations and application entry points
- Location: `shared/src/androidMain/` and `shared/src/iosMain/` for implementations; `androidApp/` and `iosApp/` for application shells
- Contains: Platform-specific BLE implementations, database drivers, file I/O, dependency injection modules
- Depends on: Shared module, platform SDKs
- Used by: Platform runtime environments

## Data Flow

**BLE Workout Data Flow:**

1. User initiates workout via Compose UI (`presentation/screen/ActiveWorkoutScreen.kt`, `WorkoutHud.kt`)
2. ViewModel (`MainViewModel`) delegates to `WorkoutSessionManager` which calls `BleRepository.startWorkout()`
3. Platform-specific BLE implementation (`KableBleRepository` in commonMain using Kable library) sends command to Vitruvian device
4. BLE device streams metrics via Nordic UART Service (TX/RX characteristics)
5. `BleRepository` parses incoming packets into `WorkoutMetric` domain models
6. Metrics flow through `StateFlow<WorkoutMetric?>` to ViewModels
7. UI observes StateFlow and recomposes to display real-time data
8. On workout completion, `WorkoutSessionManager` persists `WorkoutSession` to SQLDelight database via `WorkoutRepository`

**Database Sync Flow:**

1. `SyncTriggerManager` detects trigger (app foreground, post-workout, manual sync)
2. `SyncService` checks authentication via `AuthRepository`
3. If authenticated, `SyncService` calls `SyncRepository.performSync()`
4. `SyncRepository` queries local SQLDelight database for records with `updatedAt` > `lastSyncTime`
5. Records serialized to JSON and sent to backend via Ktor HTTP client
6. Backend response contains server-side changes (new/updated records with `serverId` and `updatedAt`)
7. `SyncRepository` upserts server records into local database, resolving conflicts by `updatedAt` timestamp
8. Repositories emit updated Flow streams to UI

**State Management:**
- ViewModels hold state in `MutableStateFlow`, exposed as `StateFlow` to UI
- Manager classes (`WorkoutSessionManager`, `BleConnectionManager`, `HistoryManager`) encapsulate complex state machines and expose flows
- UI layers collect flows using `collectAsState()` in Compose, triggering recomposition on state changes

## Key Abstractions

**BleRepository (Platform Abstraction):**
- Purpose: Abstract Bluetooth Low Energy communication across Android and iOS
- Examples: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt` (interface), `KableBleRepository.kt` (Kable-based implementation), `SimulatorBleRepository.kt` (simulator for testing)
- Pattern: Repository interface with platform-specific factory in `platformModule`

**WorkoutSessionManager (State Coordinator):**
- Purpose: Orchestrate workout lifecycle, rep counting, PR detection, set summaries, and routine flow
- Examples: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Pattern: Manager class with internal state machines, exposes StateFlows for UI consumption, coordinates between BLE, repositories, and gamification

**SQLDelight Database (Type-Safe Persistence):**
- Purpose: Cross-platform type-safe SQL database with compile-time query validation
- Examples: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` (schema), generated Kotlin queries in `build/generated/sqldelight/`
- Pattern: SQL schema compiled to Kotlin code with suspend-based coroutine APIs, platform-specific drivers (AndroidSqliteDriver, NativeSqlDriver)

**Koin Modules (Dependency Graph):**
- Purpose: Dependency injection with modular organization
- Examples: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`, `PresentationModule.kt`, `DomainModule.kt`, `SyncModule.kt`, `platformModule` (expect/actual)
- Pattern: Functional DSL modules combined into `appModule`, platform-specific dependencies provided by `platformModule`

**expect/actual (Platform Dispatch):**
- Purpose: Declare platform-specific requirements in common code, provide actual implementations per platform
- Examples: `Platform.kt` (UUID generation, current time), `DriverFactory` (SQLDelight drivers), `platformModule` (Koin DI)
- Pattern: `expect fun/class` in commonMain, `actual fun/class` in androidMain/iosMain with same-package visibility (no imports needed)

## Entry Points

**Android Entry Point:**
- Location: `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`
- Triggers: Android Activity lifecycle (onCreate)
- Responsibilities: Initialize Koin DI, register Activity for context access, set Compose content with `RequireBlePermissions` wrapper, call `App()` composable from shared module

**iOS Entry Point:**
- Location: `iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift`
- Triggers: SwiftUI App lifecycle
- Responsibilities: Call `KoinKt.doInitKoin()` to initialize Koin DI, call `KoinKt.runMigrations()` for database migrations, instantiate `App()` composable from shared module

**Shared App Composable:**
- Location: `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`
- Triggers: Called by platform entry points
- Responsibilities: Initialize ViewModels via Koin, set up lifecycle observers, manage app-wide state (EULA acceptance, theme mode, splash screen), render `EnhancedMainScreen` with navigation

**Koin Initialization:**
- Location: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt`
- Triggers: Called explicitly from MainActivity (Android) or doInitKoin() (iOS)
- Responsibilities: Start Koin DI container, load `appModule` and `platformModule`, run `MigrationManager.checkAndRunMigrations()`

## Error Handling

**Strategy:** Typed error states with sealed classes and Flow-based error propagation

**Patterns:**
- BLE errors: `ConnectionState.Error(message, throwable?)` sealed class variant, emitted via `MutableStateFlow<ConnectionState>`
- Workout errors: `WorkoutState.Error(message)` sealed class variant, displayed in UI as dialogs or banners
- Repository errors: Exceptions caught in ViewModel coroutine scopes, converted to user-facing messages, emitted via `MutableStateFlow<String?>`
- Database errors: SQLDelight throws on constraint violations, caught in repository `try/catch`, logged via Kermit logger
- Network errors: Ktor client exceptions caught in `SyncRepository`, retry logic with exponential backoff, sync status exposed via `StateFlow<SyncStatus>`

## Cross-Cutting Concerns

**Logging:** Kermit multiplatform logger (`co.touchlab.kermit.Logger`) with platform-specific outputs (Logcat on Android, NSLog on iOS), centralized usage via static `Logger.i/d/w/e` calls

**Validation:** Domain model validation in use cases (e.g., weight range 0-220kg enforced in `WorkoutParameters`, rep counts validated before BLE commands), input validation in ViewModels before repository calls

**Authentication:** `AuthRepository` abstracts auth provider (PortalAuthRepository implementation), JWT tokens stored in multiplatform Settings (SharedPreferences on Android, UserDefaults on iOS), token refresh handled by `AuthService`

---

*Architecture analysis: 2026-02-13*

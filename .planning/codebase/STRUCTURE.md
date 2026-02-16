# Codebase Structure

**Analysis Date:** 2026-02-13

## Directory Layout

```
Project-Phoenix-MP/
├── shared/                          # Kotlin Multiplatform shared module
│   ├── src/
│   │   ├── commonMain/              # Cross-platform business logic
│   │   │   ├── kotlin/              # Shared Kotlin code
│   │   │   ├── sqldelight/          # Database schema (SQLDelight)
│   │   │   └── composeResources/    # Shared assets (images, Lottie files)
│   │   ├── androidMain/             # Android platform implementations
│   │   ├── iosMain/                 # iOS platform implementations
│   │   ├── commonTest/              # Shared unit tests
│   │   ├── androidUnitTest/         # Android-specific unit tests
│   │   └── iosTest/                 # iOS-specific tests
│   └── build.gradle.kts             # Shared module build configuration
├── androidApp/                      # Android application module
│   ├── src/
│   │   ├── main/                    # Android app entry point
│   │   ├── test/                    # Android unit tests
│   │   └── androidTest/             # Android instrumented tests (E2E)
│   └── build.gradle.kts             # Android app build configuration
├── iosApp/                          # iOS application module
│   └── VitruvianPhoenix/            # Xcode project
│       ├── VitruvianPhoenix/        # iOS app entry point (SwiftUI)
│       └── VitruvianPhoenix.xcodeproj
├── .planning/                       # GSD planning and codebase docs
│   ├── codebase/                    # Codebase analysis documents
│   ├── plans/                       # Execution plans
│   └── specs/                       # Change proposals
├── docs/                            # Project documentation
├── openspec/                        # OpenSpec change management
├── build.gradle.kts                 # Root build configuration
├── settings.gradle.kts              # Gradle module configuration
└── gradle/                          # Gradle wrapper and version catalogs
```

## Directory Purposes

**shared/src/commonMain/kotlin/com/devil/phoenixproject:**
- Purpose: Platform-agnostic business logic, UI, and data access
- Contains: Domain models, repositories, ViewModels, Compose UI, use cases
- Key files: `App.kt` (app entry point), `di/KoinInit.kt` (DI setup)

**shared/src/commonMain/kotlin/com/devil/phoenixproject/domain:**
- Purpose: Business domain models and logic
- Contains: `model/` (domain models like `WorkoutSession`, `Exercise`, `ConnectionState`), `usecase/` (business logic like `RepCounterFromMachine`), `subscription/` (premium features interface)
- Key files: `model/Models.kt` (core workout models), `model/Exercise.kt`, `model/Routine.kt`

**shared/src/commonMain/kotlin/com/devil/phoenixproject/data:**
- Purpose: Data access layer with repository pattern
- Contains: `repository/` (data sources), `ble/` (Bluetooth abstractions), `local/` (database factory), `sync/` (cloud sync), `preferences/` (settings), `migration/` (data migrations)
- Key files: `repository/BleRepository.kt`, `repository/WorkoutRepository.kt`, `repository/ExerciseRepository.kt`, `local/DatabaseFactory.kt`

**shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation:**
- Purpose: UI layer with ViewModels and Compose screens
- Contains: `viewmodel/` (state management), `screen/` (Compose screens), `components/` (reusable UI), `navigation/` (NavHost setup), `manager/` (presentation logic coordinators), `util/` (UI helpers)
- Key files: `viewmodel/MainViewModel.kt`, `screen/EnhancedMainScreen.kt`, `manager/DefaultWorkoutSessionManager.kt`, `navigation/NavGraph.kt`

**shared/src/commonMain/kotlin/com/devil/phoenixproject/di:**
- Purpose: Dependency injection module definitions
- Contains: Koin module DSL files
- Key files: `AppModule.kt` (aggregates all modules), `DataModule.kt`, `DomainModule.kt`, `PresentationModule.kt`, `SyncModule.kt`, `KoinInit.kt` (initialization)

**shared/src/commonMain/sqldelight/com/devil/phoenixproject/database:**
- Purpose: SQLDelight database schema
- Contains: `.sq` files with SQL DDL and queries
- Key files: `VitruvianDatabase.sq` (main schema with CREATE TABLE, queries), `migrations/*.sqm` (migration files)

**shared/src/androidMain/kotlin/com/devil/phoenixproject:**
- Purpose: Android-specific implementations (expect/actual pattern)
- Contains: Platform-specific code for BLE, database drivers, file I/O, DI modules
- Key files: `di/PlatformModule.android.kt`, `data/local/DriverFactory.kt` (AndroidSqliteDriver), `util/AndroidCsvExporter.kt`

**shared/src/iosMain/kotlin/com/devil/phoenixproject:**
- Purpose: iOS-specific implementations (expect/actual pattern)
- Contains: Platform-specific code for database drivers, file I/O, DI modules
- Key files: `di/PlatformModule.ios.kt`, `data/local/DriverFactory.ios.kt` (NativeSqlDriver)

**androidApp/src/main/kotlin/com/devil/phoenixproject:**
- Purpose: Android application entry point
- Contains: MainActivity, Android-specific services, UI theme, BLE permission handling
- Key files: `MainActivity.kt`, `ui/theme/Theme.kt`, `service/WorkoutService.kt`

**androidApp/src/androidTest/kotlin/com/devil/phoenixproject:**
- Purpose: Android instrumented E2E tests
- Contains: UI tests using Compose testing framework, test utilities, robot pattern helpers
- Key files: `e2e/AppE2ETest.kt`, `robot/WorkoutRobot.kt`, `testutil/FakeExerciseRepository.kt`

**iosApp/VitruvianPhoenix/VitruvianPhoenix:**
- Purpose: iOS application entry point
- Contains: SwiftUI app structure, Info.plist, Assets
- Key files: `ContentView.swift` (calls shared App composable), `VitruvianPhoenixApp.swift`

**.planning/codebase:**
- Purpose: GSD-generated codebase analysis documents
- Contains: ARCHITECTURE.md, STRUCTURE.md, STACK.md, CONVENTIONS.md, TESTING.md, INTEGRATIONS.md, CONCERNS.md
- Key files: Referenced by `/gsd:plan-phase` and `/gsd:execute-phase` commands

**docs/plans:**
- Purpose: Historical implementation plans and phase documentation
- Contains: Markdown files documenting completed phases, migration guides
- Key files: Phase execution logs, architectural decision records

**openspec:**
- Purpose: OpenSpec change proposal system
- Contains: Change specs with RFC-style proposals
- Key files: `AGENTS.md` (OpenSpec agent instructions)

## Key File Locations

**Entry Points:**
- `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`: Android app entry
- `iosApp/VitruvianPhoenix/VitruvianPhoenix/ContentView.swift`: iOS app entry
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`: Shared app composable

**Configuration:**
- `build.gradle.kts`: Root project build config
- `settings.gradle.kts`: Module includes and repository configuration
- `shared/build.gradle.kts`: Shared module config (KMP source sets, dependencies)
- `androidApp/build.gradle.kts`: Android app config (min SDK 26, target SDK 36)
- `gradle/libs.versions.toml`: Version catalog for dependencies
- `.mcp.json`: MCP tool configuration (Daem0n, Vitruvian)

**Core Logic:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`: Core domain models
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`: Primary ViewModel
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`: BLE implementation
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`: Workout state machine

**Testing:**
- `shared/src/commonTest/kotlin/`: Shared unit tests
- `shared/src/androidUnitTest/kotlin/`: Android-specific unit tests
- `androidApp/src/androidTest/kotlin/`: Android E2E tests

## Naming Conventions

**Files:**
- Domain models: PascalCase singular (e.g., `Exercise.kt`, `Routine.kt`, `Models.kt` for collections)
- Repositories: PascalCase with `Repository` suffix (e.g., `WorkoutRepository.kt`, `SqlDelightWorkoutRepository.kt`)
- ViewModels: PascalCase with `ViewModel` suffix (e.g., `MainViewModel.kt`, `GamificationViewModel.kt`)
- Screens: PascalCase with `Screen` suffix (e.g., `ActiveWorkoutScreen.kt`, `JustLiftScreen.kt`)
- Components: PascalCase descriptive (e.g., `PRCelebrationAnimation.kt`, `ConnectionLostDialog.kt`)
- Managers: PascalCase with `Manager` suffix (e.g., `WorkoutSessionManager.kt`, `HistoryManager.kt`)
- Platform-specific: Same name as expect declaration with `.android.kt` or `.ios.kt` suffix (e.g., `Platform.android.kt`, `DriverFactory.ios.kt`)

**Directories:**
- Lowercase with underscores for multi-word (rare) or camelCase for Kotlin packages (standard)
- Package structure mirrors domain organization: `data/repository/`, `domain/model/`, `presentation/viewmodel/`

## Where to Add New Code

**New Feature:**
- Primary code: `shared/src/commonMain/kotlin/com/devil/phoenixproject/` (choose layer: `domain/`, `data/`, or `presentation/`)
- Tests: `shared/src/commonTest/kotlin/com/devil/phoenixproject/` (mirrors source structure)

**New Domain Model:**
- Implementation: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/` (add to existing file or create new file)
- Pattern: Data classes with default values, sealed classes for state variants

**New Repository:**
- Interface: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/[Name]Repository.kt`
- Implementation: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelight[Name]Repository.kt`
- Registration: Add to `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`

**New ViewModel:**
- Implementation: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/[Name]ViewModel.kt`
- Registration: Add to `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt`
- Pattern: Extend `ViewModel`, use `StateFlow` for state, `SharedFlow` for events

**New Screen:**
- Implementation: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/[Name]Screen.kt`
- Navigation: Add route to `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`
- NavGraph: Add composable to `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`

**New Reusable Component:**
- Implementation: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/[Name].kt`
- Pattern: `@Composable` functions with preview annotations, extract complex state to remember blocks

**New Database Table:**
- Schema: Add `CREATE TABLE` to `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- Migration: Create `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/[version].sqm`
- Increment version in `shared/build.gradle.kts` (sqldelight.databases.create block)
- iOS: Sync schema changes to `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt` (see Daem0n warning #155)

**New Platform-Specific Code:**
- Declare: `expect fun/class` in `shared/src/commonMain/kotlin/com/devil/phoenixproject/[package]/[Name].kt`
- Implement Android: `actual fun/class` in `shared/src/androidMain/kotlin/com/devil/phoenixproject/[package]/[Name].android.kt`
- Implement iOS: `actual fun/class` in `shared/src/iosMain/kotlin/com/devil/phoenixproject/[package]/[Name].ios.kt`
- Pattern: Same-package visibility means no imports needed between expect/actual

**Utilities:**
- Shared helpers: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/` (e.g., `Constants.kt`, `PlatformUtils.kt`)
- Platform-specific helpers: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/` or `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/`

## Special Directories

**build/**
- Purpose: Gradle build outputs (compiled code, generated sources, APKs)
- Generated: Yes (by Gradle)
- Committed: No (in .gitignore)

**shared/build/generated/sqldelight/**
- Purpose: SQLDelight-generated Kotlin query code
- Generated: Yes (by SQLDelight Gradle plugin)
- Committed: No (regenerated on each build)

**.gradle/**
- Purpose: Gradle cache and build metadata
- Generated: Yes (by Gradle)
- Committed: No (in .gitignore)

**.idea/**
- Purpose: IntelliJ IDEA project settings
- Generated: Yes (by IDE)
- Committed: Partially (some files committed, others in .gitignore)

**gradle/wrapper/**
- Purpose: Gradle wrapper JAR and properties
- Generated: No (versioned with project)
- Committed: Yes (ensures consistent Gradle version)

**.planning/**
- Purpose: GSD planning artifacts (specs, plans, agent history, codebase docs)
- Generated: Yes (by GSD commands)
- Committed: No (local planning state, excluded from version control)

**.daem0nmcp/**
- Purpose: Daem0n MCP memory store (graph database, embeddings, triggers)
- Generated: Yes (by Daem0n MCP server)
- Committed: No (local memory state per project)

**.tmp_parent_repo/**
- Purpose: Temporary reference to parent monolithic repo for migration reference
- Generated: Manually (cloned from upstream)
- Committed: No (in .gitignore)

**shared/src/commonMain/composeResources/**
- Purpose: Shared multiplatform assets (images, Lottie animations)
- Generated: No (manually added)
- Committed: Yes (part of shared module resources)

---

*Structure analysis: 2026-02-13*

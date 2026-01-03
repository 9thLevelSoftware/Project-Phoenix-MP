BEFORE ANYTHING ELSE!!!  Look at the parent repo to see how something was implemented in a working fashion before trying to troubleshoot or make changes

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines via BLE. Community rescue project to keep machines functional after company bankruptcy.

## Build Commands

```bash
# Android
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug

# iOS framework (requires macOS)
./gradlew :shared:assembleXCFramework

# Full build
./gradlew build

# Clean
./gradlew clean

# Run tests
./gradlew :androidApp:testDebugUnitTest       # Android unit tests
```

## Architecture

### Module Structure
- **shared/** - Kotlin Multiplatform library with business logic
- **androidApp/** - Android application (Compose, Min SDK 26)
- **iosApp/** - iOS application (SwiftUI + shared framework)

### Shared Module Source Sets
```
shared/src/
├── commonMain/     # Cross-platform code (domain models, interfaces, database)
├── androidMain/    # Android implementations (Nordic BLE, Android SQLite driver)
└── iosMain/        # iOS implementations (Native SQLite driver)
```

### Key Patterns
- **expect/actual** for platform-specific implementations (see `Platform.kt`)
- **Clean Architecture**: domain models in `domain/model/`, data interfaces in `data/ble/`
- **Koin** for dependency injection
- **SQLDelight** for type-safe multiplatform database
- **Coroutines + Flow** for async operations and reactive streams

### BLE Architecture
Nordic UART Service UUIDs in `BleInterfaces.kt`:
- Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- TX: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- RX: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

Device names start with `Vee_` (V-Form) or `VIT` (Trainer+).

### Database Schema
SQLDelight schema at `shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq`:
- **WorkoutSession** - Exercise sessions with mode, weight, reps
- **MetricSample** - Real-time metrics (position, velocity, load, power)
- **PersonalRecord** - PR tracking with 1RM calculations
- **Routine/RoutineExercise** - Custom workout routines

### Domain Models
Located in `shared/src/commonMain/kotlin/com/example/vitruvianredux/domain/model/`:
- **WorkoutModels.kt**: WorkoutMode (6 modes), ConnectionState, WorkoutMetrics, WorkoutSession
- **ExerciseModels.kt**: MuscleGroup (12 groups), Exercise, Routine

### Constants
`util/Constants.kt` contains:
- Weight limits: 0-220kg (0.5kg increments)
- BLE timeouts: 10s scan, 15s connection
- One-rep max formulas (Brzycki, Epley)

## Tech Stack Versions
- Kotlin 2.0.21
- Compose Multiplatform 1.7.1
- AGP 8.5.2
- SQLDelight 2.0.2
- Koin 4.0.0
- Coroutines 1.9.0

## Hardware Support
- **Vitruvian V-Form Trainer** (VIT-200): 200kg max, device name `Vee_*`
- **Vitruvian Trainer+**: 220kg max
- IMPORTANT: When applicable, prefer using jetbrains-index MCP tools for code navigation and refactoring.

## The Daem0n's Covenant

This project is bound to Daem0n for persistent AI memory. Observe this protocol:

### At Session Dawn
- Commune with `get_briefing()` immediately when powers manifest
- Heed any warnings or failed approaches before beginning work

### Before Alterations
- Cast `context_check("your intention")` before modifications
- Cast `recall_for_file("path")` when touching specific files
- Acknowledge any warnings about past failures

### After Decisions
- Cast `remember(category, content, rationale, file_path)` to inscribe decisions
- Use categories: decision, pattern, warning, learning

### After Completion
- Cast `record_outcome(memory_id, outcome, worked)` to seal the memory
- ALWAYS record failures (worked=false) - they illuminate future paths

See Summon_Daem0n.md for the complete Grimoire.

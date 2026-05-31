<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

## Agent & Sub-Task Constraints

- Spawned agents MUST use ONLY the MCP tools configured in this project's `.mcp.json` — no freelancing
- Agents MUST follow project skills in `.claude/skills/` (agent-browser)
- Agents MUST NOT use tools or skills from other projects (OpenCode, TheBeckoningMU, DaemonChat)
- Verify agent work against project `.mcp.json` configuration before accepting results

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
Located in `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/`:
- **WorkoutModels.kt**: WorkoutMode (6 modes), ConnectionState, WorkoutMetrics, WorkoutSession
- **ExerciseModels.kt**: MuscleGroup (12 groups), Exercise, Routine

### Constants
`util/Constants.kt` contains:
- Weight limits: 0-220kg (0.5kg increments)
- BLE timeouts: 10s scan, 15s connection
- One-rep max formulas (Brzycki, Epley)

## Tech Stack Versions
- Kotlin 2.3.0
- Compose Multiplatform 1.10.1
- AGP 9.0.1
- SQLDelight 2.2.1
- Koin 4.1.1
- Coroutines 1.10.2

## Hardware Support
- **Vitruvian V-Form Trainer** (VIT-200): 200kg max, device name `Vee_*`
- **Vitruvian Trainer+**: 220kg max
- IMPORTANT: When applicable, prefer using jetbrains-index MCP tools for code navigation and refactoring.

## Sync Architecture

### Key Sync Files
- `data/sync/SyncManager.kt` — Orchestrates push/pull operations with batching and partial success handling
- `data/sync/SyncTriggerManager.kt` — Debounces sync triggers, exponential backoff for transient errors
- `data/sync/PortalApiClient.kt` — HTTP client with error classification and token refresh
- `data/repository/SqlDelightSyncRepository.kt` — Merge strategies per entity type (INSERT OR IGNORE, upsert, LWW)
- `data/sync/SyncModels.kt` — DTOs for wire format (camelCase for JSON, matches Edge Functions)

### Sync Trigger Patterns
- **`onWorkoutCompleted()`**: Always syncs immediately (bypasses throttle) since workout data is critical
- **`onAppForeground()`**: Respects throttle/backoff to avoid excessive sync attempts
- **`onConnectivityRestored()`**: Triggers immediate retry if sync was waiting for network

### Error Classification
```kotlin
enum class SyncErrorCategory {
    TRANSIENT,  // 5xx, timeout, 429 - retry with backoff
    PERMANENT,  // 400, 404 - don't retry
    AUTH,       // 401 - trigger re-login
    NETWORK,    // UnknownHost, connection errors - wait for connectivity
}
```

### Batch Handling
- **SYNC_BATCH_SIZE = 50**: Sessions per batch to stay under Edge Function body limit (~1MB)
- **MAX_FULL_BATCH_RETRIES = 3**: Consecutive failures before requiring manual intervention
- Non-session data (routines, cycles, RPG, badges) included only in final batch

### iOS Token Storage
Uses multiplatform-settings with Keychain backend (`KeychainSettings`) for secure JWT storage:
- Access token, refresh token, and expiry stored securely
- Automatically migrates from legacy NSUserDefaults on first access
- Requires iOS Keychain capability in entitlements

### 1RM Estimate Parity (PARITY-CRITICAL)
- Canonical formula: hybrid — Brzycki `w*36/(37-reps)` for reps <= 10, Epley `w*(1+reps/30)` for reps > 10. Continuous at reps == 10.
- Single implementation: `OneRepMaxCalculator.estimate()` (`util/Constants.kt`). All 1RM estimates (UI display, PR storage, cycle reporting) route through it — never reimplement the formula.
- Mobile computes the estimate per exercise-session (per-cable kg) and ships it as `PortalExerciseDto.estimatedOneRepMaxKg`. The portal stores it verbatim in `exercise_progress.estimated_1rm_kg` and recomputes (same hybrid) ONLY when the field is absent (legacy payloads). Mirror any change in the phoenix-portal counterpart.
- Max-weight PRs (`personal_records`) are a SEPARATE metric from the estimated 1RM — do not relabel one as the other.
- Stored `Exercise.one_rep_max_kg` (manual 5/3/1 input or VBT assessment) is the fallback scaling baseline for `% of PR` routines when no matching PersonalRecord exists (`ResolveRoutineWeightsUseCase`).

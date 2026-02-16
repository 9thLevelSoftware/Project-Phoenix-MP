# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines (V-Form, Trainer+) via BLE. Community rescue project keeping machines functional after company bankruptcy. Supports Android (Compose) and iOS (SwiftUI) from a shared KMP codebase. Architecture now fully decomposed — both ViewModel layer and BLE layer are modular, testable, and facade-gated.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ BLE connection to V-Form (`Vee_*`) and Trainer+ (`VIT*`) devices — v0.1
- ✓ 6 workout modes (Old School, Eccentric, etc.) with real-time metric display — v0.1
- ✓ Rep counting via machine + position-based phase detection — v0.2
- ✓ Exercise library with muscle groups, equipment, video support — v0.2
- ✓ Routines with supersets, set/rep/weight tracking — v0.2
- ✓ Personal records with 1RM calculation (Brzycki, Epley) — v0.3
- ✓ Gamification: XP, badges, workout streaks — v0.3
- ✓ Training cycles with day rotation — v0.3
- ✓ Cloud sync infrastructure — v0.4
- ✓ MainViewModel decomposition into 5 managers (History, Settings, BLE, Gamification, WorkoutSession) — v0.4
- ✓ DefaultWorkoutSessionManager decomposed into WorkoutCoordinator + RoutineFlowManager + ActiveSessionEngine — v0.4.1
- ✓ Circular dependency eliminated via bleErrorEvents SharedFlow pattern — v0.4.1
- ✓ Koin DI split into 4 feature-scoped modules with verify() test — v0.4.1
- ✓ HistoryAndSettingsTabs split into focused files (HistoryTab + SettingsTab) — v0.4.1
- ✓ 38 characterization tests covering workout lifecycle and routine flow — v0.4.1
- ✓ Testing foundation with DWSMTestHarness and WorkoutStateFixtures — v0.4.1
- ✓ BLE protocol constants centralized in BleConstants.kt (UUIDs, timing, thresholds) — v0.4.2
- ✓ Stateless ProtocolParser with byte utilities and 4 packet parsers (516 test lines) — v0.4.2
- ✓ BleOperationQueue with Mutex-based BLE serialization (Issue #222 prevention) — v0.4.2
- ✓ DiscoMode extracted as self-contained module, concrete cast eliminated (Issue #144) — v0.4.2
- ✓ HandleStateDetector 4-state machine with 37 unit tests (Issues #176, #210 preserved) — v0.4.2
- ✓ MonitorDataProcessor for position validation and velocity EMA (555 test lines) — v0.4.2
- ✓ MetricPollingEngine managing 4 polling loops with atomic lifecycle — v0.4.2
- ✓ KableBleConnectionManager with scan/connect/disconnect/auto-reconnect — v0.4.2
- ✓ KableBleRepository reduced to 394-line thin facade delegating to 6 modules — v0.4.2

### Active

<!-- Current scope. Building toward these. -->

(None — next milestone not yet defined. Use `/gsd:new-milestone` to start.)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->
- Premium features (data foundation, biomechanics, intelligence) — deferred to post-cleanup milestone
- iOS-specific UI work — focus has been shared module; iOS parity is future milestone
- BLE protocol changes — protocol is reverse-engineered and stable

## Context

- App is at v0.4.2, actively used by community
- **ViewModel layer** (v0.4.1): MainViewModel is a thin 420-line facade delegating to 5 managers. DWSM (449L) orchestrates WorkoutCoordinator, RoutineFlowManager, ActiveSessionEngine.
- **BLE layer** (v0.4.2): KableBleRepository is a 394-line thin facade delegating to 6 modules: BleOperationQueue, DiscoMode, HandleStateDetector, MonitorDataProcessor, MetricPollingEngine, KableBleConnectionManager.
- UI composables decomposed: WorkoutTab (1,495L), HistoryTab (1,060L), SettingsTab (1,704L)
- Koin DI: 4 feature-scoped modules (data, sync, domain, presentation) with verify() test
- Test infrastructure: DWSMTestHarness, WorkoutStateFixtures, fakes for all repositories, plus ~1,600 lines of BLE module unit tests
- OpenSpec specs (00-05) drafted for future premium features
- ~19,955 lines of Kotlin in shared module (net +12,141 from v0.4.2)
- Tech debt: hardware-specific BLE edge cases deferred to physical device QA

## Constraints

- **Platform**: KMP shared module — all business logic must remain in commonMain
- **Compatibility**: No breaking changes to existing workout behavior — characterization tests enforce
- **Incremental**: Each refactoring phase must leave the app in a buildable, working state
- **BLE stability**: BLE layer now fully decomposed — future changes target individual modules, not the facade

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Manager extraction pattern (scope injection, interface-based) | Enables testability, preserves ViewModel lifecycle | ✓ Good |
| MainViewModel as thin facade during transition | Preserves UI API while extracting logic incrementally | ✓ Good — UI unchanged |
| Characterize before refactoring | Tests lock in behavior, catch regressions during extraction | ✓ Good — 38 tests |
| WorkoutCoordinator as zero-method state bus | Sub-managers share state without circular refs | ✓ Good — v0.4.1 |
| Delegate pattern for sub-manager bridging | WorkoutLifecycleDelegate/WorkoutFlowDelegate avoid direct refs | ✓ Good — v0.4.1 |
| bleErrorEvents SharedFlow for BLE→DWSM | Eliminates lateinit var circular dependency | ✓ Good — v0.4.1 |
| Same-package visibility for UI extractions | Zero import changes needed when splitting files | ✓ Good — v0.4.1 |
| Feature-scoped Koin modules with verify() | Catches DI wiring issues at test time | ✓ Good — v0.4.1 |
| 8-module BLE decomposition (no DI changes) | Sub-components wired internally, Koin unchanged | ✓ Good — v0.4.2 |
| Nested object pattern for BleConstants | BleConstants.Timing, BleConstants.Thresholds for discoverability | ✓ Good — v0.4.2 |
| TDD for all BLE module extractions | Tests written first, then implementation, then delegation | ✓ Good — v0.4.2 |
| Callback-based DiscoMode design | suspend (ByteArray) -> Unit avoids circular dependency | ✓ Good — v0.4.2 |
| Injectable timeProvider for HandleStateDetector | Deterministic time testing without platform dependencies | ✓ Good — v0.4.2 |
| lateinit + init block for ConnectionManager | Breaks Kotlin compiler recursive type inference | ✓ Good — v0.4.2 |
| Module declaration order in KableBleRepository | bleQueue → discoMode → handleDetector → monitorProcessor → pollingEngine → connectionManager for init safety | ✓ Good — v0.4.2 |

---
*Last updated: 2026-02-16 after v0.4.2 milestone*

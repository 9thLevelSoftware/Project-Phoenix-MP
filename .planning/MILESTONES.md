# Milestones

## v0.4.0 — Foundation (Pre-GSD)

**Shipped:** BLE control, workout execution, exercise library, routines, supersets, training cycles, personal records, gamification, cloud sync, initial MainViewModel extraction.

**Phases completed:** N/A (pre-GSD — work tracked via git history and OpenSpec)

**Last phase number:** 0

---

## v0.4.1 — Architectural Cleanup (Shipped: 2026-02-13)

**Delivered:** Complete architectural decomposition of remaining monoliths with testing foundation.

**Phases completed:** 4 phases, 10 plans

**Key accomplishments:**
- Created 38 characterization tests with DWSMTestHarness and WorkoutStateFixtures
- Decomposed 4,024-line DefaultWorkoutSessionManager into 4 focused components (449L orchestration layer)
- Extracted WorkoutCoordinator (257L) as zero-method shared state bus
- Extracted RoutineFlowManager (1,091L) for routine CRUD and navigation
- Extracted ActiveSessionEngine (2,174L) for workout lifecycle and BLE commands
- Eliminated circular dependency via bleErrorEvents SharedFlow pattern
- Split 2,750-line HistoryAndSettingsTabs.kt into HistoryTab.kt + SettingsTab.kt
- Extracted SetSummaryCard, WorkoutSetupDialog, ModeSubSelectorDialog from WorkoutTab.kt
- Split 30+ binding commonModule into 4 feature-scoped Koin modules with verify() test

**Last phase number:** 4

**Archive:** `.planning/milestones/v0.4.1-*`

---


## v0.4.2 — BLE Layer Decomposition (Shipped: 2026-02-16)

**Delivered:** Complete decomposition of 2,886-line KableBleRepository monolith into 6 focused, testable modules behind a 394-line thin facade.

**Phases completed:** 9 phases (5-13), 14 plans
**Timeline:** 1 day (2.19 hours execution), 69 commits, +14,796/-2,655 lines across 62 files

**Key accomplishments:**
- Decomposed KableBleRepository from 2,886 lines to 394-line thin facade delegating to 6 modules
- Created ProtocolParser with stateless byte parsing functions and 516 lines of unit tests
- Extracted HandleStateDetector 4-state machine with 37 unit tests (Issues #176, #210 preserved)
- Extracted MonitorDataProcessor for position validation and velocity EMA with 555 lines of tests
- Created MetricPollingEngine managing 4 polling loops (monitor, diagnostic, heuristic, heartbeat)
- Extracted KableBleConnectionManager with full connection lifecycle and auto-reconnect

**Tech debt (hardware-only):**
- BleOperationQueue: fault 16384 stress test deferred to physical device QA
- MonitorDataProcessor: deload/ROM violation events require hardware trigger
- ConnectionManager: auto-reconnect timing requires BLE range/power-cycle test

**Last phase number:** 13

**Archive:** `.planning/milestones/v0.4.2-*`

---


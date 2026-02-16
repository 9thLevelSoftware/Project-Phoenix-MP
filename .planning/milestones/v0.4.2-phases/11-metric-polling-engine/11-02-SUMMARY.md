---
phase: 11-metric-polling-engine
plan: 02
status: complete
started: 2026-02-15
completed: 2026-02-15
duration: ~8min
---

## What Was Built

Wired KableBleRepository to delegate all polling lifecycle to MetricPollingEngine, completing the Phase 11 extraction. KableBleRepository no longer directly manages polling Jobs — all start/stop/restart operations go through the engine.

## Key Files

### Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt` (1829 → 1384 lines, -445 net)
  - Added `pollingEngine` inline property (after bleQueue, handleDetector, monitorProcessor for init-order safety)
  - Removed 4 Job references: monitorPollingJob, diagnosticPollingJob, heuristicPollingJob, heartbeatJob
  - Removed Mutex (monitorPollingMutex) and 2 diagnostic counters (diagnosticPollCount, lastDiagnosticFaults)
  - Removed 8 methods: startHeartbeat, performHeartbeatRead, sendHeartbeatNoOp, startDiagnosticPolling, startHeuristicPolling, startMonitorPolling, parseMonitorData, parseHeuristicData
  - Kept parseDiagnosticData (still called from post-CONFIG one-shot in sendWorkoutCommand)
  - Updated 8 call sites to delegate to pollingEngine

### Unchanged (verified)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt` — interface unchanged
- `SimulatorBleRepository.kt`, `FakeBleRepository.kt` — unchanged

## Decisions

- **parseDiagnosticData stays simplified**: Removed fault-change tracking (lastDiagnosticFaults) from the one-shot post-CONFIG diagnostic read. The engine handles continuous fault tracking in its own polling loop. One-shot just logs current state.
- **stopDiscoMode() placement**: Added before `pollingEngine.startAll(p)` in startObservingNotifications, before `pollingEngine.startMonitorPolling()` in enableHandleDetection and restartMonitorPolling, and before `pollingEngine.restartAll()` in startActiveWorkoutPolling. Keeps disco mode management in KableBleRepository where it belongs.
- **State.Disconnected handler**: Updated to use `pollingEngine.stopAll()` instead of just `heartbeatJob?.cancel()` — ensures all polling stops on unexpected disconnect.
- **enableHandleDetection**: Removed redundant `handleDetector.enable(autoStart=true)` call — the engine's `startMonitorPolling(forAutoStart=true)` calls it internally. Prevents double-enable.

## Deviations

- Line reduction exceeded target: 445 lines vs ~300 planned (more code was removed than estimated because the plan didn't account for all logging/comment lines in the methods).

## Self-Check: PASSED

- [x] KableBleRepository delegates all polling to pollingEngine
- [x] pollingEngine declared after bleQueue, handleDetector, monitorProcessor (init-order)
- [x] No orphan references to removed variables (grep confirmed zero matches)
- [x] BleRepository interface unchanged (git diff confirmed)
- [x] SimulatorBleRepository, FakeBleRepository unchanged
- [x] All tests pass (./gradlew :androidApp:testDebugUnitTest)
- [x] Complete 5-module delegation: bleQueue, discoMode, handleDetector, monitorProcessor, pollingEngine

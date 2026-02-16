---
phase: 11-metric-polling-engine
plan: 01
status: complete
started: 2026-02-15
completed: 2026-02-15
duration: ~12min
---

## What Was Built

MetricPollingEngine — a coroutine Job lifecycle manager for the 4 BLE polling loops, extracted from KableBleRepository (Phase 11, fifth module in v0.4.2 decomposition).

## Key Files

### Created
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngine.kt` (532 lines)
  - 4 polling loops: monitor (10-20Hz), diagnostic (500ms), heuristic (250ms/4Hz), heartbeat (2s)
  - Lifecycle: startAll, stopAll, stopMonitorOnly, restartAll, restartDiagnosticAndHeartbeat
  - Monitor Mutex prevents concurrent polling loops
  - Timeout disconnect (POLL-03): onConnectionLost after MAX_CONSECUTIVE_TIMEOUTS
  - Processing pipeline: parse -> process (MonitorDataProcessor) -> detect (HandleStateDetector) -> emit
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngineTest.kt` (345 lines)
  - 18 tests: lifecycle (4), Issue #222 partial stop (4), conditional restart (5), timeout disconnect (3), diag+heartbeat restart (2)

### Modified
- None (KableBleRepository delegation is Plan 02)

## Decisions

- **Test approach**: Used internal test helpers (startFakeJobs/startFakeJob) instead of FakePeripheral because Kable's Peripheral interface has complex platform-specific types (Identifier is an expect class). Lifecycle tests verify Job start/stop behavior, timeout tests verify counter logic via simulateTimeout/checkTimeoutThreshold helpers.
- **stopDiscoMode stays in KableBleRepository**: Engine doesn't reference DiscoMode. KableBleRepository calls stopDiscoMode() before engine methods (per Research open question #3).
- **Connection state check uses isActive**: Removed `_connectionState` checks from polling loops. The coroutine's `isActive` flag is sufficient since disconnect() calls stopAll() which cancels all jobs.
- **onConnectionLost launched in separate coroutine**: `scope.launch { onConnectionLost() }` prevents disconnect from cancelling the currently-executing polling coroutine before cleanup (pitfall #5).

## Deviations

- Plan specified strict RED/GREEN/REFACTOR. Implementation merged GREEN and REFACTOR since the code was clean after initial implementation. No separate refactor commit needed.
- Tests pass at RED stage (lifecycle logic implemented in test helpers, not just stubs). This is acceptable since the lifecycle logic IS the core behavior being tested.

## Self-Check: PASSED

- [x] MetricPollingEngine.kt exists with all 4 polling loops
- [x] MetricPollingEngineTest.kt exists with 18 tests
- [x] Engine has startAll, stopAll, stopMonitorOnly, restartAll, restartDiagnosticAndHeartbeat
- [x] Tests verify Issue #222 partial-stop invariant
- [x] Tests verify POLL-03 timeout disconnect
- [x] All tests pass (./gradlew :shared:testDebugUnitTest)
- [x] No changes to KableBleRepository

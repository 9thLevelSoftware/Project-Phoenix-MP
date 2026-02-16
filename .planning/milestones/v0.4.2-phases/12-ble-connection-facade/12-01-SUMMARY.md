---
phase: 12-ble-connection-facade
plan: 01
subsystem: ble
tags: [kable, ble-connection, peripheral-ownership, callback-pattern, coroutines]

# Dependency graph
requires:
  - phase: 07-ble-operation-queue
    provides: BleOperationQueue for serialized read/write
  - phase: 08-disco-mode-interface
    provides: DiscoMode callback-based module pattern
  - phase: 09-handle-state-detector
    provides: HandleStateDetector for auto-start detection
  - phase: 10-monitor-data-processor
    provides: MonitorDataProcessor for metric processing
  - phase: 11-metric-polling-engine
    provides: MetricPollingEngine for 4 polling loops
provides:
  - KableBleConnectionManager with exclusive Peripheral ownership
  - Connection lifecycle (scan, connect with retry, disconnect, auto-reconnect)
  - Notification subscriptions (REPS, VERSION, MODE characteristics)
  - Command sending via BleOperationQueue with post-CONFIG diagnostic reads
  - processIncomingData opcode routing (0x01 metrics, 0x02 reps)
  - Self-contained awaitResponse() for future protocol handshakes
affects: [12-ble-connection-facade plan 02 (facade delegation)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Callback-based manager with 7 lambda constructor parameters"
    - "lastReportedState local tracking for stopScanning guard (avoids needing facade state)"
    - "Dual emission in processIncomingData: internal flow + external callback"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManagerTest.kt
  modified: []

key-decisions:
  - "lastReportedState tracking variable for stopScanning guard instead of currentConnectionState callback parameter"
  - "processIncomingData and parseDiagnosticData made internal (not private) for testability, following MetricPollingEngine test helper pattern"
  - "Self-contained _commandResponses SharedFlow in manager + external callback dual emission for awaitResponse support"
  - "Removed emoji characters from log messages for cleaner logging"

patterns-established:
  - "Connection manager callback pattern: 7 lambdas for state/event/data routing to facade"
  - "Dual emission pattern: internal flow for self-consumption + callback for external routing"

# Metrics
duration: 7min
completed: 2026-02-16
---

# Phase 12 Plan 01: KableBleConnectionManager Summary

**Connection lifecycle manager with exclusive Peripheral ownership, 15 methods extracted from KableBleRepository, callback-based event routing, and 13 unit tests**

## Performance

- **Duration:** 7 min
- **Started:** 2026-02-16T01:53:32Z
- **Completed:** 2026-02-16T02:00:29Z
- **Tasks:** 2
- **Files created:** 2

## Accomplishments
- Created KableBleConnectionManager (1105 lines) with all connection lifecycle code extracted from KableBleRepository
- Moved 9 state variables + discoveredAdvertisements map with exclusive Peripheral ownership
- 13 unit tests passing for callback routing, disconnect cleanup, and diagnostic safety
- Auto-reconnect flag logic (wasEverConnected + isExplicitDisconnect) preserved intact in state observer

## Task Commits

Each task was committed atomically:

1. **Task 1: Create KableBleConnectionManager with all connection lifecycle code** - `441a28c6` (feat)
2. **Task 2: Add unit tests for KableBleConnectionManager lifecycle** - `d5ac3949` (test)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt` - Connection lifecycle manager with Peripheral ownership, scan/connect/disconnect/auto-reconnect/notification subscriptions/command sending
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManagerTest.kt` - 13 unit tests for callback routing, state cleanup, diagnostic safety

## Decisions Made
- Used `lastReportedState` local tracking variable for the `stopScanning()` guard instead of adding a `currentConnectionState` callback parameter to the constructor. Simpler and self-contained.
- Made `processIncomingData` and `parseDiagnosticData` `internal` (not `private`) for testability, following the established MetricPollingEngine pattern with internal test helpers.
- Self-contained `_commandResponses` SharedFlow in the manager that emits to both the internal flow (for `awaitResponse()`) and the external callback (for facade consumers). Dual emission avoids needing to expose the flow through the facade.
- Removed emoji characters from log messages for cleaner, more consistent logging output.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- KableBleConnectionManager is ready for Plan 12-02 (facade delegation)
- KableBleRepository still has the old code — Plan 12-02 will delegate to the new manager
- No modifications to KableBleRepository were made (as specified in plan)

---
*Phase: 12-ble-connection-facade*
*Completed: 2026-02-16*

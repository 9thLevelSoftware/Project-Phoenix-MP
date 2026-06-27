# Project Phoenix MP Review: Data - BLE Part 1

Scope: BLE connection management, event delivery, metrics, operation queue, packets, repository.

Assigned files reviewed directly when present. Several assigned paths do not exist in the current checkout, so I resolved and reviewed the current implementation counterparts where possible:

| Assigned path | Current status / counterpart reviewed |
| --- | --- |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleConnectionManager.kt` | Assigned path missing. Reviewed `data/ble/KableBleConnectionManager.kt` and `presentation/manager/BleConnectionManager.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleConnectionState.kt` | Assigned path missing. Current `ConnectionState` is in `domain/model/Models.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleDevice.kt` | Assigned path missing. Current scanned-device model is `ScannedDevice` in `data/repository/BleRepository.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleEvent.kt` | Assigned path missing. Current critical event primitives are split across `BleEventDeliveryTracker.kt`, `BleRepository.kt`, and `KableBleRepository.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleEventDeliveryTracker.kt` | Present and reviewed. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleMetrics.kt` | Assigned path missing. Current metric model/polling are `domain/model/Models.kt` (`WorkoutMetric`) and `data/ble/MetricPollingEngine.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt` | Present and reviewed. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BlePacket.kt` | Assigned path missing. Current packet model/factory code is `data/ble/ProtocolModels.kt` and `util/BlePacketFactory.kt`. |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleRepository.kt` | Assigned path missing. Current repository interface/implementation are `data/repository/BleRepository.kt` and `data/repository/KableBleRepository.kt`. |

## Findings

### 1. Auto-reconnect can be suppressed after any explicit disconnect/cancel

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`
- Line numbers: 513-724, 646-657, 1013-1027, 1034-1044
- Category: bug
- Severity: high
- Description: `disconnect()` and `cancelConnection()` set `isExplicitDisconnect = true`, but a subsequent successful `connect()` never resets it before the next connection lifecycle. If the previous explicit disconnect did not flow through the `State.Disconnected` handler that resets the flag, the next real unexpected disconnect can hit the guard at lines 646-657 with `isExplicitDisconnect == true` and skip the reconnection request.
- Suggested fix direction: Reset `isExplicitDisconnect = false` at the start of a new user/auto connection attempt after old state cleanup, or scope the explicit-disconnect flag to a specific peripheral/lifecycle generation so it cannot leak into the next connection.

### 2. `scanAndConnect()` ignores UUID/service-data devices that normal scanning accepts

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`
- Line numbers: 245-297, 469-479
- Category: failure-point
- Severity: medium
- Description: `startScanning()` recognizes Vitruvian devices by advertised name, service UUID, or FEF3 service data. `scanAndConnect()` only accepts devices with a non-null name beginning with `Vee_` or `VIT`. Devices that are discoverable through the repository's manual scan path can therefore never be found by the auto-connect path.
- Suggested fix direction: Extract one shared Vitruvian advertisement predicate and use it in both `startScanning()` and `scanAndConnect()`. Preserve the nameless/service-data fallback when auto-connecting.

### 3. Notification observer jobs are untracked and can be duplicated across reconnect/readiness cycles

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`
- Line numbers: 584-601, 850-955, 882-912, 915-950
- Category: failure-point
- Severity: medium
- Description: `State.Connected` launches `onDeviceReady()`, which calls `startObservingNotifications()`. That method launches REPS, VERSION, and MODE observation coroutines into the long-lived repository scope but does not retain job handles or cancel previous observers before starting new ones. Repeated `Connected` emissions or rapid reconnects can leave duplicate collectors on the same peripheral or stale collectors from prior peripherals.
- Suggested fix direction: Store notification observer jobs in the connection manager, cancel them before starting a new observer set, and cancel them during disconnect/cleanup/shutdown. Consider guarding `onDeviceReady()` so each peripheral generation initializes only once.

### 4. Device readiness continues even when services are not discovered yet

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`
- Line numbers: 763-850
- Category: failure-point
- Severity: medium
- Description: `onDeviceReady()` reads `p.services.value` once. If it is null, the code logs a warning but still starts notifications and polling immediately. On platforms where service discovery populates slightly after `State.Connected`, this can attempt characteristic operations before discovery is complete and create intermittent connection/setup failures.
- Suggested fix direction: Wait with a bounded timeout for `p.services` to become non-null and to contain required characteristics before starting observers/polling. If readiness fails, surface a retryable initialization error instead of continuing with partially discovered GATT state.

### 5. UI connection methods discard repository failures

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- Line numbers: 160-187
- Category: failure-point
- Severity: medium
- Description: `startScanning()` launches `bleRepository.startScanning()` and ignores its `Result`; `connectToDevice()` launches `bleRepository.connect(device)` and ignores its `Result`. Failures can be logged by the lower layer but never reflected in `_connectionError`, leaving the UI without actionable feedback for scan/connect failures.
- Suggested fix direction: Check returned `Result` values in these launched coroutines and update `_connectionError` or route to the existing failure callback path. Also catch non-cancellation exceptions around these non-init launches for Kotlin/Native safety.

### 6. `stopWorkout()` returns success even if the reset command failed

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Line numbers: 241-252
- Category: bug
- Severity: high
- Description: `stopWorkout()` calls `sendWorkoutCommand(resetCmd)` but does not inspect the returned `Result`. If the BLE write fails, the method still delays, stops polling, and returns `Result.success(Unit)`. Callers can believe the trainer was stopped/reset when no stop command reached the machine.
- Suggested fix direction: Capture the send result and return the failure before stopping/tearing down polling, or explicitly distinguish "command failed but local polling stopped" in the returned result and UI state.

### 7. Repository `startWorkout()` still emits a legacy 4-byte command path

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Line numbers: 216-233
- Category: failure-point
- Severity: medium
- Description: The repository-level `startWorkout(params)` constructs a 4-byte packet manually (`0x02, mode, weightLow, weightHigh`) and starts active polling on success. Elsewhere the codebase uses `BlePacketFactory.createProgramParams()` / `createEchoControl()` for the full 96-byte/32-byte protocol. Any caller using this interface method can start a workout with an incomplete/legacy packet that does not carry reps, warmup, progression, Echo settings, or current protocol fields.
- Suggested fix direction: Either remove/deprecate this interface method if all real starts are routed elsewhere, or implement it by delegating to the same validated packet factory path used by active session code.

### 8. Critical safety/reconnect events can be dropped silently

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Line numbers: 79-97, 110-122, 162
- Category: failure-point
- Severity: high
- Description: Deload, ROM-violation, and reconnection events are `MutableSharedFlow`s with small extra buffers and `DROP_OLDEST`. The producers call `emit()` from launched coroutines or pass through the callback without checking whether an older critical event was evicted. `BleEventDeliveryTracker` defines counters for these event types, but only REP drops are recorded in the repository.
- Suggested fix direction: Use delivery semantics appropriate for safety events: durable state plus event, replay where needed, or explicit `tryEmit`/overflow accounting. Wire `BleEventDeliveryTracker.recordDropped()` for DELOAD, ROM_VIOLATION, and RECONNECTION_REQUEST, and log/report drops as safety-relevant events.

### 9. Rep events with no active subscriber are treated as successfully delivered

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Line numbers: 74-78, 453-463
- Category: failure-point
- Severity: high
- Description: `_repEvents` is a `MutableSharedFlow` with `replay = 0`. `publishRepEvent()` records a drop only when `tryEmit()` returns false. For a zero-replay shared flow, emissions with no active subscriber can return true while the event is immediately discarded. A rep notification that arrives before the workout collector is active can therefore be lost without incrementing `repEventsDropped`.
- Suggested fix direction: Add a small replay/cache or convert the critical rep stream to an acknowledged/event-queue abstraction. At minimum, monitor subscription state or active workout collection state and record/log rep events emitted while no consumer is available.

### 10. `BleOperationQueue.write()` accepts zero/negative retry counts and reports a misleading unknown error

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt`
- Line numbers: 44-75
- Category: failure-point
- Severity: low
- Description: The retry loop is `for (attempt in 0 until maxRetries)`. If a caller passes `maxRetries <= 0`, no BLE write is attempted and the function returns `IllegalStateException("Unknown write error")`, hiding the real configuration problem.
- Suggested fix direction: Validate `maxRetries >= 1` at entry and fail fast with an `IllegalArgumentException`, or coerce to at least one attempt.

### 11. `BleOperationQueue.withLock()` exposes a non-reentrant deadlock footgun to callers

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt`
- Line numbers: 19, 31, 81-96
- Category: failure-point
- Severity: low
- Description: The file warns that Kotlin `Mutex` is not reentrant, while also exposing `withLock()` for compound operations plus separate `read()`, `write()`, and `writeSimple()` methods that acquire the same mutex. A future compound operation that calls one of these helpers inside `withLock()` will deadlock. The contract is documented but not enforced.
- Suggested fix direction: Keep the compound locked API internal/narrow, add debug owner checks, or provide unlocked lower-level helpers explicitly intended for use inside `withLock()` so nested acquisition is not needed.

### 12. Polling API/documentation mismatch around heuristic polling during bodyweight/rest mode

- File path: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt`
- Line numbers: 225-233
- Category: error
- Severity: low
- Description: The interface comment says `stopMonitorPollingOnly()` should stop "monitor and heuristic polling" while keeping diagnostic polling and heartbeat running. The implementation path in `MetricPollingEngine.stopMonitorOnly()` cancels only `monitorPollingJob` and leaves heuristic polling active. That mismatch makes it unclear whether heuristic reads are intentionally kept as connection activity or accidentally continue during modes that should not emit workout telemetry.
- Suggested fix direction: Decide the intended behavior. If heuristic polling should stop, cancel `heuristicPollingJob` in `stopMonitorOnly()`. If it should continue, update the repository contract comment and any callers/tests that assume heuristic data stops.

## Files with no direct code findings in reviewed current counterparts

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleEventDeliveryTracker.kt`: simple counter state flow; no direct local logic bug found beyond repository integration gaps noted above.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt` (`ConnectionState`, `WorkoutMetric` sections): no direct local logic bug found in the reviewed state/metric data models.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BleRepository.kt` (`ScannedDevice`, `HandleDetection`, `RepNotification`, interface): no direct data-model issue found beyond the interface/implementation mismatch noted above.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolModels.kt`: no direct local issue found in the reviewed packet data classes.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`: reviewed as current packet factory counterpart; no direct packet-construction finding included for this scope.

## Summary

- Code findings: 12
- Severity breakdown:
  - Critical: 0
  - High: 4
  - Medium: 5
  - Low: 3
- Category breakdown:
  - Bug: 2
  - Stub: 0
  - Error: 1
  - Failure-point: 9
- Assigned paths missing in current checkout: 7 of 9

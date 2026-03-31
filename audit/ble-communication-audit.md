# BLE Communication Layer Audit Report

**Date:** 2026-03-31
**Scope:** BLE connection management, protocol parsing, operation queue, metric polling, handle state detection, platform-specific implementations
**Status:** Audit-only (no fixes applied)

## Executive Summary

3 HIGH, 12 MEDIUM, 10 LOW severity issues identified across the BLE communication stack. No critical issues found. Key concerns center on data loss during unexpected disconnects, auto-reconnect logic bugs, and missing iOS background BLE support.

## Findings

### HIGH Severity

#### BLE-H001: Auto-reconnect suppressed after cleanup-then-reconnect sequence
- **File:** `shared/src/commonMain/.../data/ble/KableBleConnectionManager.kt`
- **Description:** `cleanupExistingConnection()` sets `isExplicitDisconnect = true` and cancels `stateObserverJob` before calling `peripheral?.disconnect()`. Since the state observer is cancelled, the `State.Disconnected` handler never runs to reset `isExplicitDisconnect = false`. When `connect()` creates a new peripheral and the new connection later drops unexpectedly, the `State.Disconnected` handler sees `isExplicitDisconnect = true` and skips auto-reconnect.
- **Impact:** After manual re-connection scenarios, subsequent unexpected disconnects will not trigger auto-reconnect.
- **Suggested Fix:** Reset `isExplicitDisconnect = false` at the START of `connect()` rather than relying on the disconnected handler.

#### BLE-H002: No workout data persistence on unexpected disconnect
- **File:** `shared/src/commonMain/.../data/ble/KableBleConnectionManager.kt` (Disconnected handler), `ActiveSessionEngine.kt`
- **Description:** When connection drops during an active workout, the Disconnected handler stops polling and reports state change but does NOT trigger saving of `coordinator.collectedMetrics`. If the user doesn't reconnect or the app crashes, all workout data since the last set completion is lost.
- **Impact:** Users can lose workout data during BLE disconnections.
- **Suggested Fix:** Persist accumulated metrics to database before reporting disconnection state.

#### BLE-H003: KableBleRepository scope is never cancelled
- **File:** `shared/src/commonMain/.../data/repository/KableBleRepository.kt`
- **Description:** `CoroutineScope(SupervisorJob() + Dispatchers.Default)` is created but never cancelled. Since it's a Koin singleton, this persists for app lifetime. If Koin ever re-creates the object (testing, module reload), the old scope and all its coroutines leak.
- **Impact:** Coroutine leak potential during testing or Koin module reloads.
- **Suggested Fix:** Implement a cleanup/close method; consider using a lifecycle-aware scope.

### MEDIUM Severity

#### BLE-M001: No timeout on disconnect()/cancelConnection() calls
- **File:** `KableBleConnectionManager.kt`
- **Description:** Both call `peripheral?.disconnect()` without a `withTimeout` wrapper. If the BLE stack hangs, these calls could block indefinitely.
- **Impact:** App can enter a stuck state on some Android devices with problematic BLE stacks.
- **Suggested Fix:** Wrap in `withTimeout(5000)`.

#### BLE-M002: No retry on notification subscription failure
- **File:** `KableBleConnectionManager.kt`, `startObservingNotifications()`
- **Description:** REPS characteristic observation failure is caught and logged but not retried. A workout could continue without rep counting.
- **Impact:** Incorrect workout history if rep notification subscription silently fails.
- **Suggested Fix:** Add retry logic with exponential backoff for notification subscription.

#### BLE-M003: onDeviceReady() failure results in silent disconnect
- **File:** `KableBleConnectionManager.kt`, Connected handler
- **Description:** If `onDeviceReady()` throws, the error handler disconnects without surfacing a user-visible error message explaining the failure.
- **Impact:** Users see connection drop after apparently succeeding, with no explanation.
- **Suggested Fix:** Emit a specific error state (e.g., "Service discovery failed") before disconnecting.

#### BLE-M004: Non-reentrant Mutex without runtime deadlock detection
- **File:** `BleOperationQueue.kt`
- **Description:** Class comment warns "Never nest read()/write()/withLock() calls" but there's no runtime assertion to detect violations. A nested call would silently deadlock.
- **Impact:** Silent deadlock if nesting invariant is violated.
- **Suggested Fix:** Add a thread-local or coroutine context check to detect nested calls.

#### BLE-M005: Write retry only handles "Busy" string-matched errors
- **File:** `BleOperationQueue.kt`, `write()`
- **Description:** Retry logic checks `e.message?.contains("Busy")` and `e.message?.contains("WriteRequestBusy")`. This string matching is fragile across Android BLE stacks.
- **Impact:** Other transient errors fail immediately without retry on some devices.
- **Suggested Fix:** Use exception type matching rather than string matching; add retry for known transient GATT errors.

#### BLE-M006: No overall timeout on write operations
- **File:** `BleOperationQueue.kt`, `write()`
- **Description:** Has retry logic with exponential backoff but no overall timeout. If a long-running `read()` holds the mutex, a `write()` call could block indefinitely.
- **Impact:** Potential indefinite blocking.
- **Suggested Fix:** Add an overall `withTimeout` wrapper around the mutex acquisition + write.

#### BLE-M007: Monitor polling rate has no adaptive delay for iOS
- **File:** `MetricPollingEngine.kt`, `startMonitorPolling()`
- **Description:** No delay on success relies on BLE response time (~10-20ms) to rate-limit. On iOS, connection priority is automatic and varies by device/iOS version. No adaptive mechanism exists.
- **Impact:** Inconsistent polling rates on iOS devices.
- **Suggested Fix:** Implement adaptive delay based on actual response times.

#### BLE-M008: Heartbeat always fails read before write
- **File:** `MetricPollingEngine.kt`, `performHeartbeatRead()`/`sendHeartbeatNoOp()`
- **Description:** Every heartbeat cycle attempts to read the TX characteristic (which is write-only), fails, then falls back to a no-op write. Generates unnecessary BLE traffic and error logs every 2 seconds.
- **Impact:** Unnecessary BLE overhead and log noise.
- **Suggested Fix:** Skip the read attempt; go directly to the no-op write path.

#### BLE-M009: startObservingNotifications() launches untracked coroutines
- **File:** `KableBleConnectionManager.kt`, `startObservingNotifications()`
- **Description:** Five `scope.launch` blocks created for notification observation have no Job references stored, so they can't be explicitly cancelled on reconnection. They rely on the old peripheral erroring out.
- **Impact:** Potential coroutine accumulation if peripheral objects don't clean up properly.
- **Suggested Fix:** Store Job references and explicitly cancel them in cleanup.

#### BLE-M010: discoveredAdvertisements map accessed without synchronization
- **File:** `KableBleConnectionManager.kt`
- **Description:** Plain `mutableMapOf()` accessed from scanning coroutine (writes) and `connect()` (reads) on `Dispatchers.Default` (multi-threaded). Non-thread-safe HashMap.
- **Impact:** Potential ConcurrentModificationException or silent data corruption.
- **Suggested Fix:** Use `ConcurrentHashMap` or wrap in a Mutex.

#### BLE-M011: No iOS background BLE support
- **File:** `BleExtensions.ios.kt`
- **Description:** Both iOS extension functions are no-ops. iOS CoreBluetooth requires state restoration configuration for background BLE. Without it, connections drop when app backgrounds.
- **Impact:** Workouts interrupted if user switches apps or locks screen on iOS.
- **Suggested Fix:** Implement CoreBluetooth state restoration and background mode capabilities.

#### BLE-M012: BlePacketCapture.onPacket() called unconditionally in production
- **File:** `MetricPollingEngine.kt`, `parseMonitorData()`
- **Description:** Called on every monitor packet. The early return is fast but unnecessary overhead in production builds.
- **Impact:** Minor: unnecessary function call overhead at 10-20Hz.
- **Suggested Fix:** Gate the call behind a debug build check.

### LOW Severity

#### BLE-L001: Dead code in parseMonitorPacket velocity size guards
- **File:** `ProtocolParser.kt`, `parseMonitorPacket()`
- **Description:** Size guards like `data.size >= 8` are always true because the function already returned null for `data.size < 16`.

#### BLE-L002: No NaN/Inf validation on heuristic float parsing
- **File:** `ProtocolParser.kt`, `parseHeuristicPacket()`
- **Description:** Floats parsed via `getFloatLE()` used directly without NaN/Infinity checks. Corrupted BLE data could propagate NaN to UI.

#### BLE-L003: parseRepPacket legacy format skips bytes 2-3
- **File:** `ProtocolParser.kt`, `parseRepPacket()`
- **Description:** In the legacy 6-byte format, bytes 2-3 are completely ignored. Matches parent repo but could miss data from variant firmware.

#### BLE-L004: Monitor packet extraBytes allocation on every poll
- **File:** `ProtocolParser.kt`, `parseMonitorPacket()`
- **Description:** `data.copyOfRange(18, data.size)` allocates a new ByteArray per packet. At 10-20Hz, ~600-1200 small allocations per minute. Minor GC pressure.

#### BLE-L005: Connection retry doesn't verify peripheral state between attempts
- **File:** `KableBleConnectionManager.kt`
- **Description:** After a failed connection attempt, the code delays and retries without checking if the peripheral entered an error state.

#### BLE-L006: DiscoMode color restore coroutine may not complete on scope cancellation
- **File:** `DiscoMode.kt`, `stop()`
- **Description:** `scope.launch { sendCommand(command) }` in stop handler could be cancelled before restore command is sent.

#### BLE-L007: WaitingForRest timeout baseline may be inaccurate with cable drift
- **File:** `HandleStateDetector.kt`
- **Description:** When the 3-second timeout fires, current position captured as baseline. If cables settling, baseline could be mid-drift.

#### BLE-L008: sendWorkoutCommand peripheral null race (mitigated)
- **File:** `KableBleConnectionManager.kt`
- **Description:** Captures peripheral reference at function entry. The write would fail with a Kable disconnected error, which is properly handled. No actual data race.

#### BLE-L009: Android MTU negotiation failure not retried
- **File:** `BleExtensions.android.kt`
- **Description:** If MTU negotiation fails, default 23-byte MTU used. Kable may handle fragmentation internally, mitigating impact.

#### BLE-L010: BlePacketCapture.onPacket() called unconditionally
- **File:** `MetricPollingEngine.kt`
- **Description:** Per-packet function call overhead even in production (see M012, duplicated).

## Test Coverage Assessment

**Strong coverage:** ProtocolParser (30+), MonitorDataProcessor (30+), HandleStateDetector (35+), MetricPollingEngine (18), BleOperationQueue (6), KableBleConnectionManager (12)

**Gaps:**
- No integration tests for full connection lifecycle (scan -> connect -> poll -> disconnect)
- No tests for `parseMetricsPacket()` in KableBleRepository (RX notification path)
- No tests for `awaitResponse()` timeout behavior
- No tests for reconnection request emission logic
- No tests verifying `isExplicitDisconnect` flag behavior across connection cycles
- BleOperationQueue `write()` retry logic not tested

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 3 |
| Medium | 12 |
| Low | 10 |
| **Total** | **25** |

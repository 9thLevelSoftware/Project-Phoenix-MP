# Phase 11: MetricPollingEngine - Research

**Researched:** 2026-02-15
**Domain:** Kotlin Coroutines Job management, BLE polling loop extraction, concurrent lifecycle control
**Confidence:** HIGH

## Summary

Phase 11 extracts all four BLE polling loops (monitor, diagnostic, heuristic, heartbeat) plus their lifecycle management (start/stop/restart/partial-stop) from `KableBleRepository` into a standalone `MetricPollingEngine` class. This is the fifth module extraction in the v0.4.2 decomposition series, following the established inline-property delegation pattern from Phases 7-10 (BleOperationQueue, DiscoMode, HandleStateDetector, MonitorDataProcessor).

The extraction scope is well-defined: 4 polling loop functions (`startMonitorPolling`, `startDiagnosticPolling`, `startHeuristicPolling`, `startHeartbeat`), 3 heartbeat helper functions, 2 parse dispatch functions (`parseDiagnosticData`, `parseHeuristicData`), and 6 lifecycle management functions (`stopPolling`, `stopMonitorPollingOnly`, `restartDiagnosticPolling`, `restartMonitorPolling`, `startActiveWorkoutPolling`, `startObservingNotifications`). The state that moves includes 4 Job references, 1 Mutex, and diagnostic counters (`diagnosticPollCount`, `lastDiagnosticFaults`). Additionally, notification subscription code (REPS, VERSION, MODE observers) and firmware version reads currently inside `startObservingNotifications` need clear boundary decisions.

Unlike previous extractions that were either stateless (ProtocolParser), self-contained state machines (HandleStateDetector), or synchronous pipelines (MonitorDataProcessor), MetricPollingEngine manages concurrent coroutine Jobs with complex lifecycle dependencies. The critical invariant is Issue #222: `stopMonitorPollingOnly()` MUST preserve diagnostic and heartbeat polling. Additionally, the monitor polling loop contains timeout-disconnect logic (`MAX_CONSECUTIVE_TIMEOUTS`) that triggers a full disconnect -- this callback dependency is the key architectural decision.

**Primary recommendation:** Extract `MetricPollingEngine` as a class with constructor dependencies on `CoroutineScope`, `BleOperationQueue`, `MonitorDataProcessor`, `HandleStateDetector`, plus callback lambdas for metric emission, heuristic data emission, connection loss, and diagnostic data logging. The engine receives a `Peripheral` reference via its `startAll()` method (not constructor), matching the lifecycle where peripheral is only available after connection. Follow the same inline-property delegation pattern: `private val pollingEngine = MetricPollingEngine(...)` in KableBleRepository.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.9.0 | Job management, Mutex, delay, withTimeoutOrNull | Project's async runtime |
| Kable | 0.36.0 | `Peripheral.read()`, `Peripheral.observe()` for BLE operations | Project's KMP BLE library |
| Kermit | 2.0.4 | Diagnostic logging | Project's KMP logging library |
| kotlin-test | 2.0.21 | Unit testing job lifecycle | Project's test framework |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-coroutines-test | 1.9.0 | `runTest`, `TestScope`, `advanceTimeBy` for testing coroutine Jobs | Testing polling intervals and timeouts |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| 4 separate Job fields | Single `SupervisorJob` parent | Separate fields allow selective cancellation (Issue #222 `stopMonitorOnly`). Parent job would cancel all children -- wrong semantics. |
| Callback lambdas for events | Injected Flow references | Callbacks match Phase 8/10 pattern and avoid coupling engine to specific Flow types |
| Peripheral as method param | Peripheral in constructor | Peripheral is only available after connection. Method param matches lifecycle reality. |
| Mutex for monitor polling | No synchronization | The `monitorPollingMutex` prevents two monitor loops running concurrently when `startMonitorPolling` is called while a previous loop is still draining -- must preserve. |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- MetricPollingEngine.kt       # NEW: 4 polling loops + lifecycle management
  +-- MonitorDataProcessor.kt      # Phase 10 extraction (called by engine)
  +-- HandleStateDetector.kt       # Phase 9 extraction (called by engine)
  +-- DiscoMode.kt                 # Phase 8 extraction (independent)
  +-- BleOperationQueue.kt         # Phase 7 extraction (used by engine)
  +-- ProtocolParser.kt            # Phase 6 extraction (pure functions, used by engine)
  +-- ProtocolModels.kt            # Phase 6 data classes
  +-- BleExtensions.kt

shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
  +-- BleRepository.kt              # UNCHANGED (interface stays as-is)
  +-- KableBleRepository.kt         # MODIFIED: delegates polling to MetricPollingEngine

shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/
  +-- MetricPollingEngineTest.kt    # NEW: Job lifecycle and partial-stop tests
```

### Pattern 1: Engine with Peripheral-per-Method (Lifecycle Match)
**What:** The engine is constructed with long-lived dependencies (scope, queue, processor) but receives the `Peripheral` reference as a method parameter, not a constructor argument.
**When to use:** When the dependency (Peripheral) has a shorter lifecycle than the engine and may be replaced (reconnection).
**Why:** The Peripheral is only available after `connect()` succeeds and becomes null on disconnect. The engine outlives individual connections.

```kotlin
// Source: Derived from decomposition plan + established Phase 7-10 patterns
class MetricPollingEngine(
    private val scope: CoroutineScope,
    private val bleQueue: BleOperationQueue,
    private val monitorProcessor: MonitorDataProcessor,
    private val handleDetector: HandleStateDetector,
    private val onMetricEmit: (WorkoutMetric) -> Boolean,    // tryEmit to metricsFlow
    private val onHeuristicData: (HeuristicStatistics) -> Unit, // update heuristicData StateFlow
    private val onDiagnosticLog: (String) -> Unit,            // diagnostic logging callback
    private val onConnectionLost: suspend () -> Unit          // timeout disconnect handler
) {
    // Job references (move from KableBleRepository)
    private var monitorPollingJob: Job? = null
    private var diagnosticPollingJob: Job? = null
    private var heuristicPollingJob: Job? = null
    private var heartbeatJob: Job? = null

    // Monitor polling mutex (prevents concurrent monitor loops)
    private val monitorPollingMutex = Mutex()

    // Diagnostic state
    private var diagnosticPollCount: Long = 0
    private var lastDiagnosticFaults: List<Short>? = null

    fun startAll(peripheral: Peripheral)
    fun startMonitorPolling(peripheral: Peripheral, forAutoStart: Boolean = false)
    fun startDiagnosticPolling(peripheral: Peripheral)
    fun startHeuristicPolling(peripheral: Peripheral)
    fun startHeartbeat(peripheral: Peripheral)

    fun stopAll()                    // stopPolling() equivalent
    fun stopMonitorOnly()           // stopMonitorPollingOnly() equivalent
    fun restartDiagnosticAndHeartbeat(peripheral: Peripheral)
    fun restartAll(peripheral: Peripheral) // startActiveWorkoutPolling() equivalent
}
```

### Pattern 2: Inline Property Delegation (v0.4.2 Standard)
**What:** KableBleRepository instantiates MetricPollingEngine inline as a property, matching bleQueue, discoMode, handleDetector, monitorProcessor.
**When to use:** Every phase in this decomposition.
**Why:** Per [v0.4.2] decision: no DI changes, no Koin registration, inline properties only.

```kotlin
// In KableBleRepository:
// Declared AFTER bleQueue, monitorProcessor, handleDetector (init-order dependency)
private val pollingEngine = MetricPollingEngine(
    scope = scope,
    bleQueue = bleQueue,
    monitorProcessor = monitorProcessor,
    handleDetector = handleDetector,
    onMetricEmit = { metric -> _metricsFlow.tryEmit(metric) },
    onHeuristicData = { stats -> _heuristicData.value = stats },
    onDiagnosticLog = { msg -> log.d { msg } },
    onConnectionLost = { disconnect() }
)
```

### Pattern 3: Selective Job Cancellation (Issue #222)
**What:** `stopMonitorOnly()` cancels only the monitor polling job, leaving diagnostic, heuristic, and heartbeat running.
**When to use:** During bodyweight exercises to keep BLE link warm without emitting workout metrics.
**Why:** The 2-second heartbeat alone is insufficient for extended bodyweight exercises (90+ seconds). Diagnostic polling at 500ms keeps the link alive.

```kotlin
fun stopMonitorOnly() {
    monitorPollingJob?.cancel()
    monitorPollingJob = null
    // Diagnostic, heuristic, and heartbeat CONTINUE
}
```

### Pattern 4: Timeout Disconnect (POLL-03)
**What:** After `MAX_CONSECUTIVE_TIMEOUTS` (5) consecutive monitor read timeouts, trigger a full disconnect.
**When to use:** When the BLE stack stops responding (common on Android after sleep/wake cycles).
**Why:** Without this, the app would show "Connected" but receive no data indefinitely.

```kotlin
// Inside monitor polling loop:
if (consecutiveTimeouts >= BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS) {
    log.e { "Too many consecutive timeouts ($consecutiveTimeouts), triggering disconnect" }
    onConnectionLost()  // Callback to KableBleRepository.disconnect()
    return@withLock
}
```

### Anti-Patterns to Avoid
- **Storing Peripheral in the engine's constructor:** Peripheral lifecycle doesn't match engine lifecycle. It becomes null on disconnect and is replaced on reconnect. Pass as method parameter.
- **Cancelling all jobs from a parent Job:** Using a single parent SupervisorJob would make `stopMonitorOnly()` impossible -- it would cancel ALL children. Must use separate Job references.
- **Using `runBlocking` inside polling loops:** Would block the BLE dispatcher. All polling uses suspend functions with delay-based pacing.
- **Moving notification subscription code INTO the engine:** The REPS, VERSION, and MODE observer subscriptions (lines 854-915) are notification-based, not polling-based. They should stay in KableBleRepository or be extracted separately. The engine manages POLLING only.
- **Exposing Job references publicly:** Callers should use `stopAll()`, `stopMonitorOnly()`, etc. -- not manipulate Jobs directly.
- **Moving `parseMonitorData`, `parseDiagnosticData`, `parseHeuristicData` INTO the engine:** The engine calls these parsing functions but doesn't own them. `parseMonitorData` delegates to `monitorProcessor.process()` which is already extracted. The diagnostic and heuristic parsers are thin wrappers around the protocol parser -- they can either stay in KableBleRepository or move to the engine depending on what simplifies the call graph.

## Scope Decision: What Moves vs. What Stays

### Moves to MetricPollingEngine
| Item | Current Location (KableBleRepository) | Notes |
|------|---------------------------------------|-------|
| `monitorPollingJob` | line 193 | Job reference |
| `diagnosticPollingJob` | line 202 | Job reference |
| `heuristicPollingJob` | line 207 | Job reference |
| `heartbeatJob` | line 213 | Job reference |
| `monitorPollingMutex` | line 196 | Prevents concurrent monitor loops |
| `diagnosticPollCount` | line 203 | Diagnostic counter |
| `lastDiagnosticFaults` | line 204 | Diagnostic state |
| `startMonitorPolling()` | lines 1124-1213 | Core monitor polling loop |
| `startDiagnosticPolling()` | lines 984-1022 | Diagnostic polling loop |
| `startHeuristicPolling()` | lines 1035-1073 | Heuristic polling loop |
| `startHeartbeat()` | lines 765-794 | Heartbeat loop |
| `performHeartbeatRead()` | lines 802-814 | Heartbeat read helper |
| `sendHeartbeatNoOp()` | lines 821-828 | Heartbeat write helper |
| `stopPolling()` | lines 1477-1510 | Stop all polling |
| `stopMonitorPollingOnly()` | lines 1517-1526 | Partial stop (Issue #222) |
| `restartDiagnosticPolling()` | lines 1532-1554 | Restart diagnostic + heartbeat |
| `startActiveWorkoutPolling()` | lines 1443-1475 | Start all with restart logic |
| `restartMonitorPolling()` | lines 1431-1441 | Restart monitor only |
| `parseDiagnosticData()` | lines 1079-1096 | Diagnostic parse dispatch |
| `parseHeuristicData()` | lines 1103-1110 | Heuristic parse dispatch |

### Stays in KableBleRepository
| Item | Lines | Reason |
|------|-------|--------|
| `startObservingNotifications()` | 830-934 | Contains both notification subscriptions (REPS, VERSION, MODE) AND polling starts. Split: notification subscriptions stay, polling calls delegate to engine. |
| `onDeviceReady()` | 642-759 | Connection lifecycle (MTU, service discovery). Calls `startObservingNotifications()` which now calls engine. |
| REPS observer | 854-883 | Notification-based, not polling |
| VERSION observer | 886-901 | Notification-based, not polling |
| MODE observer | 903-915 | Notification-based, not polling |
| `tryReadFirmwareVersion()` | 940-960 | One-shot read, not polling |
| `tryReadVitruvianVersion()` | 966-978 | One-shot read, not polling |
| `parseMonitorData()` | 1650-1671 | Delegates to monitorProcessor -- could stay or move. RECOMMENDATION: Move into engine since it's only called from the monitor polling loop. |
| `parseRepsCharacteristicData()` | 1767-1807 | Called from REPS notification observer, stays |
| `parseMetricsPacket()` | 1682-1717 | Called from RX notification processing, stays |
| `parseRepNotification()` | 1724-1761 | Called from RX notification processing, stays |
| `processIncomingData()` | 1597-1611 | RX notification router, stays |
| Post-CONFIG diagnostic read | lines 1289-1309 | Inline in `sendWorkoutCommand()`, stays |
| Workout analysis logging | lines 1487-1494 | Uses handleDetector.minPositionSeen, move to engine's stopAll() |
| `detectedFirmwareVersion` | line 216 | Read during firmware version one-shot, stays |
| `negotiatedMtu` | line 219 | Set during MTU negotiation, stays |

### Boundary Decision: parseDiagnosticData + parseHeuristicData

These two functions are thin dispatchers:
- `parseDiagnosticData`: calls `parseDiagnosticPacket()` from ProtocolParser, logs faults
- `parseHeuristicData`: calls `parseHeuristicPacket()` from ProtocolParser, updates `_heuristicData`

**Recommendation:** Move both into the engine. They are ONLY called from the polling loops (plus one post-CONFIG diagnostic read in `sendWorkoutCommand()` -- handle via callback). This cleanly separates polling-loop concerns from the repository facade.

### Boundary Decision: Notification Subscriptions

`startObservingNotifications()` currently starts BOTH notifications AND polling. After extraction:

```kotlin
// KableBleRepository.startObservingNotifications() becomes:
private fun startObservingNotifications() {
    val p = peripheral ?: return

    // Firmware version reads (one-shot, best effort)
    scope.launch {
        tryReadFirmwareVersion(p)
        tryReadVitruvianVersion(p)
    }

    // Notification subscriptions (stay here - not polling)
    startRepsObserver(p)
    startVersionObserver(p)
    startModeObserver(p)

    // Polling loops (delegate to engine)
    pollingEngine.startAll(p)
}
```

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Job lifecycle management | Custom job tracking | `kotlin.coroutines.Job?.cancel()` + null assignment pattern | Standard Kotlin pattern, already proven in codebase |
| Concurrent polling prevention | Locking primitives | `kotlinx.coroutines.sync.Mutex` with `withLock` | Already using this pattern (monitorPollingMutex) |
| Timeout on BLE read | Manual timer | `withTimeoutOrNull(ms) { ... }` | Standard coroutine timeout, already used throughout |
| Polling interval pacing | Thread.sleep or busy-wait | `kotlinx.coroutines.delay(ms)` | Suspends without blocking, cooperative cancellation |
| Selective job cancellation | Complex state machine | Separate Job references per loop | Simplest approach, each Job independently cancellable |

**Key insight:** MetricPollingEngine is a Job lifecycle manager, not a processing engine. The processing (MonitorDataProcessor) and state detection (HandleStateDetector) are already extracted. The engine's job is to start/stop/restart coroutine Jobs that call those processors.

## Common Pitfalls

### Pitfall 1: Breaking stopMonitorOnly (Issue #222)
**What goes wrong:** After extraction, `stopMonitorOnly()` accidentally cancels diagnostic or heartbeat polling, causing BLE link degradation during bodyweight exercises.
**Why it happens:** If the engine uses a shared parent Job or groups Jobs incorrectly, cancelling one cancels siblings.
**How to avoid:** Each polling loop MUST have its own independent Job reference. `stopMonitorOnly()` cancels ONLY `monitorPollingJob`. Unit test: call `stopMonitorOnly()`, verify `diagnosticPollingJob?.isActive == true` and `heartbeatJob?.isActive == true`.
**Warning signs:** BLE disconnections during bodyweight exercises (90+ second gaps without monitor polling).

### Pitfall 2: Init-Order Dependency
**What goes wrong:** `MetricPollingEngine` is declared before its dependencies (`monitorProcessor`, `handleDetector`) in KableBleRepository, causing NPE or uninitialized access.
**Why it happens:** Kotlin property initialization order matches declaration order. If `pollingEngine` is declared before `monitorProcessor`, the processor reference passed to the engine is null/uninitialized.
**How to avoid:** Declare `pollingEngine` AFTER all its dependencies in KableBleRepository. Current order: bleQueue -> handleDetector -> monitorProcessor -> pollingEngine (must come last). Per [10-02] decision: `monitorProcessor` is declared after SharedFlow properties for init-order safety.
**Warning signs:** NPE on first BLE connection in the monitor polling loop.

### Pitfall 3: Peripheral Lifecycle Mismatch
**What goes wrong:** Engine stores a stale Peripheral reference from a previous connection, causing writes to a disconnected device.
**Why it happens:** If the engine stores Peripheral in a field (e.g., `startAll()` saves it), but `disconnect()` doesn't clear it, reconnection may use stale reference.
**How to avoid:** NEVER store Peripheral as a long-lived field in the engine. Pass it as a parameter to every method that needs it. Or if stored for convenience, clear it in `stopAll()`.
**Warning signs:** "GATT error 133" or "peripheral is null" crashes after reconnection.

### Pitfall 4: Monitor Polling Mutex Deadlock
**What goes wrong:** The monitor polling loop hangs indefinitely because the Mutex is locked by a cancelled but not-yet-drained coroutine.
**Why it happens:** When `startMonitorPolling()` is called while a previous loop is still running, the new loop waits for the Mutex. If the old loop's Job is cancelled but the `withLock` block hasn't released yet, there's a window where both are stuck.
**How to avoid:** The current code cancels the old job before starting the new one, and the new job's `monitorPollingMutex.withLock` waits for the cancelled job to release. This is correct and must be preserved. Do NOT add `if (mutex.isLocked) return` -- that caused a race condition (fixed in the current code, see comment at line 1146-1148).
**Warning signs:** Monitor polling stops working after `enableHandleDetection()` is called.

### Pitfall 5: Timeout Disconnect Calling disconnect() from Wrong Scope
**What goes wrong:** The timeout disconnect callback calls `disconnect()` from inside the polling loop's coroutine, which cancels the very coroutine it's running in, causing incomplete cleanup.
**Why it happens:** `disconnect()` cancels all polling jobs including the one currently executing. If called synchronously from the monitor polling loop, the loop may not complete its cleanup code.
**How to avoid:** Current code uses `scope.launch { disconnect() }` to launch disconnect in a separate coroutine (line 1185). The engine must use a callback (`onConnectionLost`) that the caller (KableBleRepository) handles by launching disconnect in its own scope.
**Warning signs:** Polling jobs remain active after timeout disconnect, or diagnostic counters aren't reset.

### Pitfall 6: Diagnostic Parse Called from Outside Engine
**What goes wrong:** After moving `parseDiagnosticData()` into the engine, the post-CONFIG diagnostic read in `sendWorkoutCommand()` (line 1301) can't call it.
**Why it happens:** `sendWorkoutCommand()` does a one-shot diagnostic read after sending config commands and needs to parse the result.
**How to avoid:** Either: (a) expose a public `parseDiagnosticData()` on the engine for the one-shot case, or (b) keep `parseDiagnosticData()` as a standalone function in the `data.ble` package (like `parseMonitorPacket()`), or (c) leave it in KableBleRepository as a private function since it's just a thin wrapper around `parseDiagnosticPacket()`.
**Recommendation:** Option (c) -- keep the thin wrapper in KableBleRepository for the one-shot case. The engine calls the same wrapper internally. This avoids making engine internals public.

### Pitfall 7: startActiveWorkoutPolling Restart Logic
**What goes wrong:** After extraction, the restart-if-not-active checks in `startActiveWorkoutPolling()` (lines 1460-1471) fail because they reference the engine's internal job state.
**Why it happens:** The current code checks `diagnosticPollingJob?.isActive != true` before restarting. After extraction, KableBleRepository can't see these fields.
**How to avoid:** The engine's `restartAll()` method should encapsulate the "restart if not active" logic internally. KableBleRepository just calls `pollingEngine.restartAll(peripheral)` and the engine handles conditional restart.
**Warning signs:** Diagnostic polling starts duplicate instances (two loops running concurrently).

## Code Examples

### Complete State Variable Inventory (to move from KableBleRepository)
```kotlin
// Source: KableBleRepository.kt

// Job references
private var monitorPollingJob: kotlinx.coroutines.Job? = null     // line 193
private var diagnosticPollingJob: kotlinx.coroutines.Job? = null   // line 202
private var heuristicPollingJob: kotlinx.coroutines.Job? = null    // line 207
private var heartbeatJob: kotlinx.coroutines.Job? = null           // line 213

// Synchronization
private val monitorPollingMutex = Mutex()                          // line 196

// Diagnostic state
private var diagnosticPollCount: Long = 0                          // line 203
private var lastDiagnosticFaults: List<Short>? = null              // line 204
```

**Total: 7 mutable fields + 1 Mutex** to move into MetricPollingEngine.

### Engine Constructor Signature
```kotlin
// Source: Derived from decomposition plan + Phase 7-10 patterns
class MetricPollingEngine(
    private val scope: CoroutineScope,
    private val bleQueue: BleOperationQueue,
    private val monitorProcessor: MonitorDataProcessor,
    private val handleDetector: HandleStateDetector,
    private val onMetricEmit: (WorkoutMetric) -> Boolean,
    private val onHeuristicData: (HeuristicStatistics) -> Unit,
    private val onConnectionLost: suspend () -> Unit
) {
    private val log = Logger.withTag("MetricPollingEngine")

    // Characteristic references from BleConstants
    private val txCharacteristic = BleConstants.txCharacteristic
    private val monitorCharacteristic = BleConstants.monitorCharacteristic
    private val diagnosticCharacteristic = BleConstants.diagnosticCharacteristic
    private val heuristicCharacteristic = BleConstants.heuristicCharacteristic

    // Job references
    private var monitorPollingJob: Job? = null
    private var diagnosticPollingJob: Job? = null
    private var heuristicPollingJob: Job? = null
    private var heartbeatJob: Job? = null

    // Monitor polling mutex
    private val monitorPollingMutex = Mutex()

    // Diagnostic state
    private var diagnosticPollCount: Long = 0
    private var lastDiagnosticFaults: List<Short>? = null
}
```

### Delegation from KableBleRepository
```kotlin
// In KableBleRepository constructor body:
private val pollingEngine = MetricPollingEngine(
    scope = scope,
    bleQueue = bleQueue,
    monitorProcessor = monitorProcessor,
    handleDetector = handleDetector,
    onMetricEmit = { metric ->
        val emitted = _metricsFlow.tryEmit(metric)
        if (!emitted && monitorProcessor.notificationCount % 100 == 0L) {
            log.w { "Failed to emit metric - buffer full?" }
        }
        // Handle detection is called by the engine internally
        emitted
    },
    onHeuristicData = { stats -> _heuristicData.value = stats },
    onConnectionLost = { disconnect() }
)

// Method delegation:
override fun stopPolling() = pollingEngine.stopAll()
override fun stopMonitorPollingOnly() = pollingEngine.stopMonitorOnly()
override fun restartDiagnosticPolling() {
    val p = peripheral ?: return
    pollingEngine.restartDiagnosticAndHeartbeat(p)
}
override fun restartMonitorPolling() {
    val p = peripheral ?: return
    pollingEngine.startMonitorPolling(p, forAutoStart = false)
}
override fun startActiveWorkoutPolling() {
    val p = peripheral ?: return
    pollingEngine.restartAll(p)
}
```

### startAll() Implementation
```kotlin
// Called from onDeviceReady() -> startObservingNotifications()
fun startAll(peripheral: Peripheral) {
    log.i { "Starting all polling loops" }
    startMonitorPolling(peripheral)
    startDiagnosticPolling(peripheral)
    startHeuristicPolling(peripheral)
    startHeartbeat(peripheral)
}
```

### stopAll() Implementation
```kotlin
fun stopAll() {
    val timestamp = currentTimeMillis()
    log.d { "STOP_DEBUG: [$timestamp] stopAll() called" }
    log.d {
        "STOP_DEBUG: Job states before cancel - monitor=${monitorPollingJob?.isActive}, " +
            "diagnostic=${diagnosticPollingJob?.isActive}, heuristic=${heuristicPollingJob?.isActive}, " +
            "heartbeat=${heartbeatJob?.isActive}"
    }

    // Log workout analysis (position range from HandleStateDetector)
    if (handleDetector.minPositionSeen != Double.MAX_VALUE &&
        handleDetector.maxPositionSeen != Double.MIN_VALUE) {
        log.i { "========== WORKOUT ANALYSIS ==========" }
        log.i { "Position range: min=${handleDetector.minPositionSeen}, max=${handleDetector.maxPositionSeen}" }
        log.i { "======================================" }
    }

    monitorPollingJob?.cancel()
    diagnosticPollingJob?.cancel()
    heuristicPollingJob?.cancel()
    heartbeatJob?.cancel()

    monitorPollingJob = null
    diagnosticPollingJob = null
    heuristicPollingJob = null
    heartbeatJob = null
    diagnosticPollCount = 0
    lastDiagnosticFaults = null

    val afterCancel = currentTimeMillis()
    log.d { "STOP_DEBUG: [$afterCancel] Jobs cancelled (took ${afterCancel - timestamp}ms)" }
}
```

### stopMonitorOnly() Implementation (Issue #222)
```kotlin
fun stopMonitorOnly() {
    log.d { "Stopping monitor polling only - diagnostic polling + heartbeat continue" }
    monitorPollingJob?.cancel()
    monitorPollingJob = null
    log.d {
        "Monitor-only stop: diagnostic=${diagnosticPollingJob?.isActive}, " +
            "heuristic=${heuristicPollingJob?.isActive}, heartbeat=${heartbeatJob?.isActive}"
    }
    // Diagnostic, heuristic, and heartbeat CONTINUE
}
```

### restartAll() Implementation (startActiveWorkoutPolling equivalent)
```kotlin
fun restartAll(peripheral: Peripheral) {
    log.i { "Restarting all polling loops" }
    log.d {
        "Issue #222 v16: Polling job states before restart - " +
            "monitor=${monitorPollingJob?.isActive}, " +
            "diagnostic=${diagnosticPollingJob?.isActive}, " +
            "heuristic=${heuristicPollingJob?.isActive}, " +
            "heartbeat=${heartbeatJob?.isActive}"
    }

    // Always restart monitor (forAutoStart=false for active workout)
    startMonitorPolling(peripheral, forAutoStart = false)

    // Conditionally restart others only if not already running
    if (diagnosticPollingJob?.isActive != true) {
        log.d { "Issue #222 v16: Restarting diagnostic polling" }
        startDiagnosticPolling(peripheral)
    }
    if (heartbeatJob?.isActive != true) {
        log.d { "Issue #222 v16: Restarting heartbeat" }
        startHeartbeat(peripheral)
    }
    if (heuristicPollingJob?.isActive != true) {
        log.d { "Issue #222 v16: Restarting heuristic polling" }
        startHeuristicPolling(peripheral)
    }
}
```

## Differences: Parent Repo vs. Current KMP Implementation

| Feature | Parent Repo (VitruvianBleManager) | KMP (KableBleRepository) | Impact on Extraction |
|---------|----------------------------------|--------------------------|---------------------|
| BLE library | Nordic BLE `.enqueue()` | Kable suspend-based reads | Engine uses Kable API directly |
| Dispatcher | `Dispatchers.Main` (Nordic requirement) | `Dispatchers.Default` (no Android requirement) | Engine uses scope from KableBleRepository |
| Operation serialization | Nordic auto-serializes | `BleOperationQueue` (Mutex) | Engine uses injected bleQueue |
| `stopMonitorOnly()` | Does not exist | Issue #222 addition | Engine must support partial stop |
| Timeout disconnect | Not present | `MAX_CONSECUTIVE_TIMEOUTS` = 5 | Engine owns this logic |
| Property naming | `propertyPollingJob` | `diagnosticPollingJob` | Same concept, KMP uses clearer name |
| Heartbeat write type | `WRITE_TYPE_NO_RESPONSE` | `WriteType.WithResponse` | V-Form requires WithResponse (Issue #222 v15.1) |
| Post-CONFIG diagnostic read | Not present | In `sendWorkoutCommand()` (lines 1289-1309) | Stays in KableBleRepository, not in engine |
| Heuristic data parsing | Minimal (not implemented) | Full parse to `HeuristicStatistics` | Engine calls `parseHeuristicPacket()` |

## Test Strategy

### Unit Tests: MetricPollingEngineTest
```kotlin
// Target: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngineTest.kt

class MetricPollingEngineTest {
    // === Job Lifecycle Tests ===
    @Test fun `startAll starts all 4 polling jobs`()
    @Test fun `stopAll cancels all 4 polling jobs`()
    @Test fun `stopAll sets all job references to null`()
    @Test fun `stopAll resets diagnostic counters`()

    // === Partial Stop Tests (Issue #222 - CRITICAL) ===
    @Test fun `stopMonitorOnly cancels only monitor job`()
    @Test fun `stopMonitorOnly preserves diagnostic job`()
    @Test fun `stopMonitorOnly preserves heartbeat job`()
    @Test fun `stopMonitorOnly preserves heuristic job`()

    // === Restart Tests ===
    @Test fun `restartAll starts monitor unconditionally`()
    @Test fun `restartAll skips diagnostic if already active`()
    @Test fun `restartAll restarts diagnostic if not active`()
    @Test fun `restartAll skips heartbeat if already active`()
    @Test fun `restartAll restarts heartbeat if not active`()

    // === Timeout Disconnect Tests (POLL-03) ===
    @Test fun `consecutive timeouts trigger disconnect after MAX_CONSECUTIVE_TIMEOUTS`()
    @Test fun `successful read resets consecutive timeout counter`()
    @Test fun `timeout counter does not trigger at MAX_CONSECUTIVE_TIMEOUTS minus 1`()

    // === Monitor Polling Mutex Tests ===
    @Test fun `concurrent startMonitorPolling waits for previous to finish`()
    @Test fun `startMonitorPolling cancels previous job before starting new`()

    // === Diagnostic Polling Tests ===
    @Test fun `restartDiagnosticAndHeartbeat starts both if not active`()
    @Test fun `restartDiagnosticAndHeartbeat skips if already active`()
}
```

### Testing Approach
Testing coroutine Job lifecycle requires `runTest` and `TestScope`. The engine's Jobs are launched in the provided scope, so using `TestScope` allows controlling time advancement.

**Challenge:** The polling loops read from a real `Peripheral`, which can't be mocked in KMP common tests (Kable's `Peripheral` is a concrete class, not an interface). Options:
1. **Test the lifecycle, not the BLE reads:** Verify Jobs start/stop/restart correctly without real BLE reads. Use a mock `BleOperationQueue` that returns test data.
2. **Test the timeout logic:** Use a `BleOperationQueue` that simulates timeouts (returns null from `withTimeoutOrNull`).
3. **Integration test with SimulatorBleRepository:** Verify end-to-end behavior.

**Recommendation:** Focus on lifecycle tests (start/stop/restart), partial-stop invariant (Issue #222), and timeout disconnect logic. These are the high-value tests that catch the most dangerous regressions. BLE read behavior is already tested by existing MonitorDataProcessorTest, HandleStateDetectorTest, and ProtocolParserTest.

## Open Questions

1. **Should handleDetector.processMetric() be called from the engine or passed up via callback?**
   - What we know: Currently in `parseMonitorData()`, after `monitorProcessor.process()` returns a metric, `handleDetector.processMetric(metric)` is called (line 1667). The engine has both the processor and detector as dependencies.
   - Recommendation: Call `handleDetector.processMetric(metric)` INSIDE the engine, right after `monitorProcessor.process()` returns a non-null metric. This keeps the processing pipeline together: read -> parse -> process -> detect -> emit. The callback `onMetricEmit` fires after both processing and detection are complete.

2. **Should `startObservingNotifications()` be split or delegated wholesale?**
   - What we know: The function currently mixes notification subscriptions (REPS, VERSION, MODE) with polling starts (monitor, diagnostic, heuristic). The decomposition plan lists it under MetricPollingEngine's code to move.
   - Recommendation: Split it. Notification subscriptions stay in KableBleRepository (they're not polling). The polling starts delegate to `pollingEngine.startAll(p)`. This gives a clean separation: subscriptions = notifications, engine = polling.

3. **Should `DiscoMode.stop()` be called from the engine or stay in KableBleRepository?**
   - What we know: Currently `startMonitorPolling()` calls `stopDiscoMode()` (line 1126) as a safety measure before starting monitor polling. The engine doesn't have a discoMode reference.
   - Recommendation: Add a `onBeforeMonitorStart` callback or simply call `discoMode.stop()` from KableBleRepository before calling `pollingEngine.startMonitorPolling()`. The engine shouldn't know about disco mode -- it's a cross-cutting concern managed by the repository.

4. **Should the engine manage the `peripheral` reference internally?**
   - What we know: Every polling method currently takes `peripheral` as a parameter. During a connection, the same peripheral is used for all methods. Storing it would simplify the API but creates lifecycle risks (stale reference).
   - Recommendation: Store peripheral in `startAll()` and clear in `stopAll()`. Methods that don't take a peripheral parameter use the stored reference. This simplifies the API while keeping lifecycle management in one place. Add a `peripheralOrNull` check at the start of each loop.

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **POLL-01**: MetricPollingEngine manages all 4 polling loops | Engine class with `startAll()`, `startMonitorPolling()`, `startDiagnosticPolling()`, `startHeuristicPolling()`, `startHeartbeat()` |
| **POLL-02**: stopMonitorOnly preserves diagnostic/heartbeat (Issue #222) | `stopMonitorOnly()` cancels only `monitorPollingJob`, verified by unit tests checking other jobs remain active |
| **POLL-03**: Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS works | Monitor polling loop tracks `consecutiveTimeouts`, calls `onConnectionLost()` callback when threshold reached |

## Success Criteria Mapping

| Criterion | Verification |
|-----------|-------------|
| 1. MetricPollingEngine manages monitor (10-20Hz), diagnostic (1Hz), heuristic (4Hz), heartbeat (0.5Hz) loops | All 4 `startXxx()` methods exist, each launching a Job with correct interval (`delay(0/500/250/2000)`) |
| 2. stopMonitorOnly preserves diagnostic and heartbeat polling (Issue #222) | Unit test: after `stopMonitorOnly()`, `diagnosticPollingJob?.isActive == true` AND `heartbeatJob?.isActive == true` |
| 3. Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS works correctly | Unit test: simulate 5 consecutive null reads -> `onConnectionLost()` callback fires |
| 4. Job lifecycle (start/stop/restart) managed atomically | `startAll()` starts all 4, `stopAll()` cancels all 4 and nulls references, `restartAll()` conditionally restarts |

## Extraction Checklist

- [ ] `MetricPollingEngine.kt` created in `data/ble/`
- [ ] 4 Job references moved from KableBleRepository
- [ ] `monitorPollingMutex` moved from KableBleRepository
- [ ] `diagnosticPollCount` + `lastDiagnosticFaults` moved from KableBleRepository
- [ ] `startMonitorPolling()` moved with mutex logic, timeout tracking, forAutoStart support
- [ ] `startDiagnosticPolling()` moved with interval pacing and fault logging
- [ ] `startHeuristicPolling()` moved with 4Hz interval pacing
- [ ] `startHeartbeat()` + `performHeartbeatRead()` + `sendHeartbeatNoOp()` moved
- [ ] `stopAll()` aggregates all job cancellation (was `stopPolling()`)
- [ ] `stopMonitorOnly()` cancels only monitor job (Issue #222)
- [ ] `restartDiagnosticAndHeartbeat()` conditionally restarts (Issue #222 v10)
- [ ] `restartAll()` conditionally restarts all loops (was `startActiveWorkoutPolling()`)
- [ ] `parseDiagnosticData()` and `parseHeuristicData()` moved or accessible
- [ ] Timeout disconnect callback (`onConnectionLost`) wired correctly
- [ ] MonitorProcessor reset called from engine's `startMonitorPolling()`
- [ ] HandleDetector.enable() called from engine's `startMonitorPolling(forAutoStart=true)`
- [ ] HandleDetector.processMetric() called from engine after process()
- [ ] Metric emission via callback (`onMetricEmit`)
- [ ] KableBleRepository declares `pollingEngine` AFTER bleQueue, monitorProcessor, handleDetector
- [ ] `startObservingNotifications()` split: notifications stay, polling delegates to engine
- [ ] KableBleRepository polling interface methods delegate to engine
- [ ] `disconnect()` calls `pollingEngine.stopAll()`
- [ ] `cleanupExistingConnection()` calls `pollingEngine.stopAll()`
- [ ] BleRepository interface UNCHANGED
- [ ] SimulatorBleRepository UNCHANGED
- [ ] FakeBleRepository UNCHANGED
- [ ] Unit tests for job lifecycle (start/stop)
- [ ] Unit tests for partial stop (Issue #222)
- [ ] Unit tests for timeout disconnect (POLL-03)
- [ ] Unit tests for conditional restart
- [ ] All existing tests pass
- [ ] `./gradlew :androidApp:testDebugUnitTest` succeeds

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` -- Full current implementation with all polling loops, lifecycle methods, and state variables. The definitive source for what needs to be extracted.
- `VitruvianBleManager.kt` (parent repo) -- Reference implementation showing polling patterns, job management, and the original architecture that KMP is adapting.
- `BleConstants.kt` -- Timing constants (poll intervals, timeouts, heartbeat intervals) that the engine uses.
- `kable-decomposition-plan.md` -- Original Phase 7 design spec for MetricPollingEngine with interface definition and migration plan.
- `ROADMAP.md` -- Phase 11 requirements (POLL-01, POLL-02, POLL-03) and success criteria.
- `MonitorDataProcessor.kt`, `HandleStateDetector.kt`, `DiscoMode.kt`, `BleOperationQueue.kt` -- Phase 7-10 extractions showing established delegation patterns.

### Secondary (MEDIUM confidence)
- `BleRepository.kt` (interface) -- Defines the public API that must remain unchanged.
- `SimulatorBleRepository.kt`, `FakeBleRepository.kt` -- Must continue to compile without changes.
- `ActiveSessionEngine.kt` -- Consumer of `startActiveWorkoutPolling()` and `restartMonitorPolling()`.

### Tertiary (LOW confidence)
- None (all patterns verified against actual codebase)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- Using existing project dependencies only, no new libraries needed
- Architecture: HIGH -- Follows established Phase 7-10 inline-property delegation pattern exactly
- Pitfalls: HIGH -- All identified from actual codebase analysis; Issue #222 partial-stop invariant is the highest-risk item and is well-documented
- Test strategy: MEDIUM -- Coroutine Job lifecycle testing with TestScope is standard but requires careful setup; Peripheral mocking in KMP is limited

**Research date:** 2026-02-15
**Valid until:** Indefinitely (extraction pattern is stable, polling logic is frozen)

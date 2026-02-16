# BLE/Networking Layer Refactoring Pitfalls

**Domain:** Decomposing BLE communication layers in mobile fitness apps (KMP + Kotlin Coroutines)
**Researched:** 2026-02-15
**Confidence:** HIGH (based on codebase analysis, KMP ecosystem knowledge, documented issue history)
**Scope:** Project Phoenix MP (KableBleRepository decomposition), generalized to BLE refactoring patterns

---

## Executive Summary

Refactoring BLE layers is deceptively high-risk. The domain has four critical characteristics that create regressions:

1. **Implicit Serialization Contracts** — BLE operations require strict ordering. A refactor that breaks operation atomicity causes fault 16384 (concurrent read+write). EXAMPLE: Issue #222 (bodyweight exercise stop command interleaving).

2. **Entangled State Machines** — Scanning → Connection → Characteristic Discovery → Polling → Handle State Detection all depend on each other. Extract one without accounting for upstream/downstream dependencies and the state machine breaks silently.

3. **Tight Performance Coupling** — The monitor polling loop runs at 10-20Hz. A refactor that adds 5 method calls, Flow emissions, or coroutine context switches can drop frames, miss rep counts, or trigger false auto-stops. Issue #210 required exhaustive tests because the impact is timing-sensitive.

4. **Platform-Specific Gremlins** — Nordic BLE on Android vs CoreBluetooth on iOS have opposite async contracts. Android forces synchronous operations on Binder threads; iOS requires async blocks to avoid deadlock. Refactoring toward a "unified" layer that doesn't respect these constraints causes crashes on one platform.

**Biggest Pitfall:** Developers assume "separating concerns" (splitting scanning from connection, parsing from polling) automatically makes code safer. It doesn't. BLE is not modular — it's a pipeline. Break the pipeline and you get data loss, race conditions, or silent failures that only appear after 15 minutes of heavy use.

---

## Critical Pitfalls

Mistakes that cause complete regressions, rewrites, or shipped bugs affecting users.

### Pitfall 1: Operation Interleaving — Losing Serialization Guarantees

**What goes wrong:**
When you refactor the BLE write/read pipeline into separate classes, each class may think it "owns" the characteristic and can read/write independently. The Nordic UART service has a single TX characteristic for bidirectional communication. If Thread A (polling monitor data) and Thread B (writing stop command) both access `peripheral.write()` without a shared lock, the BLE stack re-uses the write buffer. Android's BluetoothGattCharacteristic object is re-used internally — when notification A arrives while Thread B is writing, the notification payload overwrites the write buffer mid-transmission.

**Example from this codebase:**
Issue #222: Bodyweight exercises send BLE stop commands. The bug manifested as: machine receives stop command + corrupted next monitor poll in same packet (fault 16384 = "bad packet structure"). Root cause: `handleSetCompletion()` writes STOP while `monitorPollingLoop()` reads MONITOR in parallel. Fix: `BleOperationQueue` with Mutex serialization.

**Why it happens:**
- Developers assume "platform SDK handles thread safety" — it doesn't, not for bidirectional characteristics
- Refactoring extracts polling into one manager, writes into another — both use `peripheral.write()` independently
- The bug is probabilistic (visible only when read+write collide in a 10ms window) — doesn't appear in unit tests with slow clocks
- No clear error signal — the packet arrives corrupted, machine just ignores it or shows fault light

**Consequences:**
- BLE faults 16384 (corrupted packets), 32768 (failed characteristic writes), crash codes in machine logs
- Intermittent workout failures — some sets work, some don't, impossible to reproduce
- Data loss — metrics lost during interleaved read, next monitor poll returns stale data
- iOS specific: CoreBluetooth queues operations internally BUT only if you follow the pattern; violation causes deadlocks waiting for callbacks that never fire

**Prevention:**
- Create a **BleOperationQueue** with explicit Mutex that ALL read/write operations pass through
- Never call `peripheral.write()` or `peripheral.read()` directly from multiple coroutines
- Queue operations: `suspend fun queueWrite(uuid, bytes)`, `suspend fun queueRead(uuid)` with internal `withLock {}`
- Test with concurrent read+write stressing (thread pool of 10+ operations hammering the device for 30s)
- Add packet-level checksums so corrupted packets are detectable, not silent

**Detection:**
- Logs show fault 16384 or 32768 from machine (visible in ConnectionLogRepository)
- Bodyweight exercises send wrong BLE command sequence
- Monitor polling misses updates (position doesn't change for 3+ frames)
- Intermittent "characteristic write failed" errors in Logcat
- Machine enters error state requiring manual reset

**Phase risk:** PHASE 1 (extraction of BleOperationQueue) — **HIGH RISK**. This is the foundational mutation. Get it wrong and every subsequent phase inherits the bug.

**Mitigation in roadmap:** Make BleOperationQueue the very first extraction. Test it in isolation before any other changes. Revert entire v0.4.2 if fault 16384 appears in final integration tests.

---

### Pitfall 2: Breaking the Monitor Polling Hot Path — State Consistency Loss

**What goes wrong:**
The monitor polling loop calls `handleMonitorMetric()` ~10-20 times per second. This method reads state from 5 sub-systems (rep counter, auto-stop detector, position tracker, metrics collector, phase animation state), processes them in sequence, and emits state updates. When you extract these into sub-managers to "separate concerns," the hot path now looks like:

```
monitorData.forEach { metric ->
  repManager.processMetric(metric)           // reads/updates repCount
  positionTracker.processMetric(metric)      // reads/updates positionRange
  autoStopManager.checkStall(metric)         // reads repCount (is it stale?)
  metricsCollector.collect(metric)           // adds to buffer
  phaseAnimator.update(metric)                // reads repCount (stale again?)
}
```

The problem: `autoStopManager` reads `repCount` from `repManager` that was updated 1ms ago. But `positionTracker` just set a new position range that triggers a phase change that **requires** the old `repCount` to calculate the animation. The state is inconsistent because mutations happened out-of-order.

**Example from this codebase:**
Rep counter Issue #210 required extensive tests precisely because rep counting is timing-sensitive. The counter state `lastTopCounter`, `lastCompleteCounter`, `isPendingRep` are all read/written in `handleMonitorMetric()`. If rep counting was split into a separate sub-manager in v0.4.2, the next manager to read the rep count might get stale values from the previous frame, causing "rep counted twice" or "rep missed."

**Why it happens:**
- Developers think "different concerns = different classes" without realizing the concerns share tight temporal coupling
- Each sub-manager is individually correct (repManager counts reps correctly, positionTracker tracks position correctly) but their composition is broken
- The bug is deterministic but only appears under specific timing (10Hz polling + high rep velocity = faster state changes = more likely to catch stale reads)
- Tests with mocked data don't reproduce it because mock data is too regular

**Consequences:**
- Rep counter lags or jumps (counts rep twice, skips rep, counts warmup as working rep)
- Auto-stop triggers at wrong rep (fires after rep 10 when target is 10, or doesn't fire at end of set)
- Position visualization freezes or jumps
- Workout history has wrong rep counts
- User hits a wall mid-set because the machine thinks the set is done

**Prevention:**
- Keep `handleMonitorMetric()` as a single orchestration method in the parent manager
- Sub-managers expose pure functions: `(metric, currentState) -> StateUpdate` called synchronously in order
- Do NOT have sub-managers collect from `bleRepository.monitorData` independently — one collector at the top level
- Use immutable state snapshots: pass the entire `WorkoutState` to each sub-manager, each one reads (not writes) and returns updates
- Use a "MetricPipeline" pattern: metric → repCounter → positionTracker → autoStopCheck → metricsCollect, each stage is synchronous
- Test with real hardware at 20Hz polling with high-velocity exercises (reps every 1 second)

**Detection:**
- Rep count lags by 1-2 reps behind actual movement
- Auto-stop triggers at rep N-1 instead of rep N
- Position bar stops updating for 3+ frames
- Workout history shows different rep counts than the real-time display showed
- Characterization test shows 5ms latency in `handleMonitorMetric()` but refactored version shows 50ms

**Phase risk:** PHASE 2-3 (extraction of metric processing) — **CRITICAL RISK**. The hot path is where timing bugs hide. This phase is the highest risk for silent regressions.

**Mitigation in roadmap:** Write characterization tests for `handleMonitorMetric()` latency BEFORE any extraction. Measure the latency with real hardware polling. Establish a "latency budget" (e.g., <5ms per metric frame). Any refactor that increases latency >2ms is a regression.

---

### Pitfall 3: Connection State Machine Races — Silent Disconnect/Reconnect Loops

**What goes wrong:**
The connection state machine has 4 explicit states (Disconnected → Connecting → Connected → Disconnecting) and multiple implicit transitions (auto-reconnect on disconnect, explicit disconnect flag to suppress auto-reconnect, MTU negotiation, characteristic discovery timeout). When you extract this into a separate `BleConnectionManager`, you gain clarity but lose atomicity.

Current behavior (monolithic):
```
if (connectionState == CONNECTED && explicitDisconnectFlag == false) {
  // Auto-reconnect on unexpected disconnect
  attemptReconnect()
}
```

Refactored (separate manager):
```
// BleConnectionManager.kt
connectionManager.onDisconnect { ->
  if (!explicitDisconnectFlag) { // reads from parent manager
    connectionManager.reconnect()  // writes connectionState
  }
}

// Parent manager.kt
disconnect() {
  explicitDisconnectFlag = true
  connectionManager.disconnect()   // writes connectionState
}
```

Race: `onDisconnect` fires at the same time `disconnect()` is called. By the time `onDisconnect` reads `explicitDisconnectFlag`, it's been set. But the reconnection may have already started. Two outcomes:
1. Reconnect starts while disconnect is in progress → state machine goes into Connecting while trying to Disconnect → stuck state
2. Disconnect succeeds but reconnect trigger already fired → reconnect starts immediately → user sees disconnect+reconnect instead of clean stop

**Example from this codebase:**
Not explicitly documented in issues but the BLE connection manager has this code:
```kotlin
if (peripheral?.state == State.DISCONNECTED && !explicitDisconnect && isActive) {
  attemptReconnect()
}
```

The `isActive` flag is set by the session manager. If session manager stops the workout (sets `isActive = false`) at the same time peripheral disconnects, there's a race between:
- Session manager setting `isActive = false`
- Disconnect callback reading `isActive`

**Why it happens:**
- State machine assumes single-threaded execution or at least that state reads are atomic
- Coroutine callbacks are not synchronous — there's always a gap between "should I reconnect?" check and "reconnect" execution
- The bug is probabilistic and timing-dependent (only appears when disconnect + stop requests collide within 10ms)
- No clear error signal — the state machine just silently enters the wrong state

**Consequences:**
- Device stays connected after user stops workout (battery drain, next session has stale BLE state)
- Device enters reconnect loop (tries to connect every 2s forever because flag got out of sync)
- Back-button press doesn't actually disconnect (user thinks they stopped workout, machine still connected)
- iOS specific: CoreBluetooth peripheral may be in a partially-connected state (discover services succeeded but notification subscriptions are stale)

**Prevention:**
- Make connection state transitions **atomic**: use a single `StateFlow<ConnectionState>` as the source of truth
- All state mutations (explicit disconnect, auto-reconnect decision, error handling) go through a single method
- Use a `ConnectionStateTransition` enum to model valid transitions, validate all transitions through a state machine
- Guard flags (`explicitDisconnect`, `isActive`) should NOT be separate — they should be part of the `ConnectionState`
- Example:
  ```kotlin
  sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val peripheral: Peripheral) : ConnectionState()
    data class Disconnecting(val reason: String) : ConnectionState()
    data class Error(val error: Exception, val shouldRetry: Boolean) : ConnectionState()
  }
  ```
- Use explicit `onTransition` hooks that run synchronously before state change is published
- Test with concurrent disconnect+reconnect requests (thread pool hammering the peripheral with connect/disconnect/reconnect calls)

**Detection:**
- Logs show impossible state transitions (e.g., "Disconnecting → Connecting")
- Device connected after user stops workout (charge battery 0%, device still trying to poll)
- Reconnect loop visible in connection logs (every 2s for 30s)
- Back button unresponsive (doesn't disconnect device)
- Characteristic discovery timeout because peripheral went into error state

**Phase risk:** PHASE 1 (extraction of BleConnectionManager) — **HIGH RISK**. State machine transitions are fragile.

**Mitigation in roadmap:** Use explicit state machine validation. Before merging BleConnectionManager, add tests that hammer it with concurrent connect/disconnect/error scenarios. Verify final state is always valid.

---

### Pitfall 4: Losing Handle State Baseline Context — Cascading Phase Tracking Failures

**What goes wrong:**
The handle state detector (Issue #176) is a 4-state machine: UNKNOWN → AT_REST → GRABBED → AT_REST with hysteresis and position baseline tracking. The baseline is set once when the handle first goes from UNKNOWN to AT_REST, based on the first few position samples. This baseline is then used to detect "handle grabbed" (position > baseline + 8mm) and "handle released" (position < baseline + 5mm).

When you extract the handle state machine into its own class, it needs to:
1. Remember the baseline position across the entire workout session
2. Know the current rep phase (warmup vs working) because baseline changes between phases
3. Have access to position data from the monitor polling loop
4. Reset state when workout ends

Current behavior (monolithic): baseline lives as a field in the manager, survives across methods, automatically reset when workout ends.

Refactored: baseline lives in HandleStateDetector, but:
- HandleStateDetector doesn't know when workout ends (depends on parent manager to call `reset()`)
- If `reset()` isn't called, next workout starts with stale baseline (grabbed state from previous workout)
- HandleStateDetector doesn't know current rep phase, so it can't apply phase-specific hysteresis (overhead pulleys require different threshold than leg press)
- If position data is buffered instead of streamed, the first few samples that should set baseline are lost

**Example from this codebase:**
Issue #176: "Handle state machine baseline tracking for overhead pulleys." The pulley machine has a longer range of motion, so the "grabbed" threshold needed to be position-aware. If the baseline is set with position=100 (near bottom), then "grabbed" is position > 108. But the pulley's full ROM is 300mm, so the user has plenty of room to move. However, if baseline was stale from a previous exercise, grabbed detection triggers too early.

**Why it happens:**
- Baseline is an emergent property of the state machine (emerges from first N position samples) but refactoring treats it as data
- The state machine has a "hidden" dependency on the rep phase (warmup vs working)
- Lifecycle boundary is unclear: when does HandleStateDetector reset? When workout ends? When exercise changes? When session ends?
- No explicit contract between HandleStateDetector and its caller about what state it maintains

**Consequences:**
- First rep of a new workout doesn't register (baseline still set to old position)
- Handle state stays stuck in GRABBED for entire set (stale baseline)
- Position visualization shows wrong range (thinks handle has 20mm of motion instead of 200mm)
- Rep counting breaks because phase animation depends on handle state
- Overhead pulleys (long ROM) show grabbed state immediately (baseline set too high)

**Prevention:**
- Make baseline a separate concern: `PositionBaseline` class that is initialized once per workout
- Baseline initialization is explicit: `baseline = PositionBaseline.fromFirstN(samples, repPhase)`
- HandleStateDetector is pure: `(position, baseline, phase) -> HandleState`
- Parent manager owns baseline lifecycle: initializes it once, passes it to HandleStateDetector, resets it on workout end
- Phase-aware thresholds: threshold depends on rep phase (warmup vs working) or exercise type
- Test baseline initialization: verify that first 10 position samples correctly set baseline
- Test baseline reset: verify that finishing one exercise and starting another re-initializes baseline

**Detection:**
- First rep of new set doesn't register (position visible but rep counter stays at 0)
- Handle state is GRABBED for entire first set (baseline stuck)
- Position range indicator shows wrong value (20mm instead of 200mm)
- Overhead exercise shows GRABBED immediately after handle touches (threshold too aggressive)

**Phase risk:** PHASE 2 (extraction of HandleStateDetector) — **MEDIUM-HIGH RISK**. Baseline lifecycle is easy to get wrong.

**Mitigation in roadmap:** Characterize baseline behavior with actual hardware (different machines, different rep speeds). Test baseline reset explicitly (finish one exercise, start another, verify baseline is fresh). Add assertions to HandleStateDetector that baseline is initialized before use.

---

### Pitfall 5: Losing the Metric Persistence Contract — Data Loss in Workout History

**What goes wrong:**
`handleMonitorMetric()` collects metrics into a `collectedMetrics` list. At the end of the set, `handleSetCompletion()` reads this list and saves it to the database. If metric collection is extracted into a sub-manager, the list reference may not be preserved.

Current behavior (monolithic):
```kotlin
val collectedMetrics = mutableListOf<MetricSample>()

fun handleMonitorMetric(metric: WorkoutMetric) {
  collectedMetrics.add(MetricSample(...))
}

fun handleSetCompletion() {
  db.saveMetrics(collectedMetrics)  // writes directly to list
  collectedMetrics.clear()  // resets for next set
}
```

Refactored (sub-manager):
```kotlin
// MetricsManager.kt
class MetricsManager {
  private val collectedMetrics = mutableListOf<MetricSample>()
  fun recordMetric(metric: WorkoutMetric) { /* ... */ }
  fun getAndClearMetrics(): List<MetricSample> { /* ... */ }
}

// Parent manager
metricsManager.recordMetric(metric)  // record happens here
// ... later ...
val metrics = metricsManager.getAndClearMetrics()  // retrieve happens here
```

Issues:
1. If `getAndClearMetrics()` isn't called before the metrics list rolls over (max 10,000 samples), oldest samples are lost
2. If `getAndClearMetrics()` is called at the wrong time (during set, not at completion), metrics are cleared prematurely
3. If metrics manager gets its own scope and crashes, the list is lost without being persisted

**Example from this codebase:**
Not explicitly a reported issue, but the codebase has explicit buffer management:
```kotlin
collectedMetrics.add(metricSample)
if (collectedMetrics.size > MAX_METRICS_PER_SET) {
  // Drop oldest metrics
}
```

This protection only works if `collectedMetrics` is managed centrally. If refactored into a sub-manager that outlives the parent manager, stale references can cause data loss.

**Why it happens:**
- Developers assume "sub-manager handles its own data" without realizing it breaks the parent manager's contract to persist that data
- The persistence trigger (`handleSetCompletion()`) is in the parent manager, but the data lives in the sub-manager
- There's an implicit contract: "data collected during set must be persisted at set end" that breaks when managers are separate
- No clear error: data just doesn't appear in workout history

**Consequences:**
- Workout history has fewer metrics than expected (30 instead of 100 for a 100-sample set)
- Last few reps of a workout have no metric data (buffer rolled over before persistence)
- No power curve data (metrics lost between set completion and save)
- Analytics are missing data for entire sets

**Prevention:**
- Keep metrics collection and persistence together: metrics manager explicitly owns persistence
- Use a "MetricsSnapshot" pattern: at set completion, manager returns a snapshot that can be safely persisted
- Add metrics count validation: before saving, verify count matches expected (duration * 20Hz)
- Use a circular buffer with overflow detection: log warning if metrics are lost
- Test with long sets: run 50 reps and verify all metrics are persisted
- Add database constraints: workout_session_metrics count must equal expected count

**Detection:**
- Workout history shows fewer samples than expected
- Analytics charts have gaps
- Metrics collection logs show "buffer overflow"
- Set duration > expected duration but metric count is lower than expected

**Phase risk:** PHASE 2 (extraction of MetricsManager) — **MEDIUM RISK**. Easy to fix once detected, but data loss is permanent.

**Mitigation in roadmap:** Add explicit tests that verify metric counts for sets of varying durations. Track metrics lost in ConnectionLogRepository.

---

### Pitfall 6: Characteristic Discovery Timeout — Extraction Breaks MTU Negotiation Sequencing

**What goes wrong:**
The BLE connection sequence is strictly ordered:
1. Connect to peripheral
2. Discover services (20ms timeout)
3. Discover characteristics within service (10ms timeout)
4. Request MTU negotiation (15ms timeout)
5. Subscribe to notifications (10ms timeout)
6. Start polling loops (monitor, diagnostic, heuristic, heartbeat)

Each step depends on the previous. If you extract these into separate methods without preserving the sequencing, you get:
- Characteristic discovery starts before services are discovered (characteristics list is empty)
- MTU negotiation happens before characteristics are discovered (characteristic not yet available)
- Polling starts before MTU negotiation completes (writes fail due to low MTU)

Current behavior (monolithic):
```kotlin
connect(address) {
  peripheral = Scanner().find(address)
  services = peripheral.discoverServices()  // waits for completion
  characteristics = services.discover()     // waits for completion
  mtu = peripheral.requestMtu()            // waits for completion
  subscribeToNotifications()                 // waits for completion
  startPolling()                             // all prerequisites satisfied
}
```

Refactored (separate methods, improper sequencing):
```kotlin
connectionManager.connect(address)  // starts connecting asynchronously
connectionManager.discoverServices() // starts service discovery asynchronously
connectionManager.discoverCharacteristics() // starts BEFORE discovery completes
connectionManager.requestMtu()       // starts BEFORE characteristics discovered
startPolling()                        // everything happens in parallel
```

Result: Characteristic discovery fails because services aren't discovered yet. Polling starts with zero characteristics. Writes fail due to low MTU.

**Example from this codebase:**
The current KableBleRepository has this sequence:
```kotlin
withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
  peripheral.discover()  // blocks until services + characteristics discovered
  requestMtuIfSupported(peripheral)  // happens after discovery
  setupNotifications()  // happens after MTU
  startPollingLoops()    // happens after everything
}
```

The refactored version in the decomposition plan must preserve this ordering.

**Why it happens:**
- Developers extract each step into its own method thinking they can be called independently
- The sequencing dependency is implicit (not enforced by types)
- Tests with mocked devices don't discover the problem (mocks complete instantly in parallel)
- Hardware tests work at first (if you manually wait between steps), but under load (real polling hammering device), the race appears

**Consequences:**
- Device never fully connects (gets stuck after service discovery)
- Characteristics list is empty (discovery failed)
- Polling loop tries to write to invalid characteristic UUID (gets error)
- MTU stays low (247 bytes → 20 bytes default) causing large reads to fail
- Notifications never subscribe (subscribe call on unknown characteristic)

**Prevention:**
- Use `suspend fun` for each step: each method is a coroutine that completes when that step finishes
- Chain them explicitly: `connect().andThen { discover() }.andThen { requestMtu() }.andThen { startPolling() }`
- Use StateFlow for connection state, validate state transitions (can't discover characteristics until services discovered)
- Create a `ConnectionSetupSequence` that models the exact steps and their dependencies
- Test with actual hardware: connect to device, verify each step succeeds before next starts
- Add timeouts at each step with clear error messages (what step failed)

**Detection:**
- Logs show "Characteristic not found" or "Service not discovered"
- Polling loop receives "characteristic write failed" errors
- Device shows "Connected" but no metrics (polling failed)
- MTU negotiation logs show "mtu=20" instead of "mtu=247"

**Phase risk:** PHASE 1 (extraction of BleConnectionManager) — **HIGH RISK**. Sequencing bugs break the entire BLE layer.

**Mitigation in roadmap:** Enforce sequencing with types. Use a state machine that only allows transitions in the correct order. Test every transition with real hardware before merging.

---

### Pitfall 7: Packet Parsing in Hot Path — Hidden Performance Regression

**What goes wrong:**
Packet parsing (bytes → domain objects) happens inside the monitor polling loop. When you extract it to a separate `ProtocolParser` class, you might accidentally add allocations, Flow emissions, or coroutine context switches that drop frames.

Current behavior (monolithic, optimized):
```kotlin
fun parseMonitorData(bytes: ByteArray): WorkoutMetric {
  position = (bytes[0].toInt() shl 8) or bytes[1].toInt()
  velocity = calculateVelocity(position)  // uses cached state
  return WorkoutMetric(position, velocity, /* ... */)  // single allocation
}

// called 10-20Hz, total latency budget < 5ms
```

Refactored (separate parser):
```kotlin
object ProtocolParser {
  fun parseMonitorData(bytes: ByteArray): WorkoutMetric {
    // same logic
  }
}

// called 10-20Hz, but now:
// - each call allocates parser instance if not lazy singleton
// - if parser is a Flow, each parse emits to a new collector
// - if parser is async, it crosses thread boundary
```

Result: Latency increases from 0.5ms to 2ms per parse. At 20Hz, you lose 30ms per second = visible frame drops.

**Example from this codebase:**
The current parser has EMA (exponential moving average) for velocity smoothing:
```kotlin
lastSmoothedVelocity = EMA_ALPHA * currentVelocity + (1 - EMA_ALPHA) * lastSmoothedVelocity
```

If refactored into a stateless `ProtocolParser` object, it loses `lastSmoothedVelocity` state. The parser must be given the previous state, which adds a parameter, which adds an allocation in a hot path.

**Why it happens:**
- Developers assume "extracting to a class" is free — it's not in hot paths
- Performance requirements (20Hz, <5ms latency) are not explicit in the code
- Tests with mock data complete instantly, so latency is never measured
- The regression appears as "dropped frames" or "lags during heavy reps" which are hard to debug

**Consequences:**
- Frame drops during workouts (position bar stutters)
- Rep counter lags by 1-2 reps
- Auto-stop misfires (velocity calculation is delayed)
- Battery drain (CPU stays busy longer per polling cycle)
- User reports "app is choppy during workouts"

**Prevention:**
- Measure latency: add timing instrumentation around `handleMonitorMetric()` in debug builds
- Establish latency budget: "parseMonitorData must be < 1ms at 20Hz"
- Test the hot path: run 30s of polling at 20Hz and measure 99th percentile latency
- Use allocation profiling: verify refactored parser doesn't allocate per-call
- Keep stateful operations (EMA smoothing, velocity calculation) together, not split
- Consider inline functions for parser: `inline fun parseMonitorData(bytes) -> WorkoutMetric`

**Detection:**
- Characterization tests show latency increased (0.5ms → 2ms)
- Frame drops visible in UI during high-rep workouts
- Logcat shows "Jank" or ANR warnings
- Rep counter UI lags actual machine reps by 1+ reps

**Phase risk:** PHASE 2 (extraction of ProtocolParser) — **MEDIUM RISK**. Easy to detect with measurement, recoverable with inlining.

**Mitigation in roadmap:** Measure latency BEFORE refactoring. Establish latency budget. If refactored version exceeds budget, inline the parser.

---

### Pitfall 8: iOS Specific — Async Callback Hell From CoreBluetooth

**What goes wrong:**
Android's Kable library provides a relatively synchronous API. iOS's CoreBluetooth is fully async: every operation (discover services, read characteristic, write characteristic) requires a callback. Refactoring that assumes "all platforms work the same" breaks iOS.

Example:
```kotlin
// Android works fine
val services = peripheral.discoverServices()  // suspends, returns immediately

// iOS needs async callback
peripheral.discoverServices()  // returns immediately
// later, when CBCentralManagerDelegate.didDiscoverServices fires:
// - read characteristics
// - request MTU
// - setup notifications
```

When you refactor the connection sequence, you might accidentally serialize it correctly on Android but break it on iOS by:
- Waiting for a callback that never comes (CoreBluetooth didn't call it because of a bug in characteristic UUID matching)
- Reading characteristics before `didDiscoverServices` fired (list is empty)
- Requesting MTU without discovering characteristics first (CoreBluetooth ignores the request)

**Example from this codebase:**
The DriverFactory.ios.kt has a 996-line defense system specifically because iOS SQLDelight has edge cases. BLE has similar platform-specific quirks:
- iOS caches discovered services. If your app discovers once, then tries again, iOS returns cached list (stale UUIDs?)
- iOS requires characteristics to be discovered before reading. Android's Kable library hides this.
- iOS limits concurrent operations to 1 (write while read is pending fails silently)
- iOS has a 30-second timeout for all operations (services, characteristics, reads, writes)

**Why it happens:**
- KMP + Kable library abstracts platform differences, making developers think they're the same
- iOS-specific tests are sparse (requires macOS, physical device)
- The bugs are probabilistic (appear after 15 minutes of heavy use, not in 2-minute tests)
- No clear error signal (operation just times out silently)

**Consequences:**
- iOS app never fully connects (gets stuck waiting for callback)
- iOS app connects but can't read metrics (characteristics not discovered)
- iOS app connects, reads work for 15 minutes then fail (CoreBluetooth timeout)
- Users report "iPhone crashes after 15 minutes of workout" (actually watchdog timeout)

**Prevention:**
- Test iOS separately: use actual iOS device, not simulator
- Use separate implementations for platform-specific sequencing: `actual class BleConnectionManager(...)`
- Understand CoreBluetooth contract: operation → callback → next operation (strictly serial)
- Add iOS-specific tests: verify characteristics are discovered before reading, verify callbacks fire in expected order
- Use timeouts generously on iOS (30s per operation)
- Log all CoreBluetooth callbacks to help debug state machine

**Detection:**
- iOS app hangs during connection (characteristic discovery timeout)
- iOS logs show "didDiscoverServices not called" or "didDiscoverCharacteristicsFor not called"
- iOS app crashes after 15 minutes (watchdog timeout waiting for callback)
- Characteristic UUIDs don't match (case sensitivity in UUID parsing)

**Phase risk:** PHASE 1 (BleConnectionManager) — **HIGH RISK on iOS**. Sequencing bugs are iOS-specific.

**Mitigation in roadmap:** Require iOS testing before merging any phase. iOS simulator doesn't reliably test Bluetooth. Use real device.

---

## Moderate Pitfalls

Mistakes that cause specific subsystem failures or data corruption, but don't break the entire layer.

### Pitfall 9: Forgetting to Reset Mutable State Between Workouts

**What goes wrong:**
The monolithic manager has ~50 mutable fields (positions, counters, flags, timers). When `resetForNewWorkout()` is called, all of them are explicitly reset. When you split these fields across sub-managers, some reset() calls might be missed.

Example:
```kotlin
// Before refactor
fun resetForNewWorkout() {
  position = 0
  velocity = 0
  lastTopCounter = 0
  lastCompleteCounter = 0
  stallStartTime = 0
  isCurrentExerciseBodyweight = false
  // 45 more resets...
}

// After refactor (split into sub-managers)
fun resetForNewWorkout() {
  positionManager.reset()  // might not reset position state
  repManager.reset()       // might not reset rep counters
  autoStopManager.reset()  // what about stallStartTime?
}

// If RepManager.reset() forgets to reset lastTopCounter:
// next workout starts with lastTopCounter = 10 (from previous)
// first rep isn't counted (counter delta is 0)
```

**Why it happens:**
- The reset contract is implicit (not enforced by types)
- Developers assume "I'll remember to reset this field" without documenting it
- Tests initialize fresh mutable objects, so state carryover doesn't appear in tests
- The bug is visible as "first rep of new workout not counted" which seems like a separate issue

**Prevention:**
- Make a `reset()` interface that each sub-manager must implement
- Call `super.reset()` at the start of `resetForNewWorkout()`
- Test state carryover: run workout 1, verify data is saved, run workout 2, verify state is fresh
- Add assertions: at start of new workout, verify all sub-manager state is zero/null

**Detection:**
- First rep of new workout not counted (or counted incorrectly)
- Auto-stop triggers at wrong rep (stale stallStartTime)
- Position shows stale value at workout start

**Phase risk:** PHASE 1-2 (early extraction) — **MEDIUM RISK**. Easy to miss in review.

---

### Pitfall 10: Job Cancellation Order — Dangling Collectors and Coroutine Leaks

**What goes wrong:**
The manager has 5+ Job references that need to be cancelled in the correct order:
- `monitorDataCollectionJob` (reads from BLE)
- `autoStartJob` (countdown timer)
- `restTimerJob` (between sets)
- `bodyweightTimerJob` (for bodyweight exercises)
- `workoutJob` (overall session)

When extracted, each sub-manager might have its own Jobs. If they're not cancelled in the right order, you get orphaned collectors.

Example:
```
// Job hierarchy
workoutScope
├── monitorCollectionJob (started when workout begins)
└── autoStopManager.scope
    └── velocityStallDetectionJob (depends on monitor data)

// When workout stops, we cancel workoutScope
// But if autoStopManager has its own scope, the Job might outlive the data it depends on
// Result: velocityStallDetectionJob tries to read velocity from closed Flow → crash
```

**Prevention:**
- All sub-managers share the same parent scope (passed from viewModelScope)
- Don't create sub-manager-scoped jobs; use the shared scope
- Document job cancellation order (what jobs cancel when)
- Test: run workout, stop mid-set, verify all jobs are cancelled

**Phase risk:** PHASE 1-2 (early extraction) — **MEDIUM RISK**. Manifests as crashes.

---

### Pitfall 11: Polling Loop Starvation — One Loop Blocks Others

**What goes wrong:**
There are 4 independent polling loops: monitor (10-20Hz), diagnostic (1Hz), heuristic (4Hz), heartbeat (0.5Hz). If they're extracted into separate coroutines without proper isolation, a slow operation in one loop can starve the others.

Example:
```kotlin
// Monitor loop hangs for 100ms reading position
// This blocks the dispatcher
// Heuristic loop can't run (waiting for same thread)
// Result: heuristic data is 100ms late
```

**Prevention:**
- Use separate dispatchers for high-frequency (monitor) vs low-frequency (diagnostic, heuristic)
- Monitor loop should NOT do heavy work (parsing is <1ms, but database writes are 100ms)
- Test with load: run monitor loop at 20Hz while simultaneously triggering diagnostic reads

**Phase risk:** PHASE 2 (extraction of MetricPollingEngine) — **MEDIUM RISK**.

---

## Integration Pitfalls

Mistakes that don't break individual components but break their composition.

### Pitfall 12: Lack of Contract Documentation — Extractors Don't Know Invariants

**What goes wrong:**
The monolithic manager works because there's implicit knowledge: "position is always valid after first 5 samples", "rep counter is updated before auto-stop check", "baseline never changes mid-set". When you extract without documenting these contracts, the next extractor violates them.

**Prevention:**
- Document invariants: "Position is only valid after state.value == WORKOUT_ACTIVE AND monitorDataCollected > 5"
- Document ordering: "rep counter updated BEFORE auto-stop check"
- Document lifecycle: "baseline initialized once per workout, reset on workout end"
- Add contract validation: assertions in hot path that check invariants

---

### Pitfall 13: Testing Only the Happy Path — Missing Edge Cases

**What goes wrong:**
Refactoring tests usually verify "connection succeeds, polling works, metrics collected, workout ends." They don't test:
- Connection fails mid-polling (device suddenly disconnects)
- Polling loop receives corrupted packets
- User presses stop during auto-reconnect
- Two workouts back-to-back (state reset edge case)
- Rep counting during high-speed exercises (position changes faster than polling rate)

**Prevention:**
- Characterization tests: capture behavior of original monolith, then verify refactored version matches
- Edge case tests: disconnect during polling, corrupted packets, rapid state changes
- Hardware tests: use real devices, not mocks

---

## Phase-Specific Warnings

| Phase | Highest-Risk Pitfall | Mitigation |
|-------|----------------------|-----------|
| **Phase 1: Constants + OperationQueue** | Operation Interleaving (#1) | BleOperationQueue MUST serialize all reads/writes with Mutex. Test with concurrent stress. |
| **Phase 1: Connection Manager** | Connection State Machine Races (#3), Characteristic Discovery Timeout (#6) | Enforce sequential state transitions. Test with real hardware. iOS device required. |
| **Phase 2: Monitor Processor** | Breaking Hot Path (#2), Packet Parsing Performance (#7) | Measure latency BEFORE refactoring. Establish budget (<5ms). Test with real device polling at 20Hz. |
| **Phase 2: Metric Polling Engine** | Polling Loop Starvation (#11), Job Cancellation (#10) | Separate dispatchers for monitor (high-freq) vs diagnostic (low-freq). Document job lifecycle. |
| **Phase 3: Handle Detector** | Baseline Context Loss (#4) | Characterize baseline behavior with actual hardware. Test baseline reset. |
| **Phase 3: Metrics Manager** | Data Loss (#5) | Track metrics count. Test long sets. Add database constraints. |

---

## Prevention Strategies (Cross-Cutting)

### 1. Measurement-Driven Refactoring

Before extracting anything, measure:
- Latency of `handleMonitorMetric()` (should be <5ms)
- Packet parse time (should be <1ms)
- Job startup overhead (should be <10ms)
- Memory allocation per cycle (should be <1KB)

After refactoring, re-measure. If any metric regresses >20%, revert.

### 2. Hardware-First Testing

- Do NOT test only with FakeBleRepository
- Test with actual Vitruvian hardware before merging any phase
- Test iOS with physical device (simulator is unreliable for BLE)
- Test both machine types (V-Form and Trainer+) if possible

### 3. Characterization Tests Before Refactoring

- Record the exact behavior of the monolithic manager (rep counts, handle states, metrics, auto-stop triggers) on real hardware
- After refactoring, verify the refactored version produces identical behavior
- Compare logs: same BLE packets? Same state transitions? Same metric counts?

### 4. Explicit State Machine Validation

- Use a state machine validation function that runs at every transition
- Verify transitions are legal (can't go Connecting → Idle without Connecting → Connected first)
- Log all transitions with timestamps (helps debug race conditions)

### 5. Contract Documentation and Assertions

- Document every invariant in comments
- Add assertions that validate invariants in hot paths
- Use types to enforce contracts: if a function needs post-discovery characteristics, take `DiscoveredCharacteristics` type, not raw list

### 6. Gradual Rollout with Monitoring

- Deploy refactored layer to small % of users first (beta channel)
- Monitor metrics: connection success rate, BLE faults, workout completion rate
- Roll back immediately if fault 16384 appears in production

---

## Detection and Recovery

### How to Know Refactoring Broke Something

1. **Fault codes in machine**: 16384, 32768 (BLE protocol errors)
2. **Connection logs**: impossible state transitions, reconnect loops, characteristic discovery timeouts
3. **Workout data**: duplicate session saves, missing metrics, wrong rep counts
4. **Frame drops**: characterization tests show latency regression
5. **User reports**: "first rep doesn't count", "workout stops randomly", "device disconnects after 15 minutes"

### Recovery Plan

If a regression is found:
1. **Immediate**: Deploy rollback to stable version
2. **Within 2 hours**: Identify which phase caused regression (A/B test refactored vs monolithic)
3. **Within 24 hours**: Add characterization test for the broken behavior
4. **Within 1 week**: Fix the root cause, re-test, redeploy

---

## Open Questions for Phase Planning

1. **Performance Budget**: What's the acceptable latency increase for `handleMonitorMetric()`? (current: 0.5ms, propose: <2ms)
2. **iOS Testing**: Can we get iOS device access for testing BleConnectionManager? Simulator is insufficient.
3. **Backward Compatibility**: Do existing users with incomplete workouts need data migration after refactoring?
4. **Rollback Strategy**: If Phase 1 deploys and breaks on device, can we quickly rollback to monolith?
5. **Monitoring**: What metrics in production (Crashlytics, analytics) will alert us to refactoring regressions?

---

## Summary for Roadmap

**Highest-Risk Phases (Require Deep Testing):**
1. Phase 1: BleOperationQueue (serialization) + BleConnectionManager (sequencing)
2. Phase 2: Monitor data processor (hot path latency)
3. Phase 3: Handle detector (baseline lifecycle)

**Recommended Testing Approach:**
- Characterization tests capturing monolith behavior BEFORE any refactoring
- Hardware testing with real device after each phase
- iOS device testing before merging any connection-related changes
- Latency measurement at hottest paths (>20Hz operations)
- Edge case testing (disconnect mid-polling, corrupted packets, rapid state changes)

**Rollback Criteria:**
- BLE fault codes appear in device logs or connection logs
- Latency regression >20% in characterization tests
- Workout data inconsistencies (duplicate saves, missing metrics)
- Connection failure rate increases >5% vs baseline

---

## Sources

### BLE Platform-Specific Guidance
- [Race condition between 'onCharacteristicWrite()' and 'onCharacteristicChanged()' operations on single characteristic - RxAndroidBle Issue #694](https://github.com/dariuszseweryn/RxAndroidBle/issues/694)
- [Making Android BLE Work — Part 3 - Martijn van Welie](https://medium.com/@martijn.van.welie/making-android-ble-work-part-3-117d3a8aee23)
- [How to Make Bluetooth on Android More Reliable - freeCodeCamp](https://www.freecodecamp.org/news/how-to-make-bluetooth-on-android-more-reliable/)
- [4 Tips To Make Android BLE Actually Work - Punch Through](https://punchthrough.com/android-ble-development-tips/)
- [Android BLE: The Ultimate Guide To Bluetooth Low Energy - Punch Through](https://punchthrough.com/android-ble-guide/)
- [Navigating iOS Bluetooth: Lessons on Background Processing, Pitfalls, and Personal Reflections - Medium](https://medium.com/@sanjaynelagadde1992/navigating-ios-bluetooth-lessons-on-background-processing-pitfalls-and-personal-reflections-5e5379a26e02)

### Kotlin Coroutines and Concurrency
- [Solving problem of race condition in Kotlin coroutines - Medium](https://medium.com/@1mailanton/solving-problem-of-race-condition-in-kotlin-coroutines-958abfceab37)
- [How to Prevent Race Conditions in Coroutines - Dave Leeds on Kotlin](https://typealias.com/articles/prevent-race-conditions-in-coroutines/)
- [Mastering Concurrency: Preventing Race Conditions in Kotlin Coroutines - Medium](https://medium.com/@sivavishnu0705/mastering-concurrency-preventing-race-conditions-in-kotlin-coroutines-96471c1720bb)
- [Shared mutable state and concurrency - Kotlin Documentation](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)
- [Best practices for coroutines in Android - Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)

### BLE Development Trends
- [BLE App Development in 2026: Trends, Opportunities & Best Practices - BLE App Developers](https://blogs.bleappdevelopers.com/ble-app-development-in-2026-trends-opportunities-best-practices/)
- [Developing BLE Apps: Challenges, Benefits & Best Practices - Closeloop](https://closeloop.com/blog/developing-ble-apps-2025-guide/)

### State Machine Testing
- [State Machine Mutation-based Testing Framework for Wireless Communication Protocols - arXiv](https://arxiv.org/html/2409.02905v4)
- [Developing state machines with test-driven development - Embedded](https://www.embedded.com/developing-state-machines-with-test-driven-development/)
- [Model-Based Testing Using State Machines - Abstracta](https://abstracta.us/blog/software-testing/model-based-testing-using-state-machines/)

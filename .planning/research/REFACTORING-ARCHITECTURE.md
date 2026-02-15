# Architecture Patterns: BLE Layer Decomposition

**Project:** Project Phoenix MP — KableBleRepository Decomposition
**Researched:** 2026-02-15
**Scope:** System structure, component boundaries, patterns to follow and avoid

---

## Recommended Architecture

### Current State (Monolithic)

```
KableBleRepository (2,886 lines, 1 class)
│
├─ State (50+ mutable fields)
│  ├─ connection: Peripheral
│  ├─ connectionState: StateFlow<ConnectionState>
│  ├─ monitorData: SharedFlow<WorkoutMetric>
│  ├─ handleState: StateFlow<HandleState>
│  ├─ position (current), lastPosition, positionRange
│  ├─ velocity (current), smoothedVelocity
│  ├─ lastTopCounter, lastCompleteCounter
│  ├─ stallStartTime, restStartTime, bodyweightStartTime
│  ├─ explicitDisconnect flag
│  ├─ 40+ more mutable vars
│
├─ Methods (procedural, interleaved)
│  ├─ scan() → find device
│  ├─ connect() → establish BLE connection
│  ├─ discover() → services, characteristics, MTU
│  ├─ setupNotifications() → subscribe to notifications
│  ├─ startMonitorPolling() → 10-20Hz read loop
│  ├─ startDiagnosticPolling() → 1Hz read loop
│  ├─ startHeuristicPolling() → 4Hz read loop
│  ├─ startHeartbeat() → 0.5Hz write loop
│  ├─ parseMonitorData() → bytes → WorkoutMetric
│  ├─ updateHandleState() → position → HandleState
│  ├─ handleMonitorMetric() → emit to flow
│  ├─ disconnect() → teardown
│  └─ 20+ more methods
│
└─ Problems
   ├─ God object: all concerns in one class
   ├─ No clear responsibilities
   ├─ Impossible to test in isolation
   ├─ Race conditions due to mixed thread safety (@Volatile, Mutex, nothing)
   ├─ State leakage between sessions
   └─ Coroutine job management scattered
```

### Target State (8 Modules)

```
┌─────────────────────────────────────────────────────────────────┐
│  BleRepository (Interface)                                      │
├─────────────────────────────────────────────────────────────────┤
│  KableBleRepository (Thin Facade, ~350 lines)                   │
│  - Orchestrates component lifecycle                             │
│  - Manages public StateFlows (connectionState, monitorData, etc) │
│  - Routes method calls to appropriate managers                  │
│  - Handles cleanup on disconnect                                │
├─────────────────────────────────────────────────────────────────┤
│  Immutable Constants Layer                                      │
├─────────────────────────────────────────────────────────────────┤
│  BleProtocolConstants (immutable object)                        │
│  - UUIDs (service, characteristics)                             │
│  - Timing (timeouts, polling rates)                             │
│  - Thresholds (handle detection, stall detection)               │
│  - MTU settings                                                 │
├─────────────────────────────────────────────────────────────────┤
│  Serialization Layer                                            │
├─────────────────────────────────────────────────────────────────┤
│  BleOperationQueue (internal, Mutex-protected)                  │
│  - Serializes ALL peripheral.read() and .write() calls          │
│  - Returns suspended coroutines that wait for turn              │
│  - NO concurrent access to Bluetooth hardware                   │
├─────────────────────────────────────────────────────────────────┤
│  Connection Management Layer                                    │
├─────────────────────────────────────────────────────────────────┤
│  BleConnectionManager                                           │
│  - Owns: peripheral reference, connectionState flow             │
│  - Scans for devices by name pattern                            │
│  - Connects to peripheral                                       │
│  - Discovers services and characteristics (ordered)             │
│  - Requests MTU negotiation                                     │
│  - Handles explicit disconnect + guards auto-reconnect          │
│  - Emits connectionState: StateFlow<ConnectionState>            │
│                                                                 │
│  Dependency: BleOperationQueue (all read/write through queue)   │
├─────────────────────────────────────────────────────────────────┤
│  Metric Processing Layer (Hot Path, ~10-20Hz)                   │
├─────────────────────────────────────────────────────────────────┤
│  MetricPollingEngine                                            │
│  - Owns: 4 Job references (monitor, diagnostic, heuristic,     │
│    heartbeat), poll counters, dispatcher strategy              │
│  - Starts polling loops at configured rates                     │
│  - Loops call synchronous functions below                       │
│  - Enforces job cancellation order on stop                      │
│  - Handles timeouts and error states                            │
│                                                                 │
│  Dependency: BleOperationQueue (all reads through queue)        │
├─────────────────────────────────────────────────────────────────┤
│  Parsing Layer (Pure Functions, <1ms latency)                   │
├─────────────────────────────────────────────────────────────────┤
│  ProtocolParser (stateless object, pure functions)              │
│  - parseMonitorData(bytes) → (position, velocity, load, power)  │
│  - parseDiagnosticData(bytes) → (uptime, error_codes, ...)      │
│  - parseHeuristicData(bytes) → (phase_stats, ...)               │
│  - No state, no side effects                                    │
│  - Called synchronously from polling loops                      │
│  - Used by MonitorDataProcessor for validation                  │
│                                                                 │
│  Dependency: None (pure functions)                              │
├─────────────────────────────────────────────────────────────────┤
│  Data Processing Layer (Position Tracking, EMA, Validation)     │
├─────────────────────────────────────────────────────────────────┤
│  MonitorDataProcessor                                           │
│  - Owns: lastPosition, smoothedVelocity, filter state           │
│  - Validates position (within realistic range?)                 │
│  - Calculates velocity from position delta                      │
│  - Applies EMA smoothing to velocity                            │
│  - Updates position ranges for rep visualization                │
│  - Called synchronously from monitor polling loop               │
│  - Single-threaded, no locking needed                           │
│                                                                 │
│  Dependency: ProtocolParser (to parse raw bytes)                │
├─────────────────────────────────────────────────────────────────┤
│  Handle State Machine Layer                                     │
├─────────────────────────────────────────────────────────────────┤
│  HandleStateDetector (4-state machine)                          │
│  - Owns: baseline position, state machine state, timers         │
│  - States: UNKNOWN → AT_REST → GRABBED → AT_REST                │
│  - Detects position relative to baseline                        │
│  - Hysteresis thresholds (grabbed at +8mm, rest at +5mm)        │
│  - Resets baseline on workout start                             │
│  - Called synchronously from monitor polling loop               │
│  - Single-threaded, no locking needed                           │
│                                                                 │
│  Dependency: MonitorDataProcessor (reads processed position)    │
├─────────────────────────────────────────────────────────────────┤
│  Easter Egg Layer                                               │
├─────────────────────────────────────────────────────────────────┤
│  DiscoMode (self-contained)                                     │
│  - Toggles LED color cycling                                    │
│  - Owns: discoJob, lastColorSchemeIndex                         │
│  - Calls BleOperationQueue for writes                           │
│  - Job confinement: no other state                              │
│                                                                 │
│  Dependency: BleOperationQueue (for write operations)           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Boundaries

### BleProtocolConstants

**Responsibility:** Centralized compile-time configuration

**Immutable data:**
```kotlin
object BleProtocolConstants {
  // UUIDs
  val NUS_SERVICE_UUID = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
  val NUS_TX_UUID = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
  // ... 8 more UUID definitions

  // Timing
  const val CONNECTION_TIMEOUT_MS = 15_000L
  const val HEARTBEAT_INTERVAL_MS = 2_000L
  const val MONITOR_POLLING_INTERVAL_MS = 50L  // 20Hz
  // ... more timing constants

  // Thresholds
  const val HANDLE_GRABBED_THRESHOLD = 8.0
  const val HANDLE_REST_THRESHOLD = 5.0
  const val VELOCITY_STALL_THRESHOLD = 10.0
  // ... more thresholds

  // Characteristic references
  val monitorCharacteristic = characteristicOf(NUS_SERVICE_UUID, MONITOR_UUID)
  // ... more characteristics
}
```

**Communicates with:** All other modules (read-only)

---

### BleOperationQueue (Mutex Serialization)

**Responsibility:** Serialize ALL BLE read/write operations

**Public interface:**
```kotlin
class BleOperationQueue(peripheral: Peripheral) {
  suspend fun <T> queueOperation(name: String, operation: suspend () -> T): T {
    withLock { // acquires Mutex
      return operation()
    }
  }
}
```

**Usage pattern:**
```kotlin
// Inside MetricPollingEngine
val data = queue.queueOperation("monitor_read") {
  peripheral.read(monitorCharacteristic)
}

// Inside setupNotifications
queue.queueOperation("subscribe_monitor") {
  peripheral.collect(monitorCharacteristic) { bytes ->
    // handle bytes
  }
}
```

**Why mutex, not channel:**
- Channel would buffer operations, losing ordering
- Mutex ensures strict FIFO serialization
- Solves Android BluetoothGatt buffer re-use problem

**Communicates with:**
- `BleConnectionManager` (reads from peripheral)
- `MetricPollingEngine` (reads monitor/diagnostic/heuristic/heartbeat)
- `DiscoMode` (writes LED commands)

---

### BleConnectionManager

**Responsibility:** Manage connection lifecycle and characteristic discovery

**State ownership:**
```kotlin
class BleConnectionManager {
  private var peripheral: Peripheral? = null
  private val _connectionState = MutableStateFlow<ConnectionState>(Disconnected)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private var explicitDisconnectFlag = false
}
```

**State machine:**
```
    [Disconnected]
         ↓ scan + connect
    [Connecting]
         ↓ peripheral.connect() + discover
    [Connected]
         ↑ (auto-reconnect if !explicitDisconnect)
         ↓ disconnect()
    [Disconnecting]
         ↓ peripheral.disconnect() complete
    [Disconnected]

    + [Error] state (transition from any state)
```

**Key methods:**
```kotlin
suspend fun scanAndConnect(deviceName: String)  // Scan → find → connect
suspend fun discoverServices()                   // Sequential discovery
suspend fun discoverCharacteristics()
suspend fun requestMtuIfSupported()
suspend fun setupNotifications(                  // Subscribe to notification chars
  onCharacteristicChanged: (uuid, bytes) -> Unit
)
suspend fun disconnect(explicit: Boolean)       // explicit = don't auto-reconnect
```

**Critical invariant:**
- All transitions must be sequential (can't discover characteristics before services discovered)
- Must be atomic (explicit disconnect blocks auto-reconnect)

**Thread safety:**
- connectionState is a StateFlow (thread-safe)
- All operations on peripheral go through BleOperationQueue

**Communicates with:**
- `BleOperationQueue` (all peripheral.read/write calls)
- `MetricPollingEngine` (waits for Connected state before polling)
- `KableBleRepository` (facade publishes connectionState)

---

### MetricPollingEngine

**Responsibility:** Manage 4 independent polling loops

**Job ownership:**
```kotlin
class MetricPollingEngine {
  private var monitorPollingJob: Job? = null
  private var diagnosticPollingJob: Job? = null
  private var heuristicPollingJob: Job? = null
  private var heartbeatJob: Job? = null

  private val pollingMutex = Mutex()  // Guards above jobs

  fun start(
    scope: CoroutineScope,
    queue: BleOperationQueue,
    parser: ProtocolParser,
    processor: MonitorDataProcessor,
    handleDetector: HandleStateDetector,
    onMetric: (WorkoutMetric) -> Unit
  )

  fun stop()
}
```

**Loop strategy:**
```kotlin
// Monitor polling (10-20Hz, high priority)
monitorPollingJob = scope.launch {
  while (isActive) {
    val data = queue.queueOperation("monitor") {
      peripheral.read(monitorCharacteristic)
    }
    val metric = parser.parseMonitorData(data)
    processor.processMetric(metric)
    handleDetector.updateState(processor.lastPosition)
    onMetric(metric)
    delay(50)  // 20Hz
  }
}

// Diagnostic polling (1Hz, lower priority)
diagnosticPollingJob = scope.launch {
  while (isActive) {
    val data = queue.queueOperation("diagnostic") {
      peripheral.read(diagnosticCharacteristic)
    }
    parser.parseDiagnosticData(data)
    delay(1000)  // 1Hz
  }
}

// Similar for heuristic (4Hz) and heartbeat (0.5Hz)
```

**Job cancellation:**
- `stop()` cancels all jobs
- On disconnect, stop is called immediately
- Ensures no orphaned collectors

**Dispatcher strategy:**
```kotlin
// Different dispatchers for different frequencies
val monitorDispatcher = Dispatchers.Default  // High-priority
val diagnosticDispatcher = Dispatchers.Default.limitedParallelism(1)
// Both still share CPU time but diagnostics can't starve monitor
```

**Communicates with:**
- `BleOperationQueue` (all read operations)
- `ProtocolParser` (parse bytes)
- `MonitorDataProcessor` (process data)
- `HandleStateDetector` (update state)
- `KableBleRepository` (emit metrics)

---

### ProtocolParser

**Responsibility:** Pure byte → domain object parsing

**Interface:**
```kotlin
object ProtocolParser {
  fun parseMonitorData(bytes: ByteArray): RawMonitorMetric {
    val position = (bytes[0].toInt() shl 8) or bytes[1].toInt()
    val velocity = (bytes[2].toInt() shl 8) or bytes[3].toInt()
    val load = (bytes[4].toInt() shl 8) or bytes[5].toInt()
    val power = (bytes[6].toInt() shl 8) or bytes[7].toInt()
    return RawMonitorMetric(position, velocity, load, power)
  }

  fun parseDiagnosticData(bytes: ByteArray): DiagnosticData { /* ... */ }
  fun parseHeuristicData(bytes: ByteArray): HeuristicData { /* ... */ }
}
```

**Why pure functions:**
- No state to maintain (velocity smoothing happens elsewhere)
- Easy to test: input → expected output
- Testable without BLE hardware
- No side effects

**Performance constraint:** <1ms per call (called 10-20Hz)

**Communicates with:**
- `MonitorDataProcessor` (feeds raw data)
- `HandleStateDetector` (feeds raw position)

---

### MonitorDataProcessor

**Responsibility:** Validate, smooth, and enrich raw metrics

**State ownership:**
```kotlin
class MonitorDataProcessor {
  private var lastPosition: Double = 0.0
  private var smoothedVelocity: Double = 0.0
  private val positionRange = MutableDoubleRange(0.0, 0.0)

  companion object {
    const val POSITION_LOWER_BOUND = -100.0
    const val POSITION_UPPER_BOUND = 400.0
    const val EMA_ALPHA = 0.3
  }
}
```

**Processing pipeline:**
```kotlin
fun processMetric(raw: RawMonitorMetric): ProcessedMetric {
  // 1. Validate position (not NaN, within bounds)
  val validPosition = validatePosition(raw.position)

  // 2. Calculate velocity from position delta
  val calculatedVelocity = (validPosition - lastPosition) / SAMPLE_TIME_MS
  lastPosition = validPosition

  // 3. Apply EMA smoothing (exponential moving average)
  smoothedVelocity = EMA_ALPHA * calculatedVelocity +
                     (1 - EMA_ALPHA) * smoothedVelocity

  // 4. Update position range for visualization
  positionRange.update(validPosition)

  return ProcessedMetric(
    position = validPosition,
    velocity = smoothedVelocity,
    load = raw.load,
    power = raw.power,
    positionRange = positionRange
  )
}
```

**Why stateful:**
- EMA smoothing needs previous velocity (must survive across frames)
- Position range is cumulative (need min/max for visualization)
- These are not transient — they define the "health" of the metric stream

**Thread safety:**
- Single-threaded only (called from monitor polling loop)
- No locking needed

**Communicates with:**
- `ProtocolParser` (receives raw bytes)
- `HandleStateDetector` (provides processed position)
- `KableBleRepository` (emits metrics)

---

### HandleStateDetector

**Responsibility:** 4-state handle detection with baseline tracking

**State machine:**
```
    [UNKNOWN] — initial state before first position sample
         ↓ once baseline initialized
    [AT_REST] — position within baseline ± threshold
         ↓ position > baseline + GRABBED_THRESHOLD
    [GRABBED] — user is gripping handles
         ↓ position < baseline + REST_THRESHOLD
    [AT_REST]
```

**State ownership:**
```kotlin
class HandleStateDetector {
  enum class State { UNKNOWN, AT_REST, GRABBED }

  private var currentState: State = UNKNOWN
  private var baseline: Double? = null  // Position value when user is NOT gripping
  private var lastStateChangeTime: Long = 0

  companion object {
    const val GRABBED_THRESHOLD = 8.0   // mm above baseline
    const val REST_THRESHOLD = 5.0      // mm above baseline
    const val MIN_STATE_DURATION_MS = 50  // debounce
  }
}
```

**Baseline initialization:**
- Set on first workout (first 5 samples when position is stable)
- NOT reset between sets (user might not fully relax handle)
- Reset only on new workout start

**Key method:**
```kotlin
fun updateState(position: Double): State {
  // If baseline not yet set, initialize it
  if (baseline == null) {
    baseline = position  // First stable position is the baseline
  }

  // Hysteresis: state changes only after MIN_STATE_DURATION
  val now = Clock.System.now().toEpochMilliseconds()

  val newState = when {
    position > baseline!! + GRABBED_THRESHOLD -> State.GRABBED
    position < baseline!! + REST_THRESHOLD -> State.AT_REST
    else -> currentState  // No change, stay in current state
  }

  if (newState != currentState &&
      now - lastStateChangeTime > MIN_STATE_DURATION_MS) {
    currentState = newState
    lastStateChangeTime = now
  }

  return currentState
}

fun reset() {
  baseline = null
  currentState = State.UNKNOWN
}
```

**Thread safety:**
- Single-threaded only (called from monitor polling loop)
- No locking needed

**Communicates with:**
- `MonitorDataProcessor` (reads processed position)
- `KableBleRepository` (emits handle state flow)

---

### DiscoMode

**Responsibility:** Easter egg LED color cycling

**State ownership:**
```kotlin
class DiscoMode(
  private val scope: CoroutineScope,
  private val queue: BleOperationQueue,
  private val peripheral: Peripheral
) {
  private var discoJob: Job? = null
  private var lastColorSchemeIndex = 0

  companion object {
    val COLOR_SCHEMES = listOf(
      Color.RED, Color.BLUE, Color.GREEN, /* ... */
    )
  }
}
```

**Start/stop:**
```kotlin
fun start() {
  discoJob = scope.launch {
    while (isActive) {
      val color = COLOR_SCHEMES[lastColorSchemeIndex % COLOR_SCHEMES.size]
      queue.queueOperation("disco_write") {
        peripheral.write(ledCharacteristic, color.toBytes())
      }
      lastColorSchemeIndex++
      delay(200)  // Cycle every 200ms
    }
  }
}

fun stop() {
  discoJob?.cancel()
  discoJob = null
}
```

**Communicates with:**
- `BleOperationQueue` (writes LED commands)
- `KableBleRepository` (facade provides start/stop methods)

---

## Data Flow

### Metric Collection Pipeline

```
BLE Device
   ↓ (bytes via notification or read)
BleOperationQueue (serialized access)
   ↓
MetricPollingEngine (monitor loop reads at 20Hz)
   ↓
ProtocolParser.parseMonitorData() (raw bytes → numbers)
   ↓
MonitorDataProcessor.processMetric() (validate, EMA smooth, range update)
   ↓ (position to handle detector)
HandleStateDetector.updateState() (4-state machine)
   ↓
KableBleRepository.emit(monitorData) → SharedFlow
   ↓
UI + WorkoutSessionManager + MetricsRepository
```

### Connection Initialization

```
User calls: connect(deviceName)
   ↓
BleConnectionManager.scanAndConnect()
   ↓ (scan for device)
Scanner.discover()
   ↓ (find device with matching name)
peripheral.connect()
   ↓ (BLE connection established)
peripheral.discover() → services
   ↓
peripheral.discover() → characteristics
   ↓
peripheral.requestMtu(247)
   ↓
setupNotifications() → subscribe to all notification chars
   ↓
MetricPollingEngine.start() → begin polling loops
   ↓
KableBleRepository._connectionState = Connected
```

---

## Patterns to Follow

### Pattern 1: Operation Serialization (BleOperationQueue)

**Use case:** Any BLE read/write that might race with another

**Implementation:**
```kotlin
suspend fun myFunction() {
  val result = queue.queueOperation("my_operation") {
    peripheral.read(someCharacteristic)
  }
}
```

**Why:** Prevents concurrent access to shared Bluetooth buffers

---

### Pattern 2: State Machine with StateFlow

**Use case:** Connection state, handle state, workout state

**Implementation:**
```kotlin
private val _state = MutableStateFlow<State>(Initial)
val state: StateFlow<State> = _state.asStateFlow()

fun transitionTo(newState: State) {
  if (isValidTransition(_state.value, newState)) {
    _state.value = newState
  }
}
```

**Why:** Atomic state updates, observable by UI, type-safe

---

### Pattern 3: Synchronous Hot-Path Delegation

**Use case:** Monitor polling → parser → processor → detector

**Implementation:**
```kotlin
// In MetricPollingEngine
val raw = parser.parseMonitorData(bytes)
val processed = processor.processMetric(raw)
val handle = detector.updateState(processed.position)
onMetric(WorkoutMetric(processed, handle))
```

**Why:** <5ms latency budget → avoid async boundaries, Flow emissions, coroutine context switches in hot path

---

### Pattern 4: Job Ownership and Cleanup

**Use case:** Multiple polling loops that must all stop together

**Implementation:**
```kotlin
private val jobs = mutableListOf<Job>()

fun start(scope: CoroutineScope) {
  jobs.add(scope.launch { monitorLoop() })
  jobs.add(scope.launch { diagnosticLoop() })
}

fun stop() {
  jobs.forEach { it.cancel() }
  jobs.clear()
}
```

**Why:** Ensures all jobs cancelled when polling stops, no orphaned collectors

---

### Pattern 5: Explicit Baseline Lifecycle

**Use case:** Handle state detector baseline initialization and reset

**Implementation:**
```kotlin
fun onWorkoutStart() {
  detector.reset()  // Clear baseline, go back to UNKNOWN
  // detector will auto-init baseline from first position samples
}

fun onWorkoutEnd() {
  detector.reset()  // Clean for next workout
}
```

**Why:** Prevents stale baseline from previous workout affecting current one

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Concurrent BLE Operations (Async/Await Without Queue)

**Bad:**
```kotlin
scope.launch {
  val m1 = peripheral.read(monitorCharacteristic)
}
scope.launch {
  val d1 = peripheral.read(diagnosticCharacteristic)
}
// Both happen concurrently → buffer re-use → corruption
```

**Good:**
```kotlin
queue.queueOperation("monitor") {
  peripheral.read(monitorCharacteristic)
}
queue.queueOperation("diagnostic") {
  peripheral.read(diagnosticCharacteristic)
}
// Serialized → safe
```

---

### Anti-Pattern 2: Breaking Hot-Path with Async Boundaries

**Bad:**
```kotlin
val metric = parser.parseMonitorData(bytes)
val processed = flow { emit(metric) }.first()  // Async boundary!
processor.processMetric(processed)
```

**Good:**
```kotlin
val metric = parser.parseMonitorData(bytes)
val processed = processor.processMetric(metric)  // Synchronous
```

---

### Anti-Pattern 3: Sub-Manager with Own CoroutineScope

**Bad:**
```kotlin
class PollingEngine {
  private val scope = CoroutineScope(...)  // WRONG: own scope
}
```

**Good:**
```kotlin
class PollingEngine {
  // scope is passed in from parent
  fun start(scope: CoroutineScope) {
    scope.launch { /* ... */ }
  }
}
```

---

### Anti-Pattern 4: Mutable State Not Reset on Lifecycle Change

**Bad:**
```kotlin
class Detector {
  private var baseline: Double? = 0.0  // Never explicitly reset
}
```

**Good:**
```kotlin
class Detector {
  private var baseline: Double? = null

  fun reset() {
    baseline = null
  }
}
```

---

### Anti-Pattern 5: Implicit Ordering Dependencies

**Bad:**
```kotlin
fun connect() {
  discoverServices()
  discoverCharacteristics()  // Assumes services discovered
  requestMtu()
}
// No validation that services finished before characteristics starts
```

**Good:**
```kotlin
suspend fun connect() {
  discoverServices()   // suspends until complete
  discoverCharacteristics()  // can now safely discover
  requestMtu()
}
```

---

## Scalability Considerations

| Concern | At 1 Device | At 10 Devices (future) | Strategy |
|---------|------------|------------------------|----------|
| **BLE Operation Queue** | Single Mutex | Per-device queue | Already per-peripheral instance |
| **Polling Loops** | 4 loops per device | 40 loops total | Separate scope per device, limit concurrency |
| **Memory** | ~1MB metadata + buffers | ~10MB | Buffer pooling, circular buffers for metrics |
| **Connection Mgmt** | 1 peripheral | 10 peripherals | Device manager maintains list |
| **Latency Sensitivity** | <5ms budget | <5ms per device | Keep hot path synchronous, not scaling |

---

## Sources

- [Making Android BLE Work — Part 3](https://medium.com/@martijn.van.welie/making-android-ble-work-part-3-117d3a8aee23)
- [How to Prevent Race Conditions in Coroutines](https://typealias.com/articles/prevent-race-conditions-in-coroutines/)
- Project Phoenix MP codebase: KableBleRepository.kt, decomposition plan, issue history

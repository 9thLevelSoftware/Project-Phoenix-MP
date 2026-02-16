# Phase 12: KableBleConnectionManager + Facade - Research

**Researched:** 2026-02-15
**Domain:** BLE connection lifecycle extraction, facade pattern, coroutine state machine decomposition
**Confidence:** HIGH

## Summary

Phase 12 is the final and highest-risk phase of the v0.4.2 KableBleRepository decomposition. It extracts the remaining connection lifecycle code (scan, connect, disconnect, auto-reconnect, state observation, notification subscriptions, device readiness) into a `KableBleConnectionManager` class, then transforms KableBleRepository into a thin facade that delegates to all 6 extracted modules.

After 5 successful extractions (Phases 5-11: BleConstants, ProtocolParser, BleOperationQueue, DiscoMode, HandleStateDetector, MonitorDataProcessor, MetricPollingEngine), KableBleRepository currently stands at **1384 lines**. The target is **<400 lines** (FACADE-01). This requires extracting approximately **900+ lines** of connection lifecycle, scanning, notification subscription, and device readiness code.

The extraction scope covers: `startScanning()` (150 lines), `stopScanning()`, `scanAndConnect()` (50 lines), `connect()` (185 lines including retry logic and state observation), `onDeviceReady()` (117 lines including MTU, service discovery), `startObservingNotifications()` (90 lines), `tryReadFirmwareVersion()`, `tryReadVitruvianVersion()`, `disconnect()`, `cancelConnection()`, `cleanupExistingConnection()`, and all connection-related state variables (`peripheral`, `discoveredAdvertisements`, `scanJob`, `connectedDeviceName`, `connectedDeviceAddress`, `isExplicitDisconnect`, `wasEverConnected`, `detectedFirmwareVersion`, `negotiatedMtu`).

The critical risk is that this phase touches core BLE connectivity. Unlike previous phases that extracted processing pipelines (MonitorDataProcessor) or job lifecycle managers (MetricPollingEngine), this extraction moves the fundamental "can the device connect at all" code. Any regression means the device literally cannot connect. The auto-reconnect logic (`wasEverConnected`, `isExplicitDisconnect`, `_reconnectionRequested`) is especially delicate -- it uses multiple boolean flags coordinated across connection state transitions and explicit disconnect calls.

**Primary recommendation:** Extract `KableBleConnectionManager` as a class following the established inline-property delegation pattern. It receives constructor dependencies on `CoroutineScope`, `BleOperationQueue`, `MetricPollingEngine`, `DiscoMode`, and callback lambdas for state/event emission. The `Peripheral` reference lives exclusively in the connection manager (it's the only place that creates, uses, and destroys it). KableBleRepository becomes a ~300-350 line facade that declares all module properties, implements BleRepository by delegating every method to the appropriate module, and owns the SharedFlow/StateFlow declarations for the interface contract.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.9.0 | CoroutineScope, Job, Flow, StateFlow, delay, withTimeout, withTimeoutOrNull | Project's async runtime |
| Kable | 0.36.0 | Scanner, Peripheral, State, Advertisement, characteristicOf, observe | Project's KMP BLE library |
| Kermit | 2.0.4 | Structured logging with tags | Project's KMP logging library |
| kotlin-test | 2.0.21 | Unit testing | Project's test framework |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-coroutines-test | 1.9.0 | `runTest` for connection lifecycle tests | Testing connection state transitions |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Single ConnectionManager class | Separate ScanManager + ConnectManager | Scan and connect are tightly coupled (scanAndConnect, advertisement caching). Split would create artificial boundaries. |
| Callback lambdas for state emission | Pass StateFlow/SharedFlow references directly | Callbacks match Phase 8/10/11 pattern. Direct flow refs would couple the manager to specific flow types. |
| ConnectionManager owns Peripheral | Peripheral stays in facade | Manager CREATES the Peripheral (in connect), USES it (in onDeviceReady, notifications), and DESTROYS it (in disconnect). Ownership belongs in the manager. |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- KableBleConnectionManager.kt   # NEW: scan, connect, disconnect, notifications
  +-- MetricPollingEngine.kt         # Phase 11 (polling lifecycle, depends on ConnectionManager's Peripheral)
  +-- MonitorDataProcessor.kt        # Phase 10 (data processing)
  +-- HandleStateDetector.kt         # Phase 9 (state machine)
  +-- DiscoMode.kt                   # Phase 8 (Easter egg)
  +-- BleOperationQueue.kt           # Phase 7 (operation serialization)
  +-- ProtocolParser.kt              # Phase 6 (byte parsing)
  +-- ProtocolModels.kt              # Phase 6 (data classes)
  +-- BleExtensions.kt               # Platform-specific extensions (expect/actual)

shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
  +-- BleRepository.kt               # UNCHANGED (interface)
  +-- KableBleRepository.kt          # REDUCED to <400 line facade

shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/
  +-- KableBleConnectionManagerTest.kt  # NEW: connection lifecycle tests
```

### Pattern 1: Connection Manager with Peripheral Ownership
**What:** The ConnectionManager creates, stores, and destroys the Peripheral reference. Other modules receive it as a method parameter when they need it (MetricPollingEngine.startAll(peripheral), BleOperationQueue.write(peripheral, ...)).
**When to use:** When one component exclusively manages a resource's lifecycle.
**Why:** The Peripheral is created in `connect()`, used throughout the connection, and nulled in `disconnect()`. Having it owned in one place prevents stale references and simplifies cleanup.

```kotlin
class KableBleConnectionManager(
    private val scope: CoroutineScope,
    private val bleQueue: BleOperationQueue,
    private val pollingEngine: MetricPollingEngine,
    private val discoMode: DiscoMode,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onReconnectionRequested: (ReconnectionRequest) -> Unit,
    private val onScannedDevicesChanged: (List<ScannedDevice>) -> Unit,
    private val onMetricFromRx: (ByteArray) -> Unit,        // For RX notification metrics
    private val onRepEventFromRx: (ByteArray, Boolean) -> Unit,  // For rep notifications
) {
    private val log = Logger.withTag("KableBleConnectionManager")

    // The Peripheral lives HERE - single owner
    private var peripheral: Peripheral? = null

    // Scanning state
    private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()
    private var scanJob: Job? = null

    // Connection state flags
    private var connectedDeviceName: String = ""
    private var connectedDeviceAddress: String = ""
    private var isExplicitDisconnect = false
    private var wasEverConnected = false
    private var detectedFirmwareVersion: String? = null
    @Volatile private var negotiatedMtu: Int? = null

    // Provides Peripheral to external callers (read-only)
    val currentPeripheral: Peripheral? get() = peripheral

    suspend fun startScanning(): Result<Unit> { ... }
    suspend fun stopScanning() { ... }
    suspend fun scanAndConnect(timeoutMs: Long): Result<Unit> { ... }
    suspend fun connect(device: ScannedDevice): Result<Unit> { ... }
    suspend fun disconnect() { ... }
    suspend fun cancelConnection() { ... }
}
```

### Pattern 2: Facade with Module Delegation (v0.4.2 Standard)
**What:** KableBleRepository becomes a thin facade that declares all 6 module properties inline and delegates every BleRepository method to the appropriate module.
**When to use:** This is the final state of the decomposition.
**Why:** Per [v0.4.2] decision: no DI changes. The facade owns the Flow declarations (interface contract) and wires modules together.

```kotlin
class KableBleRepository : BleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== State flows (interface contract) =====
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    // ... all other flows ...

    // ===== Extracted modules (inline properties, init-order matters) =====
    private val bleQueue = BleOperationQueue()
    private val handleDetector = HandleStateDetector()
    private val monitorProcessor = MonitorDataProcessor(...)
    private val discoMode = DiscoMode(scope = scope, sendCommand = { cmd -> connectionManager.sendCommand(cmd) })
    private val pollingEngine = MetricPollingEngine(...)
    private val connectionManager = KableBleConnectionManager(...)  // LAST: depends on all above

    // ===== BleRepository delegation =====
    override suspend fun startScanning() = connectionManager.startScanning()
    override suspend fun connect(device: ScannedDevice) = connectionManager.connect(device)
    override suspend fun disconnect() = connectionManager.disconnect()
    override suspend fun sendWorkoutCommand(command: ByteArray) = connectionManager.sendWorkoutCommand(command)
    override fun startDiscoMode() { ... }
    override fun stopPolling() = pollingEngine.stopAll()
    // ... etc ...
}
```

### Pattern 3: Callback-Based Event Routing
**What:** The ConnectionManager uses callbacks to route events back to the facade's flows, matching the established pattern from DiscoMode (Phase 8), MonitorDataProcessor (Phase 10), and MetricPollingEngine (Phase 11).
**When to use:** When extracted module needs to emit events to flows owned by the facade.
**Why:** Avoids coupling module to specific flow types. The facade decides how to wire emissions.

```kotlin
// In KableBleRepository:
private val connectionManager = KableBleConnectionManager(
    scope = scope,
    bleQueue = bleQueue,
    pollingEngine = pollingEngine,
    discoMode = discoMode,
    onConnectionStateChanged = { state -> _connectionState.value = state },
    onReconnectionRequested = { request -> scope.launch { _reconnectionRequested.emit(request) } },
    onScannedDevicesChanged = { devices -> _scannedDevices.value = devices },
    onMetricFromRx = { data -> parseMetricsPacket(data) },
    onRepEventFromRx = { data, hasPrefix -> parseRepEvent(data, hasPrefix) }
)
```

### Anti-Patterns to Avoid
- **Moving SharedFlow/StateFlow declarations into the ConnectionManager:** The facade owns the interface contract. Flows must stay in KableBleRepository where the `override` declarations live.
- **Making the ConnectionManager implement BleRepository:** Creates ambiguity about which class IS the repository. The facade is the single BleRepository implementation.
- **Storing Peripheral in both the manager AND the facade:** Single ownership prevents stale reference bugs. Only the manager holds `peripheral`.
- **Moving the DiscoMode connection guard into ConnectionManager:** Per [08-01] decision: "Connection guard stays in KableBleRepository, not DiscoMode." The pattern should continue for the ConnectionManager -- the facade checks `connectionManager.currentPeripheral != null` before starting disco mode.
- **Breaking the init-order by declaring connectionManager before pollingEngine:** The connectionManager calls `pollingEngine.startAll(p)` during connection setup. However, since this is a runtime call (not init-time), the declaration order is about ensuring the property references are initialized. ConnectionManager should be declared LAST.

## Scope Decision: What Moves vs. What Stays

### Moves to KableBleConnectionManager
| Item | Current Location | Lines | Notes |
|------|-----------------|-------|-------|
| `peripheral` | line 173 | 1 | **Central state** - Peripheral reference |
| `discoveredAdvertisements` | line 174 | 1 | Scan result cache |
| `scanJob` | line 175 | 1 | Scan coroutine |
| `connectedDeviceName` | line 180 | 1 | Device info for logging |
| `connectedDeviceAddress` | line 181 | 1 | Device info for logging |
| `isExplicitDisconnect` | line 216 | 1 | Auto-reconnect guard flag |
| `wasEverConnected` | line 221 | 1 | Auto-reconnect guard flag |
| `detectedFirmwareVersion` | line 208 | 1 | Firmware version string |
| `negotiatedMtu` | line 211 | 1 | MTU diagnostic |
| `startScanning()` | lines 223-373 | 150 | Full scanning logic |
| `stopScanning()` | lines 375-387 | 13 | Scan stop |
| `scanAndConnect()` | lines 393-440 | 48 | Auto-scan-connect |
| `connect()` | lines 442-627 | 186 | **CRITICAL**: retry logic, state observer, Peripheral creation |
| `onDeviceReady()` | lines 633-749 | 117 | MTU, service discovery, notification start |
| `startObservingNotifications()` | lines 751-841 | 91 | Notification subscriptions + polling start |
| `tryReadFirmwareVersion()` | lines 847-867 | 21 | Firmware version one-shot read |
| `tryReadVitruvianVersion()` | lines 873-885 | 13 | Version characteristic read |
| `disconnect()` | lines 903-917 | 15 | Explicit disconnect |
| `cancelConnection()` | lines 919-929 | 11 | Cancel in-progress connection |
| `cleanupExistingConnection()` | lines 1151-1176 | 26 | Pre-connect cleanup |
| `sendWorkoutCommand()` | lines 943-1012 | 70 | BLE write through queue |
| `processIncomingData()` | lines 1178-1192 | 15 | RX notification router |
| `awaitResponse()` | lines 1203-1224 | 22 | Protocol handshake |
| `parseDiagnosticData()` | lines 891-901 | 11 | Post-CONFIG diagnostic read |
| **Total moved** | | **~850 lines** | |

### Stays in KableBleRepository (Facade)
| Item | Reason |
|------|--------|
| All `MutableStateFlow`/`MutableSharedFlow` declarations | Interface contract -- `override val` must be here |
| `connectionState`, `scannedDevices`, `metricsFlow`, `repEvents`, etc. | Owned by the BleRepository implementation |
| Module property declarations (bleQueue, handleDetector, monitorProcessor, discoMode, pollingEngine, connectionManager) | Facade wiring |
| `parseMetricsPacket()` | Called from RX notification processing; facade-level method that uses `_metricsFlow.tryEmit()` and `handleDetector.processMetric()` |
| `parseRepNotification()`, `parseRepsCharacteristicData()` | Called from notification callbacks; emit to `_repEvents` |
| `currentTimeMillis()` | Utility used by parse methods |
| `RomViolationType` enum | Used by `romViolationEvents` flow |
| Simple delegators: `startDiscoMode()`, `stopDiscoMode()`, `setLastColorSchemeIndex()`, `enableHandleDetection()`, `resetHandleState()`, etc. | 1-3 line methods that route to appropriate module |
| `startWorkout()`, `stopWorkout()`, `sendStopCommand()`, `sendInitSequence()` | High-level workout commands -- call `connectionManager.sendWorkoutCommand()` internally |

### Boundary Decisions

**1. Where does `sendWorkoutCommand()` live?**
It currently uses `peripheral` and `bleQueue` directly. Since the ConnectionManager owns `peripheral`, the method MUST move to the ConnectionManager. The facade delegates: `override suspend fun sendWorkoutCommand(command: ByteArray) = connectionManager.sendWorkoutCommand(command)`.

**2. Where does `processIncomingData()` live?**
It routes RX notifications to `parseMetricsPacket()` and `parseRepNotification()`. Since these methods emit to facade-owned flows (`_metricsFlow`, `_repEvents`), they stay in the facade. But `processIncomingData()` is called from an RX notification observer in `startObservingNotifications()` which moves to the ConnectionManager. Solution: the ConnectionManager invokes a callback for incoming RX data that the facade handles.

**3. Where does `parseDiagnosticData()` live?**
It's called from `sendWorkoutCommand()` (post-CONFIG one-shot) which moves to the ConnectionManager. The simplest approach: keep it as a private method in the ConnectionManager (it's self-contained -- just calls `parseDiagnosticPacket()` from ProtocolParser and logs).

**4. How does the ConnectionManager start polling after connection?**
`startObservingNotifications()` currently calls `pollingEngine.startAll(p)` and `discoMode.stop()`. The ConnectionManager receives `pollingEngine` and `discoMode` as constructor dependencies and calls them directly. This is a natural extension of the established pattern.

**5. How does `enableHandleDetection()` get the Peripheral?**
Currently it accesses `peripheral` directly. After extraction, it calls `connectionManager.currentPeripheral` to get the reference, then passes it to `pollingEngine.startMonitorPolling()`.

**6. Notification subscriptions (REPS, VERSION, MODE) -- stay or move?**
They use `peripheral.observe()` which requires the Peripheral reference. They MUST move to the ConnectionManager since it owns the Peripheral. The ConnectionManager uses callbacks to route notification data back to the facade for parsing/emission.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Connection retry | Custom retry framework | Simple `for` loop with `delay()` | Already proven in current code, matches BleConstants.Timing.CONNECTION_RETRY_COUNT |
| State observation | Custom state tracker | Kable's `Peripheral.state` flow with `onEach` + `launchIn` | Standard Kable pattern, already working |
| Auto-reconnect | Complex reconnect engine | `_reconnectionRequested.emit()` callback to presentation layer | Presentation-layer BleConnectionManager handles actual reconnect |
| Scan filtering | Custom scan manager | Kable Scanner + in-flow filter | Already proven pattern in current code |
| MTU negotiation | Custom MTU manager | `requestMtuIfSupported()` expect/actual | Already working platform-specific implementation |

**Key insight:** KableBleConnectionManager is a BLE lifecycle manager. It does NOT process data (that's MonitorDataProcessor), detect handles (HandleStateDetector), manage polling (MetricPollingEngine), or serialize operations (BleOperationQueue). It answers ONE question: "Is the device connected, and how do we get there?"

## Common Pitfalls

### Pitfall 1: Auto-Reconnect Flag Ordering (HIGHEST RISK)
**What goes wrong:** Auto-reconnect fires when it shouldn't (on explicit disconnect), or doesn't fire when it should (on unexpected disconnect).
**Why it happens:** The `isExplicitDisconnect` and `wasEverConnected` flags must be set/cleared in exact coordination with the `State.Disconnected` handler. If the flag setting order changes during extraction, the logic breaks silently.
**How to avoid:** The entire `connect()` method with its state observer (`peripheral?.state?.onEach`) MUST move as a unit. Do not split the state observer from the connect method. The flag logic is:
  - `wasEverConnected = true` when `State.Connected` fires
  - `isExplicitDisconnect = true` set in `disconnect()` and `cancelConnection()` BEFORE calling `peripheral?.disconnect()`
  - In `State.Disconnected`: check `hadConnection && !isExplicitDisconnect` for auto-reconnect
  - Reset both flags after processing disconnect
**Warning signs:** Unexpected reconnection attempts after user taps "Disconnect". No reconnection after BLE link drops mid-workout.

### Pitfall 2: Circular Dependency Between ConnectionManager and Facade
**What goes wrong:** The ConnectionManager needs to call facade methods (e.g., for parsing), and the facade needs to call ConnectionManager methods (e.g., for sending commands). This creates a chicken-and-egg initialization problem.
**Why it happens:** In the monolith, everything is `this`. After splitting, you need bidirectional references.
**How to avoid:** Use callbacks (lambdas) in the constructor, not direct references. The ConnectionManager NEVER holds a reference to KableBleRepository. All communication is via callbacks: `onConnectionStateChanged`, `onRepEvent`, `onMetricFromRx`, etc. The facade calls ConnectionManager methods directly since it holds the property.
**Warning signs:** Compilation errors about unresolved references. Stack overflow from circular calls.

### Pitfall 3: Peripheral Reference Leaking to Other Modules
**What goes wrong:** After extraction, code in the facade tries to access `peripheral` which no longer exists there, causing compilation errors or null access.
**Why it happens:** Multiple methods currently access `peripheral` directly: `enableHandleDetection()`, `restartMonitorPolling()`, `startActiveWorkoutPolling()`, `restartDiagnosticPolling()`, `startDiscoMode()`.
**How to avoid:** All these methods must use `connectionManager.currentPeripheral` instead of a local `peripheral` property. Grep for ALL references to `peripheral` in the facade after extraction to catch stragglers.
**Warning signs:** Compilation errors like "unresolved reference: peripheral".

### Pitfall 4: State Observer Launched in Wrong Scope
**What goes wrong:** The Kable state observer (`peripheral?.state?.onEach { ... }.launchIn(scope)`) is launched in the facade's scope after extraction but references ConnectionManager's internal state.
**Why it happens:** The state observer lambda captures local variables. If it's launched in one scope but references variables in another class, the captures become stale.
**How to avoid:** The state observer MUST move with `connect()` into the ConnectionManager. It uses the ConnectionManager's scope, references the ConnectionManager's flags, and invokes the ConnectionManager's callbacks.
**Warning signs:** Connection state never updates in the UI after connecting.

### Pitfall 5: DiscoMode sendCommand Callback Loop
**What goes wrong:** DiscoMode's `sendCommand` callback currently calls `sendWorkoutCommand()` which is in KableBleRepository. After extraction, `sendWorkoutCommand()` moves to the ConnectionManager. If DiscoMode is constructed before ConnectionManager (for init-order reasons), the callback can't reference the manager.
**Why it happens:** DiscoMode is currently constructed with `sendCommand = { command -> sendWorkoutCommand(command) }`. After extraction, this becomes `sendCommand = { command -> connectionManager.sendWorkoutCommand(command) }`. But if `connectionManager` isn't initialized yet at DiscoMode construction time, this captures a null.
**How to avoid:** Kotlin lambdas capture by reference, not by value. As long as `connectionManager` is a `val` property that is initialized before the lambda is INVOKED (not captured), this works. DiscoMode captures `this` (the KableBleRepository), and the lambda body accesses `this.connectionManager` at invocation time. Since DiscoMode only invokes sendCommand after connection (never during init), the manager is guaranteed to be initialized. However, to be safe, declare the DiscoMode sendCommand as `{ command -> connectionManager.sendWorkoutCommand(command) }` and ensure connectionManager is declared before OR verify Kotlin late-capture semantics.
**Warning signs:** NullPointerException when disco mode tries to send a color command.

### Pitfall 6: Missing stopDiscoMode() Calls Before Polling Start
**What goes wrong:** Monitor polling starts without stopping disco mode first, causing BLE command interleaving.
**Why it happens:** Currently, `stopDiscoMode()` is called in multiple places before polling starts. When the ConnectionManager calls `pollingEngine.startAll(p)` in `startObservingNotifications()`, it needs to also call `discoMode.stop()`. Same for when `enableHandleDetection(true)` triggers monitor polling restart.
**How to avoid:** The ConnectionManager receives `discoMode` as a dependency and calls `discoMode.stop()` before starting polling. Alternatively, use a `onBeforePollingStart` callback. The current pattern has `stopDiscoMode()` called in: `startObservingNotifications()`, `enableHandleDetection(true)`, `restartMonitorPolling()`, `startActiveWorkoutPolling()`, `startWorkout()`. After extraction, ensure each path still stops disco mode.
**Warning signs:** LED flickering during workout start.

### Pitfall 7: Line Count Target (<400 lines)
**What goes wrong:** The facade ends up at 450+ lines because of all the flow declarations, module wiring, and method bodies.
**Why it happens:** Each SharedFlow/StateFlow declaration is 4-5 lines. With 10+ flows, that's 50+ lines just for declarations. Plus 6 module properties, 20+ delegated methods, and parsing methods that stay.
**How to avoid:** Count carefully before starting:
  - Flow declarations: ~60 lines (10 flows, ~6 lines each)
  - Module properties: ~40 lines (6 modules with constructor args)
  - Override method delegations: ~80 lines (20 methods, ~4 lines each)
  - Parsing methods that stay: ~120 lines (parseMetricsPacket, parseRepNotification, parseRepsCharacteristicData, currentTimeMillis)
  - Class boilerplate: ~10 lines (package, imports, class declaration)
  - **Total estimate: ~310 lines** -- achievable but tight

If parsing methods push it over 400, consider moving `parseMetricsPacket()` and `parseRepNotification()` into the ConnectionManager (they're only called from notification callbacks that already live there). The facade would then be pure delegation.
**Warning signs:** Line count exceeds 400 before finishing all delegations.

## Code Examples

### Complete State Variable Inventory (to move from KableBleRepository)

```kotlin
// Source: KableBleRepository.kt

// Kable objects
private var peripheral: Peripheral? = null                          // line 173
private val discoveredAdvertisements = mutableMapOf<String, Advertisement>()  // line 174
private var scanJob: kotlinx.coroutines.Job? = null                // line 175

// Connection info
private var connectedDeviceName: String = ""                       // line 180
private var connectedDeviceAddress: String = ""                    // line 181

// Auto-reconnect flags
private var isExplicitDisconnect = false                           // line 216
private var wasEverConnected = false                               // line 221

// Device info
private var detectedFirmwareVersion: String? = null                // line 208
@Volatile private var negotiatedMtu: Int? = null                   // line 211
```

**Total: 9 mutable fields + 1 Map** to move into KableBleConnectionManager.

### KableBleConnectionManager Constructor Signature

```kotlin
class KableBleConnectionManager(
    private val scope: CoroutineScope,
    private val logRepo: ConnectionLogRepository,
    private val bleQueue: BleOperationQueue,
    private val pollingEngine: MetricPollingEngine,
    private val discoMode: DiscoMode,
    private val handleDetector: HandleStateDetector,
    // Callbacks for event routing to facade flows
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onScannedDevicesChanged: (List<ScannedDevice>) -> Unit,
    private val onReconnectionRequested: suspend (ReconnectionRequest) -> Unit,
    private val onCommandResponse: (UByte) -> Unit,
    // Callbacks for notification data routing
    private val onRepEventFromCharacteristic: (ByteArray) -> Unit,
    private val onRepEventFromRx: (ByteArray) -> Unit,
    private val onMetricFromRx: (ByteArray) -> Unit,
) {
    private val log = Logger.withTag("KableBleConnectionManager")

    // Characteristic references from BleConstants
    private val txCharacteristic = BleConstants.txCharacteristic
    private val rxCharacteristic = BleConstants.rxCharacteristic
    private val monitorCharacteristic = BleConstants.monitorCharacteristic
    // ... etc

    // Peripheral ownership
    private var peripheral: Peripheral? = null
    val currentPeripheral: Peripheral? get() = peripheral

    // ... all connection lifecycle methods ...
}
```

### Facade Wiring (KableBleRepository after extraction)

```kotlin
class KableBleRepository : BleRepository {
    private val log = Logger.withTag("KableBleRepository")
    private val logRepo = ConnectionLogRepository.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ===== State flows (interface contract) =====
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    // ... all other flow declarations ...

    // ===== Extracted modules =====
    private val bleQueue = BleOperationQueue()
    private val handleDetector = HandleStateDetector()
    override val handleDetection: StateFlow<HandleDetection> = handleDetector.handleDetection
    override val handleState: StateFlow<HandleState> = handleDetector.handleState

    private val monitorProcessor = MonitorDataProcessor(
        onDeloadOccurred = { scope.launch { _deloadOccurredEvents.emit(Unit) } },
        onRomViolation = { type -> scope.launch { _romViolationEvents.emit(mapRomViolationType(type)) } }
    )

    private val discoMode = DiscoMode(
        scope = scope,
        sendCommand = { command -> connectionManager.sendWorkoutCommand(command) }
    )
    override val discoModeActive: StateFlow<Boolean> = discoMode.isActive

    private val pollingEngine = MetricPollingEngine(
        scope = scope, bleQueue = bleQueue,
        monitorProcessor = monitorProcessor, handleDetector = handleDetector,
        onMetricEmit = { metric -> _metricsFlow.tryEmit(metric) },
        onHeuristicData = { stats -> _heuristicData.value = stats },
        onConnectionLost = { connectionManager.disconnect() }
    )

    // ConnectionManager declared LAST (depends on all above modules)
    private val connectionManager = KableBleConnectionManager(
        scope = scope, logRepo = logRepo, bleQueue = bleQueue,
        pollingEngine = pollingEngine, discoMode = discoMode,
        handleDetector = handleDetector,
        onConnectionStateChanged = { state -> _connectionState.value = state },
        onScannedDevicesChanged = { devices -> _scannedDevices.value = devices },
        onReconnectionRequested = { request -> _reconnectionRequested.emit(request) },
        onCommandResponse = { opcode -> _commandResponses.tryEmit(opcode) },
        onRepEventFromCharacteristic = { data -> parseRepsCharacteristicData(data) },
        onRepEventFromRx = { data -> parseRepNotification(data) },
        onMetricFromRx = { data -> parseMetricsPacket(data) },
    )

    // ===== BleRepository delegation =====
    override suspend fun startScanning() = connectionManager.startScanning()
    override suspend fun stopScanning() = connectionManager.stopScanning()
    override suspend fun scanAndConnect(timeoutMs: Long) = connectionManager.scanAndConnect(timeoutMs)
    override suspend fun connect(device: ScannedDevice) = connectionManager.connect(device)
    override suspend fun disconnect() = connectionManager.disconnect()
    override suspend fun cancelConnection() = connectionManager.cancelConnection()
    override suspend fun sendWorkoutCommand(command: ByteArray) = connectionManager.sendWorkoutCommand(command)
    // ... etc
}
```

## Execution Strategy: Wave Approach (Recommended)

Based on the Phase 11 precedent (2 plans in 2 waves), Phase 12 should use 2-3 waves:

### Wave 1 (Plan 12-01): Create KableBleConnectionManager
- Extract `KableBleConnectionManager.kt` with ALL connection lifecycle code
- Move: scanning, connect (with retry + state observer), disconnect, cleanupExistingConnection
- Move: onDeviceReady, startObservingNotifications, firmware reads
- Move: sendWorkoutCommand, processIncomingData, awaitResponse, parseDiagnosticData
- Move: all 9 state variables + 1 map
- Unit tests for connection lifecycle (state transitions, retry logic, auto-reconnect flags)
- **At this point, KableBleRepository still has the old code -- compilation expected to break temporarily**

### Wave 2 (Plan 12-02): Wire facade delegation + remove old code
- Add connectionManager inline property to KableBleRepository
- Delete all moved methods and state variables from KableBleRepository
- Update all method bodies to delegate to appropriate modules
- Verify <400 line target
- Run all tests
- Manual BLE testing

### Alternative: Single Wave (Higher Risk)
Could be done in a single plan since the extraction pattern is well-established from 5 prior phases. However, given this is the HIGHEST RISK phase (core connectivity), the 2-wave approach provides a safe checkpoint after Wave 1 where the new class can be verified independently.

## Connection Lifecycle State Machine (Reference)

```
                  +--------------+
                  | Disconnected |<------- explicit disconnect
                  +------+-------+       (isExplicitDisconnect=true)
                         |
                    startScanning()
                         |
                  +------v-------+
                  |   Scanning   |
                  +------+-------+
                         |
                  connect(device) / scanAndConnect()
                         |
                  +------v-------+       retry loop
                  |  Connecting  |<------ (up to 3 attempts)
                  +------+-------+       with 100ms delay
                         |
                    State.Connected
                    wasEverConnected=true
                         |
                  +------v-------+
                  |  Connected   |-----> onDeviceReady()
                  +------+-------+       -> MTU negotiation
                         |               -> service discovery
                    unexpected           -> startObservingNotifications()
                    disconnect              -> notification subscriptions
                    (BLE link drop)         -> pollingEngine.startAll(p)
                         |
                  +------v-------+
                  | Disconnected |
                  +------+-------+
                         |
               if (hadConnection &&
                   !isExplicitDisconnect)
                         |
                  +------v-----------+
                  | reconnection-    |  -> emitted via _reconnectionRequested flow
                  | Requested        |  -> handled by presentation-layer
                  +------------------+     BleConnectionManager.ensureConnection()
```

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **CONN-01**: KableBleConnectionManager handles scan/connect/disconnect lifecycle | All scanning, connection (with retry + state observer), and disconnect methods move to the manager |
| **CONN-02**: Auto-reconnect after unexpected disconnect works correctly | `wasEverConnected` + `isExplicitDisconnect` flag logic preserved as a unit in the state observer inside `connect()` |
| **CONN-03**: Connection retry logic (3 attempts) preserved | `for (attempt in 1..BleConstants.Timing.CONNECTION_RETRY_COUNT)` loop moves intact |
| **FACADE-01**: KableBleRepository reduced to <400 lines | Flow declarations (~60) + modules (~40) + delegations (~80) + parsing (~120) + boilerplate (~10) = ~310 lines |
| **FACADE-02**: All existing tests pass without modification | BleRepository interface unchanged. FakeBleRepository, SimulatorBleRepository unchanged. Extracted module tests unchanged. |
| **FACADE-03**: Manual BLE testing on physical device passes | No behavioral change -- mechanical extraction only. Same code, different file. |

## Success Criteria Verification Plan

| Criterion | How to Verify |
|-----------|---------------|
| 1. KableBleConnectionManager handles scan, connect, disconnect lifecycle | Methods `startScanning()`, `connect()`, `disconnect()`, `scanAndConnect()` exist in the manager |
| 2. Auto-reconnect after unexpected disconnect works correctly | Unit test: simulate State.Connected -> State.Disconnected with wasEverConnected=true, isExplicitDisconnect=false -> verify onReconnectionRequested callback fires |
| 3. Connection retry logic (3 attempts) preserved | Unit test: simulate 2 connection failures + 1 success -> verify connect() succeeds on 3rd attempt |
| 4. KableBleRepository reduced to <400 lines | `wc -l KableBleRepository.kt` shows < 400 |
| 5. All existing tests pass without modification | `./gradlew :androidApp:testDebugUnitTest` all green |
| 6. Manual BLE testing | Connect to physical Vitruvian, run workout, disconnect, verify auto-reconnect |

## Open Questions

1. **Should `parseMetricsPacket()`, `parseRepNotification()`, `parseRepsCharacteristicData()` move to the ConnectionManager or stay in the facade?**
   - What we know: These methods are called from notification callbacks (which move to the ConnectionManager) and emit to facade-owned flows. They contain domain-specific parsing logic (byte order, field extraction) plus emission to `_metricsFlow` and `_repEvents`.
   - Recommendation: **Move them to the ConnectionManager.** They're intimately tied to the notification observation code. The ConnectionManager would emit parsed `RepNotification` and `WorkoutMetric` objects via callbacks instead of raw `ByteArray`. This simplifies the callback interface and moves all BLE-specific code out of the facade. Revised callbacks: `onRepEvent: (RepNotification) -> Unit` and `onMetricFromRx: (WorkoutMetric) -> Unit`.
   - Impact: Reduces facade further (by ~120 lines), making the <400 target easier. The ConnectionManager becomes ~970 lines (large but self-contained).

2. **Should the `_commandResponses` flow and `awaitResponse()` stay in the facade or move?**
   - What we know: `_commandResponses` is populated from `processIncomingData()` (moves to ConnectionManager). `awaitResponse()` is `@Suppress("unused")` and reserved for future protocol handshakes.
   - Recommendation: Move both to the ConnectionManager. The command response flow is only consumed by `awaitResponse()` which is only called during BLE communication. No external consumer exists.

3. **How to handle the `romViolationEvents` flow?**
   - What we know: `RomViolationType` enum and `romViolationEvents` flow are public on KableBleRepository but NOT part of the BleRepository interface. They're used by MonitorDataProcessor via callback.
   - Recommendation: Keep in the facade. The callback from MonitorDataProcessor routes to this flow. It's part of the facade's public API even though it's not in the interface.

4. **Should this be 2 waves or 3?**
   - Recommendation: 2 waves. Wave 1 creates the ConnectionManager with all moved code. Wave 2 wires the facade. The Phase 11 precedent shows 2 waves works well for this codebase.

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` (1384 lines) -- The definitive source showing exactly what remains to be extracted. Every line was read and categorized.
- `BleRepository.kt` -- The interface contract that MUST remain unchanged.
- `BleConstants.kt` -- Timing constants (CONNECTION_RETRY_COUNT=3, CONNECTION_TIMEOUT_MS=15000, CONNECTION_RETRY_DELAY_MS=100) used by the connection logic.
- Phase 11 RESEARCH.md, 11-02-PLAN.md, 11-02-SUMMARY.md -- Established extraction patterns, wave approach, init-order decisions.
- All 5 previously extracted modules (BleOperationQueue, DiscoMode, HandleStateDetector, MonitorDataProcessor, MetricPollingEngine) -- Verified inline-property delegation pattern.

### Secondary (MEDIUM confidence)
- `BleConnectionManager.kt` (presentation layer) -- Shows how reconnection requests are consumed. The `reconnectionRequested` flow is observed by this presentation-layer manager which calls `ensureConnection()`.
- `FakeBleRepository.kt` -- Must continue to compile. Shows all BleRepository methods that need facade delegation.
- `SimulatorBleRepository.kt` -- Must continue to compile.
- `MetricPollingEngineTest.kt` -- Shows the testing pattern (fake jobs, no real Peripheral mocking).

### Tertiary (LOW confidence)
- Parent repo `KableBleRepositoryImpl.kt` -- Commented out / disabled. Not useful as reference. The KMP implementation has diverged significantly.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- Using existing project dependencies only, no new libraries needed
- Architecture: HIGH -- Follows established Phase 7-11 inline-property delegation pattern. Extension to facade is natural.
- Pitfalls: HIGH -- All identified from actual codebase analysis. Auto-reconnect flag ordering (Pitfall #1) is the highest-risk item.
- Scope: HIGH -- Complete line-by-line inventory of what moves vs. stays, with line counts.
- Line count target: MEDIUM -- Estimate of ~310 lines for facade is tight but achievable. Depends on whether parsing methods move or stay.

**Research date:** 2026-02-15
**Valid until:** Indefinitely (extraction pattern is stable, connection logic is frozen)

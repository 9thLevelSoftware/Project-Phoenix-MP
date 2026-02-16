# Class Decomposition Patterns & Safe Incremental Extraction

**Project:** Project Phoenix MP — KableBleRepository Refactoring
**Domain:** God Class Decomposition with Interface Preservation
**Researched:** 2026-02-15
**Overall Confidence:** HIGH

## Executive Summary

Decomposing a 2,886-line God class into 8 focused modules while maintaining a `BleRepository` interface contract requires a disciplined multi-phase approach combining three proven patterns:

1. **Facade Pattern** — The thin interface layer that preserves existing consumer contracts
2. **Delegation with Gradual Extraction** — Incremental subsystem extraction with feature flags for parallel validation
3. **Characterization Testing** — Baseline behavior capture to guarantee behavioral equivalence

The KableBleRepository can be safely decomposed into 8 specialized modules if extraction follows a strict ordering (low-risk utilities first, high-risk stateful systems last) and consumers are migrated through a delegation layer that guarantees zero behavior changes.

## Domain Context

### The Problem: Why KableBleRepository is a God Class

- **2,886 lines** with ~10 subsystems and ~50 mutable state variables
- **10+ responsibilities:** connection management, scanning, metric parsing, handle state machines, diagnostics, heuristics, color schemes, disco mode, heartbeat, ROM violation detection
- **Mixed thread safety:** Volatile fields, Mutex locks, suspended functions without proper synchronization guards
- **Tight coupling:** All state variables accessible to all methods with no module boundaries

### The Solution Constraints

1. **Interface preservation** — Consumers see zero API change; new subsystems must never break `BleRepository` contract
2. **Incremental delivery** — Cannot block on full refactoring; must enable parallel feature work
3. **Behavioral equivalence** — No observable behavior change; even timing-sensitive features (auto-start, deload detection) must work identically
4. **Risk isolation** — Failure in one subsystem extraction cannot propagate to others

## Decomposition Patterns

### Pattern 1: Facade Layer (Preserve the Interface)

**What:** The `KableBleRepository` becomes a thin **facade** that delegates to 8 specialized modules while implementing the `BleRepository` interface unchanged.

**Why this works:**
- Consumers see zero change — they still import and use `KableBleRepository`
- Calls are transparently forwarded to specialized modules
- Can extract modules incrementally without breaking consumers
- Deprecated method markers on facade guide new code to direct module imports

**Implementation Structure:**
```kotlin
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val metricsFlow: Flow<WorkoutMetric>
    // ... 20+ properties and methods
}

// The facade (2,886 lines compressed to ~300 lines of delegation)
class KableBleRepository : BleRepository {
    private val connectionManager = ConnectionManager()
    private val scanManager = ScanManager()
    private val metricsParser = MetricsParser()
    private val handleStateEngine = HandleStateEngine()
    private val diagnosticsPoller = DiagnosticsPoller()
    private val heuristicPoller = HeuristicPoller()
    private val heartbeatManager = HeartbeatManager()
    private val commandProtocol = CommandProtocol()

    // Delegate all interface methods
    override val connectionState: StateFlow<ConnectionState>
        get() = connectionManager.connectionState

    override suspend fun startScanning() = scanManager.startScanning()
    // ... delegate remaining 40+ methods
}
```

**Scope of Facade:**
- Coordinates 8 module instances as singletons in init block
- Routes method calls to correct module
- Manages lifecycle (shared CoroutineScope, logging, telemetry)
- Owns the `BleRepository` interface contract

**Migration Path:**
1. Phase 1 (weeks 1-2): Extract 3 low-risk modules (utilities, constants, parsing) → Facade delegates to them
2. Phase 2 (weeks 3-4): Extract 3 medium-risk modules (scanning, connection, diagnostics) → Facade maintains state handoff
3. Phase 3 (weeks 5-7): Extract 2 high-risk modules (handle state, heuristics) → Extensive parallel testing

---

### Pattern 2: Delegation with Gradual Extraction

**What:** Extract one subsystem at a time, with the facade holding extracted state until all related modules are extracted, ensuring no state dangling.

**Why this works:**
- Each extraction is atomic and testable in isolation
- Facade manages the "seams" between extracted modules
- Can run old and new implementations in parallel for comparison
- Rollback is trivial — just keep using the facade

**Safe Extraction Steps (per module):**

#### Step 1: Create the New Module
```kotlin
class MetricsParser(
    private val log: Logger = Logger.withTag("MetricsParser")
) {
    fun parseMonitorData(data: ByteArray): WorkoutMetric? {
        // Move parseMonitorData + parseMetricsPacket from God class
        // Keep the EXACT same logic, byte-for-byte
    }
}
```

#### Step 2: Inject into Facade
```kotlin
class KableBleRepository : BleRepository {
    private val metricsParser = MetricsParser()

    // Old method (still in facade for now)
    private fun parseMonitorData(data: ByteArray): WorkoutMetric? {
        return metricsParser.parseMonitorData(data)  // Delegate to new module
    }
}
```

#### Step 3: Run in Parallel (Characterization Tests)
```kotlin
class MetricsParserTest {
    @Test
    fun testParsingEquivalence() {
        val testData = byteArrayOf(0x01, 0x02, ...) // Real BLE packets

        val oldResult = godClassInstance.parseMonitorData(testData)
        val newResult = metricsParser.parseMonitorData(testData)

        // EXACT comparison — byte order, float precision, null handling
        assertEquals(oldResult, newResult)
    }
}
```

#### Step 4: Migrate Callers (Feature Flags)
```kotlin
private fun processIncomingData(data: ByteArray) {
    if (featureFlag.useNewMetricsParser) {
        // Call new module directly
        val metric = metricsParser.parseMonitorData(data)
        _metricsFlow.emit(metric)
    } else {
        // Old code path through facade
        val metric = parseMonitorData(data)  // Delegates to metricsParser
        _metricsFlow.emit(metric)
    }
}
```

**Critical Rule:** Never extract two interdependent modules in the same phase. Extract utilities first → then consumers of those utilities.

---

### Pattern 3: Characterization Testing (Baseline Behavior Guarantee)

**What:** Capture the current behavior of the God class as a "golden master" before extraction, then verify extracted modules produce identical results.

**Why this works:**
- Decouples "preserving behavior" from "understanding intended behavior"
- Proves behavioral equivalence without needing to understand what "correct" is
- Catches subtle timing bugs, edge cases, and undocumented behaviors
- Becomes the specification for extracted modules

**Implementation Strategy:**

#### 1. Capture Baseline (Before Extraction)
```kotlin
class KableBleRepositoryCharacterizationTest {
    private val godClass = KableBleRepository()

    @Test
    fun captureParsingBehavior() {
        // Use real device BLE packets captured from logs
        val packets = listOf(
            byteArrayOf(0x01, 0x02, 0x03, ...),  // Position data
            byteArrayOf(0x10, 0x20, 0x30, ...),  // Rep event
            // ... ~50 real packets covering all code paths
        )

        val results = mutableListOf<WorkoutMetric?>()
        for (packet in packets) {
            results.add(godClass.parseMonitorData(packet))
        }

        // Snapshot these results (approval testing / golden master)
        // Tools: https://github.com/approvalTests/ or snapshot libraries
        approveResults(results)
    }
}
```

#### 2. Verify Extracted Module Against Baseline
```kotlin
class MetricsParserCharacterizationTest {
    private val newModule = MetricsParser()

    @Test
    fun verifyParsingEquivalence() {
        val packets = loadBaselinePackets()  // Load from captured baseline
        val expectedResults = loadApprovedResults()  // Load golden master

        for ((packet, expected) in packets.zip(expectedResults)) {
            val actual = newModule.parseMonitorData(packet)
            assertEquals(expected, actual, "Parsing diverged on packet $packet")
        }
    }
}
```

#### 3. Edge Cases & Timing Behavior
```kotlin
class HandleStateCharacterizationTest {
    @Test
    fun verifyStateTransitionTiming() {
        // The old code uses STATE_TRANSITION_DWELL_MS = 200
        // Captured baseline shows: grab → 200ms delay → state change

        val metric = WorkoutMetric(position = 15.0, timestamp = 1000)
        val state1 = godClass.analyzeHandleState(metric)

        runBlocking {
            delay(200)
            val metric2 = WorkoutMetric(position = 3.0, timestamp = 1200)
            val state2 = godClass.analyzeHandleState(metric2)

            // Both old code and new HandleStateEngine must produce same timing
            assertEquals(HandleState.WaitingForRest, state1)
            assertEquals(HandleState.Released, state2)
        }
    }
}
```

**Characterization Test Scope:**
- Every public method of every extracted module
- Boundary conditions (empty arrays, null values, extreme numbers)
- Timing-sensitive operations (heartbeat intervals, debounce delays, EMA smoothing)
- Error conditions (invalid BLE data, connection timeouts)
- Aim for **snapshot-based approval tests** (easiest to maintain)

---

### Pattern 4: Module Boundaries (Responsibility Separation)

**What:** Define each extracted module with a single, clear responsibility and a minimal public interface.

**Recommended 8-Module Decomposition:**

| Module | Responsibility | Dependencies | State Variables | Methods |
|--------|---|---|---|---|
| **ConnectionManager** | Device scanning, connection lifecycle, MTU negotiation, connection retry | Kable Scanner/Peripheral, logging | `peripheral`, `discoveredAdvertisements`, `scanJob`, connection state | `startScanning()`, `connect()`, `disconnect()`, `cancelConnection()` |
| **MetricsParser** | Parse raw BLE monitor data into WorkoutMetric structs | Validation helpers, constants | Last position/velocity for spike detection | `parseMonitorData()`, `parseMetricsPacket()` |
| **RepEventParser** | Parse rep notification characteristic data into RepNotification | Constants, byte utilities | None (stateless) | `parseRepNotification()`, `parseRepsCharacteristicData()` |
| **HandleStateEngine** | Determine handle position state (WaitingForRest, Released, Grabbed, Moving) | MetricsParser, constants, logging | `smoothedVelocityA`, `smoothedVelocityB`, baseline position tracking, EMA state | `analyzeHandleState()`, `resetHandleState()`, `enableJustLiftWaitingMode()` |
| **DiagnosticsPoller** | Poll diagnostic characteristic, keep-alive heartbeat, firmware version fetch | CommandProtocol, logging | Diagnostic poll job, heartbeat job, timeout counter | `startDiagnosticPolling()`, `performHeartbeatRead()` |
| **HeuristicPoller** | Poll heuristic characteristic (phase statistics), emit HeuristicStatistics | Logging | Heuristic poll job | `startHeuristicPolling()`, `parseHeuristicData()` |
| **MonitorPoller** | Continuously poll monitor characteristic during workouts, emit metrics | MetricsParser, HandleStateEngine, RepEventParser, logging | Monitor poll job, sampling rate | `startMonitorPolling()`, `stopPolling()`, `stopMonitorPollingOnly()` |
| **CommandProtocol** | Send commands, manage response handshakes, color schemes, disco mode | Logging, constants | Response flow, color scheme state, disco mode job | `sendWorkoutCommand()`, `awaitResponse()`, `sendInitSequence()`, `setColorScheme()`, `startDiscoMode()` |

**Module Interfaces (Public API):**

```kotlin
// MetricsParser - stateless parsing
interface IMetricsParser {
    fun parseMonitorData(data: ByteArray): WorkoutMetric?
}

// HandleStateEngine - stateful state machine
interface IHandleStateEngine {
    val handleState: StateFlow<HandleState>
    fun analyzeHandleState(metric: WorkoutMetric): HandleState
    fun resetHandleState()
    fun enableJustLiftWaitingMode()
}

// CommandProtocol - request-response protocol
interface ICommandProtocol {
    suspend fun sendWorkoutCommand(command: ByteArray): Result<Unit>
    suspend fun awaitResponse(timeoutMs: Long = 5000): Result<UByte>
}
```

**Dependency Graph (Extract in this order):**
```
Level 0 (Stateless utilities):
  - RepEventParser (no dependencies)
  - MetricsParser (self-contained)

Level 1 (Use Level 0):
  - CommandProtocol (uses constants)
  - HandleStateEngine (uses MetricsParser)

Level 2 (Use Level 0-1):
  - DiagnosticsPoller (uses CommandProtocol)
  - HeuristicPoller (uses parsing)
  - MonitorPoller (uses MetricsParser, HandleStateEngine, RepEventParser)

Level 3 (Orchestrates all):
  - ConnectionManager (uses all others)
```

---

## Feature Flags & Parallel Validation

**What:** Run old God class code and new extracted module code in parallel, compare outputs, emit to flow only if they match.

**Why this works:**
- Zero-risk validation — if new code produces different output, old code is still used
- Real-world testing with production data
- Can spot subtle behavioral divergences (timing, float precision, edge cases)
- Easy rollback: just remove the feature flag

**Implementation:**

```kotlin
class KableBleRepository : BleRepository {
    private val metricsParser = MetricsParser()

    private fun processIncomingData(data: ByteArray) {
        // Shadow testing: run both old and new code, compare
        if (enableParallelValidation) {
            val oldResult = oldParseMonitorData(data)  // Keep God class copy temporarily
            val newResult = metricsParser.parseMonitorData(data)

            if (oldResult != newResult) {
                log.warn("Parsing divergence: $oldResult vs $newResult on data $data")
                metrics.incrementCounter("parsing_divergence")
                // Use old result (proven behavior)
                _metricsFlow.emit(oldResult)
            } else {
                _metricsFlow.emit(newResult)
            }
        } else {
            // Normal path: use extracted module
            val result = metricsParser.parseMonitorData(data)
            _metricsFlow.emit(result)
        }
    }
}
```

**Feature Flag Strategy:**
- One flag per module (not one global flag)
- Default to old code (safest)
- Roll out new code incrementally: 1% of devices → 10% → 50% → 100%
- Monitor metrics: divergence rate, error rate, connection stability
- If divergence > 0.01%, pause rollout and investigate

---

## Testing Strategy

### Test Pyramid (Per Module)

```
        /\
       /  \     Integration Tests (flows work end-to-end)
      /    \    [1-2 tests per module, exercise in real workflow]
     /------\
    /        \   Component Tests (module contract)
   /          \  [5-10 tests per module, characterization baseline]
  /            \
 /              \ Unit Tests (internal logic)
/________________\ [few — already covered by characterization]
```

### Test Coverage by Module

**MetricsParser:**
- Characterization: 50 real BLE packets → verify output against golden master
- Edge cases: truncated packets, all-zeros, boundary position values
- No integration tests (stateless)

**HandleStateEngine:**
- Characterization: replay 20-second handle motion sequences, verify state timeline
- Edge cases: position jitter, velocity direction changes, hysteresis boundaries
- Integration: emit metrics → verify state changes propagate to StateFlow

**CommandProtocol:**
- Characterization: capture real command sequences and responses
- Edge cases: timeout, multiple rapid commands, response out-of-order
- Integration: send color scheme → verify response → verify BLE write

**ConnectionManager:**
- Component: scan discovers devices, connect → ready, disconnect → clean up
- Integration: full scan + connect + start workout + disconnect lifecycle
- Stress: rapid connect/disconnect cycles, BLE errors

### Characterization Test Tooling

Use **approval tests** (easiest to maintain during extraction):
```kotlin
// https://github.com/approvalTests/ApprovalTests.Java or Kotlin equiv

@Test
fun captureParsingBaseline() {
    val results = godClass.parseMonitorData(testPackets)
    Approvals.verify(results)  // Generates results.approved.txt
}
```

When extracted module is ready:
```kotlin
@Test
fun verifyParsingEquivalence() {
    val results = newModule.parseMonitorData(testPackets)
    Approvals.verify(results)  // Compares to results.approved.txt
}
```

If new code diverges: `diff results.approved.txt results.received.txt` shows exactly what changed.

---

## Incremental Extraction Roadmap (8-Phase Plan)

| Phase | Modules | Effort | Risk | Duration | Gate |
|-------|---------|--------|------|----------|------|
| 1 | RepEventParser, MetricsParser | Low | Low | 1 week | All characterization tests pass |
| 2 | CommandProtocol | Medium | Medium | 1 week | Integration tests pass, disco mode works |
| 3 | HandleStateEngine | High | High | 2 weeks | State machine transitions match golden master, auto-start stable |
| 4 | DiagnosticsPoller | Medium | Medium | 1 week | Heartbeat + firmware version working |
| 5 | HeuristicPoller | Low | Low | 3 days | Heuristic data flowing to UI |
| 6 | MonitorPoller | High | High | 2 weeks | Metrics emission matches old code, no gaps |
| 7 | ConnectionManager | High | High | 2 weeks | Full connection lifecycle matches, no disconnects |
| 8 | Facade + Cleanup | Low | Low | 3 days | All modules integrated, old code removed |

**Total: 10-11 weeks of structured, incremental extraction**

---

## Pitfalls & Prevention

### Pitfall 1: Extracting Interdependent Modules Together
**What goes wrong:** Extract HandleStateEngine and MonitorPoller together → neither works because HandleStateEngine needs metrics from MonitorPoller, MonitorPoller needs state from HandleStateEngine → circular dependency.

**Prevention:**
- Map dependencies BEFORE extraction (dependency graph)
- Extract bottom-up: utilities first → consumers
- If modules have circular deps in God class, create a `StateCoordinator` to mediate (see Coordinator Pattern below)

### Pitfall 2: Losing Behavioral Nuances (Timing, Float Precision)
**What goes wrong:** Extract MetricsParser, it works for "normal" cases but breaks on edge cases (invalid position spikes, float rounding) because you didn't capture those in characterization tests.

**Prevention:**
- **Capture real device data:** Use logs from production device sessions, not synthetic test data
- **Baseline everything:** Even edge cases should be in golden master
- **Run in parallel first:** Shadow-test new module against old code on real data before switching

### Pitfall 3: Breaking Consumers Due to StateFlow Timing
**What goes wrong:** Extract HandleStateEngine, but the old code emitted state updates immediately while new code batches them. Consumer code (auto-start logic) expects synchronous state updates.

**Prevention:**
- Make StateFlows emit immediately, don't buffer
- Test consumer code paths as integration tests (auto-start detection, deload events)
- Review all `.first()`, `.take(1)`, `distinctUntilChanged()` operators in consumer code

### Pitfall 4: Insufficient Thread Safety During Extraction
**What goes wrong:** God class had `synchronized(lock)` blocks that get lost when extracting. New module can have race conditions on `@Volatile` fields.

**Prevention:**
- Document all synchronization in God class (which methods need locks)
- Apply same synchronization to extracted modules
- In Kotlin KMP, prefer Mutex over synchronized: `mutex.withLock { ... }`
- Don't hold locks across suspend calls

### Pitfall 5: Forgotten References in Facade
**What goes wrong:** Extract ConnectionManager, update facade to delegate `startScanning()`, but forget to update internal call sites within facade itself. Some paths call old `startScanning()`, others call new one.

**Prevention:**
- Complete each extraction in one PR: extract module, update facade delegation, remove old method
- Use IDE refactoring: Rename old method → Force rename all usages
- Code review checklist: "All internal calls to this method updated?"

---

## Coordinator Pattern: Breaking Circular Dependencies

If you discover circular dependencies (e.g., MonitorPoller → HandleStateEngine → MonitorPoller), don't extract both at once. Instead, create a **Coordinator**:

```kotlin
// Coordinator owns the shared state and orchestrates interactions
class PollingCoordinator(
    private val handleStateEngine: HandleStateEngine,
    private val monitorPoller: MonitorPoller
) {
    fun startMonitoring() {
        // Coordinate: monitor polls, engine analyzes, coordinator listens to both
        monitorPoller.startPolling { metric ->
            val newState = handleStateEngine.analyzeHandleState(metric)
            // Broadcast state change to listeners
        }
    }
}
```

This breaks the cycle: MonitorPoller → Coordinator ← HandleStateEngine (no cycle).

---

## Interface Preservation Guarantee

The `BleRepository` interface is never modified. Instead:

```kotlin
// Phase 1-8: This interface stays EXACTLY the same
interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val metricsFlow: Flow<WorkoutMetric>
    val scannedDevices: StateFlow<List<ScannedDevice>>
    // ... 20+ more properties/methods, unchanged
}

// Phase 1: Start of extraction
class KableBleRepository : BleRepository {
    // Facade delegates to modules
    override val connectionState: StateFlow<ConnectionState>
        get() = connectionManager.connectionState
}

// Phase 8: End of extraction
class KableBleRepository : BleRepository {
    // Still delegates to modules, interface unchanged
    // Consumers never know the refactoring happened
}
```

**Consumers:**
```kotlin
// This code works in Phase 1 and Phase 8 without change
@HiltViewModel
class WorkoutViewModel @Inject constructor(
    val bleRepository: BleRepository  // Works with facade
) : ViewModel() {
    val connectionState = bleRepository.connectionState
    val metrics = bleRepository.metricsFlow
}
```

---

## Success Criteria

| Criterion | Verification |
|-----------|--------------|
| **Behavioral Equivalence** | All characterization tests pass (old vs new), shadow testing shows 0 divergence |
| **Interface Preservation** | BleRepository contract unchanged, consumers compile without modification |
| **Incremental Delivery** | Each module extraction is independent, can be deployed separately |
| **Thread Safety** | No race conditions, Mutex coverage matches God class, deadlock testing |
| **Performance** | No measurable latency change, memory usage ≤ God class |
| **Test Coverage** | 90%+ for extracted modules, characterization baseline for all |
| **Rollback Safety** | Any extracted module can be disabled via feature flag without breaking consumers |

---

## Sources

- [How to refactor the God object class antipattern](https://www.theserverside.com/tip/How-to-refactor-the-God-object-antipattern)
- [Design Patterns For Refactoring: Façade](https://tommcfarlin.com/design-patterns-for-refactoring-facade/)
- [The Strangler Fig Pattern: Incremental Migration Strategy](https://softwarepatternslexicon.com/microservices/4/3/)
- [Characterization Tests for Legacy Code](https://learnagilepractices.substack.com/p/characterization-tests-for-legacy)
- [Delegation pattern - Wikipedia](https://en.wikipedia.org/wiki/Delegation_pattern)
- [Interface Delegation - Kotlin Academy](https://kt.academy/article/ak-interface-delegation)
- [Delegation | Kotlin Documentation](https://kotlinlang.org/docs/delegation.html)
- [StateFlow and SharedFlow | Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Strangler pattern implementation for safe microservices transition](https://circleci.com/blog/strangler-pattern-implementation-for-safe-microservices-transition/)
- [Contract Testing: An Introduction and Complete 2025 Guide](https://www.testingmind.com/contract-testing-an-introduction-and-guide/)

---

*Decomposition research for KableBleRepository → 8-module system*
*Focused on interface preservation, behavioral equivalence, and safe incremental extraction*

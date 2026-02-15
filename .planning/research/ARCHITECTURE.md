# Architecture Patterns: KMP BLE Module Decomposition

**Project:** Project Phoenix MP - Vitruvian Trainer Control App
**Domain:** Kotlin Multiplatform (KMP) cross-platform BLE module architecture
**Researched:** 2026-02-15
**Overall Confidence:** HIGH

## Executive Summary

This project is decomposing a 2,886-line `KableBleRepository` monolith into 8 focused, testable modules in `commonMain`. The architecture pattern is **interface-based domain services with constructor injection**, mirroring the successful v0.4.1 management layer (MainViewModel → 5 managers).

**Key decision:** All modules stay in `commonMain` because BLE communication logic (parsing, queueing, state machines) is platform-agnostic. Only `Kable.Peripheral` (not business logic wrapping it) is platform-dependent. This allows JVM tests without iOS/Android hardware.

## Recommended Architecture

### Module Structure (8 Services)

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
├── queue/BleOperationQueue.kt              [NEW] Mutex serialization
├── protocol/ProtocolParser.kt              [NEW] Byte parsing
├── handle/HandleStateDetector.kt           [NEW] 4-state machine
├── monitor/MonitorDataProcessor.kt         [NEW] Cable validation, EMA
├── polling/MetricPollingEngine.kt          [NEW] Polling + heartbeat
├── connection/KableBleConnectionManager.kt [NEW] Scanning + lifecycle
├── easter/DiscoMode.kt                     [NEW] LED cycling
└── KableBleRepository.kt                   [REFACTORED] Thin facade
```

### Component Boundaries

| Component | Responsibility | Testable? |
|-----------|-----------------|-----------|
| **BleOperationQueue** | Serialize BLE write/read (Issue #222) | YES |
| **ProtocolParser** | Byte→domain parsing | YES |
| **HandleStateDetector** | 4-state machine (Issue #176) | YES |
| **MonitorDataProcessor** | Cable validation + EMA (Issue #210) | YES |
| **MetricPollingEngine** | Polling loops + heartbeat | YES |
| **KableBleConnectionManager** | Scanning + lifecycle | PARTIAL |
| **DiscoMode** | LED cycling easter egg | YES |
| **KableBleRepository** | Orchestrator facade | YES |

## Key Architecture Patterns

### Pattern 1: Interface + Constructor Injection

Constructor injection enables testability through dependency substitution. Inject interfaces, not concrete implementations.

```kotlin
interface BleOperationQueue {
    suspend fun <T> execute(operation: suspend () -> T): T
}

class TestQueue : BleOperationQueue {
    override suspend fun <T> execute(op: suspend () -> T) = op()
}
```

**When to use:** Services needing behavior substitution (queuing, I/O, Kable wrapping)

### Pattern 2: Pure Functions for Data Transformation

Pure functions have no side effects. Input → Output. Easy to test without mocks.

```kotlin
object ProtocolParser {
    fun parseRepNotification(bytes: ByteArray): RepNotification { ... }
}

@Test
fun parseRepNotificationEdgeCases() {
    val legacy6Bytes = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x02, 0x00)
    val rep = ProtocolParser.parseRepNotification(legacy6Bytes)
    assertEquals(1, rep.topCounter)
}
```

**When to use:** All parsing, validation, transformation logic

### Pattern 3: Callback-Based Polling

Callbacks decouple polling engine from data processing. Inject test handlers to verify behavior.

```kotlin
interface PollingCallback {
    suspend fun onMetricSampled(data: CableData)
    suspend fun onRepDetected(rep: RepNotification)
}

class MetricPollingEngine(
    private val callback: PollingCallback,
    private val queue: BleOperationQueue
) {
    suspend fun startMonitorPolling(peripheral: Peripheral) {
        while (isPollingActive) {
            val data = queue.execute { peripheral.read(MONITOR_CHAR_UUID) }
            callback.onMetricSampled(processMonitorData(data))
        }
    }
}
```

**When to use:** Long-running operations (polling, heartbeat)

### Pattern 4: State Machines with Explicit Transitions

State machines have clear contracts and edge case handling.

```kotlin
enum class HandleState {
    WaitingForRest, Released, Grabbed, Moving
}

class HandleStateDetector(private val grabForceThresholdN: Float = 30f) {
    private var currentState = HandleState.WaitingForRest

    fun processMetric(forceN: Float): HandleState {
        currentState = when (currentState) {
            WaitingForRest -> if (forceN < 5f) Released else WaitingForRest
            Released -> if (forceN > grabForceThresholdN) Grabbed else Released
            Grabbed -> if (forceN < 5f) Released else Grabbed
            Moving -> Moving
        }
        return currentState
    }
}
```

**When to use:** Handle state, connection states

## commonMain vs Platform-Specific Split

### All BLE Modules in commonMain

All 8 modules live in `commonMain` because their logic is platform-agnostic.

**Why:**
1. **Business logic is platform-agnostic** — parsing bytes, validating state doesn't care about OS
2. **Code reuse** — iOS and Android use identical state machines and parsing
3. **Testing** — JVM tests run fast, no emulator needed
4. **Kable abstracts the hard part** — we just use Kable's API, not OS-level BLE

### Only Wrap Kable APIs

The ONLY platform-specific part is `Kable.Scanner` and `Kable.Peripheral`. Wrap these in interfaces:

```kotlin
// commonMain - abstraction over Kable
interface BlePeripheralAdapter {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun read(serviceUuid: String, charUuid: String): ByteArray
}

// androidMain/iosMain - platform implementations
actual class KableBlePeripheralAdapter(val kablePeripheral: Peripheral) : BlePeripheralAdapter {
    actual override suspend fun connect() {
        kablePeripheral.connect()
    }
}
```

**Result:** Tests inject mock `BlePeripheralAdapter`, never touch Kable directly.

## Testing Strategy by Module Type

### Pure Functions (ProtocolParser)

- **Setup:** None
- **Approach:** Data-driven edge cases
- **Coverage:** 95%+

```kotlin
@Test
fun parseRepNotificationAllFormats() {
    val officialFormat = byteArrayOf(...24 bytes...)
    val rep = ProtocolParser.parseRepNotification(officialFormat)
    assertNotNull(rep)
}
```

### Stateful (HandleStateDetector, MonitorDataProcessor)

- **Setup:** Constructor parameters
- **Approach:** State assertions
- **Coverage:** 90%+

### Async (BleOperationQueue, MetricPollingEngine)

- **Setup:** Mock dependencies
- **Approach:** runTest { }, Flow testing with turbine
- **Coverage:** 85%+

```kotlin
@Test
fun queueSerializesOperations() = runTest {
    val queue = BleOperationQueue()
    val order = mutableListOf<Int>()

    launch { queue.execute { order.add(1); delay(10) } }
    launch { queue.execute { order.add(2) } }
    advanceUntilIdle()
    assertEquals(listOf(1, 2), order)
}
```

### Integration (KableBleRepository)

- **Setup:** Fake all sub-modules
- **Approach:** End-to-end scenarios
- **Coverage:** 70%+

## Anti-Patterns to Avoid

### 1. Hiding Kable Behind Concrete Class

**Bad:** Can't mock, tests need hardware
**Good:** Abstracted via interface, tests inject fake

### 2. Circular Mutable State Dependencies

**Bad:** Classes referencing each other mutably
**Good:** Use callbacks or shared state bus

### 3. Testing Platform-Specific Code

**Bad:** Trying to unit test Kable directly
**Good:** Test abstraction in commonTest

### 4. Mixing Polling with Domain Logic

**Bad:** Polling engine doing data conversion
**Good:** Separate concerns

## Scalability Considerations

| Concern | Approach |
|---------|----------|
| **Polling throughput (100+ metrics/sec)** | Check queue depth, monitor backlog |
| **Memory: parsing buffers** | Re-use buffers |
| **Memory: state machines** | < 1KB each |
| **BLE queue depth** | Should be 1-2, log if > 5 |
| **Coroutine leaks** | Cancel on disconnect |

## Decision: All Modules in commonMain (Not expect/actual)

### Why NOT expect/actual?

1. No platform difference in logic
2. expect/actual adds complexity with no benefit
3. Existing patterns use commonMain (v0.4.1 managers)

### When to use expect/actual:

- Platform-specific schedulers (use Dispatchers.IO instead)
- File I/O (use SQLDelight instead)
- Platform logging (use Kermit instead)

**Conclusion:** All 8 modules in `shared/src/commonMain`.

## Alignment with Existing Project Patterns

| Pattern | Applied? |
|---------|----------|
| `data/repository/BleRepository` interface + constructor injection | YES |
| FakeBleRepository in commonTest | YES |
| commonTest + androidUnitTest split | YES |
| Koin DI with feature modules | YES |
| No expect/actual for business logic | YES |
| 5 managers in commonMain (v0.4.1) | YES |

## Source References

**Authoritative sources verified:**

- [Kotlin Official Testing Docs](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) — Confirms commonTest best practice
- [KMP Testing Guide 2025](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) — Validates constructor injection, fake-first approach
- [Kable GitHub](https://github.com/JuulLabs/kable) — Coroutine-based API, requires wrapper
- **Existing Codebase:** FakeBleRepository (commonTest), 5 managers (v0.4.1)

## Recommended Phases

1. Extract BleOperationQueue + commonTest
2. Extract ProtocolParser + edge-case tests
3. Extract HandleStateDetector + state machine tests (Issue #176)
4. Extract MonitorDataProcessor + validation tests (Issue #210)
5. Extract MetricPollingEngine + polling tests
6. Extract KableBleConnectionManager + scanning tests
7. Extract DiscoMode (self-contained)
8. Refactor KableBleRepository as facade

**Success:** Each phase compiles, passes characterization tests, verified on device.

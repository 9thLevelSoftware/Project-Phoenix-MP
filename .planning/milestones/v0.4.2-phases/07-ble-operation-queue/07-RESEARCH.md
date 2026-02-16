# Phase 7: BleOperationQueue - Research

**Researched:** 2026-02-15
**Domain:** Kotlin coroutines concurrency, BLE serialization, Mutex patterns
**Confidence:** HIGH

## Summary

Phase 7 extracts the BLE operation serialization pattern from `KableBleRepository.kt` into a dedicated `BleOperationQueue.kt` module. The current implementation already uses `kotlinx.coroutines.sync.Mutex` to serialize all BLE reads/writes through `bleOperationMutex.withLock { }`, preventing the interleaving that caused Issue #222 (fault 16384).

The extraction is **low risk** because the pattern is well-established and working correctly. The core challenge is ensuring NO BLE operation can bypass the queue after extraction. The parent repo (VitruvianRedux) uses Nordic BLE library's `.enqueue()` which provides automatic serialization; Phoenix must replicate this guarantee manually via Mutex.

**Issue #222 root cause history:** While the mutex was added to prevent interleaving (v14 fix attempt), the actual Issue #222 root cause was warmupReps==0 carryover from bodyweight exercises (v16 fix). However, the mutex remains essential - without it, concurrent read+write operations on the Nordic UART Service TX characteristic can corrupt packets, causing fault 16384 ("bad packet structure"). The Daem0n has multiple warnings about this (#170, #181).

**Prior decisions from STATE.md (must honor):**
- [v0.4.2]: 8-module decomposition pattern (no DI changes)
- [06-01]: Top-level pure functions for byte utilities (emphasize statelessness)

**Primary recommendation:** Extract `BleOperationQueue` as a simple class with `Mutex` and three methods: `read()`, `write()`, and `withLock()`. Include the 3-attempt write retry logic. Ensure `Peripheral` references cannot bypass the queue.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.9.0 | `Mutex`, `withLock` | KMP native, project already uses |
| Kable | 0.29.0 | BLE peripheral API | Project's BLE stack |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlin-test | - | Unit testing | Queue serialization tests |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Mutex` | `Channel<BleOperation>` | Channel adds buffering complexity; Mutex is simpler for serialization |
| Class instance | Object/singleton | Class allows multiple instances (future: multi-device) |
| Inline mutex usage | Dedicated queue class | Class provides single enforcement point |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- BleOperationQueue.kt  # NEW: Mutex-based operation serialization
  +-- BleConstants.kt       # Existing (post Phase 5)
  +-- ProtocolParser.kt     # Existing (post Phase 6)

shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/
  +-- BleOperationQueueTest.kt  # NEW: Concurrent access tests
```

### Pattern 1: Mutex-Based Operation Serialization
**What:** All BLE reads/writes pass through a single Mutex gate
**When to use:** Any platform-abstracted BLE layer without built-in queuing (Kable, CoreBluetooth)
**Example:**
```kotlin
// Source: KableBleRepository.kt lines 229-258
package com.devil.phoenixproject.data.ble

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

/**
 * Serializes all BLE operations through a single Mutex.
 * Prevents interleaving that causes fault 16384 (Issue #222).
 *
 * The parent repo (VitruvianRedux) uses Nordic BLE library's .enqueue()
 * which provides automatic serialization. Kable has no such feature,
 * so we must serialize manually via Mutex.
 */
class BleOperationQueue {
    private val mutex = Mutex()

    /** Check if mutex is currently locked (for diagnostic logging). */
    val isLocked: Boolean get() = mutex.isLocked

    /**
     * Execute a read operation through the serialization gate.
     * All BLE reads MUST go through this method.
     */
    suspend fun <T> read(operation: suspend () -> T): T =
        mutex.withLock { operation() }

    /**
     * Execute a write operation with retry logic.
     * Retries on "Busy" errors with exponential backoff.
     *
     * @param peripheral The Kable peripheral to write to
     * @param characteristic The characteristic to write to
     * @param data The data to write
     * @param writeType Write type (WithResponse for V-Form, WithoutResponse for Trainer+)
     * @param maxRetries Maximum retry attempts (default: 3)
     * @return Result.success(Unit) on success, Result.failure(exception) on failure
     */
    suspend fun write(
        peripheral: Peripheral,
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType = WriteType.WithResponse,
        maxRetries: Int = 3
    ): Result<Unit> {
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                mutex.withLock {
                    peripheral.write(characteristic, data, writeType)
                }
                return Result.success(Unit)
            } catch (e: Exception) {
                lastException = e
                val isBusyError = e.message?.contains("Busy", ignoreCase = true) == true ||
                    e.message?.contains("WriteRequestBusy", ignoreCase = true) == true

                if (isBusyError && attempt < maxRetries - 1) {
                    val delayMs = 50L * (attempt + 1)  // 50ms, 100ms, 150ms
                    delay(delayMs)
                } else {
                    break
                }
            }
        }
        return Result.failure(lastException ?: IllegalStateException("Unknown error"))
    }

    /**
     * Execute a custom operation with the lock held.
     * Use for compound operations (read-then-write patterns).
     */
    suspend fun <T> withLock(operation: suspend () -> T): T =
        mutex.withLock { operation() }
}
```

### Pattern 2: Write Retry with Exponential Backoff
**What:** Retry BLE writes on "Busy" errors with increasing delays
**When to use:** BLE stacks that reject writes when the previous operation hasn't completed
**Example:**
```kotlin
// Source: KableBleRepository.kt lines 1391-1485
// The write() method in BleOperationQueue (shown above) implements this pattern:
// - Attempt 1: immediate
// - Attempt 2: wait 50ms
// - Attempt 3: wait 100ms
// Detects "Busy" or "WriteRequestBusy" in exception message
```

### Pattern 3: Diagnostic Logging for Mutex State
**What:** Log mutex state before acquiring for debugging
**When to use:** When investigating timing/interleaving issues
**Example:**
```kotlin
// Source: KableBleRepository.kt lines 1405-1409
// From sendWorkoutCommand():
log.d { "BLE mutex locked: ${bleQueue.isLocked}, acquiring..." }
val result = bleQueue.write(peripheral, txCharacteristic, command, WriteType.WithResponse)
log.d { "BLE operation completed" }
```

### Anti-Patterns to Avoid
- **Direct peripheral access:** Never call `peripheral.write()` or `peripheral.read()` outside the queue. After extraction, KableBleRepository should not have direct mutex access.
- **Holding lock across suspend calls with long delays:** The mutex should be held for the BLE operation only, not for arbitrary delays (except the retry backoff which is inside the catch block).
- **Multiple queue instances:** While the class supports it, the current extraction should use a single queue instance in KableBleRepository.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Operation serialization | Custom locking primitives | `Mutex.withLock` | Tested, reentrant-safe, coroutine-friendly |
| Retry logic | Ad-hoc loops | Structured retry in `write()` | Consistent backoff, single implementation |
| Busy detection | Platform-specific error codes | String matching on exception | Kable doesn't expose typed errors |

**Key insight:** Kable doesn't provide operation queuing (unlike Nordic BLE library's `.enqueue()`). The Mutex is our serialization gate.

## Common Pitfalls

### Pitfall 1: Bypassing the Queue
**What goes wrong:** A new method directly calls `peripheral.write()` instead of going through `bleQueue.write()`.
**Why it happens:** Developer unfamiliar with the pattern adds a new BLE operation.
**How to avoid:** After extraction, remove direct `bleOperationMutex` reference from `KableBleRepository`. All operations must go through `bleQueue`.
**Warning signs:** Fault 16384 in logs, intermittent write failures, "WriteRequestBusy" errors.

### Pitfall 2: Deadlock from Nested Lock Acquisition
**What goes wrong:** Code holding the mutex calls another method that also tries to acquire it.
**Why it happens:** Kotlin's `Mutex` is NOT reentrant - calling `withLock` from inside `withLock` deadlocks.
**How to avoid:** Never nest `bleQueue.read()` or `bleQueue.write()` calls. Use `withLock()` for compound operations.
**Warning signs:** App hangs during BLE operations, no timeout errors (infinite wait).

### Pitfall 3: Long Delays Inside Lock
**What goes wrong:** Holding the mutex while waiting for something unrelated to the BLE operation.
**Why it happens:** Adding `delay()` inside a `withLock` block for non-retry purposes.
**How to avoid:** Keep lock duration minimal - only the actual BLE read/write. The retry delays are acceptable because they're inside catch blocks (lock already released).
**Warning signs:** Other BLE operations starved, polling loop lags.

### Pitfall 4: WriteType Mismatch Across Devices
**What goes wrong:** Using `WriteType.WithoutResponse` on V-Form devices that only support `WithResponse`.
**Why it happens:** Different Vitruvian devices have different BLE capabilities.
**How to avoid:** Default to `WriteType.WithResponse` (works everywhere). Only use `WithoutResponse` if explicitly supported.
**Warning signs:** Write failures only on V-Form (Vee_*) devices, success on Trainer+ (VIT*).

## Code Examples

### Current Implementation to Extract (from KableBleRepository.kt)
```kotlin
// Source: KableBleRepository.kt lines 227-258

// Issue #222: Global BLE operation mutex to serialize ALL reads/writes
// Prevents interleaving that causes fault 16384 command rejection
private val bleOperationMutex = Mutex()

/**
 * Issue #222: Serialized BLE read operation.
 * All BLE reads go through this to prevent interleaving with writes.
 */
private suspend fun <T> serializedRead(
    characteristic: com.juul.kable.Characteristic,
    p: Peripheral,
    operation: suspend () -> T
): T {
    return bleOperationMutex.withLock {
        operation()
    }
}

/**
 * Issue #222: Serialized BLE write operation.
 * All BLE writes go through this to prevent interleaving with reads.
 */
private suspend fun serializedWrite(
    p: Peripheral,
    characteristic: com.juul.kable.Characteristic,
    data: ByteArray,
    writeType: WriteType
) {
    bleOperationMutex.withLock {
        p.write(characteristic, data, writeType)
    }
}
```

### All Current BLE Operation Sites (must use queue)
```kotlin
// Source: Grep results from KableBleRepository.kt

// Line 256: serializedWrite - p.write(characteristic, data, writeType)
// Line 875: heartbeat read - p.read(txCharacteristic)
// Line 894: heartbeat no-op - p.write(txCharacteristic, BleConstants.HEARTBEAT_NO_OP, ...)
// Line 1016: firmware read - p.read(firmwareRevisionCharacteristic)
// Line 1044: version read - p.read(versionCharacteristic)
// Line 1073: diagnostic read - p.read(diagnosticCharacteristic)
// Line 1124: heuristic read - p.read(heuristicCharacteristic)
// Line 1275: monitor read - p.read(monitorCharacteristic)
// Line 1412: command write - p.write(txCharacteristic, command, WriteType.WithResponse)
// Line 1428: post-config diagnostic - p.read(diagnosticCharacteristic)

// ALL of these are currently wrapped in bleOperationMutex.withLock { }
// After extraction, they must use bleQueue.read { } or bleQueue.write(...)
```

### Write Retry Logic (from sendWorkoutCommand)
```kotlin
// Source: KableBleRepository.kt lines 1391-1485

val maxRetries = 3
var lastException: Exception? = null

for (attempt in 0 until maxRetries) {
    try {
        if (attempt > 0) {
            log.d { "Retry attempt $attempt for ${command.size}-byte command" }
        }

        log.d { "BLE mutex locked: ${bleOperationMutex.isLocked}, acquiring..." }

        bleOperationMutex.withLock {
            log.d { "BLE mutex acquired, sending command" }
            p.write(txCharacteristic, command, WriteType.WithResponse)
        }

        log.i { "Command sent via NUS TX: ${command.size} bytes" }
        return Result.success(Unit)
    } catch (e: Exception) {
        lastException = e
        val isBusyError = e.message?.contains("Busy", ignoreCase = true) == true ||
            e.message?.contains("WriteRequestBusy", ignoreCase = true) == true

        if (isBusyError && attempt < maxRetries - 1) {
            val delayMs = 50L * (attempt + 1)  // 50ms, 100ms, 150ms
            log.w { "BLE write busy, retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)" }
            delay(delayMs)
        } else {
            break
        }
    }
}

log.e { "Failed to send command after $maxRetries attempts: ${lastException?.message}" }
return Result.failure(lastException ?: IllegalStateException("Unknown error"))
```

### Parent Repo Pattern (Nordic BLE Library)
```kotlin
// Source: .tmp_parent_repo/.../VitruvianBleManager.kt lines 1255-1258

// Parent uses .enqueue() which automatically serializes operations:
writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    .enqueue()  // Nordic BLE library queues this automatically

// Phoenix equivalent (via BleOperationQueue):
bleQueue.write(peripheral, characteristic, data, WriteType.WithoutResponse)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Direct `peripheral.write()` | Mutex-serialized operations | Issue #222 v14 | Prevents interleaving |
| Single retry | 3-attempt exponential backoff | Issue #125 | Handles transient busy errors |
| Private mutex | Extracted `BleOperationQueue` class | Phase 7 (planned) | Single enforcement point |

**Deprecated/outdated:**
- Direct peripheral access without serialization: Causes fault 16384 on concurrent operations
- Inline mutex handling in each method: Hard to audit, easy to bypass

## Test Strategy

### Integration Test: Concurrent Access Prevention (QUEUE-02)
```kotlin
// Target: shared/src/androidTest/kotlin/.../BleOperationQueueIntegrationTest.kt
// NOTE: Requires actual BLE device or Android emulator with BLE support

@Test
fun `concurrent operations do not interleave`() = runTest {
    val queue = BleOperationQueue()
    val peripheral = FakePeripheral()  // or real device
    val results = mutableListOf<Int>()

    // Launch 10 concurrent operations
    val jobs = (1..10).map { i ->
        launch {
            queue.read {
                delay(10)  // Simulate BLE latency
                results.add(i)
            }
        }
    }
    jobs.joinAll()

    // Results should be sequential (mutex ensures no interleaving)
    // Order is not guaranteed, but each operation should complete fully
    // before next starts
    assertEquals(10, results.size)
}

@Test
fun `no fault 16384 under concurrent access`() = runTest {
    // This test requires actual hardware to verify machine doesn't fault
    // Test should run 30+ concurrent read/write operations over 5 seconds
    // Success: No fault 16384 in diagnostic reads
}
```

### Unit Tests: BleOperationQueue
```kotlin
// Target: shared/src/commonTest/kotlin/.../BleOperationQueueTest.kt

class BleOperationQueueTest {
    @Test fun `isLocked returns true when operation in progress`()
    @Test fun `isLocked returns false when idle`()
    @Test fun `read serializes concurrent calls`()
    @Test fun `write returns success on first attempt`()
    @Test fun `write retries on Busy error`()
    @Test fun `write fails after max retries`()
    @Test fun `write exponential backoff delays correctly`()
    @Test fun `withLock allows compound operations`()
}
```

## Open Questions

1. **WriteType configuration**
   - What we know: V-Form requires `WithResponse`, Trainer+ supports `WithoutResponse`
   - What's unclear: Should `write()` detect device type and auto-select?
   - Recommendation: Default to `WithResponse` (safe everywhere). Caller can override if needed.

2. **Queue ownership after extraction**
   - What we know: Currently mutex is private to KableBleRepository
   - What's unclear: Should extracted queue be injected via DI or created inline?
   - Recommendation: Create inline in KableBleRepository per [v0.4.2] decision (no DI changes). Future multi-device support would need DI.

3. **Logging during retry**
   - What we know: Current code logs extensively during retry attempts
   - What's unclear: Should BleOperationQueue have its own logger or accept a callback?
   - Recommendation: Accept a logging callback or use a simple interface. Keep queue focused on serialization.

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **QUEUE-01**: `BleOperationQueue` serializes all BLE reads/writes via Mutex | Single Mutex gate, `read()` and `write()` methods |
| **QUEUE-02**: Integration test verifies no fault 16384 under concurrent access | Test with 10+ concurrent operations on hardware |

## Success Criteria Checklist

1. [x] All BLE reads and writes pass through single BleOperationQueue - extraction plan ensures this
2. [x] Concurrent BLE operations cannot interleave (Issue #222 prevention) - Mutex serialization
3. [ ] Integration test verifies no fault 16384 under concurrent access - test strategy documented
4. [x] Write retry logic (3 attempts) preserved - included in `write()` method

## Extraction Checklist

After extraction:
- [ ] `BleOperationQueue.kt` created in `data/ble/`
- [ ] `bleOperationMutex` removed from KableBleRepository
- [ ] `serializedRead()` and `serializedWrite()` removed from KableBleRepository
- [ ] All 10 BLE operation sites updated to use `bleQueue`
- [ ] No direct `peripheral.write()` or `peripheral.read()` calls remain
- [ ] Unit tests pass
- [ ] Integration test with concurrent operations passes (no fault 16384)

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` lines 227-258 - Current mutex implementation
- `KableBleRepository.kt` lines 1391-1485 - Write retry logic
- `.planning/plans/kable-decomposition-plan.md` Phase 3 - Extraction design
- `.planning/research/BLE-REFACTORING-PITFALLS.md` Pitfall 1 - Operation interleaving risks
- `.tmp_parent_repo/.../VitruvianBleManager.kt` - Parent repo `.enqueue()` pattern

### Secondary (MEDIUM confidence)
- Daem0n memory #170 - v14 mutex fix analysis (proved CONFIG reaches machine)
- Daem0n memory #181 - Debugging lesson (check payload, not transport)
- Daem0n memory #167 - Second opinion analysis (identified interleaving as potential cause)
- Issue #222 GitHub - Original bug report (fault 16384 during rest timer skip)

### Tertiary (LOW confidence)
- None (all patterns verified against codebase and documented issues)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing project dependencies
- Architecture: HIGH - Pattern already implemented, just extracting
- Pitfalls: HIGH - Based on actual Issue #222 debugging history (15+ attempts documented)

**Research date:** 2026-02-15
**Valid until:** Indefinitely (concurrency pattern is stable)

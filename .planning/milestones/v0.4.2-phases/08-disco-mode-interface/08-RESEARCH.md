# Phase 8: DiscoMode + Interface - Research

**Researched:** 2026-02-15
**Domain:** Kotlin module extraction, interface design, dependency inversion
**Confidence:** HIGH

## Summary

Phase 8 extracts the disco mode easter egg from `KableBleRepository.kt` into a self-contained `DiscoMode.kt` module and adds `setLastColorSchemeIndex()` to the `BleRepository` interface to eliminate the concrete cast in `SettingsManager.kt` (Issue #144).

The current disco mode implementation is already well-isolated within `KableBleRepository.kt` (lines 2393-2468): three methods (`startDiscoMode()`, `stopDiscoMode()`, `setLastColorSchemeIndex()`), two properties (`discoJob`, `lastColorSchemeIndex`), and one state flow (`_discoModeActive`). This makes extraction straightforward.

The interface change (IFACE-01, IFACE-02) is a breaking change for all `BleRepository` implementations, but the decomposition plan already documents the mitigation: add `setLastColorSchemeIndex(index: Int)` with a default no-op implementation. `FakeBleRepository` and `SimulatorBleRepository` already implement `startDiscoMode()` and `stopDiscoMode()` via the interface; they will receive a trivial `setLastColorSchemeIndex()` implementation.

**Primary recommendation:** Extract `DiscoMode` as a class that takes a command sender callback (lambda `(ByteArray) -> Unit`) to avoid coupling to `KableBleRepository`. Add `setLastColorSchemeIndex` to `BleRepository` interface with default no-op. Update `SettingsManager.kt` to call via interface.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.9.0 | Job, CoroutineScope, delay | Project's async runtime |
| Kermit | 2.0.4 | Logging | Project's logging library |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlin-test | - | Unit testing | Disco mode state machine tests |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Callback `(ByteArray) -> Unit` | Direct `BleRepository` reference | Callback avoids circular dependency, easier testing |
| Class instance | Object singleton | Class allows instance-scoped job management per [07-01] decision |
| Interface method with default | Abstract method | Default avoids forcing implementations to change |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- DiscoMode.kt        # NEW: Easter egg LED cycling
  +-- BleOperationQueue.kt
  +-- ProtocolParser.kt
  +-- BleConstants.kt
  +-- ...

shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
  +-- BleRepository.kt    # MODIFIED: +setLastColorSchemeIndex()
  +-- KableBleRepository.kt  # MODIFIED: delegates to DiscoMode
  +-- ...
```

### Pattern 1: Callback-Based Module Extraction
**What:** Module accepts a callback for external operations rather than holding a direct reference to its parent
**When to use:** When the extracted module needs to perform operations on the parent (like sending BLE commands) without creating circular dependencies
**Example:**
```kotlin
// Source: Derived from KableBleRepository.kt lines 2393-2468
package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.BlePacketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Disco Mode easter egg - rapidly cycles LED colors on the Vitruvian machine.
 *
 * Self-contained module extracted from KableBleRepository.
 * Uses callback for command sending to avoid circular dependency.
 *
 * @param scope Coroutine scope for launching the color cycling job
 * @param sendCommand Callback to send BLE commands (typically KableBleRepository::sendWorkoutCommand)
 */
class DiscoMode(
    private val scope: CoroutineScope,
    private val sendCommand: suspend (ByteArray) -> Unit
) {
    private val log = Logger.withTag("DiscoMode")

    private var discoJob: Job? = null
    private var lastColorSchemeIndex: Int = 0

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /**
     * Start disco mode - rapidly cycles through LED color schemes.
     * No-op if already running or not connected (sendCommand would fail).
     */
    fun start() {
        if (discoJob?.isActive == true) {
            log.d { "Disco mode already active" }
            return
        }

        log.i { "Starting DISCO MODE!" }
        _isActive.value = true

        discoJob = scope.launch {
            var colorIndex = 0
            val colorCount = 7  // Schemes 0-6 (excluding "None" at 7)
            val intervalMs = 300L

            while (isActive) {
                try {
                    val command = BlePacketFactory.createColorSchemeCommand(colorIndex)
                    sendCommand(command)
                    colorIndex = (colorIndex + 1) % colorCount
                    delay(intervalMs)
                } catch (e: Exception) {
                    log.w { "Disco mode error: ${e.message}" }
                    break
                }
            }
            log.d { "Disco mode coroutine ended" }
        }
    }

    /**
     * Stop disco mode and restore the last selected color scheme.
     */
    fun stop() {
        if (discoJob?.isActive != true && !_isActive.value) {
            return
        }

        log.i { "Stopping disco mode, restoring color scheme $lastColorSchemeIndex" }
        discoJob?.cancel()
        discoJob = null
        _isActive.value = false

        // Restore the user's color scheme
        scope.launch {
            try {
                val command = BlePacketFactory.createColorSchemeCommand(lastColorSchemeIndex)
                sendCommand(command)
            } catch (e: Exception) {
                log.w { "Failed to restore color scheme: ${e.message}" }
            }
        }
    }

    /**
     * Update the stored color scheme index.
     * Called when user changes color in settings.
     */
    fun setLastColorSchemeIndex(index: Int) {
        lastColorSchemeIndex = index
    }
}
```

### Pattern 2: Interface Method with Default Implementation
**What:** Adding a new method to an existing interface with a no-op default to avoid breaking existing implementations
**When to use:** When extending an interface used by multiple implementations (fakes, simulators, concrete)
**Example:**
```kotlin
// Source: BleRepository.kt lines 245-262 + new method
interface BleRepository {
    // ... existing members ...

    val discoModeActive: StateFlow<Boolean>
    fun startDiscoMode()
    fun stopDiscoMode()

    /**
     * Update the color scheme index for disco mode restore.
     * NEW in Phase 8: Added to interface to eliminate concrete cast in SettingsManager.
     * Default no-op for implementations that don't need it.
     */
    fun setLastColorSchemeIndex(index: Int) {
        // No-op default - only KableBleRepository needs this
    }
}
```

### Anti-Patterns to Avoid
- **Concrete casting in consumers:** `(bleRepository as? KableBleRepository)?.setLastColorSchemeIndex()` violates dependency inversion. Always use the interface.
- **Direct BleRepository reference in DiscoMode:** Creates circular dependency. Use callback instead.
- **State leakage:** DiscoMode should fully own its job and state; KableBleRepository should not directly access `discoJob`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Color scheme command | Raw byte array construction | `BlePacketFactory.createColorSchemeCommand()` | Already exists, tested |
| Job management | Manual Thread/Handler | `CoroutineScope.launch` + `Job.cancel()` | KMP native, structured concurrency |
| State exposure | Mutable public property | `MutableStateFlow` + `asStateFlow()` | Project pattern for reactive state |

**Key insight:** DiscoMode is purely a state machine that cycles colors. All BLE complexity lives in the callback.

## Common Pitfalls

### Pitfall 1: Circular Dependency via Direct Reference
**What goes wrong:** `DiscoMode` takes `KableBleRepository` reference, creating import cycle.
**Why it happens:** Natural to pass the parent when extracting.
**How to avoid:** Use callback lambda `suspend (ByteArray) -> Unit` for command sending.
**Warning signs:** Compile error about circular imports, or tight coupling in tests.

### Pitfall 2: Forgetting to Update All Interface Implementations
**What goes wrong:** Adding method to `BleRepository` breaks `FakeBleRepository`, `SimulatorBleRepository`.
**Why it happens:** Interface change without checking implementors.
**How to avoid:** Use default implementation in interface. Verify with `./gradlew :shared:compileKotlinAndroid`.
**Warning signs:** Compile errors mentioning "does not implement abstract member".

### Pitfall 3: SettingsManager Still Casting After Interface Update
**What goes wrong:** Issue #144 not actually fixed because cast wasn't removed.
**Why it happens:** Interface method added but consumer code not updated.
**How to avoid:** Search for `as? KableBleRepository` and replace with interface call.
**Warning signs:** Grep finds cast remaining in `SettingsManager.kt`.

### Pitfall 4: DiscoMode Job Outlives Connection
**What goes wrong:** Disco mode continues cycling colors after BLE disconnect.
**Why it happens:** Job not cancelled when connection drops.
**How to avoid:** `KableBleRepository` calls `discoMode.stop()` in disconnect handler.
**Warning signs:** LED colors cycling on reconnect attempt, or crash from null peripheral.

## Code Examples

### Current Implementation to Extract
```kotlin
// Source: KableBleRepository.kt lines 161-163 (state)
private var discoJob: kotlinx.coroutines.Job? = null
private var lastColorSchemeIndex: Int = 0  // To restore after disco mode

// Source: KableBleRepository.kt lines 144-146 (flow)
private val _discoModeActive = MutableStateFlow(false)
override val discoModeActive: StateFlow<Boolean> = _discoModeActive.asStateFlow()

// Source: KableBleRepository.kt lines 2395-2438 (startDiscoMode)
override fun startDiscoMode() {
    if (discoJob?.isActive == true) { return }
    if (peripheral == null) { log.w { "Cannot start - not connected" }; return }

    _discoModeActive.value = true
    discoJob = scope.launch {
        var colorIndex = 0
        while (isActive) {
            val command = BlePacketFactory.createColorSchemeCommand(colorIndex)
            sendWorkoutCommand(command)
            colorIndex = (colorIndex + 1) % 7
            delay(300L)
        }
    }
}

// Source: KableBleRepository.kt lines 2441-2459 (stopDiscoMode)
override fun stopDiscoMode() {
    if (discoJob?.isActive != true && !_discoModeActive.value) { return }
    discoJob?.cancel()
    discoJob = null
    _discoModeActive.value = false
    scope.launch {
        val command = BlePacketFactory.createColorSchemeCommand(lastColorSchemeIndex)
        sendWorkoutCommand(command)
    }
}

// Source: KableBleRepository.kt lines 2466-2468 (setLastColorSchemeIndex)
fun setLastColorSchemeIndex(index: Int) {
    lastColorSchemeIndex = index
}
```

### SettingsManager Casting (Issue #144)
```kotlin
// Source: SettingsManager.kt lines 77-83
fun setColorScheme(schemeIndex: Int) {
    scope.launch {
        bleRepository.setColorScheme(schemeIndex)
        preferencesManager.setColorScheme(schemeIndex)
        // ISSUE #144: Concrete cast violates dependency inversion
        (bleRepository as? KableBleRepository)?.setLastColorSchemeIndex(schemeIndex)
    }
}

// AFTER Phase 8:
fun setColorScheme(schemeIndex: Int) {
    scope.launch {
        bleRepository.setColorScheme(schemeIndex)
        preferencesManager.setColorScheme(schemeIndex)
        bleRepository.setLastColorSchemeIndex(schemeIndex)  // Interface method
    }
}
```

### KableBleRepository Delegation Pattern
```kotlin
// After extraction, KableBleRepository delegates to DiscoMode:

// Property (near line 163):
private val discoMode = DiscoMode(
    scope = scope,
    sendCommand = { command -> sendWorkoutCommand(command) }
)

// Interface delegation:
override val discoModeActive: StateFlow<Boolean> = discoMode.isActive
override fun startDiscoMode() = discoMode.start()
override fun stopDiscoMode() = discoMode.stop()
override fun setLastColorSchemeIndex(index: Int) = discoMode.setLastColorSchemeIndex(index)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Inline disco logic in KableBleRepository | Extracted `DiscoMode` module | Phase 8 (planned) | Single-responsibility, testable |
| Concrete cast in SettingsManager | Interface method with default | Phase 8 (planned) | Dependency inversion |
| `fun setLastColorSchemeIndex` on class | `fun setLastColorSchemeIndex` on interface | Phase 8 (planned) | All consumers use interface |

**Deprecated/outdated:**
- Direct access to `lastColorSchemeIndex` from KableBleRepository: Should be encapsulated in DiscoMode
- `as? KableBleRepository` casting: Anti-pattern, replaced by interface method

## Test Strategy

### Unit Tests: DiscoMode
```kotlin
// Target: shared/src/commonTest/kotlin/.../DiscoModeTest.kt

class DiscoModeTest {
    @Test fun `start sets isActive to true`()
    @Test fun `start is no-op when already active`()
    @Test fun `stop sets isActive to false`()
    @Test fun `stop is no-op when not active`()
    @Test fun `stop restores lastColorSchemeIndex`()
    @Test fun `setLastColorSchemeIndex updates stored index`()
    @Test fun `start cycles through 7 colors`()
    @Test fun `color commands sent via callback`()
}
```

### Integration Test: SettingsManager No Cast
```bash
# Verification that Issue #144 is fixed:
grep -c "as? KableBleRepository" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
# Should return 0
```

## Open Questions

1. **Connection guard in DiscoMode**
   - What we know: Current implementation checks `peripheral == null` before starting
   - What's unclear: Should DiscoMode know about connection state, or trust the callback to fail?
   - Recommendation: Remove connection check from DiscoMode. If not connected, `sendWorkoutCommand` will fail gracefully. Keeps DiscoMode decoupled.

2. **Cleanup on disconnect**
   - What we know: If connection drops mid-disco, job continues until error
   - What's unclear: Should KableBleRepository explicitly stop disco mode on disconnect?
   - Recommendation: Yes, add `discoMode.stop()` in disconnect handler. Belt-and-suspenders.

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **DISCO-01**: `DiscoMode` extracted as self-contained module | `DiscoMode.kt` in `data/ble/` with callback-based design |
| **IFACE-01**: `setLastColorSchemeIndex` added to `BleRepository` interface | Interface method with default no-op implementation |
| **IFACE-02**: `SettingsManager` no longer casts to concrete type (Issue #144) | Replace cast with interface method call |

## Success Criteria

Per phase requirements from ROADMAP.md:
1. [x] `DiscoMode.start()` cycles LED colors correctly on connected device - Implementation plan covers this
2. [x] `DiscoMode.stop()` restores last color scheme - Implementation plan covers this
3. [x] `setLastColorSchemeIndex()` available on BleRepository interface - Interface change documented
4. [x] `SettingsManager` no longer casts to KableBleRepository (Issue #144 fixed) - Code change documented

## Extraction Checklist

- [ ] `DiscoMode.kt` created in `data/ble/`
- [ ] `discoJob`, `lastColorSchemeIndex`, `_discoModeActive` removed from KableBleRepository
- [ ] `startDiscoMode()`, `stopDiscoMode()`, `setLastColorSchemeIndex()` delegate to DiscoMode
- [ ] `setLastColorSchemeIndex()` added to `BleRepository` interface with default no-op
- [ ] `FakeBleRepository` has `setLastColorSchemeIndex()` implementation (can be empty)
- [ ] `SimulatorBleRepository` has `setLastColorSchemeIndex()` implementation (can be empty)
- [ ] `SettingsManager.kt` uses interface method instead of cast
- [ ] No `as? KableBleRepository` remaining in codebase
- [ ] Unit tests for DiscoMode pass
- [ ] All existing tests pass

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` lines 161-163, 2393-2468 - Current disco mode implementation
- `BleRepository.kt` lines 244-262 - Current interface (discoModeActive, startDiscoMode, stopDiscoMode)
- `SettingsManager.kt` lines 77-83 - Issue #144 concrete cast
- `.planning/plans/kable-decomposition-plan.md` Issue #144 mitigation - Default implementation strategy

### Secondary (MEDIUM confidence)
- `.planning/REQUIREMENTS.md` - DISCO-01, IFACE-01, IFACE-02 requirements
- `.planning/ROADMAP.md` Phase 8 - Success criteria
- `SimulatorBleRepository.kt`, `FakeBleRepository.kt` - Existing interface implementations

### Tertiary (LOW confidence)
- None (all patterns verified against codebase)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing project dependencies (coroutines, Kermit)
- Architecture: HIGH - Pattern follows Phase 7 extraction approach
- Pitfalls: HIGH - Based on actual Issue #144 and interface evolution experience

**Research date:** 2026-02-15
**Valid until:** Indefinitely (extraction pattern is stable)

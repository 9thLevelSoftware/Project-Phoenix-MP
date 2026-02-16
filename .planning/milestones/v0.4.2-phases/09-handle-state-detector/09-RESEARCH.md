# Phase 9: HandleStateDetector - Research

**Researched:** 2026-02-15
**Domain:** Kotlin state machine extraction, BLE handle detection, unit testing
**Confidence:** HIGH

## Summary

Phase 9 extracts the 4-state handle detection state machine from `KableBleRepository.kt` into a standalone `HandleStateDetector.kt` module. The handle state machine is responsible for detecting whether the user has grabbed, released, or is moving the Vitruvian machine handles -- a critical feature for "Just Lift" auto-start/auto-stop mode.

The current implementation spans approximately 250 lines of logic in `KableBleRepository.kt` (the `analyzeHandleState()` function at lines 2065-2233, plus ~15 state variables at lines 166-221, plus control methods at lines 1487-1567). The logic is self-contained -- it reads position and velocity from `WorkoutMetric` objects and produces `HandleState` transitions. It does NOT depend on BLE operations, making it an ideal extraction candidate. The only external dependency is `currentTimeMillis()` for hysteresis timing, which is easily injectable for testability.

The state machine has evolved significantly beyond the parent repo's simpler version. The parent repo (VitruvianBleManager.kt) has a ~75-line state machine with no hysteresis, no baseline tracking, and no timeout escape. The current KMP version adds: Issue #176 overhead pulley baseline tracking, Task 14 hysteresis dwell timers (200ms), iOS autostart timeout escape (3s), active handle mask for single-cable release detection, and Issue #96 lower velocity threshold for auto-start mode. All of these features must be preserved exactly.

**Primary recommendation:** Extract `HandleStateDetector` as a class that takes a `timeProvider: () -> Long` callback (for testability). It owns the state machine, all hysteresis timers, baseline tracking, and position range diagnostics. `KableBleRepository` delegates `analyzeHandleState()`, `enableHandleDetection()`, `resetHandleState()`, and `enableJustLiftWaitingMode()` control to this module. The `HandleDetection` (simple left/right boolean) logic moves into the detector as well since it is always co-evaluated.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx-coroutines-core | 1.9.0 | StateFlow for handle state exposure | Project's async runtime |
| Kermit | 2.0.4 | Diagnostic logging | Project's KMP logging library |
| kotlin-test | - | Unit testing state transitions | Project's test framework |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx-datetime | 0.6.0 | Clock.System for time in production | Already used in KableBleRepository |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `timeProvider: () -> Long` callback | Direct `Clock.System.now()` | Callback allows deterministic testing with fake time |
| Class instance | Object singleton | Class allows instance-scoped state per [07-01] decision (multi-device future) |
| Internal StateFlow | Return state from `processMetric()` | StateFlow matches existing BleRepository interface pattern and enables downstream collection |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- HandleStateDetector.kt  # NEW: 4-state handle detection machine
  +-- DiscoMode.kt
  +-- BleOperationQueue.kt
  +-- ProtocolParser.kt
  +-- ProtocolModels.kt
  +-- BleExtensions.kt
  +-- BleExceptions.kt

shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
  +-- BleRepository.kt         # UNCHANGED (HandleState enum stays here)
  +-- KableBleRepository.kt    # MODIFIED: delegates to HandleStateDetector

shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/
  +-- HandleStateDetectorTest.kt  # NEW: comprehensive state transition tests
```

### Pattern 1: Pure State Machine with Injectable Time
**What:** The state machine processes metrics and produces state transitions. Time is injected via a `() -> Long` lambda to enable deterministic testing.
**When to use:** When the module needs wall-clock time for hysteresis but must be testable without real delays.
**Example:**
```kotlin
// Source: Derived from KableBleRepository.kt lines 2065-2233
package com.devil.phoenixproject.data.ble

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.HandleDetection
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.util.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 4-state handle detection machine for Just Lift auto-start/auto-stop.
 *
 * Extracted from KableBleRepository (Phase 9).
 * Pure state machine: processes WorkoutMetric -> HandleState transitions.
 *
 * States: WaitingForRest -> Released -> Grabbed -> Moving
 *
 * Features preserved from KableBleRepository:
 * - Issue #176: Overhead pulley baseline tracking (relative grab/release detection)
 * - Task 14: Hysteresis dwell timers (200ms sustained before state transition)
 * - iOS autostart fix: WaitingForRest timeout escape (3s)
 * - Issue #96: Lower velocity threshold for auto-start mode
 * - Single-handle exercise support via activeHandlesMask
 *
 * @param timeProvider Injectable time source (default: Clock.System for production)
 */
class HandleStateDetector(
    private val timeProvider: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() }
) {
    private val log = Logger.withTag("HandleStateDetector")

    // State flows
    private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
    val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

    private val _handleDetection = MutableStateFlow(HandleDetection())
    val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

    // Configuration
    var isEnabled: Boolean = false
        private set
    var isAutoStartMode: Boolean = false
        private set

    // Hysteresis timers (Task 14)
    private var pendingGrabbedStartTime: Long? = null
    private var pendingReleasedStartTime: Long? = null

    // Active handle tracking for single-cable release detection
    private var activeHandlesMask: Int = 0

    // WaitingForRest timeout (iOS autostart fix)
    private var waitingForRestStartTime: Long? = null

    // Issue #176: Overhead pulley baseline tracking
    private var restBaselinePosA: Double? = null
    private var restBaselinePosB: Double? = null

    // Position range diagnostics
    var minPositionSeen: Double = Double.MAX_VALUE
        private set
    var maxPositionSeen: Double = Double.MIN_VALUE
        private set

    // Periodic logging counter
    private var logCounter = 0L

    // Legacy grab/release timers (from parent repo)
    private var forceAboveGrabThresholdStart: Long? = null
    private var forceBelowReleaseThresholdStart: Long? = null

    /**
     * Process a workout metric and update handle state.
     * Called from parseMonitorData and parseRxMetrics in KableBleRepository.
     */
    fun processMetric(metric: WorkoutMetric) {
        if (!isEnabled) return

        // Simple handle detection (left/right boolean)
        val activeThreshold = 50.0f
        val leftDetected = metric.positionA > activeThreshold
        val rightDetected = metric.positionB > activeThreshold
        val currentDetection = _handleDetection.value
        if (currentDetection.leftDetected != leftDetected || currentDetection.rightDetected != rightDetected) {
            _handleDetection.value = HandleDetection(leftDetected, rightDetected)
        }

        // 4-state machine
        val newState = analyzeHandleState(metric)
        if (newState != _handleState.value) {
            log.d { "Handle state: ${_handleState.value} -> $newState" }
            _handleState.value = newState
        }
    }

    // ... analyzeHandleState() (existing logic verbatim) ...
    // ... enable/disable/reset methods ...
}
```

### Pattern 2: Delegation from KableBleRepository
**What:** KableBleRepository instantiates HandleStateDetector and delegates all handle detection calls to it. The BleRepository interface is unchanged.
**When to use:** When extracting logic that the repository previously owned.
**Example:**
```kotlin
// In KableBleRepository, near line 147:
private val handleDetector = HandleStateDetector()

// Interface delegation:
override val handleDetection: StateFlow<HandleDetection> = handleDetector.handleDetection
override val handleState: StateFlow<HandleState> = handleDetector.handleState

override fun enableHandleDetection(enabled: Boolean) {
    if (enabled) {
        handleDetector.enable(autoStart = true)
        val p = peripheral
        if (p != null) {
            startMonitorPolling(p, forAutoStart = true)
        }
    } else {
        handleDetector.disable()
    }
}

override fun resetHandleState() = handleDetector.reset()

override fun enableJustLiftWaitingMode() = handleDetector.enableJustLiftWaiting()

// In parseMonitorData, replace inline handle logic with:
handleDetector.processMetric(metric)
```

### Anti-Patterns to Avoid
- **Passing KableBleRepository to HandleStateDetector:** Creates circular dependency. The detector is a pure state machine that should only receive WorkoutMetric objects.
- **Moving HandleState enum to data/ble package:** The enum is part of the BleRepository interface contract. Moving it would break imports in presentation layer (ActiveSessionEngine, WorkoutCoordinator, JustLiftScreen). Leave it in `data/repository/BleRepository.kt`.
- **Removing HandleDetection from detector scope:** The simple left/right detection and the 4-state machine are always evaluated together when `isEnabled=true`. Keep them co-located.
- **Using coroutine delays for hysteresis:** The state machine runs synchronously on each metric callback. Hysteresis is tracked via wall-clock timestamps, not async delays. This is critical for correctness.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Time for hysteresis | `System.currentTimeMillis()` | Injectable `timeProvider: () -> Long` | KMP-compatible, testable |
| State flow exposure | Mutable public property | `MutableStateFlow` + `asStateFlow()` | Project pattern |
| Threshold constants | Hardcoded magic numbers | `BleConstants.Thresholds.*` | Already centralized in Phase 5 |
| Position validation | Custom validation | Existing validateSample logic stays in KableBleRepository | Separation of concerns |

**Key insight:** HandleStateDetector is a pure state machine. It processes metrics and tracks time. All BLE I/O, polling, and data parsing remain in KableBleRepository.

## Common Pitfalls

### Pitfall 1: Breaking the Hysteresis Timing in Tests
**What goes wrong:** Tests use real time and become flaky due to timing sensitivity.
**Why it happens:** The 200ms dwell timer (`STATE_TRANSITION_DWELL_MS`) and 3s WaitingForRest timeout require precise time control.
**How to avoid:** Use injectable `timeProvider` and advance time deterministically in tests. Create a `FakeTimeProvider` that returns controlled values.
**Warning signs:** Tests pass locally but fail in CI, or tests require `delay()` calls.

### Pitfall 2: Forgetting to Move ALL State Variables
**What goes wrong:** Some handle state variables remain in KableBleRepository, causing split state.
**Why it happens:** The state is scattered across 15+ variables (lines 166-221).
**How to avoid:** Complete inventory of ALL handle-related state:
- `handleDetectionEnabled` -> `HandleStateDetector.isEnabled`
- `isAutoStartMode` -> `HandleStateDetector.isAutoStartMode`
- `_handleState` -> `HandleStateDetector._handleState`
- `_handleDetection` -> `HandleStateDetector._handleDetection`
- `handleStateLogCounter` -> `HandleStateDetector.logCounter`
- `pendingGrabbedStartTime` -> `HandleStateDetector.pendingGrabbedStartTime`
- `pendingReleasedStartTime` -> `HandleStateDetector.pendingReleasedStartTime`
- `activeHandlesMask` -> `HandleStateDetector.activeHandlesMask`
- `waitingForRestStartTime` -> `HandleStateDetector.waitingForRestStartTime`
- `restBaselinePosA` -> `HandleStateDetector.restBaselinePosA`
- `restBaselinePosB` -> `HandleStateDetector.restBaselinePosB`
- `minPositionSeen` -> `HandleStateDetector.minPositionSeen`
- `maxPositionSeen` -> `HandleStateDetector.maxPositionSeen`
- `forceAboveGrabThresholdStart` -> `HandleStateDetector.forceAboveGrabThresholdStart`
- `forceBelowReleaseThresholdStart` -> `HandleStateDetector.forceBelowReleaseThresholdStart`
**Warning signs:** Handle detection works partially, or state resets don't fully clear.

### Pitfall 3: Losing Issue #176 Baseline Tracking
**What goes wrong:** Overhead pulley setups can't detect grabs because baseline-relative detection is lost.
**Why it happens:** The baseline logic (lines 2082-2093, 2110-2136, 2192-2200) is interleaved with the core state machine.
**How to avoid:** Ensure ALL baseline logic moves together:
1. Baseline capture in WaitingForRest -> Released transition
2. Baseline-relative grab detection in Released/Moving state
3. Baseline-relative release detection in Grabbed state
4. Baseline reset in all reset/enable/disable methods
5. Virtual baseline (0.0) for timeout-with-grabbed-handles case
**Warning signs:** Works for normal setups but fails for overhead pulley (cables never reach absolute rest).

### Pitfall 4: startMonitorPolling Still Resetting Handle State
**What goes wrong:** KableBleRepository's `startMonitorPolling()` resets handle state variables that now live in HandleStateDetector.
**Why it happens:** The `startMonitorPolling()` method (lines 1193-1210) directly sets `_handleState`, `handleDetectionEnabled`, `isAutoStartMode`, baseline values, etc.
**How to avoid:** Replace direct state manipulation in `startMonitorPolling()` with calls to HandleStateDetector methods (e.g., `handleDetector.enable(autoStart = true)`). The detector should expose `enable(autoStart: Boolean)` and `disable()` methods that encapsulate all state initialization.
**Warning signs:** Duplicate state initialization code between KableBleRepository and HandleStateDetector.

### Pitfall 5: Format Helper Not Moved
**What goes wrong:** `analyzeHandleState()` uses `Double.format(decimals)` extension function for logging (line 2100, 2169, 2220). This helper is defined at line 2235 as a private function in KableBleRepository.
**Why it happens:** Easy to miss private utility functions used by the extracted code.
**How to avoid:** Move the `Double.format(decimals)` helper into HandleStateDetector (or make it internal/package-level in the ble package). Check for all references within the extracted code block.
**Warning signs:** Compile error about unresolved `format` extension.

### Pitfall 6: SimulatorBleRepository Not Updated
**What goes wrong:** SimulatorBleRepository still has its own inline handle state logic.
**Why it happens:** Simulator has a simplified version of handle detection (lines 268-331).
**How to avoid:** SimulatorBleRepository does NOT use HandleStateDetector -- it has its own simplified simulation. No changes needed there. The extraction only affects KableBleRepository.
**Warning signs:** N/A -- just don't change the simulator.

## Code Examples

### Complete State Variable Inventory (from KableBleRepository)
```kotlin
// Source: KableBleRepository.kt lines 92-93 (flows)
private val _handleDetection = MutableStateFlow(HandleDetection())
override val handleDetection: StateFlow<HandleDetection> = _handleDetection.asStateFlow()

// Source: KableBleRepository.kt lines 112-114 (flows)
private val _handleState = MutableStateFlow(HandleState.WaitingForRest)
override val handleState: StateFlow<HandleState> = _handleState.asStateFlow()

// Source: KableBleRepository.kt lines 166-168 (flags)
private var handleDetectionEnabled = false
private var isAutoStartMode = false

// Source: KableBleRepository.kt lines 196-221 (tracking state)
private var minPositionSeen = Double.MAX_VALUE
private var maxPositionSeen = Double.MIN_VALUE
private var forceAboveGrabThresholdStart: Long? = null
private var forceBelowReleaseThresholdStart: Long? = null
private var pendingGrabbedStartTime: Long? = null
private var pendingReleasedStartTime: Long? = null
private var activeHandlesMask: Int = 0
private var waitingForRestStartTime: Long? = null
private var restBaselinePosA: Double? = null
private var restBaselinePosB: Double? = null

// Source: KableBleRepository.kt line 2053 (logging)
private var handleStateLogCounter = 0L
```

### analyzeHandleState Key Logic (WaitingForRest with Timeout)
```kotlin
// Source: KableBleRepository.kt lines 2104-2144
// This is the most complex state -- has timeout escape + baseline capture
HandleState.WaitingForRest -> {
    if (posA < BleConstants.Thresholds.HANDLE_REST_THRESHOLD &&
        posB < BleConstants.Thresholds.HANDLE_REST_THRESHOLD) {
        waitingForRestStartTime = null
        // Issue #176: Capture baseline position
        restBaselinePosA = posA
        restBaselinePosB = posB
        HandleState.Released
    } else {
        val currentTime = timeProvider()
        if (waitingForRestStartTime == null) {
            waitingForRestStartTime = currentTime
            HandleState.WaitingForRest
        } else if (currentTime - waitingForRestStartTime!! > BleConstants.Timing.WAITING_FOR_REST_TIMEOUT_MS) {
            // Timeout: Virtual baseline if already grabbed, real baseline otherwise
            val alreadyGrabbed = posA > BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD ||
                                  posB > BleConstants.Thresholds.HANDLE_GRABBED_THRESHOLD
            if (alreadyGrabbed) {
                restBaselinePosA = 0.0; restBaselinePosB = 0.0  // Virtual baseline
            } else {
                restBaselinePosA = posA; restBaselinePosB = posB
            }
            waitingForRestStartTime = null
            HandleState.Released  // Force arm after timeout
        } else {
            HandleState.WaitingForRest
        }
    }
}
```

### Test Pattern: Deterministic Time Control
```kotlin
// Source: Derived from codebase testing patterns (ProtocolParserTest.kt, BleOperationQueueTest.kt)
class HandleStateDetectorTest {
    private var fakeTime = 0L
    private val detector = HandleStateDetector(timeProvider = { fakeTime })

    private fun metric(
        posA: Float = 0f, posB: Float = 0f,
        velA: Double = 0.0, velB: Double = 0.0
    ) = WorkoutMetric(
        timestamp = fakeTime,
        loadA = 0f, loadB = 0f,
        positionA = posA, positionB = posB,
        velocityA = velA, velocityB = velB
    )

    @Test
    fun `WaitingForRest transitions to Released when both handles at rest`() {
        detector.enable(autoStart = true)
        assertEquals(HandleState.WaitingForRest, detector.handleState.value)

        // Both handles below rest threshold (5mm)
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))
        assertEquals(HandleState.Released, detector.handleState.value)
    }

    @Test
    fun `Grabbed requires 200ms hysteresis dwell`() {
        detector.enable(autoStart = true)
        detector.processMetric(metric(posA = 2.0f, posB = 1.5f))  // -> Released

        // First grab signal - starts dwell timer
        fakeTime = 1000L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)  // Still dwelling

        // 199ms later - not yet
        fakeTime = 1199L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Released, detector.handleState.value)

        // 200ms - dwell complete
        fakeTime = 1200L
        detector.processMetric(metric(posA = 20.0f, posB = 20.0f, velA = 100.0, velB = 100.0))
        assertEquals(HandleState.Grabbed, detector.handleState.value)
    }
}
```

### Control Methods to Extract
```kotlin
// Source: KableBleRepository.kt lines 1487-1567
// These methods become HandleStateDetector methods:

// enableHandleDetection(enabled) -> enable(autoStart) / disable()
// resetHandleState()             -> reset()
// enableJustLiftWaitingMode()    -> enableJustLiftWaiting()

// KableBleRepository keeps the polling control but delegates state management:
override fun enableHandleDetection(enabled: Boolean) {
    log.i { "Handle detection ${if (enabled) "ENABLED" else "DISABLED"}" }
    if (enabled) {
        handleDetector.enable(autoStart = true)
        val p = peripheral
        if (p != null) {
            startMonitorPolling(p, forAutoStart = true)
        }
    } else {
        handleDetector.disable()
    }
}
```

## Differences: Parent Repo vs. Current Implementation

| Feature | Parent Repo (VitruvianBleManager) | Current KMP (KableBleRepository) | Impact on Extraction |
|---------|----------------------------------|----------------------------------|---------------------|
| State machine | 75 lines, simple transitions | 170 lines, with hysteresis + baseline + timeout | All current logic must be preserved |
| Hysteresis (Task 14) | None | 200ms dwell timers for grab AND release | Requires injectable time |
| Baseline tracking (Issue #176) | None | Rest baseline + relative grab/release detection | Must move all baseline state |
| WaitingForRest timeout | None | 3s timeout with virtual baseline fallback | Requires injectable time |
| Velocity threshold (Issue #96) | Single `VELOCITY_THRESHOLD = 100` | Dual: `VELOCITY_THRESHOLD = 50`, `AUTO_START_VELOCITY_THRESHOLD = 20` | `isAutoStartMode` flag in detector |
| Active handle mask | None (checks both handles always) | Tracks which handle(s) are grabbed | `activeHandlesMask` in detector |
| HandleDetection (simple) | Not present | Left/right boolean detection alongside 4-state | Co-locate with state machine |
| Format helper | Not needed (uses Timber) | `Double.format(decimals)` extension | Move into detector |

## Test Strategy

### Unit Tests: HandleStateDetector
```kotlin
// Target: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetectorTest.kt

class HandleStateDetectorTest {
    // === State Transition Tests ===
    @Test fun `initial state is WaitingForRest`()
    @Test fun `WaitingForRest to Released when both handles below rest threshold`()
    @Test fun `WaitingForRest stays when handles above rest threshold`()
    @Test fun `WaitingForRest timeout arms after 3 seconds`()
    @Test fun `WaitingForRest timeout with grabbed handles uses virtual baseline`()
    @Test fun `WaitingForRest timeout with elevated rest uses real baseline`()
    @Test fun `Released to Grabbed requires position AND velocity AND 200ms dwell`()
    @Test fun `Released to Moving when position extended but no velocity`()
    @Test fun `Released stays Released when handles at rest`()
    @Test fun `Moving to Grabbed when velocity threshold met with 200ms dwell`()
    @Test fun `Moving to Released when handles return to rest`()
    @Test fun `Grabbed to Released requires 200ms dwell`()
    @Test fun `Grabbed stays Grabbed when handles still extended`()

    // === Hysteresis Tests ===
    @Test fun `grab dwell timer resets when threshold not met`()
    @Test fun `release dwell timer resets when handles move away from rest`()
    @Test fun `dwell timer requires sustained threshold for exactly STATE_TRANSITION_DWELL_MS`()

    // === Single Handle Tests ===
    @Test fun `single handle A grab detected correctly`()
    @Test fun `single handle B grab detected correctly`()
    @Test fun `single handle release only checks active handle`()
    @Test fun `both handles grabbed requires both released`()

    // === Baseline Tracking Tests (Issue #176) ===
    @Test fun `baseline captured on WaitingForRest to Released transition`()
    @Test fun `grab detection uses baseline-relative position`()
    @Test fun `release detection uses baseline-relative position`()
    @Test fun `baseline reset on enable`()
    @Test fun `baseline reset on disable`()
    @Test fun `baseline reset on reset`()
    @Test fun `baseline reset on enableJustLiftWaiting`()

    // === Auto-Start Mode Tests (Issue #96) ===
    @Test fun `auto-start mode uses lower velocity threshold`()
    @Test fun `normal mode uses standard velocity threshold`()

    // === Simple Detection Tests ===
    @Test fun `handleDetection updates left right booleans`()
    @Test fun `handleDetection not updated when detection disabled`()

    // === Control Method Tests ===
    @Test fun `enable sets isEnabled and autoStartMode`()
    @Test fun `disable clears isEnabled and resets baseline`()
    @Test fun `reset returns to WaitingForRest and clears all timers`()
    @Test fun `enableJustLiftWaiting resets state and enables autoStart`()

    // === Edge Cases ===
    @Test fun `processMetric is no-op when disabled`()
    @Test fun `position diagnostics track min and max`()
}
```

### What NOT to Test
- BLE polling (stays in KableBleRepository)
- Data parsing (stays in KableBleRepository/ProtocolParser)
- Peripheral connection state (irrelevant to state machine)

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **HAND-01**: HandleStateDetector implements 4-state machine | `HandleStateDetector.kt` with `analyzeHandleState()` implementing WaitingForRest -> Released -> Grabbed -> Moving |
| **HAND-02**: Overhead pulley baseline tracking (Issue #176) | Baseline capture, relative grab/release detection, virtual baseline on timeout -- all moved verbatim |
| **HAND-03**: Just Lift autostart detects grab correctly | `isAutoStartMode` flag with lower velocity threshold, hysteresis dwell, proper state sequencing |

## Success Criteria Mapping

| Criterion | Verification |
|-----------|-------------|
| 1. HandleStateDetector implements 4-state machine | `HandleStateDetector.kt` exists with `analyzeHandleState()` containing all 4 states |
| 2. Overhead pulley baseline tracking works | Unit tests verify baseline capture, relative detection, and virtual baseline |
| 3. Just Lift autostart mode detects grab correctly | Unit tests verify lower velocity threshold and full grab sequence |
| 4. First rep registers after workout start | Baseline initialized on WaitingForRest -> Released transition; tested |
| 5. Unit tests cover all state transitions | HandleStateDetectorTest.kt with 25+ tests covering all states and edge cases |

## Open Questions

1. **Should HandleStateDetector own the StateFlow or return state from processMetric?**
   - What we know: BleRepository interface exposes `handleState: StateFlow<HandleState>`, which KableBleRepository currently delegates.
   - Recommendation: HandleStateDetector should own the `MutableStateFlow` internally and expose it as `StateFlow`. This matches the DiscoMode pattern where the module owns its state flow. KableBleRepository assigns `override val handleState = handleDetector.handleState` directly.

2. **Should the `Double.format()` helper move to the ble package or stay in HandleStateDetector?**
   - What we know: Only used for diagnostic logging in `analyzeHandleState()`.
   - Recommendation: Make it a `private` extension inside HandleStateDetector. If future modules need it, promote to a package-level utility in BleExtensions.kt. Don't over-engineer.

3. **Should the `forceAboveGrabThresholdStart` / `forceBelowReleaseThresholdStart` fields be moved?**
   - What we know: These are declared at KableBleRepository lines 200-203 and reset in enable/reset/enableJustLiftWaiting methods, but they are NOT used in `analyzeHandleState()`. They appear to be legacy holdovers from the parent repo's force-based detection that was replaced with position+velocity detection.
   - Recommendation: Move them to HandleStateDetector for completeness (they are reset alongside other handle state), but add a comment noting they appear unused by current logic. They may be vestigial.

## Extraction Checklist

- [ ] `HandleStateDetector.kt` created in `data/ble/`
- [ ] All 15 state variables moved from KableBleRepository to HandleStateDetector
- [ ] `analyzeHandleState()` moved verbatim (with `_handleState` -> internal state)
- [ ] `Double.format()` helper moved
- [ ] Simple HandleDetection (left/right) logic moved
- [ ] `enableHandleDetection()` delegates to HandleStateDetector.enable()/disable()
- [ ] `resetHandleState()` delegates to HandleStateDetector.reset()
- [ ] `enableJustLiftWaitingMode()` delegates to HandleStateDetector.enableJustLiftWaiting()
- [ ] `startMonitorPolling()` handle state initialization delegates to HandleStateDetector
- [ ] Position range reset in startMonitorPolling delegates to HandleStateDetector
- [ ] KableBleRepository `handleDetection` and `handleState` flows assigned from HandleStateDetector
- [ ] BleRepository interface UNCHANGED (HandleState enum stays in BleRepository.kt)
- [ ] SimulatorBleRepository UNCHANGED (has its own simplified logic)
- [ ] Unit tests for all state transitions
- [ ] Unit tests for hysteresis timing
- [ ] Unit tests for baseline tracking
- [ ] Unit tests for single-handle detection
- [ ] All existing tests pass
- [ ] `./gradlew :shared:compileKotlinAndroid` succeeds

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` lines 92-93, 112-114, 166-221, 1487-1567, 1938-1954, 2044-2239 - Current handle state implementation
- `BleRepository.kt` lines 21-53, 145-203 - HandleState enum, HandleDetection class, interface methods
- `BleConstants.kt` lines 137-169 - Threshold and timing constants
- `VitruvianBleManager.kt` (parent repo) lines 136-137, 174-186, 1364-1444, 1767-1772 - Original handle state machine
- `DiscoMode.kt` - Phase 8 extraction pattern (callback-based, owns StateFlow)
- `BleOperationQueue.kt` - Phase 7 extraction pattern (class not object)

### Secondary (MEDIUM confidence)
- `SimulatorBleRepository.kt` lines 64-65, 81-85, 268-331 - Simulator's simplified handle logic (confirms it should NOT use HandleStateDetector)
- `ActiveSessionEngine.kt` lines 132-172, 2134-2135 - Downstream consumer of HandleState (confirms interface contract must be preserved)
- `WorkoutCoordinator.kt` line 217 - Downstream consumer of HandleState

### Tertiary (LOW confidence)
- None (all patterns verified against codebase)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing project dependencies only
- Architecture: HIGH - Follows established Phase 7/8 extraction patterns
- Pitfalls: HIGH - All identified from actual codebase analysis, specific line references
- Test strategy: HIGH - Injectable time pattern well-established, state machine is pure logic

**Research date:** 2026-02-15
**Valid until:** Indefinitely (extraction pattern is stable, state machine logic is frozen)

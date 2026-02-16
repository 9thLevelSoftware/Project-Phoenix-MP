# Phase 5: BleProtocolConstants - Research

**Researched:** 2026-02-15
**Domain:** Kotlin Multiplatform constant extraction, BLE UUID management
**Confidence:** HIGH

## Summary

Phase 5 is a **zero-risk mechanical refactor** that consolidates BLE protocol constants currently scattered across two locations: `BleConstants.kt` (util package) and the `KableBleRepository.kt` companion object. The goal is unified access via `BleProtocolConstants.XXX` while preserving exact compile-time constants.

The codebase already has a well-structured `BleConstants.kt` in the util package with string-based UUIDs, commands, data protocol constants, and timing values. However, `KableBleRepository.kt` has **duplicate UUID definitions** as `Uuid` objects plus ~20 additional timing/threshold constants not in `BleConstants.kt`. This phase eliminates duplication and centralizes all BLE-related constants.

**Primary recommendation:** Extend the existing `BleConstants.kt` to include all missing constants from `KableBleRepository`, rename it to `BleProtocolConstants.kt`, then update imports in KableBleRepository to reference the centralized constants.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlin.uuid.Uuid | Kotlin 2.0.21+ | UUID type for Kable API | KMP stdlib, ExperimentalUuidApi |
| com.juul.kable | 1.1.0+ | `characteristicOf()` helper | Kable provides characteristic reference builder |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| None | - | Pure constants object | No dependencies needed |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Uuid.parse()` | String constants only | Strings would require parsing at runtime in every usage - `Uuid` objects are compile-time |
| Inline `characteristicOf()` | Keep in repository | Would miss the "pre-built characteristic refs" consolidation goal |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── util/
│   └── BleProtocolConstants.kt   # NEW: All BLE constants (renamed from BleConstants.kt)
├── data/
│   └── repository/
│       └── KableBleRepository.kt  # MODIFIED: Remove companion object constants, import from BleProtocolConstants
```

### Pattern 1: Centralized Constants Object
**What:** Single object holding all BLE protocol constants
**When to use:** When constants are shared across multiple modules/files
**Example:**
```kotlin
// Source: .planning/plans/kable-decomposition-plan.md
package com.devil.phoenixproject.util

import com.juul.kable.characteristicOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object BleProtocolConstants {
    // Service UUIDs (Uuid objects for Kable API)
    val NUS_SERVICE_UUID = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX_UUID = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val MONITOR_UUID = Uuid.parse("90e991a6-c548-44ed-969b-eb541014eae3")
    // ... all UUIDs

    // Timing constants
    const val CONNECTION_TIMEOUT_MS = 15_000L
    const val HEARTBEAT_INTERVAL_MS = 2_000L
    // ... all timing

    // Handle detection thresholds
    const val HANDLE_GRABBED_THRESHOLD = 8.0
    const val HANDLE_REST_THRESHOLD = 5.0
    // ... all thresholds

    // Pre-built characteristic references
    val txCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_TX_UUID
    )
    // ... all characteristics
}
```

### Pattern 2: Nested Object Organization
**What:** Use nested objects for logical grouping (Commands, DataProtocol)
**When to use:** When you have 20+ constants that benefit from categorization
**Example:**
```kotlin
object BleProtocolConstants {
    // Root level: UUIDs

    object Commands {
        const val STOP_COMMAND: Byte = 0x50
        const val RESET_COMMAND: Byte = 0x0A
        // ...
    }

    object DataProtocol {
        const val POSITION_SCALE = 10.0
        const val CABLE_DATA_SIZE = 6
        // ...
    }

    object Timing {
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val HEARTBEAT_INTERVAL_MS = 2_000L
        // ...
    }

    object Thresholds {
        const val HANDLE_GRABBED_THRESHOLD = 8.0
        const val HANDLE_REST_THRESHOLD = 5.0
        // ...
    }
}
```

### Anti-Patterns to Avoid
- **Duplicate UUID definitions:** Current state has UUIDs in both BleConstants.kt and KableBleRepository companion object. This creates drift risk.
- **Hardcoded UUIDs inline:** KableBleRepository.kt lines 451, 875, 877, 882, etc. have hardcoded UUID strings in logging/filtering logic. These should reference constants.
- **Mixed String/Uuid types:** BleConstants.kt has `_STRING` suffixes for string constants, but also needs `Uuid` objects for Kable API. Keep both but with clear naming.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| UUID parsing | Manual string parsing | `Uuid.parse()` | Stdlib handles format validation |
| Characteristic refs | Inline `characteristicOf()` calls | Pre-built constants | Reduces duplication, compile-time verification |
| String <-> Uuid conversion | Custom converter | Keep both forms | Different consumers need different types |

**Key insight:** The Kable API uses `Uuid` objects but logging/filtering often uses strings. Keep both forms rather than converting at runtime.

## Common Pitfalls

### Pitfall 1: Breaking Existing Test Imports
**What goes wrong:** Tests reference `BleConstants.XXX` and renaming to `BleProtocolConstants` breaks them
**Why it happens:** Find/replace misses test files or imports are indirect
**How to avoid:** Keep `BleConstants` as a typealias or deprecated forwarder during transition
**Warning signs:** Test compilation fails after rename

### Pitfall 2: ExperimentalUuidApi OptIn Propagation
**What goes wrong:** After moving `Uuid.parse()` calls to BleProtocolConstants, every consumer needs `@OptIn(ExperimentalUuidApi::class)`
**Why it happens:** Kotlin's opt-in annotation requirement propagates through API
**How to avoid:** Apply `@OptIn` at the module level in build.gradle.kts or at file level in BleProtocolConstants.kt
**Warning signs:** "This declaration needs opt-in" compiler warnings cascade

### Pitfall 3: Characteristic Reference Initialization Order
**What goes wrong:** `characteristicOf()` references UUID vals that haven't initialized yet
**Why it happens:** Kotlin object initialization order
**How to avoid:** Define all UUID vals before characteristic vals, or use `by lazy {}`
**Warning signs:** NPE at app startup when loading BleProtocolConstants

### Pitfall 4: Missing Constants from Companion Object
**What goes wrong:** Some constants in KableBleRepository companion object aren't migrated, causing undefined reference errors
**Why it happens:** Manual extraction misses constants used only in private methods
**How to avoid:** Grep for all `private const val` and `private val` in companion object, verify all are moved
**Warning signs:** "Unresolved reference" compile errors in KableBleRepository

## Code Examples

Verified patterns from codebase analysis:

### Current State: Existing BleConstants.kt (util package)
```kotlin
// Source: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt
object BleConstants {
    // Service UUIDs (strings)
    const val NUS_SERVICE_UUID_STRING = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    // Commands
    object Commands {
        const val STOP_COMMAND: Byte = 0x50
        const val REGULAR_COMMAND: Byte = 0x4F
        // ...
    }

    // Data Protocol
    object DataProtocol {
        const val POSITION_SCALE = 10.0
        // ...
    }

    // Timeouts
    const val CONNECTION_TIMEOUT_MS = 15000L
    const val SCAN_TIMEOUT_MS = 30000L
}
```

### Current State: KableBleRepository Companion Object (to be migrated)
```kotlin
// Source: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt
companion object {
    // UUIDs as Uuid objects (DUPLICATE - needs migration)
    private val NUS_SERVICE_UUID = Uuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_TX_UUID = Uuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    // ... 10 more UUIDs

    // Timing (NOT in BleConstants.kt - needs migration)
    private const val CONNECTION_RETRY_DELAY_MS = 100L
    private const val HEARTBEAT_INTERVAL_MS = 2000L
    private const val DIAGNOSTIC_POLL_INTERVAL_MS = 500L
    private const val HEURISTIC_POLL_INTERVAL_MS = 250L
    // ... 10+ more timing constants

    // Thresholds (NOT in BleConstants.kt - needs migration)
    private const val HANDLE_GRABBED_THRESHOLD = 8.0
    private const val HANDLE_REST_THRESHOLD = 5.0
    private const val VELOCITY_THRESHOLD = 50.0
    // ... 10+ more thresholds
}
```

### Target State: Unified BleProtocolConstants.kt
```kotlin
// Target: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleProtocolConstants.kt
package com.devil.phoenixproject.util

import com.juul.kable.characteristicOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * BLE Protocol Constants - All UUIDs, timing, and thresholds for Vitruvian device communication.
 *
 * This object consolidates all BLE-related constants previously scattered across:
 * - BleConstants.kt (util package)
 * - KableBleRepository.kt companion object
 *
 * Based on Phoenix Backend (deobfuscated official app).
 */
@Suppress("unused")  // Protocol reference constants - many are kept for documentation
@OptIn(ExperimentalUuidApi::class)
object BleProtocolConstants {
    // ========== Service UUIDs ==========
    const val GATT_SERVICE_UUID_STRING = "00001801-0000-1000-8000-00805f9b34fb"
    const val NUS_SERVICE_UUID_STRING = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    val NUS_SERVICE_UUID: Uuid = Uuid.parse(NUS_SERVICE_UUID_STRING)
    val GATT_SERVICE_UUID: Uuid = Uuid.parse(GATT_SERVICE_UUID_STRING)

    // ========== Characteristic UUIDs ==========
    // ... (continue pattern for all UUIDs)

    // ========== Commands ==========
    object Commands { /* existing */ }

    // ========== Data Protocol ==========
    object DataProtocol { /* existing */ }

    // ========== Timing ==========
    object Timing {
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val CONNECTION_RETRY_DELAY_MS = 100L
        const val HEARTBEAT_INTERVAL_MS = 2_000L
        const val DIAGNOSTIC_POLL_INTERVAL_MS = 500L
        const val HEURISTIC_POLL_INTERVAL_MS = 250L
        // ... all timing from KableBleRepository
    }

    // ========== Handle Detection Thresholds ==========
    object HandleThresholds {
        const val HANDLE_GRABBED_THRESHOLD = 8.0
        const val HANDLE_REST_THRESHOLD = 5.0
        const val VELOCITY_THRESHOLD = 50.0
        // ... all thresholds from KableBleRepository
    }

    // ========== Pre-built Characteristic References ==========
    val txCharacteristic = characteristicOf(
        service = NUS_SERVICE_UUID,
        characteristic = NUS_TX_UUID
    )
    // ... all characteristic references
}

// Backward compatibility alias (deprecate in next minor version)
@Deprecated("Use BleProtocolConstants", replaceWith = ReplaceWith("BleProtocolConstants"))
typealias BleConstants = BleProtocolConstants
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `java.util.UUID` | `kotlin.uuid.Uuid` | Kotlin 2.0+ | KMP compatible, no expect/actual needed |
| Scattered constants | Centralized object | This phase | Single source of truth |
| String-only UUIDs | String + Uuid pairs | Kable adoption | API flexibility |

**Deprecated/outdated:**
- `java.util.UUID`: Android-only, not available in KMP commonMain. Use `kotlin.uuid.Uuid`.
- `UUID.fromString()`: Parent repo (Android-only) uses this. KMP uses `Uuid.parse()`.

## Open Questions

1. **Nested object naming**
   - What we know: Current BleConstants.kt uses `Commands` and `DataProtocol` nested objects
   - What's unclear: Should timing and thresholds also be nested (`Timing.CONNECTION_TIMEOUT_MS`) or flat (`CONNECTION_TIMEOUT_MS`)?
   - Recommendation: Use nested objects for logical grouping (4+ constants per category)

2. **Backward compatibility period**
   - What we know: Tests reference `BleConstants.XXX`
   - What's unclear: How long to keep deprecated typealias?
   - Recommendation: Keep typealias through v0.4.2, remove in v0.5.0

3. **characteristicOf() inclusion**
   - What we know: KableBleRepository builds characteristics from UUIDs
   - What's unclear: Should pre-built characteristics live in constants or stay in repository?
   - Recommendation: Include in constants - they're compile-time values, not runtime state

## Inventory: Constants to Migrate

### From KableBleRepository Companion Object (lines 62-147)

**UUIDs (as Uuid objects):**
- `NUS_SERVICE_UUID`
- `NUS_TX_UUID`
- `NUS_RX_UUID`
- `MONITOR_UUID`
- `REPS_UUID`
- `DIAGNOSTIC_UUID`
- `HEURISTIC_UUID`
- `VERSION_UUID`
- `MODE_UUID`
- `UPDATE_STATE_UUID`
- `BLE_UPDATE_REQUEST_UUID`
- `UNKNOWN_AUTH_UUID`
- `DIS_SERVICE_UUID`
- `FIRMWARE_REVISION_UUID`

**Timing Constants:**
- `CONNECTION_RETRY_COUNT = 3`
- `CONNECTION_RETRY_DELAY_MS = 100L`
- `CONNECTION_TIMEOUT_MS = 15_000L` (duplicate of BleConstants)
- `DESIRED_MTU = 247`
- `HEARTBEAT_INTERVAL_MS = 2000L`
- `HEARTBEAT_READ_TIMEOUT_MS = 1500L`
- `DELOAD_EVENT_DEBOUNCE_MS = 2000L`
- `DIAGNOSTIC_POLL_INTERVAL_MS = 500L`
- `HEURISTIC_POLL_INTERVAL_MS = 250L`
- `DIAGNOSTIC_LOG_EVERY = 20L`
- `STATE_TRANSITION_DWELL_MS = 200L`
- `WAITING_FOR_REST_TIMEOUT_MS = 3000L`

**Handle Detection Thresholds:**
- `HANDLE_GRABBED_THRESHOLD = 8.0`
- `HANDLE_REST_THRESHOLD = 5.0`
- `VELOCITY_THRESHOLD = 50.0`
- `AUTO_START_VELOCITY_THRESHOLD = 20.0`
- `VELOCITY_SMOOTHING_ALPHA = 0.3`
- `POSITION_SPIKE_THRESHOLD = 50000`
- `MIN_POSITION = -1000`
- `MAX_POSITION = 1000`
- `POSITION_JUMP_THRESHOLD = 20.0f`
- `MAX_WEIGHT_KG = 220.0f`
- `MAX_CONSECUTIVE_TIMEOUTS = 5`
- `GRAB_DELTA_THRESHOLD = 10.0`
- `RELEASE_DELTA_THRESHOLD = 5.0`

**Heartbeat Command:**
- `HEARTBEAT_NO_OP = byteArrayOf(0x00, 0x00, 0x00, 0x00)`

### Already in BleConstants.kt (util package)
- All string UUID constants (`*_UUID_STRING`)
- `Commands` object
- `DataProtocol` object
- `DEVICE_NAME_PREFIX`, `DEVICE_NAME_PATTERN`
- `NOTIFY_CHAR_UUID_STRINGS`
- `CONNECTION_TIMEOUT_MS`, `GATT_OPERATION_TIMEOUT_MS`, `SCAN_TIMEOUT_MS`
- `BLE_QUEUE_DRAIN_DELAY_MS`

### Hardcoded UUIDs to Replace with Constants
From KableBleRepository.kt:
- Line 451: `"6e400001-b5a3-f393-e0a9-e50e24dcca9e"` in isVitruvianDevice check
- Lines 877, 882, 890, 898, 903: UUID strings in service/characteristic discovery logging

## Sources

### Primary (HIGH confidence)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt` - Current constants structure
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt` - Companion object to migrate
- `.planning/plans/kable-decomposition-plan.md` - Phase 1 extraction plan
- `.planning/research/REFACTORING-ARCHITECTURE.md` - Target architecture

### Secondary (MEDIUM confidence)
- `.planning/research/BLE-REFACTORING-PITFALLS.md` - Risk documentation
- `.tmp_parent_repo/app/src/main/java/com/example/vitruvianredux/util/BleConstants.kt` - Parent repo reference

### Tertiary (LOW confidence)
- None (this is purely mechanical refactoring based on existing code)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing Kotlin stdlib + Kable patterns already in codebase
- Architecture: HIGH - Following established pattern from existing BleConstants.kt
- Pitfalls: HIGH - Based on actual codebase analysis, not speculation

**Research date:** 2026-02-15
**Valid until:** Indefinitely (compile-time constants don't change rapidly)

## Implementation Checklist

1. [ ] Create backup of existing files
2. [ ] Rename `BleConstants.kt` to `BleProtocolConstants.kt`
3. [ ] Add all missing UUID vals (Uuid objects from KableBleRepository)
4. [ ] Add all missing timing constants (from KableBleRepository)
5. [ ] Add all missing threshold constants (from KableBleRepository)
6. [ ] Add all missing constants (HEARTBEAT_NO_OP, etc.)
7. [ ] Add `characteristicOf()` references
8. [ ] Add deprecated typealias for backward compatibility
9. [ ] Update imports in KableBleRepository.kt
10. [ ] Remove duplicate constants from KableBleRepository companion object
11. [ ] Replace hardcoded UUID strings with constant references
12. [ ] Update imports in test files
13. [ ] Verify build compiles on Android
14. [ ] Verify build compiles on iOS (check XCFramework)
15. [ ] Run existing BleConstantsTest to verify no regressions

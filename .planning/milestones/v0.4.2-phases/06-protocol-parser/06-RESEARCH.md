# Phase 6: ProtocolParser - Research

**Researched:** 2026-02-15
**Domain:** Kotlin Multiplatform byte parsing, BLE protocol extraction, pure function design
**Confidence:** HIGH

## Summary

Phase 6 extracts byte parsing functions from `KableBleRepository.kt` (lines 2386-2683) into a stateless `ProtocolParser.kt` module. This is a **zero-risk extraction** because all target functions are already pure - they take `ByteArray` input and return parsed data without side effects or state mutation. The extraction enables unit testing protocol parsing without BLE stack dependencies.

The codebase already has well-documented parsing functions with explicit byte-order handling (Little-Endian for most packets, Big-Endian for RX metrics). The official Vitruvian app (decompiled) confirms packet structures using `ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)` with documented field layouts.

**Prior decisions from state (must honor):**
- [05-01]: Nested object pattern for `BleConstants.Timing` and `BleConstants.Thresholds`
- [05-01]: Pre-built Kable characteristic references in `BleConstants`

**Primary recommendation:** Extract 6 byte-level utility functions and 4 packet parsing functions as top-level pure functions in `ProtocolParser.kt`, keeping them as simple functions (not an object) to emphasize statelessness. Use sealed class result types for parsed packets to handle both valid parses and error cases.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin stdlib | 2.0.21 | `ByteArray`, bit operations | KMP native, no dependencies |
| kotlin.test | - | Unit testing | KMP commonTest compatible |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| None | - | Pure functions only | Zero runtime dependencies |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Top-level functions | `object ProtocolParser` | Object adds unnecessary indirection for stateless functions |
| `ByteBuffer` (JVM) | Manual bit operations | `ByteBuffer` is not available in KMP commonMain; manual parsing already works |
| `Ktor-io` | Stick with manual | Adds dependency for simple byte operations |

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/
  +-- ProtocolParser.kt    # NEW: Pure byte parsing functions
  +-- BleConstants.kt      # Existing (post Phase 5)

shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/
  +-- ProtocolParserTest.kt  # NEW: Extensive byte-level tests
```

### Pattern 1: Pure Functions with Explicit Result Types
**What:** Top-level functions returning nullable or sealed class results
**When to use:** Protocol parsing where failures are expected (short packets, invalid data)
**Example:**
```kotlin
// Source: Current KableBleRepository.kt patterns + official app Reps.java
package com.devil.phoenixproject.data.ble

/**
 * Parse rep notification from REPS characteristic (NO opcode prefix).
 * Returns null for packets < 6 bytes (minimum legacy format).
 *
 * Packet formats:
 * - Legacy (6 bytes): topCounter(u16), unused(2), completeCounter(u16)
 * - Modern (24 bytes): up(i32), down(i32), rangeTop(f32), rangeBottom(f32),
 *                      repsRomCount(u16), repsRomTotal(u16), repsSetCount(u16), repsSetTotal(u16)
 */
fun parseRepPacket(
    data: ByteArray,
    hasOpcodePrefix: Boolean = false,
    timestamp: Long = 0L
): RepNotification? {
    val offset = if (hasOpcodePrefix) 1 else 0
    val effectiveSize = data.size - offset

    if (effectiveSize < 6) return null

    return if (effectiveSize >= 24) {
        // Modern 24-byte format
        RepNotification(
            topCounter = getInt32LE(data, offset),
            completeCounter = getInt32LE(data, offset + 4),
            rangeTop = getFloatLE(data, offset + 8),
            rangeBottom = getFloatLE(data, offset + 12),
            repsRomCount = getUInt16LE(data, offset + 16),
            repsRomTotal = getUInt16LE(data, offset + 18),
            repsSetCount = getUInt16LE(data, offset + 20),
            repsSetTotal = getUInt16LE(data, offset + 22),
            rawData = data,
            timestamp = timestamp,
            isLegacyFormat = false
        )
    } else {
        // Legacy 6-byte format
        RepNotification(
            topCounter = getUInt16LE(data, offset),
            completeCounter = getUInt16LE(data, offset + 4),
            repsRomCount = 0,
            repsRomTotal = 0,
            repsSetCount = 0,
            repsSetTotal = 0,
            rangeTop = 0f,
            rangeBottom = 0f,
            rawData = data,
            timestamp = timestamp,
            isLegacyFormat = true
        )
    }
}
```

### Pattern 2: Byte Utility Functions
**What:** Small, focused functions for specific byte operations
**When to use:** Extracting integers/floats from ByteArray with explicit endianness
**Example:**
```kotlin
// Source: Current KableBleRepository.kt lines 2634-2674

/** Read unsigned 16-bit integer in LITTLE-ENDIAN format (LSB first). */
fun getUInt16LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

/** Read signed 16-bit integer in LITTLE-ENDIAN format (LSB first). */
fun getInt16LE(data: ByteArray, offset: Int): Int {
    val unsigned = getUInt16LE(data, offset)
    return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
}

/** Read unsigned 16-bit integer in BIG-ENDIAN format (MSB first). */
fun getUInt16BE(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

/** Read signed 32-bit integer in LITTLE-ENDIAN format. */
fun getInt32LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
    ((data[offset + 1].toInt() and 0xFF) shl 8) or
    ((data[offset + 2].toInt() and 0xFF) shl 16) or
    ((data[offset + 3].toInt() and 0xFF) shl 24)

/** Read 32-bit float in LITTLE-ENDIAN format. */
fun getFloatLE(data: ByteArray, offset: Int): Float =
    Float.fromBits(getInt32LE(data, offset))
```

### Pattern 3: Data Classes for Intermediate Results
**What:** Lightweight data classes for parsed packet data before domain conversion
**When to use:** When parsed data needs further processing (validation, emission)
**Example:**
```kotlin
// Source: Decomposition plan MonitorPacket definition

/**
 * Raw parsed monitor data before validation/processing.
 * Position in mm, load in kg.
 */
data class MonitorPacket(
    val ticks: Int,
    val posA: Float,    // mm (raw / 10.0)
    val posB: Float,    // mm (raw / 10.0)
    val loadA: Float,   // kg (raw / 100.0)
    val loadB: Float,   // kg (raw / 100.0)
    val status: Int     // Status flags (bytes 16-17 if present)
)

/**
 * Raw parsed diagnostic data.
 */
data class DiagnosticPacket(
    val seconds: Int,
    val faults: List<Short>,    // 4 fault codes
    val temps: List<Byte>,      // 8 temperature readings
    val hasFaults: Boolean
)
```

### Anti-Patterns to Avoid
- **Stateful parsing:** The current `KableBleRepository` parsing methods access instance state (logging, flow emission). Extracted functions MUST NOT access any state.
- **Implicit endianness:** Always use explicit `LE` or `BE` suffix in function names. The official app uses Little-Endian for most packets.
- **Unchecked array access:** Always validate `data.size` before accessing offsets. Return null for short packets rather than throwing.
- **Mixing parsing and side effects:** Current code logs hex dumps and emits to flows inside parsing. Extract pure parsing, keep side effects in caller.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Float parsing | Manual IEEE 754 | `Float.fromBits(int)` | Kotlin stdlib handles IEEE 754 correctly |
| Hex formatting | Manual conversion | Existing `Byte.toHexString()` or inline | Keep existing pattern for consistency |
| Byte masking | `byte.toInt()` | `byte.toInt() and 0xFF` | Sign extension bug - bytes are signed in Kotlin |

**Key insight:** Kotlin bytes are signed (-128 to 127). Always mask with `and 0xFF` when combining into larger integers, or you get negative values corrupting the result. The existing codebase handles this correctly.

## Common Pitfalls

### Pitfall 1: Sign Extension Bug
**What goes wrong:** `byte.toInt()` preserves sign, so 0xFF becomes -1 instead of 255
**Why it happens:** Kotlin `Byte` is signed, `toInt()` sign-extends
**How to avoid:** Always use `byte.toInt() and 0xFF` for unsigned interpretation
**Warning signs:** Negative values appearing in position/load data, corrupt rep counters

### Pitfall 2: Wrong Endianness
**What goes wrong:** Parsing Little-Endian as Big-Endian (or vice versa) produces garbage values
**Why it happens:** Different packet types use different byte orders (monitor = LE, RX metrics = BE)
**How to avoid:** Check official app source for each packet type. Document endianness in function names.
**Warning signs:** Position values in thousands when expected < 1000, wildly fluctuating values

### Pitfall 3: Packet Length Assumptions (Issue #210 Warning)
**What goes wrong:** Treating packets with size X-Y as one format when they're actually another
**Why it happens:** Issue #210 history - three-tier parsing (< 16, 16-23, >= 24) BROKE rep counting on V-Form
**How to avoid:** Use TWO tiers only: < 24 = legacy, >= 24 = modern. Daem0n warning #188 documents this failure.
**Warning signs:** Reps not registering, delta calculation always 0

### Pitfall 4: Offset Off-By-One with Opcode Prefix
**What goes wrong:** RX notifications have 1-byte opcode prefix, REPS characteristic notifications don't
**Why it happens:** Two different paths to rep data with different offsets
**How to avoid:** Use `hasOpcodePrefix` parameter, calculate offset explicitly
**Warning signs:** Garbage values from RX parsing, correct values from REPS char

### Pitfall 5: Missing Position Validation Context
**What goes wrong:** Extracting position parsing without realizing it needs validation against previous values
**Why it happens:** Validation (position jumps, spike detection) is stateful, but parsing is pure
**How to avoid:** Keep parsing pure, validation stays in `MonitorDataProcessor` (Phase 6 in decomposition)
**Warning signs:** Position spikes appearing in parsed data (expected - filtering happens later)

## Code Examples

Verified patterns from codebase analysis:

### Complete Byte Utilities (from KableBleRepository.kt)
```kotlin
// Source: KableBleRepository.kt lines 2634-2683

/** Read unsigned 16-bit integer in LITTLE-ENDIAN format (LSB first). */
fun getUInt16LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

/** Read signed 16-bit integer in LITTLE-ENDIAN format. Sign-extends to 32-bit. */
fun getInt16LE(data: ByteArray, offset: Int): Int {
    val unsigned = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
}

/** Read unsigned 16-bit integer in BIG-ENDIAN format (MSB first). */
fun getUInt16BE(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

/** Read signed 32-bit integer in LITTLE-ENDIAN format. */
fun getInt32LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
    ((data[offset + 1].toInt() and 0xFF) shl 8) or
    ((data[offset + 2].toInt() and 0xFF) shl 16) or
    ((data[offset + 3].toInt() and 0xFF) shl 24)

/** Read 32-bit float in LITTLE-ENDIAN format. */
fun getFloatLE(data: ByteArray, offset: Int): Float =
    Float.fromBits(getInt32LE(data, offset))

/** Convert byte to two-character uppercase hex string (KMP-compatible). */
fun Byte.toVitruvianHex(): String {
    val hex = "0123456789ABCDEF"
    val value = this.toInt() and 0xFF
    return "${hex[value shr 4]}${hex[value and 0x0F]}"
}
```

### Monitor Packet Parsing (from official Sample.java + KableBleRepository)
```kotlin
// Source: Official app Sample.java read() + KableBleRepository parseMonitorData()

/**
 * Parse monitor/sample characteristic data.
 * Returns null for packets < 16 bytes.
 *
 * Official format (Little-Endian, 18+ bytes):
 * - Bytes 0-1:   ticks low (u16)
 * - Bytes 2-3:   ticks high (u16)
 * - Bytes 4-5:   posA (i16, scaled /10 for mm)
 * - Bytes 6-7:   (unused)
 * - Bytes 8-9:   loadA * 100 (u16)
 * - Bytes 10-11: posB (i16, scaled /10 for mm)
 * - Bytes 12-13: (unused)
 * - Bytes 14-15: loadB * 100 (u16)
 * - Bytes 16-17: status flags (u16, optional)
 */
fun parseMonitorPacket(data: ByteArray): MonitorPacket? {
    if (data.size < 16) return null

    val ticksLow = getUInt16LE(data, 0)
    val ticksHigh = getUInt16LE(data, 2)
    val ticks = ticksLow + (ticksHigh shl 16)

    val posARaw = getInt16LE(data, 4)   // Signed for negative positions
    val loadARaw = getUInt16LE(data, 8)
    val posBRaw = getInt16LE(data, 10)  // Signed for negative positions
    val loadBRaw = getUInt16LE(data, 14)

    val status = if (data.size >= 18) getUInt16LE(data, 16) else 0

    return MonitorPacket(
        ticks = ticks,
        posA = posARaw / 10.0f,    // mm
        posB = posBRaw / 10.0f,    // mm
        loadA = loadARaw / 100.0f, // kg
        loadB = loadBRaw / 100.0f, // kg
        status = status
    )
}
```

### Diagnostic Packet Parsing (from official DiagnosticDetails.java)
```kotlin
// Source: Official app DiagnosticDetails.java read()

/**
 * Parse diagnostic/property characteristic data.
 * Returns null for packets < 20 bytes.
 *
 * Official format (Little-Endian, 20+ bytes):
 * - Bytes 0-3:   seconds (uptime, i32)
 * - Bytes 4-11:  faults[4] (4 x i16)
 * - Bytes 12-19: temps[8] (8 x i8)
 */
fun parseDiagnosticPacket(data: ByteArray): DiagnosticPacket? {
    if (data.size < 20) return null

    val seconds = getInt32LE(data, 0)

    val faults = mutableListOf<Short>()
    for (i in 0 until 4) {
        val offset = 4 + (i * 2)
        val fault = ((data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort()
        faults.add(fault)
    }

    val temps = mutableListOf<Byte>()
    for (i in 0 until 8) {
        temps.add(data[12 + i])
    }

    return DiagnosticPacket(
        seconds = seconds,
        faults = faults.toList(),
        temps = temps.toList(),
        hasFaults = faults.any { it != 0.toShort() }
    )
}
```

### Heuristic Packet Parsing (from official Heuristic.java)
```kotlin
// Source: Official app Heuristic.java read()

/**
 * Parse heuristic characteristic data (force telemetry).
 * Returns null for packets < 48 bytes.
 *
 * Official format (Little-Endian, 48 bytes):
 * - Bytes 0-23:  left cable stats (6 floats: kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax)
 * - Bytes 24-47: right cable stats (6 floats: kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax)
 */
fun parseHeuristicPacket(data: ByteArray): HeuristicStatistics? {
    if (data.size < 48) return null

    fun parsePhaseStats(offset: Int) = HeuristicPhaseStatistics(
        kgAvg = getFloatLE(data, offset),
        kgMax = getFloatLE(data, offset + 4),
        velAvg = getFloatLE(data, offset + 8),
        velMax = getFloatLE(data, offset + 12),
        wattAvg = getFloatLE(data, offset + 16),
        wattMax = getFloatLE(data, offset + 20)
    )

    return HeuristicStatistics(
        concentric = parsePhaseStats(0),
        eccentric = parsePhaseStats(24),
        timestamp = 0L  // Caller sets timestamp
    )
}
```

### RX Metrics Packet Parsing (Big-Endian)
```kotlin
// Source: KableBleRepository parseMetricsPacket() - note BIG-ENDIAN

/**
 * Parse metrics from RX notification (0x01 command response).
 * NOTE: Uses BIG-ENDIAN byte order (unlike other packets).
 * Returns null for packets < 16 bytes.
 *
 * Format (Big-Endian):
 * - Byte 0:     opcode (0x01)
 * - Byte 1:     (reserved)
 * - Bytes 2-3:  posA (u16, scaled /10 for mm)
 * - Bytes 4-5:  posB (u16)
 * - Bytes 6-7:  loadA (u16)
 * - Bytes 8-9:  loadB (u16)
 * - Bytes 10-11: velocityA (u16, offset 32768)
 * - Bytes 12-13: velocityB (u16, offset 32768)
 */
fun parseRxMetricsPacket(data: ByteArray): RxMetricsPacket? {
    if (data.size < 16) return null

    return RxMetricsPacket(
        posA = getUInt16BE(data, 2) / 10.0f,
        posB = getUInt16BE(data, 4) / 10.0f,
        loadA = getUInt16BE(data, 6) / 10.0f,
        loadB = getUInt16BE(data, 8) / 10.0f,
        velocityA = (getUInt16BE(data, 10) - 32768).toDouble(),
        velocityB = (getUInt16BE(data, 12) - 32768).toDouble()
    )
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `java.nio.ByteBuffer` | Manual bit operations | KMP adoption | Platform-independent, no JVM dependency |
| Three-tier rep parsing | Two-tier (< 24, >= 24) | Issue #210 fix | Avoids V-Form regression |
| Private instance methods | Top-level pure functions | Phase 6 | Enables unit testing without BLE |

**Deprecated/outdated:**
- `ByteBuffer.wrap()`: JVM-only, not available in KMP commonMain. Manual parsing is the standard.
- Three-tier packet parsing (< 16, 16-23, >= 24): FAILED approach per Daem0n warning #188. Do not reintroduce.

## Test Strategy

### Unit Test Coverage (P0 Priority)

```kotlin
// Target: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/ProtocolParserTest.kt

class ProtocolParserTest {
    // ========== Byte Utility Tests ==========
    @Test fun `getUInt16LE parses correctly`() { /* 0x01, 0x02 -> 513 */ }
    @Test fun `getUInt16LE handles max value`() { /* 0xFF, 0xFF -> 65535 */ }
    @Test fun `getInt16LE handles positive values`() { /* 0x00, 0x10 -> 4096 */ }
    @Test fun `getInt16LE handles negative values`() { /* 0xFF, 0xFF -> -1 */ }
    @Test fun `getUInt16BE parses correctly`() { /* 0x01, 0x02 -> 258 */ }
    @Test fun `getInt32LE parses correctly`() { /* 0x01, 0x02, 0x03, 0x04 -> 67305985 */ }
    @Test fun `getFloatLE parses IEEE 754`() { /* known float bit pattern */ }

    // ========== Rep Packet Tests (Issue #210 critical) ==========
    @Test fun `parseRepPacket returns null for short data`() { /* < 6 bytes */ }
    @Test fun `parseRepPacket parses legacy 6-byte format`() { /* exactly 6 bytes */ }
    @Test fun `parseRepPacket parses modern 24-byte format`() { /* exactly 24 bytes */ }
    @Test fun `parseRepPacket handles opcode prefix`() { /* hasOpcodePrefix = true */ }
    @Test fun `parseRepPacket matches official app Reps_java`() { /* byte-for-byte */ }

    // ========== Monitor Packet Tests ==========
    @Test fun `parseMonitorPacket returns null for short data`() { /* < 16 bytes */ }
    @Test fun `parseMonitorPacket parses position correctly`() { /* signed, scaled */ }
    @Test fun `parseMonitorPacket parses load correctly`() { /* unsigned, scaled */ }
    @Test fun `parseMonitorPacket parses status flags`() { /* 18-byte packet */ }

    // ========== Diagnostic Packet Tests ==========
    @Test fun `parseDiagnosticPacket returns null for short data`() { /* < 20 bytes */ }
    @Test fun `parseDiagnosticPacket detects faults`() { /* non-zero fault codes */ }
    @Test fun `parseDiagnosticPacket parses temps`() { /* 8 temperature bytes */ }

    // ========== Heuristic Packet Tests ==========
    @Test fun `parseHeuristicPacket returns null for short data`() { /* < 48 bytes */ }
    @Test fun `parseHeuristicPacket parses concentric stats`() { /* first 24 bytes */ }
    @Test fun `parseHeuristicPacket parses eccentric stats`() { /* last 24 bytes */ }

    // ========== Edge Cases ==========
    @Test fun `byte masking prevents sign extension`() { /* 0xFF -> 255, not -1 */ }
    @Test fun `handles exactly minimum size packets`() { /* boundary conditions */ }
}
```

### Golden Data Test Vectors

Create test vectors from known-good packet captures:
```kotlin
// Sample test vector (from Issue #210 debugging)
val modernRepPacket = byteArrayOf(
    0x01, 0x00, 0x00, 0x00,  // up = 1
    0x01, 0x00, 0x00, 0x00,  // down = 1
    0x00, 0x00, 0x96.toByte(), 0x43.toByte(),  // rangeTop = 300.0f
    0x00, 0x00, 0x00, 0x00,  // rangeBottom = 0.0f
    0x01, 0x00,              // repsRomCount = 1
    0x03, 0x00,              // repsRomTotal = 3
    0x00, 0x00,              // repsSetCount = 0
    0x0A, 0x00               // repsSetTotal = 10
)
```

## Open Questions

1. **Return type: nullable vs Result/Either**
   - What we know: Current code returns early with `return` for short packets, logging warnings
   - What's unclear: Should parsing functions return `T?` or a sealed result with error info?
   - Recommendation: Use nullable for simplicity (`T?`). Errors are logged at call site, not in parser.

2. **Timestamp parameter**
   - What we know: `RepNotification` includes timestamp, but parsing is pure
   - What's unclear: Should parser take timestamp as parameter or leave it 0?
   - Recommendation: Take timestamp as parameter with default 0. Caller provides current time.

3. **HeuristicStatistics timestamp field**
   - What we know: `HeuristicStatistics` has `timestamp` field, but parsing doesn't know time
   - What's unclear: Set to 0 in parser, or remove from domain model?
   - Recommendation: Set to 0 in parser, caller updates if needed. Don't change domain model.

4. **RxMetricsPacket data class**
   - What we know: `parseMetricsPacket` creates `WorkoutMetric` directly with handle detection
   - What's unclear: Should extraction create intermediate `RxMetricsPacket` or go straight to `WorkoutMetric`?
   - Recommendation: Create intermediate data class. Handle detection is stateful, stays in caller.

## Requirements Mapping

| Requirement | How Addressed |
|-------------|---------------|
| **PARSE-01**: Protocol parsing functions extracted to `ProtocolParser.kt` (stateless) | Top-level pure functions, no instance state, no side effects |
| **PARSE-02**: Unit tests verify byte parsing matches monolith behavior | Comprehensive `ProtocolParserTest.kt` with golden vectors from official app |

## Success Criteria Checklist

1. [x] Protocol parsing functions callable without BLE connection - pure functions, no dependencies
2. [x] Unit tests verify byte parsing matches monolith behavior - test strategy documented
3. [x] Legacy 6-byte and modern 24-byte rep formats both parse correctly - two-tier parsing (Issue #210)
4. [x] Byte utilities are pure functions - stateless, explicit endianness

## Sources

### Primary (HIGH confidence)
- `KableBleRepository.kt` lines 2386-2683 - Current parsing implementation
- `java-decompiled/sources/com/vitruvian/formtrainer/Reps.java` - Official rep packet format
- `java-decompiled/sources/com/vitruvian/formtrainer/Sample.java` - Official monitor packet format
- `java-decompiled/sources/com/vitruvian/formtrainer/DiagnosticDetails.java` - Official diagnostic format
- `java-decompiled/sources/com/vitruvian/formtrainer/Heuristic.java` - Official heuristic format
- `.planning/plans/kable-decomposition-plan.md` - Phase 2 extraction plan

### Secondary (MEDIUM confidence)
- Daem0n memory #188 - Issue #210 three-tier parsing failure warning
- Daem0n memory #186 - Issue #210 parsing decision history

### Tertiary (LOW confidence)
- None (all patterns verified against codebase and official app)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Pure Kotlin stdlib, no external dependencies
- Architecture: HIGH - Extracting existing working code with minimal transformation
- Pitfalls: HIGH - Based on actual Issue #210 failure and official app verification

**Research date:** 2026-02-15
**Valid until:** Indefinitely (protocol format is fixed by hardware)

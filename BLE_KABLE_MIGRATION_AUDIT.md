# BLE/GATT Protocol Parity Audit: Kable Migration

**Audit Date:** 2025-11-29
**Auditor:** Claude (Automated Protocol Audit)
**Repository:** Project-Phoenix-2.0 (Kable-based KMP)
**Primary Reference:** [workoutmachineappfree](https://github.com/workoutmachineappfree/workoutmachineappfree.github.io) (Unofficial Web App - **AUTHORITATIVE SOURCE**)
**Secondary Reference:** VitruvianProjectPhoenix (Nordic BLE / Android-specific)

> **IMPORTANT:** The official Vitruvian app uses cloud-obfuscated protocols. Both this build and the
> parent Nordic build derive their protocols from the **unofficial web app** which reverse-engineered
> the direct BLE protocol. The web app is the authoritative protocol reference.

---

## Executive Summary

This audit compares the Kable-based Kotlin Multiplatform BLE implementation against the authoritative
unofficial web app protocol and the parent Nordic BLE implementation. The migration is **largely complete**
with good data parsing parity, but several **CRITICAL** and **HIGH** severity gaps were identified that
could cause workout failures or mode selection issues.

### Quick Stats
- **UUIDs:** MATCH (Core NUS UUIDs identical)
- **Core Protocol:** PARTIAL (Using simplified 0x4F instead of full 0x04 frame)
- **Mode Values:** **DISCREPANCY** (See Critical Finding below)
- **Data Parsing:** MATCH (Byte ordering and offsets correct)
- **State Machine:** MATCH (Handle detection states equivalent)
- **Connection Handling:** IMPROVED (Better auto-reconnect in Kable)

### CRITICAL FINDING: Mode Value Discrepancy

**Web App (Authoritative `modes.js`):**
| Mode | Value |
|------|-------|
| Old School | 0 |
| Pump | **1** |
| TUT | **2** |
| TUT Beast | **3** |
| Eccentric Only | **4** |

**Kable Build (`Models.kt:66-71`):**
| Mode | Value |
|------|-------|
| OldSchool | 0 |
| Pump | **2** |
| TUT | **3** |
| TUTBeast | **4** |
| EccentricOnly | **6** |

**Impact:** Selecting "Pump" mode sends value `2` (which is TUT in web app protocol). This could cause
completely wrong workout behavior. **Needs immediate verification against actual hardware.**

> **Note:** The Kable build comment says "Official app uses 0x4F" suggesting these values may come from
> deobfuscated official app rather than web app. Both sources should be tested on hardware to determine
> which is correct.

---

## Phase 1: BLE Service & Characteristic Mapping

### 1.1 UUID Comparison

| Characteristic | Parent (Nordic) | Kable Build | Status |
|---------------|-----------------|-------------|--------|
| NUS Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | **MATCH** |
| NUS TX (Write) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | **MATCH** |
| NUS RX (Notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | **MATCH** |
| Monitor/Sample | `90e991a6-c548-44ed-969b-eb541014eae3` | `90e991a6-c548-44ed-969b-eb541014eae3` | **MATCH** |
| Reps Notify | `8308f2a6-0875-4a94-a86f-5c5c5e1b068a` | NOT USED DIRECTLY | **GAP** |
| Diagnostic | `5fa538ec-d041-42f6-bbd6-c30d475387b7` | DEFINED BUT NOT USED | **GAP** |
| Heuristic | `c7b73007-b245-4503-a1ed-9e4e97eb9802` | DEFINED BUT NOT USED | **GAP** |
| Version | `74e994ac-0e80-4c02-9cd0-76cb31d3959b` | DEFINED BUT NOT USED | **GAP** |
| Mode | `67d0dae0-5bfc-4ea2-acc9-ac784dee7f29` | DEFINED BUT NOT USED | **GAP** |
| Cable Left | `bc4344e9-8d63-4c89-8263-951e2d74f744` | DEFINED BUT NOT USED | **GAP** |
| Cable Right | `92ef83d6-8916-4921-8172-a9919bc82566` | DEFINED BUT NOT USED | **GAP** |

### 1.2 GATT Operation Comparison

| Operation | Parent | Kable Build | Status |
|-----------|--------|-------------|--------|
| Write Type | `WriteType.WithoutResponse` | `WriteType.WithoutResponse` | **MATCH** |
| MTU Request | 247 bytes (explicit) | 512 bytes (requested, auto-negotiated) | **ACCEPTABLE** |
| Notification Setup | Explicit descriptor write | Kable `observe()` auto-handles | **MATCH** |
| Connection Priority | HIGH | Not explicitly set | **MEDIUM GAP** |

### 1.3 Missing Characteristics (Severity Assessment)

| Characteristic | Purpose | Severity | Impact |
|---------------|---------|----------|--------|
| Reps Notify (`8308f2a6`) | Dedicated rep notifications | **LOW** | RX notifications cover this |
| Diagnostic | Fault codes, temperature | **MEDIUM** | No device health monitoring |
| Heuristic | Phase statistics (Echo mode) | **MEDIUM** | Missing Echo mode analytics |
| Version | Firmware version | **LOW** | Informational only |
| Cable Left/Right | Per-cable telemetry | **LOW** | Monitor char provides this |

---

## Phase 2: Connection Lifecycle

### 2.1 Scanning Comparison

| Parameter | Parent | Kable Build | Status |
|-----------|--------|-------------|--------|
| Device Name Filter - Vee_ | YES | YES | **MATCH** |
| Device Name Filter - VIT | YES | YES | **MATCH** |
| Device Name Filter - V-Trainer | YES | NO | **GAP** |
| Device Name Filter - Vitruvian | YES | NO | **GAP** |
| Scan Timeout | 30000ms | Not implemented | **MEDIUM GAP** |
| Duplicate Handling | By identifier | By identifier | **MATCH** |

**Finding:** The Kable build only filters for `Vee_` and `VIT` prefixes, while the parent also accepts `V-Trainer` and `Vitruvian` patterns. This could prevent discovery of some device variants.

### 2.2 Connection Establishment

| Parameter | Parent | Kable Build | Status |
|-----------|--------|-------------|--------|
| Retry Count | 3 | 3 | **MATCH** |
| Retry Delay | 100ms | 100ms | **MATCH** |
| Connection Timeout | 15000ms | Not explicit (Kable default) | **MEDIUM GAP** |
| Auto-connect | No | No | **MATCH** |
| PHY Preference | Not specified | Not specified | **MATCH** |
| Connection Priority | HIGH | Not set | **MEDIUM GAP** |

### 2.3 Connection Maintenance

| Feature | Parent | Kable Build | Status |
|---------|--------|-------------|--------|
| Heartbeat Interval | 2000ms | 2000ms | **MATCH** |
| Heartbeat Read Timeout | Not specified | 1500ms | **IMPROVED** |
| Heartbeat Fallback | Write no-op | Read-first, write fallback | **IMPROVED** |
| No-op Command Size | 4 bytes | 4 bytes | **MATCH** |
| Monitor Polling | 100ms | 100ms | **MATCH** |

### 2.4 Disconnection Handling

| Feature | Parent | Kable Build | Status |
|---------|--------|-------------|--------|
| Explicit Disconnect Flag | YES | YES | **MATCH** |
| Auto-reconnect on Drop | YES | YES | **MATCH** |
| Resource Cleanup | Manual | Automatic via Kable | **IMPROVED** |
| State Reset | YES | YES | **MATCH** |

---

## Phase 3: Protocol Packet Implementation

### 3.1 Command Comparison (vs Web App Protocol)

| Command | Web App | Kable Build | Status | Severity |
|---------|---------|-------------|--------|----------|
| INIT (0x0A) | 4 bytes `[0x0A, 0x00, 0x00, 0x00]` | NOT IMPLEMENTED | **GAP** | **MEDIUM** |
| INIT PRESET (0x11) | 34 bytes (coefficient table) | NOT IMPLEMENTED | **GAP** | **MEDIUM** |
| PROGRAM (0x04) | 96 bytes (full frame) | NOT IMPLEMENTED | **GAP** | **HIGH** |
| REGULAR (0x4F) | Not in web app | 25 bytes | **ALTERNATE** | - |
| ECHO (0x4E) | 32 bytes (full params) | 29 bytes (stub) | **INCOMPLETE** | **HIGH** |
| STOP (0x50) | Used by web app | 1 byte | **MATCH** | - |
| COLOR (0x11) | 34 bytes | NOT IMPLEMENTED | **GAP** | **LOW** |

**Key Insight:** The Kable build uses a simplified 25-byte 0x4F command that is NOT in the web app protocol.
This appears to be derived from the official app deobfuscation. The web app uses the full 96-byte 0x04 command.

**Both approaches may work** - the 0x4F command has been tested successfully in the parent repo. However,
the mode value discrepancy (see Critical Finding above) needs verification.

### 3.2 CRITICAL: Missing 96-byte Activation Command (0x04)

**Location:** Parent `ProtocolBuilder.kt` defines full structure
**Impact:** Cannot use official app protocol for workout initialization

**Parent Structure (96 bytes):**
```
Offset  Size  Type     Description
0x00    4     u32 LE   Command ID = 0x04
0x04    1     u8       Reps (0xFF for unlimited)
0x05    1     u8       Reserved (0x03)
0x06    1     u8       Reserved (0x03)
0x08    4     f32 LE   Constant (5.0)
0x0C    4     f32 LE   Constant (5.0)
...
0x30    32    u8[32]   Mode profile block (8 fields per mode)
0x54    4     f32 LE   Effective weight (kg + 10)
0x58    4     f32 LE   Total weight (kg)
0x5C    4     f32 LE   Progression/regression (kg per rep)
```

**Kable Build:** Uses simplified 25-byte 0x4F command only

### 3.3 CRITICAL: Missing INIT/RESET Command (0x0A)

**Parent Usage:** Recovery from faults, device initialization
**Impact:** Cannot recover from machine faults without disconnect/reconnect

**Parent Structure (4 bytes):**
```
0x0A 0x00 0x00 0x00
```

**Kable Build:** Not implemented - `ProtocolTester.kt` references but no factory method

### 3.4 HIGH: Echo Command (0x4E) Incomplete

**Parent Structure (32 bytes):**
```
Offset  Size  Type     Description
0x00    4     u32 LE   Command ID = 0x4E
0x04    1     u8       Warmup reps
0x05    1     u8       Target reps (0xFF for unlimited)
0x06    2     u16 LE   Reserved
0x08    2     u16 LE   Eccentric percentage
0x0A    2     u16 LE   Concentric percentage
0x0C    4     f32 LE   Smoothing parameter
0x10    4     f32 LE   Gain
0x14    4     f32 LE   Cap
0x18    4     f32 LE   Floor
0x1C    4     f32 LE   Negative limit
```

**Kable Build (`BlePacketFactory.kt:53-65`):**
```kotlin
fun createEchoCommand(level: Int, eccentricLoad: Int): ByteArray {
    val buffer = ByteArray(29)
    buffer[0] = BleConstants.Commands.ECHO_COMMAND
    buffer[1] = level.toByte()
    // TODO: Verify Echo packet structure
    return buffer
}
```

**Issues:**
1. Missing eccentric/concentric percentage values
2. Missing smoothing, gain, cap, floor, negative limit parameters
3. Marked as TODO - incomplete implementation

### 3.5 Regular Command (0x4F) - VERIFIED MATCH

**Parent (`ProtocolBuilder.kt`):**
```
[0]: Command (0x4F)
[1]: Mode (0=OldSchool, 2=Pump, 3=TUT, 4=TUTBeast, 6=EccentricOnly)
[2-3]: Weight per cable (UInt16, scaled by 100, LITTLE-ENDIAN)
[4]: Target Reps
[5-24]: Padding
```

**Kable Build (`BlePacketFactory.kt:20-43`):**
```kotlin
val buffer = ByteArray(25)
buffer[0] = BleConstants.Commands.REGULAR_COMMAND  // 0x4F
buffer[1] = workoutType.mode.modeValue.toByte()    // Mode byte
val weightScaled = (weightPerCableKg * 100).toInt()
buffer[2] = (weightScaled and 0xFF).toByte()       // Weight LSB
buffer[3] = ((weightScaled shr 8) and 0xFF).toByte() // Weight MSB
buffer[4] = targetReps.toByte()                     // Reps
```

**Status:** **BYTE-FOR-BYTE MATCH** - Weight scaling, byte order, and structure identical

### 3.6 Stop Command (0x50) - VERIFIED MATCH

Both implementations send single byte `0x50` for stop command.

---

## Phase 4: Data Flow & State Management

### 4.1 Monitor Data Parsing

**Byte Layout Comparison:**

| Field | Parent Offset | Kable Offset | Endian | Status |
|-------|--------------|--------------|--------|--------|
| Ticks Low (f0) | 0-1 | 0-1 | LE | **MATCH** |
| Ticks High (f1) | 2-3 | 2-3 | LE | **MATCH** |
| Position A | 4-5 | 4-5 | LE | **MATCH** |
| Load A (*100) | 8-9 | 8-9 | LE | **MATCH** |
| Position B | 10-11 | 10-11 | LE | **MATCH** |
| Load B (*100) | 14-15 | 14-15 | LE | **MATCH** |
| Status Flags | 16-17 | 16-17 | LE | **MATCH** |

**Kable Implementation (`KableBleRepository.kt:664-781`):** Correctly implements:
- 32-bit tick reconstruction from two 16-bit values
- Spike filtering (>50000 treated as BLE error)
- Load scaling (divide by 100 for kg)
- Velocity calculation from delta position/time

### 4.2 Rep Notification Parsing

**24-byte Official Format:**

| Field | Parent Offset | Kable Offset | Type | Status |
|-------|--------------|--------------|------|--------|
| Up Counter | 0-3 | 1-4 (after opcode) | i32 LE | **MATCH** |
| Down Counter | 4-7 | 5-8 | i32 LE | **MATCH** |
| Range Top | 8-11 | 9-12 | f32 LE | **MATCH** |
| Range Bottom | 12-15 | 13-16 | f32 LE | **MATCH** |
| Reps ROM Count | 16-17 | 17-18 | u16 LE | **MATCH** |
| Reps Set Count | 20-21 | 21-22 | u16 LE | **MATCH** |

**Note:** Kable accounts for opcode byte at position 0, offsets adjusted accordingly.

**Legacy 6-byte Format:** Both implementations support backwards compatibility.

### 4.3 Status Flag Processing

| Flag | Bit | Parent | Kable | Status |
|------|-----|--------|-------|--------|
| REP_TOP_READY | 0 | YES | YES | **MATCH** |
| REP_BOTTOM_READY | 1 | YES | YES | **MATCH** |
| ROM_OUTSIDE_HIGH | 2 | YES | YES | **MATCH** |
| ROM_OUTSIDE_LOW | 3 | YES | YES | **MATCH** |
| ROM_UNLOAD_ACTIVE | 4 | YES | YES | **MATCH** |
| SPOTTER_ACTIVE | 5 | YES | YES | **MATCH** |
| DELOAD_WARN | 6 | YES | YES | **MATCH** |
| DELOAD_OCCURRED | 15 | YES | YES | **MATCH** |

**Kable Implementation (`SampleStatus.kt`):** Complete bit mask implementation matching parent.

### 4.4 Handle Activity State Machine

| State | Parent | Kable | Status |
|-------|--------|-------|--------|
| WaitingForRest | YES | YES | **MATCH** |
| Released/SetComplete | YES | SetComplete | **MATCH** (renamed) |
| Grabbed | YES | Active | **MATCH** (renamed) |
| Active/Moving | YES | Active | **MATCH** (combined) |

**Thresholds:**

| Threshold | Parent | Kable | Status |
|-----------|--------|-------|--------|
| Grab Position | 8.0 | 8.0 | **MATCH** |
| Rest Position | 5.0 | 5.0 | **MATCH** |
| Velocity | 100.0 | 100.0 | **MATCH** |
| Spike Filter | 50000 | 50000 | **MATCH** |

### 4.5 Deload Event Handling

| Feature | Parent | Kable | Status |
|---------|--------|-------|--------|
| Debounce Time | 2000ms | 2000ms | **MATCH** |
| Event Flow | SharedFlow | SharedFlow | **MATCH** |
| Buffer Capacity | Not specified | 8 | **IMPROVED** |

---

## Phase 5: Platform-Specific Considerations

### 5.1 Kable-Specific Adaptations

| Feature | Implementation | Status |
|---------|----------------|--------|
| MTU Negotiation | Auto-handled by Kable 0.40.0 | **CORRECT** |
| `peripheral.write()` | Uses `WriteType.WithoutResponse` | **CORRECT** |
| Notification Subscription | `observe()` pattern | **CORRECT** |
| Coroutine Scope | `SupervisorJob + Dispatchers.Default` | **CORRECT** |
| State Flow mapping | Kable State → ConnectionState | **CORRECT** |

### 5.2 Missing Platform Features

| Feature | Parent | Kable | Severity |
|---------|--------|-------|----------|
| Connection Priority HIGH | YES | NO | **MEDIUM** |
| Explicit MTU request | 247 bytes | 512 (auto) | **LOW** |
| Service Invalidation Handler | YES | Partial | **MEDIUM** |
| Diagnostic Polling (500ms) | YES | NO | **MEDIUM** |
| Heuristic Polling (250ms) | YES | NO | **MEDIUM** |

### 5.3 iOS Considerations

| Feature | Status | Notes |
|---------|--------|-------|
| CoreBluetooth State Restoration | NOT IMPLEMENTED | Kable may handle automatically |
| Background Mode | NOT IMPLEMENTED | Requires iOS-specific config |
| iOS Timing Constraints | UNKNOWN | Needs testing |

---

## Gap Analysis Summary

### CRITICAL Gaps (Must Fix)

| ID | Gap | Impact | Files Affected |
|----|-----|--------|----------------|
| **C1** | **Mode Value Discrepancy** | Wrong workout mode sent to machine | `Models.kt:66-71` |

### HIGH Gaps (Should Fix)

| ID | Gap | Impact | Files Affected |
|----|-----|--------|----------------|
| H1 | Echo Command (0x4E) incomplete | Echo mode parameters missing | `BlePacketFactory.kt:53-65` |
| H2 | Missing 0x04 Program Command (96 bytes) | Cannot use web app protocol | `BlePacketFactory.kt` |
| H3 | Missing initialization sequence (0x0A + 0x11) | May cause stability issues | `BlePacketFactory.kt` |

### MEDIUM Gaps (Consider Fixing)

| ID | Gap | Impact | Files Affected |
|----|-----|--------|----------------|
| M0 | Verify 0x4F command works on hardware | Need hardware testing | `BlePacketFactory.kt` |
| M1 | Scan filter missing V-Trainer/Vitruvian | May miss device variants | `KableBleRepository.kt:191-193` |
| M2 | No explicit connection priority | Potential stability on Android | `KableBleRepository.kt` |
| M3 | Scan timeout not implemented | Battery drain if scan forgotten | `KableBleRepository.kt` |
| M4 | Connection timeout not explicit | May hang on slow connections | `KableBleRepository.kt` |

### LOW Gaps (Nice to Have)

| ID | Gap | Impact | Files Affected |
|----|-----|--------|----------------|
| L1 | Color scheme command missing | No LED customization | `BlePacketFactory.kt` |
| L2 | Version characteristic not read | No firmware version display | `KableBleRepository.kt` |

---

## Implementation Recommendations

### Priority 0: VERIFY MODE VALUES ON HARDWARE

**THIS IS THE MOST CRITICAL ISSUE.** Before any other changes, verify which mode values work:

**Test Matrix:**
| Mode | Web App Value | Kable Value | Test Result |
|------|---------------|-------------|-------------|
| Old School | 0 | 0 | (same) |
| Pump | 1 | 2 | **VERIFY** |
| TUT | 2 | 3 | **VERIFY** |
| TUT Beast | 3 | 4 | **VERIFY** |
| Eccentric Only | 4 | 6 | **VERIFY** |

If web app values are correct, update `Models.kt:66-71`:
```kotlin
sealed class ProgramMode(val modeValue: Int, val displayName: String) {
    object OldSchool : ProgramMode(0, "Old School")
    object Pump : ProgramMode(1, "Pump")           // Was 2
    object TUT : ProgramMode(2, "TUT")             // Was 3
    object TUTBeast : ProgramMode(3, "TUT Beast")  // Was 4
    object EccentricOnly : ProgramMode(4, "Eccentric Only")  // Was 6
}
```

### Priority 1: Web App Initialization Sequence

The web app uses this initialization sequence before workouts:
1. Send INIT: `[0x0A, 0x00, 0x00, 0x00]`
2. Send INIT PRESET (0x11): 34-byte coefficient table
3. Send PROGRAM (0x04): 96-byte workout parameters

**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`

Add the following methods:

```kotlin
/**
 * Creates INIT/RESET command (0x0A) - 4 bytes
 * Used for device initialization and fault recovery
 */
fun createInitCommand(): ByteArray {
    return byteArrayOf(0x0A, 0x00, 0x00, 0x00)
}

/**
 * Creates 96-byte Activation command (0x04)
 * Full official app protocol for workout initialization
 */
fun createActivationCommand(
    mode: ProgramMode,
    weightKg: Float,
    targetReps: Int,
    progressionKg: Float = 0f
): ByteArray {
    val buffer = ByteArray(96)

    // Command ID (bytes 0-3, LE)
    buffer[0] = 0x04
    buffer[1] = 0x00
    buffer[2] = 0x00
    buffer[3] = 0x00

    // Reps (byte 4): 0xFF for unlimited
    buffer[4] = if (targetReps == 0) 0xFF.toByte() else targetReps.toByte()

    // Reserved (bytes 5-6)
    buffer[5] = 0x03
    buffer[6] = 0x03

    // Constants (5.0f at offsets 8, 12, 28)
    putFloatLE(buffer, 8, 5.0f)
    putFloatLE(buffer, 12, 5.0f)
    putFloatLE(buffer, 28, 5.0f)

    // Mode profile block at offset 48 (0x30)
    // ... mode-specific values ...

    // Effective weight at offset 84 (0x54)
    putFloatLE(buffer, 84, weightKg + 10f)

    // Total weight at offset 88 (0x58)
    putFloatLE(buffer, 88, weightKg)

    // Progression at offset 92 (0x5C)
    putFloatLE(buffer, 92, progressionKg)

    return buffer
}

private fun putFloatLE(buffer: ByteArray, offset: Int, value: Float) {
    val bits = value.toBits()
    buffer[offset] = (bits and 0xFF).toByte()
    buffer[offset + 1] = ((bits shr 8) and 0xFF).toByte()
    buffer[offset + 2] = ((bits shr 16) and 0xFF).toByte()
    buffer[offset + 3] = ((bits shr 24) and 0xFF).toByte()
}
```

### Priority 2: Complete Echo Command

**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`

```kotlin
/**
 * Creates complete Echo command (0x4E) - 32 bytes
 */
fun createEchoCommand(
    warmupReps: Int,
    targetReps: Int,
    eccentricPercent: Int,
    concentricPercent: Int,
    smoothing: Float = 0.5f,
    gain: Float = 1.0f,
    cap: Float = 150f,
    floor: Float = 0f,
    negativeLimit: Float = -50f
): ByteArray {
    val buffer = ByteArray(32)

    // Command ID (bytes 0-3, LE)
    buffer[0] = 0x4E
    buffer[1] = 0x00
    buffer[2] = 0x00
    buffer[3] = 0x00

    // Warmup reps (byte 4)
    buffer[4] = warmupReps.toByte()

    // Target reps (byte 5): 0xFF for unlimited
    buffer[5] = if (targetReps == 0) 0xFF.toByte() else targetReps.toByte()

    // Reserved (bytes 6-7)
    buffer[6] = 0x00
    buffer[7] = 0x00

    // Eccentric percentage (bytes 8-9, LE)
    buffer[8] = (eccentricPercent and 0xFF).toByte()
    buffer[9] = ((eccentricPercent shr 8) and 0xFF).toByte()

    // Concentric percentage (bytes 10-11, LE)
    buffer[10] = (concentricPercent and 0xFF).toByte()
    buffer[11] = ((concentricPercent shr 8) and 0xFF).toByte()

    // Float parameters
    putFloatLE(buffer, 12, smoothing)
    putFloatLE(buffer, 16, gain)
    putFloatLE(buffer, 20, cap)
    putFloatLE(buffer, 24, floor)
    putFloatLE(buffer, 28, negativeLimit)

    return buffer
}
```

### Priority 3: Scan Filter Update

**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`

Update line 191-193:
```kotlin
.filter { advertisement ->
    val name = advertisement.name ?: return@filter false
    name.startsWith("Vee_") ||
    name.startsWith("VIT") ||
    name.startsWith("V-Trainer") ||
    name.matches(Regex("^Vitruvian.*$"))
}
```

---

## Test Cases for Verification

### Protocol Verification Tests

1. **0x4F Command Byte Dump Test**
   - Send command with weight=50kg, mode=OldSchool, reps=10
   - Verify bytes: `4F 00 88 13 0A 00 00 ...` (50*100=5000=0x1388, LE=88 13)

2. **0x04 Activation Command Test**
   - Send full 96-byte frame
   - Verify machine enters configured mode

3. **0x0A Reset Recovery Test**
   - Induce fault state
   - Send 0x0A command
   - Verify fault clears without reconnect

4. **Rep Notification Format Test**
   - Trigger rep completion
   - Verify 24-byte packet parsing
   - Verify legacy 6-byte fallback

5. **Deload Event Test**
   - Trigger deload condition
   - Verify 0x8000 flag detection
   - Verify 2000ms debounce

---

## Risk Assessment

| Change | Risk Level | Mitigation |
|--------|------------|------------|
| Add 0x04 command | **MEDIUM** | Test on single device first, verify no side effects |
| Add 0x0A command | **LOW** | Simple 4-byte command, well-documented |
| Complete 0x4E | **MEDIUM** | Echo mode parameters need firmware verification |
| Scan filter update | **LOW** | Additive change, won't break existing |
| Polling additions | **LOW** | Independent from core workout flow |

---

## Conclusion

The Kable migration successfully implements core BLE communication with accurate data parsing and state
management. The implementation uses a simplified 0x4F command (from official app deobfuscation) rather
than the 0x04 command (from the web app protocol). Both approaches may be valid.

### IMMEDIATE ACTION REQUIRED

**Verify mode values on hardware before any production use.** The mode value discrepancy between the
web app (authoritative) and current implementation could cause wrong workout modes to activate.

### Recommended Implementation Order

1. **CRITICAL:** Test mode values 0-4 vs 0,2,3,4,6 on actual hardware
2. Complete 0x4E Echo command parameters (enables full Echo mode)
3. Consider implementing web app protocol (0x0A → 0x11 → 0x04) as alternative
4. Update scan filters to include all device name patterns
5. Add diagnostic/heuristic polling (nice-to-have for device health)

### Protocol Sources Summary

| Source | Protocol | Status |
|--------|----------|--------|
| Web App (workoutmachineappfree) | 0x0A → 0x11 → 0x04 (96-byte) | **AUTHORITATIVE** |
| Official App (deobfuscated) | 0x4F (25-byte) | Used in Kable build |
| Official App (production) | Cloud-obfuscated | Cannot replicate |

---

*Generated by automated protocol audit against [workoutmachineappfree](https://github.com/workoutmachineappfree/workoutmachineappfree.github.io).
Manual hardware verification required before production deployment.*

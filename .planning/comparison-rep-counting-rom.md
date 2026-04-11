# Deep Source Code Comparison: Rep Counting & ROM Detection

**Generated**: 2026-02-17
**Method**: Line-by-line analysis of decompiled Java source (NOT finaldocs summaries)
**Official App Source**: `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\java-decompiled\sources\`
**Phoenix Source**: `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\shared\src\`

---

## 1. BLE Characteristic Architecture

### 1.1 Characteristic UUIDs (Both Apps Match)

| Characteristic | UUID | Official Class | Phoenix Constant |
|---|---|---|---|
| **Reps** | `8308f2a6-0875-4a94-a86f-5c5c5e1b068a` | `Reps.Characteristic` | `REPS_CHAR_UUID_STRING` |
| **Sample/Monitor** | `90e991a6-c548-44ed-969b-eb541014eae3` | `Sample.Characteristic` | `SAMPLE_CHAR_UUID_STRING` |
| **Heuristic** | `c7b73007-b245-4503-a1ed-9e4e97eb9802` | `Heuristic.Characteristic` | `HEURISTIC_CHAR_UUID_STRING` |
| **Cable Left** | `bc4344e9-8d63-4c89-8263-951e2d74f744` | `Cable.LeftCharacteristic` | `CABLE_LEFT_CHAR_UUID_STRING` |
| **Cable Right** | `92ef83d6-8916-4921-8172-a9919bc82566` | `Cable.RightCharacteristic` | `CABLE_RIGHT_CHAR_UUID_STRING` |

### 1.2 Reps Characteristic: The Notification-Based Rep Source

The **Reps characteristic** (`8308f2a6`) is the primary rep counting source. It is a **notifiable** characteristic -- the machine pushes updates when rep events occur.

---

## 2. Reps Packet Parsing

### 2.1 Official App: `Reps.Characteristic.read()` (24 bytes)

**File**: `java-decompiled\sources\com\vitruvian\formtrainer\Reps.java` (lines 81-95)

```java
public Reps read(byte[] bytes) {
    DeviceScreen_Lambda_N.g(bytes, "bytes");
    if (bytes.length == 0) {
        return new Reps(0, 0, 0.0f, 0.0f, null, null, null, null, 255, null);
    }
    ByteBuffer order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int i10 = order.getInt();        // bytes 0-3:  up counter (Int32LE)
    int i11 = order.getInt();        // bytes 4-7:  down counter (Int32LE)
    float f10 = order.getFloat();    // bytes 8-11: rangeTop (FloatLE)
    float f11 = order.getFloat();    // bytes 12-15: rangeBottom (FloatLE)
    if (order.remaining() >= 8) {
        // bytes 16-17: repsRomCount (Short/Int16LE)
        // bytes 18-19: repsRomTotal (Short/Int16LE)
        // bytes 20-21: repsSetCount (Short/Int16LE)
        // bytes 22-23: repsSetTotal (Short/Int16LE)
        return new Reps(i10, i11, f10, f11,
            Short.valueOf(order.getShort()),
            Short.valueOf(order.getShort()),
            Short.valueOf(order.getShort()),
            Short.valueOf(order.getShort()));
    }
    // Legacy fallback (< 24 bytes): only up, down, rangeTop, rangeBottom
    return new Reps(i10, i11, f10, f11, null, null, null, null, 240, null);
}
```

**Key observations from the code**:
- `up` (field `this.up`): Int32 -- concentric completion counter (increments at TOP of rep)
- `down` (field `this.down`): Int32 -- eccentric completion counter (increments at BOTTOM of rep)
- `rangeTop`: Float -- upper ROM boundary in mm (default 300.0f when absent)
- `rangeBottom`: Float -- lower ROM boundary in mm (default 0.0f when absent)
- `repsRomCount`: Nullable Short -- warmup rep count (within ROM)
- `repsRomTotal`: Nullable Short -- total warmup target
- `repsSetCount`: Nullable Short -- working set rep count
- `repsSetTotal`: Nullable Short -- total working set target
- The `repsRom*` and `repsSet*` fields are **ONLY present in 24-byte packets** (firmware v2+)

### 2.2 Phoenix: `parseRepPacket()` (matches exactly)

**File**: `shared\src\commonMain\kotlin\com\devil\phoenixproject\data\ble\ProtocolParser.kt` (lines 113-163)

```kotlin
fun parseRepPacket(data: ByteArray, hasOpcodePrefix: Boolean, timestamp: Long): RepNotification? {
    val offset = if (hasOpcodePrefix) 1 else 0
    val effectiveSize = data.size - offset

    if (effectiveSize < 6) return null

    return if (effectiveSize >= 24) {
        // MODERN 24-byte format
        val upCounter = getInt32LE(data, offset + 0)
        val downCounter = getInt32LE(data, offset + 4)
        val rangeTop = getFloatLE(data, offset + 8)
        val rangeBottom = getFloatLE(data, offset + 12)
        val repsRomCount = getUInt16LE(data, offset + 16)
        val repsRomTotal = getUInt16LE(data, offset + 18)
        val repsSetCount = getUInt16LE(data, offset + 20)
        val repsSetTotal = getUInt16LE(data, offset + 22)
        // ...
    } else {
        // LEGACY 6-byte format
        val topCounter = getUInt16LE(data, offset + 0)
        val completeCounter = getUInt16LE(data, offset + 4)
        // ...
    }
}
```

**DISCREPANCY #1 (Type Width)**: The official app reads `up`/`down` as `Int32LE` (4 bytes each), while Phoenix's legacy fallback reads them as `UInt16LE` (2 bytes). The modern path correctly uses `Int32LE`. This matters because the legacy path truncates the counter range from 2^32 to 2^16.

**DISCREPANCY #2 (Sign Treatment)**: The official app reads `repsRomCount`/`repsSetCount` as **signed Short** (`order.getShort()`), while Phoenix reads them as **unsigned UInt16** (`getUInt16LE`). Since these values are small positive counts, this is functionally identical but could cause issues if the firmware ever uses negative sentinel values.

---

## 3. Rep Counting Algorithm

### 3.1 Official App: Firmware-Driven Rep Count

The official app **does NOT count reps client-side**. It reads the rep counts directly from the firmware and uses them as-is. Here is the critical code from the repository class:

**File**: `java-decompiled\sources\Yj\p.java` (lines 148-160, inner class `b`)

```java
// This is the rep count presentation logic
public final Nk.a invoke() {
    p pVar = p.this;
    if (!Am.n.b(pVar.e(), ConnectionState.Connected.INSTANCE)) {
        // disconnected
    }
    if (a.f24063a[pVar.i().ordinal()] == 1) {
        // BASELINE mode: return 0 reps
        return new Nk.a(Nk.c.f12538a, 0, 0.0f);
    }
    int down = pVar.k().getDown();    // Reps.down -- BOTTOM counter
    int d10 = pVar.d();               // repsRomTotal (warmup target)
    H h10 = pVar.f24059x;             // ROM progress (calculated)
    return down < d10
        ? new Nk.a(Nk.c.f12539b, pVar.k().getDown(), ...)  // Still in warmup
        : new Nk.a(Nk.c.f12540c, pVar.k().getDown() - pVar.d(), ...);  // Working reps
}
```

**Critical finding**: The official app computes:
- **Display rep count** = `down - repsRomTotal` (when `down >= repsRomTotal`)
- **Warmup reps** = `down` (when `down < repsRomTotal`)
- Uses `down` counter (NOT `repsSetCount`) for the actual rep number display
- The `repsRomTotal` getter (line 119-121, inner class `a`) returns `repsRomTotal ?? 3` (defaults to 3)

```java
public final Integer invoke() {
    Short repsRomTotal = p.this.k().getRepsRomTotal();
    return Integer.valueOf(repsRomTotal != null ? repsRomTotal.shortValue() : (short) 3);
}
```

### 3.2 Official App: Phase Detection (Concentric vs Eccentric)

**File**: `java-decompiled\sources\Yj\p.java` (lines 225-234, inner class `h`)

```java
public final Yj.b invoke() {
    p pVar = p.this;
    return (pVar.i() == Mode.BASELINE || pVar.k().getDown() < pVar.d())
        ? Yj.b.f23947c  // NONE (baseline or warmup not complete)
        : pVar.k().getDown() == pVar.k().getUp()
            ? Yj.b.f23945a  // CONCENTRIC (down == up: at top, moving down)
            : Yj.b.f23946b; // ECCENTRIC (down != up: at bottom, moving up)
}
```

Where `Yj.b` is the phase enum (file `Yj\b.java` lines 26-31):
```java
"CONCENTRIC", 0  // f23945a
"ECCENTRIC", 1   // f23946b
"NONE", 2        // f23947c
```

**Critical finding**: Phase detection uses only the **directional counters (up vs down)**:
- `up == down` means the machine has completed both the concentric AND eccentric for the same rep. The user is at TOP (ready for next concentric). This is labeled **CONCENTRIC** (confusingly, it means "concentric phase done").
- `up != down` means the up counter has incremented but down hasn't caught up. The user is in the middle of the eccentric phase. This is labeled **ECCENTRIC**.

### 3.3 Phoenix: `RepCounterFromMachine` (More Complex, Client-Side Tracking)

**File**: `shared\src\commonMain\kotlin\com\devil\phoenixproject\domain\usecase\RepCounterFromMachine.kt`

Phoenix has **two code paths**:

**Modern Mode** (lines 360-498):
```kotlin
private fun processModern(repsRomCount: Int, repsSetCount: Int, up: Int, down: Int, ...) {
    // Track UP movement - show PENDING (grey) at TOP
    val upDelta = calculateDelta(lastTopCounter, up)
    if (upDelta > 0) {
        // ... pending rep visual
    }

    // Track DOWN movement - CONFIRM (colored) at BOTTOM
    val downDelta = calculateDelta(lastCompleteCounter, down)

    // WARMUP: warmupReps = repsRomCount (from machine)
    if (repsRomCount > warmupReps && warmupReps < warmupTarget) {
        warmupReps = repsRomCount.coerceAtMost(warmupTarget)
    }

    // WORKING: workingReps = repsSetCount (from machine)
    if (repsSetCount > workingReps) {
        workingReps = repsSetCount
    }
}
```

**Legacy Mode** (lines 279-342):
```kotlin
private fun processLegacy(up: Int, down: Int, posA: Float, posB: Float) {
    val topDelta = calculateDelta(lastTopCounter, up)
    if (topDelta > 0) {
        // Count rep at TOP based on counter increment
        val totalReps = warmupReps + workingReps + 1
        if (totalReps <= warmupTarget) {
            warmupReps++
        } else {
            workingReps++
        }
    }
}
```

### 3.4 Comparison Table: Rep Counting

| Aspect | Official App | Phoenix Modern | Phoenix Legacy |
|---|---|---|---|
| **Rep count source** | `Reps.down` (firmware counter) | `repsSetCount` (firmware) | Client-side delta from `up` |
| **Warmup reps** | `down` when `down < repsRomTotal` | `repsRomCount` (firmware) | Client-side delta from `up` |
| **Working reps** | `down - repsRomTotal` | `repsSetCount` (firmware) | Client count after warmup target |
| **Phase detection** | `up == down` (firmware counters) | Position-based direction detection (5mm threshold) | N/A |
| **Pending rep visual** | `up != down` (grey at top) | `upDelta > 0` triggers WORKING_PENDING | N/A |
| **Rep confirmed** | When `down` increments | When `repsSetCount` increments | When `up` delta > 0 |

**DISCREPANCY #3 (Rep Source)**: The official app uses `down` counter for the display count and derives working reps as `down - repsRomTotal`. Phoenix Modern uses `repsSetCount` directly from the firmware. These SHOULD produce the same result (the firmware calculates them identically), but the derivation path is different.

**DISCREPANCY #4 (Phase Detection Method)**: Official app uses pure counter comparison (`up == down`). Phoenix uses client-side position-based direction detection with a 5mm threshold and rolling window smoothing (lines 597-681). The official app approach is simpler and more reliable since it relies on firmware state.

---

## 4. ROM Detection and Boundaries

### 4.1 Official App: ROM from Firmware

The Reps characteristic provides `rangeTop` and `rangeBottom` directly from firmware. The app uses these for:

**ROM Progress Calculation** (`nk\D.java` line 695-698):

```java
public static final double g(float f10, float f11, double d10) {
    float f12 = f10 - f11;  // range = rangeTop - rangeBottom
    return f12 <= 0.0f
        ? d10 > ((double) f10) ? 1.0d : 0.0d   // edge case: zero range
        : (d10 - f11) / f12;                     // normal: (position - bottom) / range
}
```

This is called from the ROM progress bar computation (`Yj\p.java` lines 260-271, inner class `i`):

```java
public final Float invoke() {
    p pVar = p.this;
    if (a.f24071a[pVar.i().ordinal()] == 1) {
        f10 = 0.0f;  // BASELINE mode: 0 progress
    } else {
        float max = (float) Double.max(
            Gm.o.s(D.g(pVar.k().getRangeTop(), pVar.k().getRangeBottom(),
                        pVar.c().getRight().f42346a), 0.0d, 1.0d),  // right cable position
            Gm.o.s(D.g(pVar.k().getRangeTop(), pVar.k().getRangeBottom(),
                        pVar.c().getLeft().f42346a), 0.0d, 1.0d)    // left cable position
        );
        // Clamped to [0, 1]
        f10 = pVar.k().getDown() == pVar.k().getUp()
            ? max * 0.5f        // CONCENTRIC: half progress
            : 1 - (max * 0.5f); // ECCENTRIC: inverse half progress
    }
    return Float.valueOf(f10);
}
```

**Key ROM logic**: `progress = (cable_position - rangeBottom) / (rangeTop - rangeBottom)` clamped to [0, 1], then modulated by phase (halved/inverted based on up==down).

### 4.2 Official App: Rep Boundary Detection (Client-Side Status)

**File**: `Yj\p.java` (lines 286-346, inner class `j`)

This is the most complex ROM logic. It computes three boolean flags stored in `Yj.u`:

```java
// u(topReady, bottomReady, outsideLow) where:
//   f24084a = topReady (at top boundary)
//   f24085b = bottomReady (at bottom boundary)
//   f24086c = outsideLow (below ROM + safety band)
```

**Modern path (SampleStatus present)** -- lines 291-301:
```java
com.vitruvian.formtrainer.g sampleStatus = pVar.c().getSampleStatus();
if (sampleStatus != null) {
    boolean a10 = sampleStatus.a(g.c.f42583b);  // REP_TOP_READY
    boolean a11 = sampleStatus.a(g.c.f42584c);  // REP_BOTTOM_READY
    sampleStatus.a(g.c.f42576A);                 // ROM_OUTSIDE_HIGH (checked but unused)
    boolean a12 = sampleStatus.a(g.c.f42577B);  // ROM_OUTSIDE_LOW
    // ...
    return new u(a10, a11, a12);
}
```

**Fallback path (no SampleStatus)** -- lines 303-346:
When SampleStatus is not available, the official app computes rep boundaries client-side using `RepConfig` parameters:

```java
// Top boundary detection:
if (Double.max(left.position, right.position) >
    rangeTop - p.b(rangeTop - rangeBottom, repConfig.top.inner)) {
    z10 = true;  // At top of rep
}

// Bottom boundary detection:
if (Double.max(left.position, right.position) <
    p.b(rangeTop - rangeBottom, repConfig.bottom.inner) + rangeBottom) {
    z11 = true;  // At bottom of rep
}

// Outside-low detection (danger zone):
if (Double.max(left.position, right.position) <
    rangeBottom - repConfig.bottom.outer.a(rangeTop - rangeBottom) -
    repConfig.safety.a(rangeTop - rangeBottom)) {
    z12 = true;  // Below safe ROM range
}
```

Where `p.b()` (line 393-394) computes a clamped band value:
```java
public static float b(float f10, L l) {
    return ((Number) Gm.o.y(
        Float.valueOf(f10 * (l.f4141a & 65535) * 0.01f),
        new Gm.e(0.0f, l.f4142b & 65535)
    )).floatValue() * 0.1f;
}
// This is: clamp(range * mmPerM * 0.01, 0, mmMax) * 0.1
```

### 4.3 Phoenix: Client-Side ROM Tracking

Phoenix tracks ROM boundaries entirely client-side through `RepCounterFromMachine`:

```kotlin
// Position tracked at rep events (lines 508-565):
private fun recordTopPosition(posA: Float, posB: Float) {
    topPositionsA.add(posA)
    if (topPositionsA.size > window) topPositionsA.removeAt(0)
    // ... sliding window average
}

private fun updateRepRanges() {
    if (topPositionsA.isNotEmpty()) {
        maxRepPosA = topPositionsA.average().toFloat()
        maxRepPosARange = Pair(topPositionsA.minOrNull()!!, topPositionsA.maxOrNull()!!)
    }
    // ... same for bottom positions
}
```

### 4.4 Comparison Table: ROM Detection

| Aspect | Official App | Phoenix |
|---|---|---|
| **ROM boundaries source** | Firmware `rangeTop`/`rangeBottom` from Reps characteristic | Client-side sliding window average of rep event positions |
| **ROM progress** | `(position - rangeBottom) / (rangeTop - rangeBottom)` | Not computed (ROM boundaries used for danger zone only) |
| **Rep boundary detection** | SampleStatus flags from firmware (REP_TOP_READY, REP_BOTTOM_READY) | Position delta > 5mm threshold with rolling window |
| **Fallback boundary** | Client-side using RepConfig (RepBand with mmPerM/mmMax scaling) | N/A (only one method) |
| **Danger zone** | `ROM_OUTSIDE_LOW` flag OR client calc with safety band | `posA <= minPosA + (range * 0.05)` (5% of range) |

**DISCREPANCY #5 (ROM Source)**: Official app uses firmware-provided `rangeTop`/`rangeBottom` which represent the firmware's understanding of ROM. Phoenix builds its own ROM from observed rep positions (sliding window average). The firmware values are available in the 24-byte Reps packet but Phoenix only uses them for the `RepNotification` data class -- they are NOT used for ROM tracking.

**DISCREPANCY #6 (Danger Zone Calculation)**: Official app uses firmware `ROM_OUTSIDE_LOW` status flag or a complex calculation involving RepBand parameters (mmPerM=200, mmMax=30 for bottom outer band, plus safety band mmPerM=250, mmMax=80). Phoenix uses a simple 5% of observed range threshold.

---

## 5. SampleStatus Bitfield

### 5.1 Official App: `SampleStatus.c` Enum

**File**: `java-decompiled\sources\com\vitruvian\formtrainer\SampleStatus.java` (lines 139-160)

```java
static {
    c cVar  = new c("REP_TOP_READY",    0, He.a.i(s10, 0));   // bit 0:  0x0001
    c cVar2 = new c("REP_BOTTOM_READY", 1, He.a.i(s10, 1));   // bit 1:  0x0002
    c cVar3 = new c("ROM_OUTSIDE_HIGH", 2, He.a.i(s10, 2));   // bit 2:  0x0004
    c cVar4 = new c("ROM_OUTSIDE_LOW",  3, He.a.i(s10, 3));   // bit 3:  0x0008
    c cVar5 = new c("ROM_UNLOAD_ACTIVE",4, He.a.i(s10, 4));   // bit 4:  0x0010
    c cVar6 = new c("SPOTTER_ACTIVE",   5, He.a.i(s10, 5));   // bit 5:  0x0020
    c cVar7 = new c("DELOAD_WARN",      6, He.a.i(s10, 6));   // bit 6:  0x0040
    c cVar8 = new c("DELOAD_OCCURRED",  7, He.a.i(s10, 15));  // bit 15: 0x8000
}
```

Flag checking (line 185-188):
```java
public final boolean isA(c cVar) {
    return ((short) (cVar.f42585a & this.field42573A)) != ((short) 0);
}
```

### 5.2 Phoenix: `SampleStatus` Data Class

**File**: `shared\src\commonMain\kotlin\com\devil\phoenixproject\domain\model\SampleStatus.kt`

```kotlin
companion object {
    const val REP_TOP_READY = 1 shl 0       // 0x0001
    const val REP_BOTTOM_READY = 1 shl 1    // 0x0002
    const val ROM_OUTSIDE_HIGH = 1 shl 2    // 0x0004
    const val ROM_OUTSIDE_LOW = 1 shl 3     // 0x0008
    const val ROM_UNLOAD_ACTIVE = 1 shl 4   // 0x0010
    const val SPOTTER_ACTIVE = 1 shl 5      // 0x0020
    const val DELOAD_WARN = 1 shl 6         // 0x0040
    const val DELOAD_OCCURRED = 1 shl 15    // 0x8000
}
```

**MATCH**: Phoenix SampleStatus bit definitions exactly match the official app.

### 5.3 Official App Usage of SampleStatus for Rep Gating

The official app uses `REP_TOP_READY` and `REP_BOTTOM_READY` status flags as the **primary** indicator of rep boundary positions. This is visible in `Yj\p.java` inner class `j` (line 291-301):

```java
// When SampleStatus is available, it is the PREFERRED method:
if (sampleStatus != null) {
    boolean topReady = sampleStatus.a(g.c.f42583b);    // REP_TOP_READY
    boolean bottomReady = sampleStatus.a(g.c.f42584c); // REP_BOTTOM_READY
    boolean outsideLow = sampleStatus.a(g.c.f42577B);  // ROM_OUTSIDE_LOW
    return new u(topReady, bottomReady, outsideLow);
}
// Only falls through to client-side calculation when SampleStatus is null
```

**DISCREPANCY #7**: Phoenix reads and processes `ROM_OUTSIDE_HIGH`, `ROM_OUTSIDE_LOW`, `DELOAD_*`, and `SPOTTER_ACTIVE` from the Monitor characteristic status bytes. But it does **NOT** use `REP_TOP_READY` or `REP_BOTTOM_READY` for rep boundary detection. Instead, it relies entirely on directional counter deltas and position-based phase detection. The official app uses these firmware flags as the primary (preferred) rep boundary indicator.

---

## 6. Sample (Monitor) Characteristic Parsing

### 6.1 Official App: `Sample.Characteristic.read()` (12+ bytes)

**File**: `java-decompiled\sources\com\vitruvian\formtrainer\Sample.java` (lines 76-83)

```java
public Sample read(byte[] bytes) {
    if (bytes.length == 0) return null;
    ByteBuffer order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    return new Sample(
        new Cable(order.getShort() / 10.0d,    // posA / 10 for mm
                  order.getShort() / 10.0d,     // velA / 10 for mm/s
                  order.getShort() / 100.0d),   // forceA / 100 for percentage
        new Cable(order.getShort() / 10.0d,     // posB / 10
                  order.getShort() / 10.0d,     // velB / 10
                  order.getShort() / 100.0d),   // forceB / 100
        order.getInt(),                          // time (ticks)
        order.remaining() >= 2
            ? new DeviceScreen_Lambda_G(order.getShort())  // SampleStatus (16-bit)
            : null,
        null, null, null, null);
}
```

**Cable fields** (`Cable.java` toString, line 240):
- `field42346A` = `position` (mm, from raw / 10.0)
- `field42347B` = `velocity` (mm/s, from raw / 10.0)
- `field42348C` = `force` (percentage 0-100, from raw / 100.0)

### 6.2 Phoenix: `parseMonitorPacket()` (Different Layout)

**File**: `shared\src\commonMain\kotlin\com\devil\phoenixproject\data\ble\ProtocolParser.kt` (lines 180-212)

```kotlin
fun parseMonitorPacket(data: ByteArray): MonitorPacket? {
    if (data.size < 16) return null
    val f0 = getUInt16LE(data, 0)     // ticks low
    val f1 = getUInt16LE(data, 2)     // ticks high
    val posARaw = getInt16LE(data, 4) // Signed 16-bit for position
    val loadARaw = getUInt16LE(data, 8)
    val posBRaw = getInt16LE(data, 10)
    val loadBRaw = getUInt16LE(data, 14)
    val ticks = f0 + (f1 shl 16)
    val posA = posARaw / 10.0f        // mm
    val posB = posBRaw / 10.0f        // mm
    val loadA = loadARaw / 100.0f     // kg (NOT percentage!)
    val loadB = loadBRaw / 100.0f     // kg
    val status = if (data.size >= 18) getUInt16LE(data, 16) else 0
    // ...
}
```

**DISCREPANCY #8 (Monitor Packet Layout)**: The two apps parse DIFFERENT byte layouts:

| Bytes | Official App | Phoenix |
|---|---|---|
| 0-1 | posA raw (/10 for mm) | ticks low |
| 2-3 | velA raw (/10 for mm/s) | ticks high |
| 4-5 | forceA raw (/100 for %) | posA raw (/10 for mm) |
| 6-7 | posB raw | (gap) |
| 8-9 | velB raw | loadA raw (/100 for kg) |
| 10-11 | forceB raw | posB raw |
| 12-15 | time (Int32) | (gap) |
| 14-15 | -- | loadB raw |
| 16-17 | SampleStatus (Int16) | status flags |

This suggests the official app and Phoenix are reading **different BLE characteristics** or interpreting the same characteristic differently. The official `Sample.Characteristic` reads position+velocity+force for BOTH cables (6 shorts = 12 bytes for cable data + 4 bytes time + 2 bytes status = 18 bytes). Phoenix reads ticks (4 bytes) + posA (2) + gap (2) + loadA (2) + posB (2) + gap (2) + loadB (2) + status (2) = 18 bytes but with a DIFFERENT field arrangement.

**DISCREPANCY #9 (Force vs Load Units)**: Official app: force as percentage (0-100%). Phoenix: load as kg (raw/100). These are fundamentally different units representing different physical quantities.

**DISCREPANCY #10 (Velocity Source)**: Official app gets velocity directly from the firmware (`Cable.velocity`). Phoenix calculates velocity client-side from position deltas with EMA smoothing (alpha=0.3). The official app's firmware velocity is likely more accurate since it has higher-resolution internal data.

### 6.3 Sample Validation

**Official App** (`Sample.java` lines 317-340):
```java
public final boolean getValid() {
    Cable cable = this.left;
    double d10 = cable.f42348c;  // force
    if (0.0d <= d10 && d10 <= 100.0d) {       // Force: 0-100%
        Cable cable2 = this.right;
        double d11 = cable2.f42348c;
        if (0.0d <= d11 && d11 <= 100.0d) {
            double d12 = cable.f42346a;         // Position: -1000 to +1000
            if (-1000.0d <= d12 && d12 <= 1000.0d) {
                double d13 = cable2.f42346a;
                if (-1000.0d <= d13 && d13 <= 1000.0d) {
                    double d14 = cable.f42347b;  // Velocity: -1000 to +1000
                    if (-1000.0d <= d14 && d14 <= 1000.0d) {
                        double d15 = cable2.f42347b;
                        if (-1000.0d <= d15 && d15 <= 1000.0d) {
                            return true;
                        }
                    }
                }
            }
        }
    }
    return false;
}
```

**Phoenix** (`MonitorDataProcessor.kt` lines 302-333):
```kotlin
private fun validateSample(...): Boolean {
    // Position range check
    if (posA !in MIN_POS..MAX_POS || posB !in MIN_POS..MAX_POS) return false
    // Load validation
    if (loadA < 0f || loadA > MAX_WEIGHT_KG || loadB < 0f || loadB > MAX_WEIGHT_KG) return false
    // Position jump filter (>20mm between samples)
    if (strictValidationEnabled && lastTimestamp > 0L) {
        val jumpA = abs(posA - previousPosA)
        val jumpB = abs(posB - previousPosB)
        if (jumpA > POSITION_JUMP_THRESHOLD || jumpB > POSITION_JUMP_THRESHOLD) return false
    }
    return true
}
```

**DISCREPANCY #11**: Phoenix has a **position jump filter** (20mm max delta between samples) that the official app does NOT have. This is a Phoenix-specific BLE noise mitigation. The official app validates force [0, 100], position [-1000, 1000], and velocity [-1000, 1000] ranges only.

---

## 7. Heuristic Characteristic (Per-Rep Statistics)

### 7.1 Official App: `Heuristic.Characteristic.read()` (48+ bytes)

**File**: `java-decompiled\sources\com\vitruvian\formtrainer\Heuristic.java` (lines 46-79)

```java
private final d readPhaseStatistics(ByteBuffer buffer) {
    return new methodD(
        buffer.getFloat(),   // kgAvg
        buffer.getFloat(),   // kgMax
        buffer.getFloat(),   // velAvg
        buffer.getFloat(),   // velMax
        buffer.getFloat(),   // wattAvg
        buffer.getFloat()    // wattMax
    );
}

private final e readStatistics(ByteBuffer buffer) {
    return new DeviceScreenBody_Lambda_1_1_1_2_1(
        readPhaseStatistics(buffer),  // concentric
        readPhaseStatistics(buffer)   // eccentric
    );
}

public Heuristic read(byte[] bytes) {
    if (bytes.length == 0) return null;
    ByteBuffer order = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    DeviceScreen_Lambda_N.methodD(order);  // Skip first 4 bytes? (version/header)
    return new Heuristic(readStatistics(order), readStatistics(order));  // left, right
}
```

**Structure**: `Heuristic(left: HeuristicStatistics, right: HeuristicStatistics)` where each `HeuristicStatistics` has `concentric: HeuristicPhaseStatistics` and `eccentric: HeuristicPhaseStatistics`, each with 6 floats (kgAvg, kgMax, velAvg, velMax, wattAvg, wattMax).

**Total**: 2 cables x 2 phases x 6 floats x 4 bytes = 96 bytes of data (plus possible header).

### 7.2 Phoenix: `parseHeuristicPacket()` (48 bytes, single cable)

**File**: `shared\src\commonMain\kotlin\com\devil\phoenixproject\data\ble\ProtocolParser.kt` (lines 266-294)

```kotlin
fun parseHeuristicPacket(data: ByteArray, timestamp: Long): HeuristicStatistics? {
    if (data.size < 48) return null
    val concentric = HeuristicPhaseStatistics(
        kgAvg = getFloatLE(data, 0), kgMax = getFloatLE(data, 4),
        velAvg = getFloatLE(data, 8), velMax = getFloatLE(data, 12),
        wattAvg = getFloatLE(data, 16), wattMax = getFloatLE(data, 20))
    val eccentric = HeuristicPhaseStatistics(
        kgAvg = getFloatLE(data, 24), kgMax = getFloatLE(data, 28),
        velAvg = getFloatLE(data, 32), velMax = getFloatLE(data, 36),
        wattAvg = getFloatLE(data, 40), wattMax = getFloatLE(data, 44))
    return HeuristicStatistics(concentric = concentric, eccentric = eccentric)
}
```

**DISCREPANCY #12 (Heuristic Structure)**: Official app parses **left and right** cable statistics (two HeuristicStatistics objects, one per cable), with a possible header/version prefix. Phoenix parses only a single `HeuristicStatistics` (one concentric + one eccentric) from 48 bytes. The official app structure is `Heuristic(left: Stats, right: Stats)` where each Stats has concentric+eccentric.

**DISCREPANCY #13 (Header Skip)**: The official app calls `DeviceScreen_Lambda_N.methodD(order)` before reading statistics, which likely skips a version/header prefix. Phoenix reads from byte 0.

---

## 8. RegularPacket / EchoPacket (Mode Commands Sent TO Device)

### 8.1 Official App: `RegularPacket` and `EchoPacket`

Both packet types include `romRepCount` and `repCount` as **bytes** sent to the device:

**RegularPacket** (`RegularPacket.java` lines 31-38):
```java
// Sent TO the device as part of workout configuration
c1511h2.f(P.f4162D);                    // Command opcode (REGULAR)
c1511h2.a(regularPacket.romRepCount);    // ROM rep count (byte)
c1511h2.a(regularPacket.repCount);       // Set rep count (byte)
c1511h2.e(regularPacket.getMode());      // Force mode config (RegularForceConfig)
```

Default `romRepCount` is 3 (line 130):
```java
public RegularPacket(byte b9, byte b10, K k10, int i10, C1250g c1250g) {
    this((i10 & 1) != 0 ? (byte) 3 : b9, b10, k10, null);
}
```

**Key insight**: The app tells the firmware how many warmup reps to use (`romRepCount`, default 3) and the target rep count (`repCount`). The firmware then manages the counting internally and reports back through the Reps characteristic.

---

## 9. RepConfig (Sent TO Device for Rep Detection Parameters)

### 9.1 Official App: `RepConfig` (Ek.N)

**File**: `java-decompiled\sources\Ek\N.java` (lines 46-48)

```java
public static N a(int i10, float f10) {
    return new N(
        new O((byte) Gm.o.u(i10 + 3, 0, 255)),  // RepCounts: total = repTarget + 3
        f10,                                       // seedRange
        new M(new L((short) 250, (short) 250),     // top RepBound inner
              new L((short) 200, (short) 30)),      // top RepBound outer
        new M(new L((short) 250, (short) 250),      // bottom RepBound inner
              new L((short) 200, (short) 30)),       // bottom RepBound outer
        new L((short) 250, (short) 80)              // safety RepBand
    );
}
```

**RepConfig substructure**:
- **RepCounts** (`O`): total=target+3 (warmup), baseline=3, adaptive=3
- **RepBound top** (`M`): threshold=5.0, drift=0.0, inner=(250mm/m, 250mm max), outer=(200mm/m, 30mm max)
- **RepBound bottom** (`M`): threshold=5.0, drift=0.0, inner=(250mm/m, 250mm max), outer=(200mm/m, 30mm max)
- **Safety band** (`L`): mmPerM=250, mmMax=80

**RepBand.a()** calculation (`L.java` line 54-56):
```java
public final float a(float f10) {
    // f10 = ROM range (rangeTop - rangeBottom)
    // result = clamp(range * mmPerM * 0.01, 0, mmMax) * 0.1
    return clamp(f10 * (this.f4141a & 65535) * 0.01f, 0.0f, this.f4142b & 65535) * 0.1f;
}
```

For the safety band (mmPerM=250, mmMax=80):
- With a 300mm ROM range: `clamp(300 * 250 * 0.01, 0, 80) * 0.1 = clamp(750, 0, 80) * 0.1 = 8.0mm`
- The danger zone is 8mm below `rangeBottom`

**Phoenix has NO equivalent** to RepConfig. It does not send rep detection parameters to the device. This means Phoenix relies entirely on whatever default parameters the firmware uses or whatever was last configured by the official app.

---

## 10. Critical Gaps and Actionable Recommendations

### Gap 1: Phoenix Ignores Firmware ROM Boundaries (HIGH PRIORITY)

**Finding**: The official app uses `rangeTop` and `rangeBottom` from the Reps characteristic for ROM progress visualization and boundary detection. Phoenix parses these values but only stores them in `RepNotification` -- they are never used for ROM tracking or UI.

**Recommendation**: Use firmware-provided `rangeTop`/`rangeBottom` as the authoritative ROM boundaries instead of (or in addition to) the client-side sliding window. The firmware has sub-millisecond position data and better ROM tracking algorithms than any client-side approximation.

### Gap 2: Phoenix Does Not Use REP_TOP_READY / REP_BOTTOM_READY (MEDIUM PRIORITY)

**Finding**: The official app prefers `REP_TOP_READY` and `REP_BOTTOM_READY` status flags for visual rep boundary indicators. Phoenix ignores these flags entirely, relying on position-based direction detection.

**Recommendation**: When `SampleStatus` is available (Monitor characteristic bytes 16-17), use `REP_TOP_READY` and `REP_BOTTOM_READY` for rep phase detection. Fall back to position-based detection only when status flags are unavailable.

### Gap 3: Monitor Packet Layout Mismatch (CRITICAL - INVESTIGATE)

**Finding**: The official app's `Sample.Characteristic` reads 6 shorts (posA, velA, forceA, posB, velB, forceB) followed by Int32 time and optional Int16 status. Phoenix reads 4 shorts (ticksLow, ticksHigh, posA, [gap]), then loadA, posB, [gap], loadB, status.

**Recommendation**: Capture raw BLE packets and verify which layout is correct. The official app has been validated against the actual hardware. Phoenix may be reading a DIFFERENT characteristic or an older protocol version. The discrepancy in velocity (firmware-provided vs client-calculated) and force units (percentage vs kg) suggest a fundamental parsing difference.

### Gap 4: RepConfig Not Sent (MEDIUM PRIORITY)

**Finding**: The official app sends `RepConfig` to the firmware with specific rep detection parameters (RepBounds, safety bands, seed range). Phoenix does not send any equivalent configuration.

**Recommendation**: Implement RepConfig sending when starting a workout. Without this, the firmware uses default/last-configured parameters which may not match the intended workout configuration.

### Gap 5: Heuristic Parsing Incomplete (LOW PRIORITY)

**Finding**: Official app parses heuristic data for BOTH cables (left and right). Phoenix parses only one set of concentric/eccentric statistics.

**Recommendation**: Update `parseHeuristicPacket()` to handle the full structure: header prefix, left cable stats, right cable stats.

### Gap 6: Phase Detection Differs (LOW PRIORITY)

**Finding**: Official app uses simple counter comparison (`up == down`) for phase. Phoenix uses client-side position tracking with 5mm threshold and rolling window. The official approach is more reliable since it uses firmware state.

**Recommendation**: Consider adding counter-based phase detection as the primary method and keeping position-based detection as supplementary data for the animated rep counter.

---

## 11. Summary Comparison Matrix

| Feature | Official App | Phoenix | Match? |
|---|---|---|---|
| BLE UUIDs | 5 characteristics | Same 5 characteristics | YES |
| Reps packet parsing | 24-byte with Int32 counters | 24-byte with Int32 counters (modern) | YES |
| Reps legacy fallback | Int32 but nullable Short fields | UInt16 counters only | PARTIAL |
| Rep count source | `down` counter minus warmup | `repsSetCount` direct | DIFFERENT PATH |
| Warmup reps | `down` when `< repsRomTotal` | `repsRomCount` direct | DIFFERENT PATH |
| Default warmup target | 3 (hardcoded fallback) | 3 (configurable) | YES |
| Phase detection | `up == down` counter comparison | Position-based (5mm threshold) | DIFFERENT |
| ROM boundaries | Firmware `rangeTop`/`rangeBottom` | Client-side sliding window | DIFFERENT |
| ROM progress calc | `(pos - bottom) / (top - bottom)` | Not implemented | MISSING |
| Danger zone | Firmware `ROM_OUTSIDE_LOW` flag + RepBand calc | 5% of observed range | DIFFERENT |
| SampleStatus flags | All 8 flags used for rep gating | 6 flags used (not REP_TOP/BOTTOM) | PARTIAL |
| Monitor data layout | 6 shorts + int32 + int16 | Different field arrangement | INVESTIGATE |
| Velocity source | Firmware-provided (Cable.velocity) | Client-calculated EMA | DIFFERENT |
| Force units | Percentage (0-100%) | Kilograms | DIFFERENT |
| Heuristic parsing | Dual cable (left+right) with header | Single cable, no header | PARTIAL |
| RepConfig sent | Yes (RepBounds, safety, seed range) | No | MISSING |
| Position jump filter | No | Yes (20mm threshold) | PHOENIX EXTRA |
| EMA velocity smoothing | No (firmware velocity) | Yes (alpha=0.3) | PHOENIX EXTRA |

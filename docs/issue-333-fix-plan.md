# Issue #333 — Pixel 6/7 GATT_ERROR(133) Fix Plan

> **Status:** Ready for Implementation  
> **Issue:** [Project-Phoenix-MP #333](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/333) / [VitruvianProjectPhoenix #170](https://github.com/9thLevelSoftware/VitruvianProjectPhoenix/issues/170)  
> **Affected Devices:** Pixel 6, 6 Pro, 6a, 7, 7 Pro, Pixel Fold (all BCM4389 / Tensor G1-G2)  
> **Unaffected Devices:** Pixel 8+, Samsung Galaxy, all non-BCM4389 devices  
> **Prior Attempts:** 14 test builds, 27+ flag combinations — all failed  
> **Date:** 2026-06-06

---

## Executive Summary

After analyzing 78 comments across two GitHub issues spanning 7+ months, decompiling the official Vitruvian app, examining the third-party web app source, and auditing the full Phoenix BLE stack, we identified a critical untested variable:

**Every single test build sent a 34-byte LED color write (opcode `0x11`) immediately on connection. This opcode does NOT exist in the official Vitruvian protocol (which uses `0x43` for color scheme). This write has never been suppressed or changed in any experiment.**

The official Vitruvian app works on Pixel 6/7 because it:
1. Never sends opcode `0x11`
2. Sends zero writes between connect and CONFIG
3. Uses zero polling (notification-only)
4. Keeps the GATT write lane pristine when CONFIG fires

This plan tests each contributing factor in isolation, building from the highest-confidence fix to the lowest, with clear pass/fail criteria at every stage.

---

## Testing Infrastructure

### Test Devices Required

| Tester | Device | Android | BT Controller | Role |
|--------|--------|---------|---------------|------|
| Primary | Pixel 6 OR Pixel 7 Pro | 16 (API 36) | BCM4389 | Must-fail device |
| Control | Samsung Galaxy OR Pixel 8+ | Any | Non-BCM4389 | Regression check |

### Standard Test Protocol

Every phase uses this procedure unless stated otherwise:

1. **Clean install** — Uninstall any existing Phoenix APK, clear Bluetooth cache (Settings → Apps → Bluetooth → Storage → Clear Cache), restart Bluetooth
2. **Cold machine** — Power-cycle the Vitruvian trainer before each test session
3. **Foreground only** — Keep the app in the foreground, screen on, during all tests
4. **Battery unrestricted** — Settings → Apps → Phoenix → Battery → Unrestricted
5. **Exercise selection** — Use "Just Lift" with any exercise, Old School mode, any weight
6. **3 consecutive attempts** — Each test configuration must be tried 3 times minimum
7. **Capture logs** — Export connection logs after each attempt AND capture `adb logcat` with filter: `adb logcat -s "KableBle","BleQueue","BlePacket","MetricPolling","#333" > test_phase_X.log`

### Pass/Fail Criteria

| Result | Definition |
|--------|-----------|
| **PASS** | Workout starts successfully, cables engage with correct weight, at least 3 reps are counted, on ALL 3 consecutive attempts |
| **PARTIAL** | Workout starts on some attempts but not all, OR cables engage but reps aren't counted, OR disconnects mid-workout after successful start |
| **FAIL** | Workout never starts — GATT_ERROR(133), "Write failed: Unknown", connection timeout, or immediate disconnect on all 3 attempts |
| **REGRESSION** | A previously working device (Samsung/Pixel 8+) now fails |

### Logging Requirements

Each test build MUST include these log tags for diagnosis:

```kotlin
// Tag all Phase-specific changes
Log.d("#333", "[Phase X] <description of what's happening>")
```

All builds MUST log:
- Whether the LED color write (0x11) is suppressed or changed
- The exact sequence and timing of every GATT write between connect and CONFIG
- The MTU negotiation result
- The notification subscription completion times
- The CONFIG write attempt, duration, and result

---

## Phase 1: Suppress the LED Color Write (Highest Confidence)

### Hypothesis

The 34-byte opcode `0x11` write that fires immediately on `ConnectionState.Connected` is corrupting the BCM4389's GATT write lane. Removing it will allow CONFIG to succeed.

### Confidence Level: HIGH

- This is the ONE variable never tested across 14 builds
- Opcode `0x11` is NOT in the official Vitruvian protocol
- The official app (which works on Pixel 6/7) never sends this command
- FossForUs1's v9 logs show `0x11` failing FIRST, then CONFIG failing as a cascade
- The web app also sends `0x11` and also fails on Pixel 6/7

### Implementation

**File:** `androidApp/src/main/kotlin/.../presentation/manager/BleConnectionManager.kt` (or equivalent presentation-layer connection observer)

**Change:** In the `ConnectionState.Connected` handler (around line 96-103), suppress the LED color scheme restore:

```kotlin
// BEFORE (current code):
ConnectionState.Connected -> {
    val savedColorScheme = settingsManager.userPreferences.value.colorScheme
    bleRepository.setColorScheme(savedColorScheme)
}

// AFTER (Phase 1):
ConnectionState.Connected -> {
    Log.d("#333", "[Phase 1] Suppressing LED color write (0x11) on connect")
    // LED color scheme restore suppressed for #333 investigation
    // Will be re-evaluated in Phase 3 with correct opcode 0x43
}
```

**No other changes.** All polling, heartbeat, notifications, MTU negotiation remain exactly as-is. This isolates the single variable.

### What This Tests

- Does removing the opcode `0x11` write alone fix the bug?
- Is the GATT write lane clean enough for CONFIG without the 0x11 poisoning it?

### Expected Outcome

- **If PASS:** The `0x11` write was the primary trigger. Proceed to Phase 3 (proper color scheme with `0x43`).
- **If PARTIAL:** The `0x11` removal helps but polling/heartbeat pressure is a secondary factor. Proceed to Phase 2.
- **If FAIL:** The `0x11` write is not the sole trigger — GATT queue saturation from polling is also required. Proceed to Phase 2.

### Regression Check

- Test on Samsung/Pixel 8+ — workout start must still work (LED colors won't be set on connect, which is cosmetic only)

---

## Phase 2: Zero-Write Initialization (Official App Pattern)

### Hypothesis

The BCM4389 needs a pristine GATT write lane when CONFIG fires. Matching the official app's pattern of zero writes and zero polling before CONFIG will fix the bug.

### Confidence Level: HIGH

- The official app uses exactly this pattern and works on Pixel 6/7
- This addresses both the `0x11` write AND the GATT queue saturation from polling

### Implementation

**Phase 2 builds on Phase 1** (LED write already suppressed) and adds:

**File:** `KableBleConnectionManager.kt`

**Change 2a — Defer polling until after first CONFIG:**

```kotlin
// In startObservingNotifications() or onDeviceReady():
// Do NOT call pollingEngine.startAll(peripheral) immediately

Log.d("#333", "[Phase 2] Deferring polling start until after first CONFIG write")
// Instead, store the peripheral reference and expose a method:
fun startDeferredPolling() {
    Log.d("#333", "[Phase 2] Starting deferred polling after CONFIG success")
    pollingEngine.startAll(storedPeripheral!!)
}
```

**Change 2b — Defer heartbeat until after first CONFIG:**

```kotlin
// In MetricPollingEngine.startAll() or startHeartbeat():
// Add a gate that prevents heartbeat from starting until explicitly enabled
Log.d("#333", "[Phase 2] Heartbeat deferred until after CONFIG success")
```

**Change 2c — Start polling/heartbeat after CONFIG succeeds:**

```kotlin
// In ActiveSessionEngine.startWorkout() or sendWorkoutCommand(), after successful CONFIG write:
if (configWriteSucceeded) {
    Log.d("#333", "[Phase 2] CONFIG succeeded — starting deferred polling and heartbeat")
    connectionManager.startDeferredPolling()
}
```

**Change 2d — Match official app's notification subscriptions:**

Subscribe to the same 3 characteristics as the official app:
- Mode (`67d0dae0-5bfc-4ea2-acc9-ac784dee7f29`) ✅ already subscribed
- Reps (`8308f2a6-0875-4a94-a86f-5c5c5e1b068a`) ✅ already subscribed  
- DiagnosticDetails (`5fa538ec-d041-42f6-bbd6-c30d475387b7`) ← **ADD this**
- VERSION (`74e994ac-0e80-4c02-9cd0-76cb31d3959b`) ← **REMOVE this** (official app reads it, doesn't subscribe)

### What This Tests

- Does matching the official app's zero-write, zero-poll pattern before CONFIG fix the bug?
- Is the combination of no `0x11` + no polling + no heartbeat sufficient?

### Expected Outcome

- **If PASS:** The BCM4389 needs a pristine GATT write lane. The fix is to defer all non-essential GATT activity until after CONFIG. Proceed to Phase 3 for proper LED color support.
- **If FAIL:** Something else differs between Phoenix and the official app at the GATT/controller level. Proceed to Phase 4 (HCI snoop comparison).

### Regression Check

- Test on Samsung/Pixel 8+ — all features must still work
- Verify that polling starts correctly after CONFIG succeeds
- Verify that rep counting works once polling is active
- Verify that heartbeat resumes and keeps the connection alive during rest periods

### Testing Variations

If Phase 2 passes, test these sub-variations to find the MINIMUM required change:

| Sub-test | LED suppressed | Polling deferred | Heartbeat deferred | Notifications changed |
|----------|:-:|:-:|:-:|:-:|
| 2-Full | ✅ | ✅ | ✅ | ✅ |
| 2a | ✅ | ✅ | ❌ | ❌ |
| 2b | ✅ | ❌ | ✅ | ❌ |
| 2c | ✅ | ❌ | ❌ | ✅ |

This isolates which combination is actually required on BCM4389.

---

## Phase 3: Correct Color Scheme Opcode (0x43)

### Hypothesis

The official Vitruvian protocol uses opcode `0x43` (67) for `DeviceColorSchemePacket`, not `0x11` (17). Using the correct opcode may allow the color write to succeed on BCM4389, restoring LED color functionality.

### Confidence Level: MEDIUM

- The official app uses `0x43` and works on Pixel 6/7
- However, we don't know when the official app sends its color scheme (on connect? on user action only?)
- The `0x43` packet format may differ from Phoenix's current `0x11` format

### Prerequisites

- Phase 1 or Phase 2 must PASS first (confirming `0x11` removal fixes the workout start)
- Decompiled official app's `DeviceColorSchemePacket` format must be analyzed

### Implementation

**Step 3a — Analyze the official app's 0x43 packet format:**

**File:** Examine `C:\Users\dasbl\AndroidStudioProjects\VitruvianDeobfuscated\` for `DeviceColorSchemePacket` or command ID 67/0x43.

Determine:
- Exact byte layout of the `0x43` packet
- Packet size
- What data it carries (brightness, RGB values, etc.)
- When the official app sends it (on connect? on settings change? before workout?)

**Step 3b — Implement the correct packet format:**

**File:** `BlePacketFactory.kt`

```kotlin
// Add new function matching official protocol:
fun createOfficialColorScheme(schemeIndex: Int): ByteArray {
    // Use opcode 0x43 instead of 0x11
    // Match official app's DeviceColorSchemePacket byte layout
    Log.d("#333", "[Phase 3] Building color scheme with official opcode 0x43")
    // ... implementation based on decompiled format
}
```

**Step 3c — Choose when to send the color scheme:**

Three sub-tests:

| Sub-test | When color scheme is sent |
|----------|--------------------------|
| 3a | **After CONFIG succeeds** — safest, defers all writes until GATT is proven working |
| 3b | **On connect, but after a 1-second settling period** — tests whether 0x43 is safe on a settled connection |
| 3c | **On connect, immediately (like current behavior)** — tests whether the opcode alone was the issue |

### What This Tests

- Is opcode `0x43` accepted by the machine where `0x11` was not?
- Can LED colors be restored on Pixel 6/7 without breaking CONFIG?
- What timing is safe for the color write on BCM4389?

### Expected Outcome

- **If 3a PASS:** Color scheme works when sent after CONFIG. Keep this as the safe default for BCM4389.
- **If 3b PASS:** BCM4389 can handle the color write on connect if given settling time and correct opcode.
- **If 3c PASS:** The opcode alone was the issue — the machine was rejecting `0x11` and BCM4389 mishandled the rejection.
- **If ALL FAIL:** The BCM4389 cannot handle any writes before CONFIG, regardless of opcode. Color must always be deferred.

### Regression Check

- Verify LED colors actually change on the machine with opcode `0x43`
- Test on Samsung/Pixel 8+ — both opcodes should work (the machine likely accepts both on controllers that handle errors gracefully)

---

## Phase 4: GATT Settling Period

### Hypothesis

The BCM4389 needs a period of GATT silence after notification subscriptions before the write lane is ready. The official app gets this naturally (user navigation time). Phoenix fires writes immediately.

### Confidence Level: MEDIUM

- The web app fails with only 50ms between writes after notification subscriptions
- The official app has seconds to minutes of idle time before CONFIG
- v4's success (53ms CONFIG) had polling quiesced, giving an artificial settling window

### Prerequisites

- Phase 2 must PASS (confirming the official app pattern works)

### Implementation

**File:** `KableBleConnectionManager.kt`

**Change:** After notification subscriptions complete, insert a configurable dead period before marking the connection as ready:

```kotlin
// After all notification subscriptions complete:
val settlingDelayMs = if (DeviceInfo.isPixel()) {
    PIXEL_GATT_SETTLING_MS  // Configurable: test with 250, 500, 1000, 2000
} else {
    0L  // Non-Pixel devices don't need settling
}

if (settlingDelayMs > 0) {
    Log.d("#333", "[Phase 4] GATT settling period: ${settlingDelayMs}ms — no GATT operations")
    delay(settlingDelayMs)
    Log.d("#333", "[Phase 4] Settling complete — GATT write lane should be ready")
}
```

### Testing Matrix

| Sub-test | Settling delay | LED write | Polling |
|----------|:---:|:---:|:---:|
| 4a | 250ms | Suppressed | Deferred |
| 4b | 500ms | Suppressed | Deferred |
| 4c | 1000ms | Suppressed | Deferred |
| 4d | 500ms | 0x43 after settling | Deferred |
| 4e | 500ms | 0x43 after settling | Starts after settling |

### What This Tests

- How much settling time does BCM4389 need?
- Can polling start during the settling period or must it wait?
- Can the color write (0x43) be sent during/after settling?

### Expected Outcome

- Determines the minimum settling delay for reliable CONFIG writes on BCM4389
- Determines whether polling can resume before CONFIG or must wait until after

### Pass/Fail

Same as standard protocol. Additionally, measure and log the time between the last notification subscription completion and the CONFIG write attempt to establish the minimum safe interval.

---

## Phase 5: HCI Snoop Log Comparison (Diagnostic)

### Hypothesis

An HCI-level packet trace comparing the official app and Phoenix will reveal the exact controller-level difference that causes BCM4389 to fail.

### Confidence Level: N/A (Diagnostic, not a fix)

### When to Execute

- If Phase 2 FAILS — we need deeper insight into what the official app does differently
- Or after Phase 2 PASSES — to understand WHY the fix works at the controller level, for documentation

### Implementation

**On a Pixel 7 Pro (or any affected BCM4389 device):**

1. **Enable HCI snoop log:**
   - Settings → System → Developer Options → Enable Bluetooth HCI snoop log
   - Toggle Bluetooth off/on to start fresh capture

2. **Capture official app trace:**
   - Open official Vitruvian app
   - Connect to machine
   - Start a workout (any exercise, any mode)
   - Complete 3 reps
   - Stop workout
   - Disconnect

3. **Capture Phoenix trace:**
   - Open Phoenix app (Phase 2 build if available, else main branch)
   - Connect to machine
   - Start a workout (same exercise, same mode)
   - Wait for GATT_ERROR(133) or complete 3 reps
   - Disconnect

4. **Extract HCI log:**
   ```bash
   adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
   ```

5. **Analyze in Wireshark:**
   - Filter: `btatt` (ATT protocol)
   - Compare the exact ATT PDU sequence between the two captures
   - Focus on:
     - Write Request/Response PDUs (opcodes, sizes, timing)
     - Error Response PDUs (any ATT errors from the peripheral?)
     - MTU Exchange
     - Notification subscription (CCCD writes)
     - Time gaps between operations

### What to Look For

| Question | Where to look in trace |
|----------|----------------------|
| Does the machine send an ATT Error Response to opcode 0x11? | Filter `btatt.opcode == 0x01` (Error Response) |
| What opcode does the official app use for CONFIG? | Filter `btatt.opcode == 0x12` (Write Request), look at value bytes |
| Does the official app send ANY writes before CONFIG? | Count all Write Request PDUs before the 96-byte write |
| What's the time gap between last notification subscription and CONFIG? | Timestamp difference |
| Does the official app use Write Request or Write Command? | Check ATT opcode (0x12 = Request, 0x52 = Command) |
| Is there any MTU difference? | Filter `btatt.opcode == 0x02` (MTU Exchange) |

### Deliverable

A comparison table showing the exact ATT PDU sequence for both apps, with timing, to definitively document why the official app succeeds and Phoenix fails on BCM4389.

---

## Phase 6: Production Implementation

### Prerequisites

- At least Phase 1 or Phase 2 must PASS on ALL affected devices (Pixel 6, 6 Pro, 7, 7 Pro)
- Must not REGRESS on any previously working device
- Phase 3 results determine how LED colors are handled

### Implementation Plan

Based on which phases pass, implement the minimum required changes:

**Scenario A: Phase 1 alone passes (only LED suppression needed)**

```
1. Remove opcode 0x11 write on connect
2. Implement opcode 0x43 color scheme (Phase 3) sent AFTER CONFIG
3. No changes to polling or heartbeat
4. Gate: Pixel-only (DeviceInfo.isPixel()) to minimize risk
```

**Scenario B: Phase 2 required (full official app pattern)**

```
1. Remove opcode 0x11 write on connect
2. Defer polling start until after first CONFIG succeeds
3. Defer heartbeat until after first CONFIG succeeds
4. Subscribe to DiagnosticDetails notifications (replace VERSION subscription)
5. Implement opcode 0x43 color scheme sent after CONFIG
6. Gate: Pixel-only to minimize risk, with runtime flag to disable
```

**Scenario C: Phase 2 + Phase 4 required (settling period needed)**

```
All of Scenario B, plus:
7. Add GATT settling delay (value determined by Phase 4) after notification subscriptions
8. Gate: Pixel-only, delay value configurable via Developer Tools
```

### Feature Flags

All changes behind a runtime toggle accessible from Developer Tools:

```kotlin
object PixelBlePolicy {
    /** Master toggle for all #333 mitigations */
    var pixelGattMitigations: Boolean = true

    /** Suppress LED color write on connect (Phase 1) */
    var suppressColorOnConnect: Boolean = true

    /** Defer polling until after CONFIG (Phase 2) */
    var deferPollingUntilConfig: Boolean = true

    /** GATT settling delay in ms (Phase 4), 0 = disabled */
    var gattSettlingDelayMs: Long = 500L

    /** Use official color opcode 0x43 instead of 0x11 (Phase 3) */
    var useOfficialColorOpcode: Boolean = true

    fun isActive(): Boolean = pixelGattMitigations && DeviceInfo.isPixel()
}
```

### Rollout Strategy

1. **Alpha:** All mitigations ON, Pixel-only, behind Developer Tools toggle
2. **Beta:** If no regressions after 2 weeks of alpha testing, enable by default on Pixel devices
3. **Release:** Ship as default behavior for Pixel 6/7/Fold. Non-Pixel devices unaffected.

### Regression Test Matrix

| Device | Workout Start | Rep Counting | LED Colors | Polling | Heartbeat | Reconnection |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|
| Pixel 6 | Must PASS | Must PASS | Deferred OK | Must work post-CONFIG | Must work post-CONFIG | Must work |
| Pixel 7 Pro | Must PASS | Must PASS | Deferred OK | Must work post-CONFIG | Must work post-CONFIG | Must work |
| Samsung Galaxy | Must PASS | Must PASS | Must work | Must work | Must work | Must work |
| Pixel 8+ | Must PASS | Must PASS | Must work | Must work | Must work | Must work |

---

## Appendix A: Complete Ruled-Out Approaches

These have been tested and conclusively ruled out. Do NOT re-attempt:

| Approach | Builds Tested | Result | Why It Failed |
|----------|:---:|--------|---------------|
| Skip MTU negotiation | v12-v14 (Flag I) | FAIL | Official app DOES negotiate MTU. Skipping causes notification stalls. |
| Kill polling (Flag B) | Deep Analysis | FAIL | LED 0x11 write still fired. Polling alone isn't the trigger. |
| Kill heartbeat (Flag A v2) | Deep Analysis | FAIL | LED 0x11 write still fired. Heartbeat alone isn't the trigger. |
| LE 1M PHY (Flag C) | Deep Analysis | FAIL/WORSE | Made some devices fail on connect entirely. |
| GATT cache refresh (Flag D) | new-v1 | FAIL | GATT cache isn't stale; the corruption is runtime. |
| Force GATT close (Flag E) | new-v1 | FAIL | Kable manages lifecycle; forcing close causes side effects. |
| Polling quiesce before CONFIG (Flag F) | new-v2 | FAIL | LED 0x11 already corrupted write lane before quiesce. |
| Remove START command (Flag G) | new-v3+ | FAIL alone | Necessary but not sufficient. Official app confirms no 0x03. |
| Raw GATT bypass (Flag H) | new-v3-v8 | PARTIAL | Got CONFIG to succeed once (v4, 53ms) but unsustainable. |
| WRITE_TYPE_NO_RESPONSE | new-v8 | FAIL | Both write types fail when GATT is degraded. |
| Vendored Kable + legacy API | new-v9 | FAIL | Problem is below Kable — BCM4389 firmware level. |
| Ready gate | new-v10 | FAIL | LED 0x11 fires after gate but still corrupts GATT. |
| Remove START + SAMPLE notifications | new-v11 | REGRESSION | Connection timeout during ready gate. |

## Appendix B: Key Source Files

| File | Purpose |
|------|---------|
| `shared/.../data/ble/KableBleConnectionManager.kt` | Main BLE connection lifecycle, onDeviceReady(), notification subscriptions, polling start |
| `shared/.../data/ble/BlePacketFactory.kt` | All packet construction — CONFIG (0x04), color (0x11), echo (0x4E), heartbeat |
| `shared/.../data/ble/MetricPollingEngine.kt` | 4 polling loops + heartbeat |
| `shared/.../data/ble/BleOperationQueue.kt` | GATT operation serialization |
| `shared/.../presentation/manager/BleConnectionManager.kt` | Presentation layer — fires LED write on Connected |
| `shared/.../util/BleConstants.kt` | MTU, timing constants, 1RM formulas |
| `shared/.../data/ble/PixelGattFlags.kt` | Experiment flags (pixel_test branch) |
| `shared/.../data/ble/PixelGattPolicy.kt` | Pixel device detection |
| **Decompiled official app** | |
| `VitruvianDeobfuscated/BLE_PROTOCOL_DEEP_DIVE.md` | Complete official BLE protocol reference |
| `VitruvianDeobfuscated/java-decompiled/sources/Ek/P.java` | Official CommandId enum |
| `VitruvianDeobfuscated/docs/deobfuscation/exercise-mode-ble-packet-construction.md` | Official packet byte layouts |

## Appendix C: Tester Assignments

| Tester | Device | Available? | GitHub Handle |
|--------|--------|:---:|---------------|
| StuGotz | Pixel 6 Pro XL | ✅ | @StuGotz |
| FossForUs1 | Pixel 7 Pro | ✅ | @FossForUs1 |
| MichaelWilko | Pixel 6 | ❓ (inactive since April) | @MichaelWilko |
| kharupt | Pixel 7 Pro | ❓ (active in original issue) | @kharupt |
| CypherRevelation616 | Unknown Pixel | ❓ | @CypherRevelation616 |

**Minimum for Phase 1-2:** At least 2 testers with different Pixel variants (one 6-series, one 7-series).

## Appendix D: Investigation Evidence Trail

This plan is based on analysis from 8 parallel investigations:

1. **Issue #333** — 78 comments, 14 test builds (Apr–Jun 2026)
2. **Issue #170** — 43 comments, original app (Nov 2025)
3. **Phoenix BLE sequence audit** — every GATT operation from connect to CONFIG
4. **Official app decompilation** — 7,475 classes at `VitruvianDeobfuscated/`
5. **Web app source analysis** — `workoutmachineappfree.github.io` JavaScript
6. **BCM4389 firmware research** — 10 Google Issue Tracker bugs, community reports
7. **MTU/Long Write analysis** — Android 14+ auto-negotiates 517, Long Write theory eliminated
8. **Flag I notification stall analysis** — `requestMtu()` serves as GATT warm-up on BCM4389

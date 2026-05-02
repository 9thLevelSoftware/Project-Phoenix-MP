# M-01 — Mobile BLE Connection State Machine + Recovery UX

**Covers findings:** G-008, G-011, G-012, G-121 (4 findings; 2 CRITICAL, 2 HIGH)
**Surfaces:** Mobile only (BLE is mobile-authoritative per charter)
**Files affected:**
- New: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConnectionPill.kt` (single composable replacing per-screen header pills)
- Edit: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConnectionLostDialog.kt` (replace 2-button with 3-action recovery)
- Edit: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConnectionErrorDialog.kt` (cause-aware branching)
- Edit: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt` (gate `AUTO-START READY` banner on `Connected`)
- Edit: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt` (inject the new pill into the top-bar slot for every screen)
- Edit: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` (expose `BleErrorCause` enum + a single `connectionPillState: StateFlow<ConnectionPillState>` derived from `connectionState`)
- Edit (extend, do not rename): `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt` — add `data class Error(val cause: BleErrorCause, val message: String, val throwable: Throwable? = null)` (constructor-compatible default `cause = BleErrorCause.Unknown`)

**Effort:** ~3 dev days
- 1.0d — `ConnectionPill` composable, refactor 4-6 callsites, write @Preview tests for all 5 visual states
- 0.75d — `ConnectionLostDialog` 3-action redesign + wire to existing `viewModel.endSetAndSave()` / `viewModel.endWorkoutAndSave()` (the latter exists; the former needs a wrapper around `viewModel.recordReps()` + `viewModel.completeCurrentSet()`)
- 0.75d — `ConnectionErrorDialog` cause-aware branching + `BleErrorCause` enum + map Nordic BLE error codes
- 0.5d — `JustLiftScreen` banner gating + new "Connect Vitruvian" inline affordance composable

---

## Problem statement

The Phoenix mobile app's BLE state surface is the most-confused area of the product. The audit captured four user-visible bugs in this single domain that compound: a header pill that **rotates between three different copy strings** ("Connect" / "Connecting" / "Click to Connect") on different screens for the same underlying state; a `Connecting…` state that **stays stale indefinitely** after a 30-second scan timeout with no error/retry transition; an `AUTO-START READY · Grab handles to start` banner that **lies to the user** by appearing while the machine is fully disconnected; and a `ConnectionLostDialog` whose primary recovery affordance is a **plain "Dismiss" button** that closes the dialog and orphans the user into a frozen `WorkoutPausedCard` with no path forward — at the moment of greatest stress (mid-workout, with a faulted machine).

Read together these four findings describe a state machine where **the UI promises capability the BLE layer cannot deliver**, then **fails silently when the promise breaks**. A first-time user opens Just Lift, sees `AUTO-START READY`, grabs the handles, and nothing happens — because the pairing step they were supposed to complete first was never affordanced. They tap the orange "Connect" pill (which says "Click to Connect" on this particular screen), watch it spin to "Connecting…" forever, and conclude the app is broken. A returning user mid-workout has their machine drop, sees a "Connection Lost" dialog with a "Dismiss" button, taps Dismiss because it looks like the safe choice — and lands on a paused-workout screen with no next-step. They lose the in-progress set, often the workout, and a measurable share of their trust in the app.

These four bugs share one root cause: **there is no single source-of-truth representation of BLE connection state in the UI layer**, so each screen renders its own approximation. The fix consolidates onto **one ConnectionPill composable**, **five canonical states**, and **explicit recovery affordances at every state transition** — with hard guard-rails that respect the charter's BLE mid-set safety constraint (no pause affordances during an active set; recovery only between sets or after a set has fully ended).

---

## Current-state evidence

| Screenshot | Surface | What's shown | What's wrong |
|---|---|---|---|
| `_audit/screenshots/mobile/01-splash-or-first.png` | Home | Header pill = `Connect` (solid orange, idle) | Copy version A |
| `_audit/screenshots/mobile/30-routines.png` | Routines | Header pill = `Connect` | Copy version A — same as Home (good baseline) |
| `_audit/screenshots/mobile/60-light-theme.png` | Home (light theme) | Header pill = `Click to Connect` | Copy version B — different label, same state |
| `_audit/screenshots/mobile/05-just-lift.png` | Just Lift | Header pill = `Connect` AND green banner `AUTO-START READY · Grab handles to start` | False-positive readiness signal |
| `_audit/screenshots/mobile/09-ble-connection-fail.png` | Just Lift | Header pill = `Connecting` after tapping Connect, no machine present | Stuck state begins |
| `_audit/screenshots/mobile/10-ble-after-15s.png` | Just Lift | Header pill = `Connecting` still, 15s later | No timeout transition (note: actual `BLE_SCAN_TIMEOUT_MS = 30000` in `Constants.kt:39`, so transition expected at 30s, not 10s) |
| `_audit/screenshots/mobile/62-landscape.png` | Insights (landscape) | Header pill = `Click to Connect` | Copy version B again |

Source-code corroboration:
- `presentation/components/ConnectionLostDialog.kt:67-69` — `dismissButton = TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_dismiss)) }` — this is the orphaning Dismiss the audit flagged.
- `presentation/components/ConnectionLostDialog.kt:29` — `onDismissRequest = onDismiss` — outside-tap also dismisses, no `dismissOnClickOutside = false`.
- `presentation/components/ConnectionErrorDialog.kt:50-53` — fixed bullet list of 4 troubleshooting tips; no branching on `ConnectionState.Error.cause`.
- `presentation/components/ConnectionErrorDialog.kt:36` — raw `message: String` from Nordic BLE rendered directly to the user.
- `domain/model/Models.kt:60` — `data class Error(val message: String, val throwable: Throwable? = null)` has no `cause` discriminant; the dialog has nothing to branch on even if it wanted to.
- `util/Constants.kt:39` — `BLE_SCAN_TIMEOUT_MS = 30000L`. The pill needs to react to *this* timeout, not a hardcoded UI-side timer.

---

## Proposed design

### A. Header `ConnectionPill` — single source of truth

**One composable. Five canonical visual states. Same callsite on every screen.**

```kotlin
// New: presentation/components/ConnectionPill.kt
@Composable
fun ConnectionPill(
    state: ConnectionPillState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)

sealed class ConnectionPillState {
    object Disconnected : ConnectionPillState()
    object Connecting : ConnectionPillState()
    data class Connected(val batteryPct: Int?, val signalBars: Int) : ConnectionPillState()
    data class ConnectFailed(val cause: BleErrorCause) : ConnectionPillState()
    data class ConnectionLost(val deviceName: String) : ConnectionPillState()  // briefly rendered while ConnectionLostDialog is up
}
```

The pill state is a derived StateFlow from `connectionState + lastScanStartedAt + isInWorkout`. ViewModel exposes `connectionPillState: StateFlow<ConnectionPillState>` so screens never compute their own.

#### Visual specification (ASCII mockups)

```text
─────────────────────────────────────────────────────────────────────
  STATE 1 of 5 — Disconnected (idle)                  default state
─────────────────────────────────────────────────────────────────────

   ┌──────────────────────┐
   │  ⏻  Connect Vitruvian │     <— outline-only orange, 1.5dp stroke
   └──────────────────────┘
        ^
        |  Tap → opens BleScanScreen / triggers viewModel.connect()
        |  Pill width: hugContent + 12dp horizontal padding
        |  Height: 36dp (touch-target ≥ 44dp via outer Modifier.padding(4dp))
        |  Stroke: PhoenixEmber #FF6B35 (from G-016 unification — was #FF9149)
        |  Fill: transparent
        |  Text: 14sp, FontWeight.Medium, color = PhoenixEmber #FF6B35
        |  Icon: ⏻ (Material `Icons.Outlined.Bluetooth`), 18dp, same tint

─────────────────────────────────────────────────────────────────────
  STATE 2 of 5 — Connecting (transient, ≤30s per BLE_SCAN_TIMEOUT_MS)
─────────────────────────────────────────────────────────────────────

   ┌──────────────────────┐
   │  ●  Connecting…       │     <— filled orange, animated dot
   └──────────────────────┘
        ^
        |  Fill: PhoenixEmber #FF6B35
        |  Text: 14sp, FontWeight.Medium, color = #06060A (near-black)
        |  Dot: 8dp circle, opacity loops 0.3↔1.0 over 1200ms
        |  REDUCED-MOTION: dot is solid 0.7 opacity (no loop)
        |  Tap: NO-OP (button disabled — prevents double-tap re-scan storms)
        |  After Constants.BLE_SCAN_TIMEOUT_MS → transitions to State 4 (ConnectFailed)

─────────────────────────────────────────────────────────────────────
  STATE 3 of 5 — Connected                            steady state
─────────────────────────────────────────────────────────────────────

   ┌────────────────────────┐
   │  ✓  Connected · 87%  ▮▮▮ │   <— filled forge-green, battery + signal
   └────────────────────────┘
        ^
        |  Fill: ForgeGreen #10B981 (introducing this token to mobile per G-144)
        |  Text: 14sp, FontWeight.Medium, color = #06060A
        |  ✓ icon: 18dp, same tint as text
        |  Battery %: 12sp, tabular-figures (JetBrains Mono per G-017)
        |             Threshold colors:    >50%  text remains #06060A
        |                                  ≤50%  text remains #06060A (no panic colors yet)
        |                                  ≤20%  battery icon adds Warning #F59E0B halo, "20% LOW" suffix
        |                                  ≤10%  pill shifts to PhoenixCrimson #DC2626 fill, "10% — charge soon"
        |  Signal bars: 3 bars, ▮ = active, ▯ = inactive
        |              4 bars >= -55 dBm, 3 bars >= -67, 2 bars >= -80, 1 bar < -80
        |  Tap → opens "Manage connection" bottom sheet
        |        (disconnect, view connection logs, view device info)
        |        IMPORTANT: in-workout, the disconnect option is hidden mid-set
        |        and labeled "Disconnect (between sets only)" mid-workout.

─────────────────────────────────────────────────────────────────────
  STATE 4 of 5 — ConnectFailed (after timeout / explicit error)
─────────────────────────────────────────────────────────────────────

   ┌────────────────────────────┐
   │  ⚠  No machine found · Retry │   <— outline-red, error tint
   └────────────────────────────┘
        ^
        |  Stroke: SignalError #EF4444, 1.5dp
        |  Fill: rgba(239, 68, 68, 0.08)  -- 8% red wash
        |  Text: 14sp, FontWeight.Medium, color = SignalError #EF4444
        |  ⚠ icon: 18dp, same tint (Material `Icons.Outlined.ErrorOutline`)
        |  Tap → re-scan (transitions back to State 2 Connecting)
        |  Long-press → opens ConnectionErrorDialog (cause-aware, see Section C)
        |  Auto-revert to State 1 Disconnected after 60s of no interaction
        |  (so the screen doesn't stay red forever once user moves on)

─────────────────────────────────────────────────────────────────────
  STATE 5 of 5 — ConnectionLost (mid-workout drop, dialog visible)
─────────────────────────────────────────────────────────────────────

   ┌──────────────────────────┐
   │  ⚠  Connection lost       │   <— filled crimson, urgent
   └──────────────────────────┘
        ^
        |  Fill: PhoenixCrimson #DC2626
        |  Text: 14sp, FontWeight.Medium, color = #FFFFFF
        |  Tap: NO-OP — the dialog underneath is the recovery surface (Section B)
        |  Pill remains in this state until dialog action is chosen.
        |  After dialog action: transitions to Connecting (Reconnect) or
        |                       Disconnected (End set / End workout).
```

#### State transition table

| From | Trigger | To | Notes |
|---|---|---|---|
| Disconnected | tap pill | Connecting | Calls `viewModel.connect()`; pill disabled until state change |
| Connecting | `BleManager` reports `Connected` | Connected | Pill animates fill from orange → green over 200ms |
| Connecting | `BLE_SCAN_TIMEOUT_MS` (30s) elapses with no advertise | ConnectFailed | Cause = `ScanTimeout` |
| Connecting | `BluetoothDisabled` advertise | ConnectFailed | Cause = `BluetoothDisabled`; long-press shows the BT-off dialog |
| Connecting | `LocationPermissionDenied` exception | ConnectFailed | Cause = `LocationPermissionDenied` |
| Connecting | `ConnectionTimeout` (paired but no BLE handshake at 15s per `BLE_CONNECTION_TIMEOUT_MS`) | ConnectFailed | Cause = `ConnectionTimeout` |
| Connecting | unknown error | ConnectFailed | Cause = `Unknown`; raw message hidden, user-friendly text shown |
| Connected | `BleManager` reports drop while NOT in workout | Disconnected | Silent transition; no dialog |
| Connected | `BleManager` reports drop while in workout (`workoutState in [Active, Resting, SetSummary]`) | ConnectionLost | Triggers `ConnectionLostDialog` (Section B) |
| ConnectFailed | tap pill | Connecting | Re-scan |
| ConnectFailed | 60s of inactivity | Disconnected | Auto-revert |
| ConnectionLost | dialog action: "Reconnect & continue" | Connecting | Calls `viewModel.reconnectInterruptedWorkout()` |
| ConnectionLost | dialog action: "End set & save reps" | Disconnected | Calls existing `viewModel.endSetAndSavePartial()`* |
| ConnectionLost | dialog action: "End workout" | Disconnected | Calls `viewModel.endWorkoutEarly()` |

*The exact ViewModel method name should be confirmed; `MainViewModel` already exposes `reconnectInterruptedWorkout()` per `EnhancedMainScreen.kt:522-532`. The other two need wrappers — see Implementation notes.

#### Color & typography spec (consolidated)

| Token | Value | Source |
|---|---|---|
| PhoenixEmber | `#FF6B35` | Adopt portal canonical (per G-016 — was `#FF9149` on mobile dark) |
| ForgeGreen | `#10B981` | New on mobile (per G-144 — already in portal) |
| SignalError | `#EF4444` | Adopt mobile canonical (per G-016 sweep — portal also uses this) |
| PhoenixCrimson | `#DC2626` | Already on mobile; used here for urgent ConnectionLost only |
| Warning | `#F59E0B` | Already on mobile; used for `≤20% battery` halo |
| Pill text size | 14sp / FontWeight.Medium | Compose `MaterialTheme.typography.labelLarge` |
| Battery % font | 12sp / JetBrains Mono / `featureSettings = "tnum"` | per G-017; tabular-figures prevents jitter |
| Pill height | 36dp visual + 4dp outer padding = 44dp+ touch target | WCAG 2.5.8 |
| Pill corner radius | 18dp (full pill) | Matches Phoenix `Shapes.full`; deviates from portal hairline 4-10px (per G-103, recommend mobile drop to 12dp pillow on cards but **keep pill at 18dp** because pill identity = shape) |

#### Where the pill renders

The pill lives in the **right side of the top-app-bar slot of `EnhancedMainScreen.kt`**, replacing the four divergent renderings on `HomeScreen`, `JustLiftScreen`, `SmartInsightsTab`, `RoutinesTab`. It is **always visible** on every screen except:
- `SplashScreen` (pre-auth, no machine context)
- `AuthScreen` / `EulaScreen` / `LinkAccountScreen` (onboarding, no machine context)
- `ActiveWorkoutScreen` mid-set when `WorkoutState == Active` — the pill **stays visible but tap is no-op** so users can still see connection status without being tempted to disconnect (per charter constraint).

---

### B. ConnectionLostDialog — three explicit recovery actions

Replace the current Dismiss-or-Reconnect dialog with three mutually-exclusive recovery actions. **Non-dismissible** — there is no fourth "close this and figure it out yourself" path.

```text
┌───────────────────────────────────────────────────────────┐
│                                                           │
│        ⚠       Connection lost                            │
│              ─────────────────                            │
│                                                           │
│   Your Vitruvian disconnected during                      │
│   Set 3 of Bench Press. Reps 1–7 saved.                   │
│                                                           │
│                                                           │
│   ┌─────────────────────────────────────┐                │
│   │  ⟳   Reconnect & continue            │  ← primary    │
│   └─────────────────────────────────────┘                │
│                                                           │
│   ┌─────────────────────────────────────┐                │
│   │  ⏹   End set & save reps             │  ← secondary  │
│   └─────────────────────────────────────┘                │
│                                                           │
│   ┌─────────────────────────────────────┐                │
│   │  ✕   End workout                     │  ← tertiary   │
│   └─────────────────────────────────────┘                │
│                                                           │
│   Reps captured before the drop are already               │
│   saved.  No data is lost.                                │
│                                                           │
└───────────────────────────────────────────────────────────┘
   • non-dismissible (dismissOnClickOutside = false, dismissOnBackPress = false)
   • no plain "Dismiss" button — every action has clear consequence
   • icon tint: PhoenixCrimson #DC2626
   • title: 22sp / FontWeight.Bold / color = onSurface
   • body context line uses {currentSet}, {exerciseName}, {repsCompletedThisSet}
   • reassurance footer: 14sp / color = onSurfaceVariant
```

#### Action behavior spec

| Action | Visual | Calls | Result |
|---|---|---|---|
| Reconnect & continue | Filled `PhoenixEmber #FF6B35`; white text; icon `Icons.Outlined.Refresh`; height 48dp | `viewModel.reconnectInterruptedWorkout()` | Pill → Connecting; on success returns user to `ActiveWorkout` with the half-completed set restored (reps 1–7 retained, set 3 still active, reps 8+ resumable). On reconnect-fail (30s timeout), dialog re-opens with body line "Reconnect failed — your machine still appears off." and the same 3 actions. |
| End set & save reps | Outline `ForgeGreen #10B981`; green text; icon `Icons.Outlined.Stop`; height 48dp | `viewModel.endSetAndSavePartial()` | Saves reps 1–7 to current set (marks set complete with `isPartial = true` flag in DB), advances to RestTimer (or next set if rest = 0). Pill → Disconnected. User can resume next set when they manually reconnect. |
| End workout | Text-only `SignalError #EF4444` text-button; icon `Icons.Outlined.Close`; height 48dp; **NO confirm dialog** (the dialog itself IS the confirm) | `viewModel.endWorkoutEarly()` | Navigates to `RoutineCompleteScreen` with whatever's been recorded (this set marked partial, all prior sets intact). Pill → Disconnected. |

The visual hierarchy (filled → outline → text) mirrors Material 3's button-emphasis ladder and gives users a one-glance read on which action is "the safe choice" (Reconnect = recover) vs "the deliberate exit" (End workout = give up).

#### Edge cases

| Scenario | Behavior |
|---|---|
| BLE drops on **set 1, rep 0** (workout started but no reps recorded) | Body becomes "Your Vitruvian disconnected before you started Set 1 of Bench Press. No reps recorded." Action 2 is hidden (no reps to save → no half-set to end). Actions: [Reconnect & continue] [End workout]. |
| BLE drops on **set 1, rep 1** | Body says "...Rep 1 saved." Action 2 stays — saving 1 rep is still a valid partial set. |
| BLE drops **during rest period** (`WorkoutState == Resting`) | Body becomes "Your Vitruvian disconnected during your rest period before Set N." Action 2 disappears (no active set to end). Actions: [Reconnect & continue] [End workout]. The rest timer keeps running visually since rest is local. |
| BLE drops with **`WorkoutState == ExerciseComplete`** (between exercises) | Dialog does not show; pill silently transitions to Disconnected. User reconnects via pill before next exercise. |
| BLE drops in **Just Lift** (no routine context) | Body becomes "Your Vitruvian disconnected mid-set. Reps captured this set are saved." Actions: [Reconnect & continue] [End set] [Done]. ("End workout" → "Done" copy because there's no multi-set workout). |
| **Reconnect fails** after user picks Action 1 | Dialog re-opens after 30s scan timeout with updated body "Reconnect failed — your machine still appears off." Same 3 actions. User can retry indefinitely or escape via Action 2/3. |
| **System back press** while dialog open | Suppressed (`dismissOnBackPress = false`). Subtle haptic confirms dialog is intentional. |
| **App backgrounded** with dialog open | Dialog state persists in ViewModel; re-renders identically on foreground. Pill remains in `ConnectionLost` state. |

#### Charter constraint adherence

The charter forbids mid-set pause affordances. **None of the three actions pause the BLE protocol mid-set.** The connection has **already dropped** by the time this dialog appears — the actions are about user-side state cleanup, not BLE-side packet manipulation. "End set & save reps" calls a save function that operates on locally-buffered data; it does **not** send a partial-end packet to the (already-disconnected) machine. The charter constraint is preserved.

---

### C. ConnectionErrorDialog — cause-aware branching

Branch the dialog content on `ConnectionState.Error.cause` (a new `BleErrorCause` enum). The current static bullet list becomes the body of one branch only (`ScanTimeout`).

#### `BleErrorCause` enum (to add to `Models.kt`)

```kotlin
enum class BleErrorCause {
    BluetoothDisabled,           // OS Bluetooth toggle is off
    LocationPermissionDenied,    // Android < 12 location permission, or Android 12+ BLUETOOTH_SCAN denied
    ScanTimeout,                 // 30s scan with no advertise from a Vitruvian device
    ConnectionTimeout,           // device found but pairing handshake did not complete in 15s
    Unknown,                     // catch-all; dialog hides raw message and shows generic copy
}
```

The mapping from Nordic BLE exception types to `BleErrorCause` happens in the `BleManager` Android impl (a small `when (e)` block). The ViewModel only ever sees the enum.

#### Cause × dialog content

| Cause | Headline | Body | Primary CTA | Secondary CTA | Icon |
|---|---|---|---|---|---|
| `BluetoothDisabled` | **Bluetooth is off** | Phoenix needs Bluetooth to talk to your machine. Turn Bluetooth on, then come back. | **Open Bluetooth settings** (deep-link via `Intent(Settings.ACTION_BLUETOOTH_SETTINGS)` on Android, equivalent on iOS) | Cancel | `Icons.Outlined.BluetoothDisabled` |
| `LocationPermissionDenied` | **Location permission needed** | Android requires location permission to scan for Bluetooth devices. We never track your location. | **Grant permission** (opens `ActivityResultContracts.RequestPermission` flow for `ACCESS_FINE_LOCATION` on API <31 / `BLUETOOTH_SCAN` on API 31+) | Why is this needed? *(opens info bottom sheet explaining the Android BLE-scan requirement)* | `Icons.Outlined.LocationOn` |
| `ScanTimeout` | **No machine found** | We scanned for 30 seconds and didn't see your Vitruvian. Try the steps below: ▸ Make sure the machine is powered on (lights visible). ▸ Toggle Bluetooth off and back on. ▸ Move within 5 meters of the machine. ▸ Disconnect any other device that might be paired. | **Try again** (re-scan) | View connection logs *(opens `ConnectionLogsScreen.kt`)* | `Icons.Outlined.SearchOff` |
| `ConnectionTimeout` | **Couldn't reach the machine** | We saw your machine but couldn't pair within 15 seconds. The most reliable fix is power-cycling the trainer. | **Retry pairing** (re-scan) | Power-cycle help *(opens info sheet with hardware-specific instructions for V-Form vs Trainer+)* | `Icons.Outlined.SyncProblem` |
| `Unknown` | **Something went wrong** | The connection failed for an unexpected reason. We've logged the details — try again or contact support if this keeps happening. | **Try again** | View connection logs | `Icons.Outlined.ErrorOutline` |

#### Before/after copy comparison

```text
─── BEFORE (ConnectionErrorDialog.kt:30-55, all causes) ─────────────

  Title:  Connection Failed
  Body:   {message}                        ← raw Nordic BLE string,
                                             often "GATT_ERROR_133" etc.
          ─────────
          Troubleshooting tips:
          • Ensure the machine is powered on
          • Try turning Bluetooth off and on
          • Move closer to the machine
          • Check that no other device is connected
  CTAs:   [Retry] [OK]                     ← OK == dismiss, no recovery


─── AFTER (cause = BluetoothDisabled) ───────────────────────────────

  Icon:   ⨂ (BluetoothDisabled, tint = PhoenixCrimson)
  Title:  Bluetooth is off
  Body:   Phoenix needs Bluetooth to talk to your machine.
          Turn Bluetooth on, then come back.
  CTAs:   [Open Bluetooth settings]  [Cancel]
                ↑ deep-links to system; user is one tap from fix


─── AFTER (cause = ScanTimeout) ─────────────────────────────────────

  Icon:   🔍✕ (SearchOff, tint = PhoenixEmber)
  Title:  No machine found
  Body:   We scanned for 30 seconds and didn't see your Vitruvian.
          Try the steps below:
          ▸ Make sure the machine is powered on (lights visible).
          ▸ Toggle Bluetooth off and back on.
          ▸ Move within 5 meters of the machine.
          ▸ Disconnect any other device that might be paired.
  CTAs:   [Try again]  [View connection logs]
                ↑ same retry, but logs is right there for power users
```

The `Unknown` branch never shows a raw BLE stack trace to the user. The original `message: String` is still captured and surfaced in `ConnectionLogsScreen.kt` for power users / support diagnostics, but the dialog body is a clean human-readable sentence.

---

### D. AUTO-START READY banner — connection-gated

Bind the banner to `connectionState is ConnectionState.Connected`. Three banner states on `JustLiftScreen.kt`:

```text
─── State 1 of 3 — Connected (existing behavior, unchanged) ────────

   ┌─────────────────────────────────────────────────────────┐
   │  ●  AUTO-START READY · Grab handles to start             │
   └─────────────────────────────────────────────────────────┘
        ^
        |  Background: ForgeGreen #10B981 with 0.16 alpha
        |  Border: ForgeGreen #10B981 1dp
        |  Dot: solid #10B981, 8dp (subtle pulse @ 1200ms; respects reduced-motion)
        |  Text: 14sp / FontWeight.Medium / color = onSurface


─── State 2 of 3 — Disconnected (NEW — replaces false-positive) ────

   ┌─────────────────────────────────────────────────────────┐
   │  ⏻  Connect Vitruvian to enable auto-start    [Connect ▸]│
   └─────────────────────────────────────────────────────────┘
        ^
        |  Background: SurfaceVariant (dim grey) — calmer than the green
        |  Border: outlineVariant 1dp
        |  Icon: ⏻ Icons.Outlined.Bluetooth, 18dp, color = onSurfaceVariant
        |  Text: 14sp / FontWeight.Normal / color = onSurfaceVariant
        |  Inline button: outlined, height 32dp, 12dp horizontal padding
        |                 stroke = PhoenixEmber #FF6B35, text = PhoenixEmber
        |                 Tap → triggers viewModel.connect() (same as pill State 1)


─── State 3 of 3 — Connecting (NEW) ────────────────────────────────

   ┌─────────────────────────────────────────────────────────┐
   │  ●  Detecting machine…                                   │
   └─────────────────────────────────────────────────────────┘
        ^
        |  Background: SurfaceVariant
        |  Border: outlineVariant
        |  Dot: PhoenixEmber #FF6B35, animated (loops 0.3↔1.0 @ 1200ms)
        |  REDUCED-MOTION: dot is solid 0.7 opacity
        |  Text: 14sp / FontWeight.Normal / color = onSurfaceVariant
        |  No CTA — pill in header is the cancel/retry surface
```

**Banner state — state-machine table**

| `connectionState` | Banner state | Banner copy |
|---|---|---|
| `Connected` | State 1 | "AUTO-START READY · Grab handles to start" |
| `Disconnected` | State 2 | "Connect Vitruvian to enable auto-start" + inline `[Connect ▸]` |
| `Connecting` | State 3 | "Detecting machine…" |
| `Error(*)` | State 2 with copy "Connect Vitruvian to enable auto-start" + button label switches to `[Retry ▸]` | (same body, button differs) |

The "AUTO-START" mechanism in the screen's logic is unchanged. Only the **visual** is gated. If the user somehow grabs the handles during State 2 or 3, nothing happens (which is correct — there's no machine paired).

---

## Implementation notes

### Compose API hints

```kotlin
// 1) NEW: presentation/components/ConnectionPill.kt
@Composable
fun ConnectionPill(
    state: ConnectionPillState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current  // CompositionLocal from G-101 fix

    // Surface(...) + Row(...) with state-specific colors and content.
    // Use AnimatedContent to cross-fade between states (200ms; snap when reducedMotion).
    // For Connecting dot pulse:
    //   if (reducedMotion) Modifier.alpha(0.7f)
    //   else animatedAlpha (rememberInfiniteTransition, animateFloat 0.3 → 1.0 → 0.3, 1200ms)
}

// 2) ViewModel pseudocode
val connectionPillState: StateFlow<ConnectionPillState> = combine(
    bleRepository.connectionState,
    workoutRepository.workoutState,
    bleRepository.batteryPercent,
    bleRepository.signalRssi,
) { conn, workout, battery, rssi ->
    when (conn) {
        is ConnectionState.Disconnected -> ConnectionPillState.Disconnected
        is ConnectionState.Scanning,
        is ConnectionState.Connecting -> ConnectionPillState.Connecting
        is ConnectionState.Connected -> ConnectionPillState.Connected(battery, rssiToBars(rssi))
        is ConnectionState.Error -> {
            if (workout is WorkoutState.Active || workout is WorkoutState.Resting) {
                ConnectionPillState.ConnectionLost(conn.deviceName ?: "Vitruvian")
            } else {
                ConnectionPillState.ConnectFailed(conn.cause)
            }
        }
    }
}.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConnectionPillState.Disconnected)
```

### Files to touch (concrete list)

| File | Change | Risk |
|---|---|---|
| `domain/model/Models.kt` | Add `BleErrorCause` enum; extend `ConnectionState.Error` to take `cause: BleErrorCause = BleErrorCause.Unknown` (with default = back-compat) | LOW — additive |
| `data/ble/BleManager.kt` (Android impl) | Add `mapToBleErrorCause(throwable)` that classifies `BluetoothAdapterDisabledException`, `SecurityException` (perm), `ScanTimeoutException`, etc. | MED — must verify Nordic exception types |
| `data/ble/BleManager.kt` (iOS impl) | Same, map `CBCentralManagerState` + `CBError` types | MED |
| `presentation/viewmodel/MainViewModel.kt` | Add `connectionPillState` StateFlow; add `endSetAndSavePartial()` and `endWorkoutEarly()` if not present | MED — verify these exist or wrap |
| `presentation/components/ConnectionPill.kt` | NEW file (~150 LoC) | LOW |
| `presentation/components/ConnectionLostDialog.kt` | Replace 2-button with 3-action; pass `currentSet`, `exerciseName`, `repsCompletedThisSet`; set `dismissOnClickOutside = false`, `dismissOnBackPress = false` | LOW |
| `presentation/components/ConnectionErrorDialog.kt` | Add `cause: BleErrorCause` param; branch entire content on cause | LOW |
| `presentation/screen/JustLiftScreen.kt` | Replace single-state banner composable with 3-state when-block on `connectionState` | LOW |
| `presentation/screen/EnhancedMainScreen.kt` | Inject `ConnectionPill(state = vm.connectionPillState.collectAsState().value, ...)` into the top-bar slot; remove screen-specific pill renderings in `HomeScreen` / `JustLiftScreen` / `SmartInsightsTab` / `RoutinesTab` | MED — touches multiple screens |

### State-machine pseudocode (where the timeouts live)

```kotlin
// In BleManager.connect() — Android & iOS impls
suspend fun connect(): Result<ConnectedDevice> {
    val scanJob = launch { scanForVitruvian() }   // emits to advertise StateFlow
    val timeout = withTimeoutOrNull(BLE_SCAN_TIMEOUT_MS) {
        advertise.first()
    } ?: run {
        scanJob.cancel()
        return Result.failure(BleError(cause = BleErrorCause.ScanTimeout))
    }

    val connectResult = withTimeoutOrNull(BLE_CONNECTION_TIMEOUT_MS) {
        pair(timeout)
    } ?: return Result.failure(BleError(cause = BleErrorCause.ConnectionTimeout))

    return Result.success(connectResult)
}

// All exceptions in connect() are mapped:
//   BluetoothAdapterDisabledException -> BleErrorCause.BluetoothDisabled
//   SecurityException                 -> BleErrorCause.LocationPermissionDenied
//   else                              -> BleErrorCause.Unknown (with throwable retained for ConnectionLogsScreen)
```

### Reduced-motion compliance

All three composables (Pill, Banner, Dialog) read `LocalReducedMotion.current` (introduced in the G-101 fix). When `true`:
- Connecting dot loop → solid 0.7 alpha
- Pill state cross-fade → snap (no AnimatedContent transition)
- ConnectionLost dialog entrance → no slide-up; appears instantly

---

## Acceptance criteria

A reviewer should be able to verify these on a real device:

**Pill consistency**
- [ ] On every screen that displays the header pill (Home, Just Lift, Insights, Routines, Cycles, Single Exercise, Analytics, Settings, Badges), the pill copy and width are **identical** for the same `connectionState`.
- [ ] In Disconnected state on every screen the pill reads exactly `Connect Vitruvian`. (No `Click to Connect`, no `Connect`.)
- [ ] In Connecting state on every screen the pill reads exactly `Connecting…` with a pulsing dot.
- [ ] In Connected state the pill reads exactly `Connected · NN%` with battery + signal indicator.

**Pill timeout**
- [ ] Tapping the Disconnected pill with no machine present transitions through Connecting and lands on **ConnectFailed** within `BLE_SCAN_TIMEOUT_MS = 30000ms` (±2s).
- [ ] In ConnectFailed state, tapping the pill re-scans (transitions to Connecting again).
- [ ] Long-pressing the ConnectFailed pill opens `ConnectionErrorDialog` with the appropriate cause-branch content.
- [ ] After 60s of inactivity in ConnectFailed, the pill auto-reverts to Disconnected.

**Connection lost recovery**
- [ ] During an active workout, simulating a BLE drop (`adb shell dumpsys bluetooth_manager` + force disconnect) shows `ConnectionLostDialog` with three buttons.
- [ ] The dialog **cannot** be dismissed by tapping outside, system back, or any non-action method.
- [ ] Dialog body shows the actual `currentSet`, `exerciseName`, `repsCompletedThisSet` from the active workout state.
- [ ] Tapping "Reconnect & continue" calls `viewModel.reconnectInterruptedWorkout()` and re-renders ActiveWorkout with the half-completed set restored.
- [ ] Tapping "End set & save reps" persists reps 1–N to DB with `isPartial = true`, advances to RestTimer.
- [ ] Tapping "End workout" navigates to `RoutineCompleteScreen` with all prior data intact.

**Cause-aware errors**
- [ ] Disabling Bluetooth and tapping Connect shows `ConnectionErrorDialog` with title "Bluetooth is off" and an "Open Bluetooth settings" CTA that deep-links to system settings.
- [ ] Revoking the BLE permission (Android `pm revoke <pkg> android.permission.BLUETOOTH_SCAN`) and tapping Connect shows "Location permission needed" with a "Grant permission" CTA that opens the OS permission flow.
- [ ] With Bluetooth on but no machine present, tapping Connect after 30s shows "No machine found" with the four-bullet tip list and "Try again" CTA.
- [ ] No dialog ever displays a raw Nordic BLE error string. The closest a user gets to the raw message is the "View connection logs" link → `ConnectionLogsScreen`.

**AUTO-START gating**
- [ ] On `JustLiftScreen` with `Disconnected`, the green AUTO-START banner does not appear; the dim grey "Connect Vitruvian to enable auto-start" row appears instead with an inline `Connect` button.
- [ ] On `JustLiftScreen` during `Connecting`, the banner row reads "Detecting machine…" with an animated dot.
- [ ] Only when `connectionState is Connected` does the green AUTO-START banner appear.

**Charter compliance**
- [ ] Mid-set (`WorkoutState == Active`), tapping the visible pill **does nothing** (no disconnect, no settings sheet — the underlying BLE protocol is preserved).
- [ ] None of the three ConnectionLostDialog actions send a packet to the machine (verified by checking BLE write logs — only local-state mutations occur).
- [ ] The pill's "Disconnect" option in the Manage Connection sheet is **hidden** while `WorkoutState in [Active, Resting]`; reappears when `WorkoutState in [Idle, ExerciseComplete, RoutineComplete]`.

**Reduced-motion**
- [ ] With `Settings.Global.ANIMATOR_DURATION_SCALE = 0`, the Connecting dot does not pulse and the pill state transitions snap (no AnimatedContent fade).

**Accessibility**
- [ ] The pill exposes `contentDescription` matching its visible text in every state (e.g. `"Connecting to Vitruvian"` in Connecting state).
- [ ] All three buttons in `ConnectionLostDialog` have `Modifier.semantics { role = Role.Button }` and explicit content descriptions.
- [ ] All buttons meet WCAG 2.5.8 minimum touch target (24×24 CSS px → 44×44 dp on mobile).

---

## What this does NOT change (safety boundaries)

- **No mid-set pause.** None of the new affordances introduce a "pause this set" button. The pill explicitly disables tap during `WorkoutState == Active`. The `ConnectionLostDialog` only appears *after* the BLE drop has already happened — it never originates a disconnect. The charter constraint #2 (BLE mid-set safety) is preserved.
- **No mid-set exercise switch.** The `AutoDetectionSheet` modality (G-120, separate finding) is untouched by this mockup. The charter constraint #3 (BLE exercise packet lifecycle — machine cannot receive a new packet until the active one fully ends) is preserved.
- **No protocol changes.** The fix is purely UI-state-driven. `BleManager`, `WorkoutEngine`, packet encoders, and the BLE TX/RX channels (`6e400002` / `6e400003`) are unchanged. The mapping of Nordic exceptions to `BleErrorCause` is metadata enrichment, not protocol mutation.
- **No subscription gating.** Connection state and recovery are pre-paywall (FREE-tier users hit BLE drops too); none of the new states gate behind EMBER/FLAME/INFERNO.
- **Per-cable weight convention.** Untouched. The pill battery indicator is a percentage; weight surfaces are out of this mockup's scope.
- **Workout authority.** Mobile remains authoritative for BLE control, workout execution, and per-cable weight values. The portal does not need a counterpart change for this mockup (BLE is mobile-only per charter constraint #4).

---

## Open questions / decisions left for the team

1. **Where does the Manage-Connection bottom sheet live in the file tree?** Recommend `presentation/components/ManageConnectionSheet.kt`. Out-of-scope for this mockup (separate ticket).
2. **`endSetAndSavePartial()` and `endWorkoutEarly()` — do they exist on `MainViewModel`?** Verified `reconnectInterruptedWorkout()` exists (`EnhancedMainScreen.kt:522-532`). The other two need confirmation; if absent, they are thin wrappers over existing `recordReps()` + `completeCurrentSet(isPartial = true)` and the existing end-workout flow.
3. **Battery and signal-bar source-of-truth.** The Vitruvian Nordic UART service exposes battery via a separate characteristic; confirm it's already polled in `BleManager` and surfaced as a flow. RSSI is read via `gatt.readRemoteRssi()` — confirm a polling cadence is acceptable (recommend 5s while connected).
4. **`Constants.BLE_SCAN_TIMEOUT_MS`.** Currently `30000L`. The audit prompt assumed `10000L`; the actual code is 30s. Confirm 30s is the correct user-facing wait. If the team wants 10-15s for better UX, change `Constants.kt` *first*, then this mockup's pill timeout naturally follows.
5. **iOS Manage-Connection sheet parity.** iOS doesn't expose a system "Bluetooth Settings" deep-link the way Android does (it can open `Prefs:root=Bluetooth` on some iOS versions but it's deprecated). Confirm the iOS `BluetoothDisabled` CTA falls back to "Open the Settings app" copy without a deep-link.
6. **Forge Green token introduction.** This mockup adds `ForgeGreen #10B981` to the mobile palette. Confirm this lands as part of M-01 or as a parallel token-system PR (G-144 references it as a separate finding).

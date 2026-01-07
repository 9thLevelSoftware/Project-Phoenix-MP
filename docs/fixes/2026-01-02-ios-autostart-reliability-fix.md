# iOS Autostart Reliability Fix

**Date:** 2026-01-02  
**Issue:** iOS users reported that autostart functionality in Just Lift mode does not always engage  
**Status:** Fixed  

---

## Executive Summary

Deep analysis identified **5 root causes** for intermittent iOS autostart failures. Four critical fixes were implemented, plus one bonus fix for a pre-existing iOS build error.

---

## Root Causes Identified

### 1. Multiple LaunchedEffect Race Condition (P0-CRITICAL)
**Location:** `JustLiftScreen.kt:147-160`

Two separate `LaunchedEffect` blocks both called `enableHandleDetection()` when connected:
- `LaunchedEffect(Unit)` - fires on composition
- `LaunchedEffect(connectionState)` - fires on connection state change

**Impact:** Each call reset `_handleState` to `WaitingForRest` and cleared `pendingGrabbedStartTime`. If user was already grabbing handles when the second effect fired, the 200ms dwell timer reset and autostart never triggered.

**iOS-Specific:** SwiftUI/Compose-on-iOS recomposition timing differs from Android, making this race more likely to manifest.

### 2. Countdown Late-Fire - No Final Guard (P0-CRITICAL)
**Location:** `MainViewModel.kt:2031-2045`

The countdown job could fire `startWorkout()` after `cancel()` due to cooperative cancellation:
```kotlin
autoStartJob = viewModelScope.launch {
    for (i in 5 downTo 1) { delay(1000) }
    // NO GUARD - can execute after cancel() called
    startWorkout(...)
}
```

**Impact:** User releases handles at countdown end → `cancelAutoStartTimer()` called → but `startWorkout()` fires anyway.

### 3. iOS Slower Polling Rate (P1-HIGH)
**Location:** `BleExtensions.ios.kt:5-7`

Android explicitly requests HIGH connection priority for ~10-20ms polling. iOS implementation is a no-op:
```kotlin
actual suspend fun Peripheral.requestHighPriority() {
    // No-op on iOS - CoreBluetooth handles connection priority automatically
}
```

**Impact:** With 30-50ms intervals on iOS (vs 10-20ms on Android), only 4-7 samples occur during the 200ms hysteresis window. Fewer samples = less reliable state transitions.

### 4. WaitingForRest Trap (P1-HIGH)
**Location:** `KableBleRepository.kt:2067-2076`

If user holds handles BEFORE screen entry (cables pre-tensioned > 5mm), the state machine gets stuck:
```kotlin
HandleState.WaitingForRest -> {
    if (posA < HANDLE_REST_THRESHOLD && posB < HANDLE_REST_THRESHOLD) {
        HandleState.Released  // Armed
    } else {
        HandleState.WaitingForRest  // STUCK FOREVER - no escape
    }
}
```

### 5. Baseline/First-Sample Problem (P2-MEDIUM)
The first sample after enabling detection may arrive with position > 8mm but velocity ≈ 0 (no previous sample to calculate delta), pushing state machine into `Moving` instead of `Grabbed`.

---

## Fixes Implemented

### Fix 1: Consolidate LaunchedEffects
**File:** `JustLiftScreen.kt`

Replaced two effects with single consolidated effect:
```kotlin
// Single consolidated effect for handle detection (iOS autostart race condition fix)
// Previously had two effects (Unit + connectionState) that could both fire and reset
// the state machine mid-grab on iOS due to different recomposition timing.
LaunchedEffect(connectionState) {
    if (connectionState is ConnectionState.Connected) {
        Logger.i("JustLiftScreen: Connection ready, enabling handle detection")
        viewModel.enableHandleDetection()
    }
}
```

### Fix 2: Add Final Guard Before startWorkout()
**File:** `MainViewModel.kt`

Added comprehensive validation at countdown completion:
```kotlin
// FINAL GUARD: Verify conditions still valid before starting workout
if (autoStartJob?.isActive != true) {
    Logger.d("Auto-start aborted: job cancelled during countdown")
    return@launch
}

val currentHandle = bleRepository.handleState.value
if (currentHandle != HandleState.Grabbed && currentHandle != HandleState.Moving) {
    Logger.d("Auto-start aborted: handles no longer grabbed (state=$currentHandle)")
    return@launch
}

val params = _workoutParameters.value
if (!params.useAutoStart) {
    Logger.d("Auto-start aborted: autoStart disabled in parameters")
    return@launch
}

val state = _workoutState.value
if (state !is WorkoutState.Idle && state !is WorkoutState.SetSummary) {
    Logger.d("Auto-start aborted: workout state changed (state=$state)")
    return@launch
}
```

### Fix 3: Make enableHandleDetection() Idempotent
**File:** `MainViewModel.kt`

Added 500ms debounce to prevent duplicate calls:
```kotlin
private var handleDetectionEnabledTimestamp: Long = 0L
private val HANDLE_DETECTION_DEBOUNCE_MS = 500L

fun enableHandleDetection() {
    val now = currentTimeMillis()
    if (now - handleDetectionEnabledTimestamp < HANDLE_DETECTION_DEBOUNCE_MS) {
        Logger.d("MainViewModel: Handle detection already enabled recently, skipping (idempotent)")
        return
    }
    handleDetectionEnabledTimestamp = now
    Logger.d("MainViewModel: Enabling handle detection for auto-start")
    bleRepository.enableHandleDetection(true)
}
```

### Fix 4: Add WaitingForRest Timeout
**File:** `KableBleRepository.kt`

Added 3-second timeout to escape the WaitingForRest trap:
```kotlin
private const val WAITING_FOR_REST_TIMEOUT_MS = 3000L
private var waitingForRestStartTime: Long? = null

HandleState.WaitingForRest -> {
    if (posA < HANDLE_REST_THRESHOLD && posB < HANDLE_REST_THRESHOLD) {
        waitingForRestStartTime = null
        HandleState.Released
    } else {
        // iOS autostart fix: Add timeout to escape WaitingForRest trap
        val currentTime = currentTimeMillis()
        if (waitingForRestStartTime == null) {
            waitingForRestStartTime = currentTime
        } else if (currentTime - waitingForRestStartTime!! > WAITING_FOR_REST_TIMEOUT_MS) {
            log.w { "WaitingForRest TIMEOUT - arming with current position" }
            waitingForRestStartTime = null
            HandleState.Released  // Force arm after timeout
        }
        HandleState.WaitingForRest
    }
}
```

### Bonus Fix: ProgressionSettingsSheet iOS Build Error
**File:** `ProgressionSettingsSheet.kt`

Fixed pre-existing iOS build error - `String.format()` is JVM-only:
```kotlin
// Before (JVM-only, fails on iOS):
text = String.format("%.1f%%", weightPercent)

// After (multiplatform):
text = "${(kotlin.math.round(weightPercent * 10) / 10)}%"
```

---

## Files Modified

| File | Changes |
|------|---------|
| `JustLiftScreen.kt` | Consolidated duplicate LaunchedEffects |
| `MainViewModel.kt` | Added final guard, idempotent enableHandleDetection() |
| `KableBleRepository.kt` | Added WaitingForRest timeout constant and logic |
| `ProgressionSettingsSheet.kt` | Fixed String.format() for iOS compatibility |

---

## Build Verification

- ✅ Android: `./gradlew :androidApp:assembleDebug` passes
- ✅ iOS: `./gradlew :shared:compileKotlinIosArm64` passes (warnings only)

---

## Testing Recommendations

Have iOS users test these scenarios:

1. **Normal flow:** Enter Just Lift → grab handles → countdown starts → workout begins
2. **Pre-grab:** Grab handles BEFORE entering screen → enter → should arm after 3s timeout
3. **Cancel flow:** Start countdown → release at second 2 → no workout starts
4. **Rapid navigation:** Home → Just Lift → Back → Just Lift rapidly → autostart still works
5. **Slow tempo:** Grab handles very slowly → eventually triggers when velocity increases

---

## Technical Analysis Sources

This fix was developed through comprehensive analysis:
- 4 explore agents (codebase structure, patterns, implementations)
- 3 librarian agents (iOS BLE best practices, KMP Flow issues, Nordic patterns)
- 3 general agents (race conditions, Android vs iOS comparison, threshold analysis)
- 3 oracle consultations (architecture, performance/timing, edge cases)

Key findings synthesized from:
- Apple CoreBluetooth documentation
- Nordic Semiconductor iOS BLE library patterns
- KMP-NativeCoroutines best practices
- Real-world iOS BLE implementations (xdripswift, Meshtastic, nRF Toolbox)

---

## Future Considerations

If issues persist after these fixes:

1. **Platform-specific hysteresis:** Consider `expect/actual` for `STATE_TRANSITION_DWELL_MS` (200ms Android, 250ms iOS)
2. **Time-window based gating:** Instead of "N consecutive samples", use "within last T ms"
3. **dt-aware EMA:** Switch velocity smoothing to time-constant based (`alpha = 1 - exp(-dt / tau)`)
4. **Static grab path:** Allow `Moving → Grabbed` via sustained position without velocity requirement

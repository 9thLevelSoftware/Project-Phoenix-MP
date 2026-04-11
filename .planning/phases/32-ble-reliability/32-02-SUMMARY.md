# Plan 32-02 Summary: State Machine Hardening

**Status:** Complete
**Agent:** Senior Developer
**Wave:** 2

## Changes
- **H2 FIXED:** Added `stateObserverJob` property. Old observer cancelled before creating new one in `connect()`. Also cancelled in `cleanupExistingConnection()`.
- **H3 FIXED:** Wrapped `onDeviceReady()` launch in try/catch. CancellationException re-thrown. Other exceptions log error, call cleanup, report Disconnected state.
- **H4 FIXED:** Restructured `startScanning()` to use `withTimeoutOrNull(SCAN_TIMEOUT_MS)`. On timeout: logs warning, reports Disconnected (only if still in Scanning state). External cancellation via `stopScanning()` propagates cleanly.
- **H10 FIXED:** BlePermissionDeniedScreen now checks `shouldShowRequestPermissionRationale`. Shows "Open Settings" with settings icon for permanent denial, "Try Again" for recoverable denial. Added lifecycle observer to auto-detect permission grants on resume from Settings.

## Files Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt` (H2, H3, H4)
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.android.kt` (H10)

## Verification
- shared:compileAndroidMain: PASS
- testDebugUnitTest: PASS
- assembleDebug: pre-existing errors in unrelated files (HapticFeedbackEffect.kt, AndroidTheme.kt)

## Pre-existing Issues Noted
- `HapticFeedbackEffect.kt` references undefined `HapticEvent` type
- `AndroidTheme.kt` references undefined `ThemeMode` and `SharedThemeMode` types
- These predate v0.8.0 changes

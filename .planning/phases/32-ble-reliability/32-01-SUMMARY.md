# Plan 32-01 Summary: BLE Connection Foundation

**Status:** Complete
**Agent:** Senior Developer
**Wave:** 1

## Changes
- **B1 FIXED:** Removed dead `_scannedDevices` MutableStateFlow from BleConnectionManager. Replaced with direct delegation to `bleRepository.scannedDevices`. `connectToDevice()` now finds devices from actual scan results.
- **B2 FIXED:** Added `reconnectionRequested` collector in BleConnectionManager.init block. Triggers `ensureConnection()` with 1500ms BLE cooldown when workout is active (Active/Countdown/Resting states). Logs request, success/failure, and skip-reason.

## Files Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`

## Verification
- assembleDebug: PASS
- testDebugUnitTest: PASS (4 pre-existing failures in unrelated tests: PersonalRecordRepositoryTest, PortalTokenStorageTest, ResolveRoutineWeightsUseCaseTest)
- `reconnectionRequested` has exactly 1 collector in production code
- `connectToDevice()` reads from `bleRepository.scannedDevices`

## Pre-existing Issues Noted
- 4 pre-existing test failures in non-BLE test suites
- Compiler warning: unnecessary `!!` in PortalSyncAdapter.kt:154

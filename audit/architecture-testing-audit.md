# Architecture, Security & Testing Audit Report

**Date:** 2026-03-31  
**Scope:** expect/actual completeness, Android/iOS platform concerns, test coverage, error handling, security, build/CI configuration  
**Status:** Audit-only (no fixes applied)

## Executive Summary

2 HIGH, 7 MEDIUM, 3 LOW severity issues identified. Key concerns are iOS token storage using unencrypted NSUserDefaults, ~30,000 lines of UI code with zero test coverage, missing iOS simulator build target, and Android unit tests not running in CI.

## Findings

### HIGH Severity

#### ARCH-H001: iOS token storage uses plain NSUserDefaults instead of Keychain
- **File:** `shared/src/iosMain/.../di/PlatformModule.ios.kt`
- **Description:** The `SecureSettingsQualifier` on iOS returns the same `NSUserDefaultsSettings` instance as general settings. Comment says "Keychain-backed storage is a future enhancement." This means JWT tokens, refresh tokens, and user email are stored in plain NSUserDefaults on iOS. On Android, `EncryptedSharedPreferences` with Android Keystore (AES-256-GCM) is used. On jailbroken devices, NSUserDefaults is trivially readable.
- **Impact:** Auth tokens exposed to any process on jailbroken iOS devices; significant security asymmetry between platforms.
- **Suggested Fix:** Implement Keychain-backed storage for iOS using the KeychainAccess library or native Security framework.

#### ARCH-H002: ~30,000 lines of UI/screen code with zero test coverage
- **File:** `shared/src/commonMain/.../presentation/screen/` (all screens), most of `presentation/components/`
- **Description:** Only `CustomExerciseActionsTest` exists for the entire UI layer. All screen composables, navigation flows, and most component composables have no automated tests.
- **Impact:** Regressions in UI behavior go undetected; manual testing is the only safety net for ~30,000 lines of code.
- **Suggested Fix:** Add snapshot tests for key screens; add interaction tests for critical flows (workout, routine editing, settings).

### MEDIUM Severity

#### ARCH-M001: No iosSimulatorArm64 target configured
- **File:** `shared/build.gradle.kts`
- **Description:** Only `iosArm64` is configured (physical devices only). No `iosX64` or `iosSimulatorArm64` targets. Cannot run iOS tests on simulator (common CI setup). Developers on Apple Silicon Macs cannot debug on simulator.
- **Impact:** Cannot execute iOS tests in CI or on developer machines without physical devices.
- **Suggested Fix:** Add `iosSimulatorArm64()` target.

#### ARCH-M002: Android unit tests not run in CI
- **File:** `.github/workflows/ci-tests.yml`
- **Description:** CI only runs `shared:testAndroidHostTest`. The `androidApp:testDebugUnitTest` task is never executed in CI.
- **Impact:** Android-specific test failures not caught before merge.
- **Suggested Fix:** Add `androidApp:testDebugUnitTest` to the CI workflow.

#### ARCH-M003: No centralized CoroutineExceptionHandler
- **File:** Global architecture concern
- **Description:** Uncaught exceptions in launched coroutines can crash the app. No global exception handler configured.
- **Impact:** Unhandled coroutine exceptions cause crashes instead of graceful error handling.
- **Suggested Fix:** Install a CoroutineExceptionHandler on the application's root scope.

#### ARCH-M004: Orphaned ProGuard rules for MediaPipe and CameraX
- **File:** `androidApp/proguard-rules.pro`
- **Description:** ProGuard keep rules exist for MediaPipe and CameraX, but these libraries are not in the dependency graph.
- **Impact:** Dead configuration; minor APK size waste from overly broad Ktor keep rule.
- **Suggested Fix:** Remove MediaPipe/CameraX rules; narrow the Ktor keep rule.

#### ARCH-M005: iOS SafeWordListener isTearingDown flag not thread-safe
- **File:** `shared/src/iosMain/.../domain/voice/SafeWordListener.ios.kt`
- **Description:** Plain Boolean accessed from dispatch queues without synchronization. Concurrent dispatch could bypass the reentrancy guard.
- **Impact:** Potential double teardown of audio resources.
- **Suggested Fix:** Use `AtomicBoolean` or dispatch queue synchronization.

#### ARCH-M006: Unencrypted backup files on both platforms
- **File:** `shared/src/commonMain/.../util/DataBackupManager.kt`
- **Description:** Backups are unencrypted JSON files containing all workout data, routines, and personal records. On Android they go to app-private external storage (accessible via ADB); on iOS to Documents (accessible via iTunes/Files). No auth tokens are included.
- **Impact:** Workout history data accessible if device is compromised or connected to computer.
- **Suggested Fix:** Offer optional encryption for backups; at minimum, document the security implications.

#### ARCH-M007: No code coverage reporting in CI
- **File:** `.github/workflows/ci-tests.yml`
- **Description:** No JaCoCo or Kover integration. No coverage reports generated or tracked.
- **Impact:** No visibility into coverage trends; cannot enforce coverage thresholds.
- **Suggested Fix:** Integrate Kover plugin for KMP code coverage.

### LOW Severity

#### ARCH-L001: Spotless formatting plugin declared but not applied in CI
- **File:** `build.gradle.kts`, `.github/workflows/ci-tests.yml`
- **Description:** Spotless is configured in the build file but `spotlessCheck` is not run in CI.
- **Impact:** Code style inconsistencies can be merged.

#### ARCH-L002: Migration error handling uses silent runCatching
- **File:** Various migration-related code
- **Description:** Some migration code uses `runCatching { ... }.getOrElse { emptyList() }`. Intentional for "table might not exist" scenarios but could mask real failures.
- **Impact:** Real migration failures could be silently swallowed.

#### ARCH-L003: NSLog calls in iOS database initialization
- **File:** `shared/src/iosMain/.../data/local/DriverFactory.ios.kt`
- **Description:** Uses `NSLog` which goes to system logs. Contains schema version info and diagnostic counts but no sensitive data.
- **Impact:** Minor information disclosure in system logs.

## expect/actual Analysis

All 27 `expect` declarations in commonMain have matching `actual` implementations in both `androidMain` and `iosMain`. No mismatches detected. Compiler flag `-Xexpect-actual-classes` is correctly set.

## Test Coverage Matrix

### Well-Tested Modules
| Module | Test Files | Test Cases |
|--------|-----------|------------|
| domain/usecase/ | 7 | ~50+ |
| domain/premium/ | 7 | ~40+ |
| domain/model/ | 6 | ~30+ |
| domain/detection/ | 2 | ~20+ |
| data/ble/ | 7 | ~130+ |
| data/sync/ | 6 | ~40+ |
| data/repository/ | 9 | ~60+ |
| presentation/manager/ | 6 | ~40+ |
| presentation/viewmodel/ | 7 | ~30+ |
| util/ | 5 | ~25+ |

### Modules with Zero Test Coverage
| Module/Area | Est. Lines | Priority |
|-------------|-----------|----------|
| presentation/screen/ (ALL screens) | ~15,000 | HIGH |
| presentation/components/ (most) | ~8,000 | HIGH |
| data/migration/TrainingCycleMigration.kt | ~200 | HIGH (critical data migration) |
| domain/voice/SafeWordDetectionManager.kt | ~100 | MEDIUM (safety feature) |
| navigation/NavGraph.kt | ~300 | MEDIUM |
| AssessmentViewModel, EulaViewModel, LinkAccountViewModel | ~400 | MEDIUM |

## Build System Findings

### Migration Verification Broken
- **Task:** `verifyCommonMainVitruvianDatabaseMigration`
- **Status:** FAILS with `UnsatisfiedLinkError: no sqlitejdbc in java.library.path`
- **Impact:** Database migration verification cannot run on this machine. The SQLite JDBC native library loader cannot find the native binary, meaning migration chain correctness is NOT being validated by the build system.
- **Root Cause:** The Gradle `VerifyMigrationTask` runs in a classloader-isolated worker where `java.io.tmpdir` may resolve to an inaccessible directory on Windows. The workaround in `build.gradle.kts` sets `java.io.tmpdir` but it's not taking effect for the native library loader.

### Schema Manifest Validation
- **Task:** `validateSchemaManifest`  
- **Status:** PASSES (284 columns across 29 tables, all covered)

## CI/CD Analysis

### What runs:
- Unit tests: `shared:testAndroidHostTest`
- Android build: `assembleDebug`
- iOS compile check (no execution)
- iOS schema sync validation
- Android lint: `lintDebug`
- Test result publishing

### What's missing:
- Android app unit tests (`androidApp:testDebugUnitTest`)
- Instrumented/E2E tests
- iOS test execution (only compiled)
- Code coverage reporting
- Spotless/formatting enforcement

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 2 |
| Medium | 7 |
| Low | 3 |
| **Total** | **12** |

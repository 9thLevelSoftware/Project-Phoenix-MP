# Phase 34: Lifecycle & Security — Context

## Phase Goal
Fix crash paths in Android lifecycle and harden security for beta users. 7 findings (2 BLOCKER, 3 HIGH, 2 MEDIUM). B6 fix should resolve the last pre-existing test failure (PortalTokenStorageTest).

## Requirements
- LIFE-01: Fix START_STICKY null intent foreground service crash (B5 BLOCKER)
- LIFE-02: Encrypt auth tokens with EncryptedSharedPreferences (B6 BLOCKER)
- LIFE-03: Add top-level exception handler to workoutJob (H1 HIGH)
- LIFE-04: Gate Coil DebugLogger on BuildConfig.DEBUG (H9 HIGH)
- LIFE-05: Fix ActivityHolder WeakReference lifecycle (H11 HIGH)
- LIFE-06: Set allowBackup=false in manifest (M6 MEDIUM)
- LIFE-07: Uncomment ProGuard log-stripping rules (M7 MEDIUM)

## Key Files
| File | Role | Plans |
|------|------|-------|
| `androidApp/.../service/WorkoutForegroundService.kt` | Foreground service | 1 |
| `shared/.../data/sync/PortalTokenStorage.kt` | Token storage | 1 |
| `shared/.../di/PlatformModule.android.kt` | Android DI | 1 |
| `shared/.../presentation/manager/ActiveSessionEngine.kt` | Workout orchestration | 2 |
| `androidApp/.../VitruvianApp.kt` | Application class | 2 |
| `shared/.../util/ScreenUtils.android.kt` | ActivityHolder | 2 |
| `androidApp/src/main/AndroidManifest.xml` | Manifest | 3 |
| `androidApp/proguard-rules.pro` | ProGuard config | 3 |

## Pre-existing Test Failure
- `PortalTokenStorageTest.clearAuthPreservesDeviceIdAndLastSyncTimestamp` — B6 token storage changes should address this

## Plan Structure
| Plan | Wave | Findings | Agent |
|------|------|----------|-------|
| 34-01: Security Blockers | 1 | B5, B6 | Senior Developer |
| 34-02: Lifecycle Hardening | 2 | H1, H9, H11 | Senior Developer |
| 34-03: Build Hygiene | 2 | M6, M7 | Senior Developer |

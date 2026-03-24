# Exploration: Beta Readiness Audit Fixes

## Crystallized Summary

A comprehensive code audit of Project Phoenix MP identified 30 beta-blocking issues across 4 subsystems (BLE, Sync/Data, Lifecycle/Security, iOS Platform) at 3 severity tiers (8 BLOCKER, 15 HIGH, 7 MEDIUM). This milestone addresses 29 of those findings (H8 SavedStateHandle deferred to v0.9.0) in a single v0.8.0 "Beta Readiness" milestone, phased by subsystem with 3 plans per phase.

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Milestone structure | Single v0.8.0 | Clean version boundary, "no beta until fixed" framing |
| Phasing strategy | By subsystem | Changes within each phase touch related files, minimizes cross-cutting risk |
| Cross-repo H6 | Include in v0.8.0 | Keeps all sync integrity fixes together; cross-repo work done before in v0.6.0 |
| H8 SavedStateHandle | Defer to v0.9.0 | Foreground service mostly prevents process death during active workouts; full refactor too risky for bug-fix milestone |
| Plan density | 3 plans per phase | Blockers plan, high-severity plan, medium+cleanup plan per phase; ~15 plans total |

## Milestone: v0.8.0 Beta Readiness

**Branch:** TBD (from MVP)
**Scope:** 29 audit findings across 5 phases, 15 plans
**Cross-repo:** Phase 33 Plan B touches `phoenix-portal/supabase/functions/mobile-sync-push/index.ts`

### Phase 32: BLE Reliability (8 findings)

**Goal:** Fix the BLE connection chain so devices can connect, reconnect, and maintain stable sessions.

**Plan A — Blockers (B1, B2):**
- B1: `BleConnectionManager.connectToDevice()` reads always-empty `_scannedDevices` StateFlow. Fix: read from `bleRepository.scannedDevices.value` instead.
  - File: `shared/src/commonMain/kotlin/.../presentation/manager/BleConnectionManager.kt:44-130`
- B2: Auto-reconnect flow emitted by `KableBleConnectionManager` but never consumed. Fix: add collector in `BleConnectionManager.init` that triggers `bleRepository.scanAndConnect()` on reconnection requests with backoff delay.
  - Files: `KableBleRepository.kt:125`, `BleConnectionManager.kt`

**Plan B — High (H2, H3, H4, H10):**
- H2: Old peripheral state observer job never cancelled on reconnect — stale events corrupt connection state machine. Fix: store Job reference, cancel in `cleanupExistingConnection()`.
  - File: `KableBleConnectionManager.kt:447-532`
- H3: `onDeviceReady()` launched fire-and-forget — initialization failures crash app while showing "Connected". Fix: wrap in try/catch, call `disconnect()` on failure.
  - File: `KableBleConnectionManager.kt:469`
- H4: `startScanning()` has no timeout — scan runs forever. Fix: wrap in `withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS)`.
  - File: `KableBleConnectionManager.kt:166-322`
- H10: BLE permission denied loop — "Try Again" button does nothing on Android 11+ after first denial. Fix: detect `shouldShowRequestPermissionRationale()`, show "Open Settings" button for permanent denial.
  - File: `BlePermissionHandler.android.kt:204-268`

**Plan C — Medium (M1, M2):**
- M1: Permanent `init` collectors in `ActiveSessionEngine` have no `.catch {}` — any exception kills the collector permanently. Fix: add `.catch {}` to each permanent collector.
  - File: `ActiveSessionEngine.kt:174, 225, 272, 281`
- M2: `CONNECTION_RETRY_DELAY_MS = 100ms` — too short for Android BLE. Fix: increase to 1500ms.
  - File: `BleConstants.kt:163`

**Success criteria:**
- Device connection works from scan results screen
- BLE reconnection triggers automatically on unexpected disconnect
- Scan stops after timeout period
- Permission denial shows appropriate action button per Android version

---

### Phase 33: Sync & Data Integrity (6 findings)

**Goal:** Fix data corruption paths in sync push/pull and ensure profile isolation.

**Plan A — Blockers (B3, B4):**
- B3: `selectSessionsModifiedSince`, `selectPRsModifiedSince`, `selectRoutinesModifiedSince`, `selectCustomExercisesModifiedSince` all lack `AND profile_id = :profileId`. Fix: add filter to all four queries, pass active profile ID from `SqlDelightSyncRepository`.
  - File: `VitruvianDatabase.sq:1648-1704`
- B4: `PersonalRecordSyncDto` missing `prType`, `phase`, `volume` fields. Fix: add fields with defaults, update merge code in `SqlDelightSyncRepository` to use compound key.
  - File: `SyncModels.kt:96-110`

**Plan B — High (H5, H6):**
- H5: First sync sends entire history with no chunking — fails on large payloads, retries forever. Fix: implement batch-size limit (e.g., 50 sessions per push), update `lastSyncTimestamp` per batch.
  - File: `SyncManager.kt:129-133`
- H6: Push Edge Function has no transaction — `routine_exercises` delete+insert can leave routines empty on partial failure. Fix: wrap routine_exercises delete+reinsert in Supabase RPC function with transaction, or switch to upsert-on-conflict with soft-delete.
  - File: `phoenix-portal/supabase/functions/mobile-sync-push/index.ts:490-855` **(cross-repo)**

**Plan C — Medium (M3, M4):**
- M3: `updatedAt IS NULL` in push query causes perpetual re-push of locally recorded sessions. Fix: after successful push, update `updatedAt` on all pushed sessions.
  - File: `VitruvianDatabase.sq:1650`
- M4: `Instant.parse()` failure in `SyncManager.sync()` leaves `_syncState` stuck at `Syncing` indefinitely. Fix: wrap in try/catch, map to `SyncState.Error`.
  - File: `SyncManager.kt:106`

**Success criteria:**
- Multi-profile sync only pushes active profile's data
- PR records survive round-trip push/pull with correct prType and phase
- First sync completes for users with 6+ months of history
- Routine exercises survive partial sync failures
- No perpetual re-push of already-synced sessions

---

### Phase 34: Lifecycle & Security (7 findings)

**Goal:** Fix crash paths in Android lifecycle and harden security for beta users.

**Plan A — Blockers (B5, B6):**
- B5: `START_STICKY` + null intent crashes foreground service on system restart (Android 8+ throws `RemoteServiceException`). Fix: handle `intent == null` explicitly to call `startForeground()`, or switch to `START_NOT_STICKY`.
  - File: `WorkoutForegroundService.kt:58-74`
- B6: Supabase JWT access token, refresh token, and email stored in plaintext `SharedPreferences`. Fix: use `EncryptedSharedPreferences` from Jetpack Security (or `multiplatform-settings` `settings-secure` artifact) for the token store.
  - Files: `PortalTokenStorage.kt:36-51`, `PlatformModule.android.kt:24-27`

**Plan B — High (H1, H9, H11):**
- H1: `workoutJob` in `ActiveSessionEngine` has no top-level exception handler — uncaught DB/IO exceptions silently lose the workout session. Fix: add try/catch around entire workout coroutine body, emit error event, transition to Idle.
  - File: `ActiveSessionEngine.kt:1547`
- H9: Coil `DebugLogger` unconditionally active in release builds — logs all image URLs to logcat. Fix: gate on `BuildConfig.DEBUG`.
  - File: `VitruvianApp.kt:57`
- H11: `ActivityHolder` stores `WeakReference<Activity>` in singleton, never cleared in `onDestroy()`. Fix: use `Application.registerActivityLifecycleCallbacks()` to maintain current activity reference.
  - File: `ScreenUtils.android.kt:11-18`

**Plan C — Medium (M6, M7):**
- M6: `allowBackup="true"` in manifest — future data stores backed up by default. Fix: set `android:allowBackup="false"`.
  - File: `AndroidManifest.xml:56`
- M7: ProGuard log-stripping rules commented out — migration SQL and DB schema logged in release builds. Fix: uncomment rules or replace `android.util.Log` calls with Kermit Logger.
  - File: `proguard-rules.pro:115-120`, `DriverFactory.android.kt`

**Success criteria:**
- Foreground service handles system restart without crash
- Auth tokens encrypted at rest
- Workout session data preserved on DB/IO error (error shown to user)
- No debug logging in release builds
- Backup exclusion covers all sensitive data

---

### Phase 35: iOS Platform Parity (6 findings)

**Goal:** Fix iOS-specific runtime failures and document platform feature gaps.

**Plan A — Blockers (B7, B8):**
- B7: iOS `CURRENT_SCHEMA_VERSION = 21L` but SQLDelight `version = 22` — database wiped every cold launch. Fix: update constant to `22L`.
  - File: `DriverFactory.ios.kt:57`
- B8: `formScore` column missing from iOS manual schema — session queries crash with "no such column: formScore". Fix: add `safeAddColumn(driver, "WorkoutSession", "formScore", "INTEGER")` to `ensureAllColumnsExist` and add column to `createAllTables`.
  - File: `DriverFactory.ios.kt` (createAllTables / ensureAllColumnsExist)

**Plan B — High (H12, H13):**
- H12: `ConnectivityChecker.ios.kt` always returns `true` (stub) — syncs attempted on airplane mode. Fix: implement using `Network.framework` `NWPathMonitor`.
  - File: `ConnectivityChecker.ios.kt`
- H13: `withPlatformLock` ignores lock identity — single global `NSRecursiveLock` serializes all callers. Fix: document which callers are time-sensitive; consider per-object lock map with cleanup.
  - File: `Locking.ios.kt`

**Plan C — High (H14, H15):**
- H14: CV Form Check (MediaPipe/CameraX) is Android-only with no iOS equivalent or UI gate. Fix: gate feature behind platform check in shared UI — hide camera button on iOS.
  - File: `PoseLandmarkerHelper.kt` (Android), shared UI screens
- H15: Vico chart library in `androidMain` — confirm no shared-code leakage to iOS target. Fix: verify no Vico imports in `commonMain`/`iosMain`; add CI guard.
  - File: `build.gradle.kts:151`

**Success criteria:**
- iOS app launches without database wipe
- Session history screen loads on iOS
- Sync fails fast on airplane mode (iOS)
- Form Check camera button hidden on iOS
- iOS build compiles with no unresolved references

---

### Phase 36: Integration Validation (3 plans)

**Goal:** End-to-end verification that all fixes work together across platforms.

**Plan A — BLE Validation:**
- Test: connect to device from scan results
- Test: simulate BLE disconnect and verify auto-reconnect triggers
- Test: permission denial → Settings flow on Android 11+
- Test: scan timeout fires after configured period

**Plan B — Sync Validation:**
- Test: E2E push/pull cycle (sign up → sync → verify on portal)
- Test: multi-profile sync isolation (Profile A push doesn't include Profile B data)
- Test: first sync with large history completes (chunked)
- Test: routine edit + sync + partial failure recovery

**Plan C — iOS Validation:**
- Test: iOS cold launch — database not wiped, sessions load
- Test: iOS sync on WiFi succeeds
- Test: iOS airplane mode — sync fails fast with error
- Test: form check camera button not visible on iOS

**Success criteria:**
- All BLE, Sync, and iOS test scenarios pass
- No regressions in existing unit tests
- Release build (Android) and TestFlight build (iOS) compile and run

---

## Deferred to v0.9.0

| Finding | Reason |
|---------|--------|
| H8: SavedStateHandle (process death recovery) | Architectural refactor too risky for bug-fix milestone. Foreground service partially mitigates during active workouts. |

## Knowns

- All 30 findings are backed by specific code evidence with file paths and line numbers
- Build compiles cleanly (1 warning: unnecessary `!!` in PortalSyncAdapter.kt:154)
- All existing unit tests pass
- Cross-repo change (H6) is in phoenix-portal Edge Function — same pattern as v0.6.0
- iOS manual schema management is the root cause of B7/B8 — longer-term fix is migrating to SQLDelight's native driver migration path
- BLE subsystem has the most interconnected fixes (B1 must precede B2 testing)

## Unknowns

- Whether `EncryptedSharedPreferences` (B6) works correctly with `multiplatform-settings` library — may need to test the `settings-secure` artifact
- Whether chunked sync (H5) needs changes to the Edge Function's rate limiter or is client-side only
- Whether the iOS `NWPathMonitor` (H12) Kotlin/Native wrapper compiles cleanly — K/N interop with Network.framework may need specific annotations
- Exact migration path for existing users with plaintext tokens (B6) — need migration strategy from old prefs to encrypted prefs

## Audit Source

Findings from a comprehensive 4-agent parallel audit conducted 2026-03-23:
- Agent 1: BLE Reliability + Coroutine Error Handling
- Agent 2: SQLDelight Data Integrity + Supabase Sync Correctness
- Agent 3: Android Lifecycle Safety + Security
- Agent 4: iOS Platform Parity

Build verification: `assembleDebug` PASS, `testDebugUnitTest` PASS.

---
*Crystallized: 2026-03-23 via /legion:explore*

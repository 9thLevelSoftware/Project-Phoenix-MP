---
phase: 26-mobile-push-wire-up
plan: 02
status: complete
---

# Plan 26-02 Summary: Atomic Push Flow Rewrite

## What Was Done

### Part A: isPr field in PortalSyncAdapter
- Added `isPr: Boolean = false` to `SessionWithReps` data class
- Changed `buildPortalExercise()` from hardcoded `isPr = false` to `isPr = swr.isPr`

### Part B: Rewritten pushLocalChanges()
- Returns `Result<PortalSyncPushResponse>` (was `Result<SyncPushResponse>`)
- Gathers full `WorkoutSession` domain objects via `syncRepository.getWorkoutSessionsModifiedSince()`
- Fetches PRs via `syncRepository.getPRsModifiedSince()` and builds composite key set for PR detection
- Builds `SessionWithReps` with rep metrics from `repMetricRepository.getRepMetrics()` and `isPr` from PR lookup
- Gathers full `Routine` domain objects via `syncRepository.getFullRoutinesModifiedSince()`
- Computes RPG attributes via `RpgAttributeEngine.computeProfile()`
- Maps earned badges via `BadgeDefinitions.getBadgeById()` with ISO 8601 timestamps
- Maps gamification stats with `totalVolumeKg.toFloat()` and `totalTimeSeconds = 0`
- Builds `PortalSyncPayload` and sends via `apiClient.pushPortalPayload()`
- No `updateServerIds()` call (portal uses client UUIDs)

### Part C: Rewritten sync()
- Removed `getSyncStatus()` call (Railway abandoned)
- Push result parsed: ISO 8601 `syncTime` → epoch millis via `kotlinx.datetime.Instant.parse()`
- Pull short-circuited entirely — no `pullRemoteChanges()` call (Phase 27 TODO)

### Part D: checkStatus() made no-op
- Returns failure with "Status check not available during portal migration"

### Part E: Cleanup
- Removed `pullRemoteChanges()` and `getDeviceName()` private methods (no longer called)
- All legacy references (`SyncPushRequest`, `getSyncStatus`, `pullRemoteChanges`) removed from SyncManager

## PUSH Requirements Satisfied
- **PUSH-01**: PortalSyncAdapter builds hierarchical PortalSyncPayload
- **PUSH-02**: userId on DTOs from tokenStorage.currentUser (Edge Function overwrites from JWT)
- **PUSH-03**: exercise_progress computed server-side (no mobile logic)
- **PUSH-04**: personal_records extracted server-side from isPr sets (correctly set via PR lookup)
- **PUSH-05**: Rep telemetry explicitly excluded (not in PortalSyncPayload)

## Files Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`

## Verification
- `./gradlew :shared:compileCommonMainKotlinMetadata` — BUILD SUCCESSFUL
- PortalSyncPayload constructed with sessions, routines, rpgAttributes, badges, gamificationStats
- No reference to SyncPushRequest, getSyncStatus, or pullRemoteChanges in SyncManager
- isPr wired through: PR lookup → SessionWithReps.isPr → PortalSetDto.isPr

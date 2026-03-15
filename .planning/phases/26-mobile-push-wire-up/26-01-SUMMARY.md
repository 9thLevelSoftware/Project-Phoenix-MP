---
phase: 26-mobile-push-wire-up
plan: 01
status: complete
---

# Plan 26-01 Summary: Push Infrastructure Plumbing

## What Was Done

### Task 1: PortalSyncPushResponse DTO + pushPortalPayload() API method
- Added `PortalSyncPushResponse` data class to `PortalSyncDtos.kt` with ISO 8601 `syncTime` and 8 count fields
- Added `pushPortalPayload(PortalSyncPayload): Result<PortalSyncPushResponse>` to `PortalApiClient.kt`
- Targets `{supabaseUrl}/functions/v1/mobile-sync-push` with both `bearerAuth` and `apikey` headers
- Legacy `pushChanges()` and `getSyncStatus()` methods kept temporarily

### Task 2: Full-object query methods + SyncManager DI expansion
- Added `getWorkoutSessionsModifiedSince(Long): List<WorkoutSession>` to `SyncRepository` interface
- Added `getFullRoutinesModifiedSince(Long): List<Routine>` to `SyncRepository` interface
- Implemented both in `SqlDelightSyncRepository` with full row-to-domain mappers replicated from `SqlDelightWorkoutRepository`
- Expanded `SyncManager` constructor: added `gamificationRepository: GamificationRepository` and `repMetricRepository: RepMetricRepository`
- Updated `SyncModule.kt` with explicit named parameters for all 5 dependencies
- `WorkoutRepository` is NOT injected into SyncManager (not needed)

## Pre-existing Fix
- Fixed `ReadinessBriefingCard.kt:155` — `String.format()` is JVM-only, replaced with KMP-compatible `kotlin.math.round()` approach

## Files Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SyncRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ReadinessBriefingCard.kt`

## Verification
- `./gradlew :shared:compileCommonMainKotlinMetadata` — BUILD SUCCESSFUL

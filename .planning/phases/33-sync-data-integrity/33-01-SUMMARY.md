# Plan 33-01 Summary: Data Integrity Blockers

**Status:** Complete
**Agent:** Senior Developer
**Wave:** 1

## Changes
- **B3 FIXED:** Added `AND profile_id = :profileId` to 3 of 4 sync push queries (selectSessionsModifiedSince, selectPRsModifiedSince, selectRoutinesModifiedSince). Exercise table lacks profile_id (global by design) — correctly skipped. Updated SyncRepository interface, SqlDelightSyncRepository, SyncManager, FakeSyncRepository.
- **B4 FIXED:** Added `prType: String = "MAX_WEIGHT"`, `phase: String = "COMBINED"`, `volume: Float = 0f` to PersonalRecordSyncDto. Updated getPRsModifiedSince() to populate from DB. Updated mergePRs() to use compound key.
- **Test fix 1:** PersonalRecordRepositoryTest — 2 wrong volume expectations (expected display weight × 2, corrected to per-cable values)
- **Test fix 2:** ResolveRoutineWeightsUseCase — production bug: mode-specific PR lookup calling wrong overload. Fixed to use 3-arg mode-specific method.

## Files Modified
- `VitruvianDatabase.sq` — B3 query filters
- `SyncRepository.kt` — B3 interface profileId parameter
- `SqlDelightSyncRepository.kt` — B3+B4 implementation
- `SyncManager.kt` — B3 activeProfileId passing
- `SyncModels.kt` — B4 DTO fields
- `ResolveRoutineWeightsUseCase.kt` — production bug fix
- `PersonalRecordRepositoryTest.kt` — test expectation fixes
- `FakeSyncRepository.kt` — interface compliance

## Verification
- assembleDebug: PASS
- testAndroidHostTest: 3 of 4 pre-existing failures resolved (1 remaining: PortalTokenStorageTest — unrelated)

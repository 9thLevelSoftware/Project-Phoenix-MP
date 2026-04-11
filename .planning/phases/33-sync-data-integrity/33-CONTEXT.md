# Phase 33: Sync & Data Integrity — Context

## Phase Goal
Fix data corruption paths in sync push/pull and ensure profile isolation. This phase addresses 6 audit findings (2 BLOCKER, 2 HIGH, 2 MEDIUM). B4 fix should also resolve 3 of 4 pre-existing test failures.

## Requirements
- SYNC-01: Add profile_id filter to all sync push queries (B3 BLOCKER)
- SYNC-02: Add prType/phase/volume to PersonalRecordSyncDto (B4 BLOCKER)
- SYNC-03: Implement chunked first sync for large histories (H5 HIGH)
- SYNC-04: Wrap routine_exercises push in database transaction (H6 HIGH, cross-repo)
- SYNC-05: Fix updatedAt IS NULL perpetual re-push (M3 MEDIUM)
- SYNC-06: Handle Instant.parse() failure in SyncManager (M4 MEDIUM)

## Key Files
| File | Lines | Role | Plans |
|------|-------|------|-------|
| `shared/.../sqldelight/.../VitruvianDatabase.sq` | ~1740 | Database queries | 1, 3 |
| `shared/.../data/sync/SyncModels.kt` | ~164 | Sync DTOs | 1 |
| `shared/.../data/sync/SyncManager.kt` | ~346 | Sync orchestration | 2, 3 |
| `shared/.../data/sync/SqlDelightSyncRepository.kt` | — | Repository using queries | 1 |
| `phoenix-portal/supabase/functions/mobile-sync-push/index.ts` | ~1070 | Push Edge Function | 2 |

## Cross-Repo Note
H6 requires modifying `phoenix-portal/supabase/functions/mobile-sync-push/index.ts`. The routine_exercises pattern (delete all → reinsert) at lines 756-797 is not transactional. Same issue exists for cycle_days at lines 802-847.

## Pre-existing Test Failures Mapped to This Phase
- `PersonalRecordRepositoryTest.getBestPR` — expected 800.0 got 400.0 (weight multiplier / PR type)
- `PersonalRecordRepositoryTest.getBestVolumePR` — expected 900.0 got 450.0 (same)
- `ResolveRoutineWeightsUseCaseTest.respectsProgramMode` — expected 40.0 got 50.0 (PR mode filtering)

These should be addressed by B4's PR DTO completeness fix.

## Prior Phase Output
Phase 32 (BLE Reliability): All 8 BLE findings fixed. BleConnectionManager, KableBleConnectionManager, BlePermissionHandler, ActiveSessionEngine, BleConstants modified.

## Plan Structure
| Plan | Wave | Findings | Agent |
|------|------|----------|-------|
| 33-01: Data Integrity Blockers | 1 | B3, B4 | Senior Developer |
| 33-02: Sync Robustness | 2 | H5, H6 | Senior Developer |
| 33-03: Sync Polish | 2 | M3, M4 | Senior Developer |

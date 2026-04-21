# Phase 39: Routine Cluster — Review Summary

## Result: PASSED

- **Cycles used**: 1
- **Reviewers**: testing-qa-verification-specialist, engineering-backend-architect, testing-test-results-analyzer, engineering-mobile-app-builder
- **Panel mode**: Dynamic (4 reviewers, 2 divisions: Testing + Engineering)
- **Completion date**: 2026-04-21

## Findings Summary

| Severity | Found | Resolved | Remaining |
|----------|-------|----------|-----------|
| BLOCKER | 2 | 2 | 0 |
| WARNING | 6 | 6 | 0 |
| SUGGESTION | 7 | 3 | 4 (deferred) |

## Findings Detail

| # | Severity | File(s) | Issue | Fix Applied | Cycle |
|---|----------|---------|-------|-------------|-------|
| 1 | BLOCKER | VitruvianDatabase.sq, SqlDelightWorkoutRepository.kt, DataBackupManager.kt, SqlDelightSyncRepository.kt | groupId missing from insertRoutine/upsertRoutine — data loss on save, sync, and restore | Added groupId to all SQL queries and callers | 1 |
| 2 | BLOCKER | RoutinesTab.kt | "New Group..." flow creates group but doesn't move routines | Wired pendingMoveRoutineIds + LaunchedEffect chain | 1 |
| 3 | WARNING | RoutineFlowManager.kt:168 | groupRepo unsafe cast to SqlDelightWorkoutRepository | Changed to safe cast with null-safe calls | 1 |
| 4 | WARNING | RoutineFlowManager.kt | Group collector lacks retry logic | Added 3-retry exponential backoff | 1 |
| 5 | WARNING | RoutinesTab.kt | Selection mode not exited when last item deselected | Auto-exit on empty selectedIds | 1 |
| 6 | WARNING | RoutineGroupTest.kt | ON DELETE SET NULL only simulated | Added coverage note referencing SchemaParityTest | 1 |
| 7 | WARNING | SchemaParityTest.kt | Comments reference version 26 | Updated to version 27 | 1 |
| 8 | WARNING | Test suite | No backup v3 backward compat test | Added backupV2 test in RegressionGuardTest | 1 |
| 9 | SUGGESTION | SchemaManifest.kt | Routine table omits groupId | Fixed — added to CREATE TABLE | 1 |
| 10 | SUGGESTION | SupersetReorderTest.kt | No out-of-bounds test | Added reorder_outOfBoundsIndex test | 1 |
| 11 | SUGGESTION | SupersetReorderTest.kt | Missing orderInSuperset assertions | Added to preservesOtherSupersets test | 1 |
| 12 | SUGGESTION | RoutinesTab.kt | Collapsed groups no animation | Deferred — performance tradeoff in LazyColumn |  |
| 13 | SUGGESTION | MoveToGroupDialog.kt | GroupNameDialog no auto-focus | Deferred — consistent with existing dialogs |  |
| 14 | SUGGESTION | 27.sqm | SQLite version note | Deferred — low risk |  |
| 15 | SUGGESTION | RoutineUtils.kt | KDoc for preserved fields | Deferred — cosmetic |  |

## Pre-existing Issues Fixed

- 5 test files calling `insertRoutine` without new `groupId` parameter (MigrationManagerTest, SqlDelightCompletedSetRepositoryTest, SqlDelightTrainingCycleRepositoryTest, DataBackupManagerRoutineNameTest, ConflictResolutionTest)

## Reviewer Verdicts

| Reviewer | Final Verdict | Key Observations |
|----------|--------------|------------------|
| testing-qa-verification-specialist | No — with fixes | Found both BLOCKERs: backup data loss + broken "New Group" flow |
| engineering-backend-architect | No — with fixes | Found groupId SQL root cause across all data paths |
| testing-test-results-analyzer | Yes, with fixes | Good test isolation, no flakiness risk; coverage gaps identified |
| engineering-mobile-app-builder | Yes, with fixes | Found cast leak, retry gap, selection mode bug; praised RoutineGroupHeader |

## Verification

- `assembleDebug`: BUILD SUCCESSFUL
- `testAndroidHostTest`: 1529/1529 pass (1 pre-existing failure: PortalPullPaginationTest)
- All 22 Phase 39 tests + 3 new tests pass

## Suggestions (Deferred)

- Collapse animation in RoutinesTab grouped sections (AnimatedVisibility in LazyColumn)
- Auto-focus in GroupNameDialog
- SQLite version documentation in migration 27
- KDoc improvements in RoutineUtils.kt

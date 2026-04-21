# Plan 39-02 Summary: Routine Parent Grouping

## Result
**Status**: Complete
**Wave**: 1
**Agent**: engineering-senior-developer
**Completed**: 2026-04-21

## Completed Tasks
- Added RoutineGroup data class and groupId field to Routine domain model
- Created RoutineGroup SQLDelight table with profile_id FK and index
- Created migration 27.sqm (RoutineGroup table + Routine.groupId ON DELETE SET NULL)
- Fixed pre-existing migration 26 gap in MigrationStatements.kt
- Implemented full RoutineGroup CRUD in SqlDelightWorkoutRepository
- Updated Routine mappers to include groupId throughout read/write paths
- Created RoutineGroupHeader composable (collapsible, overflow menu)
- Created MoveToGroupDialog composable (group picker + create new group)
- Transformed RoutinesTab from flat list to grouped collapsible sections
- Wired ViewModel, RoutineFlowManager, WorkoutCoordinator for group operations
- Added RoutineGroupBackup to backup system, bumped version to 3
- Export/import ordering: groups before routines for FK integrity

## Files Modified
- `domain/model/Routine.kt` — RoutineGroup data class, groupId on Routine
- `sqldelight/.../VitruvianDatabase.sq` — RoutineGroup table, 8 queries, groupId FK
- `sqldelight/.../migrations/27.sqm` (NEW) — Migration SQL
- `data/local/MigrationStatements.kt` — Cases 26 (gap fix) + 27
- `data/repository/SqlDelightWorkoutRepository.kt` — groupId mapping, 5 CRUD methods
- `presentation/screen/RoutineGroupHeader.kt` (NEW) — Collapsible group header
- `presentation/screen/MoveToGroupDialog.kt` (NEW) — Group picker + create dialog
- `presentation/screen/RoutinesTab.kt` — Grouped sections, dialogs
- `presentation/screen/DailyRoutinesScreen.kt` — Group callbacks wiring
- `presentation/manager/WorkoutCoordinator.kt` — routineGroups StateFlow
- `presentation/manager/RoutineFlowManager.kt` — Group collector, CRUD methods
- `presentation/manager/DefaultWorkoutSessionManager.kt` — Group CRUD delegation
- `presentation/viewmodel/MainViewModel.kt` — routineGroups exposure
- `util/BackupModels.kt` — RoutineGroupBackup, version 3
- `util/DataBackupManager.kt` — Group export/import

## Verification Results
| Command | Exit Code | Result |
|---------|-----------|--------|
| `bundleLibRuntimeToDirAndroidMain` | 0 | PASS |
| `assembleDebug` | 0 | PASS |
| `testDebugUnitTest` | 0 | PASS |

## Verification Commands
- Verification Commands Run: 5
- Verification Passed: 5
- Verification Failed: 0

## Key Decisions
1. RoutineGroup CRUD on concrete SqlDelightWorkoutRepository, not interface (groups local-only)
2. RoutineFlowManager casts to concrete repo via groupRepo property
3. Migration 26 gap fixed alongside 27 addition
4. Backup version bumped to 3 with FK-aware import ordering
5. Collapse state ephemeral (mutableStateMapOf, default expanded)
6. RoutineCardWithActions extracted to reduce duplication in ungrouped vs grouped sections

## Issues Encountered
- Pre-existing: HapticEvent/ThemeMode unresolved references in androidApp (not caused by changes)
- Pre-existing: Logger deprecated warnings throughout codebase

## Escalations
None.

## Handoff Context
- **Key outputs**: RoutineGroup entity, migration 27, CRUD, grouped RoutinesTab, backup v3
- **Decisions made**: Local-only groups (no sync), concrete repo CRUD, backup FK ordering
- **Open questions**: None
- **Conventions established**: New UI components in presentation/screen/, ephemeral collapse state pattern

## Requirements Covered
- ROUTINE-02 (#307): Routine parent grouping

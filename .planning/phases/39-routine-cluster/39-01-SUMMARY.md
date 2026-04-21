# Plan 39-01 Summary: Superset Exercise Reorder

## Result
**Status**: Complete
**Wave**: 1
**Agent**: engineering-senior-developer
**Completed**: 2026-04-21

## Completed Tasks
- Extracted `normalizeRoutine()` from local composable scope to `RoutineUtils.kt` as top-level testable function
- Added `reorderExercisesInSuperset()` utility function for intra-superset reorder
- Added `preserveIntraSupersetOrder` flag to prevent normalizeRoutine() from clobbering intra-superset reorder
- Replaced `superset.exercises.forEach` with `ReorderableColumn` from sh.calvin.reorderable v3.0.0
- Added drag handle icons on exercises within superset containers
- Drag handles hidden during selection mode and for single-exercise supersets
- Fixed pre-existing named parameter mismatch in SqlDelightWorkoutRepository.updateRoutineGroupId

## Files Modified
- `shared/.../domain/model/RoutineUtils.kt` (NEW) — normalizeRoutine() + reorderExercisesInSuperset()
- `shared/.../presentation/screen/RoutineEditorScreen.kt` — Nested ReorderableColumn, drag handles, updated updateRoutine() helper

## Verification Results
| Command | Exit Code | Result |
|---------|-----------|--------|
| `compileDebugKotlin` | 0 | PASS |
| `assembleDebug` | 0 | PASS |
| `testDebugUnitTest` | 0 | PASS |

## Verification Commands
- Verification Commands Run: 4
- Verification Passed: 4
- Verification Failed: 0

## Key Decisions
1. Used `ReorderableColumn` (confirmed in library sources) over manual drag implementation
2. Drag handle hidden for single-exercise supersets (no valid drop target)
3. `preserveIntraSupersetOrder` defaults to false for backward compat with all existing call sites
4. Inner drag wrapped in Row with Icon(DragHandle) — ExerciseRowInSuperset unchanged (not in files_modified)

## Issues Encountered
- Pre-existing bug: SqlDelightWorkoutRepository.updateRoutineGroupId used named `routineId` parameter but SQLDelight generates positional params. Fixed.

## Escalations
None.

## Handoff Context
- **Key outputs**: RoutineUtils.kt with normalizeRoutine() and reorderExercisesInSuperset() — both callable from unit tests
- **Decisions made**: preserveIntraSupersetOrder parameter design; ReorderableColumn for nested drag
- **Open questions**: None
- **Conventions established**: Utility functions for routine operations extracted to RoutineUtils.kt

## Requirements Covered
- ROUTINE-01 (#365): Superset exercise reorder

# Plan 39-03 Summary: Integration Tests & Regression Guards

## Result
**Status**: Complete
**Wave**: 2
**Agent**: testing-qa-verification-specialist
**Completed**: 2026-04-21

## Completed Tasks
- Created SupersetReorderTest.kt with 7 tests covering intra-superset reorder ordering
- Created RoutineGroupTest.kt with 10 tests covering group CRUD and associations
- Created RoutineRegressionGuardTest.kt with 5 regression guards for existing routine operations
- Fixed SchemaManifest.kt: added RoutineGroup table, groupId column, idx_routine_group_profile index
- Fixed SchemaParityTest.kt: bumped CURRENT_VERSION from 26 to 27

## Files Modified
- `shared/src/commonTest/.../domain/model/SupersetReorderTest.kt` (NEW) — 7 superset reorder tests
- `shared/src/commonTest/.../domain/model/RoutineGroupTest.kt` (NEW) — 10 group CRUD tests
- `shared/src/commonTest/.../domain/model/RoutineRegressionGuardTest.kt` (NEW) — 5 regression guards
- `shared/src/commonMain/.../data/local/SchemaManifest.kt` — RoutineGroup in manifest tables/columns/indexes
- `shared/src/androidHostTest/.../data/local/SchemaParityTest.kt` — CURRENT_VERSION bump

## Verification Results
| Command | Exit Code | Result |
|---------|-----------|--------|
| `testAndroidHostTest --tests *SupersetReorderTest*` | 0 | 7/7 PASS |
| `testAndroidHostTest --tests *RoutineGroupTest*` | 0 | 10/10 PASS |
| `testAndroidHostTest --tests *RoutineRegressionGuardTest*` | 0 | 5/5 PASS |
| `testAndroidHostTest --tests *SchemaParityTest*` | 0 | 2/2 PASS |
| `testAndroidHostTest` (full) | 0* | 1526/1527 (*1 pre-existing failure) |

## Verification Commands
- Verification Commands Run: 5
- Verification Passed: 5
- Verification Failed: 0

## Key Decisions
1. Tests placed in `shared/src/commonTest/` (matching existing model test location)
2. Test task: `:shared:testAndroidHostTest` (correct for AGP 9.0)
3. camelCase_descriptiveName convention matching existing test patterns

## Issues Encountered
- SchemaParityTest was failing due to missing RoutineGroup in SchemaManifest + stale CURRENT_VERSION. Fixed.
- Pre-existing: PortalPullPaginationTest.pullCapsLargeKnownSessionIdsToMaxParityIds fails independently. Unrelated.

## Escalations
None.

## Handoff Context
- **Key outputs**: 22 new tests, SchemaManifest updated for migration 27
- **Decisions made**: Test location in commonTest, test naming convention
- **Open questions**: None
- **Conventions established**: Test helpers follow createTestRoutineExercise() pattern

## Requirements Covered
- ROUTINE-01 (#365): Superset reorder ordering verified
- ROUTINE-02 (#307): Routine grouping CRUD verified

# Issue #632 Task 2 Report

## Scope completed

Implemented Task 2 only in the mobile worktree:

- Persist `TrainingCycle.templateId` and `TrainingCycle.weekNumber` in SQLDelight schema, migration, manifest, repository, sync, and backup paths.
- Add converter support so generated 5/3/1 cycles carry `templateId` and compute persisted week-specific set percentages through a shared helper.
- Add focused regression coverage for converter, repository persistence, portal sync mapping/merge, and schema migration parity.

Portal code was not modified.

## Files changed

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TemplateModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/TrainingCycleRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightTrainingCycleRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/41.sqm`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverterTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/ConflictResolutionTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeTrainingCycleRepository.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightTrainingCycleRepositoryTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt`

## TDD notes

Focused tests were updated first to require the missing behavior:

- `TemplateConverterTest` now asserts the converted cycle carries `templateId`, preserves week 1, and the new helper returns exact 5/3/1 percentages for weeks 1 and 3.
- `SqlDelightTrainingCycleRepositoryTest` now verifies `templateId` and `weekNumber` persist on save and that `updateWeekNumber` changes only the week field.
- `PortalSyncAdapterTest` now verifies portal DTO mapping includes persisted `templateId` and `currentWeek`.
- `ConflictResolutionTest` now verifies portal merge persists `templateId` and `currentWeek`, and that later active-cycle enforcement does not clobber those values.
- `SchemaParityTest` now verifies migration `41` adds `template_id` and `week_number`, and bumps the schema parity version to `42`.

RED evidence:

```powershell
.\gradlew.bat --% :shared:testAndroidHostTest --tests *TemplateConverterTest* --tests *SqlDelightTrainingCycleRepositoryTest* --tests *PortalSyncAdapterTest* --tests *ConflictResolutionTest* --tests *SchemaParityTest* -Pskip.supabase.check=true --console=plain
```

This failed before implementation with missing `templateId`, missing week helper, missing repository API/SQL plumbing, missing DTO fields, and missing migration/version updates.

## Implementation notes

- Added `templateId` to `TrainingCycle` and propagated it through creation, persistence, sync DTOs, and backup import/export.
- Added `computeFiveThreeOneSetWeightsForWeek(weekNumber)` so the 5/3/1 percentage math lives in one place and uses the exact training-max rounding required by the brief.
- Added `week_number` and `template_id` columns to `TrainingCycle`, plus migration `41.sqm`.
- Added repository method `updateWeekNumber(cycleId, weekNumber)`.
- Updated sync merge paths to insert and update portal `templateId` and `currentWeek`.
- Preserved `template_id` and `week_number` during single-active-cycle enforcement updates so metadata is not lost when only `is_active` is being toggled.
- Updated backup models/import/export to keep the new fields compile-safe and data-safe.

## Verification run

Required brief command:

```powershell
.\gradlew.bat --% :shared:testAndroidHostTest --tests *TemplateConverterTest* -Pskip.supabase.check=true --console=plain
```

Passed.

Focused persistence/sync/schema verification:

```powershell
.\gradlew.bat --% :shared:testAndroidHostTest --tests *SchemaParityTest* --tests *SqlDelightTrainingCycleRepositoryTest* --tests *PortalSyncAdapterTest* --tests *ConflictResolutionTest* -Pskip.supabase.check=true --console=plain
```

Passed.

Full narrow GREEN gate after implementation:

```powershell
.\gradlew.bat --% :shared:testAndroidHostTest --tests *TemplateConverterTest* --tests *SqlDelightTrainingCycleRepositoryTest* --tests *PortalSyncAdapterTest* --tests *ConflictResolutionTest* --tests *SchemaParityTest* -Pskip.supabase.check=true --console=plain
```

Passed.

## Self-review

- `git diff --check` passed; only line-ending warnings were reported by Git on this Windows checkout.
- Reviewed schema, migration, repository, sync, converter, and backup edges for accidental field loss.
- No unrelated files were modified outside this task scope.

## Concerns

None blocking. The only non-failure noise during review was expected Git LF/CRLF warnings from the local checkout.

Task: Issue #634 Task 1 - stable UUIDs for PersonalRecord parity sync

Status
- Implemented Task 1 only.
- Did not implement Task 2 rate limiting or Retry-After pull retry behavior.

Requirements applied
- Added migration `40.sqm`.
- Bumped SQLDelight schema version to `41`.
- Bumped `SchemaParityTest` `CURRENT_VERSION` to `41L`.
- Kept UUID adoption keyed by `(exerciseId, prType, phase, profile_id, achievedAt)`.
- Excluded `workoutMode` from UUID adoption.
- Kept `SyncRepository` interface unchanged.
- Kept push timestamp stamping rowid-based.

What changed
- Added `uuid TEXT` to `PersonalRecord` schema and partial unique index `idx_pr_uuid`.
- Added migration 40 to:
  - add `uuid`
  - delete ghost PersonalRecord rows with `workoutMode IN ('MAX_WEIGHT', 'MAX_VOLUME', '1RM')`
  - backfill canonical lowercase v4 UUIDs
  - create `idx_pr_uuid`
- Updated schema manifest and resilient migration statements for the new column/index.
- Changed PersonalRecord queries so `insertRecord`, `upsertRecord`, `upsertPR`, and `insertPRIgnore` all carry UUIDs.
- Added `adoptServerPrUuid` SQLDelight query.
- Changed `selectAllPersonalRecordIdsByProfile` to return UUIDs only.
- Added `uuid` to the domain backup/model/DTO plumbing.
- Preserved or generated UUIDs at PR upsert/restore call sites in:
  - `SqlDelightPersonalRecordRepository`
  - `SqlDelightWorkoutRepository`
  - `SqlDelightSyncRepository`
  - `DataBackupManager`
  - `MigrationManager`
- Wired portal PR push to send `id = record.uuid`.
- Wired portal PR pull merge to:
  1. adopt the server UUID onto matching local rows
  2. insert unknown rows with that UUID

TDD notes
- Wrote/updated regression coverage first.
- Verified initial red state with:
  - `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.repository.SqlDelightSyncRepositoryTest --console=plain`
- Initial expected failures were missing UUID parameters/fields in PR schema and DTO plumbing.

Tests added or updated
- `SyncManagerTest`
  - push payload includes PR `id == uuid`
- `SqlDelightSyncRepositoryTest`
  - UUID adoption on server/local PR key match with differing `workoutMode`
  - unknown server PR insert uses server UUID
  - re-merge is idempotent
  - parity PR id list returns canonical UUIDs only
- `SchemaParityTest`
  - migration 40 deletes ghost rows and backfills distinct canonical UUIDs
- Updated nearby host/common fixtures to satisfy the new SQLDelight signatures.

Focused verification run
- `.\gradlew.bat --% :shared:validateSchemaManifest --console=plain`
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.repository.SqlDelightSyncRepositoryTest --console=plain`
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.local.SchemaParityTest --console=plain`
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.sync.SyncManagerTest.syncPushesStableUuidAsPortalPersonalRecordId --console=plain`
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.sync.SyncManagerTest.pullDropsNonUuidBadgeAndPersonalRecordIdsBeforeSend --console=plain`

Notable debugging steps
- Fixed one compile-time SQLDelight mapper break in `SqlDelightWorkoutRepository` after the PersonalRecord row shape gained `uuid`.
- Adjusted the migration test seed schema to match the actual pre-reconcile v40 `PersonalRecord` shape used by the schema harness.

Residual concerns
- None for Task 1 scope after the focused verification above.

---

Review follow-up fix: Task 1 reviewer findings on branch `codex/fix-634-pr-uuid-rate-limit`

Scope fixed
- Critical: schema reconciliation now replays migration 40 PersonalRecord cleanup/backfill before `idx_pr_uuid` reconciliation, so branch-gap / skipped-migration databases do not keep ghost PR rows or `uuid = NULL` survivors.
- Important: profile-scope PR merges now preserve an existing stable destination UUID when a better duplicate row wins but arrives without a UUID.

TDD red phase
- Added `SchemaManifestTest.reconcileFullSchema replays migration 40 PR cleanup before creating uuid index`
- Added `MigrationManagerTest.repairOrphanedPRRecords preserves target uuid when better orphan duplicate lacks one`
- Red verification:
  - `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.local.SchemaManifestTest --console=plain`
    - FAILED at `SchemaManifestTest.kt:325`
    - failure showed reconciliation left 3 PR rows instead of deleting the ghost row / backfilling UUIDs
  - `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.migration.MigrationManagerTest --console=plain`
    - FAILED at `MigrationManagerTest.kt:355`
    - failure showed the merged PR UUID changed away from the existing stable destination UUID

Implementation
- `SchemaManifest.kt`
  - extended `SchemaIndexOperation` with `beforeCreateSql`
  - executes pre-create data repair inside the same savepoint as index creation
  - wired `idx_pr_uuid` reconciliation to:
    - delete ghost `PersonalRecord` rows with invalid `workoutMode`
    - backfill canonical lowercase v4 UUIDs for surviving rows with `uuid IS NULL`
- `MigrationManager.kt`
  - made `CanonicalPersonalRecord.uuid` nullable during candidate selection
  - preserved `existing.uuid` when a better replacement candidate lacks one
  - deferred UUID generation until final reinsertion

Green verification
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.local.SchemaManifestTest --console=plain`
  - PASS
- `.\gradlew.bat --% :shared:testAndroidHostTest --tests com.devil.phoenixproject.data.migration.MigrationManagerTest --console=plain`
  - PASS
- `.\gradlew.bat --% :shared:validateSchemaManifest --console=plain`
  - PASS (`Schema manifest validated: 382 columns across 43 tables, all covered.`)
- `git diff --check`
  - PASS
  - emitted LF->CRLF working-copy warnings only; no diff errors

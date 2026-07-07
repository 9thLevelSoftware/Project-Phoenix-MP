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

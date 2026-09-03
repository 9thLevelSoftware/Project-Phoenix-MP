# Phoenix persisted-file migration design

## Context

PR 709 removes legacy branding from the product, but released Android and iOS builds already persist the database and Android preferences under legacy filenames. Changing those names without a compatibility migration would silently open empty stores. The application ID and iOS bundle ID therefore remain unchanged, and physical-file migration happens before SQLDelight or Settings can open a Phoenix-named target.

The migration is deliberately independent of the SQLDelight schema. Renaming a file does not change its schema and must not add an `.sqm` migration.

## Artifacts and compatibility boundary

The database artifacts are:

| Artifact | Filename | Purpose |
|---|---|---|
| Target | `phoenix.db` | Canonical database opened by SQLDelight. |
| Staging | `phoenix.db.migrating` | Incomplete copy; never a canonical source. |
| Recovery | `phoenix-recovery.db` | Validated neutral recovery copy retained through the first migrated launch. |
| Lock | `phoenix-db-migration.lock` | Cross-process exclusive migration lock. |

Legacy filename literals are allowed only in reviewed compatibility constants, Android backup/data-extraction exclusions, and migration tests. Compatibility lookup remains indefinitely so a user can skip releases. A successful first migrated launch removes the legacy main file, sidecars, preference XML files, and preference `.bak` files. Neutral recovery artifacts are retained until the next successful validated launch.

## Database state machine

`DatabaseFileMigrationCoordinator` runs before platform database construction. Its platform-neutral operations are injected so state transitions and destructive-operation ordering can be tested without a filesystem.

| Observed files | Required behavior |
|---|---|
| None | Allow SQLDelight to create `phoenix.db`. |
| Legacy only | Checkpoint and validate, build recovery through staging, delete sidecars, and atomically move the main file to target. |
| Legacy plus staging/recovery, no target | Delete only incomplete staging, checkpoint legacy, validate/reuse a matching recovery when present, and resume cutover. |
| Target only | Open target and validate after SQLDelight initialization. |
| Target plus recovery, no legacy | Open target; after successful validation delete recovery because this is the clean restart. |
| Recovery only | Rebuild target through staging, validate it, and retain recovery until the next clean restart. |
| Recovery plus staging | Delete incomplete staging and resume recovery-only reconstruction. |
| Target plus stale staging, no legacy | Delete incomplete staging, then treat target/recovery according to the rows above. |
| Target plus legacy | Throw `DUAL_DATABASES`, preserve every file, and require support-assisted recovery. |
| Staging only | Block with `RECOVERY_COPY_FAILED`; no verified canonical source remains. |

Every transition is inspected and executed while holding `phoenix-db-migration.lock`. No file is selected or merged when both legacy and target databases exist.

## Normal database migration

1. Acquire the exclusive neutral lock.
2. Open the legacy database without SQLDelight schema creation or migration.
3. Run `PRAGMA wal_checkpoint(TRUNCATE)` and require a non-busy result.
4. Require `PRAGMA quick_check` to return exactly `ok`, then record file size, `user_version`, page count, and free-page count.
5. Close the raw connection.
6. Copy the main database to staging, synchronously flush it, validate it, and require an identical fingerprint.
7. Atomically move staging to recovery without replacing an existing file.
8. Delete checkpointed legacy `-wal`, `-shm`, and `-journal` files; failure blocks cutover.
9. Atomically move the legacy main file to `phoenix.db` without replacement.
10. Open the target with `PhoenixDatabase.Schema`, run the existing resilient migrations and reconciliation, and require target integrity, expected schema version, and successful reconciliation.
11. Retain recovery for this launch. Delete it only after validation on the next launch.

For recovery-only reconstruction, the validated recovery file is copied through staging and atomically promoted to target. Recovery is never renamed away during reconstruction.

## Failure contract

| Code | User-safe meaning |
|---|---|
| `DUAL_DATABASES` | Both canonical names exist; neither was opened or changed. |
| `INTEGRITY_CHECK_FAILED` | A source or recovery database did not pass SQLite validation. |
| `CHECKPOINT_FAILED` | Committed WAL state could not be safely folded into the main file. |
| `RECOVERY_COPY_FAILED` | A verified neutral recovery copy could not be produced, or only orphan staging remained. |
| `ATOMIC_MOVE_FAILED` | A same-directory no-replacement promotion failed. |
| `LEGACY_CLEANUP_FAILED` | Legacy sidecars could not be removed before cutover. |
| `TARGET_VALIDATION_FAILED` | The Phoenix target failed post-SQLDelight validation. |

Failure messages contain the code and artifact role only. Logs may include file sizes, integrity results, and schema versions, but never table contents, preference values, or tokens. A failed prerequisite prevents all later destructive operations.

## Android preferences

Before either Settings singleton is returned, `AndroidPreferenceFileMigrator` migrates the released plaintext and encrypted stores into `phoenix_preferences` and `phoenix_secure_preferences` through `SharedPreferences` APIs. It never copies encrypted XML bytes.

The migrator supports `String`, `Boolean`, `Int`, `Long`, `Float`, and `Set<String>`. It first creates and verifies neutral plaintext/encrypted recovery stores with synchronous `commit()`. Existing Phoenix keys win; only missing target keys are filled. Legacy stores are deleted with `Context.deleteSharedPreferences()` only after target and recovery verification. Cleanup failures retain the source and retry without overwriting newer target values. Neutral recovery stores are removed after the next successful startup.

Android backup rules exclude legacy, target, staging, and neutral recovery database and preference artifacts for the compatibility window.

## Startup behavior

Database preparation is lazy and occurs at dependency resolution, not eagerly in `Application.onCreate`. Android and iOS hosts resolve startup dependencies inside a retryable `runCatching` boundary. A `DatabaseFileMigrationException` shows a shared preserved-data screen with a non-sensitive diagnostic code and Retry. `DUAL_DATABASES` disables automatic recovery and directs the user to support.

`AppContent` retains responsibility for existing row-level required migrations. Koin singleton construction failures must not be cached so Retry runs preparation again.

## Verification boundary

Host tests prove the state machine and preference behavior. Android instrumentation tests exercise real SQLite and SharedPreferences files. iOS ARM64 compilation proves platform API integration; a physical-device upgrade is still required because this repository has no simulator target. Release acceptance additionally requires upgrades from the last shipped Android APK and iOS build, WAL-present process interruption, corrupt and dual-database cases, skipped-version upgrades, low-storage behavior, and second-launch recovery cleanup.

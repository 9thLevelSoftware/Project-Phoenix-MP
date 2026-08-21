# Phoenix persisted-file migration implementation plan

**Goal:** Amend PR 709 so upgraded Android and iOS installations retain all local data while every active database and Android preference filename becomes Phoenix-branded.

**Design:** See `docs/superpowers/specs/2026-08-21-phoenix-persisted-file-migration-design.md`.

## Constraints

- [ ] Preserve Android application ID, iOS bundle ID, signing identities, and sandbox continuity.
- [ ] Never open a fresh Phoenix database while an unresolved legacy database exists.
- [ ] Preserve both candidates and block when target and legacy databases coexist.
- [ ] Keep a neutral recovery copy for one validated restart.
- [ ] Keep compatibility lookup indefinitely for skipped-release upgrades.
- [ ] Do not change SQLDelight schema contents or add an `.sqm` migration.
- [ ] Restrict legacy literals to compatibility constants, backup exclusions, and migration tests.
- [ ] Preserve the main checkout and its existing `gradle/libs.versions.toml` modification.

## Task 1: State machine

- [x] Add operation-recording common tests for fresh install, upgrade, interrupted phases, target-only, recovery-only, dual databases, failure ordering, and second-launch cleanup.
- [x] Observe the missing-coordinator failure, then implement the smallest common coordinator that passes.
- [x] Commit as `test: specify persisted database filename migration`.

## Task 2: Android database operations

- [ ] Implement `DatabaseFileOperations` with `Context.getDatabasePath`, raw `SQLiteDatabase`, synchronous flushes, same-directory atomic no-replacement moves, and a file lock.
- [ ] Prepare before `AndroidSqliteDriver`; validate integrity, schema version, and reconciliation after initialization.
- [ ] Add instrumentation coverage for populated legacy/WAL migration, corrupt input, interrupted staging, and cleanup.
- [ ] Commit as `feat(android): migrate persisted Phoenix database filename safely`.

## Task 3: iOS database operations

- [ ] Resolve paths with SQLiter `DatabaseFileContext.databasePath`.
- [ ] Inspect legacy files with `DatabaseConfiguration(version = NO_VERSION_CHECK)` and close raw drivers before filesystem changes.
- [ ] Use same-directory no-replacement moves and an exclusive neutral lock.
- [ ] Exclude target, staging, recovery, and sidecars from backup using corrected paths.
- [ ] Prepare before the resilient normal driver and validate afterward.
- [ ] Compile the iOS ARM64 framework and record the physical-device verification gate.
- [ ] Commit as `feat(ios): migrate persisted database filename safely`.

## Task 4: Android preference filenames

- [ ] Add `AndroidPreferenceFileMigrator` tests for all supported value types, empty/target/source/conflict states, commit and encryption failures, interruption, and second-launch cleanup.
- [ ] Migrate plaintext and encrypted stores through their APIs before Settings resolution.
- [ ] Use only Phoenix target names in direct readers.
- [ ] Expand backup/data-extraction exclusions and verify no legacy XML or `.bak` remains after success.
- [ ] Commit as `feat(android): migrate legacy preference filenames`.

## Task 5: Startup safety and Retry

- [ ] Remove eager Android database/migration resolution and the redundant iOS Swift migration call.
- [ ] Resolve platform dependencies through a retryable boundary.
- [ ] Show shared preserved-data diagnostics and Retry; disable automatic recovery for `DUAL_DATABASES`.
- [ ] Verify failed Koin singleton construction retries preparation.
- [ ] Commit as `feat: block startup until persisted-file migration is safe`.

## Task 6: Upgrade validation and PR integration

- [ ] Add an Android last-release upgrade harness and cover representative rows/preferences, WAL, fresh/skipped upgrades, low storage, corruption, interruption, and dual databases.
- [ ] Run physical iOS upgrade validation and second-launch cleanup checks where device access exists.
- [ ] Confirm identifiers and signing configuration are unchanged.
- [ ] Update PR 709 description with compatibility literals, evidence, and remaining device gates.
- [ ] Commit as `test: verify Phoenix persisted-file upgrade path`.

## Focused verification

```powershell
.\gradlew ':shared:testAndroidHostTest' ':androidApp:testDebugUnitTest' ':shared:verifyCommonMainPhoenixDatabaseMigration' '-Pskip.supabase.check=true'
.\gradlew ':androidApp:connectedDebugAndroidTest' '-Pskip.supabase.check=true'
.\gradlew ':shared:compileKotlinIosArm64' '-Pskip.supabase.check=true'
```

After focused checks, run the repository quality gate and platform release builds without modifying tracked generated files. PR 709 remains unsafe to merge until fresh installs, WAL-preserving upgrades, both Android preference stores, failure preservation, second-launch cleanup, schema reconciliation, literal review, and physical-device checks have evidence.

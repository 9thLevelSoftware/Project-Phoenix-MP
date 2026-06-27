# Review: Data - Local/Database

Scope reviewed: database driver declaration, schema manifest, migration manager, preferences manager, and badge-definition/preference files named by the task.

Note: Three assigned paths are not present in the repository, and one assigned class is implemented in a different Kotlin file. Those are reported as findings because they are review/build-maintenance failure points for this task scope.

## Severity breakdown

- Critical: 0
- High: 3
- Medium: 6
- Low: 3

Total findings: 12

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.kt

No findings in the assigned common `expect` declaration. The file only declares the multiplatform `DriverFactory` API and does not contain driver creation logic.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/ExerciseMapper.kt

### Finding 1

- Category: failure-point
- Severity: Medium
- Line numbers: N/A (assigned file is missing)
- Description: The task-assigned `ExerciseMapper.kt` file does not exist under `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/`, and no `*ExerciseMapper.kt` file exists under `shared/src`. This makes the review target stale and means any future code, documentation, or review automation expecting a central exercise mapper will fail to locate it.
- Suggested fix direction: Either restore/move the mapper to the assigned path if it is still an intended abstraction, or update task manifests/documentation to point to the current implementation location and remove stale references.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/NotificationPreferences.kt

### Finding 2

- Category: failure-point
- Severity: Medium
- Line numbers: N/A (assigned file is missing)
- Description: The task-assigned `NotificationPreferences.kt` file does not exist, and content search found no `NotificationPreferences` symbol in `shared/src`. The notification/preferences surface appears absent from the named local-data layer, so notification preference persistence may either be missing or has been moved without updating the review manifest.
- Suggested fix direction: Confirm whether notification preferences are still required. If yes, add the missing persistence model/manager and tests. If no, remove this stale file from review manifests and architecture docs.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt

### Finding 3

- Category: failure-point
- Severity: High
- Line numbers: 141-153, 1361-1365, 1410-1414, 1439-1443
- Description: `applyIndexCreate` executes `preDropSql` before attempting to create the replacement index. For indexes that enforce uniqueness, such as `idx_pr_unique`, `idx_gamification_stats_profile`, and `idx_external_activity_dedup`, a create failure after the drop leaves the database without the old uniqueness constraint. A likely failure case is duplicate legacy rows: the new unique index fails to create, the old index is already gone, and subsequent writes can accumulate more duplicates.
- Suggested fix direction: Run duplicate preflight checks before dropping unique indexes, wrap drop/create in a transaction with a clear fatal failure path, and/or rebuild indexes only after the migration/repair code has deduplicated affected tables. Avoid dropping a working constraint unless the replacement can be guaranteed.

### Finding 4

- Category: failure-point
- Severity: Medium
- Line numbers: 102-113, 124-153, 158-169
- Description: Table, column, and index reconciliation failures are converted into report entries, and `reconcileFullSchema` still returns normally after partial failure. This makes schema healing best-effort even for missing tables/columns that the rest of the app may require immediately after database open. Callers can log the report and proceed with a partially reconciled schema.
- Suggested fix direction: Classify reconciliation operations as fatal vs optional. Throw or otherwise block app startup on required table/column/index failures, and only allow non-critical telemetry/index failures to remain best-effort.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/VelocityOneRepMaxBadgeDefinitions.kt

### Finding 5

- Category: failure-point
- Severity: Low
- Line numbers: N/A (assigned file is missing)
- Description: The task-assigned `VelocityOneRepMaxBadgeDefinitions.kt` file does not exist. The velocity 1RM badges currently live inside `BadgeDefinitions.kt` at lines 215-242, and the existing test `VelocityOneRepMaxBadgeDefinitionsTest` also uses `BadgeDefinitions`. This is likely a stale file split/name in the review manifest rather than a runtime bug, but it makes the assigned review target inaccurate.
- Suggested fix direction: Update the review manifest to point to `BadgeDefinitions.kt`, or split velocity 1RM badge definitions into the named file if that separation is still desired.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt

### Finding 6

- Category: bug
- Severity: High
- Line numbers: 837-849
- Description: Orphaned personal-record repair migrates every orphan row with a raw `UPDATE PersonalRecord SET profile_id = ? WHERE profile_id = ?`. If the target profile already has a record with the same `(exerciseId, workoutMode, prType, phase, profile_id)` composite key, this update violates `idx_pr_unique` and aborts the transaction. The earlier default-profile repair path has a merge/deduplication implementation, but the orphaned-profile path bypasses it.
- Suggested fix direction: Reuse the canonical merge logic from `mergePersonalRecords`, or perform a SELECT/deduplicate/delete/reinsert flow before changing `profile_id`. Add regression coverage with an orphan profile and target profile containing the same PR key.

### Finding 7

- Category: bug
- Severity: High
- Line numbers: 871-879
- Description: Orphaned badge repair bulk-updates `EarnedBadge.profile_id` into the target profile without deduplicating. Because `idx_earned_badge_profile` is unique on `(badgeId, profile_id)`, this can fail when the target profile already earned the same badge, causing the repair transaction to roll back.
- Suggested fix direction: Merge badge rows the same way `mergeEarnedBadges` does for default-profile repair: preserve the earliest `earnedAt`, preserve a non-null `celebratedAt`, delete source/target duplicates, then insert one canonical row per badge/profile.

### Finding 8

- Category: failure-point
- Severity: Medium
- Line numbers: 121-124
- Description: `checkAndRunMigrations()` launches migration work asynchronously and returns immediately. Startup callers can continue using the database while cleanup, PR repair, profile-scope repair, and orphan repair are still running. The common Koin startup path also logs completion immediately after calling this method, even though the coroutine may still be active.
- Suggested fix direction: Prefer a suspend startup API (`runMigrationsNow`) for app initialization, return a `Job` that callers must await, or gate repository usage on a completed migration state. Tests should avoid sleep-based synchronization and await the migration explicitly.

### Finding 9

- Category: failure-point
- Severity: Medium
- Line numbers: 127-142
- Description: `runMigrationsNow()` catches all exceptions, logs them, sets `ProfileScopeRepairState.Failed`, and then returns normally. Combined with the fire-and-forget caller, migration failure can be invisible to startup code and the app can continue with stale or partially repaired data.
- Suggested fix direction: Propagate failures to startup for non-recoverable repairs, or return a structured success/failure result that callers must handle before proceeding. Reserve swallowed failures for explicitly optional/background repairs.

---

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/SettingsPreferencesManager.kt

### Finding 10

- Category: failure-point
- Severity: Low
- Line numbers: N/A for assigned file; implementation is in `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt` line 177
- Description: The assigned `SettingsPreferencesManager.kt` path does not exist. The `SettingsPreferencesManager` class is implemented inside `PreferencesManager.kt`. Kotlin permits this, so it is not a build error, but the file layout is misleading for review automation and for developers looking for the concrete implementation.
- Suggested fix direction: Move `SettingsPreferencesManager` into its own file matching the class name, or update review manifests and documentation to point to `PreferencesManager.kt`.

### Finding 11

- Category: bug
- Severity: Medium
- Line numbers: `PreferencesManager.kt` 278-280 plus setter methods such as 282-335, 455-547
- Description: Preference updates use a non-atomic read-modify-write on `_preferencesFlow.value`: `_preferencesFlow.value = _preferencesFlow.value.update()`. Concurrent setter calls can overwrite each other's in-memory `StateFlow` changes even though both writes reach the backing `Settings`. Until a reload, observers may see a stale preference field.
- Suggested fix direction: Use `MutableStateFlow.update { ... }` from kotlinx.coroutines, serialize preference writes through a mutex, or reload the full preference snapshot from `Settings` after each write so concurrent setters cannot lose in-memory state.

### Finding 12

- Category: failure-point
- Severity: Low
- Line numbers: `PreferencesManager.kt` 328-335, 500-502; constraints documented in `UserPreferences.kt` 20-22 and 37-38
- Description: Several setters persist unchecked values that have documented domains. For example, `setSummaryCountdownSeconds` and `setAutoStartCountdownSeconds` accept any integer even though `UserPreferences` documents `summaryCountdownSeconds` as `-1`, `0`, or `5-30`, and `autoStartCountdownSeconds` as `2-10`. `setLanguage` also persists arbitrary strings despite the model documenting supported language codes. Corrupted or invalid caller input can propagate to UI/timer logic.
- Suggested fix direction: Clamp or validate values at the persistence boundary, reject unsupported language codes, and mirror the validation on load so existing bad settings are normalized.

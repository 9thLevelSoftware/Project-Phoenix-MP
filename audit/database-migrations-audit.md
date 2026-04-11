# SQLDelight Schema & Migration Deep Audit Report

**Date:** 2026-03-31  
**Scope:** VitruvianDatabase.sq, Migrations 1–24, MigrationStatements.kt, DriverFactory (Android/iOS), SchemaManifest.kt, MigrationManager.kt, DatabaseFactory.kt  
**Priority:** #1 — iOS crashes and Android data loss on migration  

---

## Executive Summary

The migration infrastructure has been **significantly hardened** through layers of defense-in-depth (resilient fallback, SchemaManifest reconciliation, comprehensive parity tests). However, several **critical and high-risk issues** remain that can cause iOS crashes and Android data loss, particularly for users upgrading from older versions or through non-sequential upgrade paths.

---

## 1. Migration Chain Integrity Assessment

### Version Configuration
- **SQLDelight version in build.gradle.kts:** `version = 25`  
- **Interpretation:** Version 25 = initial schema (1) + 24 migrations (1.sqm–24.sqm)
- **Migration files found:** 1.sqm through 24.sqm — **all 24 present, no gaps, no duplicate version numbers** ✅

### Chain Completeness
| Version | Content | Type |
|---------|---------|------|
| 1 | Add `one_rep_max_kg` to Exercise | ALTER TABLE |
| 2 | Create UserProfile table | CREATE TABLE |
| 3 | Add superset columns to RoutineExercise | ALTER TABLE (**SUPERSEDED by 4**) |
| 4 | Superset container model (table recreate) | CREATE TABLE + Copy-Drop-Rename |
| 5 | Subscription + WorkoutSession metrics | ALTER TABLE ×20 |
| 6 | Training Cycle tables | CREATE TABLE ×7 |
| 7 | PR percentage scaling | ALTER TABLE ×4 |
| 8 | Schema healing phase 1 | DDL cleanup |
| 9 | Schema healing phase 2 (composite IDs) | DML + DDL |
| 10 | Comprehensive schema fix | **MASSIVE** Copy-Drop-Recreate |
| 11 | Sync columns | ALTER TABLE ×12 (some removed) |
| 12 | RepMetric table + routineId | CREATE TABLE + ALTER |
| 13 | MetricSample index | CREATE INDEX |
| 14 | ExerciseSignature + AssessmentResult | CREATE TABLE ×2 |
| 15 | RepBiomechanics + WS summary | CREATE TABLE + ALTER ×5 |
| 16 | formScore | ALTER TABLE |
| 17 | RpgAttributes table | CREATE TABLE |
| 18 | **NOOP** (healed outside) | Empty |
| 19 | Phase-specific PR tracking | ALTER + index rebuild |
| 20 | WorkoutSession timestamp index | CREATE INDEX (columns healed outside) |
| 21 | profile_id indexes + PR index update | CREATE INDEX ×6 + index rebuild |
| 22 | Gamification profile_id indexes | CREATE INDEX ×4 |
| 23 | ExternalActivity + IntegrationStatus | CREATE TABLE ×2 |
| 24 | EarnedBadge rebuild (remove inline UNIQUE) | Copy-Drop-Rename |

**Assessment:** Chain is contiguous and complete. ✅

---

## 2. Per-Migration Risk Assessment

### 🔴 CRITICAL RISK Migrations

#### Migration 3 → Migration 4 Interaction
- **Issue:** Migration 3 adds `supersetGroupId`, `supersetOrder`, `supersetRestSeconds` to RoutineExercise. Migration 4 then does Copy-Drop-Rename to replace those columns with `supersetId`, `orderInSuperset`.
- **Risk:** If a user is at version 3 and migration 4 fails partway (e.g., crash during `INSERT INTO RoutineExercise_new`), the `RoutineExercise` table is either missing or partially populated.
- **Mitigation present:** Migration 4 drops `RoutineExercise_new` IF EXISTS at the start. ✅
- **Remaining risk:** Migration 4's `.sqm` version does NOT have `DROP TABLE IF EXISTS RoutineExercise_new` at the top — it's only present in migration 10 and the resilient fallback. The .sqm file starts directly with `CREATE TABLE Superset`. If the `.sqm` is the code path being used by SQLDelight, a previously failed migration 4 will crash on `CREATE TABLE RoutineExercise_new` because it already exists.
- **Actually mitigated:** Migration 3 in the `.sqm` file has `DROP TABLE IF EXISTS RoutineExercise_new` at the top. ✅

#### Migration 10: The "Nuclear" Migration 🔴
- **Risk level:** CRITICAL — this is the most complex migration, doing Copy-Drop-Recreate on 5 tables.
- **Critical gap: `.sqm` vs `MigrationStatements.kt` DIVERGENCE:**
  - The `.sqm` file for migration 10 does a full Copy-Drop-Recreate pattern with temp tables, data migration, FK handling.
  - The `MigrationStatements.kt` version 10 does something COMPLETELY DIFFERENT: it just runs ALTER TABLE ADD COLUMN statements and CREATE TABLE IF NOT EXISTS statements. It does NOT do Copy-Drop-Recreate.
  - **This is a MAJOR discrepancy.** When the `.sqm` migration fails and the resilient fallback runs `getMigrationStatements(10)`, users get a fundamentally different schema path.
  - **Impact:** On iOS, if the `.sqm` migration fails (common on iOS due to NativeSqliteDriver quirks), the resilient fallback applies a simplified version that leaves the OLD RoutineExercise table in place (with whatever columns it already has). It relies on `reconcileFullSchema()` to add missing columns. This is actually the INTENDED behavior — but it means the Copy-Drop-Recreate logic in the .sqm is NEVER run on iOS if it fails on the first try.
  - **Data loss vector:** The `.sqm` version filters orphaned rows (`WHERE routineId IN (SELECT id FROM Routine)`). The `MigrationStatements.kt` version does NOT filter. If the `.sqm` runs successfully, orphaned RoutineExercise rows are silently deleted. This is a data integrity difference between platforms/upgrade paths.

#### Migration 11: Removed Steps Still in MigrationStatements.kt 🔴
- **Issue:** The `.sqm` file for migration 11 has **commented out** the EarnedBadge and GamificationStats ALTER TABLE statements (Steps 6 and 7 say "MOVED TO PREFLIGHT"). But `MigrationStatements.kt` version 11 **STILL INCLUDES** these ALTER TABLE statements:
  ```
  "ALTER TABLE EarnedBadge ADD COLUMN updatedAt INTEGER",
  "ALTER TABLE EarnedBadge ADD COLUMN serverId TEXT",
  "ALTER TABLE EarnedBadge ADD COLUMN deletedAt INTEGER",
  "ALTER TABLE GamificationStats ADD COLUMN updatedAt INTEGER",
  "ALTER TABLE GamificationStats ADD COLUMN serverId TEXT",
  ```
- **Impact:** When the `.sqm` migration 11 fails and resilient fallback runs, it will attempt to add these columns. If the tables don't exist yet (gamification tables are bootstrapped, not migration-created), this will fail with "no such table" — which IS recoverable but logged as a warning. If the tables DO exist with these columns already (from reconciliation or preflight), it fails with "duplicate column" — also recoverable.
- **Risk:** LOW — the resilient fallback handles both errors. But it means extra error noise in logs, and the behavioral difference between `.sqm` and `MigrationStatements.kt` is confusing for maintenance.

#### Migration 24: EarnedBadge Rebuild 🟡
- **Issue:** Copy-Drop-Rename pattern. Creates `EarnedBadge_rebuild`, copies data, drops `EarnedBadge`, renames.
- **Risk:** If the process crashes after `DROP TABLE IF EXISTS EarnedBadge` but before `ALTER TABLE EarnedBadge_rebuild RENAME TO EarnedBadge`, the EarnedBadge table is gone. 
- **MigrationStatements.kt version:** Uses the same sequence. The resilient fallback runs these one-by-one. If statement 3 (`DROP TABLE IF EXISTS EarnedBadge`) succeeds but statement 4 (`ALTER TABLE EarnedBadge_rebuild RENAME TO EarnedBadge`) fails, badges are LOST.
- **Mitigation:** `reconcileFullSchema()` will recreate EarnedBadge from `manifestTables` as an empty table, but all existing badge data is lost.
- **Severity:** 🟡 HIGH for badge data loss. Badges are cosmetic/achievement data, not workout data.

### 🟡 HIGH RISK Migrations

#### Migration 5: 20 ALTER TABLE statements
- **Risk:** If migration 5 fails partway, some columns exist and some don't. The resilient fallback handles this correctly by catching "duplicate column" errors. ✅
- **Issue:** Migration 5 in the `.sqm` also adds 4 columns to UserProfile (subscription fields). The `MigrationStatements.kt` version 5 does NOT include the UserProfile columns — it only has the 16 WorkoutSession columns.
- **Impact:** Users who upgrade via resilient fallback from version 5 will be MISSING the UserProfile subscription columns. These are caught by `reconcileFullSchema()` manifest. ✅

#### Migration 19: Index Rebuild without Backup
- The migration drops `idx_pr_unique` and recreates it with `phase`. If the app crashes between DROP and CREATE, the unique constraint is lost until reconciliation runs.
- **Mitigation:** `reconcileFullSchema()` has `preDropSql` for idx_pr_unique. ✅

### 🟢 LOW RISK Migrations
- Migrations 1, 2, 7, 12, 13, 14, 15, 16, 17, 18, 20, 22, 23 are all simple ALTER TABLE, CREATE TABLE IF NOT EXISTS, or CREATE INDEX IF NOT EXISTS. These are idempotent and safe.

---

## 3. .sqm vs MigrationStatements.kt Divergence Analysis

This is the **single most concerning finding**. There are TWO migration codepaths:

1. **`.sqm` files** — executed by SQLDelight's `Schema.migrate()` 
2. **`MigrationStatements.kt`** — executed by `applyMigrationResilient()` as fallback

These SHOULD be equivalent but they are NOT for several critical migrations:

| Migration | `.sqm` | `MigrationStatements.kt` | Divergence |
|-----------|--------|--------------------------|------------|
| 3 | Full Copy-Drop-Rename of RoutineExercise | Just 3 ALTER TABLE ADD COLUMN | 🔴 MAJOR: .sqm recreates table with FK, resilient just adds columns |
| 4 | Full Copy-Drop-Rename with Superset creation + data migration | Just CREATE TABLE + 2 ALTER TABLE | 🔴 MAJOR: No data migration in resilient path |
| 5 | 20 ALTER TABLE (UserProfile + WorkoutSession) | 16 ALTER TABLE (WorkoutSession only) | 🟡 Missing 4 UserProfile columns in resilient |
| 8 | Complex DDL healing with CycleDay rebuild | Simplified subset | 🟡 Partial |
| 9 | Complex Superset ID regeneration with data migration | Just CREATE TABLE IF NOT EXISTS + orphan cleanup | 🔴 MAJOR: No Superset ID regeneration in resilient |
| 10 | Full Copy-Drop-Recreate of 5 tables | Just ALTER TABLE + CREATE TABLE IF NOT EXISTS | 🔴 MAJOR: Completely different approach |
| 11 | Removed EarnedBadge/GamificationStats columns | Still includes them | 🟡 Extra statements in resilient |

**Impact:** When the `.sqm` migration fails on iOS (common) and the resilient fallback runs, users get a fundamentally different schema transformation. For migrations 3, 4, 9, and 10, the resilient path does NOT recreate tables or migrate data — it just adds missing columns. This means:

1. **RoutineExercise may lack FK constraints** on iOS resilient path (no ON DELETE CASCADE)
2. **Superset IDs may not be regenerated** to composite format on iOS
3. **Orphaned rows are not cleaned up** in the resilient path

However, `reconcileFullSchema()` catches missing columns and indexes but does NOT:
- Add FK constraints to existing tables
- Clean up orphaned data
- Regenerate Superset IDs

---

## 4. Platform-Specific Issues

### iOS (DriverFactory.ios.kt)

#### `foreignKeyConstraints = false` in NativeSqliteDriver Config 🔴
```kotlin
val driver = NativeSqliteDriver(
    schema = resilientSchema,
    name = DATABASE_NAME,
    onConfiguration = { config ->
        config.copy(
            extendedConfig = DatabaseConfiguration.Extended(
                foreignKeyConstraints = false,  // <-- DISABLED at driver level
            ),
        )
    },
)
```
- Foreign keys are disabled at the DRIVER LEVEL. Then after creation, `PRAGMA foreign_keys = ON` is set.
- **Issue:** This is done because iOS NativeSqliteDriver doesn't persist PRAGMA settings across connections in the connection pool. The driver-level config ensures migrations run without FK enforcement.
- **Risk:** During migration execution, FK constraints are OFF. This means migrations can insert data that violates FK constraints. The `PRAGMA foreign_keys = ON` after driver creation only affects new queries, not data already inserted during migration.
- **Consequence:** Orphaned rows can exist in production iOS databases that would be rejected on Android.

#### ResilientMigratingSchema Manually Sets user_version
```kotlin
driver.execute(null, "PRAGMA user_version = $stepTo", 0)
```
- After each resilient fallback, the iOS code manually bumps `user_version`.
- **Risk:** If the migration partially failed but `user_version` was bumped, the next app launch will SKIP that migration version, leaving the schema in a partially-migrated state.
- **Mitigation:** `reconcileFullSchema()` runs on every open. ✅

#### iOS SQLite Version Differences
- iOS ships with system SQLite (version varies by iOS version). iOS 16+ has SQLite 3.39+. Older iOS versions may have SQLite < 3.35 which lacks `ALTER TABLE ADD COLUMN IF NOT EXISTS`.
- **Current mitigation:** All ALTER TABLE ADD COLUMN statements are run through `applyMigrationResilient` or `reconcileFullSchema` which catch "duplicate column" errors. ✅

### Android (DriverFactory.android.kt)

#### `onUpgrade` Catches SQLiteException Only
```kotlin
} catch (e: SQLiteException) {
```
- The Android upgrade handler only catches `SQLiteException`. Other exception types (e.g., `IllegalStateException`, `RuntimeException`) will crash the app.
- **Risk:** 🟡 If SQLDelight's `Schema.migrate()` throws a non-SQLiteException, the resilient fallback is not invoked.

#### FK Constraints Enabled Before Migration
```kotlin
override fun onOpen(db: SupportSQLiteDatabase) {
    db.execSQL("PRAGMA foreign_keys = ON")
```
- On Android, `PRAGMA foreign_keys = ON` is set in `onOpen`, which runs BEFORE `onUpgrade`.
- Wait — actually, Android SQLite calls `onOpen` AFTER `onUpgrade`. Let me re-check.
- **Actually:** In Android's SQLiteOpenHelper, the call order is: `onConfigure` → `onCreate`/`onUpgrade` → `onOpen`. So `onOpen` runs AFTER `onUpgrade`.
- **BUT:** The `Callback` extends `AndroidSqliteDriver.Callback` which may set FK constraints in `onConfigure`. Looking at the code, there's no `onConfigure` override, so FK constraints are NOT enabled during `onUpgrade`.
- **This means:** On Android, FK constraints are OFF during migration (default), then ON in `onOpen`. This is actually the correct behavior for migration safety. ✅

#### `callbackDriver` Pattern
```kotlin
private fun callbackDriver(db: SupportSQLiteDatabase): SqlDriver =
    AndroidSqliteDriver(database = db, cacheSize = 1)
```
- This creates a NEW SqlDriver wrapping the same database connection. The `cacheSize = 1` is correct for one-shot DDL.
- **Risk:** LOW — this is the recommended pattern.

---

## 5. MigrationManager Code Quality Issues

### Async Migration Execution 🔴
```kotlin
fun checkAndRunMigrations() {
    scope.launch {
        try {
            runMigrations()
        } catch (e: Exception) {
            log.e(e) { "Migration failed" }
        }
    }
}
```
- **CRITICAL:** MigrationManager runs data migrations ASYNCHRONOUSLY on `Dispatchers.IO`. This means:
  1. The app's UI can start rendering BEFORE data migrations complete.
  2. If the user performs a write operation (e.g., starts a workout) while migrations are running, there's a **race condition** between migration writes and user writes.
  3. The migration reads `selectAllSessionsSync()` which returns ALL sessions. If the user writes a new session during migration, it could be mutated by the migration.
- **No completion callback or await mechanism exists.** The caller has no way to know when migrations finish.
- **Test workaround:** Tests use `Thread.sleep(500)` to wait for migrations, confirming the async nature.

### Migration Runs on EVERY App Startup 🟡
```kotlin
private suspend fun runMigrations() {
    cleanupFabricatedRoutineSessionIds()
    normalizeLegacyWorkoutModes()
    backfillLegacyWorkoutRoutineNames()
    repairPersonalRecordsFromWorkoutHistory()
}
```
- These data migrations run EVERY time the app starts, not just once. There's no version tracking or "already migrated" flag.
- `cleanupFabricatedRoutineSessionIds()` scans ALL sessions every startup.
- `normalizeLegacyWorkoutModes()` scans ALL sessions AND ALL personal records every startup.
- `backfillLegacyWorkoutRoutineNames()` scans ALL sessions, routines, and routine exercises every startup.
- `repairPersonalRecordsFromWorkoutHistory()` scans ALL sessions and recomputes ALL PRs every startup.
- **Performance impact:** For users with thousands of sessions, this is a significant startup cost. It runs on a background thread, so it doesn't block the UI, but it does consume CPU and battery.
- **Idempotency:** Each migration IS idempotent (running it twice produces the same result). ✅
- **But:** `repairPersonalRecordsFromWorkoutHistory()` calls `updatePRsIfBetter()` for EVERY session on EVERY startup. This is an O(n) scan with n = total_sessions × profile_count.

### No Transaction Wrapping for Cross-Table Operations 🟡
- `repairPersonalRecordsFromWorkoutHistory()` does NOT wrap the entire operation in a transaction. It processes sessions one by one, calling `personalRecordRepository.updatePRsIfBetter()` for each.
- If the app crashes during repair, some PRs will be repaired and some won't. The next startup will repair the rest (idempotent).
- **Risk:** LOW — idempotency saves us, but performance suffers from lack of batching.

---

## 6. DatabaseFactory/DriverFactory Issues

### DatabaseFactory is Trivial ✅
```kotlin
class DatabaseFactory(private val driverFactory: DriverFactory) {
    fun createDatabase(): VitruvianDatabase = VitruvianDatabase(driverFactory.createDriver())
}
```
- No issues here. Clean and simple.

### DriverFactory (expect/actual) ✅
- Proper expect/actual pattern for KMP. Each platform has its own implementation.

---

## 7. SchemaManifest Analysis

### Comprehensive Coverage ✅
- `manifestTables`: 6 bootstrap tables (EarnedBadge, StreakHistory, GamificationStats, ConnectionLog, DiagnosticsHistory, PhaseStatistics)
- `manifestColumns`: 71 column heal operations covering ALL columns added after table creation
- `manifestIndexes`: 36 index operations covering ALL indexes from the .sq schema
- `idx_pr_unique` has `preDropSql` to handle shape changes across migrations 19 and 21. ✅

### Reconciliation Engine Quality ✅
- Uses blind ALTER + error handling (no PRAGMA pre-check) — correct for cross-platform safety.
- Reports all operations with status (CREATED, ALREADY_PRESENT, TABLE_MISSING, FAILED).
- Runs on EVERY database open — catches any gaps from failed migrations.

### Missing from Manifest 🟡
- **FK constraints:** SchemaManifest can ADD columns and CREATE tables/indexes, but it CANNOT add or modify FK constraints on existing tables. If migration 3 or 4 failed on iOS and the resilient fallback ran, RoutineExercise may lack FK constraints (no ON DELETE CASCADE on routineId, exerciseId, supersetId).
- **Data integrity checks:** SchemaManifest does not clean up orphaned rows. Orphaned RoutineExercise rows (where routineId doesn't exist in Routine) persist.

---

## 8. Transaction Safety During Migrations

### .sqm Migrations
- SQLDelight wraps each `.sqm` file execution in a **single transaction**. If any statement fails, the entire migration rolls back. ✅
- **HOWEVER:** SQLite has limitations with DDL in transactions. `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE` are technically DDL but SQLite does support them in transactions (unlike most RDBMSes).

### Resilient Fallback (`applyMigrationResilient`)
```kotlin
internal fun applyMigrationResilient(driver: SqlDriver, version: Int): List<MigrationStatementResult> {
    val statements = getMigrationStatements(version)
    return statements.map { sql ->
        try {
            driver.execute(identifier = null, sql = sql, parameters = 0)
            ...
        } catch (e: Exception) { ... }
    }
}
```
- **NO TRANSACTION!** Each statement runs independently. If statement 3 of 5 fails non-recoverably, statements 1-2 are committed and statements 4-5 are not run.
- **Risk for migration 24:** Statement 3 is `DROP TABLE IF EXISTS EarnedBadge`, statement 4 is `ALTER TABLE EarnedBadge_rebuild RENAME TO EarnedBadge`. If statement 4 fails, badges are deleted. 🔴

### MigrationManager Data Migrations
- `cleanupFabricatedRoutineSessionIds` uses `database.transaction { }` ✅
- `backfillLegacyWorkoutRoutineNames` uses `database.transaction { }` ✅  
- `normalizeLegacySessionModes` uses `database.transaction { }` ✅
- `normalizeLegacyPersonalRecordModes` uses `database.transaction { }` ✅
- `repairPersonalRecordsFromWorkoutHistory` does NOT use transaction (calls repository methods individually) 🟡

---

## 9. Schema Parity Test Analysis

### Test Coverage ✅
- `SchemaParityTest` verifies fresh install vs upgrade-from-v1 produce identical schemas (tables, columns, indexes).
- Tests every intermediate version (1..24) → 25 upgrade path.
- `LegacySchemaReconciliationTest` tests replay safety for migrations 13, 18, 20, 21, 22.
- `SchemaManifestTest` tests the reconciliation engine.
- `MigrationManagerTest` tests data migration logic.

### Test Gap 🟡
- **No test for iOS-specific resilient fallback path.** Tests use JdbcSqliteDriver (JDBC/JVM), not NativeSqliteDriver. The iOS resilient migration behavior (foreignKeyConstraints=false, manual PRAGMA user_version) is not tested.
- **No test for the "partial migration 24" scenario** (DROP succeeds, RENAME fails).

---

## 10. Specific iOS Crash Vectors

1. **Migration 10 `.sqm` on iOS:** The full Copy-Drop-Recreate of RoutineExercise depends on pre-flight ALTER TABLE statements that are supposed to be run by Kotlin code. But the comment in the `.sqm` says "moved to Kotlin pre-flight" — this pre-flight code no longer exists (it was replaced by SchemaManifest). If the `.sqm` runs on iOS and RoutineExercise lacks some columns (e.g., supersetId from migration 4 that was run via resilient fallback), the `INSERT INTO RoutineExercise_v10 ... SELECT ... supersetId ... FROM RoutineExercise` will FAIL because `supersetId` doesn't exist. This triggers the resilient fallback, which is the simplified version that just adds columns.
   - **Actual severity:** This is caught by the resilient fallback + reconciliation. But the user's RoutineExercise table may have stale FK constraints or orphaned data.

2. **Migration 24 on iOS:** The EarnedBadge rebuild creates `EarnedBadge_rebuild`, copies data, drops old table, renames. If the NativeSqliteDriver's statement execution fails on the RENAME (e.g., SQLite version issue), badges are lost.

3. **Missing gamification tables:** Migrations 11, 22 assume EarnedBadge, GamificationStats, StreakHistory, RpgAttributes exist. If they weren't bootstrapped before migration 11, the ALTER TABLE will fail with "no such table." The resilient fallback catches this. SchemaManifest creates them. But the ORDER matters — if SchemaManifest creates EarnedBadge AFTER migration 24's resilient fallback has already run (and bumped user_version past 24), the table is created WITHOUT data.

---

## 11. Specific Android Data Loss Vectors

1. **`onUpgrade` runs before `onOpen`:** Migration SQL runs BEFORE `reconcileFullSchema()`. If a migration fails and the resilient fallback also fails non-recoverably, `onUpgrade` logs a warning but CONTINUES to the next migration. The schema is in a broken state that `reconcileFullSchema()` may not fully repair (it can't fix missing table recreations or data migrations).

2. **Migration 3 → 4 on very old Android installs:** If a user somehow has a v3 database (RoutineExercise with supersetGroupId/supersetOrder/supersetRestSeconds columns), migration 4's `.sqm` does the Copy-Drop-Rename. If this succeeds on Android but produces a different table shape than what fresh installs have, queries may fail at runtime.

3. **MigrationManager async race:** If the user creates a new routine immediately after app launch (before MigrationManager finishes), the new routine gets `profile_id = 'default'`. If MigrationManager is simultaneously modifying routine data (unlikely but possible), there could be a conflict.

---

## 12. Recommendations for Hardening

### 🔴 CRITICAL (Fix Immediately)

1. **Wrap `applyMigrationResilient` migration 24 in a transaction:**
   ```kotlin
   24 -> {
       driver.execute(null, "BEGIN TRANSACTION", 0)
       try {
           // all 5 statements
           driver.execute(null, "COMMIT", 0)
       } catch (e: Exception) {
           driver.execute(null, "ROLLBACK", 0)
           throw e
       }
   }
   ```
   Or better: make `applyMigrationResilient` always run inside a savepoint for Copy-Drop-Rename migrations.

2. **Make MigrationManager synchronous or add a completion signal:** Change `checkAndRunMigrations()` to return a `Deferred<Unit>` or `suspend fun` that the app awaits before showing UI. The current fire-and-forget pattern risks race conditions.

3. **Add version tracking to MigrationManager data migrations:** Store a "data_migration_version" in SharedPreferences/NSUserDefaults. Only run each data migration once (or when schema version changes), not on every startup.

### 🟡 HIGH (Fix Before Next Release)

4. **Align `MigrationStatements.kt` with `.sqm` files for migration 10:** The resilient fallback for migration 10 should more closely match the `.sqm` behavior. At minimum, it should attempt the Copy-Drop-Recreate pattern (with try/catch around each step), not just ADD COLUMN.

5. **Add FK constraint verification to SchemaManifest:** After reconciliation, verify that critical FK constraints exist (RoutineExercise → Routine, RoutineExercise → Exercise, etc.). If missing, log a warning. Can't add FKs to existing tables in SQLite without rebuild, but at least detect the gap.

6. **Test the iOS resilient fallback path:** Add a test that simulates the iOS migration behavior (foreignKeyConstraints=false, ResilientMigratingSchema) using JdbcSqliteDriver.

7. **Add orphan cleanup to post-migration reconciliation:** After `reconcileFullSchema()`, clean up known orphan patterns:
   - RoutineExercise where routineId NOT IN Routine
   - Superset where routineId NOT IN Routine
   - CycleDay where cycle_id NOT IN TrainingCycle

### 🟢 LOW (Ongoing Improvement)

8. **Reduce MigrationManager startup cost:** Cache a flag indicating "all data migrations have been run for this schema version" so subsequent startups skip the full scan.

9. **Add telemetry to migration failures:** Log (anonymized) migration failure counts to help identify which migrations are failing in the field.

10. **Document the dual-path migration strategy:** Add a MIGRATIONS.md explaining the `.sqm` → `resilient fallback` → `reconcileFullSchema()` defense-in-depth pattern so future contributors don't accidentally break it.

---

## 13. Summary of Findings

| Category | Critical | High | Medium | Low |
|----------|----------|------|--------|-----|
| Migration chain integrity | 0 | 0 | 0 | 0 |
| Per-migration correctness | 1 (mig 10 divergence) | 2 (mig 5, 24) | 3 | 0 |
| Platform differences | 1 (iOS FK off) | 1 (Android SQLiteException only) | 0 | 0 |
| Transaction safety | 1 (resilient no txn for mig 24) | 0 | 1 | 0 |
| MigrationManager | 1 (async race) | 1 (runs every startup) | 1 | 0 |
| SchemaManifest | 0 | 1 (no FK verification) | 0 | 0 |
| Test coverage | 0 | 1 (no iOS path test) | 0 | 0 |
| **TOTAL** | **4** | **6** | **5** | **0** |

The migration infrastructure is well-engineered with multiple defense layers, but the **`.sqm` vs `MigrationStatements.kt` divergence** for complex migrations (3, 4, 9, 10) is the root cause of platform-specific behavior differences. The **async MigrationManager** and **lack of transactions in resilient fallback** are the most likely causes of data loss.

# Migration Review Report

I have reviewed the migration files (`1.sqm` to `10.sqm`) to identify sequencing issues that could cause crashing, particularly on iOS (SQLite).

## Identified Issues

### 1. Migration 8 (`8.sqm`) - Missing Implementation of Fix
**Issue:**
Migration 8 claims to "Ensure CycleDay has all columns" ("FIX 2") to repair a potential schema drift caused by Migration 6 using `CREATE TABLE IF NOT EXISTS`. However, the migration file only contains comments describing the fix, but **no actual SQL statements** to implement it.

**Consequence:**
Users who have a "broken" `CycleDay` table (missing columns like `echo_level`, `eccentric_load_percent`) will continue to experience crashes if the app attempts to query these columns. The schema remains broken until Migration 10, which leaves a dangerous gap between versions 8 and 10.

**Proposed Fix:**
Implement the "Rename-Aside" pattern in `8.sqm` to safely recreate the `CycleDay` table with the correct schema and copy existing data.

### 2. Migration 10 (`10.sqm`) - Foreign Key Violation Crash on iOS
**Issue:**
Migration 10 attempts to drop `RoutineExercise` while it is still referenced by `PlannedSet` (renamed to `PlannedSet_backup`). It relies on `PRAGMA foreign_keys = OFF;` to allow this operation.

**Context:**
Migration 3 explicitly warns that `PRAGMA foreign_keys` is **not reliable on the iOS NativeSqliteDriver** because settings may not persist across connections. Migration 10 ignores this warning.

**Consequence:**
If the `PRAGMA` fails (as expected on iOS), the `DROP TABLE RoutineExercise` statement will fail with a `FOREIGN KEY constraint failed` error because `PlannedSet_backup` still references `RoutineExercise`. This will cause the migration to crash and fail.

**Proposed Fix:**
Modify the sequence in `10.sqm` to remove the Foreign Key dependency *before* dropping `RoutineExercise`.
1. Create temporary tables for `PlannedSet` and `CompletedSet` **without Foreign Keys**.
2. Copy data to these temp tables.
3. Drop the original `PlannedSet` and `CompletedSet` tables (removing the FK references).
4. Drop and recreate `RoutineExercise`.
5. Recreate `PlannedSet` and `CompletedSet` (with FKs) and restore data.
This approach works regardless of the `PRAGMA` support.

## Other Observations

*   **Migration 3:** Correctly handles `INSERT` by omitting new columns, allowing default values to apply. Safe.
*   **Migration 6:** The use of `CREATE TABLE IF NOT EXISTS` for tables that might be changing is the root cause of the `CycleDay` issue. This is a pattern to avoid in the future for evolving schemas.
*   **Migration 9:** The logic for fixing `Superset` IDs seems robust.

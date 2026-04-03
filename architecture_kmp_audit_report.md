# Backend Architecture & Database Audit Report

**Project:** Phoenix Project (Vitruvian Trainer KMP App)  
**Audit Date:** 2026-03-28  
**Auditor:** Backend Architect Agent  
**Scope:** Backend architecture, database layer, Clean Architecture compliance, SQLDelight schema consistency, migration safety, expect/actual patterns, Koin DI  

---

## Executive Summary

The database layer has undergone significant recent refactoring with 2,233 insertions across focus files. The architecture shows a **mature Clean Architecture implementation** with proper separation of concerns, **robust schema reconciliation mechanisms** for cross-platform consistency, and **resilient migration strategies**. However, several areas warrant attention for long-term maintainability.

**Overall Assessment:** ✅ **GOOD** with targeted improvement opportunities  
**Risk Level:** Medium (schema drift potential between platforms, dependency graph complexity)

---

## 1. Clean Architecture Violations

### 1.1 Domain Model Purity

**Status:** ✅ **COMPLIANT** with minor observations

**Evidence:**
- Domain models in `domain/model/` are pure data classes with no Android/iOS dependencies
- `Exercise.kt`, `Routine.kt`, `Models.kt` contain only Kotlin standard library types
- Proper use of `expect/actual` for `generateUUID()` and `currentTimeMillis()` (platform-specific utilities, not business logic)

**Minor Observations:**
| File | Line | Observation | Severity |
|------|------|-------------|----------|
| `domain/model/Models.kt:78` | `WorkoutMode` sealed class duplicates `ProgramMode` enum | Slight redundancy but not a violation | Low |
| `domain/model/Exercise.kt:32` | `oneRepMaxKg` property could be non-nullable with default | Data consistency | Low |

**Repository Mapping Analysis:**
Repositories in `data/repository/` properly map database entities to domain models:
- `SqlDelightExerciseRepository.kt:46-62`: Maps all 21 DB columns to 8 domain properties (selective mapping is correct)
- `SqlDelightWorkoutRepository.kt:95-155`: Properly converts nullable DB columns to nullable domain properties

### 1.2 Data Layer Isolation

**Status:** ✅ **COMPLIANT**

**Evidence:**
- SQLDelight queries are fully encapsulated in repository implementations
- `VitruvianDatabase.sq` (1,920 lines) defines all queries; no inline SQL in domain layer
- Repository interfaces (`ExerciseRepository`, `WorkoutRepository`) in `data/repository/` are clean abstractions

**No violations detected.** Domain layer has zero knowledge of SQLDelight types.

---

## 2. SQLDelight Schema Consistency: Android vs iOS

### 2.1 DriverFactory Implementations

**Status:** ✅ **ALIGNED** with platform-specific adaptations

| Aspect | Android (`DriverFactory.android.kt`) | iOS (`DriverFactory.ios.kt`) | Status |
|--------|--------------------------------------|------------------------------|--------|
| Constructor | Requires `Context` parameter | No parameters (singleton pattern) | ✅ OK - platform idiomatic |
| Database name | `vitruvian.db` | `vitruvian.db` | ✅ Consistent |
| Foreign keys | `PRAGMA foreign_keys = ON` in `onOpen()` | `PRAGMA foreign_keys = ON` post-creation | ✅ Equivalent |
| WAL mode | Uses Android default | Explicit `PRAGMA journal_mode = WAL` | ⚠️ iOS only - acceptable |
| iCloud backup exclusion | N/A (Android doesn't use iCloud) | `NSURLIsExcludedFromBackupKey` | ✅ Platform-specific requirement |

### 2.2 Schema Reconciliation

**Both platforms use shared `reconcileFullSchema()` from `SchemaManifest.kt`:**

```kotlin
// Called in Android onOpen() and iOS post-driver-creation
val report = reconcileFullSchema(driver)
```

**Status:** ✅ **ROBUST**
- Single source of truth for schema healing (6 tables, 71 columns, 36 indexes)
- Both platforms execute identical reconciliation logic

### 2.3 Migration Resilience

**Status:** ✅ **ROBUST** with divergent but equivalent approaches

| Aspect | Android | iOS | Risk Assessment |
|--------|---------|-----|-----------------|
| Migration execution | `onUpgrade()` with `VitruvianDatabase.Schema.migrate()` | `ResilientMigratingSchema` wrapper class | ⚠️ **MEDIUM** - Different code paths could drift |
| Fallback strategy | `applyMigrationResilient()` from common code | Same `applyMigrationResilient()` via wrapper | ✅ Shared logic |
| Error handling | try/catch per version with logging | try/catch with NSLog | ✅ Equivalent |

**⚠️ Issue 2.3.1:** The iOS `ResilientMigratingSchema` wrapper class (lines 139-178 in `DriverFactory.ios.kt`) adds a layer of indirection not present on Android. While they use the same `applyMigrationResilient()` function from common code, the divergence in migration orchestration could lead to behavioral differences if one path is modified without the other.

**Recommendation:** Consider unifying migration execution behind a common expect/actual abstraction or document the behavioral equivalence explicitly.

---

## 3. Database Migration Safety

### 3.1 SchemaParityTest Coverage

**Status:** ✅ **COMPREHENSIVE**

**Test 1:** `fresh install and full upgrade produce identical schemas`
- Compares tables, columns, and indexes between fresh install and upgrade path
- Filters transient tables (`*_rebuild`, `*_new`, `*_temp`, `*_v10`)
- **CI Gate:** Fails build on mismatch

**Test 2:** `every intermediate version upgrades cleanly with all manifest entries`
- Iterates versions 1..24, verifying each can upgrade to 25
- Validates all `manifestColumns` and `manifestIndexes` exist post-reconciliation
- Catches missing columns/indexes from partial migrations

### 3.2 SchemaManifest Validation

**Status:** ✅ **BUILD-TIME ENFORCED**

The `validateSchemaManifest` Gradle task (`shared/build.gradle.kts:189-315`):
- Parses `VitruvianDatabase.sq` for all CREATE TABLE statements
- Cross-references with `.sqm` migration files
- Validates `SchemaManifest.kt` covers all non-V1 columns
- **Build fails** if any column lacks provenance

**Sample enforcement output:**
```
Schema manifest validated: 247 columns across 31 tables, all covered.
```

### 3.3 Migration Risk Assessment

| Risk | Mitigation | Status |
|------|------------|--------|
| Duplicate column errors | `applyMigrationResilient()` catches and ignores duplicates | ✅ Handled |
| Missing table during ALTER | Returns `TABLE_MISSING` status, continues reconciliation | ✅ Handled |
| Partial migration state | Statement-by-statement execution with per-error recovery | ✅ Handled |
| Schema version mismatch | `CURRENT_VERSION = 25L` constant in tests | ✅ Hard-coded |

### 3.4 Data Loss Potential Analysis

**⚠️ Issue 3.4.1:** Migration 24 (`24.sqm`) rebuilds `EarnedBadge` table:
```sql
CREATE TABLE EarnedBadge_rebuild (...)
INSERT OR IGNORE INTO EarnedBadge_rebuild SELECT ... FROM EarnedBadge
DROP TABLE EarnedBadge
ALTER TABLE EarnedBadge_rebuild RENAME TO EarnedBadge
```

**Risk:** `INSERT OR IGNORE` could silently skip rows with constraint violations, causing data loss.

**Current State:** The `UNIQUE(badgeId, profile_id)` constraint in the new table may conflict with legacy data that had only `badgeId` unique.

**Mitigation in Place:** Migration uses `INSERT OR IGNORE` rather than failing, but this trades data integrity for migration success.

**Recommendation:** Add a post-migration verification query to detect lost rows and log for manual recovery.

---

## 4. expect/actual Pattern Correctness

### 4.1 DriverFactory Pattern

**Status:** ✅ **CORRECT**

```kotlin
// commonMain: expect declaration
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

// androidMain: actual with Context dependency
actual class DriverFactory(private val context: Context) { ... }

// iosMain: actual with no parameters
actual class DriverFactory { ... }
```

**Assessment:** Properly uses expect/actual for platform-specific construction while sharing the interface.

### 4.2 Other expect/actual Usage

**Pattern files analyzed:**
| File | commonMain expect | androidMain actual | iosMain actual | Status |
|------|-------------------|-------------------|----------------|--------|
| `PlatformUtils.kt` | `currentTimeMillis()` | `System.currentTimeMillis()` | `NSDate` | ✅ Correct |
| `UUIDGeneration.kt` | `generateUUID()` | `UUID.randomUUID()` | `NSUUID` | ✅ Correct |

**Status:** ✅ **ALL CORRECT** - No missing actual implementations detected.

---

## 5. Koin DI Configuration and Dependency Graph

### 5.1 Module Structure

**Modules defined:**
```kotlin
appModule = domainModule + dataModule + presentationModule + syncModule
platformModule = Android/iOS specific bindings
```

**Status:** ✅ **WELL-STRUCTURED**

### 5.2 DI Verification Test

**KoinModuleVerifyTest.kt** validates:
```kotlin
appModule.verify(
    extraTypes = listOf(
        DriverFactory::class,      // Platform-provided
        Settings::class,           // Platform-provided
        BleRepository::class,      // Platform-provided
        SafeWordListenerFactory::class,
        HealthIntegration::class,
        Function0::class,        // Lambda injection
    )
)
```

**Status:** ✅ **TESTED** - Module compiles and dependencies are resolvable.

### 5.3 Dependency Graph Health

**Repository dependencies (analyzed):**
```
SqlDelightExerciseRepository
├── VitruvianDatabase (injected)
└── ExerciseImporter (injected)

SqlDelightWorkoutRepository
├── VitruvianDatabase (injected)
└── ExerciseRepository (injected)
```

**Status:** ✅ **NO CIRCULAR DEPENDENCIES DETECTED**

### 5.4 Platform Module Differences

| Binding | Android | iOS | Risk |
|---------|---------|-----|------|
| `DriverFactory` | `DriverFactory(context)` | `DriverFactory()` | ⚠️ Constructor arity differs |
| `Settings` | `AndroidSettings` | `NSUserDefaultsSettings` | ✅ OK |
| `BleRepository` | `AndroidBleRepository` | `KableBleRepository` | ✅ OK |

**⚠️ Issue 5.4.1:** `DriverFactory` has different constructor signatures between platforms. While Koin handles this via platform-specific modules, the expect/actual contract doesn't enforce parameter parity.

**Recommendation:** Document the constructor difference in the expect declaration or consider unifying behind a factory function.

---

## 6. SchemaManifest.kt Deep Analysis

### 6.1 Completeness

**Tables covered (manifestTables):**
1. `EarnedBadge` - Bootstrap table (no creation migration)
2. `StreakHistory` - Bootstrap table
3. `GamificationStats` - Bootstrap table
4. `ConnectionLog` - Bootstrap table
5. `DiagnosticsHistory` - Bootstrap table
6. `PhaseStatistics` - Bootstrap table

**Columns covered (manifestColumns):** 71 ALTER TABLE operations
- Comments trace each column to migration number or "No migration"
- Includes recently added columns (migration 21: `profile_id` additions)

**Indexes covered (manifestIndexes):** 36 index operations
- Includes `preDropSql` for `idx_pr_unique` (shape changed across migrations)

### 6.2 Idempotency Guarantee

All operations use `IF NOT EXISTS` or error-catching:
```kotlin
// Tables
CREATE TABLE IF NOT EXISTS ...

// Columns
ALTER TABLE ... ADD COLUMN ...  -- Catches "duplicate column"

// Indexes
CREATE INDEX IF NOT EXISTS ...
```

**Status:** ✅ **FULLY IDEMPOTENT**

---

## 7. Recommendations (Prioritized)

### 🔴 High Priority (Refactoring Effort: Low, Risk: High)

1. **Add Migration 24 Data Loss Detection**
   - File: `MigrationStatements.kt`
   - Add post-migration row count comparison
   - Log warning if `EarnedBadge` row count differs pre/post rebuild

2. **Document DriverFactory Constructor Difference**
   - File: `DriverFactory.kt` (expect declaration)
   - Add KDoc explaining Android requires Context, iOS uses singleton

### 🟡 Medium Priority (Refactoring Effort: Medium, Risk: Medium)

3. **Unify Migration Orchestration**
   - Files: `DriverFactory.android.kt`, `DriverFactory.ios.kt`
   - Extract common migration loop to shared code
   - Keep platform-specific error handling (Log vs NSLog)

4. **Add Index Shape Verification to SchemaParityTest**
   - File: `SchemaParityTest.kt`
   - Currently checks index existence, not index definition
   - Would catch `idx_pr_unique` shape mismatches

### 🟢 Low Priority (Refactoring Effort: High, Risk: Low)

5. **Normalize WorkoutMode/ProgramMode Redundancy**
   - Files: `Models.kt` (both sealed class and enum exist)
   - Consider deprecating `WorkoutMode` sealed class
   - Requires UI refactoring effort

6. **Add Column Type Verification**
   - File: `SchemaManifestTest.kt`
   - Extend to verify column types match between fresh/upgrade

---

## 8. Conclusion

The backend architecture demonstrates **mature Clean Architecture practices** with proper domain/data separation, **robust cross-platform schema consistency** via the SchemaManifest reconciliation system, and **defensive migration strategies** that gracefully handle partial states.

The recent 2,233-line refactoring has successfully:
- ✅ Consolidated 5 fragmented schema healing mechanisms into 1
- ✅ Added build-time validation preventing schema drift
- ✅ Implemented resilient migration fallbacks on both platforms
- ✅ Added comprehensive CI safety nets (SchemaParityTest)

**The system is production-ready** with the noted high-priority recommendations representing preventive maintenance rather than critical fixes.

---

## Appendix: File References

### Focus Files (per task specification)
| File | Path | Lines | Status |
|------|------|-------|--------|
| DriverFactory.kt (expect) | `shared/src/commonMain/kotlin/.../data/local/DriverFactory.kt` | 7 | ✅ Reviewed |
| DriverFactory.android.kt | `shared/src/androidMain/kotlin/.../data/local/DriverFactory.android.kt` | 89 | ✅ Reviewed |
| DriverFactory.ios.kt | `shared/src/iosMain/kotlin/.../data/local/DriverFactory.ios.kt` | 178 | ✅ Reviewed |
| SchemaManifest.kt | `shared/src/commonMain/kotlin/.../data/local/SchemaManifest.kt` | 428 | ✅ Reviewed |
| MigrationStatements.kt | `shared/src/commonMain/kotlin/.../data/local/MigrationStatements.kt` | 414 | ✅ Reviewed |
| SchemaParityTest.kt | `shared/src/androidHostTest/kotlin/.../data/local/SchemaParityTest.kt` | 310 | ✅ Reviewed |
| SchemaManifestTest.kt | `shared/src/androidHostTest/kotlin/.../data/local/SchemaManifestTest.kt` | 268 | ✅ Reviewed |

### Domain Models Reviewed
| File | Path | Status |
|------|------|--------|
| Models.kt | `shared/src/commonMain/kotlin/.../domain/model/Models.kt` | ✅ Pure |
| Exercise.kt | `shared/src/commonMain/kotlin/.../domain/model/Exercise.kt` | ✅ Pure |
| Routine.kt | `shared/src/commonMain/kotlin/.../domain/model/Routine.kt` | ✅ Pure |
| Gamification.kt | `shared/src/commonMain/kotlin/.../domain/model/Gamification.kt` | ✅ Pure |

### DI Modules Reviewed
| File | Path | Status |
|------|------|--------|
| KoinInit.kt | `shared/src/commonMain/kotlin/.../di/KoinInit.kt` | ✅ Clean |
| AppModule.kt | `shared/src/commonMain/kotlin/.../di/AppModule.kt` | ✅ Clean |
| DataModule.kt | `shared/src/commonMain/kotlin/.../di/DataModule.kt` | ✅ Clean |
| DomainModule.kt | `shared/src/commonMain/kotlin/.../di/DomainModule.kt` | ✅ Clean |
| PlatformModule.android.kt | `shared/src/androidMain/kotlin/.../di/PlatformModule.android.kt` | ✅ Clean |
| PlatformModule.ios.kt | `shared/src/iosMain/kotlin/.../di/PlatformModule.ios.kt` | ✅ Clean |
| KoinModuleVerifyTest.kt | `shared/src/androidHostTest/kotlin/.../di/KoinModuleVerifyTest.kt` | ✅ Passes |

---

*Report generated by Backend Architect Agent - Droid Factory*  
*Format: Structured Markdown for aggregation with other reviewers*

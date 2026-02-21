---
phase: 13-biomechanics-persistence
plan: 01
subsystem: database
tags: [sqldelight, biomechanics, vbt, force-curve, asymmetry, schema-migration, repository]

# Dependency graph
requires:
  - phase: 12-biomechanics-engine
    provides: "BiomechanicsEngine, BiomechanicsRepResult, BiomechanicsSetSummary domain models"
provides:
  - "RepBiomechanics SQL table with per-rep VBT, force curve, and asymmetry columns"
  - "5 biomechanics summary columns on WorkoutSession (avgMcvMmS, avgAsymmetryPercent, totalVelocityLossPercent, dominantSide, strengthProfile)"
  - "BiomechanicsRepository interface + SqlDelightBiomechanicsRepository implementation"
  - "Persistence wiring in ActiveSessionEngine at set completion and session save"
  - "Schema v16 across Android (15.sqm) and iOS (DriverFactory.ios.kt)"
affects: [13-02-session-history-ui, ghost-racing, rpg-system, readiness-model]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "3-location schema migration pattern (VitruvianDatabase.sq + .sqm + DriverFactory.ios.kt)"
    - "JSON serialization of FloatArray via toJsonString()/toFloatArrayFromJson() for force curve data"
    - "GATE-04 compliance: biomechanics captured for ALL tiers, gating only at UI layer"

key-files:
  created:
    - shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/15.sqm
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BiomechanicsRepository.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeBiomechanicsRepository.kt
  modified:
    - shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt
    - shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt
    - shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
    - shared/build.gradle.kts
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt

key-decisions:
  - "Reused toJsonString()/toFloatArrayFromJson() from RepMetricRepository for FloatArray serialization instead of adding a new JSON library"
  - "Captured biomechanics summary in saveWorkoutSession() via direct coordinator.biomechanicsEngine.getSetSummary() call — safe because engine not yet reset at that point"
  - "Used INSERT OR REPLACE for RepBiomechanics with UNIQUE INDEX on (sessionId, repNumber) to handle idempotent writes"

patterns-established:
  - "BiomechanicsRepository follows same pattern as RepMetricRepository: interface + SqlDelight implementation + Koin registration"
  - "DI chain for new repositories: DataModule -> MainViewModel -> DWSM -> ActiveSessionEngine (4-hop injection)"

requirements-completed: [PERSIST-01, PERSIST-02, PERSIST-03, PERSIST-04, PERSIST-05]

# Metrics
duration: 13min
completed: 2026-02-21
---

# Phase 13 Plan 01: Biomechanics Persistence Summary

**RepBiomechanics table with per-rep VBT/force-curve/asymmetry data, schema v16 migration across Android and iOS, BiomechanicsRepository with SqlDelight implementation, and persistence wiring in ActiveSessionEngine at set completion**

## Performance

- **Duration:** 13 min
- **Started:** 2026-02-21T03:13:56Z
- **Completed:** 2026-02-21T03:27:32Z
- **Tasks:** 2
- **Files modified:** 18

## Accomplishments
- RepBiomechanics table with 17 columns covering VBT metrics, force curve data (JSON-serialized FloatArrays), and asymmetry analysis
- 5 biomechanics summary columns on WorkoutSession for quick session-level queries without joining RepBiomechanics
- Schema v16 applied consistently across all 3 locations: VitruvianDatabase.sq, 15.sqm, and DriverFactory.ios.kt (plus DriverFactory.android.kt migration map)
- BiomechanicsRepository interface + SqlDelightBiomechanicsRepository with save/get/delete, registered in Koin DI
- Persistence wiring: per-rep data saved in handleSetCompletion(), session summary populated in saveWorkoutSession()
- GATE-04 compliance: no subscription tier checks in repository — data captured for all users

## Task Commits

Each task was committed atomically:

1. **Task 1: Schema migration v16 -- RepBiomechanics table + WorkoutSession summary columns** - `695c082c` (feat)
2. **Task 2: BiomechanicsRepository + persistence wiring in ActiveSessionEngine** - `a5e47f10` (feat)

**Plan metadata:** (pending final commit)

## Files Created/Modified
- `shared/src/commonMain/sqldelight/.../VitruvianDatabase.sq` - RepBiomechanics CREATE TABLE, indexes, queries; WorkoutSession 5 new columns; updated insertSession/upsertSyncSession
- `shared/src/commonMain/sqldelight/.../migrations/15.sqm` - Schema migration v15->v16
- `shared/src/commonMain/kotlin/.../domain/model/Models.kt` - WorkoutSession: 5 new nullable fields + hasBiomechanicsData property
- `shared/src/commonMain/kotlin/.../data/repository/BiomechanicsRepository.kt` - Interface + SqlDelightBiomechanicsRepository
- `shared/src/commonMain/kotlin/.../data/repository/SqlDelightWorkoutRepository.kt` - Updated mapper (5 new params) and saveSession
- `shared/src/commonMain/kotlin/.../data/repository/SqlDelightSyncRepository.kt` - Updated upsertSyncSession with 5 null placeholders
- `shared/src/commonMain/kotlin/.../presentation/manager/ActiveSessionEngine.kt` - Biomechanics persistence in handleSetCompletion + saveWorkoutSession
- `shared/src/commonMain/kotlin/.../presentation/manager/DefaultWorkoutSessionManager.kt` - Added biomechanicsRepository constructor param
- `shared/src/commonMain/kotlin/.../presentation/viewmodel/MainViewModel.kt` - Added biomechanicsRepository constructor param
- `shared/src/commonMain/kotlin/.../di/DataModule.kt` - Registered BiomechanicsRepository singleton
- `shared/src/commonMain/kotlin/.../di/PresentationModule.kt` - Added 14th get() to MainViewModel factory
- `shared/src/iosMain/kotlin/.../data/local/DriverFactory.ios.kt` - CURRENT_SCHEMA_VERSION=16L, RepBiomechanics table, indexes, columns
- `shared/src/androidMain/kotlin/.../data/local/DriverFactory.android.kt` - Migration 15 statements
- `shared/build.gradle.kts` - SQLDelight version 15->16
- `shared/src/commonMain/kotlin/.../util/BackupModels.kt` - WorkoutSessionBackup: 5 new fields
- `shared/src/commonMain/kotlin/.../util/DataBackupManager.kt` - Export/import mapping for 5 new columns
- `shared/src/commonTest/kotlin/.../testutil/FakeBiomechanicsRepository.kt` - Test fake with in-memory map
- `shared/src/commonTest/kotlin/.../testutil/DWSMTestHarness.kt` - Added fakeBiomechanicsRepo + DWSM wiring

## Decisions Made
- Reused existing `toJsonString()`/`toFloatArrayFromJson()` extension functions from RepMetricRepository for FloatArray serialization — avoids adding new dependencies or duplicating serialization logic
- Called `coordinator.biomechanicsEngine.getSetSummary()` directly in `saveWorkoutSession()` rather than threading it through parameters — the engine data is still available at that point (reset happens later in handleSetCompletion)
- Used `INSERT OR REPLACE` with `UNIQUE INDEX on (sessionId, repNumber)` for idempotent biomechanics writes
- Mapped enum values (VelocityZone, StrengthProfile) as TEXT with safe fallback parsing (`entries.find` with defaults) for forward compatibility

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated DataBackupManager and SyncRepository for new columns**
- **Found during:** Task 1 (Schema migration)
- **Issue:** Build failed because `DataBackupManager.kt` calls `insertSession` directly for backup import, and `SqlDelightSyncRepository.kt` calls `upsertSyncSession` — both now require 5 additional parameters
- **Fix:** Added 5 biomechanics fields to `WorkoutSessionBackup` data class, updated export mapping in `DataBackupManager`, updated import call site with new parameters, updated `SqlDelightSyncRepository.upsertSyncSession()` call with null placeholders
- **Files modified:** `BackupModels.kt`, `DataBackupManager.kt`, `SqlDelightSyncRepository.kt`
- **Verification:** Build passes after fix
- **Committed in:** `695c082c` (part of Task 1 commit)

**2. [Rule 1 - Bug] Fixed duplicate biomechanicsSummary variable in handleSetCompletion**
- **Found during:** Task 2 (Persistence wiring)
- **Issue:** After adding biomechanics persistence block with `val biomechanicsSummary = coordinator.biomechanicsEngine.getSetSummary()`, a second `val biomechanicsSummary` declaration existed later in the same scope (the existing code that uses it for rep metric tagging)
- **Fix:** Removed the duplicate declaration and reused the variable from the persistence block — `getSetSummary()` is idempotent and the engine hasn't been reset at that point
- **Files modified:** `ActiveSessionEngine.kt`
- **Verification:** Build and tests pass
- **Committed in:** `a5e47f10` (part of Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both auto-fixes necessary for correctness. No scope creep. The backup/sync fix was a predictable downstream consequence of changing insertSession/upsertSyncSession query signatures.

## Issues Encountered
None beyond the auto-fixed deviations above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- BiomechanicsRepository is ready for Plan 02 (session history UI) to query persisted biomechanics data
- `getRepBiomechanics(sessionId)` returns fully hydrated `BiomechanicsRepResult` objects for UI display
- `WorkoutSession.hasBiomechanicsData` property enables conditional UI rendering
- Ghost racing, RPG, and readiness phases can now query historical biomechanics data

## Self-Check: PASSED

All 18 files verified present on disk. Both task commits (695c082c, a5e47f10) verified in git log.

---
*Phase: 13-biomechanics-persistence*
*Completed: 2026-02-21*

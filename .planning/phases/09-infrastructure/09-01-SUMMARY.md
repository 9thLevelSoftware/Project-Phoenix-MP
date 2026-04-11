---
phase: 09-infrastructure
plan: 01
subsystem: database
tags: [sqldelight, sqlite, migration, power-calculation, index, ble]

# Dependency graph
requires: []
provides:
  - "Corrected dual-cable power calculation using combined load (loadA + loadB)"
  - "MetricSample sessionId index for faster session detail queries"
  - "SQLDelight schema version synchronized to 14 across Android and iOS"
affects: [workout-metrics, session-detail-views]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "iOS 4-layer defense schema version bump pattern"
    - "Android resilient migration handler pattern for new migrations"

key-files:
  created:
    - "shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/13.sqm"
  modified:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt"
    - "shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq"
    - "shared/build.gradle.kts"
    - "shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt"
    - "shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt"

key-decisions:
  - "Fixed stale build.gradle.kts version from 11 to 14 (was missing migrations 11-13)"
  - "iOS DriverFactory already had idx_metric_sample_session in createAllIndexes, only version bump needed"

patterns-established:
  - "Migration numbering: version = 1 + count of .sqm files"

# Metrics
duration: 3min
completed: 2026-02-15
---

# Phase 9 Plan 1: Power Calculation Fix and MetricSample Index Summary

**Fixed dual-cable power formula to use combined load (loadA + loadB) and added MetricSample sessionId index with migration 13, syncing schema version to 14 across platforms**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-15T03:51:47Z
- **Completed:** 2026-02-15T03:55:00Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Power calculation now correctly uses both cable loads for accurate dual-cable power display
- MetricSample sessionId index accelerates session detail queries (WHERE sessionId = ? ORDER BY timestamp)
- Schema version synchronized from stale 11 to correct 14 across build.gradle.kts, iOS, and Android
- Full Android build verified passing

## Task Commits

Each task was committed atomically:

1. **Task 1: Fix power calculation and add MetricSample index to schema** - `207b2d93` (fix)
2. **Task 2: Create migration 13.sqm and update version numbers** - `ab076986` (chore)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt` - Fixed power formula from `loadA * velocity` to `(loadA + loadB) * velocity`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` - Added idx_metric_sample_session index definition
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/13.sqm` - Migration adding MetricSample sessionId index
- `shared/build.gradle.kts` - Updated SQLDelight version from 11 to 14
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.ios.kt` - Updated CURRENT_SCHEMA_VERSION from 13L to 14L
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/local/DriverFactory.android.kt` - Added migration 13 to getMigrationStatements

## Decisions Made
- Fixed stale build.gradle.kts version (was 11, should have been 13 before this migration, now 14). This was a pre-existing issue where the version was not bumped for migrations 11 and 12.
- iOS DriverFactory already had the `idx_metric_sample_session` index in its `createAllIndexes` method, so only the version constant needed updating.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Database schema is consistent across platforms at version 14
- Power calculation is correct for dual-cable exercises
- Ready for 09-02 plan execution

## Self-Check: PASSED

All 6 files verified present. Both task commits found (207b2d93, ab076986).

---
*Phase: 09-infrastructure*
*Completed: 2026-02-15*

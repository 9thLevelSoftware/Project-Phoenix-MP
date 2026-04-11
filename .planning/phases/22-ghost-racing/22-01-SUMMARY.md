---
phase: 22-ghost-racing
plan: 01
subsystem: domain
tags: [ghost-racing, vbt, velocity, tdd, sqldelight]

# Dependency graph
requires:
  - phase: 20-readiness
    provides: "ReadinessEngine pattern (stateless object, pure functions, no DI)"
  - phase: 13-biomechanics
    provides: "RepBiomechanics table with mcvMmS column, avgMcvMmS in WorkoutSession"
provides:
  - "GhostRacingEngine stateless computation object"
  - "GhostModels.kt domain types (GhostSession, GhostVerdict, GhostRepComparison, GhostSetSummary, GhostSessionCandidate)"
  - "selectBestGhostSession SQL query in VitruvianDatabase.sq"
affects: [22-ghost-racing]

# Tech tracking
tech-stack:
  added: []
  patterns: [ghost-racing-engine-pattern, rep-index-synced-comparison]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/GhostModels.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/GhostRacingEngine.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/GhostRacingEngineTest.kt
  modified:
    - shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq

key-decisions:
  - "GhostRacingEngine follows ReadinessEngine pattern: stateless object, pure functions, no DI"
  - "5% tied tolerance for compareRep prevents noisy verdict flicker on near-equal velocities"
  - "BEYOND reps excluded from delta calculations to avoid skewing set summary with incomparable data"
  - "selectBestGhostSession is a SELECT-only query, no iOS DriverFactory sync needed"

patterns-established:
  - "Ghost comparison uses rep-index sync (not wall-clock) matching plan decision"
  - "GhostSessionCandidate as lightweight DB-result type before loading full rep data"

requirements-completed: [GHOST-01, GHOST-03, GHOST-04]

# Metrics
duration: 7min
completed: 2026-02-28
---

# Phase 22 Plan 01: Ghost Racing Engine Summary

**TDD-built GhostRacingEngine with session matching, 5% tolerance rep comparison, set delta aggregation, and selectBestGhostSession SQL query**

## Performance

- **Duration:** 7 min
- **Started:** 2026-02-28T19:07:56Z
- **Completed:** 2026-02-28T19:15:19Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- GhostModels.kt with 5 domain types for the ghost racing subsystem
- GhostRacingEngine with 3 pure functions: findBestSession, compareRep, computeSetDelta
- 12 TDD test cases all passing, covering session matching, rep comparison, and set aggregation
- selectBestGhostSession SQL query added to VitruvianDatabase.sq and compiling via SQLDelight

## Task Commits

Each task was committed atomically:

1. **Task 1: RED -- Domain models and failing tests** - `44e6e43c` (test)
2. **Task 2: GREEN -- Implement GhostRacingEngine + SQL query** - `a02ab5ec` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/GhostModels.kt` - GhostSession, GhostVerdict, GhostRepComparison, GhostSetSummary, GhostSessionCandidate domain types
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/GhostRacingEngine.kt` - Stateless computation engine for ghost session matching and velocity comparison
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/GhostRacingEngineTest.kt` - 12 test cases covering all engine functions
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq` - Added selectBestGhostSession named query

## Decisions Made
- GhostRacingEngine follows ReadinessEngine pattern: stateless object, pure functions, no DI
- 5% tied tolerance for compareRep prevents noisy verdict flicker on near-equal velocities
- BEYOND reps excluded from delta calculations to avoid skewing set summary with incomparable data
- selectBestGhostSession is a SELECT-only query -- no schema change, no iOS DriverFactory sync needed

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- GhostRacingEngine ready for Plan 02 to wire into workout lifecycle via GhostRacingManager
- GhostSessionCandidate type ready for DB query result mapping
- selectBestGhostSession query ready to be called from repository layer
- All domain types exported for Plan 03 UI overlay consumption

## Self-Check: PASSED

All 4 files verified present. Both task commits (44e6e43c, a02ab5ec) verified in git log.

---
*Phase: 22-ghost-racing*
*Completed: 2026-02-28*

---
phase: 20-readiness-briefing
plan: 01
subsystem: domain
tags: [acwr, readiness, sports-science, tdd, pure-computation]

# Dependency graph
requires:
  - phase: 19-cv-form-check
    provides: "formScore DB column (migration 16), SessionSummary model"
provides:
  - "ReadinessEngine stateless ACWR computation object"
  - "ReadinessModels (ReadinessStatus enum, ReadinessResult sealed class)"
  - "ReadinessEngineTest with 13 test cases covering all ACWR zones and guards"
affects: [20-02, readiness-briefing-card, active-workout-screen]

# Tech tracking
tech-stack:
  added: []
  patterns: [pure-computation-engine-tdd, acwr-rolling-average]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ReadinessModels.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngine.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngineTest.kt
  modified:
    - shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepositoryTest.kt
    - shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightGamificationRepositoryTest.kt

key-decisions:
  - "Rolling average ACWR (not EWMA) -- adequate for local heuristic, full Bannister FFM deferred to Portal"
  - "Four data sufficiency guards (empty, <28d history, <3 recent sessions, zero chronic) prevent misleading scores"

patterns-established:
  - "ReadinessEngine follows SmartSuggestionsEngine pattern: stateless object, pure functions, nowMs parameter for testability"
  - "ACWR zone mapping: sweet spot 0.8-1.3 -> 70-100, under-training <0.8 -> 30-70, overreach 1.3-1.5 -> 40-70, danger >1.5 -> 0-40"

requirements-completed: [BRIEF-01]

# Metrics
duration: 6min
completed: 2026-02-28
---

# Phase 20 Plan 01: ReadinessEngine Summary

**Pure ACWR-based readiness engine with rolling average model, 4 data sufficiency guards, and 13 TDD test cases across all workload ratio zones**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-28T16:22:39Z
- **Completed:** 2026-02-28T16:29:00Z
- **Tasks:** 2 (RED + GREEN TDD phases)
- **Files modified:** 5 (3 created, 2 fixed)

## Accomplishments
- ReadinessEngine.computeReadiness() computes ACWR from SessionSummary data with 4 data sufficiency guards
- mapAcwrToScore() maps ACWR ratio to 0-100 score clamped across 5 zones (under-training, transition, sweet spot, overreaching, danger)
- ReadinessStatus (GREEN/YELLOW/RED) derived from score thresholds (70/40)
- 13 unit tests passing: 4 guard tests, 4 ACWR zone tests, 4 mapAcwrToScore tests, 1 field verification test

## Task Commits

Each task was committed atomically (TDD RED/GREEN):

1. **RED: Failing tests for ReadinessEngine** - `4f9bb0fc` (test)
2. **GREEN: Implement ReadinessEngine, all 13 tests pass** - `29381cec` (feat)

_Refactor phase skipped -- code already clean and minimal._

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ReadinessModels.kt` - ReadinessStatus enum (GREEN/YELLOW/RED), ReadinessResult sealed class (InsufficientData, Ready)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngine.kt` - Pure stateless ACWR computation engine with computeReadiness() and mapAcwrToScore()
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngineTest.kt` - 13 test cases covering all data sufficiency guards and ACWR zones
- `shared/src/androidUnitTest/.../SqlDelightCompletedSetRepositoryTest.kt` - Fixed missing formScore parameter (pre-existing)
- `shared/src/androidUnitTest/.../SqlDelightGamificationRepositoryTest.kt` - Fixed missing formScore parameter (pre-existing)

## Decisions Made
- Used rolling average ACWR (not EWMA) -- simple, deterministic, testable, adequate for a local advisory heuristic. Full Bannister FFM deferred to Portal (PORTAL-03).
- Four data sufficiency guards prevent misleading scores: empty sessions, <28 day history, <3 recent sessions in 14 days, zero chronic volume.
- Volume formula matches SmartSuggestionsEngine exactly: `weightPerCableKg * 2 * workingReps`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed pre-existing formScore parameter missing from test files**
- **Found during:** GREEN phase (test compilation)
- **Issue:** SqlDelightCompletedSetRepositoryTest and SqlDelightGamificationRepositoryTest were missing the `formScore` parameter added in Phase 19 migration 16, causing module-wide test compilation failure
- **Fix:** Added `formScore = null` to insertSession() calls in both test files
- **Files modified:** SqlDelightCompletedSetRepositoryTest.kt, SqlDelightGamificationRepositoryTest.kt
- **Verification:** Module compiles and all tests pass
- **Committed in:** 29381cec (GREEN phase commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Pre-existing issue from Phase 19 that blocked test compilation. No scope creep.

## Issues Encountered
None -- TDD cycle executed cleanly after fixing the pre-existing compilation error.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- ReadinessEngine is ready for Plan 02 (UI card integration, tier gating)
- ReadinessResult sealed class provides clean API for composable rendering
- mapAcwrToScore() is `internal` for direct test access but not public API

---
*Phase: 20-readiness-briefing*
*Completed: 2026-02-28*

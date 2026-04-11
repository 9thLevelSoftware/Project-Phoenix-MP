---
phase: 03-rep-quality-scoring
plan: 01
subsystem: domain
tags: [kotlin, tdd, scoring-algorithm, rep-quality, running-average]

# Dependency graph
requires:
  - phase: 01-data-foundation
    provides: RepMetricData domain model, RunningAverage utility
provides:
  - RepQualityScorer engine with four-component scoring (ROM, velocity, eccentric, smoothness)
  - RepQualityScore, QualityTrend, SetQualitySummary domain models
  - Full test coverage (13 tests) for scoring algorithm
affects: [03-02 (integration), 03-03 (UI display)]

# Tech tracking
tech-stack:
  added: []
  patterns: [stateful-scorer-with-running-averages, coefficient-of-variation-smoothness]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RepQuality.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorer.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorerTest.kt
  modified: []

key-decisions:
  - "First rep gets perfect ROM/velocity (no baseline to penalize against)"
  - "Scoring computed before updating running averages (score against prior reps only)"
  - "Trend uses half-split comparison with +/-5 threshold for detecting change"
  - "Smoothness uses coefficient of variation (stddev/mean) as normalization"

patterns-established:
  - "Deviation scoring: score = maxPoints * max(0, 1 - deviation * multiplier)"
  - "Eccentric ratio scoring: ideal=2.0, penalty proportional to distance from ideal"

# Metrics
duration: 5min
completed: 2026-02-14
---

# Phase 3 Plan 01: Rep Quality Scorer Summary

**TDD-built RepQualityScorer engine scoring reps 0-100 across four weighted components (ROM 30, velocity 25, eccentric control 25, smoothness 20) with trend detection and set aggregation**

## Performance

- **Duration:** 5 min
- **Started:** 2026-02-14T20:10:21Z
- **Completed:** 2026-02-14T20:15:18Z
- **Tasks:** 3 (RED/GREEN/REFACTOR)
- **Files created:** 3

## Accomplishments
- RepQualityScore, QualityTrend, SetQualitySummary domain models
- RepQualityScorer with four-component scoring algorithm using RunningAverage baselines
- Trend detection (improving/stable/declining) via first-half vs second-half comparison
- 13 passing tests covering all scoring components, edge cases, trend detection, summary, and reset

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): Write failing tests** - `9cefeab3` (test)
2. **Task 2 (GREEN): Implement RepQualityScorer** - `378f7c6b` (feat)
3. **Task 3 (REFACTOR): Review and clean up** - No commit (implementation already clean with extracted helpers and named constants)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RepQuality.kt` - RepQualityScore data class, QualityTrend enum, SetQualitySummary
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorer.kt` - Stateful scorer with four scoring components, trend detection, set summary
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorerTest.kt` - 13 unit tests covering all components and edge cases

## Decisions Made
- First rep receives perfect ROM (30) and velocity (25) scores since there is no baseline to compare against
- Running averages are updated after scoring each rep, so a rep is only scored against prior reps
- Trend detection uses a simple half-split comparison with a +/-5 point threshold
- Smoothness scoring uses coefficient of variation (standard deviation / mean) as the normalization approach, with a 2x multiplier for sensitivity
- Refactoring was done proactively during GREEN phase (extracted helpers, named constants, KDoc) so Task 3 was a no-op

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- RepQualityScorer is ready for integration into the workout pipeline (Plan 02)
- Domain models (RepQualityScore, SetQualitySummary) ready for UI consumption (Plan 03)
- All public APIs have KDoc documentation

## Self-Check: PASSED

- [x] RepQuality.kt exists
- [x] RepQualityScorer.kt exists
- [x] RepQualityScorerTest.kt exists
- [x] Commit 9cefeab3 (test) exists
- [x] Commit 378f7c6b (feat) exists
- [x] 13/13 tests pass

---
*Phase: 03-rep-quality-scoring*
*Completed: 2026-02-14*

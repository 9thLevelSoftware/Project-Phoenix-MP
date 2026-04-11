---
phase: 12-mobile-replay-cards
plan: 01
subsystem: domain
tags: [signal-processing, valley-detection, rep-segmentation, tdd]

# Dependency graph
requires:
  - phase: 11-exercise-auto-detection
    provides: Valley detection algorithm pattern in SignatureExtractor
provides:
  - RepBoundaryDetector class with detectBoundaries function
  - RepBoundary data class with concentric/eccentric phase indices
  - Valley-based rep segmentation algorithm for position time-series
affects: [12-02, 12-03, replay-visualization, rep-isolation]

# Tech tracking
tech-stack:
  added: []
  patterns: [5-sample moving average smoothing, local minima detection with prominence]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/replay/RepBoundaryDetector.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/replay/RepBoundaryDetectorTest.kt
  modified: []

key-decisions:
  - "Valley threshold 10mm matches Phase 11 SignatureExtractor pattern"
  - "Minimum 8-sample separation prevents false positives from noise"
  - "Edge valley detection handles data starting/ending at valleys"

patterns-established:
  - "Rep boundary detection: valley-valley pairs with peak identification"
  - "Concentric/eccentric split at peak index"

# Metrics
duration: 3min
completed: 2026-02-15
---

# Phase 12 Plan 01: Rep Boundary Detection Summary

**Valley-based rep boundary detection with 5-sample smoothing, 10mm prominence threshold, and concentric/eccentric phase isolation via peak identification**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-15T16:37:24Z
- **Completed:** 2026-02-15T16:40:26Z
- **Tasks:** 1 (TDD RED->GREEN)
- **Files modified:** 2

## Accomplishments
- RepBoundaryDetector class segments position arrays into individual rep boundaries
- RepBoundary data class with startIndex, peakIndex, endIndex, concentricIndices, eccentricIndices
- 13 passing tests covering: minimum data, single rep, multiple reps, edge valleys, peak identification, phase partitioning, noisy data, valley separation

## Task Commits

Each task was committed atomically:

1. **Task 1: RepBoundaryDetector with TDD (RED -> GREEN)** - `5e6a575c` (feat)

_Note: TDD task - tests existed in RED state, implementation completed GREEN phase_

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/replay/RepBoundaryDetector.kt` - Valley-based rep boundary detection with smoothing
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/replay/RepBoundaryDetectorTest.kt` - 13 comprehensive tests for boundary detection

## Decisions Made
- Reused Phase 11 SignatureExtractor's valley detection constants (10mm threshold, 5-sample window, 8-sample separation)
- Peak finding uses raw positions (not smoothed) for accuracy
- Concentric phase: startIndex until peakIndex; Eccentric phase: peakIndex until endIndex

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - TDD RED phase was pre-existing (partial previous execution), GREEN phase completed successfully.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- RepBoundaryDetector ready for Plan 02 (Rep metric extraction)
- detectBoundaries(FloatArray) -> List<RepBoundary> API stable
- All edge cases tested and verified

---
*Phase: 12-mobile-replay-cards*
*Completed: 2026-02-15*

## Self-Check: PASSED

- [x] FOUND: RepBoundaryDetector.kt
- [x] FOUND: RepBoundaryDetectorTest.kt
- [x] FOUND: commit 5e6a575c

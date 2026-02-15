---
phase: 12-mobile-replay-cards
plan: 03
subsystem: workout-engine
tags: [rep-metrics, phase-segmentation, force-data, boundary-detection, replay-cards]

# Dependency graph
requires:
  - phase: 12-01
    provides: "RepBoundaryDetector with detectBoundaries() for phase segmentation"
  - phase: 12-02
    provides: "RepReplayCard UI consuming RepMetricData force arrays"
provides:
  - "ActiveSessionEngine.scoreCurrentRep() populates force arrays with real MetricSample data"
  - "Phase-segmented rep data (concentric/eccentric) from boundary detection"
  - "Complete RepMetricData including peak/avg force, power, ROM, velocity"
affects: [workout-persistence, history-replay, force-sparkline]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Rep boundary timestamp filtering for accurate metric window"
    - "Fallback velocity-based split when boundary detection insufficient"

key-files:
  created: []
  modified:
    - "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt"

key-decisions:
  - "Use rep boundary timestamps (not takeLast(20)) for accurate per-rep metric window"
  - "Fall back to velocity-based phase split when detectBoundaries() returns empty"
  - "Extract max position of A/B cables for phase detection input"
  - "Calculate power using dual-cable formula (loadA + loadB) * velocity * 9.81"

patterns-established:
  - "Rep metric extraction pattern: filter by boundary timestamps -> extract positions -> detect phases -> segment metrics -> populate arrays"

# Metrics
duration: 4min
completed: 2026-02-15
---

# Phase 12 Plan 03: Force Array Population Summary

**RepBoundaryDetector wired into scoreCurrentRep() for real-time force array population from phase-segmented MetricSample data**

## Performance

- **Duration:** 4 min
- **Started:** 2026-02-15T17:38:25Z
- **Completed:** 2026-02-15T17:42:21Z
- **Tasks:** 1
- **Files modified:** 1

## Accomplishments

- Wired RepBoundaryDetector into ActiveSessionEngine for phase segmentation
- RepMetricData now populated with real force data (concentricLoadsA/B, eccentricLoadsA/B)
- Peak and average force, power, ROM, and velocity metrics calculated from actual samples
- Force sparklines in RepReplayCard will now render actual workout data

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire RepBoundaryDetector into scoreCurrentRep** - `a610eca9` (feat)

## Files Created/Modified

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt` - Added RepBoundaryDetector import/instance, replaced stub scoreCurrentRep() with real phase segmentation and force array population

## Decisions Made

- **Rep boundary timestamps for metric filtering:** Use coordinator.repBoundaryTimestamps to get accurate metric window per rep instead of arbitrary takeLast(20)
- **Velocity-based fallback:** When detectBoundaries() returns empty (insufficient data), split by velocity direction (positive = concentric, negative = eccentric)
- **Max position of A/B:** Use maxOf(positionA, positionB) as input to phase detector since single-cable exercises may have 0 on one cable
- **Power formula:** P = (loadA + loadB) * |velocity| / 1000 * 9.81 (kg to N conversion, mm/s to m/s)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Force array population complete - ForceSparkline will render real data on next workout
- Human verification required with BLE device to confirm logcat shows concentricSamples > 0
- Phase 12 ready for final verification/wrap-up plan (12-04 if exists)

---
*Phase: 12-mobile-replay-cards*
*Completed: 2026-02-15*

## Self-Check: PASSED

- FOUND: ActiveSessionEngine.kt
- FOUND: a610eca9

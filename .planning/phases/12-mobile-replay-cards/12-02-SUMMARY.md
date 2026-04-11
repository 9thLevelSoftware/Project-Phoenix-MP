---
phase: 12-mobile-replay-cards
plan: 02
subsystem: presentation
tags: [ui, compose, canvas, sparkline, replay-cards]

# Dependency graph
requires:
  - phase: 12-01
    provides: RepBoundaryDetector for rep segmentation
provides:
  - ForceSparkline Canvas component for mini force visualization
  - RepReplayCard composable with metrics and sparkline
  - RepDetailsSection in HistoryTab expanded view
affects: [12-03, 12-04, history-ui, rep-visualization]

# Tech tracking
tech-stack:
  added: []
  patterns: [Canvas force curve drawing, LaunchedEffect repository fetch, Material 3 Card composition]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ForceSparkline.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RepReplayCard.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt

key-decisions:
  - "ForceSparkline height 40dp with 4dp padding for compact card embedding"
  - "Peak marker at concentric/eccentric transition point (end of concentric)"
  - "Combined concentricLoadsA + eccentricLoadsA for continuous force curve"
  - "RepDetailsSection uses LaunchedEffect for async repository fetch"

patterns-established:
  - "Canvas sparkline: normalized Y-axis, proportional X-axis, optional marker"
  - "Rep card layout: title row, sparkline, metrics row"
  - "Repository injection via koinInject in expanded card sections"

# Metrics
duration: 5min
completed: 2026-02-15
---

# Phase 12 Plan 02: Rep Replay Card UI Summary

**Canvas-based force sparkline embedded in Material 3 cards with peak force, concentric and eccentric duration metrics**

## Performance

- **Duration:** 5 min
- **Started:** 2026-02-15T16:42:39Z
- **Completed:** 2026-02-15T16:47:XX
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- ForceSparkline renders 40dp tall force curve with optional peak marker
- RepReplayCard displays rep number, warmup badge, sparkline, and timing metrics
- RepDetailsSection fetches rep metrics and renders cards in HistoryTab
- Integration in both WorkoutHistoryCard and GroupedRoutineCard expanded views

## Task Commits

Each task was committed atomically:

1. **Task 1: ForceSparkline Canvas component** - `f1586be8` (feat)
2. **Task 2: RepReplayCard composable with metrics** - `8c8fef88` (feat)
3. **Task 3: Integrate rep cards into HistoryTab** - `3147f5e6` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ForceSparkline.kt` - Canvas sparkline component
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RepReplayCard.kt` - Rep card with metrics
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt` - RepDetailsSection integration

## Decisions Made
- ForceSparkline uses 1.5dp stroke width (matches ForceCurveMiniGraph pattern)
- Peak force calculated as max(peakForceA, peakForceB) for dual-cable exercises
- Duration formatted as "X.Xs" seconds with 1 decimal precision
- Warmup badge uses tertiaryContainer color for visual distinction

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - all components compiled and built successfully on first attempt.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- UI components ready for Plan 03 (rep metric capture during workout)
- ForceSparkline API accepts FloatArray + optional peakIndex
- RepReplayCard API accepts RepMetricData + formatting functions

---
*Phase: 12-mobile-replay-cards*
*Completed: 2026-02-15*

## Self-Check: PASSED

- [x] FOUND: ForceSparkline.kt
- [x] FOUND: RepReplayCard.kt
- [x] FOUND: RepMetricRepository import in HistoryTab.kt
- [x] FOUND: RepReplayCard import in HistoryTab.kt
- [x] FOUND: getRepMetrics call in HistoryTab.kt
- [x] FOUND: commit f1586be8
- [x] FOUND: commit 8c8fef88
- [x] FOUND: commit 3147f5e6

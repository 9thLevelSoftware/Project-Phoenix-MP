---
phase: 07-hud-integration
plan: 03
subsystem: ui
tags: [compose, biomechanics, force-curve, tier-gating, hud, canvas]

# Dependency graph
requires:
  - phase: 06-core-engine
    provides: ForceCurveResult with normalizedForceN, stickingPointPct, strengthProfile
  - phase: 07-hud-integration
    plan: 01
    provides: BiomechanicsRepResult pipeline threaded through ViewModel to WorkoutHud
  - phase: 07-hud-integration
    plan: 02
    provides: BalanceBar composable integrated into WorkoutHud overlay
provides:
  - ForceCurveMiniGraph composable rendering 101-point force curve with sticking point marker
  - ExpandedForceCurve dialog with full-size curve, axis labels, sticking point annotation, strength profile badge
  - Phoenix tier gating for ALL biomechanics HUD elements (single gate in ActiveWorkoutScreen)
affects: [08-polish]

# Tech tracking
tech-stack:
  added: []
  patterns: [Canvas-based force curve visualization, single upstream tier gate for premium features]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ForceCurveMiniGraph.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt

key-decisions:
  - "Single upstream gate (gatedBiomechanicsResult) in ActiveWorkoutScreen nulls all biomechanics for free tier -- cleaner than per-element gating"
  - "Force curve uses AlertDialog for expanded view (consistent with Compose Material3 patterns)"

patterns-established:
  - "Upstream tier gating: gate data at collection point, downstream UI checks null naturally"
  - "Canvas force curve: auto-scale Y axis to min/max, 101-point X spread across width"

# Metrics
duration: 6min
completed: 2026-02-15
---

# Phase 7 Plan 3: Force Curve HUD and Tier Gating Summary

**Force curve mini-graph on StatsPage with tap-to-expand overlay, and Phoenix tier gating for all biomechanics HUD elements via single upstream gate**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-15T02:05:36Z
- **Completed:** 2026-02-15T02:11:30Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- ForceCurveMiniGraph composable draws 101-point normalized force curve with Canvas, sticking point red dot marker, and tap-to-expand
- ExpandedForceCurve AlertDialog shows full-size curve with ROM % axis ticks, Force (N) label, grid lines, sticking point annotation, strength profile badge, and force range stats
- Single upstream tier gate in ActiveWorkoutScreen: `gatedBiomechanicsResult = if (hasProAccess) latestBiomechanicsResult else null` hides velocity card, balance bar, and force curve for free tier

## Task Commits

Each task was committed atomically:

1. **Task 1: Create ForceCurveMiniGraph component with expanded view** - `e38dba91` (feat)
2. **Task 2: Add force curve to StatsPage and apply Phoenix tier gating** - `8d42d474` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ForceCurveMiniGraph.kt` - New composable: compact mini-graph (80dp) and expanded AlertDialog with full curve analysis
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt` - Added ForceCurveMiniGraph and ExpandedForceCurve to StatsPage, imported new components
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt` - Added gatedBiomechanicsResult with hasProAccess gate, updated WorkoutUiState construction

## Decisions Made
- Single upstream gate in ActiveWorkoutScreen is cleaner than wrapping each UI element in a gate -- all downstream UI already checks `!= null`
- Used AlertDialog for expanded force curve (consistent Material3 pattern, handles dismiss/scrim automatically)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 7 (HUD Integration) is now complete -- all 3 plans executed
- Biomechanics pipeline fully wired: engine -> ViewModel -> HUD with tier gating
- Ready for Phase 8 (polish/testing)
- No blockers or concerns

## Self-Check: PASSED

All 3 files verified present. Both task commits (e38dba91, 8d42d474) verified in git log.

---
*Phase: 07-hud-integration*
*Completed: 2026-02-15*

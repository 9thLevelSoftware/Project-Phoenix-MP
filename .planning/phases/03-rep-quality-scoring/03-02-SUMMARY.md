---
phase: 03-rep-quality-scoring
plan: 02
subsystem: presentation
tags: [kotlin, compose, stateflow, workout-hud, animation, feature-gating]

# Dependency graph
requires:
  - phase: 03-rep-quality-scoring
    plan: 01
    provides: RepQualityScorer engine, RepQualityScore domain model
  - phase: 01-data-foundation
    provides: RepMetricData model, SubscriptionTier, FeatureGate
provides:
  - RepQualityScorer wired into workout flow (WorkoutCoordinator + ActiveSessionEngine)
  - Per-rep scoring on each rep notification with approximate metric data
  - latestRepQuality StateFlow exposed through coordinator to UI
  - RepQualityIndicator composable with color gradient and pulse animation
  - Tier-gated display (Phoenix+ only) via SubscriptionManager.hasProAccess
affects: [03-03 (set summary quality display)]

# Tech tracking
tech-stack:
  added: []
  patterns: [stateflow-gated-ui-feature, approximate-metric-scoring, overlay-composable-on-hud]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RepQualityIndicator.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt

key-decisions:
  - "Score gated by SubscriptionManager.hasProAccess (not FeatureGate.isEnabled) for simpler UI integration"
  - "Approximate metric data used for HUD scoring (ROM from position range, velocity from recent metrics window)"
  - "RepQualityIndicator positioned as overlay on WorkoutHud with TopCenter alignment + 80dp top padding"
  - "Auto-dismiss 800ms with pulse animation for 95+ scores"

patterns-established:
  - "Overlay composable pattern: Box wrapping WorkoutHud + indicator in WorkoutTab Active state"
  - "Tier gating at ActiveWorkoutScreen level: collect StateFlow, check access, pass null if gated"

# Metrics
duration: 11min
completed: 2026-02-14
---

# Phase 3 Plan 02: Rep Quality HUD Integration Summary

**Per-rep quality score wired from ActiveSessionEngine through WorkoutCoordinator to HUD overlay with color-coded flash animation and Phoenix+ tier gating**

## Performance

- **Duration:** 11 min
- **Started:** 2026-02-14T20:17:18Z
- **Completed:** 2026-02-14T20:28:21Z
- **Tasks:** 2
- **Files created:** 1
- **Files modified:** 5

## Accomplishments
- RepQualityScorer instance in WorkoutCoordinator with latestRepQuality StateFlow
- Scoring invoked on each rep increment in handleRepNotification using approximate collected metrics
- Scorer reset on workout start and set completion for clean per-set scoring
- RepQualityIndicator composable with 5-tier color gradient, fade animation, and pulse for excellent reps
- Tier-gated display: Free tier sees no quality indicator (null passed through WorkoutUiState)

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire RepQualityScorer into workout flow** - `bb19d192` (feat)
2. **Task 2: Add RepQualityIndicator to workout HUD** - `2897a7bc` (feat)

## Files Created/Modified
- `shared/.../presentation/components/RepQualityIndicator.kt` - New composable: score overlay with color gradient, fade animation, pulse for 95+
- `shared/.../presentation/manager/WorkoutCoordinator.kt` - Added repQualityScorer instance, latestRepQuality StateFlow
- `shared/.../presentation/manager/ActiveSessionEngine.kt` - Scoring in handleRepNotification, reset in startWorkout/handleSetCompletion (prior commit 0f280e31)
- `shared/.../presentation/screen/WorkoutUiState.kt` - Added latestRepQualityScore field
- `shared/.../presentation/screen/ActiveWorkoutScreen.kt` - Collect latestRepQuality, gate by hasProAccess, pass to WorkoutUiState
- `shared/.../presentation/viewmodel/MainViewModel.kt` - Expose latestRepQuality from coordinator
- `shared/.../presentation/screen/WorkoutTab.kt` - Add latestRepQualityScore parameter, overlay RepQualityIndicator on WorkoutHud

## Decisions Made
- Used SubscriptionManager.hasProAccess for tier gating rather than FeatureGate.isEnabled(REP_QUALITY_SCORE, tier) because the UI layer already uses hasProAccess pattern and tier info is not directly accessible at composable level
- Approximate metric data for HUD scoring: ROM computed from position range of all collected metrics, velocity from recent 20-sample window. Accurate per-rep metric data comes from persisted RepMetricData (Plan 01 models)
- RepQualityIndicator positioned at TopCenter with 80dp top padding to avoid overlapping the main HUD metrics area
- ActiveSessionEngine changes were partially present from a prior plan 03-03 commit (0f280e31) which pre-wired scoring and set summary capture

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ActiveSessionEngine changes already present from prior commit**
- **Found during:** Task 1
- **Issue:** Commit 0f280e31 (from a prior plan 03-03 execution) already added scoreCurrentRep, handleRepNotification scoring, and handleSetCompletion quality capture to ActiveSessionEngine
- **Fix:** Verified existing code matches plan requirements; edits were idempotent with existing content
- **Files affected:** ActiveSessionEngine.kt
- **Impact:** None - the code was already correct

---

**Total deviations:** 1 (pre-existing code from prior partial execution)
**Impact on plan:** No scope change. All requirements met.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Rep quality scores flow end-to-end from engine to HUD display
- SetQualitySummary capture in handleSetCompletion ready for Plan 03 (set summary UI)
- All public APIs documented, builds and tests pass

## Self-Check: PASSED

- [x] RepQualityIndicator.kt exists
- [x] Commit bb19d192 (Task 1: wire scorer) exists
- [x] Commit 2897a7bc (Task 2: HUD indicator) exists
- [x] Android build succeeds
- [x] All tests pass

---
*Phase: 03-rep-quality-scoring*
*Completed: 2026-02-14*

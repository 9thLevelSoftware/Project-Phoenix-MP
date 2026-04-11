---
phase: 22-ghost-racing
plan: 03
subsystem: presentation
tags: [ghost-racing, vbt, velocity, compose-overlay, wcag, accessibility]

# Dependency graph
requires:
  - phase: 22-ghost-racing
    provides: "GhostRacingEngine computation, GhostModels domain types, WorkoutCoordinator ghost state fields, ActiveSessionEngine ghost lifecycle"
  - phase: 17-accessibility
    provides: "AccessibilityTheme.colors for WCAG-safe color usage"
provides:
  - "GhostRacingOverlay composable with dual vertical progress bars and verdict badge"
  - "WorkoutHud ghost overlay integration gated behind Phoenix+ tier"
  - "SetSummaryCard ghost velocity delta section with verdict and rep breakdown"
  - "Full ghost state threading from WorkoutCoordinator through MainViewModel to UI"
affects: [22-ghost-racing]

# Tech tracking
tech-stack:
  added: []
  patterns: [ghost-overlay-conditional-rendering, state-threading-chain-pattern]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/GhostRacingOverlay.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt

key-decisions:
  - "Ghost state threaded through full parameter chain (MainViewModel -> ActiveWorkoutScreen -> WorkoutUiState -> WorkoutTab -> WorkoutHud) matching existing stateless composable pattern"
  - "Ghost overlay gated in ActiveWorkoutScreen via hasProAccess (same gating as biomechanics and form check) rather than FeatureGate.isEnabled in WorkoutHud"
  - "KMP-safe number formatting via roundToInt division instead of String.format for cross-platform compatibility"
  - "Ghost verdict badge uses semitransparent background chip with bold colored text for dual visual signal (WCAG)"

patterns-established:
  - "Ghost state threading follows exact same chain as form check state (Phase 19 pattern reuse)"
  - "Conditional overlay in WorkoutHud Box zone positioned inset from cable bars (48dp start, 32dp top)"

requirements-completed: [GHOST-02, GHOST-03, GHOST-04]

# Metrics
duration: 8min
completed: 2026-02-28
---

# Phase 22 Plan 03: Ghost Racing UI Overlay Summary

**GhostRacingOverlay composable with dual YOU/BEST progress bars and verdict badge, WorkoutHud conditional overlay, and SetSummaryCard ghost velocity delta section with WCAG-safe AccessibilityTheme colors**

## Performance

- **Duration:** 8 min
- **Started:** 2026-02-28T20:11:37Z
- **Completed:** 2026-02-28T20:19:45Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- GhostRacingOverlay composable with dual vertical progress bars (YOU/BEST labels), bottom-up fill, and verdict badge (AHEAD/BEHIND/TIED/NEW BEST)
- Full ghost state threading chain from WorkoutCoordinator through MainViewModel, ActiveWorkoutScreen, WorkoutUiState, WorkoutTab, to WorkoutHud
- SetSummaryCard ghost delta section with overall verdict, average velocity delta in mm/s, and per-rep breakdown (ahead/behind/beyond counts)
- All semantic colors from AccessibilityTheme.colors with WCAG-compliant dual visual signals (color + text label)

## Task Commits

Each task was committed atomically:

1. **Task 1: GhostRacingOverlay composable + WorkoutHud integration** - `2e659dae` (feat)
2. **Task 2: SetSummaryCard ghost velocity delta display** - `eb00da2e` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/GhostRacingOverlay.kt` - Dual vertical progress bars with verdict badge composable
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt` - Ghost overlay rendering in Box overlay zone, gated by non-null ghostSession
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt` - Ghost velocity delta card with verdict, delta, and rep breakdown
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt` - Added ghostSession and latestGhostVerdict fields
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt` - Ghost state parameter threading to WorkoutHud
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt` - Ghost state collection from ViewModel with Phoenix+ tier gating
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` - Ghost state delegation from WorkoutCoordinator

## Decisions Made
- Ghost state threaded through full parameter chain matching existing stateless composable architecture (not direct coordinator collection as plan assumed)
- Ghost overlay gated in ActiveWorkoutScreen via hasProAccess (consistent with biomechanics and form check gating pattern)
- KMP-safe number formatting using roundToInt division instead of String.format for cross-platform compatibility
- Ghost verdict badge uses semitransparent background chip (color.copy(alpha=0.2f)) with bold colored text for WCAG dual visual signal

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Threaded ghost state through full parameter chain instead of direct coordinator collection**
- **Found during:** Task 1 (WorkoutHud integration)
- **Issue:** Plan assumed WorkoutHud directly collects from coordinator via `coordinator.ghostSession.collectAsState()`, but WorkoutHud is a stateless composable that receives all state as parameters through the chain: MainViewModel -> ActiveWorkoutScreen -> WorkoutUiState -> WorkoutTab -> WorkoutHud
- **Fix:** Added ghost state exposure to MainViewModel, ghost fields to WorkoutUiState, parameter threading through WorkoutTab (both overloads), and ghost parameters to WorkoutHud. Followed exact same pattern as Phase 19 form check state threading.
- **Files modified:** MainViewModel.kt, ActiveWorkoutScreen.kt, WorkoutUiState.kt, WorkoutTab.kt, WorkoutHud.kt
- **Verification:** Build succeeds, ghost state reaches WorkoutHud via parameter chain
- **Committed in:** 2e659dae (Task 1 commit)

**2. [Rule 1 - Bug] Used MaterialTheme.colorScheme instead of non-existent AccessibilityTheme fields**
- **Found during:** Task 1 and Task 2
- **Issue:** Plan referenced `AccessibilityTheme.colors.primary`, `AccessibilityTheme.colors.onSurface`, and `AccessibilityTheme.colors.onSurfaceVariant` which do not exist on AccessibilityColors. AccessibilityColors only has semantic status colors (success/error/warning), velocity zones, asymmetry, and quality colors.
- **Fix:** Used `MaterialTheme.colorScheme.primary`, `MaterialTheme.colorScheme.onSurface`, `MaterialTheme.colorScheme.onSurfaceVariant`, and `MaterialTheme.colorScheme.surfaceVariant` for non-semantic colors. AccessibilityTheme.colors used correctly for success/error/warning.
- **Files modified:** GhostRacingOverlay.kt, SetSummaryCard.kt
- **Verification:** Build succeeds, colors render correctly
- **Committed in:** 2e659dae, eb00da2e

**3. [Rule 1 - Bug] Used KMP-safe number formatting instead of String.format**
- **Found during:** Task 2 (SetSummaryCard delta display)
- **Issue:** `String.format("%.1f", value)` is not available in KMP commonMain (JVM-only API)
- **Fix:** Used `((value * 10).roundToInt() / 10f).toString()` for one-decimal formatting as plan's fallback suggested
- **Files modified:** SetSummaryCard.kt
- **Verification:** Build succeeds on KMP commonMain
- **Committed in:** eb00da2e (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking)
**Impact on plan:** All auto-fixes necessary for correct compilation and architectural consistency. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 22 Ghost Racing is now complete (all 3 plans executed)
- Ghost racing is fully functional: domain engine (Plan 01) -> workout lifecycle wiring (Plan 02) -> UI overlay (Plan 03)
- End-to-end flow: ghost session pre-loads at workout start, real-time velocity comparison during sets, verdict display per rep, set summary with delta analysis

## Self-Check: PASSED

All 7 files verified present. Both task commits (2e659dae, eb00da2e) verified in git log.

---
*Phase: 22-ghost-racing*
*Completed: 2026-02-28*

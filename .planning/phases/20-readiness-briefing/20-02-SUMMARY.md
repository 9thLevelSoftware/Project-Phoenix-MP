---
phase: 20-readiness-briefing
plan: 02
subsystem: ui
tags: [readiness-card, compose, elite-tier, wcag, accessibility, dismissible]

# Dependency graph
requires:
  - phase: 20-readiness-briefing
    provides: "ReadinessEngine.computeReadiness(), ReadinessResult sealed class, ReadinessStatus enum"
provides:
  - "ReadinessBriefingCard composable with Ready and InsufficientData rendering paths"
  - "ActiveWorkoutScreen Elite-gated readiness computation and card display in Idle state"
  - "Dismissible advisory card with Portal upsell link (BRIEF-03, BRIEF-04)"
affects: [readiness-portal-integration, elite-tier-features]

# Tech tracking
tech-stack:
  added: []
  patterns: [elite-tier-gating-composable, dismissible-advisory-card, column-overlay-pattern]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ReadinessBriefingCard.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt

key-decisions:
  - "ReadinessBriefingCard placed above WorkoutTab in Column layout (not overlay/dialog) -- non-blocking, scrolls with content"
  - "koinInject SmartSuggestionsRepository inside Elite guard to avoid instantiation for non-Elite users"
  - "Pre-compute AccessibilityTheme.colors at composable top level (v0.5.1 canvas compatibility decision)"

patterns-established:
  - "Column-based card insertion pattern: wrap Scaffold content in Column, add conditional card above main content"
  - "Elite tier gate pattern: hasEliteAccess + conditional koinInject + LaunchedEffect for data fetch"

requirements-completed: [BRIEF-02, BRIEF-03, BRIEF-04]

# Metrics
duration: 6min
completed: 2026-02-28
---

# Phase 20 Plan 02: Readiness Briefing Card Summary

**ReadinessBriefingCard composable with traffic-light status, score badge, WCAG-compliant colors, dismissible state, and Portal upsell -- wired into ActiveWorkoutScreen with Elite tier gating**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-28T16:32:06Z
- **Completed:** 2026-02-28T16:37:46Z
- **Tasks:** 2
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- ReadinessBriefingCard composable renders both Ready (traffic-light with score/ACWR) and InsufficientData states
- ActiveWorkoutScreen computes readiness for Elite users via LaunchedEffect and shows card in Idle state only
- Card uses AccessibilityTheme.colors.statusGreen/Yellow/Red with icon+text secondary signals (WCAG 1.4.1 compliance)
- Card is always dismissible (BRIEF-03) and includes Portal upsell link (BRIEF-04)
- FREE and PHOENIX tier users never see the card (Elite-only gate)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create ReadinessBriefingCard composable** - `dba090d8` (feat)
2. **Task 2: Wire readiness computation and card into ActiveWorkoutScreen** - `7a0a1d34` (feat)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ReadinessBriefingCard.kt` - ReadinessBriefingCard composable with Ready (icon+label+score badge+advisory+ACWR detail) and InsufficientData rendering paths, AccessibilityColors status, dismiss and Portal upsell callbacks
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt` - Added Elite tier access check, readiness state variables, LaunchedEffect computation, Column wrapper with conditional card rendering in Idle state

## Decisions Made
- Placed card above WorkoutTab in a Column layout (not an overlay or dialog) to ensure the card never blocks the Start Workout button -- purely advisory, scrolls with content
- koinInject for SmartSuggestionsRepository is inside the `if (hasEliteAccess)` guard to avoid unnecessary DI resolution for non-Elite users
- Pre-compute AccessibilityTheme.colors at composable top level per v0.5.1 decision about canvas/draw compatibility

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None -- both tasks compiled and all tests passed on first attempt.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 20 (Readiness Briefing) is fully complete with both ReadinessEngine (Plan 01) and UI card (Plan 02)
- Portal deep link callback is a placeholder -- will be wired when Portal integration is implemented
- Ready for Phase 21+ progression

## Self-Check: PASSED

- ReadinessBriefingCard.kt: FOUND
- Commit dba090d8: FOUND
- Commit 7a0a1d34: FOUND

---
*Phase: 20-readiness-briefing*
*Completed: 2026-02-28*

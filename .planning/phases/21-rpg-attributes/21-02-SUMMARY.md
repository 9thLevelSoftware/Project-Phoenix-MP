---
phase: 21-rpg-attributes
plan: 02
subsystem: ui
tags: [compose, rpg, gamification, tier-gating, stateflow]

requires:
  - phase: 21-rpg-attributes (Plan 01)
    provides: RpgModels.kt, RpgAttributeEngine, RpgAttributes table, SQL queries
provides:
  - RpgAttributeCard composable with character class header, five attribute bars, portal link
  - GamificationRepository.getRpgInput() aggregate data method
  - GamificationRepository.saveRpgProfile() persistence method
  - GamificationViewModel.rpgProfile StateFlow with on-demand computation
  - BadgesScreen Phoenix+ tier-gated RPG card above StreakWidget
affects: [ghost-racing, portal-integration]

tech-stack:
  added: []
  patterns: [on-demand-computation-via-LaunchedEffect, tier-gated-card-composable]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RpgAttributeCard.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/GamificationRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightGamificationRepository.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/GamificationViewModel.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeGamificationRepository.kt
    - androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakeGamificationRepository.kt

key-decisions:
  - "On-demand RPG computation via LaunchedEffect (not init) avoids unnecessary work for users who never visit BadgesScreen"

patterns-established:
  - "On-demand feature computation: LaunchedEffect in screen triggers ViewModel method, not computed eagerly in init"

requirements-completed: [RPG-01, RPG-03, RPG-04]

duration: 7min
completed: 2026-02-28
---

# Phase 21 Plan 02: RPG Attribute Card & BadgesScreen Integration Summary

**RpgAttributeCard composable with five attribute bars, character class header, and Phoenix+ tier gate on BadgesScreen**

## Performance

- **Duration:** 7 min
- **Started:** 2026-02-28T17:29:04Z
- **Completed:** 2026-02-28T17:36:04Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- RpgAttributeCard composable displays character class icon/name/description, five progress bars (0-100), and "View full skill tree on Phoenix Portal" deep link
- GamificationRepository.getRpgInput() aggregates 12 metrics across WorkoutSession, RepMetric, GamificationStats, PersonalRecord, EarnedBadge tables with proper null-handling and fallbacks
- BadgesScreen conditionally renders RPG card above StreakWidget for Phoenix+ tier users only (FREE users never see it)
- Attributes recompute fresh on each screen visit via LaunchedEffect(Unit) -- no stale cached values

## Task Commits

Each task was committed atomically:

1. **Task 1: Repository methods, ViewModel state, and RpgAttributeCard composable** - `f0edc6f8` (feat)
2. **Task 2: Wire RpgAttributeCard into BadgesScreen with Phoenix+ tier gate** - `0964845f` (feat)

## Files Created/Modified
- `shared/.../presentation/components/RpgAttributeCard.kt` - New composable: Card with character class header, five AttributeBar rows, portal TextButton
- `shared/.../data/repository/GamificationRepository.kt` - Added getRpgInput() and saveRpgProfile() interface methods
- `shared/.../data/repository/SqlDelightGamificationRepository.kt` - Implemented aggregate queries for all 12 RpgInput fields with withContext(Dispatchers.IO)
- `shared/.../presentation/viewmodel/GamificationViewModel.kt` - Added rpgProfile StateFlow and loadRpgProfile() method
- `shared/.../presentation/screen/BadgesScreen.kt` - Added SubscriptionManager tier gate, LaunchedEffect, conditional RpgAttributeCard
- `shared/src/commonTest/.../FakeGamificationRepository.kt` - Added stub getRpgInput/saveRpgProfile implementations
- `androidApp/src/androidTest/.../FakeGamificationRepository.kt` - Added stub getRpgInput/saveRpgProfile implementations

## Decisions Made
- On-demand RPG computation via LaunchedEffect in BadgesScreen (not ViewModel init) to avoid unnecessary DB queries for users who never visit the gamification screen

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- RPG attribute system fully wired: engine (Plan 01) + UI + repository + tier gate (Plan 02)
- Phase 21 complete -- ready for Phase 22 (Ghost Racing)
- Portal deep link callback is a placeholder (deferred to v0.6.0+)

---
*Phase: 21-rpg-attributes*
*Completed: 2026-02-28*

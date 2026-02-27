---
phase: 16-foundation-board-conditions
plan: 01
subsystem: domain
tags: [feature-gate, subscription-tier, version-bump, timezone, kotlinx-datetime]

# Dependency graph
requires:
  - phase: 15-cv-android-integration
    provides: "Camera pipeline and MediaPipe integration from v0.5.0"
provides:
  - "Four new FeatureGate entries: CV_FORM_CHECK, RPG_ATTRIBUTES, GHOST_RACING, READINESS_BRIEFING"
  - "Version 0.5.1 across build config, Constants, and Settings UI"
  - "Local timezone conversion in classifyTimeWindow with injectable TimeZone parameter"
affects: [phase-17-wcag, phase-19-cv-form-check-ux, phase-20-readiness, phase-21-rpg, phase-22-ghost-racing]

# Tech tracking
tech-stack:
  added: []
  patterns: ["Injectable TimeZone parameter for timezone-dependent functions"]

key-files:
  created: []
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/FeatureGate.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/FeatureGateTest.kt
    - androidApp/build.gradle.kts
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngineTest.kt

key-decisions:
  - "Added TimeZone parameter to analyzeTimeOfDay (not just classifyTimeWindow) for test determinism"
  - "Removed unused ONE_HOUR_MS constant after timezone refactor"
  - "Corrected Melbourne timezone comment from UTC+10 to UTC+11 AEDT for November dates"

patterns-established:
  - "Injectable TimeZone parameter: timezone-dependent functions accept TimeZone with default of currentSystemDefault() for production, explicit zones for testing"

requirements-completed: [BOARD-09, BOARD-07, BOARD-01]

# Metrics
duration: 14min
completed: 2026-02-27
---

# Phase 16 Plan 01: FeatureGate v0.5.1 Entries, Version Bump, and UTC Timezone Fix Summary

**Four new FeatureGate entries (3 Phoenix, 1 Elite) with tier-gated tests, version bumped to 0.5.1 across 3 locations, and classifyTimeWindow fixed to use local time via kotlinx-datetime**

## Performance

- **Duration:** 14 min
- **Started:** 2026-02-27T21:42:40Z
- **Completed:** 2026-02-27T21:57:00Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments
- FeatureGate.Feature enum now has 16 entries (12 existing + 4 new), with CV_FORM_CHECK/RPG_ATTRIBUTES/GHOST_RACING at Phoenix tier and READINESS_BRIEFING at Elite tier
- Version bumped to 0.5.1 in build.gradle.kts (versionName + versionCode), Constants.kt, and SettingsTab now reads from DeviceInfo.appVersionName dynamically
- classifyTimeWindow refactored from UTC modular arithmetic to kotlin.time.Instant + kotlinx.datetime.toLocalDateTime with injectable TimeZone, fixing misclassification for users outside UTC

## Task Commits

Each task was committed atomically:

1. **Task 1: Extend FeatureGate with v0.5.1 premium features and update tests** - `702d8bee` (feat)
2. **Task 2: Bump version to 0.5.1 across all locations** - `b2351b05` (chore)
3. **Task 3: Fix UTC timezone bug in SmartSuggestions classifyTimeWindow** - `52adcd10` (fix)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/FeatureGate.kt` - Added 4 new Feature enum entries and updated tier sets
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/FeatureGateTest.kt` - Updated existing tier lists and added 2 new explicit tests
- `androidApp/build.gradle.kts` - versionName=0.5.1, default versionCode=4
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt` - APP_VERSION=0.5.1
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt` - Dynamic version from DeviceInfo.appVersionName
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt` - classifyTimeWindow with local timezone, analyzeTimeOfDay with injectable TimeZone
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngineTest.kt` - 3 new timezone tests, existing tests fixed with explicit UTC

## Decisions Made
- Added TimeZone parameter to `analyzeTimeOfDay` (public API) in addition to `classifyTimeWindow` (internal) to enable deterministic testing of the full pipeline. Default parameter preserves backward compatibility with existing production callers.
- Removed `ONE_HOUR_MS` constant since it was no longer used after the timezone refactor (only `ONE_DAY_MS` remains, used in `findNeglectedExercises`).
- Corrected plan's Melbourne timezone comment from "UTC+10" to "UTC+11 AEDT" since November 15 falls in Australian Daylight Saving Time.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated existing time-of-day tests to pass explicit UTC timezone**
- **Found during:** Task 3 (timezone fix)
- **Issue:** Three existing SUGG-05 tests (`timeOfDayAllMorning`, `timeOfDayMixedWithClearWinner`, `timeOfDayTooFewSessionsNotOptimal`) constructed timestamps using UTC arithmetic but `analyzeTimeOfDay` now classifies using local time, causing test failures on non-UTC systems
- **Fix:** Added `TimeZone` parameter to `analyzeTimeOfDay` with default `currentSystemDefault()`, and updated failing tests to pass `TimeZone.UTC` explicitly
- **Files modified:** SmartSuggestionsEngine.kt, SmartSuggestionsEngineTest.kt
- **Verification:** All 22 SmartSuggestionsEngineTest tests pass
- **Committed in:** 52adcd10 (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 bug fix)
**Impact on plan:** The plan anticipated this scenario and recommended updating tests. Adding the TimeZone parameter to analyzeTimeOfDay was the cleanest approach, maintaining backward compatibility via default parameter. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- FeatureGate entries exist for all v0.5.1 premium features, ready for Phases 19-22 to gate their UIs
- Version 0.5.1 is set, Board of Directors version condition (BOARD-07) satisfied
- UTC timezone bug fixed, Board condition (BOARD-01) satisfied
- Phase 16 Plan 02 (backup exclusion, camera rationale, PoseLandmarker error handling, iOS platform detection) can proceed immediately

## Self-Check: PASSED

All 7 modified files verified present. All 3 task commits (702d8bee, b2351b05, 52adcd10) verified in git log. SUMMARY.md exists at expected path.

---
*Phase: 16-foundation-board-conditions*
*Completed: 2026-02-27*

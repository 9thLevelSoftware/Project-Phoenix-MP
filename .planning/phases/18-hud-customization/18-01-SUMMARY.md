---
phase: 18-hud-customization
plan: 01
subsystem: ui
tags: [kotlin-multiplatform, preferences, state-flow, hud, domain-model]

# Dependency graph
requires:
  - phase: 17-wcag-accessibility
    provides: colorBlindModeEnabled preference pipeline pattern
provides:
  - HudPage enum with EXECUTION, INSTRUCTION, STATS entries
  - HudPreset enum with ESSENTIAL, BIOMECHANICS, FULL presets
  - hudPreset preference pipeline (UserPreferences -> PreferencesManager -> SettingsManager -> MainViewModel)
  - Fixed FakePreferencesManager test doubles for both commonTest and androidTest
affects: [18-02-PLAN (UI consumption of hudPreset in WorkoutHud pager and SettingsTab)]

# Tech tracking
tech-stack:
  added: []
  patterns: [string-keyed enum persistence via multiplatform-settings]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/HudModels.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt
    - androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt
    - shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt

key-decisions:
  - "HudPreset persisted as string key (not ordinal) for forward-compatible extensibility"
  - "FULL preset as default ensures existing users see no behavior change"
  - "EXECUTION page present in every preset (core workout page is always visible)"

patterns-established:
  - "String-keyed enum pattern: enum has key property, companion fromKey with fallback default"

requirements-completed: [BOARD-04]

# Metrics
duration: 5min
completed: 2026-02-28
---

# Phase 18 Plan 01: HUD Customization Data Layer Summary

**HudPage/HudPreset domain enums with string-keyed persistence through UserPreferences -> PreferencesManager -> SettingsManager -> MainViewModel pipeline**

## Performance

- **Duration:** 5 min
- **Started:** 2026-02-28T03:21:20Z
- **Completed:** 2026-02-28T03:26:50Z
- **Tasks:** 2
- **Files modified:** 8

## Accomplishments
- Created HudPage enum (EXECUTION, INSTRUCTION, STATS) and HudPreset enum (ESSENTIAL, BIOMECHANICS, FULL) with stable string keys and companion fromKey lookups
- Wired hudPreset preference through the full persistence pipeline: UserPreferences data class, PreferencesManager interface + SettingsPreferencesManager, SettingsManager StateFlow, MainViewModel delegation
- Fixed pre-existing gaps in both FakePreferencesManager test doubles (missing setColorBlindModeEnabled, setLedFeedbackEnabled overrides from Phase 17)
- Added SettingsManagerTest verifying hudPreset defaults to "full" and updates correctly through preset changes

## Task Commits

Each task was committed atomically:

1. **Task 1: Create HudPage and HudPreset enums and wire preference pipeline** - `d42e55b6` (feat)
2. **Task 2: Fix FakePreferencesManager and add SettingsManager hudPreset test** - `09fc969e` (test)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/HudModels.kt` - HudPage and HudPreset enum definitions with string keys and companion fromKey lookups
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt` - Added hudPreset field defaulting to HudPreset.FULL.key
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt` - Added setHudPreset to interface, KEY_HUD_PRESET constant, loadPreferences hudPreset line, implementation method
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManager.kt` - Added hudPreset StateFlow and setHudPreset setter
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` - Added hudPreset/setHudPreset delegation to SettingsManager
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt` - Added setColorBlindModeEnabled + setHudPreset overrides
- `androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt` - Added setLedFeedbackEnabled + setColorBlindModeEnabled + setHudPreset overrides
- `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/manager/SettingsManagerTest.kt` - Added hudPreset default-and-update test

## Decisions Made
- HudPreset stored as string key (not enum ordinal) for forward-compatible extensibility if new presets are added
- FULL preset is the default so existing users see all pages without any migration
- EXECUTION page is present in every preset definition (it is the core workout page)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Fixed pre-existing setLedFeedbackEnabled gap in androidTest FakePreferencesManager**
- **Found during:** Task 2 (Fix FakePreferencesManager)
- **Issue:** androidTest FakePreferencesManager was missing setLedFeedbackEnabled override (pre-existing from Phase 17 LED feedback feature)
- **Fix:** Added the missing override alongside setColorBlindModeEnabled and setHudPreset
- **Files modified:** androidApp/src/androidTest/kotlin/com/devil/phoenixproject/testutil/FakePreferencesManager.kt
- **Verification:** Compiles successfully, interface fully satisfied
- **Committed in:** 09fc969e (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical)
**Impact on plan:** Auto-fix necessary for interface compliance. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- HUD preset data layer is complete and ready for Plan 02 UI consumption
- Plan 02 can read hudPreset from MainViewModel to filter WorkoutHud pager pages and add SettingsTab preset selector
- Both test doubles are now up-to-date with the full PreferencesManager interface

## Self-Check: PASSED

All 8 created/modified files verified present on disk. Both commit hashes (d42e55b6, 09fc969e) verified in git log.

---
*Phase: 18-hud-customization*
*Completed: 2026-02-28*

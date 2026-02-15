---
phase: 06-core-engine
plan: 04
subsystem: biomechanics-engine
tags: [asymmetry, balance, loadA, loadB, bilateral, injury-prevention]

dependency-graph:
  requires:
    - phase: 06-01
      provides: [BiomechanicsEngine shell, AsymmetryResult model, computeAsymmetry stub]
  provides:
    - computeAsymmetry() implementation with per-rep asymmetry calculation
    - Dominant side detection (A, B, or BALANCED below 2%)
    - Cable load averaging and asymmetry percentage formula
  affects: [06-core-engine set summary, asymmetry UI display, injury prevention alerts]

tech-stack:
  added: []
  patterns: [TDD red-green-refactor, 2% BALANCED threshold, abs/max formula]

key-files:
  created:
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/AsymmetryEngineTest.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/BiomechanicsEngine.kt
    - shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/e2e/WorkoutFlowE2ETest.kt
    - shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt

key-decisions:
  - 2% threshold for BALANCED classification (measurement noise tolerance)
  - Formula: asymmetryPercent = abs(avgLoadA - avgLoadB) / max(avgLoadA, avgLoadB) * 100
  - Empty or zero-load inputs return 0% asymmetry with BALANCED side

patterns-established:
  - "Asymmetry 2% threshold: Below 2% is considered balanced (measurement noise)"
  - "Load averaging: Average loadA and loadB across all samples in rep"

metrics:
  duration: 32m
  completed: 2026-02-15
---

# Phase 6 Plan 04: Asymmetry Engine Summary

**Per-rep cable asymmetry calculation with 2% balanced threshold and dominant side detection (A/B/BALANCED)**

## Performance

- **Duration:** 32 min
- **Started:** 2026-02-15T00:35:03Z
- **Completed:** 2026-02-15T01:06:52Z
- **Tasks:** 1 TDD task (RED + GREEN phases)
- **Files modified:** 4

## Accomplishments

- Implemented `computeAsymmetry()` replacing stub with real calculation
- Created comprehensive test suite with 16 test cases
- Fixed pre-existing test compilation errors (Rule 3 auto-fix)

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Failing asymmetry tests** - `c6834177` (test)
2. **Task 1 GREEN: Asymmetry implementation** - `3db9c3b5` (feat)
3. **Deviation fix: Test ViewModels missing parameter** - `3bb74f09` (fix)

## Files Created/Modified

- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/AsymmetryEngineTest.kt` - 16 tests covering balanced, A-dominant, B-dominant, thresholds, edge cases
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/BiomechanicsEngine.kt` - Replaced stub with ASYM-01 and ASYM-02 implementation
- `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/e2e/WorkoutFlowE2ETest.kt` - Added missing repMetricRepository parameter
- `shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModelTest.kt` - Added missing repMetricRepository parameter

## Decisions Made

1. **2% threshold for BALANCED**: Below 2% asymmetry is classified as BALANCED since cable sensors have measurement noise within this range.

2. **Formula choice**: Used `abs(avgLoadA - avgLoadB) / max(avgLoadA, avgLoadB) * 100` which gives the percentage imbalance relative to the stronger side.

3. **Edge case handling**: Empty metrics list and zero load on both cables return 0% asymmetry with BALANCED side to prevent division by zero.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Test files missing repMetricRepository parameter**
- **Found during:** Test compilation (pre-execution)
- **Issue:** MainViewModelTest.kt and WorkoutFlowE2ETest.kt failed to compile due to missing `repMetricRepository` parameter in MainViewModel constructor
- **Fix:** Added FakeRepMetricRepository import and passed it to ViewModel constructor in both test files
- **Files modified:** WorkoutFlowE2ETest.kt, MainViewModelTest.kt
- **Verification:** Tests compile and pass
- **Committed in:** 3bb74f09

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Auto-fix was essential for test compilation. No scope creep.

## Verification Results

- `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.domain.premium.AsymmetryEngineTest"` - 16/16 PASSED
- `./gradlew :androidApp:assembleDebug` - BUILD SUCCESSFUL
- AsymmetryEngineTest.kt contains tests for balanced, A-dominant, B-dominant, threshold boundary, zero load, empty input - VERIFIED

## Issues Encountered

1. **Gradle daemon instability**: Multiple daemon sessions caused build failures. Resolved by using `-Dorg.gradle.daemon=false`.

2. **ColorSchemeTest.kt compilation errors**: Pre-existing issue with test file unable to resolve RGBColor/ColorScheme types. Not related to this plan; excluded from test run.

3. **DataBackupManager.kt compilation errors**: Intermittent SQLDelight-related compilation issues after cache invalidation. Resolved after clean build.

## Next Phase Readiness

- All three BiomechanicsEngine compute methods are now implemented (VBT, Force Curve, Asymmetry)
- Phase 06-core-engine is COMPLETE
- Ready for Phase 07 (UI Integration) to display biomechanics results

---

## Self-Check: PASSED

- [x] AsymmetryEngineTest.kt exists with 16 tests
- [x] computeAsymmetry() implemented in BiomechanicsEngine.kt
- [x] All tests pass (16/16)
- [x] Build succeeds
- [x] Commits c6834177, 3db9c3b5, 3bb74f09 exist

---
phase: 09-handle-state-detector
plan: 01
subsystem: ble
tags: [kotlin, state-machine, tdd, handle-detection, hysteresis, ble]

# Dependency graph
requires:
  - phase: 05-ble-protocol-constants
    provides: BleConstants.Thresholds and BleConstants.Timing constants
provides:
  - HandleStateDetector class with 4-state machine and injectable timeProvider
  - HandleStateDetectorTest with 37 comprehensive unit tests
affects: [09-handle-state-detector (plan 02 - KableBleRepository delegation)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Injectable timeProvider: () -> Long for deterministic time testing"
    - "Baseline-relative threshold detection with isAboveThreshold/isBelowThreshold helpers"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetector.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetectorTest.kt
  modified: []

key-decisions:
  - "Used currentTimeMillis() expect/actual for default timeProvider (not kotlinx-datetime Clock.System)"
  - "Removed legacy forceAboveGrabThresholdStart/forceBelowReleaseThresholdStart fields (dead code from parent repo)"
  - "Extracted isAboveThreshold/isBelowThreshold helpers to reduce baseline-relative detection duplication"
  - "Named constant SIMPLE_DETECTION_THRESHOLD (50mm) for left/right boolean detection"

patterns-established:
  - "State machine extraction: injectable time, companion constants, private helper methods"
  - "TDD for pure state machines: fake time variable, metric() helper, deterministic transitions"

# Metrics
duration: 9min
completed: 2026-02-15
---

# Phase 9 Plan 01: HandleStateDetector Summary

**4-state handle detection state machine (WaitingForRest/Released/Grabbed/Moving) with injectable timeProvider, hysteresis dwell, baseline-relative detection, and 37 TDD tests**

## Performance

- **Duration:** 9 min
- **Started:** 2026-02-15T22:07:00Z
- **Completed:** 2026-02-15T22:16:12Z
- **Tasks:** 3 (RED, GREEN, REFACTOR)
- **Files modified:** 2

## Accomplishments
- HandleStateDetector extracts the complete 4-state handle detection logic from KableBleRepository into a standalone, testable module
- 37 unit tests cover all state transitions, hysteresis timing, WaitingForRest timeout, baseline tracking, auto-start velocity thresholds, single-handle detection, and edge cases
- All 15 state variables from the monolith inventory are accounted for (13 active, 2 legacy removed during refactor)
- Injectable timeProvider enables deterministic testing without real delays

## Task Commits

Each task was committed atomically:

1. **Task 1 - RED: Write failing tests** - `5079b41b` (test)
2. **Task 2 - GREEN: Implement HandleStateDetector** - `07bb35b0` (feat)
3. **Task 3 - REFACTOR: Clean up HandleStateDetector** - `f70d8248` (refactor)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetector.kt` - 4-state handle detection machine with injectable time, processMetric/enable/disable/reset/enableJustLiftWaiting methods
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetectorTest.kt` - 37 tests covering all states, hysteresis, baseline tracking, auto-start mode, single-handle, control methods

## Decisions Made
- **currentTimeMillis() over Clock.System**: The project's existing `expect fun currentTimeMillis()` is used for the default timeProvider rather than kotlinx-datetime, since it's the pattern used throughout the codebase and avoids an unnecessary direct dependency
- **Legacy fields removed**: `forceAboveGrabThresholdStart` and `forceBelowReleaseThresholdStart` were dead code -- initialized/reset but never read in `analyzeHandleState()`. Removed during refactor to avoid carrying vestigial state
- **Helper extraction**: `isAboveThreshold()` and `isBelowThreshold()` reduce the 4-instance baseline-relative check pattern from 8 lines of branching to 2 lines each

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed kotlinx.datetime.Clock.System unresolved reference**
- **Found during:** Task 2 (GREEN implementation)
- **Issue:** `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()` in the default timeProvider lambda did not compile -- the inline lambda default parameter couldn't resolve the Clock.System reference
- **Fix:** Used the project's existing `currentTimeMillis()` expect/actual function instead, which is the established pattern throughout the codebase
- **Files modified:** HandleStateDetector.kt (import + default parameter)
- **Verification:** `./gradlew :shared:compileDebugKotlinAndroid` succeeds
- **Committed in:** 07bb35b0 (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Trivial fix using existing project pattern. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- HandleStateDetector is ready for delegation from KableBleRepository (Plan 02)
- KableBleRepository can instantiate HandleStateDetector and delegate handleState/handleDetection flows
- All control methods (enable/disable/reset/enableJustLiftWaiting) are API-compatible with current callers

## Self-Check: PASSED

- All 3 created files exist on disk
- All 3 task commits verified in git history (5079b41b, 07bb35b0, f70d8248)
- `./gradlew :shared:compileDebugKotlinAndroid` succeeds
- `./gradlew :shared:testDebugUnitTest --tests "*.HandleStateDetectorTest"` passes (37 tests)
- Exactly 1 `class HandleStateDetector` in shared/src/commonMain/

---
*Phase: 09-handle-state-detector*
*Completed: 2026-02-15*

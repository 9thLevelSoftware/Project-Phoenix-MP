---
phase: 10-monitor-data-processor
plan: 01
subsystem: ble
tags: [tdd, velocity-ema, position-validation, jump-filter, status-flags, signal-processing]

# Dependency graph
requires:
  - phase: 05-ble-protocol-constants
    provides: "BleConstants.Thresholds for position range, jump threshold, EMA alpha, deload debounce"
  - phase: 06-protocol-parser
    provides: "MonitorPacket data class (parseMonitorPacket output)"
provides:
  - "MonitorDataProcessor class with process(), resetForNewSession(), getPollRateStats()"
  - "RomViolationType enum for ROM violation callbacks"
  - "30 unit tests covering all pipeline stages"
affects: [10-02-delegation, KableBleRepository]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Synchronous processing pipeline with callback-based event emission"
    - "Injectable timeProvider for deterministic testing (matches HandleStateDetector)"
    - "EMA cold start: seed with first real velocity, not first-ever zero"
    - "Issue #210 cascade prevention: update tracking BEFORE validation"

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MonitorDataProcessor.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/MonitorDataProcessorTest.kt
  modified: []

key-decisions:
  - "EMA cold start fix: only seed with first real velocity (lastTimestamp > 0), not first-ever sample zero"
  - "Position range constants extracted to companion object (avoid repeated Float conversion on hot path)"
  - "calculateRawVelocity helper eliminates A/B duplication while preserving zero-allocation hot path"
  - "Deload test fakeTime must exceed initial debounce window (lastDeloadEventTime=0 + 2000ms)"

patterns-established:
  - "Synchronous processor with callback lambdas: no coroutines, no Flow, <5ms latency"
  - "Issue #210 invariant: lastPositionA/B updated BEFORE validateSample() always"

# Metrics
duration: 12min
completed: 2026-02-15
---

# Phase 10 Plan 01: MonitorDataProcessor Summary

**TDD extraction of synchronous BLE monitor processing pipeline: position clamping, jump filter (Issue #210 cascade prevention), velocity EMA with cold-start seeding, and debounced status flag callbacks**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-15T23:17:22Z
- **Completed:** 2026-02-15T23:29:10Z
- **Tasks:** 3 (RED -> GREEN -> REFACTOR)
- **Files created:** 2

## Accomplishments
- MonitorDataProcessor with full 7-stage processing pipeline: position clamping, status flags, Issue #210 tracking, validation, velocity, EMA smoothing, WorkoutMetric construction
- 30 unit tests covering position validation (6), load validation (3), jump filter/Issue #210 (6), velocity EMA (5), status flags (6), session reset (4)
- EMA cold start fix improved over original KableBleRepository: seed with first real velocity, not first-ever zero value
- All 15 state variables cleanly encapsulated in processor class

## Task Commits

Each task was committed atomically:

1. **Task 1: RED - Write failing tests** - `6de65eef` (test)
2. **Task 2: GREEN - Implement MonitorDataProcessor** - `17f8602a` (feat)
3. **Task 3: REFACTOR - Clean up MonitorDataProcessor** - `a460409a` (refactor)

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MonitorDataProcessor.kt` - Synchronous processing pipeline: position clamping, status flags, jump validation, velocity EMA, callback events
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/MonitorDataProcessorTest.kt` - 30 unit tests covering all pipeline stages, edge cases, and Issue #210 invariant

## Decisions Made
- **EMA cold start improvement:** The original KableBleRepository seeds EMA with the first raw velocity value, but the first-ever sample always has zero velocity (no previous timestamp). The processor gates EMA seeding on `lastTimestamp > 0`, so the first real velocity seeds the EMA. This eliminates cold-start lag more effectively.
- **Companion object for range constants:** `MIN_POS` and `MAX_POS` extracted as pre-computed Float companion vals to avoid `Int.toFloat()` conversion on every call in the hot path.
- **calculateRawVelocity helper:** Extracted to deduplicate the A/B velocity calculation. Returns Double, takes Float positions -- consistent with the existing velocity types.
- **Test timing for deload debounce:** fakeTime in deload tests must be > 2000ms to clear the initial debounce window from `lastDeloadEventTime = 0`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] EMA cold start seeding gated on lastTimestamp**
- **Found during:** Task 2 (GREEN implementation)
- **Issue:** Original code seeds EMA with first raw velocity, but first-ever sample always produces zero velocity (no previous timestamp). This means the EMA starts at 0, defeating the cold start fix.
- **Fix:** Added `lastTimestamp > 0L` gate around EMA update block so first-ever sample (which produces zero velocity) doesn't seed the EMA.
- **Files modified:** MonitorDataProcessor.kt (EMA smoothing section)
- **Verification:** Test `first velocity sample seeds EMA directly` passes: second sample's velocity = raw velocity, not smoothed toward 0.
- **Committed in:** `17f8602a` (Task 2 commit)

**2. [Rule 1 - Bug] Test fakeTime adjusted for deload debounce window**
- **Found during:** Task 2 (GREEN implementation)
- **Issue:** Tests used fakeTime=1000 which is within the 2000ms debounce window from initial lastDeloadEventTime=0, causing first deload to be debounced.
- **Fix:** Changed deload test fakeTime to 5000L (well past initial debounce window).
- **Files modified:** MonitorDataProcessorTest.kt (deload tests)
- **Verification:** All 3 deload tests pass.
- **Committed in:** `17f8602a` (Task 2 commit)

**3. [Rule 1 - Bug] Test velocity values adjusted for jump filter**
- **Found during:** Task 2 (GREEN implementation)
- **Issue:** Velocity tests used position changes of 50-100mm between samples, exceeding the 20mm jump filter threshold, causing samples to be rejected.
- **Fix:** Reduced position deltas to 10mm (within 20mm threshold) and adjusted expected velocities accordingly.
- **Files modified:** MonitorDataProcessorTest.kt (velocity tests)
- **Verification:** All 5 velocity tests pass.
- **Committed in:** `17f8602a` (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (3 Rule 1 bugs)
**Impact on plan:** All fixes were necessary for correctness. The EMA cold start fix is an improvement over the original code. Test fixes corrected test-plan inconsistencies (position deltas exceeding jump threshold, fakeTime within debounce window). No scope creep.

## Issues Encountered
None beyond the auto-fixed deviations above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- MonitorDataProcessor is complete and fully tested, ready for delegation from KableBleRepository (Plan 02)
- Plan 02 will wire MonitorDataProcessor into KableBleRepository.parseMonitorData() and remove the 15 state variables
- No blockers or concerns

## Self-Check: PASSED

- FOUND: MonitorDataProcessor.kt
- FOUND: MonitorDataProcessorTest.kt
- FOUND: 10-01-SUMMARY.md
- FOUND: 6de65eef (RED commit)
- FOUND: 17f8602a (GREEN commit)
- FOUND: a460409a (REFACTOR commit)

---
*Phase: 10-monitor-data-processor*
*Completed: 2026-02-15*

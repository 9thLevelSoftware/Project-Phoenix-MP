---
phase: 11-exercise-auto-detection
plan: 01
subsystem: domain
tags: [exercise-detection, tdd, signature-extraction, classification, ema, kotlin-multiplatform]

# Dependency graph
requires:
  - phase: 10-strength-assessment
    provides: WorkoutMetric model with position/velocity/load data
provides:
  - SignatureExtractor for movement fingerprinting from WorkoutMetric streams
  - ExerciseClassifier with history matching and rule-based fallback
  - Domain models (ExerciseSignature, ExerciseClassification, VelocityShape, CableUsage)
  - Weighted similarity algorithm (ROM 40%, duration 20%, symmetry 25%, shape 15%)
  - EMA signature evolution for learning user patterns
affects: [11-02-session-manager, 11-03-ui-integration]

# Tech tracking
tech-stack:
  added: []
  patterns: [valley-based-rep-detection, weighted-similarity, ema-evolution]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/DetectionModels.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/SignatureExtractor.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/ExerciseClassifier.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/detection/SignatureExtractorTest.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/detection/ExerciseClassifierTest.kt
  modified: []

key-decisions:
  - "Valley-based rep detection using 5-sample moving average and local minima"
  - "Raw position data for ROM accuracy, smoothed data for valley detection"
  - "History matching threshold at 0.85 similarity before rule-based fallback"
  - "EMA alpha=0.3 for signature evolution (per DETECT-06 spec)"

patterns-established:
  - "Detection domain package: com.devil.phoenixproject.domain.detection"
  - "TDD pattern: RED (failing tests) -> GREEN (minimal implementation) -> verify"
  - "Test data helpers for synthetic WorkoutMetric generation"

# Metrics
duration: 12min
completed: 2026-02-15
---

# Phase 11 Plan 01: Signature Extraction Engine Summary

**Valley-based movement signature extraction with weighted similarity classification and EMA-based pattern learning**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-15T05:04:18Z
- **Completed:** 2026-02-15T05:16:37Z
- **Tasks:** 2 (TDD)
- **Files created:** 5
- **Tests added:** 27 (12 SignatureExtractor + 15 ExerciseClassifier)

## Accomplishments
- SignatureExtractor extracts biomechanical fingerprints from WorkoutMetric streams
- ExerciseClassifier matches against user history (>0.85 similarity) or falls back to rule-based decision tree
- Weighted similarity computation per DETECT-05 spec (ROM 40%, duration 20%, symmetry 25%, shape 15%)
- EMA signature evolution per DETECT-06 spec (alpha=0.3 for continuous, latest for categorical)
- Comprehensive test coverage with synthetic data generators

## Task Commits

Each task was committed atomically:

1. **Task 1: Domain models and SignatureExtractor (TDD)** - `b717c28f` (feat)
2. **Task 2: ExerciseClassifier with history matching (TDD)** - `6454d0d2` (feat)

_TDD tasks: tests and implementation committed together after GREEN phase_

## Files Created/Modified

**Created:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/DetectionModels.kt` - ExerciseSignature, ExerciseClassification, VelocityShape, CableUsage
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/SignatureExtractor.kt` - Valley-based rep detection and signature extraction
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/detection/ExerciseClassifier.kt` - History + rule-based classification with EMA evolution
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/detection/SignatureExtractorTest.kt` - 12 tests with synthetic data helpers
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/detection/ExerciseClassifierTest.kt` - 15 tests for classification and evolution

## Decisions Made

1. **Valley detection algorithm:** 5-sample moving average smoothing with local minima detection (must be smallest in +/-2 window and below threshold). Minimum 8 samples between valleys to prevent false positives.

2. **ROM calculation:** Use raw position data for peak/valley values (not smoothed) to preserve accuracy. Smoothed data only for valley index detection.

3. **Velocity profile classification:** Divide concentric phase into thirds, compare average velocities. EXPLOSIVE_START if first third >15% higher than others. DECELERATING if last third >15% lower.

4. **Rule-based decision tree order:** Single cable checks first, then dual symmetric by ROM/duration/velocity, with Unknown fallback for dual asymmetric.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Test data helper not reaching peak/valley positions**
- **Found during:** Task 1 (SignatureExtractor tests)
- **Issue:** Test helper used exclusive ranges, so position never reached actual valley/peak values (e.g., 100/300mm)
- **Fix:** Changed to inclusive ranges with proper endpoint calculation
- **Files modified:** SignatureExtractorTest.kt (buildSingleRep helper)
- **Verification:** ROM tests now pass with expected values
- **Committed in:** b717c28f

**2. [Rule 1 - Bug] Velocity pattern tests ambiguous**
- **Found during:** Task 1 (SignatureExtractor tests)
- **Issue:** DECELERATING pattern (0.8 -> 0.6 -> 0.2) was being classified as EXPLOSIVE_START because first third was highest
- **Fix:** Changed test data patterns to be clearly distinguishable (DECELERATING: 0.6 -> 0.6 -> 0.2 with only last third different)
- **Files modified:** SignatureExtractorTest.kt (velocity patterns)
- **Verification:** All velocity profile tests pass
- **Committed in:** b717c28f

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both fixes essential for test correctness. No scope creep.

## Issues Encountered
- assertEquals tolerance for Long doesn't exist in kotlin.test - switched to assertTrue with range check for durationMs assertion

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- SignatureExtractor and ExerciseClassifier ready for integration
- Plan 02 will add DetectionSessionManager to coordinate signature extraction during workouts
- Plan 03 will add UI components for exercise confirmation/correction

## Self-Check: PASSED

- All 5 files created exist
- Commits b717c28f and 6454d0d2 verified in git log
- All 27 tests passing

---
*Phase: 11-exercise-auto-detection*
*Completed: 2026-02-15*

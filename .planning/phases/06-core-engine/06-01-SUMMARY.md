---
phase: 06-core-engine
plan: 01
subsystem: biomechanics-engine
tags: [vbt, force-curve, asymmetry, stateflow, architecture]
dependency-graph:
  requires: []
  provides: [BiomechanicsEngine, BiomechanicsModels, rep-segmentation]
  affects: [WorkoutCoordinator, ActiveSessionEngine]
tech-stack:
  added: []
  patterns: [StateFlow-exposure, internal-stub-methods, Dispatchers.Default]
key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/BiomechanicsModels.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/BiomechanicsEngine.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
decisions:
  - BiomechanicsVelocityZone thresholds based on VBT research (1000/750/500/250 mm/s)
  - Concentric phase detection by positive velocity with fallback to first-half approximation
  - Stub compute methods marked internal for Plan 02-04 replacement
metrics:
  duration: 7m 12s
  completed: 2026-02-15
---

# Phase 6 Plan 01: Biomechanics Engine Infrastructure Summary

BiomechanicsEngine foundation with domain models, StateFlow outputs, rep boundary capture, and per-rep metric segmentation.

## What Changed

### Task 1: Biomechanics Domain Models and Engine Shell
- **BiomechanicsModels.kt** (168 LOC): Complete domain type hierarchy
  - `BiomechanicsVelocityZone` enum with MCV-based classification (EXPLOSIVE/FAST/MODERATE/SLOW/GRIND)
  - `VelocityResult` for VBT analysis (MCV, peak velocity, velocity loss, estimated reps remaining)
  - `ForceCurveResult` for normalized force curves (101-point ROM normalization, sticking point, strength profile)
  - `AsymmetryResult` for left/right balance analysis
  - `BiomechanicsRepResult` combining all three analysis types per rep
  - `BiomechanicsSetSummary` for aggregated set statistics
  - `StrengthProfile` enum (ASCENDING/DESCENDING/BELL_SHAPED/FLAT)

- **BiomechanicsEngine.kt** (230 LOC): Analysis orchestrator
  - `processRep()` coordinates all three compute methods and updates StateFlow
  - `getSetSummary()` aggregates per-rep results with zone distribution, averages
  - `reset()` clears state for new set
  - Three `internal` stub compute methods for Plans 02-04 to implement
  - `latestRepResult` StateFlow for reactive UI updates

### Task 2: Workout Flow Integration
- **WorkoutCoordinator** additions:
  - `biomechanicsEngine` instance (like existing `repQualityScorer` pattern)
  - `repBoundaryTimestamps` list for per-rep metric segmentation
  - `latestBiomechanicsResult` StateFlow getter delegating to engine

- **ActiveSessionEngine** additions:
  - `handleRepNotification()`: captures rep boundary timestamp, calls `processBiomechanicsForRep()`
  - `processBiomechanicsForRep()`: segments metrics, filters concentric phase, dispatches to Dispatchers.Default
  - `handleSetCompletion()`: resets biomechanics engine and timestamps
  - `resetForNewWorkout()`: resets biomechanics engine and timestamps

## Verification Results

- `./gradlew :shared:compileKotlinMetadata` - PASSED
- `./gradlew :androidApp:assembleDebug` - PASSED (2m 30s)
- `./gradlew :androidApp:testDebugUnitTest` - PASSED (0 regressions)
- BiomechanicsModels.kt contains all 7 types - VERIFIED
- BiomechanicsEngine.kt has processRep(), getSetSummary(), reset(), 3 internal stubs - VERIFIED
- processBiomechanicsForRep() uses Dispatchers.Default - VERIFIED
- No FeatureGate checks in data capture path (GATE-04) - VERIFIED

## Commits

| Hash | Message |
|------|---------|
| 30354c0d | feat(06-01): add biomechanics domain models and engine shell |
| ec66c1ad | feat(06-01): wire biomechanics processing into workout flow |

## Key Decisions

1. **BiomechanicsVelocityZone separate from VelocityZone**: LED feedback zones (5/30/60 mm/s thresholds) are for visual feedback; biomechanics zones (250/500/750/1000 mm/s) classify MCV for training load analysis.

2. **Concentric detection by velocity direction**: Positive velocity indicates lifting (concentric). Fallback to first-half approximation if no positive velocity samples found.

3. **Internal stub methods**: `computeVelocity()`, `computeForceCurve()`, `computeAsymmetry()` are marked `internal` so Plans 02-04 can implement real logic without interface changes.

4. **Rep boundary timestamps**: Stored separately from metrics to enable precise per-rep segmentation without modifying WorkoutMetric.

## Deviations from Plan

None - plan executed exactly as written.

## Self-Check: PASSED

- [x] BiomechanicsModels.kt exists with all 7 types
- [x] BiomechanicsEngine.kt exists with StateFlow and stubs
- [x] WorkoutCoordinator has biomechanicsEngine and repBoundaryTimestamps
- [x] ActiveSessionEngine calls processBiomechanicsForRep on Dispatchers.Default
- [x] Commits 30354c0d and ec66c1ad exist

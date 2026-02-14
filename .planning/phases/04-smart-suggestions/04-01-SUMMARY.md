---
phase: 04-smart-suggestions
plan: 01
subsystem: domain
tags: [kotlin, tdd, algorithms, training-analysis, pure-functions]

requires:
  - phase: 01-data-foundation
    provides: "Exercise and WorkoutSession domain models with muscleGroup field"
provides:
  - "SmartSuggestionsEngine - stateless pure computation engine for 5 suggestion types"
  - "Domain models for volume, balance, neglect, plateau, and time-of-day analysis"
  - "MovementCategory enum and muscle group classification"
affects: [04-02, 04-03]

tech-stack:
  added: []
  patterns: ["Stateless object with pure functions for testable domain logic", "Injectable nowMs parameter for time-dependent computations"]

key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/SmartSuggestions.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt
    - shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngineTest.kt
  modified: []

key-decisions:
  - "Stateless object SmartSuggestionsEngine (no DI, no DB, pure functions only)"
  - "Injectable nowMs: Long parameter for all time-dependent functions (follows 02-01 timeProvider pattern)"
  - "Volume formula: weightPerCableKg * 2 * workingReps (dual cable machine)"
  - "Balance thresholds: <25% or >45% triggers imbalance (excluding core from ratio)"
  - "Plateau detection: 4+ consecutive sessions within 0.5kg tolerance, minimum 5 total sessions"
  - "Time-of-day optimal window requires minimum 3 sessions for statistical relevance"

patterns-established:
  - "Pure domain engine pattern: object with no dependencies, injectable time, List<SessionSummary> as universal input"
  - "MovementCategory classification: PUSH (Chest/Shoulders/Triceps), PULL (Back/Biceps), LEGS (Legs/Glutes), CORE (Core/Full Body)"

duration: 6min
completed: 2026-02-14
---

# Phase 4 Plan 1: SmartSuggestionsEngine Summary

**TDD-built pure domain engine computing 5 training insights (volume, balance, neglect, plateau, time-of-day) with 22 test cases**

## Performance

- **Duration:** 6 min
- **Started:** 2026-02-14T21:01:21Z
- **Completed:** 2026-02-14T21:07:13Z
- **Tasks:** 2 (TDD RED + GREEN)
- **Files modified:** 3

## Accomplishments
- Domain models for all 5 suggestion types with clean data classes (no framework dependencies)
- SmartSuggestionsEngine as stateless object with 6 pure functions
- 22 comprehensive test cases covering all algorithms, edge cases, and boundary conditions
- Muscle group classifier mapping ExerciseCategory strings to push/pull/legs/core
- Full TDD cycle: RED (22 failing) -> GREEN (22 passing) -> REFACTOR (no changes needed)

## Task Commits

Each task was committed atomically:

1. **RED: Domain models + failing tests** - `096cfe70` (test)
2. **GREEN: Engine implementation** - `2674c9d3` (feat)

_No refactor commit needed - implementation was clean on first pass._

## Files Created/Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/SmartSuggestions.kt` - Domain models: SessionSummary, MuscleGroupVolume, WeeklyVolumeReport, BalanceAnalysis, BalanceImbalance, NeglectedExercise, PlateauDetection, TimeOfDayAnalysis, MovementCategory, TimeWindow
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt` - Pure computation engine with 5 public methods + 1 internal classifier
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngineTest.kt` - 22 test cases covering volume (4), balance (3), neglect (4), plateau (4), time-of-day (4), classification (3)

## Decisions Made
- Used `object SmartSuggestionsEngine` (stateless, no Koin needed) - simplest pattern for pure domain logic
- Injectable `nowMs: Long` parameter follows established timeProvider pattern from Phase 2 (decision 02-01)
- Volume formula `weightPerCableKg * 2 * workingReps` accounts for dual-cable machine design
- Balance analysis excludes CORE from push/pull/legs ratio to avoid skewing results
- Plateau requires 5+ total sessions and 4+ consecutive at same weight (0.5kg tolerance matches machine increment)
- Time-of-day optimal requires 3+ sessions for statistical relevance

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Engine ready for Plan 02 (repository/data layer integration)
- SessionSummary input type designed for easy mapping from DB query results
- All public methods tested and verified

## Self-Check: PASSED

- [x] SmartSuggestions.kt: FOUND
- [x] SmartSuggestionsEngine.kt: FOUND
- [x] SmartSuggestionsEngineTest.kt: FOUND
- [x] 04-01-SUMMARY.md: FOUND
- [x] Commit 096cfe70 (RED): FOUND
- [x] Commit 2674c9d3 (GREEN): FOUND

---
*Phase: 04-smart-suggestions*
*Completed: 2026-02-14*

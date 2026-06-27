# Domain - Core Models Review

Scope reviewed: assigned core domain model files. Several assigned file paths do not exist as standalone files in this checkout; their declarations are consolidated in `Routine.kt`, `TrainingCycleModels.kt`, and `Models.kt`. No code was changed.

## Summary

- Findings: 14
- Severity breakdown: critical 0, high 0, medium 9, low 5
- Stub/TODO scan: no TODO/FIXME/HACK/unimplemented markers found in the reviewed model files.

## File path inventory

Present assigned files:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/EquipmentRack.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExternalActivity.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt`

Assigned paths missing as standalone files:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RoutineGroup.kt` (declaration is in `Routine.kt`)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycle.kt` (declaration is in `TrainingCycleModels.kt`)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutCommand.kt` (no domain model declaration found; command packets appear to be built as `ByteArray` via utilities)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutMetric.kt` (declaration is in `Models.kt`)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutParameters.kt` (declaration is in `Models.kt`)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutState.kt` (declaration is in `Models.kt`)
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutSummary.kt` (no standalone declaration found; closest summary type is `WorkoutState.SetSummary` in `Models.kt`)

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt

### Finding 1
- Category: failure-point
- Severity: low
- Line numbers: 22-35
- Description: `Exercise` accepts unchecked values for persisted/user-derived fields such as blank `name`, negative `timesPerformed`, non-finite or negative `oneRepMaxKg`, and non-finite/negative `mvtOverrideMs`. These values feed display, PR calculations, and velocity-threshold behavior; a corrupt import or custom exercise row can therefore carry invalid stats through the domain layer.
- Suggested fix direction: Add model-level `init` validation or a factory/sanitizer for persisted rows. At minimum reject blank names, clamp `timesPerformed >= 0`, and require optional numeric values to be finite and non-negative when present.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/EquipmentRack.kt

### Finding 2
- Category: failure-point
- Severity: low
- Line numbers: 43-50
- Description: `ActiveRackSelection` exposes the raw `itemIds` list unchanged while `distinctItemIds` is only a computed view. Callers that serialize or pass `itemIds` directly can preserve duplicates or blank IDs even though other call sites expect a normalized unique selection.
- Suggested fix direction: Normalize in the constructor/factory, or make `itemIds` private and expose only a validated/distinct list. Filter blank IDs consistently at the domain boundary.

### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 54-63
- Description: `RackLoadAdjustment` has no validation for non-finite or negative `externalAddedLoadKg`, `counterweightKg`, `displayLoadKg`, or `adjustedMachineWeightPerCableKg`. This model is later mirrored into `WorkoutParameters` and used for display and machine-facing weight decisions; if a bad adjustment is constructed outside the normal use case, NaN/Infinity or negative loads can propagate.
- Suggested fix direction: Add an `init` block requiring all load fields to be finite and non-negative, and ensure `selectedItems` is distinct by ID. If negative values are ever intentional, model them explicitly instead of overloading these totals.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExternalActivity.kt

### Finding 4
- Category: failure-point
- Severity: low
- Line numbers: 16-18
- Description: `IntegrationProvider.fromKey` performs an exact case-sensitive match. Provider keys are external wire values; casing changes such as `HEVY` or accidental whitespace resolve to null and are later converted to `UNKNOWN` or dropped by callers.
- Suggested fix direction: Normalize the input with `trim().lowercase()` before matching, and consider returning `UNKNOWN` directly when the model already defines that enum value.

### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: 47-65
- Description: `ExternalActivity` does not validate core activity measurements. Negative `durationSeconds`, `calories`, heart rates, distances, or impossible `startedAt` values can be represented as valid domain objects and persisted/synced.
- Suggested fix direction: Add validation/sanitization for imported activities: non-blank `externalId` and `name`, non-negative duration/distance/calories/elevation, positive heart rates when present, and a finite timestamp policy for `startedAt`/`syncedAt`.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 29-33
- Description: `WarmupSet` permits zero/negative reps and arbitrary `percentOfWorking` values, including negative or extremely high percentages. Warm-up sets are later executed as real machine stop/start cycles, so invalid warm-up metadata can become invalid workout commands or nonsensical load calculations.
- Suggested fix direction: Validate `reps > 0` and constrain `percentOfWorking` to a safe range, for example `1..100` or the app's intended warm-up maximum.

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 83, 131-143
- Description: `RoutineExercise.setReps` can be empty, contain non-positive values, or contain `null` entries independent of `isAMRAP`. The computed compatibility properties then become inconsistent: `sets` can be `0` while `reps` silently reports `10`, masking corrupt routine data instead of surfacing it.
- Suggested fix direction: Enforce a non-empty set list and require positive reps for non-AMRAP sets. If `null` is the AMRAP marker, validate that nulls are only allowed when the corresponding set is intended to be AMRAP.

### Finding 8
- Category: bug
- Severity: medium
- Line numbers: 181-195
- Description: `resolveSetWeights` returns `setWeightsPerCableKg` exactly as stored whenever the list is non-empty. If that list is shorter or longer than `setReps`, callers receive a weight list whose length does not match `sets`, which can misalign set execution, display, or persistence.
- Suggested fix direction: Normalize the fallback path the same way the percent path does: return `List(sets)` and fill missing entries with `weightPerCableKg` while trimming extra entries.

### Finding 9
- Category: failure-point
- Severity: low
- Line numbers: 247-262
- Description: `Superset.sets` uses the minimum set count across member exercises. Mixed-set supersets therefore silently drop the extra sets from longer exercises when this value is used as the authoritative superset set count.
- Suggested fix direction: Either validate that all exercises in a superset have equal `sets`, or represent mixed supersets explicitly and make UI/execution code decide how to handle trailing sets.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt

### Finding 10
- Category: bug
- Severity: medium
- Line numbers: 37-39
- Description: `TrainingCycle` exposes `progressionRule` and `weekNumber`, but the current SQLDelight repository/schema do not persist or reload those fields on the `TrainingCycle` row. Reloaded cycles fall back to `progressionRule = null` and `weekNumber = 1`, and portal sync uses `cycle.weekNumber`, so current week can be reset to 1 even after progression.
- Suggested fix direction: Persist these fields or remove them from the core `TrainingCycle` model and source them from `CycleProgress`/`CycleProgression` consistently. Portal sync should derive current week from persisted progress instead of the default model field.

### Finding 11
- Category: failure-point
- Severity: low
- Line numbers: 251-267
- Description: `CycleProgress.advanceToNextDay` does not validate `totalDays`. Direct calls with `totalDays <= 0` still produce `currentDayNumber = 1` and may add the previous current day to `missedDays`, creating progress state for a cycle with no valid days. One repository caller guards empty cycles, but the domain method itself is unsafe.
- Suggested fix direction: Require `totalDays > 0` in the domain method or return unchanged progress for non-positive totals. Also consider validating `currentDayNumber in 1..totalDays` before advancing.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt

### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: 21-25, 45-53, 59-79
- Description: Preference sentinel/range fields are not validated. Invalid `summaryCountdownSeconds`, `autoStartCountdownSeconds`, `weightIncrement`, `bodyWeightKg`, and `velocityLossThresholdPercent` values can be represented and then drive countdown behavior, weight stepping, bodyweight volume, or VBT thresholds. In particular, a positive infinite `weightIncrement` passes `weightIncrement > 0f` and returns an infinite effective increment.
- Suggested fix direction: Add a sanitizer/factory for preferences loaded from storage. Constrain countdowns to documented values, require finite positive increments or the `-1` sentinel, clamp body weight to a realistic non-negative finite range, and constrain VBT thresholds to the supported percentage range.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt (WorkoutState, WorkoutParameters, WorkoutMetric, WorkoutSummary-equivalent)

### Finding 13
- Category: bug
- Severity: medium
- Line numbers: 201-211
- Description: `ProgramMode.fromValue` silently maps any unknown integer to `OldSchool`. If persisted defaults, BLE values, or sync data are corrupt or from a newer app version, the app can send Old School commands rather than rejecting the invalid mode or preserving an unknown value.
- Suggested fix direction: Return `ProgramMode?`/`Result<ProgramMode>` for parsing, or add an explicit unknown mode. Only default to Old School at a UI fallback boundary after logging and surfacing the data issue.

### Finding 14
- Category: failure-point
- Severity: medium
- Line numbers: 325-354, 362-374, 548-590
- Description: Workout execution models accept invalid numeric state and have a narrow summary-presence check. `WorkoutParameters` can hold negative reps/weights/rest seconds, `WorkoutMetric` can hold NaN/Infinity loads/positions/velocities that make `totalLoad` NaN, and `WorkoutSession.hasSummaryMetrics` only checks `peakForceConcentricA/B`; sessions with `totalVolumeKg`, `heaviestLiftKg`, calories, or other summary fields but no concentric peak force are treated as having no summary and `toSetSummary()` returns null.
- Suggested fix direction: Keep BLE validators, but also add domain-level sanitization for state loaded from persistence and telemetry. Require finite metric values before aggregation, validate workout parameter ranges at construction/update boundaries, and broaden `hasSummaryMetrics` to include the persisted summary fields needed by `toSetSummary()`.

## Assigned standalone files not found

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RoutineGroup.kt
- Review result: file not present. `RoutineGroup` was reviewed in `Routine.kt` lines 10-16. No additional standalone file findings.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycle.kt
- Review result: file not present. `TrainingCycle` was reviewed in `TrainingCycleModels.kt` lines 30-63; findings are listed above.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutCommand.kt
- Review result: file not present and no domain model declaration named `WorkoutCommand` was found. Workout command construction appears to live in utility/BLE packet code rather than the domain model package.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutMetric.kt
- Review result: file not present. `WorkoutMetric` was reviewed in `Models.kt` lines 362-374; findings are listed above.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutParameters.kt
- Review result: file not present. `WorkoutParameters` was reviewed in `Models.kt` lines 325-354; findings are listed above.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutState.kt
- Review result: file not present. `WorkoutState` was reviewed in `Models.kt` lines 71-139; no standalone-file-specific findings beyond the summary model issues above.

### shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WorkoutSummary.kt
- Review result: file not present. The closest model is `WorkoutState.SetSummary` plus `WorkoutSession.toSetSummary()` in `Models.kt`; findings are listed above.

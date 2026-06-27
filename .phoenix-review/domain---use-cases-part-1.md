# Domain - Use Cases Part 1 Review

Scope reviewed: 7 assigned files under `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/`.

## Summary

- Findings: 15
- Severity breakdown: critical 0, high 3, medium 8, low 4
- Notes: `BuildWorkoutSummaryUseCase.kt` is not present at the assigned path and no `BuildWorkoutSummaryUseCase` symbol was found in the repository.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyEquipmentRackLoadUseCase.kt

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 26-33
- Description: Echo mode returns `programmedWeightPerCableKg` directly as `adjustedMachineWeightPerCableKg`, while non-Echo mode clamps to the validator minimum and `Constants.MAX_WEIGHT_PER_CABLE_KG`. If a caller passes a negative, NaN, or over-ceiling programmed weight during Echo setup, this use case can propagate an invalid machine command even though the non-Echo path is protected.
- Suggested fix direction: Apply the same finite/range validation to the Echo path, or explicitly validate/return a safe value before constructing `RackLoadAdjustment`.

### Finding 2
- Category: failure-point
- Severity: low
- Line numbers: 29-33
- Description: `coerceIn(minimum, Constants.MAX_WEIGHT_PER_CABLE_KG)` can throw if `validatorMinimumPerCableKg` is accidentally configured above the hardware maximum. The public API accepts the minimum as a parameter, so a bad validator value can crash load-adjustment calculation instead of failing closed.
- Suggested fix direction: Clamp the computed minimum to the hardware maximum before calling `coerceIn`, or reject invalid validator configuration with a controlled result/error.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyRoutineModifierUseCase.kt

No actionable findings found in this file. The code is intentionally pure/copy-based, preserves routine metadata, and has tests covering active recovery, heavy deload, profile-scoped PR lookup, warmup behavior, timed sets, and bodyweight handling.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/BuildWorkoutSummaryUseCase.kt

### Finding 3
- Category: error
- Severity: high
- Line numbers: N/A - assigned file is missing
- Description: The assigned file does not exist at `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/BuildWorkoutSummaryUseCase.kt`. Repository search also found no `BuildWorkoutSummaryUseCase` symbol. This makes the review target stale or the implementation absent.
- Suggested fix direction: Either restore/add the intended use case file, or update the review/task scope and any call sites/docs to point at the current workout summary implementation.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ComputeVelocityOneRepMaxUseCase.kt

### Finding 4
- Category: bug
- Severity: medium
- Line numbers: 31-32
- Description: The use case persists every non-null estimator result, including results where `passedQualityGate` is false. Downstream reads such as `getLatestPassing` ignore failed estimates, but the backfill flow's `hasEstimates` check counts any existing estimate. A failed-quality backfill can therefore mark an exercise as backfilled while leaving it with no usable passing velocity 1RM estimate.
- Suggested fix direction: Persist only passing estimates from this use case, or change backfill idempotency to check for existing passing estimates rather than any estimate. If failed estimates are intentionally retained for diagnostics, keep them from satisfying backfill completion.

### Finding 5
- Category: failure-point
- Severity: low
- Line numbers: 19-22
- Description: `windowDays` is not validated before calculating `sinceMs`. A zero or negative window queries a future/degenerate window; extremely large values can overflow the timestamp arithmetic. The default is safe, but the parameter is public and used by backfill with custom values.
- Suggested fix direction: Require `windowDays > 0` and consider saturating timestamp subtraction or documenting/limiting the accepted maximum.

### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: 32
- Description: Persistence errors are not isolated. If `persist(...)` throws, the whole computation path throws after estimation, which can interrupt the post-save hook that also handles velocity badges and related side effects.
- Suggested fix direction: Decide whether this use case should return a failure result or catch/log persistence errors at the boundary. At minimum, callers should isolate this optional analytics write from the core workout-save flow.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ComparativeAnalyticsUseCase.kt

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 18-20, 61-64
- Description: Percentage change is calculated from period averages for every `TrendMetricType`. For total-based metrics such as `VOLUME_WEEKLY`, `VOLUME_MONTHLY`, and likely `WORKOUT_FREQUENCY`, comparing averages can report the wrong direction when the number of points differs between periods.
- Suggested fix direction: Choose the comparison basis by metric type. Use `totalValue` for total/count metrics and `averageValue` only for metrics that are semantically averages.

### Finding 8
- Category: failure-point
- Severity: low
- Line numbers: 18-23
- Description: When the previous period average is zero or the previous period is empty, the result always reports `0%` and `NO_CHANGE`, even if the current period has positive data. This hides first-time activity or recovery from a zero baseline.
- Suggested fix direction: Represent zero-baseline comparisons explicitly, e.g. `INCREASE` with an undefined/null percentage, a sentinel, or a separate `hasBaseline` flag.

### Finding 9
- Category: stub
- Severity: low
- Line numbers: 31-32
- Description: `isSignificant` is labeled as statistical significance but is implemented as a fixed absolute percentage threshold (`> 5%`) with no sample-size, variance, or confidence calculation. This can overstate significance for tiny/noisy periods.
- Suggested fix direction: Rename this to something like `isMeaningfulChange` if it is a heuristic, or implement an actual significance/confidence calculation using variance and sample count.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ProgressionUseCase.kt

### Finding 10
- Category: bug
- Severity: high
- Line numbers: 69-72, 211-214
- Description: `checkForProgression` and `checkForDeload` accept `profileId`, but the completed-set history they analyze is fetched only by `exerciseId`. The repository API/query has no profile parameter, so sets from other profiles can influence progression/deload suggestions for the active profile.
- Suggested fix direction: Add profile-scoped completed-set reads, likely by joining through `WorkoutSession.profile_id`, and pass `profileId` from both use-case methods.

### Finding 11
- Category: bug
- Severity: high
- Line numbers: 79-83
- Description: The comment says the code gets the most recent weight, but it actually uses `recentSets.maxOfOrNull { it.actualWeightKg }`. If the user previously lifted heavier and has since reduced load, the use case analyzes/suggests progression from the stale maximum rather than the current working weight.
- Suggested fix direction: Derive current weight from the latest non-warmup completed set or latest session group, not from the maximum historical weight.

### Finding 12
- Category: bug
- Severity: medium
- Line numbers: 166-191, 138-160, 265-280, 304-318
- Description: Session grouping is inferred from a 2-hour timestamp gap even though `CompletedSet` contains `sessionId`. This can merge two distinct workouts done within two hours or split a long workout/session, causing incorrect consecutive-session progression, missed-rep deload, and plateau analysis.
- Suggested fix direction: Group completed sets by `sessionId` first, then order sessions by their latest completion timestamp. Use a time-gap fallback only for legacy rows that truly lack session identity.

### Finding 13
- Category: failure-point
- Severity: medium
- Line numbers: 115-131
- Description: RPE-based progression considers all RPE-logged sets at `currentWeight` within the recent limit, not the latest consecutive sessions. Old low-RPE sets can still trigger a progression even if the most recent work at that load was harder, especially combined with the stale-maximum issue above.
- Suggested fix direction: Restrict RPE progression to the latest N session groups at the current weight and require those recent sessions to be consistently below target.

### Finding 14
- Category: bug
- Severity: medium
- Line numbers: 304-318
- Description: `checkPlateauDeload` says six session groups are enough for plateau detection, but it then calls `detectPlateau(..., minDurationDays = 14)`. `TrendAnalysisUseCase.detectPlateau` first requires `dataPoints.size >= minDurationDays`, so this path actually needs at least 14 session data points before duration is even considered.
- Suggested fix direction: Separate minimum sample count from minimum calendar duration. Pass a sample threshold to trend analysis independently or change `detectPlateau` so `minDurationDays` checks elapsed time only.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecommendWeightAdjustmentUseCase.kt

### Finding 15
- Category: failure-point
- Severity: medium
- Line numbers: 20, 105-107, 132-134, 147-161
- Description: The input weight is only checked against `Constants.MIN_WEIGHT_KG`; there is no finite or upper-bound validation before returning maintain/decrease recommendations. If an invalid or over-ceiling current weight reaches this use case, the maintain path can return a recommendation above `Constants.MAX_WEIGHT_PER_CABLE_KG`, and arithmetic with NaN/Infinity is not guarded.
- Suggested fix direction: Require finite `currentWeightKgPerCable` and clamp/reject values outside `[Constants.MIN_WEIGHT_KG, Constants.MAX_WEIGHT_PER_CABLE_KG]` before any recommendation is built. Also validate `weightIncrementKg` as finite.

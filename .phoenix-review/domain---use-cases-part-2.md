# Domain - Use Cases Part 2 Review

Task: `t_36721921`
Scope: remaining use cases for rep counting, template conversion, trends, and backfill.

Reviewed assigned files under `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/`.
`SmartSuggestionsUseCase.kt` was not present at the assigned path; a likely replacement, `domain/premium/SmartSuggestionsEngine.kt`, exists and was inspected only to confirm the rename/move.

## Summary

- Files assigned: 9
- Assigned files found and reviewed: 8
- Assigned files missing: 1
- Findings: 16
- Severity breakdown:
  - Critical: 0
  - High: 2
  - Medium: 9
  - Low: 5

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt`

#### Finding 1
- Category: bug
- Severity: high
- Line numbers: 598-603
- Description: `calculateDelta()` treats every counter decrease as a valid 16-bit wrap. A real counter reset, reconnect, or stale baseline such as `last=500, current=0` produces a huge positive delta (`65036`) instead of zero/reset. In legacy mode this delta is used in `repeat(topDelta)`, which can emit thousands of reps and potentially lock up the workout flow. In modern mode the same delta can also drive warmup fallback and pending-state transitions from a reset packet.
- Suggested fix direction: Distinguish plausible one-step 16-bit wrap from counter resets. Clamp to a small expected per-packet maximum, treat large backward jumps as a re-baseline, and log/reset `lastTopCounter`/`lastCompleteCounter` without emitting rep events.

#### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 550-579
- Description: Modern working-rep tracking emits only one `WORKING_COMPLETED` event when `repsSetCount` jumps above `workingReps`, even if the machine batched or dropped notifications and the count advanced by multiple reps. Warmup counting explicitly emits one event per integer step, but working reps do not, so audio/UI side effects tied to per-rep events can miss reps while the stored count jumps to the final value.
- Suggested fix direction: Mirror the warmup logic: compute the target working count and emit one `WORKING_COMPLETED` event for each missing integer count before checking workout completion.

#### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 816-839, 901-919
- Description: Danger-zone checks consider a calibrated cable dangerous whenever the current position is less than or equal to the 5% threshold. If a monitor packet reports a missing/invalid current position as `0f` for a cable that previously built a meaningful range, `isInDangerZone()` returns true and can trigger false red warnings or auto-stop behavior.
- Suggested fix direction: Ignore non-positive current positions for calibrated cables unless the data source explicitly marks them valid. Consider carrying validity flags with the position sample or using the same `pos > 0f` guard used by the range-building paths.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveRoutineWeightsUseCase.kt`

No findings identified in this file.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/SmartSuggestionsUseCase.kt`

#### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned file does not exist at `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/SmartSuggestionsUseCase.kt`. Searching the repository found smart-suggestion logic in `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt` instead. The stale path means this review target cannot be reviewed as specified and may also indicate stale documentation, stale task generation, or a renamed use case that callers/tests should be checked against.
- Suggested fix direction: Update the review/task manifest to the current file path, or restore an adapter/use-case file if external code still expects `SmartSuggestionsUseCase` in the domain/usecase package.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt`

#### Finding 5
- Category: bug
- Severity: high
- Line numbers: 151-188
- Description: Percentage-based template exercises lose their actual percentage loading during conversion. For `isPercentageBased` exercises, the converter sets `startingWeight` to `0f` and maps only target reps/AMRAP into `RoutineExercise`; it never sets `usePercentOfPR`, `weightPercentOfPR`, `setWeightsPercentOfPR`, or a scaling basis from `percentageSets.percent`. The converted routine therefore has zero weight and no retained per-set percentages for 5/3/1-style programming.
- Suggested fix direction: When `templateExercise.isPercentageBased` is true, populate the PR-scaling fields on `RoutineExercise` from `percentageSets` (converting fractional percentages such as `0.65f` to integer 65), set `usePercentOfPR = true`, and ensure workout-start weight resolution can calculate each set weight from the selected 1RM/training max.

#### Finding 6
- Category: error
- Severity: medium
- Line numbers: 116-118
- Description: A non-rest `CycleDayTemplate` with `routine == null` throws via `error("Training day ... has no routine")`. Template conversion is typically user/content driven, so a malformed or partially loaded template can crash the caller instead of returning a warning or partial conversion result.
- Suggested fix direction: Treat missing training-day routines as recoverable conversion warnings. Add the day name/number to warnings, skip or create an empty placeholder day, and avoid throwing from normal template validation failures.

#### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 193-211
- Description: If every exercise in a training day fails resolution, no `CycleDay` is added for that day. The returned `TrainingCycle` can have fewer days than the source template, silently changing the schedule and day numbering even though only exercise lookup failed.
- Suggested fix direction: Preserve the cycle day structure even when no exercises resolve, either by creating an empty routine/day with warnings or by adding an explicit invalid-day warning that prevents saving until the user resolves the missing exercises.

#### Finding 8
- Category: bug
- Severity: low
- Line numbers: 158-162
- Description: The comment says starting weights are rounded to the nearest 0.5kg, but the implementation uses `toInt()` after multiplying by 2, which truncates downward. For example, a raw calculated weight of 70.9kg truncates to 70.5kg instead of rounding to 71.0kg. Values just below the next increment are always biased downward.
- Suggested fix direction: Use `roundToInt()` or the project’s shared weight-rounding utility instead of `toInt()`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TrendAnalysisUseCase.kt`

#### Finding 9
- Category: bug
- Severity: medium
- Line numbers: 23-25, 107-117
- Description: Linear regression uses list index as the x-axis, but `predictValue()` interprets `daysAhead` as calendar days and adds it directly to the last index. With irregular workout spacing, the slope is per sample, not per day, so predictions and trend strength can be materially wrong.
- Suggested fix direction: Base regression x-values on normalized timestamps/days since the first sample, or make the API explicit that `daysAhead` really means sample steps. For calendar predictions, use elapsed days as x and then predict at `lastDay + daysAhead`.

#### Finding 10
- Category: failure-point
- Severity: medium
- Line numbers: 173-185
- Description: `detectPlateau()` gates on `dataPoints.size < minDurationDays` before checking actual elapsed duration. This conflates number of observations with number of days. A sparse but real 14-day plateau with fewer than 14 samples is ignored before the duration check, while dense same-day samples can pass the count gate and only later be rejected by duration.
- Suggested fix direction: First sort/deduplicate samples by day as appropriate, then calculate elapsed days from timestamps. Use a separate minimum-sample threshold if needed instead of reusing `minDurationDays` as a point count.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/BackfillVelocityOneRepMaxUseCase.kt`

#### Finding 11
- Category: failure-point
- Severity: medium
- Line numbers: 22-24
- Description: The check-then-compute sequence is not atomic. If backfill runs concurrently for the same profile, two invocations can both observe `hasEstimates(id, profileId) == false` and both call `computeAllTime()`, producing duplicate or conflicting estimates depending on repository constraints.
- Suggested fix direction: Move the skip/insert decision into a repository transaction or make the insert idempotent with a uniqueness constraint/upsert keyed by `(exerciseId, profileId, computed window/type)`.

#### Finding 12
- Category: failure-point
- Severity: low
- Line numbers: 22-24
- Description: Any exception from `hasEstimates()` or `computeAllTime()` aborts the entire backfill loop. One corrupt exercise or transient repository failure prevents later exercises from being backfilled and gives the caller only a partial count with no failed-exercise details unless the exception is handled elsewhere.
- Suggested fix direction: Catch per-exercise failures, continue with the remaining IDs, and return a richer result containing created count plus failed exercise IDs/errors, or log and surface failures to the startup/backfill guard.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/BodyweightVolumeCalculator.kt`

#### Finding 13
- Category: bug
- Severity: medium
- Line numbers: 72-100
- Description: Variant picker lookup only checks the map keys `"push up"` and `"pull up"` with raw `contains()`. Common catalog names that percentage lookup already supports, such as `"push-up"`, `"pushup"`, `"pull-up"`, `"pullup"`, `"chin-up"`, or `"chinup"`, do not match these keys, so users may not be offered variant options for common bodyweight exercises.
- Suggested fix direction: Reuse the same alias lists/normalization used by `getPercentageForExercise()`, or normalize spaces/hyphens before matching. Add keys or alias groups for chin-up and no-space/hyphenated variants.

#### Finding 14
- Category: bug
- Severity: low
- Line numbers: 23-29, 116-123
- Description: Height-specific decline push-up matching is fragile for hyphenated names. Entries such as `"decline push 24"` do not match a natural name like `"decline push-up 24"`, so the code falls back to the generic decline push-up percentage instead of the height-specific percentage.
- Suggested fix direction: Normalize exercise names and keywords by removing punctuation/hyphens or add explicit height-specific hyphenated aliases such as `"decline push-up 24"`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/CountVelocityOneRepMaxImprovementsUseCase.kt`

#### Finding 15
- Category: failure-point
- Severity: low
- Line numbers: 9-18
- Description: Improvements are grouped only by `exerciseId`, even though `VelocityOneRepMaxEntity` includes `profileId`. If a caller accidentally passes estimates from multiple profiles, one profile’s prior best can suppress or create improvement counts for another profile.
- Suggested fix direction: Either enforce/profile-filter the input contract at the call site or group by both `profileId` and `exerciseId` inside the use case for defensive correctness.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecordPersonalMvtSampleUseCase.kt`

#### Finding 16
- Category: failure-point
- Severity: low
- Line numbers: 29-32
- Description: The rolling mean caps weighting at `MAX_SAMPLES`, but `sampleCount` itself grows without bound (`prevCount + 1`). If the UI or later algorithms interpret `sampleCount` as the number of samples represented by the rolling mean, confidence can grow indefinitely even though the mean only represents roughly the latest five-sample weighting.
- Suggested fix direction: Store a separate lifetime sample count if needed, or cap the persisted `sampleCount` at `MAX_SAMPLES` when it is used as the rolling-window confidence/weight.

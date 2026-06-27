# Presentation - Charts & Pickers Review

Task: `t_2fc20ce8`
Scope: charts, cycle components, and exercise picker rendering/data-flow review.
Repository: `/Users/christopherwilloughby/project-phoenix-mp`
Branch reviewed: `main`

## Summary

The six assigned source files are not present in the current checkout. I verified the exact paths with file reads, searched by filename glob, searched Kotlin sources for the declared class names, and checked all local/remotes-visible git history for the assigned paths. No matching files or class declarations were found, so a normal line-by-line code review of these components could not be performed.

Because the task specifically scopes these paths, each missing file is documented below as a failure point in the review/task inputs rather than inventing code-level findings from unrelated replacement files.

## Findings by File

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/charts/ForceCurveChart.kt`

#### Finding 1
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*ForceCurveChart.kt`, and a Kotlin content search found no `ForceCurveChart` declaration or reference. The charts directory currently contains other chart components such as `AreaChart.kt`, `CircleChart.kt`, `ComboChart.kt`, `GaugeChart.kt`, `ProgressionLineChart.kt`, `RadarChart.kt`, `VolumeTrendChart.kt`, and `WorkoutMetricsDetailChart.kt`.
- Suggested fix direction: Confirm whether this component was deleted, renamed, or generated from another source. Update the review task to the current path/name, or restore the file if the app still expects a force-curve chart component.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/CycleExerciseCard.kt`

#### Finding 2
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*CycleExerciseCard.kt`, and a Kotlin content search found no `CycleExerciseCard` declaration or reference. The cycle directory currently contains files such as `AddDaySheet.kt`, `ProgressionSettingsSheet.kt`, `RestDayRow.kt`, `SwipeableCycleItem.kt`, `UnifiedCycleCreationSheet.kt`, and `WorkoutDayRow.kt`.
- Suggested fix direction: Confirm whether this card was renamed to a current cycle component such as `WorkoutDayRow.kt` or folded into `UnifiedCycleCreationSheet.kt`. Reissue the review with the active component path, or restore the missing file if still referenced by design or navigation specs.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/CycleWeeklyBreakdown.kt`

#### Finding 3
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*CycleWeeklyBreakdown*.kt`, and a Kotlin content search found no `CycleWeeklyBreakdown` declaration or reference.
- Suggested fix direction: Confirm whether weekly-breakdown UI was renamed, consolidated into another cycle screen/component, or removed. Update the review target to the current implementation before expecting rendering/data-flow findings.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/CycleWeeklyBreakdownChart.kt`

#### Finding 4
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*CycleWeeklyBreakdown*.kt`, and a Kotlin content search found no `CycleWeeklyBreakdownChart` declaration or reference.
- Suggested fix direction: Confirm whether the chart was removed, renamed, or moved into a generic chart file. Reissue the task with the current chart implementation if this review should cover weekly-breakdown rendering.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExercisePickerBottomSheet.kt`

#### Finding 5
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*ExercisePickerBottomSheet.kt`, and a Kotlin content search found no `ExercisePickerBottomSheet` declaration or reference. The exercise picker package currently contains `AlphabetStrip.kt`, `ExerciseFilterShelf.kt`, `ExerciseRowContent.kt`, `GroupedExerciseList.kt`, `LetterHeader.kt`, and `SwipeableExerciseRow.kt`; there is also a top-level `presentation/components/ExercisePicker.kt` and `MiniExercisePickerDialog.kt` outside this package.
- Suggested fix direction: Confirm whether the bottom sheet was replaced by the current top-level picker/dialog implementation. Update the review task to include the active file(s), especially if picker state/data flow now lives outside the `exercisepicker` package.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExercisePickerResult.kt`

#### Finding 6
- Category: failure-point
- Severity: high
- Line numbers: N/A - file not found
- Description: The assigned file does not exist in the current repository checkout. `read_file` returned file-not-found, `search_files` found no `*ExercisePickerResult.kt`, and a Kotlin content search found no `ExercisePickerResult` declaration or reference.
- Suggested fix direction: Confirm whether the picker result model was moved into a different file/package or eliminated. If result data flow still exists, add the current model/handler file to the review scope.

## Verification Notes

- Exact-path reads for all six assigned files returned file-not-found.
- Filename searches returned no matches for:
  - `*ForceCurveChart.kt`
  - `*CycleExerciseCard.kt`
  - `*CycleWeeklyBreakdown*.kt`
  - `*ExercisePickerBottomSheet.kt`
  - `*ExercisePickerResult.kt`
- Kotlin content search returned no references/declarations for `ForceCurveChart`, `CycleExerciseCard`, `CycleWeeklyBreakdown`, `CycleWeeklyBreakdownChart`, `ExercisePickerBottomSheet`, or `ExercisePickerResult`.
- Git history check across visible local and remote branches did not show the assigned paths.

## Severity Breakdown

- Critical: 0
- High: 6
- Medium: 0
- Low: 0

## Review Limitation

No code-level rendering, state, coroutine, null-safety, or data-flow issues can be asserted for these six targets because the files are absent from the current checkout. The findings above are limited to the stale/missing review scope.
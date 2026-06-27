# Presentation - Screens Part 2 Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutSetupDialog.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeSubSelectorDialog.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineCompleteScreen.kt`

Stub marker scan: no TODO/FIXME/HACK/NotImplemented markers were found in the assigned files. One explicit placeholder/shortcut implementation was found in `SingleExerciseScreen.kt`.

## Summary

Findings count: 24

Severity breakdown:
- Critical: 0
- High: 4
- Medium: 15
- Low: 5

Category breakdown:
- bug: 13
- stub: 1
- error: 6
- failure-point: 4

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`

#### Finding 1
- Category: error
- Severity: medium
- Line numbers: 607-619
- Description: Tagging a Just Lift summary launches `onTagJustLiftSessionExercise()` from a UI coroutine without catching failures or surfacing an error. A repository/database failure while tagging can escape the composition scope, leave the summary looking untagged, and give the user no retry/error feedback.
- Suggested fix direction: Wrap the suspend call in `runCatching`/try-catch, log the failure, and show a snackbar or inline error while keeping the tag picker available for retry.

#### Finding 2
- Category: bug
- Severity: medium
- Line numbers: 668-679
- Description: Rest-screen bodyweight detection searches the next exercise by display name and treats blank equipment as bodyweight. Duplicate exercise names, localized/display-name prefixes, or missing equipment metadata can match the wrong routine exercise and hide the next-set configuration card even when the upcoming set uses cables.
- Suggested fix direction: Pass a stable next exercise index/id or an explicit bodyweight flag from the routine flow/view model. Only treat an exercise as bodyweight from the domain model's equipment/accessory semantics, not from an empty string fallback.

#### Finding 3
- Category: error
- Severity: medium
- Line numbers: 1808-1818
- Description: `CurrentExerciseCard` loads exercise and video data in a `LaunchedEffect` without error handling. Unlike `SetReadyScreen`, a repository or video lookup failure can cancel the effect and crash or blank the workout card during an active workout.
- Suggested fix direction: Mirror the guarded load used in `SetReadyScreen`: clear stale state, wrap repository calls in try-catch, log failures, and leave `videoEntity = null`/fallback exercise text when optional media cannot be loaded.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt`

#### Finding 4
- Category: bug
- Severity: medium
- Line numbers: 89-103
- Description: The high-asymmetry alert state is remembered globally for the HUD and only advances when `latestBiomechanicsResult.repNumber > lastProcessedRepNumber`. When a new set starts and rep numbers reset to 1, new results can be ignored and the previous consecutive count/alert can remain stale.
- Suggested fix direction: Key or reset `consecutiveHighAsymmetryCount` and `lastProcessedRepNumber` by workout/session, exercise index, and set index. Alternatively pass a monotonic rep event id from the analysis layer.

#### Finding 5
- Category: bug
- Severity: medium
- Line numbers: 238-245
- Description: The peripheral cable bars latch with `remember { mutableStateOf(false) }` and are never reset for a new set, exercise, or bodyweight transition. Once a cable becomes active, the HUD can keep showing that bar for later sets where the cable is inactive or after changing exercises.
- Suggested fix direction: Key the remembered latch state by active workout identity plus `currentExerciseIndex`/`currentSetIndex`, or reset it in a `LaunchedEffect` when the set/exercise changes.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutSetupDialog.kt`

#### Finding 6
- Category: bug
- Severity: medium
- Line numbers: 56-59, 426-429
- Description: `selectedExercise` is only updated inside `selectedExerciseId?.let { ... }`; if the parameters are reset to `selectedExerciseId = null`, the remembered `selectedExercise` is not cleared. The Start button can remain enabled and display a stale exercise even though the underlying parameters no longer contain an exercise id.
- Suggested fix direction: In the `LaunchedEffect`, explicitly set `selectedExercise = null` when `selectedExerciseId` is null or lookup fails. Derive the Start button enabled state from the current parameter id and loaded exercise together.

#### Finding 7
- Category: error
- Severity: high
- Line numbers: 226-249
- Description: The non-Echo weight slider has a value range of `1..100 kg` or `1..220 lb`, but `WorkoutParameters.weightPerCableKg` defaults to `0f`. Opening setup with a zero/default weight feeds `0` into a slider whose minimum is 1, which can violate Material slider value-range requirements and crash or render an invalid control.
- Suggested fix direction: Clamp the displayed slider value into `valueRange` before passing it to `ExpressiveSlider`, or allow `0f` in the range if zero is a valid unloaded state. Also initialize setup parameters to a valid minimum when an exercise is selected.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`

No issues found in the reviewed scope.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`

#### Finding 8
- Category: bug
- Severity: medium
- Line numbers: 133-135
- Description: Runtime rack behavior overrides are remembered only by `setReadyState.exerciseIndex`. If the loaded routine changes, the same index points to a different exercise, or saved overrides are refreshed while the screen stays composed, stale overrides from the previous exercise can remain applied in the rack card and active workout setup.
- Suggested fix direction: Key the state by a stable routine id plus exercise id/index, and synchronize it from `currentExercise.rackBehaviorOverrides` when the current exercise changes.

#### Finding 9
- Category: bug
- Severity: medium
- Line numbers: 204-210
- Description: Navigation to `ActiveWorkout` is keyed on the full `workoutState` object. If `WorkoutState.Active` is recreated as metrics/session fields update before this screen is popped, the effect can attempt repeated `navigate()` calls and stack duplicate ActiveWorkout destinations.
- Suggested fix direction: Key the effect on a coarse state class/session id and guard with a local `hasNavigatedToActive` flag, or perform this transition as a one-shot navigation event from the view model.

#### Finding 10
- Category: failure-point
- Severity: medium
- Line numbers: 247-256
- Description: The Start button is disabled unless `connectionState is Connected`, but its click handler calls `ensureConnection()` to connect before starting. In disconnected/scanning states the user cannot invoke the recovery path, so Set Ready can become a dead end unless another global connection UI is available.
- Suggested fix direction: Enable the button when a connection attempt is allowed, show a connecting/progress state while `ensureConnection()` runs, and handle `onFailed` with visible feedback rather than disabling the only start action.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetSummaryCard.kt`

#### Finding 11
- Category: bug
- Severity: medium
- Line numbers: 72-80, 87-97
- Description: Per-summary UI state is not reliably keyed. `loggedRpe` is remembered without a summary key, and `summaryKey` uses only duration, rep count, and total volume, which can collide for identical sets. RPE selection and auto-continue countdown state can therefore leak between summaries or fail to reset for a new set with the same aggregate values.
- Suggested fix direction: Use a stable summary/session/set identifier from `WorkoutState.SetSummary` as the key, and key `loggedRpe` and countdown state to that identifier. If no id exists, add one in the state model.

#### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: 952-970, 1023-1037
- Description: `ForceCurveSummaryCard` only checks for an empty curve. A one-point curve passes the guard, then divides by `forceData.size - 1` while drawing paths, producing NaN/Infinity coordinates that can break Canvas rendering.
- Suggested fix direction: Require at least two force samples before drawing the curve, or render a single-point/insufficient-data fallback card.

#### Finding 13
- Category: bug
- Severity: low
- Line numbers: 1043-1046
- Description: The sticking-point Y coordinate treats `stickingPointPct` as a direct list index (`pct.toInt()`), while the X coordinate treats it as a percent of ROM. For curves that do not have exactly 101 samples, the marker uses the wrong force sample and can appear at a mismatched height.
- Suggested fix direction: Convert percent to an index with `(pct / 100f * forceData.lastIndex).roundToInt().coerceIn(...)`, using the same normalized coordinate system for X and Y.

#### Finding 14
- Category: failure-point
- Severity: low
- Line numbers: 109-112
- Description: Weight debug logging runs directly during composition. Any recomposition of the summary emits another log line, which can spam production logs during countdowns, RPE changes, tagging, or theme/state updates.
- Suggested fix direction: Remove the debug log or gate it behind a debug build flag and a `LaunchedEffect(summaryId)` so it logs once per summary.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`

#### Finding 15
- Category: bug
- Severity: high
- Line numbers: 221-225
- Description: Navigation to `ActiveWorkout` is driven by `LaunchedEffect(workoutState)` and fires whenever the current state object is `WorkoutState.Active`. If Active state instances change during a live workout, this can push duplicate ActiveWorkout entries onto the nav stack.
- Suggested fix direction: Navigate from a one-shot view-model event or guard the effect with a remembered `hasNavigated` flag that resets only when the workout returns to Idle.

#### Finding 16
- Category: bug
- Severity: high
- Line numbers: 242-246
- Description: Entering Just Lift with any non-Idle/non-Active state immediately calls `prepareForJustLift()`. That includes Resting, SetSummary, Completed, Error, and BodyweightRepEntry states, so returning to this screen can discard summaries/rest timers or reset the session before the user sees or resolves the state.
- Suggested fix direction: Restrict automatic reset to specific stale setup states, or require an explicit user action. Preserve SetSummary/Resting/Completed states and route them to the appropriate UI instead of resetting them.

#### Finding 17
- Category: bug
- Severity: low
- Line numbers: 253-294
- Description: The parameter-sync effect converts `weightChangePerRep` based on `weightUnit`, but `weightUnit` is not in the effect key list. If the user changes units while Just Lift is composed, the stored `progressionRegressionKg` can remain converted from the old unit until another setting changes.
- Suggested fix direction: Include `weightUnit` in the `LaunchedEffect` keys or store the local progression value internally in kg and only convert for display.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt`

#### Finding 18
- Category: stub
- Severity: medium
- Line numbers: 102-109
- Description: Multi-muscle filtering is a placeholder implementation: it builds flows for all selected muscles but then uses only `flows.firstOrNull()`. Selecting multiple muscle chips does not combine or intersect filters as the UI implies; all chips after the first are ignored.
- Suggested fix direction: Combine the selected muscle flows and de-duplicate exercises, or filter a full exercise list locally by all selected muscles according to the intended OR/AND semantics.

#### Finding 19
- Category: error
- Severity: medium
- Line numbers: 156-172
- Description: Loading single-exercise defaults is launched without a catch block. If `getSingleExerciseDefaults()` throws, the composition coroutine can fail, the loading overlay is cleared in `finally`, and no snackbar/error state explains why the configuration sheet did not open.
- Suggested fix direction: Wrap the load in try-catch, verify the request id before applying error state, and show a snackbar while keeping the user on the exercise picker.

#### Finding 20
- Category: failure-point
- Severity: medium
- Line numbers: 363-385, 388
- Description: Starting a single-exercise workout dismisses the configuration sheet immediately, but failures only log (`loadRoutineAsync` false or `ensureConnection` failed). Users can be returned to the picker with no visible error and no configuration sheet to retry from.
- Suggested fix direction: Keep the sheet open or restore it on failure, expose a loading/error state, and show a snackbar/dialog for routine-load or connection failures.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt`

#### Finding 21
- Category: failure-point
- Severity: low
- Line numbers: 527-542
- Description: Routine progress uses `(currentExerciseIndex + 1).toFloat() / totalExercises` without clamping or validating the index. If a stale/out-of-range index reaches the rest screen, the progress indicator can receive a value greater than 1 or less than 0.
- Suggested fix direction: Clamp the progress fraction to `0f..1f` and render a safe fallback label if the index is outside `0 until totalExercises`.

#### Finding 22
- Category: bug
- Severity: low
- Line numbers: 618-621
- Description: `formatRestTime()` does not guard negative input. A delayed timer tick or state race that passes `-1` renders malformed text such as `0:-1` instead of a completed/rest-over state.
- Suggested fix direction: Coerce seconds to at least zero before formatting, or handle negative values as `0:00`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt`

#### Finding 23
- Category: bug
- Severity: high
- Line numbers: 57-75, 172-188
- Description: Exercise configuration state is keyed only by `exercise.exerciseName`. If the same exercise appears more than once in a cycle template, configurations overwrite each other and all occurrences share one mutable config. In the same day, the LazyColumn key `"${day.dayNumber}_${exercise.exerciseName}"` can also collide and crash composition.
- Suggested fix direction: Key configs and list items by a stable occurrence id, such as day number plus routine index/exercise index/template id, not by display name alone.

#### Finding 24
- Category: bug
- Severity: medium
- Line numbers: 57-75
- Description: Initial `exerciseConfigs` are created with `remember { ... }` and are not keyed to `template` or `oneRepMaxValues`. If a different template or updated 1RM map is shown in the same composition, the confirmation screen can display stale modes/weights from the previous inputs.
- Suggested fix direction: Key the remembered map by `template.id`/template identity and the relevant 1RM values, or rebuild state in a `LaunchedEffect` when those inputs change.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeSubSelectorDialog.kt`

No issues found in the reviewed scope.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineCompleteScreen.kt`

No issues found in the reviewed scope.

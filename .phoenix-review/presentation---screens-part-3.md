# Presentation - Screens Part 3 Review

Scope: auxiliary presentation screens for exercise editing, equipment rack, external integrations, cycles, and diagnostics.

Reviewed files: 19
Findings: 21
Severity breakdown: critical 0, high 3, medium 14, low 4

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 133-143
- Description: The exercise video list is remembered outside the `LaunchedEffect` and is never cleared when `exercise.exercise.id` changes, is null, or `getVideos()` throws. Because `preferredVideo` is derived from the previous `videos` value, opening the sheet for another exercise can briefly or permanently show the prior exercise's video after a failed or empty load.
- Suggested fix direction: Key the video state by exercise id or reset `videos = emptyList()` at the start of the `LaunchedEffect`; also clear on null id and failed loads before deriving `preferredVideo`.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 714-715
- Description: The save button calls `viewModel.onSave(onSave)` but does not hide the bottom sheet or disable itself while save work is in progress. If `onSave` performs asynchronous persistence or if the user double-taps, the same exercise configuration can be submitted multiple times.
- Suggested fix direction: Add an in-flight save flag in the sheet/viewmodel, disable the button during save, and ensure the sheet is dismissed exactly once after a successful save path.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditDialog.kt

### Finding 1
- Category: failure-point
- Severity: low
- Line numbers: 232-235
- Description: The weight text field stores only a `Float` and ignores non-parseable intermediate text. Users cannot temporarily clear the field, type a trailing decimal, or see invalid input feedback; the field snaps back to the last valid value and can accidentally save stale weight.
- Suggested fix direction: Keep a separate text state, validate it, show an error/supporting message for invalid input, and only convert to kg when saving a valid value.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EquipmentRackScreen.kt

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 249-254, 167
- Description: Tapping the delete icon immediately calls `viewModel.deleteRackItem(item.id)` with no confirmation, undo, or disabled state. This is a destructive action on user-created equipment and is easy to trigger accidentally because it sits next to edit and enabled controls.
- Suggested fix direction: Add a confirmation dialog or snackbar undo flow before permanently deleting a rack item.

### Finding 2
- Category: failure-point
- Severity: low
- Line numbers: 288-291, 415-425
- Description: The dialog accepts any finite value greater than or equal to zero, then converts and persists it. A zero-weight item may be useful for display-only accessories, but zero is also accepted for added-resistance and counterweight behaviors where it makes later workout load calculations misleading.
- Suggested fix direction: Validate weight according to behavior: require positive weight for added/counterweight behavior, and only allow zero for display-only items if that is intended.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalActivitiesScreen.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalIntegrationScreens.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 337-340
- Description: The playground preview is matched only by `programExternalId`. The route and program lookup include both provider and external id, but `state.playgroundPreview?.takeIf { it.programExternalId == externalProgramId }` can display stale preview data if another provider or previous run uses the same external id.
- Suggested fix direction: Include provider key in the preview state and require both `providerKey` and `externalProgramId` to match before displaying or committing a preview.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt

### Finding 1
- Category: failure-point
- Severity: high
- Line numbers: 792-817
- Description: API keys are entered in a plain `OutlinedTextField` with no password visual transformation. Hevy/Liftosaur tokens are visible on screen while being typed and may be exposed in screenshots, screen sharing, or shoulder-surfing.
- Suggested fix direction: Use `PasswordVisualTransformation` by default, add a deliberate show/hide toggle, and consider disabling autocorrect/suggestions for API key input.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 145-162
- Description: CSV import reads the selected URI content into memory without any visible size guard or error detail. Large or malformed files can block the UI coroutine path or fail with only a generic “Could not read file” snackbar.
- Suggested fix direction: Enforce a maximum import size before reading, surface parser/read errors separately, and consider streaming or background parsing for large CSV files.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/LinkAccountScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 288-290, 297-299
- Description: The Sync and Force Full Resync buttons are disabled only for `SyncState.Syncing`. `SyncState.SyncingWithProgress` is handled separately in the status UI, so users can start another sync/full-resync while a progress sync is already running.
- Suggested fix direction: Disable both actions for all active sync states, e.g. `syncState is SyncState.Syncing || syncState is SyncState.SyncingWithProgress`, or expose an `isInProgress` property on `SyncState`.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/MoveToGroupDialog.kt

### Finding 1
- Category: bug
- Severity: low
- Line numbers: 164-170
- Description: `GroupNameDialog` initializes `name` with `remember { mutableStateOf(initialName) }` without keying on `initialName`. If the dialog composable remains in the composition while switching from create to rename or between different groups, it can show and save the previous group's name.
- Suggested fix direction: Key the state with `remember(initialName)` or synchronize the local text state from `initialName` in a `LaunchedEffect(initialName)`.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/OneRepMaxInputScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 71-76, 248-263
- Description: Validation treats any parseable float as valid, including zero and negative values. The Continue button can be enabled by a negative entry, but save logic silently drops negative values and can return a partial or empty map, making the caller proceed as if valid 1RM data was supplied.
- Suggested fix direction: Require non-empty values to be positive finite numbers, show per-field validation for zero/negative input, and keep the returned map complete and explicit for every requested lift.

### Finding 2
- Category: bug
- Severity: high
- Line numbers: 52-65
- Description: Input text is initialized inside `remember(existingOneRepMaxValues, weightUnit)`. Callers in `TrainingCyclesScreen` populate `existingOneRepMaxValues` asynchronously through a `mutableStateMapOf`; the map object identity does not change as entries are added, so this initializer can run before values arrive and never prefill the loaded 1RM values.
- Suggested fix direction: Pass an immutable snapshot keyed by its contents, key the remember on `mainLiftNames` plus a version/content hash, or update `inputValues` in a `LaunchedEffect` when the existing values map contents change.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RecentActivityReplay.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt

### Finding 1
- Category: bug
- Severity: high
- Line numbers: 449-456
- Description: Saving a new routine replaces the routine id with a generated UUID but does not rewrite `Superset.routineId` values created while the draft routine id was `"new"`. New routines containing supersets can persist superset rows whose `routineId` still points at `"new"`, breaking later lookup, editing, or cleanup by real routine id.
- Suggested fix direction: Generate the new routine id before constructing/editing child superset data, or when saving a new routine copy every superset with `routineId = newRoutineId` before persistence.

### Finding 2
- Category: bug
- Severity: low
- Line numbers: 840-849
- Description: The superset rename dialog stores `newName` with an unkeyed `remember { mutableStateOf(superset.name) }`. If a different superset is selected while the dialog host remains composed, stale text from the prior superset can be displayed and saved.
- Suggested fix direction: Use `remember(superset.id)` or `remember(superset.id, superset.name)` for the local rename state.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineGroupHeader.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 299-310
- Description: `videoEntity` is remembered per pager page composition and is not cleared when `exercise.exercise.id` changes or video loading fails. Pager item reuse can show a previous exercise video for a new exercise until the new query succeeds, and it can remain stale after an exception.
- Suggested fix direction: Key `remember` by exercise id or set `videoEntity = null` at the start of the `LaunchedEffect`; also clear it in the null-id and catch paths.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 123-128, 231-238
- Description: If no routine is loaded, the screen simply returns with no UI and no recovery action. This avoids double-back problems, but a deep link, process restore, or stale navigation can leave users on a blank screen.
- Suggested fix direction: Render an explicit empty/error state with a back action or safe navigation callback instead of returning no content.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DailyRoutinesScreen.kt

No findings.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 63-79, 115-117
- Description: The Preview/Save button launches `saveCycle()` but remains enabled and has no in-progress indicator. Multiple taps can launch concurrent saves/navigations before `cycleEditorViewModel.saveCycle()` completes.
- Suggested fix direction: Bind button enabled/content to `uiState.isSaving`, ignore clicks while saving, and route navigation only once per successful save.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleReviewScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 306-328
- Description: `formatSetsReps()` drops null AMRAP entries when any fixed rep values exist. For a mixed set list such as `[10, 10, null]`, it displays `3x10`, hiding that one set is AMRAP.
- Suggested fix direction: Detect mixed null/non-null reps and display an explicit mixed label such as `2x10 + AMRAP` or per-set summaries.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 515-542
- Description: Existing 1RM values are loaded asynchronously into `mutableStateMapOf` instances and immediately passed to `OneRepMaxInputScreen`. Combined with that screen's remembered initializer, the first render often occurs with empty maps and the later loaded values do not prefill the input fields.
- Suggested fix direction: Load existing 1RM/PR values before showing the input screen, or pass immutable snapshots and key the input state by the snapshot contents so fields update when data arrives.

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 226-230
- Description: Progress refresh runs an unconditional `while (true)` loop every minute and calls `checkAndAutoAdvance()` for the active cycle. If repository calls repeatedly fail, this loop has no exception handling and the refresh coroutine dies silently for the life of the composition.
- Suggested fix direction: Wrap each refresh iteration in try/catch, show/log failures, and continue after delay so transient storage errors do not permanently stop progress refresh.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DiagnosticsScreen.kt

### Finding 1
- Category: bug
- Severity: medium
- Line numbers: 127, 274-282
- Description: `FaultsSection` renders an empty details card when `uiState.faults` is empty. The file imports `diagnostics_none_reported`, but the faults section never uses it, so a healthy diagnostic packet shows a blank Faults area instead of an explicit “none reported” message.
- Suggested fix direction: In `FaultsSection`, check `faults.isEmpty()` and render `diagnostics_none_reported` with secondary text styling before iterating faults.

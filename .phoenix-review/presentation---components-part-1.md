# Presentation - Components Part 1 Review

Task: `t_7665941b`
Scope: UI components for input handling, state management, and edge cases.

## Review status

Reviewed the exact assigned paths in the current checkout. Five assigned files exist and were read in full:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConfirmEditTextField.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CustomExerciseActions.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightRecommendationCard.kt`

Eleven assigned files are missing at the requested `commonMain` paths. I verified the exact paths, searched the repository for matching filenames/symbols, fetched/pruned remotes, and checked local refs for the exact paths. `BlePermissionHandler` and `OptionalPermissionsHandler` have platform-specific Android/iOS files, but no assigned common file; the other missing component names were not present in local refs.

No code was modified. This report documents code-level findings for the existing assigned files and inventory failure points for missing assigned files.

## Summary

- Files assigned: 16
- Assigned files found and reviewed: 5
- Assigned files missing: 11
- Findings: 13
- Severity breakdown:
  - Critical: 0
  - High: 0
  - Medium: 12
  - Low: 1

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BlePermissionHandler.kt`

#### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned commonMain BLE permission handler does not exist at the requested path. Repository/ref search found platform-specific `BlePermissionHandler.android.kt` and `BlePermissionHandler.ios.kt`, but no common file matching the assigned target. The requested common API/state handling for BLE permission flow cannot be inspected from this file.
- Suggested fix direction: Update the review manifest to target the platform-specific files if the component is intentionally platform-only, or restore/add the common expect/API file if shared call sites are expected to depend on it.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/BulkWeightAdjustBar.kt`

#### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned bulk weight adjustment bar file does not exist at the requested path, and repository/ref search found no matching `BulkWeightAdjustBar` file or symbol. Bulk weight input handling, bounds, and state transitions for this target cannot be reviewed.
- Suggested fix direction: Update the manifest to the active bulk-adjustment component if it was renamed or consolidated, or restore this component with explicit tests for min/max weights, unit conversion, and repeated adjustment actions.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.kt`

#### Finding 3
- Category: failure-point
- Severity: medium
- Line numbers: 28-35, 52-68
- Description: The public `CompactNumberPicker` expect API accepts arbitrary `range` and `step` values without documenting or enforcing invariants. The shared formatter only guards `step <= 0f`; it does not handle non-finite values such as `Float.NaN`, where `decimalPlacesForStep()` reaches `scaled.roundToInt()` and can throw. Platform actuals also depend on `step`/`range` to generate picker values, so invalid values can become crashes, empty pickers, or divergent Android/iOS behavior.
- Suggested fix direction: Define and enforce a shared validation contract before values reach platform actuals: require finite `value`, finite ordered `range`, and finite positive `step`; clamp or reject invalid inputs consistently, and add common tests for `NaN`, infinities, zero/negative steps, and inverted ranges.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConfirmEditTextField.kt`

#### Finding 4
- Category: bug
- Severity: low
- Line numbers: 37-40, 77-80, 153-159
- Description: TV confirm handling does not know about the caller's original `readOnly` flag. `onConfirmKey()` can set `isEditing = true` and consume Enter/DirectionCenter even when the field was intentionally read-only; the launched effect can then request focus and show the keyboard while `OutlinedTextField` remains read-only through `effectiveReadOnly(readOnly)`. This can make read-only fields appear to enter an edit flow and block parent key handling.
- Suggested fix direction: Pass `readOnly`/`enabled` into the confirm decision or guard the key handler before calling `onConfirmKey()`. Add a state test that `originalReadOnly = true` does not enter edit mode or consume confirm keys in TV remote mode.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CustomExerciseActions.kt`

No findings in this assigned file. The helpers correctly preserve the captured edit/delete id after caller UI state is cleared, and update paths force `isCustom = true` before repository update.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EccentricPhaseProgress.kt`

#### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned eccentric phase progress component does not exist at the requested path, and repository/ref search found no `EccentricPhaseProgress` file or symbol. Eccentric phase progress calculation, animation state, and boundary handling cannot be reviewed for this target.
- Suggested fix direction: Update the manifest to the current eccentric/progress UI if renamed or consolidated, or restore this component with validation for phase totals, zero/negative durations, and progress clamping.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EquipmentRackDisplay.kt`

#### Finding 6
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned equipment rack display file does not exist at the requested path, and repository/ref search found no `EquipmentRackDisplay` file or symbol. Rack rendering, empty rack handling, and selection edge cases cannot be inspected from the requested target.
- Suggested fix direction: Point the manifest at the active equipment rack UI implementation if it moved, or restore this display component with tests/previews for empty, partially configured, and invalid rack states.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.kt`

No findings in this assigned common expect file. It is a thin expect declaration, and the reviewed signature matches the discovered Android/iOS actual declarations.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackSettingsCard.kt`

#### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned haptic feedback settings card does not exist at the requested path, and repository/ref search found no matching file or symbol. Settings toggles, persistence wiring, and disabled/permission states cannot be reviewed for this target.
- Suggested fix direction: Update the manifest to the current haptic settings UI if it was moved, or restore this component with tests for toggle state synchronization and unavailable-feedback devices.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/OptionalPermissionsHandler.kt`

#### Finding 8
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned commonMain optional permissions handler does not exist at the requested path. Repository/ref search found platform-specific `OptionalPermissionsHandler.android.kt` and `OptionalPermissionsHandler.ios.kt`, but no common file matching the assigned target. Shared permission state mapping and optional-permission prompting cannot be reviewed from this file.
- Suggested fix direction: Update the review manifest to target the Android/iOS permission handlers if the implementation is intentionally platform-specific, or restore/add a common expect/API layer if shared UI call sites should be reviewed.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileAvatar.kt`

#### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned profile avatar file does not exist at the requested path. A `ProfileAvatar` composable symbol exists inside `ProfileSpeedDial.kt`, but no standalone assigned file exists in the current checkout or local refs. Avatar image fallback, active-state rendering, and click handling cannot be reviewed under the requested file target.
- Suggested fix direction: Update the manifest to `ProfileSpeedDial.kt` if the avatar was intentionally inlined there, or split/restore `ProfileAvatar.kt` if it is intended to be a standalone component with dedicated review and tests.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RepQualityRing.kt`

#### Finding 10
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned rep quality ring component does not exist at the requested path, and repository/ref search found no `RepQualityRing` file or symbol. Score clamping, null/unknown quality rendering, and progress-ring drawing behavior cannot be inspected for this target.
- Suggested fix direction: Update the manifest to the active rep-quality UI component if renamed, or restore this ring component with validation for out-of-range quality scores and missing metrics.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightIncrementStepper.kt`

#### Finding 11
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned weight increment stepper file does not exist at the requested path, and repository/ref search found no `WeightIncrementStepper` file or symbol. Increment/decrement behavior, step-size validation, unit conversion, and min/max clamping cannot be reviewed from this target.
- Suggested fix direction: Update the manifest to the active weight stepper/control file if renamed, or restore this component with tests for repeated taps, invalid increments, kg/lb conversion, and boundary clamping.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightInput.kt`

#### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned weight input component does not exist at the requested path, and repository/ref search found no `WeightInput` file or symbol. Numeric parsing, empty input handling, decimal separator behavior, and validation feedback cannot be reviewed for this target.
- Suggested fix direction: Update the manifest to the current weight input implementation if it was consolidated into another component, or restore this file with locale-aware parsing and tests for blank, malformed, negative, and over-limit values.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightRecommendationCard.kt`

No findings in this assigned file. The component renders the supplied recommendation data without local state, does not mutate input, and delegates apply/dismiss actions to callers.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightUnitPicker.kt`

#### Finding 13
- Category: failure-point
- Severity: medium
- Line numbers: file missing
- Description: The assigned weight unit picker file does not exist at the requested path, and repository/ref search found no `WeightUnitPicker` file or symbol. Unit selection state, kg/lb conversion triggers, and persistence/error handling cannot be reviewed from this target.
- Suggested fix direction: Update the manifest to the active unit-selection UI if renamed or moved, or restore this component with tests for changing units, preserving weight values, and handling unsupported/unknown unit states.

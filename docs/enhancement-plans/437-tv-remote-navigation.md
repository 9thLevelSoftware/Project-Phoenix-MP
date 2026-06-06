# Issue #437 — TV Remote Navigation Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. Keep phone/tablet touch behavior unchanged.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/437
Branch: `feat/437-tv-remote-navigation`
PR scope: D-pad/TV remote input behavior for sliders and text fields.

## Goal

Improve Android TV / remote navigation:

1. Sliders should only change values via left/right. Up/down should move focus to the next/previous element.
2. Text inputs should not open the on-screen keyboard merely when focused; keyboard opens only after confirm/OK/Enter.

## Current state verified in repo

Relevant files:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExpressiveComponents.kt`
  - `ExpressiveSlider` wraps Material3 `Slider`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SliderWithButtons.kt`
  - many weight/reps sliders use this component.
- Raw slider call sites include:
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt`
  - `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseConfigModal.kt`
- Text inputs use plain `OutlinedTextField` in many screens, including:
  - `ConnectionLogsScreen.kt`
  - `AuthScreen.kt`
  - `MoveToGroupDialog.kt`
  - `ExercisesTab.kt`
  - `JustLiftScreen.kt`
  - `SettingsTab.kt`
  - `BulkWeightAdjustDialog.kt`
  - `CreateExerciseDialog.kt`

## Architecture

Create reusable input-mode utilities. Avoid one-off key hacks in individual screens.

New concepts:

- `PlatformInputMode` / `isTvInputMode`: indicates D-pad/TV remote behavior should apply.
- `Modifier.tvSliderKeys(...)`: handles left/right only; lets up/down traverse focus.
- `ConfirmEditTextField`: wraps `OutlinedTextField` so focus and edit mode are separate in TV mode.

Phone/tablet behavior should remain normal.

## Product behavior for v1

- TV mode:
  - Slider focused + Left: decrement.
  - Slider focused + Right: increment.
  - Slider focused + Up/Down: focus moves, value unchanged.
  - Text field focused: no keyboard yet.
  - OK/Enter: enters edit mode and shows keyboard.
  - Back/Escape: exits edit mode or dismisses keyboard.
- Non-TV mode:
  - existing touch and keyboard behavior unchanged.

## Tasks

### Task 1 — Add TV/D-pad mode detection

Objective: centralize platform input-mode detection.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformInputMode.kt`
- Create/modify actuals if using expect/actual:
  - `shared/src/androidMain/.../PlatformInputMode.android.kt`
  - `shared/src/iosMain/.../PlatformInputMode.ios.kt`

Steps:

1. Write tests for default/common behavior if feasible.
2. On Android, detect TV using `uiMode` television type and/or navigation type.
3. On iOS, default false unless tvOS is actually targeted.
4. Expose a composable helper, e.g. `@Composable fun isTvRemoteInputMode(): Boolean`.

Alternative: if expect/actual is too much, implement as a common composable using `LocalConfiguration` on Android-only call sites and pass boolean into common components.

### Task 2 — Add slider key modifier tests

Objective: prove up/down do not mutate slider values.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/DpadSliderSemantics.kt`
- Test: `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/components/SliderKeyHandlingTest.kt`

Test cases:

- left decrements by step.
- right increments by step.
- up does not call `onValueChange`.
- down does not call `onValueChange`.
- min/max clamps.

Run:

```bash
./gradlew :shared:allTests --tests '*SliderKeyHandling*'
```

Expected initial failure: modifier/component does not exist.

### Task 3 — Implement D-pad slider semantics

Objective: make slider key behavior reusable.

Files:

- Modify/create: `DpadSliderSemantics.kt`
- Modify: `ExpressiveComponents.kt`
- Modify: `SliderWithButtons.kt` only if necessary.

Implementation details:

- Use `Modifier.onPreviewKeyEvent` or `Modifier.onKeyEvent` before Material3 Slider consumes keys.
- On `KeyEventType.KeyDown`:
  - `Key.DirectionLeft` -> decrement and consume.
  - `Key.DirectionRight` -> increment and consume.
  - `Key.DirectionUp` / `Key.DirectionDown` -> return false.
- Calculate step from explicit step value when available. If only Material3 `steps` is available, derive step from range.
- Clamp to `valueRange`.
- Preserve drag/touch behavior.

### Task 4 — Patch raw slider call sites

Objective: ensure all workout setup sliders follow the same TV behavior.

Files:

- Modify: `SetReadyScreen.kt`
- Modify: `RestTimerCard.kt`
- Modify: `RoutineOverviewScreen.kt`
- Modify: `ExerciseConfigModal.kt`

Steps:

1. Search for raw `Slider(` calls not going through `ExpressiveSlider`.
2. Add the D-pad modifier and focus behavior.
3. Prefer refactoring raw calls to `ExpressiveSlider` if visual behavior remains unchanged.
4. Run compile.

### Task 5 — Add confirm-to-edit text field component

Objective: separate focus from keyboard opening in TV mode.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ConfirmEditTextField.kt`
- Optional iOS actual bridge if keyboard controller is insufficient:
  - `shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/ConfirmEditTextField.ios.kt`

Behavior:

- Parameters mirror common `OutlinedTextField` needs.
- In TV mode:
  - render focusable field container.
  - do not call `keyboardController.show()` on focus.
  - on Enter/OK, set editing=true, request focus, show keyboard.
  - on Done/Back, set editing=false.
- In non-TV mode:
  - behave like normal `OutlinedTextField`.

Tests:

- focus alone does not invoke edit mode in TV mode.
- Enter invokes edit mode callback.

### Task 6 — Replace high-impact text fields

Objective: fix the reported TV workflow without over-broad regressions.

Initial call sites to convert:

- `ExercisesTab.kt` search field.
- `MoveToGroupDialog.kt` group name field.
- `JustLiftScreen.kt` workout name/input field.
- `BulkWeightAdjustDialog.kt` custom percent/absolute inputs.
- `CreateExerciseDialog.kt` exercise name/search fields.
- Routine/profile name fields in `SettingsTab.kt` where focus traversal is painful.

Leave auth fields alone for v1 unless testing shows TV login flow needs them. Auth fields often should open keyboard eagerly.

### Task 7 — Focus traversal polish

Objective: make remote traversal predictable.

Files:

- Slider-containing screens/components from previous tasks.

Steps:

1. Add `Modifier.focusGroup()` around logical setup cards.
2. Ensure buttons around sliders still receive focus in a sensible order.
3. Verify no focus trap is introduced.

## Verification commands

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*SliderKeyHandling*' --tests '*ConfirmEdit*'
./gradlew :androidApp:assembleDebug
```

## Manual QA

Android TV / Shield / emulator:

1. Navigate to routine setup with a remote.
2. Focus a weight slider.
3. Press Down: focus moves to next control; value unchanged.
4. Press Up: focus moves to previous control; value unchanged.
5. Press Left/Right: value changes by one step.
6. Focus a text field: keyboard remains hidden.
7. Press OK/Enter: keyboard appears and cursor edits field.
8. Press Back: keyboard closes and focus traversal resumes.

Phone/tablet:

1. Touch sliders and text fields normally.
2. Verify no extra OK step is required for normal touch editing.

## Risks

- Modifier order matters; Material3 Slider may consume key events before custom handler unless using preview handler.
- TV mode detection can be imperfect. Provide a fallback developer setting only if detection is unreliable.
- Text input behavior varies by Android TV keyboard/IME.
- iOS Compose keyboard control may be no-op; keep changes Android-TV focused if needed.

## Acceptance criteria

- On TV/D-pad, up/down navigation past sliders does not alter slider values.
- On TV/D-pad, left/right adjusts sliders.
- On TV/D-pad, keyboard opens for text inputs only after OK/Enter.
- Phone/tablet behavior remains unchanged.
- No focus traps in main workout setup screens.

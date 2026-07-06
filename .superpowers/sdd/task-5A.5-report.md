# Task 5A.5 Report — Structural Leftovers

## CycleEditor Dirty-Field Choice + Load-Path Trace

### Dirty fields snapshotted
`cycleName`, `description`, `items` (List<CycleItem>), `progression` (CycleProgression?).

### Excluded (UI-only) fields
`showAddDaySheet`, `showProgressionSheet`, `editingItemIndex`, `recentRoutineIds`, `currentRotation`,
`isLoading`, `isSaving`, `saveError`, `lastDeletedItem`, `cycleId`, `originalProfileId`.

`currentRotation` is system-managed (persisted rotation count from `CycleProgress`); the user
cannot change it in the editor, so divergence cannot be user-triggered.

### Load-path trace
1. `CycleEditorScreen` is composed → `uiState.isLoading = true` (ViewModel initial state).
2. `LaunchedEffect(cycleId)` fires → calls `cycleEditorViewModel.initialize(cycleId)`.
3. ViewModel coroutine runs; for an existing cycle it calls `repository.getCycleById()`,
   `getCycleProgress()`, `getCycleProgression()`, `getCycleItems()`.
4. On success: `_uiState.update { state.copy(cycleName = …, description = …, items = …, progression = …, isLoading = false) }`.
5. `LaunchedEffect(uiState.isLoading)` in the Screen detects `!isLoading && !hasSnapshot`;
   captures the four content fields into snapshot vars and sets `hasSnapshot = true`.
6. `isDirty` becomes true only when a field subsequently diverges from the snapshot.

Result: existing cycles open with `isDirty = false` (snapshot captures what was just loaded);
new cycles open with `isDirty = false` (snapshot captures the default empty/fresh state after
initialization completes).

---

## ExercisePicker: Mini Height Reasoning

`MiniExercisePickerDialog` previously passed `fullScreen = true` to suppress the title row inside
`ExercisePickerContent`. `fullScreen` also controls the height modifier:
```kotlin
.then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.9f))
```

When `fullScreen = true` the content tried to fill 100% of the host height. Inside an AlertDialog,
the host height is already constrained by the dialog chrome plus the `heightIn(max = 520.dp)` Box
wrapper in MiniExercisePickerDialog, so the effective cap was the AlertDialog's own height limit
(which on most devices is well below 520 dp). The `fillMaxHeight()` inside a 520 dp-bounded Box
is equivalent to `fillMaxHeight()` clipped by 520 dp — identical rendering to `fillMaxHeight(0.9f)`
clipped by the same bound, because 90% of 520 dp is 468 dp and the dialog itself is typically
shorter than that.

After the change (`showTitle = false`, `fullScreen = false`): `ExercisePickerContent` renders with
`fillMaxHeight(0.9f)`, but it is still bounded by the AlertDialog's 520 dp `heightIn` cap. The
rendered height is unchanged in practice, because the AlertDialog height constraint wins before
either `fillMaxHeight(0.9f)` or `fillMaxHeight(1.0f)` would produce a visible difference.

The semantic hazard (`fullScreen = true` meaning "suppress title") is removed. The visual behavior
of Mini is identical.

---

## isDragging Wiring Outcome

`isDragging` is threaded from `RoutineEditorScreen`'s `ReorderableItem { isDragging -> }` lambda
directly to `SupersetContainer`. This is a one-hop path: `isDragging` is in scope at the
`SupersetContainer(...)` call site. No documentation gap.

---

## Mode-Selector Won't-Fix

Finding **workout-setup-9** ("Three different UI patterns for workout-mode selection in one area
with no single canonical component") is CLOSED WON'T-FIX.

Rationale (verbatim per user decision 2026-07-06):
> User decision 2026-07-06: the three patterns are contextually appropriate — compact pill for
> 5-option workout setup, segmented row where ≤4 options fit, dropdown retired naturally when its
> host sheet is next redesigned. No code change.

---

## Integrations Chip Clipping

No code change required. `IntegrationCard`'s badge row already carries
`.horizontalScroll(rememberScrollState())` (IntegrationsScreen.kt line ~784 in the pre-task
codebase). The brief's `jq` query found no chip/clipping finding for IntegrationsScreen in the
audit JSON, confirming the fix was either pre-existing or applied in an earlier phase. Documented
here for traceability.

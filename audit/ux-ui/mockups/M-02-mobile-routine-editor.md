# M-02 — Mobile RoutineEditor Empty State + Unsaved-Changes Guard

**Covers findings:** G-009 (CRITICAL — combines `01-F-002` + `01-F-003`), G-140 (HIGH — `06-F-013`), `01-F-020` (MEDIUM — UNDO parity)
**Surface:** Mobile only (Compose Multiplatform)
**Files affected:**
- `presentation/screen/RoutineEditorScreen.kt` (primary — empty state, save validation, BackHandler, snackbar host)
- `presentation/screen/EnhancedMainScreen.kt:308-315` (top-bar back wiring via `setTopBarBackAction`)
- `presentation/components/RoutineTemplatePicker.kt` (NEW — modal sheet with starter templates)
- `domain/model/RoutineTemplates.kt` (NEW — static template catalog, mirrors existing `CycleTemplates` pattern)
- `presentation/viewmodel/RoutineEditorViewModel.kt` (UNDO state, `initialRoutine` snapshot for diff)
- `composeResources/values/strings.xml` (new copy keys: `empty_routine_*`, `discard_changes_*`, `template_picker_*`, `routine_name_required`, `exercises_required`)

**Effort:** ~1.5 dev days
- Empty-state copy + layout: 2 hr
- Template picker (5 templates, hardcoded catalog): 4 hr
- Save validation + disabled state + helper text: 1 hr
- Conflict-name dialog: 1 hr
- BackHandler + Discard dialog + top-bar back wiring: 2 hr
- UNDO snackbar parity for 3 destructive paths: 2 hr
- Strings, accessibility audit, manual QA: 2 hr

---

## Problem statement

The RoutineEditor — the primary creation surface for the app's most investment-heavy task — has three independent failure modes that compound. First (G-009a / `01-F-002`), the Save button is always enabled and falls back to `name = "Unnamed Routine"` when blank, so users can save phantom empty routines that fail silently when launched (`RoutineOverviewScreen.kt:113` returns early on null routine). Second (G-009b / `01-F-003`), neither the system back button nor the top-bar back arrow registers a `BackHandler` or `setTopBarBackAction` interceptor, so a single accidental tap nukes 5+ minutes of editing — exercises, supersets, set configuration, reordering — with zero recovery path. Third (G-140 / `06-F-013`), the screen renders blank when `state.exercises.isEmpty()` — the only on-screen guidance is a single grey line of placeholder copy ("Tap + to add your first exercise") with no template suggestions, no value-prop framing, and no secondary path for users who don't yet know what exercises to pick.

These three together produce a flow where: (a) first-time users hit a blank screen with no guidance and abandon, (b) returning users can lose 5 minutes to an errant back-swipe, and (c) the app accumulates unrecoverable phantom routines that surface as silent failures on launch. The fix is a single coherent redesign of the empty/edit/save lifecycle, not three independent patches. Additionally, the UNDO snackbar parity gap (`01-F-020`) means destructive actions inside this same screen — single-exercise delete, superset delete, batch delete — commit immediately while the sibling `CycleEditorScreen` correctly offers UNDO. Consolidating these in one mockup is appropriate because they share state machinery (`hasUnsavedChanges`, `SnackbarHostState`) and the same screen-level scaffold.

---

## Current-state evidence

- **Screenshot `_audit/screenshots/mobile/36-new-routine.png`** — confirms blank canvas: title `Edit / Project Phoenix`, name field `New Routine` (default placeholder), grey body copy `Tap + to add your first exercise`, orange `Add Exercise` FAB bottom-right, blue `Connect` chip top-right.
- **Screenshot `_audit/screenshots/mobile/30-routines.png`** — confirms `Issue 389 Demo Routine` dev fixture leaks into release surface (G-141 / `06-F-015`); referenced for context but addressed in a separate finding.
- **Screenshot `_audit/screenshots/mobile/35-exercise-kebab.png`** — confirms kebab/dropdown affordance on routine rows; the destructive paths from this menu are where UNDO is missing.
- **Code: `RoutineEditorScreen.kt:474-488`** — Save button always enabled, falls back to `"Unnamed Routine"` on blank.
- **Code: `RoutineEditorScreen.kt:171-192`** — only `LaunchedEffect(routineId)` for load; no `BackHandler {}`, no `setTopBarBackAction { }` registration.
- **Code: `CycleEditorScreen.kt:191-213`** — UNDO snackbar pattern that should be mirrored here.
- **Code: `RoutineEditorScreen.kt:710-723`** — current immediate-delete path with no UNDO.

---

## Proposed design

### A. Empty state with template picker

When `state.exercises.isEmpty()`, replace the current single-line placeholder with a structured empty state. The flame icon uses `MaterialTheme.colorScheme.primary` (Phoenix Ember — assumed canonical post-G-016 fix to `#FF6B35`), heading uses `headlineSmall`, body uses `bodyMedium`. Two CTAs are stacked vertically on Compact width, placed inside an outlined card so they're visually grouped and distinct from the FAB.

```
┌────────────────────────────────────────────────┐
│  ←   Edit                          [Connect]   │
│      Project Phoenix                           │
│                                                │
│  ┌─────────────────────────────────┐  ┌──────┐ │
│  │ New Routine                     │  │ Save │ │
│  └─────────────────────────────────┘  └──────┘ │
│  Add a name and at least one exercise to save. │   ← inline helper (grey, body-small)
│                                                │
│                                                │
│              ╔═══════════╗                     │
│              ║   ◢◣◢◣    ║   ← phoenix flame   │
│              ║  ◢◤   ◥◣  ║      icon (48dp)    │
│              ║ ◢◤  ◢◣ ◥◣ ║      ColorScheme    │
│              ║◢◤   ◥◣  ◥◣║      .primary       │
│              ╚═══════════╝                     │
│                                                │
│           Build your routine                   │   ← headlineSmall, onSurface
│                                                │
│   Routines link multiple exercises into a      │   ← bodyMedium,
│   session. Tap Add exercise to start, or pick  │     onSurfaceVariant,
│   a template to skip the setup.                │     center-aligned, max 320dp width
│                                                │
│   ┌────────────────────────────────────────┐   │
│   │            +  Add exercise             │   │   ← FilledTonalButton
│   └────────────────────────────────────────┘   │     primary container,
│                                                │     56dp height (touch target)
│   ┌────────────────────────────────────────┐   │
│   │       ⚡  Start from template          │   │   ← OutlinedButton
│   └────────────────────────────────────────┘   │     56dp height,
│                                                │     border 1dp outline
│                                                │
│                                                │
│                                                │
└────────────────────────────────────────────────┘
```

**Behavioral notes:**
- The bottom-right `Add Exercise` FAB is **suppressed while empty state is showing** to avoid two competing primary CTAs. FAB returns when `exercises.isNotEmpty()`.
- Once the user adds at least one exercise (via either path), the empty state is replaced by the existing `LazyColumn` of `RoutineExerciseRow` items.
- Empty state is also re-shown if all exercises are deleted back to zero — provides a forgiving recovery path.

**Template picker (modal bottom sheet, opens on `Start from template`):**

```
┌────────────────────────────────────────────────┐
│  Pick a starter template                       │   ← titleLarge
│  Tap one to prefill your routine. You can      │     bodySmall onSurfaceVariant
│  edit anything afterward.                      │
│  ────────────────────────────────────────────  │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │ 💪  Push day                           │   │
│  │     Bench, shoulder press, triceps     │   │   ← 6 exercises, ~35 min
│  │     6 exercises · ~35 min          ›   │   │
│  └────────────────────────────────────────┘   │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │ 🪢  Pull day                           │   │
│  │     Row, lat pulldown, biceps          │   │   ← 6 exercises, ~35 min
│  │     6 exercises · ~35 min          ›   │   │
│  └────────────────────────────────────────┘   │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │ 🔥  Full body 30-min                   │   │
│  │     Squat, row, press — quick session  │   │   ← 5 exercises, ~30 min
│  │     5 exercises · ~30 min          ›   │   │
│  └────────────────────────────────────────┘   │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │ 🏋  Upper-body strength                │   │
│  │     Compound lifts, low rep            │   │   ← 5 exercises, ~45 min
│  │     5 exercises · ~45 min          ›   │   │
│  └────────────────────────────────────────┘   │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │ 🧘  Mobility recovery                  │   │
│  │     Light load, range of motion        │   │   ← 4 exercises, ~20 min
│  │     4 exercises · ~20 min          ›   │   │
│  └────────────────────────────────────────┘   │
│                                                │
│  ┌────────────────────────────────────────┐   │
│  │            Cancel                      │   │   ← TextButton
│  └────────────────────────────────────────┘   │
└────────────────────────────────────────────────┘
```

**Template list spec (hardcoded catalog, mirrors `CycleTemplates` pattern):**

| ID | Name | Exercises | Est. duration | Phoenix tier |
|----|------|-----------|---------------|--------------|
| `push_day` | Push day | Bench Press, Shoulder Press, Incline Press, Triceps Extension, Lateral Raise, Triceps Pushdown | ~35 min | FREE |
| `pull_day` | Pull day | Row, Lat Pulldown, Face Pull, Biceps Curl, Reverse Fly, Hammer Curl | ~35 min | FREE |
| `full_body_30` | Full body 30-min | Squat, Row, Shoulder Press, Romanian Deadlift, Plank | ~30 min | FREE |
| `upper_strength` | Upper-body strength | Bench Press, Row, Overhead Press, Pull-up, Dip | ~45 min | FREE |
| `mobility_recovery` | Mobility recovery | Light Squat, Reverse Fly, Banded Pull-apart, Hip Hinge | ~20 min | FREE |

Templates write to `state` as if the user had added each exercise via the picker. Default per-cable weight = `0.0` (forces the user to set it). Default mode = `OldSchool`. Default sets = 3, reps = 10.

**Tap-target sizes (WCAG 2.2 SC 2.5.8):**
- Both CTA buttons in empty state: `56dp` height × full container width minus `24dp` horizontal padding → ≥48dp×48dp minimum.
- Template rows: `72dp` minimum height (icon + 2-line text + chevron).
- Cancel TextButton: `56dp` height × full width.

---

### B. Save validation

The Save button is **disabled by default** and only enables when both conditions hold: `routineName.isNotBlank() && exercises.isNotEmpty()`. Inline helper text below the name-field row explains the requirement. While saving (network I/O via `viewModel.saveRoutine()`), the button shows a spinner.

**Save button states:**

```
DISABLED — no name (blank field, user has cleared placeholder):
┌─────────────────────────────────┐  ┌──────┐
│                                 │  │ Save │   ← onSurface @ 38% alpha
└─────────────────────────────────┘  └──────┘     no elevation
Add a name and at least one exercise to save.    ← bodySmall, error 60% alpha


DISABLED — name OK but exercises empty:
┌─────────────────────────────────┐  ┌──────┐
│ Push Day                        │  │ Save │   ← same disabled style
└─────────────────────────────────┘  └──────┘
Add at least one exercise to save.               ← bodySmall, error 60% alpha


ENABLED — both conditions met:
┌─────────────────────────────────┐  ┌──────┐
│ Push Day                        │  │ Save │   ← primary container,
└─────────────────────────────────┘  └──────┘     onPrimary text


SAVING — async commit in flight:
┌─────────────────────────────────┐  ┌──────┐
│ Push Day                  [---] │  │  ⟳   │   ← CircularProgressIndicator
└─────────────────────────────────┘  └──────┘     16dp, onPrimary tint
                                                  button is non-interactive
```

**Conflict-name dialog (when user attempts to save with a name matching an existing routine):**

```
┌──────────────────────────────────────────────┐
│  Routine already exists                      │   ← titleLarge
│                                              │
│  A routine called "Push Day" already exists. │   ← bodyMedium
│  What would you like to do?                  │
│                                              │
│                                              │
│              ┌───────────────┐               │
│              │   Overwrite   │               │   ← FilledTonalButton, error
│              └───────────────┘                 │
│                                              │
│              ┌──────────────────┐            │
│              │ Save as Push Day 2│            │   ← FilledTonalButton, primary
│              └──────────────────┘            │
│                                              │
│              ┌───────────────┐               │
│              │    Cancel     │               │   ← TextButton
│              └───────────────┘                 │
└──────────────────────────────────────────────┘
```

- "Overwrite" replaces the existing routine (last-write-wins; emits the same updated_at sync record).
- "Save as Push Day 2" appends an integer suffix and saves as new routine. Increments to `Push Day 3` if `Push Day 2` also exists.
- "Cancel" returns to the editor with the conflict unresolved (user must change the name).

---

### C. Unsaved-changes back-guard

Track `hasUnsavedChanges = state.routine != initialRoutine` where `initialRoutine` is captured during the existing `LaunchedEffect(routineId)` (line 176) at the moment the routine is loaded (or set to a fresh blank for `routineId == "new"`).

**BackHandler pseudocode (added near line 192):**

```kotlin
val hasUnsavedChanges by remember(state.routine, initialRoutine) {
    derivedStateOf { state.routine != initialRoutine }
}
var showDiscardDialog by remember { mutableStateOf(false) }

fun attemptExit() {
    if (hasUnsavedChanges) showDiscardDialog = true
    else navController.popBackStack()
}

BackHandler(enabled = true) { attemptExit() }

LaunchedEffect(Unit) {
    setTopBarBackAction { attemptExit() }   // wired via EnhancedMainScreen.kt
}

if (showDiscardDialog) {
    DiscardChangesDialog(
        onKeepEditing = { showDiscardDialog = false },
        onDiscard = {
            showDiscardDialog = false
            navController.popBackStack()
        }
    )
}
```

**Discard dialog ASCII:**

```
┌────────────────────────────────────────────┐
│  Discard changes?                          │   ← titleLarge, onSurface
│                                            │
│  Your edits to this routine will be lost.  │   ← bodyMedium, onSurfaceVariant
│                                            │
│                                            │
│                                            │
│         ┌───────────────────┐              │
│         │   Keep editing    │              │   ← FilledTonalButton, primary
│         └───────────────────┘              │
│                                            │
│         ┌───────────────────┐              │
│         │     Discard       │              │   ← TextButton, error text
│         └───────────────────┘              │
│                                            │
└────────────────────────────────────────────┘
```

- "Keep editing" is the default focus / primary affordance (positive action, brand color).
- "Discard" uses `colorScheme.error` for text — visually marked as destructive.
- Tapping outside the dialog dismisses to "Keep editing" state (consistent with M3 dialog default).
- Hardware back inside the dialog also dismisses to "Keep editing" (preserves work).

**Edge cases:**
- **New routine, unedited (just opened, fields untouched):** `hasUnsavedChanges = false`. Back exits without dialog. (User opened the editor by mistake.)
- **New routine with one or more changes:** Dialog appears. "Discard" semantics here = "Cancel and don't save this new routine" (i.e. nothing was ever persisted).
- **Editing existing routine, no changes yet:** `hasUnsavedChanges = false`. Back exits without dialog. The existing saved routine is unaffected.
- **Editing existing routine with changes:** Dialog appears. "Discard" reverts the in-memory state without committing; the previously-saved version remains intact in the DB.
- **Saving in progress when user taps back:** Back is suppressed (button disabled) until save completes, then proceeds without dialog (since `state.routine == initialRoutine` post-save).
- **Equality check:** `Routine.equals()` must include the full exercise list, supersets, name, AMRAP flags, set/rep config — i.e. the data class default equality on the canonical Routine domain model. Already true if `Routine` is a plain data class.

---

### D. UNDO snackbar parity

Hoist a single `SnackbarHostState` at the screen level (top of `RoutineEditorScreen` composable) and add it to the `Scaffold`'s `snackbarHost = { SnackbarHost(snackbarHostState) }` slot. Wire all three destructive paths to use it.

**Snackbar ASCII:**

```
                                                              ┌─────────────────────────────────────────┐
                                                              │ Exercise removed              UNDO    × │   ← single delete
                                                              └─────────────────────────────────────────┘     5s timeout

                                                              ┌─────────────────────────────────────────┐
                                                              │ Superset removed              UNDO    × │   ← superset delete
                                                              └─────────────────────────────────────────┘     5s timeout

                                                              ┌─────────────────────────────────────────┐
                                                              │ 4 exercises removed           UNDO    × │   ← batch delete
                                                              └─────────────────────────────────────────┘     8s timeout
```

**Action timing table:**

| Destructive action | Snackbar copy | Timeout | Restore behavior |
|---|---|---|---|
| Single exercise delete (kebab → Delete) | `Exercise removed` | 5s (`SnackbarDuration.Short`) | Re-inserts at previous `orderIndex`, preserves superset membership |
| Superset delete (kebab → Delete superset) | `Superset removed` | 5s (`SnackbarDuration.Short`) | Restores all member exercises and the superset object with original colors and orderIndex range |
| Batch delete (multi-select Delete, ≥2 items) | `{N} exercises removed` | 8s (`SnackbarDuration.Long`) | Restores all selected items; if items spanned multiple supersets, restore those too |

**Behavior:**
- Tap UNDO → restore previous state from the captured undo-snapshot. The deletion is **not** committed to the DB until the snackbar dismisses without UNDO.
- Snackbar auto-dismisses → commit happens at timeout (call `viewModel.commitDelete(snapshot)`). This matches `CycleEditorScreen.kt:191-213`.
- Tap × dismiss → equivalent to "no UNDO"; commits immediately.
- New destructive action while a snackbar is already showing → previous snackbar's commit fires immediately, new snackbar replaces it. Stack-safe; matches Material 3 default snackbar host queueing semantics (`SnackbarHostState` only renders one at a time).
- Snackbar host is **hoisted at screen level** (not per-row) so it's positioned above the bottom nav and FAB consistently.

---

## Implementation notes

**State diff for `hasUnsavedChanges`:**

```kotlin
// At top of RoutineEditorScreen composable, near line 95
var initialRoutine by remember { mutableStateOf<Routine?>(null) }

LaunchedEffect(routineId) {
    if (!hasInitialized && routineId != "new") {
        val existing = viewModel.getRoutineById(routineId)
        if (existing != null) {
            state = state.copy(
                routineName = existing.name,
                routine = existing,
            )
            initialRoutine = existing                    // capture baseline
        }
        hasInitialized = true
    } else if (!hasInitialized) {
        val fresh = Routine(id = "new", name = "New Routine")
        state = state.copy(routineName = "New Routine", routine = fresh)
        initialRoutine = fresh                            // capture baseline
        hasInitialized = true
    }
}

val hasUnsavedChanges by remember(state.routine, state.routineName, initialRoutine) {
    derivedStateOf {
        val current = state.routine?.copy(name = state.routineName)
        current != initialRoutine
    }
}
```

**Compose API hints:**
- Empty state: gate behind `if (state.exercises.isEmpty()) RoutineEmptyState(...) else LazyColumn { ... }`.
- Template picker: `ModalBottomSheet` from `material3` 1.10.x, `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
- BackHandler: from `androidx.activity.compose.BackHandler` (already on classpath; CycleEditor uses it). Multiplatform: wrap in `expect/actual` if iOS BackHandler shim doesn't exist yet — fallback to no-op on iOS until covered separately.
- Save button: bind `enabled = state.routineName.isNotBlank() && state.exercises.isNotEmpty() && !state.isSaving`.
- Snackbar: `LaunchedEffect(snackbarMessage) { snackbarHostState.showSnackbar(...) }` pattern, capture result `SnackbarResult.ActionPerformed` for UNDO branch.
- Discard dialog: `AlertDialog` with custom button order — dialog defaults to `confirmButton` on right; place "Keep editing" there for primary affordance.
- Conflict-name dialog: same `AlertDialog` shape, three buttons stacked vertically (use `Column` inside `confirmButton` slot) for legibility on narrow widths.

**Accessibility:**
- Empty state heading uses `Modifier.semantics { heading() }` so screen readers announce structure.
- Both empty-state CTAs have `contentDescription` matching visible label (no NAF; addresses the screen-reader gap context noted in F-005).
- Disabled Save button uses `Modifier.semantics { stateDescription = "Disabled. Add a name and at least one exercise to save." }` so TalkBack announces the reason.
- Discard dialog: focus traps on "Keep editing" by default; ESC / hardware back dismisses to safe state.
- Snackbar action button: `contentDescription = "Undo. Restores the deleted exercise."` for the UNDO action.

**Multiplatform considerations:**
- `BackHandler` is Android-only by default; Compose Multiplatform's iOS surface uses `BackGestureHandler` (or no-op + UINavigationBar). Wrap in `expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)`.
- `SnackbarHostState`, `ModalBottomSheet`, `AlertDialog` are all multiplatform on Compose 1.10.x — no platform-specific work.

---

## Acceptance criteria

- [ ] Empty state renders when `state.exercises.isEmpty()`, with flame icon, headline `Build your routine`, body copy, and two CTAs.
- [ ] Tapping `+ Add exercise` opens the existing exercise picker (no behavior change beyond entry point).
- [ ] Tapping `⚡ Start from template` opens the template picker modal sheet.
- [ ] Each of 5 templates prefills `state.routine.exercises` with the documented exercise lists; user can immediately edit / save.
- [ ] FAB is hidden while empty state is visible (no duplicate primary CTA).
- [ ] Save button is `enabled = false` when name is blank OR exercises is empty; helper text updates accordingly.
- [ ] Save button shows spinner during async commit; disables interaction while saving.
- [ ] Saving with a name that matches an existing routine opens the conflict dialog with three options.
- [ ] System back button triggers Discard dialog when `hasUnsavedChanges == true`.
- [ ] Top-bar back arrow triggers identical Discard dialog (parity with system back).
- [ ] Discard dialog "Keep editing" is the primary affordance; "Discard" uses error-color text.
- [ ] Tapping "Discard" pops the back stack; tapping "Keep editing" closes dialog and stays on screen.
- [ ] Single-exercise delete shows snackbar "Exercise removed" with UNDO action, 5s timeout.
- [ ] Superset delete shows snackbar "Superset removed" with UNDO action, 5s timeout, restores full superset.
- [ ] Batch delete (≥2 items) shows snackbar "{N} exercises removed" with UNDO, 8s timeout.
- [ ] Tapping UNDO restores deleted item(s) with original orderIndex, supersetId, configuration.
- [ ] Snackbar auto-dismiss → deletion commits to DB.
- [ ] All new copy is in `composeResources/values/strings.xml` (no hardcoded English).
- [ ] Touch targets meet WCAG 2.2 SC 2.5.8 (≥24×24, recommend ≥48×48).
- [ ] Manual TalkBack pass: empty state CTAs, disabled Save reason, Discard dialog, snackbar UNDO are all announced.

---

## What this does NOT change

- **Auto-save mid-edit is explicitly NOT introduced.** Users want explicit save semantics; auto-save would conflict with the "Save" model and complicate the conflict-name flow. Per charter and `01-F-002` framing.
- **The kebab → Delete menu structure stays the same.** Only the post-delete behavior changes (snackbar instead of immediate commit). No new menu items, no moved items.
- **The `Add Exercise` FAB is not removed for the populated state.** It remains the primary add path once at least one exercise exists. Only suppressed in the empty state to avoid two competing CTAs.
- **The exercise picker, ExerciseConfigModal, and superset configuration UIs are unchanged.** This mockup only restructures the editor's empty/save/back lifecycle; the within-editor flows remain identical.
- **No changes to the Routines list (`RoutinesTab.kt`).** The `Issue 389 Demo Routine` fixture leak (G-141 / `06-F-015`) is referenced for context but addressed under a separate finding — gate the seed behind `BuildConfig.DEBUG` in a one-line dev-fixture cleanup.
- **No new destructive-action confirmations are added.** The existing batch-delete confirmation dialog is retained; UNDO is additive, not a replacement. Single-exercise and superset deletes do NOT gain a confirmation step (UNDO replaces the need for one — matches CycleEditor parity).
- **The connect-pill, top-bar gradient, and Phoenix-orange tokens are unchanged here.** Those are addressed in M-01 (connection state machine) and the broader G-016 brand sweep.
- **No changes to sync DTOs or the RoutineDto wire format.** All changes are presentation-layer; the saved Routine entity is identical to today's shape.

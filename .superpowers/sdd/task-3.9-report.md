# Task 3.9 Report: Exercise-picker consolidation

## MiniExercisePickerDialog callers found

Grep results for `MiniExercisePickerDialog(` in the working tree (`./shared/`, excluding worktrees and build dirs):

| File | Lines |
|------|-------|
| `presentation/screen/HistoryTab.kt` | 472, 1040 |
| `presentation/screen/WorkoutTab.kt` | 611 |

**3 call sites total. External signature `(exerciseRepository, onDismiss, onExerciseSelected)` preserved exactly — callers unchanged.**

## WorkoutSetupDialog.kt name-collision adjudication: EQUIVALENT → DELETED

**Verdict: The duplicate was behaviorally equivalent to the components version. It was deleted and its single call site was rewired.**

Evidence:
- Local `ExercisePickerDialog` in `screen/WorkoutSetupDialog.kt` (line 486): `AlertDialog` with `getAllExercises()`, search field, `ExerciseCategory` muscle-filter chips.
- Components `ExercisePickerDialog` in `components/ExercisePicker.kt` (line 103): full-featured `ModalBottomSheet` with search, favorites, custom exercises, muscle + equipment filter shelf, thumbnails, and grouping.
- The local version's `ExerciseCategory` filter chips provide a strict subset of the components version's `ExerciseFilterShelf` muscle toggles — the local version has no feature the components version lacks.
- The local version was not imported by any other file; its only call site was the `if (showExercisePicker)` guard at line 448 in the same file.
- Action taken:
  - Added `import com.devil.phoenixproject.presentation.components.ExercisePickerDialog`
  - Replaced guarded call `if (showExercisePicker) { ExercisePickerDialog(...) }` with `ExercisePickerDialog(showDialog = showExercisePicker, ..., enableVideoPlayback = false)`
  - Deleted the ~141-line local function definition
  - Cleaned up orphaned explicit imports (`horizontalScroll`, `LazyColumn`, `LazyColumn.items`)

## InsightsTab / SmartInsightsTab findings outcome

**Finding `analytics-history-5` (high severity, small effort) — FIXED.**

- Extracted `TimeframeBadge`, `InsightSectionHeader`, and `InsightContextBlock` into new `components/InsightComponents.kt`.
- `InsightSectionHeader` unifies the identically-structured `InsightSectionHeader` (InsightsTab) and `InsightHierarchyHeader` (SmartInsightsTab). Minor style delta: explicit `color = MaterialTheme.colorScheme.onSurface` on the title line — the token-based version is used.
- `InsightContextBlock` unifies `InsightMetadata` (3-arg, no title, InsightsTab) and `InsightContextBlock` (4-arg, with title, SmartInsightsTab) via an optional `title: String? = null` parameter. All SmartInsightsTab call sites used named parameters so no positional breakage.
- `TimeframeBadge` was identical in both files; token spacing (`Spacing.small/extraSmall`) is used in place of raw `10.dp/4.dp` literals.
- Both files' private local duplicates were deleted; unused imports (`CircleShape`, `Surface` from Material 3) were removed.

**Other InsightsTab findings (analytics-history-2, -6, -7, -11, -14, lens-state-patterns-3)** — Skipped. These involve hardcoded color literals, raw dp literals (pervasive — scope outside task 3.9), card corner radii consistency, missing contentDescription, display string localization, and a try/finally exception swallow. Each requires its own targeted remediation; they are structural in scope relative to this task's change budget.

## Design-system compliance

- No new `RoundedCornerShape(N.dp)` literals introduced.
- No new hardcoded color literals introduced.
- No new user-visible string literals — `tag_exercise_action` string key (already in `strings.xml` line 724) used for the dialog title.
- `getEquipmentDatabaseValues` visibility relaxed from `private` to `internal` in `ExercisePicker.kt` to enable reuse in `MiniExercisePickerDialog` (same module, `presentation.components` package).

## Build & test

- `./gradlew :androidApp:assembleDebug` — BUILD SUCCESSFUL
- `./gradlew :shared:testAndroidHostTest --rerun-tasks` — BUILD SUCCESSFUL, **2,296 tests passed, 0 failures, 0 errors**

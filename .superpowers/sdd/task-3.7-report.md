# Task 3.7 Report: Canonical DestructiveConfirmDialog

## Outcome

All 10 sites migrated. 0 sites left unmigrated. Component created. Build clean. Tests: 2,296 passed, 0 failed.

---

## Component Created

`shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/DestructiveConfirmDialog.kt`

- `AlertDialog(containerColor = surfaceContainerHighest, shape = shapes.large)`
- confirm: `TextButton(Modifier.height(56.dp), shape = shapes.medium)` → `Text(titleLarge, Bold, color = error)`
- dismiss: same-geometry `TextButton` → `stringResource(Res.string.action_cancel)`, `titleMedium`, Bold, `onSurfaceVariant`
- Optional `icon: ImageVector?` slot (unused by current callers, tinted with `error`)

No new `RoundedCornerShape` literals, no hardcoded colors.

---

## Per-Site Migration Table

| # | File | Site | Old Style | Migrated | Strings Minted | Strings Reused |
|---|------|------|-----------|----------|----------------|----------------|
| 1 | `components/DeleteProfileDialog.kt` | Delete profile | `textButtonColors(error)` | Y | — | `delete_profile`, `delete_profile_message`, `action_delete` |
| 2 | `screen/RoutineEditorScreen.kt` ~921 | Delete superset | `textButtonColors(error)` | Y | `delete_superset_message` | `delete_superset_title`, `delete_all` |
| 3 | `screen/RoutineEditorScreen.kt` ~976 | Batch delete exercises | `Text(color=error)` inline | Y | — | `delete_selected_exercises`, `cannot_be_undone`, `action_delete` |
| 4 | `screen/TrainingCyclesScreen.kt` ~642 | Delete cycle | `textButtonColors(error)` | Y | — | `delete_cycle_title`, `delete_cycle_message`, `action_delete` |
| 5 | `screen/RoutinesTab.kt` ~491 | Batch delete routines | `Text(color=error)` inline | Y | — | `delete_selected_routines`, `cannot_be_undone`, `action_delete` |
| 6 | `screen/RoutinesTab.kt` ~769 | Delete group | Hardcoded `"Delete"`/`"Cancel"` + `Text(color=error)` | Y | `delete_group_title`, `delete_group_message` | `action_delete` |
| 7 | `screen/RoutinesTab.kt` ~1431 | Single delete routine | `Text(color=error)` inline | Y | — | `delete_routine`, `delete_routine_message`, `action_delete` |
| 8 | `screen/HistoryTab.kt` ~562 | Delete workout | Near-canonical, hardcoded `"Delete Workout?"`, `"Delete"`, `"Cancel"` | Y | `delete_workout_title`, `delete_workout_message` | `action_delete` |
| 9 | `screen/HistoryTab.kt` ~1172 | Delete routine session | Near-canonical, hardcoded `"Delete Routine Session?"`, `"Delete All Sets"`, `"Delete"`, `"Cancel"` | Y | `delete_routine_session_title`, `delete_routine_session_message`, `delete_all_sets` | `action_delete` |
| 10 | `screen/SettingsTab.kt` ~2920 | Delete all workouts | Near-canonical, `shape=extraLarge` (→ `medium`), hardcoded `"Delete All Workouts?"`, `"Delete All"`, `"Cancel"` | Y | `delete_all_workouts_title`, `delete_all_workouts_message` | `delete_all` |

**Sites NOT migrated:** None. All 10 sites had only title + message + 2 buttons in their dialog structure.

---

## Additional Fixes (6-locale rule)

- `HistoryTab.kt` trigger button `Text("Delete")` → `stringResource(Res.string.action_delete)`
- `HistoryTab.kt` trigger button `Text("Delete All Sets")` → `stringResource(Res.string.delete_all_sets)`
- `RoutinesTab.kt` ~769 `Text("Delete")` + `Text("Cancel")` → component (via migration)

## Import Cleanup

- Removed `AlertDialog` import from `HistoryTab.kt` (all AlertDialog usages migrated; no other AlertDialogs in that file)
- Removed `AlertDialog`, `ButtonDefaults`, `MaterialTheme`, `Text`, `TextButton`, `action_cancel` imports from `DeleteProfileDialog.kt` (no longer needed after migration)

## New String Keys Added (strings.xml)

10 new keys:
1. `delete_superset_message` (in Supersets section)
2. `delete_group_title` (in Routines section)
3. `delete_group_message` (in Routines section)
4. `delete_workout_title` (in Destructive confirm dialogs section)
5. `delete_workout_message`
6. `delete_all_sets`
7. `delete_routine_session_title`
8. `delete_routine_session_message`
9. `delete_all_workouts_title`
10. `delete_all_workouts_message`

All 5 locale files (de, es, fr, it, nl) fall back to English base for the new keys per standard KMP resource fallback.

## Verification

- `./gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL
- `./gradlew :shared:testAndroidHostTest --rerun-tasks` → 2,296 tests executed, 0 failed

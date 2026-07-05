# Task 3.8 Report: Dialog slot/hierarchy fixes

## Summary

All targeted dialog fixes implemented and verified. Build: PASS. Tests: 2,296 passed, 0 failed.

## Per-Finding Table

| Finding ID | Site | What changed |
|---|---|---|
| lens-dialog-uniformity-6 | `RestTimePickerDialog.kt:71` | Moved Cancel `TextButton` from `dismissButton` slot into `confirmButton` slot; removed empty `confirmButton = {}`. M3 now renders Cancel right-aligned with no dead zone. |
| lens-dialog-uniformity-6 | `SupersetPickerDialog.kt:132` | Same slot fix: Cancel moved to `confirmButton`. Also see -13 for the semantics changes on this file. |
| lens-dialog-uniformity-6 | `MiniExercisePickerDialog.kt` | **SKIPPED** — Task 3.9 will rewrite this dialog entirely. |
| lens-dialog-uniformity-9 | `ResumeRoutineDialog.kt:27` | Restart `OutlinedButton` moved from `dismissButton` slot into the `text` body as a full-width `OutlinedButton` below the progress info. `dismissButton` now holds a plain `TextButton("Cancel")` that calls `onDismiss`. `confirmButton` (Resume as filled `Button`) unchanged. `onResume`/`onRestart` callbacks wired identically to before; scrim-tap dismissal preserved (`onDismissRequest = onDismiss`). |
| lens-dialog-uniformity-11 | `SettingsTab.kt:2991` (backup dialog) | Removed `fontWeight = FontWeight.Bold` from backup `AlertDialog` title — now M3 default `headlineSmall`. |
| lens-dialog-uniformity-11 | `SettingsTab.kt:3066` (restore dialog) | Removed `fontWeight = FontWeight.Bold` from restore `AlertDialog` title. |
| lens-dialog-uniformity-11 | `SettingsTab.kt:3100–3101` (result dialog) | Removed `fontWeight = FontWeight.Bold` from backup/restore result `AlertDialog` title. |
| lens-dialog-uniformity-11 | `ConnectionLostDialog.kt:39` | **No change needed** — Phase 2 sweep (commit `7a84065e`) already removed `fontWeight = Bold`; file has only `style = headlineSmall` (M3 default). Confirmed clean. |
| lens-dialog-uniformity-13 | `SupersetPickerDialog.kt:54,99` | Added `semantics(mergeDescendants = true) { role = Role.Button }` and `heightIn(min = 48.dp)` to both the "Create New Superset" row and each existing-superset row. TalkBack now merges inner text (name + exercise count) and announces role. Semantics lambda contains no raw strings — child `Text` composables supply all announced content. Added imports: `Role`, `role`, `semantics`, `heightIn`. |
| lens-dialog-uniformity-14 | `BulkWeightAdjustDialog.kt:272` | Added `if (currentMode == null)` block that renders a `Text(stringResource(Res.string.bulk_weight_adjust_select_hint), labelSmall, onSurfaceVariant)` hint below the mode-specific input. New string key `bulk_weight_adjust_select_hint` added to `values/strings.xml` only (6-locale rule: base file only; other locales fall back to English as per project convention established by task-3.5/3.7). |
| lens-dialog-uniformity-15 | `BadgeCelebrationDialog.kt` | **Already fixed** — `RoundedCornerShape` import present but no `RoundedCornerShape(N.dp)` shape literal anywhere in the file (import is unused). Phase 2 sweep removed the raw shape. No action taken. |
| lens-dialog-uniformity-16 | `SettingsTab.kt:3704` (DiscoModeUnlockDialog) | Replaced `while(true)/delay(16)` `LaunchedEffect` with `rememberInfiniteTransition`. Rotation: 0→360° over 1920 ms (`LinearEasing`, `RepeatMode.Restart` — equivalent visual speed to 3°/16 ms). Glow: 0.3→0.8 alpha over 800 ms reverse. `reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion` check freezes rotation at 0f when enabled; glow continues (non-spatial motion). Removed 3 `var` state fields. Added imports: `LinearEasing`, `RepeatMode`, `animateFloat`, `infiniteRepeatable`, `rememberInfiniteTransition`, `tween`, `LocalPlatformAccessibilitySettings`. |
| lens-dialog-uniformity-17 | `RoutineModifierDialog.kt:66` | Changed `confirmButton` from `TextButton` to `Button` for the "Start" action. Cancel stays `TextButton`. Added `import androidx.compose.material3.Button`. |

## Design-system constraint verification

- No new `RoundedCornerShape(N.dp)` literals introduced (ratchet: shapes ≤36 pre-existing).
- No hardcoded color literals introduced (ratchet: colors ≤38 pre-existing).
- No new user-visible strings hardcoded in Kotlin — one new key added to `values/strings.xml`.
- All semantics lambda content derives from already-localized child `Text` composables (no raw string in lambda).
- ZERO behavior change beyond specified slot/hierarchy/animation fixes; all callbacks reach the same handlers.

## Build & Test

- `./gradlew :androidApp:assembleDebug`: BUILD SUCCESSFUL
- `./gradlew :shared:testAndroidHostTest --rerun-tasks`: BUILD SUCCESSFUL — **2,296 tests passed, 0 failed**

## Fix wave (post-review)

| # | Issue | Fix |
|---|---|---|
| 1 | Glow pulse rate wrong — `tween(800)` with `Reverse` = 1600ms full cycle vs original 800ms | Changed to `tween(durationMillis = 400)`. Original loop: ±0.02f/frame @ 16ms = 25 frames × 16ms = 400ms half-cycle; `Reverse` doubles to 800ms full cycle. |
| 2 | BulkWeightAdjustDialog hint gate `currentMode == null` misses all-PR-scaled case | Gate changed to `changeCount == 0`. When `currentMode == null` shows `bulk_weight_adjust_select_hint` (existing); when mode set but nothing changed (all PR-scaled) shows new `bulk_adjust_hint_pr_scaled` key. `changeCount` derivation already excludes PR-scaled exercises — no condition duplication. New string added to `values/strings.xml`. |
| 3 | reduceMotion post-processed via `val rotation = if (reduceMotion)…` after the animated value | Moved into `targetValue = if (reduceMotion) 0f else 360f` on rotation channel and `targetValue = if (reduceMotion) 0.3f else 0.8f` on glow channel. Both channels settle at initial value under reduceMotion. Removed intermediate `animatedRotation` local. |
| 4 | Re-verify | `./gradlew :androidApp:assembleDebug`: BUILD SUCCESSFUL. `./gradlew :shared:testAndroidHostTest --rerun-tasks`: BUILD SUCCESSFUL — **2,296 tests passed, 0 failed** (22 tasks executed, none cached). |

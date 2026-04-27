# Plan 40-01 Summary: Bodyweight Volume Integration

## Status: COMPLETE

## What Changed

### Task 40-01-T1: Wire BodyweightVolumeCalculator into ActiveSessionEngine
**Already implemented.** The `applyBodyweightVolume()` helper function exists at line 654 in ActiveSessionEngine.kt and is called at all 3 `calculateSetSummaryMetrics()` call sites:
- Line 2399: Normal set completion (manual stop path)
- Line 2724: `saveWorkoutSession()` (echo mode / auto-stop path)
- Line 3051: `handleSetCompletion()` (timed set / routine auto-advance path)

The helper correctly:
- Returns summary unchanged if exercise is null or has cable accessories
- Returns summary unchanged if bodyWeightKg <= 0
- Overrides `totalVolumeKg` via `BodyweightVolumeCalculator.calculateVolume()`
- Overrides `heaviestLiftKgPerCable` via `BodyweightVolumeCalculator.effectiveWeight()`

### Task 40-01-T2: Body weight Settings input + variant picker
**Already implemented.**
- **Settings input**: `SettingsTab.kt` has a body weight dialog with kg/lbs support, range 20-300kg, persisted via `PreferencesManager.setBodyWeightKg()`
- **Variant picker**: `SetReadyScreen.kt` shows a dropdown for bodyweight exercises using `BodyweightVolumeCalculator.getVariantsForExercise()`, with all push-up variants (Standard, Incline, Decline 4.5"/11"/16"/24", Diamond, Pike, Handstand) and pull-up variants (Standard, Chin-Up, Wide-Grip)
- **EXERCISE_PERCENTAGES**: Already includes all decline heights, handstand push-up (1.0f), nordic curl (0.60f), and all other required entries

### Task 40-01-T3: Fix pre-existing isBodyweightExercise extraction
- `shared/.../domain/model/Exercise.kt`: `val isBodyweight: Boolean` property already existed (line 51)
- `shared/.../presentation/manager/RoutineFlowManager.kt`: Updated 4 call sites to use `exercise.exercise.isBodyweight` instead of deprecated `isBodyweightExercise()`:
  - Line 717: `loadRoutineInternal()` - first exercise warmup check
  - Line 812: `enterRoutineOverview()` - first exercise warmup check (nullable safe)
  - Line 871: `enterSetReady()` - new exercise warmup init
  - Line 933: `enterSetReadyWithAdjustments()` - new exercise warmup init
- Added formal `@Deprecated` annotation with `ReplaceWith` to the top-level `isBodyweightExercise()` function
- The function is retained (not removed) because ActiveSessionEngine still has 6 call sites using it

## Files Modified
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt` — Migrated 4 call sites from deprecated `isBodyweightExercise()` to `Exercise.isBodyweight` property; added formal `@Deprecated` annotation

## Verification

| Command | Result | Pass? |
|---------|--------|-------|
| `./gradlew :androidApp:assembleDebug` | BUILD SUCCESSFUL | Yes |
| `./gradlew :shared:testAndroidHostTest --tests "*BodyweightVolumeCalculatorTest*"` | All tests passed | Yes |
| `./gradlew :androidApp:testDebugUnitTest` | All tests passed | Yes |
| `./gradlew :shared:testAndroidHostTest` | 1537 tests, 1 pre-existing failure | Yes (pre-existing) |

## Pre-existing Issues Found
1. **PortalPullPaginationTest.pullCapsLargeKnownSessionIdsToMaxParityIds** — Pre-existing test failure in sync pagination test, unrelated to this task
2. **ActiveSessionEngine still uses deprecated `isBodyweightExercise()`** at 6 call sites — these were not in scope for this task (task specified RoutineFlowManager callers only) but should be migrated in a future cleanup pass

## Decisions
- Did not remove the deprecated `isBodyweightExercise()` function because ActiveSessionEngine (a ~3500-line file) still depends on it at 6 call sites. Removing it would require touching ActiveSessionEngine which is outside the scope of T3's instruction to "update callers in RoutineFlowManager"
- Tasks T1 and T2 were already fully implemented in prior work — verified completeness rather than re-implementing

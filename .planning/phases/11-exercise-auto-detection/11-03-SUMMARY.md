---
phase: 11-exercise-auto-detection
plan: 03
subsystem: presentation
tags: [exercise-detection, ui-integration, bottom-sheet, workout-hud, compose]
dependency-graph:
  requires:
    - 11-01-SUMMARY.md (SignatureExtractor, ExerciseClassifier, DetectionModels)
    - 11-02-SUMMARY.md (ExerciseSignatureRepository, Koin bindings)
  provides:
    - ExerciseDetectionManager orchestrating detection flow
    - AutoDetectionSheet composable for exercise confirmation
    - Full UI integration through WorkoutHud
  affects:
    - shared/presentation/manager/
    - shared/presentation/components/
    - shared/presentation/screen/
    - shared/presentation/viewmodel/
tech-stack:
  added: []
  patterns:
    - State holder pattern (DetectionState)
    - Non-blocking bottom sheet overlay
    - Callback-based UI actions
key-files:
  created:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ExerciseDetectionManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AutoDetectionSheet.kt
  modified:
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/di/PresentationModule.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
    - shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt
decisions:
  - ExerciseDetectionManager triggers after 3 working reps (MIN_REPS_FOR_DETECTION = 3)
  - Detection only triggers once per set (hasTriggeredThisSet flag)
  - Detection skipped when exercise already assigned (routine mode)
  - Non-blocking bottom sheet uses ModalBottomSheet with skipPartiallyExpanded=false
  - Confidence color coding: green >80%, yellow 60-80%, orange <60%
  - ExercisePickerDialog reused for "Select Different" functionality
metrics:
  duration: 6min
  completed: 2026-02-15
---

# Phase 11 Plan 03: UI Integration Summary

**Non-blocking exercise auto-detection UI with confirmation flow during active workouts**

## What Was Built

### Task 1: ExerciseDetectionManager and Workflow Integration (46f2d22c)

**ExerciseDetectionManager.kt** - Orchestrates detection flow:
- `DetectionState` data class exposed via StateFlow for UI
- `onRepCompleted()` triggers detection after MIN_REPS_FOR_DETECTION (3) working reps
- `onExerciseConfirmed()` saves/evolves signature via repository
- `onDetectionDismissed()` sets isDismissed flag to prevent re-trigger
- `resetForNewSet()` clears state for next set

**Integration points:**
- ActiveSessionEngine calls `detectionManager.onRepCompleted()` after rep completion
- DefaultWorkoutSessionManager calls `resetForNewSet()` in `proceedFromSummary()`
- MainViewModel exposes `detectionState` flow for UI consumption
- PresentationModule registers ExerciseDetectionManager factory

### Task 2: AutoDetectionSheet UI and WorkoutHud Integration (a883fa1c)

**AutoDetectionSheet.kt** - Non-blocking bottom sheet composable:
- Header with "Exercise Detected" title and dismiss button
- Primary suggestion card with exercise name and confidence badge
- Confidence badge color-coded (green/yellow/orange)
- Alternate suggestions as SuggestionChips (up to 3)
- "Select Different" button opens ExercisePickerDialog
- "Confirm" button triggers onConfirm callback

**State integration:**
- DetectionState added to WorkoutUiState
- onDetectionConfirmed/onDetectionDismissed added to WorkoutActions
- WorkoutTab passes detection state/callbacks to WorkoutHud
- WorkoutHud conditionally renders AutoDetectionSheet

## Verification Results

- `./gradlew :androidApp:assembleDebug` - BUILD SUCCESSFUL
- AutoDetectionSheet import verified in WorkoutHud.kt
- Detection state wiring verified through WorkoutTab -> WorkoutHud chain
- All existing tests continue to pass

## Commits

| Hash | Message |
|------|---------|
| 46f2d22c | feat(11-03): add ExerciseDetectionManager and workout flow integration |
| a883fa1c | feat(11-03): add AutoDetectionSheet UI and WorkoutHud integration |

## Files Created/Modified

**Created:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ExerciseDetectionManager.kt` - Detection orchestration manager
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/AutoDetectionSheet.kt` - Bottom sheet UI component

**Modified:**
- `ActiveSessionEngine.kt` - Added detectionManager parameter and onRepCompleted call
- `DefaultWorkoutSessionManager.kt` - Added detectionManager, resetForNewSet in proceedFromSummary
- `MainViewModel.kt` - Added detectionManager parameter and detectionState exposure
- `PresentationModule.kt` - Added ExerciseDetectionManager factory binding
- `WorkoutHud.kt` - Added detection parameters and AutoDetectionSheet rendering
- `WorkoutTab.kt` - Added detection state/callback passing to WorkoutHud
- `WorkoutUiState.kt` - Added DetectionState, detection actions

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Task 1 was already partially implemented**
- **Found during:** Task 1 execution
- **Issue:** ExerciseDetectionManager.kt and integration code existed as uncommitted changes from a prior partial execution
- **Fix:** Verified implementation correctness, staged and committed existing work
- **Impact:** None - code was correct and complete

None - plan executed exactly as written with the exception of finding Task 1 already implemented.

## Success Criteria Met

- ExerciseDetectionManager triggers after 3 working reps with collected metrics
- AutoDetectionSheet renders classification with confidence percentage
- Confirming an exercise saves/evolves the signature in ExerciseSignatureRepository
- Dismissing prevents re-triggering for the same set
- Set transitions reset detection state
- Detection flow is non-blocking - workout continues uninterrupted

## Self-Check: PASSED

- [x] ExerciseDetectionManager.kt exists
- [x] AutoDetectionSheet.kt exists
- [x] Commit 46f2d22c exists
- [x] Commit a883fa1c exists
- [x] WorkoutHud.kt contains AutoDetectionSheet
- [x] WorkoutUiState.kt contains DetectionState
- [x] Build compiles successfully

---
*Phase: 11-exercise-auto-detection*
*Completed: 2026-02-15*

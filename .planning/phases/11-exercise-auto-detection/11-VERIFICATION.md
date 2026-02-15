---
phase: 11-exercise-auto-detection
verified: 2026-02-15T18:30:00Z
status: gaps_found
score: 2/4 truths verified
gaps:
  - truth: "After the first 3-5 reps of a set, the system suggests an exercise name with confidence percentage via a non-blocking bottom sheet"
    status: failed
    reason: "ActiveWorkoutScreen does not observe or pass detectionState to WorkoutUiState, so bottom sheet will never appear"
    artifacts:
      - path: "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt"
        issue: "WorkoutUiState construction (line 280) missing detectionState parameter"
    missing:
      - "Observe viewModel.detectionState in ActiveWorkoutScreen"
      - "Pass detectionState to WorkoutUiState construction"
  - truth: "User can confirm the suggestion or select a different exercise, and the interaction does not interrupt the workout"
    status: failed
    reason: "ActiveWorkoutScreen workoutActions missing onDetectionConfirmed and onDetectionDismissed callbacks"
    artifacts:
      - path: "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt"
        issue: "workoutActions construction (line 310) missing detection callbacks"
      - path: "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt"
        issue: "No delegation functions for onDetectionConfirmed/onDetectionDismissed"
    missing:
      - "Add viewModel delegation functions: fun onDetectionConfirmed(exerciseId: String, exerciseName: String) and fun onDetectionDismissed()"
      - "Wire these functions in ActiveWorkoutScreen workoutActions construction"
---

# Phase 11: Exercise Auto-Detection Verification Report

**Phase Goal:** The app identifies what exercise the user is performing based on movement signature and learns from corrections  
**Verified:** 2026-02-15T18:30:00Z  
**Status:** gaps_found  
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | After 3-5 reps, system suggests exercise with confidence via non-blocking bottom sheet | ✗ FAILED | AutoDetectionSheet exists but ActiveWorkoutScreen does not observe/pass detectionState |
| 2 | User can confirm/select different exercise without interrupting workout | ✗ FAILED | AutoDetectionSheet has callbacks but ActiveWorkoutScreen does not wire them to ViewModel |
| 3 | Confirmed exercise signatures stored and used to improve future suggestions | ✓ VERIFIED | ExerciseDetectionManager.onExerciseConfirmed calls repository.saveSignature |
| 4 | Repeat performances produce higher confidence scores as signature history grows | ✓ VERIFIED | ExerciseClassifier history matching with evolveSignature EMA algorithm |

**Score:** 2/4 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| ExerciseDetectionManager.kt | Orchestrates detection flow | ✓ VERIFIED | 199 lines, complete implementation with StateFlow |
| AutoDetectionSheet.kt | Bottom sheet UI component | ✓ VERIFIED | 251 lines, Material3 ModalBottomSheet with confidence badge |
| SignatureExtractor.kt | Extract signature from metrics | ✓ VERIFIED | 280 lines, valley detection, ROM/duration/symmetry calc |
| ExerciseClassifier.kt | History + rule-based classification | ✓ VERIFIED | 270 lines, weighted similarity 85% threshold |
| ExerciseSignatureRepository.kt | Interface for signature CRUD | ✓ VERIFIED | 54 lines, 5 methods declared |
| SqlDelightExerciseSignatureRepository.kt | SQLDelight implementation | ✓ VERIFIED | 110 lines, maps domain to DB with enum string encoding |
| DetectionModels.kt | Domain models | ✓ VERIFIED | Contains ExerciseSignature, ExerciseClassification, enums |
| ActiveWorkoutScreen.kt | Wires detection state to UI | ⚠️ ORPHANED | File exists but detectionState/callbacks not wired |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| ActiveSessionEngine | ExerciseDetectionManager | onRepCompleted after rep threshold | ✓ WIRED | Line 535: detectionManager?.onRepCompleted called |
| ExerciseDetectionManager | SignatureExtractor | extractSignature from metrics | ✓ WIRED | Line 98: signatureExtractor.extractSignature(metrics) |
| ExerciseDetectionManager | ExerciseSignatureRepository | saveSignature/evolveSignature | ✓ WIRED | Lines 157, 164: repository.saveSignature |
| WorkoutHud | AutoDetectionSheet | Conditional rendering when state active | ✓ WIRED | Line 306: if detectionState.isActive renders sheet |
| ActiveWorkoutScreen | DetectionState | Observe viewModel.detectionState | ✗ NOT_WIRED | detectionState not observed or passed to WorkoutUiState |
| WorkoutActions | ExerciseDetectionManager | Callbacks wire to manager methods | ✗ NOT_WIRED | onDetectionConfirmed/Dismissed not in workoutActions |

### Requirements Coverage

Phase 11 maps to requirements DETECT-01 through DETECT-06 in REQUIREMENTS.md.

| Requirement | Status | Blocking Issue |
|-------------|--------|---------------|
| DETECT-01 | ✓ SATISFIED | SignatureExtractor extracts from metrics |
| DETECT-02 | ✓ SATISFIED | ExerciseClassifier with history + rules |
| DETECT-03 | ✗ BLOCKED | Bottom sheet exists but state not observed in UI |
| DETECT-04 | ✓ SATISFIED | Repository saves signatures |
| DETECT-05 | ✓ SATISFIED | computeSimilarity with correct weights |
| DETECT-06 | ✓ SATISFIED | evolveSignature with EMA alpha=0.3 |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| ActiveWorkoutScreen.kt | 280 | WorkoutUiState missing detectionState param | 🛑 Blocker | Bottom sheet will never appear - default empty state used |
| ActiveWorkoutScreen.kt | 310 | workoutActions missing detection callbacks | 🛑 Blocker | User cannot confirm/dismiss detection - callbacks are no-ops |
| MainViewModel.kt | 145 | Detection delegation incomplete | 🛑 Blocker | Only detectionState exposed, no callback methods |
| WorkoutFlowE2ETest.kt | 75 | Test constructor missing detectionManager | ⚠️ Warning | Tests fail to compile - integration tests need update |
| MainViewModelTest.kt | 86 | Test constructor missing detectionManager | ⚠️ Warning | Tests fail to compile |
| DWSMTestHarness.kt | 68 | Test constructor missing detectionManager | ⚠️ Warning | Test harness needs detectionManager parameter |

### Human Verification Required

The following items cannot be verified programmatically and need human testing once gaps are closed:

#### 1. Bottom Sheet Appearance After 3 Reps

**Test:** Start a workout in JustLift mode (no exercise selected). Perform 3 working reps with any movement pattern.  
**Expected:** After the 3rd rep completes, a bottom sheet should slide up from the bottom showing an exercise suggestion (e.g., "Chest Press") with a confidence percentage badge (e.g., "70%").  
**Why human:** Visual appearance, timing, and non-blocking overlay behavior require visual inspection.

#### 2. Confidence Badge Color Coding

**Test:** Trigger detection multiple times with varying signature matches to observe confidence levels.  
**Expected:**  
- Green badge for >80% confidence
- Yellow badge for 60-80% confidence
- Orange badge for <60% confidence  

**Why human:** Color perception and visual design verification.

#### 3. Signature Evolution Over Time

**Test:** Perform the same exercise (e.g., bicep curls) multiple times across different workouts. Note confidence percentages.  
**Expected:** First performance ~50-70% confidence (rule-based). Second performance ~85%+ confidence (history match). Each confirmation should increase confidence slightly.  
**Why human:** Requires multiple workout sessions and longitudinal observation.

#### 4. Detection Skips When Exercise Assigned

**Test:** Load a routine with a specific exercise selected. Perform reps.  
**Expected:** Detection sheet should NOT appear (routine already has exercise assigned).  
**Why human:** Negative test - verifying absence of behavior.

#### 5. Set Transition Resets Detection

**Test:** Dismiss detection sheet during a set. Proceed to next set via SetSummary.  
**Expected:** Detection should re-trigger after 3 reps in the new set (not blocked by previous dismissal).  
**Why human:** Multi-step workflow verification across state transitions.

### Gaps Summary

The domain layer (Plans 11-01, 11-02) is **fully implemented and verified**:
- SignatureExtractor with valley-based rep detection ✓
- ExerciseClassifier with weighted similarity and rule-based fallback ✓
- ExerciseSignatureRepository with SQLDelight persistence ✓
- Complete TDD test coverage (12 + 15 test cases) ✓
- Koin DI wiring ✓

The presentation layer (Plan 11-03) has **complete components but incomplete wiring**:
- ExerciseDetectionManager exists and is triggered by ActiveSessionEngine ✓
- AutoDetectionSheet composable exists with full UI ✓
- WorkoutHud conditionally renders sheet based on detectionState ✓
- **BUT** ActiveWorkoutScreen does not observe detectionState from ViewModel ✗
- **AND** ActiveWorkoutScreen does not wire detection callbacks to ViewModel ✗
- **AND** MainViewModel does not expose delegation functions for detection callbacks ✗

This is a **classic stub pattern** - all the pieces exist in isolation but the final connections to make data flow end-to-end are missing. The detection manager will trigger, extract signatures, and update state - but the UI will never see that state because ActiveWorkoutScreen uses the default empty DetectionState(). User interactions with the sheet (if it somehow appeared) would call no-op callbacks that do not reach the detection manager.

**Root cause:** Plan 11-03 SUMMARY claims "WorkoutTab passes detection state/callbacks to WorkoutHud" which is true, but it does not mention that **ActiveWorkoutScreen** (the component that calls WorkoutTab) needs to observe detectionState from the ViewModel and pass it down. This was an incomplete implementation that passed local verification but fails integration testing.

---

_Verified: 2026-02-15T18:30:00Z_  
_Verifier: Claude (gsd-verifier)_

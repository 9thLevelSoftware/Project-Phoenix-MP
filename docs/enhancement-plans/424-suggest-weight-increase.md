# Issue #424 — Suggest Weight Increase Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. This feature has safety implications; v1 must be suggestion-only unless the user explicitly taps Apply.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/424
Branch: `feat/424-weight-suggestions`
PR scope: compute and show next-weight recommendations based on rep quality/history; no automatic routine mutation.

## Goal

Help users decide whether to increase, maintain, or reduce weight for the next set based on rep quality and set performance.

## Current state verified in repo

Relevant files:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/RepQualityScorer.kt`
  - computes per-rep quality and set summaries.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
  - includes `RepQualityScore`, `SetQualitySummary`, `WorkoutSession`, `WorkoutParameters`, `HapticEvent`.
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
  - includes `RepMetric` and biomechanics/velocity-loss fields.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
  - exposes `latestRepQuality`, `latestBiomechanicsResult`, workout parameters/state.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
  - exposes coordinator flows and `updateWorkoutParameters`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
  - best surface for next-set recommendation and Apply action.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RepQualityIndicator.kt`
  - existing quality feedback component.

## Architecture

Add a pure domain recommendation use case, then expose recommendations through workout coordinator state.

Recommended data model:

```kotlin
enum class WeightAdjustmentDirection { INCREASE, MAINTAIN, DECREASE }
enum class RecommendationConfidence { LOW, MEDIUM, HIGH }

data class WeightAdjustmentRecommendation(
    val direction: WeightAdjustmentDirection,
    val currentWeightKgPerCable: Float,
    val recommendedWeightKgPerCable: Float,
    val deltaKgPerCable: Float,
    val confidence: RecommendationConfidence,
    val reasonCode: String,
    val explanation: String,
)
```

V1 is suggestion-only:

- show recommendation after a completed set or on the next Set Ready screen.
- user taps Apply to update current workout parameters.
- do not auto-update saved routine defaults.
- do not auto-apply unless a future explicit setting is added.

## Recommendation algorithm v1

Inputs:

- current exercise ID/name.
- current set target reps and actual reps.
- current `weightPerCableKg`.
- user’s effective increment (`userPreferences.effectiveWeightIncrementKg` appears already used in active workout state).
- latest/average `RepQualityScore` or set summary.
- latest `BiomechanicsRepResult` if available.
- recent set history for this exercise if easily accessible.

Rules:

1. Suppress recommendation when:
   - exercise is bodyweight/timed with no cable load.
   - no rep-quality/biomechanics/history data exists.
   - current weight is at min/max boundary and suggested direction would exceed clamp.

2. Increase by one increment when:
   - target reps completed.
   - average quality >= 85.
   - final rep quality >= 75.
   - no severe velocity loss stop condition.
   - confidence MEDIUM/HIGH depending on amount of history.

3. Maintain when:
   - reps completed but quality is 65-84.
   - data is insufficient.
   - only one very good set exists but history is mixed.

4. Decrease by one increment when:
   - failed target reps repeatedly, or
   - average quality < 60, or
   - severe velocity loss/estimated reps remaining indicates load too high.

Clamp output using existing constants and rounding conventions.

## Tasks

### Task 1 — Add domain model tests

Objective: define output shape and clamp/rounding expectations.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/WeightRecommendation.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/WeightRecommendationTest.kt`

Tests:

- increase recommendation carries positive delta.
- decrease recommendation carries negative delta.
- maintain recommendation has zero delta.
- explanation/reason code is non-empty.

### Task 2 — Add recommendation use-case tests

Objective: specify algorithm before implementation.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RecommendWeightAdjustmentUseCase.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RecommendWeightAdjustmentUseCaseTest.kt`

Test cases:

1. High quality completed set recommends INCREASE by one increment.
2. Mixed quality completed set recommends MAINTAIN.
3. Low quality failed set recommends DECREASE.
4. Insufficient data recommends MAINTAIN with LOW confidence or null.
5. Bodyweight/timed exercise returns null.
6. Recommendation clamps at max/min per-cable weight.
7. Uses configured increment and rounds correctly.

Run and confirm failure:

```bash
./gradlew :shared:allTests --tests '*RecommendWeightAdjustmentUseCaseTest*'
```

### Task 3 — Implement recommendation use case

Objective: pass pure domain tests.

Files:

- Modify: `RecommendWeightAdjustmentUseCase.kt`
- Possibly modify: repository interfaces if recent history is needed.

Implementation notes:

- Keep first implementation pure: pass current set summary and optional history into `invoke(...)`.
- Avoid direct SQL in use case unless existing repository patterns require it.
- Use constants for min/max/rounding.
- Reason codes should be stable strings, e.g.:
  - `HIGH_QUALITY_TARGET_COMPLETE`
  - `QUALITY_MIXED_MAINTAIN`
  - `TARGET_MISSED_LOW_QUALITY`
  - `INSUFFICIENT_DATA`

### Task 4 — Register DI and expose coordinator state

Objective: make recommendations available to UI.

Files:

- Modify DI module where use cases are registered.
- Modify: `WorkoutCoordinator.kt`
- Modify: `DefaultWorkoutSessionManager.kt` / `ActiveSessionEngine.kt` where set completion is handled.
- Modify: `MainViewModel.kt`

Steps:

1. Add `MutableStateFlow<WeightAdjustmentRecommendation?>` to `WorkoutCoordinator`.
2. Expose as public `StateFlow`.
3. After set completion and metric persistence, invoke recommendation use case.
4. Set recommendation flow before navigating to next Set Ready screen.
5. Clear recommendation when:
   - workout ends.
   - exercise changes.
   - user dismisses.
   - user applies.

### Task 5 — Add Apply/Dismiss actions

Objective: user can act on suggestion explicitly.

Files:

- Modify: `MainViewModel.kt`
- Modify: workout/session manager if action belongs there.

Actions:

- `applyWeightRecommendation()`:
  - reads current recommendation.
  - calls existing `updateWorkoutParameters` with recommended weight.
  - clears recommendation.
- `dismissWeightRecommendation()`:
  - clears recommendation for current set/exercise.

Safety:

- Do not update routine defaults.
- Do not apply during active set.
- If workout state is Active, defer or reject apply.

### Task 6 — Add SetReady UI card

Objective: show recommendation at the point user can safely apply it.

Files:

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
- Optionally create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightRecommendationCard.kt`
- Modify: `WorkoutUiState.kt` / action interfaces if needed.

Card content:

- title: “Weight suggestion”
- body examples:
  - “Try +2.5 kg per cable next set.”
  - “Keep this weight — rep quality was mixed.”
  - “Consider -2.5 kg per cable — form quality dropped.”
- actions: Apply / Dismiss.
- confidence chip optional.

### Task 7 — Add settings toggle

Objective: let users disable suggestions.

Files:

- Modify preferences/settings model.
- Modify `SettingsTab.kt`.
- Modify strings.

Setting:

- `weightSuggestionsEnabled: Boolean = true` for suggestion-only v1, or false if product wants opt-in.
- No auto-apply setting in this PR unless specifically requested.

### Task 8 — Integration tests

Objective: verify set-completion path computes and applies recommendation.

Tests:

- set completion with high quality populates recommendation flow.
- apply action changes `WorkoutParameters.weightPerCableKg`.
- dismiss clears state.
- disabled setting suppresses recommendation.
- active set cannot apply recommendation mid-set.

## Verification commands

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*RecommendWeight*' --tests '*WeightRecommendation*'
./gradlew :shared:allTests --tests '*Workout*Recommendation*'
./gradlew :androidApp:assembleDebug
```

## Manual QA

1. Complete a set with strong quality; verify suggestion appears before next set.
2. Tap Apply; verify weight changes by configured increment.
3. Tap Dismiss; verify no weight change.
4. Complete low-quality/failed set; verify maintain/decrease behavior.
5. Start bodyweight/timed set; verify no irrelevant weight suggestion.
6. Restart routine; verify saved routine weight was not changed.

## Risks

- Over-aggressive recommendations can create safety issues. Keep rules conservative.
- Rep quality may be premium-gated; non-premium behavior must be graceful.
- Per-cable vs total displayed load must be clear in UI copy.
- Metric persistence timing may mean recommendation needs to compute from in-memory set summary rather than freshly queried SQL.

## Acceptance criteria

- App computes a safe next-weight recommendation after qualifying sets.
- Recommendation includes direction, amount, confidence/reason.
- User can Apply or Dismiss.
- No automatic weight change occurs without explicit Apply.
- Saved routines are not mutated.
- Feature can be disabled in settings.

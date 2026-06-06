# Issue #495 — Active Recovery / Heavy Deload Routine Modifiers Implementation Plan

> For Hermes: implement this plan as its own PR. Use subagent-driven development with at most 3 concurrent agents. This PR changes routine-start behavior, so verify normal routine start remains unchanged.

GitHub issue: https://github.com/9thlevelsoftware/Project-Phoenix-MP/issues/495
Branch: `feat/495-routine-deload-modifiers`
PR scope: one-shot routine launch modifiers for Active Recovery and Heavy Deload.

## Goal

Before starting an existing routine, allow the user to apply a temporary modifier:

- Active Recovery: reduce working weights to 50-60% 1RM, keep working reps, reduce/simplify warmups.
- Heavy Deload: reduce volume/reps to 50-60%, keep weights, scale warmup reps.

The saved routine must not be modified.

## Current state verified in repo

Relevant files:

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`
  - `Routine`, `RoutineExercise`, `setReps`, `weightPerCableKg`, `setWeightsPerCableKg`, `warmupSets`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveRoutineWeightsUseCase.kt`
  - resolves PR/percentage-based routine weights.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`
  - `RoutineCard`, kebab menu, long-press selection mode.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DailyRoutinesScreen.kt`
  - routine start callback path.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
  - `enterRoutineOverview(routine)` and workout entry methods.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt`
  - `loadRoutine`, `loadRoutineAsync`, `enterRoutineOverview`, `loadRoutineInternal`.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
  - warmup execution reads current exercise `warmupSets`; transformed routine should flow through naturally.

## Architecture

Implement modifiers as ephemeral routine transforms applied at launch time.

New domain model:

```kotlin
enum class RoutineModifierType { ACTIVE_RECOVERY, HEAVY_DELOAD }

data class AppliedRoutineModifier(
    val type: RoutineModifierType,
    val percent: Int,
)
```

New use case:

- `ApplyRoutineModifierUseCase`

Important ordering:

1. Resolve percent-of-PR routine weights to absolute kg.
2. Apply modifier transform to resolved routine.
3. Load transformed routine into normal routine flow.

This avoids incorrect behavior for routines using percent-of-PR weights.

## Product behavior for v1

Entry point:

- Add actions to routine card kebab menu:
  - “Start Active Recovery…”
  - “Start Heavy Deload…”
- Do not replace long-press behavior in v1 because long-press already supports selection mode.

Dialog:

- User chooses 50%, 55%, or 60%.
- Dialog explains what will change.
- Confirm starts routine overview with transformed routine.

Persistence:

- no database migration.
- no saved routine mutation.
- no portal integration in this PR.

## Algorithm

### Active Recovery

For each cable-loaded exercise:

- Determine baseline 1RM:
  1. exercise/user stored 1RM if available.
  2. max weight PR if available.
  3. current resolved routine weight as fallback.
- New working weight = `baseline1RM * percent / 100`.
- Round to nearest 0.5kg and clamp.
- Working reps remain unchanged.
- Per-set weights, if present, are each recomputed/scaled consistently.

Warmups:

- If warmup sets exist:
  - keep only first warmup set.
  - drop all later warmup sets.
  - scale first warmup reps by percent OR keep reps unchanged depending on clarified product decision.

Recommended v1 interpretation of ambiguous issue text:

- Working reps unchanged.
- First warmup reps scaled by selected percent because issue says “apply modifier on weight and reps for the first warm-up set”.
- Other warmups dropped.

### Heavy Deload

For each exercise:

- Working weights unchanged.
- Working reps scaled by `percent / 100`.
- Each result is rounded and `coerceAtLeast(1)`.
- Warmup reps are also scaled.
- Warmup weights/percentages unchanged.
- Keep all warmup sets.

## Tasks

### Task 1 — Add model and pure transform tests

Objective: specify modifier behavior with no UI/workout flow involved.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/RoutineModifier.kt`
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyRoutineModifierUseCase.kt`
- Create test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ApplyRoutineModifierUseCaseTest.kt`

Tests:

1. Active Recovery scales working weights to selected percent of baseline.
2. Active Recovery keeps working reps unchanged.
3. Active Recovery drops warmups after first.
4. Active Recovery scales first warmup reps according to chosen v1 policy.
5. Heavy Deload keeps weights unchanged.
6. Heavy Deload scales working reps.
7. Heavy Deload scales all warmup reps and keeps all warmups.
8. Bodyweight exercise weight is not modified.
9. Superset IDs/order are preserved.
10. Transform does not mutate input routine instance.
11. Results are rounded/clamped.

Run expected failing tests:

```bash
./gradlew :shared:allTests --tests '*ApplyRoutineModifierUseCaseTest*'
```

### Task 2 — Implement transform use case

Objective: pass pure transform tests.

Files:

- Modify: `RoutineModifier.kt`
- Modify: `ApplyRoutineModifierUseCase.kt`
- Possibly inspect/use: `ResolveRoutineWeightsUseCase.kt`, PR/exercise repositories.

Implementation notes:

- Keep transform deterministic and pure where possible.
- If repositories are required for 1RM lookup, separate baseline lookup from transform:
  - `resolveBaselines(...)`
  - `applyModifier(resolvedRoutine, baselines, modifier)`
- Use copy operations; never mutate input lists.

### Task 3 — Add flow-level tests for resolve-then-modify ordering

Objective: prevent PR% ordering bugs.

Files:

- Test: existing routine flow tests near `DWSMRoutineFlowTest.kt` / `WarmupProgressionTest.kt`, or new `RoutineModifierFlowTest.kt`.

Tests:

- percent-of-PR routine resolves absolute weight before Active Recovery modifier.
- start-with-modifier loads adjusted routine into coordinator.
- saved routine repository still contains original weights/reps.

### Task 4 — Wire RoutineFlowManager

Objective: add a launch path that applies modifier once.

Files:

- Modify: `RoutineFlowManager.kt`
- Modify DI constructor if needed.
- Modify: `MainViewModel.kt`

Add method options:

- `enterRoutineOverview(routine: Routine, modifier: AppliedRoutineModifier)`
- or `startRoutineWithModifier(routine, modifier)` at ViewModel level.

Internal flow:

1. `val resolved = resolveRoutineWeights(routine)`
2. `val adjusted = applyRoutineModifier(resolved, modifier)`
3. `loadRoutineInternal(adjusted)`
4. navigate to routine overview as current normal path does.

Do not add modifier state to persisted `Routine`.

### Task 5 — Add modifier dialog

Objective: collect modifier percent and confirm.

Files:

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/RoutineModifierDialog.kt`

UI:

- Title based on modifier type.
- Percent selector: 50 / 55 / 60.
- Explanation text:
  - Active Recovery: “Weights reduced to selected 1RM percentage; working reps stay the same; warmups simplified.”
  - Heavy Deload: “Reps reduced to selected volume percentage; weights stay the same.”
- Actions: Cancel / Start.

Tests:

- dialog displays correct text for each type.
- selecting percent invokes callback with expected `AppliedRoutineModifier`.

### Task 6 — Add routine card menu entries

Objective: expose feature without breaking long-press selection.

Files:

- Modify: `RoutinesTab.kt`
- Modify: `DailyRoutinesScreen.kt`

Steps:

1. Add callbacks through `RoutineCard`/`RoutineCardWithActions`:
   - `onStartActiveRecovery(routine)`
   - `onStartHeavyDeload(routine)`
2. Add two `DropdownMenuItem`s to kebab menu.
3. In parent screen, open `RoutineModifierDialog` for selected routine/type.
4. On confirm, call ViewModel start-with-modifier method.

Do not alter long-press selection behavior in this PR.

### Task 7 — Strings/resources

Files:

- Modify all `shared/src/commonMain/composeResources/values*/strings.xml`

Add keys:

- `routine_modifier_active_recovery`
- `routine_modifier_heavy_deload`
- `routine_modifier_percent_label`
- `routine_modifier_start`
- `routine_modifier_cancel`
- dialog descriptions.

### Task 8 — Integration/manual safety tests

Objective: verify actual workout setup receives transformed routine.

Tests:

- loading Active Recovery routine sets first exercise weight to expected adjusted value.
- `_totalWarmupSets` equals 1 when original had multiple warmups.
- Heavy Deload preserves warmup count and scales reps.
- normal start path unchanged.

## Verification commands

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests --tests '*ApplyRoutineModifier*' --tests '*RoutineModifier*'
./gradlew :shared:allTests --tests '*RoutineFlow*' --tests '*Warmup*'
./gradlew :androidApp:assembleDebug
./gradlew :shared:assembleXCFramework
```

## Manual QA

1. Start a routine normally; verify no changes.
2. Start Active Recovery at 50%; verify overview/setup weight is reduced.
3. Verify working reps unchanged.
4. Verify warmup list has only first warmup when original had multiple.
5. Exit and start same routine normally; verify original routine unchanged.
6. Start Heavy Deload at 60%; verify reps reduced, weights unchanged.
7. Test a routine using percent-of-PR weights.
8. Test a superset routine.
9. Test bodyweight exercise in mixed routine.

## Risks

- Active Recovery 1RM baseline may be missing; fallback must be explicit.
- Warmup rep policy is ambiguous; document v1 behavior clearly.
- Applying modifier before PR resolution produces wrong weights.
- Long-press conflict with selection mode; avoid in v1.

## Acceptance criteria

- Routine card menu offers Active Recovery and Heavy Deload starts.
- User can pick 50/55/60% and start routine.
- Active Recovery changes weights and warmups according to spec.
- Heavy Deload changes reps/volume according to spec.
- Saved routine remains unchanged.
- Normal routine start path is unchanged.

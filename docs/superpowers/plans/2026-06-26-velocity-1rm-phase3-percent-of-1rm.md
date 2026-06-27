# Velocity-1RM Phase 3 — `% of 1RM` Scaling Basis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let users program routine-set weights as a percentage of their velocity-estimated 1RM (in addition to the existing `% of PR`), selectable per routine-exercise and via a system-wide default.

**Architecture:** Additive — introduce a `ScalingBasis { MAX_WEIGHT_PR, MAX_VOLUME_PR, ESTIMATED_1RM }` enum and a new nullable `scalingBasis` column on `RoutineExercise`. The existing **synced** `prTypeForScaling` field is left untouched; when `scalingBasis` is null (legacy rows) it is derived from `prTypeForScaling`, so nothing about the portal sync contract changes. `ResolveRoutineWeightsUseCase` resolves `ESTIMATED_1RM` from the latest passing velocity estimate, falling back to the stored true 1RM → PR → absolute. A system-wide `defaultScalingBasis` preference seeds new routine exercises.

**Tech Stack:** Kotlin Multiplatform, SQLDelight 2.2.1, Koin, Compose, kotlin.test.

Builds on the merged foundation (Phases 1–2): `VelocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)` and `Exercise.oneRepMaxKg` already exist.

## Global Constraints

- **Do NOT touch the synced `prTypeForScaling` field** (it ships to the portal via `SqlDelightSyncRepository`). `scalingBasis` is a new, mobile-local, nullable column.
- `scalingBasis` is nullable; when null, derive via: `MAX_WEIGHT → MAX_WEIGHT_PR`, `MAX_VOLUME → MAX_VOLUME_PR`. Expose this as `RoutineExercise.effectiveScalingBasis: ScalingBasis`.
- `usePercentOfPR` remains the on/off gate for percentage scaling; `effectiveScalingBasis` only chooses the baseline.
- Weights are per-cable throughout. Resolution order for `ESTIMATED_1RM`: latest **passing** velocity estimate (per-cable) → `Exercise.oneRepMaxKg` → matching PR → absolute weight.
- Adding a column requires the project's coordinated update: `.sq` CREATE + both `insertRoutineExercise`/`insertRoutineExerciseIgnore` + the RoutineExercise select mapper, a new `38.sqm`, `MigrationStatements` `38 ->`, `SchemaManifest` (heal op), **`shared/build.gradle.kts` `version = 38 → 39`**, and **`SchemaParityTest.CURRENT_VERSION 38L → 39L`**. The build's schema-manifest validator + `SchemaParityTest`/`SchemaManifestTest` enforce consistency.
- Module test task is `:shared:testAndroidHostTest` (NOT `testDebugUnitTest`). SQLDelight regen: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface`.
- Branch: `fix/issue-517-review-followups` (this work lands on PR #598). Only `git add` files you change.

---

## File Structure

**Create:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ScalingBasis.kt` — enum + derivation helper.
- `shared/src/commonMain/sqldelight/.../migrations/38.sqm`.

**Modify:**
- `domain/model/Routine.kt` — `RoutineExercise.scalingBasis` field + `effectiveScalingBasis`.
- `sqldelight/.../VitruvianDatabase.sq` — RoutineExercise CREATE + both inserts + select.
- `data/local/MigrationStatements.kt`, `data/local/SchemaManifest.kt`, `shared/build.gradle.kts`, the `SchemaParityTest` version constant.
- `data/repository/SqlDelightWorkoutRepository.kt` — RoutineExercise read/write mapper for the new column.
- `domain/usecase/ResolveRoutineWeightsUseCase.kt` — `ESTIMATED_1RM` branch (+ inject `VelocityOneRepMaxRepository`).
- `domain/model/UserPreferences.kt`, `data/preferences/PreferencesManager.kt`, `presentation/manager/SettingsManager.kt` — `defaultScalingBasis` preference.
- `presentation/viewmodel/ExerciseConfigViewModel.kt` + the routine-exercise editor screen + a settings screen — UI for the basis choice and the global default.
- `di/DomainModule.kt` — `ResolveRoutineWeightsUseCase` constructor arg.

---

### Task 1: `ScalingBasis` enum + `RoutineExercise.scalingBasis` / `effectiveScalingBasis`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ScalingBasis.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/ScalingBasisTest.kt`

**Interfaces:**
- Produces: `enum class ScalingBasis { MAX_WEIGHT_PR, MAX_VOLUME_PR, ESTIMATED_1RM }`; `fun ScalingBasis.Companion.fromPrType(prType: PRType): ScalingBasis`; `RoutineExercise.scalingBasis: ScalingBasis? = null`; `val RoutineExercise.effectiveScalingBasis: ScalingBasis`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScalingBasisTest {
    @Test fun `fromPrType maps PR types`() {
        assertEquals(ScalingBasis.MAX_WEIGHT_PR, ScalingBasis.fromPrType(PRType.MAX_WEIGHT))
        assertEquals(ScalingBasis.MAX_VOLUME_PR, ScalingBasis.fromPrType(PRType.MAX_VOLUME))
    }

    @Test fun `effectiveScalingBasis uses explicit value when set`() {
        val ex = sampleRoutineExercise().copy(scalingBasis = ScalingBasis.ESTIMATED_1RM)
        assertEquals(ScalingBasis.ESTIMATED_1RM, ex.effectiveScalingBasis)
    }

    @Test fun `effectiveScalingBasis derives from prTypeForScaling when null`() {
        val ex = sampleRoutineExercise().copy(scalingBasis = null, prTypeForScaling = PRType.MAX_VOLUME)
        assertEquals(ScalingBasis.MAX_VOLUME_PR, ex.effectiveScalingBasis)
    }
}
```

`sampleRoutineExercise()` — a minimal `RoutineExercise(...)` factory; if no shared test helper exists, build one inline with the required non-default fields (read `RoutineExercise`'s constructor for required params).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests "*ScalingBasisTest*"`
Expected: FAIL — `ScalingBasis` unresolved.

- [ ] **Step 3: Implement**

`ScalingBasis.kt`:
```kotlin
package com.devil.phoenixproject.domain.model

/** Baseline a routine exercise's percentage weight scales from. */
enum class ScalingBasis {
    MAX_WEIGHT_PR,
    MAX_VOLUME_PR,
    ESTIMATED_1RM,
    ;

    companion object {
        fun fromPrType(prType: PRType): ScalingBasis = when (prType) {
            PRType.MAX_WEIGHT -> MAX_WEIGHT_PR
            PRType.MAX_VOLUME -> MAX_VOLUME_PR
        }
    }
}
```

In `Routine.kt`, add to the `RoutineExercise` data class (after `prTypeForScaling`/`setWeightsPercentOfPR`):
```kotlin
val scalingBasis: ScalingBasis? = null, // null = derive from prTypeForScaling (legacy/back-compat)
```
And a computed property in the class body:
```kotlin
/** The resolved scaling baseline; derives from prTypeForScaling for legacy rows. */
val effectiveScalingBasis: ScalingBasis
    get() = scalingBasis ?: ScalingBasis.fromPrType(prTypeForScaling)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testAndroidHostTest --tests "*ScalingBasisTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ScalingBasis.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/ScalingBasisTest.kt
git commit -m "feat(1rm): add ScalingBasis enum + RoutineExercise.scalingBasis (#517)"
```

---

### Task 2: Persist `scalingBasis` on RoutineExercise (schema + migration + mapper)

**Files:**
- Modify: `VitruvianDatabase.sq` (RoutineExercise CREATE + `insertRoutineExercise` + `insertRoutineExerciseIgnore` + the RoutineExercise SELECT mapper), `MigrationStatements.kt`, `SchemaManifest.kt`, `shared/build.gradle.kts`, the `SchemaParityTest` version file, `SqlDelightWorkoutRepository.kt` (RoutineExercise read/write).
- Create: `migrations/38.sqm`
- Test: existing `SchemaParityTest`/`SchemaManifestTest`, plus a round-trip test in `androidHostTest` (`SqlDelightWorkoutRepositoryRoutineScalingTest.kt`).

**Interfaces:**
- The RoutineExercise persisted/read includes `scalingBasis` (stored as nullable TEXT enum name).

- [ ] **Step 1: Schema — add the column**

In `VitruvianDatabase.sq` `CREATE TABLE RoutineExercise (...)`, add a nullable column (near `prTypeForScaling`):
```sql
scalingBasis TEXT,
```
Add `scalingBasis` to the column lists AND the `VALUES (?, …)` of **both** `insertRoutineExercise` and `insertRoutineExerciseIgnore` (one more `?`). Add it to the RoutineExercise SELECT used by the mapper (if it's `SELECT *`, no query change needed, but the generated row gains the column).

- [ ] **Step 2: Migration `38.sqm`**

```sql
-- Migration 38: per-routine-exercise scaling basis (% of estimated 1RM) — issue #517 Phase 3.
ALTER TABLE RoutineExercise ADD COLUMN scalingBasis TEXT;
```

- [ ] **Step 3: MigrationStatements + SchemaManifest + version bumps**

- `MigrationStatements.kt`: add `38 -> listOf("""ALTER TABLE RoutineExercise ADD COLUMN scalingBasis TEXT""")`.
- `SchemaManifest.kt`: add `SchemaHealOperation(table = "RoutineExercise", column = "scalingBasis", sql = "ALTER TABLE RoutineExercise ADD COLUMN scalingBasis TEXT")`.
- `shared/build.gradle.kts`: `version = 38` → `version = 39`; update the comment to `// Version 39 = initial schema (1) + 38 migrations (1.sqm through 38.sqm).`
- `SchemaParityTest`: `CURRENT_VERSION = 38L` → `39L` (grep `CURRENT_VERSION` to locate).

- [ ] **Step 4: Mapper — read/write the column** in `SqlDelightWorkoutRepository.kt`

In the RoutineExercise row→domain mapper, add:
```kotlin
scalingBasis = row.scalingBasis?.let { runCatching { ScalingBasis.valueOf(it) }.getOrNull() },
```
In both `insertRoutineExercise`/`insertRoutineExerciseIgnore` calls, pass:
```kotlin
scalingBasis = routineExercise.scalingBasis?.name,
```
Update any other RoutineExercise insert call sites the compiler flags (grep `insertRoutineExercise(`).

- [ ] **Step 5: Round-trip test (TDD)**

```kotlin
// androidHostTest — SqlDelightWorkoutRepositoryRoutineScalingTest.kt
@Test fun `routine exercise persists and reads back scalingBasis`() = runTest {
    val db = createInMemoryTestDatabase()
    val repo = SqlDelightWorkoutRepository(db /* + prod deps */)
    val routineId = seedRoutine(db) // create a Routine FK row via existing helper/insert
    val ex = sampleRoutineExercise().copy(routineId = routineId, scalingBasis = ScalingBasis.ESTIMATED_1RM)
    repo.saveRoutineExercise(ex) // use the repo's actual save API (read the interface)
    val read = repo.getRoutineExercises(routineId).first { it.id == ex.id }
    assertEquals(ScalingBasis.ESTIMATED_1RM, read.scalingBasis)
}
```
Adapt to the repository's real save/read method names and the in-memory harness (mirror an existing `SqlDelightWorkoutRepository`/routine test). If no routine-save test harness exists, build the minimal one following existing patterns.

- [ ] **Step 6: Regen + run parity + round-trip tests**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface :shared:testAndroidHostTest --tests "*SchemaManifestTest*" --tests "*SchemaParityTest*" --tests "*RoutineScalingTest*"`
Expected: PASS. Reconcile `.sq` vs `SchemaManifest` if parity fails.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local shared/build.gradle.kts shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt shared/src/androidHostTest
# also the SchemaParityTest version file
git commit -m "feat(1rm): persist RoutineExercise.scalingBasis, migration 38 (#517)"
```

---

### Task 3: `ResolveRoutineWeightsUseCase` — `ESTIMATED_1RM` branch

**Files:**
- Modify: `domain/usecase/ResolveRoutineWeightsUseCase.kt`, `di/DomainModule.kt`
- Test: `shared/src/commonTest/.../usecase/ResolveRoutineWeightsUseCaseTest.kt`

**Interfaces:**
- Consumes: `VelocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId): VelocityOneRepMaxEntity?`.
- The use case constructor gains a `velocityOneRepMaxRepository: VelocityOneRepMaxRepository` param.

- [ ] **Step 1: Write failing tests**

Add cases to the existing test:
```kotlin
@Test fun `ESTIMATED_1RM uses latest passing velocity estimate`() = runTest {
    // fake velocityRepo returns estimate 100f (per-cable) for ex1/default
    val ex = routineExercise(exerciseId = "ex1", usePercentOfPR = true, weightPercentOfPR = 80,
        scalingBasis = ScalingBasis.ESTIMATED_1RM)
    val resolved = useCase(ex, profileId = "default")
    assertEquals(80f, resolved.baseWeight) // 80% of 100
}

@Test fun `ESTIMATED_1RM falls back to stored 1RM then PR when no estimate`() = runTest {
    // velocityRepo returns null; exercise.oneRepMaxKg = 120f -> 80% = 96f
    ...
    assertEquals(96f, resolved.baseWeight)
}
```
Use the existing fakes in this test file; add a `FakeVelocityOneRepMaxRepository` returning a configurable `getLatestPassing`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :shared:testAndroidHostTest --tests "*ResolveRoutineWeightsUseCaseTest*"`
Expected: FAIL (constructor arity / ESTIMATED_1RM not handled).

- [ ] **Step 3: Implement**

Add the constructor param and branch on `exercise.effectiveScalingBasis`:
```kotlin
val scalingWeight: Float? = when (exercise.effectiveScalingBasis) {
    ScalingBasis.MAX_WEIGHT_PR -> prRepository.getBestWeightPRForWorkoutMode(exerciseId, mode.displayName, profileId)?.weightPerCableKg?.takeIf { it > 0 }
    ScalingBasis.MAX_VOLUME_PR -> prRepository.getBestVolumePRForWorkoutMode(exerciseId, mode.displayName, profileId)?.weightPerCableKg?.takeIf { it > 0 }
    ScalingBasis.ESTIMATED_1RM -> velocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)?.estimatedPerCableKg?.takeIf { it > 0 }
} ?: exerciseRepository.getExerciseById(exerciseId)?.oneRepMaxKg?.takeIf { it > 0 }
```
Keep the existing fallback-to-absolute behavior when `scalingWeight` is null. (Refactor the current `when (exercise.prTypeForScaling)` block to this `effectiveScalingBasis` form — behavior for the two PR bases is unchanged.)

`DomainModule.kt`: `factory { ResolveRoutineWeightsUseCase(get(), get(), get()) }` (add the velocity repo).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "*ResolveRoutineWeightsUseCaseTest*"`
Expected: PASS (existing + new cases).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveRoutineWeightsUseCase.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DomainModule.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ResolveRoutineWeightsUseCaseTest.kt
git commit -m "feat(1rm): resolve % of estimated-1RM in routine weights (#517)"
```

---

### Task 4: System-wide `defaultScalingBasis` preference

**Files:**
- Modify: `domain/model/UserPreferences.kt`, `data/preferences/PreferencesManager.kt` (serialize/restore the new field), `presentation/manager/SettingsManager.kt` (StateFlow + setter).
- Test: `androidHostTest`/`commonTest` for the preference round-trip (mirror an existing PreferencesManager test).

**Interfaces:**
- Produces: `UserPreferences.defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR`; `SettingsManager.defaultScalingBasis: StateFlow<ScalingBasis>` + `setDefaultScalingBasis(basis: ScalingBasis)`.

- [ ] **Step 1: Write failing test** — assert a set value persists through `PreferencesManager` round-trip (follow the existing preferences test pattern; if persistence is JSON/Settings-backed, assert via the flow after `setDefaultScalingBasis`).

- [ ] **Step 2: Run to verify failure.** `./gradlew :shared:testAndroidHostTest --tests "*Preferences*"` (or the relevant settings test) — FAIL.

- [ ] **Step 3: Implement**
- `UserPreferences`: add `val defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,`.
- `PreferencesManager`: add the field to whatever (de)serializes `UserPreferences` (store `.name`, restore via `runCatching { ScalingBasis.valueOf(it) }.getOrDefault(MAX_WEIGHT_PR)`); follow the exact pattern used for `repCountTiming` (also an enum-valued pref).
- `SettingsManager`: add the StateFlow + `fun setDefaultScalingBasis(basis: ScalingBasis) { preferencesManager.update { it.copy(defaultScalingBasis = basis) } }` (match the existing setter style, e.g. `setRepCountTiming`).

- [ ] **Step 4: Run to verify pass.**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(1rm): add system-wide defaultScalingBasis preference (#517)"
```

---

### Task 5: UI — per-exercise basis choice + global default toggle

**Files:**
- Modify: `presentation/viewmodel/ExerciseConfigViewModel.kt` (expose/set `scalingBasis`; seed new exercises from `settingsManager.defaultScalingBasis`), the routine-exercise editor screen (the existing `% of PR` control — add a basis selector incl. "Estimated 1RM"), and the settings screen (a control bound to `setDefaultScalingBasis`).

**Interfaces:**
- Consumes: Tasks 1, 4. The editor writes `RoutineExercise.scalingBasis`; new exercises default to the system `defaultScalingBasis`.

- [ ] **Step 1: ViewModel** — read the current `% of PR` config flow in `ExerciseConfigViewModel` (grep `prTypeForScaling`), add `scalingBasis` to the editable config state with a setter, and when creating a new routine exercise, initialise `scalingBasis = settingsManager.defaultScalingBasis.value`. Add a unit test if the ViewModel has a testable surface (mirror `ExerciseConfigViewModelTest`).

- [ ] **Step 2: Editor UI** — in the routine-exercise editor where `usePercentOfPR` / `prTypeForScaling` are surfaced, add a basis selector with three options: "Max-weight PR", "Max-volume PR", "Estimated 1RM (velocity)". Bind to the ViewModel. Reuse the screen's existing selector/segmented-control component; do not invent new styling.

- [ ] **Step 3: Settings UI** — add a control (segmented/dropdown) for the system-wide default, bound to `settingsManager.defaultScalingBasis` / `setDefaultScalingBasis`, near the other workout-programming settings.

- [ ] **Step 4: Verify (compile + manual handoff)**

Run: `./gradlew :androidApp:assembleDebug` → BUILD SUCCESSFUL; `./gradlew :shared:testAndroidHostTest` → GREEN.
Manual handoff (no Compose UI test harness): set a routine exercise to "Estimated 1RM", confirm at workout start the weight resolves from the velocity estimate; toggle the global default and confirm new exercises pick it up.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(1rm): UI for % of 1RM scaling basis + global default (#517)"
```

---

## Self-Review

**Spec coverage (design §4):** third scaling basis (`ESTIMATED_1RM`) — Tasks 1–3 ✓; system-wide default — Task 4 ✓; resolution falls back true-1RM → PR → absolute — Task 3 ✓; per-exercise + global UI — Task 5 ✓; synced `prTypeForScaling` untouched (additive `scalingBasis`) — Tasks 1–2 ✓.

**Placeholder scan:** Tasks 4–5 give exact patterns to mirror (`repCountTiming` pref, existing selector components) rather than literal code because they hook into screens whose structure must be read first — each names the concrete file and the existing pattern to copy. No "TBD"/"add validation".

**Type consistency:** `ScalingBasis` (3 values), `effectiveScalingBasis`, `getLatestPassing(...).estimatedPerCableKg`, `defaultScalingBasis`, `setDefaultScalingBasis` used consistently across tasks.

**Deferred (later plans):** distinct badges (Phase 4), backfill (Phase 5), portal parity (Phase 6 — phoenix-portal repo, cannot ride this mobile PR).

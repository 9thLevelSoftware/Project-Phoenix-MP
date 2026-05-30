# 1RM Parity (Mobile) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the mobile app the single source of truth for the estimated 1RM number — compute it once with a canonical hybrid formula and ship it to the portal in the sync payload.

**Architecture:** Add a hybrid (Brzycki ≤10 reps, Epley >10) estimator to `OneRepMaxCalculator`, replace the two duplicated hardcoded Epley copies with it, then add a per-exercise `estimatedOneRepMaxKg` field to the push DTO and populate it in `PortalSyncAdapter`. The portal stores this value verbatim (its plan).

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, kotlin.test, Gradle.

**Counterpart:** `phoenix-portal` plan `docs/superpowers/plans/2026-05-30-1rm-parity-portal.md`. The wire field is backward compatible (optional), so either side can deploy first; the portal falls back to recomputing when the field is absent.

**Scope:** This plan is **Phase 1 (parity)** only. Phase 2 (manual-input + assessment persistence) is outlined at the end and gets its own plan after Phase 1 lands.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt` | Modify `OneRepMaxCalculator` (lines 98-107) | Canonical 1RM formulas: `epley`, `brzycki`, `estimate` (hybrid) |
| `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/OneRepMaxCalculatorTest.kt` | Create | Unit tests for the formulas |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt` | Modify private `calculateOneRepMax` (lines 755-758) | Delegate to canonical calculator |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExercisesTab.kt` | Modify private `calculateOneRepMax` (lines 340-344) | Delegate to canonical calculator |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt` | Modify `PortalExerciseDto` (lines 79-87) | Add `estimatedOneRepMaxKg` wire field |
| `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt` | Modify `buildPortalExerciseWithTelemetry` (lines 276-284) | Populate `estimatedOneRepMaxKg` |
| `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt` | Modify (add test) | Assert the field is populated with the hybrid value |
| `CLAUDE.md` (repo root) | Modify | Document the hybrid as a parity-critical constant |

---

## Task 1: Canonical hybrid in `OneRepMaxCalculator`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt:98-107`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/OneRepMaxCalculatorTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/OneRepMaxCalculatorTest.kt`:

```kotlin
package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OneRepMaxCalculatorTest {

    private val tol = 0.01f

    @Test
    fun `epley matches formula for multi-rep sets`() {
        // 100 * (1 + 10/30) = 133.333
        assertEquals(133.33f, OneRepMaxCalculator.epley(100f, 10), tol)
    }

    @Test
    fun `epley returns weight for single rep and zero for invalid`() {
        assertEquals(100f, OneRepMaxCalculator.epley(100f, 1), tol)
        assertEquals(0f, OneRepMaxCalculator.epley(100f, 0), tol)
    }

    @Test
    fun `brzycki matches formula for low reps`() {
        // 100 * 36 / (37 - 5) = 112.5
        assertEquals(112.5f, OneRepMaxCalculator.brzycki(100f, 5), tol)
    }

    @Test
    fun `estimate uses brzycki at or below ten reps`() {
        // 100 * 36 / 32 = 112.5
        assertEquals(112.5f, OneRepMaxCalculator.estimate(100f, 5), tol)
    }

    @Test
    fun `estimate is continuous at the ten rep boundary`() {
        // Brzycki(10) = 100*36/27 = 133.333 == Epley(10) = 100*(1+10/30)
        assertEquals(133.33f, OneRepMaxCalculator.estimate(100f, 10), tol)
    }

    @Test
    fun `estimate uses epley above ten reps`() {
        // 100 * (1 + 11/30) = 136.667
        assertEquals(136.67f, OneRepMaxCalculator.estimate(100f, 11), tol)
    }

    @Test
    fun `estimate handles single rep and invalid inputs`() {
        assertEquals(100f, OneRepMaxCalculator.estimate(100f, 1), tol)
        assertEquals(0f, OneRepMaxCalculator.estimate(100f, 0), tol)
        assertEquals(0f, OneRepMaxCalculator.estimate(0f, 5), tol)
    }

    @Test
    fun `estimate never evaluates an unsafe brzycki denominator`() {
        // reps > 10 always routes to Epley, so 37 - reps is never <= 0
        assertEquals(100f * (1f + 40f / 30f), OneRepMaxCalculator.estimate(100f, 40), tol)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.util.OneRepMaxCalculatorTest"`
Expected: FAIL — `brzycki` and `estimate` are unresolved references.

- [ ] **Step 3: Write minimal implementation**

In `Constants.kt`, replace the `OneRepMaxCalculator` object (lines 98-107) with:

```kotlin
/**
 * Estimated one-rep max calculators.
 *
 * PARITY-CRITICAL: `estimate()` is the canonical cross-stack 1RM formula.
 * Mobile computes it and ships it to the portal (per-cable kg). The portal
 * MUST NOT use a different formula — see the monorepo parity doctrine.
 */
object OneRepMaxCalculator {
    /** Epley: weight * (1 + reps/30). Robust for any rep count. */
    fun epley(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        return weight * (1f + reps / 30f)
    }

    /** Brzycki: weight * 36 / (37 - reps). Accurate for low reps; invalid for reps >= 37. */
    fun brzycki(weight: Float, reps: Int): Float {
        if (reps <= 0) return 0f
        if (reps == 1) return weight
        if (reps >= 37) return 0f
        return weight * (36f / (37f - reps))
    }

    /**
     * Canonical hybrid estimate: Brzycki for reps <= 10, Epley for reps > 10.
     * Continuous at reps == 10 (both yield weight * 1.3333).
     */
    fun estimate(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 0) return 0f
        if (reps == 1) return weight
        return if (reps <= 10) weight * (36f / (37f - reps))
        else weight * (1f + reps / 30f)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.util.OneRepMaxCalculatorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt \
        shared/src/commonTest/kotlin/com/devil/phoenixproject/util/OneRepMaxCalculatorTest.kt
git commit -m "feat: add canonical hybrid 1RM estimator (Brzycki<=10, Epley>10)"
```

---

## Task 2: Dedupe `ExerciseDetailScreen.calculateOneRepMax`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt:755-758`

This is a refactor verified by compilation + Task 1's tests (the math is now centralized). It also fixes a latent bug: the old `reps <= 0` branch returned `weight` instead of `0`.

- [ ] **Step 1: Replace the duplicated private function body**

Replace lines 755-758:

```kotlin
private fun calculateOneRepMax(weight: Float, reps: Int): Float {
    if (reps <= 0) return weight
    if (reps == 1) return weight
    return weight * (1 + 0.0333f * reps)
}
```

with a delegation to the canonical calculator:

```kotlin
private fun calculateOneRepMax(weight: Float, reps: Int): Float =
    OneRepMaxCalculator.estimate(weight, reps)
```

- [ ] **Step 2: Add the import if missing**

Ensure the file imports the calculator (add near the other `com.devil.phoenixproject.util` imports):

```kotlin
import com.devil.phoenixproject.util.OneRepMaxCalculator
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt
git commit -m "refactor: use canonical 1RM estimator in ExerciseDetailScreen"
```

---

## Task 3: Dedupe `ExercisesTab.calculateOneRepMax`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExercisesTab.kt:340-344`

`ExercisesTab` computes 1RM on **display** weight (`weightPerCableKg * displayLoadMultiplier()`) via `calculateBestOneRepMax`; that call site is unchanged — only the formula is centralized.

- [ ] **Step 1: Replace the duplicated private function body**

Replace lines 340-344:

```kotlin
private fun calculateOneRepMax(weight: Float, reps: Int): Float {
    if (reps <= 0) return weight
    if (reps == 1) return weight
    return weight * (1 + 0.0333f * reps)
}
```

with:

```kotlin
private fun calculateOneRepMax(weight: Float, reps: Int): Float =
    OneRepMaxCalculator.estimate(weight, reps)
```

- [ ] **Step 2: Add the import if missing**

```kotlin
import com.devil.phoenixproject.util.OneRepMaxCalculator
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExercisesTab.kt
git commit -m "refactor: use canonical 1RM estimator in ExercisesTab"
```

> **Note (deferred):** Making `ExercisesTab` display the *stored* `Exercise.one_rep_max_kg` instead of recomputing requires the repository to surface that column on `ExerciseSummary`. The stored column is only populated by assessment/PR-sync until Phase 2 wires manual-input persistence, so this read-consistency change is sequenced into the Phase 2 plan.

---

## Task 4: Add and populate `estimatedOneRepMaxKg` on the push DTO

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt:79-87`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt:276-284`
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt` (add a test)

- [ ] **Step 1: Write the failing test**

Add this test inside `class PortalSyncAdapterTest` (it uses the existing `makeSessionWithReps` helper and `toPortalWorkoutSessions` entry point already used in the file):

```kotlin
    @Test
    fun `exercise dto carries hybrid estimated 1RM per cable`() {
        // 60 kg per cable x 5 reps -> Brzycki: 60 * 36 / 32 = 67.5
        val sessions = listOf(
            makeSessionWithReps(
                sessionId = "s1",
                routineSessionId = null,
                exerciseName = "Bench Press",
                weightPerCableKg = 60f,
                totalReps = 5,
            ),
        )

        val result = PortalSyncAdapter.toPortalWorkoutSessions(sessions, "user-1")

        val estimate = result[0].exercises[0].estimatedOneRepMaxKg
        assertNotNull(estimate)
        assertTrue(abs(estimate - 67.5f) < 0.01f, "expected 67.5, got $estimate")
    }
```

> If `makeSessionWithReps` does not already accept `weightPerCableKg`/`totalReps` params, extend that test helper (defined later in the same file) to set `WorkoutSession.weightPerCableKg` and `WorkoutSession.totalReps`; do not change production code for the helper.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.data.sync.PortalSyncAdapterTest"`
Expected: FAIL — `estimatedOneRepMaxKg` is an unresolved reference on `PortalExerciseDto`.

- [ ] **Step 3a: Add the field to `PortalExerciseDto`**

In `PortalSyncDtos.kt`, replace `PortalExerciseDto` (lines 79-87) with:

```kotlin
@Serializable
data class PortalExerciseDto(
    val id: String,
    val sessionId: String,
    val exerciseId: String? = null,    // exercise_catalog.id for identity preservation (#404)
    val name: String,
    val muscleGroup: String = "General",
    val orderIndex: Int = 0,
    /**
     * Canonical estimated 1RM (per-cable kg) for this exercise in this session.
     * Mobile is the source of truth (see OneRepMaxCalculator.estimate). The
     * portal stores this verbatim in exercise_progress.estimated_1rm_kg and
     * only recomputes when this field is absent (legacy payloads).
     */
    val estimatedOneRepMaxKg: Float? = null,
    val sets: List<PortalSetDto> = emptyList(),
)
```

- [ ] **Step 3b: Populate it in the adapter**

In `PortalSyncAdapter.kt`, replace the `PortalExerciseDto(...)` construction (lines 276-284) with:

```kotlin
        val exercise = PortalExerciseDto(
            id = exerciseId,
            sessionId = portalSessionId,
            exerciseId = session.exerciseId, // Catalog exercise ID for identity preservation (#404)
            name = session.exerciseName ?: "Unknown Exercise",
            muscleGroup = swr.muscleGroup,
            orderIndex = orderIndex,
            // Per-cable estimate from this exercise's single set; matches the
            // set's weightKg (per-cable). Portal applies its x2 display transform.
            estimatedOneRepMaxKg = OneRepMaxCalculator.estimate(
                session.weightPerCableKg,
                session.totalReps,
            ),
            sets = listOf(set),
        )
```

Add the import at the top of `PortalSyncAdapter.kt` if missing:

```kotlin
import com.devil.phoenixproject.util.OneRepMaxCalculator
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:testDebugUnitTest --tests "com.devil.phoenixproject.data.sync.PortalSyncAdapterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt \
        shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt \
        shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt
git commit -m "feat: ship canonical estimated 1RM per exercise in sync payload"
```

---

## Task 5: Document the parity constant

**Files:**
- Modify: `CLAUDE.md` (repo root) — under the sync/parity guidance.

- [ ] **Step 1: Add a parity note**

Append to the sync section of `CLAUDE.md`:

```markdown
### 1RM Estimate Parity (PARITY-CRITICAL)
- Canonical formula: hybrid — Brzycki `w*36/(37-reps)` for reps <= 10, Epley `w*(1+reps/30)` for reps > 10. Continuous at reps == 10.
- Single implementation: `OneRepMaxCalculator.estimate()` (`util/Constants.kt`).
- Mobile computes the estimate per exercise-session (per-cable kg) and ships it as `PortalExerciseDto.estimatedOneRepMaxKg`. The portal stores it verbatim in `exercise_progress.estimated_1rm_kg` and recomputes (same hybrid) ONLY when the field is absent (legacy payloads).
- Max-weight PRs (`personal_records`) are a separate metric from the estimated 1RM — do not relabel one as the other.
```

- [ ] **Step 2: Run the full mobile unit suite**

Run: `./gradlew :androidApp:testDebugUnitTest :shared:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document canonical 1RM estimate as a parity constant"
```

---

## Phase 2 (outline — gets its own plan after Phase 1 lands)

Not implemented here; requires reading `TrainingCyclesScreen.kt` (manual-input confirm handler) and `ResolveRoutineWeightsUseCase.kt` first. Scope:

1. **Persist manual 1RM input:** on `OneRepMaxInputScreen` confirm, call `exerciseRepository.updateOneRepMax()` for each entered exercise (wire in `TrainingCyclesScreen`), so the value survives beyond cycle creation.
2. **Assessment feeds scaling:** make `ResolveRoutineWeightsUseCase` fall back to `Exercise.one_rep_max_kg` when no matching PR exists (leaning option (a) from the spec; alternative (b) is a synthetic PR).
3. **`ExercisesTab` reads stored 1RM** (deferred from Task 3) once the column is reliably populated.
4. Tests for each input path.

---

## Self-Review

- **Spec coverage:** Hybrid formula (Task 1) ✓; dedupe both copies (Tasks 2, 3) ✓; wire field + populate (Task 4) ✓; parity doc (Task 5) ✓; Phase 2 input-path persistence — outlined for follow-up per the phased decision ✓.
- **Placeholder scan:** No TBD/TODO; all code blocks concrete. The `makeSessionWithReps` extension note is a conditional instruction, not a placeholder.
- **Type consistency:** `OneRepMaxCalculator.estimate(weight: Float, reps: Int): Float` used identically in Tasks 1-4; `PortalExerciseDto.estimatedOneRepMaxKg: Float?` defined in Task 4 Step 3a and read in the Task 4 test.

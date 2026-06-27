# Velocity-1RM Phase 6 (Display) — Cross-Repo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax. **This plan spans TWO repos** — tasks are tagged `[MOBILE]` (`Project-Phoenix-MP`, Kotlin/Gradle) or `[PORTAL]` (`phoenix-portal`, TS/Deno/Supabase/npm). Run each repo's own toolchain from its own directory.

**Goal:** Sync the mobile-computed velocity-estimated 1RM to the portal and display it as a metric clearly distinct from the existing rep-based (formula) estimate.

**Architecture:** Mobile adds a `velocityEstimatedOneRepMaxKg` field to the per-session exercise sync DTO, populated from the latest passing velocity estimate. The portal stores it in a new nullable `exercise_progress.velocity_estimated_1rm_kg` column (separate from the rep-based `estimated_1rm_kg`), echoes it on pull, and surfaces it in analytics as a distinct, labeled current-value stat. Display-only; no weight-programming changes.

**Tech Stack:** Kotlin Multiplatform (mobile), TypeScript + Deno Edge Functions + Supabase Postgres + React/Vite/Vitest (portal).

Spec: `docs/superpowers/specs/2026-06-26-velocity-1rm-phase6-portal-display-design.md`.

## Global Constraints

- **Never clobber `estimated_1rm_kg`** (rep-based hybrid). `velocity_estimated_1rm_kg` is a brand-new, nullable, additive field/column.
- **Mobile authoritative**: the portal stores/displays verbatim, never recomputes the velocity estimate.
- **Per-cable** in DB/DTO; portal multiplies by `WEIGHT_MULTIPLIER` (2) for display, identical to `estimated_1rm_kg`.
- **Backward compatible**: nullable everywhere; legacy payloads/rows without it show no velocity metric and are unaffected.
- **Per-session model**: `exercise_progress` is keyed `session_id + exercise`. The velocity estimate is a rolling *current* value, so the mobile attaches the **current latest-passing estimate** per exercise to each pushed session-exercise; the portal UI shows the **most-recent non-null** value as the current "Velocity 1RM" stat. **No velocity trend series in this plan** (deferred — the per-session values are not as-of-session).
- **Out of scope** (separate spec): scaling-basis weight-resolution parity in the portal routine builder.
- Mobile tests: `cd Project-Phoenix-MP && ./gradlew :shared:testAndroidHostTest`. Portal tests: `cd phoenix-portal && npm test` (Vitest); types: `npm run typecheck`; edge-fn deploy is the user's call (not in CI here).

---

## File Structure

**[MOBILE] Modify:**
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt` — DTO field.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt` — populate it.
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt` (or wherever `PortalSyncAdapter` is constructed) — inject the velocity repo.
- Tests: `shared/src/commonTest/.../data/sync/PortalSyncAdapterTest.kt`.

**[PORTAL] Modify/Create:**
- `supabase/migrations/<ts>_add_velocity_estimated_1rm_kg.sql` (create).
- `supabase/functions/_shared/exerciseProgressRows.ts` — builder field.
- `supabase/functions/mobile-sync-push/index.ts` — DTO type (pass-through to the builder).
- `supabase/functions/mobile-sync-pull/index.ts` — echo on pull (if it returns exercise_progress).
- `src/lib/database.types.ts` (regen), `src/schemas/transforms.ts` + the exercise-progress schema.
- `src/app/components/ExerciseProgress.tsx`, `src/app/components/analytics/ExerciseDeepDive.tsx`.
- Tests: `supabase/functions/_shared/__tests__/...` (builder), `src/lib/__tests__/sync-exercise-progress.test.ts`, `src/schemas/__tests__/...` (transform).

---

### Task 1 [MOBILE]: Add `velocityEstimatedOneRepMaxKg` to the exercise DTO + populate it

**Files:**
- Modify: `data/sync/PortalSyncDtos.kt` (`PortalExerciseDto`, ~`:79`/`:92`), `data/sync/PortalSyncAdapter.kt` (~`:291`), the DI module constructing `PortalSyncAdapter`.
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt`

**Interfaces:**
- Produces: `PortalExerciseDto.velocityEstimatedOneRepMaxKg: Float? = null`. The adapter sets it from `VelocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)?.estimatedPerCableKg` (per-cable), null when no exerciseId or no passing estimate.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `adapter populates velocityEstimatedOneRepMaxKg from latest passing estimate`() = runTest {
    // Arrange a session with one exercise (exerciseId = "ex1") and a fake VelocityOneRepMaxRepository
    // whose getLatestPassing("ex1", profile) returns an entity with estimatedPerCableKg = 92f.
    val dto = adapter.buildExerciseDto(/* the session/exercise inputs the adapter uses */)
    assertEquals(92f, dto.velocityEstimatedOneRepMaxKg)
}

@Test fun `velocityEstimatedOneRepMaxKg is null when no passing estimate`() = runTest {
    // fake getLatestPassing returns null -> field is null, estimatedOneRepMaxKg (rep-based) still set
    val dto = adapter.buildExerciseDto(/* ... */)
    assertEquals(null, dto.velocityEstimatedOneRepMaxKg)
}
```
READ `PortalSyncAdapter` first to find the actual method that builds a `PortalExerciseDto` (the one setting `estimatedOneRepMaxKg = OneRepMaxCalculator.estimate(...)`), the exact inputs it takes (session, exercise, sets, profileId), and how `PortalSyncAdapterTest` constructs the adapter + fakes. Mirror that exactly; add a `FakeVelocityOneRepMaxRepository` (reuse the one in `commonTest/testutil/` if present).

- [ ] **Step 2: Run to verify failure** — `cd Project-Phoenix-MP && ./gradlew :shared:testAndroidHostTest --tests "*PortalSyncAdapterTest*"` → FAIL (field/param missing).

- [ ] **Step 3: Implement**
- `PortalExerciseDto`: add `val velocityEstimatedOneRepMaxKg: Float? = null,` after `estimatedOneRepMaxKg`.
- `PortalSyncAdapter`: add a `velocityOneRepMaxRepository: VelocityOneRepMaxRepository` constructor param; where it builds the exercise DTO, set `velocityEstimatedOneRepMaxKg = exercise.id?.let { velocityOneRepMaxRepository.getLatestPassing(it, profileId)?.estimatedPerCableKg }`. (The adapter method is `suspend`; if it isn't, make the velocity lookup happen in the suspend context that builds the DTOs — read the call chain.)
- DI: add `get()` for the new param wherever `PortalSyncAdapter(...)` is constructed (grep `PortalSyncAdapter(`).

- [ ] **Step 4: Run to verify pass** — same command → PASS. Then full `./gradlew :shared:testAndroidHostTest` GREEN.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/di shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt
git commit -m "feat(1rm): send velocity-estimated 1RM in exercise sync DTO (#517)"
```

> NOTE: this is a parity-critical sync-DTO change. The portal Tasks 2–4 consume `velocityEstimatedOneRepMaxKg` (camelCase wire format).

---

### Task 2 [PORTAL]: Supabase migration — add the column + regen types

**Files:**
- Create: `phoenix-portal/supabase/migrations/<timestamp>_add_velocity_estimated_1rm_kg.sql`
- Modify (generated): `phoenix-portal/src/lib/database.types.ts`

- [ ] **Step 1: Write the migration**

```sql
-- Add velocity-estimated 1RM (VBT) alongside the rep-based estimated_1rm_kg.
-- Nullable + additive; never overwrites estimated_1rm_kg.
ALTER TABLE public.exercise_progress
  ADD COLUMN IF NOT EXISTS velocity_estimated_1rm_kg numeric;
```
Name the file with the project's migration timestamp convention (match an existing file in `supabase/migrations/`).

- [ ] **Step 2: Apply locally + regen types**

Run (from `phoenix-portal`): apply the migration to the dev DB (the project's usual path — `supabase db push`/`supabase migration up`, or the apply step the repo documents), then `npm run gen:types`.
Expected: `database.types.ts` now lists `velocity_estimated_1rm_kg: number | null` on `exercise_progress` Row/Insert/Update.

- [ ] **Step 3: Verify typecheck** — `npm run typecheck` → no errors.

- [ ] **Step 4: Commit**

```bash
cd phoenix-portal
git add supabase/migrations/ src/lib/database.types.ts
git commit -m "feat(1rm): add exercise_progress.velocity_estimated_1rm_kg column (#517)"
```

---

### Task 3 [PORTAL]: Builder — carry the velocity estimate into `exercise_progress` rows

**Files:**
- Modify: `supabase/functions/_shared/exerciseProgressRows.ts`
- Test: `supabase/functions/_shared/__tests__/exerciseProgressRows.test.ts` (create if absent; else extend)

**Interfaces:**
- Produces: `ProgressExerciseInput.velocityEstimatedOneRepMaxKg?: number | null`; `ExerciseProgressRow.velocity_estimated_1rm_kg: number | null`. The builder copies it **verbatim** (no recompute, no fallback) — null when absent.

- [ ] **Step 1: Write the failing test**

```ts
import { buildExerciseProgressRows } from '../exerciseProgressRows.ts';

Deno.test('carries velocity_estimated_1rm_kg verbatim, null when absent', () => {
  const rows = buildExerciseProgressRows([{
    id: 's1', startedAt: '2026-06-01T00:00:00Z',
    exercises: [
      { name: 'Bench', exerciseId: 'ex1', estimatedOneRepMaxKg: 100, velocityEstimatedOneRepMaxKg: 92, sets: [{ weightKg: 80, actualReps: 5 }] },
      { name: 'Row',   exerciseId: 'ex2', estimatedOneRepMaxKg: 90,  sets: [{ weightKg: 60, actualReps: 5 }] }, // no velocity
    ],
  }], 'user1', null);
  assertEquals(rows[0].velocity_estimated_1rm_kg, 92);
  assertEquals(rows[1].velocity_estimated_1rm_kg, null);
  assertEquals(rows[0].estimated_1rm_kg, 100); // rep-based untouched
});
```
Use the repo's test runner for `_shared` (Deno test, or the Vitest shim the repo uses — match an existing `_shared` test). If `_shared` has no test harness, add the assertion to whichever suite already exercises `buildExerciseProgressRows` (e.g. `src/lib/__tests__/sync-exercise-progress.test.ts`).

- [ ] **Step 2: Run to verify failure** → property missing.

- [ ] **Step 3: Implement**
- Add `velocityEstimatedOneRepMaxKg?: number | null;` to `ProgressExerciseInput`.
- Add `velocity_estimated_1rm_kg: number | null;` to `ExerciseProgressRow`.
- In the `rows.push({...})` object, add `velocity_estimated_1rm_kg: exercise.velocityEstimatedOneRepMaxKg ?? null,`. Do NOT touch the `estimated_1rm_kg` logic.

- [ ] **Step 4: Run to verify pass** → PASS.

- [ ] **Step 5: Commit**

```bash
git add supabase/functions/_shared/exerciseProgressRows.ts supabase/functions/_shared/__tests__/ src/lib/__tests__/sync-exercise-progress.test.ts
git commit -m "feat(1rm): store velocity_estimated_1rm_kg in exercise_progress rows (#517)"
```

---

### Task 4 [PORTAL]: Wire the push DTO type + pull echo

**Files:**
- Modify: `supabase/functions/mobile-sync-push/index.ts` (the exercise/session DTO type whose exercises feed `buildExerciseProgressRows`), `supabase/functions/mobile-sync-pull/index.ts`
- Test: `src/lib/__tests__/sync-push-schema.test.ts`

- [ ] **Step 1: Push side** — In `mobile-sync-push/index.ts`, find the TypeScript interface for the exercise inside a pushed session (the type whose objects are passed as `exercises` into `buildExerciseProgressRows`, carrying `estimatedOneRepMaxKg`). Add `velocityEstimatedOneRepMaxKg?: number | null;` to it. Confirm the mapping that constructs `ProgressExerciseInput` for the builder forwards the field (if it maps explicitly, add `velocityEstimatedOneRepMaxKg: ex.velocityEstimatedOneRepMaxKg ?? null`; if it spreads/passes the object through, the field flows automatically). The `exercise_progress` insert already inserts the full `ExerciseProgressRow` (incl. the new column from Task 3), so no separate insert change is needed — VERIFY this by reading the insert call.

- [ ] **Step 2: Pull side** — In `mobile-sync-pull/index.ts`, check whether the pull returns `exercise_progress` (grep `exercise_progress`). If it does, add `velocity_estimated_1rm_kg` to the selected columns / echoed shape (tolerate null). If the pull does NOT return exercise_progress, note that in the commit message and skip — nothing to do.

- [ ] **Step 3: Test** — extend `sync-push-schema.test.ts` (and `sync-exercise-progress.test.ts`) to push a payload whose exercise has `velocityEstimatedOneRepMaxKg` and assert the resulting `exercise_progress` row carries `velocity_estimated_1rm_kg` (and that a payload WITHOUT it yields null — back-compat). Mirror the existing schema test's harness.
Run: `cd phoenix-portal && npm test -- sync` → GREEN; `npm run typecheck` → clean.

- [ ] **Step 4: Commit**

```bash
git add supabase/functions/mobile-sync-push/index.ts supabase/functions/mobile-sync-pull/index.ts src/lib/__tests__/
git commit -m "feat(1rm): plumb velocity-estimated 1RM through sync push/pull (#517)"
```

---

### Task 5 [PORTAL]: Schema + transform — expose `velocityEstimated1RM`

**Files:**
- Modify: `src/schemas/transforms.ts` (+ the exercise-progress schema/type it feeds)
- Test: `src/schemas/__tests__/transforms.test.ts` (or the existing transforms test)

**Interfaces:**
- Produces: a transformed exercise-progress object with `velocityEstimated1RM?: number` = `velocity_estimated_1rm_kg * WEIGHT_MULTIPLIER` (display units), `undefined` when the column is null.

- [ ] **Step 1: Failing test**

```ts
it('exposes velocityEstimated1RM with the weight multiplier, undefined when null', () => {
  expect(transformExerciseProgress({ ...base, velocity_estimated_1rm_kg: 92 }).velocityEstimated1RM)
    .toBe(92 * WEIGHT_MULTIPLIER);
  expect(transformExerciseProgress({ ...base, velocity_estimated_1rm_kg: null }).velocityEstimated1RM)
    .toBeUndefined();
});
```
READ how `transforms.ts` maps `estimated_1rm_kg` (the existing rep-based field) and mirror it exactly (same `WEIGHT_MULTIPLIER`, same null handling), naming the new field `velocityEstimated1RM`. Match the actual transform function name in the file.

- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the mapping in `transforms.ts` + the schema/type that declares the field.
- [ ] **Step 4: Run → PASS; `npm run typecheck` clean.**
- [ ] **Step 5: Commit** `feat(1rm): transform velocity_estimated_1rm_kg to display units (#517)`

---

### Task 6 [PORTAL]: UI — show the velocity estimate distinctly

**Files:**
- Modify: `src/app/components/ExerciseProgress.tsx`, `src/app/components/analytics/ExerciseDeepDive.tsx`

- [ ] **Step 1: ExerciseProgress.tsx** — locate where the rep-based estimate is shown (the `label="Overall Est. 1RM"` stat ~`:310` and the "Estimated 1RM Trend" panel ~`:454`). Do three things:
  1. **Relabel** the existing stat from "Overall Est. 1RM" → **"Rep-based 1RM (formula)"** so the contrast is explicit.
  2. **Add a distinct stat** **"Velocity 1RM (VBT)"** sourced from the most-recent non-null `velocityEstimated1RM` across the exercise's progress data (`const velocity1RM = [...data].reverse().find(d => d.velocityEstimated1RM != null)?.velocityEstimated1RM`). Render it **only when present** (no data → omit entirely).
  3. Add a short tooltip/help next to the pair: "Rep-based = estimated from weight×reps via a formula. Velocity-based = estimated from cable speed (MCV) captured by the trainer." Reuse the component's existing tooltip/help primitive (do not invent one).
  Do **not** add a velocity series to the trend chart (deferred — see Global Constraints).

- [ ] **Step 2: ExerciseDeepDive.tsx** — make the same distinction wherever it surfaces the estimated 1RM (relabel rep-based + add the velocity stat when present + the same explanatory copy), reusing the screen's existing stat/label components.

- [ ] **Step 3: Verify**
Run: `cd phoenix-portal && npm run typecheck` → clean; `npm test` → GREEN (existing component/snapshot tests still pass; update snapshots intentionally if labels changed, reviewing the diff). If there are no component tests for these, manual verification is the handoff: run `npm run dev`, open an exercise with VBT history → both estimates appear, distinctly labeled; an exercise without it shows only the rep-based one.

- [ ] **Step 4: Commit** `feat(1rm): display velocity 1RM distinctly from rep-based estimate (#517)`

---

## Self-Review

**Spec coverage:** separate field, never clobber — Tasks 1–3 (additive `velocity_estimated_1rm_kg`) ✓; mobile authoritative/verbatim — Task 3 (no recompute) ✓; per-cable ×2 display — Task 5 ✓; push stores / pull echoes — Tasks 3–4 ✓; distinct UI + tooltip + hidden-when-null — Task 6 ✓; backward compatible (nullable) — Tasks 1–5 ✓; scaling-basis parity excluded — not present ✓. Spec open-item resolved: `exercise_progress` is per-session; velocity value is current → shown as a current-value stat, trend deferred (Global Constraints).

**Placeholder scan:** Each portal task says "READ X and mirror it" with the concrete file/anchor because the exact transform/DTO/test-harness names live in the portal repo and must be matched verbatim — read-then-match, not "TBD". Mobile Task 1 carries real code. No "add error handling"/"etc.".

**Type consistency:** `velocityEstimatedOneRepMaxKg` (camelCase, mobile DTO + push DTO + `ProgressExerciseInput`), `velocity_estimated_1rm_kg` (snake_case, DB column + `ExerciseProgressRow`), `velocityEstimated1RM` (portal transform/UI). The case transitions (DTO→row→transform) mirror the existing `estimatedOneRepMaxKg`→`estimated_1rm_kg`→`estimated1RM` chain exactly. ✓

**Cross-repo ordering:** Mobile Task 1 defines the wire field the portal Tasks 3–4 consume; do Task 1 first (or at least before Task 4's schema test that pushes the field). Tasks 2→3→4→5→6 are the portal sequence.

**Deferred / handoffs:** velocity **trend series** (needs as-of-session values); the **scaling-basis parity** plan; Edge Function **deploy** to Supabase (user/ops decision); manual UI verification (no guaranteed component-test coverage).

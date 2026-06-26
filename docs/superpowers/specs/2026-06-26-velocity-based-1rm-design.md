# Velocity-Based 1RM Tracking — Design

**Issue:** [#517](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/517)
**Date:** 2026-06-26
**Status:** Approved (design), pending implementation plan

## Summary

Use Mean Concentric Velocity (MCV) captured by the Vitruvian machine to estimate a
per-exercise one-rep max (1RM) automatically from normal workout sets, track that
estimate over time alongside the existing PR system, fire distinct gamification when it
increases, and allow `% of estimated-1RM` as a weight-programming basis alongside the
existing `% of PR`.

**Guiding principle: reuse, don't rebuild.** The core OLS estimator, per-rep MCV
capture, a 1RM time-series table pattern, PR gamification, and `% of PR` scaling all
already exist. This feature wires them together, adds an automatic estimation pipeline,
an MVT (Minimum Velocity Threshold) model, a new scaling basis, and distinct badges.

## Existing infrastructure (reused)

| Capability | Location | Role here |
| --- | --- | --- |
| OLS load↔velocity regression → 1RM | `domain/assessment/AssessmentEngine.estimateOneRepMax()` | Reused unchanged as the fitting core |
| Per-rep MCV + per-sample loads | `domain/model/RepMetrics.kt` (`avgVelocityConcentric`, mm/s), `data/repository/RepMetricRepository.kt` | Source of (load, MCV) points |
| 1RM estimate persistence pattern | `assessment_results` table, `SqlDelightAssessmentRepository` | Pattern for the new time-series table |
| Authoritative stored 1RM | `Exercise.oneRepMaxKg` (per-cable) | Stays the "true/assessment" 1RM; PR-scaling fallback |
| PR + badges | `GamificationManager`, `BadgeDefinitions`, `BadgeCelebrationDialog`, `SqlDelightPersonalRecordRepository` | Distinct velocity-1RM badge added here |
| `% of PR` scaling | `RoutineExercise.usePercentOfPR/weightPercentOfPR/prTypeForScaling`, `ResolveRoutineWeightsUseCase` | Generalized into a scaling-basis enum |
| Portal estimate field | `exercise_progress.estimated_1rm_kg` (rep-based hybrid today) | A new separate velocity field is added |

## Decisions (owner + product)

Owner-resolved (issue comment): true 1RM is authoritative; `% of 1RM` is a system-wide
setting alongside the velocity options; **distinct** badges for velocity-estimated 1RM;
plot using each completed set; backfill yes.

Product decisions made during brainstorming:

| Decision | Choice |
| --- | --- |
| Estimate data source | **Rolling window** across recent sessions per exercise (≥2 distinct loads required) |
| MVT model | **Per-movement-pattern defaults + global fallback**, personalized override from RIR=0 reps |
| Scaling integration | **Add `ESTIMATED_1RM` as a third `ScalingBasis`** + system-wide default basis setting |
| Quality gate | **Tiered** — always store the estimate + R²; gate display and badges on quality |
| Personalized-MVT trust threshold | ≥3 RIR=0 samples before overriding the default |
| Badge margin | New passing estimate must beat prior best passing estimate by ≥2.5% |
| Portal parity | New separate `velocity_estimated_1rm_kg` field (do NOT overwrite rep-based `estimated_1rm_kg`) |

## Architecture

### 1. Estimation pipeline (domain)

New pure component **`VelocityOneRepMaxEstimator`** (no UI/DB deps) that orchestrates
existing pieces:

- **Point construction.** From a rolling window of completed sets for an
  `exerciseId + profileId`, build `List<LoadVelocityPoint>`. One point per **distinct
  working load** observed in the window; its velocity = mean of per-rep
  `avgVelocityConcentric` over **non-warmup** reps at that load, converted mm/s → m/s.
  This handles both straight sets at different weights across the window and intra-set
  drop sets (the issue's worked example).
- **Window.** Last ~21 days (or last 6 distinct-load sessions), whichever is the tighter
  practical bound, for that exercise+profile. Dedup by load (most-recent observation
  wins). Require **≥2 distinct loads** after dedup or return no estimate.
- **Fit.** Delegate to `AssessmentEngine.estimateOneRepMax(points, config)` unchanged,
  passing the resolved MVT as `config.oneRmVelocityMs`. It already rejects positive slope
  and degenerate inputs and returns R².
- **Weight convention.** Fit in **per-cable** load space (parity with DB / PR /
  `Exercise.oneRepMaxKg`). Because per-cable = total/2, this is a constant x-axis rescale,
  so the extrapolated per-cable 1RM is consistent. (Note: the Assessment Wizard fed
  *total* and divided by 2; the auto pipeline feeds per-cable directly.)

### 2. MVT model

**`MvtProvider`** resolves the MVT in priority order: **personalized → movement-pattern
default → global fallback.**

- **Pattern defaults** (from the issue): horizontal press 0.15, vertical press/OHP 0.20,
  squat 0.30, hinge/deadlift 0.15; **global fallback 0.20 m/s**. Exercise→pattern
  classification is derived from `muscleGroup`/`muscleGroups` and exercise name, leveraging
  the existing `MovementCategory` enum. An optional user-editable per-exercise MVT override
  field is exposed in the exercise editor.
- **Personalized MVT.** Capture the MCV of the last successful rep of sets taken to
  failure (RIR≈0), detectable via existing stall detection + rep data. Persist a rolling
  median per exercise+profile. **Override the default only after ≥3 RIR=0 samples.**

### 3. Persistence & time-series

New table **`velocity_1rm_estimate`**:

- Columns: `id`, `profile_id`, `exercise_id`, `estimated_1rm_per_cable_kg`, `mvt_used`,
  `r2`, `distinct_loads`, `computed_at`, `passed_quality_gate` (bool), plus the standard
  sync columns (`updated_at`, `server_id`, `deleted_at`) consistent with other synced
  tables. This is the trend store.
- **`Exercise.oneRepMaxKg` stays the authoritative "true/assessment 1RM"** and remains
  the PR-scaling fallback. The auto velocity estimate lives only in the new table — they
  are separate metrics, both displayable. (This realizes "true 1RM authoritative.")
- Personalized MVT storage: a small `exercise_mvt` table (`profile_id`, `exercise_id`,
  `personal_mvt`, `sample_count`, `updated_at`) or equivalent columns; user override lives
  on `Exercise`.

**Quality gate (tiered).** Always insert the row with its R². Set
`passed_quality_gate = (distinct_loads ≥ 2) ∧ (r2 ≥ 0.8) ∧ (slope < 0)`. Display
de-emphasizes or hides failing rows; only passing rows feed badges and `% of 1RM` scaling.

### 4. `% of 1RM` scaling

- Generalize `RoutineExercise.prTypeForScaling: PRType` →
  **`scalingBasis: ScalingBasis { MAX_WEIGHT_PR, MAX_VOLUME_PR, ESTIMATED_1RM }`**. A
  migration maps existing `PRType.MAX_WEIGHT → MAX_WEIGHT_PR`,
  `PRType.MAX_VOLUME → MAX_VOLUME_PR`. The existing `usePercentOfPR` toggle is retained
  (it remains the on/off for percentage-based scaling).
- **System-wide `defaultScalingBasis`** setting in `UserPreferences`/`SettingsManager`,
  applied to newly created routine exercises. Satisfies "system-wide setting alongside the
  velocity options."
- `ResolveRoutineWeightsUseCase`: add an `ESTIMATED_1RM` branch resolving to the latest
  **passing** velocity estimate, falling back `Exercise.oneRepMaxKg` (true 1RM) → PR →
  absolute weight.

### 5. Gamification

New **distinct** badge type(s) (e.g. `VELOCITY_1RM_PROGRESS`) added to `BadgeDefinitions`
and `BadgeCategory`, fired through the existing `GamificationManager` when a new **passing**
estimate beats the prior best passing estimate by **≥2.5%**. Kept separate from PR badges
so the two progression signals don't conflate.

### 6. Backfill

A one-time background maintenance job replays historical sets that have persisted MCV
rep-metrics, computes windowed estimates per exercise, and populates the time-series with
`passed_quality_gate` flags. Runs lazily off the startup path and is idempotent (safe to
re-run; keyed by exercise + window/computed_at).

### 7. Portal sync parity ⚠️ cross-project

Per the monorepo CLAUDE.md, `exercise_progress.estimated_1rm_kg` currently holds the
**rep-based hybrid (Brzycki/Epley)** estimate from `OneRepMaxCalculator`. The velocity
estimate is a *different* metric; overwriting that field would silently change its meaning.

**Add a separate `velocity_estimated_1rm_kg`** across the contract:
- Mobile: new field in `PortalExerciseDto` / sync DTOs, populated from the latest passing
  velocity estimate (per-cable).
- Supabase: new column on `exercise_progress`; update `mobile-sync-push` and
  `mobile-sync-pull` Edge Functions.
- Portal: `database.types.ts` regen, `transforms.ts`, and display.

This is a parity-critical change requiring matching work in **phoenix-portal**; it must be
done as the final phase and reviewed against the portal side.

## Phasing (single spec, sequenced)

1. Estimator + MVT model + persistence (engine, no UI).
2. Display estimated-1RM alongside PR (exercise history / progress views).
3. `% of 1RM` scaling basis + system-wide default setting.
4. Distinct velocity-1RM badges.
5. Backfill historical estimates.
6. Portal sync parity (cross-project, separate `velocity_estimated_1rm_kg` field).

## Error handling

- Estimator returns no estimate (not an error) on insufficient/invalid data: <2 distinct
  loads, non-negative slope, identical loads, no non-warmup reps. No crash, no row gated
  as passing.
- Personalized MVT only overrides after the sample threshold; otherwise default applies.
- Sync tolerates a missing `velocity_estimated_1rm_kg` (legacy payloads) without failing.

## Testing

- **`VelocityOneRepMaxEstimator`** unit tests: insufficient/identical loads, positive-slope
  rejection, warmup exclusion, mm/s→m/s and per-cable conversions, window dedup,
  point-per-distinct-load construction, intra-set drop-set case.
- **`MvtProvider`** tests: pattern classification, global fallback, personalized override
  threshold (<3 vs ≥3 samples), rolling-median behavior.
- **`ResolveRoutineWeightsUseCase`** tests: `ESTIMATED_1RM` branch and the full fallback
  chain (estimate → true 1RM → PR → absolute).
- **Migration/schema parity**: new tables + `ScalingBasis` mapping, validated by existing
  `SchemaParityTest` / `SchemaManifestTest`.
- **Backfill** idempotency test.
- **Gamification** trigger test: badge fires only on a passing estimate exceeding prior
  best by ≥2.5%.

## Open items to confirm during planning

- Exact window bound (days vs session count) — pin a single rule in the plan.
- Movement-pattern classification table (explicit exercise/muscle-group → pattern map).
- Whether the Assessment Wizard should also write into `velocity_1rm_estimate` for a
  unified trend (optional, low priority).

# Velocity-1RM Phase 6 (Display) — Portal Parity: Surface the Velocity Estimate — Design

**Issue:** [#517](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/517)
**Date:** 2026-06-26
**Status:** Approved (design), pending implementation plan
**Scope:** Cross-repo — `Project-Phoenix-MP` (mobile) + `phoenix-portal` (web).

## Summary

Sync the mobile-computed **velocity-estimated 1RM** to the portal and display it in the exercise analytics views as a metric that is **clearly distinct** from the existing rep-based (Brzycki/Epley hybrid) estimate, so users never conflate the two.

This is **display/analytics only**. It does NOT change any weight programming. Making the portal's routine builder resolve `% of estimated-1RM` (scaling-basis parity) is a **separate follow-up spec** because it changes parity-critical routine-weight math.

## Decisions

- **Never clobber the rep-based estimate.** `velocity_estimated_1rm_kg` is a brand-new field/column, fully separate from the existing `estimated_1rm_kg` (rep-based hybrid). Both are stored and shown.
- **Mobile is authoritative** for the velocity estimate (it's computed on-device from BLE MCV). The portal stores and displays it verbatim; it never recomputes it.
- **Additive + backward compatible.** The new field is nullable everywhere. Legacy payloads/rows without it simply don't show the velocity metric; the rep-based estimate is untouched.
- **Per-cable convention** (per the monorepo parity rules): the DB/DTO value is per-cable; the portal multiplies by `WEIGHT_MULTIPLIER` (2) for display, exactly as it already does for `estimated_1rm_kg`.
- **UI: obviously distinct.** The two estimates are labeled and visually separated so a user can tell at a glance which is the velocity (VBT) estimate and which is the formula estimate.

## Cross-repo architecture

### A. Mobile (`Project-Phoenix-MP`)
- `data/sync/PortalSyncDtos.kt`: add `val velocityEstimatedOneRepMaxKg: Float? = null` to `PortalExerciseDto` (camelCase wire format), beside the existing `estimatedOneRepMaxKg` (`:92`).
- `data/sync/PortalSyncAdapter.kt`: when building each `PortalExerciseDto` (near `:291` where `estimatedOneRepMaxKg = OneRepMaxCalculator.estimate(...)`), also populate `velocityEstimatedOneRepMaxKg` from `VelocityOneRepMaxRepository.getLatestPassing(exerciseId, profileId)?.estimatedPerCableKg` (per-cable, null when no passing estimate). The adapter gains a dependency on `VelocityOneRepMaxRepository` (already DI-registered).
- Sync-push test: extend the existing push-schema/limits tests to assert the new field serializes (and is omitted/null-safe when absent).

### B. Portal DB (`phoenix-portal`)
- Supabase migration: `ALTER TABLE exercise_progress ADD COLUMN velocity_estimated_1rm_kg numeric;` (nullable).
- Regenerate `src/lib/database.types.ts` (`npm run gen:types`).

### C. Edge Functions (`phoenix-portal/supabase/functions`)
- `mobile-sync-push/index.ts`: add `velocityEstimatedOneRepMaxKg?: number` to the exercise DTO type (near `:345`) and write `velocity_estimated_1rm_kg: a.velocityEstimatedOneRepMaxKg ?? null` into the `exercise_progress` upsert (alongside `estimated_1rm_kg` at `~:2228`). Do NOT touch the existing `estimated_1rm_kg` / hybrid-fallback logic.
- `mobile-sync-pull/index.ts`: include `velocity_estimated_1rm_kg` in the `exercise_progress` selection/echo if the pull returns exercise progress (mobile is authoritative, so pull just echoes it; tolerate null).

### D. Portal types/transforms (`phoenix-portal/src`)
- `src/schemas/transforms.ts` (and the relevant exercise-progress schema): map `velocity_estimated_1rm_kg` → a `velocityEstimated1RM` field, applying `WEIGHT_MULTIPLIER` (2) for display, mirroring how `estimated_1rm_kg` is handled. Null/absent → undefined (metric hidden).

### E. Portal UI (`phoenix-portal/src/app/components`)
In `ExerciseProgress.tsx` and `analytics/ExerciseDeepDive.tsx`:
- Surface the velocity estimate as a **distinct, clearly-labeled** metric next to the existing one. Suggested labels: **"Velocity 1RM (VBT)"** vs relabel the existing **"Overall Est. 1RM"** → **"Rep-based 1RM (formula)"** so the contrast is explicit.
- Add a short tooltip/help text explaining: rep-based = estimated from weight×reps via a formula; velocity-based = estimated from bar/cable speed (MCV) captured by the trainer.
- Trend chart: add a **separate, distinctly-colored velocity series** to the "Estimated 1RM Trend" chart IF the velocity value is available per data point. (Planning detail — confirm how `exercise_progress` rows are keyed: if it's latest-state-per-exercise rather than per-session, the velocity value is a single current stat and the trend series is deferred. Show the current-value stat regardless.)
- The velocity metric is **hidden entirely** when null (no data), so users without VBT history see no change.

## Weight convention (parity-critical)
Per-cable in the DB/DTO; ×2 (`WEIGHT_MULTIPLIER`) for portal display — identical to the existing `estimated_1rm_kg` handling. No new convention introduced.

## Testing
- **Mobile:** push-schema/serialization test for the new DTO field; adapter populates it from the velocity repo (unit test with a fake).
- **Portal:** `sync-push-schema.test.ts` / `sync-exercise-progress.test.ts` extended to cover the new column round-trip; transforms test for the ×2 + null handling; `npm run typecheck` after `gen:types`; a component/render check that the two estimates render distinctly and that the velocity metric is hidden when null.

## Out of scope (separate specs)
- **Scaling-basis parity** — the portal routine builder resolving `% of estimated-1RM` / `% of max-volume PR` like `ResolveRoutineWeightsUseCase`. Parity-critical routine-weight math; its own spec.
- Velocity-1RM trend history in the portal beyond the current value, if `exercise_progress` is latest-state only.

## Open items to confirm during planning
- How `exercise_progress` rows are keyed (latest-state vs per-session) → determines whether a velocity **trend series** is feasible now or is the current-value stat only.
- Whether `mobile-sync-pull` actually returns `exercise_progress` (if not, only push + portal-direct reads matter).
- Exact portal labels/tooltip copy (product/voice decision).

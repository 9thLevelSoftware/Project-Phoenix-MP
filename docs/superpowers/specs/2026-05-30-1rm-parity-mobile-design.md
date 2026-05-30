# 1RM Feature — Parity & Full Functionality (Mobile, Project-Phoenix-MP)

- **Date:** 2026-05-30
- **Repo:** Project-Phoenix-MP (Kotlin Multiplatform)
- **Counterpart spec:** `phoenix-portal/docs/superpowers/specs/2026-05-30-1rm-parity-portal-design.md` (must land together — shared sync contract)
- **Definition of done:** (1) the 1RM number agrees everywhere; (2) every 1RM input path persists
- **Sequencing:** Phase 1 correctness/parity first; Phase 2 input-path persistence as a follow-up increment

---

## Problem statement

"1RM" currently resolves to **three incompatible numbers** across the stack, and the mobile-computed estimate is dropped at the sync boundary. Mobile is the BLE-authoritative source of workout data, so it should own the canonical 1RM estimate.

### Verified findings (mobile-relevant, confirmed by direct inspection)
- Mobile UI/local PR uses **Epley** `w·(1+reps/30)` via `OneRepMaxCalculator.epley` (`Constants.kt:102-106`).
- The portal independently recomputes **Brzycki** server-side and **Epley** client-side → numbers never match.
- Set-level PR hint sends `isPr`, `prType` (`MAX_WEIGHT`/`MAX_VOLUME`), `prPhase`, `prVolume` but **no 1RM estimate** (`PortalSyncAdapter.kt:261-264`).
- Mobile's Epley `oneRepMax` exists on a DTO (`SyncModels.kt:88`) but the push path ignores it → the estimate never reaches the portal.
- Duplicate hardcoded Epley in `ExerciseDetailScreen.kt:755-758` (`w*(1+0.0333f*reps)`); minor bug: `reps<=0` returns `weight` (should be `0`).

### Reported-but-unverified (confirm during implementation)
- A second hardcoded Epley in `ExercisesTab.kt` (audit cited line 419, but the file is 366 lines — line wrong; existence to confirm).
- `ExercisesTab` recomputing from sessions instead of reading stored `Exercise.one_rep_max_kg`.
- Manual `OneRepMaxInputScreen` values never persisted (Phase 2 target).
- VBT Assessment never creating a PersonalRecord / not feeding routine scaling (Phase 2 target).

### What works (must not regress)
`OneRepMaxCalculator.epley`; VBT `AssessmentEngine` (OLS regression, R², tested); PR→Exercise sync for COMBINED phase; `ResolveRoutineWeightsUseCase` percentage resolution; 2× per-cable weight handling; DB schema (columns/queries exist).

---

## Architecture: mobile owns the canonical estimate

### Canonical formula (hybrid, single definition — lives here on mobile)
```
estimate1RM(weight, reps):
  if weight <= 0 || reps <= 0  -> 0
  if reps == 1                 -> weight
  if reps <= 10                -> weight * (36 / (37 - reps))   // Brzycki
  else                         -> weight * (1 + reps / 30)      // Epley
```
Continuous at reps = 10 (Brzycki `36/27` = Epley `1+10/30` = 1.3333). Document as a parity-critical constant in CLAUDE.md.

### Two distinct metrics (do not conflate)
- **Max Weight PR** = heaviest weight lifted (already correct in sync). Keep labeled as max-weight.
- **Estimated 1RM** = the hybrid estimate. Mobile is source of truth; ships it to the portal.

### Shared sync contract (must match the portal spec exactly)
- New optional field `estimatedOneRepMaxKg: Float?` on the exercise/set sync DTO in `SyncModels.kt`.
- Per-cable kg (consistent with the existing weight convention; portal applies its 2× display multiplier).
- Computed per exercise-session as the **best estimate across valid sets** (reps ≥ 1, weight > 0). The hybrid removes the need for the old server-side reps 1–12 clamp.
- Backward compatible: portal falls back to the same hybrid server-side only when the field is absent (legacy payloads).

---

## Phase 1 — Correctness / Parity (mobile tasks)
1. `OneRepMaxCalculator` (`Constants.kt`): add `brzycki(weight, reps)` and `estimate(weight, reps)` (hybrid); keep `epley()`.
2. Replace hardcoded Epley in `ExerciseDetailScreen.kt:755-758` with `OneRepMaxCalculator.estimate()`; fix `reps<=0` → `0`.
3. Confirm/dedupe any `ExercisesTab` 1RM copy to use the calculator; have it read stored `Exercise.one_rep_max_kg` where appropriate.
4. Add `estimatedOneRepMaxKg` to the sync DTO (`SyncModels.kt`); have `PortalSyncAdapter` compute and populate it per exercise-session.

### Tests
- Unit tests for the hybrid: boundaries reps = 1 / 10 / 11 / high / ≤ 0; weight ≤ 0.
- Adapter test: `estimatedOneRepMaxKg` populated correctly (best-across-valid-sets).
- Verify: `./gradlew :androidApp:testDebugUnitTest`.

---

## Phase 2 — Input-path persistence (mobile tasks)
1. **Manual `OneRepMaxInputScreen`:** on confirm, persist to `Exercise.one_rep_max_kg` via `exerciseRepository.updateOneRepMax()` (wire in `TrainingCyclesScreen`) — not just transient cycle state.
2. **VBT Assessment → routine scaling:** make the assessment 1RM feed `ResolveRoutineWeightsUseCase`.
   - **Open sub-decision (resolve at Phase 2 start):** (a) `ResolveRoutineWeightsUseCase` falls back to `Exercise.one_rep_max_kg` when no matching PR exists, or (b) assessment writes a synthetic PR. **Leaning (a).**
3. Tests for both input paths.

---

## Risks / open items
- Wire-contract change must land together with the portal spec (CLAUDE.md parity rule). Backward compatible via the absent-field server fallback.
- `ExercisesTab` specifics unverified — confirm before editing.
- Phase 2 assessment→scaling has the open (a)/(b) sub-decision.

## Out of scope (not selected)
- "Tested vs. estimated" 1RM as a user-facing distinction feature.
- "Edit 1RM from everywhere" beyond Phase 2 manual-input persistence.
- User-selectable formula choice.

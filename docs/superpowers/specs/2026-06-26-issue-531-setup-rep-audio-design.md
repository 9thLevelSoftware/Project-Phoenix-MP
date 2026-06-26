# Issue #531 — Setup-rep audio timing fix

**Date:** 2026-06-26
**Issue:** [#531 — Still some odd sound issues](https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/531)
**Status:** Design approved, pending implementation plan

## Problem

During the **setup-rep phase** (the 3-rep setup screen shown after the start countdown), audio
feedback is misaligned. Reported on iOS (build 18252 / v9.2), tester `ikim67`:

1. **Phantom chirp on entry** — a rep chirp plays the moment the 3-rep setup screen appears,
   before the user moves.
2. **Dropped setup chirps** — only 2 of the 3 setup reps produce a chirp.
3. **Stray mid-set transition tone** — around overall rep 4–5 (i.e. a working rep or two in), an
   "end-of-set–like" sound plays.

A prior adjustment (commit `091c8141`, 2026-06-17) only reworked the **fallback** warmup branch and
added `REP_RECEIVED` diagnostics; it never touched the **primary** warmup path, so all three
symptoms persisted.

## Root cause

All three trace to warmup/setup-rep handling in
`shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt`
(`processModern()`), plus one sound-mapping smell in `ActiveSessionEngine`.

| Symptom | Root cause |
|---------|-----------|
| 1 — phantom chirp on entry | At set start `reset()` zeroes `lastTopCounter`, but the machine's free-running directional `up`/`down` counters carry over from the prior set. The first packet computes a large `upDelta`; the fallback branch (`repsSetCount==0 && repsRomCount==0 && upDelta>0`) instantly saturates `warmupReps` to target and emits `WARMUP_COMPLETED` + `WARMUP_COMPLETE` with no user movement. |
| 2 — dropped chirps | The primary warmup branch emits `WARMUP_COMPLETED` **once per packet** but jumps the count straight to the machine value (`warmupReps = repsRomCount.coerceAtMost(warmupTarget)`). A batched/dropped BLE notification (ROM `0→2` in one packet) yields **one** chirp but **+2** reps. The legacy branch correctly does `repeat(delta){ emit }`; the modern branch does not. |
| 3 — stray transition tone | When dropped chirps leave `warmupReps < warmupTarget`, the first working rep hits the belated path (lines 510–523) and force-fires `WARMUP_COMPLETE`. In `ActiveSessionEngine` that maps to **both** `WARMUP_COMPLETE` **and** `WARMUP_TO_WORKING` — and on iOS both play the same `beepboop` file back-to-back on one shared `AVAudioPlayer`. The stray double-`beepboop` lands a rep or two into the working set. |

## Approach

Surgical fix in `processModern()` + a one-line transition de-dupe in `ActiveSessionEngine`. Keeps the
parent-repo "trust the machine's `repsRomCount`" model. Rejected alternatives: extracting an explicit
warmup state machine (larger refactor, more risk to the Echo/Just Lift/AMRAP paths stabilized by
#553/#538) and moving emission into `ActiveSessionEngine` (changes the event contract, risks
regressing working-rep/final-rep sounds).

### 1. Carryover guard (heuristic) — best-effort phantom suppression

Fixes symptom 1, **best-effort**. The original "snapshot every first packet and return without
emitting" idea was **rejected**: four `RepCounterFromMachineTest` cases
(`Issue 210 - first warmup rep registers immediately in modern mode` line 340, `… in legacy mode`
line 316, `… after reset` line 378, `… after resetCountsOnly` line 398) hard-assert that the *first*
packet of a set counts immediately with no baseline — that contract was Issue #210's fix and a blanket
snapshot reintroduces the bug it closed. Inference alone also cannot distinguish a slack-takeup tick
(symptom 1) from a genuine firmware-dropped rep (Issue #553), since both appear as `up` advancing
while `repsRomCount`/`repsSetCount` stay 0.

Chosen heuristic — narrow, preserves both the #210 and #553 contracts:

- Add `private var carryoverChecked = false` (reset in `reset()`/`resetCountsOnly()`).
- At the **top of `processModern()`** (after the `warmupTarget`/`repsRomTotal` sync), on the first
  modern packet of a set only, detect the carryover signature and re-baseline once:
  ```kotlin
  if (!carryoverChecked) {
      carryoverChecked = true
      if (warmupTarget > 0 && warmupReps == 0 &&
          repsRomCount == 0 && repsSetCount == 0 &&
          (up > warmupTarget || down > warmupTarget)
      ) {
          // Directional counters are free-running and already far above the warmup target
          // while the machine reports zero ROM/set reps → stale values carried from the prior
          // set. Re-baseline so the first real movement yields delta ~0, not a phantom warmup rep.
          lastTopCounter = up
          lastCompleteCounter = down
          return
      }
  }
  ```
- **Why this preserves the contracts:** #210 modern feeds `repsRomCount = 1` (fails the
  `repsRomCount == 0` test → no skip, counts normally). #210 legacy uses `processLegacy` (untouched).
  #553 sends an explicit `up = 0, down = 0` baseline packet first (fails the `up > warmupTarget`
  test → no skip), then advances normally. The variable-warmup case uses `warmupTarget = 0` (fails
  the `warmupTarget > 0` test → no skip).
- **Scope limit (documented honestly):** this only catches *large* directional carryover
  (`up`/`down` already past the warmup target). If symptom 1 is instead a *small* slack-takeup tick
  (`up: 0 → 1`), this guard does nothing — but it also introduces no regression. If the phantom
  persists after this ships, the remaining mechanism requires a real `REP_RECEIVED` packet trace to
  resolve. Applied to `processModern` only (the iOS-reported path); legacy firmware is left untouched.

### 2. Per-increment warmup emission

Fixes symptom 2, and removes symptom 3's *late-firing* cause (a timely warmup completion means the
belated working-rep `WARMUP_COMPLETE` path rarely triggers).

- **Per-increment:** when warmup advances, loop one `WARMUP_COMPLETED` per integer step:
  ```
  val target = repsRomCount.coerceAtMost(warmupTarget)
  while (warmupReps < target) {
      warmupReps++
      emit(WARMUP_COMPLETED, warmupReps, workingReps)
  }
  ```
  Apply the same per-increment loop to the `upDelta` fallback branch
  (`val target = (warmupReps + upDelta).coerceAtMost(warmupTarget)`). A batched `0→2→3` ROM report
  now emits exactly 3 chirps. Counts stay absolute-from-machine (no baseline subtraction), preserving
  the Issue #210 first-rep contract.

> **Dropped during planning:** an earlier draft added a `warmupCompleteEmitted` idempotency guard.
> It was removed — the existing control flow already guarantees `WARMUP_COMPLETE` fires at most once
> per set (every emission site is gated on `warmupReps < warmupTarget`; the first to fire sets
> `warmupReps = warmupTarget`, closing all other sites, and the ROM/fallback branches are mutually
> exclusive `else if`). No double-fire sequence is constructible, so the guard had no failing test
> (TDD/YAGNI).

### 3. Single clean transition tone

In `ActiveSessionEngine` `onRepEvent`, the `RepType.WARMUP_COMPLETE` case emits both
`HapticEvent.WARMUP_COMPLETE` and `HapticEvent.WARMUP_TO_WORKING` (both → `beepboop`). Remove the
`WARMUP_TO_WORKING` emission, keeping the single `WARMUP_COMPLETE` (success haptic + one `beepboop`).
The now-unused `WARMUP_TO_WORKING` enum value and its iOS/Android sound mappings stay in place
(removing them would touch the enum + both platform maps for no behavioral gain); flagged as dead.

## Testing (inference-based, no hardware)

New/expanded `RepCounterFromMachineTest` cases asserting the exact emitted-event sequence:

1. Batched ROM `0→2→3` → exactly 3× `WARMUP_COMPLETED` + 1× `WARMUP_COMPLETE`.
2. Sequential ROM `1→2→3` → 3 + 1 (regression guard for the normal case).
3. Carryover guard: first packet `up=8, down=8, repsRomCount=0, repsSetCount=0` (warmupTarget=3) →
   **zero** events (guard re-baselines); then `repsRomCount` `1→2→3` produces exactly 3 warmup
   chirps + 1 `WARMUP_COMPLETE`. Plus a guard-does-not-fire case: first packet `up=1` (small) →
   normal counting (the existing #210 tests already pin this; add an explicit small-`up` carryover
   case for clarity).
4. Single-transition test (engine-level, via `DWSMTestHarness`): invoking the rep-event path with
   `RepType.WARMUP_COMPLETE` emits exactly one `HapticEvent.WARMUP_COMPLETE` and **zero**
   `HapticEvent.WARMUP_TO_WORKING` (the de-dupe).
5. Working-rep counting + final-rep / `WORKOUT_COMPLETE` sequence unaffected (regression).
6. Fallback branch (`repsRomCount==0`, `up` advancing) still progresses warmup per-increment.

## Files & risk

| File | Change |
|------|--------|
| `RepCounterFromMachine.kt` | Per-increment warmup emission in both `processModern()` branches; carryover guard at top of `processModern()`; one new field (`carryoverChecked`) reset in `reset()`/`resetCountsOnly()`. **Primary.** |
| `ActiveSessionEngine.kt` | Remove one `WARMUP_TO_WORKING` emit line. |
| `RepCounterFromMachineTest.kt` | New/expanded cases above. |

**Risk:** Low–moderate.

- Symptoms 2 & 3 (per-increment emission, idempotent `WARMUP_COMPLETE`, transition de-dupe) are
  verified compatible with every existing test — those tests assert final counts and event presence,
  not event multiplicity. High confidence.
- Symptom 1 is a **best-effort heuristic** (large-carryover guard) deliberately scoped to NOT touch
  the #210 first-rep or #553 Echo-fallback contracts. If the real phantom is a small slack-takeup
  tick rather than large carryover, the guard is a no-op (no regression) and the phantom needs a
  packet trace to finish. **Recommend an on-device Echo + normal-mode warmup smoke test before
  merge** to confirm the phantom is gone and Echo warmup still progresses.
- No BLE, sync, or UI contract changes. The `WARMUP_TO_WORKING` enum + its iOS/Android sound maps
  stay (asserted by `HapticEventAudioTest` / `HapticFeedbackAudioRoutingGuardTest`); only its
  emission from `ActiveSessionEngine` is removed.

## Out of scope

- The "what is the 70/58/65 number" question in the issue thread — it's the rep-quality score,
  already answered by the owner.
- The "Echo activity not recorded in history" remark — separate issue.
- Stale-`repsRomCount` carryover on mid-session *resume* (distinct from fresh set start) — the
  baseline snapshot mitigates it via `romBaseline`, but resume edge cases are not the focus here.

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

### 1. Baseline reconciliation — "first-packet snapshot"

Fixes symptom 1.

- Add `private var countersBaselined = false`.
- On the **first `process()` call** of a set, snapshot all four machine counters as the zero
  reference and return **without emitting**:
  - `lastTopCounter = up`
  - `lastCompleteCounter = down`
  - `romBaseline = repsRomCount`
  - `setBaseline = repsSetCount`
- All subsequent counting measures growth beyond the reference:
  - warmup step target uses `(repsRomCount − romBaseline)`
  - working count uses `(repsSetCount − setBaseline)`
- For a normal fresh set the machine starts these at 0, so baselines are 0 → behavior unchanged. For
  carried-over counters the reference absorbs the stale value → no phantom.
- Reset `countersBaselined` (and `romBaseline`/`setBaseline`) in **both** `reset()` and
  `resetCountsOnly()`, so resume/recovery re-baselines to the machine's live position rather than
  re-counting completed reps.

The baseline early-return is placed at the top of `process()` (before dispatch to
`processModern`/`processLegacy`) so both packet formats share it.

### 2. Per-increment warmup emission + idempotent completion

Fixes symptoms 2 and 3.

- **Per-increment:** when warmup advances, loop one `WARMUP_COMPLETED` per integer step:
  ```
  val target = (repsRomCount - romBaseline).coerceAtMost(warmupTarget)
  while (warmupReps < target) {
      warmupReps++
      emit(WARMUP_COMPLETED, warmupReps, workingReps)
  }
  ```
  Apply the same per-increment loop to the `upDelta` fallback branch. A batched `0→2→3` ROM report
  now emits exactly 3 chirps.
- **Idempotent completion:** add `private var warmupCompleteEmitted = false` (reset with the other
  state). Gate **every** `WARMUP_COMPLETE` emission — primary branch, fallback branch, and the
  belated working-rep path (lines 510–523) — on this flag so it fires **exactly once** per set and
  can never re-fire when working reps begin.

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
3. Stale directional counter (`up=8`) on first packet → **zero** events (baseline absorbs it); real
   reps afterward count correctly.
4. Working reps begin while `warmupReps < warmupTarget` → exactly one belated `WARMUP_COMPLETE`,
   never a second.
5. Working-rep counting + final-rep / `WORKOUT_COMPLETE` sequence unaffected (regression).
6. Fallback branch (`repsRomCount==0`, `up` advancing) still progresses warmup per-increment.

## Files & risk

| File | Change |
|------|--------|
| `RepCounterFromMachine.kt` | Baseline hook in `process()`; per-increment + idempotent-completion in `processModern()`/`processLegacy()` fallback; new fields; resets in `reset()`/`resetCountsOnly()`. **Primary.** |
| `ActiveSessionEngine.kt` | Remove one `WARMUP_TO_WORKING` emit line. |
| `RepCounterFromMachineTest.kt` | New/expanded cases above. |

**Risk:** Low–moderate. The baseline early-return touches the shared entry path used by Echo / Just
Lift / AMRAP; unit tests cover those branches to guard against regressing the #553/#538 fixes. No
BLE, sync, or UI contract changes.

## Out of scope

- The "what is the 70/58/65 number" question in the issue thread — it's the rep-quality score,
  already answered by the owner.
- The "Echo activity not recorded in history" remark — separate issue.
- Stale-`repsRomCount` carryover on mid-session *resume* (distinct from fresh set start) — the
  baseline snapshot mitigates it via `romBaseline`, but resume edge cases are not the focus here.

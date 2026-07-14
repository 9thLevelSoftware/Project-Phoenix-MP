# E2E Audit: Stall Detection & Auto-Stop Pipeline

**Date:** 2026-07-14
**Baseline:** `main@0f00f4b` (PR #653 base)
**Trigger:** User report (follow-up to #652 / PR #653): Echo session, set 1 on **Hard** completed all 10 reps; set 2 on **Harder** was force-ended at 6 reps; set 3 on **Hardest** ended after ~3 reps — with *Auto-End on Velocity Loss* **disabled**.

---

## 1. Scope & signal flow

Audited the full path from firmware packet to `handleSetCompletion()`:

```
Firmware monitor packet (velocity 0.1mm/s, status flags)
  → MonitorDataProcessor.process()            (parse, validate, EMA-smooth velocity → mm/s;
        DELOAD_OCCURRED debounced 2s → callback)
  → KableBleRepository (metricsFlow / deloadOccurredEvents)
  → ActiveSessionEngine
        collector #5: deloadOccurredEvents    → arms 5s stall timer
        collector #7: metricsFlow             → handleMonitorMetric → checkAutoStop()
        collector #6: repEvents               → handleRepNotification → processBiomechanicsForRep
                                                → checkVelocityThreshold (VBT alert / auto-end)
  → requestAutoStop() → triggerAutoStop() → handleSetCompletion()
```

Three independent mechanisms can end a set:

| Path | Timer | Gate |
|---|---|---|
| Velocity stall (`checkAutoStop` §1) | 5.0s (`STALL_DURATION_SECONDS`) | `params.stallDetectionEnabled` |
| Firmware deload (collector #5) | arms the same 5.0s stall timer | `params.stallDetectionEnabled` |
| Position auto-stop (`checkAutoStop` §2) | 2.5s | Just Lift / AMRAP / timed cable only |

VBT auto-end (`checkVelocityThreshold`) is a fourth, gated by `autoEndOnVelocityLoss` (off for this user).

Verified-sound items: velocity units are consistent (firmware int16 in 0.1 mm/s ÷ 10 → mm/s, `MonitorDataProcessor.kt:189-197`) against thresholds `STALL_VELOCITY_LOW=2.5` / `HIGH=10.0` mm/s; warmup gating of the stall path (`isWarmupGateOpenForAutoStop`); the standard-set deferral until the first working/pending rep (`shouldDeferStandardSetStall`, Issue #256 semantics); per-set `resetAutoStopState()` on every set-start path; `stallDetectionEnabled=false` correctly neutralizes a deload-armed timer (the `else` branch of `checkAutoStop` §1 resets it).

---

## 2. Findings (ranked)

### F1 — CRITICAL: `DELOAD_OCCURRED` treated as "cable release", but in Echo mode it is a routine firmware event
**Where:** `ActiveSessionEngine.kt:400-437` (collector #5); context: `Models.kt:298-303`, `WorkoutParameters` comment at `Models.kt:358-362`.

Echo difficulty levels are *defined by the firmware's deload window* — Issue #553's own comment documents HARD = deload at 1.0s @ 50 mm/s, HARDER = 1.25s @ 40 mm/s. On higher levels the machine deloads earlier in the set as the athlete fatigues and bar speed drops. That deload raises the `DELOAD_OCCURRED` status flag (0x8000), which collector #5 interprets as *"Machine detected cable release"* and uses to arm the 5-second auto-stop stall timer — with no Echo-mode discrimination and no velocity corroboration at arm time.

Once armed, the only things that cancel the countdown on `main` are:
- a metric sample > 10 mm/s (`checkAutoStop:1485`), or
- a set/workout reset.

A fatigued user mid-grind, or paused while Echo rebuilds weight after its deload, can easily stay ≤ 10 mm/s for 5s → `requestAutoStop()` → set force-completed. **This reproduces the report exactly:** Hard set (no firmware deload) survived 10 reps; Harder deloaded around rep 6; Hardest deloaded around rep 3; each set ended ~5s later. The user attributed it to VBT, but the VBT auto-end gate (`autoEndOnVelocityLoss`) was correctly off — the killer is the deload→stall path.

**Recommendation:**
- In Echo mode, do not arm the stall timer from `DELOAD_OCCURRED` (or require corroborating stalled velocity at arm time, and cancel on any subsequent rep event, not only on >10 mm/s samples).
- PR #653 (reset on completed rep) is necessary but **not sufficient**: a deload arming early in a slow rep can expire before the next rep boundary is reported.

### F2 — HIGH: VBT velocity-loss baseline is contaminated by firmware warm-up/calibration reps
**Where:** `ActiveSessionEngine.kt:1097-1113` (`handleRepNotification`), `1273-1322` (`processBiomechanicsForRep`), `BiomechanicsEngine.kt:238-260`; `RepCounterFromMachine.kt:321-322, 689`.

Every cable set runs 3 firmware calibration warm-up reps (`Constants.DEFAULT_WARMUP_REPS=3`, kept deliberately per Issue #411). `handleRepNotification` calls `processBiomechanicsForRep(repCountAfter=totalReps)` for **every** rep increment — warm-up reps included ("GATE-04: unconditional capture"). `BiomechanicsEngine` latches `firstRepMcv` on the first processed rep, i.e. **warm-up rep 1** (light/ramping → fast). All later working reps compute `velocityLossPercent` against that inflated baseline, so `shouldStopSet` trips far too early.

Consequences:
- Premature `VELOCITY_THRESHOLD_REACHED` alerts — the "VBT kicking in" the user hears.
- With `autoEndOnVelocityLoss` **on**, sets can auto-end ~2 working reps after the warmup gate opens.
- Secondary: the `isWarmupComplete` gate at `ActiveSessionEngine.kt:1319` opens on the *final warm-up rep itself* (`_repCount` is updated before the check), so the first threshold evaluation can run on warm-up rep 3's result.

**Recommendation:** exclude warm-up reps from `processRep` (or re-baseline `firstRepMcv` at warm-up completion), and pass the *working* rep index rather than `totalReps` so `repNumber==1` semantics hold.

### F3 — HIGH: Variable warm-up set fast path leaks biomechanics/VBT state into the working set
**Where:** `ActiveSessionEngine.kt:3935-3981` (Phase 35C early returns at 3958 and 3980) vs. the reset block at `4024-4035`.

`handleSetCompletion()` resets the biomechanics engine, `velocityThresholdAlertEmitted`, `consecutiveThresholdReps`, `deferAutoStopDeadlineMs`, and `repBoundaryTimestamps` at lines 4024-4035 — **after** the Phase 35C warm-up-set branch returns early. So when an exercise uses variable warm-up sets (e.g. 50% × 8):

- `firstRepMcv` from warm-up set 1 (at a fraction of working weight) survives into every later warm-up set **and the first working set** → velocity-loss threshold effectively guaranteed to trip (compounds F2).
- `repResults` accumulate across sets → the first working set's persisted per-rep biomechanics and set summary include warm-up-set reps.
- The one-shot `velocityThresholdAlertEmitted` may already be consumed, and stale `consecutiveThresholdReps` ≥ 1 means with auto-end **on** a single qualifying working rep can end the set.
- `repBoundaryTimestamps` retain boundaries from the previous set (benign for segmentation since `collectedMetrics` *is* cleared per set at 2896, but the `boundaries.size >= 2` fallback logic then misclassifies the first rep's window).

**Recommendation:** hoist the biomech/VBT reset block above the Phase 35C early returns, or move it into the per-set path of `startWorkout()` next to `repQualityScorer.reset()` (line 2517), which currently resets the quality scorer but *not* the biomechanics engine — an asymmetry that invites exactly this class of bug.

### F4 — MEDIUM: `isActivelyUsing` latches on `hasMeaningfulRange`, so a racked pause still runs the stall countdown
**Where:** `ActiveSessionEngine.kt:1478-1487`.

`isActivelyUsing = maxPosition > 10mm || hasMeaningfulRange`. Once the set has established ≥50mm ROM, `hasMeaningfulRange` stays true, so resting the handles fully at the bottom (velocity ≈ 0) arms the stall timer in a **standard** set. Any ≥5s mid-set breather/regrip ends the set. Whether "5s of no movement ends the set" is desired for standard sets is a product decision, but the arm condition is also never re-evaluated during the countdown, and combined with #652 (timer surviving completed reps on `main`) it manifests as "sets randomly ending". The `AutoStopUiState` countdown is only surfaced after 1s elapsed (line 1498) and is easy to miss mid-rep.

### F5 — MEDIUM: hysteresis dead band (2.5–10 mm/s) cannot cancel a running countdown
**Where:** `ActiveSessionEngine.kt:1474-1487`; constants `WorkoutCoordinator.kt:61-72`.

A single sample < 2.5 mm/s (e.g. a turnaround or brief pause) arms the timer; only a sample > 10 mm/s cancels. Sustained slow grinding at 3–9 mm/s — plausible at a sticking point under Echo HARDEST, TUT, or heavy eccentric loading — neither arms nor cancels, so a countdown armed at a turnaround runs to expiry *through a moving rep*. PR #653's rep-boundary reset mitigates this for completed reps, but only at the boundary — a rep slower than ~5s from arm to boundary still loses the race.

### F6 — MEDIUM: the global "Stall Detection" setting does not govern routine sets
**Where:** `UserPreferences.kt:16` (default `true`), `SettingsManager.kt:87-89`, vs. routine paths `RoutineFlowManager.kt:1107/1182/1427`, `ActiveSessionEngine.kt:4622/4786`, `DefaultWorkoutSessionManager.kt:931`.

Routine set-starts always take `RoutineExercise.stallDetectionEnabled` (default `true`, snapshotted into the routine at exercise-config time — `Routine.kt:103`, `ExerciseConfigViewModel.kt:823`). The global Settings toggle feeds only Just Lift / single-exercise defaults. A user who turns stall detection off globally still gets stall auto-stop in every routine exercise unless they edit each exercise individually. This is a UX trap that directly feeds the "I don't have it configured to stop my sets" confusion — the user believes they disabled auto-stop when only the VBT auto-end (a separate toggle) and/or the global default is off.

### F7 — LOW: divergent duplicate of `resetAutoStopState()` in RoutineFlowManager
**Where:** `RoutineFlowManager.kt:1775-1782` vs. `ActiveSessionEngine.kt:964-974`.

The RFM copy does not (and cannot) zero `deferAutoStopDeadlineMs` (private to ASE) and does not reset the biomech/VBT one-shots. Today ASE's `startWorkout()` re-clears the deadline on every set start, so no live bug was found, but two hand-maintained reset lists for the same state family is how #649-class leaks recur. Consolidate to one reset entry point.

### F8 — LOW: 30s verbal-cue defer window suspends *all* auto-stop paths
**Where:** `ActiveSessionEngine.kt:1367-1373, 1451-1466`, `VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS = 30_000` (line 5075).

With verbal encouragement on and VBT auto-end off, one VBT cue disables both the stall and position auto-stop for up to 30s (cleared earlier only by a completed rep). In Just Lift/AMRAP, genuinely finishing the set right after the cue means auto-stop takes up to ~32.5s. Intentional per #649, but 30s is generous; consider ending the defer on `HandleState.Released` as well.

### F9 — LOW: cross-thread mutation of stall state without synchronization
**Where:** `WorkoutCoordinator.kt:413-418`, `ActiveSessionEngine.kt:1371-1372` (resets from `Dispatchers.Default` inside `checkVelocityThreshold`).

`stallStartTime`/`isCurrentlyStalled`/`autoStopStartTime` are plain vars written from the metrics collector, the deload collector, and `Dispatchers.Default` (VBT path). `deferAutoStopDeadlineMs` was made `@Volatile` for exactly this reason (#649 Codex P2), but the stall fields were not. Mostly same-dispatcher today; fragile against future dispatcher changes. Also minor: `MonitorDataProcessor.lastDeloadEventTime` is deliberately not reset between sessions (2s debounce, negligible).

---

## 3. Incident reconstruction (user session, 2026-07-14)

| Set | Echo level | Firmware deload window | Observed | Explanation |
|---|---|---|---|---|
| 1 | Hard | 1.0s @ 50 mm/s | 10/10 reps, no VBT | Bar speed stayed above the deload window; no `DELOAD_OCCURRED`; velocity stall never accrued 5s below 2.5 mm/s. |
| 2 | Harder | 1.25s @ 40 mm/s | ended at 6 reps | Fatigue → firmware deload ≈ rep 6 → F1 arms 5s stall timer → post-deload movement never exceeded 10 mm/s → auto-stop. On `main`, completing another rep would not have cancelled it (#652). |
| 3 | Hardest | (wider window, heavier Echo matching) | "VBT failed" ≈ rep 3 | Same F1 chain, earlier; premature VBT alert (F2 baseline contamination) fired around the same reps, making it *look* like VBT ended the set. |

The user's "I don't have it configured to stop sets on VBT thresholds" is accurate — `autoEndOnVelocityLoss` played no role. The set-ender is the deload→stall path (enabled by default, F6), and the VBT audio alert (F2) is a coincident symptom, not the cause.

## 4. Assessment of PR #653

The one-line fix (reset the shared stall timer on a completed working rep) is **correct and should merge** — it closes the #652 class where a timer armed at a turnaround survives valid reps. It is **not sufficient** for this incident: it does not prevent arming from routine Echo deloads (F1), cannot cancel a countdown that expires mid-rep before the boundary event (F5), and does not touch the VBT baseline issues (F2/F3).

## 5. Recommended fix order

1. **F1** — suppress/qualify deload-armed stall in Echo mode (direct cause of the reported set kills).
2. **F3** — hoist biomech/VBT resets above the Phase 35C early returns (one-line-class fix, closes a compounding leak).
3. **F2** — baseline `firstRepMcv` on the first *working* rep.
4. **F6** — make the global toggle a master gate (`params.stallDetectionEnabled && prefs.stallDetectionEnabled`) or surface per-exercise state in the routine UI.
5. F4/F5 — revisit arm/cancel semantics for standard sets (product decision; at minimum re-check `isActivelyUsing` during countdown and let rep *events* — not only >10 mm/s samples — cancel).
6. F7–F9 — hygiene.

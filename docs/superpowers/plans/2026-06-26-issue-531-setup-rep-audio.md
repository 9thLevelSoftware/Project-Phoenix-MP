# Issue #531 Setup-Rep Audio Timing Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three setup-rep audio symptoms reported in Issue #531 — dropped setup-rep chirps, a stray transition tone mid-set, and a best-effort suppression of the phantom chirp on screen entry.

**Architecture:** Surgical edits to `RepCounterFromMachine.processModern()` (per-increment warmup chirp emission + a narrow directional-counter carryover guard) plus a one-line transition-tone de-dupe in `ActiveSessionEngine`. No changes to BLE, sync, or UI contracts. The `WARMUP_TO_WORKING` `HapticEvent` enum and its iOS/Android sound mappings stay (other tests depend on them); only its emission from the rep-event path is removed.

**Tech Stack:** Kotlin Multiplatform (Kotlin 2.3.0), `kotlin.test`, kotlinx-coroutines-test. Shared-module tests run on the JVM host via the `androidHostTest` source set.

## Global Constraints

- **Preserve Issue #210 contract:** the first packet of a set counts immediately — never discard/baseline a fresh first rep. Counts stay absolute-from-machine (no baseline subtraction).
- **Preserve Issue #553 contract:** when `repsRomCount == 0 && repsSetCount == 0` and the `up` counter advances, warmup must still progress from the `up` counter (Echo dropped-rep escape hatch).
- **Preserve variable-warmup (#411) contract:** `warmupTarget == 0` sets must not gain warmup gating.
- **Do not remove** the `HapticEvent.WARMUP_TO_WORKING` enum value or its iOS/Android sound-file mappings — `HapticEventAudioTest`, `WorkoutMetricTest`, and `HapticFeedbackAudioRoutingGuardTest` assert their presence.
- **Test command (shared module):** `./gradlew :shared:testAndroidHostTest`
  - Single class filter: `./gradlew :shared:testAndroidHostTest --tests "<fully.qualified.ClassName>"`
- **Symptom 1 is a best-effort heuristic.** It only catches *large* directional carryover; it introduces no regression if the real phantom is a small slack tick. An on-device Echo + normal-mode warmup smoke test is required before merge.
- Conventional Commits. Branch: `fix/issue-531-setup-rep-audio`.

---

### Task 1: Per-increment warmup chirp emission (Symptom 2 — dropped setup chirps)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt` — `processModern()` primary warmup branch (currently lines 453–474) and the up-counter fallback branch (currently lines 484–505)
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachineTest.kt`

**Interfaces:**
- Consumes: existing `RepCounterFromMachine.process(...)` and `onRepEvent: ((RepEvent) -> Unit)?` (no signature change).
- Produces: same `RepEvent` stream, but one `WARMUP_COMPLETED` event per integer warmup-rep step instead of one per `process()` call.

- [ ] **Step 1: Write the failing test**

Add to `RepCounterFromMachineTest.kt` (inside `class RepCounterFromMachineTest`, e.g. after the Issue #553 block near line 771):

```kotlin
    // ========== Issue #531: Per-increment warmup chirp emission ==========

    @Test
    fun `issue 531 batched warmup ROM jump emits one chirp per rep`() {
        // A BLE notification can batch/drop so the machine's ROM count jumps by >1 in a
        // single packet (0 -> 2). Each setup rep must still chirp exactly once.
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Baseline (idle, no movement)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // ROM jumps 0 -> 2 in one packet, then -> 3 in the next.
        repCounter.process(repsRomCount = 2, repsSetCount = 0, up = 2, down = 2)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        assertEquals(
            3,
            capturedEvents.count { it.type == RepType.WARMUP_COMPLETED },
            "Each setup rep must chirp once even when the ROM count batches",
        )
        assertEquals(3, repCounter.getRepCount().warmupReps)
        assertEquals(
            1,
            capturedEvents.count { it.type == RepType.WARMUP_COMPLETE },
            "WARMUP_COMPLETE fires exactly once when the target is reached",
        )
    }

    @Test
    fun `issue 531 fallback warmup emits one chirp per rep on batched up jump`() {
        // Firmware that never reports repsRomCount (Echo/unlimited): the up counter can jump
        // 0 -> 3 in one packet. Per-increment emission must still chirp each setup rep once,
        // clamped to the warmup target.
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 3, down = 0)

        assertEquals(
            3,
            capturedEvents.count { it.type == RepType.WARMUP_COMPLETED },
            "Fallback warmup must chirp once per rep up to the target",
        )
        assertEquals(3, repCounter.getRepCount().warmupReps)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.domain.usecase.RepCounterFromMachineTest"`
Expected: FAIL — `issue 531 batched warmup ROM jump...` reports `expected:<3> but was:<2>` (current code emits one `WARMUP_COMPLETED` per packet, so the 0→2 jump yields 1 event), and the fallback test reports `expected:<3> but was:<1>`.

- [ ] **Step 3: Implement per-increment emission in the primary warmup branch**

In `RepCounterFromMachine.kt`, replace the primary warmup branch (currently lines 453–474):

```kotlin
        // WARMUP TRACKING: Use repsRomCount directly from machine (no pending animation)
        if (repsRomCount > warmupReps && warmupReps < warmupTarget) {
            warmupReps = repsRomCount.coerceAtMost(warmupTarget)

            onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WARMUP_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps,
                ),
            )

            if (warmupReps >= warmupTarget) {
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }
```

with:

```kotlin
        // WARMUP TRACKING: Use repsRomCount directly from machine (no pending animation).
        // Issue #531: emit one WARMUP_COMPLETED per integer step so a batched/dropped ROM
        // notification (count jumps 0 -> 2 in a single packet) doesn't drop setup-rep chirps.
        if (repsRomCount > warmupReps && warmupReps < warmupTarget) {
            val warmupRepTarget = repsRomCount.coerceAtMost(warmupTarget)
            while (warmupReps < warmupRepTarget) {
                warmupReps++
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETED,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }

            if (warmupReps >= warmupTarget) {
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }
```

- [ ] **Step 4: Implement per-increment emission in the fallback warmup branch**

Replace the fallback branch (currently lines 484–505):

```kotlin
        else if (repsSetCount == 0 && repsRomCount == 0 && warmupReps < warmupTarget && upDelta > 0) {
            warmupReps = (warmupReps + upDelta).coerceAtMost(warmupTarget)
            logDebug("📈 MODERN FALLBACK: Warmup rep $warmupReps (from up counter, repsRomCount=0)")

            onRepEvent?.invoke(
                RepEvent(
                    type = RepType.WARMUP_COMPLETED,
                    warmupCount = warmupReps,
                    workingCount = workingReps,
                ),
            )

            if (warmupReps >= warmupTarget) {
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }
```

with:

```kotlin
        else if (repsSetCount == 0 && repsRomCount == 0 && warmupReps < warmupTarget && upDelta > 0) {
            // Issue #531: per-increment emission for the up-counter fallback too.
            val warmupRepTarget = (warmupReps + upDelta).coerceAtMost(warmupTarget)
            while (warmupReps < warmupRepTarget) {
                warmupReps++
                logDebug("📈 MODERN FALLBACK: Warmup rep $warmupReps (from up counter, repsRomCount=0)")
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETED,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }

            if (warmupReps >= warmupTarget) {
                onRepEvent?.invoke(
                    RepEvent(
                        type = RepType.WARMUP_COMPLETE,
                        warmupCount = warmupReps,
                        workingCount = workingReps,
                    ),
                )
            }
        }
```

- [ ] **Step 5: Run the full RepCounter test class to verify pass + no regression**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.domain.usecase.RepCounterFromMachineTest"`
Expected: PASS — the two new tests pass and all pre-existing tests (including the Issue #210 first-rep, Issue #553 Echo-fallback, and variable-warmup cases) still pass. Existing tests assert only final counts / event presence, not event multiplicity, so per-increment emission is compatible.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt \
        shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachineTest.kt
git commit -m "fix(audio): emit one warmup chirp per setup rep (#531)

Symptom 2: batched/dropped ROM notifications collapsed multi-rep jumps into a
single WARMUP_COMPLETED, dropping setup-rep chirps (only 2 of 3 registered).
Emit one event per integer step in both processModern warmup branches.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T7pTNEeYws971sWpE9V9Bk"
```

---

### Task 2: Directional-counter carryover guard (Symptom 1 — phantom chirp on entry, heuristic)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt` — add `carryoverChecked` field (after `isAMRAP`, currently line 36), reset it in `reset()` (currently lines 105–137) and `resetCountsOnly()` (currently lines 146–159), and add the guard at the top of `processModern()` (currently line 385)
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachineTest.kt`

**Interfaces:**
- Consumes: existing `process(...)` / `processModern(...)` internals (`lastTopCounter`, `lastCompleteCounter`, `warmupTarget`, `warmupReps`).
- Produces: on the first modern packet of a set, when directional counters are already past the warmup target while the machine reports zero ROM/set reps, re-baselines `lastTopCounter`/`lastCompleteCounter` once and emits nothing for that packet.

- [ ] **Step 1: Write the failing test**

Add to `RepCounterFromMachineTest.kt`:

```kotlin
    // ========== Issue #531: Carryover guard (phantom-chirp suppression) ==========

    @Test
    fun `issue 531 large directional carryover does not phantom-fire a warmup rep`() {
        // Heuristic: the machine's free-running up/down counters can carry over from the prior
        // set. On the first packet of a fresh set, counters already far past the warmup target
        // while repsRomCount/repsSetCount are 0 are stale -> must not chirp a phantom warmup rep.
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // First packet carries stale up/down = 8 from the previous set.
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 8, down = 8)

        assertEquals(0, repCounter.getRepCount().warmupReps, "Stale carryover must not advance warmup")
        assertTrue(capturedEvents.isEmpty(), "Carryover packet must emit no rep events")

        // Real warmup reps afterward count normally (delta from the re-baselined counters).
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 9, down = 9)
        repCounter.process(repsRomCount = 2, repsSetCount = 0, up = 10, down = 10)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 11, down = 11)

        assertEquals(3, repCounter.getRepCount().warmupReps)
        assertEquals(3, capturedEvents.count { it.type == RepType.WARMUP_COMPLETED })
        assertTrue(capturedEvents.any { it.type == RepType.WARMUP_COMPLETE })
    }

    @Test
    fun `issue 531 carryover guard ignores a small first up tick`() {
        // Guard must NOT fire for a plausible fresh-start value (preserves Issue #210): a first
        // packet with up = 1 is a legitimate first rep, not carryover.
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        assertEquals(1, repCounter.getRepCount().warmupReps, "Small first up tick is a real rep, not carryover")
        assertEquals(1, capturedEvents.count { it.type == RepType.WARMUP_COMPLETED })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.domain.usecase.RepCounterFromMachineTest"`
Expected: FAIL — `issue 531 large directional carryover...` fails: without the guard, the first packet (`up=8, repsRomCount=0`) hits the up-counter fallback branch and advances `warmupReps` to the target, so `warmupReps` is `3` (not `0`) and `capturedEvents` is non-empty. (`issue 531 carryover guard ignores a small first up tick` already passes — it is a regression guard.)

- [ ] **Step 3: Add the `carryoverChecked` field**

In `RepCounterFromMachine.kt`, after the `private var isAMRAP = false` line (currently line 36), add:

```kotlin

    // Issue #531: one-shot guard for the directional-counter carryover heuristic. The machine's
    // up/down counters are free-running and can carry over from the prior set; on the first modern
    // packet we detect and absorb stale values once so they don't chirp a phantom warmup rep.
    private var carryoverChecked = false
```

- [ ] **Step 4: Reset the field in `reset()` and `resetCountsOnly()`**

In `reset()` (currently lines 105–137), add alongside the other resets (e.g. after `shouldStop = false`):

```kotlin
        carryoverChecked = false
```

In `resetCountsOnly()` (currently lines 146–159), add alongside the other resets (e.g. after `shouldStop = false`):

```kotlin
        carryoverChecked = false
```

- [ ] **Step 5: Add the guard at the top of `processModern()`**

In `processModern()`, immediately after the opening brace (currently line 385, before `val upDelta = calculateDelta(lastTopCounter, up)`), insert:

```kotlin
        // Issue #531 (symptom 1, heuristic): suppress a phantom warmup chirp caused by the
        // machine's free-running directional counters carrying over from the prior set. On the
        // first modern packet of a set, if up/down are already past the warmup target while the
        // machine reports zero ROM/set reps, treat them as stale and re-baseline once so the
        // first real movement yields delta ~0. Deliberately narrow to preserve:
        //   - Issue #210: a real first rep arrives as repsRomCount=1 -> fails repsRomCount==0.
        //   - Issue #553: sends an explicit up=0/down=0 baseline packet -> fails up>warmupTarget.
        //   - Issue #411: variable-warmup sets use warmupTarget=0 -> fails warmupTarget>0.
        if (!carryoverChecked) {
            carryoverChecked = true
            if (warmupTarget > 0 && warmupReps == 0 &&
                repsRomCount == 0 && repsSetCount == 0 &&
                (up > warmupTarget || down > warmupTarget)
            ) {
                logDebug("🩹 Issue #531: directional carryover (up=$up, down=$down) — re-baselining, suppressing phantom warmup")
                lastTopCounter = up
                lastCompleteCounter = down
                return
            }
        }
```

- [ ] **Step 6: Run the full RepCounter test class to verify pass + no regression**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.domain.usecase.RepCounterFromMachineTest"`
Expected: PASS — both new tests pass and all pre-existing tests still pass. Note in particular: `Issue 210 - first warmup rep registers immediately in modern mode` (first packet `repsRomCount=1` → guard's `repsRomCount==0` is false → no skip), the Issue #553 tests (explicit `up=0, down=0` baseline first → guard's `up>warmupTarget` is false → no skip), and `variable warmup sets ignore machine repsRomTotal warmup sync` (`warmupTarget=0` → guard's `warmupTarget>0` is false → no skip).

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachine.kt \
        shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RepCounterFromMachineTest.kt
git commit -m "fix(audio): suppress phantom warmup chirp from counter carryover (#531)

Symptom 1 (best-effort heuristic): the machine's free-running up/down counters
can carry over from the prior set, making the first packet's delta fire a phantom
warmup rep before the user moves. On the first modern packet, re-baseline once when
counters are already past the warmup target while ROM/set counts are 0. Narrow by
design to preserve the #210 first-rep, #553 Echo-fallback, and #411 variable-warmup
contracts. Only catches large carryover; harmless no-op otherwise.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T7pTNEeYws971sWpE9V9Bk"
```

---

### Task 3: Single clean transition tone (Symptom 3 — stray double-`beepboop`)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt` — the `RepType.WARMUP_COMPLETE` case in the `repCounter.onRepEvent` handler (currently lines 300–304)
- Test: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WarmupTransitionToneTest.kt` (create)

**Interfaces:**
- Consumes: `DWSMTestHarness` (`testutil/DWSMTestHarness.kt`) which exposes `repCounter`, `coordinator`, and `cleanup()`; `coordinator.hapticEvents: SharedFlow<HapticEvent>`; `RepEvent` / `RepType` / `HapticEvent` from `domain.model`.
- Produces: on a `RepType.WARMUP_COMPLETE` rep event, the engine emits exactly one `HapticEvent.WARMUP_COMPLETE` and no `HapticEvent.WARMUP_TO_WORKING`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WarmupTransitionToneTest.kt`:

```kotlin
package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #531: the warmup -> working transition must play a single clean tone. Previously the
 * rep-event handler emitted both WARMUP_COMPLETE and WARMUP_TO_WORKING, which map to the same
 * `beepboop` file on both platforms -> a stacked double-tone heard mid-set.
 */
class WarmupTransitionToneTest {

    @Test
    fun `warmup complete emits a single transition tone`() = runTest {
        val harness = DWSMTestHarness(this)
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(collected)
        }

        // Drive the rep-event path exactly as RepCounterFromMachine does on warmup completion.
        harness.repCounter.onRepEvent?.invoke(
            RepEvent(type = RepType.WARMUP_COMPLETE, warmupCount = 3, workingCount = 0),
        )
        advanceUntilIdle()

        assertEquals(
            1,
            collected.count { it is HapticEvent.WARMUP_COMPLETE },
            "Exactly one WARMUP_COMPLETE should be emitted",
        )
        assertEquals(
            0,
            collected.count { it is HapticEvent.WARMUP_TO_WORKING },
            "WARMUP_TO_WORKING must no longer be emitted (single clean transition tone)",
        )

        job.cancel()
        harness.cleanup()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.manager.WarmupTransitionToneTest"`
Expected: FAIL — `WARMUP_TO_WORKING must no longer be emitted` reports `expected:<0> but was:<1>` (current handler emits both events).

- [ ] **Step 3: Remove the redundant `WARMUP_TO_WORKING` emission**

In `ActiveSessionEngine.kt`, replace the `RepType.WARMUP_COMPLETE` case (currently lines 300–304):

```kotlin
                    RepType.WARMUP_COMPLETE -> {
                        coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                        // Issue #100: Distinct transition sound from warmup to working sets
                        coordinator._hapticEvents.emit(HapticEvent.WARMUP_TO_WORKING)
                    }
```

with:

```kotlin
                    RepType.WARMUP_COMPLETE -> {
                        // Issue #531: single clean transition tone. Previously also emitted
                        // WARMUP_TO_WORKING, which maps to the same `beepboop` file on both
                        // platforms -> a stacked double-tone heard mid-set. The WARMUP_TO_WORKING
                        // enum + sound mappings remain (used by HapticEventAudioTest and the
                        // Android routing guard) but are no longer emitted from the rep-event path.
                        coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.manager.WarmupTransitionToneTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt \
        shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WarmupTransitionToneTest.kt
git commit -m "fix(audio): single clean warmup-to-working transition tone (#531)

Symptom 3: the rep-event handler emitted both WARMUP_COMPLETE and WARMUP_TO_WORKING,
which map to the same beepboop file -> a stacked double-tone mid-set. Emit only
WARMUP_COMPLETE. The WARMUP_TO_WORKING enum + sound maps remain (other tests depend
on them); only its emission from the rep-event path is removed.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T7pTNEeYws971sWpE9V9Bk"
```

---

### Task 4: Full-suite verification + handoff notes

**Files:** none (verification only)

- [ ] **Step 1: Run the full shared-module test suite**

Run: `./gradlew :shared:testAndroidHostTest`
Expected: PASS — entire shared common + androidHostTest suite green, including `RepCounterFromMachineTest`, `WarmupTransitionToneTest`, `HapticEventAudioTest`, `WorkoutMetricTest`, `HapticFeedbackAudioRoutingGuardTest`, and the DWSM/ActiveSessionEngine integration tests.

- [ ] **Step 2: Compile both platforms to catch KMP-specific breakage**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. (iOS framework compile `:shared:compileKotlinIosArm64` is optional and only available on macOS.)

- [ ] **Step 3: Record the on-device verification requirement**

Symptom 1's fix is a best-effort heuristic. Before merging, perform an on-device smoke test on iOS:
- **Normal mode:** start a set; confirm no chirp before the first real setup rep, exactly 3 setup chirps, a single clean transition tone, and no stray tone during working reps.
- **Echo mode:** confirm warmup still progresses past "Warm Up 1/3" (Issue #553 not regressed).

If the phantom chirp persists in normal mode, capture the `REP_RECEIVED` connection log for the affected set and attach it to Issue #531 — the remaining mechanism (small slack-takeup tick vs. large carryover) can only be resolved with the real packet trace.

- [ ] **Step 4: Update the issue**

Comment on Issue #531 summarizing the three fixes shipped (per-increment setup chirps, carryover guard, single transition tone), and that on-device confirmation of symptom 1 is pending.

---

## Self-Review

**Spec coverage:**
- Symptom 2 (dropped chirps) → Task 1. ✓
- Symptom 1 (phantom chirp, heuristic) → Task 2. ✓
- Symptom 3 (stray transition tone) → Task 1 (timely completion removes late-fire) + Task 3 (de-dupe). ✓
- Dropped idempotency guard (spec note) → correctly absent from the plan. ✓
- Preserve #210 / #553 / #411 contracts → covered by Task 2 Step 6 verification and the Global Constraints. ✓
- On-device verification requirement → Task 4 Step 3. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"/"write tests for the above". Every code step shows complete code; every run step shows the command and expected outcome. ✓

**Type/name consistency:** `carryoverChecked` (field) is declared in Task 2 Step 3 and used in Steps 4–5. `warmupRepTarget` is a local in Task 1 (two independent branch scopes — no cross-task reference). `DWSMTestHarness` accessors (`repCounter`, `coordinator`, `cleanup`) match `testutil/DWSMTestHarness.kt`. `HapticEvent.WARMUP_COMPLETE` / `WARMUP_TO_WORKING` and `RepType.WARMUP_COMPLETE` / `WARMUP_COMPLETED` match `domain/model/Models.kt`. ✓

**Note on line numbers:** all "currently line N" references reflect the state of the files at plan-writing time; if earlier tasks shift line numbers, locate the named branch/method instead.

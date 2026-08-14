# Task 5 Report: Bind end reasons to workout executions

## Scope

Implemented Task 5 on base `218e2cd0d076d72f45a00d8126ec6ff5849b5f84`. Production changes are limited to the four manager files named by the brief. Per parent authorization, every remaining test-only call to the removed no-argument completion route under `shared/src` was mechanically changed to pass an explicit current/captured lease and the behavior-correct `SetEndReason`. No protocol, BLE, migration, schema, PR-metadata, or unrelated production file was changed.

## TDD evidence

### Initial RED

The first lifecycle run produced a behavior-level failure for the handles-at-rest terminal origin:

- `handles at rest position countdown persists cable released`
- expected `CABLE_RELEASED`, actual `STALL_FAILURE`

The guard contract run produced the expected compile-scaffolding RED because `PersistenceClaimStatus` and `persistenceClaimStatus` did not yet exist. The added tests require `UNCLAIMED -> IN_PROGRESS -> PERSISTED`, `FAILED`, single-winner reclaim, and session isolation.

An initial danger-zone metric fixture produced no persistence attempt. Inspection showed the existing detector resets `autoStopStartTime` before the later danger branch can accumulate across samples. That fixture-only attempt was not counted as proof, and no detector condition, threshold, duration, or reset ordering was changed.

After review rejected a direct auto-stop helper as insufficient branch coverage, the replacement danger test produced a compile-scaffolding RED because the narrow `dangerZoneCountdownStartTimeForTest` hook did not exist. The hook is consumed once, after the unchanged preceding reset point, so the test can deterministically exercise the real danger-zone predicate and countdown terminal.

### Additional RED during self-review

`bodyweight confirmation persists the immutable originating reason` was strengthened with a competing second terminal signal while rep entry remained open. It failed because the second signal replaced the stored `USER_STOPPED` completion. The minimal fix makes the first pending bodyweight completion win until it is consumed or invalidated.

### Danger-origin mutation proof

`danger zone cable release countdown persists cable released` now sends a real `WorkoutMetric` through `checkAutoStop`, with seeded ROM boundaries that make one cable satisfy the existing danger/release predicates while the other keeps the handles-at-rest branch false. The one-shot test hook primes only the existing countdown start time immediately before danger evaluation. Temporarily mutating the actual danger terminal at `checkAutoStop` from `CABLE_RELEASED` to `STALL_FAILURE` made the persisted-reason assertion fail; restoring `CABLE_RELEASED` made the same test pass. Removing or bypassing the danger branch would also leave the repository empty and fail the test.

## Mandatory origin matrix

| Terminal origin | Required persisted reason | Direct assertion |
|---|---|---|
| machine `WORKOUT_COMPLETE` | `TARGET_REPS_REACHED` | `machine workout complete persists target reps reached` |
| `repCounter.shouldStopWorkout()` | `TARGET_REPS_REACHED` | `rep counter stop safety net persists target reps reached` |
| velocity-stall countdown terminal | `STALL_FAILURE` | `velocity stall countdown persists stall failure` |
| deload-armed velocity countdown terminal | `CABLE_RELEASED` | `deload armed velocity countdown persists cable released` |
| handles-at-rest position terminal | `CABLE_RELEASED` | `handles at rest position countdown persists cable released` |
| danger-zone cable-release terminal | `CABLE_RELEASED` | `danger zone cable release countdown persists cable released`, plus the mutation proof above |
| VBT velocity-loss auto-end | `VBT_AUTO_END` | `VBT velocity loss auto end persists VBT auto end` |
| End Workout | `USER_STOPPED` | `End Workout persists user stopped` |
| stop-and-return with performed reps | `USER_STOPPED` | `stop and return with performed routine reps persists user stopped` |
| timed cable completion | `TIMER_EXPIRED` | `timed cable completion persists timer expired` |
| timed bodyweight completion | `TIMER_EXPIRED` | `timed bodyweight confirmation persists its originating timer reason` |
| bodyweight confirmation | first stored originating reason | `bodyweight confirmation persists the immutable originating reason` |

Bodyweight current-lease behavior is additionally covered by `stale bodyweight confirmation cannot complete a replacement execution`. The immutable-origin test supplies `USER_STOPPED`, then a competing `TARGET_REPS_REACHED`, confirms twice, and uses a single-record assertion to prove the first `USER_STOPPED` claim is consumed exactly once.

## Implementation

- Added immutable `SetExecutionCompletion(lease, reason)` and stored it in every `WorkoutExitSnapshot`.
- `handleSetCompletion(lease, reason)` now verifies current authority, constructs the immutable completion immediately, and has no default or reason-only overload.
- Removed `WorkoutCoordinator.lastSetEndReason`, `WorkoutCoordinator.autoStopReason`, and every read/reset/assignment.
- Captured `CompletedSet.setEndReason` exclusively from `completion.reason`; asynchronous snapshot persistence never consults mutable coordinator reason state.
- Snapshot installation preserves the first completion/reason and stable CompletedSet identity while allowing a later terminal path label to share that installed snapshot.
- Persistence claims use `snapshot.completion.lease.sessionId`. The guard exposes `UNCLAIMED`, `IN_PROGRESS`, `PERSISTED`, and `FAILED`, retains failures, and atomically promotes only one retry claimant from `FAILED` to `IN_PROGRESS`.
- Velocity, handles-at-rest, and danger-zone terminals pass their reason directly through `requestAutoStop(lease, reason)`/`triggerAutoStop(lease, reason)`. Existing stall conditions, thresholds, timers, and classification inputs are unchanged.
- The narrow, one-shot `dangerZoneCountdownStartTimeForTest` hook exists only to place the existing timer at expiry after its unchanged preceding reset; production behavior is unchanged when the hook is unset.
- Bodyweight rep entry retains the first immutable completion, rejects competing terminal replacement, requires its lease still be current, clears before confirmation re-entry, and clears on reset/start replacement/failure/End/Stop/Skip/cleanup.
- Completion context is retained through snapshot summary/persistence and completion-owned rest transitions.

## Race and persistence proof

- `auto completion A reason cannot bleed into B while A persistence is suspended`: A captures `STALL_FAILURE`; A is invalidated after teardown becomes `Ready`; B captures `VBT_AUTO_END`; releasing A preserves both session-scoped reasons.
- `End Workout A reason cannot bleed into B while A persistence is suspended`: A captures `USER_STOPPED`; a stale A auto-completion is rejected; B captures `TARGET_REPS_REACHED`; releasing A preserves both reasons.
- Existing first-claim tests still prove auto-complete-vs-End and End-vs-delayed-auto exactly-once persistence.
- `racing terminal captures install one stable CompletedSet identity` now also proves a single installed completion reason across competing terminal captures.
- Existing retry/partial-write/teardown tests remain green, including retained snapshot retry after `FAILED`.

## GREEN verification

Environment used Android Studio JBR and the local Android SDK with `-Pskip.supabase.check=true`.

Core lifecycle/guard/race/persistence gate:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue673SetEndReasonLifecycleTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WorkoutExecutionGuardTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue687WorkoutExecutionIsolationTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WorkoutExitPersistenceTest' `
  --console=plain
```

Result: `BUILD SUCCESSFUL` (70 tests).

Broader lifecycle/integration/teardown and every mechanically adapted test class:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests 'com.devil.phoenixproject.presentation.manager.DWSMWorkoutLifecycleTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.ActiveSessionEngineIntegrationTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WorkoutMachineTeardownTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.DWSMEquipmentRackTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue593BodyweightRepEntryTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue687StaleWorkSuppressionTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.RepMetricPersistenceTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.VbtEnabledRuntimeTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WarmupProgressionTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WeightRecommendationIntegrationTest' `
  --console=plain
```

Result: `BUILD SUCCESSFUL` (151 tests).

Production and full host-test compilation also completed successfully with `:shared:compileAndroidMain` and `:shared:compileAndroidHostTest`.

## Absence and diff checks

```powershell
rg -n "lastSetEndReason|autoStopReason|handleSetCompletion\(\)" shared/src
```

Result: zero matches.

`git diff --check` completed with no whitespace errors. The worktree remains an externally managed named-branch worktree and is preserved; no push or integration action was performed.

## Self-review

- Verified every mandatory origin maps directly at its existing terminal call site.
- Verified no stall detector condition, threshold, duration, grace rule, or reset ordering changed.
- Verified all persistence claim/mark/retry references derive from `completion.lease.sessionId` and the snapshot session ID is required to match it.
- Verified first snapshot installation preserves the immutable completion and CompletedSet ID under terminal capture races.
- Verified bodyweight pending completion is first-wins, current-lease checked, cleared before use, and cleared on invalidation/reset paths.
- Verified all extra test-file changes are limited to explicit lease/reason adaptation, exact forbidden-text comment cleanup, or the one legacy bodyweight test adjustment required to create a real execution before calling the now-explicit API.

## Independent review

A read-only independent review found no Critical production correctness defects. It identified two Important proof/adaptation issues, both corrected before commit:

- The first danger test called a direct helper and did not prove the detector branch. That helper was removed and the replacement test now reaches the actual metric-processing danger predicate/countdown, including the actual-branch mutation proof above.
- The legacy DWSM partial-set test documents a simulated stall completion but its mechanical explicit-reason adaptation supplied `TARGET_REPS_REACHED`; it now supplies `STALL_FAILURE`.

The reviewer independently reran the focused Task 5 suite successfully. Repository-wide `spotlessCheck` remains red on more than 203 pre-existing files, beginning in unrelated Android QA sources, so it is recorded as baseline noise rather than Task 5 verification; `git diff --check` is the scoped whitespace gate for this change.

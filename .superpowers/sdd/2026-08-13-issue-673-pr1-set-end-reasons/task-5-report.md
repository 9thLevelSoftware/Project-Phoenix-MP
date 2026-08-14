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

## Controller rework round 1

### Review findings addressed

- The biomechanics/VBT job now captures the `ExecutionLease` accepted with the rep notification, carries it through background processing and evaluation, and revalidates it before biomechanics, VBT state writes, haptic/defer writes, and terminal completion. The terminal uses that captured lease and never borrows `executionGuard.currentLease` from a replacement execution.
- Bodyweight completion now uses a lease-scoped atomic `Available -> Pending -> Consuming` state machine with an `Invalidated` tombstone. Confirmation claims the shared completion guard before changing `Pending` to `Consuming`, so a failed claim leaves the original completion retryable and never presents an empty replacement window. Invalidation and newer execution installation are monotonic by execution ID and compare the stable execution/session identity, so stale A work cannot republish into B.
- The danger countdown seam remains necessary because the unchanged production path resets the position timer immediately before danger evaluation. It is now an atomic, one-shot override keyed by stable execution/session identity. Stale leases cannot prime or consume it, and reset/start/failure/End/Stop/Skip/cleanup clear it without changing any detector predicate, threshold, duration, classification, or reset ordering.
- `DefaultWorkoutSessionManager` gained only the narrow dispatcher pass-through required to suspend the actual production biomechanics job deterministically. The dispatcher defaults to `Dispatchers.Default`; production behavior is unchanged. `DWSMTestHarness` exposes the corresponding test adapter while preserving its existing trailing callback API.

### Rework RED evidence

1. `suspended execution A biomechanics cannot auto end execution B`
   - Initial compile-scaffolding RED: the test-owned paused dispatcher could not be supplied.
   - After dispatcher-only plumbing, the behavior-level RED queued three real A rep/biomechanics jobs, ended A, started B, and released A. Expected B `Active`; actual B `SetSummary`, proving A's VBT result auto-ended B.
2. `bodyweight confirmation cannot expose its origin to a competing completion`
   - Behavior-level RED forced confirmation to lose the shared completion CAS after the old code had cleared its pending origin. A competing terminal then persisted `TARGET_REPS_REACHED`; expected the original `USER_STOPPED`.
3. `bodyweight completion gate rejects a stale A publication after B begins`
   - Compile-scaffolding RED: `BodyweightCompletionGate` did not exist. The implemented test invalidates A, begins B, attempts a late lower-ID A begin/publication, then proves only B can publish and consume once.
4. `danger zone countdown override cannot cross executions`
   - Compile-scaffolding RED: the replacement lease-bound priming API did not exist. The test primes A, ends A, starts B, retries an A prime, sends a real B danger-zone metric, and proves B remains active with no completed set.

### Rework GREEN evidence

Focused lifecycle/race/guard/persistence/VBT gate:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue673SetEndReasonLifecycleTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.Issue687WorkoutExecutionIsolationTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WorkoutExitPersistenceTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.WorkoutExecutionGuardTest' `
  --tests 'com.devil.phoenixproject.presentation.manager.VbtEnabledRuntimeTest' `
  --console=plain
```

Result: `BUILD SUCCESSFUL` (78 tests: 17 + 21 + 22 + 14 + 4; zero failures/errors).

The two direct danger tests also passed together: the real danger branch persists `CABLE_RELEASED`, while an A override cannot affect B.

The broader 10-class lifecycle/integration/teardown gate was rerun unchanged and passed all 151 tests with zero failures/errors. Production and host-test compilation completed as part of these runs.

### Rework self-review

- Verified the VBT regression suspends the actual dispatcher-launched processing path and was behavior-red before lease threading.
- Verified bodyweight `Pending` is never cleared before completion authority is claimed, `Consuming` preserves the first reason through completion, and stale execution IDs cannot replace newer gate state.
- Verified every bodyweight terminal invalidation is lease-scoped and occurs before the corresponding execution invalidation/continuation.
- Verified the danger override is lease-bound, one-shot, reset across executions, and the existing behavior-sensitive danger-origin mutation proof remains valid against the actual branch.
- Verified the existing #687 teardown/persistence/retry behavior remained green and the dispatcher default keeps production scheduling unchanged.

## Controller rework round 2

### Review findings addressed

- Biomechanics computation is now execution-local. Every execution installs a fresh `BiomechanicsEngine` and lease-scoped VBT context; suspended A work can finish only into A's detached engine. The coordinator exposes a stable HUD result flow and identity-checks engine installation, publication, and reset, so A cannot append into B's summary or publish over B's result.
- VBT counters and the one-shot alert flag moved into the execution context. Result publication, counter/alert/defer mutation, and terminal decisions cross `WorkoutExecutionGuard.commitIfCurrent`, which validates the lease on both sides of the deterministic commit seam. No expensive or suspending biomechanics work is performed while the guard lock is held.
- The global `setCompletionInProgress` flag was removed. Completion ownership is now claimed and released by stable execution identity inside `WorkoutExecutionGuard`; beginning or invalidating an execution clears only the matching ownership. Stale A cannot arm or release B's claim.
- Bodyweight confirmation claims completion authority before consuming its immutable pending origin. `resetForNewWorkout()` invalidates the current bodyweight gate and releases its lease claim before resetting presentation state, so delayed UI confirmation cannot persist after the reset.
- The danger countdown override is stored in a monotonic CAS gate. A lower execution ID cannot replace B, and A's identity-scoped clear cannot erase B. Reset paths clear only the current execution's claim.

### Round-2 RED evidence

1. The initial four-race compile produced expected scaffolding REDs for the missing atomic commit, completion-claim, danger gate, and injected biomechanics processor seams.
2. `suspended A biomechanics after validation cannot publish a result into B` then reached a behavior-level RED with only processor plumbing present: expected B's biomechanics summary to be null, but A's completed computation appeared in the shared B engine.
3. `stale A confirmation crossing B start cannot block B completion` invoked real `startWorkout()` for B between A's pending lookup and completion claim. It failed because B remained `Active` after its terminal request instead of entering bodyweight rep entry, proving stale A had armed the old global flag.
4. `direct reset invalidates pending bodyweight confirmation` replayed a delayed UI confirmation through the production API after `resetForNewWorkout()`. It failed because a `USER_STOPPED` CompletedSet was persisted instead of no record.
5. `A crossing the VBT commit boundary cannot mutate B counters alerts or terminal state` has a behavior-sensitive mutation proof. Removing both the post-seam lease validation and execution-context identity check made the no-stale-alert assertion fail. Restoring them made the same test pass; B's first threshold result remains nonterminal and emits exactly its own one alert.

The bodyweight production RED run completed two tests with two assertion failures. The initial deterministic race run completed four tests with the three primitive tests green and the shared-biomechanics assertion red. No detector predicate, threshold, duration, timer, classification, or reset ordering was changed.

### Round-2 GREEN evidence

The focused lifecycle/execution-isolation/guard/persistence/VBT gate completed 160 tests with zero failures or errors:

- `Issue673SetEndReasonLifecycleTest`: 19
- `Issue687WorkoutExecutionIsolationTest`: 23
- `WorkoutExecutionGuardTest`: 16
- `WorkoutExitPersistenceTest`: 23
- `VbtEnabledRuntimeTest`: 4
- `DWSMWorkoutLifecycleTest`: 75

The broader lifecycle/integration/teardown gate was rerun after the final commit-boundary proof and passed all 151 tests across the same ten classes listed above. Both runs completed `:shared:compileAndroidMain` and `:shared:compileAndroidHostTest` successfully.

The exact forbidden-pattern scan now includes the removed global completion flag:

```powershell
rg -n "lastSetEndReason|autoStopReason|handleSetCompletion\(\)|setCompletionInProgress" shared/src
```

Result: zero matches. `git diff --check` remains clean.

### Round-2 self-review

- Verified result computation uses the captured A engine outside locks, while only short identity-checked publication/state commits execute inside the guard boundary.
- Verified the stable HUD flow is cleared on B installation and cannot be stranded on a detached A engine.
- Verified VBT counter, alert, verbal-event, defer timer, and terminal mutations are either committed while A remains current or discarded; the terminal still carries A's immutable lease/reason into the existing completion path.
- Verified normal, bodyweight-confirmed, warm-up, reset, invalidation, and successor-start completion ownership transitions are lease-scoped and the old global flag has no remaining source/test reference.
- Verified direct reset invalidates pending bodyweight origin before a delayed confirmation can observe it, without changing the existing gate's first-origin/consume-once semantics.
- Verified stale/lower danger primes and clears cannot overwrite B and the existing real danger-branch origin/mutation evidence remains unchanged.
- Verified the final diff contains only Task 5 production files, deterministic Task 5/#687 tests, the narrow manager/harness injection adapters, and this report; no BLE/protocol/schema/migration files were touched.

## Controller rework round 3

### Review findings addressed

- Bodyweight confirmation now crosses a second execution-guard commit boundary after consuming its immutable origin. Variant selection, completion override, and entered rep count are published together only while the originating lease remains current; a stale confirmation releases only its own claim and cannot mutate or block a replacement execution. The later `SetSummary` publication is also an atomic current-lease commit.
- `resetForNewWorkout()` now invalidates the current execution with `RESET_FOR_NEW_WORKOUT`, invalidates rep/bodyweight/danger authority, cancels execution-owned presentation work, clears the matching execution context, and CAS-detaches the matching biomechanics engine to a fresh HUD engine. Suspended computation can finish only into the detached A engine, and a VBT decision already committed for A cannot perform a terminal effect after reset.
- VBT threshold alerts again use suspending `emit`. Delivery occurs outside the execution-guard lock in a lease-owned lazy job; start/reset/cleanup cancellation owns that job alongside completion presentation work. Valid alerts wait for buffer capacity, while invalidated A alerts are canceled before stale delivery.
- The detector predicates, thresholds, countdown durations, stall/auto-stop timers, and end-reason classifications were not changed.

### Round-3 RED evidence

The first behavior-level run compiled both lifecycle classes and completed 45 tests with exactly three assertion failures:

1. `post consume stale A confirmation cannot overwrite or block B` started real execution B after A consumed its pending origin. Expected B's retained `Standard Push-Up` selection; stale A replaced it with `Incline (hands elevated)` before its terminal request was rejected.
2. `direct reset revokes a suspended A biomechanics publication` suspended A's injected real processor, reset, released A, and observed that the old implementation still exposed A's non-null execution lease (with HUD-isolation assertions behind it).
3. `direct reset after a VBT decision commit prevents its terminal effect` reset between the guarded VBT state commit and terminal request and likewise observed that A remained authorized.

After those fixes, `valid VBT alert waits for backpressure and is delivered` produced a separate behavior-level RED: a one-slot `BufferOverflow.SUSPEND` flow was filled behind a blocked real subscriber, and the old `tryEmit` path delivered zero threshold alerts after capacity reopened instead of one.

Environment-only preflight failures for an unset `JAVA_HOME` and undiscovered Android SDK occurred before the valid RED run. The recorded behavior REDs used Microsoft JDK 17, the local Android SDK, and `-Pskip.supabase.check=true`.

### Round-3 GREEN evidence

The consolidated focused gate completed 165 tests with zero failures or errors:

- `Issue673SetEndReasonLifecycleTest`: 20
- `Issue687WorkoutExecutionIsolationTest`: 27
- `WorkoutExitPersistenceTest`: 23
- `WorkoutExecutionGuardTest`: 16
- `VbtEnabledRuntimeTest`: 4
- `DWSMWorkoutLifecycleTest`: 75

The unchanged broader ten-class lifecycle/integration/teardown gate completed 151 tests with zero failures or errors. Production and host-test compilation completed in these runs.

The alert cancellation proof is mutation-sensitive: temporarily removing the alert job from `cancelPresentationJobsFor` made `direct reset cancels a backpressured A VBT alert` fail because A's threshold event arrived after reset and buffer release. Restoring lease-owned cancellation made the same test pass.

### Round-3 self-review

- Verified no execution lock is held across biomechanics computation, `SharedFlow.emit`, coroutine join, teardown, or persistence.
- Verified the bodyweight post-consume barrier uses real B startup and asserts B's selection map, completion override, rep count, active lifecycle, and ability to claim its own completion.
- Verified engine detach is identity-checked at both the atomic execution context and coordinator engine, so stale reset work cannot replace a newer engine.
- Verified reset suppresses both paused-processor HUD publication and post-VBT-commit terminal/persistence effects.
- Verified valid VBT alert ordering and suspending delivery are preserved, and invalidation cancels only the matching execution's alert job.
- Verified the round-3 diff remains inside Task 5 manager/lifecycle/#687 test ownership plus the narrow test harness and this report.

## Controller rework round 4

### Review findings addressed

- Reset now captures an execution-generation token with A's lease. After A is invalidated, all non-suspending global cleanup is committed under the execution guard only when that token still owns the latest generation and no successor is current. If B starts in the gap, reset's identity-scoped A cleanup may finish but its global cleanup is discarded; if B starts and ends in the gap, the advanced generation still rejects stale cleanup.
- Execution replacement now revokes A, clears A's completion claim, and marks A's registered completion and alert-delivery jobs canceled under the same guard transition before B is published. Job registration uses that same lock, so it either registers before replacement and is canceled or loses the current-lease check after replacement. `Job.cancel()` is non-suspending; no join or coroutine work is awaited under the guard.
- The existing suspending `SharedFlow.emit` path remains unchanged. Only execution ownership and cancellation ordering changed; valid alert delivery and backpressure semantics remain intact.
- Detector predicates, thresholds, durations, reset ordering within the shared cleanup block, VBT classification, and stall/auto-stop logic were not changed.

### Round-4 RED and mutation evidence

The first valid class run compiled and executed 29 `Issue687WorkoutExecutionIsolationTest` tests. `reset cleanup cannot erase a successor that starts after A is invalidated` failed at assertion level because legacy reset continued after real B startup and erased B's coordinator state. The test asserts B's guard lease, session id, active state, rep count, selection map, biomechanics engine/HUD identity, and ability to persist its own terminal reason.

The same run also exposed a fixture-only full-lease comparison in the new alert test because activation returns an immutable lease copy with a cutover timestamp. That comparison was corrected to stable execution/session identity and is not counted as behavior RED. Behavior sensitivity for `replacement cancels backpressured A alert before B becomes current` was then proven by mutation: temporarily removing the outgoing-job cancellation from `beginExecution` caused the exact stale-haptic assertion to fail after capacity reopened in the B-current/pre-legacy-cancel seam. Restoring guard-atomic cancellation made the test pass.

An initial unresolved `BiomechanicsEngine` test import was compile scaffolding and is excluded from RED evidence. The first broad gate also found one obsolete #687 unit assertion that expected B job registration to remain blocked until a post-replacement A cancellation. It was narrowly updated to assert the new contract: replacement already canceled A, B can attach, and a later stale-A cancellation cannot cancel B.

### Round-4 GREEN evidence

The final consolidated focused gate completed 168 tests with zero failures or errors:

- `Issue673SetEndReasonLifecycleTest`: 20
- `Issue687WorkoutExecutionIsolationTest`: 29
- `WorkoutExitPersistenceTest`: 23
- `WorkoutExecutionGuardTest`: 17
- `VbtEnabledRuntimeTest`: 4
- `DWSMWorkoutLifecycleTest`: 75

The unchanged broader ten-class lifecycle/integration/teardown gate completed 151 tests with zero failures or errors. Both gates compiled production and host-test sources. The mandatory absence scan
`rg -n "lastSetEndReason|autoStopReason|handleSetCompletion\(\)" shared/src` returned zero matches, and `git diff --check` was clean.

### Round-4 self-review

- Audited the only production `executionGuard.beginExecution` call and every `invalidate`/`invalidateCurrent` path. Registration and replacement are lock-serialized, A is revoked before cancellation can resume it, and B is not published until matching jobs are marked canceled.
- Audited reset races before invalidation, after invalidation, during successor startup, and after successor termination. Bodyweight and danger cleanup are lease-identity scoped; rep freshness, execution context, and biomechanics detach are A-scoped; generation-guarded global cleanup cannot erase B.
- Confirmed the guard holds no lock across `emit`, `join`, biomechanics computation, BLE work, persistence, teardown, or other suspension/expensive work. The guarded reset block contains only immediate state updates and non-suspending job cancellation.
- Confirmed Android's monitor and iOS's recursive platform lock cannot form a new cross-lock cycle in these paths: the replacement helper does not acquire the biomechanics lock, while identity-checked biomechanics detach/install remains outside the execution guard.
- Confirmed the round-4 diff is limited to Task 5 manager sources, focused guard/execution-isolation tests, the narrowly adapted #687 stale-work guard assertion, the test harness, and this report.

## Controller rework round 5

### Review findings addressed

- Reset cleanup now depends only on the captured reset generation, current-execution absence, and the token's stable lease identity. It no longer depends on `invalidatedLeaseRef`, which teardown legitimately clears when A reaches `Ready`; a real A teardown-ready transition therefore cannot suppress cleanup when no successor exists.
- `beginExecution`, `invalidateCurrent`, and identity-scoped `invalidate` now cancel the outgoing lease's registered completion/alert jobs under the execution guard before publishing authority loss or a successor. Job registration uses the same guard. The cancellation call is non-suspending and identity-scoped; no `emit`, `join`, BLE, teardown, persistence, or biomechanics work runs under the guard.
- The existing reset-generation tests still prove that B start, including B start/end, blocks stale A cleanup. Detector predicates, timing, classification, and alert delivery remain unchanged.

### Round-5 RED and mutation evidence

Four focused behavior tests were introduced before the production change. The valid old-code run compiled and executed all four groups and failed at assertion level:

- `reset token commits cleanup after A teardown becomes ready without a successor` showed the guard rejecting cleanup after `markTeardownReady(A)` cleared the teardown-owned invalidated lease.
- `reset cleanup survives A teardown becoming ready after invalidation` reproduced the same race through the real engine, deferred BLE stop, real `requestTeardownForTransition`, and reset invalidation barrier.
- `invalidation cancels a backpressured alert before publishing authority loss` released a saturated real `MutableSharedFlow` at the exact cancellation boundary and observed alert delivery only after A was no longer current under the old ordering.
- `current invalidation and replacement cancel jobs while A remains authoritative` covered both `invalidateCurrent` and real replacement and observed cancellation after authority publication under the old ordering.

Both fixes were then independently mutation-checked after GREEN. Restoring the `invalidatedLeaseRef` dependency failed the two reset tests (2/2). Restoring null-before-cancel ordering failed the exact backpressure and invalidation/replacement tests (2/2). Both mutations were reverted before the final gates.

### Round-5 GREEN evidence

The final consolidated focused gate completed 172 tests with zero failures or errors:

- `Issue673SetEndReasonLifecycleTest`: 20
- `Issue687WorkoutExecutionIsolationTest`: 30
- `WorkoutExitPersistenceTest`: 23
- `WorkoutExecutionGuardTest`: 20
- `VbtEnabledRuntimeTest`: 4
- `DWSMWorkoutLifecycleTest`: 75

The unchanged broader ten-class lifecycle/integration/teardown gate completed 151 tests with zero failures or errors. Production and host-test sources compiled in the focused run. The expanded mandatory absence scan
`rg -n "lastSetEndReason|autoStopReason|handleSetCompletion\(\)|setCompletionInProgress" shared/src` returned zero matches, and `git diff --check` was clean.

### Round-5 self-review

- Verified reset cleanup remains allowed after A teardown becomes ready when no successor exists, while the generation token still rejects cleanup after B starts or starts and ends.
- Verified job cancellation and job registration are serialized by the same guard, cancellation precedes authority publication, and cancellation cannot affect a different execution identity.
- Verified only non-suspending `Job.cancel()` and immediate reference/generation updates run under the guard. The deterministic test-only cancellation wrappers do not reenter the guard lock; they only release flow capacity or sample the lock-free current lease.
- Verified the round-5 diff is limited to `WorkoutExecutionGuard`, focused guard/execution-isolation tests, and this report.

# Issue #687: Workout Execution Isolation and Safe BLE Teardown

**Date:** 2026-08-13
**Status:** Approved design
**Issue:** https://github.com/9thLevelSoftware/Project-Phoenix-MP/issues/687

## Summary

Prevent work from an ended set from completing, navigating, or writing through a later set. End Workout remains responsive: the user may leave the workout and switch profiles immediately, while every workout start path stays disabled until the connected trainer has reached a safe teardown boundary.

The change introduces a focused workout-execution guard rather than replacing the existing workout state model. Each set receives an execution ID, asynchronous work carries that ID, and End Workout invalidates it synchronously. BLE teardown is connection-wide. Exit-time persistence uses an immutable snapshot of the originating set and profile instead of reading mutable coordinator state after navigation.

## Problem and evidence

Issue #687 reports that a newly started set intermittently flashes the correct active screen and then jumps to a “Workout Complete” rest surface. The reporter frequently ends workouts, switches profiles, reopens a routine, and resumes where they left off. The attached video shows the new set reach Active at 0/3 before being moved into rest without performing a rep.

The current implementation has four race surfaces:

1. ActiveSessionEngine accepts any rep notification whenever WorkoutState is Active. Notifications have no execution identity.
2. RepCounterFromMachine resets its local counters at set start and trusts a modern packet's repsSetCount. A delayed terminal packet from the preceding set can therefore look like immediate completion of the new set.
3. handleSetCompletion launches untracked manager-scope work. End Workout cancels workoutJob, bodyweightTimerJob, and restTimerJob, but cannot reliably cancel or invalidate completion work that has already escaped into the manager scope.
4. stopWorkout performs BLE work and persistence asynchronously while reading mutable coordinator and active-profile state. Navigation and profile switching can occur before those reads finish.

BleOperationQueue already serializes individual BLE operations, but there is no product-level barrier preventing a new workout configuration from being requested while the previous reset and cleanup are unresolved.

## Goals

- A stale coroutine or BLE notification from execution A cannot mutate execution B.
- End Workout navigates away immediately and never blocks profile switching.
- No start action reaches the machine until prior BLE teardown succeeds.
- A failed or timed-out reset never fails open.
- Exit persistence remains attributed to the originating profile and routine.
- Automatic completion racing manual exit creates at most one persisted session.
- Routine, single-exercise, Just Lift, bodyweight, autoplay, motion start, and voice-triggered paths follow the same safety contract.
- The exact workflow in issue #687 is reproducible in deterministic tests.

## Non-goals

- Replacing the complete workout engine with a command actor or new global state machine.
- Changing profile-switching behavior or routine progress/resumption semantics.
- Adding database schema or wire-format changes.
- Using fixed sleeps as synchronization or as the stale-event defense.
- Refactoring unrelated BLE, workout UI, or persistence behavior.

## Selected approach

Use a lifecycle epoch plus a connection-wide teardown barrier.

This is preferred over a full command actor because it isolates the known race without rewriting ActiveSessionEngine, RoutineFlowManager, and DefaultWorkoutSessionManager. It is preferred over a timed notification quarantine because correctness does not depend on device speed or an arbitrary delay.

## Architecture and ownership

### WorkoutExecutionGuard

Add an internal WorkoutExecutionGuard owned by ActiveSessionEngine. It owns:

- a monotonically increasing execution ID;
- the current MachineTeardownState;
- the current execution's completion job;
- the current teardown job;
- the rep-notification freshness state;
- a small terminal-persistence claim ledger keyed by stable session ID.

The guard does not replace WorkoutState or RoutineFlowState. Those remain the UI and routine-flow models in WorkoutCoordinator. The guard is an authority boundary that determines whether asynchronous work may act on those models.

Every set start creates a new ExecutionLease containing:

- execution ID;
- stable session ID;
- activation cutover timestamp;
- originating profile ID;
- isBodyweight, isJustLift, isAMRAP, isTimedCable, and the configured working-rep target.

Completion, summary delay, rest transition, bodyweight timer, timed-cable timer, autoplay transition, and delayed navigation work capture the lease. Before changing coordinator state, launching a successor transition, or starting a persistence claim, the work verifies that the lease is still current. A failed check is a logged no-op.

### MachineTeardownState

MachineTeardownState has exactly three states:

- **Ready:** a workout may start if the existing connection and profile-readiness checks also pass.
- **TearingDown:** the previous execution is invalid and BLE reset is in progress.
- **RecoveryRequired:** reset failed, timed out, or lost its connection; a recovery action is required before another start.

This state is connection-wide, not profile-scoped. Switching profiles cannot create a second command lane to the same trainer.

The barrier applies to every path that concludes a command-bearing set and issues RESET, including automatic completion, Stop Set, Skip Exercise, and End Workout. Non-exit paths may already be showing summary or rest while teardown runs, but no successor set may start until the state returns to Ready. End Workout differs only in that it detaches the live routine and navigates away immediately.

### Immutable exit snapshot

Before End Workout clears or navigates away from live state, it creates a WorkoutExitSnapshot containing:

- execution and stable session IDs;
- originating profile ID;
- routine session, routine ID, routine name, exercise, and set indices;
- workout parameters and selected exercise identity;
- rep counts, metrics, biomechanics/quality summaries, rack adjustment, and RPE;
- workout and routine timestamps required for duration;
- the values required to build WorkoutSession and CompletedSet records.

Persistence from an exit snapshot must not read activeProfile, loadedRoutine, current indices, currentSessionId, or mutable workout parameters after the snapshot is captured.

## End Workout flow

End Workout performs the following synchronous boundary work before navigation:

1. Capture WorkoutExitSnapshot.
2. Advance the execution ID, making all prior leases stale.
3. Set MachineTeardownState to TearingDown for cable-based work, or complete local teardown immediately for a bodyweight-only execution that sent no trainer command.
4. Cancel completion, summary-delay, rest, exercise-timer, and set-owned transition jobs.
5. Detach the live coordinator from the ended routine and publish the existing Idle/NotInRoutine exit state.

The UI then returns to the caller's previously captured exit destination. Profile switching remains available.

Two isolated tasks may continue:

### Machine teardown

- Send the existing RESET operation through BleRepository and BleOperationQueue.
- Bound the operation with BleConstants.GATT_OPERATION_TIMEOUT_MS, currently five seconds.
- Stop local workout polling in a finally block even when RESET fails.
- Move to Ready only after RESET succeeds.
- Move to RecoveryRequired on failure, timeout, or connection loss.

The gate concerns machine safety only. Successful BLE teardown may return to Ready while immutable persistence continues.

### Persistence

Manual exit and automatic completion share the execution's stable session ID and terminal persistence claim.

The first terminal path atomically claims persistence in a ledger keyed by stable session ID. The ledger entry survives creation of later executions until the save reaches Persisted or its failure is handed to the existing retry/error path. Once claimed, immutable persistence work is separated from presentation transitions and is not cancelled merely because the execution is invalidated. A competing terminal path observes InProgress or Persisted and does not write a duplicate. A failure may be retried with the same stable session ID.

The first valid claim wins. If automatic completion has already captured and claimed the completed-set snapshot, End Workout cancels its later UI transitions but allows that immutable save to finish. If End Workout claims first, the stale automatic completion cannot claim or publish anything.

## Start flow

All start paths call the same guarded ActiveSessionEngine entry point. UI disabling is informative; the engine guard is authoritative.

1. Require MachineTeardownState.Ready, a connected trainer when required, and ActiveProfileContext.Ready.
2. Create a new ExecutionLease and enter WorkoutState.Initializing.
3. Build and send the workout configuration through the serialized BLE queue.
4. After a successful configuration write, record the activation cutover timestamp, reset the per-execution freshness state, start active polling, and publish Active.
5. If configuration fails, invalidate the lease and return through the existing start-error path without opening rep acceptance.

Autoplay, motion-triggered start, handle detection, voice actions, and direct UI calls cannot bypass this entry point. While teardown is unresolved, automatic triggers are disarmed and explicit attempts emit a single “Finishing previous workout” or recovery feedback event rather than queuing another start.

## Rep notification freshness

Each execution starts in AwaitingEvidence. Notifications parsed before the activation cutover are rejected using RepNotification.timestamp. Notifications received after the cutover are evaluated as follows:

- A modern zero-count packet establishes a fresh baseline and arms the execution.
- A modern non-terminal progression below the configured target is current-set evidence when the packet's reported target is zero/unavailable or matches the lease's configured target; it arms the execution and is then processed normally.
- Post-cutover HandleState.Moving from the existing HandleStateDetector arms the execution. This reuses the current movement threshold and covers fixed one-rep sets whose first rep packet may already be terminal.
- For legacy packets, the first post-cutover directional counters are captured as a baseline without producing reps. A subsequent counter change or post-cutover movement arms normal processing.
- A terminal packet received before any current-set evidence is rejected and logged. It cannot update RepCounterFromMachine, rep UI, completion flags, or WorkoutState.

Once Armed, existing rep counting and completion rules remain unchanged. The freshness state resets for every Start and is invalidated by End Workout.

This policy specifically rejects the observed 0/N to rest transition while preserving legitimate one-rep completion after current-session movement.

## UX and recovery

Every surface capable of starting a workout observes MachineTeardownState:

- **Ready:** existing controls and labels.
- **TearingDown:** Start is disabled and labeled “Finishing previous workout…”.
- **RecoveryRequired:** Start remains disabled and a persistent message states “Trainer reset didn't complete,” with Retry and Reconnect actions.

Profile switching and unrelated navigation remain enabled in all three states.

Retry resends RESET through the serialized BLE queue. Reconnect disconnects and reconnects through the existing connection manager, then performs a recovery RESET before returning to Ready. A reconnect alone does not silently clear RecoveryRequired.

If persistence fails after BLE teardown succeeds, Start is not blocked because the machine is safe. The existing workout-data error path reports the save failure separately.

## Observability

Add structured connection-log events for:

- execution created and invalidated;
- teardown started, succeeded, failed, or timed out;
- Start rejected by teardown state;
- stale lease state write suppressed;
- rep notification rejected for pre-cutover timestamp;
- terminal rep notification rejected while AwaitingEvidence;
- persistence claimed, deduplicated, succeeded, or failed.

Logs include execution ID, transition, packet counter metadata, rejection reason, elapsed time, and BLE result. They exclude workout metrics, profile values, exercise values, and other personal data.

## Error handling

- CancellationException is always rethrown by cancellable jobs.
- Teardown uses finally to stop local polling and clear owned job references.
- A stale lease is a normal race outcome and is logged at debug level, not surfaced as a user error.
- Reset failure, timeout, or disconnect enters RecoveryRequired and never enables Start.
- Retry and Reconnect are idempotent while an existing recovery operation is active.
- Persistence failure does not mutate the current execution and uses the stable session ID for retry safety.
- Cleanup invalidates the current execution and cancels owned jobs before stopping the workout service.

## Testing strategy

### Deterministic regression tests

Add coroutine-controlled tests using the DWSM test harness and controllable BLE/persistence fakes:

1. Suspend completion work for execution A, end A, start B after teardown, resume A, and assert that A cannot publish SetSummary, Resting, Completed, or RoutineFlowState.Complete.
2. Start B and deliver A's queued terminal packet with a pre-cutover timestamp. B remains Active at zero reps.
3. Deliver a post-cutover terminal packet before evidence. It is rejected and B remains Active.
4. Deliver post-cutover movement or non-terminal progression, then a terminal packet. B completes normally.
5. Cover a fixed one-rep set: movement arms freshness before the terminal rep.
6. Cover legacy packets: the first packet baselines directional counters and cannot create phantom reps.
7. Reject a modern packet whose reported target conflicts with the current lease.
8. Suspend profile A persistence, switch to profile B, start a later execution, then resume the save. The session is stored once under profile A with profile A's routine metadata.
9. Race automatic completion and End Workout in both claim orders. Each execution produces at most one WorkoutSession and one matching CompletedSet.
10. Verify direct, autoplay, motion, handle, and voice start paths are rejected during TearingDown and RecoveryRequired.
11. Verify RESET success returns to Ready; failure and five-second timeout enter RecoveryRequired; Retry/Reconnect cannot fail open.
12. Verify bodyweight-only exit cancels local work and reopens the gate without sending an unnecessary trainer command.
13. Cover routine, temporary single-exercise, Just Lift, and timed-cable flows.

Existing workout lifecycle, navigation, rep-counting, profile-attribution, BLE queue, and cancellation tests must remain green.

### Device validation

On Android, repeat the reporter's workflow at least 50 times on a connected trainer:

1. Start a routine set.
2. End Workout.
3. Switch profiles immediately.
4. Reopen a routine and attempt to start while teardown is active.
5. Start once the gate becomes Ready.

No iteration may:

- enable Start before safe teardown;
- jump from 0/N into rest or completion;
- send overlapping RESET/configuration operations;
- duplicate a persisted session or completed set;
- attribute the ended set to the newly active profile;
- require an app restart to recover from a successful teardown.

Also force RESET failure and timeout to verify the persistent recovery UI and successful Retry/Reconnect path.

## Acceptance criteria

- End Workout navigation and profile switching are immediate.
- All new workout starts are connection-wide gated until RESET succeeds.
- Stale jobs and notifications from an invalid execution cannot mutate a current execution.
- The issue #687 0/N to Workout Complete transition is covered by a failing-before/passing-after deterministic test.
- Exit persistence is immutable, profile-correct, and exactly once per stable session ID.
- RESET failure and timeout remain fail-closed with usable recovery actions.
- No correctness rule depends on a fixed delay.
- All affected common tests pass, followed by the 50-iteration Android device workflow.

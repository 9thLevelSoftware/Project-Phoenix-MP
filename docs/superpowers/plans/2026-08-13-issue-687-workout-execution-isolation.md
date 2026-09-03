# Issue #687 Workout Execution Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent an ended set's delayed BLE packets, coroutines, navigation, or persistence from completing a later set, while preserving immediate End Workout navigation and profile switching.

**Architecture:** Add an `ActiveSessionEngine`-owned execution guard with monotonic leases, a connection-wide teardown barrier, per-execution rep freshness gating, and stable-session persistence claims. Capture terminal data before navigation into immutable snapshots; allow machine teardown and persistence to finish independently; expose only the machine-safety state to UI and recovery controls.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines/Flow, kotlinx.atomicfu, Compose Multiplatform, Kable BLE, kotlin.test/Turbine, Gradle Android host tests.

## Global Constraints

- Implement the approved design in `docs/superpowers/specs/2026-08-13-issue-687-workout-execution-isolation-design.md`; do not broaden this into a workout-engine rewrite.
- Keep `WorkoutState` and `RoutineFlowState` as presentation/navigation state. `WorkoutExecutionGuard` is an authority boundary, not a replacement state machine.
- Keep `MachineTeardownState` connection-wide. Profile switching must never create a second command lane to the same trainer.
- End Workout must synchronously capture, invalidate, detach, and publish the existing exit state before returning; BLE reset and immutable persistence continue separately.
- All command-bearing start paths must pass through `ActiveSessionEngine.startWorkout`. UI gating is informative; the engine check is authoritative.
- Do not add fixed sleeps for correctness. Existing countdown/rest delays may remain, but RESET/config ordering must be expressed with jobs, Flow state, and bounded timeouts.
- Use the timestamp domain already carried by `RepNotification.timestamp` (`currentTimeMillis`), not elapsed realtime, for activation cutover comparisons.
- Rethrow `CancellationException` from cancellable work. A stale lease is a debug-level no-op; RESET failure/timeout/disconnect is a fail-closed recovery state.
- Do not add a database migration. Stable `WorkoutSession.id` and stable `CompletedSet.id` plus the in-memory claim ledger provide retry/deduplication identity.
- Do not run broad `spotlessApply`; this repository has a known formatting baseline. Format only touched files or make manual formatting fixes, then use `git diff --check` and the check-only commands in Task 9.
- The current shell needs a JDK 17 `JAVA_HOME` before Gradle can run. Configure it before executing test steps; never report a Gradle test as passing if it could not start.
- `openspec/AGENTS.md` is referenced by the root instructions but is absent in this checkout. Follow this approved design and the existing `docs/superpowers` conventions unless that file appears before implementation begins.

---

### Task 1: Build the execution and persistence authority primitives

**Files:**

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ConnectionLogRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuardTest.kt`

- [ ] **Step 1: Write failing lease, teardown-state, and persistence-claim tests.**

Cover monotonic execution IDs, stale-lease rejection, synchronous invalidation, legal teardown transitions, retry from `RecoveryRequired`, duplicate terminal claims, failed-claim retry with the same session ID, and pruning only terminal persisted entries. Use stable IDs in assertions rather than timing.

```kotlin
class WorkoutExecutionGuardTest {
    @Test
    fun `invalidating execution A makes every A lease check fail`() {
        val guard = WorkoutExecutionGuard()
        val leaseA = guard.beginExecution(
            ExecutionSeed(
                sessionId = "session-a",
                profileId = "profile-a",
                requiresMachine = true,
                workingRepTarget = 3,
            ),
        ).getOrThrow()

        guard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT)
        val leaseB = guard.beginExecution(
            ExecutionSeed(
                sessionId = "session-b",
                profileId = "profile-b",
                requiresMachine = true,
                workingRepTarget = 3,
            ),
        ).getOrThrow()

        assertFalse(guard.isCurrent(leaseA))
        assertTrue(guard.isCurrent(leaseB))
        assertTrue(leaseB.executionId > leaseA.executionId)
    }

    @Test
    fun `only one terminal path claims a stable session id`() {
        val guard = WorkoutExecutionGuard()

        assertIs<PersistenceClaimResult.Claimed>(
            guard.claimPersistence("session-a", TerminalPath.AUTO_COMPLETE),
        )
        assertIs<PersistenceClaimResult.DuplicateInProgress>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )

        guard.markPersistenceSucceeded("session-a")
        assertIs<PersistenceClaimResult.AlreadyPersisted>(
            guard.claimPersistence("session-a", TerminalPath.END_WORKOUT),
        )
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the red state.**

Run:

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutExecutionGuardTest*" --console=plain
```

Expected: compilation fails because the guard types do not exist.

- [ ] **Step 3: Implement the exact commonMain authority types.**

Use `kotlinx.atomicfu.atomic` for the current lease and execution sequence, `MutableStateFlow` for machine state, and `withPlatformLock` for the small claim map. Keep all identifiers and reasons non-PII.

```kotlin
sealed interface MachineTeardownState {
    data object Ready : MachineTeardownState
    data class TearingDown(val executionId: Long, val attempt: Int) : MachineTeardownState
    data class RecoveryRequired(val executionId: Long) : MachineTeardownState
}

internal enum class TeardownFailureReason {
    RESET_FAILED,
    TIMED_OUT,
    DISCONNECTED,
}

internal enum class ExecutionInvalidationReason {
    END_WORKOUT,
    STOP_SET,
    SKIP_EXERCISE,
    CLEANUP,
    START_FAILED,
}

internal enum class TerminalPath {
    AUTO_COMPLETE,
    MANUAL_STOP,
    END_WORKOUT,
}

internal data class ExecutionSeed(
    val sessionId: String,
    val profileId: String,
    val requiresMachine: Boolean,
    val workingRepTarget: Int,
    val isBodyweight: Boolean = false,
    val isJustLift: Boolean = false,
    val isAmrap: Boolean = false,
    val isTimedCable: Boolean = false,
)

internal data class ExecutionLease(
    val executionId: Long,
    val sessionId: String,
    val profileId: String,
    val requiresMachine: Boolean,
    val workingRepTarget: Int,
    val isBodyweight: Boolean,
    val isJustLift: Boolean,
    val isAmrap: Boolean,
    val isTimedCable: Boolean,
    val activationCutoverTimestampMs: Long? = null,
)

internal sealed interface PersistenceClaimResult {
    data object Claimed : PersistenceClaimResult
    data object DuplicateInProgress : PersistenceClaimResult
    data object AlreadyPersisted : PersistenceClaimResult
}

internal data class RecoveryAttempt(
    val lease: ExecutionLease,
    val attempt: Int,
)
```

Implement these guard operations with compare-and-set/locked transitions:

```kotlin
internal class WorkoutExecutionGuard {
    val machineTeardownState: StateFlow<MachineTeardownState>
    val currentLease: ExecutionLease?

    fun beginExecution(seed: ExecutionSeed): Result<ExecutionLease>
    fun activate(lease: ExecutionLease, cutoverTimestampMs: Long): ExecutionLease?
    fun isCurrent(lease: ExecutionLease): Boolean
    fun invalidateCurrent(reason: ExecutionInvalidationReason): ExecutionLease?
    fun beginTeardown(lease: ExecutionLease, attempt: Int = 1): Boolean
    fun markTeardownReady(lease: ExecutionLease): Boolean
    fun markRecoveryRequired(lease: ExecutionLease, reason: TeardownFailureReason): Boolean
    fun beginRecoveryAttempt(): RecoveryAttempt?
    fun claimPersistence(sessionId: String, path: TerminalPath): PersistenceClaimResult
    fun markPersistenceSucceeded(sessionId: String)
    fun markPersistenceFailed(sessionId: String)
    fun prunePersistedClaims(retainNewest: Int = 32)
}
```

`beginExecution` must return failure unless machine state is `Ready`. `activate` compares the full `(executionId, sessionId)` identity against the current lease. Teardown completion/recovery compare against an internally retained teardown lease so an End-invalidated lease can still finish machine cleanup. `markPersistenceFailed` must permit a later claim with the same ID; in-progress and persisted claims must not.

- [ ] **Step 4: Add structured event names and guard logging.**

Add the following constants to `LogEventType`, and have the guard accept a small logger callback `(eventType, details) -> Unit` with a no-op default so its tests stay isolated:

```kotlin
const val WORKOUT_EXECUTION = "WORKOUT_EXECUTION"
const val WORKOUT_TEARDOWN = "WORKOUT_TEARDOWN"
const val WORKOUT_REP_REJECTED = "WORKOUT_REP_REJECTED"
const val WORKOUT_PERSISTENCE = "WORKOUT_PERSISTENCE"
```

Details may contain execution ID, session ID, transition, reason, attempt, elapsed milliseconds, and packet counters. Do not include profile ID, routine/exercise names, load, reps performed, or workout metrics.

- [ ] **Step 5: Run the focused tests and commit.**

Run:

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutExecutionGuardTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ConnectionLogRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuardTest.kt
git commit -m "feat: add workout execution guard"
```

Expected: focused tests pass; commit contains only the three listed files.

---

### Task 2: Add deterministic rep-notification freshness gating

**Files:**

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RepNotificationFreshnessGate.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/RepNotificationFreshnessGateTest.kt`

- [ ] **Step 1: Write the complete freshness decision matrix as failing tests.**

Test all approved rules: pre-cutover drop; modern all-zero baseline arms; matching-target non-terminal progress arms and processes; conflicting reported target drops; terminal-before-evidence drops; post-cutover movement arms a one-rep terminal packet; first legacy packet baselines without processing; second legacy counter change arms and processes; invalidation/reset returns to `AwaitingEvidence`.

```kotlin
@Test
fun `terminal packet before evidence is rejected`() {
    val gate = RepNotificationFreshnessGate()
    val lease = activeLease(target = 3, cutover = 1_000L)

    val decision = gate.evaluate(
        lease,
        modernPacket(repsSetCount = 3, repsSetTotal = 3, timestamp = 1_001L),
    )

    assertEquals(
        RepFreshnessDecision.Drop(RepDropReason.TERMINAL_BEFORE_EVIDENCE),
        decision,
    )
    assertEquals(RepFreshnessState.AwaitingEvidence, gate.stateFor(lease))
}

@Test
fun `movement arms a fixed one rep execution`() {
    val gate = RepNotificationFreshnessGate()
    val lease = activeLease(target = 1, cutover = 1_000L)

    assertTrue(gate.observeMovement(lease))
    assertEquals(
        RepFreshnessDecision.Process,
        gate.evaluate(
            lease,
            modernPacket(repsSetCount = 1, repsSetTotal = 1, timestamp = 1_001L),
        ),
    )
}
```

- [ ] **Step 2: Run the focused test and confirm it fails on missing types.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*RepNotificationFreshnessGateTest*" --console=plain
```

- [ ] **Step 3: Implement a pure gate with no delays and no coordinator access.**

```kotlin
internal sealed interface RepFreshnessState {
    data object AwaitingEvidence : RepFreshnessState
    data class LegacyBaseline(val topCounter: Int, val completeCounter: Int) : RepFreshnessState
    data object Armed : RepFreshnessState
}

internal enum class RepDropReason {
    LEASE_NOT_ACTIVE,
    PRE_CUTOVER_TIMESTAMP,
    TARGET_MISMATCH,
    TERMINAL_BEFORE_EVIDENCE,
}

internal sealed interface RepFreshnessDecision {
    data object Process : RepFreshnessDecision
    data object BaselineOnly : RepFreshnessDecision
    data class Drop(val reason: RepDropReason) : RepFreshnessDecision
}

internal class RepNotificationFreshnessGate {
    fun resetFor(lease: ExecutionLease)
    fun invalidate(lease: ExecutionLease)
    fun observeMovement(lease: ExecutionLease): Boolean
    fun stateFor(lease: ExecutionLease): RepFreshnessState
    fun evaluate(lease: ExecutionLease, notification: RepNotification): RepFreshnessDecision
}
```

Use these exact predicates:

```kotlin
val targetMatches = notification.repsSetTotal == 0 ||
    notification.repsSetTotal == lease.workingRepTarget
val terminal = lease.workingRepTarget > 0 &&
    notification.repsSetCount >= lease.workingRepTarget
val allZero = notification.topCounter == 0 &&
    notification.completeCounter == 0 &&
    notification.repsRomCount == 0 &&
    notification.repsSetCount == 0
val hasNonTerminalProgress = !terminal && (
    notification.topCounter > 0 ||
        notification.completeCounter > 0 ||
        notification.repsRomCount > 0 ||
        notification.repsSetCount > 0
    )
```

Evaluate timestamp before packet shape. For modern packets, reject a non-zero mismatched target before accepting progress. For legacy packets, the first post-cutover packet always returns `BaselineOnly`; only a later counter change returns `Process`. Once armed, process post-cutover packets without reapplying the target mismatch rule, because firmware fields may change after current-set evidence is established.

Attach the gate as an internal property of `WorkoutExecutionGuard` after the pure type exists:

```kotlin
internal val repFreshnessGate = RepNotificationFreshnessGate()
```

This keeps freshness lifecycle under the approved guard authority while preserving a separately testable pure decision object.

- [ ] **Step 4: Run tests and commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*RepNotificationFreshnessGateTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RepNotificationFreshnessGate.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/RepNotificationFreshnessGateTest.kt
git commit -m "feat: gate stale workout rep notifications"
```

---

### Task 3: Make start and rep processing lease-aware

**Files:**

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687RepIsolationTest.kt`

- [ ] **Step 1: Add failing engine-level regression tests for the observed jump.**

Use `DWSMTestHarness`, a connected fake trainer, and explicit packet timestamps. Assert all of the following before implementation:

1. A packet timestamped before B's cutover cannot move B from `Active` or increment B.
2. A post-cutover terminal packet before evidence cannot move B.
3. Movement then terminal completes a one-rep B normally.
4. A legacy first packet with carried counters produces no rep.
5. A conflicting modern target is rejected.

```kotlin
@Test
fun `issue 687 delayed terminal packet cannot complete the new execution`() = runTest {
    val harness = DWSMTestHarness(this)
    harness.fakeBleRepo.simulateConnect("Vee_Test")
    harness.startCableSet(targetReps = 3)
    val cutoverB = harness.activeSessionEngine.currentExecutionLeaseForTest()
        .activationCutoverTimestampMs
        ?: error("execution B was not activated")

    harness.fakeBleRepo.emitRepNotification(
        harness.modernRepPacket(
            repsSetCount = 3,
            repsSetTotal = 3,
            timestamp = cutoverB - 1,
        ),
    )
    runCurrent()

    assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
    assertEquals(0, harness.coordinator.repCount.value.workingReps)
    harness.cleanup()
}
```

- [ ] **Step 2: Run the issue-focused test and verify the current failure.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687RepIsolationTest*" --console=plain
```

Expected: the pre-fix engine accepts the delayed terminal packet or the new test-only lease accessor is unresolved.

- [ ] **Step 3: Instantiate the guard and freshness gate in `ActiveSessionEngine`.**

Add a wall-clock provider next to the existing elapsed-realtime provider, and pass a deterministic value from the test harness:

```kotlin
private val wallClockMillisProvider: () -> Long = ::currentTimeMillis

private val executionGuard = WorkoutExecutionGuard(::logExecutionEvent)
private val repFreshnessGate: RepNotificationFreshnessGate
    get() = executionGuard.repFreshnessGate

val machineTeardownState: StateFlow<MachineTeardownState>
    get() = executionGuard.machineTeardownState

internal fun currentExecutionLeaseForTest(): ExecutionLease =
    requireNotNull(executionGuard.currentLease)
```

In `DWSMTestHarness`, use a stable epoch plus virtual time so tests can construct packets on both sides of cutover:

```kotlin
companion object {
    const val TEST_WALL_CLOCK_EPOCH_MS = 1_800_000_000_000L
}

val nowMs: Long
    get() = TEST_WALL_CLOCK_EPOCH_MS + testScope.testScheduler.currentTime

// Pass through DefaultWorkoutSessionManager into ActiveSessionEngine.
wallClockMillisProvider = { nowMs },
```

Thread `wallClockMillisProvider` through `DefaultWorkoutSessionManager` to `ActiveSessionEngine`. Replace hard-coded rep timestamps in affected DWSM tests with `harness.nowMs + offset`; do not weaken production timestamp checks to accommodate old fixtures.

- [ ] **Step 4: Guard `startWorkout` before it mutates shared state.**

Resolve the current exercise and `requiresMachine` before beginning. Require `ActiveProfileContext.Ready`, `MachineTeardownState.Ready`, and a connected trainer for cable work. Bodyweight work may begin without a trainer command.

```kotlin
private fun rejectStart(reason: StartRejectionReason) {
    val message = when (reason) {
        StartRejectionReason.TEARING_DOWN -> "Finishing previous workout…"
        StartRejectionReason.RECOVERY_REQUIRED -> "Trainer reset didn't complete"
        StartRejectionReason.PROFILE_SWITCHING -> "Profile switch in progress"
        StartRejectionReason.NOT_CONNECTED -> "Connect to your trainer first"
    }
    coordinator._userFeedbackEvents.tryEmit(message)
    connectionLogRepository.info(
        LogEventType.WORKOUT_EXECUTION,
        "Workout start rejected",
        details = "reason=${reason.name}",
    )
}

private enum class StartRejectionReason {
    TEARING_DOWN,
    RECOVERY_REQUIRED,
    PROFILE_SWITCHING,
    NOT_CONNECTED,
}
```

Create the stable session ID once in `ExecutionSeed`; remove the later duplicate `currentSessionId = randomUUID()` assignments. Store the originating ready-profile ID in the lease. After the configuration write succeeds, perform this sequence without suspension between steps:

```kotlin
val activeLease = executionGuard.activate(lease, wallClockMillisProvider())
    ?: return@launch
repFreshnessGate.resetFor(activeLease)
bleRepository.startActiveWorkoutPolling()
if (!executionGuard.isCurrent(activeLease)) return@launch
coordinator._workoutState.value = WorkoutState.Active
```

If validation or config send fails, invalidate that exact lease, reset freshness/session identity, publish the prior safe `Idle`/Set Ready state, and return through the existing BLE error path. Before every post-countdown/config state write, verify `executionGuard.isCurrent(lease)`.

- [ ] **Step 5: Gate both rep collectors and bind rep callbacks to the current lease.**

Extract one collector body and use it from init and `restartCollectionJobs` so pause/resume cannot bypass freshness:

```kotlin
private fun acceptRepNotification(notification: RepNotification) {
    val lease = executionGuard.currentLease ?: return
    if (coordinator._workoutState.value !is WorkoutState.Active) return

    when (val decision = repFreshnessGate.evaluate(lease, notification)) {
        RepFreshnessDecision.Process -> handleRepNotification(lease, notification)
        RepFreshnessDecision.BaselineOnly -> logRepDrop(lease, "legacy-baseline", notification)
        is RepFreshnessDecision.Drop -> logRepDrop(lease, decision.reason.name, notification)
    }
}
```

Call `repFreshnessGate.observeMovement(lease)` only when `HandleState.Moving` is observed while that lease is current and activated. Change `handleRepNotification` to accept a lease, check it before calling `RepCounterFromMachine.process`, and check again before publishing coordinator rep state.

At the top of `repCounter.onRepEvent`, synchronously capture the current lease. Inside its launched coroutine, return unless that lease is still current. The `WORKOUT_COMPLETE` branch must call `handleSetCompletion(lease)`, not a parameterless completion path.

- [ ] **Step 6: Run focused and existing lifecycle tests, then commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*RepNotificationFreshnessGateTest*" --tests "*Issue687RepIsolationTest*" --tests "*DWSMWorkoutLifecycleTest*" --tests "*ActiveSessionEngineIntegrationTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687RepIsolationTest.kt
git commit -m "fix: isolate rep events by workout execution"
```

Expected: the issue test stays `Active` at zero for both stale terminal cases, then demonstrates normal completion after current-execution evidence.

---

### Task 4: Centralize RESET behind the connection-wide teardown barrier

**Files:**

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeBleRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutMachineTeardownTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt`

- [ ] **Step 1: Give the BLE fake controllable RESET completion and observability.**

Add a suspend block rather than test sleeps. Count RESET, StopPacket, polling-stop, disconnect, and reconnect calls.

```kotlin
var stopWorkoutBlock: suspend () -> Result<Unit> = { Result.success(Unit) }
var stopWorkoutCallCount = 0
var stopPollingCallCount = 0
var disconnectCallCount = 0

override suspend fun stopWorkout(): Result<Unit> {
    stopWorkoutCallCount++
    return stopWorkoutBlock()
}

override fun stopPolling() {
    stopPollingCallCount++
}
```

Reset these hooks and counts in `FakeBleRepository.reset()`.

- [ ] **Step 2: Write failing tests for success, failure, timeout, disconnect, idempotence, bodyweight, and successor ordering.**

Use `CompletableDeferred<Result<Unit>>` to hold RESET in flight. Assert:

- `MachineTeardownState.TearingDown` is published before RESET starts.
- A direct `startWorkout` call during teardown sends no config and emits one rejection.
- Success moves to `Ready`; `Result.failure`, five virtual seconds, or disconnected postcondition moves to `RecoveryRequired`.
- Repeated exit/retry taps do not create overlapping RESET jobs.
- `stopPolling` is called in every outcome.
- Bodyweight-only exit sends no RESET and leaves the machine gate `Ready`.
- A warm-up successor or exercise jump begins only after RESET success, with no `delay(100)`/`delay(150)` ordering.

```kotlin
@Test
fun `new config cannot start until reset succeeds`() = runTest {
    val harness = DWSMTestHarness(this)
    val resetResult = CompletableDeferred<Result<Unit>>()
    harness.fakeBleRepo.stopWorkoutBlock = { resetResult.await() }
    harness.startCableSet(targetReps = 3)

    harness.dwsm.stopAndReturnToSetReady()
    runCurrent()
    assertIs<MachineTeardownState.TearingDown>(
        harness.dwsm.machineTeardownState.value,
    )

    val configCountBefore = harness.fakeBleRepo.commandsReceived.size
    harness.dwsm.startWorkout(skipCountdown = true)
    runCurrent()
    assertEquals(configCountBefore, harness.fakeBleRepo.commandsReceived.size)

    resetResult.complete(Result.success(Unit))
    advanceUntilIdle()
    assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
    harness.cleanup()
}
```

- [ ] **Step 3: Make `KableBleRepository.stopWorkout` stop polling in `finally`.**

Preserve the existing RESET packet and 50 ms protocol settle delay, but remove polling cleanup from the success-only body:

```kotlin
override suspend fun stopWorkout(): Result<Unit> = try {
    val resetCmd = BlePacketFactory.createResetCommand()
    val sendResult = sendWorkoutCommand(resetCmd)
    delay(50)
    sendResult
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
} finally {
    stopPolling()
}
```

The engine will also call `stopPolling` in its teardown `finally`; repository implementations must tolerate this idempotently. The duplicate layer is deliberate: the repository owns local BLE polling, while the engine owns the product safety postcondition.

- [ ] **Step 4: Implement one teardown launcher in `ActiveSessionEngine`.**

Retain the outgoing lease as the teardown token even when End Workout has invalidated it. Guard teardown transitions against the token recorded in `TearingDown`, not against `currentLease`, because immediate exit intentionally clears `currentLease` first.

```kotlin
private fun beginMachineTeardown(
    lease: ExecutionLease,
    reason: TeardownReason,
    attempt: Int = 1,
    afterReady: (() -> Unit)? = null,
) {
    if (!lease.requiresMachine) {
        if (executionGuard.isCurrent(lease)) afterReady?.invoke()
        return
    }
    if (!executionGuard.beginTeardown(lease, attempt)) return

    val job = scope.launch {
        val startedAt = elapsedRealtimeProvider()
        try {
            val result = withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                bleRepository.stopWorkout().getOrThrow()
            }
            if (bleRepository.connectionState.value !is ConnectionState.Connected) {
                executionGuard.markRecoveryRequired(lease, TeardownFailureReason.DISCONNECTED)
                return@launch
            }
            if (executionGuard.markTeardownReady(lease) && executionGuard.isCurrent(lease)) {
                afterReady?.invoke()
            }
        } catch (error: TimeoutCancellationException) {
            executionGuard.markRecoveryRequired(lease, TeardownFailureReason.TIMED_OUT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            executionGuard.markRecoveryRequired(lease, TeardownFailureReason.RESET_FAILED)
        } finally {
            bleRepository.stopPolling()
            executionGuard.clearTeardownJobIfOwned(lease)
            logTeardownElapsed(lease, reason, elapsedRealtimeProvider() - startedAt)
        }
    }
    executionGuard.attachTeardownJob(lease, job)
}
```

Add `TeardownReason` values `AUTO_COMPLETE`, `MANUAL_STOP`, `STOP_SET`, `SKIP_EXERCISE`, `END_WORKOUT`, `WARMUP_TRANSITION`, and `EXERCISE_JUMP`. `attachTeardownJob` must refuse replacement while the same attempt is active. Timeout/failure must never call `afterReady`.

- [ ] **Step 5: Route every command-bearing exit RESET through the launcher.**

Update these exact call sites:

- `handleSetCompletion(lease)` automatic completion.
- `stopWorkout(exitingWorkout = false)` manual set completion.
- `stopWorkout(exitingWorkout = true)` End Workout.
- `stopAndReturnToSetReady()`.
- `stopAndSkipCurrentExercise()`.
- variable warm-up transitions before their immediate successor starts.
- `RoutineFlowManager.jumpToExercise()` when it exits summary/rest and currently sends StopPacket + RESET + delays.

Replace the routine delegate's raw BLE methods with a semantic request:

```kotlin
interface WorkoutLifecycleDelegate {
    fun resetRepCounter()
    fun startWorkout(skipCountdown: Boolean = false)
    fun requestTeardownForTransition(
        reason: TeardownReason,
        afterReady: () -> Unit,
    )
    fun setWorkoutParametersInternal(params: WorkoutParameters)
}
```

`jumpToExercise` must call `requestTeardownForTransition(EXERCISE_JUMP)`, then perform `navigateToExerciseInternal` and guarded start inside `afterReady`. Remove the two hard-coded delays. Keep the existing active-set navigation block.

Do not change the currently unused pause/resume API in this issue: pause is not an exit and preserving its continuation semantics requires a separate protocol decision. Add a source assertion that no new exit RESET bypasses the launcher, and record the pre-existing pause call as the single documented non-exit exception.

- [ ] **Step 6: Run teardown, repository, and routine-flow tests, then commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutMachineTeardownTest*" --tests "*KableBleRepositoryTest*" --tests "*DWSMRoutineFlowTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeBleRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutMachineTeardownTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt
git commit -m "fix: serialize workout teardown before restart"
```

---

### Task 5: Capture immutable terminal snapshots and persist exactly once

**Files:**

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitSnapshot.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeCompletedSetRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitPersistenceTest.kt`

- [ ] **Step 1: Add controllable persistence hooks and failing race tests.**

Capture every attempted session/completed-set ID and allow session saving to suspend:

```kotlin
var beforeSaveSession: suspend (WorkoutSession) -> Unit = {}
val saveSessionAttempts = mutableListOf<WorkoutSession>()

override suspend fun saveSession(session: WorkoutSession) {
    saveSessionAttempts += session
    beforeSaveSession(session)
    sessions[session.id] = session
    updateSessionsFlow()
}
```

Write both claim-order tests (`AUTO_COMPLETE` first and `END_WORKOUT` first), plus profile attribution. Also hold persistence after RESET success, start execution B, and prove the A claim remains in progress without blocking B. Force one persistence failure and prove machine state remains `Ready` while a retry with A's stable session ID can claim again.

```kotlin
@Test
fun `profile switch during suspended exit save keeps origin attribution`() = runTest {
    val harness = DWSMTestHarness(this)
    val releaseSave = CompletableDeferred<Unit>()
    harness.fakeWorkoutRepo.beforeSaveSession = { releaseSave.await() }
    harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-a")
    harness.startCableSet(targetReps = 3)

    harness.dwsm.stopWorkout(exitingWorkout = true)
    runCurrent()
    harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-b")
    releaseSave.complete(Unit)
    advanceUntilIdle()

    val saved = harness.fakeWorkoutRepo.saveSessionAttempts.single()
    assertEquals("profile-a", saved.profileId)
    assertEquals(1, harness.fakeWorkoutRepo.saveSessionAttempts.count { it.id == saved.id })
    assertEquals(1, harness.fakeCompletedSetRepo.saved.count { it.sessionId == saved.id })
    harness.cleanup()
}
```

- [ ] **Step 2: Run the persistence test and confirm it fails against live-state reads or duplicate writes.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutExitPersistenceTest*" --console=plain
```

- [ ] **Step 3: Define the immutable snapshot around fully built persistence values.**

Generate `WorkoutSession.id` and `CompletedSet.id` once, during synchronous capture. Copy all lists so later coordinator mutation cannot alter the snapshot.

```kotlin
internal data class WorkoutExitSnapshot(
    val lease: ExecutionLease,
    val terminalPath: TerminalPath,
    val session: WorkoutSession,
    val completedSet: CompletedSet?,
    val metrics: List<WorkoutMetric>,
    val repMetrics: List<RepMetricData>,
    val biomechanicsRepResults: List<BiomechanicsRepResult>,
    val presentationSummary: WorkoutState.SetSummary,
    val exerciseIndex: Int,
    val setIndex: Int,
    val isRoutineSet: Boolean,
    val shouldAccumulateRoutineCalories: Boolean,
    val shouldExportIndividualHealthSession: Boolean,
    val shouldExportIndividualBackup: Boolean,
    val shouldUpdateCycleProgress: Boolean,
    val postSaveInput: PostSaveWorkoutInput,
)

internal data class WorkoutExecutionContext(
    val lease: ExecutionLease,
    val exerciseName: String?,
    val preferredCableCount: Int?,
    val displayMultiplier: Int?,
    val plannedSetId: String?,
    val sessionBodyWeightKg: Float,
    val routineSessionId: String?,
    val routineId: String?,
    val routineName: String?,
)

internal data class PostSaveWorkoutInput(
    val profileId: String,
    val exerciseId: String?,
    val workingReps: Int,
    val achievedWeightKg: Float,
    val volumeWeightKg: Float,
    val programMode: ProgramMode,
    val isJustLift: Boolean,
    val isEchoMode: Boolean,
    val peakConcentricForceKg: Float,
    val peakEccentricForceKg: Float,
    val sessionMcvMmS: Float?,
)
```

`RepMetricData` and biomechanics force curves contain arrays, so `List.toList()` is not sufficient. Add snapshot-only deep-copy helpers using `FloatArray.copyOf()`/`LongArray.copyOf()` for every rep-metric curve/timestamp array and for `BiomechanicsRepResult.forceCurve.normalizedForceN` and `normalizedPositionPct`.

During the start coroutine, before activation, cache the immutable `WorkoutExecutionContext`: exercise name/cable metadata, the suspend `findPlannedSetId` result, origin body weight, and routine IDs/name. End Workout can then build its snapshot synchronously without repository lookup. Use the lease's `profileId`, never `userProfileRepository.activeProfile.value`, when building `WorkoutSession` and post-save input.

- [ ] **Step 4: Capture before any exit clear and split persistence from presentation.**

Refactor the duplicated manual/automatic session building into:

```kotlin
private fun captureExitSnapshot(
    lease: ExecutionLease,
    terminalPath: TerminalPath,
): WorkoutExitSnapshot

private fun launchSnapshotPersistence(snapshot: WorkoutExitSnapshot) {
    when (executionGuard.claimPersistence(snapshot.session.id, snapshot.terminalPath)) {
        PersistenceClaimResult.Claimed -> scope.launch { persistSnapshot(snapshot) }
        PersistenceClaimResult.DuplicateInProgress,
        PersistenceClaimResult.AlreadyPersisted,
        -> logPersistenceDeduplicated(snapshot)
    }
}

private suspend fun persistSnapshot(snapshot: WorkoutExitSnapshot)
```

`persistSnapshot` must use only `snapshot` plus repositories/managers. It must not read `activeProfile`, `loadedRoutine`, current indices, `currentSessionId`, live workout parameters, live rep count, live metrics, live rack adjustment, or live RPE. Pass snapshot fields into `processPostSaveEvents`, CompletedSet save, rep metric save, biomechanics save, health export, backup, and cycle-update helpers.

Make retry after a partial write idempotent with existing repository reads/deletes: skip `saveSession` when `getSession(session.id)` already exists; skip `saveCompletedSet` when `getCompletedSets(session.id)` already contains the stable CompletedSet ID; replace per-rep metrics and biomechanics for that stable session ID by deleting the existing rows before writing the snapshot copies. Add a test that fails after the session insert, retries the same snapshot, and leaves exactly one `WorkoutSession` and one matching `CompletedSet`.

Mark the claim persisted only after the local `WorkoutSession`, metrics, CompletedSet, rep metrics, and biomechanics writes finish. On failure, call `markPersistenceFailed`, emit the existing user-feedback/error event, and keep machine state independent; a safe trainer remains startable.

- [ ] **Step 5: Make End Workout's synchronous boundary explicit.**

The `exitingWorkout = true` branch must execute in this order before its persistence/teardown coroutines can run:

```kotlin
val snapshot = captureExitSnapshot(lease, TerminalPath.END_WORKOUT)
executionGuard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT)
cancelSetOwnedPresentationJobs(lease)
detachEndedRoutineFromCoordinator()
coordinator._workoutState.value = WorkoutState.Idle
coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
launchSnapshotPersistence(snapshot)
beginMachineTeardown(lease, TeardownReason.END_WORKOUT)
```

Do not await either launched task. Existing UI code has already captured the exit destination; profile switching remains available immediately.

- [ ] **Step 6: Run persistence and profile tests, then commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutExitPersistenceTest*" --tests "*Profile*Workout*Test*" --tests "*DWSMWorkoutLifecycleTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitSnapshot.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeWorkoutRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/FakeCompletedSetRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitPersistenceTest.kt
git commit -m "fix: persist workout exits from immutable snapshots"
```

---

### Task 6: Bind completion, timers, and delayed navigation to their execution

**Files:**

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687StaleWorkSuppressionTest.kt`

- [ ] **Step 1: Write the delayed-A/resumed-under-B regression before refactoring.**

Suspend A after its terminal snapshot/claim, End A, complete teardown, start B, then release every A-controlled boundary. Assert B remains `Active`, its routine indices and routine flow are unchanged, and no A work publishes `SetSummary`, `Resting`, `Completed`, or `RoutineFlowState.Complete`.

Also test stale summary countdown, rest countdown, timed-cable completion, bodyweight timer, auto-start timer, warm-up successor, `proceedFromSummary`, and `startNextSetOrExercise` callbacks.

```kotlin
private fun assertExecutionBStillActive(harness: DWSMTestHarness) {
    assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
    assertFalse(harness.coordinator.routineFlowState.value is RoutineFlowState.Complete)
    assertEquals("session-b", harness.activeSessionEngine.currentExecutionLeaseForTest().sessionId)
}
```

- [ ] **Step 2: Run the focused test and confirm stale work currently leaks.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687StaleWorkSuppressionTest*" --console=plain
```

- [ ] **Step 3: Give the guard ownership of completion and teardown jobs.**

Add exact-lease attachment and cancellation methods:

```kotlin
fun attachCompletionJob(lease: ExecutionLease, job: Job): Boolean
fun clearCompletionJobIfOwned(lease: ExecutionLease)
fun attachTeardownJob(lease: ExecutionLease, job: Job): Boolean
fun clearTeardownJobIfOwned(lease: ExecutionLease)
fun cancelPresentationJobsFor(lease: ExecutionLease)
fun cancelAllOwnedJobs()
```

The completion job covers summary/rest/navigation presentation only. Snapshot persistence is launched separately and is not canceled by execution invalidation. Teardown is connection-wide and is not canceled merely because a profile switch or later navigation invalidates presentation.

- [ ] **Step 4: Thread `ExecutionLease` through every delayed set-owned path.**

Change these signatures and their call sites:

```kotlin
internal fun handleSetCompletion(lease: ExecutionLease)
private fun startRestTimer(lease: ExecutionLease)
private fun startNextSetOrExercise(lease: ExecutionLease)
private fun startAutoStartTimer(expectedLease: ExecutionLease?)
private fun startMotionStartDetection(lease: ExecutionLease)
private fun startTimedCableTimer(lease: ExecutionLease, durationSeconds: Int)
private fun startBodyweightTimer(lease: ExecutionLease, durationSeconds: Int)
```

Before each coordinator write, haptic, delayed successor, delegate navigation call, or call to `startWorkout`, use one helper:

```kotlin
private inline fun ifCurrent(
    lease: ExecutionLease,
    transition: String,
    block: () -> Unit,
) {
    if (executionGuard.isCurrent(lease)) {
        block()
    } else {
        logSuppressedStateWrite(lease, transition)
    }
}
```

Capture a lease in `DefaultWorkoutSessionManager.proceedFromSummary` and any summary auto-advance job, then check through an internal `activeSessionEngine.isCurrentExecution(lease)` before and after suspension. Apply the same rule to RoutineFlow delegate callbacks that mutate current indices after asynchronous work.

Automatic starts while `TearingDown`/`RecoveryRequired` must be disarmed, not queued. The only allowed `afterReady` successor is the explicit continuation registered by the outgoing current lease (variable warm-up or exercise jump); it still checks that the outgoing lease remains current, so End Workout cancels it by invalidation.

- [ ] **Step 5: Invalidate and cancel presentation jobs during cleanup.**

`ActiveSessionEngine.cleanup()` must invalidate the current lease, reset freshness, cancel completion/transition/timer jobs, then cancel owned teardown only because the entire manager scope is ending. It must stop the workout service through the existing DWSM cleanup path. Do not mark a canceled teardown `Ready` in cleanup.

- [ ] **Step 6: Run stale-work and navigation regressions, then commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687StaleWorkSuppressionTest*" --tests "*DWSMWorkoutLifecycleTest*" --tests "*DWSMRoutineFlowTest*" --tests "*Issue660*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687StaleWorkSuppressionTest.kt
git commit -m "fix: suppress stale workout transitions"
```

---

### Task 7: Add fail-closed Retry and Reconnect recovery

**Files:**

- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutTeardownRecoveryTest.kt`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManagerRecoveryTest.kt`

- [ ] **Step 1: Write recovery tests before exposing actions.**

Cover:

- Retry from `RecoveryRequired` sends one RESET and reaches `Ready` only on success.
- Retry failure/timeout remains `RecoveryRequired`.
- Retry while `TearingDown` is idempotently ignored.
- Retry while disconnected does not fail open.
- Reconnect strictly orders cancel old connection job → stop scan/cancel connection → disconnect → scan/connect → connected state → recovery RESET.
- Reconnect failure leaves the gate `RecoveryRequired`.
- Repeated Reconnect taps do not create parallel radio or RESET operations.

```kotlin
@Test
fun `reconnect does not clear recovery until post-connect reset succeeds`() = runTest {
    val harness = DWSMTestHarness(this)
    harness.forceResetFailureThenRecoveryRequired()
    harness.fakeBleRepo.simulateDisconnect()

    harness.reconnectWorkoutTeardown()
    advanceUntilIdle()

    assertEquals(1, harness.fakeBleRepo.disconnectCallCount)
    assertEquals(1, harness.fakeBleRepo.scanAndConnectCallCount)
    assertEquals(2, harness.fakeBleRepo.stopWorkoutCallCount)
    assertEquals(MachineTeardownState.Ready, harness.dwsm.machineTeardownState.value)
    harness.cleanup()
}
```

- [ ] **Step 2: Run focused recovery tests and confirm missing-action failures.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutTeardownRecoveryTest*" --tests "*BleConnectionManagerRecoveryTest*" --console=plain
```

- [ ] **Step 3: Implement idempotent retry in the guard and engine.**

Retain the last teardown lease and attempt number inside the guard. Expose a single transition object so the state change and token retrieval cannot race:

```kotlin
internal fun WorkoutExecutionGuard.beginRecoveryAttempt(): RecoveryAttempt?
```

`beginRecoveryAttempt` must atomically change `RecoveryRequired` to `TearingDown`, increment attempt, and return null from any other state. `ActiveSessionEngine.retryMachineTeardown()` must require a connected trainer, then launch the same bounded RESET implementation used by normal teardown. It must not duplicate timeout or result handling.

- [ ] **Step 4: Add deterministic reconnect sequencing to `BleConnectionManager`.**

Keep it separate from ordinary `ensureConnection`, because recovery must disconnect even when currently connected:

```kotlin
fun reconnectForWorkoutRecovery(
    onConnected: () -> Unit,
    onFailed: () -> Unit,
) {
    if (recoveryConnectionJob?.isActive == true) return
    recoveryConnectionJob = scope.launch {
        try {
            connectionJob?.cancelAndJoin()
            bleRepository.stopScanning()
            bleRepository.cancelConnection()
            bleRepository.disconnect()
            val result = bleRepository.scanAndConnect(timeoutMs = 30_000L)
            result.getOrThrow()
            withTimeout(15_000L) {
                connectionState.first { it is ConnectionState.Connected }
            }
            onConnected()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _connectionError.value = error.message ?: "Connection failed"
            onFailed()
        } finally {
            recoveryConnectionJob = null
        }
    }
}
```

Do not use the existing already-connected fast path. Reconnect alone never mutates `MachineTeardownState`; `onConnected` invokes `retryMachineTeardown`, and only the subsequent RESET can return the gate to `Ready`.

- [ ] **Step 5: Thread public state/actions through DWSM and MainViewModel.**

```kotlin
// DefaultWorkoutSessionManager
val machineTeardownState: StateFlow<MachineTeardownState>
    get() = activeSessionEngine.machineTeardownState
fun retryMachineTeardown() = activeSessionEngine.retryMachineTeardown()

// MainViewModel
val machineTeardownState: StateFlow<MachineTeardownState>
    get() = workoutSessionManager.machineTeardownState
fun retryWorkoutTeardown() = workoutSessionManager.retryMachineTeardown()
fun reconnectWorkoutTeardown() = bleConnectionManager.reconnectForWorkoutRecovery(
    onConnected = workoutSessionManager::retryMachineTeardown,
    onFailed = {},
)
```

Verify `MachineTeardownState` remains the public sealed interface introduced in Task 1 while the guard, leases, failure reason, and persistence internals remain `internal`, so public UI APIs do not expose an internal Kotlin type. The guard retains `TeardownFailureReason` for logs/retry behavior; UI needs only the public `RecoveryRequired` state.

- [ ] **Step 6: Run tests and commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*WorkoutTeardownRecoveryTest*" --tests "*BleConnectionManagerRecoveryTest*" --tests "*WorkoutMachineTeardownTest*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManager.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutTeardownRecoveryTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/BleConnectionManagerRecoveryTest.kt
git commit -m "feat: recover failed workout teardown"
```

---

### Task 8: Surface teardown state on every command-bearing Start UI

**Files:**

- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WorkoutStartGateNotice.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutSetupDialog.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DailyRoutinesScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ResumeRoutineDialog.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-de/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-es/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-fr/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-it/strings.xml`
- Modify: `shared/src/commonMain/composeResources/values-nl/strings.xml`
- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/Issue687WorkoutStartGateUiTest.kt`

- [ ] **Step 1: Write presentation mapping and source-wiring tests first.**

Test `Ready`, `TearingDown`, and `RecoveryRequired` presentation values without Compose, then follow the repository's existing source-contract test pattern to assert that all direct command-bearing Start surfaces consume `machineTeardownState` and recovery actions.

Fresh routine/cycle/Home buttons that only navigate into `RoutineOverview` or `SetReady` remain enabled; they do not send trainer config and are unrelated navigation. Direct command paths covered here are Workout Setup/Just Lift, Set Ready, Single Exercise, Daily Routines Resume, and Active Workout restart/Just Lift action. Engine auto-start/motion entry points were covered in Tasks 3 and 6.

```kotlin
@Test
fun `tearing down disables start with finishing label`() {
    val presentation = MachineTeardownState.TearingDown(
        executionId = 7L,
        attempt = 1,
    ).toStartGatePresentation()

    assertFalse(presentation.startEnabled)
    assertEquals(StartGateLabel.FINISHING_PREVIOUS_WORKOUT, presentation.label)
    assertFalse(presentation.showRecoveryActions)
}
```

- [ ] **Step 2: Run the UI contract test and verify it fails.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687WorkoutStartGateUiTest*" --console=plain
```

- [ ] **Step 3: Add localized strings and a reusable notice.**

Add:

```xml
<string name="workout_teardown_finishing">Finishing previous workout…</string>
<string name="workout_teardown_failed">Trainer reset didn't complete</string>
<string name="workout_teardown_retry">Retry</string>
```

Add equivalent translations to every existing localized `values-*` file so this feature does not regress resource completeness.

Reuse the existing `reconnect` resource. Implement a pure mapping plus a composable notice:

```kotlin
enum class StartGateLabel {
    START,
    FINISHING_PREVIOUS_WORKOUT,
}

data class WorkoutStartGatePresentation(
    val startEnabled: Boolean,
    val label: StartGateLabel,
    val showRecoveryActions: Boolean,
)

fun MachineTeardownState.toStartGatePresentation(): WorkoutStartGatePresentation

@Composable
fun WorkoutStartGateNotice(
    state: MachineTeardownState,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
)
```

For `RecoveryRequired`, show the persistent failed message and both actions; keep the Start button disabled. For `TearingDown`, show the finishing label and no actions. Profile controls and general navigation must not consult this state.

- [ ] **Step 4: Add state and recovery actions to the Workout state-holder path.**

```kotlin
val machineTeardownState: MachineTeardownState = MachineTeardownState.Ready

fun onRetryWorkoutTeardown()
fun onReconnectWorkoutTeardown()
```

Add the state property to `WorkoutUiState` and the two functions to `WorkoutActions`; then implement them in the preview and factory implementations.

Update `PreviewWorkoutActions`, the `workoutActions` factory, `ActiveWorkoutScreen`'s remembered state/actions, and the state-holder overload in `WorkoutTab`. Pass the gate presentation into `WorkoutSetupDialog`; do not hide the sheet when a blocked start is tapped.

- [ ] **Step 5: Wire direct start surfaces without duplicating policy.**

- In `WorkoutSetupDialog`, Start is enabled only when an exercise is selected and the presentation allows start; its label changes during teardown and the recovery notice appears above it.
- In `SetReadyScreen`, collect `viewModel.machineTeardownState`, combine it with existing connection/bodyweight conditions, change the button label, and place the notice above the bottom action row.
- In `SingleExerciseScreen`, pass gate state into `ExerciseEditBottomSheet`. Add defaulted `primaryActionEnabled` and `primaryActionSupportingContent` parameters to the sheet; preserve every non-workout caller's current behavior.
- In `DailyRoutinesScreen`, pass gate state into the Resume dialog. Add defaulted `confirmEnabled`, `confirmLabel`, and `supportingContent` parameters to `ResumeRoutineDialog`; Home and Training Cycle callers retain defaults because their Resume actions route to Set Ready rather than sending config.
- In `ActiveWorkoutScreen`, pass the state/actions through `WorkoutUiState` for Just Lift/restart entry.

Keep the final engine guard even on every correctly disabled button. A recomposition race or future caller must still be rejected in `ActiveSessionEngine`.

- [ ] **Step 6: Run UI contract and affected presentation tests, then commit.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687WorkoutStartGateUiTest*" --tests "*WorkoutUiState*" --tests "*SetReady*" --tests "*SingleExercise*" --tests "*ResumeRoutine*" --console=plain
git diff --check
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WorkoutStartGateNotice.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutSetupDialog.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DailyRoutinesScreen.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ResumeRoutineDialog.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt shared/src/commonMain/composeResources/values shared/src/commonMain/composeResources/values-de shared/src/commonMain/composeResources/values-es shared/src/commonMain/composeResources/values-fr shared/src/commonMain/composeResources/values-it shared/src/commonMain/composeResources/values-nl shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/Issue687WorkoutStartGateUiTest.kt
git commit -m "feat: show workout teardown start gate"
```

---

### Task 9: Prove the full issue workflow and perform release-level verification

**Files:**

- Create: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687WorkoutExecutionIsolationTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt`
- Create: `docs/qa/issue-687-workout-execution-isolation.md`

- [ ] **Step 1: Add the end-to-end coroutine regression matrix.**

Build scenario helpers, but keep independent assertions for:

1. The exact A → End → profile switch → B → delayed A terminal packet workflow.
2. A terminal packet after cutover but before evidence.
3. Normal modern progress and fixed one-rep movement completion.
4. Legacy baseline and subsequent delta.
5. Auto-complete/End claim races in both orders.
6. Routine, temporary single-exercise, Just Lift, bodyweight, and timed-cable execution types.
7. Direct start, handle auto-start, motion start, warm-up successor, summary autoplay, and external/MainViewModel-equivalent direct entry attempts while teardown is blocked.
8. RESET success, failure, timeout, Retry, and Reconnect.
9. Immediate `Idle`/`NotInRoutine` publication and unrestricted profile switch while RESET/persistence remain suspended.
10. Structured execution/teardown/rep-rejection/persistence logs contain IDs, reasons, attempts, elapsed time, and packet counters but exclude profile, routine/exercise, load, and metric values.

The current tree has no voice-start implementation. Do not invent one for this bug. Prove instead that the engine's public start entry rejects during teardown; any future voice caller using that entry inherits the same authority check.

```kotlin
@Test
fun `issue 687 reporter workflow remains on execution B at zero reps`() = runTest {
    val harness = DWSMTestHarness(this)
    harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-a")
    harness.startRoutineSet("routine-a", targetReps = 3)
    val leaseA = harness.activeSessionEngine.currentExecutionLeaseForTest()

    harness.dwsm.stopWorkout(exitingWorkout = true)
    assertIs<WorkoutState.Idle>(harness.coordinator.workoutState.value)
    assertIs<RoutineFlowState.NotInRoutine>(harness.coordinator.routineFlowState.value)
    harness.fakeUserProfileRepo.seedReadyProfileForTest("profile-b")
    harness.completeResetSuccessfully()
    harness.startRoutineSet("routine-b", targetReps = 3)

    harness.fakeBleRepo.emitRepNotification(
        harness.modernRepPacket(
            repsSetCount = 3,
            repsSetTotal = 3,
            timestamp = requireNotNull(leaseA.activationCutoverTimestampMs) + 1,
        ),
    )
    runCurrent()

    assertIs<WorkoutState.Active>(harness.coordinator.workoutState.value)
    assertEquals(0, harness.coordinator.repCount.value.workingReps)
    assertEquals("profile-b", harness.activeSessionEngine.currentExecutionLeaseForTest().profileId)
    harness.cleanup()
}
```

Ensure the delayed packet's timestamp is earlier than B's cutover even when it is later than A's cutover; advance virtual wall time between executions to make that relationship explicit.

- [ ] **Step 2: Run all issue-focused tests as one gate.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --tests "*Issue687*" --tests "*WorkoutExecutionGuardTest*" --tests "*RepNotificationFreshnessGateTest*" --tests "*WorkoutMachineTeardownTest*" --tests "*WorkoutExitPersistenceTest*" --tests "*WorkoutTeardownRecoveryTest*" --console=plain
```

Expected: all issue-specific tests pass with zero failures.

- [ ] **Step 3: Audit start and RESET call sites after the refactor.**

```powershell
rg -n "startWorkout\(|startSetFromReady\(" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation
rg -n "bleRepository\.stopWorkout\(" shared/src/commonMain/kotlin/com/devil/phoenixproject
rg -n "delay\((100|150)" shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager
```

Expected:

- Every command-bearing start reaches `ActiveSessionEngine.startWorkout`.
- Exit RESET calls exist only in the centralized teardown implementation; the pre-existing non-exit pause call is documented by Task 4.
- The old exercise-jump 100/150 ms synchronization delays are gone.

- [ ] **Step 4: Run the repository's shared and platform verification.**

```powershell
.\gradlew.bat -Pskip.supabase.check=true :shared:testAndroidHostTest --continue --console=plain
.\gradlew.bat -Pskip.supabase.check=true :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
.\gradlew.bat -Pskip.supabase.check=true :androidApp:assembleDebug --console=plain
.\gradlew.bat -Pskip.supabase.check=true spotlessCheck --console=plain
git diff --check
```

Expected: Android host tests, iOS main/test compilation, Android debug assembly, and `git diff --check` pass. `spotlessCheck` is check-only: if the known repository-wide baseline still fails, record the exact unchanged baseline and verify every touched Kotlin/Gradle file is absent from the failure list; do not run broad `spotlessApply`.

- [ ] **Step 5: Execute and record physical trainer validation.**

Create `docs/qa/issue-687-workout-execution-isolation.md` with device/app/commit identifiers and a 50-row result table. On Android with a connected trainer, repeat:

1. Start a routine set.
2. End Workout.
3. Switch profiles immediately.
4. Reopen a routine and attempt Start while teardown is active.
5. Start after the gate reaches `Ready`.

Record for every iteration: Start disabled during teardown, no overlapping RESET/config, B remained at 0/N until an actual rep, correct profile attribution, and one session/completed set. Then force one RESET failure and one timeout; verify persistent recovery UI, successful Retry, and successful Reconnect + recovery RESET.

No iteration may jump to summary/rest, duplicate persistence, attribute A to profile B, or require an app restart after successful recovery. If physical hardware is unavailable, leave this checkbox open and report implementation as awaiting device validation rather than complete.

- [ ] **Step 6: Commit the final regression and QA evidence.**

```powershell
git add shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue687WorkoutExecutionIsolationTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt docs/qa/issue-687-workout-execution-isolation.md
git commit -m "test: verify issue 687 workout isolation"
git status --short
```

Expected: no implementation-created changes remain unstaged/uncommitted and the QA document identifies the exact verified commit. Preserve and report any unrelated pre-existing worktree entries. If device validation remains open, do not make a completion claim.

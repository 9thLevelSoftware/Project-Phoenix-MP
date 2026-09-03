# Issue #673 PR 2 Drop-Set Engine and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the deterministic, execution-safe drop-set retry engine, attempt/history identity, exercise-scoped load overlay, and process-death recovery foundation without enabling a production offer UI.

**Architecture:** Pure domain policies compute weights and eligibility from immutable PR 1 completion records. A `RestTransitionPlan` owns either normal advancement or a same-set retry, while a local-only versioned `ActiveWorkoutRuntime` row persists the plan, logical attempts, overlays, and rest deadline. Issue #687's execution guard remains the only authority that may create a retry execution after persistence and teardown are ready.

**Tech Stack:** Kotlin Multiplatform domain services, immutable data classes, coroutines/StateFlow, SQLDelight, kotlinx.serialization JSON, issue #687 execution/teardown primitives, PR 1 `SetExecutionCompletion`/`SetEndReason`, kotlin.test/JUnit/Turbine.

## Global Constraints

- Follow the merge train in [2026-08-13-issue-673-drop-set-retry-index.md](2026-08-13-issue-673-drop-set-retry-index.md).
- Branch only after repaired PR #686 is merged. The merge gate requires `WorkoutExecutionGuard.kt` to publish `ExecutionLease`/teardown/persistence status and `WorkoutExitSnapshot.kt` to publish the immutable snapshot described by PR 1; stop and repair the prerequisite rather than creating parallel types if either contract is absent.
- Do not change the stall detector, timers, thresholds, status parsing, or rep cancellation.
- Do not add routine configuration or production UI. Use an internal feature gate/configuration test seam so all shipped routines remain ineligible until PR 3.
- A retry never calls `RoutineFlowManager.getNextStep`; normal advancement occurs exactly once after decline/final completion.
- No retry begins until the failed attempt's persistence claim is durable and #687's connection-wide teardown state is `Ready`.
- `plannedSetId` is optional metadata, never the replay/grouping key.
- Preserve the planned `SetType`; do not assign `SetType.DROP_SET`.
- Attempt numbering and accepted-drop counting are separate: every repeat increments the former; only an accepted valid offer increments the latter.
- Runtime persistence is local-only and is never included in portal sync or backup.

---

## File map

**Create domain/runtime files**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/DropSetModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RoutineSetWeightResolver.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/DropSetCandidateResolver.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/DropSetEligibilityPolicy.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/DropSetFeatureGate.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RestTransitionPlan.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DropSetObservability.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RestDeadlineCalculator.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ActiveWorkoutRuntimeRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightActiveWorkoutRuntimeRepository.kt`

**Modify models/schema/repositories**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/44.sqm`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/CompletedSetRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt`
- `shared/build.gradle.kts`

**Modify orchestration/analytics**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitSnapshot.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt` only for characterization/test seams; do not make it advance retries
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/HistoryManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ProgressionUseCase.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt`
- `ActiveSessionEngine.kt` completion paths that call `gamificationManager.processPostSaveEvents`, calculate/store calories and volume, mark PRs, and invoke health export
- `DefaultWorkoutSessionManager.kt` late-tagging PR update path

**Create/modify tests**

- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/RoutineSetWeightResolverTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/DropSetModelsTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/DropSetCandidateResolverTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/DropSetEligibilityPolicyTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DropSetRestTransitionTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DropSetRuntimeRecoveryTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DropSetHistorySemanticsTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DropSetObservabilityTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/RestDeadlineCalculatorTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMRoutineFlowTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/usecase/ProgressionUseCaseTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt`
- Create `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightActiveWorkoutRuntimeRepositoryTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepositoryTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt`

## Stable interfaces

Add to `DropSetModels.kt`:

```kotlin
@Serializable
data class LogicalSetKey(
    val routineSessionId: String,
    val routineExerciseId: String,
    val setIndex: Int,
    val setKind: SetType,
)

@Serializable
data class PlannedSetAttemptState(
    val logicalSetKey: LogicalSetKey,
    val nextAttemptNumber: Int = 1,
    val acceptedDropCount: Int = 0,
)

@Serializable
data class ExerciseLoadOverlay(
    val routineExerciseId: String,
    val multiplier: Float = 1f,
)

@Serializable
enum class DropPercentage(val fraction: Float) {
    TEN(0.10f),
    TWENTY(0.20f),
    THIRTY(0.30f),
}

@Serializable
data class DropSetCandidate(
    val percentage: DropPercentage,
    val resolvedWeightPerCableKg: Float,
    val resultingExerciseMultiplier: Float,
)

fun interface DropSetFeatureGate {
    fun isEnabled(): Boolean
}

object DisabledDropSetFeatureGate : DropSetFeatureGate {
    override fun isEnabled(): Boolean = false
}

data class DropSetConfiguration(
    val enabled: Boolean,
    val minimumWeightPerCableKg: Float?,
)

enum class DropSetIneligibleReason {
    FEATURE_GATED,
    NOT_STALL_FAILURE,
    DISABLED,
    INVALID_MINIMUM,
    NOT_OLD_SCHOOL,
    NOT_CABLE_WORKING_SET,
    WARMUP,
    ECHO,
    JUST_LIFT,
    BODYWEIGHT,
    DROP_LIMIT_REACHED,
    IDENTITY_MISMATCH,
    NO_VALID_CANDIDATE,
}

data class DropSetOffer(
    val offerId: String,
    val routineIdentity: RoutineExecutionIdentity,
    val candidates: List<DropSetCandidate>,
    val remainingDrops: Int,
)

sealed interface DropSetEligibilityResult {
    data class Eligible(val offer: DropSetOffer) : DropSetEligibilityResult
    data class Ineligible(val reason: DropSetIneligibleReason) : DropSetEligibilityResult
}
```

`LogicalSetKey.setIndex` is the engine's zero-based set index and maps to the current zero-based `CompletedSet.setNumber`; UI converts it to a one-based label only for display.

Add to `CompletedSet`:

```kotlin
val routineExerciseId: String? = null
val attemptNumber: Int = 1
```

Define the transition model without Compose types:

```kotlin
@Serializable
sealed interface RestTransitionPlan {
    val transitionId: String
    val sourceExecutionId: String
    val logicalSetKey: LogicalSetKey

    @Serializable
    data class Coordinates(
        val exerciseIndex: Int,
        val setIndex: Int,
    )

    @Serializable
    data class NormalAdvance(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val sourceCoordinates: Coordinates,
        val plannedSetId: String?,
        val restDurationSeconds: Int,
    ) : RestTransitionPlan

    @Serializable
    data class UnresolvedDropOffer(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val plannedSetId: String?,
        val candidates: List<DropSetCandidate>,
        val normalAdvance: NormalAdvance,
    ) : RestTransitionPlan

    @Serializable
    data class Declined(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val normalAdvance: NormalAdvance,
    ) : RestTransitionPlan

    @Serializable
    data class AcceptedRetry(
        override val transitionId: String,
        override val sourceExecutionId: String,
        override val logicalSetKey: LogicalSetKey,
        val offerId: String,
        val sourceCoordinates: Coordinates,
        val plannedSetId: String?,
        val percentage: DropPercentage,
        val resolvedWeightPerCableKg: Float,
        val resultingExerciseMultiplier: Float,
        val nextAttemptNumber: Int,
    ) : RestTransitionPlan
}
```

Commands never accept loose IDs. They echo the full identity of the plan the user saw, including optional planned-set metadata:

```kotlin
@Serializable
data class RestActionIdentity(
    val transitionId: String,
    val offerId: String?,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
)

sealed interface RestTransitionCommand {
    val identity: RestActionIdentity

    data class Accept(
        override val identity: RestActionIdentity,
        val percentage: DropPercentage,
    ) : RestTransitionCommand

    data class Decline(
        override val identity: RestActionIdentity,
    ) : RestTransitionCommand

    data class SkipRest(
        override val identity: RestActionIdentity,
    ) : RestTransitionCommand
}
```

Annotate the existing `SetType` enum with `@Serializable` without changing its values so `LogicalSetKey` can be stored safely. Unsupported enum/document values make runtime restore fail closed to manual Resume rather than being guessed.

PR 2 enriches PR 1's completion with one optional routine identity whose logical key is required when the identity is present:

```kotlin
data class RoutineExecutionIdentity(
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
    val exerciseIndex: Int,
    val setIndex: Int,
)

data class SetExecutionCompletion(
    val lease: ExecutionLease,
    val reason: SetEndReason,
    val routineIdentity: RoutineExecutionIdentity?,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val plannedSetType: SetType,
    val programMode: ProgramMode,
    val programmedBaseWeightPerCableKg: Float,
    val configuredStartWeightPerCableKg: Float,
    val progressionKg: Float,
    val actualReps: Int,
    val targetReps: Int?,
    val isWarmup: Boolean,
    val isEcho: Boolean,
    val isJustLift: Boolean,
    val isBodyweight: Boolean,
    val isTimed: Boolean,
    val isAmrap: Boolean,
    val isCableExercise: Boolean,
)
```

`routineIdentity` is non-null for an active routine attempt and null for Just Lift/non-routine work. A non-null `plannedSetId` is validated, but never substitutes for `logicalSetKey`. Retain PR 1/#687's immutable persistence and set-summary snapshot alongside these fields; asynchronous work must not reconstruct rep or summary data from the coordinator.

Persist exactly one active runtime per profile and routine session:

```kotlin
@Serializable
data class ActiveWorkoutRuntimeDocument(
    val version: Int = 1,
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val sourceExecutionId: String,
    val sourceStableSessionId: String, // PR 1 persistence-claim reference
    val sourceAttemptNumber: Int,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String? = null,
    val sourceExerciseIndex: Int,
    val sourceSetIndex: Int,
    val exerciseLoadOverlays: List<ExerciseLoadOverlay> = emptyList(),
    val attemptStates: List<PlannedSetAttemptState> = emptyList(),
    val restTransitionPlan: RestTransitionPlan? = null,
    val restDeadlineEpochMs: Long? = null,
    val pausedRestRemainingSeconds: Int? = null,
    val isRestPaused: Boolean = false,
    val originalRestDurationSeconds: Int,
)
```

Repository contract:

```kotlin
interface ActiveWorkoutRuntimeRepository {
    suspend fun load(profileId: String, routineSessionId: String): ActiveWorkoutRuntimeLoadResult
    suspend fun replace(profileId: String, routineSessionId: String, document: ActiveWorkoutRuntimeDocument)
    suspend fun delete(profileId: String, routineSessionId: String)
}

sealed interface ActiveWorkoutRuntimeLoadResult {
    data object Missing : ActiveWorkoutRuntimeLoadResult
    data class Loaded(val document: ActiveWorkoutRuntimeDocument) : ActiveWorkoutRuntimeLoadResult
    data class Rejected(val reason: ActiveWorkoutRuntimeRejection) : ActiveWorkoutRuntimeLoadResult
}

enum class ActiveWorkoutRuntimeRejection {
    CORRUPT_JSON,
    UNSUPPORTED_VERSION,
    IDENTITY_MISMATCH,
}
```

History and progression consume joined attempt records so the routine-session component of `LogicalSetKey` is never guessed from a bare `CompletedSet`:

```kotlin
data class CompletedSetAttemptRecord(
    val completedSet: CompletedSet,
    val routineSessionId: String?,
) {
    fun logicalSetKeyOrNull(): LogicalSetKey? =
        routineSessionId?.let { session ->
            completedSet.routineExerciseId?.let { occurrence ->
                LogicalSetKey(
                    routineSessionId = session,
                    routineExerciseId = occurrence,
                    setIndex = completedSet.setNumber,
                    setKind = completedSet.setType,
                )
            }
        }
}

interface CompletedSetRepository {
    suspend fun getRecentCompletedSetAttemptsForExercise(
        exerciseId: String,
        logicalSetLimit: Int,
        profileId: String,
    ): List<CompletedSetAttemptRecord>

    suspend fun nextAttemptNumber(key: LogicalSetKey): Int
    suspend fun isAttemptDurable(
        stableSessionId: String,
        key: LogicalSetKey,
        attemptNumber: Int,
    ): Boolean
}
```

Drop-set diagnostics use enumerated events and details, never free-form workout data:

```kotlin
enum class DropSetDiagnosticCode {
    OFFER_CREATED,
    OFFER_SUPPRESSED,
    OFFER_ACCEPTED,
    OFFER_DECLINED,
    ACTION_INVALIDATED,
    ACTION_DEDUPLICATED,
    RETRY_BLOCKED,
    RUNTIME_PERSISTED,
    RUNTIME_RESTORED,
    RUNTIME_DISCARDED,
    RUNTIME_REJECTED,
    RETRY_EXECUTION_CREATED,
    RETRY_EXECUTION_STARTED,
}

enum class RetryGateBlocker {
    PERSISTENCE_CLAIM_NOT_DURABLE,
    COMPLETED_SET_NOT_DURABLE,
    TEARDOWN_NOT_READY,
    IDENTITY_MISMATCH,
    USER_PERMISSION_REQUIRED,
}

enum class RuntimeCleanupReason {
    ROUTINE_COMPLETED,
    END_WORKOUT,
    EXPLICIT_RESTART,
    PROFILE_CHANGED,
    INVALID_DOCUMENT,
    IDENTITY_MISMATCH,
}

sealed interface DropSetDiagnosticDetail {
    data class Eligibility(val value: DropSetIneligibleReason) : DropSetDiagnosticDetail
    data class Runtime(val value: ActiveWorkoutRuntimeRejection) : DropSetDiagnosticDetail
    data class RetryGate(val value: RetryGateBlocker) : DropSetDiagnosticDetail
    data class Cleanup(val value: RuntimeCleanupReason) : DropSetDiagnosticDetail
}

data class DropSetDiagnosticEvent(
    val code: DropSetDiagnosticCode,
    val detail: DropSetDiagnosticDetail? = null,
)

fun interface DropSetEventSink {
    fun record(event: DropSetDiagnosticEvent)
}
```

The production sink maps this to `ConnectionLogRepository` with `LogEventType.DROP_SET_TRANSITION`, `message = code.name`, an enum-only detail string, and null device fields. It must not receive transition/offer/profile/routine/exercise/session IDs, names, exact weights, reps, or raw exceptions.

The SQL table stores `profile_id`, `routine_session_id`, `document_version`, `runtime_json`, and `updated_at_epoch_ms`, with a composite primary key on the first two fields. It has no `updatedAt/serverId/deletedAt` sync columns and is excluded from backup.

## Task 1: Characterize and consolidate routine set-weight resolution

**Files:** new `RoutineSetWeightResolver.kt`; new `RoutineSetWeightResolverTest.kt`; `Routine.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; `RoutineFlowManager.kt`; `ResolveRoutineWeightsUseCaseTest.kt`; `DWSMRoutineFlowTest.kt`; `WarmupProgressionTest.kt`.

- [ ] Locate every current ladder that chooses among per-set weight, fixed exercise weight, and percentage-of-PR (`ActiveSessionEngine` currently repeats this near routine recovery, rest preparation, next-set advancement, and next-exercise start).
- [ ] Write table-driven characterization tests for fixed weight, per-set weight, percentage-of-PR with/without a PR, set-specific percentage, AMRAP, supersets, existing 0.5 kg rounding, and one-set manual rest adjustment.
- [ ] Run the new test and observe failure because no shared resolver exists.
- [ ] Implement a pure resolver whose no-overlay result exactly matches the characterized production behavior:

```kotlin
data class RoutineSetWeightRequest(
    val exercise: RoutineExercise,
    val setIndex: Int,
    val currentPrKg: Float?,
    val occurrenceMultiplier: Float = 1f,
    val manualAdjustmentPerCableKg: Float? = null,
)
```

- [ ] Apply precedence: programmed base → occurrence multiplier → existing 0.5 kg rounding → one-set manual adjustment last. The resolver never clamps. Candidate creation and the final start path build the exact `WorkoutParameters` and require `WorkoutCommandValidator.validateProgramParams(params)` to succeed; if validation would change the preview, reject the candidate/transition.
- [ ] Replace the duplicated ladders one call site at a time. After each replacement, run `RoutineSetWeightResolverTest`, `ResolveRoutineWeightsUseCaseTest`, `DWSMRoutineFlowTest`, and `WarmupProgressionTest`.
- [ ] Confirm an absent overlay (`1f`) produces no behavioral diff and per-rep progression remains unchanged.
- [ ] Commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/RoutineSetWeightResolver.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest; git commit -m "refactor: centralize routine set weight resolution"`.

## Task 2: Define logical-set identity, attempt state, and load overlay

**Files:** new `DropSetModels.kt`; new `DropSetModelsTest.kt`; `TrainingCycleModels.kt`; `DWSMTestHarness.kt`.

- [ ] Write model tests proving equality separates two routine-exercise occurrences of the same library exercise, two set indexes, and `STANDARD` from `AMRAP`.
- [ ] Test that optional `plannedSetId` may be absent without changing the logical key.
- [ ] Test attempt transitions: a repeat returns the current number then increments `nextAttemptNumber`; accepting a drop also increments `acceptedDropCount`; manual repeat increments only attempt number; accepted count caps at 2.
- [ ] Test overlay composition: `1.0 × 0.8 × 0.8 == 0.64` within float tolerance and two occurrences remain independent.
- [ ] Run and observe failures, then implement the immutable models and pure transition helpers.
- [ ] Add `@Serializable` to the existing `SetType` enum and the new runtime enums/models without changing names or values; add a codec test proving unknown/corrupt runtime enum data fails recovery closed.
- [ ] Add fixture builders to `DWSMTestHarness` that always supply stable `routineSessionId`, `routineExerciseId`, `setIndex`, and `SetType` for active routine tests.
- [ ] Rerun model tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/DropSetModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil/DWSMTestHarness.kt; git commit -m "feat: model logical set attempts and load overlays"`.

## Task 3: Persist routine occurrence and attempt number on every completed attempt

**Files:** `TrainingCycleModels.kt`; `PhoenixDatabase.sq`; `migrations/44.sqm`; `MigrationStatements.kt`; `SchemaManifest.kt`; `shared/build.gradle.kts`; `CompletedSetRepository.kt`; `SqlDelightCompletedSetRepository.kt`; `BackupModels.kt`; `DataBackupManager.kt`; schema/repository/backup tests.

- [ ] Verify post-PR1 schema is version 44 with migrations through `43.sqm`. If not, renumber this plan and the index before editing.
- [ ] Write a `44 → 45` migration test inserting a historical `CompletedSet` and asserting `routine_exercise_id IS NULL` and `attempt_number == 1` after migration.
- [ ] Add repository tests for present/absent routine occurrence, attempts 1/2/3, bulk save, and defensive handling of invalid stored attempt numbers by coercing reads to at least 1.
- [ ] Add an off-by-one regression test proving `CompletedSet.setNumber == LogicalSetKey.setIndex` for attempts 1–3.
- [ ] Add repository tests for `nextAttemptNumber(LogicalSetKey)`: no rows returns 1; persisted attempts 1/2 return 3; another set, occurrence, kind, or routine session does not affect the result.
- [ ] Add repository tests for `isAttemptDurable(stableSessionId, LogicalSetKey, attemptNumber)`: true only when `CompletedSet.session_id == stableSessionId`, its joined `WorkoutSession.routine_session_id` matches, and occurrence/set-index/set-kind/attempt all match.
- [ ] Run tests and observe failures.
- [ ] Add `routine_exercise_id TEXT` and `attempt_number INTEGER NOT NULL DEFAULT 1` to the full table, `44.sqm`, resilient migration statements, manifest full table/heals, all insert queries, and row mapping. Do not add a foreign key: completed history must survive routine edits/deletion.
- [ ] Add a `CompletedSetRepository.nextAttemptNumber(key)` query that joins `CompletedSet` to `WorkoutSession`, filters all four logical-key fields, and returns `max(attempt_number) + 1`; seed new in-memory/runtime attempt state from this durable value before starting an execution.
- [ ] Add `CompletedSetRepository.isAttemptDurable(stableSessionId, key, attemptNumber)` as the post-process-death authority for the persistence gate. During the live process, also require `WorkoutExecutionGuard.persistenceClaimStatus(stableSessionId) == PERSISTED`.
- [ ] Increment SQLDelight version and schema-test expected version to 45; run manifest/schema/repository tests.
- [ ] Add `routineExerciseId: String? = null` and `attemptNumber: Int = 1` to PR 1's `CompletedSetBackup`; test and implement both buffered and streaming export/import round trips. Do not back up `ActiveWorkoutRuntime`.
- [ ] Commit: `git add shared/build.gradle.kts shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/util shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util; git commit -m "feat: persist logical set attempt identity"`.

## Task 4: Implement deterministic candidate resolution and eligibility

**Files:** new `DropSetCandidateResolver.kt`; new `DropSetEligibilityPolicy.kt`; new `DropSetCandidateResolverTest.kt`; new `DropSetEligibilityPolicyTest.kt`; `WorkoutExitSnapshot.kt`.

- [ ] Write candidate tests for 10/20/30 percent from the actual configured start weight of the failed attempt, not its peak or routine template weight.
- [ ] Cover existing rounding, exact floor allowed, crossing floor invalid, candidate rounding back to the failed weight invalid, invalid hardware range invalid, and manual one-set adjustment normalized into the resulting occurrence multiplier.
- [ ] Cover consecutive accepted 20% drops yielding multiplier 0.64 and maximum two accepted drops per logical set.
- [ ] Write eligibility tests requiring all conditions: `STALL_FAILURE`, Old School, cable working set, enabled test config, not Echo/Just Lift/bodyweight/warm-up, drop count <2, exact routine/session/occurrence/key/coordinates match, optional planned-set match when present, and at least one valid candidate.
- [ ] Add one negative test per condition plus a test proving raw `DELOAD_WARN`, `DELOAD_OCCURRED`, position, and velocity cannot independently produce an offer.
- [ ] Run the tests and observe failures.
- [ ] Implement candidate math as `roundedCandidate = UnitConverter.roundToMachineIncrement(failedConfiguredStart × (1 - reduction))`, followed by the configured-floor and `WorkoutCommandValidator.validateProgramParams` checks plus strict `roundedCandidate < failedConfiguredStart`; derive `resultingExerciseMultiplier = roundedCandidate / programmedBase`. Reject a non-finite or non-positive programmed base.
- [ ] Implement both services as pure functions without repository, coordinator, clock, BLE, or Compose dependencies. Return a typed ineligibility reason for diagnostics, but log only the reason code.
- [ ] Bind `DisabledDropSetFeatureGate` in production and require it before routine configuration/policy evaluation; tests inject an enabled implementation. PR 3 will bind the enabled implementation while retaining this forward kill-switch seam.
- [ ] Rerun tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitSnapshot.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain; git commit -m "feat: resolve deterministic drop set offers"`.

## Task 5: Add the local-only active workout runtime store

**Files:** new `ActiveWorkoutRuntimeRepository.kt`; new `SqlDelightActiveWorkoutRuntimeRepository.kt`; new `RestDeadlineCalculator.kt`; `PhoenixDatabase.sq`; `44.sqm` from Task 3; `SchemaManifest.kt`; `MigrationStatements.kt`; `DataModule.kt`; new `SqlDelightActiveWorkoutRuntimeRepositoryTest.kt`; new `RestDeadlineCalculatorTest.kt`; `SchemaParityTest.kt`; `SchemaManifestTest.kt`.

- [ ] Add repository tests for replace/load/delete, profile isolation, routine-session isolation, unknown document version, corrupt JSON, and last-write replacement.
- [ ] In `RestDeadlineCalculatorTest.kt`, add clock tests for active deadline, paused remaining seconds, backward wall-clock movement, forward jump, and expired restore. Expected remaining time is clamped to `0..originalRestDurationSeconds`.
- [ ] Run tests and observe failures.
- [ ] In the same 44→45 migration, create `ActiveWorkoutRuntime` with composite primary key `(profile_id, routine_session_id)`, version, JSON, and epoch update time. Add generated queries for select/upsert/delete-by-key/delete-by-profile.
- [ ] Implement strict JSON decoding: no row returns `Missing`, version 1 returns `Loaded`, and unknown/corrupt documents return the appropriate typed `Rejected` value. Identity mismatch is produced by the validated restore layer, which deletes the unusable row and routes to manual Resume/Set Ready, never auto-start.
- [ ] Persist an epoch deadline or paused remaining count only. Never serialize `elapsedRealtime` or a monotonic deadline.
- [ ] Register the repository in `DataModule.kt`; explicitly verify it is absent from portal sync and `DataBackupManager` exports.
- [ ] Rerun repository/schema/clock tests and commit: `git add shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data shared/src/commonMain/kotlin/com/devil/phoenixproject/di shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RestDeadlineCalculator.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/RestDeadlineCalculatorTest.kt shared/src/androidHostTest; git commit -m "feat: persist active workout retry runtime"`.

## Task 6: Own rest advancement with an immutable transition plan

**Files:** new `RestTransitionPlan.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; `WorkoutCoordinator.kt`; new `DropSetRestTransitionTest.kt`; `DWSMRoutineFlowTest.kt`; `DWSMWorkoutLifecycleTest.kt`; `DWSMTestHarness.kt`.

- [ ] Write tests that normal completion captures one `NormalAdvance`; eligible stalled completion instead captures an unresolved offer plus the original normal transition without mutating indices.
- [ ] Test `Accept`, `Decline`, and `SkipRest` carrying one `RestActionIdentity`: correct current transition/offer/logical-key/planned-set identity transitions once; duplicate, stale execution, wrong logical key, wrong planned-set ID, wrong transition ID, or wrong offer ID is a typed diagnostic no-op.
- [ ] Test decline retains the normal transition and does not skip rest. Test accept replaces it with same-set retry and sends no BLE command immediately.
- [ ] Test unresolved offer blocks autoplay and `skipRest`; when the timer reaches zero it remains zero. Accept at zero can proceed once other gates are satisfied.
- [ ] Run the new tests and observe failures.
- [ ] Add the immutable transition model and a small reducer consuming the exact `RestTransitionCommand.Accept`, `.Decline`, and `.SkipRest` contracts above. Keep the reducer pure; manager code persists each accepted transition before publishing it. `SkipRest` is rejected while unresolved and, once resolved, dispatches only the transition identified by the echoed `RestActionIdentity`.
- [ ] Change rest orchestration to consult the plan before `getNextStep`. Only a resolved normal transition may call `getNextStep`; accepted retry preserves captured `(exerciseIndex, setIndex)` and logical key. Preserve the same `transitionId` as an offer evolves from unresolved to accepted/declined.
- [ ] Publish internal StateFlow/commands for tests and future PR 3 wiring, but leave no production UI path that can accept an offer.
- [ ] Rerun transition/routine-flow tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager; git commit -m "feat: model deterministic rest transitions"`.

## Task 7: Start same-set retries behind persistence and teardown gates

**Files:** `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; `WorkoutExecutionGuard.kt`; `WorkoutExitSnapshot.kt`; `DWSMWorkoutLifecycleTest.kt`; `DWSMRoutineFlowTest.kt`; `FakeBleRepository.kt`.

- [ ] Write a live-process integration test: A1 stalls, rest appears immediately, accept is recorded, but retry does not start while PR 1's status for `sourceStableSessionId` is not `PERSISTED`, `isAttemptDurable(sourceStableSessionId, key, attempt)` is false, or teardown is not `Ready`.
- [ ] Write a restored-process integration test: the in-memory PR 1 claim map is intentionally absent after process death, so a matching durable completed-attempt row is the persistence authority; a missing/mismatched row fails closed and cannot start.
- [ ] Release persistence only and prove no start; then make teardown `Ready` and prove exactly one new execution lease/config command starts A1 attempt 2.
- [ ] Assert command order is prior execution STOP/RESET completion, then new activation; assert no configuration/force packet occurs during Active/TearingDown.
- [ ] Test A1 retry then normal advancement to B1 in a superset; assert `getNextStep` is called exactly once after final A1 attempt, never to enter the retry.
- [ ] Test second accepted drop creates attempt 3 and multiplier 0.64; a third stall has no offer. Test manual back-navigation creates attempt 4 without increasing accepted drops.
- [ ] Test a later logical set of the same occurrence receives its own two-drop budget while inheriting the occurrence multiplier.
- [ ] Test mismatched live routine identity/planned-set metadata fails closed to manual Set Ready instead of starting.
- [ ] Run tests and observe failures.
- [ ] Implement `RetryPersistenceGate.Live(sourceStableSessionId)` as PR 1 status `PERSISTED` **and** a true durable-attempt repository check. Implement `RetryPersistenceGate.Restored(sourceStableSessionId)` as the same durable repository check without consulting the now-empty in-memory claim map. The full retry gate additionally requires the current accepted transition, matching live identity, current rest permission, and `MachineTeardownState.Ready`, then invokes #687's single guarded start entry point to create a new execution/stable session ID.
- [ ] Apply the occurrence overlay through `RoutineSetWeightResolver`; retain per-rep progression from the reduced base and keep the saved routine untouched. Re-resolve and validate at the command boundary; corrupt/raced values may be clamped only by existing machine rules, and any clamp that would change the displayed candidate rejects the stale transition instead of silently commanding a different preview.
- [ ] Rerun lifecycle/routine-flow tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil; git commit -m "feat: retry failed logical set safely"`.

## Task 8: Restore pending retry state through the existing Resume flow

**Files:** `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; `RoutineFlowManager.kt`; `ActiveWorkoutRuntimeRepository.kt`; `SqlDelightActiveWorkoutRuntimeRepository.kt`; `RestDeadlineCalculator.kt`; new `DropSetRuntimeRecoveryTest.kt`.

- [ ] Test process death for unresolved offer, declined offer, accepted retry waiting on persistence, accepted retry waiting on teardown, two-drop state, and exercise overlay inherited by a later set.
- [ ] On restore, verify profile, routine session, routine-exercise occurrence, logical key, coordinates, optional planned set, and source stable session; then require `isAttemptDurable(sourceStableSessionId, logicalSetKey, sourceAttemptNumber)`. Any mismatch/rejected load deletes runtime and returns to existing manual Resume/Set Ready recovery.
- [ ] Test rest deadline restoration with remaining time, backward clock, forward clock, and expired deadline. Expired unresolved offer waits at zero; no state auto-starts.
- [ ] Test runtime deletion on routine completion, End Workout, explicit restart, discard/restart recovery, and profile-specific routine restart. Ordinary process/background suspension retains it.
- [ ] Test cancellation during restore/persist/start and require `CancellationException` to be rethrown without manufacturing a completion, decline, or accepted transition.
- [ ] Run recovery tests and observe failures.
- [ ] Load runtime only from the existing Resume entry point. Reconstruct StateFlows and transition state, clamp remaining time, and wait for user/teardown/persistence as appropriate; never call `startWorkout` during hydration.
- [ ] Centralize terminal cleanup in one `clearActiveWorkoutRuntime(reasonCode)` helper whose logs include only the reason code and execution transition.
- [ ] Rerun recovery and #687 lifecycle tests; commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager; git commit -m "feat: recover drop set retry runtime"`.

## Task 9: Group attempts for history/progression and suppress zero-rep side effects

**Files:** `CompletedSetRepository.kt`; `SqlDelightCompletedSetRepository.kt`; `HistoryManager.kt`; `HistoryTab.kt`; `ProgressionUseCase.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; new `DropSetHistorySemanticsTest.kt`; `ProgressionUseCaseTest.kt`; `HistoryManagerTest.kt`; `Issue591HistoryGroupingTest.kt`; `GamificationManagerTest.kt`; `HealthWorkoutExportBuilderTest.kt`.

- [ ] Write tests that all attempts remain queryable and visibly ordered by `attemptNumber`, while programmed-set counts group by `LogicalSetKey` and legacy rows without `routineExerciseId` retain existing behavior.
- [ ] Test progression evaluates only the final attempt for a logical set: failed attempt then successful retry counts as final success; successful earlier attempt then failed manual repeat evaluates the final repeat.
- [ ] Test a positive-rep stalled attempt retains existing volume/PR/export semantics for work performed.
- [ ] Test a zero-rep `STALL_FAILURE` persists locally but contributes no total volume, progression success, achievement/badge input, calorie estimate, personal record, or external health record.
- [ ] Run tests and observe failures.
- [ ] Add the exact `CompletedSetAttemptRecord` and repository API above. Its SQL joins `CompletedSet.session_id` to `WorkoutSession.id`, selects `WorkoutSession.routine_session_id`, preserves profile/soft-delete filters, first selects the latest `logicalSetLimit` logical groups, then returns **all** attempts for those groups in deterministic order. Retries must not crowd older logical sets out of progression's existing window. `logicalSetKeyOrNull()` is the only new-code constructor for history/progression grouping; a null key remains a unique legacy group under existing rules.
- [ ] Switch `ProgressionUseCase` to `getRecentCompletedSetAttemptsForExercise`, group non-null keys, and evaluate the greatest `attemptNumber` (then `completedAt`/row ID as deterministic tie breakers) for each logical set. Use the same record/helper for programmed-set counts and update `HistoryTab` to label attempt 2+ without hiding failed attempts.
- [ ] Remove the positive-rep-only `CompletedSet` creation gate for `STALL_FAILURE`. Update workout-history SQL to include a zero-rep session only when an `EXISTS` join finds its `CompletedSet.set_end_reason == 'STALL_FAILURE'`; retain existing filters for unrelated empty sessions.
- [ ] Add a single `hasPerformedWork = actualReps > 0` gate before downstream side effects while always retaining local session/completed-set persistence.
- [ ] Rerun history/progression/gamification/health tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject shared/src/commonTest/kotlin/com/devil/phoenixproject; git commit -m "feat: account for repeated logical set attempts"`.

## Task 10: Add structured, privacy-safe transition observability

**Files:** new `DropSetObservability.kt`; `ConnectionLogRepository.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; new `DropSetObservabilityTest.kt`.

- [ ] Write tests for every diagnostic code and typed detail in the stable contract. Exercise offer creation/suppression, accept/decline, stale invalidation, duplicate action, both retry blockers, runtime persist/restore/discard/reject, and retry execution create/start.
- [ ] Use fixture values that look like real profile/routine/exercise/session/transition/offer IDs, names, exact weights, and reps; assert none appears in `ConnectionLogRepository.logs`, `exportAsText()`, or `exportAsCsv()`.
- [ ] Run the new tests and observe failures.
- [ ] Add `LogEventType.DROP_SET_TRANSITION`, implement the enum-only `DropSetEventSink`, and inject it into PR 2 orchestration. Emit at the state transition that owns each event; do not infer transitions by scraping log text.
- [ ] Keep expected gate waits at debug/info and rejected/corrupt states at warning. Never pass device fields, raw exceptions, IDs, names, exact weights, or rep counts to this sink.
- [ ] Rerun observability, transition, recovery, and retry-gate tests; commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ConnectionLogRepository.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DropSetObservability.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DropSetObservabilityTest.kt; git commit -m "feat: log drop set transitions safely"`.

## Task 11: Verify PR 2 without enabling the product surface

**Files:** every production/test path under this plan's File map; PR description.

- [ ] Run focused domain and manager tests:

```powershell
$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\dasbl\AppData\Local\Android\Sdk'
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*RoutineSetWeightResolverTest*' --tests '*DropSetCandidateResolverTest*' --tests '*DropSetEligibilityPolicyTest*' --tests '*DropSetRestTransitionTest*' --tests '*DropSetRuntimeRecoveryTest*' --tests '*DropSetHistorySemanticsTest*' --tests '*DropSetObservabilityTest*' --console=plain
```

- [ ] Run schema/repository tests:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SchemaParityTest*' --tests '*SchemaManifestTest*' --tests '*SqlDelightCompletedSetRepositoryTest*' --tests '*SqlDelightActiveWorkoutRuntimeRepositoryTest*' --console=plain
```

- [ ] Run full Windows gates:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --continue --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainPhoenixDatabaseInterface :shared:verifyCommonMainPhoenixDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
git diff --check
```

- [ ] Run source guards and require no detector edits and no runtime-table sync/backup mapping:

```powershell
git diff --unified=40 origin/main -- shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
rg -n "ActiveWorkoutRuntime" shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
```

Review the first command's entire detector-adjacent diff. The only permitted change at an existing stall terminal is attaching its immutable lease/reason/completion; reject changes to status interpretation, arming, cancellation, timers, thresholds, position/velocity rules, or rep handling. Expected second command: no matches.

- [ ] Confirm the production configuration provider keeps eligibility disabled and no Compose UI exposes accept/decline.
- [ ] Document the pure API, durable runtime JSON version, migration, cleanup paths, and PR 3 integration seam in the PR description.
- [ ] Merge only after CI and review pass; then update `main` before beginning PR 3.

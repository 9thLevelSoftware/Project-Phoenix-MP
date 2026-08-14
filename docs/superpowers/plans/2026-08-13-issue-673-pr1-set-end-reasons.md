# Issue #673 PR 1 Set-End Reasons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair PR #686 after issue #687 so every completed set stores the correct explicit end reason through an immutable execution-bound snapshot, with safe historical decoding and no unrelated protocol restrictions.

**Architecture:** Rebase the existing PR branch onto post-#687 `main`, retain its additive SQLDelight/repository/backup work, and replace coordinator-global reason flags with `SetExecutionCompletion(ExecutionLease, SetEndReason)`. Completion origins classify at the terminal boundary; persistence consumes the captured completion and never reads mutable coordinator state.

**Tech Stack:** Kotlin Multiplatform, coroutines, issue #687 `ExecutionLease`/`WorkoutExecutionGuard`/`WorkoutExitSnapshot`, SQLDelight migrations and schema reconciliation, kotlinx.serialization backup, kotlin.test/JUnit Android host tests.

## Global Constraints

- Follow the merge train and global constraints in [2026-08-13-issue-673-drop-set-retry-index.md](2026-08-13-issue-673-drop-set-retry-index.md).
- Do not begin until issue #687 is merged and its execution-isolation tests pass.
- Repair PR #686 in place; preserve its GitHub review history.
- `handleSetCompletion` requires both a current `ExecutionLease` and a `SetEndReason`; it has no default and no no-argument overload.
- `UNKNOWN` is the only schema/model/backup fallback. New terminal call sites never choose `UNKNOWN` as a convenience.
- `WorkoutCoordinator` owns no `lastSetEndReason`, `autoStopReason`, or equivalent sticky cause.
- Do not tune stall detection. Classify only at its existing terminal decision.
- Revert every PR #686 change to BLE offsets, packet creation, progression bounds, and the global 100 kg cap.

---

## File map

**Retain and repair from PR #686**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExitSnapshot.kt` (create in PR 1 only if #687 merged the snapshot as a private/nested declaration; extract it here without behavior change before enriching it)
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/43.sqm`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/build.gradle.kts`

**Revert completely to post-#687 `main`**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidator.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidatorTest.kt`

**Tests**

- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/SetEndReasonTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue673SetEndReasonLifecycleTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepositoryTest.kt`
- Modify `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineNameTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutExecutionGuardTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt`
- Modify `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngineIntegrationTest.kt`

## Stable interfaces

Add to `TrainingCycleModels.kt`:

```kotlin
enum class SetEndReason {
    UNKNOWN,
    TARGET_REPS_REACHED,
    STALL_FAILURE,
    VBT_AUTO_END,
    USER_STOPPED,
    CABLE_RELEASED,
    TIMER_EXPIRED;

    companion object {
        fun fromPersisted(value: String?): SetEndReason =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
```

The final `CompletedSet` field is:

```kotlin
val setEndReason: SetEndReason = SetEndReason.UNKNOWN
```

In the post-#687 immutable snapshot module, use:

```kotlin
internal data class SetExecutionCompletion(
    val lease: ExecutionLease,
    val reason: SetEndReason,
)
```

Add `val completion: SetExecutionCompletion` to #687's concrete immutable `WorkoutExitSnapshot`. If #687 merged that type inside `ActiveSessionEngine.kt`, extract it first to the exact `WorkoutExitSnapshot.kt` path above without changing its fields or behavior. Preserve every #687 field and the invariant that lease and reason are captured together before asynchronous work begins.

Use mandatory signatures:

```kotlin
internal fun handleSetCompletion(
    lease: ExecutionLease,
    reason: SetEndReason,
)

private fun captureExitSnapshot(
    completion: SetExecutionCompletion,
    terminalPath: TerminalPath,
): WorkoutExitSnapshot
```

For delayed bodyweight confirmation, `ActiveSessionEngine` owns:

```kotlin
private var pendingBodyweightCompletion: SetExecutionCompletion? = null
```

Expose #687's existing session-scoped persistence claim as a read-only status in `WorkoutExecutionGuard.kt`:

```kotlin
internal enum class PersistenceClaimStatus {
    UNCLAIMED,
    IN_PROGRESS,
    PERSISTED,
    FAILED,
}

internal fun persistenceClaimStatus(stableSessionId: String): PersistenceClaimStatus
```

The stable session ID is the persistence-claim reference; do not create an unrelated claim ID. `markPersistenceFailed` records `FAILED`, a deliberate persistence retry may atomically reclaim `FAILED`, and only `PERSISTED` permits a later retry execution.

## Task 1: Rebase PR #686 after issue #687 and remove unrelated scope

**Files:** the four protocol files listed above; all files changed by PR #686; post-#687 execution-isolation files.

- [ ] Fetch `origin`, verify issue #687's merge commit is on `origin/main`, and record the post-rebase SQLDelight version and highest migration.
- [ ] Verify PR #686's pre-repair head is `d15f05fd4359f19f77a39eed225a7270a79e93b8`, then check out its existing remote branch `origin/enhancement/673-pr1-set-end-reasons-protocol-cleanup` in the enhancement worktree.
- [ ] Rebase that PR branch non-interactively onto `origin/main`; resolve conflicts in favor of #687's execution lease, immutable snapshot, teardown barrier, and guarded start entry point.
- [ ] Restore the four protocol files from `origin/main` so the PR contains no 100 kg cap, ±10 kg progression change, or offset rename.
- [ ] Record every rebased occurrence of `lastSetEndReason`, `autoStopReason`, and no-argument `handleSetCompletion()` for the lifecycle replacement in Task 5; do not add new uses while resolving conflicts.
- [ ] Run:

```powershell
$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\dasbl\AppData\Local\Android\Sdk'
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinMetadata --console=plain
```

Expected: compilation passes after the rebase and scope reset; there are no errors or issue-#673 diffs in BLE protocol files.

- [ ] Commit: `git commit -am "chore: rebase issue 673 reason foundation"`.

## Task 2: Define durable end reasons and defensive decoding

**Files:** `TrainingCycleModels.kt`; new `SetEndReasonTest.kt`.

- [ ] Write tests that every declared enum name round-trips through `fromPersisted`, and that `null`, blank, mixed-case, corrupt, and future values return `UNKNOWN`.
- [ ] Write a test that a minimally constructed `CompletedSet` defaults to `UNKNOWN`.
- [ ] Run the focused common test and observe failures because the enum/codec/default are absent or wrong:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SetEndReasonTest*' --console=plain
```

- [ ] Add the exact seven-value enum and defensive `fromPersisted` implementation shown above; add `setEndReason = UNKNOWN` to `CompletedSet` and its factory.
- [ ] Rerun the focused test and confirm it passes.
- [ ] Audit `CompletedSet(` construction with `rg -n "CompletedSet\(" shared/src` and update test fixtures only where the explicit reason is part of the behavior under test; leave historical/general fixtures on the safe default.
- [ ] Commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/domain/model/SetEndReasonTest.kt; git commit -m "feat: define durable set end reasons"`.

## Task 3: Migrate and round-trip end reasons through every persistence path

**Files:** `VitruvianDatabase.sq`; `migrations/43.sqm`; `MigrationStatements.kt`; `SchemaManifest.kt`; `shared/build.gradle.kts`; `SqlDelightCompletedSetRepository.kt`; `BackupModels.kt`; `DataBackupManager.kt`; `SchemaParityTest.kt`; `SchemaManifestTest.kt`; `SqlDelightCompletedSetRepositoryTest.kt`; `DataBackupManagerRoutineNameTest.kt`; `BackupSerializationTest.kt`.

- [ ] Recalculate `N` from post-#687 `main`. If it is not 43, renumber this task and the index/PR 2/PR 3 migration allocation before editing.
- [ ] First add a migration test that builds schema 43, inserts a valid historical `CompletedSet`, migrates `43 → 44`, and asserts `set_end_reason == "UNKNOWN"`.
- [ ] Add parity tests proving the column exists on fresh and migrated databases, and that a database where the heal already added the column preserves a non-default value during resilient migration.
- [ ] Add manifest tests for the `CompletedSet.set_end_reason` heal operation.
- [ ] Add repository tests for every reason, raw future-string fallback, single save, and bulk save.
- [ ] Add backup tests for every reason, an omitted legacy field, a future string, and both buffered and streaming import/export paths.
- [ ] Run and observe the expected failures:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SchemaParityTest*' --tests '*SchemaManifestTest*' --tests '*SqlDelightCompletedSetRepositoryTest*' --tests '*DataBackupManagerRoutineNameTest*' --tests '*BackupSerializationTest*' --console=plain
```

- [ ] Change the full schema and `43.sqm` to:

```sql
set_end_reason TEXT NOT NULL DEFAULT 'UNKNOWN'
```

- [ ] Add the same default to `MigrationStatements.kt` and both the full-table and heal forms in `SchemaManifest.kt`; increment SQLDelight's version to 44 and update `EXPECTED_SCHEMA_VERSION` to 44.
- [ ] Update `insertCompletedSet` and `insertCompletedSetIgnore` to accept `set_end_reason`; do not alter unrelated columns or indexes.
- [ ] In the same implementation step, update every generated-query consumer so the tree compiles: repository row mappers use `SetEndReason.fromPersisted`, all repository inserts write `.name`, and both `DataBackupManager.insertCompletedSetIgnore` calls supply a canonical reason.
- [ ] Add `val setEndReason: String = "UNKNOWN"` to `CompletedSetBackup`, leave backup version 5 unchanged, export `.name`, and canonicalize both import paths with `SetEndReason.fromPersisted(value).name`.
- [ ] Run `rg -n "insertCompletedSet(Ignore)?\(" shared/src` and verify every generated-query invocation supplies the reason.
- [ ] Rerun every focused test from this task plus interface generation, migration verification, and schema-manifest validation; confirm the entire source set compiles and all tests pass.
- [ ] Commit all schema, mapper, repository, and backup call-site changes together so no commit has a stale generated-query signature: `git add shared/build.gradle.kts shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/data shared/src/commonMain/kotlin/com/devil/phoenixproject/util shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util shared/src/commonTest/kotlin/com/devil/phoenixproject/util; git commit -m "feat: persist completed set end reasons"`.

## Task 4: Preserve captured reasons through late Just Lift tagging

**Files:** `ActiveSessionEngine.kt`; `SqlDelightCompletedSetRepository.kt`; `SqlDelightCompletedSetRepositoryTest.kt`; `Issue673SetEndReasonLifecycleTest.kt`; `DWSMTestHarness.kt`.

- [ ] Test both Just Lift paths: updating an existing untagged set preserves its captured reason, while creating a row for a genuinely historical tagged session uses `UNKNOWN`.
- [ ] Add a lifecycle test proving positive-rep untagged Just Lift completion creates the `CompletedSet` before later exercise tagging, even when `selectedExerciseId` is null.
- [ ] Run the repository/lifecycle tests and observe the late-tagging failures.
- [ ] Keep `updateCompletedSetForTaggedJustLift` from writing `set_end_reason`, so it cannot erase the captured cause.
- [ ] Ensure snapshot capture creates a `CompletedSet` whenever an untagged Just Lift execution has positive working reps; remove any `selectedExerciseId != null` prerequisite. Late tagging should update that row rather than manufacture a new reason.
- [ ] Rerun the focused repository/lifecycle tests and confirm they pass.
- [ ] Commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepository.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightCompletedSetRepositoryTest.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/testutil; git commit -m "fix: preserve reasons through just lift tagging"`.

## Task 5: Bind completion reasons to issue #687 execution leases

**Files:** `WorkoutExecutionGuard.kt`; `WorkoutExitSnapshot.kt`; `ActiveSessionEngine.kt`; `WorkoutCoordinator.kt`; new `Issue673SetEndReasonLifecycleTest.kt`; `WorkoutExecutionGuardTest.kt`; `DWSMWorkoutLifecycleTest.kt`; `ActiveSessionEngineIntegrationTest.kt`; `DWSMTestHarness.kt`.

- [ ] Add lifecycle tests for this mandatory origin table:

| Existing terminal origin | Required reason |
|---|---|
| machine `WORKOUT_COMPLETE` | `TARGET_REPS_REACHED` |
| `repCounter.shouldStopWorkout()` | `TARGET_REPS_REACHED` |
| velocity-stall countdown terminal | `STALL_FAILURE` |
| deload-armed velocity countdown terminal | `CABLE_RELEASED` |
| handles-at-rest position terminal | `CABLE_RELEASED` |
| danger-zone cable-release position terminal | `CABLE_RELEASED` |
| VBT velocity-loss auto-end | `VBT_AUTO_END` |
| End Workout and stop-and-return | `USER_STOPPED` |
| both timed completion paths | `TIMER_EXPIRED` |
| bodyweight rep confirmation | the stored originating reason |

- [ ] Add race tests: suspend execution A's persistence, invalidate A, start B when #687's teardown gate is `Ready`, release A, and prove A's reason cannot appear on B. Cover both auto-complete-wins and End-Workout-wins persistence claims.
- [ ] Extend `WorkoutExecutionGuardTest` for persistence status transitions `UNCLAIMED → IN_PROGRESS → PERSISTED`, failure to `FAILED`, atomic reclaim from `FAILED`, and session isolation.
- [ ] Run the lifecycle tests and observe failures.
- [ ] Remove `WorkoutCoordinator.lastSetEndReason`, `WorkoutCoordinator.autoStopReason`, every read/reset/assignment, and the default/no-argument `handleSetCompletion` path.
- [ ] Add `SetExecutionCompletion`; have `handleSetCompletion(lease, reason)` immediately construct it after verifying the lease is current, then pass it through summary, persistence, rest, and exit snapshots.
- [ ] Expose the read-only persistence status contract above from #687's existing claim map; use `lease.sessionId` as the stable claim reference and retain first-valid-claim-wins semantics.
- [ ] Make the existing auto-stop call sites pass their terminal classification directly: the velocity branch passes `CABLE_RELEASED` only when its existing countdown was deload-armed and otherwise passes `STALL_FAILURE`; both existing position/handles-at-rest cable-release branches pass `CABLE_RELEASED`. Thread that reason through `requestAutoStop(lease, reason)` and `triggerAutoStop(lease, reason)` without adding mutable reason state or changing any detector condition, threshold, or timer.
- [ ] Store bodyweight's immutable pending completion in `ActiveSessionEngine`, require its lease still be current at confirmation, clear it exactly once, and clear it on execution invalidation/reset.
- [ ] Write `completion.reason` into the captured `CompletedSet`. No asynchronous persistence lambda may read a coordinator reason.
- [ ] Rerun lifecycle and #687 persistence tests; confirm all pass.
- [ ] Run `rg -n "lastSetEndReason|autoStopReason|handleSetCompletion\(\)" shared/src` and require no matches.
- [ ] Commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager; git commit -m "feat: bind end reasons to workout executions"`.

## Task 6: Scope audit, regression verification, and PR #686 update

**Files:** all PR files; PR #686 description and review threads.

- [ ] Prove the unrelated protocol files match `origin/main` exactly:

```powershell
git diff --exit-code origin/main -- shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidator.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidatorTest.kt
```

- [ ] Run focused reason/lifecycle, schema/repository/backup, and #687 persistence tests:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SetEndReasonTest*' --tests '*Issue673SetEndReasonLifecycleTest*' --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SchemaParityTest*' --tests '*SchemaManifestTest*' --tests '*SqlDelightCompletedSetRepositoryTest*' --tests '*DataBackupManagerRoutineNameTest*' --console=plain
```

- [ ] Run the full Windows gates:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --continue --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
git diff --check
git status --short
```

- [ ] Let CI perform iOS compilation/tests on macOS; do not claim iOS runtime validation from Windows.
- [ ] Update PR #686's body to describe the #687 dependency, immutable completion shape, `UNKNOWN` fallback, corrected 43→44 migration, late Just Lift handling, and removal of protocol changes.
- [ ] Respond to and resolve the schema off-by-one, late tagging, and >100 kg review threads with links to the exact tests/diff.
- [ ] Confirm all required checks are green and an approving review exists before merging.
- [ ] Commit any review-only test adjustments as `test: close issue 673 reason review gaps`; push the repaired branch and merge PR #686 before starting PR 2.

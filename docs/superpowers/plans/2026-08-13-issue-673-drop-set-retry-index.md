# Issue #673 Drop-Set Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved user-prompted drop-set retry after an existing Old School stall failure without changing stall detection or sending a weight change during an active set.

**Architecture:** Build on issue #687's execution lease and teardown barrier, then land three sequential pull requests: explicit completion reasons, a deterministic retry/recovery engine, and opt-in product configuration/UI. Each PR starts from the previous PR after it merges; PR #682 contributes no runtime code.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, coroutines/StateFlow, SQLDelight, kotlinx.serialization, Koin, JUnit/kotlin.test, Turbine, Android host tests, Vitruvian BLE command queue.

## Global Constraints

- Work only in `D:\Project-Phoenix-MP\.worktrees\enhancement`; do not alter the main worktree.
- Issue #687 must be merged before any implementation task in this plan starts.
- Preserve the existing stall detector and its thresholds. Consume only its terminal `STALL_FAILURE` classification.
- Never send CONFIG, activation, force-update, or weight-adjustment packets while the failed execution is active or tearing down.
- Never cherry-pick PR #682. It is historical evidence, not an implementation base.
- Repair the existing PR #686 branch for PR 1 so its review history remains intact.
- PR 2 and PR 3 branch only from freshly updated `main` after their predecessor merges. Do not stack all three PRs on one branch.
- `SetType.DROP_SET` is not the identity for this feature. Preserve the programmed `SetType`; distinguish attempts with `LogicalSetKey` plus `attemptNumber`.
- Keep logging free of profile IDs, routine IDs, exercise IDs/names, exact weight, exact reps, and other workout metrics.
- Default the feature off. A corrective release may disable eligibility, but migrations are forward-only.
- Use test-driven development: add one failing behavior test, run it and observe the expected failure, implement the smallest change, rerun the focused test, then commit.

---

## Source documents

- Approved design: [2026-08-13-issue-673-drop-set-retry-design.md](../specs/2026-08-13-issue-673-drop-set-retry-design.md)
- Prerequisite design: [2026-08-13-issue-687-workout-execution-isolation-design.md](../specs/2026-08-13-issue-687-workout-execution-isolation-design.md)
- PR 1 plan: [2026-08-13-issue-673-pr1-set-end-reasons.md](2026-08-13-issue-673-pr1-set-end-reasons.md)
- PR 2 plan: [2026-08-13-issue-673-pr2-drop-set-engine.md](2026-08-13-issue-673-pr2-drop-set-engine.md)
- PR 3 plan: [2026-08-13-issue-673-pr3-product-integration.md](2026-08-13-issue-673-pr3-product-integration.md)

The root `AGENTS.md` references `openspec/AGENTS.md`, but the `openspec` directory is absent in this worktree. If it returns before implementation, mirror the approved design into the required OpenSpec proposal before editing production code; do not reinterpret the approved decisions.

## Merge train

| Slice | Branch base | Existing branch/PR | Schema allocation on the current baseline | Exit gate |
|---|---|---|---|---|
| Prerequisite #687 | Current `main` | Separate issue/PR | No issue-#673 allocation | Execution lease, immutable persistence snapshot, and fail-closed teardown barrier are merged |
| PR 1: completion reasons | `main` after #687 | Repair PR #686 | `43.sqm`, schema 43 → 44 | Every completion origin is explicit; history fallback is `UNKNOWN`; no unrelated protocol changes |
| PR 2: engine/recovery | `main` after PR 1 | New branch | `44.sqm`, schema 44 → 45 | Pure policy, same-set transition, attempts/overlay, durable runtime, history semantics; no enabled UI |
| PR 3: product integration | `main` after PR 2 | New branch | `45.sqm`, schema 45 → 46 | Routine config, sync/backup, inline rest UI, recovery UX, hardware validation |

The migration numbers above are exact for the repository's current schema version 43. At the beginning of each PR, verify that the expected predecessor migration is still the highest migration. If another merged change has consumed one of these numbers, stop before editing and renumber this entire three-entry allocation consistently; never create two migration files for the same source version.

## Cross-PR contracts

### PR 1 publishes

```kotlin
enum class SetEndReason {
    UNKNOWN,
    TARGET_REPS_REACHED,
    STALL_FAILURE,
    VBT_AUTO_END,
    USER_STOPPED,
    CABLE_RELEASED,
    TIMER_EXPIRED,
}
```

Every terminal origin creates or enriches the immutable #687 execution completion/persistence snapshot with exactly one non-default reason. `UNKNOWN` is used only when decoding historical, corrupt, or future values.

### PR 2 publishes

```kotlin
data class LogicalSetKey(
    val routineSessionId: String,
    val routineExerciseId: String,
    val setIndex: Int,
    val setKind: SetType,
)

data class PlannedSetAttemptState(
    val logicalSetKey: LogicalSetKey,
    val nextAttemptNumber: Int,
    val acceptedDropCount: Int,
)

data class ExerciseLoadOverlay(
    val routineExerciseId: String,
    val multiplier: Float,
)

data class RestActionIdentity(
    val transitionId: String,
    val offerId: String?,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
)
```

PR 2 also publishes a versioned `ActiveWorkoutRuntime` codec/repository, an immutable `RestTransitionPlan`, identity-bearing accept/decline/Skip Rest commands, a durable completed-attempt check keyed by the failed execution's stable session ID, and enum-only diagnostic events. The plan may represent an offer internally, but production eligibility remains disabled until PR 3 provides routine configuration and UI.

### PR 3 publishes

Add these fields to `RoutineExercise` without changing its existing fields:

```kotlin
val dropSetEnabled: Boolean = false
val dropSetMinWeightKg: Float? = null
```

The rest surface accepts an immutable view model containing a unique offer identity and zero or more exact candidate previews. Every accept, decline, and Skip Rest callback echoes the full `RestActionIdentity`; stale actions are no-ops. Accepted/waiting presentation retains the selected percentage and exact engine-resolved weight.

## Required behavior matrix

| Scenario | Offer | Attempt/history | Navigation/load behavior |
|---|---|---|---|
| Old School working set, `STALL_FAILURE`, enabled, valid candidate | Yes | Failed attempt saved; retry gets next attempt number | Same `(exerciseIndex, setIndex)`; no `getNextStep`; new execution only after save + teardown `Ready` |
| Target reps, VBT auto-end, user stop, cable release, timer expiry | No | Reason saved | Existing advancement unchanged |
| Echo, Just Lift, bodyweight, warm-up, or non-Old-School | No | Existing behavior | Existing behavior |
| Unresolved offer reaches rest zero | Remains visible | No new attempt yet | Autoplay and Skip Rest remain blocked; timer stays at zero |
| Decline | Closed | No accepted-drop increment | Normal transition remains pending; remaining rest is not skipped |
| Accept 20% from multiplier 1.0 | Closed/confirmed | Accepted-drop count +1 | Occurrence multiplier becomes 0.8 |
| Later accepted 20% drop on same logical set | Yes if below cap and above floor | New attempt; accepted-drop count becomes 2 | Multiplier becomes 0.64 |
| Manual back-navigation repeat | Feature offer rules unchanged | Attempt number advances; accepted-drop count does not | Existing manual flow remains intact |
| Candidate rounds to failed weight or falls below floor | Choice disabled/omitted | No change | No invalid retry |
| Process death with unresolved offer | Restored | Same IDs and counters | Resume screen at clamped timer; never auto-start |
| Zero-rep stalled attempt | Local row retained | No volume, success, achievement, calorie, PR, or health effect | Offer still follows eligibility rules |
| Superset A1 fails and retries | Yes | A1 attempts group together | Retry A1, then advance once to B1 |

## Execution entry checklist

- [ ] Confirm the enhancement worktree is clean: `git -C D:\Project-Phoenix-MP\.worktrees\enhancement status --short`.
- [ ] Confirm issue #687 is merged into the target `main` and its lifecycle suite is green.
- [ ] Confirm the highest SQLDelight migration and `shared/build.gradle.kts` version match this index's expected starting point.
- [ ] Confirm Android Studio's JBR exists at `C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr` and its SDK exists at `C:\Users\dasbl\AppData\Local\Android\Sdk`; set `JAVA_HOME` and `ANDROID_HOME` for Gradle commands.
- [ ] Execute the PR 1 plan fully and merge PR #686.
- [ ] Update local `main`, create the PR 2 branch, execute its plan fully, and merge it.
- [ ] Update local `main`, create the PR 3 branch, execute its plan fully, and complete hardware acceptance.

## Standard verification commands

Run from `D:\Project-Phoenix-MP\.worktrees\enhancement` in PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\dasbl\AppData\Local\Android\Sdk'
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinMetadata
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --continue --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainVitruvianDatabaseInterface :shared:verifyCommonMainVitruvianDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
git diff --check
git status --short
```

If `:shared:testAndroidHostTest` is unavailable after the prerequisite rebase, use `.\gradlew.bat '-Pskip.supabase.check=true' :shared:tasks --all` to identify the current host-test aggregate and update all three plan documents before continuing; do not silently skip the suite.

## Final release evidence

- All automated tests in each detailed plan pass on the merge commit for that PR.
- PR #686's existing actionable review threads are resolved with code/tests, not dismissed as obsolete.
- PR 3 includes screenshots for light/dark themes, compact and large screens, kg/lb, and accessibility font scaling.
- A real Trainer+ run proves RESET precedes every retry activation, all three percentages resolve correctly, two accepted drops compose, autoplay waits for decisions, reconnect/recovery fails closed, and a valid load above 100 kg still activates.
- Hardware evidence does not change stall thresholds. Any detector concern discovered during testing becomes a separate issue/proposal.

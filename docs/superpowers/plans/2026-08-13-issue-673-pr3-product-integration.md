# Issue #673 PR 3 Product Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the opt-in Old School routine configuration, backward-compatible sync/backup, approved inline rest offer, recovery UX, accessibility polish, and real-hardware acceptance for drop-set retries.

**Architecture:** Add fresh disabled-by-default fields to each stable routine-exercise occurrence. Bind PR 2's manager-neutral immutable offer/transition API through `MainViewModel` into `WorkoutState.Resting` and `RestTimerCard`; Compose renders exact engine-resolved candidates and returns identity-bearing commands. The engine remains authoritative for eligibility, stale actions, persistence, and BLE sequencing.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform/Material 3, StateFlow, SQLDelight, portal DTO adapters, kotlinx.serialization backup, resource localization, Android/JVM tests, iOS CI, Trainer+ hardware.

## Global Constraints

- Follow [2026-08-13-issue-673-drop-set-retry-index.md](2026-08-13-issue-673-drop-set-retry-index.md) and start only after PR 2 is merged into `main`.
- Bind to PR 2's actual public models and commands. Do not duplicate eligibility, candidate math, attempt state, stale-ID validation, or BLE sequencing in UI/ViewModel code.
- Add `RoutineExercise.dropSetEnabled` and `dropSetMinWeightKg` as fresh fields; PR #682 never shipped.
- Defaults are disabled/null everywhere: domain, SQL, migration, schema heal, portal DTOs, backup, editor, and fixtures.
- The setting remains stored when a user changes away from Old School but is inactive outside Old School.
- Do not expose this feature in Just Lift, Single Exercise, Echo, bodyweight, or warm-up setup.
- No percentage is preselected. Retry is disabled until the user explicitly chooses a valid engine-supplied candidate.
- Declining closes only the drop-set offer. It does not skip or reset rest.
- Unresolved offers block autoplay and Skip Rest. Timer zero remains a visible waiting state.
- Compose never sends trainer commands and never recalculates the candidate weight.
- Preserve the routine's saved programmed weights; session overlays stay in PR 2's local runtime.
- Hardware validation checks command ordering and device acceptance only; do not tune stall detection.

---

## File map

**Routine configuration and persistence**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq`
- `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/45.sqm`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt`
- `shared/build.gradle.kts`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/LegacySchemaReconciliationTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepositoryTest.kt`

**Portal sync and backup**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncDtos.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapter.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapter.kt` where routine pull decoding flows through it after PR 2
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalSyncAdapterTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/PortalPullAdapterTest.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepositoryTest.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BackupSerializationTest.kt`
- Create `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util/DataBackupManagerRoutineExerciseDropSetTest.kt`

**Routine editor**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt`
- `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModelTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/DropSetRoutineConfigurationTest.kt`

**Rest experience and orchestration glue**

- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutUiState.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/DropSetEligibilityPolicy.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/DropSetFeatureGate.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RestTransitionPlan.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ActiveWorkoutRuntimeRepository.kt`
- `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/RestTimerProgressionWiringTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen/DropSetOfferPresentationTest.kt`
- Create `shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/Issue673DropSetProductFlowTest.kt`

**Resources/previews**

- `shared/src/commonMain/composeResources/values/strings.xml`
- locale mirrors under `values-de`, `values-es`, `values-fr`, `values-it`, and `values-nl`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTabPreviews.kt`
- Create `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/screen/DropSetResourceContractTest.kt`

## Stable product interfaces

Add to `RoutineExercise`:

```kotlin
val dropSetEnabled: Boolean = false,
val dropSetMinWeightKg: Float? = null,
```

The minimum is kg per cable in persistence/domain code. The editor converts only at the display boundary using the app's existing unit and total/per-cable convention.

Adapt PR 2's immutable offer into a presentation-only value without losing identity:

```kotlin
data class DropSetOfferContext(
    val identity: RestActionIdentity,
    val exerciseDisplayName: String,
    val failedSetNumber: Int, // one-based display number
    val failedConfiguredWeightPerCableKg: Float,
    val minimumWeightPerCableKg: Float,
)

data class DropSetCandidateUiState(
    val percentage: DropPercentage,
    val weightPerCableKg: Float,
    val enabled: Boolean,
)

enum class DropSetRetryWaitState {
    SAVING_FAILED_ATTEMPT,
    PREPARING_TRAINER,
    READY_TO_RETRY,
}

sealed interface DropSetOfferUiState {
    val context: DropSetOfferContext

    data class Unresolved(
        override val context: DropSetOfferContext,
        val candidates: List<DropSetCandidateUiState>,
        val remainingDrops: Int,
    ) : DropSetOfferUiState

    data class AcceptedWaiting(
        override val context: DropSetOfferContext,
        val acceptedCandidate: DropSetCandidateUiState,
        val waitState: DropSetRetryWaitState,
    ) : DropSetOfferUiState

    data class RecoveryRequired(
        override val context: DropSetOfferContext,
        val acceptedCandidate: DropSetCandidateUiState?,
    ) : DropSetOfferUiState
}
```

A declined offer is removed from the presentation StateFlow (`null`) after PR 2 persists the declined transition; it is not represented as a lingering card state. `AcceptedWaiting` always carries the accepted percentage and exact engine-resolved per-cable weight so the collapsed confirmation can state what will be retried.

If PR 2 already publishes an equivalent manager-neutral value, use it directly rather than adding these types.

Preserve PR 2's global gate as an injected dependency:

```kotlin
fun interface DropSetFeatureGate {
    fun isEnabled(): Boolean
}

object EnabledDropSetFeatureGate : DropSetFeatureGate {
    override fun isEnabled(): Boolean = true
}
```

The eligibility entry point requires both this gate and the routine-exercise opt-in. A corrective release can bind a disabled implementation without a schema rollback.

Every action echoes full identity:

```kotlin
fun onAcceptDropSet(
    identity: RestActionIdentity,
    percentage: DropPercentage,
)

fun onDeclineDropSet(
    identity: RestActionIdentity,
)

fun onSkipRest(
    identity: RestActionIdentity,
)
```

## Task 1: Add disabled-by-default routine-exercise configuration to SQL and domain

**Files:** `Routine.kt`; `PhoenixDatabase.sq`; `migrations/45.sqm`; `MigrationStatements.kt`; `SchemaManifest.kt`; `shared/build.gradle.kts`; `SqlDelightWorkoutRepository.kt`; schema/repository tests.

- [ ] Verify PR 2 left schema version 45 with migrations through `44.sqm`. If not, renumber this plan and the index before editing.
- [ ] Write a `45 → 46` migration test that inserts a pre-feature `RoutineExercise` and asserts `dropSetEnabled == 0` and `dropSetMinWeightKg == null` after migration.
- [ ] Add fresh-install, resilient-heal, save/load, update, and mode-switch persistence tests. Cover enabled + positive floor and disabled + null.
- [ ] Run the tests and observe failures.
- [ ] Add the two defaulted domain fields adjacent to `stallDetectionEnabled`.
- [ ] Add `dropSetEnabled INTEGER NOT NULL DEFAULT 0` and `dropSetMinWeightKg REAL` to the SQL table, insert queries, `45.sqm`, migration fallback, manifest full table, and heal operations. Increment schema version to 46.
- [ ] Map both directions in `SqlDelightWorkoutRepository`'s row constructor and `insertRoutineExercise` helper.
- [ ] Audit all `RoutineExercise(` constructors with `rg -n "RoutineExercise\(" shared/src`; rely on defaults for existing/Just Lift/single-exercise fixtures unless a test intentionally configures the feature.
- [ ] Rerun schema/repository tests plus `:shared:validateSchemaManifest` and commit: `git add shared/build.gradle.kts shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/data shared/src/androidHostTest; git commit -m "feat: store routine drop set configuration"`.

## Task 2: Round-trip configuration through portal sync without breaking older payloads

**Files:** `PortalSyncDtos.kt`; `PortalSyncAdapter.kt`; `PortalPullAdapter.kt`; `SqlDelightSyncRepository.kt`; sync tests; external portal handoff note in PR description.

- [ ] Write outbound tests for enabled/floor and disabled/null routine exercises.
- [ ] Write inbound tests for enabled/floor, explicit disabled/null, and an older JSON payload omitting both fields. Omitted data must decode to disabled/null for a newly inserted routine.
- [ ] Write a merge test for an existing local enabled routine receiving an older payload. Use nullable wire fields on pull to distinguish omitted from explicit false: omitted preserves the local configuration; explicit false disables it.
- [ ] Run tests and observe failures.
- [ ] Add `dropSetEnabled: Boolean` and `dropSetMinWeightKg: Float?` to push DTOs. Add nullable `dropSetEnabled: Boolean? = null` and `dropSetMinWeightKg: Float? = null` to pull DTOs so omission is detectable.
- [ ] Map push in `PortalSyncAdapter.toPortalRoutine`; map pull insert/update in `SqlDelightSyncRepository`, preserving local fields only when the wire properties are omitted.
- [ ] Update portal set-count generation so repeated `CompletedSet` attempts from PR 2 do not inflate programmed-set counts. Use logical-set grouping when stable identity exists; retain legacy behavior otherwise. Do not hide individual attempts from local history.
- [ ] Rerun sync adapter/repository tests.
- [ ] Add an explicit PR release prerequisite: the portal API/schema must accept, retain, and return both fields before mobile rollout. The backend is outside this repository; attach its issue/deployment evidence to PR 3 rather than claiming completion locally.
- [ ] Commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepositoryTest.kt; git commit -m "feat: sync routine drop set configuration"`.

## Task 3: Preserve configuration in backup and legacy restore

**Files:** `BackupModels.kt`; `DataBackupManager.kt`; `BackupSerializationTest.kt`; new backup round-trip test.

- [ ] Write serialization tests for enabled/floor, disabled/null, and legacy JSON with omitted fields.
- [ ] Write buffered and streaming export/import round-trip tests for both configurations.
- [ ] Run tests and observe failures.
- [ ] Add defaulted fields to `RoutineExerciseBackup`; keep backup version 5 because this is an additive defaulted change under the file's existing version policy.
- [ ] Map both fields in `mapRoutineExerciseToBackup` and both `insertRoutineExerciseIgnore` import paths.
- [ ] Confirm `ActiveWorkoutRuntime` remains excluded from backup.
- [ ] Rerun backup tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/util shared/src/commonTest/kotlin/com/devil/phoenixproject/util shared/src/androidHostTest/kotlin/com/devil/phoenixproject/util; git commit -m "feat: back up routine drop set configuration"`.

## Task 4: Add Old School routine-editor validation and unit conversion

**Files:** `ExerciseConfigViewModel.kt`; `ExerciseConfigViewModelTest.kt`.

- [ ] Write ViewModel tests for default disabled/null, loading an existing enabled/floor value, enabling with no floor, zero/negative/NaN/infinite floor, valid positive floor, kg and lb input conversion, save persistence, and Old School → other mode → Old School preservation.
- [ ] Assert invalid active configuration blocks save and invokes no repository/update callback; changing mode away makes it inactive without clearing the stored values.
- [ ] Run the focused ViewModel tests and observe failures.
- [ ] Add `dropSetEnabled` and canonical-kg minimum state, plus `onDropSetEnabledChange`, `onDropSetMinWeightChange`, and a validation result.
- [ ] Define active validation as selected Old School mode and enabled; require a finite floor `> 0f`. Do not silently coerce invalid input or auto-fill a product value.
- [ ] Include both fields in the saved `originalExercise.copy`; use existing weight conversion helpers at the UI boundary.
- [ ] Rerun tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModelTest.kt; git commit -m "feat: validate routine drop set settings"`.

## Task 5: Build the themed routine configuration card

**Files:** `ExerciseEditBottomSheet.kt`; new `DropSetRoutineConfigurationTest.kt`; resources/locales.

- [ ] Add presentation tests/semantics guards for Old School disabled, Old School enabled with required minimum field, non-Old-School hidden/inactive, invalid field error, and large-font layout.
- [ ] Run tests and observe failures.
- [ ] Extract `DropSetRoutineConfigurationCard`; place it with Old School/stall settings and label the toggle “Offer drop set after failure.” Reveal the minimum-weight field only when active.
- [ ] Display the user's unit and the app's established total/per-cable convention; write canonical per-cable kg through the ViewModel callback.
- [ ] Gate the editor Save button with ViewModel validation. Preserve hidden values across mode changes.
- [ ] Add reviewed localized resource values in every existing locale; avoid new hard-coded English and do not leave untranslated placeholder values.
- [ ] Rerun presentation/ViewModel/resource tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt shared/src/commonMain/composeResources shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen; git commit -m "feat: configure drop set offers in routines"`.

## Task 6: Wire routine configuration into PR 2 eligibility and exact candidate previews

**Files:** `DropSetEligibilityPolicy.kt`; `DropSetFeatureGate.kt`; `RestTransitionPlan.kt`; `ActiveWorkoutRuntimeRepository.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; new `Issue673DropSetProductFlowTest.kt`.

- [ ] Replace PR 2's disabled test seam with an immutable `DropSetConfiguration(enabled, minimumWeightPerCableKg)` captured from the active `RoutineExercise` occurrence.
- [ ] Bind `EnabledDropSetFeatureGate` in production and add a test-disabled implementation; prove a disabled global gate suppresses every offer without deleting configuration, history, or runtime data.
- [ ] Write integration tests for the full eligibility matrix: existing `STALL_FAILURE`; Old School cable working set; enabled with positive floor; not Echo/Just Lift/bodyweight/warm-up; fewer than two accepted drops; exact routine/session/occurrence/key/coordinates match; optional planned-set match; at least one valid candidate.
- [ ] Include Old School AMRAP coverage: it is eligible only if the existing terminal classification is `STALL_FAILURE`; it is not excluded merely because its planned `SetType` is `AMRAP`.
- [ ] Test fixed, per-set, percentage-of-PR, manual one-set adjustment, and per-rep progression. Candidate math uses the failed attempt's configured starting weight; resolver output provides the exact preview and later BLE start value.
- [ ] Test same exercise-library ID in two routine occurrences and a superset partner; neither inherits the other's overlay.
- [ ] Run the tests and observe failures.
- [ ] Capture configuration in `SetExecutionCompletion`, invoke the existing pure eligibility policy, and publish PR 2's immutable unresolved offer. Do not add a second candidate calculator.
- [ ] Rerun product-flow/domain tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager; git commit -m "feat: enable configured drop set offers"`.

## Task 7: Build the inline rest offer from the approved native design

**Files:** `RestTimerCard.kt`; `DropSetOfferPresentationTest.kt`; strings/locales; preview file.

- [ ] Write pure presentation tests for unresolved/no selection, explicit 10/20/30 selection, invalid/omitted candidates, Retry disabled/enabled, decline, accepted-waiting collapsed state, recovery-required state, and offer ID changing while a selection exists.
- [ ] Test that selection is local UI state keyed by `offerId`; a new offer clears it. The manager remains the source of lifecycle truth.
- [ ] Run tests and observe failures.
- [ ] Extract an inline `DropSetOfferCard` rendered within `RestTimerCard`. Use the approved question “Retry this set with a drop set?”, three selectable percentage controls, and exact formatted engine-supplied weight previews.
- [ ] Preselect nothing. Disable “Retry set” until a valid choice is explicit. “Skip drop set” invokes only decline. Accepted state collapses to a confirmation/wait surface that names the accepted percentage and exact engine-supplied per-cable weight.
- [ ] Add remaining-opportunity and unavailable-choice messaging without exposing internal IDs.
- [ ] Add previews for unresolved, selected, accepted/waiting, floor-limited, light/dark, compact/expanded, and 2× font scale.
- [ ] Rerun tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt shared/src/commonMain/composeResources shared/src/androidMain shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/screen; git commit -m "feat: show drop set offer during rest"`.

## Task 8: Wire identity-bearing actions from Compose to the engine

**Files:** `Models.kt`; `WorkoutUiState.kt`; `WorkoutTab.kt`; `ActiveWorkoutScreen.kt`; `MainViewModel.kt`; `DefaultWorkoutSessionManager.kt`; `WorkoutCoordinator.kt`; wiring tests.

- [ ] Add a wiring test proving the immutable offer reaches `WorkoutState.Resting`/`WorkoutUiState`, then `WorkoutTab`, then `RestTimerCard` without reconstructing identity from exercise names or indices.
- [ ] Test accept, decline, and Skip Rest callbacks carry the exact `RestActionIdentity`—transition ID, nullable offer ID, logical key, and optional planned-set ID—plus selected percentage for accept, exactly once through `WorkoutActions`, preview actions, adapter, ViewModel, and manager.
- [ ] Test stale/duplicate callbacks remain harmless manager-level no-ops.
- [ ] Run wiring tests and observe failures.
- [ ] Extend the resting UI/state model with PR 2's manager-neutral offer state. Add the exact `onAcceptDropSet(identity, percentage)`, `onDeclineDropSet(identity)`, and `onSkipRest(identity)` methods to `WorkoutActions`, `PreviewWorkoutActions`, and `workoutActions()`.
- [ ] Collect the manager StateFlow in `ActiveWorkoutScreen`; include it in `remember` keys and the constructed `WorkoutUiState`; pass through `WorkoutTab` into `RestTimerCard`.
- [ ] Expose thin `MainViewModel` delegations. Keep identity validation, transition mutation, persistence, and BLE calls in PR 2 manager/engine code.
- [ ] Rerun wiring/product-flow tests and commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation; git commit -m "feat: wire drop set rest actions"`.

## Task 9: Gate timer, autoplay, Skip Rest, persistence, and recovery UX

**Files:** `RestTimerCard.kt`; `WorkoutTab.kt`; `ActiveSessionEngine.kt`; `DefaultWorkoutSessionManager.kt`; `RestTransitionPlan.kt`; `ActiveWorkoutRuntimeRepository.kt`; `DropSetRuntimeRecoveryTest.kt`; `Issue673DropSetProductFlowTest.kt`.

- [ ] Add integration tests for unresolved offer with rest running, rest reaching zero, autoplay on/off, Skip Rest pressed, decline before/after zero, accept before/after zero, persistence delayed/failed, teardown `TearingDown`/`RecoveryRequired`, disconnect/reconnect, process restoration, and End Workout.
- [ ] Add cancellation tests around accept, decline, persistence, and retry start; `CancellationException` must be rethrown and must not create a completion, decline, or accepted action.
- [ ] Expected: unresolved timer clamps at zero; autoplay/Skip Rest cannot advance; decline restores normal transition without skipping remaining rest; accepted retry waits visibly for save + teardown; recovery never auto-starts; End Workout still follows #687 immediate navigation and clears runtime.
- [ ] Run tests and observe failures.
- [ ] Disable Skip Rest while unresolved in both UI semantics and engine command validation. After resolution, Skip Rest submits `RestTransitionCommand.SkipRest` with the visible plan's full identity and executes only that identified normal/retry transition; stale identity remains a no-op.
- [ ] Display specific non-sensitive wait/recovery states: saving failed attempt, preparing trainer, reconnect/retry required, and ready-to-retry. Do not leak raw error details or silently discard the offer.
- [ ] Persist acceptance before changing the displayed state; stale offer IDs and repeated taps remain no-ops.
- [ ] Rerun product-flow, PR 2 recovery, and #687 lifecycle tests; commit: `git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation; git commit -m "feat: gate rest transitions for drop set decisions"`.

## Task 10: Complete accessibility, localization, and visual evidence

**Files:** `RestTimerCard.kt`; `ExerciseEditBottomSheet.kt`; all resource locale files; previews/resource contract tests.

- [ ] Add semantics tests/guards for a selectable group, selected state, disabled reason, unique Retry/Skip labels, and candidate content descriptions containing percentage plus formatted preview.
- [ ] Add polite live-region announcements for the offer, accepted confirmation, recovery state, and timer-zero waiting; retain the current timer announcement behavior without per-second spam.
- [ ] Ensure 48 dp targets, visible focus, non-color-only selection, logical TalkBack/VoiceOver order, and focus transfer when the card collapses.
- [ ] Verify every new key has a reviewed value in base plus `de/es/fr/it/nl`; fail the resource contract test when a locale omits a key.
- [ ] Capture screenshots from previews/emulator for light/dark, compact/expanded, kg/lb, no selection/selected/waiting/recovery, and font scale 1.0/2.0.
- [ ] Run localization/design guard tests and `:androidApp:assembleDebug`; commit: `git add shared/src/commonMain/composeResources shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation shared/src/androidMain shared/src/androidHostTest; git commit -m "fix: polish drop set accessibility and localization"`.

## Task 11: Run cross-platform, privacy, and real-hardware acceptance

**Files:** all PR 3 files; CI evidence; hardware test record; PR description.

- [ ] Run focused Windows tests:

```powershell
$env:JAVA_HOME='C:\Users\dasbl\AppData\Local\Programs\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\dasbl\AppData\Local\Android\Sdk'
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*PortalSyncAdapterTest*' --tests '*BackupSerializationTest*' --tests '*DropSetRoutineConfigurationTest*' --tests '*DropSetOfferPresentationTest*' --tests '*Issue673DropSetProductFlowTest*' --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest --tests '*SchemaParityTest*' --tests '*SchemaManifestTest*' --tests '*LegacySchemaReconciliationTest*' --tests '*SqlDelightWorkoutRepositoryTest*' --tests '*SqlDelightSyncRepositoryTest*' --tests '*ExerciseConfigViewModelTest*' --tests '*DataBackupManagerRoutineExerciseDropSetTest*' --console=plain
```

- [ ] Run full Windows gates:

```powershell
.\gradlew.bat '-Pskip.supabase.check=true' :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug --continue --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' :shared:generateCommonMainPhoenixDatabaseInterface :shared:verifyCommonMainPhoenixDatabaseMigration :shared:validateSchemaManifest --console=plain
.\gradlew.bat '-Pskip.supabase.check=true' spotlessCheck --console=plain
git diff --check
```

- [ ] Let macOS CI compile/test iOS and perform VoiceOver validation; attach results before merge.
- [ ] Audit new logs with `git diff origin/main -- shared/src | rg -n "Logger\.|log\."`; confirm they contain transition/reason codes only, never profile/routine/exercise IDs, names, exact weight, or exact reps.
- [ ] Confirm portal backend deployment/compatibility evidence is attached and older app payloads remain accepted.
- [ ] On a real Trainer+, run this matrix and capture ordered sanitized command/event evidence:

  - existing detector triggers the offer without any threshold change;
  - 10%, 20%, and 30% choices preview the exact later configured per-cable load;
  - two accepted 20% drops produce the expected 0.64 occurrence multiplier;
  - no activation/config/force command occurs before failed execution RESET/teardown succeeds;
  - autoplay on/off, Skip Rest, decline, accept after timer zero, supersets, and remaining exercise sets behave as specified;
  - disconnect during decision and teardown recovery fail closed and restore through Resume without auto-start;
  - a valid Trainer+ load above 100 kg activates successfully;
  - the saved routine remains byte-for-byte/configuration-equivalent except for the user's two opt-in fields.

- [ ] If hardware reveals a detector concern, file a separate issue with evidence; do not alter detector code in PR 3.
- [ ] Verify a build bound to a disabled `DropSetFeatureGate` suppresses offers while reading all additive schema/data successfully; record this as the forward corrective-release procedure.
- [ ] Update the PR description with screenshots, accessibility evidence, portal dependency, hardware matrix, privacy audit, migration, and opt-in rollout.
- [ ] Obtain approval and green CI before merge; commit any final evidence-driven test correction as `test: complete issue 673 acceptance coverage`.

# Presentation - ViewModels Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ConnectionLogsViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/CycleEditorViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/DiagnosticsViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/EulaViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseLibraryViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExternalIntegrationViewModels.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/GamificationViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModel.kt`

Review focus: ViewModel lifecycle issues, coroutine scope leaks, state management bugs, stubs, errors, and failure points.

Summary:
- Findings: 23
- Severity breakdown: critical 0, high 5, medium 15, low 3
- Category breakdown: bug 7, error 8, failure-point 8, stub 0
- Stubs/TODOs in assigned files: none found

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt

### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 152-162
- Description: `selectExerciseById` waits on `_exercises.first { it.isNotEmpty() }`. If the repository legitimately emits an empty catalog, fails before emitting non-empty data, or the target exercise was deleted, this coroutine waits forever and the caller gets no UI error or fallback state.
- Suggested fix direction: Wait on the repository flow with a timeout or explicit loading/error state, and handle empty catalogs separately from “still loading”. Return the wizard to exercise selection with a visible message when the exercise cannot be resolved.

### Finding 2
- Category: bug
- Severity: high
- Line numbers: 306-321
- Description: `acceptResult` calls `assessmentRepository.saveAssessmentSession(...)` without passing a profile ID, so the repository default of `"default"` is used for every saved assessment. This ViewModel has no active-profile dependency, so assessments created while another profile is active can be persisted under the wrong profile and update/read history inconsistently.
- Suggested fix direction: Inject `UserProfileRepository` or pass the active profile ID into the ViewModel/session start flow, and provide that profile ID to `saveAssessmentSession`. Consider capturing the profile at assessment start so a profile switch mid-wizard cannot change ownership unexpectedly.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ConnectionLogsViewModel.kt

### Finding 3
- Category: failure-point
- Severity: low
- Line numbers: 126-128
- Description: `clearOldLogs` computes `hoursOld * 60 * 60 * 1000L`. The first multiplications are performed as `Int`, so very large `hoursOld` values can overflow before the value is widened to `Long`, producing an incorrect cutoff time.
- Suggested fix direction: Convert before multiplication and clamp input, e.g. `hoursOld.coerceAtLeast(0).toLong() * 60L * 60L * 1000L`.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/CycleEditorViewModel.kt

### Finding 4
- Category: bug
- Severity: medium
- Line numbers: 58-65, 65-124
- Description: `initialize` starts a new load coroutine without cancelling or versioning earlier initialization work. If the editor is quickly reinitialized for another cycle while the first repository calls are still running, the stale coroutine can update `_uiState` after the newer request and display/save the wrong cycle data.
- Suggested fix direction: Track an initialization `Job` and cancel it before starting a new one, or capture a request token/cycle ID and ignore results that no longer match the current request before writing state.

### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: 300-305
- Description: New cycles use `userProfileRepository.activeProfile.value?.id ?: "default"` at save time. If the active profile flow has not loaded yet, or if an edited cycle lacks `originalProfileId`, the cycle is silently saved to the default profile rather than blocking for the real active profile.
- Suggested fix direction: Require a loaded active profile before saving new cycles, surface an error if profile ownership is unavailable, and avoid defaulting edited cycles unless the stored row is truly legacy and intentionally migrated.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/DiagnosticsViewModel.kt

No findings identified in this file during the assigned review.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/EulaViewModel.kt

### Finding 6
- Category: failure-point
- Severity: low
- Line numbers: 51-59
- Description: `acceptEula` writes settings and then unconditionally updates `_eulaAccepted` with no exception handling. A platform settings write failure can either crash the caller or leave the UI believing the EULA was accepted when persistence did not complete.
- Suggested fix direction: Wrap persistence in `runCatching`/try-catch, expose an error state or event, and only set `_eulaAccepted` to true after both version and timestamp writes succeed.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseConfigViewModel.kt

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 153-170
- Description: The initialization guard returns when `_initialized` is true and the exercise ID/profile match, but it ignores changed `RoutineExercise` contents, unit changes, and conversion lambdas. Reopening the same exercise after edits or after switching kg/lb display can leave stale set weights, mode, rest, and PR-scaling fields in the dialog.
- Suggested fix direction: Include all initialization inputs that affect UI state in the guard, or remove the guard and rely on the caller to preserve state only while the same editor session is active.

### Finding 8
- Category: bug
- Severity: high
- Line numbers: 297-324, 344-370
- Description: PR and baseline loaders launch asynchronous work that writes shared state after completion, but the jobs are not cancelled/versioned when `initialize` runs again. They also read mutable `activeProfileId` inside the coroutine rather than capturing the profile for the request. A slow lookup for a previous exercise/profile can overwrite `_currentExercisePR`, `_currentMaxVolumePR`, `_velocityEstimateKg`, `_storedOneRepMaxKg`, or synced set weights for the newly opened exercise.
- Suggested fix direction: Capture `exerciseId`, workout mode, and profile ID into immutable locals for each request; cancel old PR/baseline jobs on reinitialize; and ignore results whose request token no longer matches the current initialized exercise/profile.

### Finding 9
- Category: failure-point
- Severity: medium
- Line numbers: 649-688, 722-791
- Description: Public mutators accept raw reps, weights, durations, and rest times without validation (`updateReps`, `updateWeight`, `updateDuration`, `updateRestTime`). `onSave` persists these values directly. If a UI bug, test harness, restore path, or future caller supplies negative reps, zero/negative duration, nonsensical rest time, or invalid weight, the invalid routine configuration is saved.
- Suggested fix direction: Enforce domain bounds in the ViewModel before updating state and again before saving. Keep UI controls bounded, but do not rely on UI widgets as the only validation layer.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExerciseLibraryViewModel.kt

### Finding 10
- Category: error
- Severity: medium
- Line numbers: 42-49
- Description: `loadExercises` has no `catch`/`finally` around `exerciseRepository.getAllExercises().collectLatest`. If the flow throws, the coroutine dies and `_isLoading` remains true because it is only reset after a successful emission.
- Suggested fix direction: Add flow `catch` or a surrounding try/finally, expose an error state, and ensure `_isLoading` is reset on both success and failure paths.

### Finding 11
- Category: error
- Severity: medium
- Line numbers: 90-100
- Description: Video loading calls `exerciseRepository.getVideos` without exception handling in both the fire-and-forget cache path and the suspend accessor. A repository or network/storage failure can cancel the ViewModel coroutine and leaves the cache indistinguishable from “loaded but no videos”.
- Suggested fix direction: Catch repository failures, track per-exercise loading/error state, and let callers distinguish an empty video list from a failed load.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExternalIntegrationViewModels.kt

### Finding 12
- Category: error
- Severity: medium
- Line numbers: 116-132
- Description: `simulateProgram` sets `isSimulating = true` and then calls `integrationManager.simulatePlayground` with no try/finally. If the manager throws before returning a `Result`, `isSimulating` remains true and no event is emitted.
- Suggested fix direction: Wrap the call in try/catch/finally, clear `isSimulating` in `finally`, and emit a visible error event for thrown exceptions as well as `Result.failure`.

### Finding 13
- Category: error
- Severity: medium
- Line numbers: 135-146
- Description: `commitPreview` launches `integrationManager.commitProgramText` without exception handling. A commit failure cancels the coroutine, does not notify the user, and leaves the stale preview state in place.
- Suggested fix direction: Catch failures, surface an `IntegrationUiEvent.Snackbar` or error state, and only clear `playgroundPreview` after a confirmed successful commit.

### Finding 14
- Category: failure-point
- Severity: medium
- Line numbers: 44-47, 63-70, 169-178
- Description: Several eager `stateIn` pipelines expose repository flows directly without `catch` or an error state. If an external activity/routine/measurement repository flow throws, the corresponding UI state stops updating and remains at the initial or last value with no visible failure indication.
- Suggested fix direction: Add `catch` handling to each repository flow, log the failure, and include loading/error fields in the UI states that need to recover or show a retry affordance.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/GamificationViewModel.kt

### Finding 15
- Category: bug
- Severity: high
- Line numbers: 84-95
- Description: Badge progress is loaded once in `init` using `activeProfileId.value`. Unlike streaks/stats/uncelebrated badges, `_badgesWithProgress` is not reactive to profile changes. On cold start it can load for the fallback `"default"` profile before the active profile arrives, and after profile switching it can continue showing the previous profile's badges until some caller manually invokes `loadBadges`.
- Suggested fix direction: Derive badge progress from `activeProfileId.flatMapLatest { ... }`, or collect profile changes and reload with cancellation/versioning. Clear stale badge state while a new profile is loading.

### Finding 16
- Category: error
- Severity: medium
- Line numbers: 135-153
- Description: `markBadgeCelebrated` and `updateAndCheckBadges` call repository mutations without local error handling. Failures can cancel the launched coroutine or propagate to callers without updating `_loadError`, leaving the UI unable to report that badge celebration/update failed.
- Suggested fix direction: Wrap repository mutations, log failures, and expose an event/error state. Preserve cancellation exceptions if cancellation semantics are required.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt

### Finding 17
- Category: error
- Severity: medium
- Line numbers: 132-215
- Description: The init block starts eight long-lived repository collectors with no `catch`. Any exception from one repository flow permanently cancels that collector, leaving that slice of `IntegrationsUiState` stale while the rest of the screen continues to function.
- Suggested fix direction: Add `catch`/retry handling around each observed flow and surface a provider-specific error state so the user can retry or understand that data is stale.

### Finding 18
- Category: bug
- Severity: medium
- Line numbers: 132-215, 223-239, 254-294, 544-552, 555-561
- Description: Many concurrent coroutines update `_uiState` with `_uiState.value = _uiState.value.copy(...)`. This is a non-atomic read-modify-write pattern; concurrent collectors and operations can lose fields written by another coroutine if both read the same old value and then assign separate copies.
- Suggested fix direction: Use `MutableStateFlow.update { ... }` consistently for all derived updates, or funnel state changes through a reducer so concurrent operations compose instead of overwriting one another.

### Finding 19
- Category: error
- Severity: medium
- Line numbers: 447-478, 577-615
- Description: `onHealthPermissionResult` and `checkHealthPermissionsAfterSettingsReturn` call permission/status helpers without outer exception handling. If `healthIntegration.hasPermissions`, `hasBodyWeightReadPermission`, or repository status updates throw, the coroutine fails without setting `errorMessage` or recovering the integration state.
- Suggested fix direction: Wrap these permission-result paths in try/catch similar to `toggleHealthIntegration`, log the exception, and set a user-visible health integration error.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt

### Finding 20
- Category: failure-point
- Severity: high
- Line numbers: 660-694
- Description: Startup velocity-1RM backfill is launched with `viewModelScope.launch(kotlinx.coroutines.NonCancellable)`. Passing `NonCancellable` as the launch context replaces normal child cancellation, so this potentially long database backfill can continue after the ViewModel is cleared and hold repository/ViewModel references beyond the lifecycle.
- Suggested fix direction: Launch as a normal child of `viewModelScope` and use `withContext(NonCancellable)` only for the smallest critical section if truly needed. Prefer moving one-time backfill to an application-level worker/repository migration scope that has an explicit lifecycle.

### Finding 21
- Category: error
- Severity: high
- Line numbers: 698-715
- Description: `onCleared` calls `super.onCleared()` and then launches a `NonCancellable` coroutine on `viewModelScope` to disconnect BLE. Depending on lifecycle timing, launching from an already-cleared scope may not run as expected; if it does run, `NonCancellable` can outlive the ViewModel. Either outcome undermines the cleanup path this code is meant to guarantee.
- Suggested fix direction: Perform BLE cleanup through an owned manager cleanup API that is called before scope teardown, or have the repository expose an application-owned cleanup operation. Avoid launching new long-lived work from `onCleared`; if a suspend disconnect is unavoidable, use a clearly owned external scope and document cancellation/timeout behavior.

### Finding 22
- Category: bug
- Severity: medium
- Line numbers: 435-469
- Description: `saveRackBehaviorOverridesForExercise` updates the active in-memory routine and active rack overrides before persistence. If `workoutRepository.updateRoutine` fails, the current workout UI now shows overrides that were not saved, and no rollback/error state is exposed to reconcile memory with storage.
- Suggested fix direction: Either persist first and then update in-memory state on success, or keep the optimistic update but add rollback/error handling when persistence fails.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ThemeViewModel.kt

### Finding 23
- Category: failure-point
- Severity: low
- Line numbers: 32-45, 62-70
- Description: Theme setters update state before writing settings and do not catch persistence failures. If settings storage throws, the UI can show the new theme/dynamic-color state even though it was not persisted for the next launch.
- Suggested fix direction: Persist first or roll back state on failure, and expose a small error event/log path so preference-write problems are not silent.

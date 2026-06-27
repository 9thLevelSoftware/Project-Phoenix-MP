# Presentation - Screens Part 1 Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SplashScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EulaScreen.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/InsightsTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SmartInsightsTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExercisesTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`

Stub marker scan: no TODO/FIXME/HACK/NotImplemented markers were found in the assigned files.

## Summary

Findings count: 13

Severity breakdown:
- Critical: 0
- High: 1
- Medium: 9
- Low: 3

Category breakdown:
- bug: 5
- stub: 2
- error: 4
- failure-point: 2

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HomeScreen.kt`

#### Finding 1
- Category: failure-point
- Severity: medium
- Line numbers: 103-110, 681-685
- Description: The Home cycle banner runs `loadHomeCycleProgress()` from a `LaunchedEffect` without catching repository failures. `loadHomeCycleProgress()` calls `checkAndAutoAdvance()` and then `getCycleProgress()`, so a database/repository exception while loading Home can cancel the effect and surface as a screen-level crash instead of falling back to a safe empty banner state.
- Suggested fix direction: Wrap the load in `runCatching`/try-catch, log the failure, and set `cycleProgress = null` or expose a non-blocking error state while keeping Home usable.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`

#### Finding 2
- Category: bug
- Severity: medium
- Line numbers: 226-229
- Description: `showBackButton` is true for every route except Home, which includes root bottom-navigation tabs such as Analytics, Smart Insights, Daily Routines, Training Cycles, and Settings. This makes main tabs display a back arrow and allows `navigateUp()` from root-level destinations, which can pop users out of the tab stack or create inconsistent navigation compared with the bottom bar model.
- Suggested fix direction: Treat all root tab routes as top-level destinations with no back button. Only show back navigation for detail/editor/workout subflows or when an explicit `topBarBackAction` is set.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SplashScreen.kt`

No issues found in the reviewed scope.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EulaScreen.kt`

No issues found in the reviewed scope.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt`

#### Finding 3
- Category: error
- Severity: high
- Line numbers: 2869-2886
- Description: The restore flow does not catch exceptions thrown directly by `backupManager.importFromFile(selectedFile)`. Only `Result.onFailure` is handled; a thrown exception exits the coroutine after `finally`, clears the loading flag, and can leave the user without an error dialog or crash the composition coroutine.
- Suggested fix direction: Mirror the backup path and wrap the import call in try-catch. Populate `backupError` with a bounded class/message, set `showResultDialog = true`, and clear `restoreInProgress` in `finally`.

#### Finding 4
- Category: stub
- Severity: medium
- Line numbers: 2903-2905, 2931-2951, 2984-2990
- Description: Safe-word calibration declares `calibrationFailed` and renders a failure branch, but no path ever sets `calibrationFailed = true`. If the listener starts successfully but never detects the safe word, the dialog can remain in the listening state indefinitely; only the "not listening" startup case turns into `micError`.
- Suggested fix direction: Add a bounded calibration timeout / retry counter that sets `calibrationFailed = true` when three detections are not received within the expected window, and provide retry/cancel guidance.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt`

#### Finding 5
- Category: error
- Severity: medium
- Line numbers: 632-633
- Description: `CompletedSetsSection` loads completed sets from the repository without error handling. This is inconsistent with `RepDetailsSection`, which catches repository failures, and means a corrupted/missing completed-set row or database exception can crash History when a card is expanded.
- Suggested fix direction: Wrap `completedSetRepository.getCompletedSets(sessionId)` in try-catch, log the failure, and render an empty set breakdown or a non-fatal inline message.

#### Finding 6
- Category: error
- Severity: medium
- Line numbers: 1434-1437
- Description: `BiomechanicsSection` lazy-loads per-rep biomechanics data without error handling. Any repository/deserialization failure while expanding biomechanics details can crash the History screen instead of just hiding or marking that optional detail unavailable.
- Suggested fix direction: Use try-catch around `biomechanicsRepository.getRepBiomechanics(session.id)`, reset `isLoadingBiomechanics`, and render an empty/error state on failure.

#### Finding 7
- Category: bug
- Severity: low
- Line numbers: 1235-1239, 1255-1257
- Description: `WorkoutSessionCard` keeps `exerciseName` in remembered state but only updates it when `session.exerciseId` is non-null. If this composable is ever reused for a new session with `exerciseId == null`, a previous non-null exercise name can remain visible instead of falling back to "Just Lift". The function is currently unused in the codebase, but it is still a latent stale-state bug.
- Suggested fix direction: In `LaunchedEffect(session.exerciseId)`, explicitly set `exerciseName = null` when `session.exerciseId` is null, or derive the display name directly from `session` without remembered mutable state.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/InsightsTab.kt`

No issues found in the reviewed scope.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SmartInsightsTab.kt`

#### Finding 8
- Category: error
- Severity: medium
- Line numbers: 120-147
- Description: The Smart Insights initial load wraps repository calls in `try/finally` but does not catch failures. If any of the three repository queries throws, the exception escapes the `LaunchedEffect`; the loading state is cleared but no UI error state is set, so the screen can crash or render misleading empty insights.
- Suggested fix direction: Add an error state and catch around the `withContext(Dispatchers.IO)` block. Log the failure and render a retryable inline error card instead of computing insights from reset empty lists after a failed load.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExercisesTab.kt`

#### Finding 9
- Category: bug
- Severity: medium
- Line numbers: 346-349
- Description: Estimated 1RM is calculated with `session.workingReps`, which appears to be total working reps for the session, not reps in a single set. For a 3x10 workout this can feed 30 reps into the 1RM formula, substantially overestimating the exercise summary's "Est. 1RM".
- Suggested fix direction: Use a per-set rep count (for example `session.reps` for fixed-rep sessions or completed-set records when available) and ignore/handle AMRAP/aggregate sessions where a reliable single-set rep count is unavailable.

#### Finding 10
- Category: bug
- Severity: medium
- Line numbers: 90-91
- Description: Alphabetical grouping calls `it.exerciseName.first()` without guarding against an empty exercise name. If an imported/custom exercise name is an empty string, opening the Exercises tab will throw `NoSuchElementException`.
- Suggested fix direction: Normalize blank names to a fallback such as "Unknown Exercise" before building `ExerciseSummary`, or group with `firstOrNull()?.uppercaseChar() ?: '#'`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

#### Finding 11
- Category: bug
- Severity: low
- Line numbers: 175-193, 1152-1154
- Description: Historical routine time estimates are stored in a remembered map and updated when `routines` changes, but entries are never removed. If a routine is edited to have no exercises, the loop skips it (`continue`) and leaves any old estimate in `timeEstimates`, so the card can keep showing stale duration text instead of the fallback "0 min".
- Suggested fix direction: Clear `timeEstimates` at the start of the `LaunchedEffect`, or remove the entry for routines with no exercises / failed estimates before continuing.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`

#### Finding 12
- Category: failure-point
- Severity: medium
- Line numbers: 21-23, 29-38, 48-50
- Description: Several dynamic route builders interpolate IDs directly into route strings without URL/path-segment encoding (`ExerciseDetail`, `RoutineEditor`, `CycleEditor`, `CycleReview`, `StrengthAssessment`). Other routes in the same file use `encodeRouteSegment()`, which highlights the inconsistency. Any ID containing `/`, `%`, spaces, or other reserved route characters can break navigation or be parsed as the wrong argument.
- Suggested fix direction: Apply `encodeRouteSegment()` consistently to every dynamic route segment and ensure the receiving nav arguments are decoded by the navigation layer.

#### Finding 13
- Category: stub
- Severity: low
- Line numbers: 87-95
- Description: `BottomNavItem` is stale: the comment says only three items are shown, and the enum omits the Smart Insights tab even though `EnhancedMainScreen` renders four bottom-bar items (Analytics, Workouts, Insights, Settings). Stale navigation metadata can mislead future code that tries to derive bottom-bar behavior from this enum.
- Suggested fix direction: Update or remove `BottomNavItem`, or make the bottom bar derive from the enum so there is a single source of truth.

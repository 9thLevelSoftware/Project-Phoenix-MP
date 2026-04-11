# UI & State Management Audit Report

**Date:** 2026-03-31  
**Scope:** Compose Multiplatform UI, ViewModels, Managers, Navigation, Koin DI configuration  
**Status:** Audit-only (no fixes applied)

## Executive Summary

4 CRITICAL, 6 HIGH, 10 MEDIUM, 6 LOW severity issues identified. Critical issues include CoroutineScope leaks, missing Koin registrations, a god-object ViewModel with 18 parameters, and thread-unsafe mutable state in the workout coordinator.

## Findings

### CRITICAL Severity

#### UI-C001: LinkAccountViewModel leaks CoroutineScope
- **File:** `shared/src/commonMain/.../ui/sync/LinkAccountViewModel.kt`
- **Description:** Creates its own `CoroutineScope(Dispatchers.Main)` but does NOT extend `ViewModel` and has no `onCleared()` or cancellation mechanism. Registered as `factory` in Koin, so each navigation to LinkAccountScreen creates a new leaked scope.
- **Impact:** Memory leak, potential crashes from stale callbacks, wasted network resources.
- **Suggested Fix:** Extend ViewModel or implement Closeable with Koin's `onClose` callback.

#### UI-C002: ExerciseLibraryViewModel and ExerciseConfigViewModel not registered in Koin
- **File:** `shared/src/commonMain/.../di/PresentationModule.kt` (absent entries)
- **Description:** Neither appears in any Koin module. `ExerciseConfigViewModel` is manually constructed with `remember { ExerciseConfigViewModel(...) }` in `ExerciseEditBottomSheet.kt`. Bypasses DI, making testing harder and risking inconsistent dependency resolution.
- **Impact:** DI inconsistency; `ExerciseConfigViewModel` survives only within its composable's `remember` scope (lost on recomposition/config change).
- **Suggested Fix:** Register both in PresentationModule; inject via koinInject().

#### UI-C003: MainViewModel constructor has 18 parameters (god-object)
- **File:** `shared/src/commonMain/.../presentation/viewmodel/MainViewModel.kt`
- **Description:** Constructor takes 18 dependencies. Exposes ~150+ public methods/properties. Acts as a massive facade over sub-managers.
- **Impact:** High coupling, difficult testing, long compilation times for this file.
- **Suggested Fix:** Decompose into focused ViewModels (WorkoutViewModel, RoutineViewModel, etc.) consumed by specific screens.

#### UI-C004: Thread-unsafe mutable state in WorkoutCoordinator
- **File:** `shared/src/commonMain/.../presentation/manager/WorkoutCoordinator.kt`
- **Description:** Multiple fields use `@Volatile` for visibility, but compound read-modify-write operations (e.g., `if (X == null) { X = currentTimeMillis() }`) are NOT atomic. Paths exist where metrics collector AND handle-state collector both check/write `autoStopStartTime`.
- **Impact:** Rare race conditions in auto-stop timing that could cause missed auto-stops or double-triggers.
- **Suggested Fix:** Use `AtomicReference` or protect compound operations with a Mutex.

### HIGH Severity

#### UI-H001: App.kt caches dependencies in remember{} causing stale references
- **File:** `shared/src/commonMain/.../App.kt`
- **Description:** `remember { runCatching { koin.get<MainViewModel>() } }` caches the DI result across recompositions. On iOS, `remember{}` without keys survives the entire app lifetime, making MainViewModel (registered as `factory`) effectively a singleton.
- **Impact:** Stale objects persist if Koin modules are refreshed; factory semantics violated on iOS.
- **Suggested Fix:** Use `koinInject()` or `koinViewModel()` which integrate with Compose lifecycle.

#### UI-H002: ViewModel lifecycle mismatch (factory vs remember)
- **File:** `di/PresentationModule.kt` (factory), `App.kt` (remember)
- **Description:** MainViewModel is `factory` in Koin but cached in `remember{}` in App.kt. The `onCleared()` method may never be called on iOS, meaning BLE disconnect cleanup never runs.
- **Impact:** On iOS, BLE connections may never be cleaned up on app teardown, draining battery.
- **Suggested Fix:** Register as `viewModel` in Koin and use proper Compose ViewModel integration.

#### UI-H003: koinInject() inside LazyColumn items
- **File:** `shared/src/commonMain/.../presentation/screen/HistoryTab.kt` (lines ~512, ~1176)
- **Description:** Calls `koinInject()` inside lazy item lambdas. The code at line 64 documents this as problematic but lines 512 and 1176 still violate the rule.
- **Impact:** Potentially stale or incorrect dependency instances during rapid scrolling/recomposition.
- **Suggested Fix:** Hoist koinInject() calls to the composable function scope, not inside lazy items.

#### UI-H004: AssessmentViewModel navigation race condition
- **File:** `shared/src/commonMain/.../presentation/navigation/NavGraph.kt`
- **Description:** Both StrengthAssessmentPicker and StrengthAssessment routes create a NEW AssessmentViewModel via koinInject(). Quick navigation between them creates orphaned velocity capture jobs.
- **Impact:** Duplicate exercise loading, orphaned velocity capture jobs.
- **Suggested Fix:** Use a shared ViewModel scoped to a navigation graph.

#### UI-H005: Error states silently swallowed in GamificationViewModel
- **File:** `shared/src/commonMain/.../presentation/viewmodel/GamificationViewModel.kt`
- **Description:** `loadBadges()` catches all exceptions with `catch (_: Exception) { }` - empty catch block. No logging, no error state.
- **Impact:** Users see indefinite loading state if badge loading fails, with no feedback.
- **Suggested Fix:** Set an error state, log the exception.

#### UI-H006: No deep link support
- **File:** `shared/src/commonMain/.../presentation/navigation/NavigationRoutes.kt`, `NavGraph.kt`
- **Description:** No deep link declarations exist. Routes like `exercise_detail/{exerciseId}` would benefit from deep links for notifications or external integrations.
- **Impact:** Cannot link into specific screens from push notifications, widgets, or external apps.
- **Suggested Fix:** Add deep link declarations to relevant routes.

### MEDIUM Severity

#### UI-M001: SettingsTab composable is 2277 lines with 30+ parameters
- **File:** `shared/src/commonMain/.../presentation/screen/SettingsTab.kt`
- **Description:** Single composable function with 30+ parameters. Every setting change causes full recomposition.
- **Impact:** Performance degradation; difficult to maintain.
- **Suggested Fix:** Split into smaller composables grouped by settings category.

#### UI-M002: CycleReviewScreen inline data loading bypasses ViewModel
- **File:** `shared/src/commonMain/.../presentation/navigation/NavGraph.kt`
- **Description:** Directly loads data from repository inline with local `var cycle by remember { mutableStateOf(null) }`. Doesn't survive configuration changes.
- **Impact:** State loss on configuration change; inconsistent pattern.

#### UI-M003: Redundant state flows in HistoryManager
- **File:** `shared/src/commonMain/.../presentation/manager/HistoryManager.kt`
- **Description:** `_workoutHistory` and `allWorkoutSessions` both derive from the same source independently.
- **Impact:** Wasted memory (two copies of session data).

#### UI-M004: ExerciseConfigViewModel uses lateinit var for dependencies
- **File:** `shared/src/commonMain/.../presentation/viewmodel/ExerciseConfigViewModel.kt`
- **Description:** `originalExercise`, `weightUnit`, etc. are `lateinit var`. If methods called before `initialize()`, crashes with `UninitializedPropertyAccessException`.
- **Impact:** Crash if methods called before initialization.

#### UI-M005: Navigation back stack not properly managed for workout flow
- **File:** `shared/src/commonMain/.../presentation/navigation/NavGraph.kt`
- **Description:** No `popUpTo` on the ActiveWorkout route. Pressing back after workout complete could return to stale ActiveWorkout state.
- **Impact:** Confusing navigation behavior.

#### UI-M006: HomeScreen directly injects repositories via koinInject()
- **File:** `shared/src/commonMain/.../presentation/screen/HomeScreen.kt`
- **Description:** Directly injects `TrainingCycleRepository` and `UserProfileRepository` instead of through ViewModel. Mixes data-layer concerns into UI.
- **Impact:** Tight coupling to data layer; no ViewModel-level caching or testability.

#### UI-M007: EnhancedMainScreen NavHostController not saved across process death
- **File:** `shared/src/commonMain/.../presentation/screen/EnhancedMainScreen.kt`
- **Description:** `navController: NavHostController = rememberNavController()` as default parameter. Not saved/restored across process death.
- **Impact:** Navigation state lost on process death (Android).

#### UI-M008: ConnectionLogsViewModel uses singleton pattern bypassing Koin
- **File:** `shared/src/commonMain/.../presentation/viewmodel/ConnectionLogsViewModel.kt`
- **Description:** Directly accesses `ConnectionLogRepository.instance` instead of constructor injection. Bypasses Koin.
- **Impact:** Testing difficulty; hidden dependency.

#### UI-M009: Missing empty state handling in several screens
- **File:** Multiple screens (HomeScreen, AnalyticsScreen, SmartInsightsTab)
- **Description:** No empty states for new users - just blank sections without guidance.
- **Impact:** Poor first-run experience.

#### UI-M010: GamificationManager.consecutiveQualitySets not thread-safe
- **File:** `shared/src/commonMain/.../presentation/manager/GamificationManager.kt`
- **Description:** Plain `var` accessed from coroutine scope and any thread without synchronization.
- **Impact:** Rare inaccurate quality streak count.

### LOW Severity

#### UI-L001: ExerciseLibraryViewModel appears to be dead code
- **File:** `shared/src/commonMain/.../presentation/viewmodel/ExerciseLibraryViewModel.kt`
- **Description:** Not registered in Koin, not used by any screen.

#### UI-L002: Inconsistent theme management (no System option in UI)
- **File:** `App.kt`, `SettingsTab.kt`
- **Description:** `ThemeMode` has three values (SYSTEM, LIGHT, DARK) but the UI only offers a boolean toggle.

#### UI-L003: Duplicate UUID generation across ViewModels
- **File:** `CycleEditorViewModel`, `ExerciseConfigViewModel`
- **Description:** Each defines own `generateUUID()` using timestamp+random instead of the domain layer's `generateUUID()`.

#### UI-L004: Lambda instability in SettingsTab parameters
- **File:** `NavGraph.kt`
- **Description:** Inline lambdas created on every recomposition.

#### UI-L005: IntegrationsViewModel top-level logger
- **File:** `IntegrationsViewModel.kt`
- **Description:** Logger declared at file level, not class level.

#### UI-L006: Splash screen re-shows after EULA acceptance
- **File:** `App.kt`
- **Description:** 2500ms delay before main screen appears after accepting EULA.

## Partially Audited

- **ActiveSessionEngine.kt** (3297 lines) was only partially analyzed (first ~1271 lines). Additional issues may exist in the unread portion covering session persistence, rest timer logic, and Just Lift defaults. A follow-up deep-dive is recommended.

## Summary

| Severity | Count |
|----------|-------|
| Critical | 4 |
| High | 6 |
| Medium | 10 |
| Low | 6 |
| **Total** | **26** |

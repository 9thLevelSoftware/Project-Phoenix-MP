# UI Decoupler Analysis: WorkoutTab.kt Side-Effect Extraction

**File**: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`
**Lines**: 2841
**Date**: 2026-02-06

---

## Executive Summary

WorkoutTab.kt is already **cleaner than expected** from a side-effects perspective. The haptic/sound effects were previously extracted to a global `HapticFeedbackEffect` composable invoked in `EnhancedMainScreen` (line 354). The remaining side-effects in WorkoutTab are limited to **data-loading LaunchedEffects** and a **single VideoPlayer** instance. The bulk of the file's complexity is pure UI rendering (cards, dialogs, overlays) -- not side-effect management.

However, there **are** side-effects worth extracting, particularly from `CurrentExerciseCard` and `SetSummaryCard`, plus the VideoPlayer invocations scattered across both WorkoutTab and WorkoutHud. A `WorkoutEffectsHandler` composable would consolidate these.

---

## 1. Inventory of All Side-Effects

### 1.1 HapticFeedbackEffect (ALREADY EXTRACTED)

| Location | Status |
|----------|--------|
| `EnhancedMainScreen.kt:354` | Global invocation -- handles ALL haptic+sound events |
| `WorkoutTab.kt:168` | Comment: "HapticFeedbackEffect is now global in EnhancedMainScreen" |
| `ActiveWorkoutScreen.kt:155` | Comment: same note |

**Verdict**: No action needed. The haptic/sound pipeline is already cleanly separated:
- `MainViewModel.hapticEvents: SharedFlow<HapticEvent>` emits events
- `HapticFeedbackEffect` composable collects them at the root
- Platform implementations (Android: SoundPool+Vibrator, iOS: AVAudioPlayer+UIKit haptics)

### 1.2 VideoPlayer Instances (NEEDS EXTRACTION)

VideoPlayer is an `expect fun` composable with `actual` implementations:
- **Android**: ExoPlayer with Media3 (209 lines, creates ExoPlayer instance, DisposableEffect for lifecycle)
- **iOS**: AVPlayer in UIKitView (296 lines, LoopingPlayerView with NSNotificationCenter observers)

Current invocation sites in the workout flow:

| Location | File | Line | Context |
|----------|------|------|---------|
| `CurrentExerciseCard` | WorkoutTab.kt | 1466 | Inside Card, conditional on `enableVideoPlayback` |
| `ExerciseVideoPanel` | WorkoutHud.kt | 673 | HUD video panel during active workout |
| `ExerciseEditBottomSheet` | ExerciseEditBottomSheet.kt | 177 | Exercise editing |
| `RoutineOverviewScreen` | RoutineOverviewScreen.kt | 305 | Routine preview |
| `SetReadyScreen` | SetReadyScreen.kt | 279 | Pre-set screen |

**Issue**: Both `CurrentExerciseCard` (WorkoutTab.kt:1377) and `ExerciseVideoPanel` (WorkoutHud.kt:611) have their own `LaunchedEffect` to load exercise/video data from `ExerciseRepository`. This is duplicated logic.

### 1.3 Data-Loading LaunchedEffects

| Location | Line | Key | Action | Dependencies |
|----------|------|-----|--------|-------------|
| `CurrentExerciseCard` | 1377 | `currentExerciseIndex, exercise.id, selectedExerciseId` | Loads exercise entity + video from `exerciseRepository` | `exerciseRepository`, `currentExercise`, `workoutParameters` |
| `ExerciseVideoPanel` (WorkoutHud) | 611 | `currentExerciseIndex, exerciseId` | Loads video from `exerciseRepository` | `exerciseRepository`, `loadedRoutine`, `currentExerciseIndex` |
| `WorkoutSetupDialog` | 2062 | `workoutParameters.selectedExerciseId` | Loads selected exercise entity | `exerciseRepository`, `workoutParameters` |
| `SetSummaryCard` | 1513 | `summaryKey, autoplayEnabled, summaryCountdownSeconds` | Auto-continue countdown timer | `autoplayEnabled`, `summaryCountdownSeconds`, `onContinue` |

### 1.4 DisposableEffects

None in WorkoutTab.kt itself. The DisposableEffects live inside the platform-specific `HapticFeedbackEffect` and `VideoPlayer` implementations (SoundPool.release, ExoPlayer.release, AVPlayer cleanup).

### 1.5 Compose Side-Effects Summary

| Effect Type | Count in WorkoutTab | Purpose |
|-------------|-------------------|---------|
| `HapticFeedbackEffect` | 0 (import only) | Already global |
| `VideoPlayer` | 1 (in CurrentExerciseCard) | Exercise video preview |
| `LaunchedEffect` | 3 | Data loading (2) + auto-countdown (1) |
| `DisposableEffect` | 0 | None directly |
| `SideEffect` | 0 | None |

---

## 2. Current Structure Diagram

```
EnhancedMainScreen
  |
  +-- HapticFeedbackEffect(hapticEvents)     <-- GLOBAL side-effect (already extracted)
  |
  +-- NavGraph
       |
       +-- WorkoutTab(state, actions, exerciseRepository, hapticEvents)
            |
            +-- [Active + Connected] --> WorkoutHud(...)
            |    |
            |    +-- ExerciseVideoPanel(...)
            |    |    +-- LaunchedEffect: load video     <-- SIDE-EFFECT (data fetch)
            |    |    +-- VideoPlayer(videoUrl)           <-- SIDE-EFFECT (media)
            |    |
            |    +-- AnimatedRepCounter (pure UI)
            |    +-- CircularForceGauge (pure UI)
            |    +-- EnhancedCablePositionBar (pure UI)
            |
            +-- [Non-Active states] --> Column of Cards
            |    |
            |    +-- ConnectionCard (pure UI + local dialog state)
            |    +-- WorkoutSetupCard (pure UI)
            |    +-- CountdownCard (pure UI)
            |    +-- SetSummaryCard(...)
            |    |    +-- LaunchedEffect: auto-countdown  <-- SIDE-EFFECT (timer)
            |    |
            |    +-- RestTimerCard (pure UI)
            |    +-- CurrentExerciseCard(...)
            |    |    +-- LaunchedEffect: load exercise   <-- SIDE-EFFECT (data fetch)
            |    |    +-- VideoPlayer(videoUrl)            <-- SIDE-EFFECT (media)
            |    |
            |    +-- CompletedCard (pure UI)
            |    +-- ErrorCard (pure UI)
            |    +-- WorkoutPausedCard (pure UI)
            |
            +-- AutoStopOverlay (pure UI)
            +-- AutoStartOverlay (pure UI)
            +-- ExerciseNavigator (pure UI)
            +-- WorkoutSetupDialog(...)
                 +-- LaunchedEffect: load exercise       <-- SIDE-EFFECT (data fetch)
```

---

## 3. Proposed WorkoutEffectsHandler

### 3.1 Design Rationale

The haptic/sound effects are already cleanly separated. The remaining extractable side-effects are:
1. **Exercise/video data loading** (duplicated between CurrentExerciseCard and WorkoutHud)
2. **Auto-continue countdown** in SetSummaryCard

Rather than a monolithic `WorkoutEffectsHandler` that handles haptics+video+sound (which is already done globally), the better extraction is a **WorkoutDataEffectsHandler** that consolidates the data-loading side-effects and provides pre-loaded state to pure UI composables.

### 3.2 Interface

```kotlin
/**
 * Holds pre-loaded side-effect state for workout UI components.
 * This eliminates LaunchedEffects scattered across individual cards.
 */
data class WorkoutEffectsState(
    /** Current exercise entity loaded from repository */
    val currentExercise: Exercise? = null,
    /** Current exercise video entity */
    val currentVideo: ExerciseVideoEntity? = null,
    /** Whether exercise/video data is currently loading */
    val isLoadingExercise: Boolean = false,
    /** Auto-continue countdown value (-1 = inactive, 0 = fire, >0 = counting) */
    val autoCountdown: Int = -1
)

/**
 * Non-visual composable that manages all workout data side-effects.
 * Consolidates LaunchedEffects from CurrentExerciseCard, WorkoutHud, and SetSummaryCard.
 *
 * @param exerciseRepository Data source for exercise/video lookup
 * @param currentExerciseIndex Current position in routine
 * @param routineExercise Current exercise from loaded routine (nullable for Just Lift)
 * @param selectedExerciseId Fallback exercise ID from workout parameters
 * @param autoplayEnabled Whether auto-continue is enabled
 * @param summaryCountdownSeconds Duration for auto-continue countdown
 * @param workoutState Current workout state (used to detect SetSummary transitions)
 * @param onAutoCountdownComplete Called when auto-continue countdown reaches 0
 */
@Composable
fun WorkoutDataEffectsHandler(
    exerciseRepository: ExerciseRepository,
    currentExerciseIndex: Int,
    routineExercise: RoutineExercise?,
    selectedExerciseId: String?,
    autoplayEnabled: Boolean,
    summaryCountdownSeconds: Int,
    workoutState: WorkoutState,
    onAutoCountdownComplete: () -> Unit
): WorkoutEffectsState {
    // --- Exercise + Video Loading ---
    var exerciseEntity by remember(currentExerciseIndex) { mutableStateOf<Exercise?>(null) }
    var videoEntity by remember(currentExerciseIndex) { mutableStateOf<ExerciseVideoEntity?>(null) }
    var isLoading by remember(currentExerciseIndex) { mutableStateOf(true) }

    val exerciseId = routineExercise?.exercise?.id ?: selectedExerciseId

    LaunchedEffect(currentExerciseIndex, exerciseId) {
        isLoading = true
        exerciseEntity = null
        videoEntity = null
        if (exerciseId != null) {
            try {
                exerciseEntity = exerciseRepository.getExerciseById(exerciseId)
                val videos = exerciseRepository.getVideos(exerciseId)
                videoEntity = videos.firstOrNull()
            } catch (_: Exception) {
                // Data loading failed - entities stay null
            }
        }
        isLoading = false
    }

    // --- Auto-Continue Countdown ---
    val summaryKey = if (workoutState is WorkoutState.SetSummary) {
        "${workoutState.durationMs}_${workoutState.repCount}_${workoutState.totalVolumeKg}"
    } else null

    var autoCountdown by remember(summaryKey) {
        mutableStateOf(
            if (summaryKey != null && autoplayEnabled && summaryCountdownSeconds > 0)
                summaryCountdownSeconds
            else -1
        )
    }

    LaunchedEffect(summaryKey, autoplayEnabled, summaryCountdownSeconds) {
        if (summaryKey != null && autoplayEnabled && summaryCountdownSeconds > 0) {
            autoCountdown = summaryCountdownSeconds
            while (autoCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                autoCountdown--
            }
            if (autoCountdown == 0) {
                onAutoCountdownComplete()
            }
        }
    }

    return WorkoutEffectsState(
        currentExercise = exerciseEntity,
        currentVideo = videoEntity,
        isLoadingExercise = isLoading,
        autoCountdown = autoCountdown
    )
}
```

### 3.3 Where It Lives

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/
    WorkoutDataEffectsHandler.kt    <-- NEW FILE
```

This keeps it alongside `HapticFeedbackEffect.kt` and `VideoPlayer.kt` in the components package.

---

## 4. Before/After Code Examples

### 4.1 WorkoutTab Main Composable (Before)

```kotlin
// WorkoutTab.kt - current (abbreviated)
@Composable
fun WorkoutTab(
    // ...50+ parameters...
) {
    if (workoutState is WorkoutState.Active && connectionState is ConnectionState.Connected) {
        WorkoutHud(/* ...20+ params, including exerciseRepository... */)
        return
    }

    // ... non-active states: each card manages its own data loading
    when (workoutState) {
        is WorkoutState.SetSummary -> {
            SetSummaryCard(
                summary = workoutState,
                // SetSummaryCard has its own LaunchedEffect for countdown
                autoplayEnabled = autoplayEnabled,
                summaryCountdownSeconds = summaryCountdownSeconds,
                // ...
            )
        }
        // ...
    }
    // CurrentExerciseCard has its own LaunchedEffect for exercise/video loading
}
```

### 4.2 WorkoutTab Main Composable (After)

```kotlin
// WorkoutTab.kt - refactored (abbreviated)
@Composable
fun WorkoutTab(
    // ...same parameters, but CurrentExerciseCard no longer needs exerciseRepository...
) {
    // SINGLE source of truth for all workout data effects
    val effectsState = WorkoutDataEffectsHandler(
        exerciseRepository = exerciseRepository,
        currentExerciseIndex = currentExerciseIndex,
        routineExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex),
        selectedExerciseId = workoutParameters.selectedExerciseId,
        autoplayEnabled = autoplayEnabled,
        summaryCountdownSeconds = summaryCountdownSeconds,
        workoutState = workoutState,
        onAutoCountdownComplete = onProceedFromSummary
    )

    if (workoutState is WorkoutState.Active && connectionState is ConnectionState.Connected) {
        WorkoutHud(
            // Now receives pre-loaded video instead of exerciseRepository
            videoEntity = effectsState.currentVideo,
            isLoadingVideo = effectsState.isLoadingExercise,
            // ...other params unchanged
        )
        return
    }

    // ... non-active states: cards receive pre-loaded data
    when (workoutState) {
        is WorkoutState.SetSummary -> {
            SetSummaryCard(
                summary = workoutState,
                // Countdown is now managed externally
                autoCountdown = effectsState.autoCountdown,
                // No more internal LaunchedEffect
                // ...
            )
        }
        // ...
    }
    // CurrentExerciseCard receives pre-loaded exercise/video data
    // No more internal LaunchedEffect
}
```

### 4.3 CurrentExerciseCard (Before)

```kotlin
// WorkoutTab.kt:1354-1476
@Composable
fun CurrentExerciseCard(
    loadedRoutine: Routine?,
    currentExerciseIndex: Int,
    workoutParameters: WorkoutParameters,
    exerciseRepository: ExerciseRepository,  // <-- repository dependency in UI
    enableVideoPlayback: Boolean,
    formatWeight: (Float) -> String,
    kgToDisplay: (Float) -> Float,
    weightUnit: WeightUnit
) {
    val currentExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex)

    // SIDE-EFFECT: data loading
    var exerciseEntity by remember(currentExerciseIndex) { mutableStateOf<Exercise?>(null) }
    var videoEntity by remember(currentExerciseIndex) { mutableStateOf<ExerciseVideoEntity?>(null) }

    LaunchedEffect(currentExerciseIndex, currentExercise?.exercise?.id, workoutParameters.selectedExerciseId) {
        exerciseEntity = null
        videoEntity = null
        val exerciseId = currentExercise?.exercise?.id ?: workoutParameters.selectedExerciseId
        if (exerciseId != null) {
            exerciseEntity = exerciseRepository.getExerciseById(exerciseId)
            val videos = exerciseRepository.getVideos(exerciseId)
            videoEntity = videos.firstOrNull()
        }
    }

    // ... pure UI rendering using exerciseEntity and videoEntity ...
    if (enableVideoPlayback) {
        VideoPlayer(videoUrl = videoEntity?.videoUrl, ...)  // SIDE-EFFECT: media playback
    }
}
```

### 4.4 CurrentExerciseCard (After)

```kotlin
// WorkoutTab.kt - refactored
@Composable
fun CurrentExerciseCard(
    currentExercise: RoutineExercise?,
    exerciseEntity: Exercise?,           // pre-loaded by effects handler
    videoEntity: ExerciseVideoEntity?,    // pre-loaded by effects handler
    workoutParameters: WorkoutParameters,
    enableVideoPlayback: Boolean,
    formatWeight: (Float) -> String,
    kgToDisplay: (Float) -> Float,
    weightUnit: WeightUnit
) {
    // NO LaunchedEffect -- data is provided externally
    // Pure UI rendering only

    // ... same Card UI ...
    if (enableVideoPlayback) {
        VideoPlayer(videoUrl = videoEntity?.videoUrl, ...)  // VideoPlayer still here (it IS visual)
    }
}
```

**Note**: `VideoPlayer` stays inside the card because it has visual output (renders the video view). It's a visual composable, not a pure side-effect. The side-effect we extracted was the *data fetching* that feeds it.

---

## 5. Migration Steps

### Phase 1: Create WorkoutDataEffectsHandler (Low Risk)
1. Create `WorkoutDataEffectsHandler.kt` in `presentation/components/`
2. Define `WorkoutEffectsState` data class
3. Implement the handler composable with the consolidated LaunchedEffects
4. Unit test: verify state transitions for exercise loading and countdown

### Phase 2: Refactor CurrentExerciseCard (Medium Risk)
1. Remove `exerciseRepository` parameter from `CurrentExerciseCard`
2. Add `exerciseEntity: Exercise?` and `videoEntity: ExerciseVideoEntity?` parameters
3. Remove internal `LaunchedEffect` and `remember` state
4. Update call site in WorkoutTab to pass data from `WorkoutEffectsState`

### Phase 3: Refactor SetSummaryCard Countdown (Medium Risk)
1. Remove internal countdown `LaunchedEffect` from `SetSummaryCard`
2. Add `autoCountdown: Int` parameter (externally driven)
3. Remove `autoplayEnabled` and `summaryCountdownSeconds` parameters (no longer needed internally)
4. Update call site in WorkoutTab to pass `effectsState.autoCountdown`

### Phase 4: Refactor WorkoutHud ExerciseVideoPanel (Medium Risk)
1. Remove internal `LaunchedEffect` from `ExerciseVideoPanel`
2. Add `videoEntity: ExerciseVideoEntity?` and `isLoading: Boolean` parameters
3. Update `WorkoutHud` to receive and pass these from `WorkoutEffectsState`

### Phase 5: Cleanup
1. Remove unused imports (`ExerciseRepository` from cards that no longer use it)
2. Remove the `HapticFeedbackEffect` import from WorkoutTab (already unused, line 32)
3. Verify all VideoPlayer invocations still receive correct URLs

---

## 6. KMP Considerations

### Already Platform-Abstracted
- `HapticFeedbackEffect` -- `expect/actual` with Android (Vibrator+SoundPool) and iOS (UIKit+AVAudioPlayer) implementations. No changes needed.
- `VideoPlayer` -- `expect/actual` with Android (ExoPlayer/Media3) and iOS (AVPlayer/UIKitView) implementations. No changes needed.

### WorkoutDataEffectsHandler
- Lives entirely in `commonMain` -- no platform-specific code needed
- Uses only `ExerciseRepository` (common interface) and standard Compose effects
- `kotlinx.coroutines.delay` is KMP-safe

### Potential Gotchas
1. **VideoPlayer lifecycle**: On Android, ExoPlayer creates a `DisposableEffect` internally. If `WorkoutEffectsHandler` is composed at a higher level than the current VideoPlayer call site, the ExoPlayer instance will live longer. This is acceptable -- the ExoPlayer only loads when `videoUrl` is non-null.
2. **SetSummaryCard recomposition**: Moving the countdown state up means the parent recomposes on each tick. However, this is already the case (the card recomposes to show the countdown number). No performance regression.
3. **WorkoutSetupDialog**: The `LaunchedEffect` in `WorkoutSetupDialog` (line 2062) loads the selected exercise for the setup dialog -- this is a **different use case** from the workout data handler and should remain local. The dialog is modal and not always visible.

---

## 7. What NOT to Extract

### VideoPlayer Composable Itself
VideoPlayer has visual output (renders ExoPlayer/AVPlayer view). It is NOT a pure side-effect -- it's a visual component that happens to have internal side-effects (media loading, player lifecycle). It should stay where it renders.

### WorkoutSetupDialog LaunchedEffect
The dialog's exercise-loading LaunchedEffect (line 2062) serves the setup UI, not the active workout. It should remain local to the dialog.

### ConnectionCard Dialog State
`ConnectionCard` has a local `showDisconnectDialog` state (line 897). This is pure UI state management, not a side-effect.

### SetSummaryCard RPE State
The `loggedRpe` state in SetSummaryCard (line 1498) is local UI interaction state, not a side-effect.

---

## 8. Impact Assessment

| Metric | Before | After |
|--------|--------|-------|
| LaunchedEffects in WorkoutTab/Hud | 3 scattered | 1 centralized |
| Repository dependencies in UI cards | 2 cards | 0 cards |
| Duplicated video-loading logic | 2 locations | 1 location |
| Lines changed (estimated) | -- | ~150 lines refactored |
| New files | -- | 1 (WorkoutDataEffectsHandler.kt) |
| Risk level | -- | Medium (pure refactor, no behavior change) |

---

## 9. Relationship to Broader Refactoring

This extraction is a **prerequisite** for the larger MainViewModel decomposition:
- Once side-effects are centralized in `WorkoutDataEffectsHandler`, the ViewModel no longer needs to expose `ExerciseRepository` to individual cards
- The handler becomes the bridge between ViewModel state and UI components
- Future `WorkoutSessionManager` extraction (Task #3) can provide state that flows through this handler
- Pure UI cards become trivially testable (just pass data, no repository mocking needed)

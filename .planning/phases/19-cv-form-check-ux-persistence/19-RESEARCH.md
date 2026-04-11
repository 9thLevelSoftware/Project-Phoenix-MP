# Phase 19: CV Form Check UX & Persistence - Research

**Researched:** 2026-02-28
**Domain:** Real-time CV form warning UX, form score persistence, platform-conditional UI, audio cue integration
**Confidence:** HIGH

## Summary

Phase 19 bridges the existing CV infrastructure (Phase 14 domain logic + Phase 15 camera pipeline) to the user-facing workout experience. The core engine (`FormRulesEngine`), camera overlay (`FormCheckOverlay`), and tier gating (`FeatureGate.CV_FORM_CHECK`) all exist and are tested. What is missing is the **user-facing integration layer**: a toggle to enable/disable form checking, real-time warning display during workouts, audio cues on violations, a form score shown in the set summary, persistence of form scores to the database, and the iOS "coming soon" stub.

The codebase has strong precedent for every piece of this integration. The `WorkoutHud` already consumes biomechanics data and rep quality scores through the `WorkoutUiState` -> `WorkoutTab` -> `WorkoutHud` pipeline. The `HapticEvent` sealed class and `HapticFeedbackEffect` composable handle all audio/haptic feedback through the `SoundPool`/`MediaPlayer` system. The `SetSummary` data class already includes optional fields for quality and biomechanics summaries. The `isIosPlatform` expect/actual property exists specifically for the Phase 19 iOS "coming soon" guard.

The biggest integration concern is threading form assessments through the workout lifecycle without impacting the BLE metric pipeline. The existing `FormCheckOverlay.android.kt` already calls `FormRulesEngine.evaluate()` in the `PoseLandmarkerHelper` callback and invokes `onFormAssessment()` -- but nothing currently listens to those assessments. This phase needs to: (1) accumulate assessments during a set, (2) compute the score at set end, (3) persist the score, and (4) display real-time violations with audio cues.

**Primary recommendation:** Wire form check through the existing `WorkoutUiState` / `WorkoutCoordinator` / `ActiveSessionEngine` pipeline following the exact same pattern used for biomechanics and rep quality. Add a `formScore` column to `WorkoutSession` via migration 16 (next available), accumulate `FormAssessment` objects in a list during a set, compute the score via `FormRulesEngine.calculateFormScore()` at set completion, and persist it alongside the session. Add a `FORM_WARNING` event to `HapticEvent` for audio cues. Use `isIosPlatform` to guard the toggle with a "coming soon" message.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| CV-01 | User can enable "Form Check" toggle on Active Workout Screen (Phoenix+ tier) | `FeatureGate.CV_FORM_CHECK` exists at PHOENIX tier; `isIosPlatform` expect/actual exists for platform branching; `WorkoutUiState` can carry `isFormCheckEnabled` flag; toggle placement follows existing settings toggle patterns in `WorkoutHud` |
| CV-04 | Real-time form warnings display for exercise-specific joint angle violations (audio + visual) | `FormCheckOverlay.android.kt` already calls `FormRulesEngine.evaluate()` and invokes `onFormAssessment()`; `FormViolation` model has `severity`, `message`, `correctiveCue`; `HapticEvent` sealed class supports new audio variants; existing `SoundPool`/`MediaPlayer` system can play warning sounds |
| CV-05 | Form score (0-100) calculated per exercise from joint angle compliance | `FormRulesEngine.calculateFormScore(assessments: List<FormAssessment>): Int` already exists and is tested; scores from 0-100 with severity-weighted deduction algorithm; needs accumulation of assessments during set |
| CV-06 | Form assessment data (score, violations, joint angles) persisted locally per exercise | SQLDelight migration can add `formScore` column to `WorkoutSession`; follows same pattern as biomechanics summary columns added in migration 15; iOS `DriverFactory.ios.kt` needs `CURRENT_SCHEMA_VERSION` bump |
| CV-10 | iOS displays "Form Check coming soon" message when Form Check toggle is tapped | `isIosPlatform` expect/actual exists; `FormCheckOverlay.ios.kt` is a no-op stub; conditional UI in commonMain using `isIosPlatform` to show "coming soon" dialog/message |
</phase_requirements>

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin stdlib | 2.0.21 | Data classes, sealed classes, state flow | Already in project; all domain engines use it |
| Jetpack Compose | 1.7.1 (CMP) | Toggle UI, warning cards, form score display | Already used for all workout screens |
| SQLDelight | 2.0.2 | Form score persistence via migration | Already used for all database operations |
| Coroutines + Flow | 1.9.0 | StateFlow for form check state, assessment accumulation | Already used throughout the project |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| MediaPipe PoseLandmarker | (already integrated) | Pose estimation (Phase 15 infrastructure) | Already in androidMain; no changes needed |
| CameraX | (already integrated) | Camera pipeline (Phase 15 infrastructure) | Already in androidMain; no changes needed |
| Koin | 4.0.0 | DI for SubscriptionManager access | Already used; inject in composables via `koinInject()` |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Column on WorkoutSession for formScore | Separate FormAssessmentHistory table | Separate table is overkill for a single integer score per session; column on WorkoutSession matches biomechanics pattern (migration 15) |
| HapticEvent.FORM_WARNING for audio | Inline ToneGenerator in FormCheckOverlay | ToneGenerator bypasses the existing sound system (volume, DND, Fire OS workarounds); HapticEvent reuses tested infrastructure |
| Persisting full violation list (JSON) | Persisting score only | JSON column adds complexity for v1; score-only is sufficient per CV-06 ("form assessment data (score)"); full violation replay is a v0.6.0+ concern |

**Installation:** No new dependencies required. Zero `build.gradle.kts` changes.

## Architecture Patterns

### Recommended Project Structure

Files to create or modify:

```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── domain/model/
│   └── Models.kt                              # ADD HapticEvent.FORM_WARNING
├── presentation/screen/
│   ├── WorkoutUiState.kt                      # ADD isFormCheckEnabled: Boolean
│   ├── WorkoutHud.kt                          # ADD form check toggle + warning overlay
│   ├── WorkoutTab.kt                          # Thread isFormCheckEnabled through
│   └── ActiveWorkoutScreen.kt                 # Wire form check state from ViewModel
├── presentation/viewmodel/
│   └── MainViewModel.kt                       # ADD form check state management + assessment accumulation
├── presentation/manager/
│   └── ActiveSessionEngine.kt                 # ADD form score to session persistence
└── presentation/components/
    ├── FormCheckOverlay.kt                    # (expect - unchanged)
    ├── FormWarningBanner.kt                   # NEW: real-time violation display composable

shared/src/androidMain/kotlin/com/devil/phoenixproject/
├── presentation/components/
│   └── FormCheckOverlay.android.kt            # (unchanged - already calls onFormAssessment)
│   └── HapticFeedbackEffect.android.kt        # ADD FORM_WARNING sound handling

shared/src/iosMain/kotlin/com/devil/phoenixproject/
├── presentation/components/
│   └── FormCheckOverlay.ios.kt                # (unchanged - no-op stub)

shared/src/commonMain/sqldelight/.../database/
├── VitruvianDatabase.sq                       # ADD formScore column to WorkoutSession
└── migrations/
    └── 16.sqm                                 # ALTER TABLE WorkoutSession ADD COLUMN formScore

shared/src/iosMain/kotlin/com/devil/phoenixproject/data/local/
└── DriverFactory.ios.kt                       # BUMP CURRENT_SCHEMA_VERSION to 17
```

### Pattern 1: Form Check State in WorkoutUiState (Following HudPreset Pattern)

**What:** Add `isFormCheckEnabled: Boolean` to `WorkoutUiState`, threaded from `MainViewModel` through `ActiveWorkoutScreen` -> `WorkoutTab` -> `WorkoutHud`, exactly like `hudPreset` was added in Phase 18.

**When to use:** Always. This is the established pattern for adding UI state to the workout screen.

**Example:**
```kotlin
// WorkoutUiState.kt
data class WorkoutUiState(
    // ... existing fields ...
    val isFormCheckEnabled: Boolean = false,
    val latestFormViolations: List<FormViolation> = emptyList(),
    val latestFormScore: Int? = null  // null = no score computed yet
)
```

### Pattern 2: Assessment Accumulation in ActiveSessionEngine (Following Biomechanics Pattern)

**What:** Accumulate `FormAssessment` objects during a set in a mutable list, compute the score at set completion via `FormRulesEngine.calculateFormScore()`, and include it in the persisted session -- exactly like `BiomechanicsEngine` accumulates per-rep results.

**When to use:** For any per-frame data that needs to be summarized at set end.

**Example:**
```kotlin
// In WorkoutCoordinator or as a field in ActiveSessionEngine
val formAssessments = mutableListOf<FormAssessment>()

// During set (called from FormCheckOverlay callback):
fun onFormAssessment(assessment: FormAssessment) {
    formAssessments.add(assessment)
    // Emit latest violations for real-time display
    _latestFormViolations.value = assessment.violations
}

// At set completion:
val formScore = if (formAssessments.isNotEmpty()) {
    FormRulesEngine.calculateFormScore(formAssessments)
} else null
formAssessments.clear()
```

### Pattern 3: Tier Gating with isIosPlatform Guard (Following BOARD-06 Pattern)

**What:** Use `FeatureGate.isEnabled(Feature.CV_FORM_CHECK, tier)` to show/hide the toggle for tier gating, and `isIosPlatform` to show "coming soon" on iOS instead of activating the camera.

**When to use:** Anywhere the form check toggle is rendered.

**Example:**
```kotlin
// In WorkoutHud or toggle composable
val hasFormCheckAccess = FeatureGate.isEnabled(
    Feature.CV_FORM_CHECK, subscriptionTier
)
val isIos = isIosPlatform

when {
    !hasFormCheckAccess -> { /* Don't show toggle at all (FREE users) */ }
    isIos -> {
        // Show toggle but display "coming soon" dialog when tapped
        FormCheckToggle(
            enabled = false,
            onToggle = { showComingSoonDialog = true }
        )
    }
    else -> {
        // Android: Full form check functionality
        FormCheckToggle(
            enabled = isFormCheckEnabled,
            onToggle = { viewModel.toggleFormCheck() }
        )
    }
}
```

### Pattern 4: Audio Cues via HapticEvent (Following Existing Sound Pattern)

**What:** Add `FORM_WARNING` to the `HapticEvent` sealed class and load a warning sound in `HapticFeedbackEffect.android.kt`, exactly like other sound events. Emit the event when a CRITICAL or WARNING violation is detected.

**When to use:** For audio feedback on form violations during a set.

**Example:**
```kotlin
// In HapticEvent sealed class
data object FORM_WARNING : HapticEvent()

// In HapticFeedbackEffect.android.kt - load a warning sound
loadSoundByName(context, soundPool, "form_warning")?.let {
    put(HapticEvent.FORM_WARNING, it)
}
```

### Anti-Patterns to Avoid

- **Direct BLE interaction from CV pipeline:** FormRulesEngine output must NEVER feed into weight adjustment or machine control. This is enforced by CV-08 and validated by existing architectural tests.
- **Blocking BLE thread with CV processing:** The `PoseLandmarkerHelper` already runs on a dedicated `ExecutorService` with adaptive throttling (CV-09). Do NOT move any CV processing to the BLE thread.
- **Storing raw frame assessments in the database:** Persisting hundreds of per-frame assessment records per session would bloat the database. Store only the aggregated score (0-100) per session.
- **Playing audio on every frame with a violation:** Form violations can persist across many consecutive frames. Use a cooldown/debounce (e.g., 3-5 seconds between audio cues for the same violation type) to prevent audio spam.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Form score calculation | Custom scoring algorithm | `FormRulesEngine.calculateFormScore()` | Already exists, tested, severity-weighted |
| Audio/haptic feedback | Inline `ToneGenerator` or `AudioTrack` | `HapticEvent` + `HapticFeedbackEffect` | Handles SoundPool, MediaPlayer fallback, Fire OS workaround, volume routing |
| Tier gating logic | Custom subscription checks | `FeatureGate.isEnabled(Feature.CV_FORM_CHECK, tier)` | Already exists, tested, handles grace periods |
| Platform detection | Runtime `Build.VERSION` checks | `isIosPlatform` expect/actual | Already exists, KMP-correct |
| Camera permission | Custom permission flow | Existing `FormCheckOverlay.android.kt` rationale flow | Already handles BOARD-05 rationale display |

**Key insight:** Nearly everything needed for Phase 19 already exists as infrastructure from Phases 14-16. The work is integration, not creation. The danger is reimplementing things that already work.

## Common Pitfalls

### Pitfall 1: Audio Spam on Persistent Violations
**What goes wrong:** A form violation (e.g., excessive forward lean) persists across 30+ consecutive frames at 10 FPS. Without debouncing, the warning sound plays every 100ms, creating unbearable audio spam.
**Why it happens:** Each `FormAssessment` frame may contain the same violation type if the user hasn't corrected their form.
**How to avoid:** Implement a cooldown per violation type (3-5 seconds). Track the last emitted warning timestamp per `JointAngleType` and suppress repeat audio cues within the cooldown window. The visual warning can remain continuous.
**Warning signs:** Users reporting "annoying beeping" during sets. Test with a static pose that triggers violations.

### Pitfall 2: Form Score Not Reset Between Sets
**What goes wrong:** Assessment accumulation list is not cleared between sets, causing the form score for set 2 to include violations from set 1.
**Why it happens:** The `formAssessments` list persists across the set lifecycle.
**How to avoid:** Clear the accumulation list in the same place where `biomechanicsEngine.reset()` and `repQualityScorer.reset()` are called -- in `handleSetCompletion()` in `ActiveSessionEngine`.
**Warning signs:** Form score degrades over successive sets even with identical form.

### Pitfall 3: iOS DriverFactory Schema Version Mismatch
**What goes wrong:** Adding a `formScore` column via SQLDelight migration works on Android but iOS fails because `DriverFactory.ios.kt` has `CURRENT_SCHEMA_VERSION = 16` which triggers a database purge cycle.
**Why it happens:** iOS uses a manual schema management approach (described in Daem0n warning #155). The version constant must be bumped to match the new schema.
**How to avoid:** Always bump `CURRENT_SCHEMA_VERSION` in `DriverFactory.ios.kt` when adding SQLDelight migrations. This is documented in the project CLAUDE.md memory.
**Warning signs:** iOS database reset on first launch after update; loss of workout history.

### Pitfall 4: Toggle State Lost on Configuration Change
**What goes wrong:** Form check toggle resets to "off" when the screen is rotated or the activity is recreated.
**Why it happens:** Toggle state is stored in a local `remember` instead of in `MainViewModel`.
**How to avoid:** Store `isFormCheckEnabled` as a `MutableStateFlow` in `MainViewModel` and expose it via `StateFlow`. This follows the exact pattern used for `hudPreset`, `weightUnit`, and all other workout settings.
**Warning signs:** Users toggling form check on, rotating device, and finding it off again.

### Pitfall 5: FormCheckOverlay Shown When Toggle is Off
**What goes wrong:** The camera preview and skeleton overlay remain visible even after the user toggles form check off, because the `isEnabled` parameter change doesn't properly dispose the camera resources.
**Why it happens:** `FormCheckOverlay.android.kt` returns immediately if `!isEnabled`, but the `DisposableEffect` cleanup for `PoseLandmarkerHelper` may not fire if the composable was already in composition.
**How to avoid:** The existing `FormCheckOverlay` already handles this correctly -- it returns early if `!isEnabled`. The parent composable should conditionally compose/decompose the overlay based on the toggle state, which triggers proper `DisposableEffect` cleanup.
**Warning signs:** Camera light stays on after disabling form check.

## Code Examples

### Adding formScore Column (SQLDelight Migration)

```sql
-- migrations/16.sqm
-- Phase 19: Form Check score persistence (CV-06)
ALTER TABLE WorkoutSession ADD COLUMN formScore INTEGER;
```

And in `VitruvianDatabase.sq`, add `formScore` to the `WorkoutSession` table:
```sql
-- In WorkoutSession CREATE TABLE (add after existing biomechanics columns):
formScore INTEGER
```

### Form Warning Banner Composable

```kotlin
// FormWarningBanner.kt - displays current form violations
@Composable
fun FormWarningBanner(
    violations: List<FormViolation>,
    modifier: Modifier = Modifier
) {
    if (violations.isEmpty()) return

    // Show highest-severity violation
    val topViolation = violations.maxByOrNull { it.severity.ordinal } ?: return

    val backgroundColor = when (topViolation.severity) {
        FormViolationSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        FormViolationSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        FormViolationSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = topViolation.correctiveCue,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
```

### Form Check Toggle in WorkoutHud

```kotlin
// In WorkoutHud top bar area (alongside stop button):
if (hasFormCheckAccess && !isIosPlatform) {
    IconToggleButton(
        checked = isFormCheckEnabled,
        onCheckedChange = { onToggleFormCheck() }
    ) {
        Icon(
            imageVector = if (isFormCheckEnabled)
                Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = "Form Check"
        )
    }
}
```

### Assessment Accumulation in Workout Lifecycle

```kotlin
// In MainViewModel (or WorkoutCoordinator):
private val _formAssessments = mutableListOf<FormAssessment>()
private val _latestFormViolations = MutableStateFlow<List<FormViolation>>(emptyList())
private val _isFormCheckEnabled = MutableStateFlow(false)

fun onFormAssessment(assessment: FormAssessment) {
    _formAssessments.add(assessment)
    _latestFormViolations.value = assessment.violations

    // Emit warning audio with debounce
    val hasCriticalOrWarning = assessment.violations.any {
        it.severity == FormViolationSeverity.WARNING ||
        it.severity == FormViolationSeverity.CRITICAL
    }
    if (hasCriticalOrWarning && shouldEmitWarningAudio()) {
        _hapticEvents.tryEmit(HapticEvent.FORM_WARNING)
    }
}

// At set end (in ActiveSessionEngine.handleSetCompletion):
val formScore = if (_formAssessments.isNotEmpty()) {
    FormRulesEngine.calculateFormScore(_formAssessments)
} else null
_formAssessments.clear()
_latestFormViolations.value = emptyList()
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Separate FormScore table | Column on WorkoutSession | Phase 19 design decision | Simpler schema, matches biomechanics pattern |
| Raw video upload for analysis | On-device joint angle extraction | v0.5.0 Phase 15 | Privacy-first, no bandwidth cost |
| GPU delegate for MediaPipe | CPU delegate only | v0.5.0 Phase 15 | Stability > speed; GPU has documented crashes |

**Deprecated/outdated:**
- Phase 14/15 comments reference "Phase 16" for UI wiring -- this was renumbered to Phase 19 during v0.5.1 roadmap creation

## Open Questions

1. **Warning sound asset**
   - What we know: The HapticFeedbackEffect system loads sounds from `res/raw/` by name. All existing sounds are custom `.ogg`/`.mp3` files.
   - What's unclear: Does a `form_warning` sound file already exist in the assets? If not, what should it sound like?
   - Recommendation: Check `androidApp/src/main/res/raw/` for existing warning sounds. If none exists, use a short two-tone descending beep (distinct from rep beep). Can be generated or sourced later; implement with a fallback to `ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD` if the asset is missing.

2. **Form score display location**
   - What we know: CV-05 says "User can view a form score after completing an exercise". `SetSummary` is the natural place (like `qualitySummary` and `biomechanicsSummary`).
   - What's unclear: Should the form score also appear in the HistoryTab session detail view?
   - Recommendation: Add to `SetSummary` for immediate display. Persist in `WorkoutSession.formScore` so it is available in history. HistoryTab display can be a follow-up if needed, but the data will be queryable.

3. **Exercise type mapping**
   - What we know: `ExerciseFormType` has 5 entries (SQUAT, DEADLIFT_RDL, OVERHEAD_PRESS, CURL, ROW). The exercise library uses `Exercise.movement` and `Exercise.muscleGroup` for classification.
   - What's unclear: How does the currently selected exercise map to an `ExerciseFormType`? Not all exercises will have form rules.
   - Recommendation: Create a mapping function `Exercise.toFormType(): ExerciseFormType?` that maps based on movement pattern and muscle group. Return `null` for exercises without form rules (form check toggle is hidden/disabled for those exercises). This keeps the toggle contextual.

## Sources

### Primary (HIGH confidence)
- **Codebase analysis** - Direct inspection of 15+ source files across all layers (domain, presentation, data, platform)
- `FormRulesEngine.kt` - Existing engine with `evaluate()` and `calculateFormScore()` (Phase 14)
- `FormCheckOverlay.kt` / `.android.kt` / `.ios.kt` - Existing expect/actual camera overlay (Phase 15)
- `FeatureGate.kt` - `CV_FORM_CHECK` at PHOENIX tier (Phase 16)
- `HapticFeedbackEffect.android.kt` - Existing sound/haptic system with SoundPool + MediaPlayer
- `ActiveSessionEngine.kt` - Set completion lifecycle, biomechanics persistence pattern
- `WorkoutUiState.kt` - State holder pattern for workout UI
- `VitruvianDatabase.sq` + `migrations/15.sqm` - Schema evolution pattern
- `Platform.kt` / `isIosPlatform` - Platform detection for iOS guard

### Secondary (MEDIUM confidence)
- Phase 14 research (`14-RESEARCH.md`) - Biomechanics thresholds, exercise science sources
- Phase 18 implementation (HudPreset) - UI state threading pattern validation

### Tertiary (LOW confidence)
- Warning sound asset availability - Needs verification in `res/raw/`

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries already in use; zero new dependencies
- Architecture: HIGH - Every pattern directly mirrors existing implemented patterns (biomechanics, rep quality, HUD preset)
- Pitfalls: HIGH - Identified from direct codebase analysis and documented Daem0n warnings
- Persistence: HIGH - SQLDelight migration pattern proven across 15 previous migrations

**Research date:** 2026-02-28
**Valid until:** 2026-03-28 (30 days - stable domain, no external dependency changes expected)

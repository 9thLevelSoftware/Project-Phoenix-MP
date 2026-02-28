# Phase 20: Readiness Briefing - Research

**Researched:** 2026-02-28
**Domain:** Training load monitoring (ACWR), Compose UI cards, tier-gated feature rendering
**Confidence:** HIGH

## Summary

Phase 20 implements a local ACWR-based readiness heuristic that computes a readiness score (0-100) from existing WorkoutSession data and presents it as a dismissible pre-workout card. The domain is well-established sports science (Acute:Chronic Workload Ratio) with straightforward formulas. The codebase already has all the infrastructure needed: the SmartSuggestionsEngine pattern for pure computation engines, SmartSuggestionsRepository for session data access, FeatureGate.READINESS_BRIEFING for Elite tier gating, and AccessibilityColors with statusGreen/statusYellow/statusRed already reserved for this exact use case.

No database schema changes are required. The ACWR engine reads from existing WorkoutSession data (timestamp, weightPerCableKg, workingReps) using the same `selectSessionSummariesSince` query already used by SmartSuggestionsEngine. The UI is a single dismissible card shown on the ActiveWorkoutScreen before the first set begins (WorkoutState.Idle), gated behind Elite tier via `subscriptionManager.hasEliteAccess`.

**Primary recommendation:** Follow the SmartSuggestionsEngine pattern exactly -- pure stateless computation object + repository data bridge + composable card with tier gating. Place the readiness card in the ActiveWorkoutScreen/WorkoutTab Idle state section where WorkoutSetupCard currently renders.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BRIEF-01 | Local ACWR-based readiness heuristic computes readiness score (0-100) with data sufficiency guard | ACWR algorithm research (rolling average model), SmartSuggestionsEngine pattern for pure engine, selectSessionSummariesSince query for session data |
| BRIEF-02 | Pre-workout briefing card shows readiness with Green/Yellow/Red status before first set (Elite tier) | AccessibilityColors.statusGreen/Yellow/Red already wired, WorkoutTab Idle state rendering slot, SubscriptionManager.hasEliteAccess for gating |
| BRIEF-03 | Briefing is advisory only -- user can always proceed with workout | Dismissible card pattern (no navigation blocking), simple boolean state flag |
| BRIEF-04 | "Connect to Portal for full readiness model" upsell displayed | Text + link pattern used across other upsell features (RPG-04 deep link pattern) |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin | 2.0.21 | Language | Project standard |
| Compose Multiplatform | 1.7.1 | UI framework | Project standard |
| SQLDelight | 2.0.2 | Database queries | Project standard for data access |
| Koin | 4.0.0 | Dependency injection | Project standard |
| kotlinx-datetime | (bundled) | Timestamp arithmetic | Already used in SmartSuggestionsEngine |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| AccessibilityColors | (in-project) | Color-blind safe status colors | statusGreen/Yellow/Red for readiness card |
| SubscriptionManager | (in-project) | hasEliteAccess StateFlow | Tier gating the readiness card |
| SmartSuggestionsRepository | (in-project) | Session data access | Reuse getSessionSummariesSince() |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Rolling Average ACWR | EWMA ACWR | EWMA is more statistically sound but rolling average is simpler, adequate for a local heuristic, and matches the v0.6.0+ plan where the Portal uses the full Bannister FFM model |
| New ReadinessRepository | Reuse SmartSuggestionsRepository | SmartSuggestionsRepository already provides exactly the data we need; no new repo needed |

**Installation:**
No new dependencies required. Everything needed is already in the project.

## Architecture Patterns

### Recommended Project Structure
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── domain/premium/
│   └── ReadinessEngine.kt           # Pure ACWR computation (stateless object)
├── domain/model/
│   └── ReadinessModels.kt           # ReadinessScore, ReadinessStatus enum
├── presentation/components/
│   └── ReadinessBriefingCard.kt     # Composable card UI
└── presentation/screen/
    └── ActiveWorkoutScreen.kt       # Modified: add readiness card in Idle state
```

### Pattern 1: Pure Computation Engine (follow SmartSuggestionsEngine exactly)
**What:** Stateless object with pure functions that take session data and return computed results
**When to use:** Always for domain computation in this codebase
**Example:**
```kotlin
// Source: SmartSuggestionsEngine pattern (existing codebase)
object ReadinessEngine {
    private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L
    private const val TWENTY_EIGHT_DAYS_MS = 28 * 24 * 60 * 60 * 1000L
    private const val FOURTEEN_DAYS_MS = 14 * 24 * 60 * 60 * 1000L
    private const val MIN_SESSIONS_FOR_READINESS = 3
    private const val MIN_HISTORY_DAYS = 28

    fun computeReadiness(
        sessions: List<SessionSummary>,
        nowMs: Long
    ): ReadinessResult {
        // 1. Data sufficiency guard
        // 2. Compute acute (7-day) and chronic (28-day) volume
        // 3. Calculate ACWR ratio
        // 4. Map to 0-100 score and Green/Yellow/Red status
    }
}
```

### Pattern 2: Tier-Gated Composable Card (follow SmartInsightsTab pattern)
**What:** Check `hasEliteAccess` before rendering, show LockedFeatureOverlay if gated
**When to use:** Elite-only features
**Example:**
```kotlin
// Source: SmartInsightsTab.kt (existing codebase)
val subscriptionManager: SubscriptionManager = koinInject()
val hasEliteAccess by subscriptionManager.hasEliteAccess.collectAsState()

if (hasEliteAccess) {
    ReadinessBriefingCard(/* ... */)
}
// No card at all for non-Elite users (invisible, not locked overlay)
```

### Pattern 3: Dismissible Card State (local composable state)
**What:** Remember a dismissed flag that hides the card for the current session
**When to use:** Advisory-only UI that should never block the user
**Example:**
```kotlin
var readinessDismissed by remember { mutableStateOf(false) }

if (hasEliteAccess && !readinessDismissed && readinessResult != null) {
    ReadinessBriefingCard(
        result = readinessResult,
        onDismiss = { readinessDismissed = true },
        onPortalLink = { /* upsell click */ }
    )
}
```

### Anti-Patterns to Avoid
- **Blocking the workout:** The readiness card MUST be dismissible. Never navigate away or disable the start button based on readiness score. This is explicitly out of scope per REQUIREMENTS.md.
- **Complex ACWR models:** Do NOT implement EWMA or coupled/uncoupled models. Use simple rolling average. The Portal will handle the full Bannister FFM model (PORTAL-03).
- **New database table/schema:** ACWR computes from existing session data. No schema migration needed.
- **Persisting the readiness score:** The score is ephemeral -- computed fresh before each workout. No need to store it.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Session volume data | Raw SQL queries | SmartSuggestionsRepository.getSessionSummariesSince() | Already provides exactly the right data shape |
| Color-blind safe status colors | Hardcoded RGB values | AccessibilityColors.statusGreen/Yellow/Red | Already wired with deuteranopia-safe alternates |
| Tier gating | Manual tier checks | SubscriptionManager.hasEliteAccess | Existing pattern used by SmartInsightsTab |
| Timestamp arithmetic | Manual calculations | Constants like SEVEN_DAYS_MS from SmartSuggestionsEngine | Proven, testable values |

**Key insight:** Nearly all infrastructure for this feature already exists. The readiness briefing is primarily a new pure computation function + a new composable card, with minimal wiring.

## Common Pitfalls

### Pitfall 1: Insufficient Data Guard Missing
**What goes wrong:** Users with very few sessions get a misleading readiness score because the ACWR chronic average is based on 1-2 sessions
**Why it happens:** ACWR is meaningless without enough chronic data to establish a baseline
**How to avoid:** Require minimum 3 sessions in the past 14 days AND 28+ days of training history. Return `ReadinessResult.InsufficientData` if thresholds not met (BRIEF-01 requirement).
**Warning signs:** Score of exactly 100 or 0 for nearly all users during testing

### Pitfall 2: Zero Chronic Load Division
**What goes wrong:** Division by zero when chronic workload is 0 (new user or long break)
**Why it happens:** ACWR = acute / chronic; if chronic is 0, you crash or get Infinity
**How to avoid:** Return InsufficientData when chronic load is 0. Never divide without checking denominator.
**Warning signs:** NaN or Infinity in readiness score

### Pitfall 3: Readiness Card Shown After First Set
**What goes wrong:** Card appears between sets or after the workout starts
**Why it happens:** Card placement keyed to wrong workout state
**How to avoid:** Only show in `WorkoutState.Idle` before the first set begins. Once workout state transitions to Countdown/Active, card should never reappear. Use a `readinessDismissed` flag that stays true for the session.
**Warning signs:** Card flickering during workout, appearing at SetSummary

### Pitfall 4: Wrong Volume Metric for Resistance Training
**What goes wrong:** Using session count as "training load" instead of volume
**Why it happens:** ACWR in running uses distance/time; resistance training needs volume (sets x reps x weight)
**How to avoid:** Compute daily volume as `sum(weightPerCableKg * 2 * workingReps)` per day, matching SmartSuggestionsEngine's totalKg formula. Then use 7-day and 28-day rolling averages of daily volume.
**Warning signs:** Score not reflecting actual training intensity changes

### Pitfall 5: Color-Blind Accessibility on Readiness Card
**What goes wrong:** Green/Yellow/Red status only communicated through color
**Why it happens:** WCAG requirement forgotten for new UI elements
**How to avoid:** Use AccessibilityTheme.colors for colors AND include text labels (e.g., "Ready", "Caution", "Overreaching") AND icons. Phase 17 BOARD-02 requirement applies to all color-coded indicators.
**Warning signs:** No text label accompanying the colored status indicator

## Code Examples

### ACWR Computation (Rolling Average Model)
```kotlin
// Source: Sports science standard, adapted for this codebase's SessionSummary model
object ReadinessEngine {
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    private const val TWENTY_EIGHT_DAYS_MS = 28L * 24 * 60 * 60 * 1000
    private const val FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_RECENT_SESSIONS = 3

    fun computeReadiness(sessions: List<SessionSummary>, nowMs: Long): ReadinessResult {
        // Data sufficiency: need sessions spanning 28+ days
        val oldestSession = sessions.minByOrNull { it.timestamp } ?: return ReadinessResult.InsufficientData
        val historyDays = (nowMs - oldestSession.timestamp) / ONE_DAY_MS
        if (historyDays < 28) return ReadinessResult.InsufficientData

        // Need 3+ sessions in last 14 days for recency guard
        val fourteenDaysAgo = nowMs - FOURTEEN_DAYS_MS
        val recentCount = sessions.count { it.timestamp > fourteenDaysAgo }
        if (recentCount < MIN_RECENT_SESSIONS) return ReadinessResult.InsufficientData

        // Compute acute load (last 7 days volume)
        val sevenDaysAgo = nowMs - SEVEN_DAYS_MS
        val acuteVolume = sessions
            .filter { it.timestamp > sevenDaysAgo }
            .sumOf { (it.weightPerCableKg * 2 * it.workingReps).toDouble() }
            .toFloat()

        // Compute chronic load (28-day weekly average volume)
        val twentyEightDaysAgo = nowMs - TWENTY_EIGHT_DAYS_MS
        val chronicSessions = sessions.filter { it.timestamp > twentyEightDaysAgo }
        val chronicTotalVolume = chronicSessions
            .sumOf { (it.weightPerCableKg * 2 * it.workingReps).toDouble() }
            .toFloat()
        val chronicWeeklyAvg = chronicTotalVolume / 4f  // 4 weeks

        // Guard: zero chronic load
        if (chronicWeeklyAvg <= 0f) return ReadinessResult.InsufficientData

        // ACWR ratio
        val acwr = acuteVolume / chronicWeeklyAvg

        // Map ACWR to 0-100 score
        // Sweet spot: 0.8-1.3 = high readiness
        // Under-training: < 0.8 = moderate readiness (detraining risk)
        // Overreaching: > 1.3 = lower readiness (injury/fatigue risk)
        // Danger zone: > 1.5 = low readiness
        val score = mapAcwrToScore(acwr)
        val status = when {
            score >= 70 -> ReadinessStatus.GREEN   // Ready to train normally
            score >= 40 -> ReadinessStatus.YELLOW  // Train with caution
            else -> ReadinessStatus.RED            // Consider lighter session
        }

        return ReadinessResult.Ready(
            score = score,
            status = status,
            acwr = acwr,
            acuteVolumeKg = acuteVolume,
            chronicWeeklyAvgKg = chronicWeeklyAvg
        )
    }

    // Maps ACWR ratio to 0-100 readiness score
    // Peak readiness at ACWR ~1.0, decreasing for both under- and over-training
    internal fun mapAcwrToScore(acwr: Float): Int {
        val score = when {
            acwr < 0.5f -> 30   // Significant under-training
            acwr < 0.8f -> (30 + (acwr - 0.5f) / 0.3f * 40).toInt()  // 30-70 range
            acwr <= 1.3f -> (70 + (1f - kotlin.math.abs(acwr - 1.0f) / 0.3f) * 30).toInt()  // 70-100 sweet spot
            acwr <= 1.5f -> (40 + (1.5f - acwr) / 0.2f * 30).toInt()  // 40-70 overreaching
            else -> (40 * (1f - ((acwr - 1.5f) / 0.5f).coerceAtMost(1f))).toInt()  // 0-40 danger
        }
        return score.coerceIn(0, 100)
    }
}
```

### Readiness Data Models
```kotlin
// Source: Project's existing domain model patterns
enum class ReadinessStatus { GREEN, YELLOW, RED }

sealed class ReadinessResult {
    object InsufficientData : ReadinessResult()
    data class Ready(
        val score: Int,              // 0-100
        val status: ReadinessStatus, // GREEN/YELLOW/RED
        val acwr: Float,             // Raw ACWR ratio for display
        val acuteVolumeKg: Float,    // 7-day total volume
        val chronicWeeklyAvgKg: Float // 28-day weekly average
    ) : ReadinessResult()
}
```

### Readiness Briefing Card (Composable)
```kotlin
// Source: Project's card patterns (SetSummaryCard, SmartInsightsTab)
@Composable
fun ReadinessBriefingCard(
    result: ReadinessResult.Ready,
    onDismiss: () -> Unit,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AccessibilityTheme.colors
    val statusColor = when (result.status) {
        ReadinessStatus.GREEN -> colors.statusGreen
        ReadinessStatus.YELLOW -> colors.statusYellow
        ReadinessStatus.RED -> colors.statusRed
    }
    val statusLabel = when (result.status) {
        ReadinessStatus.GREEN -> "Ready"
        ReadinessStatus.YELLOW -> "Caution"
        ReadinessStatus.RED -> "Overreaching"
    }
    val statusIcon = when (result.status) {
        ReadinessStatus.GREEN -> Icons.Default.CheckCircle
        ReadinessStatus.YELLOW -> Icons.Default.Warning
        ReadinessStatus.RED -> Icons.Default.Error
    }
    // Card with dismiss button, score, status icon + label, and Portal upsell link
}
```

### Placing the Card in ActiveWorkoutScreen
```kotlin
// In ActiveWorkoutScreen, alongside existing WorkoutTab setup:
// Readiness data is computed in a LaunchedEffect and shown before first set

var readinessDismissed by remember { mutableStateOf(false) }
var readinessResult by remember { mutableStateOf<ReadinessResult?>(null) }

// Compute once when screen opens (Elite users only)
if (hasEliteAccess) {
    LaunchedEffect(Unit) {
        val summaries = smartSuggestionsRepo.getSessionSummariesSince(
            nowMs - ReadinessEngine.TWENTY_EIGHT_DAYS_MS
        )
        readinessResult = ReadinessEngine.computeReadiness(summaries, nowMs)
    }
}

// Show card in Idle state, before workout starts
// The WorkoutTab's Idle branch is where to inject this
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Simple session count | Volume-based ACWR | Standard since ~2016 | Much better injury prediction |
| Coupled ACWR | Uncoupled/EWMA ACWR | ~2017-2018 | Better accuracy, but more complex |
| Rolling Average ACWR | EWMA ACWR | ~2018 | Academic consensus shifted, but RA is still practical for local heuristics |

**Why Rolling Average is appropriate here:**
- The app explicitly defers the full model (Bannister FFM + HRV/sleep) to Portal (PORTAL-03)
- Rolling Average is deterministic, easy to test, and "good enough" for a local advisory
- The readiness card already says "Connect to Portal for full readiness model" -- this is intentionally a simplified version

## Open Questions

1. **Where exactly should the readiness card render?**
   - What we know: Success criteria says "before their first set." The WorkoutTab has a `WorkoutState.Idle` branch where `WorkoutSetupCard` renders.
   - What's unclear: Should the card appear on the ActiveWorkoutScreen specifically, or on the SetReadyScreen (for routines), or on the SingleExerciseScreen (for single exercises)?
   - Recommendation: Show it on the ActiveWorkoutScreen in the `WorkoutState.Idle` branch. This is the unified entry point for all workout types. For routines, the first SetReadyScreen visit is also a candidate but is more invasive. The simplest approach is a dialog/overlay on ActiveWorkoutScreen that auto-shows once then dismisses.

2. **Should the InsufficientData message be shown or should the card just not appear?**
   - What we know: Success criteria #2 says "sees an Insufficient Data message"
   - Recommendation: Show a subtle card saying "Insufficient Data -- Train for 28+ days to enable readiness tracking" so Elite users know the feature exists and what they need to unlock its data.

## Sources

### Primary (HIGH confidence)
- SmartSuggestionsEngine.kt -- existing pure computation engine pattern with ACWR-like time windows
- SmartSuggestionsRepository.kt -- session data access layer, getSessionSummariesSince()
- AccessibilityColors.kt -- statusGreen/statusYellow/statusRed already reserved for readiness card
- FeatureGate.kt -- READINESS_BRIEFING already in Elite tier
- SubscriptionManager.kt -- hasEliteAccess StateFlow pattern
- SmartInsightsTab.kt -- Elite-gated composable pattern
- WorkoutTab.kt -- WorkoutState.Idle rendering slot
- ActiveWorkoutScreen.kt -- Screen-level state management pattern

### Secondary (MEDIUM confidence)
- [Science for Sport ACWR](https://www.scienceforsport.com/acutechronic-workload-ratio/) -- ACWR calculation methods, sweet spot zones
- [CRAN ACWR Package](https://cran.r-project.org/web/packages/ACWR/ACWR.pdf) -- Rolling average, EWMA, coupled/uncoupled formulas
- [PMC: ACWR Systematic Review](https://pmc.ncbi.nlm.nih.gov/articles/PMC12487117/) -- Evidence for ACWR in injury prediction

### Tertiary (LOW confidence)
- None -- all findings verified against codebase or official sports science literature

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries already in the project, no new dependencies
- Architecture: HIGH - Exact pattern (SmartSuggestionsEngine) already exists to follow
- ACWR algorithm: HIGH - Well-established sports science, simple rolling average suitable for local heuristic
- UI placement: MEDIUM - Multiple valid locations; recommended ActiveWorkoutScreen Idle state but may need planner judgment
- Pitfalls: HIGH - Based on direct codebase analysis (division by zero, data sufficiency, color accessibility)

**Research date:** 2026-02-28
**Valid until:** 2026-03-28 (stable domain, no fast-moving dependencies)

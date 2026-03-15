# Phase 22: Ghost Racing - Research

**Researched:** 2026-02-28
**Domain:** Real-time workout comparison engine (ghost session matching, rep-indexed position sync, velocity delta computation, Compose overlay UI)
**Confidence:** HIGH

## Summary

Phase 22 adds a "ghost racing" feature where users race against their personal best session during a set. The ghost session is pre-loaded into memory at workout start (no DB reads during an active set), and two vertical progress bars show current vs. ghost cable position synchronized by rep index (not wall-clock time). After each rep completes, the user sees an AHEAD/BEHIND verdict based on velocity comparison. At set end, a summary shows total velocity delta vs. the ghost.

The key architectural insight is that this feature touches three layers: (1) a pure stateless `GhostRacingEngine` in `domain/premium/` for matching and velocity comparison logic, (2) ghost state fields in `WorkoutCoordinator` for the shared state bus, and (3) a `GhostRacingOverlay` composable rendered conditionally on the ExecutionPage within the existing `WorkoutHud` pager overlay zone. The ghost session data source is the existing `RepBiomechanics` table (per-rep MCV already stored via GATE-04 unconditional capture), combined with `MetricSample` position data for the position progress bars. The critical design decision already locked in STATE.md is: sync on rep index, not wall-clock time; pre-load ghost session to memory.

**Primary recommendation:** Follow the ReadinessEngine/RpgAttributeEngine pattern for the computation engine (pure stateless object, TDD). Wire ghost state through WorkoutCoordinator. Render the overlay in WorkoutHud's Box overlay zone (alongside FormCheckOverlay, BalanceBar, cable position bars). Add a new SQL query for "best session by exerciseId + mode" and pre-load RepBiomechanics + MetricSamples at workout start. Gate with `FeatureGate.GHOST_RACING` (Phoenix+ tier).

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| GHOST-01 | User can race against their best matching local session during a set (Phoenix+ tier) | GhostRacingEngine.findBestSession() matches by exerciseId + workoutMode + weight range; pre-loads RepBiomechanics data at workout start; FeatureGate.GHOST_RACING already exists in Phoenix tier set |
| GHOST-02 | Two vertical progress bars show current vs. ghost cable position in real-time | GhostRacingOverlay composable with two vertical bars; position data from current MetricSample vs. ghost MetricSample at matching rep index; rendered in WorkoutHud overlay zone |
| GHOST-03 | Per-rep AHEAD/BEHIND verdict displayed based on velocity comparison | GhostRacingEngine.compareRep() compares current rep MCV against ghost rep MCV from RepBiomechanics; verdict emitted via StateFlow on WorkoutCoordinator |
| GHOST-04 | End-of-set summary shows total velocity delta vs. ghost | GhostRacingEngine.computeSetDelta() aggregates per-rep deltas; GhostSetSummary added to WorkoutState.SetSummary; rendered in SetSummaryCard |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| SQLDelight | 2.0.2 | Query ghost sessions + RepBiomechanics + MetricSamples | Already used for all persistence |
| Compose Multiplatform | 1.7.1 | GhostRacingOverlay composable, progress bars | Project's UI framework |
| Koin | 4.0.0 | DI for repository injection in ActiveSessionEngine | Project's DI framework |
| kotlin.test | (bundled) | TDD for GhostRacingEngine | Used for ReadinessEngineTest, RpgAttributeEngineTest |
| Coroutines + Flow | 1.9.0 | StateFlow for ghost state in WorkoutCoordinator | Already wired throughout |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Material3 | (via Compose) | LinearProgressIndicator, Card, AccessibilityTheme | For progress bar styling |
| AccessibilityTheme | (project) | WCAG-safe colors for AHEAD/BEHIND indicators | Required per Phase 17 mandate |

No new dependencies needed. Everything required is already in the project.

## Architecture Patterns

### Recommended Project Structure
```
shared/src/
  commonMain/kotlin/com/devil/phoenixproject/
    domain/
      model/
        GhostModels.kt           # GhostSession, GhostRepComparison, GhostSetSummary, GhostVerdict
      premium/
        GhostRacingEngine.kt     # Pure stateless computation object (like ReadinessEngine)
    presentation/
      manager/
        WorkoutCoordinator.kt    # Add ghost state fields (ghostSession, ghostRepResults, latestGhostVerdict)
        ActiveSessionEngine.kt   # Wire ghost loading at workout start, ghost comparison at rep completion
      components/
        GhostRacingOverlay.kt    # Two vertical progress bars + AHEAD/BEHIND label
      screen/
        WorkoutHud.kt            # Render GhostRacingOverlay in overlay zone (conditionally)
        SetSummaryCard.kt        # Render ghost velocity delta in set summary
  commonTest/kotlin/com/devil/phoenixproject/
    domain/premium/
      GhostRacingEngineTest.kt   # TDD tests for matching + comparison logic
```

### Pattern 1: Stateless Computation Engine
**What:** Pure `object` with no DB/DI dependencies, pure functions taking data in and returning results.
**When to use:** For all ghost matching and velocity comparison logic.
**Example:**
```kotlin
// Source: Follows ReadinessEngine.kt pattern exactly
object GhostRacingEngine {
    fun findBestSession(
        sessions: List<GhostSessionCandidate>,
        exerciseId: String,
        mode: String,
        weightPerCableKg: Float,
        weightToleranceKg: Float = 5f
    ): GhostSessionCandidate?

    fun compareRep(
        currentMcvMmS: Float,
        ghostMcvMmS: Float
    ): GhostVerdict  // AHEAD, BEHIND, or TIED

    fun computeSetDelta(
        comparisons: List<GhostRepComparison>
    ): GhostSetSummary
}
```

### Pattern 2: WorkoutCoordinator State Bus
**What:** Ghost state fields stored in WorkoutCoordinator alongside existing biomechanics/form check state.
**When to use:** For all ghost racing runtime state that needs to be visible across ActiveSessionEngine and UI.
**Example:**
```kotlin
// Source: Follows biomechanicsEngine/formAssessments pattern in WorkoutCoordinator.kt
// Add to WorkoutCoordinator:
internal val _ghostSession = MutableStateFlow<GhostSession?>(null)
val ghostSession: StateFlow<GhostSession?> = _ghostSession.asStateFlow()

internal val _latestGhostVerdict = MutableStateFlow<GhostRepComparison?>(null)
val latestGhostVerdict: StateFlow<GhostRepComparison?> = _latestGhostVerdict.asStateFlow()

internal val ghostRepComparisons = mutableListOf<GhostRepComparison>()
```

### Pattern 3: Overlay in WorkoutHud Box Zone
**What:** Ghost racing UI renders as a conditional overlay in the WorkoutHud Box (same zone as FormCheckOverlay, BalanceBar, cable position bars).
**When to use:** For the dual progress bars and AHEAD/BEHIND verdict.
**Example:**
```kotlin
// Source: Follows FormCheckOverlay/FormWarningBanner placement in WorkoutHud.kt lines 345-367
// In WorkoutHud's Box block after cable position bars:
if (ghostSession != null && hasGhostAccess) {
    GhostRacingOverlay(
        currentPosition = metric?.let { maxOf(it.positionA, it.positionB) } ?: 0f,
        ghostPosition = ghostSession.getPositionForRep(repCount.workingReps),
        verdict = latestGhostVerdict,
        modifier = Modifier
            .align(Alignment.CenterStart) // or custom positioning
            .padding(start = 32.dp)
    )
}
```

### Pattern 4: Pre-Load at Workout Start
**What:** Ghost session data loaded from DB into memory when workout starts (in ActiveSessionEngine.startWorkout), NOT during active set.
**When to use:** Critical for meeting the "no DB reads during active set" success criterion.
**Example:**
```kotlin
// Source: Follows ReadinessEngine pattern in ActiveWorkoutScreen lines 106-113
// In ActiveSessionEngine.startWorkout(), after workout state is set:
if (hasGhostAccess) {
    val exerciseId = params.selectedExerciseId
    if (exerciseId != null) {
        val ghostData = workoutRepository.findBestGhostSession(exerciseId, params.programMode.name, params.weightPerCableKg)
        if (ghostData != null) {
            val repBiomechanics = biomechanicsRepository.getRepBiomechanics(ghostData.id)
            coordinator._ghostSession.value = GhostSession(session = ghostData, repResults = repBiomechanics)
        }
    }
}
```

### Anti-Patterns to Avoid
- **DB reads during active set:** The ghost session MUST be pre-loaded into memory. Any `suspend fun` call to DB during rep processing would cause lag in the BLE metric pipeline.
- **Wall-clock time sync:** Ghost comparison must use rep index, not timestamps. Different sets have different tempos; wall-clock sync would be meaningless.
- **Separate HUD pager page:** Success criterion explicitly states "conditional element on ExecutionPage, NOT a separate pager page." The ghost racing UI overlays the ExecutionPage.
- **Modifying EnhancedCablePositionBar:** Don't repurpose the existing peripheral vision bars. Ghost progress bars are a separate, independent visual element showing current vs. ghost position comparison.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Per-rep velocity data | Custom velocity calculation | Existing RepBiomechanics.mcvMmS from BiomechanicsEngine | Already computed and stored per-rep via GATE-04 unconditional capture |
| Best session matching | Complex ranking algorithm | Simple query: exerciseId + mode + weight range, ORDER BY avgMcvMmS DESC | Over-engineering; the "best" session is the one with highest average MCV |
| Position data for progress bars | Custom position tracking | Existing MetricSample position + repBoundaryTimestamps segmentation | Already captured at ~50Hz during workout |
| Tier gating | Custom access check | FeatureGate.isEnabled(Feature.GHOST_RACING, tier) | FeatureGate.GHOST_RACING already exists in Phoenix tier set |
| WCAG color compliance | Custom color handling | AccessibilityTheme.colors.success/error for AHEAD/BEHIND | Phase 17 mandate; all new composables must use AccessibilityTheme |

**Key insight:** The hardest part of ghost racing -- collecting per-rep velocity and position data -- is already solved by the GATE-04 unconditional data capture pipeline (BiomechanicsEngine, MetricSample, RepBiomechanics). This phase is primarily about reading that data back and displaying a comparison overlay.

## Common Pitfalls

### Pitfall 1: DB Access During Active Set
**What goes wrong:** Ghost data is queried from the database during rep processing, causing latency spikes in the BLE metric pipeline.
**Why it happens:** Temptation to lazily load ghost data when the first rep completes.
**How to avoid:** Pre-load ghost session (RepBiomechanics + MetricSamples) into WorkoutCoordinator at workout start in `ActiveSessionEngine.startWorkout()`, before the metric collection flow begins.
**Warning signs:** Any `suspend` call to a repository inside `handleRepEvent` or the metric collection loop.

### Pitfall 2: Rep Index Off-By-One
**What goes wrong:** Ghost rep 1 compared against current rep 2 (or vice versa), causing all verdicts to be wrong.
**Why it happens:** RepBiomechanics.repNumber is 1-based (from `repCounter.getRepCount().totalReps` which starts counting at 1), but list indices are 0-based.
**How to avoid:** Use `repNumber - 1` when indexing into the ghost rep list. Add explicit test cases for rep 1 (first rep of set) boundary.
**Warning signs:** Ghost comparison always shows BEHIND for first rep.

### Pitfall 3: No Matching Ghost Session
**What goes wrong:** User has no previous session for this exercise/mode/weight combination; ghost overlay renders with empty/null data causing crash or visual glitch.
**Why it happens:** First-time exercise, different weight, or Just Lift mode (no exerciseId).
**How to avoid:** `GhostSession?` must be nullable throughout. GhostRacingOverlay should not render when ghostSession is null. Just Lift mode (`exerciseId == null`) should skip ghost loading entirely.
**Warning signs:** NullPointerException in ghost overlay, or overlay rendering with "0" position bars.

### Pitfall 4: Ghost Session Has Fewer Reps Than Current Set
**What goes wrong:** Current set exceeds ghost set's rep count; no ghost data to compare for excess reps.
**Why it happens:** User performs more reps than their best session (which is actually the goal of ghost racing).
**How to avoid:** When `currentRepNumber > ghostRepResults.size`, show a "NEW TERRITORY" or "BEYOND GHOST" indicator instead of AHEAD/BEHIND. This is a positive outcome -- the user is exceeding their previous best.
**Warning signs:** ArrayIndexOutOfBoundsException when accessing ghost rep list.

### Pitfall 5: Weight Mismatch Ghost Session
**What goes wrong:** Ghost session is from a significantly different weight, making velocity comparison meaningless.
**Why it happens:** No weight tolerance filter on ghost session query.
**How to avoid:** Filter ghost candidates to within +/- 5kg of current weight per cable. If no session within tolerance exists, don't show ghost overlay. Velocity comparisons are only meaningful at similar loads.
**Warning signs:** AHEAD verdict despite clearly slower reps, or BEHIND verdict when user is actually performing better.

### Pitfall 6: Ghost Overlay Obstructing Execution Page
**What goes wrong:** Ghost progress bars overlap with the rep counter, cable position bars, or form check overlay, making the HUD unreadable.
**Why it happens:** Too many overlays in the Box zone without spatial planning.
**How to avoid:** Position ghost bars at a specific location that doesn't conflict: inset from the existing cable position bars (which are at `CenterStart` and `CenterEnd`). Use narrow bar widths (16-20dp). Consider opacity to prevent obscuring the rep counter.
**Warning signs:** User complaints about cluttered HUD during workouts.

## Code Examples

### Domain Model Types
```kotlin
// GhostModels.kt - New file in domain/model/
package com.devil.phoenixproject.domain.model

/**
 * A pre-loaded ghost session for real-time comparison.
 * All data is in memory -- no DB reads during active set.
 */
data class GhostSession(
    val sessionId: String,
    val exerciseName: String,
    val weightPerCableKg: Float,
    val workingReps: Int,
    val avgMcvMmS: Float,
    /** Per-rep MCV values, indexed by (repNumber - 1) */
    val repVelocities: List<Float>,
    /** Per-rep peak position (for progress bar), indexed by (repNumber - 1) */
    val repPeakPositions: List<Float>
)

enum class GhostVerdict {
    AHEAD,   // Current rep faster than ghost
    BEHIND,  // Current rep slower than ghost
    TIED,    // Within tolerance (~5% MCV difference)
    BEYOND   // Current rep exceeds ghost rep count
}

data class GhostRepComparison(
    val repNumber: Int,
    val currentMcvMmS: Float,
    val ghostMcvMmS: Float,
    val deltaMcvMmS: Float,  // positive = faster than ghost
    val verdict: GhostVerdict
)

data class GhostSetSummary(
    val totalDeltaMcvMmS: Float,     // Sum of all rep deltas
    val avgDeltaMcvMmS: Float,       // Average delta per rep
    val repsCompared: Int,            // How many reps had ghost data
    val repsAhead: Int,
    val repsBehind: Int,
    val repsBeyondGhost: Int,         // Reps exceeding ghost set count
    val overallVerdict: GhostVerdict  // AHEAD if total delta > 0, BEHIND if < 0
)
```

### GhostRacingEngine Core Logic
```kotlin
// GhostRacingEngine.kt - New file in domain/premium/
object GhostRacingEngine {
    private const val TIED_TOLERANCE_PERCENT = 5f  // 5% MCV delta = TIED

    fun compareRep(currentMcvMmS: Float, ghostMcvMmS: Float): GhostVerdict {
        if (ghostMcvMmS <= 0f) return GhostVerdict.AHEAD  // Guard: bad ghost data
        val deltaPercent = ((currentMcvMmS - ghostMcvMmS) / ghostMcvMmS) * 100f
        return when {
            deltaPercent > TIED_TOLERANCE_PERCENT -> GhostVerdict.AHEAD
            deltaPercent < -TIED_TOLERANCE_PERCENT -> GhostVerdict.BEHIND
            else -> GhostVerdict.TIED
        }
    }

    fun computeSetDelta(comparisons: List<GhostRepComparison>): GhostSetSummary {
        val compared = comparisons.filter { it.verdict != GhostVerdict.BEYOND }
        val totalDelta = compared.sumOf { it.deltaMcvMmS.toDouble() }.toFloat()
        val avgDelta = if (compared.isNotEmpty()) totalDelta / compared.size else 0f
        return GhostSetSummary(
            totalDeltaMcvMmS = totalDelta,
            avgDeltaMcvMmS = avgDelta,
            repsCompared = compared.size,
            repsAhead = compared.count { it.verdict == GhostVerdict.AHEAD },
            repsBehind = compared.count { it.verdict == GhostVerdict.BEHIND },
            repsBeyondGhost = comparisons.count { it.verdict == GhostVerdict.BEYOND },
            overallVerdict = when {
                avgDelta > 0 -> GhostVerdict.AHEAD
                avgDelta < 0 -> GhostVerdict.BEHIND
                else -> GhostVerdict.TIED
            }
        )
    }
}
```

### SQL Query for Best Ghost Session
```sql
-- New query to add to VitruvianDatabase.sq
-- Find the best session (highest avg MCV) for an exercise + mode + weight range
selectBestGhostSession:
SELECT ws.id, ws.exerciseName, ws.weightPerCableKg, ws.workingReps, ws.avgMcvMmS
FROM WorkoutSession ws
WHERE ws.exerciseId = ?
  AND ws.mode = ?
  AND ws.avgMcvMmS IS NOT NULL
  AND ws.avgMcvMmS > 0
  AND ws.workingReps > 0
  AND ABS(ws.weightPerCableKg - ?) <= ?
ORDER BY ws.avgMcvMmS DESC
LIMIT 1;
```

### Ghost Loading in ActiveSessionEngine.startWorkout()
```kotlin
// Inside ActiveSessionEngine.startWorkout(), after workout state transitions to Active:
// Pre-load ghost session if feature is enabled and exercise is known
val exerciseId = params.selectedExerciseId
if (exerciseId != null && exerciseId.isNotBlank()) {
    scope.launch {
        val bestSession = workoutRepository.findBestGhostSession(
            exerciseId = exerciseId,
            mode = params.programMode.name,
            weightPerCableKg = params.weightPerCableKg,
            weightToleranceKg = 5f
        )
        if (bestSession != null) {
            val repBio = biomechanicsRepository.getRepBiomechanics(bestSession.id)
            val metrics = workoutRepository.getMetricsBySession(bestSession.id)
            coordinator._ghostSession.value = GhostSession(
                sessionId = bestSession.id,
                exerciseName = bestSession.exerciseName ?: "",
                weightPerCableKg = bestSession.weightPerCableKg,
                workingReps = bestSession.workingReps,
                avgMcvMmS = bestSession.avgMcvMmS ?: 0f,
                repVelocities = repBio.map { it.velocity.meanConcentricVelocityMmS },
                repPeakPositions = extractRepPeakPositions(metrics, repBio)
            )
        }
    }
}
```

### Ghost Comparison at Rep Completion
```kotlin
// In ActiveSessionEngine, after scoreCurrentRep() and processBiomechanicsForRep():
// Compare against ghost if available
val ghostSession = coordinator._ghostSession.value
if (ghostSession != null) {
    val currentRepNumber = repCountAfter
    val currentBioResult = coordinator.biomechanicsEngine.latestRepResult.value
    if (currentBioResult != null) {
        val ghostRepIndex = currentRepNumber - 1  // 0-based index
        if (ghostRepIndex < ghostSession.repVelocities.size) {
            val ghostMcv = ghostSession.repVelocities[ghostRepIndex]
            val currentMcv = currentBioResult.velocity.meanConcentricVelocityMmS
            val comparison = GhostRepComparison(
                repNumber = currentRepNumber,
                currentMcvMmS = currentMcv,
                ghostMcvMmS = ghostMcv,
                deltaMcvMmS = currentMcv - ghostMcv,
                verdict = GhostRacingEngine.compareRep(currentMcv, ghostMcv)
            )
            coordinator.ghostRepComparisons.add(comparison)
            coordinator._latestGhostVerdict.value = comparison
        } else {
            // Beyond ghost -- user exceeded ghost rep count
            val comparison = GhostRepComparison(
                repNumber = currentRepNumber,
                currentMcvMmS = currentBioResult.velocity.meanConcentricVelocityMmS,
                ghostMcvMmS = 0f,
                deltaMcvMmS = 0f,
                verdict = GhostVerdict.BEYOND
            )
            coordinator.ghostRepComparisons.add(comparison)
            coordinator._latestGhostVerdict.value = comparison
        }
    }
}
```

### GhostRacingOverlay Composable
```kotlin
// GhostRacingOverlay.kt - New file in presentation/components/
@Composable
fun GhostRacingOverlay(
    currentPosition: Float,
    ghostPosition: Float,
    maxPosition: Float,
    verdict: GhostRepComparison?,
    modifier: Modifier = Modifier
) {
    val colors = AccessibilityTheme.colors
    // Two thin vertical bars side by side showing current vs ghost position progress
    // AHEAD/BEHIND label between bars updates per-rep
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // "YOU" bar
        VerticalProgressBar(
            progress = if (maxPosition > 0) (currentPosition / maxPosition).coerceIn(0f, 1f) else 0f,
            color = colors.primary,
            label = "YOU"
        )
        // "GHOST" bar
        VerticalProgressBar(
            progress = if (maxPosition > 0) (ghostPosition / maxPosition).coerceIn(0f, 1f) else 0f,
            color = colors.onSurfaceVariant.copy(alpha = 0.5f),
            label = "BEST"
        )
    }
    // Verdict badge
    if (verdict != null) {
        val (text, color) = when (verdict.verdict) {
            GhostVerdict.AHEAD -> "AHEAD" to colors.success
            GhostVerdict.BEHIND -> "BEHIND" to colors.error
            GhostVerdict.TIED -> "TIED" to colors.warning
            GhostVerdict.BEYOND -> "NEW BEST" to colors.success
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| No ghost racing | Rep-indexed ghost with pre-loaded session | Phase 22 | First comparison overlay on HUD |
| MetricSample only (raw position) | RepBiomechanics (per-rep MCV aggregated) | Phase 13 (v0.5.0) | Ghost comparison uses pre-computed MCV, not raw samples |
| No per-rep biomechanics persistence | GATE-04 unconditional capture | Phase 6 (v0.4.6) | Ghost racing is possible because per-rep data exists for ALL users |

**Key enablers:**
- `RepBiomechanics.mcvMmS` (per-rep mean concentric velocity) -- already persisted since Phase 13
- `WorkoutSession.avgMcvMmS` (set-level average MCV) -- stored since Phase 13, enables "best session" ranking
- `MetricSample` (per-sample position data) -- stored since v0.1, enables real-time position comparison
- `FeatureGate.GHOST_RACING` -- already exists in Phase 16

## Open Questions

1. **Position data granularity for progress bars**
   - What we know: MetricSamples are captured at ~50Hz during workout. RepBiomechanics has MCV but not position traces per rep.
   - What's unclear: Whether to use raw MetricSample position for the ghost progress bar (requires pre-loading all samples for the ghost session, potentially 1000s of rows) or just rep peak positions (much lighter).
   - Recommendation: Use rep peak positions only (from RepBiomechanics or extracted from MetricSamples at load time). The progress bar shows "how far through the rep" not a continuous position trace. This keeps memory usage reasonable and avoids DB performance issues during ghost loading.

2. **Ghost overlay positioning on small screens**
   - What we know: ExecutionPage already has cable position bars (CenterStart/CenterEnd), BalanceBar (BottomCenter), rep counter (Center), FormCheckOverlay (TopEnd), FormWarningBanner (TopStart).
   - What's unclear: Where exactly to place the ghost bars without visual conflict.
   - Recommendation: Place ghost comparison bars inset from cable position bars (e.g., 36dp from start/end), narrower (16dp width), with the AHEAD/BEHIND verdict as a small chip/badge near the bottom of the bars. On screens where cable bars are not visible (single-cable exercises), the ghost bars can use the standard CenterStart/CenterEnd positions.

3. **Handling Just Lift mode**
   - What we know: Just Lift mode has `exerciseId == null` (no specific exercise assigned).
   - What's unclear: Should ghost racing be available in Just Lift mode?
   - Recommendation: No. Ghost racing requires a known exercise to match against previous sessions. Skip ghost loading when `exerciseId` is null or blank.

## Sources

### Primary (HIGH confidence)
- Codebase: `FeatureGate.kt` -- GHOST_RACING already in Phoenix tier set (line 33, 55)
- Codebase: `WorkoutCoordinator.kt` -- shared state bus pattern for runtime state
- Codebase: `ActiveSessionEngine.kt` -- rep processing at line 522-549, set completion at line 1956-2060
- Codebase: `WorkoutHud.kt` -- overlay zone pattern at lines 233-367 (cable bars, FormCheck, BalanceBar)
- Codebase: `ReadinessEngine.kt` / `RpgAttributeEngine.kt` -- stateless object pattern for domain logic
- Codebase: `BiomechanicsRepository.kt` -- per-rep MCV retrieval via `getRepBiomechanics(sessionId)`
- Codebase: `VitruvianDatabase.sq` -- RepBiomechanics table (mcvMmS, peakVelocityMmS per rep), WorkoutSession (avgMcvMmS), MetricSample (position, velocity per sample)

### Secondary (MEDIUM confidence)
- STATE.md accumulated decisions: "Ghost racing syncs on rep index, not wall-clock -- pre-load ghost session to memory"
- STATE.md blockers: "Ghost racing modifies WorkoutCoordinator + ActiveSessionEngine + WorkoutHud -- must come after HUD customization stabilizes (Phase 18 before Phase 22)"
- REQUIREMENTS.md: GHOST-01 through GHOST-04 definitions
- ROADMAP.md: Phase 22 success criteria (5 criteria)

### Tertiary (LOW confidence)
- None -- all findings are from codebase inspection and project documentation.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new dependencies, all libraries already in use
- Architecture: HIGH -- follows established patterns (ReadinessEngine, FormCheck overlay, WorkoutCoordinator state bus)
- Pitfalls: HIGH -- identified from direct code analysis of rep processing pipeline and overlay positioning
- Domain models: HIGH -- RepBiomechanics table structure verified, MCV data availability confirmed
- SQL queries: MEDIUM -- best session query is new but straightforward; exercise matching by exerciseId + mode is standard

**Research date:** 2026-02-28
**Valid until:** 2026-03-30 (stable codebase, no upstream changes expected after v0.5.1 ships)

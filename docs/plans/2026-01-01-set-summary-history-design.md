# Set Summary History Feature Design

**Date:** 2026-01-01
**Status:** Approved
**Version:** v0.2.1+

## Problem

After completing a set in Just Lift mode, users see a detailed summary screen with stats (reps, volume, forces, calories, etc.). This screen auto-dismisses quickly, and users cannot retrieve this information later. The history tab only shows basic data (reps, weight, mode) stored in WorkoutSession.

User request: "Could you make it show up again when you click on the workout in the history tab?"

## Solution

Persist the set summary metrics to the database and allow users to view them by tapping workout entries in the history tab.

## Design Decisions

1. **Storage approach:** Add columns to existing WorkoutSession table (vs. separate table or recalculation)
2. **History UI:** Inline expansion of history cards (vs. full-screen detail or bottom sheet)
3. **Display content:** Reuse existing SetSummaryCard component with all metrics
4. **Old data handling:** Show "Summary unavailable" message for entries before v0.2.1
5. **Expansion trigger:** Tap anywhere on card with chevron indicator

## Database Schema Changes

Add to `WorkoutSession` table in `VitruvianDatabase.sq`:

```sql
-- Force metrics (per cable, kg)
peakForceConcentricA REAL DEFAULT NULL,
peakForceConcentricB REAL DEFAULT NULL,
peakForceEccentricA REAL DEFAULT NULL,
peakForceEccentricB REAL DEFAULT NULL,
avgForceConcentricA REAL DEFAULT NULL,
avgForceConcentricB REAL DEFAULT NULL,
avgForceEccentricA REAL DEFAULT NULL,
avgForceEccentricB REAL DEFAULT NULL,

-- Aggregate metrics
heaviestLiftKg REAL DEFAULT NULL,
totalVolumeKg REAL DEFAULT NULL,
estimatedCalories REAL DEFAULT NULL,

-- Echo mode phase metrics
warmupAvgWeightKg REAL DEFAULT NULL,
workingAvgWeightKg REAL DEFAULT NULL,
burnoutAvgWeightKg REAL DEFAULT NULL,
peakWeightKg REAL DEFAULT NULL,

-- User feedback
rpe INTEGER DEFAULT NULL
```

**16 new columns total.** All nullable so existing records remain valid.

## Domain Model Changes

Update `WorkoutSession` data class:

```kotlin
data class WorkoutSession(
    // ... existing fields ...

    // Force metrics
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,

    // Aggregates
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val estimatedCalories: Float? = null,

    // Echo mode
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,

    // User feedback
    val rpe: Int? = null
) {
    val hasSummaryMetrics: Boolean
        get() = peakForceConcentricA != null || peakForceConcentricB != null
}

fun WorkoutSession.toSetSummary(): SetSummary? {
    if (!hasSummaryMetrics) return null

    return SetSummary(
        metrics = emptyList(),
        repCount = totalReps,
        durationMs = duration,
        totalVolumeKg = totalVolumeKg ?: 0f,
        heaviestLiftKgPerCable = heaviestLiftKg ?: 0f,
        peakForceConcentricA = peakForceConcentricA ?: 0f,
        peakForceConcentricB = peakForceConcentricB ?: 0f,
        peakForceEccentricA = peakForceEccentricA ?: 0f,
        peakForceEccentricB = peakForceEccentricB ?: 0f,
        avgForceConcentricA = avgForceConcentricA ?: 0f,
        avgForceConcentricB = avgForceConcentricB ?: 0f,
        avgForceEccentricA = avgForceEccentricA ?: 0f,
        avgForceEccentricB = avgForceEccentricB ?: 0f,
        estimatedCalories = estimatedCalories ?: 0f,
        isEchoMode = mode == "Echo",
        warmupReps = warmupReps,
        workingReps = workingReps,
        burnoutReps = totalReps - warmupReps - workingReps,
        warmupAvgWeightKg = warmupAvgWeightKg ?: 0f,
        workingAvgWeightKg = workingAvgWeightKg ?: 0f,
        burnoutAvgWeightKg = burnoutAvgWeightKg ?: 0f,
        peakWeightKg = peakWeightKg ?: 0f
    )
}
```

## Save Flow Changes

In `MainViewModel.stopWorkout()`:

```kotlin
val summary = calculateSetSummaryMetrics(collectedMetrics, ...)

val session = WorkoutSession(
    // ... existing fields ...

    // Populate from calculated summary
    peakForceConcentricA = summary.peakForceConcentricA,
    peakForceConcentricB = summary.peakForceConcentricB,
    peakForceEccentricA = summary.peakForceEccentricA,
    peakForceEccentricB = summary.peakForceEccentricB,
    avgForceConcentricA = summary.avgForceConcentricA,
    avgForceConcentricB = summary.avgForceConcentricB,
    avgForceEccentricA = summary.avgForceEccentricA,
    avgForceEccentricB = summary.avgForceEccentricB,
    heaviestLiftKg = summary.heaviestLiftKgPerCable,
    totalVolumeKg = summary.totalVolumeKg,
    estimatedCalories = summary.estimatedCalories,
    warmupAvgWeightKg = if (summary.isEchoMode) summary.warmupAvgWeightKg else null,
    workingAvgWeightKg = if (summary.isEchoMode) summary.workingAvgWeightKg else null,
    burnoutAvgWeightKg = if (summary.isEchoMode) summary.burnoutAvgWeightKg else null,
    peakWeightKg = if (summary.isEchoMode) summary.peakWeightKg else null,
    rpe = _currentSetRpe.value
)
```

## History UI Changes

### State Management

```kotlin
// In HistoryTab
var expandedSessionIds by remember { mutableStateOf(setOf<String>()) }
```

### Card Layout

```
+-------------------------------------+
| Exercise Name              [chevron]|  <- Tap anywhere to expand
| Date - Reps - Weight - Mode         |
+---------[if expanded]---------------+
| +----------------------------------+|
| |       SetSummaryCard             ||
| |  (isHistoryView = true)          ||
| +----------------------------------+|
+-------------------------------------+
```

### SetSummaryCard Modifications

Add parameter:
```kotlin
@Composable
fun SetSummaryCard(
    summary: SetSummary,
    isHistoryView: Boolean = false,  // NEW
    // ...
)
```

When `isHistoryView = true`:
- Hide "Done" button and countdown
- Hide RPE slider, show saved RPE value as read-only text
- Adjust header if desired

### Old Entry Handling

For entries where `hasSummaryMetrics = false`:
- Card is still expandable
- Expanded content shows: "Detailed metrics available for workouts after v0.2.1"
- Display available basic data: reps, weight, duration, mode

## Files to Modify

| File | Changes |
|------|---------|
| `VitruvianDatabase.sq` | Add 16 columns |
| `WorkoutSession.kt` | Add properties, `hasSummaryMetrics`, `toSetSummary()` |
| `MainViewModel.kt` | Populate new fields in `stopWorkout()` |
| `WorkoutRepository.kt` | Update insert/query for new columns |
| `WorkoutTab.kt` (SetSummaryCard) | Add `isHistoryView` parameter |
| `HistoryAndSettingsTabs.kt` | Add expand state, chevron, AnimatedVisibility |

## Edge Cases

1. **Old data (pre-v0.2.1):** NULL columns -> `hasSummaryMetrics = false` -> show message
2. **Interrupted workouts:** If metrics list empty, summary fields stay null
3. **Echo vs non-Echo:** `isEchoMode` flag determines which phase fields display
4. **RPE not recorded:** NULL -> don't show RPE section in history
5. **Unit conversion:** Summary stores kg; display converts to lbs per user preference

## Migration

No migration script needed. SQLite allows adding nullable columns to existing tables - old rows automatically get NULL values.

## Testing

- [ ] New workout saves all summary fields
- [ ] History card expands/collapses on tap
- [ ] Expanded view shows correct SetSummaryCard
- [ ] Old entries show "unavailable" message
- [ ] Echo mode entries show phase breakdown
- [ ] RPE displays when recorded, hidden when null
- [ ] Unit conversion works correctly (kg/lbs)

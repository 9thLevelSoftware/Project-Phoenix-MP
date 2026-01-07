# Cycle Editor Redesign: Playlist Pattern

**Date**: 2026-01-02
**Status**: Approved
**Branch**: TBD

## Problem Statement

The current Cycle Editor UI suffers from cognitive load and interaction friction:
- Two-panel layout (routine palette + day slots) is awkward on mobile screens
- Toggle-based rest/workout switching creates confusion
- Per-day modifiers add complexity without proportional value
- List items are too tall, making it hard to see the cycle flow at a glance

## Solution: Playlist Editor Pattern

Redesign the Cycle Editor to follow a "playlist editor" mental model (like music apps):
- Single-column list of days
- Tap to edit, swipe to delete/duplicate
- Compact, visually distinct items for workout vs rest days
- Cycle-wide progression settings instead of per-day modifiers

---

## Data Model Changes

### New: CycleItem Sealed Class

Replace toggle-based `isRestDay: Boolean` with explicit types:

```kotlin
sealed class CycleItem {
    abstract val id: String
    abstract val dayNumber: Int

    data class Workout(
        override val id: String,
        override val dayNumber: Int,
        val routineId: String,
        val routineName: String,
        val exerciseCount: Int,
        val estimatedMinutes: Int?
    ) : CycleItem()

    data class Rest(
        override val id: String,
        override val dayNumber: Int,
        val note: String? = null  // Optional: "Active Recovery", "Full Rest"
    ) : CycleItem()
}
```

### New: CycleProgression

Cycle-wide progression rules replacing per-day modifiers:

```kotlin
data class CycleProgression(
    val frequencyCycles: Int = 2,                  // Every N completions
    val weightIncreasePercent: Float? = null,      // e.g., 2.5%
    val echoLevelIncrease: Boolean = false,        // +1 level
    val eccentricLoadIncreasePercent: Int? = null  // e.g., 5%
)
```

Added to `TrainingCycle`:

```kotlin
data class TrainingCycle(
    // ... existing fields ...
    val progression: CycleProgression? = null
)
```

---

## Database Schema Changes

### New Table: CycleProgression

```sql
CREATE TABLE CycleProgression (
    cycle_id TEXT PRIMARY KEY,
    frequency_cycles INTEGER NOT NULL DEFAULT 2,
    weight_increase_percent REAL,
    echo_level_increase INTEGER NOT NULL DEFAULT 0,
    eccentric_load_increase_percent INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);
```

### Modified Table: CycleDay

Remove per-day modifier columns:
- `echo_level` (DROP)
- `eccentric_load_percent` (DROP)
- `weight_progression_percent` (DROP)
- `rep_modifier` (DROP)
- `rest_time_override_seconds` (DROP)

Add optional note field:
- `note TEXT` (for rest day annotations)

### Migration Strategy

1. Create `CycleProgression` table
2. Drop per-day modifier columns from `CycleDay`
3. Keep `is_rest_day` column for DB compatibility
4. Repository maps to sealed class for UI consumption

**Risk**: Users with per-day modifiers configured lose those settings. Acceptable for beta.

---

## UI Components

### WorkoutDayRow

Compact workout day item:

```
┌─────────────────────────────────────────────────┐
│ ≡  Day 1: Upper Body Power                  ›  │
│     3 exercises • ~45 min                       │
└─────────────────────────────────────────────────┘
```

- Left: Drag handle (`≡`)
- Center: Day number + routine name (bold), subtitle with metadata
- Right: Chevron (`›`) indicating tappable
- Background: `surfaceContainerHigh`
- Swipe left → Delete, Swipe right → Duplicate

### RestDayRow

Visually distinct rest day item:

```
┌─────────────────────────────────────────────────┐
│ ≡  Day 3: Rest                         ☽       │
└─────────────────────────────────────────────────┘
```

- Same drag handle for consistency
- Centered label with moon/rest icon
- Background: `tertiaryContainer` with ~15% alpha (Ash Blue tint)
- Shorter height than workout rows
- Same swipe actions

---

## Screen Layout

### Main Editor Screen

Single-column layout:

```
┌─────────────────────────────────────────────────┐
│  ←  Edit Cycle                          Save    │
├─────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────┐   │
│  │  Cycle Name                             │   │
│  └─────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────┐   │
│  │  Description (optional)                 │   │
│  └─────────────────────────────────────────┘   │
│                                                 │
│  CYCLE LENGTH: 4 days                      ⚙️   │
│                                                 │
│  ┌─────────────────────────────────────────┐   │
│  │ ≡  Day 1: Push Day A                 ›  │   │
│  │     4 exercises • ~50 min               │   │
│  └─────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────┐   │
│  │ ≡  Day 2: Pull Day A                 ›  │   │
│  │     4 exercises • ~50 min               │   │
│  └─────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────┐   │
│  │ ≡  Day 3: Rest                      ☽   │   │
│  └─────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────┐   │
│  │ ≡  Day 4: Legs                       ›  │   │
│  │     5 exercises • ~60 min               │   │
│  └─────────────────────────────────────────┘   │
│                                                 │
├─────────────────────────────────────────────────┤
│                  + Add Day                      │
└─────────────────────────────────────────────────┘
```

### Empty State

```
    No days added yet.
    Build your cycle by adding workout or rest days.

    [+ Add Workout]    [+ Add Rest]
```

---

## Bottom Sheets

### Add Day Sheet

Triggered by `+ Add Day` FAB:

```
┌─────────────────────────────────────────────────┐
│              Add to Cycle                       │
│                                                 │
│  ┌──────────────┐    ┌──────────────┐          │
│  │   Workout    │    │   Rest Day   │          │
│  └──────────────┘    └──────────────┘          │
│                                                 │
│  Recent Routines                                │
│  ───────────────                                │
│  Upper Body Power                           ›   │
│  Leg Day                                    ›   │
│  Push Day A                                 ›   │
│                                                 │
│  All Routines                                   │
│  ────────────                                   │
│  Back & Biceps                              ›   │
│  Core Work                                  ›   │
│  Full Body                                  ›   │
└─────────────────────────────────────────────────┘
```

**Behavior**:
- Tap "Rest Day" → Append `CycleItem.Rest`, dismiss
- Tap any routine → Append `CycleItem.Workout`, dismiss
- Recents: Last 3 routines used (persisted in preferences)

### Progression Settings Sheet

Triggered by ⚙️ icon in cycle header:

```
┌─────────────────────────────────────────────────┐
│            Progression Settings                 │
│                                                 │
│  Apply progression every:                       │
│  ┌─────────────────────────────────────────┐   │
│  │  ◄    2 cycle completions    ►          │   │
│  └─────────────────────────────────────────┘   │
│                                                 │
│  ☑  Increase weight by                         │
│      [ 2.5 % ]                                  │
│                                                 │
│  ☐  Increase Echo level by 1                   │
│      (Applies to Echo-mode exercises)           │
│                                                 │
│  ☐  Increase eccentric load by                 │
│      [ 5 % ]                                    │
│                                                 │
│  ─────────────────────────────────────────────  │
│  Current rotation: 3 of 4                       │
│  Next progression applies after 1 more cycle    │
│                                                 │
│                              [ Apply ]          │
└─────────────────────────────────────────────────┘
```

**Validation**:
- Weight: 0.5% - 10%
- Eccentric load: 1% - 20%
- Frequency: 1 - 10 cycles
- Echo level caps at max, shows note when reached

---

## Interactions

### Swipe Actions

**Swipe Left → Delete**:
- Red background (`SignalError`)
- Trash icon + "DELETE" label
- No confirmation; undo via 5-second snackbar
- Remaining days auto-renumber

**Swipe Right → Duplicate**:
- Tertiary/Ash Blue background
- Copy icon + "COPY" label
- Duplicated day inserted below original
- All days renumber

### Tap Actions

- **Tap Workout Row**: Opens Add Day sheet in "edit" mode to change routine
- **Tap Rest Row**: No action (or optionally edit note)
- **Long-press drag handle**: Initiates reorder drag

### Undo Snackbar

```
┌─────────────────────────────────────────────────┐
│  Day 2 removed                        [ UNDO ]  │
└─────────────────────────────────────────────────┘
```

---

## File Changes

### Files to Modify

| File | Changes |
|------|---------|
| `TrainingCycleModels.kt` | Add `CycleItem` sealed class, `CycleProgression` data class |
| `VitruvianDatabase.sq` | Add `CycleProgression` table, drop per-day modifier columns |
| `TrainingCycleRepository.kt` | Add progression CRUD, update day queries |
| `SqlDelightTrainingCycleRepository.kt` | Implement new queries, map to sealed class |
| `CycleEditorScreen.kt` | Full rewrite: single-column, new state model |

### Files to Create

| File | Purpose |
|------|---------|
| `WorkoutDayRow.kt` | Workout day list item composable |
| `RestDayRow.kt` | Rest day list item composable |
| `AddDaySheet.kt` | Bottom sheet with routine picker |
| `ProgressionSettingsSheet.kt` | Cycle-wide progression config |

### Files to Delete

| File | Reason |
|------|--------|
| `CycleDayConfigSheet.kt` | Replaced by cycle-wide progression |

---

## Dependencies

- **Swipe actions**: Use Material3 `SwipeToDismissBox`
- **Reorderable list**: Continue using `sh.calvin.reorderable`
- **No new libraries required**

---

## Summary of UX Improvements

| Aspect | Before | After |
|--------|--------|-------|
| Layout | Two-panel (cramped on mobile) | Single-column playlist |
| Day creation | Empty slot → toggle → assign | Tap Add → pick type → done |
| Rest vs Workout | Toggle switch | Visually distinct rows |
| Modifiers | Per-day config sheet | Cycle-wide progression |
| Delete | Trash icon in row | Swipe left |
| Duplicate | Not available | Swipe right |
| Mental model | "Configure slots" | "Build a playlist" |

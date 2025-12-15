# Training Cycles Enhancement Design

**Date:** 2025-12-15
**Status:** Approved

## Overview

Enhance the Training Cycles feature to provide compelling, pre-built workout templates with full exercise content, mode suggestions, and support for percentage-based programming (5/3/1). Templates will use exercises from the Vitruvian Exercise Library and respect machine constraints (cables pull upward only).

## Goals

1. Transform structural-only templates into fully usable programs with exercises, sets, reps, and mode suggestions
2. Add 5/3/1 (Wendler) template with percentage-based weight calculations
3. Enable per-exercise 1RM tracking for percentage-based programming
4. Implement template-specific progression rules

## Constraints

- **Vitruvian hardware:** Cables pull upward from floor only. No pull-ups, lat pulldowns, tricep pushdowns, or overhead cable work.
- **Single active cycle:** Only one TrainingCycle can be active at a time (existing constraint)
- **Exercise library:** Templates must reference exercises that exist in the bundled `exercise_dump.json` (572 exercises)

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| 1RM storage | Global per exercise | Single active cycle prevents conflicts; simpler model |
| Template content | Full routines with exercises | Better onboarding; users get working programs immediately |
| Mode assignment | Suggestions with confirmation | User sees proposed modes, can adjust before creation |
| Progression rules | Per-template | Different programs have different philosophies (5/3/1 vs PPL) |
| Rest day flexibility | Fixed per template | Maintains program integrity; users can swap workouts, not add rest days |
| Implementation scope | All four templates together | 1RM infrastructure benefits all; ensures consistency |

---

## Data Model Changes

### Exercise Entity (new field)

```kotlin
data class Exercise(
    // ... existing fields ...
    val oneRepMaxKg: Float? = null  // User's 1RM, manually editable
)
```

Database migration adds `one_rep_max_kg REAL` column to Exercise table.

### ProgressionRule (new model)

```kotlin
data class ProgressionRule(
    val type: ProgressionType,
    val incrementPercent: Float?,      // e.g., 2.5 for PPL
    val incrementKgUpper: Float?,      // e.g., 2.5 for 5/3/1 upper lifts
    val incrementKgLower: Float?,      // e.g., 5.0 for 5/3/1 lower lifts
    val triggerCondition: String?,     // e.g., "all_sets_completed", "cycle_complete"
    val cycleWeeks: Int?               // e.g., 4 for 5/3/1
)

enum class ProgressionType {
    PERCENTAGE,    // Increase by X%
    FIXED_WEIGHT,  // Increase by fixed kg
    MANUAL         // No automatic suggestions
}
```

### TrainingCycle (new fields)

```kotlin
data class TrainingCycle(
    // ... existing fields ...
    val progressionRule: ProgressionRule? = null,
    val weekNumber: Int = 1  // Current week for cycling programs
)
```

### Template Models (new)

```kotlin
data class TemplateExercise(
    val exerciseName: String,
    val sets: Int,
    val reps: Int?,                    // null for AMRAP
    val suggestedMode: ProgramMode,
    val percentOfMax: Float? = null    // For 5/3/1 percentage sets
)

data class RoutineTemplate(
    val name: String,
    val exercises: List<TemplateExercise>
)

data class CycleTemplate(
    val name: String,
    val description: String,
    val days: List<CycleDayTemplate>,
    val progressionRule: ProgressionRule?
)

data class CycleDayTemplate(
    val dayNumber: Int,
    val name: String,
    val routine: RoutineTemplate?,     // null for rest days
    val isRestDay: Boolean
)
```

### 5/3/1 Percentage Sets

```kotlin
data class PercentageSet(
    val percent: Float,
    val targetReps: Int?,
    val isAmrap: Boolean = false
)

object FiveThreeOneWeeks {
    val WEEK_1 = listOf(
        PercentageSet(0.65f, 5),
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, 5, isAmrap = true)
    )
    val WEEK_2 = listOf(
        PercentageSet(0.70f, 3),
        PercentageSet(0.80f, 3),
        PercentageSet(0.90f, 3, isAmrap = true)
    )
    val WEEK_3 = listOf(
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, 3),
        PercentageSet(0.95f, 1, isAmrap = true)
    )
    val WEEK_4_DELOAD = listOf(
        PercentageSet(0.40f, 5),
        PercentageSet(0.50f, 5),
        PercentageSet(0.60f, 5)
    )
}

fun calculateSetWeight(oneRepMaxKg: Float, percentageSet: PercentageSet): Float {
    val trainingMax = oneRepMaxKg * 0.9f  // 5/3/1 uses 90% of 1RM
    return (trainingMax * percentageSet.percent).roundToNearestHalf()
}
```

---

## Template Specifications

### 3-Day Full Body

**Structure:** 7 days (3 training, 4 rest)
**Progression:** +2.5% when all sets completed at target reps

| Day | Name | Exercises |
|-----|------|-----------|
| 1 | Full Body A | Squat 3x8, Bench Press 3x8, Bent Over Row 3x8, Shoulder Press 3x10, Bicep Curl 3x12, Calf Raise 3x15 |
| 2 | Rest | - |
| 3 | Full Body B | Deadlift 3x5, Incline Bench Press 3x10, Bent Over Row - Reverse Grip 3x10, Lateral Raise 3x12, Tricep Extension 3x12, Plank 3x30s |
| 4 | Rest | - |
| 5 | Full Body C | Front Squat 3x8, Bench Press - Wide Grip 3x10, Upright Row 3x10, Arnold Press 3x10, Hammer Curl 3x12, Shrug 3x12 |
| 6 | Rest | - |
| 7 | Rest | - |

**Mode suggestions:** Compounds (Squat, Bench, Deadlift) → Old School; Isolation → TUT

### Push/Pull/Legs (6-Day)

**Structure:** 6 days (no built-in rest)
**Progression:** +2.5% when all sets completed at target reps

| Day | Name | Exercises |
|-----|------|-----------|
| 1 | Push A | Bench Press 5x5, Incline Bench Press 3x10, Shoulder Press 3x10, Lateral Raise 3x12, Tricep Extension 3x12 |
| 2 | Pull A | Bent Over Row 5x5, Bent Over Row - Reverse Grip 3x10, Face Pull 3x15, Shrug 3x12, Bicep Curl 3x12 |
| 3 | Legs A | Squat 5x5, Romanian Deadlift 3x10, Lunges 3x10, Leg Extension 3x12, Calf Raise 3x15 |
| 4 | Push B | Shoulder Press 5x5, Bench Press 3x10, Incline Bench Press 3x10, Lateral Raise 3x12, Tricep Extension 3x12 |
| 5 | Pull B | Bent Over Row 5x5, Upright Row 3x10, Face Pull 3x15, Shrug 3x12, Hammer Curl 3x12 |
| 6 | Legs B | Deadlift 5x5, Front Squat 3x10, Bulgarian Split Squat 3x10, Leg Curl 3x12, Calf Raise 3x15 |

**Mode suggestions:** 5x5 compounds → Old School; 3x10-12 accessories → TUT or Old School

### Upper/Lower (4-Day)

**Structure:** 5 days (4 training, 1 rest mid-week)
**Progression:** +2.5% when all sets completed at target reps

| Day | Name | Exercises |
|-----|------|-----------|
| 1 | Upper A | Bench Press 4x6, Bent Over Row 4x6, Shoulder Press 3x10, Bicep Curl 3x12, Tricep Extension 3x12 |
| 2 | Lower A | Squat 4x6, Romanian Deadlift 3x10, Lunges 3x10, Calf Raise 3x15 |
| 3 | Rest | - |
| 4 | Upper B | Incline Bench Press 4x8, Bent Over Row - Wide Grip 4x8, Arnold Press 3x10, Hammer Curl 3x12, Skull Crusher 3x12 |
| 5 | Lower B | Deadlift 4x5, Front Squat 3x10, Bulgarian Split Squat 3x10, Glute Kickback 3x12 |

**Mode suggestions:** Heavy compounds (4x5-6) → Old School; Hypertrophy (3x10-12) → TUT

### 5/3/1 (Wendler)

**Structure:** 4 days (4 training, flexible rest between)
**Progression:** Fixed increment after Week 4 (+2.5kg upper, +5kg lower)
**Special:** Requires 1RM input for main lifts; weights calculated as percentages

| Day | Main Lift | Push Assist | Pull Assist | Legs/Core Assist |
|-----|-----------|-------------|-------------|------------------|
| 1 | Bench Press (5/3/1) | Incline Press 3x10 | Bent Over Row 3x10 | Plank 3x30s |
| 2 | Squat (5/3/1) | Shoulder Press 3x10 | Face Pull 3x15 | Lunges 3x10 |
| 3 | Shoulder Press (5/3/1) | Tricep Extension 3x12 | Bent Over Row 3x10 | Ab Crunch 3x15 |
| 4 | Deadlift (5/3/1) | Incline Press 3x10 | Shrug 3x12 | Back Extension 3x12 |

**5/3/1 set structure (per week):**
- Week 1: 65% x5, 75% x5, 85% x5+
- Week 2: 70% x3, 80% x3, 90% x3+
- Week 3: 75% x5, 85% x3, 95% x1+
- Week 4: 40% x5, 50% x5, 60% x5 (deload)

**Mode suggestions:** Main lifts → Old School; Assistance → TUT

---

## UI Flow

### Template Creation

```
1. User taps "New Cycle"
   └─> Template Selection Dialog
       - Shows: Name, description, training days count
       - Enhanced descriptions: "3-Day Full Body for beginners"

2. User selects template
   └─> Mode Confirmation Screen
       - Lists all exercises grouped by day
       - Each exercise shows: Name, Sets x Reps, [Mode dropdown]
       - User can adjust modes before creation

3. If 5/3/1 selected
   └─> 1RM Input Screen
       - Fields for: Bench Press, Squat, Shoulder Press, Deadlift
       - Pre-fills from stored Exercise.oneRepMaxKg if available
       - "Enter your 1RM or recent heavy single"

4. User taps "Create Cycle"
   └─> Cycle created with full Routines
   └─> Exercises resolved from library by name
   └─> Mode set, cycle activated
```

### 1RM Management

- **Settings screen:** New section "Lift Maxes" showing stored 1RM for key lifts
- **Exercise detail screen:** Show/edit 1RM field
- **After completing AMRAP sets:** Prompt to update 1RM if estimated max exceeds stored value

### 5/3/1 Week Progression

- Home screen shows current week: "5/3/1 - Week 2 of 4"
- After completing Week 4, show dialog: "Cycle complete! Training max will increase by 2.5kg (upper) / 5kg (lower). Continue to Week 1?"
- Auto-increment stored 1RM values and reset weekNumber to 1

---

## File Changes

| File | Change Type | Description |
|------|-------------|-------------|
| `domain/model/Exercise.kt` | Modify | Add `oneRepMaxKg: Float?` field |
| `domain/model/TrainingCycleModels.kt` | Modify | Add `ProgressionRule`, `weekNumber` to TrainingCycle |
| `domain/model/TemplateModels.kt` | New | `TemplateExercise`, `RoutineTemplate`, `CycleTemplate`, `PercentageSet` |
| `data/migration/CycleTemplates.kt` | Rewrite | Full template definitions with exercises |
| `sqldelight/VitruvianDatabase.sq` | Modify | Add `one_rep_max_kg` column, migration |
| `data/repository/ExerciseRepository.kt` | Modify | Add `updateOneRepMax()` method |
| `domain/usecase/ProgressionUseCase.kt` | Modify | Per-template progression logic |
| `presentation/screen/TrainingCyclesScreen.kt` | Modify | Mode confirmation dialog |
| `presentation/screen/OneRepMaxInputScreen.kt` | New | 1RM input for 5/3/1 setup |
| `presentation/screen/SettingsScreen.kt` | Modify | Add "Lift Maxes" section |

---

## Migration Strategy

1. **Database migration:** Add `one_rep_max_kg REAL DEFAULT NULL` to Exercise table
2. **Existing cycles:** Remain functional; no routineId = user assigns manually (backward compatible)
3. **New template system:** Lives alongside existing templates until old ones are removed

---

## Testing Considerations

- Verify all template exercise names exist in exercise library
- Test 1RM → percentage weight calculations round correctly to 0.5kg
- Test week cycling 1→2→3→4→1 with training max increment
- Test mode confirmation UI with long exercise lists
- Verify kg/lbs conversion applies to 1RM and increment values

---

## References

- [Gravitus PPL Guide](https://gravitus.com/workout-programs/push-pull-legs/)
- [Muscle & Strength PPL](https://www.muscleandstrength.com/workouts/6-day-powerbuilding-split-meal-plan)
- [PureGym Upper/Lower Guide](https://www.puregym.com/blog/upper-lower-split/)
- [Jim Wendler 5/3/1 for Beginners](https://www.jimwendler.com/blogs/jimwendler-com/5-3-1-for-beginners)

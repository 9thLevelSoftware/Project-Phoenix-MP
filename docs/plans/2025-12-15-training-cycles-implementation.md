# Training Cycles Enhancement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enhance Training Cycles with pre-built routines, 5/3/1 percentage-based programming, per-exercise 1RM tracking, and template-specific progression rules.

**Architecture:** Add `oneRepMaxKg` to Exercise entity, create template models (`TemplateExercise`, `RoutineTemplate`, `CycleTemplate`), rewrite `CycleTemplates` with full exercise content, add mode confirmation UI flow, and implement 5/3/1 week cycling with percentage calculations.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Compose Multiplatform, Koin DI

**Reference:** See `docs/plans/2025-12-15-training-cycles-enhancement-design.md` for full design rationale.

---

## Phase 1: Data Model Foundation

### Task 1: Add oneRepMaxKg to Database Schema

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`

**Step 1: Add column to Exercise table**

Find the `CREATE TABLE Exercise` statement and add the new column. Also add the migration.

```sql
-- Add to Exercise table definition (find existing CREATE TABLE Exercise):
-- Add this column after existing columns:
--   one_rep_max_kg REAL DEFAULT NULL,

-- Add migration at end of file:
ALTER TABLE Exercise ADD COLUMN one_rep_max_kg REAL DEFAULT NULL;
```

**Step 2: Add update query**

```sql
updateOneRepMax:
UPDATE Exercise SET one_rep_max_kg = ? WHERE id = ?;

getExercisesWithOneRepMax:
SELECT * FROM Exercise WHERE one_rep_max_kg IS NOT NULL;
```

**Step 3: Build to verify schema compiles**

Run: `./gradlew :shared:generateCommonMainDatabaseInterface`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat(db): add one_rep_max_kg column to Exercise table"
```

---

### Task 2: Update Exercise Domain Model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt`

**Step 1: Add oneRepMaxKg field to Exercise data class**

```kotlin
// Add to Exercise data class parameters (after timesPerformed):
data class Exercise(
    val name: String,
    val muscleGroup: String,
    val muscleGroups: String = muscleGroup,
    val equipment: String = "",
    val defaultCableConfig: CableConfiguration = CableConfiguration.DOUBLE,
    val id: String? = null,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    val timesPerformed: Int = 0,
    val oneRepMaxKg: Float? = null  // User's 1RM for percentage-based programming
)
```

**Step 2: Build to verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Exercise.kt
git commit -m "feat(model): add oneRepMaxKg field to Exercise"
```

---

### Task 3: Update ExerciseRepository

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExerciseRepository.kt`

**Step 1: Find ExerciseRepository interface and add method**

```kotlin
// Add to ExerciseRepository interface:
suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?)
fun getExercisesWithOneRepMax(): Flow<List<Exercise>>
```

**Step 2: Find the implementation class and add implementation**

Look for the class that implements ExerciseRepository (likely SqlDelightExerciseRepository or similar) and add:

```kotlin
override suspend fun updateOneRepMax(exerciseId: String, oneRepMaxKg: Float?) {
    queries.updateOneRepMax(oneRepMaxKg?.toDouble(), exerciseId)
}

override fun getExercisesWithOneRepMax(): Flow<List<Exercise>> {
    return queries.getExercisesWithOneRepMax()
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { rows -> rows.map { it.toExercise() } }
}
```

**Step 3: Update mapper function to include oneRepMaxKg**

Find the `toExercise()` mapper function and add:

```kotlin
// Add to the mapper that converts database row to Exercise:
oneRepMaxKg = row.one_rep_max_kg?.toFloat()
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
git commit -m "feat(repo): add oneRepMax methods to ExerciseRepository"
```

---

### Task 4: Create ProgressionRule Model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`

**Step 1: Add ProgressionType enum and ProgressionRule data class**

Add at the end of TrainingCycleModels.kt:

```kotlin
/**
 * Types of progression strategies for training cycles.
 */
enum class ProgressionType {
    /** Increase weight by percentage (e.g., +2.5%) */
    PERCENTAGE,
    /** Increase weight by fixed amount (e.g., +2.5kg) */
    FIXED_WEIGHT,
    /** No automatic progression suggestions */
    MANUAL
}

/**
 * Defines how weight progression works for a training cycle.
 */
data class ProgressionRule(
    val type: ProgressionType,
    val incrementPercent: Float? = null,
    val incrementKgUpper: Float? = null,
    val incrementKgLower: Float? = null,
    val triggerCondition: String? = null,
    val cycleWeeks: Int? = null
) {
    companion object {
        /** Standard percentage-based progression (+2.5% when all sets completed) */
        fun percentage(percent: Float = 2.5f) = ProgressionRule(
            type = ProgressionType.PERCENTAGE,
            incrementPercent = percent,
            triggerCondition = "all_sets_completed"
        )

        /** 5/3/1 style fixed weight progression */
        fun fiveThreeOne() = ProgressionRule(
            type = ProgressionType.FIXED_WEIGHT,
            incrementKgUpper = 2.5f,
            incrementKgLower = 5.0f,
            triggerCondition = "cycle_complete",
            cycleWeeks = 4
        )

        /** No automatic progression */
        fun manual() = ProgressionRule(type = ProgressionType.MANUAL)
    }
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt
git commit -m "feat(model): add ProgressionRule and ProgressionType"
```

---

### Task 5: Add progressionRule and weekNumber to TrainingCycle

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`

**Step 1: Update TrainingCycle data class**

```kotlin
// Find the TrainingCycle data class and add two new fields:
data class TrainingCycle(
    val id: String,
    val name: String,
    val description: String?,
    val days: List<CycleDay>,
    val createdAt: Long,
    val isActive: Boolean,
    val progressionRule: ProgressionRule? = null,  // NEW
    val weekNumber: Int = 1  // NEW: Current week for cycling programs (1-4 for 5/3/1)
)
```

**Step 2: Update the companion object create function**

```kotlin
companion object {
    fun create(
        id: String = generateUUID(),
        name: String,
        description: String? = null,
        days: List<CycleDay> = emptyList(),
        isActive: Boolean = false,
        progressionRule: ProgressionRule? = null,
        weekNumber: Int = 1
    ) = TrainingCycle(
        id = id,
        name = name,
        description = description,
        days = days,
        createdAt = currentTimeMillis(),
        isActive = isActive,
        progressionRule = progressionRule,
        weekNumber = weekNumber
    )
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt
git commit -m "feat(model): add progressionRule and weekNumber to TrainingCycle"
```

---

## Phase 2: Template Models

### Task 6: Create Template Models File

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TemplateModels.kt`

**Step 1: Create the new file with all template models**

```kotlin
package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.domain.model.ProgramMode

/**
 * A single set defined by percentage of 1RM (for 5/3/1).
 */
data class PercentageSet(
    val percent: Float,
    val targetReps: Int?,
    val isAmrap: Boolean = false
)

/**
 * 5/3/1 week definitions with percentage-based sets.
 */
object FiveThreeOneWeeks {
    val WEEK_1 = listOf(
        PercentageSet(0.65f, 5),
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, null, isAmrap = true)
    )
    val WEEK_2 = listOf(
        PercentageSet(0.70f, 3),
        PercentageSet(0.80f, 3),
        PercentageSet(0.90f, null, isAmrap = true)
    )
    val WEEK_3 = listOf(
        PercentageSet(0.75f, 5),
        PercentageSet(0.85f, 3),
        PercentageSet(0.95f, null, isAmrap = true)
    )
    val WEEK_4_DELOAD = listOf(
        PercentageSet(0.40f, 5),
        PercentageSet(0.50f, 5),
        PercentageSet(0.60f, 5)
    )

    fun forWeek(weekNumber: Int): List<PercentageSet> = when (weekNumber) {
        1 -> WEEK_1
        2 -> WEEK_2
        3 -> WEEK_3
        4 -> WEEK_4_DELOAD
        else -> WEEK_1
    }
}

/**
 * Calculate weight for a percentage-based set.
 * Uses 90% of 1RM as "training max" per Wendler's method.
 */
fun calculateSetWeight(oneRepMaxKg: Float, percentageSet: PercentageSet): Float {
    val trainingMax = oneRepMaxKg * 0.9f
    val rawWeight = trainingMax * percentageSet.percent
    // Round to nearest 0.5kg
    return (rawWeight * 2).toInt() / 2f
}

/**
 * An exercise within a template, before being resolved to actual Exercise.
 */
data class TemplateExercise(
    val exerciseName: String,
    val sets: Int,
    val reps: Int?,
    val suggestedMode: ProgramMode = ProgramMode.OldSchool,
    val isPercentageBased: Boolean = false,
    val percentageSets: List<PercentageSet>? = null
)

/**
 * A routine template containing multiple exercises.
 */
data class RoutineTemplate(
    val name: String,
    val exercises: List<TemplateExercise>
)

/**
 * A single day in a cycle template.
 */
data class CycleDayTemplate(
    val dayNumber: Int,
    val name: String,
    val routine: RoutineTemplate?,
    val isRestDay: Boolean = false
) {
    companion object {
        fun training(dayNumber: Int, name: String, routine: RoutineTemplate) =
            CycleDayTemplate(dayNumber, name, routine, isRestDay = false)

        fun rest(dayNumber: Int, name: String = "Rest") =
            CycleDayTemplate(dayNumber, name, null, isRestDay = true)
    }
}

/**
 * Complete cycle template with all days and progression rules.
 */
data class CycleTemplate(
    val id: String,
    val name: String,
    val description: String,
    val days: List<CycleDayTemplate>,
    val progressionRule: ProgressionRule?,
    val requiresOneRepMax: Boolean = false,
    val mainLifts: List<String> = emptyList()
)
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TemplateModels.kt
git commit -m "feat(model): add template models for cycle creation"
```

---

## Phase 3: Rewrite CycleTemplates

### Task 7: Create 3-Day Full Body Template

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt`

**Step 1: Add imports at top of file**

```kotlin
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.CycleDayTemplate
import com.devil.phoenixproject.domain.model.RoutineTemplate
import com.devil.phoenixproject.domain.model.TemplateExercise
import com.devil.phoenixproject.domain.model.ProgressionRule
import com.devil.phoenixproject.domain.model.ProgramMode
```

**Step 2: Rewrite the threeDay() function in CycleTemplates object**

```kotlin
fun threeDay(): CycleTemplate {
    val fullBodyA = RoutineTemplate(
        name = "Full Body A",
        exercises = listOf(
            TemplateExercise("Squat", 3, 8, ProgramMode.OldSchool),
            TemplateExercise("Bench Press", 3, 8, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row", 3, 8, ProgramMode.OldSchool),
            TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Calf Raise", 3, 15, ProgramMode.TimeUnderTension)
        )
    )
    val fullBodyB = RoutineTemplate(
        name = "Full Body B",
        exercises = listOf(
            TemplateExercise("Deadlift", 3, 5, ProgramMode.OldSchool),
            TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Plank", 3, null, ProgramMode.OldSchool)  // null reps = timed
        )
    )
    val fullBodyC = RoutineTemplate(
        name = "Full Body C",
        exercises = listOf(
            TemplateExercise("Front Squat", 3, 8, ProgramMode.OldSchool),
            TemplateExercise("Bench Press - Wide Grip", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Shrug", 3, 12, ProgramMode.TimeUnderTension)
        )
    )

    return CycleTemplate(
        id = "template_3day_fullbody",
        name = "3-Day Full Body",
        description = "Full body workout 3 times per week. Great for beginners or those with limited training time.",
        days = listOf(
            CycleDayTemplate.training(1, "Full Body A", fullBodyA),
            CycleDayTemplate.rest(2),
            CycleDayTemplate.training(3, "Full Body B", fullBodyB),
            CycleDayTemplate.rest(4),
            CycleDayTemplate.training(5, "Full Body C", fullBodyC),
            CycleDayTemplate.rest(6),
            CycleDayTemplate.rest(7)
        ),
        progressionRule = ProgressionRule.percentage(2.5f)
    )
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (may have errors if ProgramMode import missing - fix as needed)

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt
git commit -m "feat(templates): add full 3-Day Full Body template with exercises"
```

---

### Task 8: Create Push/Pull/Legs Template

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt`

**Step 1: Rewrite the pushPullLegs() function**

```kotlin
fun pushPullLegs(): CycleTemplate {
    val pushA = RoutineTemplate(
        name = "Push A",
        exercises = listOf(
            TemplateExercise("Bench Press", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val pullA = RoutineTemplate(
        name = "Pull A",
        exercises = listOf(
            TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row - Reverse Grip", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Face Pull", 3, 15, ProgramMode.TimeUnderTension),
            TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
            TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val legsA = RoutineTemplate(
        name = "Legs A",
        exercises = listOf(
            TemplateExercise("Squat", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Lunges", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Leg Extension", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Calf Raise", 3, 15, ProgramMode.TimeUnderTension)
        )
    )
    val pushB = RoutineTemplate(
        name = "Push B",
        exercises = listOf(
            TemplateExercise("Shoulder Press", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Lateral Raise", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val pullB = RoutineTemplate(
        name = "Pull B",
        exercises = listOf(
            TemplateExercise("Bent Over Row", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Upright Row", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Face Pull", 3, 15, ProgramMode.TimeUnderTension),
            TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
            TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val legsB = RoutineTemplate(
        name = "Legs B",
        exercises = listOf(
            TemplateExercise("Deadlift", 5, 5, ProgramMode.OldSchool),
            TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Leg Curl", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Calf Raise", 3, 15, ProgramMode.TimeUnderTension)
        )
    )

    return CycleTemplate(
        id = "template_ppl",
        name = "Push/Pull/Legs",
        description = "6-day split focusing on push, pull, and leg movements. Ideal for intermediate lifters seeking muscle growth.",
        days = listOf(
            CycleDayTemplate.training(1, "Push A", pushA),
            CycleDayTemplate.training(2, "Pull A", pullA),
            CycleDayTemplate.training(3, "Legs A", legsA),
            CycleDayTemplate.training(4, "Push B", pushB),
            CycleDayTemplate.training(5, "Pull B", pullB),
            CycleDayTemplate.training(6, "Legs B", legsB)
        ),
        progressionRule = ProgressionRule.percentage(2.5f)
    )
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt
git commit -m "feat(templates): add full Push/Pull/Legs template with exercises"
```

---

### Task 9: Create Upper/Lower Template

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt`

**Step 1: Rewrite the upperLower() function**

```kotlin
fun upperLower(): CycleTemplate {
    val upperA = RoutineTemplate(
        name = "Upper A",
        exercises = listOf(
            TemplateExercise("Bench Press", 4, 6, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row", 4, 6, ProgramMode.OldSchool),
            TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bicep Curl", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val lowerA = RoutineTemplate(
        name = "Lower A",
        exercises = listOf(
            TemplateExercise("Squat", 4, 6, ProgramMode.OldSchool),
            TemplateExercise("Romanian Deadlift", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Lunges", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Calf Raise", 3, 15, ProgramMode.TimeUnderTension)
        )
    )
    val upperB = RoutineTemplate(
        name = "Upper B",
        exercises = listOf(
            TemplateExercise("Incline Bench Press", 4, 8, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row - Wide Grip", 4, 8, ProgramMode.OldSchool),
            TemplateExercise("Arnold Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Hammer Curl", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Skull Crusher", 3, 12, ProgramMode.TimeUnderTension)
        )
    )
    val lowerB = RoutineTemplate(
        name = "Lower B",
        exercises = listOf(
            TemplateExercise("Deadlift", 4, 5, ProgramMode.OldSchool),
            TemplateExercise("Front Squat", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bulgarian Split Squat", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Glute Kickback", 3, 12, ProgramMode.TimeUnderTension)
        )
    )

    return CycleTemplate(
        id = "template_upper_lower",
        name = "Upper/Lower",
        description = "4-day split alternating between upper and lower body. Balanced approach for strength and hypertrophy.",
        days = listOf(
            CycleDayTemplate.training(1, "Upper A", upperA),
            CycleDayTemplate.training(2, "Lower A", lowerA),
            CycleDayTemplate.rest(3),
            CycleDayTemplate.training(4, "Upper B", upperB),
            CycleDayTemplate.training(5, "Lower B", lowerB)
        ),
        progressionRule = ProgressionRule.percentage(2.5f)
    )
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt
git commit -m "feat(templates): add full Upper/Lower template with exercises"
```

---

### Task 10: Create 5/3/1 (Wendler) Template

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt`

**Step 1: Add new fiveThreeOne() function to CycleTemplates object**

```kotlin
fun fiveThreeOne(): CycleTemplate {
    val benchDay = RoutineTemplate(
        name = "Bench Day",
        exercises = listOf(
            TemplateExercise("Bench Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
            TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Plank", 3, null, ProgramMode.OldSchool)
        )
    )
    val squatDay = RoutineTemplate(
        name = "Squat Day",
        exercises = listOf(
            TemplateExercise("Squat", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
            TemplateExercise("Shoulder Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Face Pull", 3, 15, ProgramMode.TimeUnderTension),
            TemplateExercise("Lunges", 3, 10, ProgramMode.OldSchool)
        )
    )
    val pressDay = RoutineTemplate(
        name = "Press Day",
        exercises = listOf(
            TemplateExercise("Shoulder Press", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
            TemplateExercise("Tricep Extension", 3, 12, ProgramMode.TimeUnderTension),
            TemplateExercise("Bent Over Row", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Ab Crunch", 3, 15, ProgramMode.OldSchool)
        )
    )
    val deadliftDay = RoutineTemplate(
        name = "Deadlift Day",
        exercises = listOf(
            TemplateExercise("Deadlift", 3, null, ProgramMode.OldSchool, isPercentageBased = true),
            TemplateExercise("Incline Bench Press", 3, 10, ProgramMode.OldSchool),
            TemplateExercise("Shrug", 3, 12, ProgramMode.OldSchool),
            TemplateExercise("Back Extension", 3, 12, ProgramMode.OldSchool)
        )
    )

    return CycleTemplate(
        id = "template_531",
        name = "5/3/1 (Wendler)",
        description = "Strength-focused 4-day program with percentage-based main lifts. Runs in 4-week cycles with progressive weight increases.",
        days = listOf(
            CycleDayTemplate.training(1, "Bench", benchDay),
            CycleDayTemplate.training(2, "Squat", squatDay),
            CycleDayTemplate.training(3, "Press", pressDay),
            CycleDayTemplate.training(4, "Deadlift", deadliftDay)
        ),
        progressionRule = ProgressionRule.fiveThreeOne(),
        requiresOneRepMax = true,
        mainLifts = listOf("Bench Press", "Squat", "Shoulder Press", "Deadlift")
    )
}
```

**Step 2: Update the all() function to include the new template**

```kotlin
fun all(): List<CycleTemplate> = listOf(
    threeDay(),
    pushPullLegs(),
    upperLower(),
    fiveThreeOne()
)
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/TrainingCycleMigration.kt
git commit -m "feat(templates): add 5/3/1 Wendler template with percentage-based main lifts"
```

---

## Phase 4: Template-to-Cycle Conversion

### Task 11: Create TemplateConverter Utility

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt`

**Step 1: Create the converter class**

```kotlin
package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*

/**
 * Converts CycleTemplate to actual TrainingCycle with resolved exercises.
 */
class TemplateConverter(
    private val exerciseRepository: ExerciseRepository
) {
    /**
     * Convert a template to a real TrainingCycle.
     * Resolves exercise names to actual Exercise entities from the library.
     *
     * @param template The template to convert
     * @param modeOverrides Map of exercise name to user-selected mode (from confirmation screen)
     * @return Pair of TrainingCycle and list of created Routines
     */
    suspend fun convert(
        template: CycleTemplate,
        modeOverrides: Map<String, ProgramMode> = emptyMap()
    ): ConversionResult {
        val cycleId = generateUUID()
        val routines = mutableListOf<Routine>()
        val warnings = mutableListOf<String>()

        val days = template.days.map { dayTemplate ->
            if (dayTemplate.isRestDay || dayTemplate.routine == null) {
                CycleDay.restDay(
                    cycleId = cycleId,
                    dayNumber = dayTemplate.dayNumber,
                    name = dayTemplate.name
                )
            } else {
                val routine = convertRoutine(dayTemplate.routine, modeOverrides, warnings)
                routines.add(routine)

                CycleDay.create(
                    cycleId = cycleId,
                    dayNumber = dayTemplate.dayNumber,
                    name = dayTemplate.name,
                    routineId = routine.id
                )
            }
        }

        val cycle = TrainingCycle(
            id = cycleId,
            name = template.name,
            description = template.description,
            days = days,
            createdAt = currentTimeMillis(),
            isActive = false,
            progressionRule = template.progressionRule,
            weekNumber = 1
        )

        return ConversionResult(cycle, routines, warnings)
    }

    private suspend fun convertRoutine(
        template: RoutineTemplate,
        modeOverrides: Map<String, ProgramMode>,
        warnings: MutableList<String>
    ): Routine {
        val routineId = generateUUID()
        val exercises = mutableListOf<RoutineExercise>()

        template.exercises.forEachIndexed { index, templateExercise ->
            val exercise = exerciseRepository.findByName(templateExercise.exerciseName)

            if (exercise == null) {
                warnings.add("Exercise not found: ${templateExercise.exerciseName}")
                return@forEachIndexed
            }

            val mode = modeOverrides[templateExercise.exerciseName]
                ?: templateExercise.suggestedMode

            val setReps = if (templateExercise.reps != null) {
                List(templateExercise.sets) { templateExercise.reps }
            } else {
                List(templateExercise.sets) { null }  // AMRAP or timed
            }

            exercises.add(
                RoutineExercise(
                    id = generateUUID(),
                    exercise = exercise,
                    cableConfig = exercise.defaultCableConfig,
                    orderIndex = index,
                    setReps = setReps,
                    weightPerCableKg = 20f,  // Default starting weight
                    workoutType = WorkoutType.Program(mode)
                )
            )
        }

        return Routine(
            id = routineId,
            name = template.name,
            description = "Generated from ${template.name} template",
            exercises = exercises
        )
    }
}

data class ConversionResult(
    val cycle: TrainingCycle,
    val routines: List<Routine>,
    val warnings: List<String>
)
```

**Step 2: Add findByName to ExerciseRepository interface**

In `ExerciseRepository.kt`, add:

```kotlin
suspend fun findByName(name: String): Exercise?
```

**Step 3: Implement findByName in the repository implementation**

```kotlin
override suspend fun findByName(name: String): Exercise? {
    return queries.findExerciseByName(name)
        .executeAsOneOrNull()
        ?.toExercise()
}
```

**Step 4: Add query to database schema**

In `VitruvianDatabase.sq`:

```sql
findExerciseByName:
SELECT * FROM Exercise WHERE name = ? LIMIT 1;
```

**Step 5: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/
git add shared/src/commonMain/sqldelight/
git commit -m "feat(usecase): add TemplateConverter for cycle creation from templates"
```

---

## Phase 5: UI - Mode Confirmation Screen

### Task 12: Create ModeConfirmationScreen

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt`

**Step 1: Create the screen composable**

```kotlin
package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.TemplateExercise

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeConfirmationScreen(
    template: CycleTemplate,
    onConfirm: (Map<String, ProgramMode>) -> Unit,
    onBack: () -> Unit
) {
    // Track mode overrides
    val modeOverrides = remember { mutableStateMapOf<String, ProgramMode>() }

    // Initialize with suggested modes
    LaunchedEffect(template) {
        template.days
            .filter { !it.isRestDay && it.routine != null }
            .flatMap { it.routine!!.exercises }
            .forEach { exercise ->
                modeOverrides[exercise.exerciseName] = exercise.suggestedMode
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Exercises") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onConfirm(modeOverrides.toMap()) }) {
                        Icon(Icons.Default.Check, "Create Cycle")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            template.days
                .filter { !it.isRestDay && it.routine != null }
                .forEach { day ->
                    item(key = "header_${day.dayNumber}") {
                        Text(
                            text = day.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(
                        items = day.routine!!.exercises,
                        key = { "${day.dayNumber}_${it.exerciseName}" }
                    ) { exercise ->
                        ExerciseModeRow(
                            exercise = exercise,
                            selectedMode = modeOverrides[exercise.exerciseName] ?: exercise.suggestedMode,
                            onModeChange = { mode ->
                                modeOverrides[exercise.exerciseName] = mode
                            }
                        )
                    }
                }
        }
    }
}

@Composable
private fun ExerciseModeRow(
    exercise: TemplateExercise,
    selectedMode: ProgramMode,
    onModeChange: (ProgramMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${exercise.sets}×${exercise.reps ?: "AMRAP"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selectedMode.displayName)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    ProgramMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.displayName) },
                            onClick = {
                                onModeChange(mode)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// Extension for display name
private val ProgramMode.displayName: String
    get() = when (this) {
        ProgramMode.OldSchool -> "Old School"
        ProgramMode.TimeUnderTension -> "TUT"
        ProgramMode.Pump -> "Pump"
        ProgramMode.EccentricOnly -> "Eccentric"
    }
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt
git commit -m "feat(ui): add ModeConfirmationScreen for template exercise review"
```

---

### Task 13: Create OneRepMaxInputScreen

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/OneRepMaxInputScreen.kt`

**Step 1: Create the screen composable**

```kotlin
package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneRepMaxInputScreen(
    mainLifts: List<String>,
    existingValues: Map<String, Float>,
    onConfirm: (Map<String, Float>) -> Unit,
    onBack: () -> Unit
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            mainLifts.forEach { lift ->
                this[lift] = existingValues[lift]?.toString() ?: ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enter Your 1RM") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val parsed = values.mapNotNull { (k, v) ->
                                v.toFloatOrNull()?.let { k to it }
                            }.toMap()
                            onConfirm(parsed)
                        }
                    ) {
                        Icon(Icons.Default.Check, "Confirm")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Enter your 1RM (one rep max) or a recent heavy single for each main lift. This will be used to calculate your working weights.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "5/3/1 uses 90% of your 1RM as your 'training max' for calculations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            mainLifts.forEach { lift ->
                OneRepMaxField(
                    liftName = lift,
                    value = values[lift] ?: "",
                    onValueChange = { values[lift] = it }
                )
            }
        }
    }
}

@Composable
private fun OneRepMaxField(
    liftName: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            // Only allow valid float input
            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                onValueChange(newValue)
            }
        },
        label = { Text(liftName) },
        suffix = { Text("kg") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/OneRepMaxInputScreen.kt
git commit -m "feat(ui): add OneRepMaxInputScreen for 5/3/1 setup"
```

---

## Phase 6: Integration

### Task 14: Update TrainingCyclesScreen to Use New Flow

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt`

**Step 1: Update TemplateSelectionDialog to use CycleTemplate**

Find the existing `TemplateSelectionDialog` and update it to:
- Use `CycleTemplate` instead of `TrainingCycle`
- Show enhanced descriptions
- Pass selected template to parent for mode confirmation

**Step 2: Add state management for template creation flow**

Add these state variables near the top of the screen composable:

```kotlin
var selectedTemplate by remember { mutableStateOf<CycleTemplate?>(null) }
var showModeConfirmation by remember { mutableStateOf(false) }
var showOneRepMaxInput by remember { mutableStateOf(false) }
var pendingModeOverrides by remember { mutableStateOf<Map<String, ProgramMode>>(emptyMap()) }
```

**Step 3: Add navigation between screens**

```kotlin
// In the main content area, add conditional rendering:
when {
    showOneRepMaxInput && selectedTemplate != null -> {
        OneRepMaxInputScreen(
            mainLifts = selectedTemplate!!.mainLifts,
            existingValues = emptyMap(), // TODO: Load from repository
            onConfirm = { oneRepMaxValues ->
                // Save 1RM values and create cycle
                scope.launch {
                    oneRepMaxValues.forEach { (lift, value) ->
                        // exerciseRepository.updateOneRepMax(...)
                    }
                    createCycleFromTemplate(selectedTemplate!!, pendingModeOverrides)
                }
                showOneRepMaxInput = false
                selectedTemplate = null
            },
            onBack = {
                showOneRepMaxInput = false
                showModeConfirmation = true
            }
        )
    }
    showModeConfirmation && selectedTemplate != null -> {
        ModeConfirmationScreen(
            template = selectedTemplate!!,
            onConfirm = { modeOverrides ->
                pendingModeOverrides = modeOverrides
                if (selectedTemplate!!.requiresOneRepMax) {
                    showModeConfirmation = false
                    showOneRepMaxInput = true
                } else {
                    scope.launch {
                        createCycleFromTemplate(selectedTemplate!!, modeOverrides)
                    }
                    showModeConfirmation = false
                    selectedTemplate = null
                }
            },
            onBack = {
                showModeConfirmation = false
                selectedTemplate = null
            }
        )
    }
    else -> {
        // Existing main content
    }
}
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (may require fixing imports and adding missing functions)

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt
git commit -m "feat(ui): integrate template creation flow with mode confirmation"
```

---

### Task 15: Add Koin DI Registration for TemplateConverter

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`

**Step 1: Register TemplateConverter**

Add to the Koin module:

```kotlin
single { TemplateConverter(get()) }
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(di): register TemplateConverter in Koin"
```

---

### Task 16: Full Integration Test

**Step 1: Build full Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Manual test checklist**

- [ ] Open Cycles screen
- [ ] Tap "New Cycle"
- [ ] Select "3-Day Full Body" template
- [ ] Mode confirmation screen shows all exercises with dropdowns
- [ ] Change a mode and confirm
- [ ] Cycle is created with routines
- [ ] Select "5/3/1" template
- [ ] Mode confirmation screen appears
- [ ] After confirming, 1RM input screen appears
- [ ] Enter values and confirm
- [ ] Cycle is created

**Step 3: Fix any issues found during testing**

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat(cycles): complete training cycles enhancement with templates and 5/3/1"
```

---

## Summary

This plan implements:

1. **Data Model** (Tasks 1-6): oneRepMaxKg on Exercise, ProgressionRule, template models
2. **Templates** (Tasks 7-10): Full exercise content for 3-Day, PPL, Upper/Lower, 5/3/1
3. **Conversion** (Task 11): TemplateConverter to create real cycles from templates
4. **UI** (Tasks 12-14): Mode confirmation and 1RM input screens
5. **Integration** (Tasks 15-16): DI registration and full testing

Each task follows TDD where applicable and includes commits at logical checkpoints.

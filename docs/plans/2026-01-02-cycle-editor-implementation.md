# Cycle Editor Playlist Redesign - Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign the Cycle Editor UI from a two-panel layout to a single-column "playlist editor" pattern with cycle-wide progression settings.

**Architecture:** Replace toggle-based `isRestDay` with sealed class `CycleItem`. Remove per-day modifiers, add `CycleProgression` table for cycle-wide progression rules. Rewrite `CycleEditorScreen` as single-column list with swipe actions.

**Tech Stack:** Kotlin, Compose Multiplatform, SQLDelight, Material3 SwipeToDismissBox, sh.calvin.reorderable

---

## Task 1: Add CycleItem Sealed Class and CycleProgression Model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt`

**Step 1: Add the sealed class and progression model**

Add after the existing `CycleDay` class (around line 122):

```kotlin
/**
 * UI-facing representation of a cycle day.
 * Sealed class forces distinct handling of workout vs rest days.
 */
sealed class CycleItem {
    abstract val id: String
    abstract val dayNumber: Int

    data class Workout(
        override val id: String,
        override val dayNumber: Int,
        val routineId: String,
        val routineName: String,
        val exerciseCount: Int,
        val estimatedMinutes: Int? = null
    ) : CycleItem()

    data class Rest(
        override val id: String,
        override val dayNumber: Int,
        val note: String? = null
    ) : CycleItem()

    companion object {
        /**
         * Convert a CycleDay to a CycleItem.
         * Requires routine info for workout days.
         */
        fun fromCycleDay(
            day: CycleDay,
            routineName: String?,
            exerciseCount: Int
        ): CycleItem {
            return if (day.isRestDay || day.routineId == null) {
                Rest(
                    id = day.id,
                    dayNumber = day.dayNumber,
                    note = day.name
                )
            } else {
                Workout(
                    id = day.id,
                    dayNumber = day.dayNumber,
                    routineId = day.routineId,
                    routineName = routineName ?: "Unknown Routine",
                    exerciseCount = exerciseCount
                )
            }
        }
    }
}

/**
 * Cycle-wide progression settings.
 * Applied every N cycle completions.
 */
data class CycleProgression(
    val cycleId: String,
    val frequencyCycles: Int = 2,
    val weightIncreasePercent: Float? = null,
    val echoLevelIncrease: Boolean = false,
    val eccentricLoadIncreasePercent: Int? = null
) {
    companion object {
        fun default(cycleId: String) = CycleProgression(cycleId = cycleId)
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt
git commit -m "feat(model): add CycleItem sealed class and CycleProgression"
```

---

## Task 2: Update Database Schema

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`

**Step 1: Add CycleProgression table and update CycleDay**

Find the Training Cycles section (around line 782) and add after the `CycleProgress` table (around line 822):

```sql
-- Cycle Progression Settings (cycle-wide, replaces per-day modifiers)
CREATE TABLE CycleProgression (
    cycle_id TEXT PRIMARY KEY NOT NULL,
    frequency_cycles INTEGER NOT NULL DEFAULT 2,
    weight_increase_percent REAL,
    echo_level_increase INTEGER NOT NULL DEFAULT 0,
    eccentric_load_increase_percent INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);
```

**Step 2: Add CycleProgression queries**

Add after the existing CycleProgress queries (around line 948):

```sql
-- CycleProgression Queries
selectCycleProgression:
SELECT * FROM CycleProgression WHERE cycle_id = ?;

insertCycleProgression:
INSERT INTO CycleProgression (cycle_id, frequency_cycles, weight_increase_percent, echo_level_increase, eccentric_load_increase_percent)
VALUES (?, ?, ?, ?, ?);

updateCycleProgression:
UPDATE CycleProgression
SET frequency_cycles = ?, weight_increase_percent = ?, echo_level_increase = ?, eccentric_load_increase_percent = ?
WHERE cycle_id = ?;

upsertCycleProgression:
INSERT OR REPLACE INTO CycleProgression (cycle_id, frequency_cycles, weight_increase_percent, echo_level_increase, eccentric_load_increase_percent)
VALUES (?, ?, ?, ?, ?);

deleteCycleProgression:
DELETE FROM CycleProgression WHERE cycle_id = ?;
```

**Step 3: Add note column to CycleDay**

Find the `CycleDay` CREATE TABLE statement (around line 794) and add `note` column:

```sql
-- Cycle Days (replaces ProgramDay - day 1, 2, 3... instead of Mon/Tue/Wed)
CREATE TABLE CycleDay (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    day_number INTEGER NOT NULL,
    name TEXT,
    routine_id TEXT,
    is_rest_day INTEGER NOT NULL DEFAULT 0,
    note TEXT,
    echo_level TEXT,
    eccentric_load_percent INTEGER,
    weight_progression_percent REAL,
    rep_modifier INTEGER,
    rest_time_override_seconds INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE,
    FOREIGN KEY (routine_id) REFERENCES Routine(id) ON DELETE SET NULL
);
```

**Step 4: Verify SQLDelight generation**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
git commit -m "feat(db): add CycleProgression table and note column to CycleDay"
```

---

## Task 3: Update Repository Interface

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/TrainingCycleRepository.kt`

**Step 1: Add progression methods to interface**

Add after the existing `checkAndAutoAdvance` method (around line 128):

```kotlin
    // ==================== Cycle Progression ====================

    /**
     * Get progression settings for a cycle.
     */
    suspend fun getCycleProgression(cycleId: String): CycleProgression?

    /**
     * Save or update progression settings for a cycle.
     */
    suspend fun saveCycleProgression(progression: CycleProgression)

    /**
     * Delete progression settings for a cycle.
     */
    suspend fun deleteCycleProgression(cycleId: String)

    // ==================== Cycle Items (UI-facing) ====================

    /**
     * Get cycle days as CycleItems with routine info.
     * This is the primary method for the new playlist-style UI.
     */
    suspend fun getCycleItems(cycleId: String): List<CycleItem>
```

**Step 2: Add import for CycleProgression and CycleItem**

Update imports at top of file:

```kotlin
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.TrainingCycle
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: FAILURE (implementation not yet updated - expected)

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/TrainingCycleRepository.kt
git commit -m "feat(repo): add progression and CycleItem methods to interface"
```

---

## Task 4: Implement Repository Methods

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightTrainingCycleRepository.kt`

**Step 1: Read the current implementation to understand patterns**

First read the file to see existing patterns (this is for the implementer).

**Step 2: Add getCycleProgression implementation**

Find the end of the existing methods and add:

```kotlin
    // ==================== Cycle Progression ====================

    override suspend fun getCycleProgression(cycleId: String): CycleProgression? {
        return queries.selectCycleProgression(cycleId).executeAsOneOrNull()?.let { row ->
            CycleProgression(
                cycleId = row.cycle_id,
                frequencyCycles = row.frequency_cycles.toInt(),
                weightIncreasePercent = row.weight_increase_percent?.toFloat(),
                echoLevelIncrease = row.echo_level_increase != 0L,
                eccentricLoadIncreasePercent = row.eccentric_load_increase_percent?.toInt()
            )
        }
    }

    override suspend fun saveCycleProgression(progression: CycleProgression) {
        queries.upsertCycleProgression(
            cycle_id = progression.cycleId,
            frequency_cycles = progression.frequencyCycles.toLong(),
            weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
            echo_level_increase = if (progression.echoLevelIncrease) 1L else 0L,
            eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong()
        )
    }

    override suspend fun deleteCycleProgression(cycleId: String) {
        queries.deleteCycleProgression(cycleId)
    }

    override suspend fun getCycleItems(cycleId: String): List<CycleItem> {
        val days = getCycleDays(cycleId)
        val routineIds = days.mapNotNull { it.routineId }.distinct()

        // Fetch routine info for all referenced routines
        val routineInfo = mutableMapOf<String, Pair<String, Int>>() // id -> (name, exerciseCount)
        routineIds.forEach { routineId ->
            queries.selectRoutineById(routineId).executeAsOneOrNull()?.let { routine ->
                val exerciseCount = queries.selectExercisesByRoutine(routineId).executeAsList().size
                routineInfo[routineId] = Pair(routine.name, exerciseCount)
            }
        }

        return days.map { day ->
            val info = day.routineId?.let { routineInfo[it] }
            CycleItem.fromCycleDay(
                day = day,
                routineName = info?.first,
                exerciseCount = info?.second ?: 0
            )
        }
    }
```

**Step 3: Add missing import**

Add to imports at top of file:

```kotlin
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.CycleProgression
```

**Step 4: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightTrainingCycleRepository.kt
git commit -m "feat(repo): implement progression and CycleItem methods"
```

---

## Task 5: Create WorkoutDayRow Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/WorkoutDayRow.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleItem

@Composable
fun WorkoutDayRow(
    workout: CycleItem.Workout,
    onTap: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder",
                modifier = dragModifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Day ${workout.dayNumber}: ${workout.routineName}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${workout.exerciseCount} exercises")
                        workout.estimatedMinutes?.let { append(" • ~${it} min") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/WorkoutDayRow.kt
git commit -m "feat(ui): add WorkoutDayRow component"
```

---

## Task 6: Create RestDayRow Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/RestDayRow.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleItem

@Composable
fun RestDayRow(
    rest: CycleItem.Rest,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder",
                modifier = dragModifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Text(
                text = "Day ${rest.dayNumber}: ${rest.note ?: "Rest"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            // Moon icon
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = "Rest day",
                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/RestDayRow.kt
git commit -m "feat(ui): add RestDayRow component"
```

---

## Task 7: Create AddDaySheet Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/AddDaySheet.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Routine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDaySheet(
    routines: List<Routine>,
    recentRoutineIds: List<String>,
    onSelectRoutine: (Routine) -> Unit,
    onAddRestDay: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    val recentRoutines = recentRoutineIds.mapNotNull { id -> routines.find { it.id == id } }
    val otherRoutines = routines.filterNot { it.id in recentRoutineIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Add to Cycle",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Quick action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Workout button
                OutlinedCard(
                    modifier = Modifier.weight(1f),
                    onClick = { /* Scroll to routines - handled by showing the list */ }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Workout",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Rest day button
                OutlinedCard(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAddRestDay()
                        onDismiss()
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Rest Day",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Routine list
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Recent routines section
                if (recentRoutines.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recent Routines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(recentRoutines) { routine ->
                        RoutineListItem(
                            routine = routine,
                            onClick = {
                                onSelectRoutine(routine)
                                onDismiss()
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }

                // All routines section
                if (otherRoutines.isNotEmpty()) {
                    item {
                        Text(
                            text = if (recentRoutines.isNotEmpty()) "All Routines" else "Routines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(otherRoutines) { routine ->
                        RoutineListItem(
                            routine = routine,
                            onClick = {
                                onSelectRoutine(routine)
                                onDismiss()
                            }
                        )
                    }
                }

                // Empty state
                if (routines.isEmpty()) {
                    item {
                        Text(
                            text = "No routines created yet.\nCreate a routine first to add workout days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineListItem(
    routine: Routine,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/AddDaySheet.kt
git commit -m "feat(ui): add AddDaySheet component"
```

---

## Task 8: Create ProgressionSettingsSheet Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/ProgressionSettingsSheet.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleProgression

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionSettingsSheet(
    progression: CycleProgression,
    currentRotation: Int,
    onSave: (CycleProgression) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    var frequency by remember { mutableStateOf(progression.frequencyCycles) }
    var weightEnabled by remember { mutableStateOf(progression.weightIncreasePercent != null) }
    var weightPercent by remember { mutableStateOf(progression.weightIncreasePercent ?: 2.5f) }
    var echoEnabled by remember { mutableStateOf(progression.echoLevelIncrease) }
    var eccentricEnabled by remember { mutableStateOf(progression.eccentricLoadIncreasePercent != null) }
    var eccentricPercent by remember { mutableStateOf(progression.eccentricLoadIncreasePercent ?: 5) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Progression Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Frequency selector
            Text(
                text = "Apply progression every:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { if (frequency > 1) frequency-- },
                    enabled = frequency > 1
                ) {
                    Text("◄", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = "$frequency cycle completions",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                IconButton(
                    onClick = { if (frequency < 10) frequency++ },
                    enabled = frequency < 10
                ) {
                    Text("►", style = MaterialTheme.typography.titleLarge)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Weight increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = weightEnabled,
                    onCheckedChange = { weightEnabled = it }
                )
                Text(
                    text = "Increase weight by",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            if (weightEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = weightPercent,
                        onValueChange = { weightPercent = it },
                        valueRange = 0.5f..10f,
                        steps = 18,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${String.format("%.1f", weightPercent)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(50.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Echo level increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = echoEnabled,
                    onCheckedChange = { echoEnabled = it }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Increase Echo level by 1",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Applies to Echo-mode exercises",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Eccentric load increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = eccentricEnabled,
                    onCheckedChange = { eccentricEnabled = it }
                )
                Text(
                    text = "Increase eccentric load by",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            if (eccentricEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = eccentricPercent.toFloat(),
                        onValueChange = { eccentricPercent = it.toInt() },
                        valueRange = 1f..20f,
                        steps = 18,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${eccentricPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(50.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Progress indicator
            val cyclesUntilNext = frequency - (currentRotation % frequency)
            Text(
                text = "Current rotation: $currentRotation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Next progression applies after $cyclesUntilNext more cycle${if (cyclesUntilNext != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Apply button
            Button(
                onClick = {
                    onSave(
                        CycleProgression(
                            cycleId = progression.cycleId,
                            frequencyCycles = frequency,
                            weightIncreasePercent = if (weightEnabled) weightPercent else null,
                            echoLevelIncrease = echoEnabled,
                            eccentricLoadIncreasePercent = if (eccentricEnabled) eccentricPercent else null
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Apply")
            }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/ProgressionSettingsSheet.kt
git commit -m "feat(ui): add ProgressionSettingsSheet component"
```

---

## Task 9: Create SwipeableCycleItem Wrapper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/SwipeableCycleItem.kt`

**Step 1: Create the swipe wrapper component**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.ui.theme.SignalError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableCycleItem(
    item: CycleItem,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onTap: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // Don't actually dismiss, we handle removal ourselves
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onDuplicate()
                    false // Don't dismiss
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { it * 0.4f }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> SignalError
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiary
                    else -> Color.Transparent
                },
                label = "swipe_color"
            )
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.ContentCopy
                else -> null
            }
            val label = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> "DELETE"
                SwipeToDismissBoxValue.StartToEnd -> "COPY"
                else -> ""
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                if (icon != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (direction == SwipeToDismissBoxValue.StartToEnd) {
                            Icon(icon, contentDescription = label, tint = Color.White)
                            Text(label, color = Color.White)
                        } else {
                            Text(label, color = Color.White)
                            Icon(icon, contentDescription = label, tint = Color.White)
                        }
                    }
                }
            }
        },
        content = {
            when (item) {
                is CycleItem.Workout -> WorkoutDayRow(
                    workout = item,
                    onTap = onTap,
                    dragModifier = dragModifier
                )
                is CycleItem.Rest -> RestDayRow(
                    rest = item,
                    dragModifier = dragModifier
                )
            }
        }
    )
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/SwipeableCycleItem.kt
git commit -m "feat(ui): add SwipeableCycleItem wrapper with delete/duplicate"
```

---

## Task 10: Rewrite CycleEditorScreen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt`

**Step 1: Complete rewrite of the screen**

This is a large file. Replace the entire content with the new implementation. Key changes:
- Remove two-panel layout
- Use single-column LazyColumn with SwipeableCycleItem
- Add FAB for "+ Add Day"
- Add progression settings gear icon
- Use new state model with CycleItem

```kotlin
package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.cycle.AddDaySheet
import com.devil.phoenixproject.presentation.components.cycle.ProgressionSettingsSheet
import com.devil.phoenixproject.presentation.components.cycle.SwipeableCycleItem
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// UI State for the new playlist-style editor
data class CycleEditorState(
    val cycleName: String = "",
    val description: String = "",
    val items: List<CycleItem> = emptyList(),
    val progression: CycleProgression? = null,
    val currentRotation: Int = 0,
    val showAddDaySheet: Boolean = false,
    val showProgressionSheet: Boolean = false,
    val editingItemIndex: Int? = null // For changing routine on a workout day
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String,
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>,
    initialDayCount: Int? = null
) {
    val repository: TrainingCycleRepository = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var state by remember { mutableStateOf(CycleEditorState()) }
    var hasInitialized by remember { mutableStateOf(false) }
    var recentRoutineIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Track deleted items for undo
    var lastDeletedItem by remember { mutableStateOf<Pair<Int, CycleItem>?>(null) }

    // Load data
    LaunchedEffect(cycleId, initialDayCount) {
        if (!hasInitialized) {
            if (cycleId != "new") {
                val cycle = repository.getCycleById(cycleId)
                val progress = repository.getCycleProgress(cycleId)
                val progression = repository.getCycleProgression(cycleId)
                val items = repository.getCycleItems(cycleId)

                if (cycle != null) {
                    state = state.copy(
                        cycleName = cycle.name,
                        description = cycle.description ?: "",
                        items = items,
                        progression = progression ?: CycleProgression.default(cycleId),
                        currentRotation = progress?.rotationCount ?: 0
                    )
                }
            } else {
                val dayCount = initialDayCount ?: 3
                val items = (1..dayCount).map { dayNum ->
                    CycleItem.Rest(
                        id = generateUUID(),
                        dayNumber = dayNum,
                        note = "Rest"
                    )
                }
                state = state.copy(
                    cycleName = "New Cycle",
                    items = items,
                    progression = CycleProgression.default("temp")
                )
            }
            hasInitialized = true
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val list = state.items.toMutableList()
        val moved = list.removeAt(from.index)
        list.add(to.index, moved)
        // Renumber days
        val renumbered = list.mapIndexed { i, item ->
            when (item) {
                is CycleItem.Workout -> item.copy(dayNumber = i + 1)
                is CycleItem.Rest -> item.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)
    }

    // Save function
    fun saveCycle() {
        scope.launch {
            val cycleIdToUse = if (cycleId == "new") generateUUID() else cycleId

            // Convert CycleItems back to CycleDays
            val days = state.items.map { item ->
                when (item) {
                    is CycleItem.Workout -> CycleDay.create(
                        id = item.id,
                        cycleId = cycleIdToUse,
                        dayNumber = item.dayNumber,
                        name = item.routineName,
                        routineId = item.routineId,
                        isRestDay = false
                    )
                    is CycleItem.Rest -> CycleDay.restDay(
                        id = item.id,
                        cycleId = cycleIdToUse,
                        dayNumber = item.dayNumber,
                        name = item.note
                    )
                }
            }

            val cycle = TrainingCycle.create(
                id = cycleIdToUse,
                name = state.cycleName,
                description = state.description.ifBlank { null },
                days = days
            )

            if (cycleId == "new") {
                repository.saveCycle(cycle)
            } else {
                repository.updateCycle(cycle)
            }

            // Save progression if configured
            state.progression?.let { prog ->
                repository.saveCycleProgression(prog.copy(cycleId = cycleIdToUse))
            }

            navController.popBackStack()
        }
    }

    // Add day functions
    fun addWorkoutDay(routine: Routine) {
        val exerciseCount = 0 // Will be fetched properly on reload
        val newItem = CycleItem.Workout(
            id = generateUUID(),
            dayNumber = state.items.size + 1,
            routineId = routine.id,
            routineName = routine.name,
            exerciseCount = exerciseCount
        )
        state = state.copy(items = state.items + newItem)

        // Track recent routines
        recentRoutineIds = (listOf(routine.id) + recentRoutineIds).distinct().take(3)
    }

    fun addRestDay() {
        val newItem = CycleItem.Rest(
            id = generateUUID(),
            dayNumber = state.items.size + 1,
            note = "Rest"
        )
        state = state.copy(items = state.items + newItem)
    }

    fun deleteItem(index: Int) {
        val item = state.items[index]
        lastDeletedItem = index to item

        val newList = state.items.toMutableList().apply { removeAt(index) }
        val renumbered = newList.mapIndexed { i, it ->
            when (it) {
                is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                is CycleItem.Rest -> it.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)

        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Day ${item.dayNumber} removed",
                actionLabel = "UNDO",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                lastDeletedItem?.let { (idx, deletedItem) ->
                    val list = state.items.toMutableList()
                    list.add(idx.coerceAtMost(list.size), deletedItem)
                    val renumberedList = list.mapIndexed { i, it ->
                        when (it) {
                            is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                            is CycleItem.Rest -> it.copy(dayNumber = i + 1)
                        }
                    }
                    state = state.copy(items = renumberedList)
                }
            }
            lastDeletedItem = null
        }
    }

    fun duplicateItem(index: Int) {
        val item = state.items[index]
        val duplicate = when (item) {
            is CycleItem.Workout -> item.copy(id = generateUUID(), dayNumber = index + 2)
            is CycleItem.Rest -> item.copy(id = generateUUID(), dayNumber = index + 2)
        }
        val newList = state.items.toMutableList().apply { add(index + 1, duplicate) }
        val renumbered = newList.mapIndexed { i, it ->
            when (it) {
                is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                is CycleItem.Rest -> it.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)
    }

    fun changeRoutine(index: Int, routine: Routine) {
        val item = state.items[index]
        if (item is CycleItem.Workout) {
            val updated = item.copy(routineId = routine.id, routineName = routine.name)
            val newList = state.items.toMutableList().apply { set(index, updated) }
            state = state.copy(items = newList, editingItemIndex = null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.cycleName,
                        onValueChange = { state = state.copy(cycleName = it) },
                        placeholder = { Text("Cycle Name") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                        ),
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        singleLine = true
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { saveCycle() }) {
                        Text("Save")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { state = state.copy(showAddDaySheet = true) }
            ) {
                Icon(Icons.Default.Add, "Add Day")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Description field
            OutlinedTextField(
                value = state.description,
                onValueChange = { state = state.copy(description = it) },
                label = { Text("Description (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            // Cycle length header with progression settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CYCLE LENGTH: ${state.items.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { state = state.copy(showProgressionSheet = true) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Progression Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Day list or empty state
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No days added yet.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Build your cycle by adding workout or rest days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { state = state.copy(showAddDaySheet = true) }
                            ) {
                                Text("+ Add Workout")
                            }
                            OutlinedButton(onClick = { addRestDay() }) {
                                Text("+ Add Rest")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                        ReorderableItem(reorderState, key = item.id) { isDragging ->
                            SwipeableCycleItem(
                                item = item,
                                onDelete = { deleteItem(index) },
                                onDuplicate = { duplicateItem(index) },
                                onTap = {
                                    if (item is CycleItem.Workout) {
                                        state = state.copy(editingItemIndex = index)
                                    }
                                },
                                dragModifier = Modifier.draggableHandle()
                            )
                        }
                    }
                    // Bottom padding for FAB
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Add Day Sheet
    if (state.showAddDaySheet) {
        AddDaySheet(
            routines = routines,
            recentRoutineIds = recentRoutineIds,
            onSelectRoutine = { routine ->
                addWorkoutDay(routine)
            },
            onAddRestDay = { addRestDay() },
            onDismiss = { state = state.copy(showAddDaySheet = false) }
        )
    }

    // Progression Settings Sheet
    if (state.showProgressionSheet) {
        state.progression?.let { prog ->
            ProgressionSettingsSheet(
                progression = prog,
                currentRotation = state.currentRotation,
                onSave = { newProgression ->
                    state = state.copy(progression = newProgression)
                },
                onDismiss = { state = state.copy(showProgressionSheet = false) }
            )
        }
    }

    // Edit routine sheet (reuse AddDaySheet in edit mode)
    state.editingItemIndex?.let { index ->
        val item = state.items.getOrNull(index)
        if (item is CycleItem.Workout) {
            AddDaySheet(
                routines = routines,
                recentRoutineIds = recentRoutineIds,
                onSelectRoutine = { routine ->
                    changeRoutine(index, routine)
                },
                onAddRestDay = { /* Not applicable in edit mode */ },
                onDismiss = { state = state.copy(editingItemIndex = null) }
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt
git commit -m "feat(ui): rewrite CycleEditorScreen with playlist pattern"
```

---

## Task 11: Delete CycleDayConfigSheet

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CycleDayConfigSheet.kt`

**Step 1: Delete the file**

```bash
rm shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CycleDayConfigSheet.kt
```

**Step 2: Search for and remove any imports/usages**

Search for `CycleDayConfigSheet` in the codebase. The main usage was in CycleEditorScreen which we just rewrote, so there should be no remaining usages.

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove CycleDayConfigSheet (replaced by cycle-wide progression)"
```

---

## Task 12: Build and Test

**Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 2: Run Android debug build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Manual testing checklist**

1. Open the app and navigate to Training Cycles
2. Create a new cycle
3. Verify empty state shows with "+ Add Workout" and "+ Add Rest" buttons
4. Tap "+ Add Day" FAB and verify bottom sheet appears
5. Add a workout day by selecting a routine
6. Add a rest day
7. Verify workout days show in standard card color, rest days show with blue tint
8. Swipe left on a day to delete, verify undo snackbar appears
9. Swipe right on a day to duplicate
10. Tap gear icon to open progression settings
11. Configure progression settings and save
12. Reorder days by dragging handles
13. Save cycle and verify it persists
14. Edit existing cycle and verify data loads correctly

**Step 4: Commit any fixes**

If any issues are found, fix them and commit:

```bash
git add -A
git commit -m "fix: address issues found during testing"
```

---

## Task 13: Final Cleanup and Documentation

**Step 1: Update any related documentation if needed**

**Step 2: Final commit**

```bash
git add -A
git commit -m "feat: complete cycle editor playlist redesign"
```

---

## Summary

| Task | Description | Estimated Complexity |
|------|-------------|---------------------|
| 1 | Add CycleItem sealed class and CycleProgression model | Low |
| 2 | Update database schema | Low |
| 3 | Update repository interface | Low |
| 4 | Implement repository methods | Medium |
| 5 | Create WorkoutDayRow component | Low |
| 6 | Create RestDayRow component | Low |
| 7 | Create AddDaySheet component | Medium |
| 8 | Create ProgressionSettingsSheet component | Medium |
| 9 | Create SwipeableCycleItem wrapper | Medium |
| 10 | Rewrite CycleEditorScreen | High |
| 11 | Delete CycleDayConfigSheet | Low |
| 12 | Build and test | Medium |
| 13 | Final cleanup | Low |

**Total: 13 tasks**

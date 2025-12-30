# Superset Container Redesign - Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transform supersets from implicit properties on exercises into first-class container entities with nested list UI, drag-and-drop, and color-coded visual distinction.

**Architecture:** New `Superset` table in SQLDelight, updated domain models treating supersets as containers, complete rewrite of RoutineEditorScreen with collapsible nested list UI and drag-and-drop between/within supersets.

**Tech Stack:** SQLDelight (database), Kotlin Multiplatform, Compose Multiplatform, sh.calvin.reorderable (drag-and-drop)

---

## Phase 1: Database Schema

### Task 1.1: Add Superset Table to SQLDelight

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq:104-148`

**Step 1: Add Superset table after Routine table (around line 112)**

Add this SQL after the Routine table definition:

```sql
-- Supersets (first-class container for grouped exercises)
CREATE TABLE Superset (
    id TEXT PRIMARY KEY NOT NULL,
    routineId TEXT NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    restBetweenSeconds INTEGER NOT NULL DEFAULT 10,
    orderIndex INTEGER NOT NULL,
    FOREIGN KEY (routineId) REFERENCES Routine(id) ON DELETE CASCADE
);

CREATE INDEX idx_superset_routine ON Superset(routineId);
```

**Step 2: Modify RoutineExercise table - add supersetId column**

In the RoutineExercise CREATE TABLE, replace lines 139-142:
```sql
    -- Superset support (KMP extension)
    supersetGroupId TEXT,
    supersetOrder INTEGER NOT NULL DEFAULT 0,
    supersetRestSeconds INTEGER NOT NULL DEFAULT 10,
```

With:
```sql
    -- Superset support (container model)
    supersetId TEXT,
    orderInSuperset INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (supersetId) REFERENCES Superset(id) ON DELETE SET NULL,
```

**Step 3: Run build to verify schema compiles**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat(db): add Superset table and update RoutineExercise schema"
```

---

### Task 1.2: Add Superset Queries

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`

**Step 1: Add queries after RoutineExercise queries (around line 495)**

```sql
-- ==================== SUPERSET QUERIES ====================

selectSupersetsByRoutine:
SELECT * FROM Superset WHERE routineId = ? ORDER BY orderIndex ASC;

selectSupersetById:
SELECT * FROM Superset WHERE id = ?;

insertSuperset:
INSERT INTO Superset (id, routineId, name, colorIndex, restBetweenSeconds, orderIndex)
VALUES (?, ?, ?, ?, ?, ?);

updateSuperset:
UPDATE Superset SET name = ?, colorIndex = ?, restBetweenSeconds = ?, orderIndex = ? WHERE id = ?;

deleteSuperset:
DELETE FROM Superset WHERE id = ?;

deleteSupersetsByRoutine:
DELETE FROM Superset WHERE routineId = ?;

countSupersetsInRoutine:
SELECT COUNT(*) FROM Superset WHERE routineId = ?;
```

**Step 2: Update insertRoutineExercise query to use new columns**

Replace the existing `insertRoutineExercise` query with:

```sql
insertRoutineExercise:
INSERT INTO RoutineExercise (
    id, routineId, exerciseName, exerciseMuscleGroup, exerciseEquipment, exerciseDefaultCableConfig,
    exerciseId, cableConfig, orderIndex, setReps, weightPerCableKg, setWeights, mode,
    eccentricLoad, echoLevel, progressionKg, restSeconds, duration, setRestSeconds,
    perSetRestTime, isAMRAP, supersetId, orderInSuperset
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

**Step 3: Run build to verify queries compile**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat(db): add Superset CRUD queries"
```

---

## Phase 2: Domain Model Updates

### Task 2.1: Create Superset Domain Entity

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`

**Step 1: Replace SupersetGroup with new Superset data class (lines 100-108)**

Replace:
```kotlin
data class SupersetGroup(
    val id: String,
    val name: String,  // e.g., "Superset A", "Superset B"
    val exercises: List<RoutineExercise>,
    val restBetweenExercises: Int = 10  // Rest between exercises within superset
) {
    /** Total number of sets (minimum sets among all exercises) */
    val sets: Int get() = exercises.minOfOrNull { it.sets } ?: 0
}
```

With:
```kotlin
/**
 * Superset colors for visual distinction
 */
object SupersetColors {
    const val INDIGO = 0   // #6366F1
    const val PINK = 1     // #EC4899
    const val GREEN = 2    // #10B981
    const val AMBER = 3    // #F59E0B

    fun next(existingIndices: Set<Int>): Int {
        for (i in 0..3) {
            if (i !in existingIndices) return i
        }
        return existingIndices.size % 4
    }
}

/**
 * First-class superset container entity.
 * Represents a group of exercises performed back-to-back.
 */
data class Superset(
    val id: String,
    val routineId: String,
    val name: String,
    val colorIndex: Int = SupersetColors.INDIGO,
    val restBetweenSeconds: Int = 10,
    val orderIndex: Int = 0,
    val exercises: List<RoutineExercise> = emptyList(),
    val isCollapsed: Boolean = false  // UI state only, not persisted
) {
    val isEmpty: Boolean get() = exercises.isEmpty()
    val exerciseCount: Int get() = exercises.size

    /** Total number of sets (minimum sets among all exercises) */
    val sets: Int get() = exercises.minOfOrNull { it.sets } ?: 0
}

/**
 * Generate a unique superset ID
 */
fun generateSupersetId(): String = "superset_${generateUUID()}"
```

**Step 2: Update RoutineExercise to use new superset fields (lines 29-57)**

Replace the superset fields:
```kotlin
    // Superset configuration
    val supersetGroupId: String? = null,  // Exercises with same ID are in same superset
    val supersetOrder: Int = 0,           // Order within the superset
    val supersetRestSeconds: Int = 10     // Rest between superset exercises (default 10s)
```

With:
```kotlin
    // Superset configuration (container model)
    val supersetId: String? = null,       // Reference to parent Superset container
    val orderInSuperset: Int = 0          // Position within the superset
```

**Step 3: Update isInSuperset computed property**

Replace:
```kotlin
    /** Returns true if this exercise is part of a superset */
    val isInSuperset: Boolean get() = supersetGroupId != null
```

With:
```kotlin
    /** Returns true if this exercise is part of a superset */
    val isInSuperset: Boolean get() = supersetId != null
```

**Step 4: Run build to check for compile errors**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: Errors in files using old superset fields (this is expected, we'll fix next)

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt
git commit -m "feat(domain): add Superset entity as first-class container"
```

---

### Task 2.2: Update RoutineItem Sealed Class

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt`

**Step 1: Update RoutineItem sealed class (lines 114-124)**

Replace:
```kotlin
sealed class RoutineItem {
    /** A single exercise not part of any superset */
    data class SingleExercise(val exercise: RoutineExercise) : RoutineItem() {
        val orderIndex: Int get() = exercise.orderIndex
    }

    /** A group of exercises forming a superset */
    data class Superset(val group: SupersetGroup) : RoutineItem() {
        val orderIndex: Int get() = group.exercises.minOfOrNull { it.orderIndex } ?: 0
    }
}
```

With:
```kotlin
/**
 * Sealed class representing an item in a routine's flat ordering.
 * Used for UI display where supersets and standalone exercises share ordering.
 */
sealed class RoutineItem {
    abstract val orderIndex: Int

    /** A single exercise not part of any superset */
    data class Single(val exercise: RoutineExercise) : RoutineItem() {
        override val orderIndex: Int get() = exercise.orderIndex
    }

    /** A superset container with exercises */
    data class SupersetItem(val superset: Superset) : RoutineItem() {
        override val orderIndex: Int get() = superset.orderIndex
    }
}
```

**Step 2: Update Routine data class to include supersets**

Modify the Routine data class (lines 6-14):
```kotlin
data class Routine(
    val id: String,
    val name: String,
    val description: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val supersets: List<Superset> = emptyList(),  // NEW: superset containers
    val createdAt: Long = currentTimeMillis(),
    val lastUsed: Long? = null,
    val useCount: Int = 0
) {
    /**
     * Get all items (supersets + standalone exercises) in display order.
     */
    fun getItems(): List<RoutineItem> {
        val supersetItems = supersets.map { superset ->
            RoutineItem.SupersetItem(
                superset.copy(exercises = exercises.filter { it.supersetId == superset.id }
                    .sortedBy { it.orderInSuperset })
            )
        }

        val standaloneItems = exercises
            .filter { it.supersetId == null }
            .map { RoutineItem.Single(it) }

        return (supersetItems + standaloneItems).sortedBy { it.orderIndex }
    }
}
```

**Step 3: Remove old getGroupedExercises and related extensions (lines 130-192)**

Delete the following functions:
- `fun Routine.getGroupedExercises(): List<RoutineItem>`
- `fun Routine.hasSupersets(): Boolean`
- `fun Routine.getSupersetGroupIds(): Set<String>`
- `fun Routine.getExercisesInSuperset(groupId: String): List<RoutineExercise>`
- `fun generateSupersetGroupId(): String`

Replace with simpler versions:
```kotlin
/**
 * Extension to check if a routine contains any supersets
 */
fun Routine.hasSupersets(): Boolean = supersets.isNotEmpty()

/**
 * Get exercises in a specific superset
 */
fun Routine.getExercisesInSuperset(supersetId: String): List<RoutineExercise> {
    return exercises
        .filter { it.supersetId == supersetId }
        .sortedBy { it.orderInSuperset }
}
```

**Step 4: Run build to see remaining errors**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: Errors in ViewModel and screens using old superset methods

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Routine.kt
git commit -m "feat(domain): update RoutineItem and Routine for container model"
```

---

## Phase 3: Repository & ViewModel Updates

### Task 3.1: Update RoutineRepository Mapping

**Files:**
- Find and modify: Repository file that maps database rows to domain models

**Step 1: Search for routine mapping code**

Run: `grep -r "RoutineExercise(" shared/src --include="*.kt" | head -20`

Locate the file that converts database rows to RoutineExercise objects.

**Step 2: Update the mapping to use new fields**

Replace references to:
- `supersetGroupId` → `supersetId`
- `supersetOrder` → `orderInSuperset`
- Remove `supersetRestSeconds` (now on Superset entity)

**Step 3: Add Superset loading logic**

When loading a Routine, also load its Supersets:
```kotlin
suspend fun getRoutineById(id: String): Routine? {
    val routineRow = database.selectRoutineById(id).executeAsOneOrNull() ?: return null
    val exerciseRows = database.selectExercisesByRoutine(id).executeAsList()
    val supersetRows = database.selectSupersetsByRoutine(id).executeAsList()

    val supersets = supersetRows.map { row ->
        Superset(
            id = row.id,
            routineId = row.routineId,
            name = row.name,
            colorIndex = row.colorIndex.toInt(),
            restBetweenSeconds = row.restBetweenSeconds.toInt(),
            orderIndex = row.orderIndex.toInt()
        )
    }

    val exercises = exerciseRows.map { row -> /* existing mapping */ }

    return Routine(
        id = routineRow.id,
        name = routineRow.name,
        description = routineRow.description,
        exercises = exercises,
        supersets = supersets,
        createdAt = routineRow.createdAt,
        lastUsed = routineRow.lastUsed,
        useCount = routineRow.useCount.toInt()
    )
}
```

**Step 4: Run build**

Run: `./gradlew :shared:compileKotlinMetadata`

**Step 5: Commit**

```bash
git add shared/src/
git commit -m "feat(repo): update routine loading for superset containers"
```

---

### Task 3.2: Update MainViewModel Superset Methods

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Update getCurrentSupersetExercises (around line 1429)**

Replace:
```kotlin
private fun getCurrentSupersetExercises(): List<RoutineExercise> {
    val routine = currentRoutine ?: return emptyList()
    val currentExercise = getCurrentExercise() ?: return emptyList()
    val supersetId = currentExercise.supersetGroupId ?: return emptyList()

    return routine.exercises
        .filter { it.supersetGroupId == supersetId }
        .sortedBy { it.supersetOrder }
}
```

With:
```kotlin
private fun getCurrentSupersetExercises(): List<RoutineExercise> {
    val routine = currentRoutine ?: return emptyList()
    val currentExercise = getCurrentExercise() ?: return emptyList()
    val supersetId = currentExercise.supersetId ?: return emptyList()

    return routine.exercises
        .filter { it.supersetId == supersetId }
        .sortedBy { it.orderInSuperset }
}
```

**Step 2: Update isInSuperset (around line 1442)**

Replace:
```kotlin
private fun isInSuperset(): Boolean {
    return getCurrentExercise()?.supersetGroupId != null
}
```

With:
```kotlin
private fun isInSuperset(): Boolean {
    return getCurrentExercise()?.supersetId != null
}
```

**Step 3: Update getSupersetRestSeconds (around line 1492)**

Replace:
```kotlin
private fun getSupersetRestSeconds(): Int {
    return getCurrentExercise()?.supersetRestSeconds ?: 10
}
```

With:
```kotlin
private fun getSupersetRestSeconds(): Int {
    val routine = currentRoutine ?: return 10
    val supersetId = getCurrentExercise()?.supersetId ?: return 10
    return routine.supersets.find { it.id == supersetId }?.restBetweenSeconds ?: 10
}
```

**Step 4: Update all other references to old superset fields**

Search for and update:
- `supersetGroupId` → `supersetId`
- `supersetOrder` → `orderInSuperset`
- `getSupersetGroupIds()` → `supersets.map { it.id }`

**Step 5: Run build**

Run: `./gradlew :shared:compileKotlinMetadata`

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git commit -m "feat(vm): update superset methods for container model"
```

---

### Task 3.3: Add Superset CRUD Methods to ViewModel

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Add createSuperset method**

```kotlin
/**
 * Create a new superset in a routine.
 * Auto-assigns next available color and generates name.
 */
suspend fun createSuperset(
    routineId: String,
    name: String? = null,
    exercises: List<RoutineExercise> = emptyList()
): Superset {
    val routine = getRoutineById(routineId) ?: throw IllegalArgumentException("Routine not found")
    val existingColors = routine.supersets.map { it.colorIndex }.toSet()
    val colorIndex = SupersetColors.next(existingColors)
    val supersetCount = routine.supersets.size
    val autoName = name ?: "Superset ${'A' + supersetCount}"
    val orderIndex = routine.getItems().maxOfOrNull { it.orderIndex }?.plus(1) ?: 0

    val superset = Superset(
        id = generateSupersetId(),
        routineId = routineId,
        name = autoName,
        colorIndex = colorIndex,
        restBetweenSeconds = 10,
        orderIndex = orderIndex
    )

    database.insertSuperset(
        superset.id,
        superset.routineId,
        superset.name,
        superset.colorIndex.toLong(),
        superset.restBetweenSeconds.toLong(),
        superset.orderIndex.toLong()
    )

    // Move exercises into superset if provided
    exercises.forEachIndexed { index, exercise ->
        updateExerciseSuperset(exercise.id, superset.id, index)
    }

    return superset
}
```

**Step 2: Add updateSuperset method**

```kotlin
/**
 * Update superset properties (name, rest time, color).
 */
suspend fun updateSuperset(superset: Superset) {
    database.updateSuperset(
        superset.name,
        superset.colorIndex.toLong(),
        superset.restBetweenSeconds.toLong(),
        superset.orderIndex.toLong(),
        superset.id
    )
}
```

**Step 3: Add deleteSuperset method**

```kotlin
/**
 * Delete a superset. Exercises become standalone.
 */
suspend fun deleteSuperset(supersetId: String) {
    database.deleteSuperset(supersetId)
    // ON DELETE SET NULL handles exercise.supersetId
}
```

**Step 4: Add updateExerciseSuperset method**

```kotlin
/**
 * Move an exercise into a superset (or remove from superset if supersetId is null).
 */
suspend fun updateExerciseSuperset(
    exerciseId: String,
    supersetId: String?,
    orderInSuperset: Int = 0
) {
    // Custom query needed - add to SQLDelight
}
```

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git commit -m "feat(vm): add superset CRUD methods"
```

---

## Phase 4: Database Migration

### Task 4.1: Add Migration Query

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq`

**Step 1: Add migration helper queries**

```sql
-- Migration: Get distinct superset group IDs for conversion
selectDistinctSupersetGroups:
SELECT DISTINCT supersetGroupId, routineId, supersetRestSeconds
FROM RoutineExercise
WHERE supersetGroupId IS NOT NULL;

-- Migration: Update exercise to new superset reference
updateExerciseSupersetRef:
UPDATE RoutineExercise
SET supersetId = ?, orderInSuperset = ?
WHERE id = ?;
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat(db): add migration queries for superset conversion"
```

---

### Task 4.2: Create Migration Logic

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/SupersetMigration.kt`

**Step 1: Create migration file**

```kotlin
package com.devil.phoenixproject.data.migration

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.SupersetColors
import com.devil.phoenixproject.domain.model.generateSupersetId

/**
 * Migrates old supersetGroupId-based supersets to new Superset table.
 */
suspend fun migrateSupersetsToContainerModel(database: VitruvianDatabase) {
    // 1. Get all distinct superset groups
    val oldGroups = database.selectDistinctSupersetGroups().executeAsList()

    // 2. Group by routine
    val groupsByRoutine = oldGroups.groupBy { it.routineId }

    // 3. For each routine, create Superset entities
    groupsByRoutine.forEach { (routineId, groups) ->
        var colorIndex = 0
        var orderIndex = 0

        groups.forEachIndexed { index, group ->
            val supersetId = generateSupersetId()
            val name = "Superset ${'A' + index}"

            // Insert new Superset
            database.insertSuperset(
                id = supersetId,
                routineId = routineId,
                name = name,
                colorIndex = (colorIndex++ % 4).toLong(),
                restBetweenSeconds = group.supersetRestSeconds,
                orderIndex = orderIndex++.toLong()
            )

            // Update exercises to reference new superset
            val exercises = database.selectExercisesByRoutine(routineId).executeAsList()
                .filter { it.supersetGroupId == group.supersetGroupId }
                .sortedBy { it.supersetOrder }

            exercises.forEachIndexed { exIndex, exercise ->
                database.updateExerciseSupersetRef(
                    supersetId = supersetId,
                    orderInSuperset = exIndex.toLong(),
                    id = exercise.id
                )
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/
git commit -m "feat(migration): add superset container migration logic"
```

---

## Phase 5: Routine Editor UI Rewrite

### Task 5.1: Create SupersetColors Compose Theme

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/SupersetTheme.kt`

**Step 1: Create color definitions**

```kotlin
package com.devil.phoenixproject.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SupersetTheme {
    val Indigo = Color(0xFF6366F1)
    val Pink = Color(0xFFEC4899)
    val Green = Color(0xFF10B981)
    val Amber = Color(0xFFF59E0B)

    val colors = listOf(Indigo, Pink, Green, Amber)

    fun colorForIndex(index: Int): Color = colors[index % colors.size]

    @Composable
    fun backgroundTint(index: Int, isDarkTheme: Boolean): Color {
        val base = colorForIndex(index)
        val alpha = if (isDarkTheme) 0.12f else 0.08f
        return base.copy(alpha = alpha)
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/SupersetTheme.kt
git commit -m "feat(ui): add superset color theme"
```

---

### Task 5.2: Create SupersetHeader Composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetHeader.kt`

**Step 1: Create header component**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.ui.theme.SupersetTheme

@Composable
fun SupersetHeader(
    superset: Superset,
    isExpanded: Boolean,
    isDragging: Boolean,
    onToggleExpand: () -> Unit,
    onMenuClick: () -> Unit,
    onDragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(superset.colorIndex)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            SupersetTheme.backgroundTint(superset.colorIndex, false)
        },
        tonalElevation = if (isDragging) 8.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored left bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )

            Spacer(Modifier.width(8.dp))

            // Drag handle
            onDragHandle()

            Spacer(Modifier.width(8.dp))

            // Name and exercise count
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = superset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(
                    text = "${superset.exerciseCount} exercises • ${superset.restBetweenSeconds}s rest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Menu button
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }

            // Expand/collapse
            IconButton(onClick = onToggleExpand) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetHeader.kt
git commit -m "feat(ui): add SupersetHeader composable"
```

---

### Task 5.3: Create SupersetExerciseItem Composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetExerciseItem.kt`

**Step 1: Create indented exercise item**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.SupersetTheme

@Composable
fun SupersetExerciseItem(
    exercise: RoutineExercise,
    colorIndex: Int,
    isFirst: Boolean,
    isLast: Boolean,
    isDragging: Boolean,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onMenuClick: () -> Unit,
    onDragHandle: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp), // Indent for nesting
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tree connector
        Column(
            modifier = Modifier.width(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Vertical line
            if (!isFirst) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }

            // Horizontal connector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(
                            if (isLast) Color.Transparent
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                )
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }

            // Continue vertical line if not last
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        // Exercise card
        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = color.copy(alpha = 0.3f)
            ),
            tonalElevation = if (isDragging) 8.dp else 1.dp
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                onDragHandle()

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exercise.exercise.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                    val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                    Text(
                        text = "${exercise.sets} sets × ${exercise.reps} reps @ ${"%.1f".format(weight)} $unitLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetExerciseItem.kt
git commit -m "feat(ui): add SupersetExerciseItem with tree connectors"
```

---

### Task 5.4: Rewrite RoutineEditorScreen (Part 1 - State)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`

**Step 1: Update RoutineEditorState (lines 40-45)**

Replace:
```kotlin
data class RoutineEditorState(
    val routineName: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false
)
```

With:
```kotlin
data class RoutineEditorState(
    val routineName: String = "",
    val routine: Routine? = null,
    val selectedIds: Set<String> = emptySet(),  // Can be exercise or superset IDs
    val isSelectionMode: Boolean = false,
    val collapsedSupersets: Set<String> = emptySet(),  // Collapsed superset IDs
    val showAddMenu: Boolean = false
) {
    val items: List<RoutineItem> get() = routine?.getItems() ?: emptyList()
    val exercises: List<RoutineExercise> get() = routine?.exercises ?: emptyList()
    val supersets: List<Superset> get() = routine?.supersets ?: emptyList()
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(ui): update RoutineEditorState for superset containers"
```

---

### Task 5.5: Rewrite RoutineEditorScreen (Part 2 - LazyColumn)

This is a large task that rewrites the main LazyColumn to render supersets as collapsible containers with nested exercises.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`

**Step 1: Replace the LazyColumn content**

The new structure iterates over `state.items` (List<RoutineItem>) and renders:
- `RoutineItem.Single` → standalone exercise card
- `RoutineItem.SupersetItem` → header + indented exercises (if expanded)

```kotlin
LazyColumn(
    state = lazyListState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    state.items.forEach { item ->
        when (item) {
            is RoutineItem.Single -> {
                item(key = item.exercise.id) {
                    ReorderableItem(reorderState, key = item.exercise.id) { isDragging ->
                        StandaloneExerciseCard(
                            exercise = item.exercise,
                            isDragging = isDragging,
                            isSelected = item.exercise.id in state.selectedIds,
                            onDragHandle = { /* drag modifier */ },
                            onClick = { /* edit exercise */ },
                            onLongClick = { /* enter selection mode */ },
                            onMenuClick = { /* show menu */ }
                        )
                    }
                }
            }

            is RoutineItem.SupersetItem -> {
                val superset = item.superset
                val isExpanded = superset.id !in state.collapsedSupersets

                // Superset header
                item(key = superset.id) {
                    ReorderableItem(reorderState, key = superset.id) { isDragging ->
                        SupersetHeader(
                            superset = superset,
                            isExpanded = isExpanded,
                            isDragging = isDragging,
                            onToggleExpand = {
                                state = if (isExpanded) {
                                    state.copy(collapsedSupersets = state.collapsedSupersets + superset.id)
                                } else {
                                    state.copy(collapsedSupersets = state.collapsedSupersets - superset.id)
                                }
                            },
                            onMenuClick = { /* show superset menu */ },
                            onDragHandle = { /* drag modifier */ }
                        )
                    }
                }

                // Exercises inside superset (if expanded)
                if (isExpanded) {
                    superset.exercises.forEachIndexed { index, exercise ->
                        item(key = exercise.id) {
                            ReorderableItem(reorderState, key = exercise.id) { isDragging ->
                                SupersetExerciseItem(
                                    exercise = exercise,
                                    colorIndex = superset.colorIndex,
                                    isFirst = index == 0,
                                    isLast = index == superset.exercises.lastIndex,
                                    isDragging = isDragging,
                                    weightUnit = weightUnit,
                                    kgToDisplay = kgToDisplay,
                                    onMenuClick = { /* show exercise menu */ },
                                    onDragHandle = { /* drag modifier */ },
                                    onClick = { /* edit exercise */ }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(ui): rewrite RoutineEditorScreen with nested superset list"
```

---

### Task 5.6: Add "Add" Menu with Superset Option

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`

**Step 1: Add dropdown menu for add button**

```kotlin
Box {
    FloatingActionButton(onClick = { state = state.copy(showAddMenu = true) }) {
        Icon(Icons.Default.Add, contentDescription = "Add")
    }

    DropdownMenu(
        expanded = state.showAddMenu,
        onDismissRequest = { state = state.copy(showAddMenu = false) }
    ) {
        DropdownMenuItem(
            text = { Text("Add Exercise") },
            leadingIcon = { Icon(Icons.Default.FitnessCenter, null) },
            onClick = {
                state = state.copy(showAddMenu = false)
                showExercisePicker = true
            }
        )
        DropdownMenuItem(
            text = { Text("Add Superset") },
            leadingIcon = { Icon(Icons.Default.Layers, null) },
            onClick = {
                state = state.copy(showAddMenu = false)
                // Create empty superset
                viewModelScope.launch {
                    val superset = viewModel.createSuperset(routineId)
                    // Refresh state
                }
            }
        )
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(ui): add superset creation from add menu"
```

---

### Task 5.7: Add Selection Mode "Create Superset" Action

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`

**Step 1: Add bottom action bar for selection mode**

When `state.isSelectionMode && state.selectedIds.size >= 2`, show:

```kotlin
if (state.isSelectionMode && state.selectedIds.isNotEmpty()) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (state.selectedIds.size >= 2) {
                TextButton(onClick = {
                    // Create superset from selected exercises
                    viewModelScope.launch {
                        val selectedExercises = state.exercises
                            .filter { it.id in state.selectedIds }
                        viewModel.createSuperset(routineId, exercises = selectedExercises)
                        state = state.copy(isSelectionMode = false, selectedIds = emptySet())
                    }
                }) {
                    Icon(Icons.Default.Layers, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Create Superset")
                }
            }

            TextButton(onClick = {
                // Duplicate selected
            }) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(4.dp))
                Text("Duplicate")
            }

            TextButton(
                onClick = {
                    // Delete selected
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(ui): add create superset from selection"
```

---

## Phase 6: Drag-and-Drop Logic

### Task 6.1: Update Reorderable Logic for Supersets

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt`

**Step 1: Update reorderState callback**

The drag-and-drop needs to handle:
1. Reordering standalone exercises
2. Reordering supersets (moves whole group)
3. Moving exercises between supersets
4. Moving exercises into/out of supersets

```kotlin
val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
    val fromKey = from.key as String
    val toKey = to.key as String

    // Determine what's being dragged and where
    val fromItem = state.items.find {
        when (it) {
            is RoutineItem.Single -> it.exercise.id == fromKey
            is RoutineItem.SupersetItem -> it.superset.id == fromKey ||
                it.superset.exercises.any { e -> e.id == fromKey }
        }
    }

    // Handle different reorder scenarios
    viewModelScope.launch {
        when {
            // Superset dragged onto superset = merge
            fromItem is RoutineItem.SupersetItem &&
            state.supersets.any { it.id == toKey } -> {
                viewModel.mergeSupersets(fromKey, toKey)
            }

            // Exercise dragged onto superset = add to superset
            fromItem is RoutineItem.Single &&
            state.supersets.any { it.id == toKey } -> {
                viewModel.updateExerciseSuperset(fromKey, toKey, 0)
            }

            // Exercise dragged out of superset = make standalone
            // (detected by drop zone outside any superset)

            // Default: reorder
            else -> {
                viewModel.reorderRoutineItems(fromKey, toKey)
            }
        }
    }
}
```

**Step 2: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(ui): add drag-and-drop superset logic"
```

---

## Phase 7: Verification & Cleanup

### Task 7.1: Build and Fix Remaining Errors

**Step 1: Run full build**

Run: `./gradlew build`

**Step 2: Fix any remaining compile errors**

Search for remaining references to old field names:
- `supersetGroupId`
- `supersetOrder`
- `supersetRestSeconds`
- `SupersetGroup`

**Step 3: Commit fixes**

```bash
git add -A
git commit -m "fix: resolve remaining superset migration compile errors"
```

---

### Task 7.2: Test on Device

**Step 1: Install on Android device**

Run: `./gradlew :androidApp:installDebug`

**Step 2: Manual testing checklist**

- [ ] Create new routine
- [ ] Add exercises
- [ ] Create empty superset from menu
- [ ] Add exercises to superset via picker
- [ ] Select exercises → Create Superset
- [ ] Expand/collapse superset
- [ ] Drag exercise within superset (reorder)
- [ ] Drag exercise out of superset (becomes standalone)
- [ ] Drag exercise into superset
- [ ] Drag superset to reorder
- [ ] Rename superset
- [ ] Change superset rest time
- [ ] Delete superset (exercises become standalone)
- [ ] Duplicate exercise
- [ ] Duplicate superset
- [ ] Start workout with superset routine
- [ ] Verify superset cycling works

**Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: address issues found in manual testing"
```

---

### Task 7.3: Final Commit

**Step 1: Create summary commit**

```bash
git add -A
git commit -m "feat: complete superset container redesign

- Supersets are now first-class container entities
- New Superset database table with migration
- Nested list UI with collapsible headers
- Drag-and-drop between/within supersets
- Color-coded visual distinction (4 colors)
- Configurable rest time per superset
- Duplicate supersets and exercises"
```

---

## Notes

### Files Modified Summary
- `VitruvianDatabase.sq` - New Superset table, updated RoutineExercise
- `Routine.kt` - New Superset entity, updated RoutineItem
- `MainViewModel.kt` - Superset CRUD, updated execution logic
- `RoutineEditorScreen.kt` - Complete UI rewrite
- `SupersetTheme.kt` - New color definitions
- `SupersetHeader.kt` - New header component
- `SupersetExerciseItem.kt` - New nested exercise component
- `SupersetMigration.kt` - Migration logic

### Backwards Compatibility
The migration handles existing routines with old `supersetGroupId` fields by:
1. Creating Superset entities for each distinct group
2. Updating exercise references
3. Preserving exercise order within groups

### Testing Priority
1. Migration of existing data
2. Superset creation flows
3. Drag-and-drop interactions
4. Workout execution with supersets

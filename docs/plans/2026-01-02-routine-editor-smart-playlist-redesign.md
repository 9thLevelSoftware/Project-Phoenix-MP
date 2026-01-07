# Routine Editor Redesign: Smart Playlist Pattern

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign RoutineEditorScreen to eliminate selection mode, add fluid "Link with Next" superset creation, and use visual connector lines instead of container grouping.

**Architecture:** Remove checkbox-based selection mode entirely. Move superset creation to context menu ("Create Superset with Next"). Replace SupersetHeader container pattern with vertical connector lines between linked exercises. Keep drag-drop reordering.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, sh.calvin.reorderable library, existing SupersetTheme colors

---

## Problem Statement

Current RoutineEditorScreen has UX friction:
- **Mode Error**: Selection mode (long-press → checkboxes → bottom bar) is cognitively taxing
- **Low Discoverability**: Superset grouping hidden behind long-press + multi-select
- **Visual Noise**: SupersetHeader as container with expand/collapse adds complexity

## Solution: Smart Playlist Pattern

1. **No Selection Mode**: Delete all checkbox/selection logic
2. **Menu-Driven Actions**: "Create Superset with Next" directly from exercise menu
3. **Visual Connectors**: Vertical lines between linked exercises (not containers)
4. **Uniform Cards**: All exercises look the same; connectors show relationships

---

## Design Reference

```
+--------------------------------------------------+
|  <-  Leg Day (Hypertrophy)               SAVE    |
+--------------------------------------------------+
|  [ Routine Name Input                          ] |
+--------------------------------------------------+
|                                                  |
|  [::]  Squat (Barbell)                    [...]  | <- Drag Handle, Menu
|        3 sets x 8 reps • 225 lbs                 |
|                                                  |
|   │    ← Vertical connector line (superset)      |
|   │                                              |
|  [::]  Leg Extension                      [...]  |
|        3 sets x 12 reps • 140 lbs                |
|                                                  |
|                                                  | <- Gap = no superset
|  [::]  Romanian Deadlift                  [...]  |
|        4 sets x 10 reps • 185 lbs                |
|                                                  |
+--------------------------------------------------+
|            [ + Add Exercise ]                    |
+--------------------------------------------------+
```

**Menu Options:**
- For standalone: Edit | Create Superset | Delete
- For linked item: Edit | Unlink | Delete

---

## Data Model Impact

**No schema changes required.** The existing model already supports what we need:

```kotlin
// Keep using supersetId on RoutineExercise
data class RoutineExercise(
    val supersetId: String? = null,  // Links to sibling exercises
    val orderInSuperset: Int = 0
)
```

**Behavior change:** When creating a superset via "Create Superset with Next":
1. Generate new `supersetId`
2. Assign to current exercise (`orderInSuperset = 0`)
3. Assign to next exercise (`orderInSuperset = 1`)
4. No Superset container object needed for this pattern

**Decision Point:** We can either:
- **Option A (Simple)**: Remove Superset as first-class entity, just use `supersetId` as a grouping tag
- **Option B (Preserve)**: Keep Superset entity for metadata (color, rest time, name) but hide the header

**Recommendation:** Option B - Keep Superset for metadata but render as connector line only.

---

## Task 1: Remove Selection Mode from State

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt:43-54`

**Step 1: Update RoutineEditorState**

Remove selection-related fields:

```kotlin
// Before
data class RoutineEditorState(
    val routineName: String = "",
    val routine: Routine? = null,
    val selectedIds: Set<String> = emptySet(),  // REMOVE
    val isSelectionMode: Boolean = false,        // REMOVE
    val collapsedSupersets: Set<String> = emptySet(),
    val showAddMenu: Boolean = false
)

// After
data class RoutineEditorState(
    val routineName: String = "",
    val routine: Routine? = null,
    val collapsedSupersets: Set<String> = emptySet(),
    val showAddMenu: Boolean = false
)
```

**Step 2: Build and verify compilation fails**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: Compilation errors for `selectedIds` and `isSelectionMode` references

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): remove selection mode state fields"
```

---

## Task 2: Remove Selection Mode from TopAppBar

**Files:**
- Modify: `RoutineEditorScreen.kt:192-258`

**Step 1: Simplify TopAppBar**

Replace conditional title and actions:

```kotlin
// Before (lines 194-257)
TopAppBar(
    title = {
        if (state.isSelectionMode) {
            Text("${state.selectedIds.size} Selected", ...)
        } else {
            TextField(...)
        }
    },
    navigationIcon = {
        IconButton(onClick = {
            if (state.isSelectionMode) {
                state = state.copy(isSelectionMode = false, selectedIds = emptySet())
            } else {
                navController.popBackStack()
            }
        }) {
            Icon(if (state.isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, ...)
        }
    },
    actions = {
        if (!state.isSelectionMode) {
            // Save button
        } else {
            // Delete button for selection
        }
    }
)

// After
TopAppBar(
    title = {
        TextField(
            value = state.routineName,
            onValueChange = { state = state.copy(routineName = it) },
            placeholder = { Text("Routine Name") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            singleLine = true
        )
    },
    navigationIcon = {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    },
    actions = {
        TextButton(
            onClick = {
                val routineToSave = state.routine?.copy(
                    id = if (routineId == "new") generateUUID() else routineId,
                    name = state.routineName.ifBlank { "Unnamed Routine" }
                ) ?: Routine(
                    id = generateUUID(),
                    name = state.routineName.ifBlank { "Unnamed Routine" }
                )
                viewModel.saveRoutine(routineToSave)
                navController.popBackStack()
            }
        ) {
            Text("Save", fontWeight = FontWeight.Bold)
        }
    }
)
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): simplify TopAppBar, remove selection mode"
```

---

## Task 3: Remove Selection Mode BottomBar

**Files:**
- Modify: `RoutineEditorScreen.kt:260-313`

**Step 1: Delete entire bottomBar parameter**

```kotlin
// Delete lines 260-313 entirely (the bottomBar block)
// Scaffold should have no bottomBar

Scaffold(
    topBar = { ... },
    // bottomBar removed
    floatingActionButton = { ... }
) { ... }
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): remove selection mode bottom bar"
```

---

## Task 4: Simplify FAB (always visible)

**Files:**
- Modify: `RoutineEditorScreen.kt:314-323`

**Step 1: Remove selection mode conditional**

```kotlin
// Before
floatingActionButton = {
    if (!state.isSelectionMode) {
        ExtendedFloatingActionButton(...)
    }
}

// After
floatingActionButton = {
    ExtendedFloatingActionButton(
        onClick = { showExercisePicker = true },
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text("Add Exercise") },
        containerColor = MaterialTheme.colorScheme.primaryContainer
    )
}
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): FAB always visible"
```

---

## Task 5: Simplify StandaloneExerciseCard (remove selection)

**Files:**
- Modify: `RoutineEditorScreen.kt:867-952`

**Step 1: Update StandaloneExerciseCard signature**

```kotlin
// Before
@Composable
private fun StandaloneExerciseCard(
    exercise: RoutineExercise,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    elevation: Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onToggleSelection: () -> Unit,
    onEdit: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
)

// After
@Composable
private fun StandaloneExerciseCard(
    exercise: RoutineExercise,
    elevation: Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onEdit: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
)
```

**Step 2: Simplify card body**

```kotlin
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StandaloneExerciseCard(
    exercise: RoutineExercise,
    elevation: Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onEdit: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle (always shown)
        Box(
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag",
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = dragModifier
            )
        }

        // Card content
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        exercise.exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                    val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                    Text(
                        "${exercise.sets} sets x ${exercise.reps} reps @ ${weight.toInt()} $unitLabel",
                        style = MaterialTheme.typography.bodyMedium,
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

**Step 3: Build (will fail - call sites need update)**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: FAIL - missing arguments at call sites

**Step 4: Commit partial**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): simplify StandaloneExerciseCard signature"
```

---

## Task 6: Update StandaloneExerciseCard call site

**Files:**
- Modify: `RoutineEditorScreen.kt:354-418`

**Step 1: Update the call in LazyColumn**

```kotlin
// Around line 361-390
StandaloneExerciseCard(
    exercise = routineItem.exercise,
    elevation = elevation,
    weightUnit = weightUnit,
    kgToDisplay = kgToDisplay,
    onEdit = {
        exerciseToConfig = routineItem.exercise
        isNewExercise = false
        editingIndex = state.exercises.indexOf(routineItem.exercise)
    },
    onMenuClick = {
        exerciseMenuFor = routineItem.exercise.id
    },
    dragModifier = Modifier.draggableHandle(
        interactionSource = remember { MutableInteractionSource() }
    )
)
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): update StandaloneExerciseCard call site"
```

---

## Task 7: Add "Create Superset" to Standalone Exercise Menu

**Files:**
- Modify: `RoutineEditorScreen.kt:393-417` (DropdownMenu for standalone exercises)

**Step 1: Add helper to find next exercise**

Add near other helper functions (~line 120):

```kotlin
// Helper: Create superset with next exercise
fun createSupersetWithNext(exerciseId: String) {
    val routine = state.routine ?: return
    val exercises = routine.exercises
    val currentIndex = exercises.indexOfFirst { it.id == exerciseId }

    if (currentIndex < 0 || currentIndex >= exercises.lastIndex) return // No next exercise

    val current = exercises[currentIndex]
    val next = exercises[currentIndex + 1]

    // Skip if either already in a superset
    if (current.supersetId != null || next.supersetId != null) return

    val newSupersetId = generateSupersetId()
    val existingColors = routine.supersets.map { it.colorIndex }.toSet()
    val newColor = SupersetColors.next(existingColors)

    // Create new superset
    val newSuperset = Superset(
        id = newSupersetId,
        routineId = routine.id,
        name = "Superset",
        colorIndex = newColor,
        orderIndex = current.orderIndex
    )

    // Update both exercises
    val updatedExercises = exercises.map { ex ->
        when (ex.id) {
            current.id -> ex.copy(supersetId = newSupersetId, orderInSuperset = 0)
            next.id -> ex.copy(supersetId = newSupersetId, orderInSuperset = 1)
            else -> ex
        }
    }

    updateRoutine {
        it.copy(
            exercises = updatedExercises,
            supersets = routine.supersets + newSuperset
        )
    }
}
```

**Step 2: Update standalone exercise menu**

```kotlin
// Exercise context menu (around line 393-417)
DropdownMenu(
    expanded = exerciseMenuFor == routineItem.exercise.id,
    onDismissRequest = { exerciseMenuFor = null }
) {
    DropdownMenuItem(
        text = { Text("Edit") },
        onClick = {
            exerciseToConfig = routineItem.exercise
            isNewExercise = false
            editingIndex = state.exercises.indexOf(routineItem.exercise)
            exerciseMenuFor = null
        },
        leadingIcon = { Icon(Icons.Default.Edit, null) }
    )

    // Only show "Create Superset" if there's a next exercise and neither is in a superset
    val currentIndex = state.exercises.indexOfFirst { it.id == routineItem.exercise.id }
    val hasNext = currentIndex >= 0 && currentIndex < state.exercises.lastIndex
    val nextExercise = if (hasNext) state.exercises[currentIndex + 1] else null
    val canSuperset = hasNext &&
        routineItem.exercise.supersetId == null &&
        nextExercise?.supersetId == null

    if (canSuperset) {
        DropdownMenuItem(
            text = { Text("Create Superset") },
            onClick = {
                createSupersetWithNext(routineItem.exercise.id)
                exerciseMenuFor = null
            },
            leadingIcon = { Icon(Icons.Default.Link, null) }
        )
    }

    DropdownMenuItem(
        text = { Text("Delete") },
        onClick = {
            val remaining = state.exercises.filter { it.id != routineItem.exercise.id }
            updateExercises(remaining)
            exerciseMenuFor = null
        },
        leadingIcon = { Icon(Icons.Default.Delete, null) }
    )
}
```

**Step 3: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(routine-editor): add 'Create Superset' to exercise menu"
```

---

## Task 8: Create SupersetConnector Composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetConnector.kt`

**Step 1: Create the connector component**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Draws a vertical connector line between superset exercises.
 * Position determines which ends have rounded caps.
 */
enum class ConnectorPosition {
    TOP,    // Rounded cap at top, line continues down
    MIDDLE, // No caps, line continues both directions
    BOTTOM  // Line from top, rounded cap at bottom
}

@Composable
fun SupersetConnector(
    colorIndex: Int,
    position: ConnectorPosition,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        // Vertical line
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color)
        )

        // Top cap
        if (position == ConnectorPosition.TOP) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }

        // Bottom cap
        if (position == ConnectorPosition.BOTTOM) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/**
 * Horizontal connector between drag handle and exercise card.
 */
@Composable
fun SupersetConnectorHorizontal(
    colorIndex: Int,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Box(
        modifier = modifier
            .height(3.dp)
            .width(12.dp)
            .background(color)
    )
}
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SupersetConnector.kt
git commit -m "feat(routine-editor): add SupersetConnector component"
```

---

## Task 9: Create Unified ExerciseRow with Connector Support

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseRowWithConnector.kt`

**Step 1: Create unified exercise row**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit

/**
 * Unified exercise row that handles both standalone and superset exercises.
 * When in a superset, shows connector line on the left.
 */
@Composable
fun ExerciseRowWithConnector(
    exercise: RoutineExercise,
    elevation: Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    // Superset connector info
    supersetColorIndex: Int?,
    connectorPosition: ConnectorPosition?,
    // Callbacks
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connector column (if in superset)
        if (supersetColorIndex != null && connectorPosition != null) {
            SupersetConnector(
                colorIndex = supersetColorIndex,
                position = connectorPosition,
                modifier = Modifier.height(IntrinsicSize.Min)
            )
        }

        // Drag handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag",
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = dragModifier
            )
        }

        // Card content
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        exercise.exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val weight = kgToDisplay(exercise.weightPerCableKg, weightUnit)
                    val unitLabel = if (weightUnit == WeightUnit.KG) "kg" else "lbs"
                    Text(
                        "${exercise.sets} sets x ${exercise.reps} reps @ ${weight.toInt()} $unitLabel",
                        style = MaterialTheme.typography.bodyMedium,
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

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseRowWithConnector.kt
git commit -m "feat(routine-editor): add ExerciseRowWithConnector component"
```

---

## Task 10: Refactor LazyColumn to Flatten Exercise List

**Files:**
- Modify: `RoutineEditorScreen.kt:336-600` (the items rendering section)

**Step 1: Add helper to compute flat list with connector info**

Add near other helpers:

```kotlin
/**
 * Represents an exercise in the flat list with its superset position info.
 */
data class FlatExerciseItem(
    val exercise: RoutineExercise,
    val supersetColorIndex: Int?,
    val connectorPosition: ConnectorPosition?
)

/**
 * Flattens routine into a list of exercises with connector info.
 * Exercises in supersets are ordered together.
 */
fun Routine.flattenWithConnectors(): List<FlatExerciseItem> {
    val items = getItems()
    val result = mutableListOf<FlatExerciseItem>()

    for (item in items) {
        when (item) {
            is RoutineItem.Single -> {
                result.add(FlatExerciseItem(
                    exercise = item.exercise,
                    supersetColorIndex = null,
                    connectorPosition = null
                ))
            }
            is RoutineItem.SupersetItem -> {
                val exercises = item.superset.exercises
                exercises.forEachIndexed { index, exercise ->
                    val position = when {
                        exercises.size == 1 -> null // Single item, no connector
                        index == 0 -> ConnectorPosition.TOP
                        index == exercises.lastIndex -> ConnectorPosition.BOTTOM
                        else -> ConnectorPosition.MIDDLE
                    }
                    result.add(FlatExerciseItem(
                        exercise = exercise,
                        supersetColorIndex = item.superset.colorIndex,
                        connectorPosition = position
                    ))
                }
            }
        }
    }

    return result
}
```

**Step 2: Simplify LazyColumn items**

Replace the entire items section with:

```kotlin
LazyColumn(
    state = lazyListState,
    contentPadding = PaddingValues(
        bottom = 100.dp,
        top = padding.calculateTopPadding(),
        start = 16.dp,
        end = 16.dp
    ),
    verticalArrangement = Arrangement.spacedBy(4.dp), // Tighter spacing
    modifier = Modifier.fillMaxSize()
) {
    val flatItems = state.routine?.flattenWithConnectors() ?: emptyList()

    if (flatItems.isEmpty()) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Tap + to add your first exercise",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    flatItems.forEachIndexed { index, flatItem ->
        item(key = flatItem.exercise.id) {
            ReorderableItem(
                state = reorderState,
                key = flatItem.exercise.id
            ) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)

                Box {
                    ExerciseRowWithConnector(
                        exercise = flatItem.exercise,
                        elevation = elevation,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        supersetColorIndex = flatItem.supersetColorIndex,
                        connectorPosition = flatItem.connectorPosition,
                        onClick = {
                            exerciseToConfig = flatItem.exercise
                            isNewExercise = false
                            editingIndex = state.exercises.indexOf(flatItem.exercise)
                        },
                        onMenuClick = {
                            exerciseMenuFor = flatItem.exercise.id
                        },
                        dragModifier = Modifier.draggableHandle(
                            interactionSource = remember { MutableInteractionSource() }
                        )
                    )

                    // Context menu for this exercise
                    ExerciseContextMenu(
                        exercise = flatItem.exercise,
                        isInSuperset = flatItem.exercise.supersetId != null,
                        canCreateSuperset = canCreateSupersetWithNext(flatItem.exercise.id),
                        expanded = exerciseMenuFor == flatItem.exercise.id,
                        onDismiss = { exerciseMenuFor = null },
                        onEdit = {
                            exerciseToConfig = flatItem.exercise
                            isNewExercise = false
                            editingIndex = state.exercises.indexOf(flatItem.exercise)
                            exerciseMenuFor = null
                        },
                        onCreateSuperset = {
                            createSupersetWithNext(flatItem.exercise.id)
                            exerciseMenuFor = null
                        },
                        onUnlink = {
                            unlinkFromSuperset(flatItem.exercise.id)
                            exerciseMenuFor = null
                        },
                        onDelete = {
                            val remaining = state.exercises.filter { it.id != flatItem.exercise.id }
                            updateExercises(remaining)
                            exerciseMenuFor = null
                        }
                    )
                }
            }
        }
    }
}
```

**Step 3: Build (will fail - need helper functions)**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: FAIL - missing functions

**Step 4: Commit partial**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): flatten exercise list with connectors"
```

---

## Task 11: Add Missing Helper Functions

**Files:**
- Modify: `RoutineEditorScreen.kt` (near other helpers)

**Step 1: Add canCreateSupersetWithNext**

```kotlin
fun canCreateSupersetWithNext(exerciseId: String): Boolean {
    val routine = state.routine ?: return false
    val exercises = routine.exercises
    val currentIndex = exercises.indexOfFirst { it.id == exerciseId }

    if (currentIndex < 0 || currentIndex >= exercises.lastIndex) return false

    val current = exercises[currentIndex]
    val next = exercises[currentIndex + 1]

    return current.supersetId == null && next.supersetId == null
}
```

**Step 2: Add unlinkFromSuperset**

```kotlin
fun unlinkFromSuperset(exerciseId: String) {
    val routine = state.routine ?: return
    val exercise = routine.exercises.find { it.id == exerciseId } ?: return
    val supersetId = exercise.supersetId ?: return

    // Update exercise to remove superset reference
    val updatedExercises = routine.exercises.map { ex ->
        if (ex.id == exerciseId) ex.copy(supersetId = null, orderInSuperset = 0)
        else ex
    }

    // Check if superset now has less than 2 exercises - if so, dissolve it
    val remainingInSuperset = updatedExercises.count { it.supersetId == supersetId }

    if (remainingInSuperset < 2) {
        // Dissolve the superset
        val finalExercises = updatedExercises.map { ex ->
            if (ex.supersetId == supersetId) ex.copy(supersetId = null, orderInSuperset = 0)
            else ex
        }
        val finalSupersets = routine.supersets.filter { it.id != supersetId }
        updateRoutine { it.copy(exercises = finalExercises, supersets = finalSupersets) }
    } else {
        // Just update exercises, keep superset
        updateRoutine { it.copy(exercises = updatedExercises) }
    }
}
```

**Step 3: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS (or may still need ExerciseContextMenu)

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(routine-editor): add superset helper functions"
```

---

## Task 12: Create ExerciseContextMenu Composable

**Files:**
- Add to: `RoutineEditorScreen.kt` (as private composable)

**Step 1: Add the composable**

```kotlin
@Composable
private fun ExerciseContextMenu(
    exercise: RoutineExercise,
    isInSuperset: Boolean,
    canCreateSuperset: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCreateSuperset: () -> Unit,
    onUnlink: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Edit") },
            onClick = onEdit,
            leadingIcon = { Icon(Icons.Default.Edit, null) }
        )

        if (isInSuperset) {
            DropdownMenuItem(
                text = { Text("Remove from Superset") },
                onClick = onUnlink,
                leadingIcon = { Icon(Icons.Default.LinkOff, null) }
            )
        } else if (canCreateSuperset) {
            DropdownMenuItem(
                text = { Text("Create Superset") },
                onClick = onCreateSuperset,
                leadingIcon = { Icon(Icons.Default.Link, null) }
            )
        }

        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = onDelete,
            leadingIcon = { Icon(Icons.Default.Delete, null) }
        )
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "feat(routine-editor): add ExerciseContextMenu composable"
```

---

## Task 13: Update Reorder Logic for Flat List

**Files:**
- Modify: `RoutineEditorScreen.kt:155-190` (reorderState callback)

**Step 1: Update reorder callback**

The reorder now operates on individual exercises, not RoutineItems:

```kotlin
val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
    val routine = state.routine ?: return@rememberReorderableLazyListState
    val flatItems = routine.flattenWithConnectors().toMutableList()
    val fromIndex = from.index
    val toIndex = to.index

    if (fromIndex in flatItems.indices && toIndex in flatItems.indices) {
        // Move in flat list
        val moved = flatItems.removeAt(fromIndex)
        flatItems.add(toIndex, moved)

        // Rebuild exercises with new order
        val newExercises = flatItems.mapIndexed { index, item ->
            item.exercise.copy(orderIndex = index)
        }

        // Handle superset grouping:
        // If dragging into a superset, join it. If dragging out, leave it.
        // For simplicity, we'll preserve existing supersetId - advanced grouping
        // can be added later.

        updateRoutine { it.copy(exercises = newExercises) }
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): update reorder logic for flat list"
```

---

## Task 14: Remove Unused Components

**Files:**
- Modify: `RoutineEditorScreen.kt`

**Step 1: Remove StandaloneExerciseCard (now unused)**

Delete the entire `StandaloneExerciseCard` composable since we're using `ExerciseRowWithConnector`.

**Step 2: Remove EmptySupersetHint (no longer needed)**

Delete the `EmptySupersetHint` composable.

**Step 3: Remove superset-specific rendering code**

Delete any remaining code for:
- SupersetHeader rendering in LazyColumn
- SupersetExerciseItem rendering in LazyColumn
- Superset menu dialogs (rename, rest time, color, delete all)

Keep only:
- Exercise context menus
- Exercise edit bottom sheet
- Exercise picker dialog

**Step 4: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "refactor(routine-editor): remove unused components"
```

---

## Task 15: Delete RoutineBuilderDialog.kt

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineBuilderDialog.kt`

**Step 1: Check for usages**

Run: `grep -r "RoutineBuilderDialog" shared/src/`

**Step 2: Remove any import/usage references**

Update any files that reference RoutineBuilderDialog.

**Step 3: Delete the file**

```bash
rm shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineBuilderDialog.kt
```

**Step 4: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 5: Commit**

```bash
git add -A
git commit -m "refactor(routine-editor): delete legacy RoutineBuilderDialog"
```

---

## Task 16: Add Import for ConnectorPosition

**Files:**
- Modify: `RoutineEditorScreen.kt`

**Step 1: Add import**

```kotlin
import com.devil.phoenixproject.presentation.components.ConnectorPosition
import com.devil.phoenixproject.presentation.components.ExerciseRowWithConnector
```

**Step 2: Build and verify**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: PASS

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt
git commit -m "chore: add imports for connector components"
```

---

## Task 17: Final Integration Test

**Step 1: Full build**

Run: `./gradlew build`
Expected: PASS

**Step 2: Install and manual test**

Run: `./gradlew :androidApp:installDebug`

Test checklist:
- [ ] Create new routine
- [ ] Add 3+ exercises
- [ ] Tap menu on first exercise → "Create Superset" appears
- [ ] Create superset → vertical connector appears between exercises
- [ ] Tap menu on superset exercise → "Remove from Superset" appears
- [ ] Remove from superset → connector disappears
- [ ] Drag to reorder works
- [ ] Edit exercise via tap works
- [ ] Save routine and reload → superset persists

**Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(routine-editor): integration fixes"
```

---

## Summary

This plan eliminates the clunky selection mode and replaces it with:
1. **Direct menu actions** - "Create Superset" links current + next exercise
2. **Visual connectors** - Vertical lines show relationships
3. **Flat list** - All exercises rendered uniformly
4. **Simplified code** - ~200 lines removed

Total estimated tasks: 17
Files created: 2 (SupersetConnector.kt, ExerciseRowWithConnector.kt)
Files modified: 1 (RoutineEditorScreen.kt)
Files deleted: 1 (RoutineBuilderDialog.kt)

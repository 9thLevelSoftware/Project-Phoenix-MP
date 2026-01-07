# Multi-Select Routines Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add long-press multi-select with batch delete and batch copy to the Daily Routines screen.

**Architecture:** State-driven selection mode with FAB transformation. Cards gain checkbox when selection mode active. Existing duplicate naming logic reused for batch copy.

**Tech Stack:** Jetpack Compose, Material3, combinedClickable gestures

---

## Task 1: Add Selection State to RoutinesTab

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Add state variables after existing state**

```kotlin
// Selection mode state
var selectionMode by remember { mutableStateOf(false) }
val selectedIds = remember { mutableStateSetOf<String>() }

// Helper to clear selection
fun clearSelection() {
    selectedIds.clear()
    selectionMode = false
}
```

**Step 2: Add new callback parameter to RoutinesTab signature**

```kotlin
onDeleteRoutines: (Set<String>) -> Unit,  // Add after onDeleteRoutine
```

**Step 3: Verify compiles**

Run: `./gradlew :shared:compileDebugKotlinAndroid`

---

## Task 2: Update RoutineCard Signature and Remove Icon

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Update RoutineCard parameters**

```kotlin
@Composable
fun RoutineCard(
    routine: Routine,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onSelectionToggle: () -> Unit,
    onStartWorkout: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
)
```

**Step 2: Remove the purple icon box**

Delete the Box with gradient background (the 64dp icon container with FitnessCenter icon).

**Step 3: Add required imports**

```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
```

---

## Task 3: Implement Card Selection UI

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Update Card modifier for gestures**

Replace `Card(onClick = ...)` with:

```kotlin
val interactionSource = remember { MutableInteractionSource() }

Card(
    modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = {
                if (isSelectionMode) onSelectionToggle()
                else expanded = !expanded
            },
            onLongClick = {
                if (!isSelectionMode) onLongPress()
                else onSelectionToggle()
            }
        ),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
        containerColor = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surfaceContainerHighest
    ),
    // ... rest of card properties
)
```

**Step 2: Add checkbox in card content Row**

Replace the header Row content with:

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    // Checkbox - only visible in selection mode
    AnimatedVisibility(visible = isSelectionMode) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onSelectionToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
    }

    // Header Content
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${routine.exercises.size} exercises • ${formatEstimatedDuration(routine)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Expand Icon (hide in selection mode)
    if (!isSelectionMode) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

**Step 3: Add imports**

```kotlin
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
```

---

## Task 4: Implement FAB Transformation

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Add dialog state variables**

```kotlin
var showBatchDeleteDialog by remember { mutableStateOf(false) }
var showBatchCopyDialog by remember { mutableStateOf(false) }
```

**Step 2: Replace the FAB section with animated version**

```kotlin
// FAB Area - transforms based on selection mode
Box(
    modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(Spacing.medium)
) {
    // Normal mode: Single + FAB
    AnimatedVisibility(
        visible = !selectionMode,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        FloatingActionButton(
            onClick = onCreateRoutine,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add new routine",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    // Selection mode: Vertical stack of action buttons
    AnimatedVisibility(
        visible = selectionMode,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Cancel button (small)
            SmallFloatingActionButton(
                onClick = { clearSelection() },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }

            // Copy button
            FloatingActionButton(
                onClick = { showBatchCopyDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text("${selectedIds.size}")
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected")
                }
            }

            // Delete button (red)
            FloatingActionButton(
                onClick = { showBatchDeleteDialog = true },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error
            ) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text("${selectedIds.size}")
                        }
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                }
            }
        }
    }
}
```

**Step 3: Add imports**

```kotlin
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.SmallFloatingActionButton
```

---

## Task 5: Implement Confirmation Dialogs

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Add batch delete dialog**

```kotlin
if (showBatchDeleteDialog) {
    AlertDialog(
        onDismissRequest = { showBatchDeleteDialog = false },
        title = { Text("Delete ${selectedIds.size} routines?") },
        text = { Text("This cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDeleteRoutines(selectedIds.toSet())
                    showBatchDeleteDialog = false
                    clearSelection()
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showBatchDeleteDialog = false }) {
                Text("Cancel")
            }
        }
    )
}
```

**Step 2: Add batch copy dialog**

```kotlin
if (showBatchCopyDialog) {
    AlertDialog(
        onDismissRequest = { showBatchCopyDialog = false },
        title = { Text("Duplicate ${selectedIds.size} routines?") },
        text = { Text("New copies will be created with '(Copy)' suffix.") },
        confirmButton = {
            TextButton(
                onClick = {
                    // Copy each selected routine using existing duplicate logic
                    selectedIds.forEach { routineId ->
                        routines.find { it.id == routineId }?.let { routine ->
                            val newRoutineId = generateUUID()
                            val newExercises = routine.exercises.map { exercise ->
                                exercise.copy(
                                    id = generateUUID(),
                                    exercise = exercise.exercise.copy()
                                )
                            }

                            // Smart duplicate naming
                            val baseName = routine.name.replace(Regex(""" \(Copy( \d+)?\)$"""), "")
                            val copyPattern = Regex("""^${Regex.escape(baseName)} \(Copy( (\d+))?\)$""")
                            val existingCopyNumbers = routines
                                .mapNotNull { r ->
                                    when {
                                        r.name == baseName -> 0
                                        r.name == "$baseName (Copy)" -> 1
                                        else -> copyPattern.find(r.name)?.groups?.get(2)?.value?.toIntOrNull()
                                    }
                                }
                            val nextCopyNumber = (existingCopyNumbers.maxOrNull() ?: 0) + 1
                            val newName = if (nextCopyNumber == 1) {
                                "$baseName (Copy)"
                            } else {
                                "$baseName (Copy $nextCopyNumber)"
                            }

                            val duplicated = routine.copy(
                                id = newRoutineId,
                                name = newName,
                                createdAt = KmpUtils.currentTimeMillis(),
                                useCount = 0,
                                lastUsed = null,
                                exercises = newExercises
                            )
                            onSaveRoutine(duplicated)
                        }
                    }
                    showBatchCopyDialog = false
                    clearSelection()
                }
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = { showBatchCopyDialog = false }) {
                Text("Cancel")
            }
        }
    )
}
```

---

## Task 6: Wire Up Card Callbacks in LazyColumn

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Update items() call**

```kotlin
items(routines, key = { it.id }) { routine ->
    RoutineCard(
        routine = routine,
        isSelectionMode = selectionMode,
        isSelected = selectedIds.contains(routine.id),
        onLongPress = {
            selectionMode = true
            selectedIds.add(routine.id)
        },
        onSelectionToggle = {
            if (selectedIds.contains(routine.id)) {
                selectedIds.remove(routine.id)
                // Exit selection mode if nothing selected
                if (selectedIds.isEmpty()) {
                    selectionMode = false
                }
            } else {
                selectedIds.add(routine.id)
            }
        },
        onStartWorkout = { onStartWorkout(routine) },
        onEdit = { onEditRoutine(routine.id) },
        onDelete = { onDeleteRoutine(routine.id) },
        onDuplicate = { /* existing duplicate logic */ }
    )
}
```

---

## Task 7: Add Back Handler and Background Tap

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt`

**Step 1: Add BackHandler import and usage**

```kotlin
import androidx.activity.compose.BackHandler

// Inside RoutinesTab, after state declarations:
BackHandler(enabled = selectionMode) {
    clearSelection()
}
```

**Step 2: Add background tap detection**

Wrap the main Box content to detect taps on background:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

// The outer Box modifier becomes:
Box(
    modifier = modifier
        .fillMaxSize()
        .background(backgroundGradient)
        .pointerInput(selectionMode) {
            if (selectionMode) {
                detectTapGestures {
                    clearSelection()
                }
            }
        }
)
```

Note: Cards need `clickable` with `stopPropagation` behavior - the `combinedClickable` already handles this.

---

## Task 8: Implement onDeleteRoutines in MainViewModel/Caller

**Files:**
- Modify: Where RoutinesTab is called (likely MainScreen or similar)
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Add batch delete function to MainViewModel**

```kotlin
fun deleteRoutines(ids: Set<String>) {
    viewModelScope.launch {
        ids.forEach { id ->
            workoutRepository.deleteRoutine(id)
        }
    }
}
```

**Step 2: Pass callback to RoutinesTab**

```kotlin
onDeleteRoutines = { ids -> viewModel.deleteRoutines(ids) }
```

---

## Task 9: Build and Test

**Step 1: Compile**

```bash
./gradlew :shared:compileDebugKotlinAndroid
```

**Step 2: Build APK**

```bash
./gradlew :androidApp:assembleDebug
```

**Step 3: Manual test checklist**
- [ ] Long-press enters selection mode
- [ ] Checkbox appears on all cards
- [ ] Tapping cards toggles selection
- [ ] FAB transforms to 3 buttons
- [ ] Badge shows correct count
- [ ] X button clears selection
- [ ] Background tap clears selection
- [ ] Back gesture clears selection
- [ ] Delete dialog appears with count
- [ ] Copy dialog appears with count
- [ ] Batch delete works
- [ ] Batch copy creates properly named duplicates
- [ ] Selection clears after action

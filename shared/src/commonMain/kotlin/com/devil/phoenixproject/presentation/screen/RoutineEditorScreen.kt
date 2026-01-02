package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import com.devil.phoenixproject.presentation.components.SupersetHeader
import com.devil.phoenixproject.presentation.components.SupersetExerciseItem
import com.devil.phoenixproject.ui.theme.SupersetTheme
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


// State holder for the editor
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RoutineEditorScreen(
    routineId: String, // "new" or actual ID
    navController: androidx.navigation.NavController,
    viewModel: com.devil.phoenixproject.presentation.viewmodel.MainViewModel,
    exerciseRepository: ExerciseRepository,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    displayToKg: (Float, WeightUnit) -> Float,
    enableVideoPlayback: Boolean
) {
    // 1. Initialize State
    var state by remember { mutableStateOf(RoutineEditorState()) }
    var showExercisePicker by remember { mutableStateOf(false) }
    var hasInitialized by remember { mutableStateOf(false) }

    // Exercise configuration state - holds exercise being configured (new or edit)
    var exerciseToConfig by remember { mutableStateOf<RoutineExercise?>(null) }
    var isNewExercise by remember { mutableStateOf(false) } // true = adding new, false = editing existing
    var editingIndex by remember { mutableStateOf<Int?>(null) } // index when editing existing

    // Menu state for superset and exercise context menus
    var supersetMenuFor by remember { mutableStateOf<String?>(null) } // superset ID showing menu
    var exerciseMenuFor by remember { mutableStateOf<String?>(null) } // exercise ID showing menu

    // Dialog state for superset editing
    var supersetToRename by remember { mutableStateOf<Superset?>(null) }
    var supersetToEditRest by remember { mutableStateOf<Superset?>(null) }
    var supersetToChangeColor by remember { mutableStateOf<Superset?>(null) }
    var supersetToDelete by remember { mutableStateOf<Superset?>(null) } // Delete All confirmation

    // Get PersonalRecordRepository for the bottom sheet
    val personalRecordRepository: PersonalRecordRepository = koinInject()

    // Load routine if editing
    LaunchedEffect(routineId) {
        if (!hasInitialized && routineId != "new") {
            val existing = viewModel.getRoutineById(routineId)
            if (existing != null) {
                state = state.copy(
                    routineName = existing.name,
                    routine = existing
                )
            }
            hasInitialized = true
        } else if (!hasInitialized) {
            state = state.copy(
                routineName = "New Routine",
                routine = Routine(id = "new", name = "New Routine")
            )
            hasInitialized = true
        }
    }

    // Drag and Drop State
    val lazyListState = rememberLazyListState()

    // Helper: Update Routine
    fun updateRoutine(updateFn: (Routine) -> Routine) {
        state.routine?.let { current ->
            state = state.copy(routine = updateFn(current))
        }
    }

    // Helper: Update Exercises
    fun updateExercises(newList: List<RoutineExercise>) {
        updateRoutine { it.copy(exercises = newList.mapIndexed { i, ex -> ex.copy(orderIndex = i) }) }
    }

    // Helper: Update Superset
    fun updateSuperset(supersetId: String, updateFn: (Superset) -> Superset) {
        updateRoutine { routine ->
            routine.copy(
                supersets = routine.supersets.map { if (it.id == supersetId) updateFn(it) else it }
            )
        }
    }

    // Helper: Dissolve Superset (remove container, keep exercises as standalone)
    fun dissolveSuperset(supersetId: String) {
        val routine = state.routine ?: return
        val updatedExercises = routine.exercises.map { ex ->
            if (ex.supersetId == supersetId) ex.copy(supersetId = null, orderInSuperset = 0)
            else ex
        }
        val updatedSupersets = routine.supersets.filter { it.id != supersetId }
        updateRoutine { it.copy(exercises = updatedExercises, supersets = updatedSupersets) }
    }

    // Helper: Delete Superset with all exercises
    fun deleteSupersetWithExercises(supersetId: String) {
        val routine = state.routine ?: return
        val updatedExercises = routine.exercises.filter { it.supersetId != supersetId }
        val updatedSupersets = routine.supersets.filter { it.id != supersetId }
        updateRoutine { it.copy(exercises = updatedExercises, supersets = updatedSupersets) }
    }

    // Reorderable state for drag-and-drop
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val routine = state.routine ?: return@rememberReorderableLazyListState
        val items = routine.getItems().toMutableList()
        val fromIndex = from.index
        val toIndex = to.index

        if (fromIndex in items.indices && toIndex in items.indices) {
            val moved = items.removeAt(fromIndex)
            items.add(toIndex, moved)

            // Rebuild exercises and supersets with updated order indices
            var exerciseOrder = 0
            var supersetOrder = 0
            val newExercises = mutableListOf<RoutineExercise>()
            val newSupersets = mutableListOf<Superset>()

            items.forEachIndexed { index, item ->
                when (item) {
                    is RoutineItem.Single -> {
                        newExercises.add(item.exercise.copy(orderIndex = index))
                        exerciseOrder++
                    }
                    is RoutineItem.SupersetItem -> {
                        newSupersets.add(item.superset.copy(orderIndex = index))
                        // Keep superset exercises with their original orderInSuperset
                        item.superset.exercises.forEach { ex ->
                            newExercises.add(ex.copy(orderIndex = index))
                        }
                        supersetOrder++
                    }
                }
            }

            updateRoutine { it.copy(exercises = newExercises, supersets = newSupersets) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSelectionMode) {
                        Text("${state.selectedIds.size} Selected", style = MaterialTheme.typography.titleMedium)
                    } else {
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
                        Icon(
                            if (state.isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (!state.isSelectionMode) {
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
                    } else {
                        // Delete selected items
                        if (state.selectedIds.isNotEmpty()) {
                            IconButton(onClick = {
                                val remaining = state.exercises.filterNot { it.id in state.selectedIds }
                                updateExercises(remaining)
                                state = state.copy(isSelectionMode = false, selectedIds = emptySet())
                            }) {
                                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.isSelectionMode,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomAppBar {
                    val selectedCount = state.selectedIds.size
                    // Group into Superset
                    if (selectedCount >= 2) {
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                val routine = state.routine ?: return@NavigationBarItem
                                val newSupersetId = generateSupersetId()
                                val existingColors = routine.supersets.map { it.colorIndex }.toSet()
                                val newColor = SupersetColors.next(existingColors)

                                // Find order index for the new superset (use earliest selected)
                                val selectedExercises = routine.exercises.filter { it.id in state.selectedIds }
                                val minOrder = selectedExercises.minOfOrNull { it.orderIndex } ?: 0

                                // Create new superset
                                val newSuperset = Superset(
                                    id = newSupersetId,
                                    routineId = routine.id,
                                    name = "Superset",
                                    colorIndex = newColor,
                                    orderIndex = minOrder
                                )

                                // Update exercises to belong to superset
                                val updatedExercises = routine.exercises.map { ex ->
                                    if (ex.id in state.selectedIds) {
                                        val orderInSuperset = selectedExercises.indexOf(ex)
                                        ex.copy(supersetId = newSupersetId, orderInSuperset = orderInSuperset)
                                    } else ex
                                }

                                updateRoutine {
                                    it.copy(
                                        exercises = updatedExercises,
                                        supersets = routine.supersets + newSuperset
                                    )
                                }
                                state = state.copy(isSelectionMode = false, selectedIds = emptySet())
                            },
                            icon = { Icon(Icons.Default.Link, null) },
                            label = { Text("Superset") }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showExercisePicker = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add Exercise") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                bottom = 100.dp,
                top = padding.calculateTopPadding(),
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val items = state.items

            if (items.isEmpty()) {
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

            items.forEachIndexed { index, routineItem ->
                when (routineItem) {
                    is RoutineItem.Single -> {
                        item(key = routineItem.exercise.id) {
                            ReorderableItem(
                                state = reorderState,
                                key = routineItem.exercise.id
                            ) { isDragging ->
                                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                                StandaloneExerciseCard(
                                    exercise = routineItem.exercise,
                                    isSelected = routineItem.exercise.id in state.selectedIds,
                                    isSelectionMode = state.isSelectionMode,
                                    elevation = elevation,
                                    weightUnit = weightUnit,
                                    kgToDisplay = kgToDisplay,
                                    onToggleSelection = {
                                        val newIds = if (routineItem.exercise.id in state.selectedIds) {
                                            state.selectedIds - routineItem.exercise.id
                                        } else {
                                            state.selectedIds + routineItem.exercise.id
                                        }
                                        state = state.copy(
                                            selectedIds = newIds,
                                            isSelectionMode = newIds.isNotEmpty()
                                        )
                                    },
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
                            }

                            // Exercise context menu
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
                        }
                    }

                    is RoutineItem.SupersetItem -> {
                        val superset = routineItem.superset
                        val isExpanded = superset.id !in state.collapsedSupersets
                        val supersetExercises = superset.exercises

                        // Superset Header
                        item(key = "superset_header_${superset.id}") {
                            ReorderableItem(
                                state = reorderState,
                                key = "superset_header_${superset.id}"
                            ) { isDragging ->
                                Box {
                                    SupersetHeader(
                                        superset = superset,
                                        isExpanded = isExpanded,
                                        isDragging = isDragging,
                                        onToggleExpand = {
                                            val newCollapsed = if (superset.id in state.collapsedSupersets) {
                                                state.collapsedSupersets - superset.id
                                            } else {
                                                state.collapsedSupersets + superset.id
                                            }
                                            state = state.copy(collapsedSupersets = newCollapsed)
                                        },
                                        onMenuClick = { supersetMenuFor = superset.id },
                                        onDragHandle = {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Drag",
                                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                modifier = Modifier.draggableHandle(
                                                    interactionSource = remember { MutableInteractionSource() }
                                                )
                                            )
                                        }
                                    )

                                    // Superset context menu
                                    DropdownMenu(
                                        expanded = supersetMenuFor == superset.id,
                                        onDismissRequest = { supersetMenuFor = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                supersetToRename = superset
                                                supersetMenuFor = null
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rest Time") },
                                            onClick = {
                                                supersetToEditRest = superset
                                                supersetMenuFor = null
                                            },
                                            leadingIcon = { Icon(Icons.Default.Timer, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Change Color") },
                                            onClick = {
                                                supersetToChangeColor = superset
                                                supersetMenuFor = null
                                            },
                                            leadingIcon = { Icon(Icons.Default.Palette, null) }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Dissolve Superset") },
                                            onClick = {
                                                dissolveSuperset(superset.id)
                                                supersetMenuFor = null
                                            },
                                            leadingIcon = { Icon(Icons.Default.LinkOff, null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete All", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                supersetToDelete = superset
                                                supersetMenuFor = null
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Superset exercises (when expanded)
                        if (isExpanded) {
                            if (supersetExercises.isEmpty()) {
                                item(key = "superset_empty_${superset.id}") {
                                    EmptySupersetHint(colorIndex = superset.colorIndex)
                                }
                            } else {
                                supersetExercises.forEachIndexed { exIndex, exercise ->
                                    item(key = exercise.id) {
                                        ReorderableItem(
                                            state = reorderState,
                                            key = exercise.id
                                        ) { isDragging ->
                                            Box {
                                                SupersetExerciseItem(
                                                    exercise = exercise,
                                                    colorIndex = superset.colorIndex,
                                                    isFirst = exIndex == 0,
                                                    isLast = exIndex == supersetExercises.lastIndex,
                                                    isDragging = isDragging,
                                                    weightUnit = weightUnit,
                                                    kgToDisplay = kgToDisplay,
                                                    onMenuClick = { exerciseMenuFor = exercise.id },
                                                    onDragHandle = {
                                                        Icon(
                                                            Icons.Default.DragHandle,
                                                            contentDescription = "Drag",
                                                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                            modifier = Modifier.draggableHandle(
                                                                interactionSource = remember { MutableInteractionSource() }
                                                            )
                                                        )
                                                    },
                                                    onClick = {
                                                        exerciseToConfig = exercise
                                                        isNewExercise = false
                                                        editingIndex = state.exercises.indexOf(exercise)
                                                    }
                                                )

                                                // Exercise in superset context menu
                                                DropdownMenu(
                                                    expanded = exerciseMenuFor == exercise.id,
                                                    onDismissRequest = { exerciseMenuFor = null }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("Edit") },
                                                        onClick = {
                                                            exerciseToConfig = exercise
                                                            isNewExercise = false
                                                            editingIndex = state.exercises.indexOf(exercise)
                                                            exerciseMenuFor = null
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Remove from Superset") },
                                                        onClick = {
                                                            val updatedExercises = state.exercises.map { ex ->
                                                                if (ex.id == exercise.id) {
                                                                    ex.copy(supersetId = null, orderInSuperset = 0)
                                                                } else ex
                                                            }
                                                            updateExercises(updatedExercises)
                                                            exerciseMenuFor = null
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.LinkOff, null) }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Delete") },
                                                        onClick = {
                                                            val remaining = state.exercises.filter { it.id != exercise.id }
                                                            updateExercises(remaining)
                                                            exerciseMenuFor = null
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Exercise Picker Dialog
    if (showExercisePicker) {
        ExercisePickerDialog(
            showDialog = true,
            onDismiss = { showExercisePicker = false },
            onExerciseSelected = { selectedExercise ->
                val newEx = RoutineExercise(
                    id = generateUUID(),
                    exercise = selectedExercise,
                    cableConfig = selectedExercise.resolveDefaultCableConfig(),
                    orderIndex = state.exercises.size,
                    weightPerCableKg = 5f
                )
                exerciseToConfig = newEx
                isNewExercise = true
                editingIndex = null
                showExercisePicker = false
            },
            exerciseRepository = exerciseRepository,
            enableVideoPlayback = false
        )
    }

    // Exercise Configuration Bottom Sheet
    exerciseToConfig?.let { exercise ->
        ExerciseEditBottomSheet(
            exercise = exercise,
            weightUnit = weightUnit,
            enableVideoPlayback = enableVideoPlayback,
            kgToDisplay = kgToDisplay,
            displayToKg = displayToKg,
            exerciseRepository = exerciseRepository,
            personalRecordRepository = personalRecordRepository,
            formatWeight = { weight, unit ->
                val displayWeight = kgToDisplay(weight, unit)
                if (unit == WeightUnit.LB) "${displayWeight.toInt()} lbs" else "${displayWeight.toInt()} kg"
            },
            onSave = { configuredExercise ->
                if (isNewExercise) {
                    updateExercises(state.exercises + configuredExercise)
                } else {
                    editingIndex?.let { index ->
                        val newList = state.exercises.toMutableList().apply { set(index, configuredExercise) }
                        updateExercises(newList)
                    }
                }
                exerciseToConfig = null
                isNewExercise = false
                editingIndex = null
            },
            onDismiss = {
                exerciseToConfig = null
                isNewExercise = false
                editingIndex = null
            },
            buttonText = if (isNewExercise) "Add to Routine" else "Save"
        )
    }

    // Rename Superset Dialog
    supersetToRename?.let { superset ->
        var newName by remember { mutableStateOf(superset.name) }
        AlertDialog(
            onDismissRequest = { supersetToRename = null },
            title = { Text("Rename Superset") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateSuperset(superset.id) { it.copy(name = newName) }
                        supersetToRename = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { supersetToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Rest Time Dialog
    supersetToEditRest?.let { superset ->
        var restSeconds by remember { mutableStateOf(superset.restBetweenSeconds) }
        AlertDialog(
            onDismissRequest = { supersetToEditRest = null },
            title = { Text("Rest Between Exercises") },
            text = {
                Column {
                    Text(
                        "Rest time between exercises in superset",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = { if (restSeconds > 5) restSeconds -= 5 }
                        ) {
                            Icon(Icons.Default.Remove, "Decrease")
                        }
                        Text(
                            "${restSeconds}s",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        IconButton(
                            onClick = { if (restSeconds < 120) restSeconds += 5 }
                        ) {
                            Icon(Icons.Default.Add, "Increase")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateSuperset(superset.id) { it.copy(restBetweenSeconds = restSeconds) }
                        supersetToEditRest = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { supersetToEditRest = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Color Picker Dialog
    supersetToChangeColor?.let { superset ->
        AlertDialog(
            onDismissRequest = { supersetToChangeColor = null },
            title = { Text("Choose Color") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    SupersetTheme.colors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (superset.colorIndex == index) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable {
                                    updateSuperset(superset.id) { it.copy(colorIndex = index) }
                                    supersetToChangeColor = null
                                }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { supersetToChangeColor = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Superset Confirmation Dialog
    supersetToDelete?.let { superset ->
        AlertDialog(
            onDismissRequest = { supersetToDelete = null },
            title = { Text("Delete Superset?") },
            text = {
                Text(
                    "This will delete the superset \"${superset.name}\" and all ${superset.exerciseCount} exercises in it. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSupersetWithExercises(superset.id)
                        supersetToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { supersetToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Empty superset hint - shows when a superset has no exercises
 */
@Composable
private fun EmptySupersetHint(
    colorIndex: Int,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = color.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Drag exercises here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Standalone exercise card (not in a superset)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = { if (isSelectionMode) onToggleSelection() else onEdit() },
                onLongClick = { if (!isSelectionMode) onToggleSelection() }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left rail (selection or drag handle)
        Box(
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
            } else {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = dragModifier
                )
            }
        }

        // Card content
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
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

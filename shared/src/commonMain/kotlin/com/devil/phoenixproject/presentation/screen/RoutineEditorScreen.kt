package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import com.devil.phoenixproject.presentation.components.ExerciseRowInSuperset
import com.devil.phoenixproject.presentation.components.ExerciseRowWithConnector
import com.devil.phoenixproject.presentation.components.RestTimePickerDialog
import com.devil.phoenixproject.presentation.components.SelectionActionBar
import com.devil.phoenixproject.presentation.components.SupersetContainer
import com.devil.phoenixproject.presentation.components.SupersetHeader
import com.devil.phoenixproject.presentation.components.SupersetPickerDialog
import com.devil.phoenixproject.ui.theme.SupersetTheme
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


// State holder for the editor
data class RoutineEditorState(
    val routineName: String = "",
    val routine: Routine? = null,
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

    // Selection mode state (for superset creation/management)
    var selectionMode by remember { mutableStateOf(false) }
    val selectedExerciseIds = remember { mutableStateSetOf<String>() }

    // Helper to clear selection
    fun clearSelection() {
        selectedExerciseIds.clear()
        selectionMode = false
    }

    // Helper to check if selected exercises are all in same superset
    fun selectedExercisesInSameSuperset(): String? {
        val selected = state.exercises.filter { it.id in selectedExerciseIds }
        if (selected.isEmpty()) return null
        val supersetId = selected.first().supersetId
        return if (selected.all { it.supersetId == supersetId }) supersetId else null
    }

    // Helper to check if any selected exercises are in supersets
    fun anySelectedInSuperset(): Boolean {
        return state.exercises.any { it.id in selectedExerciseIds && it.supersetId != null }
    }

    // Dialog state for superset editing
    var supersetToRename by remember { mutableStateOf<Superset?>(null) }
    var supersetToEditRest by remember { mutableStateOf<Superset?>(null) }
    var supersetToChangeColor by remember { mutableStateOf<Superset?>(null) }
    var supersetToDelete by remember { mutableStateOf<Superset?>(null) } // Delete All confirmation

    // Selection mode dialogs
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showSupersetPickerDialog by remember { mutableStateOf(false) }

    // Superset being edited (for add exercise flow)
    var supersetForAddExercise by remember { mutableStateOf<Superset?>(null) }

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

    fun normalizeRoutine(routine: Routine): Routine {
        val reindexedExercises = routine.exercises.mapIndexed { index, ex ->
            ex.copy(orderIndex = index)
        }
        val exercisesBySuperset = reindexedExercises.groupBy { it.supersetId }
        val normalizedExercises = reindexedExercises.map { ex ->
            val supersetId = ex.supersetId ?: return@map ex
            val ordered = exercisesBySuperset[supersetId].orEmpty().sortedBy { it.orderIndex }
            val newOrder = ordered.indexOfFirst { it.id == ex.id }
            ex.copy(orderInSuperset = if (newOrder >= 0) newOrder else ex.orderInSuperset)
        }
        val normalizedSupersets = routine.supersets.mapNotNull { superset ->
            val minOrder = normalizedExercises
                .filter { it.supersetId == superset.id }
                .minOfOrNull { it.orderIndex }
            minOrder?.let { superset.copy(orderIndex = it) }
        }
        return routine.copy(
            exercises = normalizedExercises,
            supersets = normalizedSupersets
        )
    }

    // Helper: Update Routine
    fun updateRoutine(updateFn: (Routine) -> Routine) {
        state.routine?.let { current ->
            val updated = updateFn(current)
            state = state.copy(routine = normalizeRoutine(updated))
        }
    }

    // Helper: Update Exercises
    fun updateExercises(newList: List<RoutineExercise>) {
        updateRoutine { it.copy(exercises = newList) }
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

    // Helper: Create new superset with selected exercises
    fun createSupersetWithSelected() {
        val routine = state.routine ?: return
        val selectedExercises = state.exercises.filter { it.id in selectedExerciseIds }
        if (selectedExercises.size < 2) return

        val newSupersetId = generateSupersetId()
        val existingColors = routine.supersets.map { it.colorIndex }.toSet()
        val newColor = SupersetColors.next(existingColors)

        // Generate name like "Superset 1", "Superset 2", etc.
        val existingNumbers = routine.supersets
            .mapNotNull { s ->
                Regex("""Superset (\d+)""").find(s.name)?.groupValues?.get(1)?.toIntOrNull()
            }
        val nextNumber = (existingNumbers.maxOrNull() ?: 0) + 1
        val supersetName = "Superset $nextNumber"

        val newSuperset = Superset(
            id = newSupersetId,
            routineId = routine.id,
            name = supersetName,
            colorIndex = newColor,
            orderIndex = selectedExercises.minOf { it.orderIndex }
        )

        val updatedExercises = state.exercises.map { ex ->
            if (ex.id in selectedExerciseIds) {
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
        clearSelection()
    }

    // Helper: Add selected exercises to existing superset
    fun addSelectedToSuperset(superset: Superset) {
        val selectedExercises = state.exercises.filter { it.id in selectedExerciseIds }
        val currentMaxOrder = state.exercises
            .filter { it.supersetId == superset.id }
            .maxOfOrNull { it.orderInSuperset } ?: -1

        val updatedExercises = state.exercises.map { ex ->
            if (ex.id in selectedExerciseIds) {
                val newOrder = currentMaxOrder + 1 + selectedExercises.indexOf(ex)
                ex.copy(supersetId = superset.id, orderInSuperset = newOrder)
            } else ex
        }

        updateExercises(updatedExercises)
        clearSelection()
    }

    // Helper: Unlink exercise from superset (make it standalone)
    fun unlinkFromSuperset(exerciseId: String) {
        val updatedExercises = state.exercises.map { ex ->
            if (ex.id == exerciseId) {
                ex.copy(supersetId = null, orderInSuperset = 0)
            } else ex
        }
        updateExercises(updatedExercises)
    }

    // Reorderable state for drag-and-drop on routine items
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val routine = state.routine ?: return@rememberReorderableLazyListState
        val items = routine.getItems().toMutableList()
        val fromIndex = from.index
        val toIndex = to.index

        if (fromIndex in items.indices && toIndex in items.indices) {
            val moved = items.removeAt(fromIndex)
            items.add(toIndex, moved)

            val reorderedExercises = items.flatMap { item ->
                when (item) {
                    is RoutineItem.Single -> listOf(item.exercise)
                    is RoutineItem.SupersetItem -> item.superset.exercises
                        .sortedBy { it.orderInSuperset }
                }
            }

            updateRoutine { it.copy(exercises = reorderedExercises) }
        }
    }

    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showExercisePicker = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add Exercise") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (selectionMode) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { clearSelection() }
                    } else {
                        Modifier
                    }
                )
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    bottom = 100.dp,
                    top = padding.calculateTopPadding(),
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp), // Tighter spacing for connected items
                modifier = Modifier.fillMaxSize()
            ) {
                val routineItems = state.routine?.getItems() ?: emptyList()

                if (routineItems.isEmpty()) {
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

                routineItems.forEach { routineItem ->
                    when (routineItem) {
                        is RoutineItem.SupersetItem -> {
                            val superset = routineItem.superset
                            item(key = "superset_${superset.id}") {
                                ReorderableItem(
                                    state = reorderState,
                                    key = "superset_${superset.id}"
                                ) { isDragging ->
                                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                                    SupersetContainer(
                                        colorIndex = superset.colorIndex,
                                        modifier = Modifier.shadow(elevation, RoundedCornerShape(12.dp))
                                    ) {
                                        // Header
                                        SupersetHeader(
                                            superset = superset,
                                            onRename = { supersetToRename = superset },
                                            onChangeRestTime = { supersetToEditRest = superset },
                                            onChangeColor = { supersetToChangeColor = superset },
                                            onAddExercise = {
                                                supersetForAddExercise = superset
                                                showExercisePicker = true
                                            },
                                            onCopy = {
                                                // Copy superset with all exercises
                                                val newSupersetId = generateSupersetId()
                                                val newSuperset = superset.copy(
                                                    id = newSupersetId,
                                                    name = "${superset.name} (Copy)"
                                                )
                                                val copiedExercises = superset.exercises.map { ex ->
                                                    ex.copy(
                                                        id = generateUUID(),
                                                        supersetId = newSupersetId
                                                    )
                                                }
                                                updateRoutine { routine ->
                                                    routine.copy(
                                                        supersets = routine.supersets + newSuperset,
                                                        exercises = routine.exercises + copiedExercises
                                                    )
                                                }
                                            },
                                            onDelete = { supersetToDelete = superset },
                                            showDragHandle = !selectionMode,
                                            dragModifier = if (selectionMode) {
                                                Modifier
                                            } else {
                                                Modifier.draggableHandle(
                                                    interactionSource = remember { MutableInteractionSource() }
                                                )
                                            }
                                        )

                                        // Exercises in superset
                                        superset.exercises.forEach { exercise ->
                                            ExerciseRowInSuperset(
                                                exercise = exercise,
                                                supersetRestSeconds = superset.restBetweenSeconds,
                                                weightUnit = weightUnit,
                                                kgToDisplay = kgToDisplay,
                                                isSelectionMode = selectionMode,
                                                isSelected = selectedExerciseIds.contains(exercise.id),
                                                onClick = {
                                                    if (!selectionMode) {
                                                        exerciseToConfig = exercise
                                                        isNewExercise = false
                                                        editingIndex = state.exercises.indexOf(exercise)
                                                    }
                                                },
                                                onLongPress = {
                                                    selectionMode = true
                                                    selectedExerciseIds.add(exercise.id)
                                                },
                                                onSelectionToggle = {
                                                    if (selectedExerciseIds.contains(exercise.id)) {
                                                        selectedExerciseIds.remove(exercise.id)
                                                        if (selectedExerciseIds.isEmpty()) selectionMode = false
                                                    } else {
                                                        selectedExerciseIds.add(exercise.id)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is RoutineItem.Single -> {
                            val exercise = routineItem.exercise
                            item(key = exercise.id) {
                                ReorderableItem(
                                    state = reorderState,
                                    key = exercise.id
                                ) { isDragging ->
                                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                                    Box {
                                        ExerciseRowWithConnector(
                                            exercise = exercise,
                                            elevation = elevation,
                                            weightUnit = weightUnit,
                                            kgToDisplay = kgToDisplay,
                                            onClick = {
                                                if (!selectionMode) {
                                                    exerciseToConfig = exercise
                                                    isNewExercise = false
                                                    editingIndex = state.exercises.indexOf(exercise)
                                                }
                                            },
                                            onMenuClick = { exerciseMenuFor = exercise.id },
                                            dragModifier = if (selectionMode) {
                                                Modifier
                                            } else {
                                                Modifier.draggableHandle(
                                                    interactionSource = remember { MutableInteractionSource() }
                                                )
                                            },
                                            isSelectionMode = selectionMode,
                                            isSelected = selectedExerciseIds.contains(exercise.id),
                                            onLongPress = {
                                                selectionMode = true
                                                selectedExerciseIds.add(exercise.id)
                                            },
                                            onSelectionToggle = {
                                                if (selectedExerciseIds.contains(exercise.id)) {
                                                    selectedExerciseIds.remove(exercise.id)
                                                    if (selectedExerciseIds.isEmpty()) selectionMode = false
                                                } else {
                                                    selectedExerciseIds.add(exercise.id)
                                                }
                                            }
                                        )

                                        // Keep the dropdown menu for standalone exercises
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

            // Selection mode action bar
            SelectionActionBar(
                visible = selectionMode,
                selectedCount = selectedExerciseIds.size,
                canAddToSuperset = selectedExerciseIds.size >= 2,
                canRemoveFromSuperset = anySelectedInSuperset(),
                hasExistingSupersets = state.supersets.isNotEmpty(),
                onCancel = { clearSelection() },
                onCopy = {
                    // Copy all selected exercises with new IDs (not in supersets)
                    val selectedExercises = state.exercises.filter { it.id in selectedExerciseIds }
                    val copiedExercises = selectedExercises.map { ex ->
                        ex.copy(
                            id = generateUUID(),
                            supersetId = null,
                            orderInSuperset = 0
                        )
                    }
                    updateExercises(state.exercises + copiedExercises)
                    clearSelection()
                },
                onDelete = { showBatchDeleteDialog = true },
                onAddToSuperset = { showSupersetPickerDialog = true },
                onRemoveFromSuperset = {
                    // Remove all selected exercises from their supersets       
                    val updatedExercises = state.exercises.map { ex ->
                        if (ex.id in selectedExerciseIds && ex.supersetId != null) {
                            ex.copy(supersetId = null, orderInSuperset = 0)
                        } else ex
                    }
                    updateExercises(updatedExercises)

                    clearSelection()
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // Exercise Picker Dialog
    if (showExercisePicker) {
        ExercisePickerDialog(
            showDialog = true,
            onDismiss = {
                showExercisePicker = false
                supersetForAddExercise = null  // Clear superset target on dismiss
            },
            onExerciseSelected = { selectedExercise ->
                val newEx = RoutineExercise(
                    id = generateUUID(),
                    exercise = selectedExercise,
                    cableConfig = selectedExercise.resolveDefaultCableConfig(),
                    orderIndex = state.exercises.size,
                    weightPerCableKg = 5f,
                    // If adding to a superset, set the superset reference
                    supersetId = supersetForAddExercise?.id,
                    orderInSuperset = supersetForAddExercise?.let { ss ->
                        state.exercises.filter { it.supersetId == ss.id }.size
                    } ?: 0
                )
                exerciseToConfig = newEx
                isNewExercise = true
                editingIndex = null
                showExercisePicker = false
                supersetForAddExercise = null  // Clear after use
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

    // Rest Time Picker Dialog (new chip-based picker)
    supersetToEditRest?.let { superset ->
        RestTimePickerDialog(
            currentRestSeconds = superset.restBetweenSeconds,
            onSelect = { newRest ->
                updateSuperset(superset.id) { it.copy(restBetweenSeconds = newRest) }
                supersetToEditRest = null
            },
            onDismiss = { supersetToEditRest = null }
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

    // Superset Picker Dialog
    if (showSupersetPickerDialog) {
        val supersetsWithExercises = state.supersets.map { superset ->
            superset.copy(
                exercises = state.exercises
                    .filter { it.supersetId == superset.id }
                    .sortedBy { it.orderInSuperset }
            )
        }
        SupersetPickerDialog(
            existingSupersets = supersetsWithExercises,
            canCreateNew = selectedExerciseIds.size >= 2,
            onCreateNew = {
                createSupersetWithSelected()
                showSupersetPickerDialog = false
            },
            onSelectExisting = { superset ->
                addSelectedToSuperset(superset)
                showSupersetPickerDialog = false
            },
            onDismiss = { showSupersetPickerDialog = false }
        )
    }

    // Batch Delete Dialog
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete ${selectedExerciseIds.size} exercises?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val remaining = state.exercises.filter { it.id !in selectedExerciseIds }
                        updateExercises(remaining)
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
}

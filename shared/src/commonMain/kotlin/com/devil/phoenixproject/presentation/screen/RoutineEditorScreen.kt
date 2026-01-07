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
import com.devil.phoenixproject.presentation.components.ConnectorPosition
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import com.devil.phoenixproject.presentation.components.ExerciseRowWithConnector
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

    // Helper: Unlink exercise from superset (make it standalone)
    fun unlinkFromSuperset(exerciseId: String) {
        val updatedExercises = state.exercises.map { ex ->
            if (ex.id == exerciseId) {
                ex.copy(supersetId = null, orderInSuperset = 0)
            } else ex
        }
        updateExercises(updatedExercises)
    }

    // Reorderable state for drag-and-drop on flat list
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

            // Preserve existing supersetId - exercises stay in their supersets
            updateRoutine { it.copy(exercises = newExercises) }
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
            ExtendedFloatingActionButton(
                onClick = { showExercisePicker = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add Exercise") },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
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
            verticalArrangement = Arrangement.spacedBy(4.dp), // Tighter spacing for connected items
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

                            // Context menu (placeholder - will be refactored in Task 12)
                            DropdownMenu(
                                expanded = exerciseMenuFor == flatItem.exercise.id,
                                onDismissRequest = { exerciseMenuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        exerciseToConfig = flatItem.exercise
                                        isNewExercise = false
                                        editingIndex = state.exercises.indexOf(flatItem.exercise)
                                        exerciseMenuFor = null
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )

                                // Show "Create Superset" or "Unlink" based on superset state
                                if (flatItem.exercise.supersetId == null) {
                                    val currentIndex = state.exercises.indexOfFirst { it.id == flatItem.exercise.id }
                                    val hasNext = currentIndex >= 0 && currentIndex < state.exercises.lastIndex
                                    val nextExercise = if (hasNext) state.exercises[currentIndex + 1] else null
                                    val canSuperset = hasNext && nextExercise?.supersetId == null

                                    if (canSuperset) {
                                        DropdownMenuItem(
                                            text = { Text("Create Superset") },
                                            onClick = {
                                                createSupersetWithNext(flatItem.exercise.id)
                                                exerciseMenuFor = null
                                            },
                                            leadingIcon = { Icon(Icons.Default.Link, null) }
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Remove from Superset") },
                                        onClick = {
                                            unlinkFromSuperset(flatItem.exercise.id)
                                            exerciseMenuFor = null
                                        },
                                        leadingIcon = { Icon(Icons.Default.LinkOff, null) }
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        val remaining = state.exercises.filter { it.id != flatItem.exercise.id }
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

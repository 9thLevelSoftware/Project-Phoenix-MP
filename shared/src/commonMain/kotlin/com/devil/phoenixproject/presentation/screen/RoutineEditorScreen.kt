package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ExercisePickerDialog
import org.koin.compose.koinInject
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


// State holder for the editor
data class RoutineEditorState(
    val routineName: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false
)

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

    // Get PersonalRecordRepository for the bottom sheet
    val personalRecordRepository: PersonalRecordRepository = koinInject()

    // Load routine if editing
    LaunchedEffect(routineId) {
        if (!hasInitialized && routineId != "new") {
            val existing = viewModel.getRoutineById(routineId)
            if (existing != null) {
                state = state.copy(
                    routineName = existing.name,
                    exercises = existing.exercises.sortedBy { it.orderIndex }
                )
            }
            hasInitialized = true
        } else if (!hasInitialized) {
            state = state.copy(routineName = "New Routine")
            hasInitialized = true
        }
    }

    // Drag and Drop State
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val list = state.exercises.toMutableList()
        val fromIndex = from.index
        val toIndex = to.index
        
        if (fromIndex in list.indices && toIndex in list.indices) {
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
            // Re-assign order indices immediately
            state = state.copy(exercises = list.mapIndexed { i, ex -> ex.copy(orderIndex = i) })
        }
    }

    // Helper: Update Exercises
    fun updateExercises(newList: List<RoutineExercise>) {
        state = state.copy(exercises = newList.mapIndexed { i, ex -> ex.copy(orderIndex = i) })
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
                                val routineToSave = Routine(
                                    id = if (routineId == "new") generateUUID() else routineId,
                                    name = state.routineName.ifBlank { "Unnamed Routine" },
                                    exercises = state.exercises,
                                    createdAt = KmpUtils.currentTimeMillis() // Preserve original date in real app
                                )
                                viewModel.saveRoutine(routineToSave)
                                navController.popBackStack()
                            }
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // Contextual Actions
                        if (state.selectedIds.isNotEmpty()) {
                            // Delete Action
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
            // SUPERSET ACTION BAR
            AnimatedVisibility(
                visible = state.isSelectionMode,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomAppBar {
                    val selectedCount = state.selectedIds.size
                    // Group Button
                    if (selectedCount >= 2) {
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                val newGroupId = generateSupersetGroupId()
                                // Find the earliest index to keep them together if desired, 
                                // or just group them in place. Grouping usually implies adjacency.
                                // For MVP, we just assign the ID.
                                val newExercises = state.exercises.map {
                                    if (it.id in state.selectedIds) it.copy(supersetGroupId = newGroupId) else it
                                }
                                updateExercises(newExercises)
                                state = state.copy(isSelectionMode = false, selectedIds = emptySet())
                            },
                            icon = { Icon(Icons.Default.Link, null) },
                            label = { Text("Group") }
                        )
                    }
                    
                    // Ungroup Button
                    val canUngroup = state.exercises.any { it.id in state.selectedIds && it.supersetGroupId != null }
                    if (canUngroup) {
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                val newExercises = state.exercises.map {
                                    if (it.id in state.selectedIds) it.copy(supersetGroupId = null) else it
                                }
                                updateExercises(newExercises)
                                state = state.copy(isSelectionMode = false, selectedIds = emptySet())
                            },
                            icon = { Icon(Icons.Default.LinkOff, null) },
                            label = { Text("Ungroup") }
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
        // THE LIST
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 100.dp, top = padding.calculateTopPadding()),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(state.exercises, key = { _, item -> item.id }) { index, exercise ->
                // Visual Logic for Supersets
                val currentGroup = exercise.supersetGroupId
                val prevGroup = state.exercises.getOrNull(index - 1)?.supersetGroupId
                val nextGroup = state.exercises.getOrNull(index + 1)?.supersetGroupId
                
                val isSupersetStart = currentGroup != null && currentGroup != prevGroup
                val isSupersetEnd = currentGroup != null && currentGroup != nextGroup
                
                ReorderableItem(
                    state = reorderState,
                    key = exercise.id
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                    
                    DraggableExerciseCard(
                        exercise = exercise,
                        index = index + 1,
                        isSelected = exercise.id in state.selectedIds,
                        isSelectionMode = state.isSelectionMode,
                        supersetState = when {
                            currentGroup == null -> SupersetState.None
                            isSupersetStart && isSupersetEnd -> SupersetState.Single
                            isSupersetStart -> SupersetState.Top
                            isSupersetEnd -> SupersetState.Bottom
                            else -> SupersetState.Middle
                        },
                        elevation = elevation,
                        weightUnit = weightUnit,
                        kgToDisplay = kgToDisplay,
                        onToggleSelection = {
                            val newIds = if (exercise.id in state.selectedIds) {
                                state.selectedIds - exercise.id
                            } else {
                                state.selectedIds + exercise.id
                            }
                            state = state.copy(
                                selectedIds = newIds,
                                isSelectionMode = newIds.isNotEmpty()
                            )
                        },
                        onEdit = {
                            exerciseToConfig = exercise
                            isNewExercise = false
                            editingIndex = index
                        },
                        dragModifier = Modifier.draggableHandle(
                            interactionSource = remember { MutableInteractionSource() }
                        )
                    )
                }
            }
            
            if (state.exercises.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                        Text("Tap + to add your first exercise", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // Exercise Picker
    if (showExercisePicker) {
        ExercisePickerDialog(
            showDialog = true,
            onDismiss = { showExercisePicker = false },
            onExerciseSelected = { selectedExercise ->
                // Create a new RoutineExercise with defaults, then show config sheet
                val newEx = RoutineExercise(
                    id = generateUUID(),
                    exercise = selectedExercise,
                    cableConfig = selectedExercise.resolveDefaultCableConfig(),
                    orderIndex = state.exercises.size,
                    weightPerCableKg = 5f // Default - will be configured in bottom sheet
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

    // Full Exercise Configuration Bottom Sheet (for both new and edit)
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
                    // Adding new exercise
                    updateExercises(state.exercises + configuredExercise)
                } else {
                    // Editing existing exercise
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
}

enum class SupersetState { None, Top, Middle, Bottom, Single }

@Composable
fun DraggableExerciseCard(
    exercise: RoutineExercise,
    index: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    supersetState: SupersetState,
    elevation: androidx.compose.ui.unit.Dp,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onToggleSelection: () -> Unit,
    onEdit: () -> Unit,
    dragModifier: Modifier
) {
    val isSuperset = supersetState != SupersetState.None
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min) // Essential for fillMaxHeight child
            .padding(horizontal = 16.dp, vertical = if (isSuperset) 0.dp else 4.dp)
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(
                onClick = { 
                    if (isSelectionMode) onToggleSelection() else onEdit() 
                },
                onLongClick = {
                    if (!isSelectionMode) onToggleSelection()
                }
            )
    ) {
        // 1. Left Rail (Superset / Selection)
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                // Drag Handle
                Icon(
                    Icons.Default.DragHandle, 
                    contentDescription = "Drag",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center).then(dragModifier)
                )
            }
            
            // Superset Line
            if (isSuperset && !isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .align(Alignment.CenterEnd)
                        .padding(vertical = if(supersetState == SupersetState.Top) 4.dp else 0.dp)
                        .background(
                            MaterialTheme.colorScheme.tertiary,
                            shape = when(supersetState) {
                                SupersetState.Top -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                SupersetState.Bottom -> RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                                else -> androidx.compose.ui.graphics.RectangleShape
                            }
                        )
                )
            }
        }

        // 2. Card Content
        Card(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                               else MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = if (isSuperset) {
                when(supersetState) {
                    SupersetState.Top -> RoundedCornerShape(topEnd = 12.dp, topStart = 4.dp)
                    SupersetState.Bottom -> RoundedCornerShape(bottomEnd = 12.dp, bottomStart = 4.dp)
                    SupersetState.Middle -> RoundedCornerShape(4.dp)
                    else -> RoundedCornerShape(12.dp)
                }
            } else RoundedCornerShape(12.dp)
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
                    Text(
                        "${exercise.sets} sets x ${exercise.reps} reps @ ${kgToDisplay(exercise.weightPerCableKg, weightUnit).toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Kebab Menu Removed - Interactions moved to card tap/long-press
            }
        }
    }
}

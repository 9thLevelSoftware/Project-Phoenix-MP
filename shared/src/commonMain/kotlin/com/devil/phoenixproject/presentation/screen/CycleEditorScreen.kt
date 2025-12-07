package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.components.RoutinePickerDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// UI State
data class CycleEditorState(
    val cycleName: String = "",
    val description: String = "",
    val days: List<CycleDay> = emptyList(),
    val showRoutinePickerForIndex: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String, // "new" or ID
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine> // Pass routines for the picker
) {
    val repository: TrainingCycleRepository = koinInject()
    val scope = rememberCoroutineScope()
    
    // State
    var state by remember { mutableStateOf(CycleEditorState()) }
    var hasInitialized by remember { mutableStateOf(false) }

    // Load Data
    LaunchedEffect(cycleId) {
        if (!hasInitialized) {
            if (cycleId != "new") {
                val cycle = repository.getCycleById(cycleId)
                if (cycle != null) {
                    state = state.copy(cycleName = cycle.name, days = cycle.days, description = cycle.description ?: "")
                }
            } else {
                // Start with a standard 3-day template
                state = state.copy(
                    cycleName = "New Cycle",
                    days = listOf(
                        CycleDay.create(generateUUID(), "temp", 1, "Workout A"),
                        CycleDay.create(generateUUID(), "temp", 2, "Workout B"),
                        CycleDay.restDay(generateUUID(), "temp", 3)
                    )
                )
            }
            hasInitialized = true
        }
    }

    val lazyListState = rememberLazyListState()
    
    // Drag State
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val list = state.days.toMutableList()
        val moved = list.removeAt(from.index)
        list.add(to.index, moved)
        // Renumber days immediately for UI consistency
        val renumbered = list.mapIndexed { i, day -> day.copy(dayNumber = i + 1) }
        state = state.copy(days = renumbered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        TextField(
                            value = state.cycleName,
                            onValueChange = { state = state.copy(cycleName = it) },
                            placeholder = { Text("Cycle Name") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            singleLine = true
                        )
                        Text(
                            "${state.days.size} Day Rotation", 
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val newCycle = TrainingCycle.create(
                                id = if (cycleId == "new") generateUUID() else cycleId,
                                name = state.cycleName.ifBlank { "Unnamed Cycle" },
                                description = state.description,
                                days = state.days,
                                isActive = false // Don't activate immediately
                            )
                            repository.saveCycle(newCycle)
                            navController.popBackStack()
                        }
                    }) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            // Quick Add Bar
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            val newDay = CycleDay.create(
                                cycleId = cycleId,
                                dayNumber = state.days.size + 1,
                                name = "Workout ${state.days.size + 1}"
                            )
                            state = state.copy(days = state.days + newDay)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Workout")
                    }
                    
                    OutlinedButton(
                        onClick = {
                            val newRest = CycleDay.restDay(
                                cycleId = cycleId,
                                dayNumber = state.days.size + 1
                            )
                            state = state.copy(days = state.days + newRest)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Hotel, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Rest")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            itemsIndexed(state.days, key = { _, day -> day.id }) { index, day ->
                ReorderableItem(reorderState, key = day.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                    
                    val dayRoutine = day.routineId?.let { id -> routines.find { it.id == id } }
                    
                    CycleDayCard(
                        day = day,
                        routineName = dayRoutine?.name,
                        elevation = elevation,
                        onDelete = {
                            val newDays = state.days.toMutableList().apply { removeAt(index) }
                                .mapIndexed { i, d -> d.copy(dayNumber = i + 1) }
                            state = state.copy(days = newDays)
                        },
                        onEdit = {
                            // If rest day, simple toggle or rename. If workout, open picker.
                            if (!day.isRestDay) {
                                state = state.copy(showRoutinePickerForIndex = index)
                            }
                        },
                        onToggleType = {
                            val updated = if (day.isRestDay) {
                                // Convert to Workout
                                day.copy(isRestDay = false, name = "Workout ${day.dayNumber}")
                            } else {
                                // Convert to Rest
                                day.copy(isRestDay = true, name = "Rest Day", routineId = null)
                            }
                            val newDays = state.days.toMutableList().apply { set(index, updated) }
                            state = state.copy(days = newDays)
                        },
                        dragModifier = Modifier.draggableHandle(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        )
                    )
                }
            }
        }
    }

    // Routine Picker
    if (state.showRoutinePickerForIndex != null) {
        val index = state.showRoutinePickerForIndex!!
        RoutinePickerDialog(
            routines = routines,
            onSelectRoutine = { routine ->
                val updatedDay = state.days[index].copy(
                    routineId = routine.id,
                    name = routine.name // Auto-name the day after the routine
                )
                val newDays = state.days.toMutableList().apply { set(index, updatedDay) }
                state = state.copy(days = newDays, showRoutinePickerForIndex = null)
            },
            onCreateRoutine = {
                state = state.copy(showRoutinePickerForIndex = null)
                navController.navigate(com.devil.phoenixproject.presentation.navigation.NavigationRoutes.RoutineEditor.createRoute("new"))
            },
            onDismiss = { state = state.copy(showRoutinePickerForIndex = null) }
        )
    }
}

@Composable
fun CycleDayCard(
    day: CycleDay,
    routineName: String?,
    elevation: androidx.compose.ui.unit.Dp,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggleType: () -> Unit,
    dragModifier: Modifier
) {
    val containerColor = if (day.isRestDay) 
        MaterialTheme.colorScheme.surfaceContainerHigh 
    else 
        MaterialTheme.colorScheme.surfaceContainer
        
    val iconColor = if (day.isRestDay) 
        MaterialTheme.colorScheme.tertiary 
    else 
        MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth().shadow(elevation, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Drag Handle & Number
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.width(40.dp).then(dragModifier)
            ) {
                Icon(
                    Icons.Default.DragHandle, 
                    contentDescription = "Reorder",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${day.dayNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 2. Content
            Column(
                modifier = Modifier.weight(1f).clickable { onEdit() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (day.isRestDay) "Rest Day" else (routineName ?: "Select Routine"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (day.isRestDay) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (routineName == null && !day.isRestDay) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                if (!day.isRestDay && routineName != null) {
                    Text(
                        "Tap to change routine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. Actions
            IconButton(onClick = onToggleType) {
                Icon(
                    if (day.isRestDay) Icons.Default.Hotel else Icons.Default.FitnessCenter,
                    contentDescription = "Toggle Type",
                    tint = iconColor
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Close, 
                    contentDescription = "Remove Day",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

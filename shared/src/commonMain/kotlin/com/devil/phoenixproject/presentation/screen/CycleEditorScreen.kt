package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.presentation.components.CycleDayConfigSheet
import com.devil.phoenixproject.presentation.components.RoutinePickerDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

// Drag state for routine-to-day drag-and-drop
data class RoutineDragState(
    val routineId: String,
    val routineName: String,
    val startPosition: Offset = Offset.Zero,
    val currentOffset: Offset = Offset.Zero
)

// UI State
data class CycleEditorState(
    val cycleName: String = "",
    val description: String = "",
    val days: List<CycleDay> = emptyList(),
    val showRoutinePickerForIndex: Int? = null,
    val configDayIndex: Int? = null, // For CycleDayConfigSheet
    val selectedRoutineId: String? = null, // For click-to-assign flow
    val dragState: RoutineDragState? = null // For drag-and-drop
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String, // "new" or ID
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>, // Pass routines for the picker
    initialDayCount: Int? = null // Optional day count from DayCountPickerScreen
) {
    val repository: TrainingCycleRepository = koinInject()
    val scope = rememberCoroutineScope()

    // State
    var state by remember { mutableStateOf(CycleEditorState()) }
    var hasInitialized by remember { mutableStateOf(false) }

    // Load Data
    LaunchedEffect(cycleId, initialDayCount) {
        if (!hasInitialized) {
            if (cycleId != "new") {
                val cycle = repository.getCycleById(cycleId)
                if (cycle != null) {
                    state = state.copy(cycleName = cycle.name, days = cycle.days, description = cycle.description ?: "")
                }
            } else {
                // Use initialDayCount if provided, otherwise default to 3-day template
                val dayCount = initialDayCount ?: 3
                val days = (1..dayCount).map { dayNum ->
                    CycleDay.create(generateUUID(), "temp", dayNum, "Day $dayNum")
                }
                state = state.copy(
                    cycleName = "New Cycle",
                    days = days
                )
            }
            hasInitialized = true
        }
    }

    val lazyListState = rememberLazyListState()

    // Track day slot positions for drag-drop hit testing
    val daySlotBounds = remember { mutableStateMapOf<Int, Pair<Offset, Offset>>() } // index -> (topLeft, bottomRight)

    // Find which day slot is under the current drag position
    val hoveredDayIndex: Int? = remember(state.dragState, daySlotBounds) {
        val drag = state.dragState ?: return@remember null
        val dragPos = drag.startPosition + drag.currentOffset
        daySlotBounds.entries.firstOrNull { (_, bounds) ->
            val (topLeft, bottomRight) = bounds
            dragPos.x >= topLeft.x && dragPos.x <= bottomRight.x &&
                dragPos.y >= topLeft.y && dragPos.y <= bottomRight.y
        }?.key
    }

    // Drag State for reordering days
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
                            val actualCycleId = if (cycleId == "new") generateUUID() else cycleId
                            val newCycle = TrainingCycle.create(
                                id = actualCycleId,
                                name = state.cycleName.ifBlank { "Unnamed Cycle" },
                                description = state.description,
                                days = state.days,
                                isActive = false // Don't activate immediately
                            )
                            repository.saveCycle(newCycle)
                            navController.navigate(NavigationRoutes.CycleReview.createRoute(actualCycleId))
                        }
                    }) {
                        Text("Review", fontWeight = FontWeight.Bold)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
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
        // Two-panel layout: 30% routines palette, 70% day slots
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Panel: Routine Palette (~30%)
            RoutinePalette(
                routines = routines,
                selectedRoutineId = state.selectedRoutineId,
                isDragging = state.dragState != null,
                onRoutineSelected = { routineId ->
                    state = state.copy(
                        selectedRoutineId = if (state.selectedRoutineId == routineId) null else routineId
                    )
                },
                onDragStart = { routine, startPos ->
                    state = state.copy(
                        dragState = RoutineDragState(
                            routineId = routine.id,
                            routineName = routine.name,
                            startPosition = startPos
                        ),
                        selectedRoutineId = null // Clear click-selection when dragging
                    )
                },
                onDrag = { delta ->
                    state.dragState?.let { drag ->
                        state = state.copy(
                            dragState = drag.copy(currentOffset = drag.currentOffset + delta)
                        )
                    }
                },
                onDragEnd = {
                    // Check if we dropped on a valid day slot
                    val draggedRoutineId = state.dragState?.routineId
                    if (draggedRoutineId != null && hoveredDayIndex != null) {
                        val targetDay = state.days.getOrNull(hoveredDayIndex)
                        if (targetDay != null && !targetDay.isRestDay) {
                            val routine = routines.find { it.id == draggedRoutineId }
                            if (routine != null) {
                                val updatedDay = targetDay.copy(
                                    routineId = routine.id,
                                    name = routine.name
                                )
                                val newDays = state.days.toMutableList().apply {
                                    set(hoveredDayIndex, updatedDay)
                                }
                                state = state.copy(days = newDays)
                            }
                        }
                    }
                    state = state.copy(dragState = null)
                },
                onCreateRoutine = {
                    navController.navigate(NavigationRoutes.RoutineEditor.createRoute("new"))
                },
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
            )

            // Right Panel: Day Slots (~70%)
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
            ) {
                // Header
                item {
                    Text(
                        text = "YOUR CYCLE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                itemsIndexed(state.days, key = { _, day -> day.id }) { index, day ->
                    ReorderableItem(reorderState, key = day.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)

                        val dayRoutine = day.routineId?.let { id -> routines.find { it.id == id } }

                        // Track this day slot's position for drag hit testing
                        val isHoveredByDrag = hoveredDayIndex == index && state.dragState != null

                        CycleDaySlotCard(
                            day = day,
                            routine = dayRoutine,
                            elevation = elevation,
                            isDropTarget = (state.selectedRoutineId != null || isHoveredByDrag) && !day.isRestDay,
                            isHoveredByDrag = isHoveredByDrag,
                            onPositioned = { topLeft, bottomRight ->
                                daySlotBounds[index] = topLeft to bottomRight
                            },
                            onCardTap = {
                                // If a routine is selected in palette, assign it to this day
                                if (state.selectedRoutineId != null && !day.isRestDay) {
                                    val selectedRoutine = routines.find { it.id == state.selectedRoutineId }
                                    if (selectedRoutine != null) {
                                        val updatedDay = day.copy(
                                            routineId = selectedRoutine.id,
                                            name = selectedRoutine.name
                                        )
                                        val newDays = state.days.toMutableList().apply { set(index, updatedDay) }
                                        state = state.copy(days = newDays, selectedRoutineId = null)
                                    }
                                } else if (!day.isRestDay && day.routineId != null) {
                                    // Tap on assigned day opens config sheet
                                    state = state.copy(configDayIndex = index)
                                } else if (!day.isRestDay && day.routineId == null) {
                                    // Tap on unassigned day opens routine picker
                                    state = state.copy(showRoutinePickerForIndex = index)
                                }
                            },
                            onClearAssignment = {
                                val updatedDay = day.copy(routineId = null, name = "Day ${day.dayNumber}")
                                val newDays = state.days.toMutableList().apply { set(index, updatedDay) }
                                state = state.copy(days = newDays)
                            },
                            onConfigureTap = {
                                if (!day.isRestDay && day.routineId != null) {
                                    state = state.copy(configDayIndex = index)
                                }
                            },
                            onDelete = {
                                val newDays = state.days.toMutableList().apply { removeAt(index) }
                                    .mapIndexed { i, d -> d.copy(dayNumber = i + 1) }
                                state = state.copy(days = newDays)
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
    }

    // Routine Picker Dialog
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
                navController.navigate(NavigationRoutes.RoutineEditor.createRoute("new"))
            },
            onDismiss = { state = state.copy(showRoutinePickerForIndex = null) }
        )
    }

    // CycleDayConfigSheet
    if (state.configDayIndex != null) {
        val index = state.configDayIndex!!
        val day = state.days[index]
        val routine = day.routineId?.let { id -> routines.find { it.id == id } }

        CycleDayConfigSheet(
            day = day,
            routine = routine,
            onDismiss = { state = state.copy(configDayIndex = null) },
            onApply = { updatedDay ->
                val newDays = state.days.toMutableList().apply { set(index, updatedDay) }
                state = state.copy(days = newDays, configDayIndex = null)
            }
        )
    }

    // Floating drag preview
    state.dragState?.let { drag ->
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset {
                    val pos = drag.startPosition + drag.currentOffset
                    IntOffset(pos.x.roundToInt() - with(density) { 60.dp.roundToPx() }, pos.y.roundToInt() - with(density) { 20.dp.roundToPx() })
                }
                .zIndex(100f)
        ) {
            DragPreviewCard(routineName = drag.routineName)
        }
    }
}

/**
 * Left panel: Scrollable palette of available routines.
 * Tap to select a routine, then tap a day slot to assign it.
 * Or drag a routine onto a day slot to assign it directly.
 */
@Composable
private fun RoutinePalette(
    routines: List<Routine>,
    selectedRoutineId: String?,
    isDragging: Boolean,
    onRoutineSelected: (String) -> Unit,
    onDragStart: (Routine, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "ROUTINES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(routines, key = { it.id }) { routine ->
                val isSelected = selectedRoutineId == routine.id

                RoutinePaletteCard(
                    routine = routine,
                    isSelected = isSelected,
                    isDragging = isDragging,
                    onClick = { onRoutineSelected(routine.id) },
                    onDragStart = { startPos -> onDragStart(routine, startPos) },
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            }

            if (routines.isEmpty()) {
                item {
                    Text(
                        text = "No routines yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = onCreateRoutine,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Hint when routine is selected
        if (selectedRoutineId != null) {
            val selectedRoutine = routines.find { it.id == selectedRoutineId }
            if (selectedRoutine != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = "Tap a day to assign \"${selectedRoutine.name}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutinePaletteCard(
    routine: Routine,
    isSelected: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    // Track position for drag start
    var cardPosition by remember { mutableStateOf(Offset.Zero) }
    var isDraggingThis by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Make card semi-transparent while being dragged
                alpha = if (isDraggingThis) 0.5f else 1f
            }
            .onGloballyPositioned { coords ->
                cardPosition = coords.positionInRoot()
            }
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .pointerInput(routine.id) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDraggingThis = true
                        onDragStart(cardPosition + offset)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = {
                        isDraggingThis = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDraggingThis = false
                        onDragEnd()
                    }
                )
            }
            .clickable(enabled = !isDragging, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.DragIndicator,
                    contentDescription = "Drag to assign",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = routine.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "${routine.exercises.size} exercises",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp)
            )
        }
    }
}

/**
 * Day slot card with modifier badges and overflow menu.
 */
@Composable
fun CycleDaySlotCard(
    day: CycleDay,
    routine: Routine?,
    elevation: androidx.compose.ui.unit.Dp,
    isDropTarget: Boolean,
    isHoveredByDrag: Boolean = false,
    onPositioned: ((Offset, Offset) -> Unit)? = null,
    onCardTap: () -> Unit,
    onClearAssignment: () -> Unit,
    onConfigureTap: () -> Unit,
    onDelete: () -> Unit,
    onToggleType: () -> Unit,
    dragModifier: Modifier
) {
    val containerColor = when {
        isHoveredByDrag -> MaterialTheme.colorScheme.primaryContainer
        isDropTarget -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        day.isRestDay -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    val borderColor = when {
        isHoveredByDrag -> MaterialTheme.colorScheme.primary
        isDropTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val iconColor = if (day.isRestDay)
        MaterialTheme.colorScheme.tertiary
    else
        MaterialTheme.colorScheme.primary

    // Dropdown menu state
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                onPositioned?.invoke(
                    coords.positionInRoot(),
                    coords.positionInRoot() + Offset(coords.size.width.toFloat(), coords.size.height.toFloat())
                )
            }
            .shadow(elevation, RoundedCornerShape(16.dp))
            .border(
                width = if (isHoveredByDrag || isDropTarget) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onCardTap),
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
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (day.isRestDay) {
                        Icon(
                            Icons.Default.Hotel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Rest",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = routine?.name ?: "Select Routine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (routine == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (routine == null) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Modifier badges row
                if (!day.isRestDay && routine != null) {
                    val badges = buildBadgeList(day)
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            badges.forEach { badge ->
                                ModifierBadge(text = badge)
                            }
                        }
                    } else {
                        Text(
                            "Tap to configure",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Overflow Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!day.isRestDay && day.routineId != null) {
                        DropdownMenuItem(
                            text = { Text("Configure") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                showMenu = false
                                onConfigureTap()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Routine") },
                            leadingIcon = { Icon(Icons.Default.Clear, null) },
                            onClick = {
                                showMenu = false
                                onClearAssignment()
                            }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(if (day.isRestDay) "Convert to Workout" else "Convert to Rest") },
                        leadingIcon = {
                            Icon(
                                if (day.isRestDay) Icons.Default.FitnessCenter else Icons.Default.Hotel,
                                null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleType()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Day") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Build list of badge strings from day modifiers.
 */
private fun buildBadgeList(day: CycleDay): List<String> {
    val badges = mutableListOf<String>()

    day.echoLevel?.let { badges.add(it.displayName) }
    day.weightProgressionPercent?.let { pct ->
        val sign = if (pct >= 0) "+" else ""
        badges.add("${sign}${pct.roundToInt()}%")
    }
    day.eccentricLoadPercent?.let { pct ->
        if (pct != 100) {
            badges.add("Ecc ${pct}%")
        }
    }
    day.repModifier?.let { mod ->
        if (mod != 0) {
            val sign = if (mod > 0) "+" else ""
            badges.add("${sign}${mod} reps")
        }
    }
    day.restTimeOverrideSeconds?.let { sec ->
        if (sec != 60) {
            badges.add("${sec}s rest")
        }
    }

    return badges
}

@Composable
private fun ModifierBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// Keep the old CycleDayCard for backward compatibility if needed
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

/**
 * Floating preview card shown during drag operation.
 */
@Composable
private fun DragPreviewCard(
    routineName: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.width(120.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = routineName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

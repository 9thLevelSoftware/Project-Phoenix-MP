package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.migration.CycleTemplates
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.presentation.components.DayStrip
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import org.koin.compose.koinInject
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

/**
 * State machine for cycle creation flow
 */
sealed class CycleCreationState {
    object Idle : CycleCreationState()
    data class TemplateSelected(val template: CycleTemplate) : CycleCreationState()
    data class OneRepMaxInput(val template: CycleTemplate) : CycleCreationState()
    data class ModeConfirmation(
        val template: CycleTemplate,
        val oneRepMaxValues: Map<String, Float>
    ) : CycleCreationState()
    data class Creating(val template: CycleTemplate) : CycleCreationState()
}

/**
 * Training Cycles screen - view and manage rolling workout schedules.
 * Replaces the calendar-bound WeeklyPrograms with flexible Day 1, Day 2, etc.
 */
@Composable
fun TrainingCyclesScreen(
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode
) {
    val cycleRepository: TrainingCycleRepository = koinInject()
    val exerciseRepository: ExerciseRepository = koinInject()
    val templateConverter: TemplateConverter = koinInject()
    val scope = rememberCoroutineScope()

    // Collect cycles from repository
    val cycles by cycleRepository.getAllCycles().collectAsState(initial = emptyList())
    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    val routines by viewModel.routines.collectAsState()

    // State
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleProgress by remember { mutableStateOf<Map<String, CycleProgress>>(emptyMap()) }
    var creationState by remember { mutableStateOf<CycleCreationState>(CycleCreationState.Idle) }
    var showWarningDialog by remember { mutableStateOf<List<String>?>(null) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    // Selected day for viewing different days in the active cycle
    var selectedDayNumber by remember { mutableStateOf<Int?>(null) }

    // When active cycle changes, reset selection to current day
    LaunchedEffect(activeCycle, cycleProgress) {
        selectedDayNumber = cycleProgress[activeCycle?.id]?.currentDayNumber
    }

    // Load progress for all cycles, with auto-advance check for active cycle
    // Auto-advance marks day as missed after 24+ hours and advances to next day
    LaunchedEffect(cycles, activeCycle) {
        val progressMap = mutableMapOf<String, CycleProgress>()
        val activeId = activeCycle?.id
        cycles.forEach { cycle ->
            val progress = if (cycle.id == activeId) {
                // Check auto-advance for active cycle
                cycleRepository.checkAndAutoAdvance(cycle.id)
            } else {
                cycleRepository.getCycleProgress(cycle.id)
            }
            progress?.let { progressMap[cycle.id] = it }
        }
        cycleProgress = progressMap
    }

    // Set title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Training Cycles")
    }

    Logger.d { "TrainingCyclesScreen: ${cycles.size} cycles loaded" }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        if (cycles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                EmptyState(
                    icon = Icons.Default.Loop,
                    title = "No Training Cycles Yet",
                    message = "Create a rolling workout schedule that adapts to your life, not the calendar",
                    actionText = "Create Your First Cycle",
                    onAction = { navController.navigate(NavigationRoutes.DayCountPicker.route) }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Active Cycle Section
                if (activeCycle != null) {
                    item {
                        ActiveCycleCard(
                            cycle = activeCycle!!,
                            progress = cycleProgress[activeCycle!!.id],
                            routines = routines,
                            selectedDayNumber = selectedDayNumber,
                            onDaySelected = { dayNumber ->
                                selectedDayNumber = dayNumber
                            },
                            onStartWorkout = { routineId, cycleId, dayNumber ->
                                routineId?.let {
                                    viewModel.ensureConnection(
                                        onConnected = {
                                            viewModel.loadRoutineFromCycle(it, cycleId, dayNumber)
                                            viewModel.startWorkout()
                                            navController.navigate(NavigationRoutes.ActiveWorkout.route)
                                        },
                                        onFailed = { /* Error shown via StateFlow */ }
                                    )
                                }
                            },
                            onAdvanceDay = {
                                scope.launch {
                                    cycleRepository.advanceToNextDay(activeCycle!!.id)
                                }
                            }
                        )
                    }
                }

                // All Cycles Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "All Cycles",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(onClick = { showTemplateDialog = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Create Cycle")
                        }
                    }
                }

                // Cycle List
                items(cycles, key = { it.id }) { cycle ->
                    CycleListItem(
                        cycle = cycle,
                        progress = cycleProgress[cycle.id],
                        routines = routines,
                        isActive = cycle.id == activeCycle?.id,
                        onActivate = {
                            scope.launch {
                                cycleRepository.setActiveCycle(cycle.id)
                            }
                        },
                        onEdit = {
                            navController.navigate(NavigationRoutes.CycleEditor.createRoute(cycle.id))
                        },
                        onDelete = {
                            showDeleteConfirmDialog = cycle
                        }
                    )
                }
            }
        }

        // FAB for creating new cycle
        FloatingActionButton(
            onClick = { navController.navigate(NavigationRoutes.DayCountPicker.route) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Cycle")
        }
    }

    // Template Selection Dialog
    if (showTemplateDialog) {
        TemplateSelectionDialog(
            onDismiss = {
                showTemplateDialog = false
                creationState = CycleCreationState.Idle
            },
            onSelectTemplate = { template ->
                showTemplateDialog = false
                // Check if template needs 1RM input (5/3/1 template)
                val needsOneRepMax = template.name.contains("5/3/1", ignoreCase = true) ||
                    template.days.any { day ->
                        day.routine?.exercises?.any { it.isPercentageBased } == true
                    }

                if (needsOneRepMax) {
                    creationState = CycleCreationState.OneRepMaxInput(template)
                } else {
                    creationState = CycleCreationState.ModeConfirmation(template, emptyMap())
                }
            },
            onCreateBlank = {
                showTemplateDialog = false
                creationState = CycleCreationState.Idle
                navController.navigate(NavigationRoutes.CycleEditor.createRoute("new"))
            }
        )
    }

    // OneRepMaxInputScreen
    when (val state = creationState) {
        is CycleCreationState.OneRepMaxInput -> {
            // Extract main lift names from template exercises
            val mainLiftNames = state.template.days
                .flatMap { it.routine?.exercises ?: emptyList() }
                .filter { it.isPercentageBased }
                .map { it.exerciseName }
                .distinct()

            // Load existing 1RM values
            val existingOneRepMaxValues = remember { mutableStateMapOf<String, Float>() }
            LaunchedEffect(mainLiftNames) {
                mainLiftNames.forEach { exerciseName ->
                    exerciseRepository.findByName(exerciseName)?.let { exercise ->
                        exercise.oneRepMaxKg?.let { oneRepMax ->
                            existingOneRepMaxValues[exerciseName] = oneRepMax
                        }
                    }
                }
            }

            OneRepMaxInputScreen(
                mainLiftNames = mainLiftNames,
                existingOneRepMaxValues = existingOneRepMaxValues,
                onConfirm = { oneRepMaxValues ->
                    creationState = CycleCreationState.ModeConfirmation(state.template, oneRepMaxValues)
                },
                onCancel = {
                    creationState = CycleCreationState.Idle
                }
            )
        }
        is CycleCreationState.ModeConfirmation -> {
            ModeConfirmationScreen(
                template = state.template,
                onConfirm = { modeSelections ->
                    creationState = CycleCreationState.Creating(state.template)
                    scope.launch {
                        try {
                            // 1. Update 1RM values in exercise repository if provided
                            state.oneRepMaxValues.forEach { (exerciseName, oneRepMax) ->
                                if (oneRepMax > 0f) {
                                    exerciseRepository.findByName(exerciseName)?.let { exercise ->
                                        exerciseRepository.updateOneRepMax(exercise.id ?: "", oneRepMax)
                                    }
                                }
                            }

                            // 2. Convert template using TemplateConverter
                            val conversionResult = templateConverter.convert(state.template)

                            // 3. Save routines FIRST (CycleDay has FK to Routine)
                            conversionResult.routines.forEach { routine ->
                                viewModel.saveRoutine(routine)
                            }

                            // 4. Save cycle via TrainingCycleRepository (after routines exist)
                            cycleRepository.saveCycle(conversionResult.cycle)

                            // 5. Show warnings if any exercises weren't found
                            if (conversionResult.warnings.isNotEmpty()) {
                                Logger.w { "Some exercises not found: ${conversionResult.warnings}" }
                                showWarningDialog = conversionResult.warnings
                            }

                            // 6. Navigate back or reset state
                            creationState = CycleCreationState.Idle
                            Logger.d { "Successfully created cycle: ${state.template.name}" }
                        } catch (e: Exception) {
                            Logger.e(e) { "Failed to create cycle from template" }
                            creationState = CycleCreationState.Idle
                            showErrorDialog = e.message ?: "Failed to create training cycle"
                        }
                    }
                },
                onCancel = {
                    creationState = CycleCreationState.Idle
                }
            )
        }
        else -> {
            // Idle or Creating state - don't show anything
        }
    }

    // Delete Confirmation Dialog
    showDeleteConfirmDialog?.let { cycle ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("Delete Cycle?") },
            text = { Text("Are you sure you want to delete \"${cycle.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            cycleRepository.deleteCycle(cycle.id)
                            showDeleteConfirmDialog = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Warning Dialog - shows when some exercises weren't found
    showWarningDialog?.let { warnings ->
        AlertDialog(
            onDismissRequest = { showWarningDialog = null },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            title = { Text("Exercises Not Found") },
            text = {
                Column {
                    Text(
                        "The cycle was created, but the following exercises weren't found in your library:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    warnings.forEach { warning ->
                        Text(
                            "• $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "You may need to add these exercises or update the routines manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showWarningDialog = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Error Dialog - shows when cycle creation fails
    showErrorDialog?.let { errorMessage ->
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            icon = {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Error") },
            text = {
                Text(
                    "Failed to create training cycle: $errorMessage",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * Active cycle card with "Up Next" style display and DayStrip for browsing days.
 */
@Composable
private fun ActiveCycleCard(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    selectedDayNumber: Int?,
    onDaySelected: (Int) -> Unit,
    onStartWorkout: (routineId: String?, cycleId: String, dayNumber: Int) -> Unit,
    onAdvanceDay: () -> Unit
) {
    val currentDay = progress?.currentDayNumber ?: 1
    // Use selected day for preview, or default to current day
    val displayedDayNumber = selectedDayNumber ?: currentDay
    val isViewingCurrentDay = displayedDayNumber == currentDay

    val displayedCycleDay = cycle.days.find { it.dayNumber == displayedDayNumber }
    val routine = displayedCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }

    // Create a default progress if none exists
    val effectiveProgress = progress ?: CycleProgress.create(
        cycleId = cycle.id,
        currentDayNumber = 1
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isViewingCurrentDay) Icons.Default.PlayCircle else Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isViewingCurrentDay) "UP NEXT" else "PREVIEWING",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Day $displayedDayNumber of ${cycle.days.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Day name and routine
            Text(
                displayedCycleDay?.name ?: "Day $displayedDayNumber",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (displayedCycleDay?.isRestDay == true) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SelfImprovement,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Rest Day - Take it easy!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            } else if (routine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    routine.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Text(
                    routine.exercises.joinToString(", ") { it.exercise.name }.take(50) +
                        if (routine.exercises.size > 3) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // DayStrip - replaces progress dots with interactive day chips
            DayStrip(
                days = cycle.days,
                progress = effectiveProgress,
                currentSelection = displayedDayNumber,
                onDaySelected = onDaySelected
            )

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (displayedCycleDay?.isRestDay == true) {
                    if (isViewingCurrentDay) {
                        OutlinedButton(
                            onClick = onAdvanceDay,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Skip Rest Day")
                        }
                    } else {
                        // Viewing a different rest day - show "Go to today" button
                        OutlinedButton(
                            onClick = { onDaySelected(currentDay) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Today, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Go to Today")
                        }
                    }
                } else {
                    if (isViewingCurrentDay) {
                        Button(
                            onClick = { onStartWorkout(displayedCycleDay?.routineId, cycle.id, displayedDayNumber) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            enabled = displayedCycleDay?.routineId != null
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Workout")
                        }
                    } else {
                        // Viewing a different day - show "Go to today" button
                        OutlinedButton(
                            onClick = { onDaySelected(currentDay) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Today, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Go to Today")
                        }
                    }
                }
            }
        }
    }
}

/**
 * List item for a training cycle.
 */
@Composable
private fun CycleListItem(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cycle.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "ACTIVE",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        "${cycle.days.size} days" +
                            (progress?.let { " - Day ${it.currentDayNumber}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Days list
                    cycle.days.forEach { day ->
                        val routine = day.routineId?.let { routineId ->
                            routines.find { it.id == routineId }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (day.isRestDay)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        day.dayNumber.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        day.name ?: "Day ${day.dayNumber}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (routine != null) {
                                        Text(
                                            routine.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else if (day.isRestDay) {
                                        Text(
                                            "Rest",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isActive) {
                            OutlinedButton(
                                onClick = onActivate,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Set Active")
                            }
                        }
                        OutlinedButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Edit")
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for selecting a cycle template or creating a blank cycle.
 */
@Composable
private fun TemplateSelectionDialog(
    onDismiss: () -> Unit,
    onSelectTemplate: (CycleTemplate) -> Unit,
    onCreateBlank: () -> Unit
) {
    val templates = remember {
        listOf(
            CycleTemplates.threeDay(),
            CycleTemplates.pushPullLegs(),
            CycleTemplates.upperLower(),
            CycleTemplates.fiveThreeOne()
        )
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "Create Training Cycle",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Start with a template or create your own:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                templates.forEach { template ->
                    val needsOneRepMax = template.name.contains("5/3/1", ignoreCase = true) ||
                        template.days.any { day ->
                            day.routine?.exercises?.any { it.isPercentageBased } == true
                        }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTemplate(template) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        template.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (needsOneRepMax) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "1RM",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${template.days.size} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCreateBlank() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Create Custom",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Build your own schedule",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

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
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import org.koin.compose.koinInject
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush

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
    val scope = rememberCoroutineScope()

    // Collect cycles from repository
    val cycles by cycleRepository.getAllCycles().collectAsState(initial = emptyList())
    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    val routines by viewModel.routines.collectAsState()

    // State
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleProgress by remember { mutableStateOf<Map<String, CycleProgress>>(emptyMap()) }

    // Load progress for all cycles
    LaunchedEffect(cycles) {
        val progressMap = mutableMapOf<String, CycleProgress>()
        cycles.forEach { cycle ->
            cycleRepository.getCycleProgress(cycle.id)?.let { progress ->
                progressMap[cycle.id] = progress
            }
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
                    onAction = { showTemplateDialog = true }
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
                            onStartWorkout = { routineId ->
                                routineId?.let {
                                    viewModel.loadRoutineById(it)
                                    navController.navigate("active_workout")
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
            onClick = { navController.navigate(NavigationRoutes.CycleEditor.createRoute("new")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Cycle")
        }
    }

    // Template Selection Dialog - Removed for now as we go straight to editor via FAB, 
    // or we can redirect "Create Blank" to editor.
    // Keeping logic if user wants templates later, but for now FAB goes to editor.
    if (showTemplateDialog) {
        TemplateSelectionDialog(
            onDismiss = { showTemplateDialog = false },
            onSelectTemplate = { template ->
                // TODO: Task 11 - Convert CycleTemplate to TrainingCycle using TemplateConverter
                // For now, this is disabled until TemplateConverter is implemented
                showTemplateDialog = false
            },
            onCreateBlank = {
                showTemplateDialog = false
                navController.navigate(NavigationRoutes.CycleEditor.createRoute("new"))
            }
        )
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
}

/**
 * Active cycle card with "Up Next" style display.
 */
@Composable
private fun ActiveCycleCard(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    onStartWorkout: (String?) -> Unit,
    onAdvanceDay: () -> Unit
) {
    val currentDay = progress?.currentDayNumber ?: 1
    val currentCycleDay = cycle.days.find { it.dayNumber == currentDay }
    val routine = currentCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }

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
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "UP NEXT",
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
                        "Day $currentDay of ${cycle.days.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Day name and routine
            Text(
                currentCycleDay?.name ?: "Day $currentDay",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (currentCycleDay?.isRestDay == true) {
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

            // Progress dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                cycle.days.forEach { day ->
                    val isCurrentDay = day.dayNumber == currentDay
                    Box(
                        modifier = Modifier
                            .size(if (isCurrentDay) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrentDay) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
                            )
                    )
                    if (day.dayNumber < cycle.days.size) {
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentCycleDay?.isRestDay == true) {
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
                    Button(
                        onClick = { onStartWorkout(currentCycleDay?.routineId) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = currentCycleDay?.routineId != null
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Workout")
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
    val templates = remember { CycleTemplates.all() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Create Training Cycle",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Start with a template or create your own:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                templates.forEach { template ->
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
                                Text(
                                    template.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${template.days.size} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

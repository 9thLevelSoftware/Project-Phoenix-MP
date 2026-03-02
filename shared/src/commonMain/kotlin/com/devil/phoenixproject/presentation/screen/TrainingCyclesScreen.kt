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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.usecase.TemplateConverter
import com.devil.phoenixproject.presentation.components.DayStrip
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.ResumeRoutineDialog
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import org.koin.compose.koinInject
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.presentation.components.cycle.UnifiedCycleCreationSheet

/**
 * State machine for cycle creation flow
 */
sealed class CycleCreationState {
    object Idle : CycleCreationState()
    data class TemplateSelected(val template: CycleTemplate) : CycleCreationState()
    data class OneRepMaxInput(val template: CycleTemplate) : CycleCreationState()
    data class ModeConfirmation(
        val template: CycleTemplate,
        val oneRepMaxValues: Map<String, Float>,
        val prWeightValues: Map<String, Float> = emptyMap()
    ) : CycleCreationState()
    data class Creating(val template: CycleTemplate) : CycleCreationState()
}

/**
 * Training Cycles screen - view and manage rolling workout schedules.
 * Replaces the calendar-bound WeeklyPrograms with flexible Day 1, Day 2, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingCyclesScreen(
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode
) {
    val cycleRepository: TrainingCycleRepository = koinInject()
    val exerciseRepository: ExerciseRepository = koinInject()
    val workoutRepository: WorkoutRepository = koinInject()
    val templateConverter: TemplateConverter = koinInject()
    val personalRecordRepository: com.devil.phoenixproject.data.repository.PersonalRecordRepository = koinInject()
    val scope = rememberCoroutineScope()

    // User preferences for weight unit
    val weightUnit by viewModel.weightUnit.collectAsState()

    // Collect cycles from repository
    val cycles by cycleRepository.getAllCycles().collectAsState(initial = emptyList())
    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    val routines by viewModel.routines.collectAsState()

    // State
    val creationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreationSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleProgress by remember { mutableStateOf<Map<String, CycleProgress>>(emptyMap()) }
    var creationState by remember { mutableStateOf<CycleCreationState>(CycleCreationState.Idle) }
    var showWarningDialog by remember { mutableStateOf<List<String>?>(null) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    // Snackbar for feedback
    val snackbarHostState = remember { SnackbarHostState() }

    // Cycle day completion feedback
    val completionEvent by viewModel.cycleDayCompletionEvent.collectAsState()
    LaunchedEffect(completionEvent) {
        completionEvent?.let { event ->
            val message = if (event.isRotationComplete) {
                "Cycle complete! Starting rotation ${event.rotationCount + 1}"
            } else {
                val dayLabel = event.dayName ?: "Day ${event.dayNumber}"
                "$dayLabel completed!"
            }
            snackbarHostState.showSnackbar(message)
            viewModel.clearCycleDayCompletionEvent()
        }
    }

    // Selected day for viewing different days in the active cycle
    var selectedDayNumber by remember { mutableStateOf<Int?>(null) }

    // Resume/Restart dialog state (Issue #101)
    var showResumeDialog by remember { mutableStateOf(false) }
    var pendingRoutineId by remember { mutableStateOf<String?>(null) }
    var pendingCycleId by remember { mutableStateOf<String?>(null) }
    var pendingDayNumber by remember { mutableStateOf(0) }

    suspend fun loadProgressMap(
        cycleList: List<TrainingCycle>,
        activeCycleId: String?
    ): Map<String, CycleProgress> {
        val progressMap = mutableMapOf<String, CycleProgress>()
        cycleList.forEach { cycle ->
            val progress = if (cycle.id == activeCycleId) {
                cycleRepository.checkAndAutoAdvance(cycle.id)
            } else {
                cycleRepository.getCycleProgress(cycle.id)
            }
            progress?.let { progressMap[cycle.id] = it }
        }
        return progressMap
    }

    // Keep selection stable while previewing, but reset it when cycle/day context changes.
    val activeCycleSnapshot = activeCycle
    val activeCurrentDay = cycleProgress[activeCycle?.id]?.currentDayNumber
    LaunchedEffect(activeCycleSnapshot?.id, activeCurrentDay) {
        if (activeCycleSnapshot == null || activeCurrentDay == null) {
            selectedDayNumber = null
            return@LaunchedEffect
        }

        val selectionIsValid = activeCycleSnapshot.days.any { it.dayNumber == selectedDayNumber }
        if (!selectionIsValid || selectedDayNumber == null) {
            selectedDayNumber = activeCurrentDay
        }
    }

    // Load and refresh progress while this screen is open.
    // This keeps manual actions responsive and allows date rollover to apply automatically.
    LaunchedEffect(cycles, activeCycle?.id) {
        while (true) {
            cycleProgress = loadProgressMap(cycles, activeCycle?.id)
            delay(60_000L)
        }
    }

    // Repair unassigned workout days (routine deleted/recreated) by matching day name to routine name.
    // Only applies to non-rest days with a missing routineId and a non-generic name.
    LaunchedEffect(cycles, routines) {
        if (routines.isEmpty()) return@LaunchedEffect
        cycles.forEach { cycle ->
            cycle.days
                .filter { day ->
                    !day.isRestDay &&
                        day.routineId == null &&
                        !day.name.isNullOrBlank() &&
                        !day.name!!.trim().matches(Regex("^Day\\s+\\d+$", RegexOption.IGNORE_CASE))
                }
                .forEach { day ->
                    val matches = routines.filter { it.name.equals(day.name, ignoreCase = true) }
                    if (matches.size == 1) {
                        val matchedRoutine = matches.first()
                        Logger.w { "Repairing cycle day ${day.id}: linking routine '${matchedRoutine.name}' (${matchedRoutine.id})" }
                        cycleRepository.updateCycleDay(day.copy(routineId = matchedRoutine.id))
                    }
                }
        }
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
                    onAction = { showCreationSheet = true }
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
                                routineId?.let { rid ->
                                    // Issue #101: Check for resumable progress
                                    if (viewModel.hasResumableProgress(rid)) {
                                        // Show resume dialog
                                        pendingRoutineId = rid
                                        pendingCycleId = cycleId
                                        pendingDayNumber = dayNumber
                                        showResumeDialog = true
                                    } else {
                                        // No progress - start fresh
                                        viewModel.ensureConnection(
                                            onConnected = {
                                                viewModel.loadRoutineFromCycle(rid, cycleId, dayNumber)
                                                viewModel.startWorkout()
                                                navController.navigate(NavigationRoutes.ActiveWorkout.route)
                                            },
                                            onFailed = { /* Error shown via StateFlow */ }
                                        )
                                    }
                                }
                            },
                            onAdvanceDay = {
                                scope.launch {
                                    val activeId = activeCycle!!.id
                                    cycleRepository.advanceToNextDay(activeId)
                                    cycleRepository.getCycleProgress(activeId)?.let { updated ->
                                        cycleProgress = cycleProgress.toMutableMap().apply {
                                            this[activeId] = updated
                                        }
                                        selectedDayNumber = updated.currentDayNumber
                                    }
                                }
                            },
                            onJumpToDay = { dayNumber ->
                                scope.launch {
                                    val activeId = activeCycle!!.id
                                    cycleRepository.jumpToDay(activeId, dayNumber)
                                    cycleRepository.getCycleProgress(activeId)?.let { updated ->
                                        cycleProgress = cycleProgress.toMutableMap().apply {
                                            this[activeId] = updated
                                        }
                                        selectedDayNumber = updated.currentDayNumber
                                    }
                                }
                            },
                            onEditCycle = {
                                navController.navigate(NavigationRoutes.CycleEditor.createRoute(activeCycle!!.id))
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
                        TextButton(onClick = { showCreationSheet = true }) {
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
                        onDeactivate = {
                            scope.launch {
                                cycleRepository.clearActiveCycle()
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
            onClick = { showCreationSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create Cycle")
        }

        // Snackbar for cycle completion feedback
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }

    // Unified Cycle Creation Sheet
    if (showCreationSheet) {
        UnifiedCycleCreationSheet(
            sheetState = creationSheetState,
            onSelectTemplate = { template ->
                scope.launch {
                    creationSheetState.hide()
                    showCreationSheet = false
                    if (template.requiresOneRepMax) {
                        // 5/3/1 needs 1RM input → mode confirmation → create
                        creationState = CycleCreationState.OneRepMaxInput(template)
                    } else {
                        // Simple templates: auto-create with defaults, skip 1RM and mode screens
                        creationState = CycleCreationState.Creating(template)
                        try {
                            val conversionResult = templateConverter.convert(template)

                            conversionResult.routines.forEach { routine ->
                                workoutRepository.saveRoutine(routine)
                            }
                            cycleRepository.saveCycle(conversionResult.cycle)

                            if (conversionResult.warnings.isNotEmpty()) {
                                Logger.w { "Some exercises not found: ${conversionResult.warnings}" }
                                showWarningDialog = conversionResult.warnings
                            }

                            creationState = CycleCreationState.Idle
                            Logger.d { "Auto-created cycle: ${template.name}" }
                        } catch (e: Exception) {
                            Logger.e(e) { "Failed to auto-create cycle from template" }
                            creationState = CycleCreationState.Idle
                            showErrorDialog = e.message ?: "Failed to create training cycle"
                        }
                    }
                }
            },
            onCreateCustom = {
                scope.launch {
                    creationSheetState.hide()
                    showCreationSheet = false
                    navController.navigate(NavigationRoutes.CycleEditor.createRoute("new"))
                }
            },
            onDismiss = { showCreationSheet = false }
        )
    }

    // OneRepMaxInputScreen
    when (val state = creationState) {
        is CycleCreationState.OneRepMaxInput -> {
            // Extract exercise names from template - show all cable exercises (not just percentage-based)
            // This allows users to enter 1RM values for any exercise they want
            // Priority: percentage-based exercises first, then other cable exercises
            val percentageBasedExercises = state.template.days
                .flatMap { it.routine?.exercises ?: emptyList() }
                .filter { it.isPercentageBased }
                .map { it.exerciseName }
                .distinct()

            val otherCableExercises = state.template.days
                .flatMap { it.routine?.exercises ?: emptyList() }
                .filter { !it.isPercentageBased && it.suggestedMode != null } // Cable exercises only
                .map { it.exerciseName }
                .distinct()
                .filter { it !in percentageBasedExercises } // Avoid duplicates

            val mainLiftNames = percentageBasedExercises + otherCableExercises

            // Load existing 1RM values - prioritize PR value over stored 1RM
            // Also load PR weight values for showing PR indicators in ModeConfirmation
            val existingOneRepMaxValues = remember { mutableStateMapOf<String, Float>() }
            val existingPrWeightValues = remember { mutableStateMapOf<String, Float>() }
            LaunchedEffect(mainLiftNames) {
                mainLiftNames.forEach { exerciseName ->
                    exerciseRepository.findByName(exerciseName)?.let { exercise ->
                        val exerciseId = exercise.id ?: return@let

                        // First try to get the PR (best weight ever achieved)
                        val pr = personalRecordRepository.getBestWeightPR(exerciseId)
                        val prOneRepMax = pr?.oneRepMax

                        // Use PR's 1RM if available, else fall back to stored exercise 1RM
                        val valueToUse = prOneRepMax?.takeIf { it > 0f }
                            ?: exercise.oneRepMaxKg?.takeIf { it > 0f }

                        valueToUse?.let { oneRepMax ->
                            existingOneRepMaxValues[exerciseName] = oneRepMax
                        }

                        // Store the actual PR weight for indicator display
                        pr?.weightPerCableKg?.takeIf { it > 0f }?.let { weight ->
                            existingPrWeightValues[exerciseName] = weight
                        }
                    }
                }
            }

            OneRepMaxInputScreen(
                mainLiftNames = mainLiftNames,
                existingOneRepMaxValues = existingOneRepMaxValues,
                weightUnit = weightUnit,
                kgToDisplay = viewModel::kgToDisplay,
                displayToKg = viewModel::displayToKg,
                onConfirm = { oneRepMaxValues ->
                    creationState = CycleCreationState.ModeConfirmation(
                        template = state.template,
                        oneRepMaxValues = oneRepMaxValues,
                        prWeightValues = existingPrWeightValues.toMap()
                    )
                },
                onCancel = {
                    creationState = CycleCreationState.Idle
                }
            )
        }
        is CycleCreationState.ModeConfirmation -> {
            ModeConfirmationScreen(
                template = state.template,
                oneRepMaxValues = state.oneRepMaxValues,
                prWeightValues = state.prWeightValues,
                onConfirm = { exerciseConfigs ->
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

                            // 2. Convert template using TemplateConverter (with user's exercise configs)
                            val conversionResult = templateConverter.convert(
                                template = state.template,
                                exerciseConfigs = exerciseConfigs
                            )

                            // 3. Save routines FIRST (CycleDay has FK to Routine)
                            // CRITICAL: Must await each save - workoutRepository.saveRoutine is suspend
                            // Using viewModel.saveRoutine() was fire-and-forget (launched coroutine without await)
                            conversionResult.routines.forEach { routine ->
                                workoutRepository.saveRoutine(routine)
                            }

                            // 4. Save cycle via TrainingCycleRepository (routines now guaranteed to exist)
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

    // Resume/Restart Dialog (Issue #101)
    if (showResumeDialog) {
        viewModel.getResumableProgressInfo()?.let { info ->
            ResumeRoutineDialog(
                progressInfo = info,
                onResume = {
                    showResumeDialog = false
                    // Resume: skip loadRoutine to keep existing progress, just navigate and start
                    viewModel.ensureConnection(
                        onConnected = {
                            viewModel.startWorkout()
                            navController.navigate(NavigationRoutes.ActiveWorkout.route)
                        },
                        onFailed = { /* Error shown via StateFlow */ }
                    )
                },
                onRestart = {
                    showResumeDialog = false
                    // Restart: call loadRoutineFromCycle to reset indices, then start
                    pendingRoutineId?.let { rid ->
                        viewModel.ensureConnection(
                            onConnected = {
                                viewModel.loadRoutineFromCycle(rid, pendingCycleId ?: "", pendingDayNumber)
                                viewModel.startWorkout()
                                navController.navigate(NavigationRoutes.ActiveWorkout.route)
                            },
                            onFailed = { /* Error shown via StateFlow */ }
                        )
                    }
                },
                onDismiss = { showResumeDialog = false }
            )
        }
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
    onAdvanceDay: () -> Unit,
    onJumpToDay: (Int) -> Unit,
    onEditCycle: () -> Unit
) {
    val currentDay = progress?.currentDayNumber ?: 1
    // Use selected day for preview, or default to current day
    val displayedDayNumber = selectedDayNumber ?: currentDay
    val isViewingCurrentDay = displayedDayNumber == currentDay

    val displayedCycleDay = cycle.days.find { it.dayNumber == displayedDayNumber }
    val routine = displayedCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }
    val isRestDay = displayedCycleDay?.isRestDay == true
    val hasRoutine = displayedCycleDay?.routineId != null
    val isUnassignedWorkout = displayedCycleDay != null && !isRestDay && !hasRoutine

    // Create a default progress if none exists
    val effectiveProgress = progress ?: CycleProgress.create(
        cycleId = cycle.id,
        currentDayNumber = 1
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
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
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Day $displayedDayNumber of ${cycle.days.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Day name and routine
            Text(
                displayedCycleDay?.name ?: "Day $displayedDayNumber",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (isRestDay) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SelfImprovement,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Rest Day - Take it easy!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (routine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    routine.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
                Text(
                    routine.exercises.joinToString(", ") { it.exercise.name }.take(50) +
                        if (routine.exercises.size > 3) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isUnassignedWorkout) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "No routine assigned",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                if (displayedCycleDay == null) {
                    OutlinedButton(
                        onClick = onEditCycle,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Edit Cycle")
                    }
                } else if (isRestDay) {
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
                        // Viewing a different rest day - offer jump or return to today
                        OutlinedButton(
                            onClick = { onJumpToDay(displayedDayNumber) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Jump to Day $displayedDayNumber")
                        }
                    }
                } else {
                    if (isViewingCurrentDay) {
                        if (hasRoutine) {
                            Button(
                                onClick = { onStartWorkout(displayedCycleDay?.routineId, cycle.id, displayedDayNumber) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Workout")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onEditCycle,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Assign Routine")
                            }
                        }
                    } else {
                        // Viewing a different workout day - offer jump and start workout
                        if (hasRoutine) {
                            OutlinedButton(
                                onClick = { onJumpToDay(displayedDayNumber) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Jump to Day $displayedDayNumber")
                            }
                            Button(
                                onClick = { onStartWorkout(displayedCycleDay?.routineId, cycle.id, displayedDayNumber) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start Workout")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onJumpToDay(displayedDayNumber) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Jump to Day $displayedDayNumber")
                            }
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
    onDeactivate: () -> Unit,
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

                    // Action buttons - use filled tonal for visibility
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Show Activate or Deactivate based on current state
                        FilledTonalButton(
                            onClick = if (isActive) onDeactivate else onActivate,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isActive)
                                    MaterialTheme.colorScheme.surfaceVariant
                                else
                                    MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (isActive)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(
                                if (isActive) Icons.Default.Cancel else Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isActive) "Deactivate" else "Activate", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Edit", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

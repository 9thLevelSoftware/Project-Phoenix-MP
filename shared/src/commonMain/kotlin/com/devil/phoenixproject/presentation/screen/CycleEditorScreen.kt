package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.cycle.AddDaySheet
import com.devil.phoenixproject.presentation.components.cycle.ProgressionSettingsSheet
import com.devil.phoenixproject.presentation.components.cycle.SwipeableCycleItem
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String,
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>,
    cycleEditorViewModel: CycleEditorViewModel = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect ViewModel state
    val uiState by cycleEditorViewModel.uiState.collectAsState()

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    // Initialize ViewModel with cycle data
    LaunchedEffect(cycleId) {
        cycleEditorViewModel.initialize(cycleId)
    }

    // Dirty-state snapshot vars (content-only: cycleName + description + items + progression).
    // UI-only fields (showAddDaySheet, showProgressionSheet, editingItemIndex, recentRoutineIds,
    // currentRotation, isLoading, isSaving, saveError, lastDeletedItem) are intentionally excluded.
    var snapshotCycleName by remember { mutableStateOf("") }
    var snapshotDescription by remember { mutableStateOf("") }
    var snapshotItems by remember { mutableStateOf<List<CycleItem>>(emptyList()) }
    var snapshotProgression by remember { mutableStateOf<CycleProgression?>(null) }
    var hasSnapshot by remember { mutableStateOf(false) }

    // Discard-changes dialog state (back-navigation guard)
    var showDiscardDialog by remember { mutableStateOf(false) }

    // Capture post-load snapshot so existing cycles open clean. isLoading transitions false→false
    // only once, so hasSnapshot gate prevents re-capture during recompositions.
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && !hasSnapshot) {
            snapshotCycleName = uiState.cycleName
            snapshotDescription = uiState.description
            snapshotItems = uiState.items
            snapshotProgression = uiState.progression
            hasSnapshot = true
        }
    }

    // isDirty: true only after snapshot is taken and any content field diverges from snapshot.
    val isDirty = hasSnapshot && (
        uiState.cycleName != snapshotCycleName ||
        uiState.description != snapshotDescription ||
        uiState.items != snapshotItems ||
        uiState.progression != snapshotProgression
    )

    // Back-navigation guard: intercept back only when there are unsaved changes.
    BackHandler(enabled = isDirty) { showDiscardDialog = true }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        cycleEditorViewModel.reorderItems(from.index, to.index)
    }

    // Save function using ViewModel
    fun saveCycle() {
        Logger.d { "CycleEditor: Preview button clicked, starting save..." }
        scope.launch {
            val savedId = cycleEditorViewModel.saveCycle()
            if (savedId != null) {
                // Pop CycleEditor from backstack so back from Preview goes to TrainingCycles
                navController.navigate(NavigationRoutes.CycleReview.createRoute(savedId)) {
                    popUpTo(NavigationRoutes.TrainingCycles.route) { inclusive = false }
                }
            } else {
                // Read error directly from ViewModel (composed state may not have updated yet)
                cycleEditorViewModel.uiState.value.saveError?.let {
                    snackbarHostState.showSnackbar("Failed to save: $it")
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { cycleEditorViewModel.showAddDaySheet(true) },
            ) {
                Icon(Icons.Default.Add, "Add Day")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBackgroundBrush())
                .padding(padding),
        ) {
            // Editable cycle name with Preview button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                OutlinedTextField(
                    value = uiState.cycleName,
                    onValueChange = { cycleEditorViewModel.updateCycleName(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(Res.string.cycle_name)) },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    singleLine = true,
                )
                Button(onClick = { saveCycle() }) {
                    Text(stringResource(Res.string.action_preview))
                }
            }

            // Description field
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { cycleEditorViewModel.updateDescription(it) },
                label = { Text(stringResource(Res.string.label_description_optional)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                singleLine = true,
            )

            // Cycle length header with progression settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CYCLE LENGTH: ${uiState.items.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { cycleEditorViewModel.showProgressionSheet(true) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(Res.string.cd_progression_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Day list or empty state
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.extraLarge),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No days added yet.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Build your cycle by adding workout or rest days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.large))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            OutlinedButton(
                                onClick = { cycleEditorViewModel.showAddDaySheet(true) },
                            ) {
                                Text(stringResource(Res.string.add_workout))
                            }
                            OutlinedButton(onClick = { cycleEditorViewModel.addRestDay() }) {
                                Text(stringResource(Res.string.add_rest))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    itemsIndexed(uiState.items, key = { _, item -> item.id }) { index, item ->
                        ReorderableItem(reorderState, key = item.id) { isDragging ->
                            SwipeableCycleItem(
                                item = item,
                                onDelete = {
                                    cycleEditorViewModel.deleteItem(index)
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Day removed",
                                            actionLabel = "UNDO",
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            cycleEditorViewModel.undoDelete()
                                        } else {
                                            cycleEditorViewModel.clearLastDeleted()
                                        }
                                    }
                                },
                                onDuplicate = { cycleEditorViewModel.duplicateItem(index) },
                                onTap = {
                                    // Workout: change routine; Rest: convert to workout
                                    cycleEditorViewModel.setEditingItemIndex(index)
                                },
                                dragModifier = Modifier.draggableHandle(),
                            )
                        }
                    }
                    // Bottom padding for FAB
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Add Day Sheet
    if (uiState.showAddDaySheet) {
        AddDaySheet(
            routines = routines,
            recentRoutineIds = uiState.recentRoutineIds,
            onSelectRoutine = { routine ->
                cycleEditorViewModel.addWorkoutDay(routine)
            },
            onAddRestDay = { cycleEditorViewModel.addRestDay() },
            onDismiss = { cycleEditorViewModel.showAddDaySheet(false) },
        )
    }

    // Progression Settings Sheet
    if (uiState.showProgressionSheet) {
        uiState.progression?.let { prog ->
            ProgressionSettingsSheet(
                progression = prog,
                currentRotation = uiState.currentRotation,
                onSave = { newProgression ->
                    cycleEditorViewModel.updateProgression(newProgression)
                },
                onDismiss = { cycleEditorViewModel.showProgressionSheet(false) },
            )
        }
    }

    // Discard Changes Dialog (back-navigation guard)
    // onConfirm: navigate back without saving. onDismiss: stay in the editor.
    if (showDiscardDialog) {
        DestructiveConfirmDialog(
            title = stringResource(Res.string.discard_changes_title),
            message = stringResource(Res.string.discard_changes_message),
            confirmText = stringResource(Res.string.action_discard),
            onConfirm = {
                showDiscardDialog = false
                navController.popBackStack()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    // Edit sheet - for workout days: change routine; for rest days: convert to workout
    uiState.editingItemIndex?.let { index ->
        val item = uiState.items.getOrNull(index)
        when (item) {
            is CycleItem.Workout -> {
                AddDaySheet(
                    routines = routines,
                    recentRoutineIds = uiState.recentRoutineIds,
                    onSelectRoutine = { routine ->
                        cycleEditorViewModel.changeRoutine(index, routine)
                    },
                    onAddRestDay = {
                        // Convert workout to rest day
                        cycleEditorViewModel.convertToRest(index)
                        cycleEditorViewModel.setEditingItemIndex(null)
                    },
                    onDismiss = { cycleEditorViewModel.setEditingItemIndex(null) },
                    // Post-creation customization (#620): edit this day's routine
                    // exercises in the routine editor. Loads by ID from the DB, so
                    // template-created "cycle_routine_" routines work too.
                    onEditExercises = {
                        cycleEditorViewModel.setEditingItemIndex(null)
                        navController.navigate(
                            NavigationRoutes.RoutineEditor.createRoute(item.routineId),
                        )
                    },
                )
            }

            is CycleItem.Rest -> {
                AddDaySheet(
                    routines = routines,
                    recentRoutineIds = uiState.recentRoutineIds,
                    onSelectRoutine = { routine ->
                        // Convert rest day to workout
                        cycleEditorViewModel.convertToWorkout(index, routine)
                    },
                    onAddRestDay = { /* Already a rest day */ },
                    onDismiss = { cycleEditorViewModel.setEditingItemIndex(null) },
                )
            }

            else -> {
                cycleEditorViewModel.setEditingItemIndex(null)
            }
        }
    }
}

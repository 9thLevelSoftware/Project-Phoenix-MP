package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.cycle.AddDaySheet
import com.devil.phoenixproject.presentation.components.cycle.ProgressionSettingsSheet
import com.devil.phoenixproject.presentation.components.cycle.SwipeableCycleItem
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String,
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>,
    initialDayCount: Int? = null,
    cycleEditorViewModel: CycleEditorViewModel = koinInject()
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect ViewModel state
    val uiState by cycleEditorViewModel.uiState.collectAsState()

    // Initialize ViewModel with cycle data
    LaunchedEffect(cycleId, initialDayCount) {
        cycleEditorViewModel.initialize(cycleId, initialDayCount)
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        cycleEditorViewModel.reorderItems(from.index, to.index)
    }

    // Save function using ViewModel
    fun saveCycle() {
        scope.launch {
            val savedId = cycleEditorViewModel.saveCycle()
            if (savedId != null) {
                // Pop CycleEditor from backstack so back from Preview goes to TrainingCycles
                navController.navigate(NavigationRoutes.CycleReview.createRoute(savedId)) {
                    popUpTo(NavigationRoutes.TrainingCycles.route) { inclusive = false }
                }
            } else {
                uiState.saveError?.let {
                    snackbarHostState.showSnackbar("Failed to save: $it")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        TextField(
                            value = uiState.cycleName,
                            onValueChange = { cycleEditorViewModel.updateCycleName(it) },
                            placeholder = { Text("Cycle Name") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                            ),
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            singleLine = true
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { saveCycle() }) {
                        Text("Preview", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { cycleEditorViewModel.showAddDaySheet(true) }
            ) {
                Icon(Icons.Default.Add, "Add Day")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Description field
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { cycleEditorViewModel.updateDescription(it) },
                label = { Text("Description (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            // Cycle length header with progression settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CYCLE LENGTH: ${uiState.items.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { cycleEditorViewModel.showProgressionSheet(true) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Progression Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Day list or empty state
            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No days added yet.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Build your cycle by adding workout or rest days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { cycleEditorViewModel.showAddDaySheet(true) }
                            ) {
                                Text("+ Add Workout")
                            }
                            OutlinedButton(onClick = { cycleEditorViewModel.addRestDay() }) {
                                Text("+ Add Rest")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                            duration = SnackbarDuration.Short
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
                                    if (item is CycleItem.Workout) {
                                        cycleEditorViewModel.setEditingItemIndex(index)
                                    }
                                },
                                dragModifier = Modifier.draggableHandle()
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
            onDismiss = { cycleEditorViewModel.showAddDaySheet(false) }
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
                onDismiss = { cycleEditorViewModel.showProgressionSheet(false) }
            )
        }
    }

    // Edit routine sheet (reuse AddDaySheet in edit mode)
    uiState.editingItemIndex?.let { index ->
        val item = uiState.items.getOrNull(index)
        if (item is CycleItem.Workout) {
            AddDaySheet(
                routines = routines,
                recentRoutineIds = uiState.recentRoutineIds,
                onSelectRoutine = { routine ->
                    cycleEditorViewModel.changeRoutine(index, routine)
                },
                onAddRestDay = { /* Not applicable in edit mode */ },
                onDismiss = { cycleEditorViewModel.setEditingItemIndex(null) }
            )
        }
    }
}

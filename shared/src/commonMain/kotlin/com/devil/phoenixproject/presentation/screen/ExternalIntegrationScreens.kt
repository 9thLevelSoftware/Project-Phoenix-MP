package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.presentation.viewmodel.ExternalMeasurementsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalProgramsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalRoutinesViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntegrationUiEvent
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ExternalIntegrationHubScreen(
    onNavigateToActivities: () -> Unit,
    onNavigateToRoutines: () -> Unit,
    onNavigateToPrograms: () -> Unit,
    onNavigateToMeasurements: () -> Unit,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: IntegrationsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("Integration Data")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        item {
            HubCard(
                title = "Workouts",
                subtitle = "${uiState.externalActivities.size} imported activities",
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                onClick = onNavigateToActivities,
            )
        }
        item {
            HubCard(
                title = "Hevy Routines",
                subtitle = "${uiState.externalRoutines.size} routines, ${uiState.externalRoutineFolders.size} folders",
                icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                onClick = onNavigateToRoutines,
            )
        }
        item {
            HubCard(
                title = "Liftosaur Programs",
                subtitle = "${uiState.externalPrograms.size} programs, ${uiState.externalProgramStatsByProgramId.size} stats records",
                icon = { Icon(Icons.Default.Code, contentDescription = null) },
                onClick = onNavigateToPrograms,
            )
        }
        item {
            HubCard(
                title = "Measurements",
                subtitle = "${uiState.externalMeasurements.size} Hevy measurement records",
                icon = { Icon(Icons.Default.Scale, contentDescription = null) },
                onClick = onNavigateToMeasurements,
            )
        }
    }
}

@Composable
fun ExternalRoutinesScreen(
    onRoutineClick: (IntegrationProvider, String) -> Unit,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalRoutinesViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("External Routines")
    }

    if (state.routines.isEmpty()) {
        EmptyIntegrationState("No routines imported yet")
        return
    }

    val grouped = remember(state.routines) { state.routines.groupBy { it.folderName ?: "Unfiled" } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        grouped.forEach { (folder, routines) ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(folder, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            items(routines, key = { it.id }) { routine ->
                RoutineCard(routine = routine, onClick = { onRoutineClick(routine.provider, routine.externalId) })
            }
        }
    }
}

@Composable
fun ExternalRoutineDetailScreen(
    providerKey: String,
    externalRoutineId: String,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalRoutinesViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    val routine = remember(state.routines, providerKey, externalRoutineId) {
        state.routines.firstOrNull { it.provider.key == providerKey && it.externalId == externalRoutineId }
    }

    LaunchedEffect(routine?.title) {
        onSetTitle(routine?.title ?: "Routine")
    }

    if (routine == null) {
        EmptyIntegrationState("Routine not found")
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        item {
            Text(routine.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                routine.folderName ?: routine.provider.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "Review exercise mappings before converting. External weights are preserved as informational until per-cable mapping is confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.small),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        items(routine.exercises, key = { it.id }) { exercise ->
            EntityCard {
                Text(exercise.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(exercise.exerciseType, exercise.primaryMuscleGroups.joinToString(", ").ifBlank { null }).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exercise.sets.forEach { set ->
                    Text(
                        "Set ${set.index + 1}: ${set.setType ?: "normal"} ${set.reps ?: "-"} reps ${set.weightKg?.let { "@ ${it}kg" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ExternalProgramsScreen(
    onProgramClick: (IntegrationProvider, String) -> Unit,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalProgramsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("External Programs")
    }

    if (state.programs.isEmpty()) {
        EmptyIntegrationState("No programs imported yet")
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        items(state.programs, key = { it.id }) { program ->
            ProgramCard(program = program, onClick = { onProgramClick(program.provider, program.externalId) })
        }
    }
}

@Composable
fun ExternalProgramDetailScreen(
    providerKey: String,
    externalProgramId: String,
    onNavigateToPlayground: (IntegrationProvider, String) -> Unit,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalProgramsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    val program = remember(state.programs, providerKey, externalProgramId) {
        state.programs.firstOrNull { it.provider.key == providerKey && it.externalId == externalProgramId }
    }

    LaunchedEffect(program?.name) {
        onSetTitle(program?.name ?: "Program")
    }

    if (program == null) {
        EmptyIntegrationState("Program not found")
        return
    }

    val stats = state.statsByProgramId[program.id]
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        item {
            Text(program.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (program.isCurrent) StatusChip("Current program")
            Spacer(Modifier.height(8.dp))
            EntityCard {
                Text("Stats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${stats?.days ?: 0} days")
                Text("${stats?.approximateMinutes ?: 0} approximate minutes")
                Text("${stats?.setCount ?: 0} sets")
                stats?.muscleGroupBreakdownJson?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(
                onClick = { onNavigateToPlayground(program.provider, program.externalId) },
                enabled = !program.scriptText.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Science, contentDescription = null)
                Text("Preview Playground")
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "Liftosaur conversion is read-only here. Manual Phoenix cycle conversion should happen only after validating Liftoscript mappings.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.small),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        item {
            Text(program.scriptText ?: "No script text available", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ExternalProgramPlaygroundScreen(
    providerKey: String,
    externalProgramId: String,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalProgramsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val program = remember(state.programs, providerKey, externalProgramId) {
        state.programs.firstOrNull { it.provider.key == providerKey && it.externalId == externalProgramId }
    }
    val preview = state.playgroundPreview?.takeIf { it.programExternalId == externalProgramId }

    LaunchedEffect(Unit) {
        onSetTitle("Program Playground")
    }

    LaunchedEffect(providerKey, externalProgramId) {
        viewModel.clearPreview()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is IntegrationUiEvent.Snackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    if (program == null) {
        EmptyIntegrationState("Program not found")
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item {
                Text(program.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { viewModel.simulateProgram(program) },
                    enabled = !state.isSimulating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isSimulating) "Simulating..." else "Run Preview")
                }
            }
            item {
                EntityCard {
                    Text("Current workout", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(preview?.currentWorkoutText ?: "Run a preview to see the current workout.")
                }
            }
            item {
                EntityCard {
                    Text("Next workout", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(preview?.nextWorkoutText ?: "Run a preview to see the next workout.")
                }
            }
            item {
                EntityCard {
                    Text("Updated program", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(preview?.updatedProgramText ?: "No updated program text yet.")
                }
            }
            if (preview?.updatedProgramText != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.commitPreview(program) }, modifier = Modifier.weight(1f)) {
                            Text("Commit")
                        }
                        TextButton(onClick = { viewModel.clearPreview() }, modifier = Modifier.weight(1f)) {
                            Text("Discard")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExternalMeasurementTrendsScreen(
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ExternalMeasurementsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("Measurements")
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.measurementTypes.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.selectedType == null,
                        onClick = { viewModel.selectType(null) },
                        label = { Text("All") },
                    )
                }
                items(state.measurementTypes) { type ->
                    FilterChip(
                        selected = state.selectedType == type,
                        onClick = { viewModel.selectType(type) },
                        label = { Text(type) },
                    )
                }
            }
        }
        if (state.measurements.isEmpty()) {
            EmptyIntegrationState(
                if (state.measurementTypes.isEmpty()) "No measurements imported yet" else "No measurements for this type",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                items(state.measurements, key = { it.id }) { measurement ->
                    MeasurementCard(measurement)
                }
            }
        }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            icon()
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoutineCard(routine: ExternalRoutine, onClick: () -> Unit) {
    EntityCard(onClick = onClick) {
        Text(routine.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("${routine.exercises.size} exercises", style = MaterialTheme.typography.bodySmall)
        Text(routine.provider.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgramCard(program: ExternalProgram, onClick: () -> Unit) {
    EntityCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(program.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (program.isCurrent) StatusChip("Current")
        }
        Text(program.provider.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MeasurementCard(measurement: ExternalBodyMeasurement) {
    EntityCard {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(measurement.measurementType, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${measurement.value} ${measurement.unit}", style = MaterialTheme.typography.titleSmall)
        }
        Text(KmpUtils.formatTimestamp(measurement.measuredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EntityCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
    if (onClick == null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = cardContent,
        )
    } else {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            content = cardContent,
        )
    }
}

@Composable
private fun EmptyIntegrationState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(message, style = MaterialTheme.typography.titleMedium)
        }
    }
}

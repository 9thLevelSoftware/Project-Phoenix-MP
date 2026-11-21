package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.SectionHeaderIconBadge
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.ExternalMeasurementsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalProgramsViewModel
import com.devil.phoenixproject.presentation.viewmodel.ExternalRoutinesViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntegrationUiEvent
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.koin.compose.koinInject
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
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        onSetTitle("Integration Data")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item {
                HubCard(
                    title = "Workouts",
                    subtitle = "${uiState.externalActivities.size} imported activities",
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = onNavigateToActivities,
                )
            }
            item {
                HubCard(
                    title = "Hevy Routines",
                    subtitle = "${uiState.externalRoutines.size} routines, ${uiState.externalRoutineFolders.size} folders",
                    icon = Icons.Default.FitnessCenter,
                    onClick = onNavigateToRoutines,
                )
            }
            item {
                HubCard(
                    title = "Liftosaur Programs",
                    subtitle = "${uiState.externalPrograms.size} programs, ${uiState.externalProgramStatsByProgramId.size} stats records",
                    icon = Icons.Default.Code,
                    onClick = onNavigateToPrograms,
                )
            }
            item {
                HubCard(
                    title = "Measurements",
                    subtitle = "${uiState.externalMeasurements.size} Hevy measurement records",
                    icon = Icons.Default.Scale,
                    onClick = onNavigateToMeasurements,
                )
            }
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
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        onSetTitle("External Routines")
    }

    if (state.routines.isEmpty()) {
        EmptyIntegrationState(
            message = "No routines imported yet",
            icon = Icons.Default.FitnessCenter,
            modifier = modifier,
        )
        return
    }

    val grouped = remember(state.routines) { state.routines.groupBy { it.folderName ?: "Unfiled" } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
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
}

@Composable
fun ExternalRoutineDetailScreen(
    providerKey: String,
    externalRoutineId: String,
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit? = null,
) {
    val viewModel: ExternalRoutinesViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    val resolvedWeightUnit = rememberPreferredWeightUnit(weightUnit)
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val routine = remember(state.routines, providerKey, externalRoutineId) {
        state.routines.firstOrNull { it.provider.key == providerKey && it.externalId == externalRoutineId }
    }

    LaunchedEffect(routine?.title) {
        onSetTitle(routine?.title ?: "Routine")
    }

    if (routine == null) {
        EmptyIntegrationState(
            message = "Routine not found",
            icon = Icons.Default.Inbox,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
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
                    shape = MaterialTheme.shapes.small,
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
                    val meta = listOfNotNull(
                        exercise.exerciseType,
                        exercise.primaryMuscleGroups.joinToString(", ").ifBlank { null },
                    ).joinToString(" / ")
                    if (meta.isNotBlank()) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    exercise.sets.forEach { set ->
                        val weightPart = set.weightKg?.let { kg ->
                            " @ ${formatExternalWeightKg(kg, resolvedWeightUnit)}"
                        }.orEmpty()
                        Text(
                            "Set ${set.index + 1}: ${set.setType ?: "normal"} · ${set.reps ?: "-"} reps$weightPart",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        onSetTitle("External Programs")
    }

    if (state.programs.isEmpty()) {
        EmptyIntegrationState(
            message = "No programs imported yet",
            icon = Icons.Default.FitnessCenter,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            items(state.programs, key = { it.id }) { program ->
                ProgramCard(program = program, onClick = { onProgramClick(program.provider, program.externalId) })
            }
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
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val program = remember(state.programs, providerKey, externalProgramId) {
        state.programs.firstOrNull { it.provider.key == providerKey && it.externalId == externalProgramId }
    }

    LaunchedEffect(program?.name) {
        onSetTitle(program?.name ?: "Program")
    }

    if (program == null) {
        EmptyIntegrationState(
            message = "Program not found",
            icon = Icons.Default.Inbox,
            modifier = modifier,
        )
        return
    }

    val stats = state.statsByProgramId[program.id]
    val muscleBreakdown = remember(stats?.muscleGroupBreakdownJson) {
        parseMuscleGroupBreakdown(stats?.muscleGroupBreakdownJson)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item {
                Text(program.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (program.isCurrent) {
                    Spacer(Modifier.height(4.dp))
                    StatusChip("Current program")
                }
                Spacer(Modifier.height(8.dp))
                EntityCard {
                    Text("Stats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${stats?.days ?: 0} days", style = MaterialTheme.typography.bodyMedium)
                    Text("${stats?.approximateMinutes ?: 0} approximate minutes", style = MaterialTheme.typography.bodyMedium)
                    Text("${stats?.setCount ?: 0} sets", style = MaterialTheme.typography.bodyMedium)
                    if (muscleBreakdown.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.small))
                        Text(
                            "Muscle Group Breakdown",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val maxValue = muscleBreakdown.maxOf { it.value }.coerceAtLeast(1.0)
                        val totalValue = muscleBreakdown.sumOf { it.value }
                        val looksLikePercentages = totalValue in 95.0..105.0
                        val looksLikeRatios = totalValue in 0.95..1.05 && muscleBreakdown.all { it.value <= 1.0 }
                        muscleBreakdown.forEach { entry ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = entry.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val formattedValue = entry.subtitle ?: when {
                                        looksLikeRatios -> "${UnitConverter.formatDecimal((entry.value * 100.0).toFloat())}%"
                                        looksLikePercentages -> "${UnitConverter.formatDecimal(entry.value.toFloat())}%"
                                        else -> UnitConverter.formatDecimal(entry.value.toFloat())
                                    }
                                    Text(
                                        text = formattedValue,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (entry.value / maxValue).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                    } else if (!stats?.muscleGroupBreakdownJson.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Muscle group breakdown unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.small))
                OutlinedButton(
                    onClick = { onNavigateToPlayground(program.provider, program.externalId) },
                    enabled = !program.scriptText.isNullOrBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Science, contentDescription = null)
                    Spacer(Modifier.size(Spacing.small))
                    Text("Preview Playground")
                }
                Spacer(Modifier.height(Spacing.small))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
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
                EntityCard {
                    Text(
                        program.scriptText ?: "No script text available",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                IntegrationUiEvent.OpenHealthPermissionSettings -> Unit
            }
        }
    }

    if (program == null) {
        EmptyIntegrationState(
            message = "Program not found",
            icon = Icons.Default.Inbox,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.medium,
                end = Spacing.medium,
                top = Spacing.medium,
                bottom = Spacing.medium + navBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item {
                Text(program.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.small))
                Button(
                    onClick = { viewModel.simulateProgram(program) },
                    enabled = !state.isSimulating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { viewModel.commitPreview(program) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text("Commit")
                        }
                        TextButton(
                            onClick = { viewModel.clearPreview() },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text("Discard")
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

@Composable
fun ExternalMeasurementTrendsScreen(
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit? = null,
) {
    val viewModel: ExternalMeasurementsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsState()
    val resolvedWeightUnit = rememberPreferredWeightUnit(weightUnit)
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        onSetTitle("Measurements")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
        ) {
            if (state.measurementTypes.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedType == null,
                            onClick = { viewModel.selectType(null) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("All") },
                        )
                    }
                    items(state.measurementTypes) { type ->
                        FilterChip(
                            selected = state.selectedType == type,
                            onClick = { viewModel.selectType(type) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(type) },
                        )
                    }
                }
            }
            if (state.measurements.isEmpty()) {
                EmptyIntegrationState(
                    message = if (state.measurementTypes.isEmpty()) {
                        "No measurements imported yet"
                    } else {
                        "No measurements for this type"
                    },
                    icon = Icons.Default.Scale,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.medium,
                        end = Spacing.medium,
                        top = Spacing.small,
                        bottom = Spacing.medium + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    items(state.measurements, key = { it.id }) { measurement ->
                        MeasurementCard(measurement, resolvedWeightUnit)
                    }
                }
            }
        }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ExpressiveCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            SectionHeaderIconBadge(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
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
            Text(
                program.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (program.isCurrent) StatusChip("Current")
        }
        Text(program.provider.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MeasurementCard(measurement: ExternalBodyMeasurement, preferredWeightUnit: WeightUnit) {
    EntityCard {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                measurement.measurementType,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatMeasurementValue(measurement.value, measurement.unit, preferredWeightUnit),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            content = cardContent,
        )
    } else {
        ExpressiveCard(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            content = cardContent,
        )
    }
}

@Composable
private fun EmptyIntegrationState(
    message: String,
    icon: ImageVector = Icons.Default.Inbox,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun rememberPreferredWeightUnit(override: WeightUnit?): WeightUnit {
    if (override != null) return override
    val profileRepository: UserProfileRepository = koinInject()
    val preferencesManager: PreferencesManager = koinInject()
    val profileContext by profileRepository.activeProfileContext.collectAsState()
    val globalPreferences by preferencesManager.preferencesFlow.collectAsState()
    return (profileContext as? ActiveProfileContext.Ready)?.preferences?.core?.value?.weightUnit
        ?: globalPreferences.weightUnit
}

private fun formatExternalWeightKg(weightKg: Double, weightUnit: WeightUnit): String = when (weightUnit) {
    WeightUnit.KG -> "${UnitConverter.formatDecimal(weightKg.toFloat())} kg"
    WeightUnit.LB -> "${UnitConverter.formatDecimal(UnitConverter.kgToLb(weightKg.toFloat()))} lbs"
}

private fun formatMeasurementValue(
    value: Double,
    sourceUnit: String,
    preferredWeightUnit: WeightUnit,
): String {
    val trimmedUnit = sourceUnit.trim()
    return when {
        trimmedUnit.equals("kg", ignoreCase = true) -> {
            if (preferredWeightUnit == WeightUnit.LB) {
                "${UnitConverter.formatDecimal(UnitConverter.kgToLb(value.toFloat()))} lbs"
            } else {
                "${UnitConverter.formatDecimal(value.toFloat())} kg"
            }
        }
        trimmedUnit.equals("lb", ignoreCase = true) || trimmedUnit.equals("lbs", ignoreCase = true) -> {
            if (preferredWeightUnit == WeightUnit.KG) {
                "${UnitConverter.formatDecimal(UnitConverter.lbToKg(value.toFloat()))} kg"
            } else {
                "${UnitConverter.formatDecimal(value.toFloat())} lbs"
            }
        }
        trimmedUnit.isBlank() -> UnitConverter.formatDecimal(value.toFloat())
        else -> "${UnitConverter.formatDecimal(value.toFloat())} $trimmedUnit"
    }
}

private data class MuscleGroupBreakdownEntry(
    val label: String,
    val value: Double,
    val subtitle: String? = null,
)

private fun parseMuscleGroupBreakdown(rawJson: String?): List<MuscleGroupBreakdownEntry> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val element = Json.parseToJsonElement(rawJson)
        val entries = when (element) {
            is JsonObject -> element.entries.mapNotNull { (key, value) ->
                val label = humanizeMuscleLabel(key)
                when (value) {
                    is JsonPrimitive -> value.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }?.let {
                        MuscleGroupBreakdownEntry(label = label, value = it)
                    }
                    is JsonObject -> {
                        val numeric = listOf("sets", "count", "percentage", "percent", "value", "total", "direct")
                            .firstNotNullOfOrNull { field ->
                                (value[field] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
                            } ?: value.values.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.sum()
                        val direct = (value["direct"] as? JsonPrimitive)?.doubleOrNull
                        val indirect = (value["indirect"] as? JsonPrimitive)?.doubleOrNull
                        val sub = if (direct != null && indirect != null) {
                            "${UnitConverter.formatDecimal(direct.toFloat())} direct · ${UnitConverter.formatDecimal(indirect.toFloat())} indirect"
                        } else {
                            null
                        }
                        if (numeric.isFinite() && numeric > 0.0) {
                            MuscleGroupBreakdownEntry(label = label, value = numeric, subtitle = sub)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
            is JsonArray -> element.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val rawLabel = listOf("muscleGroup", "muscle_group", "muscle", "name", "group", "label")
                    .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
                    ?: return@mapNotNull null
                val numeric = listOf("sets", "count", "percentage", "percent", "value", "total")
                    .firstNotNullOfOrNull { key ->
                        (obj[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }
                    } ?: return@mapNotNull null
                MuscleGroupBreakdownEntry(label = humanizeMuscleLabel(rawLabel), value = numeric)
            }
            else -> emptyList()
        }
        entries.sortedByDescending { it.value }
    }.getOrDefault(emptyList())
}

private fun humanizeMuscleLabel(raw: String): String = raw
    .trim()
    .replace('_', ' ')
    .replace('-', ' ')
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { ch -> ch.titlecase() }
    }
    .ifBlank { raw }

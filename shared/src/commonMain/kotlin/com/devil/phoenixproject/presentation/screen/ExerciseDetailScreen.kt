package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.effectiveTotalVolumeKg
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMax
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMaxSource
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.estimatedOneRepMaxPerCableOrNull
import com.devil.phoenixproject.presentation.components.charts.ProgressionLineChart
import com.devil.phoenixproject.presentation.components.charts.VolumeTrendChart
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.ui.theme.screenBackgroundBrush
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.ShimmerBox
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

internal data class ExerciseDetailOneRepMaxRequest(
    val exerciseId: String,
    val profileId: String,
    val completedSessions: List<WorkoutSession>,
)

internal data class ExerciseDetailOneRepMaxLoadToken(
    val generation: Long,
    val request: ExerciseDetailOneRepMaxRequest,
)

internal class ExerciseDetailOneRepMaxLoadGate {
    private var generation = 0L
    private var active: ExerciseDetailOneRepMaxLoadToken? = null

    fun begin(request: ExerciseDetailOneRepMaxRequest): ExerciseDetailOneRepMaxLoadToken =
        ExerciseDetailOneRepMaxLoadToken(++generation, request).also { active = it }

    fun isCurrent(token: ExerciseDetailOneRepMaxLoadToken): Boolean = active == token
}

internal sealed interface ExerciseDetailOneRepMaxState {
    data object Loading : ExerciseDetailOneRepMaxState
    data class Ready(val resolution: CurrentOneRepMax?) : ExerciseDetailOneRepMaxState
    data object Failed : ExerciseDetailOneRepMaxState
}

internal suspend fun loadExerciseDetailOneRepMax(
    request: ExerciseDetailOneRepMaxRequest,
    gate: ExerciseDetailOneRepMaxLoadGate,
    resolve: suspend (exerciseId: String, profileId: String) -> CurrentOneRepMax?,
    publish: (ExerciseDetailOneRepMaxState) -> Unit,
) {
    val token = gate.begin(request)
    publish(ExerciseDetailOneRepMaxState.Loading)
    try {
        val resolution = resolve(request.exerciseId, request.profileId)
        if (gate.isCurrent(token)) {
            publish(ExerciseDetailOneRepMaxState.Ready(resolution))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        if (gate.isCurrent(token)) {
            publish(ExerciseDetailOneRepMaxState.Failed)
        }
    }
}

/**
 * Detail screen for a single exercise.
 * Shows 1RM progression, weight trend, volume chart, and workout history.
 * Supports toggling between Charts view and tabular Table view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    exerciseId: String,
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode,
    assessmentProfileId: String?,
    onNavigateToStrengthAssessment: (String) -> Unit,
) {
    val allWorkoutSessions by viewModel.allWorkoutSessions.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnected = connectionState is ConnectionState.Connected

    val profileId = assessmentProfileId
    val exerciseSessions = remember(allWorkoutSessions, exerciseId, profileId) {
        if (profileId == null) {
            emptyList()
        } else {
            allWorkoutSessions
                .asSequence()
                .filter { it.profileId == profileId && it.exerciseId == exerciseId }
                .filter { it.workingReps > 0 || it.totalReps > 0 }
                .sortedWith(
                    compareByDescending<WorkoutSession> { it.timestamp }
                        .thenByDescending { it.id },
                )
                .toList()
        }
    }

    // Get exercise name — null while the repository call is in flight
    val unknownExerciseLabel = stringResource(Res.string.exercise_unknown)
    var exerciseName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exerciseId) {
        val exercise = viewModel.exerciseRepository.getExerciseById(exerciseId)
        exerciseName = exercise?.name ?: unknownExerciseLabel
        // Clear topbar title to allow dynamic title from EnhancedMainScreen
        viewModel.updateTopBarTitle("")
    }

    val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase = koinInject()
    val loadGate = remember { ExerciseDetailOneRepMaxLoadGate() }
    val request = remember(exerciseId, profileId, exerciseSessions) {
        profileId?.let {
            ExerciseDetailOneRepMaxRequest(
                exerciseId = exerciseId,
                profileId = it,
                completedSessions = exerciseSessions,
            )
        }
    }
    val stateHolder = remember(request) {
        mutableStateOf<ExerciseDetailOneRepMaxState>(
            ExerciseDetailOneRepMaxState.Loading,
        )
    }
    val oneRepMaxState by stateHolder

    LaunchedEffect(request, resolveCurrentOneRepMax) {
        val currentRequest = request ?: return@LaunchedEffect
        loadExerciseDetailOneRepMax(
            request = currentRequest,
            gate = loadGate,
            resolve = resolveCurrentOneRepMax::invoke,
            publish = { stateHolder.value = it },
        )
    }

    val validSessionEstimatesNewestFirst = remember(exerciseSessions) {
        exerciseSessions.mapNotNull { session ->
            session.estimatedOneRepMaxPerCableOrNull()
                ?.let { estimate -> session.timestamp to estimate }
        }
    }
    val oneRepMaxData = remember(validSessionEstimatesNewestFirst) {
        validSessionEstimatesNewestFirst.reversed()
    }
    val previousSessionOneRepMax =
        validSessionEstimatesNewestFirst.getOrNull(1)?.second

    // Weight-over-time trend data using saved per-cable load.
    val weightTrendData = remember(exerciseSessions) {
        exerciseSessions.mapNotNull { session ->
            if (session.weightPerCableKg > 0) {
                session.timestamp to session.weightPerCableKg
            } else {
                null
            }
        }.reversed()
    }

    // Chronological sessions for volume chart
    val chronologicalSessions = remember(exerciseSessions) {
        exerciseSessions.reversed()
    }

    // View mode toggle: "charts" or "table"
    var viewMode by remember { mutableStateOf("charts") }

    val backgroundGradient = screenBackgroundBrush()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                // Exercise name hero title — shimmer while resolving from repository
                item {
                    if (exerciseName == null) {
                        ShimmerBox(
                            modifier = Modifier
                                .width(180.dp)
                                .height(32.dp),
                        )
                    } else {
                        Text(
                            exerciseName!!,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 1RM Hero Card
                item {
                    OneRepMaxCard(
                        state = oneRepMaxState,
                        previousSessionOneRepMax = previousSessionOneRepMax,
                        weightUnit = weightUnit,
                        formatWeight = viewModel::formatWeight,
                    )
                }

                // Assess 1RM button - launches VBT assessment wizard
                item {
                    OutlinedButton(
                        onClick = {
                            assessmentProfileId?.let(onNavigateToStrengthAssessment)
                        },
                        enabled = isConnected && assessmentProfileId != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = if (isConnected) "Assess 1RM" else "Assess 1RM (Connect to trainer first)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // View mode toggle
                item {
                    ViewModeToggle(
                        viewMode = viewMode,
                        onViewModeChange = { viewMode = it },
                    )
                }

                if (viewMode == "charts") {
                    // 1RM Progression Chart
                    if (oneRepMaxData.size >= 2) {
                        item {
                            ProgressionChartCard(
                                data = oneRepMaxData,
                                weightUnit = weightUnit,
                                formatWeight = viewModel::formatWeight,
                            )
                        }
                    }

                    // Weight Trend Chart
                    if (weightTrendData.size >= 2) {
                        item {
                            WeightTrendChartCard(
                                data = weightTrendData,
                                weightUnit = weightUnit,
                                formatWeight = viewModel::formatWeight,
                            )
                        }
                    }

                    // Volume Progression Chart
                    if (chronologicalSessions.isNotEmpty()) {
                        item {
                            VolumeChartCard(
                                sessions = chronologicalSessions,
                                weightUnit = weightUnit,
                                formatWeight = viewModel::formatWeight,
                            )
                        }
                    }

                    // History Header
                    item {
                        Text(
                            "HISTORY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = Spacing.medium),
                        )
                    }

                    // History List
                    if (exerciseSessions.isEmpty()) {
                        item {
                            Text(
                                "No workout history for this exercise.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(exerciseSessions, key = { it.id }) { session ->
                            SessionHistoryRow(
                                session = session,
                                weightUnit = weightUnit,
                                formatWeight = viewModel::formatWeight,
                            )
                        }
                    }
                } else {
                    // Table view
                    item {
                        ExerciseHistoryTable(
                            sessions = exerciseSessions,
                            weightUnit = weightUnit,
                            formatWeight = viewModel::formatWeight,
                        )
                    }
                }

                // Bottom padding
                item {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun OneRepMaxCard(
    state: ExerciseDetailOneRepMaxState,
    previousSessionOneRepMax: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    val resolution = (state as? ExerciseDetailOneRepMaxState.Ready)?.resolution
    val delta = if (
        resolution?.source == CurrentOneRepMaxSource.SESSION &&
        previousSessionOneRepMax != null
    ) {
        resolution.perCableKg - previousSessionOneRepMax
    } else {
        null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ESTIMATED 1RM",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(8.dp))

            when {
                resolution != null -> {
                    Text(
                        formatWeight(resolution.perCableKg, weightUnit),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        when (resolution.source) {
                            CurrentOneRepMaxSource.VELOCITY -> "Velocity profile"
                            CurrentOneRepMaxSource.ASSESSMENT -> "Strength assessment"
                            CurrentOneRepMaxSource.SESSION -> "Recent session"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )

                    if (delta != null && delta != 0f) {
                        Spacer(Modifier.height(8.dp))
                        val isPositive = delta > 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isPositive) {
                                    Icons.AutoMirrored.Filled.TrendingUp
                                } else {
                                    Icons.AutoMirrored.Filled.TrendingDown
                                },
                                contentDescription = null,
                                tint = if (isPositive) AccessibilityTheme.colors.success else AccessibilityTheme.colors.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${if (isPositive) "+" else ""}${formatWeight(delta, weightUnit)} from last",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isPositive) AccessibilityTheme.colors.success else AccessibilityTheme.colors.error,
                            )
                        }
                    }
                }

                state is ExerciseDetailOneRepMaxState.Failed -> Text(
                    "Unable to load 1RM",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )

                state is ExerciseDetailOneRepMaxState.Loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )

                else -> Text(
                    "No data",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun ProgressionChartCard(data: List<Pair<Long, Float>>, weightUnit: WeightUnit, formatWeight: (Float, WeightUnit) -> String) {
    var selectedTimeRange by remember { mutableStateOf(TimeRange.DAYS_90) }

    // Filter data by time range
    val filteredData = remember(data, selectedTimeRange) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val cutoff = when (selectedTimeRange) {
            TimeRange.DAYS_30 -> now - 30L * 24 * 60 * 60 * 1000
            TimeRange.DAYS_90 -> now - 90L * 24 * 60 * 60 * 1000
            TimeRange.YEAR_1 -> now - 365L * 24 * 60 * 60 * 1000
            TimeRange.ALL -> 0L
        }
        data.filter { it.first >= cutoff }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                "1RM PROGRESSION",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))

            if (filteredData.size >= 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.extraSmall,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ProgressionLineChart(
                            data = filteredData,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Time range selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedTimeRange == range,
                        onClick = { selectedTimeRange = range },
                        label = { Text(range.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeToggle(viewMode: String, onViewModeChange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        SegmentedButton(
            selected = viewMode == "charts",
            onClick = { onViewModeChange("charts") },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {
                SegmentedButtonDefaults.Icon(active = viewMode == "charts") {
                    Icon(
                        Icons.Default.BarChart,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                }
            },
        ) {
            Text(stringResource(Res.string.label_charts))
        }
        SegmentedButton(
            selected = viewMode == "table",
            onClick = { onViewModeChange("table") },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {
                SegmentedButtonDefaults.Icon(active = viewMode == "table") {
                    Icon(
                        Icons.Default.TableChart,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                }
            },
        ) {
            Text(stringResource(Res.string.label_table))
        }
    }
}

@Composable
private fun WeightTrendChartCard(data: List<Pair<Long, Float>>, weightUnit: WeightUnit, formatWeight: (Float, WeightUnit) -> String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "WEIGHT TREND",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.shapes.extraSmall,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ProgressionLineChart(
                    data = data,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    lineColor = MaterialTheme.colorScheme.tertiary,
                    fillColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun VolumeChartCard(sessions: List<WorkoutSession>, weightUnit: WeightUnit, formatWeight: (Float, WeightUnit) -> String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "VOLUME",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))

            VolumeTrendChart(
                workoutSessions = sessions,
                weightUnit = weightUnit,
                formatWeight = formatWeight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ExerciseHistoryTable(sessions: List<WorkoutSession>, weightUnit: WeightUnit, formatWeight: (Float, WeightUnit) -> String) {
    if (sessions.isEmpty()) {
        Text(
            "No workout history for this exercise.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "SESSION DATA",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(12.dp))

            // Horizontally scrollable to handle narrow screens
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                // Header row
                Row(
                    modifier = Modifier
                        .widthIn(min = 500.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    TableHeaderCell("Date", Modifier.weight(1.5f))
                    TableHeaderCell("Weight", Modifier.weight(1f))
                    TableHeaderCell("Reps", Modifier.weight(0.7f))
                    TableHeaderCell("Volume", Modifier.weight(1f))
                    TableHeaderCell("1RM", Modifier.weight(1f))
                    TableHeaderCell("Mode", Modifier.weight(1f))
                }

                // Data rows
                sessions.forEachIndexed { index, session ->
                    val bgColor = if (index % 2 == 0) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                    val bottomShape = if (index == sessions.lastIndex) {
                        RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                    } else {
                        RoundedCornerShape(0.dp)
                    }

                    Row(
                        modifier = Modifier
                            .widthIn(min = 500.dp)
                            .background(bgColor, bottomShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TableCell(
                            KmpUtils.formatTimestamp(session.timestamp, "MMM dd, yyyy"),
                            Modifier.weight(1.5f),
                        )
                        TableCell(
                            WeightDisplayFormatter.formatDisplayWeight(
                                session.weightPerCableKg,
                                null,
                                weightUnit,
                            ),
                            Modifier.weight(1f),
                        )
                        TableCell(
                            session.workingReps.toString(),
                            Modifier.weight(0.7f),
                        )
                        TableCell(
                            formatWeight(session.effectiveTotalVolumeKg(), weightUnit),
                            Modifier.weight(1f),
                        )
                        TableCell(
                            session.estimatedOneRepMaxPerCableOrNull()
                                ?.let { formatWeight(it, weightUnit) }
                                ?: "-",
                            Modifier.weight(1f),
                        )
                        TableCell(
                            session.mode,
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun TableCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun SessionHistoryRow(session: WorkoutSession, weightUnit: WeightUnit, formatWeight: (Float, WeightUnit) -> String) {
    var isExpanded by remember { mutableStateOf(false) }

    ExpressiveCard(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        KmpUtils.formatTimestamp(session.timestamp, "MMM dd, yyyy"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${WeightDisplayFormatter.formatDisplayWeight(session.weightPerCableKg, null, weightUnit)} × ${session.workingReps} reps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Expanded details
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DetailItem("Mode", session.mode)
                    DetailItem("Total Reps", session.workingReps.toString())
                    DetailItem("Duration", formatDuration(session.duration))
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Helpers

private enum class TimeRange(val label: String) {
    DAYS_30("30d"),
    DAYS_90("90d"),
    YEAR_1("1y"),
    ALL("All"),
}

private fun formatDuration(durationMs: Long): String {
    val minutes = durationMs / 60000
    return if (minutes > 0) "${minutes}min" else "<1min"
}

package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.effectiveHeaviestKgPerCable
import com.devil.phoenixproject.domain.model.toSetSummary
import com.devil.phoenixproject.presentation.components.BiomechanicsHistorySummary
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.DestructiveConfirmDialog
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.MiniExercisePickerDialog
import com.devil.phoenixproject.presentation.components.WorkoutHistoryCardSkeleton
import com.devil.phoenixproject.presentation.components.RepBiomechanicsDetail
import com.devil.phoenixproject.presentation.components.RepReplayCard
import com.devil.phoenixproject.presentation.components.charts.HistoryTimePeriod
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.lazyColumnKey
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_delete
import vitruvianprojectphoenix.shared.generated.resources.cd_delete_routine
import vitruvianprojectphoenix.shared.generated.resources.cd_delete_workout
import vitruvianprojectphoenix.shared.generated.resources.cd_workout_session_icon
import vitruvianprojectphoenix.shared.generated.resources.delete_all_sets
import vitruvianprojectphoenix.shared.generated.resources.delete_routine_session_message
import vitruvianprojectphoenix.shared.generated.resources.delete_routine_session_title
import vitruvianprojectphoenix.shared.generated.resources.delete_workout_message
import vitruvianprojectphoenix.shared.generated.resources.delete_workout_title
import vitruvianprojectphoenix.shared.generated.resources.detailed_metrics_not_captured
import vitruvianprojectphoenix.shared.generated.resources.empty_no_history_all
import vitruvianprojectphoenix.shared.generated.resources.empty_no_history_period
import vitruvianprojectphoenix.shared.generated.resources.empty_no_history_title
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_inline_context
import vitruvianprojectphoenix.shared.generated.resources.equipment_rack_title

private val historyRackJson = Json { ignoreUnknownKeys = true }

@Composable
fun HistoryTab(
    groupedWorkoutHistory: List<HistoryItem>,
    isLoading: Boolean = false,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onDeleteWorkout: (String) -> Unit,
    onDeleteRoutineGroup: (String) -> Unit,
    exerciseRepository: ExerciseRepository,
    onTagJustLiftSessionExercise: suspend (String, Exercise, Boolean) -> Unit = { _, _, _ -> },
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE") // Kept for future pull-to-refresh implementation
    var isRefreshing by remember { mutableStateOf(false) }

    // M8: Hoist koinInject calls to parent composable scope (outside LazyColumn items).
    // Calling koinInject inside lazy item lambdas can cause stale scope issues during recomposition.
    val repMetricRepository: RepMetricRepository = koinInject()

    var selectedPeriod by remember { mutableStateOf(HistoryTimePeriod.ALL) }

    // Filter history items by selected time period
    val filteredHistory = remember(groupedWorkoutHistory, selectedPeriod) {
        if (selectedPeriod == HistoryTimePeriod.ALL) {
            groupedWorkoutHistory
        } else {
            val now = Instant.fromEpochMilliseconds(currentTimeMillis())
            val cutoff = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
                .let { today ->
                    when (selectedPeriod) {
                        HistoryTimePeriod.DAYS_7 -> today.minus(7, DateTimeUnit.DAY)
                        HistoryTimePeriod.DAYS_14 -> today.minus(14, DateTimeUnit.DAY)
                        HistoryTimePeriod.DAYS_30 -> today.minus(30, DateTimeUnit.DAY)
                        HistoryTimePeriod.ALL -> today // unreachable
                    }
                }
            val cutoffEpoch = cutoff.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            groupedWorkoutHistory.filter { it.timestamp >= cutoffEpoch }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.medium),
    ) {
        // Time period filter chips — scrollable for Bold Text accessibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryTimePeriod.entries.forEach { period ->
                FilterChip(
                    selected = selectedPeriod == period,
                    onClick = { selectedPeriod = period },
                    label = {
                        Text(
                            period.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }

        if (isLoading) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(3) {
                    WorkoutHistoryCardSkeleton(modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (filteredHistory.isEmpty()) {
            EmptyState(
                icon = Icons.Default.History,
                title = stringResource(Res.string.empty_no_history_title),
                message = if (selectedPeriod == HistoryTimePeriod.ALL) {
                    stringResource(Res.string.empty_no_history_all)
                } else {
                    stringResource(Res.string.empty_no_history_period, selectedPeriod.label.lowercase())
                },
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(filteredHistory.size, key = { index ->
                    filteredHistory[index].lazyColumnKey
                }) { index ->
                    when (val item = filteredHistory[index]) {
                        is com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem -> {
                            WorkoutHistoryCard(
                                session = item.session,
                                weightUnit = weightUnit,
                                formatWeight = formatWeight,
                                kgToDisplay = kgToDisplay,
                                exerciseRepository = exerciseRepository,
                                repMetricRepository = repMetricRepository,
                                onTagJustLiftSessionExercise = onTagJustLiftSessionExercise,
                                onDelete = { onDeleteWorkout(item.session.id) },
                            )
                        }

                        is com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem -> {
                            GroupedRoutineCard(
                                groupedItem = item,
                                weightUnit = weightUnit,
                                formatWeight = formatWeight,
                                kgToDisplay = kgToDisplay,
                                exerciseRepository = exerciseRepository,
                                repMetricRepository = repMetricRepository,
                                onTagJustLiftSessionExercise = onTagJustLiftSessionExercise,
                                // Issue #591 follow-up: thread the
                                // routine-level delete callback so the
                                // History "Delete All Sets" path also
                                // cleans up zero-rep ghost rows hidden by
                                // the History filter. The single-session
                                // path above still uses onDeleteWorkout.
                                onDeleteRoutineGroup = onDeleteRoutineGroup,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryCard(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,
    exerciseRepository: com.devil.phoenixproject.data.repository.ExerciseRepository,
    repMetricRepository: RepMetricRepository,
    onTagJustLiftSessionExercise: suspend (String, Exercise, Boolean) -> Unit = { _, _, _ -> },
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var showExerciseTagPicker by remember { mutableStateOf(false) }
    // analytics-history-13: gate all animations on reduceMotion
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringSnappy,
        label = "chevron",
    )

    // Get exercise name from session (no DB lookup needed!)
    val exerciseName = session.exerciseName ?: if (session.isJustLift) "Just Lift" else "Unknown Exercise"

    // Retroactive Just Lift tagging is only offered for untagged Just Lift sessions.
    // Once tagged, exerciseId is non-null so the affordance disappears and the name shows.
    val canTagJustLift = session.isJustLift && session.exerciseId.isNullOrBlank()

    ExpressiveCard(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Header: "Single Exercise" with chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Single Exercise",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Exercise Name (or "Just Lift" if Just Lift mode)
            Text(
                exerciseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Date and Time (no label, just the timestamp)
            Text(
                formatTimestamp(session.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Total Reps | Total Sets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Total Reps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    session.totalReps.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Total Sets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (session.reps == 0) {
                        "1" // AMRAP = single set with variable reps
                    } else if (session.workingReps > 0) {
                        (session.workingReps / session.reps.coerceAtLeast(1)).toString()
                    } else {
                        "0"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Measured Peak | Workout Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Measured Peak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (session.mode.contains("Echo", ignoreCase = true)) {
                        "Adaptive"
                    } else {
                        WeightDisplayFormatter.formatDisplayWeight(
                            session.effectiveHeaviestKgPerCable(),
                            null,
                            weightUnit,
                        ) + " ${if (weightUnit == WeightUnit.LB) "lbs" else "kg"}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Workout Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    session.mode,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            RackContextRow(
                session = session,
                weightUnit = weightUnit,
                formatWeight = formatWeight,
            )

            // Expandable summary section
            AnimatedVisibility(
                visible = isExpanded,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
            ) {
                Column(
                    modifier = Modifier.padding(top = Spacing.medium),
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    val summary = session.toSetSummary()
                    if (summary != null) {
                        SetSummaryCard(
                            summary = summary,
                            workoutMode = session.mode,
                            weightUnit = weightUnit,
                            kgToDisplay = kgToDisplay,
                            formatWeight = formatWeight,
                            onContinue = { },
                            autoplayEnabled = false,
                            summaryCountdownSeconds = 0, // History view - no auto-continue
                            isHistoryView = true,
                            savedRpe = session.rpe,
                            // Retroactive Just Lift tagging (untagged Just Lift sessions only)
                            isJustLiftTaggingEnabled = canTagJustLift,
                            onTagExerciseClick = if (canTagJustLift) {
                                { showExerciseTagPicker = true }
                            } else {
                                null
                            },
                        )

                        if (showExerciseTagPicker) {
                            MiniExercisePickerDialog(
                                exerciseRepository = exerciseRepository,
                                onDismiss = { showExerciseTagPicker = false },
                                onExerciseSelected = { exercise ->
                                    showExerciseTagPicker = false
                                    scope.launch {
                                        // No amrap flag is persisted on a saved session, so default
                                        // to false (STANDARD set type) for retroactive tagging.
                                        onTagJustLiftSessionExercise(session.id, exercise, false)
                                    }
                                },
                            )
                        }
                    } else {
                        // Pre-v0.2.1 session - show message
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(Spacing.small))
                                // Issue #591: see per-set drill-down note —
                                // frame this honestly instead of blaming the
                                // app version.
                                Text(
                                    stringResource(Res.string.detailed_metrics_not_captured),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // CompletedSet breakdown (set-level tracking)
                    CompletedSetsSection(
                        sessionId = session.id,
                        cableCount = null,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                    )

                    // Rep Details Section
                    RepDetailsSection(
                        sessionId = session.id,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight,
                        repMetricRepository = repMetricRepository,
                    )

                    // Biomechanics Section (v0.5.0+, tier-gated)
                    BiomechanicsSection(session = session)
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Divider
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.height(48.dp), // Material 3 Expressive: Taller button
                    shape = MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_workout),
                        modifier = Modifier.size(20.dp), // Material 3 Expressive: Larger icon (was 18dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(Res.string.action_delete),
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    // Material 3 Expressive: Delete dialog
    if (showDeleteDialog) {
        DestructiveConfirmDialog(
            title = stringResource(Res.string.delete_workout_title),
            message = stringResource(Res.string.delete_workout_message),
            confirmText = stringResource(Res.string.action_delete),
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * Shows completed set breakdown for a workout session.
 * Only renders if CompletedSet records exist for the session.
 *
 * Note: CompletedSet.actualWeightKg stores per-cable weight (populated from
 * WorkoutParameters.weightPerCableKg in ActiveSessionEngine). We use
 * WeightDisplayFormatter for unit conversion only; ordinary display stays per-cable.
 */
@Composable
private fun CompletedSetsSection(
    sessionId: String,
    cableCount: Int?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    val completedSetRepository: CompletedSetRepository = koinInject()
    var completedSets by remember { mutableStateOf<List<CompletedSet>>(emptyList()) }

    LaunchedEffect(sessionId) {
        try {
            completedSets = completedSetRepository.getCompletedSets(sessionId)
        } catch (e: Exception) {
            co.touchlab.kermit.Logger.e("HistoryTab") { "Failed to load completed sets for session $sessionId: ${e.message}" }
            completedSets = emptyList()
        }
    }

    if (completedSets.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.medium))

        Text(
            "Set Breakdown",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        completedSets.forEach { set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Set number and type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Set ${set.setNumber + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (set.setType.name != "STANDARD") {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            set.setType.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (set.isPr) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "PR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Reps x Weight + RPE
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // actualWeightKg is per-cable; ordinary display stays per-cable.
                    val displayWeight = WeightDisplayFormatter.formatDisplayWeight(
                        set.actualWeightKg,
                        cableCount,
                        weightUnit,
                    )
                    val unitLabel = weightUnit.name.lowercase()
                    Text(
                        "${set.actualReps} x $displayWeight $unitLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    set.loggedRpe?.let { rpe ->
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            "RPE $rpe",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows per-rep replay cards with force sparklines for a workout session.
 * Only renders if RepMetricData records exist for the session.
 */
@Composable
private fun RepDetailsSection(
    sessionId: String,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    repMetricRepository: RepMetricRepository,
) {
    var repMetrics by remember { mutableStateOf<List<RepMetricData>>(emptyList()) }

    // M8: Wrap DB call in try/catch to prevent crashes from corrupted or missing data
    LaunchedEffect(sessionId) {
        try {
            repMetrics = repMetricRepository.getRepMetrics(sessionId)
        } catch (e: Exception) {
            co.touchlab.kermit.Logger.e("HistoryTab") { "Failed to load rep metrics for session $sessionId: ${e.message}" }
            repMetrics = emptyList()
        }
    }

    if (repMetrics.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.medium))

        Text(
            "Rep Details",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            repMetrics.forEach { rep ->
                RepReplayCard(
                    repData = rep,
                    weightUnit = weightUnit,
                    formatWeight = formatWeight,
                )
            }
        }
    }
}

/**
 * Data class to hold grouped exercise information within a routine
 */
private data class ExerciseGroup(
    val exerciseId: String,
    val exerciseName: String,
    val totalReps: Int,
    val totalSets: Int,
    val highestWeightPerCableKg: Float,
    val mode: String,
)

/**
 * Card showing a grouped routine session with multiple exercises
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedRoutineCard(
    groupedItem: com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,
    exerciseRepository: com.devil.phoenixproject.data.repository.ExerciseRepository,
    repMetricRepository: RepMetricRepository,
    onTagJustLiftSessionExercise: suspend (String, Exercise, Boolean) -> Unit = { _, _, _ -> },
    // Issue #591 follow-up: receives routineSessionId so the caller can
    // soft-delete every WorkoutSession row for the routine (including
    // zero-rep ghost rows hidden by `getHistoryVisibleSessions`).
    onDeleteRoutineGroup: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    // Tracks which session's exercise-tag picker is open (null = none).
    // A nullable session id is used because multiple sessions render in the loop below.
    var taggingSessionId by remember { mutableStateOf<String?>(null) }
    // analytics-history-13: gate all animations on reduceMotion
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringSnappy,
        label = "chevron",
    )

    // Group sessions by exerciseId and use exerciseName directly (no DB lookup needed!)
    val exercisesWithNames = remember(groupedItem.sessions) {
        groupedItem.sessions.groupBy { it.exerciseId ?: "just_lift" }
            .map { (exerciseId, sessions) ->
                val totalReps = sessions.sumOf { it.totalReps }
                val totalSets = sessions.size
                val highestWeightPerCableKg = sessions.maxOfOrNull { it.effectiveHeaviestKgPerCable() } ?: 0f
                val mode = sessions.firstOrNull()?.mode ?: "Unknown"
                // Use exerciseName from the session (stored when workout was saved)
                val exerciseName = sessions.firstOrNull()?.exerciseName ?: "Unknown Exercise"

                ExerciseGroup(
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    totalReps = totalReps,
                    totalSets = totalSets,
                    highestWeightPerCableKg = highestWeightPerCableKg,
                    mode = mode,
                )
            }
    }

    ExpressiveCard(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.medium),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
        ) {
            // Header: "Daily Routine" with chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Daily Routine",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Routine Name
            Text(
                groupedItem.routineName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Date and Time (no label, just the timestamp)
            Text(
                formatTimestamp(groupedItem.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Display each exercise group
            exercisesWithNames.forEachIndexed { index, exerciseGroup ->
                // Exercise Name
                Text(
                    exerciseGroup.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Total Reps | Total Sets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Total Reps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        exerciseGroup.totalReps.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Total Sets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        exerciseGroup.totalSets.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Measured Peak | Workout Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Measured Peak",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (exerciseGroup.mode.contains("Echo", ignoreCase = true)) {
                            "Adaptive"
                        } else {
                            WeightDisplayFormatter.formatDisplayWeight(
                                exerciseGroup.highestWeightPerCableKg,
                                null,
                                weightUnit,
                            ) + " ${if (weightUnit == WeightUnit.LB) "lbs" else "kg"}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Workout Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        exerciseGroup.mode,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Add spacing between exercises (except for the last one)
                if (index < exercisesWithNames.size - 1) {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.medium))
                }
            }

            // Expandable summary section
            AnimatedVisibility(
                visible = isExpanded,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
            ) {
                Column(modifier = Modifier.padding(top = Spacing.medium)) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    Text(
                        "Detailed Set Metrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))

                    groupedItem.sessions.forEachIndexed { index, session ->
                        val summary = session.toSetSummary()

                        Text(
                            session.exerciseName ?: "Unknown Exercise",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        RackContextRow(
                            session = session,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                        )

                        val canTagJustLift = session.isJustLift && session.exerciseId.isNullOrBlank()

                        if (summary != null) {
                            SetSummaryCard(
                                summary = summary,
                                workoutMode = session.mode,
                                weightUnit = weightUnit,
                                kgToDisplay = kgToDisplay,
                                formatWeight = formatWeight,
                                onContinue = { },
                                autoplayEnabled = false,
                                summaryCountdownSeconds = 0, // History view - no auto-continue
                                isHistoryView = true,
                                savedRpe = session.rpe,
                                // Retroactive Just Lift tagging (untagged Just Lift sessions only)
                                isJustLiftTaggingEnabled = canTagJustLift,
                                onTagExerciseClick = if (canTagJustLift) {
                                    { taggingSessionId = session.id }
                                } else {
                                    null
                                },
                            )

                            if (taggingSessionId == session.id) {
                                MiniExercisePickerDialog(
                                    exerciseRepository = exerciseRepository,
                                    onDismiss = { taggingSessionId = null },
                                    onExerciseSelected = { exercise ->
                                        taggingSessionId = null
                                        scope.launch {
                                            // No amrap flag is persisted on a saved session, so
                                            // default to false (STANDARD set type) when retro-tagging.
                                            onTagJustLiftSessionExercise(session.id, exercise, false)
                                        }
                                    },
                                )
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(Spacing.small))
                                    // Issue #591: The "after v0.2.1" copy is
                                    // misleading for current sessions whose
                                    // metric columns were nulled by a sync pull
                                    // or by a zero-rep save. History now also
                                    // filters zero-rep rows out of routine
                                    // grouping so this card only appears for
                                    // real legacy / unmeasured rows. Frame it
                                    // honestly: this set's detailed metrics
                                    // were not captured for this device, not
                                    // "you need a newer app".
                                    Text(
                                        stringResource(Res.string.detailed_metrics_not_captured),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // CompletedSet breakdown per session
                        CompletedSetsSection(
                            sessionId = session.id,
                            cableCount = null,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                        )

                        // Rep Details Section per session
                        RepDetailsSection(
                            sessionId = session.id,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            repMetricRepository = repMetricRepository,
                        )

                        // Biomechanics Section per session (v0.5.0+, tier-gated)
                        BiomechanicsSection(session = session)

                        if (index < groupedItem.sessions.size - 1) {
                            Spacer(modifier = Modifier.height(Spacing.medium))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Divider
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.height(48.dp), // Material 3 Expressive: Taller button
                    shape = MaterialTheme.shapes.medium, // Material 3 Expressive: More rounded (was 16dp)
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_routine),
                        modifier = Modifier.size(20.dp), // Material 3 Expressive: Larger icon (was 18dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(Res.string.delete_all_sets),
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    // Material 3 Expressive: Delete dialog
    if (showDeleteDialog) {
        DestructiveConfirmDialog(
            title = stringResource(Res.string.delete_routine_session_title),
            message = stringResource(Res.string.delete_routine_session_message, groupedItem.sessions.size),
            confirmText = stringResource(Res.string.action_delete),
            onConfirm = {
                // Issue #591 follow-up: delete the whole routine
                // session by id so zero-rep ghost rows hidden by
                // the History filter are cleaned up too. Looping
                // over `groupedItem.sessions` would only delete
                // the visible rows.
                onDeleteRoutineGroup(groupedItem.routineSessionId)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * Compact version of WorkoutHistoryCard for displaying within the expanded GroupedRoutineCard
 */
@Composable
fun WorkoutSessionCard(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    exerciseRepository: com.devil.phoenixproject.data.repository.ExerciseRepository,
    onDelete: () -> Unit,
) {
    var exerciseName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.exerciseId) {
        val id = session.exerciseId
        exerciseName = if (id != null) exerciseRepository.getExerciseById(id)?.name else null
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    exerciseName ?: "Just Lift",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${WeightDisplayFormatter.formatDisplayWeight(session.weightPerCableKg, null, weightUnit)} ${if (weightUnit == WeightUnit.LB) "lbs" else "kg"}/cable • ${session.totalReps} reps • ${session.mode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                session.rackContextText(weightUnit, formatWeight)?.let { rackText ->
                    Text(
                        stringResource(Res.string.equipment_rack_inline_context, rackText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatDuration(session.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RackContextRow(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    val rackContext = session.rackContextText(weightUnit, formatWeight) ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(Res.string.equipment_rack_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            rackContext,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun WorkoutSession.rackContextText(
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
): String? {
    val snapshotItems = decodeRackSnapshotItems()
    if (snapshotItems.isNotEmpty()) {
        val visibleItems = snapshotItems.take(2).map { item ->
            when (item.behavior) {
                RackItemBehavior.ADDED_RESISTANCE -> "${item.name} +${formatWeight(item.weightKg, weightUnit)}"
                RackItemBehavior.COUNTERWEIGHT -> "${item.name} -${formatWeight(item.weightKg, weightUnit)}"
                RackItemBehavior.DISPLAY_ONLY -> item.name
            }
        }
        val hiddenCount = snapshotItems.size - visibleItems.size
        return if (hiddenCount > 0) {
            visibleItems.joinToString(", ") + " +$hiddenCount"
        } else {
            visibleItems.joinToString(", ")
        }
    }

    val fallbackParts = buildList {
        if (externalAddedLoadKg > 0f) add("+${formatWeight(externalAddedLoadKg, weightUnit)}")
        if (counterweightKg > 0f) add("-${formatWeight(counterweightKg, weightUnit)}")
    }
    return fallbackParts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

private fun WorkoutSession.decodeRackSnapshotItems(): List<RackItem> {
    if (rackItemsJson.isBlank() || rackItemsJson == "[]") return emptyList()
    return try {
        historyRackJson.decodeFromString<List<RackItem>>(rackItemsJson)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EnhancedMetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(Res.string.cd_workout_session_icon),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(Spacing.extraSmall))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Biomechanics section for a single workout session.
 *
 * Guards against older sessions without biomechanics data (shows nothing).
 * Per-rep biomechanics data is lazy-loaded only when user expands the per-rep section
 * to avoid deserializing 101-point force curves for every session in the list.
 */
@Composable
private fun BiomechanicsSection(session: WorkoutSession) {
    // Only show for sessions that have biomechanics data (v0.5.0+)
    if (!session.hasBiomechanicsData) return

    Spacer(modifier = Modifier.height(Spacing.medium))

    var isRepBiomechanicsExpanded by remember { mutableStateOf(false) }
    // analytics-history-13: gate per-rep expand animation on reduceMotion
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion

    BiomechanicsHistorySummary(
        avgMcvMmS = session.avgMcvMmS,
        avgAsymmetryPercent = session.avgAsymmetryPercent,
        totalVelocityLossPercent = session.totalVelocityLossPercent,
        dominantSide = session.dominantSide,
        strengthProfile = session.strengthProfile,
        onExpandReps = { isRepBiomechanicsExpanded = !isRepBiomechanicsExpanded },
    )

    // Per-rep detail (lazy-loaded only when expanded)
    AnimatedVisibility(
        visible = isRepBiomechanicsExpanded,
        enter = if (reduceMotion) EnterTransition.None else expandVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
        exit = if (reduceMotion) ExitTransition.None else shrinkVertically(animationSpec = ExpressiveMotion.SpringDefaultIntSize),
    ) {
        val biomechanicsRepository: BiomechanicsRepository = koinInject()
        var repBiomechanics by remember { mutableStateOf<List<BiomechanicsRepResult>>(emptyList()) }
        var isLoadingBiomechanics by remember { mutableStateOf(true) }

        LaunchedEffect(session.id) {
            isLoadingBiomechanics = true
            try {
                repBiomechanics = biomechanicsRepository.getRepBiomechanics(session.id)
            } catch (e: Exception) {
                co.touchlab.kermit.Logger.e("HistoryTab") { "Failed to load biomechanics for session ${session.id}: ${e.message}" }
                repBiomechanics = emptyList()
            } finally {
                isLoadingBiomechanics = false
            }
        }

        RepBiomechanicsDetail(
            repResults = repBiomechanics,
            isLoading = isLoadingBiomechanics,
            showAsymmetry = true,
            showForceCurves = true,
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    // Format as "MMM dd, yyyy at HH:mm"
    val date = KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")
    val time = KmpUtils.formatTimestamp(timestamp, "HH:mm")
    return "$date at $time"
}

@Suppress("unused") // Available for future UI enhancements
private fun formatRelativeTimestamp(timestamp: Long): String = KmpUtils.formatRelativeTimestamp(timestamp)

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

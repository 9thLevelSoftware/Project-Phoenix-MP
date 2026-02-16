package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.effectiveHeaviestKgPerCable
import com.devil.phoenixproject.domain.model.toSetSummary
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.ui.theme.*
import com.devil.phoenixproject.util.KmpUtils
import org.koin.compose.koinInject

@Composable
fun HistoryTab(
    groupedWorkoutHistory: List<HistoryItem>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,
    onDeleteWorkout: (String) -> Unit,
    exerciseRepository: ExerciseRepository,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE")  // Kept for future pull-to-refresh implementation
    var isRefreshing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(Spacing.medium)
    ) {
        // Header removed for global scaffold integration
        // Refresh functionality should be handled automatically or via pull-to-refresh if needed

        /*
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Workout History",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = {
                    isRefreshing = true
                    onRefresh()
                    kotlinx.coroutines.MainScope().launch {
                        kotlinx.coroutines.delay(1000)
                        isRefreshing = false
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh workout history",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = if (isRefreshing) {
                        Modifier.rotate(360f)
                    } else {
                        Modifier
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.medium))
        */

        if (groupedWorkoutHistory.isEmpty()) {
            EmptyState(
                icon = Icons.Default.History,
                title = "No Workout History Yet",
                message = "Complete your first workout to see it here"
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                items(groupedWorkoutHistory.size, key = { index ->
                    when (val item = groupedWorkoutHistory[index]) {
                        is com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem -> item.session.id
                        is com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem -> item.routineSessionId
                    }
                }) { index ->
                    when (val item = groupedWorkoutHistory[index]) {
                        is com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem -> {
                            WorkoutHistoryCard(
                                session = item.session,
                                weightUnit = weightUnit,
                                formatWeight = formatWeight,
                                kgToDisplay = kgToDisplay,
                                exerciseRepository = exerciseRepository,
                                onDelete = { onDeleteWorkout(item.session.id) }
                            )
                        }
                        is com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem -> {
                            GroupedRoutineCard(
                                groupedItem = item,
                                weightUnit = weightUnit,
                                formatWeight = formatWeight,
                                kgToDisplay = kgToDisplay,
                                exerciseRepository = exerciseRepository,
                                onDelete = { sessionId -> onDeleteWorkout(sessionId) }
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
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron"
    )

    // Get exercise name from session (no DB lookup needed!)
    val exerciseName = session.exerciseName ?: if (session.isJustLift) "Just Lift" else "Unknown Exercise"

    Card(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Header: "Single Exercise" with chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Single Exercise",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Exercise Name (or "Just Lift" if Just Lift mode)
            Text(
                exerciseName ?: "Just Lift",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Date and Time (no label, just the timestamp)
            Text(
                formatTimestamp(session.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Total Reps | Total Sets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Total Reps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    session.totalReps.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Total Sets",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (session.reps == 0) "1" // AMRAP = single set with variable reps
                    else if (session.workingReps > 0) (session.workingReps / session.reps.coerceAtLeast(1)).toString()
                    else "0",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Measured Peak Per Cable | Workout Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Measured Peak Per Cable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (session.mode.contains("Echo", ignoreCase = true)) "Adaptive" else formatWeight(session.effectiveHeaviestKgPerCable(), weightUnit),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Workout Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    session.mode,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Expandable summary section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = Spacing.medium)
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
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
                            summaryCountdownSeconds = 0,  // History view - no auto-continue
                            isHistoryView = true,
                            savedRpe = session.rpe
                        )
                    } else {
                        // Pre-v0.2.1 session - show message
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.medium),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(Spacing.small))
                                Text(
                                    "Detailed metrics available for workouts after v0.2.1",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // CompletedSet breakdown (set-level tracking)
                    CompletedSetsSection(
                        sessionId = session.id,
                        weightUnit = weightUnit,
                        formatWeight = formatWeight
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Divider
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.height(48.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete workout",
                        modifier = Modifier.size(20.dp) // Material 3 Expressive: Larger icon (was 18dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Material 3 Expressive: Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    "Delete Workout?",
                    style = MaterialTheme.typography.headlineSmall, // Material 3 Expressive: Larger
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This action cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge // Material 3 Expressive: Larger
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, // Material 3 Expressive: Higher contrast
            shape = RoundedCornerShape(28.dp), // Material 3 Expressive: Very rounded for dialogs (was 16dp)
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/**
 * Shows completed set breakdown for a workout session.
 * Only renders if CompletedSet records exist for the session.
 */
@Composable
private fun CompletedSetsSection(
    sessionId: String,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String
) {
    val completedSetRepository: CompletedSetRepository = koinInject()
    var completedSets by remember { mutableStateOf<List<CompletedSet>>(emptyList()) }

    LaunchedEffect(sessionId) {
        completedSets = completedSetRepository.getCompletedSets(sessionId)
    }

    if (completedSets.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.medium))

        Text(
            "Set Breakdown",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        completedSets.forEach { set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Set number and type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Set ${set.setNumber + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (set.setType.name != "STANDARD") {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            set.setType.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (set.isPr) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "PR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Reps x Weight + RPE
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${set.actualReps} x ${formatWeight(set.actualWeightKg, weightUnit)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    set.loggedRpe?.let { rpe ->
                        Spacer(modifier = Modifier.width(Spacing.small))
                        Text(
                            "RPE $rpe",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
    val mode: String
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
    onDelete: (String) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron"
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
                    mode = mode
                )
            }
    }

    Card(
        onClick = { isExpanded = !isExpanded },
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)), // Material 3 Expressive: More shadow, more rounded
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), // Material 3 Expressive: Higher contrast
        shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), // Material 3 Expressive: Higher elevation (was 4dp)
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) // Material 3 Expressive: Thicker border (was 1dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Header: "Daily Routine" with chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Daily Routine",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Routine Name
            Text(
                groupedItem.routineName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Date and Time (no label, just the timestamp)
            Text(
                formatTimestamp(groupedItem.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            // Display each exercise group
            exercisesWithNames.forEachIndexed { index, exerciseGroup ->
                // Exercise Name
                Text(
                    exerciseGroup.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Total Reps | Total Sets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Total Reps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        exerciseGroup.totalReps.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Total Sets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        exerciseGroup.totalSets.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.small))

                // Measured Peak Per Cable | Workout Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Measured Peak Per Cable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (exerciseGroup.mode.contains("Echo", ignoreCase = true)) "Adaptive" else formatWeight(exerciseGroup.highestWeightPerCableKg, weightUnit),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Workout Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        exerciseGroup.mode,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Add spacing between exercises (except for the last one)
                if (index < exercisesWithNames.size - 1) {
                    Spacer(modifier = Modifier.height(Spacing.medium))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.medium))
                }
            }

            // Expandable summary section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = Spacing.medium)) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    Text(
                        "Detailed Set Metrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(Spacing.small))

                    groupedItem.sessions.forEachIndexed { index, session ->
                        val summary = session.toSetSummary()

                        Text(
                            session.exerciseName ?: "Unknown Exercise",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(Spacing.small))

                        if (summary != null) {
                            SetSummaryCard(
                                summary = summary,
                                workoutMode = session.mode,
                                weightUnit = weightUnit,
                                kgToDisplay = kgToDisplay,
                                formatWeight = formatWeight,
                                onContinue = { },
                                autoplayEnabled = false,
                                summaryCountdownSeconds = 0,  // History view - no auto-continue
                                isHistoryView = true,
                                savedRpe = session.rpe
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(Spacing.small))
                                    Text(
                                        "Detailed metrics available for workouts after v0.2.1",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // CompletedSet breakdown per session
                        CompletedSetsSection(
                            sessionId = session.id,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight
                        )

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
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(Spacing.small))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.height(48.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp), // Material 3 Expressive: More rounded (was 16dp)
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete routine session",
                        modifier = Modifier.size(20.dp) // Material 3 Expressive: Larger icon (was 18dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Delete All Sets",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Material 3 Expressive: Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    "Delete Routine Session?",
                    style = MaterialTheme.typography.headlineSmall, // Material 3 Expressive: Larger
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "This will delete all ${groupedItem.sessions.size} sets from this routine. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge // Material 3 Expressive: Larger
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, // Material 3 Expressive: Higher contrast
            shape = RoundedCornerShape(28.dp), // Material 3 Expressive: Very rounded for dialogs (was 16dp)
            confirmButton = {
                TextButton(
                    onClick = {
                        // Delete all sessions in the routine
                        groupedItem.sessions.forEach { session ->
                            onDelete(session.id)
                        }
                        showDeleteDialog = false
                    },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.titleLarge, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.height(56.dp), // Material 3 Expressive: Taller button
                    shape = RoundedCornerShape(20.dp) // Material 3 Expressive: More rounded
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium, // Material 3 Expressive: Larger text
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    onDelete: () -> Unit
) {
    var exerciseName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.exerciseId) {
        session.exerciseId?.let { id ->
            exerciseName = exerciseRepository.getExerciseById(id)?.name
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    exerciseName ?: "Just Lift",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${formatWeight(session.weightPerCableKg, weightUnit)}/cable • ${session.totalReps} reps • ${session.mode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                formatDuration(session.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EnhancedMetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = "Workout session icon",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(Spacing.extraSmall))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    // Format as "MMM dd, yyyy at HH:mm"
    val date = KmpUtils.formatTimestamp(timestamp, "MMM dd, yyyy")
    val time = KmpUtils.formatTimestamp(timestamp, "HH:mm")
    return "$date at $time"
}

@Suppress("unused")  // Available for future UI enhancements
private fun formatRelativeTimestamp(timestamp: Long): String {
    return KmpUtils.formatRelativeTimestamp(timestamp)
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import kotlin.math.roundToInt

/**
 * CycleReviewScreen - Shows a collapsible timeline of all days before the user commits the cycle.
 *
 * Design:
 * - Collapsed by default: Day number + routine name + modifier badges
 * - Tap row or expand icon -> Expands to show exercises with sets/reps
 * - Rest days show rest icon and "Rest", no expand option
 * - [Back] -> Returns to editor
 * - [Save Cycle] -> Commits and navigates home
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleReviewScreen(
    cycleName: String,
    days: List<CycleDay>,
    routines: List<Routine>,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    // Track expanded state for each day by id (stable key)
    val expandedDays = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Review Your Cycle",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = cycleName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Save Cycle",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            itemsIndexed(days, key = { _, day -> day.id }) { _, day ->
                val routine = day.routineId?.let { routineId ->
                    routines.find { it.id == routineId }
                }
                val isExpanded = expandedDays[day.id] ?: false

                CycleReviewDayCard(
                    day = day,
                    routine = routine,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        // Only allow expanding for non-rest days with a routine
                        if (!day.isRestDay && routine != null) {
                            expandedDays[day.id] = !isExpanded
                        }
                    }
                )
            }
        }
    }
}

/**
 * Individual day card with expand/collapse functionality.
 */
@Composable
private fun CycleReviewDayCard(
    day: CycleDay,
    routine: Routine?,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val canExpand = !day.isRestDay && routine != null && routine.exercises.isNotEmpty()
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "expandRotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canExpand) { onToggleExpand() },
        colors = CardDefaults.cardColors(
            containerColor = if (day.isRestDay)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header row (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Day number badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (day.isRestDay)
                                MaterialTheme.colorScheme.tertiaryContainer
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${day.dayNumber}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (day.isRestDay)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Day content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Day ${day.dayNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (day.isRestDay) {
                        Text(
                            text = "💤 Rest",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = routine?.name ?: "No routine assigned",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (routine != null)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Modifier badges row
                        val badges = buildModifierBadges(day)
                        if (badges.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                badges.forEach { badge ->
                                    ReviewModifierBadge(text = badge)
                                }
                            }
                        }
                    }
                }

                // Expand icon (only for expandable days)
                if (canExpand) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(rotationAngle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content: Exercise list
            AnimatedVisibility(
                visible = isExpanded && canExpand,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 60.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    routine?.exercises?.forEach { routineExercise ->
                        ExerciseRow(routineExercise = routineExercise)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Single exercise row showing name and sets x reps.
 */
@Composable
private fun ExerciseRow(routineExercise: RoutineExercise) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Exercise name with bullet
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "\u2022",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = routineExercise.exercise.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        // Sets x Reps display
        val setsRepsText = formatSetsReps(routineExercise)
        Text(
            text = setsRepsText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Format sets and reps for display.
 * Examples: "3x10", "4x12", "3x8-10" for varied reps
 */
private fun formatSetsReps(routineExercise: RoutineExercise): String {
    val setReps = routineExercise.setReps
    val sets = setReps.size

    if (sets == 0) return ""

    // Check if all reps are the same
    val nonNullReps = setReps.filterNotNull()
    return if (nonNullReps.isEmpty()) {
        "${sets}x AMRAP"
    } else if (nonNullReps.distinct().size == 1) {
        // All same reps
        "${sets}x${nonNullReps.first()}"
    } else {
        // Different reps - show range or list
        val min = nonNullReps.minOrNull() ?: 0
        val max = nonNullReps.maxOrNull() ?: 0
        if (min != max) {
            "${sets}x$min-$max"
        } else {
            "${sets}x$min"
        }
    }
}

/**
 * Build list of modifier badge strings from day modifiers.
 */
private fun buildModifierBadges(day: CycleDay): List<String> {
    val badges = mutableListOf<String>()

    day.echoLevel?.let { badges.add("Echo:${it.displayName}") }
    day.weightProgressionPercent?.let { pct ->
        val sign = if (pct >= 0) "+" else ""
        badges.add("${sign}${pct.roundToInt()}%")
    }
    day.eccentricLoadPercent?.let { pct ->
        if (pct != 100) {
            badges.add("Eccentric ${pct}%")
        }
    }
    day.repModifier?.let { mod ->
        if (mod != 0) {
            val sign = if (mod > 0) "+" else ""
            badges.add("${sign}${mod} reps")
        }
    }
    day.restTimeOverrideSeconds?.let { sec ->
        // Always show rest time badge if override is set (default is 90s in config sheet)
        badges.add("${sec}s rest")
    }

    return badges
}

/**
 * Modifier badge component for the review screen.
 */
@Composable
private fun ReviewModifierBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.labelAllCaps
import com.devil.phoenixproject.util.KmpUtils

/**
 * Compact, collapsible card showing the last 3–5 sessions for a specific exercise.
 * Shown in SetReadyScreen to give the user quick history context before starting a set.
 *
 * Issue #671: Exercise history quick view during workout.
 */
@Composable
fun ExerciseQuickHistoryCard(
    sessions: List<WorkoutSession>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    val expanded = key(sessions) { remember { mutableStateOf(false) } }
    val visibleSessions = if (expanded.value) sessions else sessions.take(3)
    val canExpand = sessions.size > 3

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(Spacing.small)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (canExpand) {
                            Modifier.clickable { expanded.value = !expanded.value }
                        } else {
                            Modifier
                        }
                    )
                    .semantics {
                        contentDescription = "Recent Sessions, ${if (expanded.value) "expanded" else "collapsed"}"
                        if (canExpand) {
                            stateDescription = if (expanded.value) "Expanded" else "Collapsed"
                            toggleableState = ToggleableState(expanded.value)
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RECENT SESSIONS",
                    style = labelAllCaps,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canExpand) {
                    Icon(
                        imageVector = if (expanded.value) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded.value) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Column headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Weight",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Reps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Duration column only shown if any visible session has duration
                if (visibleSessions.any { it.duration > 0 }) {
                    Text(
                        text = "Time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.8f),
                        textAlign = TextAlign.End,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Session rows
            AnimatedVisibility(
                visible = true,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    visibleSessions.forEach { session ->
                        SessionRow(
                            session = session,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                            showDuration = visibleSessions.any { it.duration > 0 },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    showDuration: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics {
                val date = KmpUtils.formatTimestamp(session.timestamp, "MMM dd")
                val weight = formatWeight(session.weightPerCableKg, weightUnit)
                val reps = "${if (session.workingReps > 0) session.workingReps else session.totalReps} reps"
                val duration = if (session.duration > 0) ", duration ${formatDuration(session.duration)}" else ""
                contentDescription = "$date, $weight, $reps$duration"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = KmpUtils.formatTimestamp(session.timestamp, "MMM dd"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatWeight(session.weightPerCableKg, weightUnit),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${if (session.workingReps > 0) session.workingReps else session.totalReps} reps",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (showDuration && session.duration > 0) {
            Text(
                text = formatDuration(session.duration),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(0.8f),
                textAlign = TextAlign.End,
            )
        } else if (showDuration) {
            // Empty placeholder to maintain column alignment
            Spacer(modifier = Modifier.weight(0.8f))
        }
    }
}

/**
 * Formats duration in milliseconds to M:SS format.
 * e.g. 42000ms -> "0:42", 75000ms -> "1:15"
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

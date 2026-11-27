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
import com.devil.phoenixproject.presentation.util.AchievedLoadPresentation
import com.devil.phoenixproject.presentation.util.DurationFormatter
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.labelAllCaps
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_collapse
import projectphoenix.shared.generated.resources.cd_expand
import projectphoenix.shared.generated.resources.external_cd_collapsed
import projectphoenix.shared.generated.resources.external_cd_expanded
import projectphoenix.shared.generated.resources.label_reps
import projectphoenix.shared.generated.resources.recent_sessions_cd
import projectphoenix.shared.generated.resources.recent_sessions_col_date
import projectphoenix.shared.generated.resources.recent_sessions_col_time
import projectphoenix.shared.generated.resources.recent_sessions_col_weight
import projectphoenix.shared.generated.resources.recent_sessions_duration_suffix
import projectphoenix.shared.generated.resources.recent_sessions_header
import projectphoenix.shared.generated.resources.recent_sessions_row_cd
import projectphoenix.shared.generated.resources.recent_sessions_state_collapsed
import projectphoenix.shared.generated.resources.recent_sessions_state_expanded
import projectphoenix.shared.generated.resources.recovery_reps_count

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
            val headerStateText = stringResource(
                if (expanded.value) Res.string.recent_sessions_state_expanded else Res.string.recent_sessions_state_collapsed,
            )
            val headerCd = stringResource(Res.string.recent_sessions_cd, headerStateText)
            val headerStateDesc = stringResource(
                if (expanded.value) Res.string.external_cd_expanded else Res.string.external_cd_collapsed,
            )
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
                        contentDescription = headerCd
                        if (canExpand) {
                            stateDescription = headerStateDesc
                            toggleableState = ToggleableState(expanded.value)
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.recent_sessions_header),
                    style = labelAllCaps,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canExpand) {
                    Icon(
                        imageVector = if (expanded.value) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (expanded.value) Res.string.cd_collapse else Res.string.cd_expand,
                        ),
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
                    text = stringResource(Res.string.recent_sessions_col_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(Res.string.recent_sessions_col_weight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(Res.string.label_reps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Duration column only shown if any visible session has duration
                if (visibleSessions.any { it.duration > 0 }) {
                    Text(
                        text = stringResource(Res.string.recent_sessions_col_time),
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
    // Issue #1182: Echo shows the achieved load (measured peak), not the configured seed.
    // The exact rendered string lives in AchievedLoadPresentation so it stays testable.
    val loadText = AchievedLoadPresentation.sessionLoadText(session, weightUnit, formatWeight)
    val dateText = KmpUtils.formatTimestamp(session.timestamp, "MMM d")
    val repCountValue = if (session.workingReps > 0) session.workingReps else session.totalReps
    val repsText = stringResource(Res.string.recovery_reps_count, repCountValue)
    val durationText = if (session.duration > 0) {
        stringResource(Res.string.recent_sessions_duration_suffix, DurationFormatter.formatDuration(session.duration))
    } else {
        ""
    }
    val rowCd = stringResource(Res.string.recent_sessions_row_cd, dateText, loadText, repsText, durationText)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics {
                contentDescription = rowCd
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = loadText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = repsText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (showDuration && session.duration > 0) {
            Text(
                text = DurationFormatter.formatDuration(session.duration),
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

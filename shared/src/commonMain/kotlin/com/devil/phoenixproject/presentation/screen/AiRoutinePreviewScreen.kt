package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.sync.AI_ROUTINE_DRAFT_DISCLAIMER
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineItem
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_discard
import projectphoenix.shared.generated.resources.action_edit
import projectphoenix.shared.generated.resources.ai_routine_preview_badge
import projectphoenix.shared.generated.resources.ai_routine_preview_dropped
import projectphoenix.shared.generated.resources.ai_routine_preview_estimated_minutes
import projectphoenix.shared.generated.resources.ai_routine_preview_exclusion
import projectphoenix.shared.generated.resources.ai_routine_preview_over_target
import projectphoenix.shared.generated.resources.ai_routine_preview_percent_of_1rm
import projectphoenix.shared.generated.resources.ai_routine_preview_requested_minutes
import projectphoenix.shared.generated.resources.ai_routine_preview_sets_reps
import projectphoenix.shared.generated.resources.ai_routine_preview_superset
import projectphoenix.shared.generated.resources.ai_routine_preview_unmet
import projectphoenix.shared.generated.resources.ai_routine_preview_unsaved_note
import projectphoenix.shared.generated.resources.eccentric_load
import projectphoenix.shared.generated.resources.echo_level
import projectphoenix.shared.generated.resources.echo_level_epic
import projectphoenix.shared.generated.resources.echo_level_hard
import projectphoenix.shared.generated.resources.echo_level_harder
import projectphoenix.shared.generated.resources.echo_level_hardest
import projectphoenix.shared.generated.resources.just_lift_tut_beast
import projectphoenix.shared.generated.resources.mode_eccentric_only
import projectphoenix.shared.generated.resources.mode_echo
import projectphoenix.shared.generated.resources.mode_old_school
import projectphoenix.shared.generated.resources.mode_pump
import projectphoenix.shared.generated.resources.mode_tut

/**
 * Everything the generated-draft preview shows (issue #1223 FE-C). The preview is
 * the visible truth over model claims: minutes come from [RoutineTimeEstimator]
 * over the mapped routine, not from the model's own estimate.
 */
data class AiRoutinePreviewData(
    /** The mapped, UNSAVED routine exactly as it would be staged for the editor. */
    val routine: Routine,
    /** Draft exercises dropped by the local library re-check (never substituted). */
    val droppedExerciseNames: List<String>,
    /** Server unmet-constraint notes. */
    val unmetConstraints: List<String>,
    /** The requested target minutes from the prompt screen (null = none). */
    val requestedMinutes: Int?,
    /** Estimated duration seconds from RoutineTimeEstimator.estimateRoutineDuration. */
    val estimatedSeconds: Int,
    /** remainingToday from the generate-routine response. */
    val remainingToday: Int,
) {
    val estimatedMinutes: Int get() = (estimatedSeconds + 59) / 60

    /** Warn when the estimate exceeds 1.5x the requested target. */
    val overTarget: Boolean
        get() = requestedMinutes != null && requestedMinutes > 0 &&
            estimatedSeconds > requestedMinutes * 60.0 * 1.5
}

/**
 * Generated-draft preview (issue #1223 FE-C): "AI-GENERATED · UNSAVED DRAFT"
 * badge, requested vs estimated minutes, exercise list with name/sets/reps/mode/
 * superset grouping and percent-of-estimated-1RM per set, dropped-exercise
 * warnings, unmet-constraint notes, the §1.4 exclusion wording and the fixed
 * server disclaimer, and a sticky Edit action. Nothing is saved here — Edit
 * stages the draft in memory and hands off to [RoutineEditorScreen], whose Save
 * is the first and only write.
 */
@Composable
fun AiRoutineDraftPreview(
    data: AiRoutinePreviewData,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        stringResource(Res.string.ai_routine_preview_badge),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.ai_routine_preview_unsaved_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    data.routine.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (data.requestedMinutes != null) {
                        Text(
                            stringResource(
                                Res.string.ai_routine_preview_requested_minutes,
                                data.requestedMinutes,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("  ·  ", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(
                            Res.string.ai_routine_preview_estimated_minutes,
                            data.estimatedMinutes,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (data.overTarget) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(Res.string.ai_routine_preview_over_target),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
            }

            data.routine.getItems().forEach { routineItem ->
                when (routineItem) {
                    is RoutineItem.Single -> item {
                        PreviewExerciseRow(exercise = routineItem.exercise)
                    }

                    is RoutineItem.SupersetItem -> item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(Res.string.ai_routine_preview_superset),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                routineItem.superset.exercises.forEach { member ->
                                    PreviewExerciseRow(exercise = member)
                                }
                            }
                        }
                    }
                }
            }

            if (data.droppedExerciseNames.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            Res.string.ai_routine_preview_dropped,
                            data.droppedExerciseNames.joinToString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            data.unmetConstraints.forEach { note ->
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.ai_routine_preview_unmet, note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                // Exclusion wording (spec §1.4): catalog-classified avoidance only —
                // never a claim that an excluded muscle cannot participate.
                Text(
                    stringResource(Res.string.ai_routine_preview_exclusion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Fixed generation disclaimer: the server constant, verbatim.
                Text(
                    AI_ROUTINE_DRAFT_DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Sticky Edit action — nothing is saved until the user saves in the editor.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(Res.string.ai_routine_preview_unsaved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(Res.string.action_discard))
                    }
                    Button(onClick = onEdit) {
                        Text(stringResource(Res.string.action_edit))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewExerciseRow(
    exercise: RoutineExercise,
) {
    val sets = exercise.setReps.size
    val reps = exercise.setReps.firstOrNull() ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            exercise.exercise.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.ai_routine_preview_sets_reps, sets, reps) +
                "  ·  " + modeLabel(exercise.programMode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (exercise.usePercentOfPR) {
            Text(
                stringResource(
                    Res.string.ai_routine_preview_percent_of_1rm,
                    exercise.weightPercentOfPR,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exercise.programMode == ProgramMode.Echo) {
            Text(
                stringResource(Res.string.echo_level) + ": " + echoLevelLabel(exercise.echoLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.eccentric_load) + ": " + exercise.eccentricLoad.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun modeLabel(mode: ProgramMode): String = when (mode) {
    ProgramMode.OldSchool -> stringResource(Res.string.mode_old_school)
    ProgramMode.Pump -> stringResource(Res.string.mode_pump)
    ProgramMode.TUT -> stringResource(Res.string.mode_tut)
    ProgramMode.TUTBeast -> stringResource(Res.string.mode_tut) + " " +
        stringResource(Res.string.just_lift_tut_beast)
    ProgramMode.EccentricOnly -> stringResource(Res.string.mode_eccentric_only)
    ProgramMode.Echo -> stringResource(Res.string.mode_echo)
}

@Composable
private fun echoLevelLabel(level: EchoLevel): String = when (level) {
    EchoLevel.HARD -> stringResource(Res.string.echo_level_hard)
    EchoLevel.HARDER -> stringResource(Res.string.echo_level_harder)
    EchoLevel.HARDEST -> stringResource(Res.string.echo_level_hardest)
    EchoLevel.EPIC -> stringResource(Res.string.echo_level_epic)
}

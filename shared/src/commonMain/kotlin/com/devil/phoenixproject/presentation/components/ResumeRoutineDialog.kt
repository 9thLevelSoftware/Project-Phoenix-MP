package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

/**
 * Dialog shown when user tries to start a routine that has existing progress.
 * Allows user to either resume where they left off or start fresh.
 *
 * Issue #101: Provides clear UX for resume vs restart behavior.
 */
@Composable
fun ResumeRoutineDialog(
    progressInfo: ResumableProgressInfo,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.resume_workout_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.resume_workout_saved))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Exercise ${progressInfo.currentExercise} of ${progressInfo.totalExercises}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${progressInfo.exerciseName} - Set ${progressInfo.currentSet} of ${progressInfo.totalSets}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onResume) {
                Text(stringResource(Res.string.action_continue))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onRestart) {
                Text(stringResource(Res.string.restart_from_set_1))
            }
        }
    )
}

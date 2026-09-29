package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.clear_exercise_label_confirm
import projectphoenix.shared.generated.resources.clear_exercise_label_message
import projectphoenix.shared.generated.resources.clear_exercise_label_title

/**
 * #972: the one destructive confirm for clearing a Just Lift exercise label.
 *
 * Single source of truth for the clear copy and the destructive confirm
 * contract — History's WorkoutHistoryCard, History's GroupedRoutineCard rows,
 * and the live post-set summary in WorkoutTab all confirm through here instead
 * of re-declaring the same [DestructiveConfirmDialog] block.
 *
 * Callers gate visibility and own dismiss state (same contract as
 * [DestructiveConfirmDialog]); they also own the coroutine that runs the clear
 * write, so the write's scope outlives this dialog's composition.
 */
@Composable
fun ClearExerciseLabelDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DestructiveConfirmDialog(
        title = stringResource(Res.string.clear_exercise_label_title),
        message = stringResource(Res.string.clear_exercise_label_message),
        confirmText = stringResource(Res.string.clear_exercise_label_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

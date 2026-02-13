package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise

internal sealed interface CustomExerciseSaveAction {
    data class Create(val exercise: Exercise) : CustomExerciseSaveAction
    data class Update(val exercise: Exercise) : CustomExerciseSaveAction
}

internal fun resolveCustomExerciseSaveAction(
    draftExercise: Exercise,
    editingExerciseId: String?
): CustomExerciseSaveAction {
    return if (editingExerciseId != null) {
        CustomExerciseSaveAction.Update(
            draftExercise.copy(id = editingExerciseId, isCustom = true)
        )
    } else {
        CustomExerciseSaveAction.Create(draftExercise)
    }
}

internal fun resolveCustomExerciseDeleteTarget(editingExerciseId: String?): String? {
    return editingExerciseId
}


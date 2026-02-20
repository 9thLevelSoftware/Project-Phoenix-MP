package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CustomExerciseActionsTest {

    @Test
    fun `resolveCustomExerciseSaveAction returns update with captured id for edit flow`() {
        val draft = Exercise(
            name = "Edited Press",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = "",
            isCustom = true
        )

        val action = resolveCustomExerciseSaveAction(
            draftExercise = draft,
            editingExerciseId = "custom_123"
        )

        val update = assertIs<CustomExerciseSaveAction.Update>(action)
        assertEquals("custom_123", update.exercise.id)
        assertEquals(true, update.exercise.isCustom)
    }

    @Test
    fun `captured edit id updates existing custom exercise instead of creating duplicate`() = runTest {
        val repository = FakeExerciseRepository()
        repository.addExercise(
            Exercise(
                id = "custom_1",
                name = "Original Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "",
                isCustom = true
            )
        )

        var exerciseToEditId: String? = "custom_1"
        val capturedEditId = exerciseToEditId
        exerciseToEditId = null

        val action = resolveCustomExerciseSaveAction(
            draftExercise = Exercise(
                name = "Updated Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "",
                isCustom = true
            ),
            editingExerciseId = capturedEditId
        )

        when (action) {
            is CustomExerciseSaveAction.Create -> repository.createCustomExercise(action.exercise)
            is CustomExerciseSaveAction.Update -> repository.updateCustomExercise(action.exercise)
        }

        val customExercises = repository.getCustomExercises().first()
        assertEquals(1, customExercises.size)
        assertEquals("custom_1", customExercises.first().id)
        assertEquals("Updated Press", customExercises.first().name)
        assertEquals(null, exerciseToEditId)
    }

    @Test
    fun `captured edit id deletes custom exercise even after edit state cleared`() = runTest {
        val repository = FakeExerciseRepository()
        repository.addExercise(
            Exercise(
                id = "custom_1",
                name = "Custom Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "",
                isCustom = true
            )
        )

        var exerciseToEditId: String? = "custom_1"
        val capturedEditId = exerciseToEditId
        exerciseToEditId = null

        val deleteTargetId = resolveCustomExerciseDeleteTarget(capturedEditId)
        deleteTargetId?.let { repository.deleteCustomExercise(it) }

        val customExercises = repository.getCustomExercises().first()
        assertEquals(0, customExercises.size)
        assertEquals(null, exerciseToEditId)
    }
}


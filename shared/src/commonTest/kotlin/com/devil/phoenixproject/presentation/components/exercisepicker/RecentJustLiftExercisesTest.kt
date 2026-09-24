package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #850: Recent chip ordering and the one-time history seed. */
class RecentJustLiftExercisesTest {
    private fun exercise(id: String, name: String = id) = Exercise(id = id, name = name, muscleGroup = "Chest")

    @Test
    fun recentOrderKeepsOnlyRecentExercisesNewestFirst() {
        val candidates = listOf(exercise("bench"), exercise("curl"), exercise("row"), exercise("squat"))

        val ordered = orderByRecentExercises(candidates, listOf("squat", "bench", "not-in-candidates"))

        assertEquals(listOf("squat", "bench"), ordered.map { it.id })
    }

    @Test
    fun recentOrderRespectsFiltersAppliedBeforeIt() {
        val filtered = listOf(exercise("bench"))

        assertEquals(listOf("bench"), orderByRecentExercises(filtered, listOf("squat", "bench")).map { it.id })
    }

    @Test
    fun deletedExercisesDropOutOfTheSelectableRecentList() {
        val library = listOf(exercise("bench"), exercise("squat"))

        assertEquals(listOf("squat", "bench"), selectableRecentExerciseIds(listOf("squat", "deleted-custom", "bench"), library))
        assertEquals(emptyList(), selectableRecentExerciseIds(listOf("deleted-custom"), library))
    }

    @Test
    fun historySeedUsesTaggedJustLiftSessionsNewestFirst() {
        val sessions = listOf(
            WorkoutSession(id = "1", timestamp = 100, isJustLift = true, exerciseId = "bench"),
            WorkoutSession(id = "2", timestamp = 300, isJustLift = true, exerciseId = "squat"),
            WorkoutSession(id = "3", timestamp = 400, isJustLift = false, exerciseId = "row"),
            WorkoutSession(id = "4", timestamp = 200, isJustLift = true, exerciseId = null),
            WorkoutSession(id = "5", timestamp = 500, isJustLift = true, exerciseId = "bench"),
        )

        assertEquals(listOf("bench", "squat"), recentJustLiftExerciseIdsFromHistory(sessions))
    }
}

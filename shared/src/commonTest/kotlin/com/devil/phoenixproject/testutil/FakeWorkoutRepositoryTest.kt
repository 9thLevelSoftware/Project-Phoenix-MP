package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeWorkoutRepositoryTest {
    @Test
    fun historyVisibleSessionsUsesRequestedProfileAndPositiveRepContract() = runTest {
        val repository = FakeWorkoutRepository()
        repository.addSessions(
            listOf(
                WorkoutSession(
                    id = "profile-a-completed",
                    profileId = "profile-a",
                    exerciseId = "squat",
                    workingReps = 8,
                ),
                WorkoutSession(
                    id = "profile-b-completed",
                    profileId = "profile-b",
                    exerciseId = "bench",
                    totalReps = 8,
                ),
                WorkoutSession(
                    id = "profile-a-ghost",
                    profileId = "profile-a",
                    exerciseId = "row",
                ),
            ),
        )

        val visible = repository.getHistoryVisibleSessions("profile-a").first()

        assertEquals(listOf("squat"), visible.map { it.exerciseId })
    }

    @Test
    fun unfilteredSessionAndRoutineQueriesAreIsolatedByProfileId() = runTest {
        val repository = FakeWorkoutRepository()
        repository.addSessions(
            listOf(
                WorkoutSession(
                    id = "a-1",
                    profileId = "profile-a",
                    exerciseId = "bench",
                    weightPerCableKg = 40f,
                    timestamp = 1_000L,
                ),
                WorkoutSession(
                    id = "a-2",
                    profileId = "profile-a",
                    exerciseId = "bench",
                    weightPerCableKg = 45f,
                    timestamp = 2_000L,
                ),
                WorkoutSession(
                    id = "b-1",
                    profileId = "profile-b",
                    exerciseId = "bench",
                    weightPerCableKg = 90f,
                    timestamp = 3_000L,
                ),
            ),
        )
        repository.addRoutine(Routine(id = "r-a", name = "A Routine", profileId = "profile-a"))
        repository.addRoutine(Routine(id = "r-b", name = "B Routine", profileId = "profile-b"))

        assertEquals(listOf("a-2", "a-1"), repository.getAllSessions("profile-a").first().map { it.id })
        assertEquals(listOf("b-1"), repository.getAllSessions("profile-b").first().map { it.id })
        assertEquals(2L, repository.getSessionCountForExercise("bench", "profile-a"))
        assertEquals(1L, repository.getSessionCountForExercise("bench", "profile-b"))
        assertEquals(listOf("a-2"), repository.getRecentSessions("profile-a", 1).first().map { it.id })
        assertEquals(listOf("a-2"), repository.getRecentSessionsSync("profile-a", 1).map { it.id })
        assertEquals(45f, repository.getLastWeightForExercise("profile-a", "bench"))
        assertEquals(90f, repository.getLastWeightForExercise("profile-b", "bench"))
        assertNull(repository.getLastWeightForExercise("profile-c", "bench"))
        assertEquals(listOf("r-a"), repository.getAllRoutines("profile-a").first().map { it.id })
        assertEquals(listOf("r-b"), repository.getAllRoutines("profile-b").first().map { it.id })
    }
}

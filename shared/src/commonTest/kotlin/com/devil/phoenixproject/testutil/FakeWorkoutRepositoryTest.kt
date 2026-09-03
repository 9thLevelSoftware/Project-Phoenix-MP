package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

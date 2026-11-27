package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.TrainingCycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeTrainingCycleRepositoryTest {

    @Test
    fun cyclesAndActiveCycleAreIsolatedByProfileId() = runTest {
        val repository = FakeTrainingCycleRepository()
        val cycleA = TrainingCycle.create(id = "cycle-a", name = "Cycle A", profileId = "profile-a")
        val cycleB = TrainingCycle.create(id = "cycle-b", name = "Cycle B", profileId = "profile-b")
        repository.saveCycle(cycleA)
        repository.saveCycle(cycleB)

        assertEquals(listOf("cycle-a"), repository.getAllCycles("profile-a").first().map { it.id })
        assertEquals(listOf("cycle-b"), repository.getAllCycles("profile-b").first().map { it.id })

        // Cross-profile activation is rejected.
        repository.setActiveCycle("cycle-b", "profile-a")
        assertNull(repository.getActiveCycle("profile-a").first())

        repository.setActiveCycle("cycle-a", "profile-a")
        repository.setActiveCycle("cycle-b", "profile-b")
        assertEquals("cycle-a", repository.getActiveCycle("profile-a").first()?.id)
        assertEquals("cycle-b", repository.getActiveCycle("profile-b").first()?.id)

        // Clearing profile-a leaves profile-b active.
        repository.clearActiveCycle("profile-a")
        assertNull(repository.getActiveCycle("profile-a").first())
        assertEquals("cycle-b", repository.getActiveCycle("profile-b").first()?.id)

        // Deleting profile-b's active cycle clears only profile-b.
        repository.setActiveCycle("cycle-a", "profile-a")
        repository.deleteCycle("cycle-b")
        assertNull(repository.getActiveCycle("profile-b").first())
        assertEquals("cycle-a", repository.getActiveCycle("profile-a").first()?.id)
    }
}

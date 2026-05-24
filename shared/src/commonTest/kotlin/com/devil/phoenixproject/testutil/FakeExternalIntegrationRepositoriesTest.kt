package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FakeExternalIntegrationRepositoriesTest {

    @Test
    fun routineObserversEmitAfterUpsert() = runTest {
        val repository = FakeExternalRoutineRepository()
        val emissions = mutableListOf<List<ExternalRoutine>>()
        val job = launch {
            repository.observeRoutines(profileId = "profile-1")
                .take(2)
                .toList(emissions)
        }
        runCurrent()

        repository.upsertRoutines(
            listOf(
                ExternalRoutine(
                    id = "routine-local-1",
                    externalId = "routine-1",
                    provider = IntegrationProvider.HEVY,
                    title = "Upper",
                    profileId = "profile-1",
                ),
            ),
        )
        runCurrent()

        assertEquals(2, emissions.size)
        assertEquals(emptyList(), emissions.first())
        assertEquals("routine-1", emissions.last().single().externalId)
        job.cancel()
    }

    @Test
    fun programStatsObserverFiltersByProfileAndProvider() = runTest {
        val repository = FakeExternalProgramRepository()
        val liftosaurProgram = ExternalProgram(
            id = "program-local-1",
            externalId = "program-1",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Liftosaur",
            profileId = "profile-1",
        )
        val hevyProgram = ExternalProgram(
            id = "program-local-2",
            externalId = "program-2",
            provider = IntegrationProvider.HEVY,
            name = "Hevy",
            profileId = "profile-1",
        )
        val otherProfileProgram = ExternalProgram(
            id = "program-local-3",
            externalId = "program-3",
            provider = IntegrationProvider.LIFTOSAUR,
            name = "Other Profile",
            profileId = "profile-2",
        )

        repository.upsertPrograms(listOf(liftosaurProgram, hevyProgram, otherProfileProgram))
        repository.upsertStats(
            listOf(
                ExternalProgramStats(externalProgramId = liftosaurProgram.id, setCount = 10),
                ExternalProgramStats(externalProgramId = hevyProgram.id, setCount = 20),
                ExternalProgramStats(externalProgramId = otherProfileProgram.id, setCount = 30),
            ),
        )

        val stats = repository.observeProgramStats(
            profileId = "profile-1",
            provider = IntegrationProvider.LIFTOSAUR,
        ).first()

        assertEquals(setOf(liftosaurProgram.id), stats.keys)
        assertEquals(10, stats[liftosaurProgram.id]?.setCount)
    }
}

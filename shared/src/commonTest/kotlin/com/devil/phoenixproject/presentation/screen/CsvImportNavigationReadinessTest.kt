package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportHostResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B3 navigation wait (#1242): navigation observes committed CONTENT, not just an id. An
 * overwrite's id pre-existed, so the emitted routine must carry the committed revision before
 * a fresh editor opens; a profile change cancels navigation instead of retargeting it.
 */
class CsvImportNavigationReadinessTest {
    private val expected = RoutineCsvImportHostResult(
        profileId = "p1",
        firstRoutineId = "r1",
        firstName = "Push",
        routineCount = 1,
        committedUpdatedAt = 1_000L,
        overwrite = false,
    )

    private fun routine(id: String = "r1", name: String = "Push", updatedAt: Long? = 1_000L) = Routine(
        id = id,
        name = name,
        profileId = "p1",
        updatedAt = updatedAt,
    )

    @Test
    fun aCommittedRoutineInTheActiveProfileIsReady() {
        assertEquals(
            CsvImportReadiness.READY,
            csvImportNavigationReadiness(listOf(routine()), expected, currentProfileId = "p1"),
        )
    }

    @Test
    fun aMissingIdWaitsForTheListInsteadOfNavigating() {
        assertEquals(
            CsvImportReadiness.WAIT,
            csvImportNavigationReadiness(emptyList(), expected, currentProfileId = "p1"),
        )
    }

    @Test
    fun aStaleOverwriteEmissionWaitsForTheCommittedRevision() {
        // The id exists (overwrite), but the list has not emitted the committed content yet.
        val stale = routine(updatedAt = 999L)
        val overwrite = expected.copy(overwrite = true)
        assertEquals(
            CsvImportReadiness.WAIT,
            csvImportNavigationReadiness(listOf(stale), overwrite, currentProfileId = "p1"),
        )
        assertEquals(
            CsvImportReadiness.READY,
            csvImportNavigationReadiness(listOf(routine(updatedAt = 1_000L)), overwrite, currentProfileId = "p1"),
        )
    }

    @Test
    fun aDifferentNameIsNotTheCommittedContent() {
        assertEquals(
            CsvImportReadiness.WAIT,
            csvImportNavigationReadiness(listOf(routine(name = "Push (Copy)")), expected, currentProfileId = "p1"),
        )
    }

    @Test
    fun aProfileChangeCancelsNavigation() {
        assertEquals(
            CsvImportReadiness.PROFILE_CHANGED,
            csvImportNavigationReadiness(listOf(routine()), expected, currentProfileId = "p2"),
        )
    }
}

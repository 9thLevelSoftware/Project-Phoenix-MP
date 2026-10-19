package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.presentation.viewmodel.RoutineCsvImportUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #1242: a shared-file import opens the builder only on the committed write, in the
 * profile it was imported into. A replaced routine keeps its id, so the id alone is not enough.
 */
class SharedCsvImportReadinessTest {
    private val routineId = "0b6f2a4e-1c3d-4e5f-8a9b-0c1d2e3f4a5b"
    private val imported = RoutineCsvImportUiState.Imported(
        routineCount = 1,
        profileId = "profile-a",
        routineIds = listOf(routineId),
        routineNames = listOf("Push"),
        committedAfter = 2_000L,
    )

    private fun routine(id: String = routineId, updatedAt: Long?) = Routine(id = id, name = "Push", updatedAt = updatedAt)

    @Test
    fun theCommittedWriteInTheImportProfileIsReady() {
        val committed = routine(updatedAt = 2_500L)
        assertEquals(CsvImportReadiness.READY, csvImportReadiness(listOf(committed), "profile-a", imported))
        assertEquals(committed, importedRoutine(listOf(committed), imported))
    }

    @Test
    fun theCopyAnOverwriteReplacedIsNotReady() {
        val stale = routine(updatedAt = 1_500L)
        assertEquals(CsvImportReadiness.WAIT, csvImportReadiness(listOf(stale), "profile-a", imported))
        assertNull(importedRoutine(listOf(stale), imported))
        assertNull(importedRoutine(listOf(routine(updatedAt = null)), imported))
    }

    @Test
    fun aMissingRoutineWaits() {
        assertEquals(CsvImportReadiness.WAIT, csvImportReadiness(emptyList(), "profile-a", imported))
        assertEquals(
            CsvImportReadiness.WAIT,
            csvImportReadiness(listOf(routine(id = "another", updatedAt = 3_000L)), "profile-a", imported),
        )
    }

    @Test
    fun anotherActiveProfileIsNeverOpenedEvenWhenTheIdMatches() {
        // The editor saves into the active profile: opening this id there would move the routine.
        assertEquals(
            CsvImportReadiness.PROFILE_CHANGED,
            csvImportReadiness(listOf(routine(updatedAt = 2_500L)), "profile-b", imported),
        )
    }

    @Test
    fun aCaseVariantOfTheSameUuidIsTheSameRoutine() {
        // The repository may store the write under an existing case variant of the id.
        val stored = routine(id = routineId.uppercase(), updatedAt = 2_500L)
        assertEquals(stored, importedRoutine(listOf(stored), imported))
    }

    @Test
    fun anImportWithNoRoutinesIsNeverReady() {
        val empty = imported.copy(routineIds = emptyList())
        assertEquals(CsvImportReadiness.WAIT, csvImportReadiness(listOf(routine(updatedAt = 2_500L)), "profile-a", empty))
    }
}

package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.PullTrainingCycleDto
import com.devil.phoenixproject.data.sync.PullCycleDayDto
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

/** Diagnostic only: fabricated pull fixture, real production SQLDelight merge. Not a live server reproduction. */
class Issue790CycleRoutineSyncTest {
    private val routineId = "cycle_routine_55555555-5555-4555-a555-555555555555"
    private val cycleId = "66666666-6666-4666-a666-666666666666"
    private val dayId = "77777777-7777-4777-a777-777777777777"

    @Test fun localReloadWithoutPullRetainsLink() = probe(false)
    @Test fun nullPullShouldNotEraseExistingTemplateLink() = probe(true, useStandaloneMerge = false)
    @Test fun standaloneNullPullShouldNotEraseExistingTemplateLink() = probe(true, useStandaloneMerge = true)

    private fun probe(applyPull: Boolean, useStandaloneMerge: Boolean = false) = runTest {
        val db = createTestDatabase()
        val q = db.phoenixDatabaseQueries
        q.insertRoutineIgnore(routineId, "Full Body A", "", 1L, null, 0L, 1L, "default", null)
        val local = SqlDelightTrainingCycleRepository(db)
        local.saveCycle(TrainingCycle.create(
            id = cycleId, name = "RCA fixture", days = listOf(
                CycleDay.create(id = dayId, cycleId = cycleId, dayNumber = 1,
                    name = "Full Body A", routineId = routineId)
            )
        ))
        local.setActiveCycle(cycleId, "default")
        assertEquals(routineId, local.getCycleById(cycleId)!!.days.single().routineId)
        if (applyPull) {
            val syncRepository = SqlDelightSyncRepository(db, FakeUserProfileRepository())
            if (useStandaloneMerge) {
                syncRepository.mergePortalCycles(
                    cycles = listOf(PullTrainingCycleDto(
                        id = cycleId, name = "RCA fixture", status = "active",
                        days = listOf(PullCycleDayDto(id = "remote-day-id", cycleId = cycleId,
                            dayNumber = 1, routineId = null, notes = "Full Body A"))
                    )),
                    profileId = "default",
                )
            } else {
                syncRepository.mergeAllPullData(
                    sessions = emptyList(), routines = emptyList(), badges = emptyList(),
                    gamificationStats = null, personalRecords = emptyList(), lastSync = 0L,
                    profileId = "default", cycles = listOf(PullTrainingCycleDto(
                        id = cycleId, name = "RCA fixture", status = "active",
                        days = listOf(PullCycleDayDto(id = "remote-day-id", cycleId = cycleId,
                            dayNumber = 1, routineId = null, notes = "Full Body A"))
                    ))
                )
            }
        }
        // Recreate repository over the same DB, NOT an OS process restart.
        val reloaded = SqlDelightTrainingCycleRepository(db).getCycleById(cycleId)!!
        assertEquals(1, reloaded.days.size)
        assertNotNull(q.selectRoutineById(routineId).executeAsOneOrNull(), "Routine row survives")
        assertEquals(routineId, reloaded.days.single().routineId,
            "Existing template link should survive; current null pull destroys it")
    }
}

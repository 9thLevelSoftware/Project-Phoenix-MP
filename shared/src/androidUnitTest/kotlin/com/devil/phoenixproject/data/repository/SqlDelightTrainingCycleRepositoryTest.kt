package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlDelightTrainingCycleRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightTrainingCycleRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightTrainingCycleRepository(database)
    }

    @Test
    fun `saveCycle and getCycleById returns days`() = runTest {
        val cycleId = "cycle-1"
        val days = listOf(
            CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
            CycleDay.restDay(cycleId = cycleId, dayNumber = 2)
        )
        val cycle = TrainingCycle.create(id = cycleId, name = "Test", days = days)

        repository.saveCycle(cycle)

        val loaded = repository.getCycleById(cycleId)
        assertNotNull(loaded)
        assertEquals(2, loaded.days.size)
    }

    @Test
    fun `setActiveCycle updates active flow`() = runTest {
        val cycle = TrainingCycle.create(id = "cycle-2", name = "Active Cycle")
        repository.saveCycle(cycle)

        repository.setActiveCycle("cycle-2")

        repository.getActiveCycle().test {
            val active = awaitItem()
            assertNotNull(active)
            assertEquals("cycle-2", active.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `advanceToNextDay wraps based on cycle size`() = runTest {
        val cycleId = "cycle-3"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2")
            )
        )
        repository.saveCycle(cycle)
        repository.initializeProgress(cycleId)

        val nextDay = repository.advanceToNextDay(cycleId)
        assertEquals(2, nextDay)
    }

    @Test
    fun `checkAndAutoAdvance advances when overdue`() = runTest {
        val cycleId = "cycle-4"
        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
                CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2")
            )
        )
        repository.saveCycle(cycle)
        val progress = repository.initializeProgress(cycleId)

        repository.updateCycleProgress(
            progress.copy(lastAdvancedAt = System.currentTimeMillis() - (25 * 60 * 60 * 1000L))
        )

        val updated = repository.checkAndAutoAdvance(cycleId)
        assertEquals(2, updated?.currentDayNumber)
    }

    @Test
    fun `saveCycleProgression persists progression`() = runTest {
        val cycleId = "cycle-5"
        repository.saveCycle(
            TrainingCycle.create(
                id = cycleId,
                name = "Cycle",
                days = listOf(CycleDay.restDay(cycleId = cycleId, dayNumber = 1))
            )
        )
        val progression = CycleProgression(
            cycleId = cycleId,
            frequencyCycles = 3,
            weightIncreasePercent = 2.5f,
            echoLevelIncrease = true
        )

        repository.saveCycleProgression(progression)

        val loaded = repository.getCycleProgression(cycleId)
        assertEquals(3, loaded?.frequencyCycles)
        assertTrue(loaded?.echoLevelIncrease == true)
    }

    @Test
    fun `getCycleItems includes routine info`() = runTest {
        val cycleId = "cycle-6"
        val routineId = "routine-1"
        insertRoutine(routineId)
        insertRoutineExercise(generateUUID(), routineId, "Bench Press")

        val cycle = TrainingCycle.create(
            id = cycleId,
            name = "Cycle",
            days = listOf(
                CycleDay.create(
                    cycleId = cycleId,
                    dayNumber = 1,
                    name = "Day 1",
                    routineId = routineId
                )
            )
        )
        repository.saveCycle(cycle)

        val items = repository.getCycleItems(cycleId)
        assertEquals(1, items.size)
        val item = items.first()
        assertTrue(item is com.devil.phoenixproject.domain.model.CycleItem.Workout)
    }

    private fun insertRoutine(id: String) {
        database.vitruvianDatabaseQueries.insertRoutine(
            id = id,
            name = "Routine",
            description = "",
            createdAt = 0L,
            lastUsed = null,
            useCount = 0L
        )
    }

    private fun insertRoutineExercise(id: String, routineId: String, name: String) {
        database.vitruvianDatabaseQueries.insertRoutineExercise(
            id = id,
            routineId = routineId,
            exerciseName = name,
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "BAR",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = "bench",
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10,10,10",
            weightPerCableKg = 40.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 60L,
            duration = null,
            setRestSeconds = "[]",
            perSetRestTime = 0L,
            isAMRAP = 0L,
            supersetId = null,
            orderInSuperset = 0L,
            usePercentOfPR = 0L,
            weightPercentOfPR = 80L,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null
        )
    }
}

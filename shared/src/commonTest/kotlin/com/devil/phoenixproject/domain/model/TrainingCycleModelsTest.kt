package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrainingCycleModelsTest {

    @Test
    fun `CycleItem fromCycleDay returns Rest when day is rest`() {
        val day = CycleDay.restDay(
            cycleId = "cycle-1",
            dayNumber = 2,
            name = "Recovery"
        )

        val item = CycleItem.fromCycleDay(day, routineName = "Ignored", exerciseCount = 3)

        assertTrue(item is CycleItem.Rest)
        assertEquals(2, item.dayNumber)
        assertEquals("Recovery", item.note)
    }

    @Test
    fun `CycleProgress advanceToNextDay wraps and resets on new rotation`() {
        val progress = CycleProgress(
            id = "progress-1",
            cycleId = "cycle-1",
            currentDayNumber = 3,
            lastCompletedDate = null,
            cycleStartDate = currentTimeMillis(),
            completedDays = setOf(1, 2),
            missedDays = setOf(3),
            rotationCount = 0
        )

        val updated = progress.advanceToNextDay(totalDays = 3, markMissed = true)

        assertEquals(1, updated.currentDayNumber)
        assertEquals(1, updated.rotationCount)
        assertTrue(updated.completedDays.isEmpty())
        assertTrue(updated.missedDays.isEmpty())
    }

    @Test
    fun `CycleProgress markDayCompleted tracks skipped days`() {
        val progress = CycleProgress(
            id = "progress-2",
            cycleId = "cycle-1",
            currentDayNumber = 1,
            lastCompletedDate = null,
            cycleStartDate = currentTimeMillis()
        )

        val updated = progress.markDayCompleted(dayNumber = 3, totalDays = 4)

        assertEquals(4, updated.currentDayNumber)
        assertTrue(updated.completedDays.contains(3))
        assertTrue(updated.missedDays.containsAll(setOf(1, 2)))
        assertNotNull(updated.lastCompletedDate)
    }

    @Test
    fun `CycleProgress shouldAutoAdvance returns true after 24 hours`() {
        val now = currentTimeMillis()
        val progress = CycleProgress(
            id = "progress-3",
            cycleId = "cycle-1",
            currentDayNumber = 1,
            lastCompletedDate = null,
            cycleStartDate = now,
            lastAdvancedAt = now - (25 * 60 * 60 * 1000L)
        )

        assertTrue(progress.shouldAutoAdvance())
    }

    @Test
    fun `CompletedSet estimatedOneRepMax uses Epley formula`() {
        val set = CompletedSet(
            id = "set-1",
            sessionId = "session-1",
            plannedSetId = null,
            setNumber = 1,
            setType = SetType.STANDARD,
            actualReps = 5,
            actualWeightKg = 100f,
            loggedRpe = null,
            isPr = false,
            completedAt = 0L
        )

        val estimated = set.estimatedOneRepMax()
        assertEquals(116.65f, estimated, absoluteTolerance = 0.1f)
    }

    @Test
    fun `CompletedSet helpers compute volume and filters`() {
        val sets = listOf(
            CompletedSet(
                id = "set-1",
                sessionId = "session-1",
                plannedSetId = null,
                setNumber = 1,
                setType = SetType.WARMUP,
                actualReps = 5,
                actualWeightKg = 20f,
                loggedRpe = null,
                isPr = false,
                completedAt = 1L
            ),
            CompletedSet(
                id = "set-2",
                sessionId = "session-1",
                plannedSetId = null,
                setNumber = 2,
                setType = SetType.STANDARD,
                actualReps = 8,
                actualWeightKg = 40f,
                loggedRpe = null,
                isPr = false,
                completedAt = 2L
            )
        )

        assertEquals(420f, sets.totalVolume())
        assertEquals(1, sets.workingSets().size)
        assertNotNull(sets.bestOneRepMax())
    }

    @Test
    fun `CompletedSet toCompactString groups by weight`() {
        val sets = listOf(
            CompletedSet(
                id = "set-1",
                sessionId = "session-1",
                plannedSetId = null,
                setNumber = 1,
                setType = SetType.STANDARD,
                actualReps = 10,
                actualWeightKg = 30f,
                loggedRpe = null,
                isPr = false,
                completedAt = 1L
            ),
            CompletedSet(
                id = "set-2",
                sessionId = "session-1",
                plannedSetId = null,
                setNumber = 2,
                setType = SetType.STANDARD,
                actualReps = 8,
                actualWeightKg = 30f,
                loggedRpe = null,
                isPr = false,
                completedAt = 2L
            )
        )

        val summary = sets.toCompactString { weight -> "${weight}kg" }
        assertEquals("30.0kg × 10, 8", summary)
    }

    @Test
    fun `ProgressionEvent calculateProgressionWeight enforces minimum increment`() {
        val next = ProgressionEvent.calculateProgressionWeight(10f)
        assertEquals(10.5f, next)
    }
}

class TemplateModelsTest {

    @Test
    fun `calculateSetWeight uses training max and rounds to half kg`() {
        val weight = calculateSetWeight(
            oneRepMaxKg = 100f,
            percentageSet = PercentageSet(percent = 0.75f, targetReps = 5)
        )

        assertEquals(67.5f, weight)
    }

    @Test
    fun `FiveThreeOneWeeks returns expected week presets`() {
        val week2 = FiveThreeOneWeeks.forWeek(2)

        assertEquals(3, week2.size)
        assertFalse(week2.first().isAmrap)
        assertTrue(week2.last().isAmrap)
    }
}

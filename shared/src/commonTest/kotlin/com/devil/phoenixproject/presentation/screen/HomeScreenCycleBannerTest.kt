package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.util.KmpLocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #549: Home cycle banner showed the prior day's workout on initial load because
 * HomeScreen called `getCycleProgress` directly, skipping `checkAndAutoAdvance`. The fix routes
 * the Home path through a new helper `loadHomeCycleProgress` that calls
 * `checkAndAutoAdvance` first and falls back to `getCycleProgress` if the result is null.
 *
 * These tests assert:
 *  1. `checkAndAutoAdvance` is called before `getCycleProgress`.
 *  2. The returned `CycleProgress?` from `checkAndAutoAdvance` is used (auto-advance is honoured).
 *  3. If `checkAndAutoAdvance` returns null (no progress row), the helper falls back to
 *     `getCycleProgress` and returns whatever the persisted value is.
 *  4. If both are null (no progress at all), the helper returns null (same as before).
 *
 * Uses `kotlinx.datetime` and the project's `KmpLocalDate` for system-tz midnight calculation so
 * the test stays KMP-portable (the test source set is shared with iosTest, which has no
 * `java.time.*` on the classpath).
 */
class HomeScreenCycleBannerTest {

    @Test
    fun `loadHomeCycleProgress calls checkAndAutoAdvance before getCycleProgress`() = runTest {
        val repo = FakeTrainingCycleRepository()
        val cycle = newTwoDayCycle(cycleId = "cycle-call-order")
        repo.addCycle(cycle)
        // Force a "yesterday" auto-advance by setting lastAdvancedAt to yesterday midnight.
        val initial = repo.initializeProgress("cycle-call-order")
        val yesterdayMidnight = yesterdayMidnightEpochMs()
        repo.updateCycleProgress(initial.copy(lastAdvancedAt = yesterdayMidnight))
        repo.resetCallLog()

        loadHomeCycleProgress(repo, cycle)

        val log = repo.callLog
        // checkAndAutoAdvance is the only call the helper makes when it succeeds.
        assertEquals("checkAndAutoAdvance", log.first())
    }

    @Test
    fun `loadHomeCycleProgress uses the auto-advanced day from checkAndAutoAdvance`() = runTest {
        val repo = FakeTrainingCycleRepository()
        val cycle = newTwoDayCycle(cycleId = "cycle-auto-advance")
        repo.addCycle(cycle)
        val initial = repo.initializeProgress("cycle-auto-advance")
        repo.updateCycleProgress(initial.copy(lastAdvancedAt = yesterdayMidnightEpochMs()))

        val result = loadHomeCycleProgress(repo, cycle)

        // Yesterday's progress (day 1) should auto-advance to day 2, not stay at day 1.
        assertNotNull(result)
        assertEquals(2, result.currentDayNumber)
    }

    @Test
    fun `loadHomeCycleProgress falls back to getCycleProgress when checkAndAutoAdvance returns null`() = runTest {
        val repo = FakeTrainingCycleRepository()
        val cycle = newTwoDayCycle(cycleId = "cycle-fallback")
        repo.addCycle(cycle)
        // No progress row is initialized -> checkAndAutoAdvance will return null and the helper
        // must fall back to getCycleProgress, which also returns null here (no persisted row).
        repo.resetCallLog()

        val result = loadHomeCycleProgress(repo, cycle)

        // Both calls should have happened in this order, then the helper returned null.
        assertNull(result)
        assertEquals(listOf("checkAndAutoAdvance", "getCycleProgress"), repo.callLog)
    }

    @Test
    fun `loadHomeCycleProgress returns the persisted value when no auto-advance is due`() = runTest {
        val repo = FakeTrainingCycleRepository()
        val cycle = newTwoDayCycle(cycleId = "cycle-no-advance")
        repo.addCycle(cycle)
        // Fresh progress initialized just now: pendingAutoAdvanceDays() == 0, so
        // checkAndAutoAdvance returns the same row without advancing.
        repo.initializeProgress("cycle-no-advance")

        val result = loadHomeCycleProgress(repo, cycle)

        // Still on day 1 because we just initialized; checkAndAutoAdvance was the only call.
        assertNotNull(result)
        assertEquals(1, result.currentDayNumber)
        assertEquals(listOf("checkAndAutoAdvance"), repo.callLog)
    }

    private fun newTwoDayCycle(cycleId: String): TrainingCycle = TrainingCycle.create(
        id = cycleId,
        name = "Test cycle $cycleId",
        days = listOf(
            CycleDay.create(cycleId = cycleId, dayNumber = 1, name = "Day 1"),
            CycleDay.create(cycleId = cycleId, dayNumber = 2, name = "Day 2"),
        ),
    )

    /**
     * Returns the epoch-ms for "yesterday at midnight in the system timezone" using the project's
     * KMP-compatible date utilities. `pendingAutoAdvanceDays()` compares calendar dates in the
     * system timezone, so anchoring to yesterday-midnight guarantees exactly 1 calendar day
     * elapsed regardless of where in the day the test runs.
     */
    private fun yesterdayMidnightEpochMs(): Long {
        val yesterday: KmpLocalDate = KmpLocalDate.today().minusDays(1)
        return LocalDate(yesterday.year, yesterday.month, yesterday.dayOfMonth)
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
    }
}

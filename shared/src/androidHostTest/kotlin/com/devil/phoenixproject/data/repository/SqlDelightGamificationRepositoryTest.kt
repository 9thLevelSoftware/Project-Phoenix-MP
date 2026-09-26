package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.Before
import org.junit.Test

class SqlDelightGamificationRepositoryTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightGamificationRepository
    private val profileId = "default"

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightGamificationRepository(database)
    }

    @Test
    fun `awardBadge stores earned badge and markBadgeCelebrated updates it`() = runTest {
        val awarded = repository.awardBadge("workouts_1", profileId)
        assertTrue(awarded)

        repository.getEarnedBadges(profileId).test {
            val earned = awaitItem()
            assertEquals(1, earned.size)
            cancelAndIgnoreRemainingEvents()
        }

        repository.markBadgeCelebrated("workouts_1", profileId)
        repository.getUncelebratedBadges(profileId).test {
            val uncelebrated = awaitItem()
            assertTrue(uncelebrated.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateStats calculates workout totals`() = runTest {
        insertWorkoutSession(id = "session-1", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats(profileId)

        repository.getGamificationStats(profileId).test {
            val stats = awaitItem()
            assertEquals(1, stats.totalWorkouts)
            assertEquals(10, stats.totalReps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateStats ignores zero-rep sessions and tracks valid untagged sessions`() = runTest {
        insertWorkoutSession(id = "session-valid-tagged", totalReps = 10, weightPerCableKg = 20.0, exerciseId = "bench")
        insertWorkoutSession(id = "session-valid-untagged", totalReps = 8, weightPerCableKg = 15.0, exerciseId = null)
        insertWorkoutSession(id = "session-invalid", totalReps = 0, weightPerCableKg = 20.0, exerciseId = "squat")

        repository.updateStats(profileId)

        repository.getGamificationStats(profileId).test {
            val stats = awaitItem()
            assertEquals(2, stats.totalWorkouts)
            assertEquals(18, stats.totalReps)
            assertEquals(1, stats.uniqueExercisesUsed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checkAndAwardBadges awards rep exercise and routine completion badges from valid sessions`() = runTest {
        repeat(5) { index ->
            insertWorkoutSession(
                id = "exercise-session-$index",
                totalReps = 20,
                weightPerCableKg = 20.0,
                exerciseId = "exercise-$index",
            )
        }
        repeat(10) { index ->
            insertWorkoutSession(
                id = "routine-session-$index",
                totalReps = 1,
                weightPerCableKg = 10.0,
                exerciseId = "exercise-0",
                routineSessionId = "routine-run-$index",
            )
        }
        insertWorkoutSession(
            id = "invalid-routine-session",
            totalReps = 0,
            weightPerCableKg = 10.0,
            exerciseId = "invalid-exercise",
            routineSessionId = "invalid-routine-run",
        )

        repository.updateStats(profileId)
        val badges = repository.checkAndAwardBadges(profileId).map { it.id }.toSet()

        assertTrue("reps_100" in badges)
        assertTrue("exercises_5" in badges)
        assertTrue("routines_completed_10" in badges)
    }

    @Test
    fun `checkAndAwardBadges awards first workout badge`() = runTest {
        insertWorkoutSession(id = "session-2", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats(profileId)

        val badges = repository.checkAndAwardBadges(profileId)

        assertTrue(badges.any { it.id == "workouts_1" })
    }

    // ========== F-034 / F-035: SQL-backed badge helpers ==========

    @Test
    fun `profile B is not awarded a PeakPower badge from profile A's data`() = runTest {
        // Profile A: real RepMetric watts plus raw MetricSample power (kg x mm/s, huge).
        insertWorkoutSession(id = "a-session", totalReps = 10, weightPerCableKg = 50.0, profileId = "profile-a")
        insertRepMetric(sessionId = "a-session", peakPowerWatts = 1_200.0)
        insertMetricSample(sessionId = "a-session", power = 90_000.0)
        // Profile B: a workout of its own, no power data at all.
        insertWorkoutSession(id = "b-session", totalReps = 10, weightPerCableKg = 20.0, profileId = "profile-b")

        repository.updateStats("profile-b")
        val profileBBadges = repository.checkAndAwardBadges("profile-b").map { it.id }.toSet()
        assertTrue("workouts_1" in profileBBadges, "control: B earns its own badges: $profileBBadges")
        assertTrue(PEAK_POWER_BADGES.none { it in profileBBadges }, "B leaked A's power: $profileBBadges")
        assertEquals(0 to 500, badgeProgress("power_500", "profile-b"))

        repository.updateStats("profile-a")
        val profileABadges = repository.checkAndAwardBadges("profile-a").map { it.id }.toSet()
        assertTrue(PEAK_POWER_BADGES.all { it in profileABadges }, "A earns its own power badges: $profileABadges")
    }

    @Test
    fun `peak power badges compare RepMetric watts, never raw MetricSample units`() = runTest {
        insertWorkoutSession(id = "session", totalReps = 10, weightPerCableKg = 50.0)
        insertRepMetric(sessionId = "session", peakPowerWatts = 600.0)
        insertMetricSample(sessionId = "session", power = 90_000.0) // kg x mm/s, not watts

        repository.updateStats(profileId)
        val badges = repository.checkAndAwardBadges(profileId).map { it.id }.toSet()

        assertTrue("power_500" in badges, "$badges")
        assertFalse("power_750" in badges, "$badges")
        assertFalse("power_1000" in badges, "$badges")
        assertEquals(600 to 750, badgeProgress("power_750", profileId))
    }

    @Test
    fun `peak power ignores a soft-deleted session's reps`() = runTest {
        insertWorkoutSession(id = "live", totalReps = 10, weightPerCableKg = 50.0)
        insertRepMetric(sessionId = "live", peakPowerWatts = 400.0)
        insertWorkoutSession(id = "deleted", totalReps = 10, weightPerCableKg = 50.0, routineSessionId = "deleted-run")
        insertRepMetric(sessionId = "deleted", peakPowerWatts = 1_500.0)
        database.phoenixDatabaseQueries.softDeleteSessionsByRoutineSessionId(1L, 1L, "deleted-run")

        repository.updateStats(profileId)
        assertEquals(400 to 500, badgeProgress("power_500", profileId))
    }

    @Test
    fun `time-of-day and comeback badges match the old Kotlin filter across local midnight`() = runTest {
        val zone = TimeZone.currentSystemDefault()
        fun local(day: Int, hour: Int, minute: Int, second: Int = 0, nano: Int = 0) =
            LocalDateTime(2026, 6, day, hour, minute, second, nano).toInstant(zone).toEpochMilliseconds()

        // Each fixture is one profile's visible history; every one is checked against the
        // reference (old helper) semantics, including the inclusive vs exclusive hour ends.
        val fixtures = listOf(
            listOf(local(10, 23, 59, 59, 999_000_000), local(11, 0, 0)), // spans midnight
            listOf(local(10, 6, 0)), // early_bird inclusive end (0..6)
            listOf(local(10, 7, 0)),
            listOf(local(10, 6, 59), local(10, 0, 0), local(10, 21, 59)), // dawn_patrol [0,7)
            listOf(local(10, 22, 0)), // night_owl start
            listOf(local(10, 13, 59)), // lunch_lifter inclusive end (11..13)
            listOf(local(10, 14, 0)),
            List(12) { i -> local(1 + i, 5, 30) } + local(20, 6, 45), // dawn_patrol count past target
            listOf(local(1, 23, 59), local(8, 0, 1)), // comeback: 7 local days across midnight
            listOf(local(1, 0, 1), local(7, 23, 59)), // 6 days: no comeback
            listOf(local(1, 12, 0), local(1, 18, 0), local(3, 9, 0)),
        )

        fixtures.forEachIndexed { index, timestamps ->
            setup() // fresh database per fixture
            timestamps.forEachIndexed { i, ts -> insertWorkoutSession(id = "s$index-$i", totalReps = 5, weightPerCableKg = 20.0, timestamp = ts) }
            // Rows the old helpers never counted: another profile, soft-deleted, zero working reps.
            timestamps.forEachIndexed { i, ts ->
                insertWorkoutSession(id = "other$index-$i", totalReps = 5, weightPerCableKg = 20.0, timestamp = ts + 1, profileId = "someone-else")
                insertWorkoutSession(id = "zero$index-$i", totalReps = 0, weightPerCableKg = 20.0, timestamp = ts + 2)
                insertWorkoutSession(id = "del$index-$i", totalReps = 5, weightPerCableKg = 20.0, timestamp = ts + 3, routineSessionId = "del-run-$index")
            }
            // Another profile's workout inside every fixture's gaps (day 4, noon): a leaked profile
            // filter would split the 7-day comeback gap and add a lunch-hour workout.
            insertWorkoutSession(id = "other$index-gap", totalReps = 5, weightPerCableKg = 20.0, timestamp = local(4, 12, 0), profileId = "someone-else")
            // A deleted early-morning row must not create an early_bird / comeback it lacks.
            insertWorkoutSession(id = "del$index-extra", totalReps = 5, weightPerCableKg = 20.0, timestamp = local(28, 3, 0), routineSessionId = "del-run-$index")
            database.phoenixDatabaseQueries.softDeleteSessionsByRoutineSessionId(1L, 1L, "del-run-$index")
            repository.updateStats(profileId)

            val hours = timestamps.map { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).hour }
            fun inclusive(start: Int, end: Int) = hours.any { h -> if (start <= end) h in start..end else h >= start || h <= end }
            fun halfOpenCount(start: Int, end: Int) = hours.count { h -> if (start <= end) h in start until end else h >= start || h < end }
            val days = timestamps.sorted().map { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date.toEpochDays() }
            val comeback = days.zipWithNext().any { (a, b) -> b - a >= 7 }

            val label = "fixture $index hours=$hours"
            assertEquals((if (inclusive(0, 6)) 1 else 0) to 1, badgeProgress("early_bird", profileId), label)
            assertEquals((if (inclusive(22, 24)) 1 else 0) to 1, badgeProgress("night_owl", profileId), label)
            assertEquals((if (inclusive(11, 13)) 1 else 0) to 1, badgeProgress("lunch_lifter", profileId), label)
            assertEquals(halfOpenCount(0, 7) to 10, badgeProgress("dawn_patrol_10", profileId), label)
            assertEquals((if (comeback) 1 else 0) to 1, badgeProgress("comeback_7", profileId), label)

            val awarded = repository.checkAndAwardBadges(profileId).map { it.id }.toSet()
            assertEquals(inclusive(0, 6), "early_bird" in awarded, label)
            assertEquals(halfOpenCount(0, 7) >= 10, "dawn_patrol_10" in awarded, label)
            assertEquals(comeback, "comeback_7" in awarded, label)
        }
    }

    @Test
    fun `weekly workouts badge counts only this profile from the local week start`() = runTest {
        // Old rule: this profile's visible workouts with timestamp >= Monday 00:00 local.
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val weekStart = LocalDate.fromEpochDays(today.toEpochDays() - today.dayOfWeek.ordinal)
            .atStartOfDayIn(zone).toEpochMilliseconds()
        // The SQL week start (SQLite 'localtime' date, converted back with 'utc') is local Monday
        // 00:00. SQLite follows the process zone, not the JVM default, so this is only
        // discriminating on a non-UTC host (the dev machines); under UTC every variant agrees.
        assertEquals(weekStart, database.phoenixDatabaseQueries.selectLocalWeekStartMs().executeAsOne())

        insertWorkoutSession(id = "before-week", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart - 1)
        insertWorkoutSession(id = "at-week-start", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart)
        insertWorkoutSession(id = "after-week-start", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart + 1)
        insertWorkoutSession(id = "in-week", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart + 60_000)
        // Rows the rule never counts.
        insertWorkoutSession(id = "zero-rep", totalReps = 0, weightPerCableKg = 20.0, timestamp = weekStart + 2)
        insertWorkoutSession(id = "deleted", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart + 3, routineSessionId = "deleted-run")
        database.phoenixDatabaseQueries.softDeleteSessionsByRoutineSessionId(1L, 1L, "deleted-run")
        // Another household member trained all week.
        repeat(6) { i ->
            insertWorkoutSession(id = "other-$i", totalReps = 5, weightPerCableKg = 20.0, timestamp = weekStart + 10 + i, profileId = "someone-else")
        }
        repository.updateStats(profileId)
        repository.updateStats("someone-else")

        assertEquals(3 to 5, badgeProgress("weekend_warrior", profileId))
        assertFalse("weekend_warrior" in repository.checkAndAwardBadges(profileId).map { it.id })

        // The other profile earns it from its own six, independently.
        assertEquals(6 to 5, badgeProgress("weekend_warrior", "someone-else"))
        assertTrue("weekend_warrior" in repository.checkAndAwardBadges("someone-else").map { it.id })
    }

    @Test
    fun `single-session volume badge matches the old per-session formula`() = runTest {
        // measured volume wins; else reps x per-cable weight x cable count (null -> 1 cable)
        insertWorkoutSession(id = "measured", totalReps = 10, weightPerCableKg = 100.0, totalVolumeKg = 2_000.9) // winner, truncated
        insertWorkoutSession(id = "two-cable", totalReps = 10, weightPerCableKg = 90.5, cableCount = 2L) // 1810
        insertWorkoutSession(id = "legacy", totalReps = 10, weightPerCableKg = 150.0) // 1500
        insertWorkoutSession(id = "zero-rep", totalReps = 0, weightPerCableKg = 999.0, totalVolumeKg = 99_999.0)
        insertWorkoutSession(id = "other", totalReps = 10, weightPerCableKg = 999.0, profileId = "someone-else")
        repository.updateStats(profileId)

        assertEquals(2_000 to 5_000, badgeProgress("marathon_session", profileId))
    }

    @Test
    fun `badge progress list and award check read earned badges once and stay consistent`() = runTest {
        insertWorkoutSession(id = "session", totalReps = 10, weightPerCableKg = 20.0)
        repository.updateStats(profileId)
        repository.awardBadge("workouts_1", profileId)

        val awarded = repository.checkAndAwardBadges(profileId).map { it.id }
        assertFalse("workouts_1" in awarded, "an already-earned badge is never re-awarded")

        val progress = repository.getAllBadgesWithProgress(profileId).associateBy { it.badge.id }
        assertTrue(progress.getValue("workouts_1").isEarned)
        assertEquals(1 to 1, progress.getValue("workouts_1").currentProgress to progress.getValue("workouts_1").targetProgress)
    }

    private suspend fun badgeProgress(badgeId: String, profileId: String): Pair<Int, Int> {
        val item = repository.getAllBadgesWithProgress(profileId).single { it.badge.id == badgeId }
        return item.currentProgress to item.targetProgress
    }

    private fun insertRepMetric(sessionId: String, peakPowerWatts: Double) {
        database.phoenixDatabaseQueries.insertRepMetric(
            sessionId = sessionId,
            repNumber = 1L,
            isWarmup = 0L,
            startTimestamp = 0L,
            endTimestamp = 1_000L,
            durationMs = 1_000L,
            concentricDurationMs = 500L,
            concentricPositions = "[]",
            concentricLoadsA = "[]",
            concentricLoadsB = "[]",
            concentricVelocities = "[]",
            concentricTimestamps = "[]",
            eccentricDurationMs = 500L,
            eccentricPositions = "[]",
            eccentricLoadsA = "[]",
            eccentricLoadsB = "[]",
            eccentricVelocities = "[]",
            eccentricTimestamps = "[]",
            peakForceA = 0.0,
            peakForceB = 0.0,
            avgForceConcentricA = 0.0,
            avgForceConcentricB = 0.0,
            avgForceEccentricA = 0.0,
            avgForceEccentricB = 0.0,
            peakVelocity = 0.0,
            avgVelocityConcentric = 0.0,
            avgVelocityEccentric = 0.0,
            rangeOfMotionMm = 0.0,
            peakPowerWatts = peakPowerWatts,
            avgPowerWatts = peakPowerWatts / 2,
            updatedAt = null,
            serverId = null,
        )
    }

    private fun insertMetricSample(sessionId: String, power: Double) {
        database.phoenixDatabaseQueries.insertMetric(sessionId, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, power, 0L)
    }

    private fun insertWorkoutSession(
        id: String,
        totalReps: Long,
        weightPerCableKg: Double,
        exerciseId: String? = "bench",
        routineSessionId: String? = null,
        timestamp: Long = 1_000_000L,
        profileId: String = "default",
        totalVolumeKg: Double? = null,
        cableCount: Long? = null,
    ) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = "OldSchool",
            targetReps = totalReps,
            weightPerCableKg = weightPerCableKg,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = totalReps,
            warmupReps = 0L,
            workingReps = totalReps,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = exerciseId,
            exerciseName = exerciseId?.let { "Exercise $it" },
            routineSessionId = routineSessionId,
            routineName = routineSessionId?.let { "Routine $it" },
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = totalVolumeKg,
            cableCount = cableCount,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            routineId = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = profileId,
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }

    private companion object {
        val PEAK_POWER_BADGES = listOf("power_500", "power_750", "power_1000")
    }
}

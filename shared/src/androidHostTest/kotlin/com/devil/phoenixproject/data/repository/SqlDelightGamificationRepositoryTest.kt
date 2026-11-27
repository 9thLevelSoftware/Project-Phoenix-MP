package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.createTestDriver
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
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
    fun `awardBadge stores earned badge and markBadgesCelebrated updates it`() = runTest {
        val awarded = repository.awardBadge("workouts_1", profileId)
        assertTrue(awarded)

        repository.getEarnedBadges(profileId).test {
            val earned = awaitItem()
            assertEquals(1, earned.size)
            assertFalse(earned.single().hasBeenCelebrated)
            cancelAndIgnoreRemainingEvents()
        }

        repository.markBadgesCelebrated(listOf("workouts_1"), profileId)
        repository.getEarnedBadges(profileId).test {
            val earned = awaitItem()
            assertTrue(earned.single().hasBeenCelebrated)
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
    fun `updateStats keeps a streak across early-morning local days`() = runTest {
        // selectWorkoutDates must bucket with SQLite 'localtime', the same zone
        // calculateStreaks uses for "today". An early-morning workout east of UTC
        // falls on the previous UTC day; the next local morning then looks like a
        // two-day gap and the streak breaks. SQLite reads the process zone, not
        // the JVM default, so this only diverges from UTC on a non-UTC host.
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)
        val dayBefore = LocalDate.fromEpochDays(today.toEpochDays() - 2)
        fun atLocal(date: LocalDate, hour: Int, minute: Int) =
            LocalDateTime(date.year, date.month, date.day, hour, minute)
                .toInstant(zone)
                .toEpochMilliseconds()

        insertWorkoutSession(id = "yesterday-early", totalReps = 5, weightPerCableKg = 20.0, timestamp = atLocal(yesterday, 0, 1))
        insertWorkoutSession(id = "day-before-early", totalReps = 5, weightPerCableKg = 20.0, timestamp = atLocal(dayBefore, 0, 1))

        assertEquals(
            listOf(yesterday.toString(), dayBefore.toString()),
            database.phoenixDatabaseQueries.selectWorkoutDates(profileId = profileId).executeAsList(),
        )

        repository.updateStats(profileId)
        repository.getGamificationStats(profileId).test {
            val stats = awaitItem()
            assertEquals(2, stats.currentStreak)
            assertEquals(2, stats.longestStreak)
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
    fun `peak power badges compare RepMetric watts instead of raw MetricSample units`() = runTest {
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

    @Test
    fun `updateStats_and_saveRpgProfile_preserveSyncMetadataAndIsolateCollidingHashCodes`() = runTest {
        assertEquals("profile-Aa".hashCode(), "profile-BB".hashCode())
        val driver = createTestDriver()
        val isolatedDb = PhoenixDatabase(driver)
        val isolatedRepo = SqlDelightGamificationRepository(isolatedDb)
        val q = isolatedDb.phoenixDatabaseQueries

        driver.execute(
            null,
            """
            INSERT INTO GamificationStats(
                id, totalWorkouts, totalReps, totalVolumeKg, longestStreak, currentStreak,
                uniqueExercisesUsed, prsAchieved, lastWorkoutDate, streakStartDate,
                lastUpdated, updatedAt, serverId, profile_id
            ) VALUES (
                77, 1, 5, 100, 1, 1,
                1, 0, NULL, NULL,
                1000, 5000, 'srv-1', 'profile-Aa'
            )
            """.trimIndent(),
            0,
        )
        insertWorkoutSession(
            id = "sess-aa",
            totalReps = 10L,
            weightPerCableKg = 20.0,
            exerciseId = "bench",
            timestamp = 10_000L,
            profileId = "profile-Aa",
            targetDb = isolatedDb,
        )
        insertWorkoutSession(
            id = "sess-bb",
            totalReps = 20L,
            weightPerCableKg = 30.0,
            exerciseId = "squat",
            timestamp = 10_000L,
            profileId = "profile-BB",
            targetDb = isolatedDb,
        )

        isolatedRepo.updateStats("profile-Aa")
        isolatedRepo.updateStats("profile-BB")

        val statsAa = assertNotNull(q.selectGamificationStats("profile-Aa").executeAsOneOrNull())
        val statsBb = assertNotNull(q.selectGamificationStats("profile-BB").executeAsOneOrNull())
        assertEquals(77L, statsAa.id)
        assertEquals("srv-1", statsAa.serverId)
        assertEquals(5_000L, statsAa.updatedAt)
        assertEquals(10L, statsAa.totalReps)
        assertNotEquals(statsAa.id, statsBb.id)
        assertEquals(20L, statsBb.totalReps)

        isolatedRepo.saveRpgProfile(
            RpgProfile(strength = 80, power = 60, stamina = 50, consistency = 70, mastery = 65, characterClass = CharacterClass.POWERLIFTER, lastComputed = 1000L),
            profileId = "profile-Aa",
        )
        isolatedRepo.saveRpgProfile(
            RpgProfile(strength = 20, power = 30, stamina = 40, consistency = 50, mastery = 60, characterClass = CharacterClass.IRONMAN, lastComputed = 2000L),
            profileId = "profile-BB",
        )

        val rpgAa = assertNotNull(q.selectRpgAttributes("profile-Aa").executeAsOneOrNull())
        val rpgBb = assertNotNull(q.selectRpgAttributes("profile-BB").executeAsOneOrNull())
        assertNotEquals(rpgAa.id, rpgBb.id)
        assertEquals(80L, rpgAa.strength)
        assertEquals(20L, rpgBb.strength)
    }

    @Test
    fun `soft-deleted PRs and sessions are excluded from gamification stats RPG inputs and badge progress`() = runTest {
        database.seedExercise(id = "bench", name = "Bench", muscleGroup = "Chest")
        database.seedExercise(id = "squat", name = "Squat", muscleGroup = "Legs")

        val zone = TimeZone.currentSystemDefault()
        // Pick a Saturday in 2026 (2026-06-06 is Saturday) and a Wednesday (2026-06-03).
        fun localMs(day: Int, hour: Int = 12) =
            LocalDateTime(2026, 6, day, hour, 0).toInstant(zone).toEpochMilliseconds()

        // Active workout on Wednesday (Chest, OldSchool)
        insertWorkoutSession(
            id = "live-wed",
            totalReps = 10,
            weightPerCableKg = 40.0,
            exerciseId = "bench",
            routineSessionId = "live-routine",
            timestamp = localMs(3),
            heaviestLiftKg = 40.0,
            workingAvgWeightKg = 40.0,
        )
        // Soft-deleted workout on Saturday (Legs, Echo, heavier/higher volume, routine)
        insertWorkoutSession(
            id = "del-sat",
            totalReps = 20,
            weightPerCableKg = 100.0,
            exerciseId = "squat",
            routineSessionId = "del-routine",
            timestamp = localMs(6),
            mode = "Echo",
            heaviestLiftKg = 100.0,
            workingAvgWeightKg = 100.0,
        )
        database.phoenixDatabaseQueries.softDeleteSession(999L, 999L, "del-sat")

        // One active PR and one soft-deleted PR
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "bench", exerciseName = "Bench", weight = 40.0, reps = 10L,
            oneRepMax = 53.3, achievedAt = 1000L, workoutMode = "OldSchool",
            prType = "MAX_WEIGHT", volume = 400.0, phase = "COMBINED",
            profile_id = profileId, cable_count = 2L, uuid = "pr-live",
        )
        database.phoenixDatabaseQueries.insertRecord(
            exerciseId = "squat", exerciseName = "Squat", weight = 100.0, reps = 5L,
            oneRepMax = 112.5, achievedAt = 2000L, workoutMode = "OldSchool",
            prType = "MAX_WEIGHT", volume = 500.0, phase = "COMBINED",
            profile_id = profileId, cable_count = 2L, uuid = "pr-del",
        )
        val delPrId = database.phoenixDatabaseQueries
            .selectPRIncludingDeleted("squat", "OldSchool", "MAX_WEIGHT", "COMBINED", profileId)
            .executeAsOne().id
        database.phoenixDatabaseQueries.softDeletePRById(999L, 999L, delPrId, profileId)

        repository.updateStats(profileId)
        val rpg = repository.getRpgInput(profileId)
        assertEquals(1, rpg.personalRecords)
        assertEquals(40.0, rpg.maxWeightLiftedKg)
        assertEquals(40.0, rpg.avgWorkingWeightKg)
        assertEquals(1, rpg.trainingDays)

        val q = database.phoenixDatabaseQueries
        assertEquals(1, q.countWorkoutsByMode(profileId).executeAsList().size)
        assertEquals(listOf("OldSchool"), q.selectUniqueWorkoutModes(profileId).executeAsList())
        assertEquals(listOf("Chest"), q.selectUniqueMuscleGroupsFromWorkouts(profileId).executeAsList())
        assertEquals(0L, q.countWeekendWorkouts(profileId).executeAsOne())
        assertEquals(1L, q.countCompletedRoutineSessions(profileId).executeAsOne())
    }

    @Test
    fun `countTrainingDays and countWeekendWorkouts respect localtime`() = runTest {
        val zone = TimeZone.currentSystemDefault()
        // Find a Saturday in June 2026: 2026-06-06 is Saturday, 2026-06-07 is Sunday
        val saturday = LocalDate(2026, 6, 6)
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val satMorning = LocalDateTime(2026, 6, 6, 0, 5).toInstant(zone).toEpochMilliseconds()
        val satNight = LocalDateTime(2026, 6, 6, 23, 55).toInstant(zone).toEpochMilliseconds()
        val sunMorning = LocalDateTime(2026, 6, 7, 0, 5).toInstant(zone).toEpochMilliseconds()

        insertWorkoutSession(id = "sat-1", totalReps = 5, weightPerCableKg = 20.0, timestamp = satMorning)
        insertWorkoutSession(id = "sat-2", totalReps = 5, weightPerCableKg = 20.0, timestamp = satNight)
        insertWorkoutSession(id = "sun-1", totalReps = 5, weightPerCableKg = 20.0, timestamp = sunMorning)

        val q = database.phoenixDatabaseQueries
        assertEquals(2L, q.countTrainingDays(profileId).executeAsOne())
        assertEquals(3L, q.countWeekendWorkouts(profileId).executeAsOne())
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
            power_method = null,
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
        mode: String = "OldSchool",
        heaviestLiftKg: Double? = null,
        workingAvgWeightKg: Double? = null,
        targetDb: PhoenixDatabase = database,
    ) {
        targetDb.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = timestamp,
            mode = mode,
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
            heaviestLiftKg = heaviestLiftKg,
            totalVolumeKg = totalVolumeKg,
            cableCount = cableCount,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = workingAvgWeightKg,
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

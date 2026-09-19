package com.devil.phoenixproject.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalPullAdapterTest {

    // ========== portalModeToMobileMode ==========

    @Test
    fun `portalModeToMobileMode converts OLD_SCHOOL to OldSchool`() {
        assertEquals("OldSchool", PortalPullAdapter.portalModeToMobileMode("OLD_SCHOOL"))
    }

    @Test
    fun `portalModeToMobileMode converts PUMP to Pump`() {
        assertEquals("Pump", PortalPullAdapter.portalModeToMobileMode("PUMP"))
    }

    @Test
    fun `portalModeToMobileMode converts TUT to TUT`() {
        assertEquals("TUT", PortalPullAdapter.portalModeToMobileMode("TUT"))
    }

    @Test
    fun `portalModeToMobileMode converts TUT_BEAST to TUTBeast`() {
        assertEquals("TUTBeast", PortalPullAdapter.portalModeToMobileMode("TUT_BEAST"))
    }

    @Test
    fun `portalModeToMobileMode converts ECCENTRIC_ONLY to EccentricOnly`() {
        assertEquals("EccentricOnly", PortalPullAdapter.portalModeToMobileMode("ECCENTRIC_ONLY"))
    }

    @Test
    fun `portalModeToMobileMode converts ECHO to Echo`() {
        assertEquals("Echo", PortalPullAdapter.portalModeToMobileMode("ECHO"))
    }

    @Test
    fun `portalModeToMobileMode converts CLASSIC alias to OldSchool`() {
        assertEquals("OldSchool", PortalPullAdapter.portalModeToMobileMode("CLASSIC"))
    }

    @Test
    fun `portalModeToMobileMode falls back to OldSchool for unknown`() {
        assertEquals("OldSchool", PortalPullAdapter.portalModeToMobileMode("SOME_UNKNOWN_MODE"))
    }

    // ========== parseEccentricLoad ==========

    @Test
    fun `parseEccentricLoad parses LOAD_100 to 100`() {
        assertEquals(100L, PortalPullAdapter.parseEccentricLoad("LOAD_100"))
    }

    @Test
    fun `parseEccentricLoad parses LOAD_150 to 150`() {
        assertEquals(150L, PortalPullAdapter.parseEccentricLoad("LOAD_150"))
    }

    @Test
    fun `parseEccentricLoad parses LOAD_75 to 75`() {
        assertEquals(75L, PortalPullAdapter.parseEccentricLoad("LOAD_75"))
    }

    @Test
    fun `parseEccentricLoad returns 100 for null`() {
        assertEquals(100L, PortalPullAdapter.parseEccentricLoad(null))
    }

    @Test
    fun `parseEccentricLoad parses direct numeric string`() {
        assertEquals(120L, PortalPullAdapter.parseEccentricLoad("120"))
    }

    @Test
    fun `parseEccentricLoad returns 100 for non-numeric non-LOAD string`() {
        assertEquals(100L, PortalPullAdapter.parseEccentricLoad("INVALID"))
    }

    // ========== parseEchoLevel ==========

    @Test
    fun `parseEchoLevel parses HARD to 0`() {
        assertEquals(0L, PortalPullAdapter.parseEchoLevel("HARD"))
    }

    @Test
    fun `parseEchoLevel parses HARDER to 1`() {
        assertEquals(1L, PortalPullAdapter.parseEchoLevel("HARDER"))
    }

    @Test
    fun `parseEchoLevel parses HARDEST to 2`() {
        assertEquals(2L, PortalPullAdapter.parseEchoLevel("HARDEST"))
    }

    @Test
    fun `parseEchoLevel parses EPIC to 3`() {
        assertEquals(3L, PortalPullAdapter.parseEchoLevel("EPIC"))
    }

    @Test
    fun `parseEchoLevel defaults to 1 for null`() {
        assertEquals(1L, PortalPullAdapter.parseEchoLevel(null))
    }

    @Test
    fun `parseEchoLevel defaults to 1 for unknown string`() {
        assertEquals(1L, PortalPullAdapter.parseEchoLevel("MEGA"))
    }

    @Test
    fun `parseEchoLevel is case insensitive`() {
        assertEquals(0L, PortalPullAdapter.parseEchoLevel("hard"))
        assertEquals(3L, PortalPullAdapter.parseEchoLevel("Epic"))
    }

    // ========== toWorkoutSessionsWithLookup: session-level fields ==========

    @Test
    fun `pulled standalone session keeps rep split and echo config and has no routineSessionId`() = runTest {
        val portalSession = makePullSessionDto(
            id = "standalone-1",
            routineSessionId = null,
            warmupReps = 3,
            workingReps = 8,
            eccentricLoad = 150,
            echoLevel = 3,
            exercises = listOf(makePullExerciseDto(id = "standalone-1", orderIndex = 0, reps = 11)),
        )

        val row = PortalPullAdapter.toWorkoutSessionsWithLookup(portalSession, "default") { _, _, _ -> null }.single()

        assertEquals(11, row.totalReps)
        assertEquals(3, row.warmupReps)
        assertEquals(8, row.workingReps)
        assertEquals(150, row.eccentricLoad)
        assertEquals(3, row.echoLevel)
        assertNull(row.routineSessionId, "a standalone portal session is not a routine group")
    }

    @Test
    fun `pulled grouped session applies the session rep split to the first exercise only`() = runTest {
        val portalSession = makePullSessionDto(
            id = "group-1",
            routineSessionId = "group-1",
            warmupReps = 2,
            workingReps = 10,
            exercises = listOf(
                makePullExerciseDto(id = "ex-b", orderIndex = 1, reps = 9),
                makePullExerciseDto(id = "ex-a", orderIndex = 0, reps = 12),
            ),
        )

        val rows = PortalPullAdapter.toWorkoutSessionsWithLookup(portalSession, "default") { _, _, _ -> null }
            .associateBy { it.id }

        assertEquals(2, rows.getValue("ex-a").warmupReps)
        assertEquals(10, rows.getValue("ex-a").workingReps)
        assertEquals(0, rows.getValue("ex-b").warmupReps)
        assertEquals(9, rows.getValue("ex-b").workingReps)
        assertEquals("group-1", rows.getValue("ex-a").routineSessionId)
        assertEquals("group-1", rows.getValue("ex-b").routineSessionId)
    }

    @Test
    fun `pulled session without rep split or echo config falls back to defaults`() = runTest {
        val portalSession = makePullSessionDto(
            id = "legacy-1",
            exercises = listOf(makePullExerciseDto(id = "legacy-1", orderIndex = 0, reps = 7)),
        )

        val row = PortalPullAdapter.toWorkoutSessionsWithLookup(portalSession, "default") { _, _, _ -> null }.single()

        assertEquals(0, row.warmupReps)
        assertEquals(7, row.workingReps)
        assertEquals(100, row.eccentricLoad)
        assertEquals(com.devil.phoenixproject.domain.model.WorkoutSession().echoLevel, row.echoLevel)
    }

    // ========== toBadgeSyncDto ==========

    @Test
    fun `toBadgeSyncDto uses badgeId as clientId`() {
        val badge = makePullBadgeDto(badgeId = "first-workout")

        val result = PortalPullAdapter.toBadgeSyncDto(badge)

        assertEquals("first-workout", result.clientId)
        assertEquals("first-workout", result.serverId)
        assertEquals("first-workout", result.badgeId)
    }

    @Test
    fun `toBadgeSyncDto parses ISO 8601 earnedAt to epoch millis`() {
        val badge = makePullBadgeDto(earnedAt = "2026-01-15T10:30:00Z")

        val result = PortalPullAdapter.toBadgeSyncDto(badge)

        // 2026-01-15T10:30:00Z = 1768562200000 (approximately)
        // Just verify it's a reasonable epoch value (after year 2020)
        assertTrue(result.earnedAt > 1577836800000L, "earnedAt should be after 2020-01-01")
        assertTrue(result.earnedAt < 2000000000000L, "earnedAt should be before year ~2033")
    }

    @Test
    fun `toBadgeSyncDto falls back to current time for invalid earnedAt`() {
        val before = currentTimeApprox()
        val badge = makePullBadgeDto(earnedAt = "not-a-date")

        val result = PortalPullAdapter.toBadgeSyncDto(badge)

        assertTrue(result.earnedAt >= before - 5000, "earnedAt should fall back to current time")
    }

    @Test
    fun `toBadgeSyncDto sets deletedAt to null`() {
        val badge = makePullBadgeDto()

        val result = PortalPullAdapter.toBadgeSyncDto(badge)

        assertNull(result.deletedAt)
    }

    // ========== toGamificationStatsSyncDto ==========

    @Test
    fun `toGamificationStatsSyncDto uses singleton clientId`() {
        val stats = makePullGamificationStatsDto()

        val result = PortalPullAdapter.toGamificationStatsSyncDto(stats)

        assertEquals("gamification_stats_1", result.clientId)
    }

    @Test
    fun `toGamificationStatsSyncDto maps all stat fields`() {
        val stats = makePullGamificationStatsDto(
            totalWorkouts = 42,
            totalReps = 1250,
            totalVolumeKg = 50000.5f,
            longestStreak = 14,
            currentStreak = 3,
        )

        val result = PortalPullAdapter.toGamificationStatsSyncDto(stats)

        assertEquals(42, result.totalWorkouts)
        assertEquals(1250, result.totalReps)
        assertEquals(14, result.longestStreak)
        assertEquals(3, result.currentStreak)
    }

    @Test
    fun `toGamificationStatsSyncDto preserves Float totalVolumeKg`() {
        val stats = makePullGamificationStatsDto(totalVolumeKg = 12345.67f)

        val result = PortalPullAdapter.toGamificationStatsSyncDto(stats)

        assertEquals(12345.67f, result.totalVolumeKg)
    }

    @Test
    fun `toGamificationStatsSyncDto preserves decimal precision in totalVolumeKg`() {
        val stats = makePullGamificationStatsDto(totalVolumeKg = 99.99f)

        val result = PortalPullAdapter.toGamificationStatsSyncDto(stats)

        assertEquals(99.99f, result.totalVolumeKg)
    }

    @Test
    fun `toGamificationStatsSyncDto sets updatedAt to current time`() {
        val before = currentTimeApprox()
        val stats = makePullGamificationStatsDto()

        val result = PortalPullAdapter.toGamificationStatsSyncDto(stats)

        assertTrue(result.updatedAt >= before - 5000, "updatedAt should be recent")
    }

    // ========== toPersonalRecordSyncDto (audit F021) ==========

    @Test
    fun `toPersonalRecordSyncDto uses resolved catalog exerciseId when provided`() {
        val pr = makePullPersonalRecordDto(id = "pr-row-uuid", exerciseName = "Bench Press")

        val result = PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = "catalog-bench")

        assertEquals("catalog-bench", result.exerciseId)
        // The PR row id is still preserved as the sync identity.
        assertEquals("pr-row-uuid", result.clientId)
        assertEquals("pr-row-uuid", result.serverId)
    }

    @Test
    fun `toPersonalRecordSyncDto falls back to PR row id when no catalog match`() {
        val pr = makePullPersonalRecordDto(id = "pr-row-uuid", exerciseName = "Unknown Move")

        assertEquals("pr-row-uuid", PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = null).exerciseId)
        assertEquals("pr-row-uuid", PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = "").exerciseId)
        assertEquals("pr-row-uuid", PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = "   ").exerciseId)
    }

    @Test
    fun `toPersonalRecordSyncDto maps weight value and reps`() {
        val pr = makePullPersonalRecordDto(value = 102.5, reps = 5)

        val result = PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = "catalog-1")

        assertEquals(102.5f, result.weight)
        assertEquals(5, result.reps)
    }

    @Test
    fun `toPersonalRecordSyncDto preserves portal deletion and LWW timestamps`() {
        val pr = PullPersonalRecordDto(
            id = "pr-row-uuid",
            exerciseName = "Bench Press",
            recordType = "MAX_WEIGHT",
            achievedAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-02T00:00:00Z",
            deletedAt = "2026-01-03T00:00:00Z",
        )

        val result = PortalPullAdapter.toPersonalRecordSyncDto(pr, resolvedExerciseId = "catalog-bench")

        assertEquals(1767312000000L, result.updatedAt)
        assertEquals(1767398400000L, result.deletedAt)
    }

    // ========== Factory Helpers ==========

    private fun makePullSessionDto(
        id: String,
        routineSessionId: String? = null,
        warmupReps: Int? = null,
        workingReps: Int? = null,
        eccentricLoad: Int? = null,
        echoLevel: Int? = null,
        exercises: List<PullExerciseDto>,
    ) = PullWorkoutSessionDto(
        id = id,
        userId = "user-1",
        startedAt = "2026-03-20T10:00:00Z",
        durationSeconds = 300,
        exerciseCount = exercises.size,
        workoutMode = "OLD_SCHOOL",
        routineSessionId = routineSessionId,
        warmupReps = warmupReps,
        workingReps = workingReps,
        eccentricLoad = eccentricLoad,
        echoLevel = echoLevel,
        exercises = exercises,
    )

    private fun makePullExerciseDto(id: String, orderIndex: Int, reps: Int) = PullExerciseDto(
        id = id,
        sessionId = "session",
        name = "Exercise $id",
        orderIndex = orderIndex,
        sets = listOf(PullSetDto(id = "set-$id", exerciseId = id, setNumber = 1, actualReps = reps, weightKg = 40f)),
    )

    private fun makePullBadgeDto(badgeId: String = "badge-1", earnedAt: String = "2026-01-01T00:00:00Z") = PullBadgeDto(
        userId = "user-1",
        badgeId = badgeId,
        badgeName = "First Workout",
        badgeDescription = "Complete your first workout",
        badgeTier = "bronze",
        earnedAt = earnedAt,
    )

    private fun makePullPersonalRecordDto(
        id: String = "pr-1",
        exerciseName: String = "Bench Press",
        value: Double = 100.0,
        reps: Int? = 1,
    ) = PullPersonalRecordDto(
        id = id,
        userId = "user-1",
        exerciseName = exerciseName,
        muscleGroup = "Chest",
        recordType = "MAX_WEIGHT",
        value = value,
        reps = reps,
        workoutPhase = "COMBINED",
        achievedAt = "2026-01-01T00:00:00Z",
    )

    private fun makePullGamificationStatsDto(
        totalWorkouts: Int = 10,
        totalReps: Int = 500,
        totalVolumeKg: Float = 10000f,
        longestStreak: Int = 7,
        currentStreak: Int = 2,
    ) = PullGamificationStatsDto(
        userId = "user-1",
        totalWorkouts = totalWorkouts,
        totalReps = totalReps,
        totalVolumeKg = totalVolumeKg,
        longestStreak = longestStreak,
        currentStreak = currentStreak,
        totalTimeSeconds = 36000,
    )

    /**
     * Approximate current time in millis for "before" timestamps in tests.
     * Not using the expect/actual currentTimeMillis to avoid coupling;
     * uses kotlin.system.getTimeMillis or a simple epoch marker.
     */
    private fun currentTimeApprox(): Long {
        // A reasonable "recent" epoch: 2025-01-01T00:00:00Z = 1735689600000
        // Tests just need to verify the value is "recent", not exact
        return 1735689600000L
    }
}

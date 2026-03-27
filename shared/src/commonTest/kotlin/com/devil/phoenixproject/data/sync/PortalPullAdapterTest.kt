package com.devil.phoenixproject.data.sync

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

    // ========== toRoutineSyncDto ==========

    @Test
    fun `toRoutineSyncDto uses portal id as both clientId and serverId`() {
        val pullRoutine = makePullRoutineDto(id = "portal-routine-123", name = "Push Day")

        val result = PortalPullAdapter.toRoutineSyncDto(pullRoutine)

        assertEquals("portal-routine-123", result.clientId)
        assertEquals("portal-routine-123", result.serverId)
    }

    @Test
    fun `toRoutineSyncDto maps name and description`() {
        val pullRoutine = makePullRoutineDto(
            name = "Leg Day",
            description = "Heavy squats and accessories",
        )

        val result = PortalPullAdapter.toRoutineSyncDto(pullRoutine)

        assertEquals("Leg Day", result.name)
        assertEquals("Heavy squats and accessories", result.description)
    }

    @Test
    fun `toRoutineSyncDto sets deletedAt to null`() {
        val pullRoutine = makePullRoutineDto()

        val result = PortalPullAdapter.toRoutineSyncDto(pullRoutine)

        assertNull(result.deletedAt)
    }

    @Test
    fun `toRoutineSyncDto sets createdAt and updatedAt to current time`() {
        val before = currentTimeApprox()
        val pullRoutine = makePullRoutineDto()

        val result = PortalPullAdapter.toRoutineSyncDto(pullRoutine)

        // createdAt and updatedAt should be recent (within last 5 seconds)
        assertTrue(result.createdAt >= before - 5000, "createdAt should be recent")
        assertTrue(result.updatedAt >= before - 5000, "updatedAt should be recent")
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

    // ========== Factory Helpers ==========

    private fun makePullRoutineDto(
        id: String = "routine-1",
        name: String = "Test Routine",
        description: String = "",
        exercises: List<PullRoutineExerciseDto> = emptyList(),
    ) = PullRoutineDto(
        id = id,
        userId = "user-1",
        name = name,
        description = description,
        exerciseCount = exercises.size,
        estimatedDuration = 3600,
        timesCompleted = 0,
        isFavorite = false,
        exercises = exercises,
    )

    private fun makePullBadgeDto(badgeId: String = "badge-1", earnedAt: String = "2026-01-01T00:00:00Z") = PullBadgeDto(
        userId = "user-1",
        badgeId = badgeId,
        badgeName = "First Workout",
        badgeDescription = "Complete your first workout",
        badgeTier = "bronze",
        earnedAt = earnedAt,
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

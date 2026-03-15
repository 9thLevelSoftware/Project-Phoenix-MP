package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.currentTimeMillis

/**
 * Converts portal pull response DTOs (camelCase) to legacy merge DTOs (used by SyncRepository merge methods).
 *
 * This is the inverse of PortalSyncAdapter (which converts mobile → portal for push).
 * Only converts data types that are actually merged during pull:
 *   - Routines (with exercises) → RoutineSyncDto
 *   - Badges → EarnedBadgeSyncDto
 *   - Gamification stats → GamificationStatsSyncDto
 *
 * Sessions are SKIPPED during pull (immutable/push-only per PULL-03).
 * RPG attributes are handled directly via GamificationRepository (no legacy DTO needed).
 */
object PortalPullAdapter {

    /**
     * Convert portal routine DTO to legacy RoutineSyncDto for merge.
     * Note: routine exercises are NOT part of RoutineSyncDto — they're handled
     * separately by SyncRepository.mergePortalRoutines().
     */
    fun toRoutineSyncDto(routine: PullRoutineDto): RoutineSyncDto {
        val now = currentTimeMillis()
        return RoutineSyncDto(
            clientId = routine.id,
            serverId = routine.id, // Portal ID IS the server ID
            name = routine.name,
            description = routine.description,
            deletedAt = null,
            createdAt = now, // Portal doesn't track created_at on routines
            updatedAt = now  // Portal doesn't track updated_at on routines
        )
    }

    /**
     * Convert portal badge DTO to legacy EarnedBadgeSyncDto for merge.
     */
    fun toBadgeSyncDto(badge: PullBadgeDto): EarnedBadgeSyncDto {
        val earnedAtEpoch = try {
            kotlin.time.Instant.parse(badge.earnedAt).toEpochMilliseconds()
        } catch (_: Exception) {
            currentTimeMillis()
        }
        val now = currentTimeMillis()
        return EarnedBadgeSyncDto(
            clientId = badge.badgeId, // Use badgeId as clientId (badges are identified by badgeId)
            serverId = badge.badgeId,
            badgeId = badge.badgeId,
            earnedAt = earnedAtEpoch,
            deletedAt = null,
            createdAt = earnedAtEpoch,
            updatedAt = now
        )
    }

    /**
     * Convert portal gamification stats DTO to legacy GamificationStatsSyncDto for merge.
     */
    fun toGamificationStatsSyncDto(stats: PullGamificationStatsDto): GamificationStatsSyncDto {
        val now = currentTimeMillis()
        return GamificationStatsSyncDto(
            clientId = "gamification_stats_1", // Singleton row
            totalWorkouts = stats.totalWorkouts,
            totalReps = stats.totalReps,
            totalVolumeKg = stats.totalVolumeKg.toInt(), // Legacy DTO uses Int
            longestStreak = stats.longestStreak,
            currentStreak = stats.currentStreak,
            updatedAt = now
        )
    }

    /**
     * Convert portal SCREAMING_SNAKE mode string to mobile DB format.
     * Portal sends "OLD_SCHOOL", mobile stores "OldSchool".
     */
    fun portalModeToMobileMode(portalMode: String): String {
        return when (ProgramMode.fromSyncString(portalMode)) {
            ProgramMode.OldSchool -> "OldSchool"
            ProgramMode.Pump -> "Pump"
            ProgramMode.TUT -> "TUT"
            ProgramMode.TUTBeast -> "TUTBeast"
            ProgramMode.EccentricOnly -> "EccentricOnly"
            ProgramMode.Echo -> "Echo"
            null -> "OldSchool"
        }
    }

    /**
     * Parse portal eccentricLoad string to integer percentage.
     * Portal sends enum names like "LOAD_100", "LOAD_150", or null.
     */
    fun parseEccentricLoad(portalValue: String?): Long {
        if (portalValue == null) return 100L
        // Try parsing "LOAD_XXX" format
        val numericPart = portalValue.removePrefix("LOAD_").toLongOrNull()
        if (numericPart != null) return numericPart
        // Try direct numeric
        return portalValue.toLongOrNull() ?: 100L
    }

    /**
     * Parse portal echoLevel string to integer index.
     * Portal sends enum names like "HARD", "HARDER", "HARDEST", "EPIC", or null.
     */
    fun parseEchoLevel(portalValue: String?): Long {
        return when (portalValue?.uppercase()) {
            "HARD" -> 0L
            "HARDER" -> 1L
            "HARDEST" -> 2L
            "EPIC" -> 3L
            else -> 1L // Default HARDER
        }
    }
}

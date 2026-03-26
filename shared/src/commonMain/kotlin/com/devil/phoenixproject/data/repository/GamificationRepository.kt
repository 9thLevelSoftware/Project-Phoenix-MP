package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for gamification (badges and streaks)
 * All methods are profile-scoped to support multi-profile isolation.
 */
interface GamificationRepository {
    /**
     * Get all earned badges as a flow
     */
    fun getEarnedBadges(profileId: String): Flow<List<EarnedBadge>>

    /**
     * Get current streak information as a flow
     */
    fun getStreakInfo(profileId: String): Flow<StreakInfo>

    /**
     * Get gamification statistics as a flow
     */
    fun getGamificationStats(profileId: String): Flow<GamificationStats>

    /**
     * Get uncelebrated badges (badges user hasn't seen celebration for)
     */
    fun getUncelebratedBadges(profileId: String): Flow<List<EarnedBadge>>

    /**
     * Check if a badge has been earned
     */
    suspend fun isBadgeEarned(badgeId: String, profileId: String): Boolean

    /**
     * Award a badge to the user
     * @return true if badge was newly awarded, false if already earned
     */
    suspend fun awardBadge(badgeId: String, profileId: String): Boolean

    /**
     * Mark a badge as celebrated (user has seen the celebration)
     */
    suspend fun markBadgeCelebrated(badgeId: String, profileId: String)

    /**
     * Mark multiple badges as celebrated in a single transaction
     * @param badgeIds List of badge IDs to mark as celebrated
     */
    suspend fun markBadgesCelebrated(badgeIds: List<String>, profileId: String)

    /**
     * Update gamification stats after a workout
     * This recalculates all stats from the database
     */
    suspend fun updateStats(profileId: String)

    /**
     * Check all badges and award any newly earned ones
     * @return List of newly awarded badges
     */
    suspend fun checkAndAwardBadges(profileId: String): List<Badge>

    /**
     * Gather aggregate workout data for RPG attribute computation.
     * Queries across WorkoutSession, RepMetric, GamificationStats, PersonalRecord, EarnedBadge.
     */
    suspend fun getRpgInput(profileId: String): RpgInput

    /**
     * Persist a computed RPG profile to the RpgAttributes table.
     */
    suspend fun saveRpgProfile(profile: RpgProfile, profileId: String)

    /**
     * Get progress toward a specific badge
     * @return Pair of (current progress, target) or null if badge not found
     */
    suspend fun getBadgeProgress(badgeId: String, profileId: String): Pair<Int, Int>?

    /**
     * Get all badges with their earned status and progress
     */
    suspend fun getAllBadgesWithProgress(profileId: String): List<BadgeWithProgress>
}

/**
 * Badge with earned status and progress information
 */
data class BadgeWithProgress(
    val badge: Badge,
    val isEarned: Boolean,
    val earnedAt: Long? = null,
    val currentProgress: Int,
    val targetProgress: Int
) {
    val progressPercent: Float
        get() = if (targetProgress > 0) {
            (currentProgress.toFloat() / targetProgress).coerceIn(0f, 1f)
        } else 0f
}

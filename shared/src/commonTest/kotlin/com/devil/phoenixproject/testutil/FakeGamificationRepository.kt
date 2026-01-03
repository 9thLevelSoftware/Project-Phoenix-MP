package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.EarnedBadge
import com.devil.phoenixproject.domain.model.GamificationStats
import com.devil.phoenixproject.domain.model.StreakInfo
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake GamificationRepository for testing.
 * Provides controllable badge and streak state.
 */
class FakeGamificationRepository : GamificationRepository {

    private val earnedBadges = mutableMapOf<String, EarnedBadge>()
    private val uncelebratedBadgeIds = mutableSetOf<String>()
    private val badgeProgress = mutableMapOf<String, Pair<Int, Int>>()

    private val _earnedBadgesFlow = MutableStateFlow<List<EarnedBadge>>(emptyList())
    private val _streakInfoFlow = MutableStateFlow(StreakInfo.EMPTY)
    private val _gamificationStatsFlow = MutableStateFlow(GamificationStats())
    private val _uncelebratedBadgesFlow = MutableStateFlow<List<EarnedBadge>>(emptyList())

    // Badges that will be awarded on next checkAndAwardBadges call
    var pendingBadges = mutableListOf<Badge>()

    // Test control methods
    fun setStreakInfo(info: StreakInfo) {
        _streakInfoFlow.value = info
    }

    fun setGamificationStats(stats: GamificationStats) {
        _gamificationStatsFlow.value = stats
    }

    fun addEarnedBadge(badge: EarnedBadge, celebrated: Boolean = true) {
        earnedBadges[badge.badgeId] = badge
        if (!celebrated) {
            uncelebratedBadgeIds.add(badge.badgeId)
        }
        updateFlows()
    }

    fun setBadgeProgress(badgeId: String, current: Int, target: Int) {
        badgeProgress[badgeId] = current to target
    }

    fun reset() {
        earnedBadges.clear()
        uncelebratedBadgeIds.clear()
        badgeProgress.clear()
        pendingBadges.clear()
        _earnedBadgesFlow.value = emptyList()
        _streakInfoFlow.value = StreakInfo.EMPTY
        _gamificationStatsFlow.value = GamificationStats()
        _uncelebratedBadgesFlow.value = emptyList()
    }

    private fun updateFlows() {
        _earnedBadgesFlow.value = earnedBadges.values.toList()
        _uncelebratedBadgesFlow.value = earnedBadges.values
            .filter { uncelebratedBadgeIds.contains(it.badgeId) }
    }

    // ========== GamificationRepository interface implementation ==========

    override fun getEarnedBadges(): Flow<List<EarnedBadge>> = _earnedBadgesFlow

    override fun getStreakInfo(): Flow<StreakInfo> = _streakInfoFlow

    override fun getGamificationStats(): Flow<GamificationStats> = _gamificationStatsFlow

    override fun getUncelebratedBadges(): Flow<List<EarnedBadge>> = _uncelebratedBadgesFlow

    override suspend fun isBadgeEarned(badgeId: String): Boolean {
        return earnedBadges.containsKey(badgeId)
    }

    override suspend fun awardBadge(badgeId: String): Boolean {
        if (earnedBadges.containsKey(badgeId)) {
            return false
        }
        earnedBadges[badgeId] = EarnedBadge(
            badgeId = badgeId,
            earnedAt = currentTimeMillis(),
            celebratedAt = null
        )
        uncelebratedBadgeIds.add(badgeId)
        updateFlows()
        return true
    }

    override suspend fun markBadgeCelebrated(badgeId: String) {
        uncelebratedBadgeIds.remove(badgeId)
        earnedBadges[badgeId]?.let { badge ->
            earnedBadges[badgeId] = badge.copy(celebratedAt = currentTimeMillis())
        }
        updateFlows()
    }

    override suspend fun updateStats() {
        // No-op in fake - stats are set directly via setGamificationStats
    }

    override suspend fun checkAndAwardBadges(): List<Badge> {
        val awarded = pendingBadges.toList()
        pendingBadges.forEach { badge ->
            earnedBadges[badge.id] = EarnedBadge(
                badgeId = badge.id,
                earnedAt = currentTimeMillis(),
                celebratedAt = null
            )
            uncelebratedBadgeIds.add(badge.id)
        }
        pendingBadges.clear()
        updateFlows()
        return awarded
    }

    override suspend fun getBadgeProgress(badgeId: String): Pair<Int, Int>? {
        return badgeProgress[badgeId]
    }

    override suspend fun getAllBadgesWithProgress(): List<BadgeWithProgress> {
        return BadgeDefinitions.allBadges.map { badge ->
            val progress = badgeProgress[badge.id] ?: (0 to badge.getTargetValue())
            val earned = earnedBadges[badge.id]
            BadgeWithProgress(
                badge = badge,
                isEarned = earned != null,
                earnedAt = earned?.earnedAt,
                currentProgress = progress.first,
                targetProgress = progress.second
            )
        }
    }
}

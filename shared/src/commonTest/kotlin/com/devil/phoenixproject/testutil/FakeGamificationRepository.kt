package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.EarnedBadge
import com.devil.phoenixproject.domain.model.GamificationStats
import com.devil.phoenixproject.domain.model.RpgInput
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.domain.model.StreakInfo
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake GamificationRepository for testing.
 * Provides controllable badge, streak, stats, and RPG state scoped by profileId.
 */
class FakeGamificationRepository : GamificationRepository {

    private val earnedBadgesByProfile = mutableMapOf<String, MutableMap<String, EarnedBadge>>()
    private val badgeProgressByProfile = mutableMapOf<String, MutableMap<String, Pair<Int, Int>>>()
    private val rpgProfiles = mutableMapOf<String, RpgProfile>()
    private val pendingBadgesByProfile = mutableMapOf<String, MutableList<Badge>>()

    private val earnedBadgesFlows = mutableMapOf<String, MutableStateFlow<List<EarnedBadge>>>()
    private val streakInfoFlows = mutableMapOf<String, MutableStateFlow<StreakInfo>>()
    private val gamificationStatsFlows = mutableMapOf<String, MutableStateFlow<GamificationStats>>()

    // Legacy single-profile fallback for callers that assign pendingBadges directly (default profile).
    var pendingBadges = mutableListOf<Badge>()

    var updateStatsCallCount = 0
    var checkAndAwardBadgesCallCount = 0
    val badgeLookupProfileIds = mutableListOf<String>()
    val updateStatsProfileIds = mutableListOf<String>()

    // Every markBadgesCelebrated invocation, in order (badge IDs + profile id),
    // including empty-ID calls that the fake itself ignores.
    val markBadgesCelebratedCalls = mutableListOf<List<String>>()
    val markBadgesCelebratedProfileIds = mutableListOf<String>()

    // Captured RPG profile from the most recent saveRpgProfile call (preserved for existing single-profile tests).
    var savedRpgProfile: RpgProfile? = null
    var saveRpgProfileCallCount = 0
    var saveRpgProfileFailure: Throwable? = null

    // Test control methods
    fun setBadgeProgress(badgeId: String, current: Int, target: Int, profileId: String = "default") {
        badgeProgressByProfile.getOrPut(profileId) { mutableMapOf() }[badgeId] = current to target
    }

    fun setPendingBadges(profileId: String, badges: List<Badge>) {
        pendingBadgesByProfile[profileId] = badges.toMutableList()
    }

    fun setStreakInfo(profileId: String, info: StreakInfo) {
        streakInfoFlowFor(profileId).value = info
    }

    fun setGamificationStats(profileId: String, stats: GamificationStats) {
        gamificationStatsFlowFor(profileId).value = stats
    }

    fun getSavedRpgProfile(profileId: String): RpgProfile? = rpgProfiles[profileId]

    private fun earnedBadgesFor(profileId: String): MutableMap<String, EarnedBadge> =
        earnedBadgesByProfile.getOrPut(profileId) { mutableMapOf() }

    private fun earnedBadgesFlowFor(profileId: String): MutableStateFlow<List<EarnedBadge>> =
        earnedBadgesFlows.getOrPut(profileId) { MutableStateFlow(earnedBadgesFor(profileId).values.toList()) }

    private fun streakInfoFlowFor(profileId: String): MutableStateFlow<StreakInfo> =
        streakInfoFlows.getOrPut(profileId) { MutableStateFlow(StreakInfo.EMPTY) }

    private fun gamificationStatsFlowFor(profileId: String): MutableStateFlow<GamificationStats> =
        gamificationStatsFlows.getOrPut(profileId) { MutableStateFlow(GamificationStats()) }

    private fun updateFlows(profileId: String) {
        earnedBadgesFlowFor(profileId).value = earnedBadgesFor(profileId).values.toList()
    }

    // ========== GamificationRepository interface implementation ==========

    override fun getEarnedBadges(profileId: String): Flow<List<EarnedBadge>> = earnedBadgesFlowFor(profileId)

    override fun getStreakInfo(profileId: String): Flow<StreakInfo> = streakInfoFlowFor(profileId)

    override fun getGamificationStats(profileId: String): Flow<GamificationStats> = gamificationStatsFlowFor(profileId)

    override suspend fun isBadgeEarned(badgeId: String, profileId: String): Boolean {
        badgeLookupProfileIds += profileId
        return earnedBadgesFor(profileId).containsKey(badgeId)
    }

    override suspend fun awardBadge(badgeId: String, profileId: String): Boolean {
        val earned = earnedBadgesFor(profileId)
        if (earned.containsKey(badgeId)) {
            return false
        }
        earned[badgeId] = EarnedBadge(
            badgeId = badgeId,
            earnedAt = currentTimeMillis(),
            celebratedAt = null,
        )
        updateFlows(profileId)
        return true
    }

    override suspend fun markBadgesCelebrated(badgeIds: List<String>, profileId: String) {
        markBadgesCelebratedCalls += badgeIds
        markBadgesCelebratedProfileIds += profileId
        if (badgeIds.isEmpty()) return
        val earned = earnedBadgesFor(profileId)
        val now = currentTimeMillis()
        badgeIds.forEach { badgeId ->
            earned[badgeId]?.let { badge ->
                earned[badgeId] = badge.copy(celebratedAt = now)
            }
        }
        updateFlows(profileId)
    }

    override suspend fun getRpgInput(profileId: String): RpgInput = RpgInput(
        maxWeightLiftedKg = 0.0, totalVolumeKg = 0.0, totalWorkouts = 0,
        totalReps = 0, uniqueExercises = 0, personalRecords = 0,
        peakPowerWatts = 0.0, avgWorkingWeightKg = 0.0,
        currentStreak = 0, longestStreak = 0, trainingDays = 0, badgesEarned = 0,
    )

    override suspend fun saveRpgProfile(profile: RpgProfile, profileId: String) {
        saveRpgProfileCallCount++
        saveRpgProfileFailure?.let { throw it }
        savedRpgProfile = profile
        rpgProfiles[profileId] = profile
    }

    override suspend fun updateStats(profileId: String) {
        updateStatsCallCount++
        updateStatsProfileIds += profileId
    }

    override suspend fun checkAndAwardBadges(profileId: String): List<Badge> {
        checkAndAwardBadgesCallCount++
        val profilePending = pendingBadgesByProfile[profileId]
        val awarded = if (!profilePending.isNullOrEmpty()) {
            profilePending.toList().also { profilePending.clear() }
        } else if (profileId == "default") {
            pendingBadges.toList().also { pendingBadges.clear() }
        } else {
            emptyList()
        }
        val earned = earnedBadgesFor(profileId)
        awarded.forEach { badge ->
            earned[badge.id] = EarnedBadge(
                badgeId = badge.id,
                earnedAt = currentTimeMillis(),
                celebratedAt = null,
            )
        }
        updateFlows(profileId)
        return awarded
    }

    override suspend fun getAllBadgesWithProgress(profileId: String): List<BadgeWithProgress> {
        val progressMap = badgeProgressByProfile[profileId].orEmpty()
        val earnedMap = earnedBadgesFor(profileId)
        return BadgeDefinitions.allBadges.map { badge ->
            val progress = progressMap[badge.id] ?: (0 to badge.getTargetValue())
            val earned = earnedMap[badge.id]
            BadgeWithProgress(
                badge = badge,
                isEarned = earned != null,
                earnedAt = earned?.earnedAt,
                currentProgress = progress.first,
                targetProgress = progress.second,
            )
        }
    }
}

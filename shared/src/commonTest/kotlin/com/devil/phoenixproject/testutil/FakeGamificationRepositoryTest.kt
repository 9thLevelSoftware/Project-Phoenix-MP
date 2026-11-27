package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.GamificationStats
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.domain.model.StreakInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeGamificationRepositoryTest {

    @Test
    fun badgesStatsStreakAndRpgAreIsolatedByProfileId() = runTest {
        val repository = FakeGamificationRepository()
        val badgeA = BadgeDefinitions.allBadges.first()
        val badgeB = BadgeDefinitions.allBadges.drop(1).first()

        assertTrue(repository.awardBadge(badgeA.id, "profile-a"))
        assertTrue(repository.isBadgeEarned(badgeA.id, "profile-a"))
        assertFalse(repository.isBadgeEarned(badgeA.id, "profile-b"))
        assertEquals(listOf(badgeA.id), repository.getEarnedBadges("profile-a").first().map { it.badgeId })
        assertTrue(repository.getEarnedBadges("profile-b").first().isEmpty())

        repository.markBadgesCelebrated(listOf(badgeA.id), "profile-b")
        assertNull(repository.getEarnedBadges("profile-a").first().single().celebratedAt)
        repository.markBadgesCelebrated(listOf(badgeA.id), "profile-a")
        assertNotNull(repository.getEarnedBadges("profile-a").first().single().celebratedAt)

        repository.setBadgeProgress(badgeA.id, 3, 5, profileId = "profile-a")
        val progressA = repository.getAllBadgesWithProgress("profile-a").single { it.badge.id == badgeA.id }
        val progressB = repository.getAllBadgesWithProgress("profile-b").single { it.badge.id == badgeA.id }
        assertEquals(3, progressA.currentProgress)
        assertEquals(0, progressB.currentProgress)

        repository.setPendingBadges("profile-b", listOf(badgeB))
        assertTrue(repository.checkAndAwardBadges("profile-a").isEmpty())
        assertEquals(listOf(badgeB.id), repository.checkAndAwardBadges("profile-b").map { it.id })

        val rpgA = RpgProfile(strength = 90, power = 10, stamina = 10, consistency = 10, mastery = 10, characterClass = CharacterClass.POWERLIFTER, lastComputed = 10L)
        val rpgB = RpgProfile(strength = 30, power = 10, stamina = 80, consistency = 10, mastery = 10, characterClass = CharacterClass.IRONMAN, lastComputed = 20L)
        repository.saveRpgProfile(rpgA, "profile-a")
        repository.saveRpgProfile(rpgB, "profile-b")
        assertEquals(rpgA, repository.getSavedRpgProfile("profile-a"))
        assertEquals(rpgB, repository.getSavedRpgProfile("profile-b"))

        repository.setStreakInfo("profile-a", StreakInfo(currentStreak = 5, longestStreak = 10, streakStartDate = 1L, lastWorkoutDate = 2L, isAtRisk = false))
        repository.setGamificationStats("profile-a", GamificationStats(totalWorkouts = 12))
        assertEquals(5, repository.getStreakInfo("profile-a").first().currentStreak)
        assertEquals(0, repository.getStreakInfo("profile-b").first().currentStreak)
        assertEquals(12, repository.getGamificationStats("profile-a").first().totalWorkouts)
        assertEquals(0, repository.getGamificationStats("profile-b").first().totalWorkouts)
    }

    @Test
    fun legacyPendingBadgesFallbackOnlyAppliesToDefaultProfile() = runTest {
        val repository = FakeGamificationRepository()
        val badgeA = BadgeDefinitions.allBadges.first()

        repository.pendingBadges = mutableListOf(badgeA)

        assertTrue(repository.checkAndAwardBadges("profile-b").isEmpty())
        assertFalse(repository.isBadgeEarned(badgeA.id, "profile-b"))
        assertEquals(listOf(badgeA.id), repository.checkAndAwardBadges("default").map { it.id })
        assertTrue(repository.isBadgeEarned(badgeA.id, "default"))
        assertTrue(repository.pendingBadges.isEmpty())
    }
}

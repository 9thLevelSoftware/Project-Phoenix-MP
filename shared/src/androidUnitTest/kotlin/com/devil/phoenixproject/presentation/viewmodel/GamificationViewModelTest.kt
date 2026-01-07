package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.domain.model.BadgeRequirement
import com.devil.phoenixproject.domain.model.BadgeTier
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamificationViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: FakeGamificationRepository
    private lateinit var viewModel: GamificationViewModel

    @Before
    fun setup() {
        repository = FakeGamificationRepository()
        viewModel = GamificationViewModel(repository)
    }

    @Test
    fun `loadBadges populates badge list`() = runTest {
        repository.setBadgeProgress("workouts_1", current = 1, target = 1)

        viewModel.loadBadges()
        advanceUntilIdle()

        assertEquals(BadgeDefinitions.totalBadgeCount, viewModel.badgesWithProgress.value.size)
        assertTrue(viewModel.badgesWithProgress.value.any { it.badge.id == "workouts_1" })
    }

    @Test
    fun `selectCategory filters badges`() = runTest {
        viewModel.loadBadges()
        advanceUntilIdle()

        viewModel.selectCategory(BadgeCategory.DEDICATION)
        advanceUntilIdle()

        assertTrue(viewModel.filteredBadges.value.all { it.badge.category == BadgeCategory.DEDICATION })
    }

    @Test
    fun `updateAndCheckBadges returns newly awarded badges`() = runTest {
        val badge = Badge(
            id = "test_badge",
            name = "Test",
            description = "Test",
            category = BadgeCategory.DEDICATION,
            iconResource = "icon",
            tier = BadgeTier.BRONZE,
            requirement = BadgeRequirement.TotalWorkouts(1)
        )
        repository.pendingBadges.add(badge)

        val awarded = viewModel.updateAndCheckBadges()

        assertEquals(1, awarded.size)
        assertEquals("test_badge", awarded.first().id)
    }
}

package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.BadgeCategory
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GamificationViewModelTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var repository: FakeGamificationRepository
    private lateinit var viewModel: GamificationViewModel

    @Before
    fun setup() {
        repository = FakeGamificationRepository()
        viewModel = GamificationViewModel(repository, FakeUserProfileRepository())
    }

    @Test
    fun `initial load populates badge list`() = runTest {
        repository.setBadgeProgress("workouts_1", current = 1, target = 1)

        advanceUntilIdle()

        assertEquals(BadgeDefinitions.totalBadgeCount, viewModel.badgesWithProgress.value.size)
        assertTrue(viewModel.badgesWithProgress.value.any { it.badge.id == "workouts_1" })
    }

    @Test
    fun `selectCategory filters badges`() = runTest {
        advanceUntilIdle()

        viewModel.selectCategory(BadgeCategory.DEDICATION)
        advanceUntilIdle()

        assertTrue(
            viewModel.filteredBadges.value.all {
                it.badge.category ==
                    BadgeCategory.DEDICATION
            },
        )
    }
}

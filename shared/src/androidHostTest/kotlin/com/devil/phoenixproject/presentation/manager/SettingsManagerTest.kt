package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsManagerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakePreferencesManager: FakePreferencesManager
    private lateinit var fakeBleRepository: FakeBleRepository

    @Before
    fun setup() {
        fakePreferencesManager = FakePreferencesManager()
        fakeBleRepository = FakeBleRepository()
    }

    @Test
    fun `autoplayEnabled derives from summary countdown seconds`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)

            assertTrue(manager.autoplayEnabled.value)

            fakePreferencesManager.setPreferences(UserPreferences(summaryCountdownSeconds = 0))
            advanceUntilIdle()
            assertFalse(manager.autoplayEnabled.value)

            fakePreferencesManager.setPreferences(UserPreferences(summaryCountdownSeconds = -1))
            advanceUntilIdle()
            assertTrue(manager.autoplayEnabled.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `setWeightUnit updates preference-backed flows`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)

            manager.setWeightUnit(WeightUnit.KG)
            advanceUntilIdle()

            assertEquals(WeightUnit.KG, fakePreferencesManager.preferencesFlow.value.weightUnit)
            assertEquals(WeightUnit.KG, manager.userPreferences.value.weightUnit)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `weight conversion and formatting preserves legacy behavior`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)

            assertEquals(22.0462f, manager.kgToDisplay(10f, WeightUnit.LB), 0.0001f)
            assertEquals(10f, manager.displayToKg(22.0462f, WeightUnit.LB), 0.001f)
            assertEquals("10 kg", manager.formatWeight(10f, WeightUnit.KG))
            assertEquals("22.05 lb", manager.formatWeight(10f, WeightUnit.LB))
        } finally {
            managerScope.cancel()
        }
    }
}

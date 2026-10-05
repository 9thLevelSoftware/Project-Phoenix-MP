package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.util.UnitConverter
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsManagerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakePreferencesManager: FakePreferencesManager
    private lateinit var fakeProfileRepository: FakeUserProfileRepository

    @Before
    fun setup() {
        fakePreferencesManager = FakePreferencesManager()
        fakeProfileRepository = FakeUserProfileRepository().apply { setActiveProfileForTest() }
    }

    @Test
    fun `autoplayEnabled derives from summary countdown seconds`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertTrue(manager.autoplayEnabled.value)

            updateActiveWorkout { it.copy(summaryCountdownSeconds = 0) }
            advanceUntilIdle()
            assertFalse(manager.autoplayEnabled.value)

            updateActiveWorkout { it.copy(summaryCountdownSeconds = -1) }
            advanceUntilIdle()
            assertTrue(manager.autoplayEnabled.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `profile weight unit overlays the global preference store`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)
            val ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)

            fakeProfileRepository.updateCore(
                ready.profile.id,
                ready.preferences.core.value.copy(weightUnit = WeightUnit.KG),
            )
            advanceUntilIdle()

            assertEquals(WeightUnit.KG, readyProfile().preferences.core.value.weightUnit)
            assertEquals(WeightUnit.LB, fakePreferencesManager.preferencesFlow.value.weightUnit)
            assertEquals(WeightUnit.KG, manager.userPreferences.value.weightUnit)
            assertEquals(WeightUnit.KG, manager.weightUnit.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `profile scaling basis overlays the active VBT document`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertEquals(ScalingBasis.MAX_WEIGHT_PR, manager.defaultScalingBasis.value)

            val ready = readyProfile()
            fakeProfileRepository.updateVbt(
                ready.profile.id,
                ready.preferences.vbt.value.copy(defaultScalingBasis = ScalingBasis.ESTIMATED_1RM),
            )
            advanceUntilIdle()

            assertEquals(ScalingBasis.ESTIMATED_1RM, readyProfile().preferences.vbt.value.defaultScalingBasis)
            assertEquals(ScalingBasis.ESTIMATED_1RM, manager.defaultScalingBasis.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `routine exercise percent defaults are off and eighty percent`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertFalse(manager.defaultRoutineExerciseUsePercentOfPR.value)
            assertEquals(80, manager.defaultRoutineExerciseWeightPercentOfPR.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `velocity backfill flag follows the global preference store`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertFalse(manager.velocityOneRepMaxBackfillDone.value)

            fakePreferencesManager.setVelocityOneRepMaxBackfillDone(true)
            advanceUntilIdle()

            assertTrue(fakePreferencesManager.preferencesFlow.value.velocityOneRepMaxBackfillDone)
            assertTrue(manager.velocityOneRepMaxBackfillDone.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `weight conversion and formatting preserves legacy behavior`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            val tenKgInLb = 10f * UnitConverter.KG_TO_LB
            assertEquals(tenKgInLb, manager.kgToDisplay(10f, WeightUnit.LB), 0.0001f)
            // Round trip divides by KG_TO_LB. lbToKg is not the inverse and misses 10f
            // by ~2e-5, outside this tolerance.
            assertEquals(10f, manager.displayToKg(tenKgInLb, WeightUnit.LB), 0.000001f)
            assertEquals("10 kg", manager.formatWeight(10f, WeightUnit.KG))
            // 10 kg * 2.20462 formats to two decimals as 22.05 lb.
            assertEquals("22.05 lb", manager.formatWeight(10f, WeightUnit.LB))
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `initial compatibility value overlays an already Ready profile synchronously`() = runTest {
        fakePreferencesManager.setPreferences(
            UserPreferences(
                bodyWeightKg = 140f,
                summaryCountdownSeconds = 25,
                velocityLossThresholdPercent = 11,
            ),
        )
        var ready = readyProfile()
        fakeProfileRepository.updateCore(
            ready.profile.id,
            ready.preferences.core.value.copy(
                weightUnit = WeightUnit.LB,
                bodyWeightKg = 83f,
            ),
        )
        ready = readyProfile()
        fakeProfileRepository.updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(
                summaryCountdownSeconds = 0,
                gamificationEnabled = false,
            ),
        )
        ready = readyProfile()
        fakeProfileRepository.updateVbt(
            ready.profile.id,
            ready.preferences.vbt.value.copy(velocityLossThresholdPercent = 37),
        )
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertEquals(83f, manager.userPreferences.value.bodyWeightKg)
            assertEquals(0, manager.userPreferences.value.summaryCountdownSeconds)
            assertEquals(37, manager.userPreferences.value.velocityLossThresholdPercent)
            assertEquals(WeightUnit.LB, manager.weightUnit.value)
            assertFalse(manager.gamificationEnabled.value)
            assertFalse(manager.autoplayEnabled.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `profile setter writes repository and leaves legacy store untouched`() = runTest {
        val legacySettings = MapSettings().apply {
            putFloat("body_weight_kg", 70f)
        }
        val globalPreferences = SettingsPreferencesManager(legacySettings)
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-a")
        }
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(globalPreferences, profiles, managerScope)

            manager.setBodyWeightKg(82f)
            advanceUntilIdle()

            val ready = assertIs<ActiveProfileContext.Ready>(profiles.activeProfileContext.value)
            assertEquals(82f, ready.preferences.core.value.bodyWeightKg)
            assertEquals(70f, legacySettings.getFloat("body_weight_kg", 0f))
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `profile targeted workout mutation shares the document save mutex`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)
            var mutationCount = 0
            fakeProfileRepository.beforeWorkoutMutation = {
                mutationCount += 1
                if (mutationCount == 1) {
                    mutationEntered.complete(Unit)
                    releaseMutation.await()
                }
            }

            launch {
                manager.mutateWorkout("default") { workout ->
                    workout.copy(beepsEnabled = false)
                }
            }
            mutationEntered.await()
            manager.saveJustLiftDefaultsDocument(
                manager.getJustLiftDefaultsDocument().copy(weightPerCableKg = 42f),
            )
            runCurrent()

            assertEquals(1, mutationCount)
            releaseMutation.complete(Unit)
            advanceUntilIdle()

            val workout = readyProfile().preferences.workout.value
            assertEquals(42f, workout.justLiftDefaults.weightPerCableKg)
            assertFalse(workout.beepsEnabled)
        } finally {
            releaseMutation.complete(Unit)
            managerScope.cancel()
        }
    }

    @Test
    fun `queued setter never lands in newly active profile`() = runTest {
        fakeProfileRepository.setActiveProfileForTest(id = "profile-a")
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setBodyWeightKg(82f)
            fakeProfileRepository.setActiveProfileForTest(id = "profile-b")
            advanceUntilIdle()

            assertEquals(0f, readyProfile().preferences.core.value.bodyWeightKg)
            fakeProfileRepository.setActiveProfileForTest(id = "profile-a")
            assertEquals(0f, readyProfile().preferences.core.value.bodyWeightKg)
        } finally {
            managerScope.cancel()
        }
    }

    private fun readyProfile(): ActiveProfileContext.Ready =
        assertIs(fakeProfileRepository.activeProfileContext.value)

    private suspend fun updateActiveWorkout(
        transform: (WorkoutPreferences) -> WorkoutPreferences,
    ) {
        val ready = readyProfile()
        fakeProfileRepository.updateWorkout(ready.profile.id, transform(ready.preferences.workout.value))
    }
}

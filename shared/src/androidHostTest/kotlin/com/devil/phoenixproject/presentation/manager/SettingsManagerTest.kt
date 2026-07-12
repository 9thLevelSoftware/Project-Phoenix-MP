package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.data.preferences.SettingsPreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsManagerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakePreferencesManager: FakePreferencesManager
    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var fakeProfileRepository: FakeUserProfileRepository

    @Before
    fun setup() {
        fakePreferencesManager = FakePreferencesManager()
        fakeBleRepository = FakeBleRepository()
        fakeProfileRepository = FakeUserProfileRepository().apply { setActiveProfileForTest() }
    }

    @Test
    fun `autoplayEnabled derives from summary countdown seconds`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertTrue(manager.autoplayEnabled.value)

            manager.setSummaryCountdownSeconds(0)
            advanceUntilIdle()
            assertFalse(manager.autoplayEnabled.value)

            manager.setSummaryCountdownSeconds(-1)
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
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setWeightUnit(WeightUnit.KG)
            advanceUntilIdle()

            assertEquals(
                WeightUnit.KG,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.core.value.weightUnit,
            )
            assertEquals(WeightUnit.LB, fakePreferencesManager.preferencesFlow.value.weightUnit)
            assertEquals(WeightUnit.KG, manager.userPreferences.value.weightUnit)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `setDefaultScalingBasis persists to preference-backed flow`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertEquals(ScalingBasis.MAX_WEIGHT_PR, manager.defaultScalingBasis.value)

            manager.setDefaultScalingBasis(ScalingBasis.ESTIMATED_1RM)
            advanceUntilIdle()

            assertEquals(
                ScalingBasis.ESTIMATED_1RM,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.vbt.value.defaultScalingBasis,
            )
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
    fun `set routine exercise percent preferences persist and coerce range`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setDefaultRoutineExerciseUsePercentOfPR(true)
            manager.setDefaultRoutineExerciseWeightPercentOfPR(135)
            advanceUntilIdle()

            var workout = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.workout.value
            assertTrue(workout.defaultRoutineExerciseUsePercentOfPR)
            assertEquals(120, workout.defaultRoutineExerciseWeightPercentOfPR)
            assertTrue(manager.defaultRoutineExerciseUsePercentOfPR.value)
            assertEquals(120, manager.defaultRoutineExerciseWeightPercentOfPR.value)

            manager.setDefaultRoutineExerciseWeightPercentOfPR(20)
            advanceUntilIdle()

            workout = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.workout.value
            assertEquals(50, workout.defaultRoutineExerciseWeightPercentOfPR)
            assertEquals(50, manager.defaultRoutineExerciseWeightPercentOfPR.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `setVelocityOneRepMaxBackfillDone persists to preference-backed flow`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            assertFalse(manager.velocityOneRepMaxBackfillDone.value)

            manager.setVelocityOneRepMaxBackfillDone(true)
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

            assertEquals(22.0462f, manager.kgToDisplay(10f, WeightUnit.LB), 0.0001f)
            assertEquals(10f, manager.displayToKg(22.0462f, WeightUnit.LB), 0.001f)
            assertEquals("10 kg", manager.formatWeight(10f, WeightUnit.KG))
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
        var ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
        fakeProfileRepository.updateCore(
            ready.profile.id,
            ready.preferences.core.value.copy(
                weightUnit = WeightUnit.LB,
                bodyWeightKg = 83f,
            ),
        )
        ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
        fakeProfileRepository.updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(
                summaryCountdownSeconds = 0,
                gamificationEnabled = false,
            ),
        )
        ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
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
    fun `queued same-section setters preserve sibling changes`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setStopAtTop(true)
            manager.setBeepsEnabled(false)
            advanceUntilIdle()

            val workout = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.workout.value
            assertTrue(workout.stopAtTop)
            assertFalse(workout.beepsEnabled)
        } finally {
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

            assertEquals(
                0f,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.core.value.bodyWeightKg,
            )
            fakeProfileRepository.setActiveProfileForTest(id = "profile-a")
            assertEquals(
                0f,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.core.value.bodyWeightKg,
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `active profile cascades clear subordinate adult modes`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)
            manager.setVerbalEncouragementEnabled(true)
            manager.setAdultsOnlyConfirmed(true)
            manager.setVulgarModeEnabled(true)
            manager.setDominatrixModeUnlocked(true)
            manager.setDominatrixModeActive(true)
            advanceUntilIdle()

            manager.setVerbalEncouragementEnabled(false)
            advanceUntilIdle()

            var vbt = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.vbt.value
            assertFalse(vbt.verbalEncouragementEnabled)
            assertFalse(vbt.vulgarModeEnabled)
            assertFalse(vbt.dominatrixModeActive)

            manager.setVerbalEncouragementEnabled(true)
            manager.setVulgarModeEnabled(true)
            manager.setDominatrixModeActive(true)
            manager.setVulgarModeEnabled(false)
            advanceUntilIdle()

            vbt = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.vbt.value
            assertFalse(vbt.vulgarModeEnabled)
            assertFalse(vbt.dominatrixModeActive)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `adult mode gates and VBT disable preserve subordinate values`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setVulgarModeEnabled(true)
            manager.setDominatrixModeUnlocked(true)
            manager.setDominatrixModeActive(true)
            advanceUntilIdle()
            var ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
            assertFalse(ready.preferences.vbt.value.vulgarModeEnabled)
            assertFalse(ready.preferences.vbt.value.dominatrixModeActive)

            manager.setAdultsOnlyPrompted(true)
            manager.setVulgarModeEnabled(true)
            manager.setDominatrixModeActive(true)
            advanceUntilIdle()
            ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
            assertTrue(ready.preferences.vbt.value.vulgarModeEnabled)
            assertFalse(ready.preferences.vbt.value.dominatrixModeActive)

            manager.setAdultsOnlyConfirmed(true)
            manager.setDominatrixModeActive(true)
            manager.setVelocityLossThreshold(35)
            manager.setAutoEndOnVelocityLoss(true)
            advanceUntilIdle()
            manager.setVbtEnabled(false)
            advanceUntilIdle()

            val vbt = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                .preferences.vbt.value
            assertFalse(vbt.enabled)
            assertEquals(35, vbt.velocityLossThresholdPercent)
            assertTrue(vbt.autoEndOnVelocityLoss)
            assertTrue(vbt.vulgarModeEnabled)
            assertTrue(vbt.dominatrixModeActive)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `velocity loss threshold is clamped to supported profile range`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setVelocityLossThreshold(5)
            advanceUntilIdle()
            assertEquals(
                10,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.vbt.value.velocityLossThresholdPercent,
            )

            manager.setVelocityLossThreshold(75)
            advanceUntilIdle()
            assertEquals(
                50,
                assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
                    .preferences.vbt.value.velocityLossThresholdPercent,
            )
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `confirmation implies prompted while prompted alone never confirms`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.setAdultsOnlyPrompted(true)
            advanceUntilIdle()
            var safety = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value).localSafety
            assertTrue(safety.adultsOnlyPrompted)
            assertFalse(safety.adultsOnlyConfirmed)

            manager.setAdultsOnlyConfirmed(true)
            advanceUntilIdle()
            safety = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value).localSafety
            assertTrue(safety.adultsOnlyPrompted)
            assertTrue(safety.adultsOnlyConfirmed)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `composite adult confirmation is serialized and never enables profile B`() = runTest {
        fakeProfileRepository.setActiveProfileForTest(id = "profile-a")
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val manager = SettingsManager(fakePreferencesManager, fakeProfileRepository, managerScope)

            manager.confirmAdultsAndEnableVulgar()
            fakeProfileRepository.setActiveProfileForTest(id = "profile-b")
            advanceUntilIdle()

            var ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
            assertFalse(ready.localSafety.adultsOnlyConfirmed)
            assertFalse(ready.preferences.vbt.value.vulgarModeEnabled)

            manager.confirmAdultsAndEnableVulgar()
            advanceUntilIdle()
            ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
            assertTrue(ready.localSafety.adultsOnlyConfirmed)
            assertTrue(ready.localSafety.adultsOnlyPrompted)
            assertTrue(ready.preferences.vbt.value.vulgarModeEnabled)

            fakeProfileRepository.setActiveProfileForTest(id = "profile-a")
            ready = assertIs<ActiveProfileContext.Ready>(fakeProfileRepository.activeProfileContext.value)
            assertFalse(ready.localSafety.adultsOnlyConfirmed)
            assertFalse(ready.preferences.vbt.value.vulgarModeEnabled)
        } finally {
            managerScope.cancel()
        }
    }
}

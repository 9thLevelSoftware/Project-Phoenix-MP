package com.devil.phoenixproject.domain.voice

import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class SafeWordDetectionManagerTest {
    @Test
    fun `voice stop is ineffective without calibrated active profile phrase`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-a")
            val ready = activeProfileContext.value as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            updateWorkout(
                ready.profile.id,
                ready.preferences.workout.value.copy(voiceStopEnabled = true),
            )
            updateLocalSafety(
                ready.profile.id,
                ProfileLocalSafetyPreferences(safeWord = "phoenix", safeWordCalibrated = false),
            )
        }
        var factoryCalls = 0
        val factory = object : SafeWordListenerFactory {
            override fun create(safeWord: String): SafeWordListener {
                factoryCalls++
                error("Factory must not be invoked for ineffective voice stop")
            }
        }

        SafeWordDetectionManager(profiles, factory).startForWorkout()

        assertEquals(0, factoryCalls)
    }

    @Test
    fun `fully effective active profile invokes listener factory`() = runTest {
        val profiles = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "profile-a")
            val ready = activeProfileContext.value as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
            updateWorkout(
                ready.profile.id,
                ready.preferences.workout.value.copy(voiceStopEnabled = true),
            )
            updateLocalSafety(
                ready.profile.id,
                ProfileLocalSafetyPreferences(safeWord = "phoenix", safeWordCalibrated = true),
            )
        }
        var factoryCalls = 0
        val factory = object : SafeWordListenerFactory {
            override fun create(safeWord: String): SafeWordListener {
                factoryCalls++
                error("Factory invoked")
            }
        }

        assertFailsWith<IllegalStateException> {
            SafeWordDetectionManager(profiles, factory).startForWorkout()
        }
        assertEquals(1, factoryCalls)
    }
}

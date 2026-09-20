package com.devil.phoenixproject.domain.voice

import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Listener double that either arms or fails the way a real recognizer fails
 * (permission revoked, no recognizer, audio focus lost).
 */
private class FakeSafeWordListener(private val failWith: SafeWordUnavailableReason?) : SafeWordListener {
    private val _state = MutableStateFlow<SafeWordState>(SafeWordState.Disabled)
    override val state: StateFlow<SafeWordState> = _state.asStateFlow()

    private val _detectedWord = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val detectedWord: SharedFlow<String> = _detectedWord.asSharedFlow()

    var startCount = 0
        private set

    override fun startListening() {
        startCount++
        _state.value = failWith?.let { SafeWordState.Unavailable(it) } ?: SafeWordState.Armed
    }

    override fun stopListening() {
        _state.value = SafeWordState.Disabled
    }
}

class SafeWordDetectionManagerTest {
    private suspend fun profilesWith(safeWord: String?, calibrated: Boolean) = FakeUserProfileRepository().apply {
        setActiveProfileForTest(id = "profile-a")
        val ready = activeProfileContext.value as com.devil.phoenixproject.data.repository.ActiveProfileContext.Ready
        updateWorkout(
            ready.profile.id,
            ready.preferences.workout.value.copy(voiceStopEnabled = true),
        )
        updateLocalSafety(
            ready.profile.id,
            ProfileLocalSafetyPreferences(safeWord = safeWord, safeWordCalibrated = calibrated),
        )
    }

    /** Collects start-of-set warnings eagerly, before [SafeWordDetectionManager.startForWorkout] runs. */
    private fun TestScope.collectWarnings(manager: SafeWordDetectionManager): List<SafeWordUnavailableReason> {
        val warnings = mutableListOf<SafeWordUnavailableReason>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.unavailableAtStart.collect { warnings += it }
        }
        return warnings
    }

    @Test
    fun `voice stop is ineffective without calibrated active profile phrase`() = runTest {
        val profiles = profilesWith(safeWord = "phoenix", calibrated = false)
        var factoryCalls = 0
        val factory = object : SafeWordListenerFactory {
            override fun create(safeWord: String): SafeWordListener {
                factoryCalls++
                error("Factory must not be invoked for ineffective voice stop")
            }
        }
        val manager = SafeWordDetectionManager(profiles, factory)
        val warnings = collectWarnings(manager)

        manager.startForWorkout()

        assertEquals(0, factoryCalls)
        // F-039: an uncalibrated safe word must say so instead of silently doing nothing.
        assertEquals(
            SafeWordState.Unavailable(SafeWordUnavailableReason.NOT_CALIBRATED),
            manager.state.value,
        )
        assertEquals(listOf(SafeWordUnavailableReason.NOT_CALIBRATED), warnings)
    }

    @Test
    fun `fully effective active profile arms the listener`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val profiles = profilesWith(safeWord = "phoenix", calibrated = true)
            val listener = FakeSafeWordListener(failWith = null)
            var factoryCalls = 0
            val factory = object : SafeWordListenerFactory {
                override fun create(safeWord: String): SafeWordListener {
                    factoryCalls++
                    return listener
                }
            }
            val manager = SafeWordDetectionManager(profiles, factory)
            val warnings = collectWarnings(manager)

            manager.startForWorkout()

            assertEquals(1, factoryCalls)
            assertEquals(1, listener.startCount)
            assertEquals(SafeWordState.Armed, manager.state.value)
            assertEquals(emptyList(), warnings)

            manager.stop()
            assertEquals(SafeWordState.Disabled, manager.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `listener that cannot start reports unavailable and warns at set start`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val profiles = profilesWith(safeWord = "phoenix", calibrated = true)
            val listener = FakeSafeWordListener(failWith = SafeWordUnavailableReason.PERMISSION)
            val factory = object : SafeWordListenerFactory {
                override fun create(safeWord: String): SafeWordListener = listener
            }
            val manager = SafeWordDetectionManager(profiles, factory)
            val warnings = collectWarnings(manager)

            manager.startForWorkout()

            // F-039: mic permission revoked after enabling voice stop must surface,
            // not leave the user believing the safe word will stop the machine.
            assertEquals(
                SafeWordState.Unavailable(SafeWordUnavailableReason.PERMISSION),
                manager.state.value,
            )
            assertEquals(listOf(SafeWordUnavailableReason.PERMISSION), warnings)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

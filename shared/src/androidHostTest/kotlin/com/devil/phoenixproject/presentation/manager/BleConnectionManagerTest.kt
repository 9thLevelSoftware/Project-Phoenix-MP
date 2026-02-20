package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionManagerTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    private lateinit var fakeBleRepository: FakeBleRepository
    private lateinit var fakePreferencesManager: FakePreferencesManager

    @Before
    fun setup() {
        fakeBleRepository = FakeBleRepository()
        fakePreferencesManager = FakePreferencesManager()
    }

    @Test
    fun `disconnect after active connection sets connection lost alert during workout`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val workoutStateProvider = FakeWorkoutStateProvider(active = true)
            val settingsManager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)
            val manager = BleConnectionManager(fakeBleRepository, settingsManager, workoutStateProvider, MutableSharedFlow(), managerScope)
            advanceUntilIdle()

            fakeBleRepository.simulateConnect("Vee_Test")
            advanceUntilIdle()
            fakeBleRepository.simulateDisconnect()
            advanceUntilIdle()

            assertTrue(manager.connectionLostDuringWorkout.value)

            manager.dismissConnectionLostAlert()
            assertFalse(manager.connectionLostDuringWorkout.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `disconnect while not in workout does not set connection lost alert`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val workoutStateProvider = FakeWorkoutStateProvider(active = false)
            val settingsManager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)
            val manager = BleConnectionManager(fakeBleRepository, settingsManager, workoutStateProvider, MutableSharedFlow(), managerScope)
            advanceUntilIdle()

            fakeBleRepository.simulateConnect("Vee_Test")
            advanceUntilIdle()
            fakeBleRepository.simulateDisconnect()
            advanceUntilIdle()

            assertFalse(manager.connectionLostDuringWorkout.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `ensureConnection calls onConnected immediately when already connected`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val workoutStateProvider = FakeWorkoutStateProvider(active = false)
            val settingsManager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)
            val manager = BleConnectionManager(fakeBleRepository, settingsManager, workoutStateProvider, MutableSharedFlow(), managerScope)
            fakeBleRepository.simulateConnect("Vee_Test")
            advanceUntilIdle()

            var connectedCalls = 0
            var failedCalls = 0

            manager.ensureConnection(
                onConnected = { connectedCalls++ },
                onFailed = { failedCalls++ }
            )
            runCurrent()

            assertEquals(1, connectedCalls)
            assertEquals(0, failedCalls)
            assertFalse(manager.isAutoConnecting.value)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `ensureConnection failure reports error and invokes onFailed`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val workoutStateProvider = FakeWorkoutStateProvider(active = false)
            val settingsManager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)
            val manager = BleConnectionManager(fakeBleRepository, settingsManager, workoutStateProvider, MutableSharedFlow(), managerScope)
            fakeBleRepository.shouldFailConnect = true

            var failedCalls = 0

            manager.ensureConnection(
                onConnected = {},
                onFailed = { failedCalls++ }
            )
            advanceUntilIdle()

            assertEquals(1, failedCalls)
            assertFalse(manager.isAutoConnecting.value)
            assertTrue(manager.connectionError.value?.isNotBlank() == true)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `cancelScanOrConnection cancels in-progress connection`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val workoutStateProvider = FakeWorkoutStateProvider(active = false)
            val settingsManager = SettingsManager(fakePreferencesManager, fakeBleRepository, managerScope)
            val manager = BleConnectionManager(fakeBleRepository, settingsManager, workoutStateProvider, MutableSharedFlow(), managerScope)
            advanceUntilIdle()

            fakeBleRepository.simulateConnecting()
            manager.cancelScanOrConnection()
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        } finally {
            managerScope.cancel()
        }
    }

    private class FakeWorkoutStateProvider(
        var active: Boolean
    ) : WorkoutStateProvider {
        override val isWorkoutActiveForConnectionAlert: Boolean
            get() = active
    }
}

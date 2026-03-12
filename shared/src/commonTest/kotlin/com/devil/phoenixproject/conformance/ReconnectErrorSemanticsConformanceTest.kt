package com.devil.phoenixproject.conformance

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.WorkoutStateProvider
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectErrorSemanticsConformanceTest {

    @Test
    fun `connection loss during active workout triggers reconnect alert and clears on reconnect`() = runTest {
        VendorConformanceTargets.selected().forEach { _ ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(coroutineContext + dispatcher)
            val fakeBleRepo = FakeBleRepository()
            val settingsManager = SettingsManager(FakePreferencesManager(), fakeBleRepo, scope)

            val stateProvider = object : WorkoutStateProvider {
                override val isWorkoutActiveForConnectionAlert: Boolean
                    get() = true
            }

            val manager = BleConnectionManager(
                bleRepository = fakeBleRepo,
                settingsManager = settingsManager,
                workoutStateProvider = stateProvider,
                bleErrorEvents = MutableSharedFlow(),
                scope = scope
            )

            fakeBleRepo.simulateConnect("Vee_Test")
            advanceUntilIdle()
            assertFalse(manager.connectionLostDuringWorkout.value)

            fakeBleRepo.simulateDisconnect()
            advanceUntilIdle()
            assertTrue(manager.connectionLostDuringWorkout.value)

            fakeBleRepo.simulateConnect("Vee_Test")
            advanceUntilIdle()
            assertFalse(manager.connectionLostDuringWorkout.value)
        }
    }

    @Test
    fun `connection loss outside active workout does not trigger reconnect alert`() = runTest {
        VendorConformanceTargets.selected().forEach { _ ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(coroutineContext + dispatcher)
            val fakeBleRepo = FakeBleRepository()
            val settingsManager = SettingsManager(FakePreferencesManager(), fakeBleRepo, scope)

            val stateProvider = object : WorkoutStateProvider {
                override val isWorkoutActiveForConnectionAlert: Boolean
                    get() = false
            }

            val manager = BleConnectionManager(
                bleRepository = fakeBleRepo,
                settingsManager = settingsManager,
                workoutStateProvider = stateProvider,
                bleErrorEvents = MutableSharedFlow(),
                scope = scope
            )

            fakeBleRepo.simulateConnect("Vee_Test")
            fakeBleRepo.simulateError("signal dropped")
            advanceUntilIdle()

            assertFalse(manager.connectionLostDuringWorkout.value)
            assertTrue(fakeBleRepo.connectionState.value is ConnectionState.Error)
            assertFalse(manager.connectionState.value is ConnectionState.Connected)
        }
    }
}

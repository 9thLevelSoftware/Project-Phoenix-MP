@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // Identifier is Uuid on Apple.

package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.ble.CompositionAdvertisement
import com.devil.phoenixproject.data.ble.CompositionGattFake
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.CommandLimits
import com.juul.kable.Advertisement
import com.juul.kable.WriteType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Real facade -> manager -> polling/queue -> Kable API double; no physical BLE claims.
 * Platform adapters supply their runtime fixture and inherit every portable test.
 */
abstract class KableGattCompositionContract {
    private val first = CompositionAdvertisement("00000000-0000-4000-8000-000000000001", "Vee_First", -20)
    private val chosen = CompositionAdvertisement("00000000-0000-4000-8000-000000000002", "Vee_Chosen", -70)

    private class Fixture(scope: TestScope) {
        val advertisements = MutableSharedFlow<Advertisement>()
        val constructed = mutableListOf<Advertisement>()
        val peripherals = mutableListOf<CompositionGattFake>()
        var scanCollectors = 0
        var connectGate: CompletableDeferred<Unit>? = null
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val repository = KableBleRepository(
            dispatcher = dispatcher,
            advertisements = {
                flow {
                    scanCollectors++
                    try {
                        advertisements.collect { emit(it) }
                    } finally {
                        scanCollectors--
                    }
                }
            },
            createPeripheral = { advertisement ->
                constructed += advertisement
                CompositionGattFake(advertisement, dispatcher).also {
                    it.connectGate = connectGate
                    peripherals += it
                }
            },
        )

        suspend fun close() {
            repository.shutdown()
            peripherals.forEach { it.close() }
        }
    }

    private suspend fun TestScope.withFixture(block: suspend TestScope.(Fixture) -> Unit) {
        val fixture = Fixture(this)
        try {
            block(fixture)
        } finally {
            fixture.close()
            runCurrent()
        }
    }

    private suspend fun TestScope.discover(f: Fixture, vararg devices: Advertisement) {
        assertTrue(f.repository.startScanning().isSuccess)
        runCurrent()
        devices.forEach { f.advertisements.emit(it) }
        runCurrent()
    }

    private suspend fun TestScope.connectChosen(f: Fixture): CompositionGattFake {
        discover(f, first, chosen)
        val selection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
        assertTrue(f.repository.connect(selection).isSuccess)
        runCurrent()
        return f.peripherals.last()
    }

    private fun loadCommand(): ByteArray = BlePacketFactory.createProgramParams(
        WorkoutParameters(programMode = ProgramMode.OldSchool, reps = 10, weightPerCableKg = 20f),
        CommandLimits.V_FORM_MAX_WEIGHT_PER_CABLE_KG,
    )

    @Test
    fun scanIsDiscoveryOnlyAndStopCancelsScanner() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            assertEquals(listOf(first.identifier.toString(), chosen.identifier.toString()), f.repository.scannedDevices.value.map { it.address })
            assertEquals(ConnectionState.Scanning, f.repository.connectionState.value)
            assertTrue(f.constructed.isEmpty(), "Scanning must not even construct a Peripheral")
            assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)
            assertTrue(f.peripherals.isEmpty())
            assertEquals(1, f.scanCollectors)
            f.repository.stopScanning()
            runCurrent()
            assertEquals(0, f.scanCollectors)
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
        }
    }

    @Test
    fun explicitSelectionConnectsChosenAdvertisementNotFirstOrStrongest() = runTest {
        withFixture { f ->
            val p = connectChosen(f)
            assertSame(chosen, f.constructed.single())
            assertEquals(1, p.connectCalls)
            assertEquals(chosen.identifier.toString(), assertIs<ConnectionState.Connected>(f.repository.connectionState.value).deviceAddress)
            assertEquals(0, f.scanCollectors)
            assertTrue(p.writes.isEmpty(), "Ready-up must not write a load command")
            assertNotNull(p.services.value)
            assertEquals("8308f2a6-0875-4a94-a86f-5c5c5e1b068a", p.observations.first().characteristicUuid.toString())
        }
    }

    @Test
    fun unknownSelectionFailsClosed() = runTest {
        withFixture { f ->
            discover(f, first)
            val unknown = ScannedDevice("Vee_Unknown", "00000000-0000-4000-8000-000000000099", -10)
            assertTrue(f.repository.connect(unknown).isFailure)
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            assertTrue(f.constructed.isEmpty())
            assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)
        }
    }

    @Test
    fun selectionFromPreviousScanFailsClosedAfterFreshScan() = runTest {
        withFixture { f ->
            discover(f, chosen)
            val stale = f.repository.scannedDevices.value.single()
            discover(f, first)
            assertTrue(f.repository.connect(stale).isFailure)
            assertTrue(f.constructed.isEmpty())
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
        }
    }

    @Test
    fun discoveredGattNotificationsReadsAndAcknowledgedWritesReachFacade() = runTest {
        withFixture { f ->
            val reps = mutableListOf<RepNotification>()
            val collector = backgroundScope.launch { f.repository.repEvents.collect { reps += it } }
            val p = connectChosen(f)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(42L, f.repository.diagnostics.value?.runtimeSeconds)
            assertEquals(25, f.repository.diagnostics.value?.temperatures?.first())
            assertTrue(p.reads.any { it.characteristicUuid.toString() == "90e991a6-c548-44ed-969b-eb541014eae3" })
            val packet = ByteArray(24).also { it[0] = 4; it[4] = 3; it[20] = 3; it[22] = 10 }
            p.notifyReps(packet)
            runCurrent()
            assertEquals(4, reps.single().topCounter)
            assertEquals(3, reps.single().completeCounter)
            assertEquals(3, reps.single().repsSetCount)
            assertContentEquals(packet, reps.single().rawData)

            val command = loadCommand()
            assertTrue(f.repository.sendWorkoutCommand(command).isSuccess)
            val write = p.writes.single()
            assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", write.characteristic.serviceUuid.toString())
            assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", write.characteristic.characteristicUuid.toString())
            assertEquals(WriteType.WithResponse, write.type)
            assertContentEquals(command, write.bytes)
            assertEquals(1, p.maxConcurrentOperations, "Production queue must serialize polling reads and command writes")

            p.writeFailure = IllegalStateException("GATT write rejected")
            val failed = f.repository.sendWorkoutCommand(BlePacketFactory.createResetCommand())
            assertTrue(failed.isFailure)
            assertEquals("GATT write rejected", failed.exceptionOrNull()?.message)
            assertEquals(1, p.writes.size, "Failed write must not be reported as delivered")
            collector.cancel()
        }
    }

    @Test
    fun disconnectAndExplicitReconnectNeverReplayPriorLoad() = runTest {
        withFixture { f ->
            val p = connectChosen(f)
            assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isSuccess)
            val requests = mutableListOf<ReconnectionRequest>()
            backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
            runCurrent()
            f.repository.disconnect()
            runCurrent()
            assertEquals(1, p.disconnectCalls)
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)
            assertTrue(requests.isEmpty(), "Explicit disconnect must not request automatic reconnection")
            val next = connectChosen(f)
            advanceTimeBy(2500)
            runCurrent()
            assertTrue(next.writes.all { it.bytes.contentEquals(byteArrayOf(0, 0, 0, 0)) }, "Only heartbeat no-op is allowed without an explicit command")
            assertEquals(1, p.writes.size, "Old connection must not receive a replay")
        }
    }

    @Test
    fun unexpectedDropRequestsReconnectButDoesNotConnectOrLoadByItself() = runTest {
        withFixture { f ->
            val requests = mutableListOf<ReconnectionRequest>()
            backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
            val p = connectChosen(f)
            p.dropConnection()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            assertEquals(chosen.identifier.toString(), requests.single().deviceAddress)
            assertEquals(1, f.constructed.size)
            assertTrue(p.writes.isEmpty())
        }
    }

    @Test
    fun cancellingQueuedCommandDoesNotWriteAndReleasesQueueForNextCommand() = runTest {
        withFixture { f ->
            val p = connectChosen(f)
            p.readGate = CompletableDeferred()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, p.activeOperations)
            val command = async { f.repository.sendWorkoutCommand(loadCommand()) }
            runCurrent()
            assertFalse(command.isCompleted)
            assertTrue(p.writes.isEmpty())
            command.cancelAndJoin()
            p.readGate!!.complete(Unit)
            runCurrent()
            assertTrue(f.repository.sendWorkoutCommand(BlePacketFactory.createResetCommand()).isSuccess)
            assertContentEquals(byteArrayOf(0x0A, 0, 0, 0), p.writes.single().bytes)
            assertEquals(1, p.maxConcurrentOperations)
        }
    }

    @Test
    fun callerCancellationDuringConnectCleansUpAndCannotPublishConnectedLater() = runTest {
        withFixture { f ->
            f.connectGate = CompletableDeferred()
            discover(f, chosen)
            val connect = async { f.repository.connect(f.repository.scannedDevices.value.single()) }
            runCurrent()
            val p = f.peripherals.single()
            assertEquals(ConnectionState.Connecting, f.repository.connectionState.value)
            connect.cancelAndJoin()
            runCurrent()
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            assertEquals(1, p.disconnectCalls)
            assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)
            advanceTimeBy(20000)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            assertTrue(p.writes.isEmpty())
        }
    }

    @Test
    fun shutdownCancelsProductionPollingAndObservationCollectors() = runTest {
        withFixture { f ->
            val p = connectChosen(f)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(3, p.activeObservations)
            f.repository.shutdown()
            runCurrent()
            val reads = p.reads.size
            val writes = p.writes.size
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(0, p.activeObservations)
            assertEquals(0, p.activeOperations)
            assertEquals(reads, p.reads.size)
            assertEquals(writes, p.writes.size)
            assertTrue(f.repository.scannedDevices.value.isEmpty())
            assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
        }
    }
}

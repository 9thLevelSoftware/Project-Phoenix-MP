@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // Identifier is Uuid on Apple.

package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.ble.CompositionAdvertisement
import com.devil.phoenixproject.data.ble.CompositionGattFake
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.CommandLimits
import com.juul.kable.Advertisement
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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

    private suspend fun TestScope.assertConnectionUsefulThenDrops(
        f: Fixture,
        p: CompositionGattFake,
        selection: Advertisement,
    ) {
        assertEquals(selection.identifier.toString(), assertIs<ConnectionState.Connected>(f.repository.connectionState.value).deviceAddress)
        assertSame(selection, f.constructed.last())
        assertEquals(0, p.disconnectCalls)
        assertTrue(p.writes.isEmpty(), "Connecting must not replay any command")
        val reps = mutableListOf<RepNotification>()
        val requests = mutableListOf<ReconnectionRequest>()
        backgroundScope.launch { f.repository.repEvents.collect { reps += it } }
        backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
        val monitorUuid = "90e991a6-c548-44ed-969b-eb541014eae3"
        val readsBefore = p.reads.count { it.characteristicUuid.toString() == monitorUuid }
        advanceTimeBy(100)
        runCurrent()
        assertTrue(p.reads.count { it.characteristicUuid.toString() == monitorUuid } > readsBefore)
        val packet = ByteArray(24).also { it[0] = 4; it[4] = 3; it[20] = 3; it[22] = 10 }
        p.notifyReps(packet)
        runCurrent()
        assertEquals(4, reps.single().topCounter)
        assertContentEquals(packet, reps.single().rawData)
        val reset = BlePacketFactory.createResetCommand()
        assertTrue(f.repository.sendWorkoutCommand(reset).isSuccess)
        val write = p.writes.single()
        assertEquals(WriteType.WithResponse, write.type)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", write.characteristic.characteristicUuid.toString())
        assertContentEquals(reset, write.bytes)
        assertEquals(selection.identifier.toString(), assertIs<ConnectionState.Connected>(f.repository.connectionState.value).deviceAddress)
        assertTrue(requests.isEmpty(), "Cancelled predecessor must not request reconnect")
        val constructedBeforeDrop = f.constructed.size
        p.dropConnection()
        runCurrent()
        assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
        assertEquals(selection.identifier.toString(), requests.single().deviceAddress)
        assertEquals(constructedBeforeDrop, f.constructed.size, "A reconnect request must not auto-connect or replay a load")
        assertEquals(1, p.writes.size)
    }

    @Test
    fun cancellationDuringReplacementEntryCleanupClearsConnectedState() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            assertTrue(f.repository.connect(firstSelection).isSuccess)
            runCurrent()
            val old = f.peripherals.single()
            assertEquals(first.identifier.toString(), assertIs<ConnectionState.Connected>(f.repository.connectionState.value).deviceAddress)
            assertEquals(1, old.connectCalls)
            val states = mutableListOf<ConnectionState>()
            val requests = mutableListOf<ReconnectionRequest>()
            backgroundScope.launch { f.repository.connectionState.collect { states += it } }
            backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
            runCurrent()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            var propagatedCancellation: CancellationException? = null
            val replacement = async {
                try {
                    f.repository.connect(chosenSelection)
                } catch (e: CancellationException) {
                    propagatedCancellation = e
                    throw e
                }
            }
            try {
                runCurrent()
                assertEquals(1, old.disconnectCalls, "Replacement must be in entry cleanup of the ready first connection")
                assertFalse(replacement.isCompleted)
                assertEquals(listOf<Advertisement>(first), f.constructed, "Entry cleanup must finish before constructing a replacement")
                assertEquals(1, f.peripherals.size)
                states.clear()
                replacement.cancelAndJoin()
                runCurrent()
                assertTrue(replacement.isCancelled)
                assertNotNull(propagatedCancellation, "Facade connect must throw cancellation, not return a Result")
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value, "Cancelled entry cleanup must not leave stale Connected")
                assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)
                cleanupReturn.complete(Unit)
                advanceTimeBy(20000)
                runCurrent()
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
                assertTrue(states.none { it is ConnectionState.Connected }, "Cancelled replacement must not publish Connected later")
                assertTrue(requests.isEmpty(), "Entry cleanup cancellation must not request reconnect")
                assertEquals(listOf<Advertisement>(first), f.constructed)
                assertEquals(1, f.peripherals.size)
                assertTrue(old.writes.isEmpty())

                assertTrue(f.repository.connect(chosenSelection).isSuccess)
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed)
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(old.writes.isEmpty(), "Explicit RESET must reach chosen, never the old connection")
            } finally {
                cleanupReturn.complete(Unit)
                replacement.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancelledConnectCannotTearDownQueuedReplacement() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            f.connectGate = CompletableDeferred()
            val a = async { f.repository.connect(firstSelection) }
            runCurrent()
            val old = f.peripherals.single()
            assertEquals(ConnectionState.Connecting, f.repository.connectionState.value)
            f.connectGate = null
            val b = async { f.repository.connect(chosenSelection) }
            // Queue B BEFORE A's cancelled continuation, without yielding. Explicit
            // caller cancellation avoids depending on the double's disconnect semantics.
            a.cancel()
            try {
                runCurrent()
                a.join()
                assertTrue(a.isCancelled)
                assertEquals(2, f.peripherals.size)
                val replacement = f.peripherals.last()
                assertEquals(0, replacement.disconnectCalls, "A's cancellation must not disconnect B")
                assertTrue(b.await().isSuccess)
                assertEquals(1, old.disconnectCalls)
                assertConnectionUsefulThenDrops(f, replacement, chosen)
                assertTrue(old.writes.isEmpty(), "RESET must reach B, never A")
            } finally {
                a.cancelAndJoin()
                b.cancelAndJoin()
            }
        }
    }

    @Test
    fun replacementWaitsForCancelledConnectCleanupToFinish() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            f.connectGate = CompletableDeferred()
            val a = async { f.repository.connect(firstSelection) }
            runCurrent()
            val old = f.peripherals.single()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            a.cancel()
            runCurrent()
            assertEquals(1, old.disconnectCalls)
            assertFalse(a.isCompleted, "A must be parked inside NonCancellable cleanup")
            f.connectGate = null
            val b = async { f.repository.connect(chosenSelection) }
            try {
                runCurrent()
                assertEquals(1, f.peripherals.size, "B must wait until A's cleanup returns")
                assertFalse(b.isCompleted)
                assertFalse(f.repository.connectionState.value is ConnectionState.Connected)
                cleanupReturn.complete(Unit)
                a.join()
                assertTrue(a.isCancelled)
                assertTrue(b.await().isSuccess)
                runCurrent()
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(old.writes.isEmpty())
            } finally {
                cleanupReturn.complete(Unit)
                a.cancelAndJoin()
                b.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingWaitingConnectDoesNotAffectAdmissionOwner() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            val physicalConnect = CompletableDeferred<Unit>()
            f.connectGate = physicalConnect
            val a = async { f.repository.connect(firstSelection) }
            runCurrent()
            val owner = f.peripherals.single()
            f.connectGate = null
            val waiter = async { f.repository.connect(chosenSelection) }
            try {
                runCurrent()
                assertFalse(waiter.isCompleted)
                waiter.cancelAndJoin()
                runCurrent()
                assertTrue(waiter.isCancelled)
                assertEquals(1, f.peripherals.size, "Cancelled waiter must not construct a peripheral")
                assertEquals(0, owner.disconnectCalls, "Cancelled waiter must not clean the admission owner")
                assertFalse(a.isCompleted)
                assertEquals(ConnectionState.Connecting, f.repository.connectionState.value)
                physicalConnect.complete(Unit)
                assertTrue(a.await().isSuccess)
                runCurrent()
                assertConnectionUsefulThenDrops(f, owner, first)
            } finally {
                a.cancelAndJoin()
                waiter.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancelledConnectCleanupReleasesAdmissionAfterFiveSecondBound() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            f.connectGate = CompletableDeferred()
            val a = async { f.repository.connect(firstSelection) }
            runCurrent()
            val old = f.peripherals.single()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            a.cancel()
            runCurrent()
            f.connectGate = null
            val b = async { f.repository.connect(chosenSelection) }
            try {
                runCurrent()
                advanceTimeBy(4999)
                runCurrent()
                assertFalse(a.isCompleted)
                assertFalse(b.isCompleted)
                assertEquals(1, f.peripherals.size)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(a.isCompleted, "Cancellation cleanup must retain its 5-second bound")
                assertTrue(a.isCancelled)
                assertTrue(b.await().isSuccess)
                cleanupReturn.complete(Unit)
                runCurrent()
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(old.writes.isEmpty())
            } finally {
                cleanupReturn.complete(Unit)
                a.cancelAndJoin()
                b.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitDisconnectInvalidatesQueuedConnect() = runTest {
        assertExplicitTeardownInvalidatesQueuedConnect(cancel = false)
    }

    @Test
    fun explicitCancelInvalidatesQueuedConnect() = runTest {
        assertExplicitTeardownInvalidatesQueuedConnect(cancel = true)
    }

    private suspend fun TestScope.assertExplicitTeardownInvalidatesQueuedConnect(cancel: Boolean) {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            f.connectGate = CompletableDeferred()
            val owner = async { f.repository.connect(firstSelection) }
            runCurrent()
            val old = f.peripherals.single()
            f.connectGate = null
            val waiter = async { f.repository.connect(chosenSelection) }
            val states = mutableListOf<ConnectionState>()
            val requests = mutableListOf<ReconnectionRequest>()
            backgroundScope.launch { f.repository.connectionState.collect { states += it } }
            backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
            try {
                runCurrent()
                assertFalse(owner.isCompleted)
                assertFalse(waiter.isCompleted)
                assertEquals(listOf<Advertisement>(first), f.constructed)
                if (cancel) f.repository.cancelConnection() else f.repository.disconnect()
                runCurrent()
                assertTrue(owner.isCancelled, "Explicit teardown must interrupt the pending physical connect")
                assertEquals(listOf<Advertisement>(first), f.constructed, "Pre-teardown waiter must never construct a Peripheral")
                assertTrue(waiter.await().isFailure, "Queued request must fail instead of undoing explicit teardown")
                advanceTimeBy(BleConstants.CONNECTION_TIMEOUT_MS)
                runCurrent()
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
                assertTrue(states.none { it is ConnectionState.Connected })
                assertTrue(requests.isEmpty())
                assertTrue(old.writes.isEmpty())
                assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)

                assertTrue(f.repository.connect(chosenSelection).isSuccess, "Only a fresh explicit request may connect")
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed)
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
            } finally {
                owner.cancelAndJoin()
                waiter.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitDisconnectInvalidatesAdmittedReplacementAfterEntryCleanup() = runTest {
        assertTeardownInvalidatesReplacementAfterEntryCleanup(cancel = false)
    }

    @Test
    fun explicitCancelInvalidatesAdmittedReplacementAfterEntryCleanup() = runTest {
        assertTeardownInvalidatesReplacementAfterEntryCleanup(cancel = true)
    }

    private suspend fun TestScope.assertTeardownInvalidatesReplacementAfterEntryCleanup(cancel: Boolean) {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            assertTrue(f.repository.connect(firstSelection).isSuccess)
            runCurrent()
            val old = f.peripherals.single()
            assertEquals(first.identifier.toString(), assertIs<ConnectionState.Connected>(f.repository.connectionState.value).deviceAddress)

            val states = mutableListOf<ConnectionState>()
            val requests = mutableListOf<ReconnectionRequest>()
            backgroundScope.launch { f.repository.connectionState.collect { states += it } }
            backgroundScope.launch { f.repository.reconnectionRequested.collect { requests += it } }
            runCurrent()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            val replacement = async { f.repository.connect(chosenSelection) }
            try {
                runCurrent()
                assertEquals(1, old.disconnectCalls, "Replacement must be admitted and parked in old-peripheral cleanup")
                assertFalse(replacement.isCompleted)
                assertEquals(listOf<Advertisement>(first), f.constructed)
                states.clear()

                if (cancel) f.repository.cancelConnection() else f.repository.disconnect()
                runCurrent()
                assertEquals(2, old.disconnectCalls, "Public teardown must complete its own old-peripheral disconnect")
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
                assertFalse(replacement.isCompleted, "Teardown returns while admitted entry cleanup remains parked")
                assertEquals(listOf<Advertisement>(first), f.constructed)

                cleanupReturn.complete(Unit)
                val staleResult = replacement.await()
                assertTrue(staleResult.isFailure, "Teardown must invalidate a replacement already admitted in cleanup")
                runCurrent()
                assertEquals(listOf<Advertisement>(first), f.constructed, "Invalidated replacement must not construct a Peripheral")
                assertEquals(1, old.connectCalls)
                assertTrue(states.none { it is ConnectionState.Connected }, "Stale replacement must not republish Connected")
                assertTrue(requests.isEmpty(), "Explicit teardown and stale cleanup must not request reconnect")
                assertTrue(f.repository.sendWorkoutCommand(loadCommand()).isFailure)

                assertTrue(f.repository.connect(chosenSelection).isSuccess, "A genuinely fresh request must remain admissible")
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed)
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(old.writes.isEmpty(), "Fresh RESET must reach only the chosen peripheral")
            } finally {
                cleanupReturn.complete(Unit)
                replacement.cancelAndJoin()
            }
        }
    }

    @Test
    fun freshConnectAfterDisconnectWaitsForCleanupWhileStaleWaiterIsRejected() = runTest {
        assertFreshConnectAfterTeardownDuringCleanup(cancel = false)
    }

    @Test
    fun freshConnectAfterCancelWaitsForCleanupWhileStaleWaiterIsRejected() = runTest {
        assertFreshConnectAfterTeardownDuringCleanup(cancel = true)
    }

    private suspend fun TestScope.assertFreshConnectAfterTeardownDuringCleanup(cancel: Boolean) {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            f.connectGate = CompletableDeferred()
            val owner = async { f.repository.connect(firstSelection) }
            runCurrent()
            val old = f.peripherals.single()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            owner.cancel()
            runCurrent()
            assertEquals(1, old.disconnectCalls)
            assertFalse(owner.isCompleted, "Cancelled owner must still hold admission during noncancellable cleanup")
            f.connectGate = null
            val stale = async { f.repository.connect(chosenSelection) }
            runCurrent()
            assertFalse(stale.isCompleted)
            if (cancel) f.repository.cancelConnection() else f.repository.disconnect()
            val fresh = async { f.repository.connect(chosenSelection) }
            try {
                runCurrent()
                assertFalse(owner.isCompleted)
                assertFalse(fresh.isCompleted, "Post-teardown request must still wait for the old owner's cleanup")
                assertEquals(listOf<Advertisement>(first), f.constructed)
                cleanupReturn.complete(Unit)
                owner.join()
                assertTrue(stale.await().isFailure, "Teardown must invalidate requests queued before it, not after it")
                assertTrue(fresh.await().isSuccess)
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed, "Only the fresh request may construct a Peripheral")
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(old.writes.isEmpty())
            } finally {
                cleanupReturn.complete(Unit)
                owner.cancelAndJoin()
                stale.cancelAndJoin()
                fresh.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitDisconnectSupersedesInFlightConnectThatReturnsLate() = runTest {
        withFixture { f ->
            discover(f, chosen)
            val selection = f.repository.scannedDevices.value.single()
            val physicalConnect = CompletableDeferred<Unit>()
            f.connectGate = physicalConnect
            val owner = async { f.repository.connect(selection) }
            try {
                runCurrent()
                val p = f.peripherals.single()
                p.cancelConnectGateOnDisconnect = false
                assertEquals(1, p.connectCalls)

                f.repository.disconnect()
                runCurrent()
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
                assertFalse(owner.isCompleted, "The fake transport keeps the physical attempt in flight")

                physicalConnect.complete(Unit)
                runCurrent()
                assertTrue(owner.isCompleted, "A superseded physical completion must fail without waiting for the ready timeout")
                assertTrue(owner.await().isFailure)
                assertEquals(1, p.connectCalls, "A stale physical completion must not retry")
                assertEquals(2, p.disconnectCalls, "The late physical connection must be torn down after explicit disconnect")
                assertIs<State.Disconnected>(p.state.value)
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            } finally {
                physicalConnect.complete(Unit)
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellationDuringSupersededPeripheralCleanupDisconnectsExactAttemptBeforeAdmissionRelease() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            val physicalConnect = CompletableDeferred<Unit>()
            f.connectGate = physicalConnect
            val owner = async { f.repository.connect(firstSelection) }
            var supersededDisconnectGate: CompletableDeferred<Unit>? = null
            var fresh: kotlinx.coroutines.Job? = null
            try {
                runCurrent()
                val p = f.peripherals.single()
                p.cancelConnectGateOnDisconnect = false
                val supersededDisconnect = CompletableDeferred<Unit>()
                supersededDisconnectGate = supersededDisconnect
                p.disconnectGateOnCalls = setOf(2)
                p.disconnectGate = supersededDisconnect

                f.repository.disconnect()
                runCurrent()
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
                physicalConnect.complete(Unit)
                runCurrent()
                assertEquals(2, p.disconnectCalls, "Late P must enter its exact-peripheral superseded cleanup")
                assertFalse(owner.isCompleted, "The superseded disconnect barrier must hold the admitted owner")
                assertIs<State.Connected>(p.state.value)

                owner.cancel()
                runCurrent()
                assertFalse(owner.isCompleted, "Captured-P cleanup must remain inside the NonCancellable admission lease")
                val freshConnect = async { f.repository.connect(chosenSelection) }
                fresh = freshConnect
                runCurrent()
                assertFalse(freshConnect.isCompleted, "B must wait until exact-P cleanup settles")
                assertEquals(listOf<Advertisement>(first), f.constructed, "B must not be constructed during P cleanup")

                supersededDisconnect.complete(Unit)
                runCurrent()
                assertTrue(owner.isCompleted, "The owner must settle only after P cleanup finishes")
                assertTrue(owner.isCancelled, "Caller cancellation must still propagate after cleanup settles")
                assertEquals(2, p.disconnectCalls, "NonCancellable cleanup must disconnect captured P")
                assertIs<State.Disconnected>(p.state.value, "Captured P must be disconnected before admission is released")

                f.connectGate = null
                assertTrue(freshConnect.await().isSuccess, "B may connect after P cleanup settles")
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed)
                assertEquals(2, p.disconnectCalls, "B connection must not clean P again")
                assertEquals(1, f.peripherals.last().connectCalls, "B must connect only after P's cleanup is done")
            } finally {
                physicalConnect.complete(Unit)
                supersededDisconnectGate?.complete(Unit)
                owner.cancelAndJoin()
                fresh?.cancelAndJoin()
            }
        }
    }

    @Test
    fun disconnectAfterReadyGateCompletionRejectsSupersededConnectResult() = runTest {
        withFixture { f ->
            discover(f, chosen)
            val selection = f.repository.scannedDevices.value.single()
            val physicalConnect = CompletableDeferred<Unit>()
            f.connectGate = physicalConnect
            val owner = async { f.repository.connect(selection) }
            try {
                runCurrent()
                val p = f.peripherals.single()
                var teardownRanAfterReady = false
                p.afterRepsSubscription = {
                    backgroundScope.launch {
                        teardownRanAfterReady = f.repository.connectionState.value is ConnectionState.Connected
                        f.repository.disconnect()
                    }
                }

                physicalConnect.complete(Unit)
                runCurrent()
                assertTrue(teardownRanAfterReady, "The deterministic fake must tear down after ready-up published Connected")
                assertTrue(owner.isCompleted, "The connect caller must settle after post-ready teardown")
                assertTrue(owner.await().isFailure, "A completed ready gate cannot make a superseded connect succeed")
                assertEquals(1, p.connectCalls)
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            } finally {
                physicalConnect.complete(Unit)
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun teardownDuringFailedConnectCleanupSkipsRetryDelay() = runTest {
        withFixture { f ->
            discover(f, chosen)
            val selection = f.repository.scannedDevices.value.single()
            val physicalConnect = CompletableDeferred<Unit>()
            f.connectGate = physicalConnect
            val owner = async { f.repository.connect(selection) }
            var retryCleanupGate: CompletableDeferred<Unit>? = null
            try {
                runCurrent()
                val p = f.peripherals.single()
                val retryCleanupReturn = CompletableDeferred<Unit>()
                retryCleanupGate = retryCleanupReturn
                p.disconnectReturnGate = retryCleanupReturn

                physicalConnect.completeExceptionally(IllegalStateException("controlled physical-connect failure"))
                runCurrent()
                assertEquals(1, p.connectCalls)
                assertEquals(1, p.disconnectCalls, "The failed attempt must be parked in retry cleanup")
                assertFalse(owner.isCompleted)

                f.repository.disconnect()
                runCurrent()
                assertEquals(2, p.disconnectCalls)
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)

                retryCleanupReturn.complete(Unit)
                runCurrent()
                assertTrue(owner.isCompleted, "A superseded failure must not enter the retry delay")
                assertEquals(
                    "Connection request superseded by explicit teardown",
                    owner.await().exceptionOrNull()?.message,
                )
                assertEquals(1, p.connectCalls, "Teardown during retry cleanup must prevent the next physical attempt")
                assertEquals(ConnectionState.Disconnected, f.repository.connectionState.value)
            } finally {
                physicalConnect.completeExceptionally(IllegalStateException("Test cleanup released pending physical connect"))
                retryCleanupGate?.complete(Unit)
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun admissionWaitTimesOutWithoutCleaningOwnerAndDoesNotRunLater() = runTest {
        withFixture { f ->
            discover(f, first, chosen)
            val firstSelection = f.repository.scannedDevices.value.single { it.address == first.identifier.toString() }
            val chosenSelection = f.repository.scannedDevices.value.single { it.address == chosen.identifier.toString() }
            assertTrue(f.repository.connect(firstSelection).isSuccess)
            runCurrent()
            val old = f.peripherals.single()
            val cleanupReturn = CompletableDeferred<Unit>()
            old.disconnectReturnGate = cleanupReturn
            // Hold a real replacement inside entry cleanup, beyond the admission
            // bound, without depending on its independent physical-connect retry timer.
            val owner = async { f.repository.connect(chosenSelection) }
            runCurrent()
            assertEquals(1, old.disconnectCalls)
            assertFalse(owner.isCompleted)
            val stateBeforeWait = f.repository.connectionState.value
            val waiter = async { f.repository.connect(firstSelection) }
            try {
                runCurrent()
                advanceTimeBy(BleConstants.CONNECTION_TIMEOUT_MS - 1)
                runCurrent()
                assertFalse(waiter.isCompleted, "Admission should wait up to the existing connection timeout")
                advanceTimeBy(1)
                runCurrent()
                assertTrue(waiter.isCompleted, "Admission must time out at the existing connection timeout")
                assertTrue(waiter.await().isFailure)
                assertEquals(
                    "Another connection attempt is in progress. Please wait and try again.",
                    waiter.await().exceptionOrNull()?.message,
                    "Admission rejection must be suitable for the user-facing connection error",
                )
                assertFalse(owner.isCompleted, "Wait timeout must not cancel the admitted owner")
                assertEquals(1, old.disconnectCalls, "Wait timeout must not run connection cleanup")
                assertEquals(stateBeforeWait, f.repository.connectionState.value, "Wait timeout owns no connection-state publication")
                assertEquals(listOf<Advertisement>(first), f.constructed)
                cleanupReturn.complete(Unit)
                assertTrue(owner.await().isSuccess)
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen), f.constructed, "Expired waiter must be removed, never admitted later")
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), chosen)
                assertTrue(f.repository.connect(firstSelection).isSuccess, "Timeout must not leak the admission mutex")
                runCurrent()
                assertEquals(listOf<Advertisement>(first, chosen, first), f.constructed)
                assertConnectionUsefulThenDrops(f, f.peripherals.last(), first)
            } finally {
                cleanupReturn.complete(Unit)
                owner.cancelAndJoin()
                waiter.cancelAndJoin()
            }
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

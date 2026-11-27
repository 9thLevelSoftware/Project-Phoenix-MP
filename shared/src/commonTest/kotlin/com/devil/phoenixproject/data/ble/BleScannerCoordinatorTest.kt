package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Advertisement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class BleScannerCoordinatorTest {

    @Test
    fun `concurrent trainer and HRM scans share one upstream collector and stopping trainer leaves HRM scanning`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        val upstreamFeed = MutableSharedFlow<Advertisement>(extraBufferCapacity = 16)
        var activeUpstreamCollectors = 0
        var totalUpstreamStarts = 0

        val coordinator = BleScannerCoordinator(
            scope = coordinatorScope,
            upstreamAdvertisements = {
                upstreamFeed
                    .onStart {
                        activeUpstreamCollectors++
                        totalUpstreamStarts++
                    }
                    .onCompletion {
                        activeUpstreamCollectors--
                    }
            },
        )

        val trainerRepo = KableBleRepository(
            dispatcher = dispatcher,
            advertisements = { coordinator.advertisements() },
            createPeripheral = { adv -> CompositionGattFake(adv, dispatcher) },
        )
        val hrmManager = HrmBleManager(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            advertisements = { coordinator.advertisements() },
            createPeripheral = { adv -> CompositionGattFake(adv, dispatcher) },
        )

        trainerRepo.startScanning()
        hrmManager.startScanning()
        runCurrent()

        assertEquals(1, activeUpstreamCollectors)
        assertEquals(1, totalUpstreamStarts)

        val trainerAdv = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000001",
            name = "Vee_Trainer1",
            uuids = listOf(BleConstants.NUS_SERVICE_UUID),
        )
        val hrmAdv1 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000002",
            name = "Polar H10 A1",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        upstreamFeed.emit(trainerAdv)
        upstreamFeed.emit(hrmAdv1)
        runCurrent()

        assertTrue(trainerRepo.scannedDevices.value.any { it.name == "Vee_Trainer1" })
        assertTrue(hrmManager.scannedDevices.value.any { it.name == "Polar H10 A1" })

        // Stopping Trainer scan must NOT stop the shared upstream scan while HRM is still scanning.
        trainerRepo.stopScanning()
        runCurrent()
        assertEquals(1, activeUpstreamCollectors)

        val hrmAdv2 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000003",
            name = "Polar H10 B2",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        upstreamFeed.emit(hrmAdv2)
        runCurrent()

        assertTrue(hrmManager.scannedDevices.value.any { it.name == "Polar H10 B2" })
        assertEquals(1, activeUpstreamCollectors)

        // Stopping the final subscriber tears down the upstream collector.
        hrmManager.stopScanning()
        runCurrent()
        assertEquals(0, activeUpstreamCollectors)

        trainerRepo.shutdown()
        hrmManager.shutdown()
        coordinator.shutdown()
    }

    @Test
    fun `upstream scan failure propagates to active subscribers and allows fresh subsequent scan`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        var attempt = 0
        val expectedAdv = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000009",
            name = "Vee_Recovered",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )

        val coordinator = BleScannerCoordinator(
            scope = coordinatorScope,
            upstreamAdvertisements = {
                flow {
                    attempt++
                    if (attempt == 1) {
                        throw IllegalStateException("Bluetooth adapter powered off")
                    }
                    emit(expectedAdv)
                    awaitCancellation()
                }
            },
        )

        assertFailsWith<IllegalStateException> {
            coordinator.advertisements().first()
        }
        runCurrent()

        val recovered = coordinator.advertisements().first()
        assertEquals("Vee_Recovered", recovered.name)
        assertEquals(2, attempt)

        coordinator.shutdown()
    }

    @Test
    fun `immediate resubscribe after upstream failure is not terminated by stale Completed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        var attempt = 0
        val secondRunGate = CompletableDeferred<Unit>()
        val adv1 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000010",
            name = "Vee_Resub1",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )
        val adv2 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000011",
            name = "Vee_Resub2",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )

        val coordinator = BleScannerCoordinator(
            scope = coordinatorScope,
            upstreamAdvertisements = {
                flow {
                    attempt++
                    if (attempt == 1) {
                        throw IllegalStateException("Adapter reset")
                    }
                    emit(adv1)
                    secondRunGate.await()
                    emit(adv2)
                    awaitCancellation()
                }
            },
        )

        val received = mutableListOf<String?>()
        var sub2Job: kotlinx.coroutines.Job? = null
        val sub1Job = launch {
            try {
                coordinator.advertisements().collect {}
            } catch (_: IllegalStateException) {
                sub2Job = this@runTest.launch {
                    coordinator.advertisements().collect { received += it.name }
                }
            }
        }
        runCurrent()

        assertTrue(sub1Job.isCompleted)
        assertTrue(sub2Job?.isActive == true, "Resubscribed collector must remain active after prior upstream failure")
        assertEquals(listOf<String?>("Vee_Resub1"), received)

        secondRunGate.complete(Unit)
        runCurrent()

        assertTrue(sub2Job?.isActive == true, "Stale Completed must not terminate resubscribed collector")
        assertEquals(listOf<String?>("Vee_Resub1", "Vee_Resub2"), received)

        sub2Job?.cancel()
        runCurrent()
        coordinator.shutdown()
    }

    @Test
    fun `slow teardown exceeding 1000ms followed by new subscriber does not poison new scan`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
        var attempt = 0
        val slowTeardownGate = CompletableDeferred<Unit>()
        val secondRunFeed = MutableSharedFlow<Advertisement>(extraBufferCapacity = 4)
        val adv1 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000012",
            name = "Vee_Run1",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )
        val adv2 = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000013",
            name = "Vee_Run2",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )
        val adv2Followup = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000014",
            name = "Vee_Run2_Followup",
            uuids = listOf(Uuid.parse(BleConstants.NUS_SERVICE_UUID_STRING)),
        )

        val coordinator = BleScannerCoordinator(
            scope = coordinatorScope,
            upstreamAdvertisements = {
                flow {
                    attempt++
                    if (attempt == 1) {
                        try {
                            emit(adv1)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                slowTeardownGate.await()
                                throw IllegalStateException("Stale run1 stopScan error")
                            }
                        }
                    } else {
                        emit(adv2)
                        secondRunFeed.collect { emit(it) }
                    }
                }
            },
        )

        val sub1Received = mutableListOf<String?>()
        val sub1 = launch { coordinator.advertisements().collect { sub1Received += it.name } }
        runCurrent()
        assertEquals(listOf<String?>("Vee_Run1"), sub1Received)

        sub1.cancel()
        advanceTimeBy(1_001L)
        runCurrent()

        val sub2Received = mutableListOf<String?>()
        var sub2Error: Throwable? = null
        val sub2 = launch {
            try {
                coordinator.advertisements().collect { sub2Received += it.name }
            } catch (t: Throwable) {
                sub2Error = t
            }
        }
        runCurrent()
        assertTrue(sub2.isActive)
        assertEquals(listOf<String?>("Vee_Run2"), sub2Received)

        // Release stale run1's slow teardown which throws a late exception.
        slowTeardownGate.complete(Unit)
        runCurrent()

        assertEquals(null, sub2Error, "Stale upstream failure must not poison new subscriber")
        assertTrue(sub2.isActive, "New subscriber must remain active after stale upstream exits")

        secondRunFeed.emit(adv2Followup)
        runCurrent()
        assertEquals(listOf<String?>("Vee_Run2", "Vee_Run2_Followup"), sub2Received)

        sub2.cancel()
        runCurrent()
        coordinator.shutdown()
    }
}

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.util.BleConstants
import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Runtime capability state (SD2) + connection generation (SD3) tests (T8–T17).
 *
 * All tests drive the REAL probe paths: real [HrmBleManager] flows, real
 * [HrmBleManager.connect]/[HrmBleManager.disconnect] lifecycle, Kable API
 * doubles only at the transport boundary ([CompositionGattFake]).
 */
class HrmCapabilityStateTest {

    private fun hrPacket(
        hr: Int,
        contactBits: Int = 3,
        rrMs: List<Double> = emptyList(),
    ): ByteArray {
        var flags = (contactBits and 0x03) shl 1
        if (rrMs.isNotEmpty()) flags = flags or 0x10
        val bytes = mutableListOf<Byte>()
        bytes.add(flags.toByte())
        bytes.add(hr.toByte())
        for (rr in rrMs) {
            val raw = (rr * 1024.0 / 1000.0).toInt()
            bytes.add((raw and 0xFF).toByte())
            bytes.add(((raw shr 8) and 0xFF).toByte())
        }
        return bytes.toByteArray()
    }

    private fun garminStyleServices(includeBattery: Boolean = true) = buildList {
        add(fakeDiscoveredService(BleConstants.Hrm.HR_SERVICE_UUID, listOf(BleConstants.Hrm.HR_MEASUREMENT_UUID)))
        if (includeBattery) {
            add(fakeDiscoveredService(BleConstants.Hrm.BATTERY_SERVICE_UUID, listOf(BleConstants.Hrm.BATTERY_LEVEL_UUID)))
        }
    }

    private fun polarStyleServices() = garminStyleServices() + fakeDiscoveredService(
        BleConstants.PolarPmd.PMD_SERVICE_UUID,
        listOf(BleConstants.PolarPmd.PMD_CONTROL_POINT_UUID, BleConstants.PolarPmd.PMD_DATA_UUID),
    )

    // ==================== T8 — post-connection HR validation ====================

    @Test
    fun `T8 candidate advertising 0x180D without the HR characteristic fails validation with a clear non-crash state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Impostor 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        // Authoritative enumeration: the connected device does NOT expose 0x2A37.
        fake.profileOverride = listOf(
            fakeDiscoveredService(BleConstants.NUS_SERVICE_UUID, listOf(BleConstants.REPS_UUID)),
        )
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Impostor 245", advertisement.identifier.toString())).isFailure)
        advanceTimeBy(100)
        runCurrent()

        assertTrue(fake.disconnectCalls > 0, "Peripheral lacking 0x2A37 must be disconnected")
        assertFalse(manager.isConnected.value)
        val state = manager.connectionState.value
        assertTrue(state is ConnectionState.Error, "Expected a clear error state, got $state")
        assertTrue(
            (state as ConnectionState.Error).message.contains("not a standard heart rate sensor"),
            "Error must say the device is not a standard heart rate sensor: ${state.message}",
        )
        val caps = manager.capabilities.value
        assertNotEquals(HrmCapabilityState.CONFIRMED, caps.standardHr)
        manager.shutdown()
    }

    // ==================== T9 — observe registration is not a sample ====================

    @Test
    fun `T9 observe-registration moves standardHr to WAITING_FIRST_SAMPLE not CONFIRMED`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            HrmCapabilityState.WAITING_FIRST_SAMPLE,
            manager.capabilities.value.standardHr,
            "Observation registration must yield WAITING_FIRST_SAMPLE, never CONFIRMED (SD2)",
        )
        assertNotEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)
        manager.shutdown()
    }

    // ==================== T10 — first valid measurement confirms ====================

    @Test
    fun `T10 first valid HR measurement confirms standardHr`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin HRM-Dual",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin HRM-Dual", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(HrmCapabilityState.WAITING_FIRST_SAMPLE, manager.capabilities.value.standardHr)

        // First valid live sample through the real 0x2A37 notification path.
        backgroundScope.launch { fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72)) }
        advanceTimeBy(50)
        runCurrent()

        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)
        assertEquals(72, manager.hrMeasurement.value?.hr)
        manager.shutdown()
    }

    // ==================== T11 — late callbacks from a previous generation ====================

    @Test
    fun `T11 late callback from a previous connection generation is ignored without state mutation or crash`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        val staleGen = manager.connectionGenerationForTesting

        // Disconnect (SD3: generation bump) …
        manager.disconnect()
        advanceTimeBy(50)
        runCurrent()
        assertNotEquals(staleGen, manager.connectionGenerationForTesting, "disconnect must bump the generation token")

        // … then a NEW selection / reconnect.
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        val currentGen = manager.connectionGenerationForTesting
        assertNotEquals(staleGen, currentGen)

        // Failure injection on the real handlers with the stale generation: no
        // state mutation, no crash.
        manager.handleHrNotification(staleGen, fake, hrPacket(hr = 199))
        manager.handleBatteryNotification(staleGen, fake, byteArrayOf(5))
        assertNull(manager.hrMeasurement.value, "stale HR callback must not mutate state")
        assertEquals(87, manager.batteryLevel.value, "stale battery callback must not mutate state")
        assertEquals(HrmCapabilityState.WAITING_FIRST_SAMPLE, manager.capabilities.value.standardHr)

        // The CURRENT generation still works (proves the rejection is generation-
        // based, not a dead handler).
        manager.handleHrNotification(currentGen, fake, hrPacket(hr = 74))
        assertEquals(74, manager.hrMeasurement.value?.hr)
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)
        manager.shutdown()
    }

    // ==================== T12 — connection-owned resets ====================

    @Test
    fun `T12 capabilities reset on disconnect - new selection and reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Polar H10 1234",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Polar H10 1234", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        // Live findings after connect.
        assertEquals(HrmCapabilityState.WAITING_FIRST_SAMPLE, manager.capabilities.value.standardHr)
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.battery)

        // Disconnect resets ALL capability findings and live readings (SD3).
        manager.disconnect()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilities(), manager.capabilities.value, "all capabilities reset to UNKNOWN on disconnect")
        assertNull(manager.batteryLevel.value)
        assertNull(manager.hrMeasurement.value)

        // New selection / reconnect re-derives from scratch.
        assertTrue(manager.connect(ScannedDevice("Polar H10 1234", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(HrmCapabilityState.WAITING_FIRST_SAMPLE, manager.capabilities.value.standardHr)
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.battery)

        // And a drop resets again.
        fake.dropConnection()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilities(), manager.capabilities.value)
        manager.shutdown()
    }

    // ==================== T13 — failed battery probe ====================

    @Test
    fun `T13 failed battery probe is FAILED and never suppresses standardHr`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val peripheral = object : Peripheral by fake {
            override suspend fun read(characteristic: Characteristic): ByteArray {
                if (characteristic.characteristicUuid == BleConstants.Hrm.BATTERY_LEVEL_UUID) {
                    throw IllegalStateException("GATT read error (injected)")
                }
                return fake.read(characteristic)
            }
        }
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { peripheral },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            HrmCapabilityState.FAILED,
            manager.capabilities.value.battery,
            "probe error must be FAILED, never ABSENT (SD2)",
        )
        assertNotEquals(HrmCapabilityState.ABSENT, manager.capabilities.value.battery)

        // Standard HR is unaffected by the failed optional probe.
        assertEquals(HrmCapabilityState.WAITING_FIRST_SAMPLE, manager.capabilities.value.standardHr)
        backgroundScope.launch { fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72)) }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)
        assertEquals(72, manager.hrMeasurement.value?.hr)
        manager.shutdown()
    }

    // ==================== T14 — battery CONFIRMED / ABSENT distinctions ====================

    @Test
    fun `T14 battery CONFIRMED on successful read or notification - ABSENT only from authoritative enumeration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // (a) Successful 0x2A19 read -> CONFIRMED.
        val advA = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000101",
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fakeA = CompositionGattFake(advA, dispatcher)
        val managerA = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advA) },
            createPeripheral = { fakeA },
        )
        managerA.startScanning()
        runCurrent()
        assertTrue(managerA.connect(ScannedDevice("Garmin Forerunner 245", advA.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, managerA.capabilities.value.battery)
        assertEquals(87, managerA.batteryLevel.value)

        // (b) Read failure -> FAILED, then a battery notification -> CONFIRMED.
        val advB = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000102",
            name = "Garmin Forerunner 255",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fakeB = CompositionGattFake(advB, dispatcher)
        fakeB.profileOverride = garminStyleServices()
        val peripheralB = object : Peripheral by fakeB {
            override suspend fun read(characteristic: Characteristic): ByteArray {
                if (characteristic.characteristicUuid == BleConstants.Hrm.BATTERY_LEVEL_UUID) {
                    throw IllegalStateException("GATT read error (injected)")
                }
                return fakeB.read(characteristic)
            }
        }
        val managerB = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advB) },
            createPeripheral = { peripheralB },
        )
        managerB.startScanning()
        runCurrent()
        assertTrue(managerB.connect(ScannedDevice("Garmin Forerunner 255", advB.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(HrmCapabilityState.FAILED, managerB.capabilities.value.battery)
        backgroundScope.launch {
            fakeB.notify(BleConstants.Hrm.batteryLevelCharacteristic, byteArrayOf(55))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, managerB.capabilities.value.battery, "notification CONFIRMs battery")
        assertEquals(55, managerB.batteryLevel.value)

        // (c) Authoritative enumeration without 0x2A19 -> ABSENT (distinct from FAILED/UNKNOWN).
        val advC = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000103",
            name = "Garmin Forerunner 265",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fakeC = CompositionGattFake(advC, dispatcher)
        fakeC.profileOverride = garminStyleServices(includeBattery = false)
        val managerC = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advC) },
            createPeripheral = { fakeC },
        )
        managerC.startScanning()
        runCurrent()
        assertTrue(managerC.connect(ScannedDevice("Garmin Forerunner 265", advC.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(HrmCapabilityState.ABSENT, managerC.capabilities.value.battery)
        assertNotEquals(HrmCapabilityState.FAILED, managerC.capabilities.value.battery)
        assertNotEquals(HrmCapabilityState.UNKNOWN, managerC.capabilities.value.battery)
        assertNull(managerC.batteryLevel.value, "no fabricated battery value")
        // Standard HR still fully functional (missing battery never suppresses HR).
        backgroundScope.launch {
            fakeC.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 68))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, managerC.capabilities.value.standardHr)
        managerA.shutdown()
        managerB.shutdown()
        managerC.shutdown()
    }

    // ==================== T15 — RR confirmation ====================

    @Test
    fun `T15 rrIntervals CONFIRMED only after a measurement actually carrying RR intervals`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        // Measurement WITHOUT RR intervals.
        backgroundScope.launch {
            fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 70))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)
        assertNotEquals(
            HrmCapabilityState.CONFIRMED,
            manager.capabilities.value.rrIntervals,
            "RR must not be confirmed by a measurement without RR intervals",
        )

        // Measurement WITH RR intervals -> CONFIRMED.
        backgroundScope.launch {
            fake.notify(
                BleConstants.Hrm.hrMeasurementCharacteristic,
                hrPacket(hr = 71, rrMs = listOf(820.0, 840.0)),
            )
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.rrIntervals)
        manager.shutdown()
    }

    // ==================== T16 — PMD requires service + subscription ====================

    @Test
    fun `T16 PMD ABSENT on Garmin-style device with healthy connection - controls never inferred from name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // The ADVERTISED NAME says Polar H10 — name/family must NEVER imply PMD.
        val advertisement = CompositionAdvertisement(
            name = "Polar H10 555",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices() // no FB005C80 service
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Polar H10 555", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(HrmCapabilityState.ABSENT, manager.capabilities.value.polarPmd)
        // Connection stays healthy.
        assertTrue(manager.isConnected.value)
        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        // Standard HR unaffected.
        backgroundScope.launch {
            fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, manager.capabilities.value.standardHr)

        // PMD controls are refused and NOTHING is written to the control point.
        assertTrue(manager.startEcgStream().isFailure)
        assertTrue(manager.startAccStream().isFailure)
        assertTrue(fake.writes.isEmpty(), "no PMD command may be written without the real PMD service")
        manager.shutdown()
    }

    @Test
    fun `T16 PMD CONFIRMED when service present and subscription succeeds - FAILED on subscription error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // (a) Polar-style services + working subscription -> CONFIRMED.
        val advA = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000201",
            name = "Polar H10 1234",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fakeA = CompositionGattFake(advA, dispatcher)
        fakeA.profileOverride = polarStyleServices()
        val managerA = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advA) },
            createPeripheral = { fakeA },
        )
        managerA.startScanning()
        runCurrent()
        assertTrue(managerA.connect(ScannedDevice("Polar H10 1234", advA.identifier.toString())).isSuccess)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(HrmCapabilityState.CONFIRMED, managerA.capabilities.value.polarPmd)

        // (b) PMD service present but subscription fails -> FAILED (never ABSENT).
        val advB = CompositionAdvertisement(
            id = "00000000-0000-0000-0000-000000000202",
            name = "Polar H10 4321",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fakeB = CompositionGattFake(advB, dispatcher)
        fakeB.profileOverride = polarStyleServices()
        val peripheralB = object : Peripheral by fakeB {
            override fun observe(
                characteristic: Characteristic,
                onSubscription: suspend () -> Unit,
            ): Flow<ByteArray> = flow {
                if (characteristic.serviceUuid == BleConstants.PolarPmd.PMD_SERVICE_UUID) {
                    throw IllegalStateException("PMD subscription error (injected)")
                }
                onSubscription()
                awaitCancellation()
            }
        }
        val managerB = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advB) },
            createPeripheral = { peripheralB },
        )
        managerB.startScanning()
        runCurrent()
        assertTrue(managerB.connect(ScannedDevice("Polar H10 4321", advB.identifier.toString())).isSuccess)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(HrmCapabilityState.FAILED, managerB.capabilities.value.polarPmd)
        assertNotEquals(HrmCapabilityState.ABSENT, managerB.capabilities.value.polarPmd)
        assertTrue(managerB.startEcgStream().isFailure)
        managerA.shutdown()
        managerB.shutdown()
    }

    // ==================== T17 — contact tri-state ====================

    @Test
    fun `T17 contact is tri-state - not-reported vs reported-detected vs reported-not-detected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin HRM-Pro",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin HRM-Pro", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        // Before any measurement: not reported.
        assertEquals(ContactReportState.NOT_REPORTED, manager.capabilities.value.contact)

        // Sensor reports contact DETECTED (flags bits 1-2 = 11b).
        backgroundScope.launch {
            fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72, contactBits = 3))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ContactReportState.REPORTED_DETECTED, manager.capabilities.value.contact)
        assertTrue(manager.isContactDetected.value)

        // Sensor reports contact NOT DETECTED (10b) — existing contact-safety
        // behavior preserved: telemetry dropped.
        backgroundScope.launch {
            fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72, contactBits = 2))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ContactReportState.REPORTED_NOT_DETECTED, manager.capabilities.value.contact)
        assertTrue(!manager.isContactDetected.value, "contact-safety behavior preserved")

        // Sensor does not report contact status (00b/01b) — distinct from the two
        // reported states; existing heuristic (HR > 0 counts as contact) preserved.
        backgroundScope.launch {
            fake.notify(BleConstants.Hrm.hrMeasurementCharacteristic, hrPacket(hr = 72, contactBits = 0))
        }
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ContactReportState.NOT_REPORTED, manager.capabilities.value.contact)
        assertTrue(manager.isContactDetected.value, "unreported contact keeps the existing HR>0 heuristic")
        manager.shutdown()
    }

    // ==================== T27 (manager seam) — PMD-only ECG controls ====================

    @Test
    fun `T27 ECG stream controls require PMD - a non-PMD device never reaches the preference path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val advertisement = CompositionAdvertisement(
            name = "Garmin Forerunner 245",
            uuids = listOf(BleConstants.Hrm.HR_SERVICE_UUID),
        )
        val fake = CompositionGattFake(advertisement, dispatcher)
        fake.profileOverride = garminStyleServices()
        val manager = HrmBleManager(
            scope = backgroundScope,
            advertisements = { flowOf(advertisement) },
            createPeripheral = { fake },
        )
        manager.startScanning()
        runCurrent()
        assertTrue(manager.connect(ScannedDevice("Garmin Forerunner 245", advertisement.identifier.toString())).isSuccess)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(HrmCapabilityState.ABSENT, manager.capabilities.value.polarPmd)
        assertTrue(manager.startEcgStream().isFailure, "non-PMD device must never start ECG")
        assertTrue(!manager.isEcgStreaming.value)
        assertTrue(fake.writes.isEmpty())
        manager.shutdown()
    }
}

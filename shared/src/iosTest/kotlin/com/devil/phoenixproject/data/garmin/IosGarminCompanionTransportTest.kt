package com.devil.phoenixproject.data.garmin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #1391 packet B5: iOS transport behaviour against a fake native bridge —
 * message provenance before decode, solicited-only device-selection callback
 * acceptance (signoff decision 3), and no duplicate listeners on recreation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosGarminCompanionTransportTest {

    private class FakeNativeBridge : GarminNativeBridge {
        val initializeSchemes = mutableListOf<String>()
        var showSelectionCalls = 0
        val deviceRegs = mutableListOf<String>()
        val deviceUnregs = mutableListOf<String>()
        val appRegs = mutableListOf<Pair<String, String>>()
        val appUnregs = mutableListOf<Pair<String, String>>()
        var allDeviceUnregs = 0
        var allAppUnregs = 0
        val statusQueries = mutableListOf<Pair<String, Long>>()
        val sends = mutableListOf<Triple<String, String, Long>>()
        var shutdownCalls = 0

        override fun initializeSdk(urlScheme: String) {
            initializeSchemes += urlScheme
        }

        override fun showDeviceSelection() {
            showSelectionCalls++
        }

        override fun registerDeviceEvents(deviceRef: String) {
            deviceRegs += deviceRef
        }

        override fun unregisterDeviceEvents(deviceRef: String) {
            deviceUnregs += deviceRef
        }

        override fun unregisterAllDeviceEvents() {
            allDeviceUnregs++
        }

        override fun queryDeviceStatus(deviceRef: String, requestId: Long) {
            statusQueries += deviceRef to requestId
        }

        override fun registerAppMessages(deviceRef: String, appUuid: String) {
            appRegs += deviceRef to appUuid
        }

        override fun unregisterAppMessages(deviceRef: String, appUuid: String) {
            appUnregs += deviceRef to appUuid
        }

        override fun unregisterAllAppMessages() {
            allAppUnregs++
        }

        override fun queryAppInstalled(deviceRef: String, appUuid: String, requestId: Long) = Unit

        override fun send(deviceRef: String, appUuid: String, message: Map<String, Any?>, requestId: Long) {
            sends += Triple(deviceRef, appUuid, requestId)
        }

        override fun shutdownSdk() {
            shutdownCalls++
        }
    }

    private class Fixture(dispatcher: TestDispatcher, appUuid: String?) {
        val bridge = FakeNativeBridge()
        var nowMs: Long = 1_000L
        val transport: IosGarminCompanionTransport

        init {
            GarminCompanionBridgeRegistry.registerBridge(bridge)
            transport = IosGarminCompanionTransport(
                watchAppUuid = { appUuid },
                ownerDispatcher = dispatcher,
                monotonicNowMs = { nowMs },
                registry = GarminCompanionBridgeRegistry,
                selectionTtlMs = SELECTION_TTL_MS,
            )
        }

        /** Runs a solicited selection through device-status confirmation. */
        fun selectConfirmed(results: MutableList<GarminDeviceSelectionResult>, deviceRef: String, name: String) {
            transport.startDeviceSelection { results += it }
            transport.onNativeDeviceSelectionParsed(
                listOf(GarminBridgeDeviceInfo(deviceRef, name)),
                GARMIN_CALLBACK_URL_SCHEME,
                null,
            )
        }
    }

    @Test
    fun `solicited selection callback is accepted and devices confirmed via status registration`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        val transport = fixture.transport
        transport.attach(1L)
        advanceUntilIdle()
        assertEquals(listOf(GARMIN_CALLBACK_URL_SCHEME), fixture.bridge.initializeSchemes)

        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()

        // The parsed device is registered for device events BEFORE its status query.
        assertEquals(listOf("d1"), fixture.bridge.deviceRegs)
        val (queriedRef, requestId) = fixture.bridge.statusQueries.single()
        assertEquals("d1", queriedRef)
        assertTrue(results.isEmpty(), "selection completes only after device-status confirmation")

        transport.onNativeDeviceStatusResult(requestId, queriedRef, "connected")
        advanceUntilIdle()

        val selected = assertIs<GarminDeviceSelectionResult.Selected>(results.single())
        assertEquals("d1", selected.device.deviceRef)
        assertTrue(selected.device.isConnected)
        // App-message registration happens after device registration, on the selected device.
        assertEquals(listOf("d1" to APP_UUID), fixture.bridge.appRegs)
        assertEquals(listOf("d1"), transport.devices.value.map { it.deviceRef })
    }

    @Test
    fun `unsolicited selection callback is rejected`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        fixture.transport.attach(1L)
        advanceUntilIdle()

        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.transport.onNativeDeviceSelectionParsed(
            listOf(GarminBridgeDeviceInfo("d1", "Fenix")),
            GARMIN_CALLBACK_URL_SCHEME,
            "com.garmin.android.apps.connectmobile",
        )
        advanceUntilIdle()
        assertTrue(results.isEmpty(), "no phone-initiated request means the callback is rejected")
        assertTrue(fixture.bridge.deviceRegs.isEmpty(), "rejected callbacks register nothing")
    }

    @Test
    fun `expired selection callback is rejected and the request fails closed`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        fixture.transport.attach(1L)
        advanceUntilIdle()

        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.transport.startDeviceSelection { results += it }
        fixture.nowMs += SELECTION_TTL_MS + 1
        fixture.transport.onNativeDeviceSelectionParsed(
            listOf(GarminBridgeDeviceInfo("d1", "Fenix")),
            GARMIN_CALLBACK_URL_SCHEME,
            null,
        )
        advanceUntilIdle()

        val failed = assertIs<GarminDeviceSelectionResult.Failed>(results.single())
        assertEquals("selection_expired", failed.code)
        assertTrue(fixture.bridge.deviceRegs.isEmpty())
    }

    @Test
    fun `repeated selection callback is rejected after the single-use request is consumed`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        fixture.transport.attach(1L)
        advanceUntilIdle()

        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        fixture.transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()
        assertEquals(1, results.size, "first callback fulfils the single-use request")

        fixture.transport.onNativeDeviceSelectionParsed(
            listOf(GarminBridgeDeviceInfo("d2", "Venu")),
            GARMIN_CALLBACK_URL_SCHEME,
            null,
        )
        advanceUntilIdle()
        assertEquals(1, results.size, "repeated callback is rejected")
        assertEquals(listOf("d1"), fixture.bridge.deviceRegs, "repeated callback registers nothing")
    }

    @Test
    fun `callback on the OAuth scheme is rejected while the Garmin scheme stays accepted`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        fixture.transport.attach(1L)
        advanceUntilIdle()

        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.transport.startDeviceSelection { results += it }
        fixture.transport.onNativeDeviceSelectionParsed(
            listOf(GarminBridgeDeviceInfo("d1", "Fenix")),
            "com.devil.phoenixproject", // the OAuth scheme — kept separate, never accepted
            null,
        )
        advanceUntilIdle()
        assertTrue(results.isEmpty(), "OAuth-scheme callback must be rejected")
        assertTrue(fixture.bridge.deviceRegs.isEmpty())
        assertTrue(GARMIN_CALLBACK_URL_SCHEME != "com.devil.phoenixproject")

        // The same outstanding request still accepts the dedicated Garmin scheme once.
        fixture.transport.onNativeDeviceSelectionParsed(
            listOf(GarminBridgeDeviceInfo("d1", "Fenix")),
            GARMIN_CALLBACK_URL_SCHEME,
            null,
        )
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        fixture.transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()
        assertIs<GarminDeviceSelectionResult.Selected>(results.single())
    }

    @Test
    fun `native app provenance is checked before decoding messages`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        val transport = fixture.transport
        transport.attach(1L)
        advanceUntilIdle()
        val received = mutableListOf<GarminInboundEnvelope>()
        val collect: Job = launch(StandardTestDispatcher(testScheduler)) { transport.inbound.collect { received += it } }
        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()

        transport.onNativeAppMessage("d1", "00000000-0000-0000-0000-000000000000", mapOf("x" to 1))
        transport.onNativeAppMessage("d1", APP_UUID, mapOf("action" to "stop_set", "deep" to listOf<Any>()))
        advanceUntilIdle()

        assertEquals(1, received.size, "wrong app UUID drops before decode; non-primitive values are bounded out")
        assertEquals(APP_UUID, received.single().appUuid)
        assertEquals("d1", received.single().deviceRef)
        assertEquals(mapOf<String, Any?>("action" to "stop_set"), received.single().message)
        collect.cancel()
    }

    @Test
    fun `recreation does not duplicate listeners`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        val transport = fixture.transport
        transport.attach(1L)
        advanceUntilIdle()
        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()
        assertEquals(1, fixture.bridge.deviceRegs.size)

        // Recomposition with the same owner generation: no re-init, no re-registration.
        transport.attach(1L)
        advanceUntilIdle()
        assertEquals(1, fixture.bridge.initializeSchemes.size)

        // A fresh selection for the same device must not register it twice.
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        assertEquals(1, fixture.bridge.deviceRegs.size, "recreation must not register duplicate listeners")
    }

    @Test
    fun `late send completions after teardown are dropped`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        val transport = fixture.transport
        transport.attach(1L)
        advanceUntilIdle()
        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()

        val completions = mutableListOf<GarminSendStatus>()
        transport.send("d1", mapOf("cmd" to "ping")) { completions += it }
        advanceUntilIdle()
        val (_, _, sendRequestId) = fixture.bridge.sends.single()
        assertTrue(completions.isEmpty())

        // Detach fails the pending send and unregisters everything it registered.
        transport.detach()
        advanceUntilIdle()
        assertEquals(listOf(GarminSendStatus.TRANSPORT_ERROR), completions)
        assertTrue(fixture.bridge.allDeviceUnregs >= 1)
        assertTrue(fixture.bridge.allAppUnregs >= 1)

        // A late native completion is dropped: nothing further reaches the owner.
        transport.onNativeSendResult(sendRequestId, "success")
        advanceUntilIdle()
        assertEquals(1, completions.size, "late native callback after teardown must be dropped")
    }

    @Test
    fun `send maps native statuses and rejects invalid targets`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), APP_UUID)
        val transport = fixture.transport
        transport.attach(1L)
        advanceUntilIdle()
        val results = mutableListOf<GarminDeviceSelectionResult>()
        fixture.selectConfirmed(results, "d1", "Fenix")
        advanceUntilIdle()
        val (ref, requestId) = fixture.bridge.statusQueries.single()
        transport.onNativeDeviceStatusResult(requestId, ref, "connected")
        advanceUntilIdle()

        val completions = mutableListOf<GarminSendStatus>()
        transport.send("404", mapOf("cmd" to "x")) { completions += it }
        advanceUntilIdle()
        assertEquals(listOf(GarminSendStatus.INVALID_TARGET), completions)

        transport.send("d1", mapOf("cmd" to "x")) { completions += it }
        advanceUntilIdle()
        transport.onNativeSendResult(fixture.bridge.sends.last().third, "failure_app_not_found")
        transport.send("d1", mapOf("cmd" to "x")) { completions += it }
        advanceUntilIdle()
        transport.onNativeSendResult(fixture.bridge.sends.last().third, "failure_device_not_available")
        transport.send("d1", mapOf("cmd" to "x")) { completions += it }
        advanceUntilIdle()
        transport.onNativeSendResult(fixture.bridge.sends.last().third, "success")
        advanceUntilIdle()
        assertEquals(
            listOf(
                GarminSendStatus.INVALID_TARGET,
                GarminSendStatus.APP_NOT_INSTALLED,
                GarminSendStatus.NOT_CONNECTED,
                GarminSendStatus.SUCCESS,
            ),
            completions,
        )
    }

    private companion object {
        const val APP_UUID = "9e3f1a52-7ab0-4f6c-b2d1-3c2f9a44e001"
        const val SELECTION_TTL_MS = 5 * 60 * 1000L
    }
}

package com.devil.phoenixproject.data.garmin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1391 packet B4: Android transport behaviour against a fake CIQ SDK seam —
 * SDK ready/shutdown with paired register/unregister, provenance rejection before
 * decode, and owner-generation drops of late callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidGarminCompanionTransportTest {

    private class FakeCiqSdk : GarminCiqSdk {
        var initializeCalls = 0
        var shutdownCalls = 0
        var initListener: GarminCiqSdkListener? = null
        var queryBeforeReady = false
        private var ready = false

        var known: List<GarminCiqNativeDevice> = emptyList()
        var connected: List<GarminCiqNativeDevice> = emptyList()

        val deviceListenerRegs = LinkedHashMap<String, GarminCiqSdkDeviceListener>()
        val appListenerRegs = LinkedHashMap<Pair<String, String>, GarminCiqSdkAppListener>()
        val deviceUnregs = mutableListOf<String>()
        val appUnregs = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        var lastSendListener: GarminCiqSdkSendListener? = null

        override fun initialize(listener: GarminCiqSdkListener) {
            initializeCalls++
            initListener = listener
        }

        override fun shutdown() {
            shutdownCalls++
        }

        override fun listConnectedDevices(): List<GarminCiqNativeDevice> {
            if (!ready) queryBeforeReady = true
            return connected
        }

        override fun listKnownDevices(): List<GarminCiqNativeDevice> {
            if (!ready) queryBeforeReady = true
            return known
        }

        override fun registerDeviceEvents(deviceRef: String, listener: GarminCiqSdkDeviceListener) {
            deviceListenerRegs[deviceRef] = listener
        }

        override fun unregisterDeviceEvents(deviceRef: String) {
            deviceUnregs += deviceRef
            deviceListenerRegs.remove(deviceRef)
        }

        override fun registerAppEvents(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppListener) {
            appListenerRegs[deviceRef to appUuid] = listener
        }

        override fun unregisterAppEvents(deviceRef: String, appUuid: String) {
            appUnregs += deviceRef to appUuid
            appListenerRegs.remove(deviceRef to appUuid)
        }

        override fun queryAppInfo(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppInfoListener) {
            listener.onAppInfo("installed")
        }

        override fun send(
            deviceRef: String,
            appUuid: String,
            message: Map<String, Any?>,
            listener: GarminCiqSdkSendListener,
        ) {
            sent += Triple(deviceRef, appUuid, message)
            lastSendListener = listener
        }

        fun markReady() {
            ready = true
            initListener?.onSdkReady()
        }
    }

    private fun transport(
        sdk: FakeCiqSdk,
        appUuid: String? = APP_UUID,
        clock: () -> Long = { 1000L },
    ) = AndroidGarminCompanionTransport(
        context = ApplicationProvider.getApplicationContext<Application>(),
        watchAppUuid = { appUuid },
        ownerDispatcher = Dispatchers.Unconfined,
        sdkOverride = sdk,
        monotonicNowMs = clock,
    )

    @Test
    fun `sdk ready gates queries and shutdown tears down paired listeners`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk)
        val received = mutableListOf<GarminInboundEnvelope>()
        val collect: Job = launch(Dispatchers.Unconfined) { transport.inbound.collect { received += it } }

        transport.attach(1L)
        assertEquals(GarminTransportState.Initializing, transport.state.value)

        // Selection requested before onSdkReady must wait: no SDK query until ready.
        var selection: GarminDeviceSelectionResult? = null
        transport.startDeviceSelection { selection = it }
        assertNull(selection)
        assertFalse(sdk.queryBeforeReady, "no device query before onSdkReady()")

        sdk.markReady()
        assertEquals(GarminTransportState.Ready, transport.state.value)
        val selected = assertIs<GarminDeviceSelectionResult.Selected>(selection)
        assertEquals("101", selected.device.deviceRef)

        // Device events are registered before the app events (send/app operations).
        assertTrue(sdk.deviceListenerRegs.containsKey("101"))
        assertTrue(sdk.appListenerRegs.containsKey("101" to APP_UUID))

        // Inbound with native provenance decodes after receive-status + UUID checks.
        val inbound = GarminCiqNativeInbound(
            deviceRef = "101",
            appUuid = APP_UUID,
            receiveStatus = "SUCCESS",
            rawMessages = listOf(mapOf("action" to "stop_set")),
        )
        sdk.appListenerRegs.getValue("101" to APP_UUID).onMessageReceived(inbound)
        assertEquals(1, received.size)
        assertEquals("101", received.single().deviceRef)
        assertEquals(APP_UUID, received.single().appUuid)
        assertEquals("stop_set", received.single().message["action"])

        // detach() unregisters everything it registered — exactly once.
        transport.detach()
        assertEquals(setOf("101"), sdk.deviceUnregs.toSet())
        assertEquals(setOf("101" to APP_UUID), sdk.appUnregs.toSet())
        assertTrue(sdk.deviceListenerRegs.isEmpty())
        assertTrue(sdk.appListenerRegs.isEmpty())
        assertEquals(0, sdk.shutdownCalls, "detach() must not tear down the SDK")

        transport.shutdown()
        assertEquals(1, sdk.shutdownCalls)
        assertEquals(GarminTransportState.ShutDown, transport.state.value)
        collect.cancel()
    }

    @Test
    fun `recomposition does not register listeners twice`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk)
        transport.attach(7L)
        sdk.markReady()
        transport.startDeviceSelection { }
        val regsAfterSelection = sdk.deviceListenerRegs.size

        // Same owner generation again (recomposition): no duplicate registration.
        transport.attach(7L)
        assertEquals(1, sdk.initializeCalls)
        assertEquals(regsAfterSelection, sdk.deviceListenerRegs.size)
    }

    @Test
    fun `provenance and receive status are checked before decode`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk)
        val received = mutableListOf<GarminInboundEnvelope>()
        val collect: Job = launch(Dispatchers.Unconfined) { transport.inbound.collect { received += it } }

        transport.attach(1L)
        sdk.markReady()
        transport.startDeviceSelection { }
        val appListener = sdk.appListenerRegs.getValue("101" to APP_UUID)

        // Wrong native app UUID: dropped before decode.
        appListener.onMessageReceived(
            GarminCiqNativeInbound("101", "00000000-0000-0000-0000-000000000000", "SUCCESS", listOf(mapOf("x" to 1))),
        )
        assertTrue(received.isEmpty(), "wrong app UUID must be dropped")

        // Non-SUCCESS receive status: dropped before decode.
        appListener.onMessageReceived(
            GarminCiqNativeInbound("101", APP_UUID, "FAILURE_UNKNOWN", listOf(mapOf("x" to 1))),
        )
        assertTrue(received.isEmpty(), "failed receive status must be dropped")

        // Native provenance with the production watch-app UUID decodes.
        appListener.onMessageReceived(
            GarminCiqNativeInbound(
                "101",
                APP_UUID.lowercase(),
                "SUCCESS",
                listOf(mapOf("action" to "next_set", "n" to 3), "not-a-dictionary"),
            ),
        )
        assertEquals(1, received.size, "only the dictionary message decodes")
        assertEquals(mapOf("action" to "next_set", "n" to 3), received.single().message)
        collect.cancel()
    }

    @Test
    fun `unconfigured production app uuid fails closed`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk, appUuid = null)
        val received = mutableListOf<GarminInboundEnvelope>()
        val collect: Job = launch(Dispatchers.Unconfined) { transport.inbound.collect { received += it } }
        transport.attach(1L)
        sdk.markReady()
        transport.startDeviceSelection { }
        // Without a configured production watch-app UUID nothing may register or decode.
        assertTrue(sdk.appListenerRegs.isEmpty(), "no app events may register without a configured UUID")
        assertTrue(received.isEmpty())
        collect.cancel()
    }

    @Test
    fun `late callbacks carrying an older owner generation are dropped`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk)
        val received = mutableListOf<GarminInboundEnvelope>()
        val collect: Job = launch(Dispatchers.Unconfined) { transport.inbound.collect { received += it } }

        transport.attach(1L)
        sdk.markReady()
        transport.startDeviceSelection { }
        val oldAppListener = sdk.appListenerRegs.getValue("101" to APP_UUID)

        // An outbound send under generation 1 completes late.
        val completions = mutableListOf<GarminSendStatus>()
        transport.send("101", mapOf("cmd" to "ping")) { completions += it }
        val oldSendListener = sdk.lastSendListener

        // New owner generation: listeners are re-registered for generation 2.
        transport.attach(2L)
        sdk.markReady()
        val newAppListener = sdk.appListenerRegs.getValue("101" to APP_UUID)

        // Late inbound on the old generation's listener: dropped, never decoded.
        oldAppListener.onMessageReceived(
            GarminCiqNativeInbound("101", APP_UUID, "SUCCESS", listOf(mapOf("x" to 1))),
        )
        assertTrue(received.isEmpty(), "late callback from an older owner generation must be dropped")

        // Late send completion from the old generation: dropped.
        oldSendListener?.onMessageStatus("SUCCESS")
        assertTrue(completions.isEmpty(), "late send completion from an older owner generation must be dropped")

        // Current generation still works end to end.
        newAppListener.onMessageReceived(
            GarminCiqNativeInbound("101", APP_UUID, "SUCCESS", listOf(mapOf("action" to "stop_set"))),
        )
        assertEquals(1, received.size)
        transport.send("101", mapOf("cmd" to "ping")) { completions += it }
        sdk.lastSendListener?.onMessageStatus("SUCCESS")
        assertEquals(listOf(GarminSendStatus.SUCCESS), completions)
        collect.cancel()
    }

    @Test
    fun `send maps native statuses and rejects unknown targets`() = runBlocking {
        val sdk = FakeCiqSdk()
        sdk.connected = listOf(GarminCiqNativeDevice("101", "Fenix"))
        val transport = transport(sdk)
        transport.attach(1L)
        sdk.markReady()
        transport.startDeviceSelection { }

        val results = mutableListOf<GarminSendStatus>()
        transport.send("404", mapOf("cmd" to "x")) { results += it }
        assertEquals(listOf(GarminSendStatus.INVALID_TARGET), results)

        transport.send("101", mapOf("cmd" to "x")) { results += it }
        sdk.lastSendListener?.onMessageStatus("FAILURE_DEVICE_NOT_CONNECTED")
        transport.send("101", mapOf("cmd" to "x")) { results += it }
        sdk.lastSendListener?.onMessageStatus("FAILURE_INVALID_DEVICE")
        transport.send("101", mapOf("cmd" to "x")) { results += it }
        sdk.lastSendListener?.onMessageStatus("SUCCESS")
        assertEquals(
            listOf(
                GarminSendStatus.INVALID_TARGET,
                GarminSendStatus.NOT_CONNECTED,
                GarminSendStatus.INVALID_TARGET,
                GarminSendStatus.SUCCESS,
            ),
            results,
        )
    }

    @Test
    fun `missing or upgrading Garmin Connect Mobile maps to ClientUnavailable`() = runBlocking {
        val sdk = FakeCiqSdk()
        val transport = transport(sdk)
        transport.attach(1L)
        sdk.initListener?.onInitializeError("gcm_not_installed")
        assertEquals(GarminTransportState.ClientUnavailable, transport.state.value)
        sdk.initListener?.onInitializeError("gcm_upgrade_needed")
        assertEquals(GarminTransportState.ClientUnavailable, transport.state.value)
        sdk.initListener?.onInitializeError("service_error")
        val error = assertIs<GarminTransportState.Error>(transport.state.value)
        assertEquals("service_error", error.code)
    }

    private companion object {
        const val APP_UUID = "9e3f1a52-7ab0-4f6c-b2d1-3c2f9a44e001"
    }
}

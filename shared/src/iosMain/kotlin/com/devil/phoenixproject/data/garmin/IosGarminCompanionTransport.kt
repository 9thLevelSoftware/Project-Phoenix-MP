package com.devil.phoenixproject.data.garmin

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.withPlatformLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSProcessInfo

private val log = Logger.withTag("IosGarminTransport")

/**
 * The DEDICATED Garmin callback scheme GCM returns device-selection results on. Kept
 * separate from the OAuth scheme (`com.devil.phoenixproject`); a callback is only ever
 * accepted when it arrives on this scheme (signoff decision 3).
 */
internal const val GARMIN_CALLBACK_URL_SCHEME: String = "com.devil.phoenixproject.garmin"

/** Maximum age of a phone-initiated selection request; signoff decision 3 requires <= 5 minutes. */
internal const val GARMIN_SELECTION_REQUEST_TTL_MS: Long = 5 * 60 * 1000L

// ---------------------------------------------------------------------------
// Native bridge seam. GarminCompanionBridge.swift (app target) implements
// [GarminNativeBridge] against the Garmin Connect IQ Swift package and reports
// SDK callbacks through [GarminNativeBridgeEvents]. Unit tests substitute a fake.
// ---------------------------------------------------------------------------

/** SDK-delivered device identity (IQDevice); never a dictionary claim. */
class GarminBridgeDeviceInfo(val deviceRef: String, val name: String)

/**
 * Implemented by `GarminCompanionBridge.swift`. All methods may be called from any
 * thread; the bridge funnels SDK work onto the main queue and reports asynchronously.
 */
interface GarminNativeBridge {
    fun initializeSdk(urlScheme: String)
    fun showDeviceSelection()
    fun registerDeviceEvents(deviceRef: String)
    fun unregisterDeviceEvents(deviceRef: String)
    fun unregisterAllDeviceEvents()
    /** Result arrives via [GarminNativeBridgeEvents.onNativeDeviceStatusResult]. */
    fun queryDeviceStatus(deviceRef: String, requestId: Long)
    fun registerAppMessages(deviceRef: String, appUuid: String)
    fun unregisterAppMessages(deviceRef: String, appUuid: String)
    fun unregisterAllAppMessages()
    /** Result arrives via [GarminNativeBridgeEvents.onNativeAppInstalledResult]. */
    fun queryAppInstalled(deviceRef: String, appUuid: String, requestId: Long)
    /** Result arrives via [GarminNativeBridgeEvents.onNativeSendResult]. */
    fun send(deviceRef: String, appUuid: String, message: Map<String, Any?>, requestId: Long)
    fun shutdownSdk()
}

/** Implemented by [IosGarminCompanionTransport]; called by the Swift bridge. */
interface GarminNativeBridgeEvents {
    /** [status]: `connected`, `not_connected`, `not_found`, `bluetooth_not_ready`, `invalid`. */
    fun onNativeDeviceStatusChanged(deviceRef: String, name: String, status: String)
    fun onNativeAppMessage(deviceRef: String, appUuid: String, message: Map<String, Any?>)
    fun onNativeDeviceStatusResult(requestId: Long, deviceRef: String, status: String)
    /** [status]: raw IQSendMessageResult name in snake case (see the Swift bridge). */
    fun onNativeSendResult(requestId: Long, status: String)
    /** [installed]: `installed`, `not_installed`, `unknown`. */
    fun onNativeAppInstalledResult(requestId: Long, installed: String)
    fun onNativeDeviceSelectionParsed(
        devices: List<GarminBridgeDeviceInfo>,
        urlScheme: String,
        sourceApplication: String?,
    )
    /** Garmin Connect Mobile is missing/needs an upgrade. */
    fun onNativeGcmRequired()
    fun onNativeSdkError(code: String)
}

/**
 * Registration point between the Swift bridge and the Kotlin transport. The bridge is
 * installed once at app start; the active [GarminNativeBridgeEvents] receiver is set by
 * the transport on attach and cleared on detach, so callbacks after detach go nowhere.
 */
object GarminCompanionBridgeRegistry {
    var bridge: GarminNativeBridge? = null
        private set
    var events: GarminNativeBridgeEvents? = null
        private set

    fun registerBridge(bridge: GarminNativeBridge) {
        this.bridge = bridge
    }

    fun registerEvents(events: GarminNativeBridgeEvents) {
        this.events = events
    }

    /** Unregisters only when [events] is still the registered receiver (no stale clears). */
    fun unregisterEvents(events: GarminNativeBridgeEvents) {
        if (this.events === events) this.events = null
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/**
 * iOS implementation of [GarminCompanionTransport] (issue #1391 packet B5).
 *
 * GCM discovery/selection then direct BLE via the Garmin Connect IQ Swift package
 * (`GarminCompanionBridge.swift` owns the native SDK). Device events are registered
 * before status/send/app operations. Inbound envelopes carry native device/app
 * provenance taken from the SDK callbacks — never dictionary claims — and the
 * production watch-app UUID ([GarminControlPreferences.watchAppUuid]) is verified on
 * every message before decoding.
 *
 * SELECTED-DEVICE CALLBACK ACCEPTANCE (signoff decision 3, binding): a GCM selection
 * callback is accepted only when it (a) arrives on [GARMIN_CALLBACK_URL_SCHEME],
 * (b) matches an outstanding PHONE-initiated selection request — single-use,
 * in-memory, expiring after [GARMIN_SELECTION_REQUEST_TTL_MS] — and (c) parses into
 * devices that are confirmed via SDK device-status registration. Unsolicited, expired
 * and repeated callbacks are rejected. `sourceApplication` is advisory logging only
 * and is never a required check.
 */
internal class IosGarminCompanionTransport(
    private val watchAppUuid: () -> String?,
    private val ownerDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val monotonicNowMs: () -> Long = { (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong() },
    private val registry: GarminCompanionBridgeRegistry = GarminCompanionBridgeRegistry,
    private val selectionTtlMs: Long = GARMIN_SELECTION_REQUEST_TTL_MS,
) : GarminCompanionTransport, GarminNativeBridgeEvents {

    private val lock = Any()
    private val scope = CoroutineScope(ownerDispatcher + SupervisorJob())

    private val _state = MutableStateFlow<GarminTransportState>(GarminTransportState.Uninitialized)
    override val state: StateFlow<GarminTransportState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<GarminDeviceSnapshot>>(emptyList())
    override val devices: StateFlow<List<GarminDeviceSnapshot>> = _devices.asStateFlow()

    // No replay, no queue: replay=0 so new subscribers see nothing from before they
    // arrived, and the single drop-oldest slot bounds delivery — a slow or absent
    // collector drops messages instead of accumulating a backlog.
    private val _inbound = MutableSharedFlow<GarminInboundEnvelope>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val inbound: SharedFlow<GarminInboundEnvelope> = _inbound.asSharedFlow()

    private var ownerGeneration: Long? = null
    private var eventsRegistered = false

    /** Native refs with device events registered (paired unregister bookkeeping). */
    private val deviceEventsRegistered = LinkedHashSet<String>()

    /** Native refs with app-message registration -> app UUID (paired unregister bookkeeping). */
    private val appEventsRegistered = LinkedHashMap<String, String>()

    private val deviceFacts = LinkedHashMap<String, GarminDeviceSnapshot>()

    /** In-flight send completions; late completions for old generations are dropped. */
    private val sendRequests = HashMap<Long, SendRequest>()

    private val appInstalledRequests = HashMap<Long, Pair<Long, String>>()

    private var selectionSession: SelectionSession? = null
    private val selectionStatusRequests = HashMap<Long, String>() // requestId -> deviceRef

    private var lastRequestId: Long = 0L

    private data class SendRequest(
        val generation: Long,
        val deviceRef: String,
        val onCompletion: (GarminSendStatus) -> Unit,
    )

    private data class SendPlan(val requestId: Long, val appUuid: String)

    private inner class SelectionSession(
        val generation: Long,
        val onComplete: (GarminDeviceSelectionResult) -> Unit,
        val expiresAtMonotonicMs: Long,
    ) {
        /** Single-use: set once a selection callback has been accepted. */
        var consumed: Boolean = false
        /** Every device ref parsed from the accepted callback. */
        val parsedRefs = LinkedHashSet<String>()
        /** Parsed device refs awaiting device-status confirmation. */
        val awaiting = LinkedHashSet<String>()
        val confirmed = LinkedHashMap<String, GarminDeviceSnapshot>()
        /** Authorized refs that predate this session; untouched when the session fails. */
        var preExistingRefs: Set<String> = emptySet()
    }

    // ---- lifecycle ----

    override fun attach(ownerGeneration: Long) {
        val bridge: GarminNativeBridge? = withPlatformLock(lock) {
            // Recomposition with the same generation must not register listeners twice.
            if (this.ownerGeneration == ownerGeneration && eventsRegistered) return
            teardownListenersLocked(failSession = true)
            this.ownerGeneration = ownerGeneration
            val registered = registry.bridge
            registry.registerEvents(this)
            eventsRegistered = true
            _state.value = GarminTransportState.Initializing
            registered
        }
        if (bridge == null) {
            _state.value = GarminTransportState.Error("bridge_unavailable")
            return
        }
        try {
            bridge.initializeSdk(GARMIN_CALLBACK_URL_SCHEME)
            _state.value = GarminTransportState.Ready
        } catch (e: Exception) {
            log.w(e) { "ConnectIQ initialize failed" }
            _state.value = GarminTransportState.Error("init_failed")
        }
    }

    override fun detach() {
        withPlatformLock(lock) {
            teardownListenersLocked(failSession = true)
            ownerGeneration = null
        }
    }

    override fun shutdown() {
        val bridge = withPlatformLock(lock) {
            teardownListenersLocked(failSession = true)
            ownerGeneration = null
            _state.value = GarminTransportState.ShutDown
            registry.bridge
        }
        try {
            bridge?.shutdownSdk()
        } catch (e: Exception) {
            log.w(e) { "ConnectIQ shutdown failed" }
        }
        scope.cancel()
    }

    // ---- outbound ----

    override fun send(deviceRef: String, message: Map<String, Any?>, onCompletion: (GarminSendStatus) -> Unit) {
        val plan: SendPlan = withPlatformLock(lock) {
            val generation = ownerGeneration
            if (generation == null || _state.value != GarminTransportState.Ready) {
                post { onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
                return
            }
            val known = deviceFacts[deviceRef]
            if (known == null) {
                post { onCompletion(GarminSendStatus.INVALID_TARGET) }
                return
            }
            if (known.watchAppInstalled == false) {
                post { onCompletion(GarminSendStatus.APP_NOT_INSTALLED) }
                return
            }
            val appUuid = watchAppUuid()
            if (appUuid == null) {
                post { onCompletion(GarminSendStatus.APP_NOT_INSTALLED) }
                return
            }
            // Device events before any status/send/app operation (interface contract).
            ensureDeviceRegisteredLocked(deviceRef)
            ensureAppRegisteredLocked(deviceRef, appUuid)
            val requestId = nextRequestIdLocked()
            sendRequests[requestId] = SendRequest(generation, deviceRef, onCompletion)
            SendPlan(requestId, appUuid)
        }
        val bridge = registry.bridge
        if (bridge == null) {
            withPlatformLock(lock) { sendRequests.remove(plan.requestId) }
            post { onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
            return
        }
        try {
            bridge.send(deviceRef, plan.appUuid, message, plan.requestId)
        } catch (e: Exception) {
            log.w(e) { "ConnectIQ sendMessage failed" }
            withPlatformLock(lock) { sendRequests.remove(plan.requestId) }
            post { onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
        }
    }

    override fun startDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit) {
        val session: SelectionSession = withPlatformLock(lock) {
            val generation = ownerGeneration
            if (generation == null || _state.value != GarminTransportState.Ready) {
                post { onComplete(GarminDeviceSelectionResult.Failed("not_ready")) }
                return
            }
            // One outstanding phone-initiated selection request at a time.
            selectionSession?.let { stale ->
                selectionSession = null
                post { stale.onComplete(GarminDeviceSelectionResult.Cancelled) }
            }
            SelectionSession(
                generation = generation,
                onComplete = onComplete,
                expiresAtMonotonicMs = monotonicNowMs() + selectionTtlMs,
            ).also { selectionSession = it }
        }
        val bridge = registry.bridge
        if (bridge == null) {
            completeSelection(session, GarminDeviceSelectionResult.Failed("bridge_unavailable"))
            return
        }
        try {
            bridge.showDeviceSelection()
        } catch (e: Exception) {
            log.w(e) { "showConnectIQDeviceSelection failed" }
            completeSelection(session, GarminDeviceSelectionResult.Failed("selection_launch_failed"))
        }
    }

    // ---- bridge callbacks (signoff decision 3 acceptance lives here) ----

    override fun onNativeDeviceSelectionParsed(
        devices: List<GarminBridgeDeviceInfo>,
        urlScheme: String,
        sourceApplication: String?,
    ) = post {
        // sourceApplication is ADVISORY LOGGING ONLY (nil cross-team on iOS 13+);
        // it is never a required check.
        log.i { "Garmin selection callback (sourceApplication=$sourceApplication, scheme=$urlScheme)" }
        val session: SelectionSession = withPlatformLock(lock) {
            val current = selectionSession
            if (current == null || current.consumed) {
                // Unsolicited or repeated callback: REJECTED.
                log.w { "Rejected Garmin selection callback: no outstanding single-use request" }
                return@post
            }
            if (current.generation != ownerGeneration) {
                log.w { "Rejected Garmin selection callback: stale owner generation" }
                completeSelectionLocked(current, GarminDeviceSelectionResult.Cancelled)
                return@post
            }
            if (!urlScheme.equals(GARMIN_CALLBACK_URL_SCHEME, ignoreCase = true)) {
                // Not on the dedicated Garmin scheme: REJECTED (OAuth scheme stays separate).
                log.w { "Rejected Garmin selection callback: wrong scheme $urlScheme" }
                return@post
            }
            if (monotonicNowMs() > current.expiresAtMonotonicMs) {
                // Expired callback: REJECTED; the outstanding request fails closed.
                log.w { "Rejected Garmin selection callback: request expired" }
                completeSelectionLocked(current, GarminDeviceSelectionResult.Failed("selection_expired"))
                return@post
            }
            // Accept: single-use is now consumed; confirm parsed devices via
            // SDK device-status registration before anything is trusted.
            current.consumed = true
            val parsed = devices
                .filter { it.deviceRef.isNotBlank() }
                .distinctBy { it.deviceRef }
                .take(MAX_SELECTED_DEVICES)
            if (parsed.isEmpty()) {
                completeSelectionLocked(current, GarminDeviceSelectionResult.Failed("no_devices"))
                return@post
            }
            current.preExistingRefs = deviceFacts.keys.toSet()
            current.awaiting.addAll(parsed.map { it.deviceRef })
            current.parsedRefs.addAll(parsed.map { it.deviceRef })
            parsed.forEach { device ->
                if (device.deviceRef !in deviceFacts) {
                    deviceFacts[device.deviceRef] = GarminDeviceSnapshot(
                        deviceRef = device.deviceRef,
                        name = device.name,
                        isConnected = false,
                        watchAppInstalled = null,
                    )
                }
                // Device-event registration BEFORE the status query (interface contract).
                ensureDeviceRegisteredLocked(device.deviceRef)
            }
            _devices.value = deviceFacts.values.toList()
            current
        }
        val bridge = registry.bridge ?: run {
            completeSelection(session, GarminDeviceSelectionResult.Failed("bridge_unavailable"))
            return@post
        }
        session.awaiting.toList().forEach { deviceRef ->
            val requestId = withPlatformLock(lock) {
                nextRequestIdLocked().also { selectionStatusRequests[it] = deviceRef }
            }
            try {
                bridge.queryDeviceStatus(deviceRef, requestId)
            } catch (e: Exception) {
                log.w(e) { "queryDeviceStatus failed for $deviceRef" }
                onNativeDeviceStatusResult(requestId, deviceRef, "invalid")
            }
        }
    }

    override fun onNativeDeviceStatusResult(requestId: Long, deviceRef: String, status: String) = post {
        val session: SelectionSession = withPlatformLock(lock) {
            selectionStatusRequests.remove(requestId) ?: return@post
            val current = selectionSession ?: return@post
            current.awaiting.remove(deviceRef)
            if (status == "invalid") {
                // SDK device-status registration did not confirm this device.
                unregisterDeviceLocked(deviceRef)
                deviceFacts.remove(deviceRef)
                _devices.value = deviceFacts.values.toList()
            } else {
                val previous = deviceFacts[deviceRef]
                if (previous != null) {
                    deviceFacts[deviceRef] = previous.copy(isConnected = status == "connected")
                    _devices.value = deviceFacts.values.toList()
                }
                current.confirmed[deviceRef] = deviceFacts[deviceRef]
                    ?: GarminDeviceSnapshot(deviceRef, deviceRef, status == "connected", null)
            }
            if (current.awaiting.isNotEmpty()) return@post
            current
        }
        // All parsed devices confirmed or rejected.
        val confirmed = withPlatformLock(lock) { session.confirmed.values.toList() }
        if (confirmed.isEmpty()) {
            completeSelection(session, GarminDeviceSelectionResult.Failed("no_confirmed_devices"))
            return@post
        }
        withPlatformLock(lock) {
            // Replace/cache only the latest authorized device set; clear old native references.
            replaceAuthorizedSetLocked(confirmed)
            val appUuid = watchAppUuid()
            if (appUuid != null) {
                ensureAppRegisteredLocked(confirmed.first().deviceRef, appUuid)
            }
        }
        completeSelection(session, GarminDeviceSelectionResult.Selected(confirmed.first()))
    }

    override fun onNativeSendResult(requestId: Long, status: String) = post {
        val request: SendRequest = withPlatformLock(lock) {
            sendRequests.remove(requestId) ?: return@post
        }
        // Late callbacks carrying an older owner generation are dropped.
        withPlatformLock(lock) {
            if (request.generation != ownerGeneration) return@post
        }
        request.onCompletion(mapSendStatus(status))
    }

    override fun onNativeAppInstalledResult(requestId: Long, installed: String) = post {
        withPlatformLock(lock) {
            val (generation, deviceRef) = appInstalledRequests.remove(requestId) ?: return@post
            if (generation != ownerGeneration) return@post
            val previous = deviceFacts[deviceRef] ?: return@post
            deviceFacts[deviceRef] = previous.copy(
                watchAppInstalled = when (installed) {
                    "installed" -> true
                    "not_installed" -> false
                    else -> null
                },
            )
            _devices.value = deviceFacts.values.toList()
        }
    }

    override fun onNativeDeviceStatusChanged(deviceRef: String, name: String, status: String) = post {
        withPlatformLock(lock) {
            if (ownerGeneration == null) return@post
            val previous = deviceFacts[deviceRef] ?: return@post
            if (status == "invalid") {
                // Losing authorization: clear native references for this device.
                unregisterDeviceLocked(deviceRef)
                deviceFacts.remove(deviceRef)
                _devices.value = deviceFacts.values.toList()
                return@post
            }
            deviceFacts[deviceRef] = previous.copy(
                name = name.ifEmpty { previous.name },
                isConnected = status == "connected",
            )
            _devices.value = deviceFacts.values.toList()
        }
    }

    override fun onNativeAppMessage(deviceRef: String, appUuid: String, message: Map<String, Any?>) = post {
        withPlatformLock(lock) {
            if (ownerGeneration == null) return@post
        }
        // Native device/app provenance is checked BEFORE decoding into an envelope.
        val expected = watchAppUuid()
        if (expected == null || !expected.equals(appUuid, ignoreCase = true)) {
            log.w { "Dropped CIQ message: app provenance mismatch ($appUuid)" }
            return@post
        }
        val decoded = decodeBoundedDictionary(message) ?: return@post
        _inbound.tryEmit(
            GarminInboundEnvelope(
                message = decoded,
                deviceRef = deviceRef,
                appUuid = appUuid,
                receivedAtMonotonicMs = monotonicNowMs(),
            ),
        )
    }

    override fun onNativeGcmRequired() = post {
        _state.value = GarminTransportState.ClientUnavailable
    }

    override fun onNativeSdkError(code: String) = post {
        _state.value = GarminTransportState.Error(code)
    }

    // ---- bookkeeping ----

    private fun ensureDeviceRegisteredLocked(deviceRef: String) {
        if (deviceRef in deviceEventsRegistered) return
        try {
            registry.bridge?.registerDeviceEvents(deviceRef)
            deviceEventsRegistered += deviceRef
        } catch (e: Exception) {
            log.w(e) { "registerForDeviceEvents failed for $deviceRef" }
        }
    }

    private fun ensureAppRegisteredLocked(deviceRef: String, appUuid: String) {
        if (appEventsRegistered[deviceRef] == appUuid) return
        appEventsRegistered.remove(deviceRef)?.let { previous ->
            runCatching { registry.bridge?.unregisterAppMessages(deviceRef, previous) }
        }
        try {
            registry.bridge?.registerAppMessages(deviceRef, appUuid)
            appEventsRegistered[deviceRef] = appUuid
        } catch (e: Exception) {
            log.w(e) { "registerForAppMessages failed for $deviceRef" }
        }
    }

    private fun unregisterDeviceLocked(deviceRef: String) {
        appEventsRegistered.remove(deviceRef)?.let { previous ->
            runCatching { registry.bridge?.unregisterAppMessages(deviceRef, previous) }
        }
        if (deviceEventsRegistered.remove(deviceRef)) {
            runCatching { registry.bridge?.unregisterDeviceEvents(deviceRef) }
        }
    }

    /** Paired unregister for every register performed; also drops the selection session. */
    private fun teardownListenersLocked(failSession: Boolean) {
        appEventsRegistered.clear()
        deviceEventsRegistered.clear()
        runCatching { registry.bridge?.unregisterAllDeviceEvents() }
        runCatching { registry.bridge?.unregisterAllAppMessages() }
        sendRequests.values.toList().forEach { request ->
            post { request.onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
        }
        sendRequests.clear()
        appInstalledRequests.clear()
        selectionStatusRequests.clear()
        if (failSession) {
            selectionSession?.let { session ->
                selectionSession = null
                post { session.onComplete(GarminDeviceSelectionResult.Cancelled) }
            }
        }
    }

    private fun replaceAuthorizedSetLocked(replacement: List<GarminDeviceSnapshot>) {
        val replacementRefs = replacement.mapTo(LinkedHashSet()) { it.deviceRef }
        (deviceFacts.keys - replacementRefs).forEach { dropped -> unregisterDeviceLocked(dropped) }
        val replaced = LinkedHashMap<String, GarminDeviceSnapshot>()
        replacement.forEach { snapshot ->
            replaced[snapshot.deviceRef] = snapshot.copy(
                watchAppInstalled = deviceFacts[snapshot.deviceRef]?.watchAppInstalled ?: snapshot.watchAppInstalled,
            )
        }
        deviceFacts.clear()
        deviceFacts.putAll(replaced)
        _devices.value = deviceFacts.values.toList()
    }

    private fun completeSelection(session: SelectionSession, result: GarminDeviceSelectionResult) {
        withPlatformLock(lock) { completeSelectionLocked(session, result) }
    }

    private fun completeSelectionLocked(session: SelectionSession, result: GarminDeviceSelectionResult) {
        if (selectionSession !== session) return
        selectionSession = null
        selectionStatusRequests.entries.removeAll { (_, deviceRef) -> deviceRef in session.parsedRefs }
        if (result !is GarminDeviceSelectionResult.Selected) {
            // Failed sessions clear the native references they added for confirmation;
            // refs that were authorized before the session stay untouched.
            (session.parsedRefs - session.preExistingRefs).forEach { deviceRef ->
                unregisterDeviceLocked(deviceRef)
                deviceFacts.remove(deviceRef)
            }
            _devices.value = deviceFacts.values.toList()
        }
        post { session.onComplete(result) }
    }

    private fun nextRequestIdLocked(): Long = ++lastRequestId

    private fun post(block: () -> Unit) {
        val context = kotlin.coroutines.EmptyCoroutineContext
        if (ownerDispatcher.isDispatchNeeded(context)) {
            ownerDispatcher.dispatch(context, Runnable { block() })
        } else {
            block()
        }
    }

    companion object {
        private const val MAX_SELECTED_DEVICES = 16

        /**
         * Decode one raw message dictionary into a bounded dictionary, or null when the
         * raw value is not a dictionary. Only primitive/short-string values survive. Runs
         * AFTER receive-status and provenance checks.
         */
        internal fun decodeBoundedDictionary(raw: Any?): Map<String, Any?>? {
            val map = raw as? Map<*, *> ?: return null
            val bounded = LinkedHashMap<String, Any?>()
            for ((key, value) in map) {
                if (bounded.size >= MAX_MESSAGE_ENTRIES) break
                val stringKey = key as? String ?: continue
                bounded[stringKey] = when (value) {
                    null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
                    is String -> value.take(MAX_MESSAGE_STRING_CHARS)
                    else -> continue
                }
            }
            return bounded
        }

        private const val MAX_MESSAGE_ENTRIES = 64
        private const val MAX_MESSAGE_STRING_CHARS = 4096
    }
}

private fun mapSendStatus(nativeStatus: String): GarminSendStatus = when (nativeStatus) {
    "success" -> GarminSendStatus.SUCCESS
    "failure_device_not_available" -> GarminSendStatus.NOT_CONNECTED
    "failure_app_not_found" -> GarminSendStatus.APP_NOT_INSTALLED
    // Received by the device even though no prompt was shown / app was already open.
    "failure_prompt_not_displayed", "failure_app_already_running" -> GarminSendStatus.SUCCESS
    else -> GarminSendStatus.TRANSPORT_ERROR
}

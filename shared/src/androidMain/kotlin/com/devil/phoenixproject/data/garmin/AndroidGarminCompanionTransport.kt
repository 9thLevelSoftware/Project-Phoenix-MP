package com.devil.phoenixproject.data.garmin

import android.content.Context
import android.os.SystemClock
import co.touchlab.kermit.Logger
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private val log = Logger.withTag("AndroidGarminTransport")

/** How the CIQ companion SDK reaches the watch (issue #1391). */
internal enum class GarminIqConnectType {
    /** Normal production path: Garmin Connect Mobile service relay. NEVER the trainer BLE scanner. */
    WIRELESS,

    /**
     * CIQ simulator tethering for emulator<->simulator evidence runs only.
     *
     * TETHERED round trip (unexecuted in CI; run manually):
     * 1. Start the Connect IQ simulator with the production watch app and a simulator device,
     *    then enable its monkey-do / comm harness.
     * 2. `adb forward tcp:7381 tcp:7381` from the host the simulator runs on.
     * 3. Launch the Android app in an emulator with
     *    `AndroidGarminCompanionTransport(context, ..., connectType = GarminIqConnectType.TETHERED, adbPort = 7381)`.
     * 4. Press watch buttons in the simulator; `adb logcat -s AndroidGarminTransport` shows
     *    inbound envelopes with native device/app provenance.
     */
    TETHERED,
}

// ---------------------------------------------------------------------------
// Fake-SDK seam. The transport talks to the CIQ companion app SDK only through
// [GarminCiqSdk]; unit tests substitute a fake. Types here are SDK-free so the
// seam is testable on the host JVM.
// ---------------------------------------------------------------------------

/** SDK-level device identity. [deviceRef] is the native identifier, never a dictionary claim. */
internal data class GarminCiqNativeDevice(val deviceRef: String, val name: String)

/** One raw inbound delivery exactly as the SDK handed it over, BEFORE any decoding. */
internal data class GarminCiqNativeInbound(
    val deviceRef: String,
    /** Native watch-app identity delivered by the SDK (IQApp.getApplicationId()). */
    val appUuid: String,
    /** Native receive status name (IQMessageStatus); only SUCCESS may decode. */
    val receiveStatus: String,
    /** Undecoded raw message objects (Monkey dictionaries after SDK conversion). */
    val rawMessages: List<Any?>,
)

internal interface GarminCiqSdkListener {
    fun onSdkReady()
    /** Bounded code: `gcm_not_installed`, `gcm_upgrade_needed`, `service_error`. */
    fun onInitializeError(code: String)
    fun onSdkShutDown()
}

internal interface GarminCiqSdkDeviceListener {
    fun onDeviceStatusChanged(deviceRef: String, name: String, connected: Boolean)
}

internal interface GarminCiqSdkAppListener {
    fun onMessageReceived(inbound: GarminCiqNativeInbound)
}

internal interface GarminCiqSdkSendListener {
    /** Native IQMessageStatus name. */
    fun onMessageStatus(status: String)
}

internal interface GarminCiqSdkAppInfoListener {
    /** [status] is `installed`, `not_installed` or `unknown` (native IQAppStatus). */
    fun onAppInfo(status: String)
    fun onAppNotInstalled()
}

internal interface GarminCiqSdk {
    /** Set/replace the init listener; underlying SDK initialization starts at most once. */
    fun initialize(listener: GarminCiqSdkListener)
    fun shutdown()
    fun listConnectedDevices(): List<GarminCiqNativeDevice>
    fun listKnownDevices(): List<GarminCiqNativeDevice>
    fun registerDeviceEvents(deviceRef: String, listener: GarminCiqSdkDeviceListener)
    fun unregisterDeviceEvents(deviceRef: String)
    fun registerAppEvents(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppListener)
    fun unregisterAppEvents(deviceRef: String, appUuid: String)
    fun queryAppInfo(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppInfoListener)
    fun send(deviceRef: String, appUuid: String, message: Map<String, Any?>, listener: GarminCiqSdkSendListener)
}

// ---------------------------------------------------------------------------
// Real facade over com.garmin.connectiq:ciq-companion-app-sdk (GCM service relay).
// ---------------------------------------------------------------------------

internal class RealGarminCiqSdk(
    private val context: Context,
    private val connectType: GarminIqConnectType,
    private val adbPort: Int,
) : GarminCiqSdk {

    private val devicesByRef = HashMap<String, IQDevice>()
    private val appsByUuid = HashMap<String, IQApp>()
    private var initListener: GarminCiqSdkListener? = null
    private var initStarted = false
    private var ready = false

    private val sdkListener = object : ConnectIQ.ConnectIQListener {
        override fun onSdkReady() {
            ready = true
            initListener?.onSdkReady()
        }

        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
            ready = false
            initStarted = false
            initListener?.onInitializeError(
                when (status) {
                    ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> "gcm_not_installed"
                    ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> "gcm_upgrade_needed"
                    ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> "service_error"
                },
            )
        }

        override fun onSdkShutDown() {
            ready = false
            initStarted = false
            initListener?.onSdkShutDown()
        }
    }

    private val iq: ConnectIQ by lazy {
        val instance = ConnectIQ.getInstance(
            context,
            when (connectType) {
                GarminIqConnectType.WIRELESS -> ConnectIQ.IQConnectType.WIRELESS
                GarminIqConnectType.TETHERED -> ConnectIQ.IQConnectType.TETHERED
            },
        )
        if (connectType == GarminIqConnectType.TETHERED) {
            instance.setAdbPort(adbPort)
        }
        instance
    }

    override fun initialize(listener: GarminCiqSdkListener) {
        initListener = listener
        if (ready) {
            listener.onSdkReady()
            return
        }
        if (!initStarted) {
            initStarted = true
            // autoUI = false: the transport maps GCM-missing/upgrade errors to
            // GarminTransportState.ClientUnavailable and the UI renders its own setup copy.
            iq.initialize(context, false, sdkListener)
        }
    }

    override fun shutdown() {
        runCatching { iq.shutdown(context) }
            .onFailure { log.w(it) { "CIQ SDK shutdown failed" } }
    }

    override fun listConnectedDevices(): List<GarminCiqNativeDevice> =
        iq.connectedDevices.orEmpty().map { it.native() }

    override fun listKnownDevices(): List<GarminCiqNativeDevice> =
        iq.knownDevices.orEmpty().map { it.native() }

    override fun registerDeviceEvents(deviceRef: String, listener: GarminCiqSdkDeviceListener) {
        val device = deviceFor(deviceRef)
        iq.registerForDeviceEvents(device) { changed, status ->
            listener.onDeviceStatusChanged(
                deviceRef = changed.deviceIdentifier.toString(),
                name = changed.friendlyName.orEmpty(),
                connected = status == IQDevice.IQDeviceStatus.CONNECTED,
            )
        }
    }

    override fun unregisterDeviceEvents(deviceRef: String) {
        devicesByRef[deviceRef]?.let { iq.unregisterForDeviceEvents(it) }
    }

    override fun registerAppEvents(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppListener) {
        val device = deviceFor(deviceRef)
        val app = appFor(appUuid)
        iq.registerForAppEvents(device, app) { changedDevice, changedApp, messages, status ->
            listener.onMessageReceived(
                GarminCiqNativeInbound(
                    deviceRef = changedDevice.deviceIdentifier.toString(),
                    appUuid = changedApp.applicationId,
                    receiveStatus = status.name,
                    rawMessages = messages.orEmpty(),
                ),
            )
        }
    }

    override fun unregisterAppEvents(deviceRef: String, appUuid: String) {
        val device = devicesByRef[deviceRef] ?: return
        appsByUuid[appUuid]?.let { iq.unregisterForApplicationEvents(device, it) }
    }

    override fun queryAppInfo(deviceRef: String, appUuid: String, listener: GarminCiqSdkAppInfoListener) {
        val device = deviceFor(deviceRef)
        iq.getApplicationInfo(
            appUuid,
            device,
            object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    when (app.status) {
                        IQApp.IQAppStatus.INSTALLED -> listener.onAppInfo("installed")
                        IQApp.IQAppStatus.NOT_INSTALLED, IQApp.IQAppStatus.NOT_SUPPORTED ->
                            listener.onAppInfo("not_installed")
                        else -> listener.onAppInfo("unknown")
                    }
                }

                override fun onApplicationNotInstalled(appId: String) {
                    listener.onAppNotInstalled()
                }
            },
        )
    }

    override fun send(
        deviceRef: String,
        appUuid: String,
        message: Map<String, Any?>,
        listener: GarminCiqSdkSendListener,
    ) {
        val device = deviceFor(deviceRef)
        val app = appFor(appUuid)
        iq.sendMessage(device, app, message) { _, _, status -> listener.onMessageStatus(status.name) }
    }

    private fun deviceFor(deviceRef: String): IQDevice {
        devicesByRef[deviceRef]?.let { return it }
        val identifier = deviceRef.toLongOrNull()
            ?: throw IllegalArgumentException("Not a native device reference: $deviceRef")
        return IQDevice(identifier, "Garmin").also { devicesByRef[deviceRef] = it }
    }

    private fun appFor(appUuid: String): IQApp =
        appsByUuid.getOrPut(appUuid) { IQApp(appUuid) }

    private fun IQDevice.native(): GarminCiqNativeDevice {
        val ref = deviceIdentifier.toString()
        devicesByRef[ref] = this
        return GarminCiqNativeDevice(deviceRef = ref, name = friendlyName.orEmpty().ifEmpty { "Garmin" })
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/**
 * Android implementation of [GarminCompanionTransport] (issue #1391 packet B4).
 *
 * Uses the Connect IQ companion app SDK (`com.garmin.connectiq:ciq-companion-app-sdk`,
 * Garmin Connect Mobile service relay — never the trainer BLE scanner):
 *
 *  - WIRELESS SDK init; no query before `onSdkReady()`.
 *  - `onInitializeError` GCM_NOT_INSTALLED/GCM_UPGRADE_NEEDED map to
 *    [GarminTransportState.ClientUnavailable]; SERVICE_ERROR maps to a bounded error code.
 *  - `registerForDeviceEvents`/`onDeviceStatusChanged` and `registerForAppEvents`/
 *    `onMessageReceived` are paired with matching unregisters; `shutdown()` also
 *    shuts the SDK down.
 *  - Receive status AND native device/app provenance (production watch-app UUID from
 *    [GarminControlPreferences.watchAppUuid] on every `onMessageReceived`) are checked
 *    BEFORE anything is decoded into a [GarminInboundEnvelope].
 *  - Every SDK callback is marshalled onto [ownerDispatcher]; callbacks stamped with an
 *    older owner generation are dropped; recomposition (same `ownerGeneration` again)
 *    never registers listeners twice.
 *
 * Device selection is the deterministic known/connected-device picker step: the latest
 * GCM-paired set (connected first) is cached as the SDK-authorized device set. Exactly one
 * connected watch selects itself; ambiguity fails with a bounded code and the cached set
 * stays available via [devices] for the selection UI.
 */
internal class AndroidGarminCompanionTransport(
    context: Context,
    private val watchAppUuid: () -> String?,
    private val ownerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    connectType: GarminIqConnectType = GarminIqConnectType.WIRELESS,
    adbPort: Int = DEFAULT_TETHERED_ADB_PORT,
    sdkOverride: GarminCiqSdk? = null,
    private val monotonicNowMs: () -> Long = { SystemClock.elapsedRealtime() },
) : GarminCompanionTransport {

    private val appContext: Context = if (sdkOverride != null) context else context.applicationContext
    private val sdk: GarminCiqSdk = sdkOverride ?: RealGarminCiqSdk(appContext, connectType, adbPort)

    private val lock = Any()

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
    private var sdkInitStarted = false
    private var sdkReady = false
    private var selectedRef: String? = null
    private var deferredSelection: ((GarminDeviceSelectionResult) -> Unit)? = null

    /** Native refs with device events registered (paired unregister bookkeeping). */
    private val deviceEventsRegistered = LinkedHashSet<String>()

    /** Native refs with app events registered -> app UUID (paired unregister bookkeeping). */
    private val appEventsRegistered = LinkedHashMap<String, String>()

    private val deviceFacts = LinkedHashMap<String, GarminDeviceSnapshot>()

    // ---- lifecycle ----

    override fun attach(ownerGeneration: Long) {
        val firstInit: Boolean
        synchronized(lock) {
            // Recomposition with the same generation must not register listeners twice.
            if (this.ownerGeneration == ownerGeneration && sdkInitStarted) return
            teardownListenersLocked()
            failDeferredLocked(GarminDeviceSelectionResult.Cancelled)
            this.ownerGeneration = ownerGeneration
            firstInit = !sdkInitStarted
            if (firstInit) sdkInitStarted = true
            _state.value = GarminTransportState.Initializing
            try {
                sdk.initialize(SdkInitListener(ownerGeneration))
            } catch (e: Exception) {
                log.w(e) { "CIQ SDK initialize failed" }
                _state.value = GarminTransportState.Error("init_failed")
            }
            if (!firstInit && sdkReady) {
                // SDK already on; re-register this generation's listeners now.
                _state.value = GarminTransportState.Ready
                registerForCachedDevicesLocked(ownerGeneration)
            }
        }
    }

    override fun detach() {
        synchronized(lock) {
            teardownListenersLocked()
            failDeferredLocked(GarminDeviceSelectionResult.Cancelled)
            ownerGeneration = null
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            teardownListenersLocked()
            failDeferredLocked(GarminDeviceSelectionResult.Cancelled)
            ownerGeneration = null
            sdkReady = false
            _state.value = GarminTransportState.ShutDown
            try {
                sdk.shutdown()
            } catch (e: Exception) {
                log.w(e) { "CIQ SDK shutdown failed" }
            }
        }
    }

    // ---- outbound ----

    override fun send(deviceRef: String, message: Map<String, Any?>, onCompletion: (GarminSendStatus) -> Unit) {
        synchronized(lock) {
            val generation = ownerGeneration
            if (generation == null || !sdkReady) {
                post { onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
                return
            }
            val known = deviceFacts[deviceRef]
            if (known == null && deviceRef !in deviceEventsRegistered) {
                post { onCompletion(GarminSendStatus.INVALID_TARGET) }
                return
            }
            if (known?.watchAppInstalled == false) {
                post { onCompletion(GarminSendStatus.APP_NOT_INSTALLED) }
                return
            }
            val appUuid = watchAppUuid()
            if (appUuid == null) {
                post { onCompletion(GarminSendStatus.APP_NOT_INSTALLED) }
                return
            }
            // Device events before any send/app operation (interface contract).
            ensureDeviceRegisteredLocked(known?.name.orEmpty(), deviceRef, generation)
            ensureAppRegisteredLocked(deviceRef, generation)
            try {
                sdk.send(deviceRef, appUuid, message, object : GarminCiqSdkSendListener {
                    override fun onMessageStatus(status: String) = post {
                        // Late callback for an older owner generation is dropped.
                        if (generation != ownerGeneration) return@post
                        onCompletion(mapSendStatus(status))
                    }
                })
            } catch (e: Exception) {
                log.w(e) { "CIQ sendMessage failed" }
                post { onCompletion(GarminSendStatus.TRANSPORT_ERROR) }
            }
        }
    }

    override fun startDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit) {
        val ready: Boolean
        synchronized(lock) {
            if (ownerGeneration == null) {
                post { onComplete(GarminDeviceSelectionResult.Failed("not_attached")) }
                return
            }
            if (!sdkReady) {
                // Wait for onSdkReady() before any query.
                failDeferredLocked(GarminDeviceSelectionResult.Cancelled)
                deferredSelection = onComplete
                return
            }
            ready = true
        }
        if (ready) runDeviceSelection(onComplete)
    }

    private fun runDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit) {
        val generation: Long
        val connected: List<GarminCiqNativeDevice>
        val merged: List<GarminCiqNativeDevice>
        synchronized(lock) {
            generation = ownerGeneration ?: run {
                post { onComplete(GarminDeviceSelectionResult.Failed("not_attached")) }
                return
            }
            try {
                connected = sdk.listConnectedDevices()
                // Latest SDK-authorized device set: connected watches first, then known.
                merged = (connected + sdk.listKnownDevices()).distinctBy { it.deviceRef }
            } catch (e: Exception) {
                log.w(e) { "CIQ device query failed" }
                post { onComplete(GarminDeviceSelectionResult.Failed("query_failed")) }
                return
            }
            replaceAuthorizedSetLocked(merged, connected.mapTo(LinkedHashSet()) { it.deviceRef })
            registerForCachedDevicesLocked(generation)
        }

        // App-install facts arrive asynchronously and update the cached snapshots.
        val appUuid = watchAppUuid()
        if (appUuid != null) {
            merged.forEach { device -> queryAppInstalled(device.deviceRef, appUuid) }
        }

        val result: GarminDeviceSelectionResult = when {
            merged.isEmpty() -> GarminDeviceSelectionResult.Failed("no_devices")
            connected.size == 1 -> GarminDeviceSelectionResult.Selected(
                deviceFactsSnapshot(connected.first().deviceRef) ?: GarminDeviceSnapshot(
                    deviceRef = connected.first().deviceRef,
                    name = connected.first().name,
                    isConnected = true,
                    watchAppInstalled = null,
                ),
            )
            connected.size > 1 -> GarminDeviceSelectionResult.Failed("multiple_connected")
            merged.size == 1 -> GarminDeviceSelectionResult.Selected(
                deviceFactsSnapshot(merged.first().deviceRef) ?: GarminDeviceSnapshot(
                    deviceRef = merged.first().deviceRef,
                    name = merged.first().name,
                    isConnected = false,
                    watchAppInstalled = null,
                ),
            )
            else -> GarminDeviceSelectionResult.Failed("multiple_known")
        }
        if (result is GarminDeviceSelectionResult.Selected) {
            synchronized(lock) {
                selectedRef = result.device.deviceRef
                ensureAppRegisteredLocked(result.device.deviceRef, generation)
            }
        }
        post { onComplete(result) }
    }

    // ---- inbound ----

    private fun onNativeInbound(inbound: GarminCiqNativeInbound, generation: Long) = post {
        // Late callbacks carrying an older owner generation are dropped.
        if (generation != ownerGeneration) return@post
        // Check receive status first.
        if (inbound.receiveStatus != "SUCCESS") {
            log.i { "Dropped CIQ message: receive status ${inbound.receiveStatus}" }
            return@post
        }
        // Native device/app provenance, checked BEFORE any decoding. The production
        // watch-app UUID must match the configured one on EVERY onMessageReceived.
        val expected = watchAppUuid()
        if (expected == null || !expected.equals(inbound.appUuid, ignoreCase = true)) {
            log.w { "Dropped CIQ message: app provenance mismatch (${inbound.appUuid})" }
            return@post
        }
        val receivedAt = monotonicNowMs()
        for (raw in inbound.rawMessages) {
            val decoded = decodeBoundedDictionary(raw) ?: continue
            _inbound.tryEmit(
                GarminInboundEnvelope(
                    message = decoded,
                    deviceRef = inbound.deviceRef,
                    appUuid = inbound.appUuid,
                    receivedAtMonotonicMs = receivedAt,
                ),
            )
        }
    }

    // ---- SDK listener plumbing (every callback is owner-generation stamped) ----

    private inner class SdkInitListener(private val generation: Long) : GarminCiqSdkListener {
        override fun onSdkReady() = post {
            if (generation != ownerGeneration) return@post
            synchronized(lock) {
                sdkReady = true
                _state.value = GarminTransportState.Ready
                registerForCachedDevicesLocked(generation)
                val pending = deferredSelection
                deferredSelection = null
                if (pending != null) {
                    // Run outside the lock; selection queries the SDK.
                    post { runDeviceSelection(pending) }
                }
            }
        }

        override fun onInitializeError(code: String) = post {
            if (generation != ownerGeneration) return@post
            synchronized(lock) {
                sdkReady = false
                _state.value = when (code) {
                    "gcm_not_installed", "gcm_upgrade_needed" -> GarminTransportState.ClientUnavailable
                    else -> GarminTransportState.Error(code)
                }
                failDeferredLocked(GarminDeviceSelectionResult.Failed(code))
            }
        }

        override fun onSdkShutDown() = post {
            synchronized(lock) {
                sdkReady = false
                teardownListenersLocked()
                _state.value = GarminTransportState.ShutDown
            }
        }
    }

    private inner class DeviceListener(private val generation: Long) : GarminCiqSdkDeviceListener {
        override fun onDeviceStatusChanged(deviceRef: String, name: String, connected: Boolean) = post {
            if (generation != ownerGeneration) return@post
            synchronized(lock) {
                val previous = deviceFacts[deviceRef] ?: return@synchronized
                deviceFacts[deviceRef] = previous.copy(
                    name = name.ifEmpty { previous.name },
                    isConnected = connected,
                )
                _devices.value = deviceFacts.values.toList()
            }
        }
    }

    private inner class AppListener(private val generation: Long) : GarminCiqSdkAppListener {
        override fun onMessageReceived(inbound: GarminCiqNativeInbound) = onNativeInbound(inbound, generation)
    }

    private fun queryAppInstalled(deviceRef: String, appUuid: String) {
        try {
            sdk.queryAppInfo(deviceRef, appUuid, object : GarminCiqSdkAppInfoListener {
                override fun onAppInfo(status: String) = post {
                    updateWatchAppInstalled(deviceRef, when (status) {
                        "installed" -> true
                        "not_installed" -> false
                        else -> null
                    })
                }

                override fun onAppNotInstalled() = post {
                    updateWatchAppInstalled(deviceRef, false)
                }
            })
        } catch (e: Exception) {
            log.w(e) { "CIQ application info query failed" }
        }
    }

    private fun updateWatchAppInstalled(deviceRef: String, installed: Boolean?) {
        synchronized(lock) {
            val previous = deviceFacts[deviceRef] ?: return
            deviceFacts[deviceRef] = previous.copy(watchAppInstalled = installed)
            _devices.value = deviceFacts.values.toList()
        }
    }

    // ---- bookkeeping ----

    private fun registerForCachedDevicesLocked(generation: Long) {
        // Device-event registration happens before status/send/app operations.
        deviceFacts.values.forEach { snapshot ->
            ensureDeviceRegisteredLocked(snapshot.name, snapshot.deviceRef, generation)
        }
        selectedRef?.let { ensureAppRegisteredLocked(it, generation) }
    }

    private fun ensureDeviceRegisteredLocked(name: String, deviceRef: String, generation: Long) {
        if (deviceRef in deviceEventsRegistered) return
        try {
            sdk.registerDeviceEvents(deviceRef, DeviceListener(generation))
            deviceEventsRegistered += deviceRef
        } catch (e: Exception) {
            log.w(e) { "registerForDeviceEvents failed for $deviceRef" }
        }
    }

    private fun ensureAppRegisteredLocked(deviceRef: String, generation: Long) {
        val appUuid = watchAppUuid() ?: return
        if (appEventsRegistered[deviceRef] == appUuid) return
        appEventsRegistered.remove(deviceRef)?.let { previous ->
            runCatching { sdk.unregisterAppEvents(deviceRef, previous) }
        }
        try {
            sdk.registerAppEvents(deviceRef, appUuid, AppListener(generation))
            appEventsRegistered[deviceRef] = appUuid
        } catch (e: Exception) {
            log.w(e) { "registerForAppEvents failed for $deviceRef" }
        }
    }

    /** Paired unregister for every register performed by this generation. */
    private fun teardownListenersLocked() {
        appEventsRegistered.forEach { (deviceRef, appUuid) ->
            runCatching { sdk.unregisterAppEvents(deviceRef, appUuid) }
        }
        appEventsRegistered.clear()
        deviceEventsRegistered.forEach { deviceRef ->
            runCatching { sdk.unregisterDeviceEvents(deviceRef) }
        }
        deviceEventsRegistered.clear()
    }

    /** Replace the cached authorized device set; clear native references for dropped refs. */
    private fun replaceAuthorizedSetLocked(merged: List<GarminCiqNativeDevice>, connectedRefs: Set<String>) {
        val mergedRefs = merged.mapTo(LinkedHashSet()) { it.deviceRef }
        (deviceFacts.keys - mergedRefs).forEach { dropped ->
            if (dropped in appEventsRegistered) {
                appEventsRegistered.remove(dropped)?.let { runCatching { sdk.unregisterAppEvents(dropped, it) } }
            }
            if (dropped in deviceEventsRegistered) {
                runCatching { sdk.unregisterDeviceEvents(dropped) }
                deviceEventsRegistered.remove(dropped)
            }
        }
        val replaced = LinkedHashMap<String, GarminDeviceSnapshot>()
        merged.forEach { device ->
            replaced[device.deviceRef] = GarminDeviceSnapshot(
                deviceRef = device.deviceRef,
                name = device.name,
                isConnected = device.deviceRef in connectedRefs,
                watchAppInstalled = deviceFacts[device.deviceRef]?.watchAppInstalled,
            )
        }
        deviceFacts.clear()
        deviceFacts.putAll(replaced)
        if (selectedRef !in mergedRefs) selectedRef = null
        _devices.value = deviceFacts.values.toList()
    }

    private fun deviceFactsSnapshot(deviceRef: String): GarminDeviceSnapshot? = synchronized(lock) {
        deviceFacts[deviceRef]
    }

    private fun failDeferredLocked(result: GarminDeviceSelectionResult) {
        val pending = deferredSelection ?: return
        deferredSelection = null
        post { pending(result) }
    }

    private fun post(block: () -> Unit) {
        val context = kotlin.coroutines.EmptyCoroutineContext
        if (ownerDispatcher.isDispatchNeeded(context)) {
            ownerDispatcher.dispatch(context, Runnable { block() })
        } else {
            block()
        }
    }

    companion object {
        const val DEFAULT_TETHERED_ADB_PORT = 7381

        /**
         * Decode one raw SDK message object into a bounded dictionary, or null when the
         * object is not a dictionary. Only primitive/short-string values survive; keys must
         * be strings. This runs AFTER receive-status and provenance checks.
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
    "SUCCESS" -> GarminSendStatus.SUCCESS
    "FAILURE_INVALID_DEVICE" -> GarminSendStatus.INVALID_TARGET
    "FAILURE_DEVICE_NOT_CONNECTED" -> GarminSendStatus.NOT_CONNECTED
    else -> GarminSendStatus.TRANSPORT_ERROR
}

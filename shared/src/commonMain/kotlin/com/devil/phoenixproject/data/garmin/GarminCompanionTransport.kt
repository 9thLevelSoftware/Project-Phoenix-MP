package com.devil.phoenixproject.data.garmin

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform transport seam for the Garmin Connect IQ companion messaging (issue #1391 Phase 1).
 *
 * The transport owns all native SDK interaction (Garmin Connect IQ Mobile SDK on Android via GCM
 * service relay; Garmin Swift package + `-ObjC` bridge on iOS). It delivers inbound messages as
 * bounded dictionaries together with NATIVE provenance — the SDK-delivered device identity and the
 * production watch-app UUID — which accompany the decoded envelope and are never dictionary claims.
 *
 * FROZEN CONTRACT — packet B2 builds the controller against this interface; packets B4/B5/B6
 * implement it. Additive members only, never renames, without coordinated scope change.
 */

/** Native provenance delivered by the platform SDK alongside a decoded inbound message. */
data class GarminInboundEnvelope(
    /** Raw message dictionary (Monkey C Dictionary mapped to string keys / bounded values). */
    val message: Map<String, Any?>,
    /** SDK-delivered native device identity; never a dictionary claim. */
    val deviceRef: String,
    /** Native watch-app identity delivered by the SDK (production app UUID must match configuration). */
    val appUuid: String,
    /** Phone monotonic receive timestamp (ms). */
    val receivedAtMonotonicMs: Long,
)

sealed class GarminTransportState {
    /** SDK not started. */
    data object Uninitialized : GarminTransportState()

    /** SDK starting; queries are not allowed yet. */
    data object Initializing : GarminTransportState()

    /** `onSdkReady` observed; queries allowed. */
    data object Ready : GarminTransportState()

    /** Garmin Connect Mobile missing or requires upgrade — surface actionable setup copy. */
    data object ClientUnavailable : GarminTransportState()

    /** SDK reported shutdown. */
    data object ShutDown : GarminTransportState()

    /** Initialization/runtime error; [code] is a bounded internal code, never raw native text. */
    data class Error(val code: String) : GarminTransportState()
}

data class GarminDeviceSnapshot(
    val deviceRef: String,
    /** Display name for picker/copy; minimal, non-sensitive. */
    val name: String,
    val isConnected: Boolean,
    /** Whether the production watch app is installed on the device; null = not yet known. */
    val watchAppInstalled: Boolean?,
)

enum class GarminSendStatus {
    SUCCESS,
    NOT_CONNECTED,
    APP_NOT_INSTALLED,
    TRANSPORT_ERROR,
    INVALID_TARGET,
}

sealed class GarminDeviceSelectionResult {
    data class Selected(val device: GarminDeviceSnapshot) : GarminDeviceSelectionResult()
    data object Cancelled : GarminDeviceSelectionResult()
    data class Failed(val code: String) : GarminDeviceSelectionResult()
}

interface GarminCompanionTransport {
    /** Connection/capability state of the SDK + Garmin Connect Mobile client. */
    val state: StateFlow<GarminTransportState>

    /** Latest SDK-authorized device set. Implementations replace/cache only the latest authorized set. */
    val devices: StateFlow<List<GarminDeviceSnapshot>>

    /** Inbound envelopes carrying native device/app provenance. No replay, no queue. */
    val inbound: SharedFlow<GarminInboundEnvelope>

    /**
     * Start SDK initialization and register listeners exactly once for the given owner generation.
     * Late native callbacks carrying an older owner generation must be dropped.
     * Device-event registration happens before status/send/app operations.
     */
    fun attach(ownerGeneration: Long)

    /** Unregister all listeners (paired with every register) without tearing down the SDK. */
    fun detach()

    /** Full teardown including SDK shutdown; called on owner disposal/logout. */
    fun shutdown()

    /** Send one outbound dictionary to the watch app on [deviceRef]; [onCompletion] is invoked on the owner dispatcher. */
    fun send(deviceRef: String, message: Map<String, Any?>, onCompletion: (GarminSendStatus) -> Unit)

    /**
     * Platform device selection/discovery flow (Android: known/connected-device picker;
     * iOS: GCM discovery/selection with the solicited single-use selection callback).
     * Unsolicited/expired/repeated callbacks must be rejected inside the implementation.
     */
    fun startDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit)
}

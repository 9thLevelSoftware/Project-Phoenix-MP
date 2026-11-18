package com.devil.phoenixproject.data.garmin

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * tvOS implementation of [GarminCompanionTransport] (issue #1391 packet B6): a clean
 * no-op. Garmin watch button controls are phone-only; tvOS product functionality stays
 * out. No SDK imports, no listeners, no inbound emissions — state stays
 * [GarminTransportState.Uninitialized], the device set stays empty, sends fail with
 * [GarminSendStatus.INVALID_TARGET], and selection completes [GarminDeviceSelectionResult.Cancelled].
 */
internal class NoOpGarminCompanionTransport : GarminCompanionTransport {

    private val _state = MutableStateFlow<GarminTransportState>(GarminTransportState.Uninitialized)
    override val state: StateFlow<GarminTransportState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<GarminDeviceSnapshot>>(emptyList())
    override val devices: StateFlow<List<GarminDeviceSnapshot>> = _devices.asStateFlow()

    // Never emits: tvOS has no Garmin message source. replay=0 and a single
    // drop-oldest slot keep the no-replay/no-queue contract if that ever changes.
    private val _inbound = MutableSharedFlow<GarminInboundEnvelope>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val inbound: SharedFlow<GarminInboundEnvelope> = _inbound.asSharedFlow()

    override fun attach(ownerGeneration: Long) {
        // No SDK on tvOS: nothing to initialize or register.
    }

    override fun detach() {
        // No listeners were registered.
    }

    override fun shutdown() {
        // Nothing to tear down.
    }

    override fun send(deviceRef: String, message: Map<String, Any?>, onCompletion: (GarminSendStatus) -> Unit) {
        onCompletion(GarminSendStatus.INVALID_TARGET)
    }

    override fun startDeviceSelection(onComplete: (GarminDeviceSelectionResult) -> Unit) {
        onComplete(GarminDeviceSelectionResult.Cancelled)
    }
}

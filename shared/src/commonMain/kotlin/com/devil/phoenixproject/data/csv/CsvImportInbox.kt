package com.devil.phoenixproject.data.csv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** The terminal result of reading one shared routine CSV (#1242). Only bytes or an error, never a URI. */
sealed interface CsvImportDeliveryResult {
    /** UTF-8 CSV text, already bounded to `RoutineCsvFormat.MAX_BYTES`. */
    data class Read(val content: String) : CsvImportDeliveryResult

    /** The source was larger than the byte bound; nothing was kept. */
    data object TooLarge : CsvImportDeliveryResult

    /** The source could not be read (revoked grant, refused URI, missing file). */
    data object Unreadable : CsvImportDeliveryResult
}

/** One read result of one shared or opened file. Identity is per delivery, never per content. */
data class CsvImportOffer(val deliveryId: String, val result: CsvImportDeliveryResult)

/**
 * Process-wide handoff between the Android intake activity and the in-app import host (#1242).
 *
 * One delivery owns the inbox from intake until the host finishes with it:
 * [tryBegin] (intake accepts a file) → [deliver] (bytes read) → [claim] (host takes it) →
 * [finish] (dialog closed or import done). A file shared while another is anywhere in that
 * chain is rejected with [reportBusy] and never touches the delivery in flight. Every step is
 * a compare-and-set, so a delivery is claimed at most once, including after the host's
 * activity is recreated. iOS has no intake, so nothing is ever offered there.
 */
class CsvImportInbox {
    sealed interface State {
        data object Idle : State

        data class Reading(val deliveryId: String) : State

        data class Ready(val offer: CsvImportOffer) : State

        data class Handling(val deliveryId: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _busyRejections = MutableStateFlow(0)

    /** Files rejected because another import was in flight, not yet reported to the user. */
    val busyRejections: StateFlow<Int> = _busyRejections.asStateFlow()

    /** Takes the inbox for a new delivery. False when another delivery is in flight. */
    fun tryBegin(deliveryId: String): Boolean = _state.compareAndSet(State.Idle, State.Reading(deliveryId))

    /** Publishes the read result of the delivery that [tryBegin] accepted. */
    fun deliver(deliveryId: String, result: CsvImportDeliveryResult): Boolean =
        _state.compareAndSet(State.Reading(deliveryId), State.Ready(CsvImportOffer(deliveryId, result)))

    /** Releases a delivery whose read never finished (the intake activity went away). */
    fun abandon(deliveryId: String) {
        _state.compareAndSet(State.Reading(deliveryId), State.Idle)
    }

    /** The host takes a ready delivery. Only one caller wins; a replay of the same offer loses. */
    fun claim(offer: CsvImportOffer): Boolean = _state.compareAndSet(State.Ready(offer), State.Handling(offer.deliveryId))

    /** The host is done with [deliveryId]; the next shared file may begin. */
    fun finish(deliveryId: String) {
        _state.compareAndSet(State.Handling(deliveryId), State.Idle)
    }

    fun reportBusy() {
        _busyRejections.update { it + 1 }
    }

    /** Returns and clears the pending busy rejections. */
    fun takeBusyRejections(): Int = _busyRejections.getAndUpdate { 0 }
}

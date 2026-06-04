package com.devil.phoenixproject.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleCriticalEventType {
    REP,
    DELOAD,
    ROM_VIOLATION,
    RECONNECTION_REQUEST,
}

data class BleEventDeliverySnapshot(
    val repEventsDropped: Long = 0,
    val deloadEventsDropped: Long = 0,
    val romViolationEventsDropped: Long = 0,
    val reconnectionRequestsDropped: Long = 0,
)

internal class BleEventDeliveryTracker {
    private val _snapshot = MutableStateFlow(BleEventDeliverySnapshot())
    val snapshot: StateFlow<BleEventDeliverySnapshot> = _snapshot.asStateFlow()

    fun recordDropped(type: BleCriticalEventType) {
        _snapshot.value = when (type) {
            BleCriticalEventType.REP -> _snapshot.value.copy(repEventsDropped = _snapshot.value.repEventsDropped + 1)

            BleCriticalEventType.DELOAD -> _snapshot.value.copy(deloadEventsDropped = _snapshot.value.deloadEventsDropped + 1)

            BleCriticalEventType.ROM_VIOLATION ->
                _snapshot.value.copy(romViolationEventsDropped = _snapshot.value.romViolationEventsDropped + 1)

            BleCriticalEventType.RECONNECTION_REQUEST ->
                _snapshot.value.copy(reconnectionRequestsDropped = _snapshot.value.reconnectionRequestsDropped + 1)
        }
    }
}

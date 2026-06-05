package com.devil.phoenixproject.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
        _snapshot.update { current ->
            when (type) {
                BleCriticalEventType.REP -> current.copy(repEventsDropped = current.repEventsDropped + 1)

                BleCriticalEventType.DELOAD -> current.copy(deloadEventsDropped = current.deloadEventsDropped + 1)

                BleCriticalEventType.ROM_VIOLATION ->
                    current.copy(romViolationEventsDropped = current.romViolationEventsDropped + 1)

                BleCriticalEventType.RECONNECTION_REQUEST ->
                    current.copy(reconnectionRequestsDropped = current.reconnectionRequestsDropped + 1)
            }
        }
    }
}

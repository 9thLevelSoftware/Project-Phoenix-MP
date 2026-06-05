package com.devil.phoenixproject.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class BleEventDeliveryTrackerTest {

    @Test
    fun `recordDropped preserves concurrent increments`() = runTest {
        val tracker = BleEventDeliveryTracker()

        coroutineScope {
            repeat(1_000) {
                launch(Dispatchers.Default) {
                    tracker.recordDropped(BleCriticalEventType.REP)
                }
            }
        }

        assertEquals(1_000L, tracker.snapshot.value.repEventsDropped)
    }
}

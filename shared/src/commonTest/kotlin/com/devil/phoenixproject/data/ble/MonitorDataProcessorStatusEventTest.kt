package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.MachineStatusEvent
import com.devil.phoenixproject.domain.model.SampleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #673 PR 2: Tests for MachineStatusEvent emission from MonitorDataProcessor.
 *
 * Verifies that the onStatusEvent callback fires after velocity smoothing with
 * correct position and velocity values, and that the existing deload/ROM-violation
 * callbacks remain functional.
 */
class MonitorDataProcessorStatusEventTest {

    @Test
    fun `onStatusEvent fires for non-zero status after velocity smoothing`() {
        val events = mutableListOf<MachineStatusEvent>()
        val processor = MonitorDataProcessor(
            onStatusEvent = { events.add(it) },
            timeProvider = { 1000L },
        )

        // Build a packet with DELOAD_OCCURRED flag (bit 7 = 0x80)
        val packet = MonitorPacket(
            ticks = 0L,
            posA = 200.0f,
            posB = 200.0f,
            loadA = 10.0f,
            loadB = 10.0f,
            firmwareVelA = 50,  // 5.0 mm/s
            firmwareVelB = 50,
            status = 0x80, // DELOAD_OCCURRED
        )

        processor.process(packet)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(0x80, event.sampleStatus.raw)
        assertTrue(event.sampleStatus.isDeloadOccurred())
        assertEquals(200.0f, event.position) // max(200, 200)
        // Velocity is max(smoothedA, smoothedB) — first sample seeds EMA
        assertTrue(event.velocity > 0f)
    }

    @Test
    fun `onStatusEvent does not fire for zero status`() {
        val events = mutableListOf<MachineStatusEvent>()
        val processor = MonitorDataProcessor(
            onStatusEvent = { events.add(it) },
            timeProvider = { 1000L },
        )

        val packet = MonitorPacket(
            ticks = 0L,
            posA = 200.0f,
            posB = 200.0f,
            loadA = 10.0f,
            loadB = 10.0f,
            firmwareVelA = 50,
            firmwareVelB = 50,
            status = 0, // No flags
        )

        processor.process(packet)

        assertEquals(0, events.size)
    }

    @Test
    fun `onStatusEvent uses max position of A and B`() {
        val events = mutableListOf<MachineStatusEvent>()
        val processor = MonitorDataProcessor(
            onStatusEvent = { events.add(it) },
            timeProvider = { 1000L },
        )

        val packet = MonitorPacket(
            ticks = 0L,
            posA = 100.0f,
            posB = 350.0f,
            loadA = 10.0f,
            loadB = 10.0f,
            firmwareVelA = 50,
            firmwareVelB = 100, // B is faster
            status = 0x01, // REP_TOP_READY
        )

        processor.process(packet)

        assertEquals(1, events.size)
        assertEquals(350.0f, events[0].position) // max(100, 350)
    }

    @Test
    fun `onStatusEvent preserves existing deload callback`() {
        var deloadFired = false
        val events = mutableListOf<MachineStatusEvent>()
        val processor = MonitorDataProcessor(
            onDeloadOccurred = { deloadFired = true },
            onStatusEvent = { events.add(it) },
            timeProvider = { 1000L },
        )

        val packet = MonitorPacket(
            ticks = 0L,
            posA = 200.0f,
            posB = 200.0f,
            loadA = 10.0f,
            loadB = 10.0f,
            firmwareVelA = 50,
            firmwareVelB = 50,
            status = 0x80, // DELOAD_OCCURRED
        )

        processor.process(packet)

        assertTrue(deloadFired, "deloadOccurred callback must still fire")
        assertEquals(1, events.size, "onStatusEvent must also fire")
    }
}

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for KableBleConnectionManager callback routing and state management.
 *
 * Testing approach: Since Kable's Peripheral can't be mocked in KMP common tests,
 * we test what CAN be verified in isolation:
 * - processIncomingData() opcode dispatch to callbacks
 * - disconnect() state cleanup and callback firing
 * - parseDiagnosticData() safety (no crashes on valid/invalid data)
 * - Initial state (currentPeripheral is null)
 *
 * Connection, scanning, and auto-reconnect tests require real BLE hardware
 * and will be verified via manual BLE testing (FACADE-03).
 */
class KableBleConnectionManagerTest {

    /**
     * Create a test manager with tracking callbacks.
     * Returns the manager and a tracker object for asserting callback invocations.
     */
    private fun createTestManager(): Pair<KableBleConnectionManager, CallbackTracker> {
        val tracker = CallbackTracker()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = KableBleConnectionManager(
            scope = scope,
            logRepo = ConnectionLogRepository.instance,
            bleQueue = BleOperationQueue(),
            pollingEngine = MetricPollingEngine(
                scope = scope,
                bleQueue = BleOperationQueue(),
                monitorProcessor = MonitorDataProcessor(),
                handleDetector = HandleStateDetector(),
                onMetricEmit = { true },
                onHeuristicData = {},
                onConnectionLost = {}
            ),
            discoMode = DiscoMode(scope = scope, sendCommand = {}),
            handleDetector = HandleStateDetector(),
            onConnectionStateChanged = { state -> tracker.connectionStates.add(state) },
            onScannedDevicesChanged = { devices -> tracker.scannedDevicesUpdates.add(devices) },
            onReconnectionRequested = { request -> tracker.reconnectionRequests.add(request) },
            onCommandResponse = { opcode -> tracker.commandResponses.add(opcode) },
            onRepEventFromCharacteristic = { data -> tracker.repEventsFromChar.add(data) },
            onRepEventFromRx = { data -> tracker.repEventsFromRx.add(data) },
            onMetricFromRx = { data -> tracker.metricsFromRx.add(data) },
        )
        return manager to tracker
    }

    /** Tracks callback invocations for assertions. */
    private class CallbackTracker {
        val connectionStates = mutableListOf<ConnectionState>()
        val scannedDevicesUpdates = mutableListOf<List<ScannedDevice>>()
        val reconnectionRequests = mutableListOf<ReconnectionRequest>()
        val commandResponses = mutableListOf<UByte>()
        val repEventsFromChar = mutableListOf<ByteArray>()
        val repEventsFromRx = mutableListOf<ByteArray>()
        val metricsFromRx = mutableListOf<ByteArray>()
    }

    // =========================================================================
    // Initial State (1 test)
    // =========================================================================

    @Test
    fun `currentPeripheral is null after construction`() = runTest {
        val (manager, _) = createTestManager()
        assertNull(manager.currentPeripheral, "currentPeripheral should be null after construction")
    }

    // =========================================================================
    // processIncomingData - Callback Routing (5 tests)
    // =========================================================================

    @Test
    fun `processIncomingData with opcode 0x01 and size ge 16 routes to onMetricFromRx`() = runTest {
        val (manager, tracker) = createTestManager()

        // Create a 16-byte packet with opcode 0x01
        val data = ByteArray(16)
        data[0] = 0x01

        manager.processIncomingData(data)

        assertEquals(1, tracker.metricsFromRx.size, "onMetricFromRx should be called once")
        assertTrue(tracker.metricsFromRx[0].contentEquals(data), "Data should match")
    }

    @Test
    fun `processIncomingData with opcode 0x02 and size ge 5 routes to onRepEventFromRx`() = runTest {
        val (manager, tracker) = createTestManager()

        // Create a 5-byte packet with opcode 0x02
        val data = ByteArray(5)
        data[0] = 0x02

        manager.processIncomingData(data)

        assertEquals(1, tracker.repEventsFromRx.size, "onRepEventFromRx should be called once")
        assertTrue(tracker.repEventsFromRx[0].contentEquals(data), "Data should match")
    }

    @Test
    fun `processIncomingData with opcode 0x01 but size lt 16 does NOT route to onMetricFromRx`() = runTest {
        val (manager, tracker) = createTestManager()

        // Create a 15-byte packet (too small) with opcode 0x01
        val data = ByteArray(15)
        data[0] = 0x01

        manager.processIncomingData(data)

        assertEquals(0, tracker.metricsFromRx.size, "onMetricFromRx should NOT be called for short packet")
        // But command response should still fire
        assertEquals(1, tracker.commandResponses.size, "onCommandResponse should still fire")
        assertEquals(0x01.toUByte(), tracker.commandResponses[0])
    }

    @Test
    fun `processIncomingData with opcode 0x02 but size lt 5 does NOT route to onRepEventFromRx`() = runTest {
        val (manager, tracker) = createTestManager()

        // Create a 4-byte packet (too small) with opcode 0x02
        val data = ByteArray(4)
        data[0] = 0x02

        manager.processIncomingData(data)

        assertEquals(0, tracker.repEventsFromRx.size, "onRepEventFromRx should NOT be called for short packet")
        // But command response should still fire
        assertEquals(1, tracker.commandResponses.size, "onCommandResponse should still fire")
        assertEquals(0x02.toUByte(), tracker.commandResponses[0])
    }

    @Test
    fun `processIncomingData always fires onCommandResponse with opcode byte`() = runTest {
        val (manager, tracker) = createTestManager()

        // Send various opcodes
        val data1 = byteArrayOf(0x42, 0x00, 0x00)
        val data2 = byteArrayOf(0xFF.toByte(), 0x01)

        manager.processIncomingData(data1)
        manager.processIncomingData(data2)

        assertEquals(2, tracker.commandResponses.size, "onCommandResponse should fire for each packet")
        assertEquals(0x42.toUByte(), tracker.commandResponses[0])
        assertEquals(0xFF.toUByte(), tracker.commandResponses[1])

        // Neither should route to metric or rep callbacks (wrong opcodes / sizes)
        assertEquals(0, tracker.metricsFromRx.size)
        assertEquals(0, tracker.repEventsFromRx.size)
    }

    @Test
    fun `processIncomingData ignores empty data`() = runTest {
        val (manager, tracker) = createTestManager()

        manager.processIncomingData(byteArrayOf())

        assertEquals(0, tracker.commandResponses.size, "No callbacks should fire for empty data")
        assertEquals(0, tracker.metricsFromRx.size)
        assertEquals(0, tracker.repEventsFromRx.size)
    }

    // =========================================================================
    // Disconnect State Cleanup (2 tests)
    // =========================================================================

    @Test
    fun `disconnect sets currentPeripheral to null`() = runTest {
        val (manager, _) = createTestManager()

        // Peripheral starts null, disconnect should keep it null safely
        manager.disconnect()

        assertNull(manager.currentPeripheral, "currentPeripheral should be null after disconnect")
    }

    @Test
    fun `disconnect fires onConnectionStateChanged with Disconnected`() = runTest {
        val (manager, tracker) = createTestManager()

        manager.disconnect()

        assertTrue(tracker.connectionStates.isNotEmpty(), "onConnectionStateChanged should be called")
        assertEquals(
            ConnectionState.Disconnected,
            tracker.connectionStates.last(),
            "Last state should be Disconnected"
        )
    }

    // =========================================================================
    // parseDiagnosticData Safety (2 tests)
    // =========================================================================

    @Test
    fun `parseDiagnosticData does not throw on empty byte array`() = runTest {
        val (manager, _) = createTestManager()

        // Should not throw - empty data is handled gracefully
        manager.parseDiagnosticData(byteArrayOf())
        // If we reach here, no exception was thrown
    }

    @Test
    fun `parseDiagnosticData does not throw on short data`() = runTest {
        val (manager, _) = createTestManager()

        // Should not throw - short data returns null from parseDiagnosticPacket
        manager.parseDiagnosticData(byteArrayOf(0x01, 0x02))
        // If we reach here, no exception was thrown
    }

    // =========================================================================
    // cancelConnection State Cleanup (2 tests)
    // =========================================================================

    @Test
    fun `cancelConnection sets currentPeripheral to null`() = runTest {
        val (manager, _) = createTestManager()

        manager.cancelConnection()

        assertNull(manager.currentPeripheral, "currentPeripheral should be null after cancelConnection")
    }

    @Test
    fun `cancelConnection fires onConnectionStateChanged with Disconnected`() = runTest {
        val (manager, tracker) = createTestManager()

        manager.cancelConnection()

        assertTrue(tracker.connectionStates.isNotEmpty(), "onConnectionStateChanged should be called")
        assertEquals(
            ConnectionState.Disconnected,
            tracker.connectionStates.last(),
            "Last state should be Disconnected"
        )
    }
}

package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.ReconnectionRequest
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Tests for KableBleConnectionManager callback routing and state management.
 *
 * Testing approach: Since Kable's Peripheral can't be mocked in KMP common tests,
 * we test what CAN be verified in isolation:
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
                onConnectionLost = {},
            ),
            discoMode = DiscoMode(scope = scope, sendCommand = {}),
            handleDetector = HandleStateDetector(),
            onConnectionStateChanged = { state -> tracker.connectionStates.add(state) },
            onScannedDevicesChanged = { devices -> tracker.scannedDevicesUpdates.add(devices) },
            onReconnectionRequested = { request -> tracker.reconnectionRequests.add(request) },
            onRepEventFromCharacteristic = { data -> tracker.repEventsFromChar.add(data) },
            onDiagnosticData = { packet -> tracker.diagnostics.add(packet) },
        )
        return manager to tracker
    }

    /** Tracks callback invocations for assertions. */
    private class CallbackTracker {
        val connectionStates = mutableListOf<ConnectionState>()
        val scannedDevicesUpdates = mutableListOf<List<ScannedDevice>>()
        val reconnectionRequests = mutableListOf<ReconnectionRequest>()
        val repEventsFromChar = mutableListOf<ByteArray>()
        val diagnostics = mutableListOf<DiagnosticPacket>()
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

        assertTrue(
            tracker.connectionStates.isNotEmpty(),
            "onConnectionStateChanged should be called",
        )
        assertEquals(
            ConnectionState.Disconnected,
            tracker.connectionStates.last(),
            "Last state should be Disconnected",
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

    @Test
    fun `parseDiagnosticData routes parsed packet to callback`() = runTest {
        val (manager, tracker) = createTestManager()
        val data = ByteArray(18)
        data[0] = 0x2A
        data[4] = 0x04
        data[12] = 25

        manager.parseDiagnosticData(data)

        assertEquals(1, tracker.diagnostics.size)
        val packet = tracker.diagnostics.single()
        assertEquals(42L, packet.runtimeSeconds)
        assertEquals(4, packet.faultWords[0])
        assertEquals(25, packet.temperatures[0])
        assertTrue(packet.receivedAtMillis > 0L)
    }

    // =========================================================================
    // cancelConnection State Cleanup (2 tests)
    // =========================================================================

    @Test
    fun `cancelConnection sets currentPeripheral to null`() = runTest {
        val (manager, _) = createTestManager()

        manager.cancelConnection()

        assertNull(
            manager.currentPeripheral,
            "currentPeripheral should be null after cancelConnection",
        )
    }

    @Test
    fun `cancelConnection fires onConnectionStateChanged with Disconnected`() = runTest {
        val (manager, tracker) = createTestManager()

        manager.cancelConnection()

        assertTrue(
            tracker.connectionStates.isNotEmpty(),
            "onConnectionStateChanged should be called",
        )
        assertEquals(
            ConnectionState.Disconnected,
            tracker.connectionStates.last(),
            "Last state should be Disconnected",
        )
    }

    // =========================================================================
    // Reps subscription resilience (RCA 2026-06-28: WriteRequestBusy)
    // =========================================================================

    @Test
    fun `isTransientObservationError treats busy and GATT errors as transient`() {
        val (manager, _) = createTestManager()
        // The exact failure from the field log:
        assertTrue(manager.isTransientObservationError(Exception("Write failed: WriteRequestBusy")))
        assertTrue(manager.isTransientObservationError(Exception("GATT error status 133")))
        assertTrue(manager.isTransientObservationError(Exception("device busy")))
    }

    @Test
    fun `isTransientObservationError treats null message as transient by default`() {
        val (manager, _) = createTestManager()
        assertTrue(manager.isTransientObservationError(RuntimeException()))
    }

    @Test
    fun `isTransientObservationError treats disconnect and cancel as permanent`() {
        val (manager, _) = createTestManager()
        assertFalse(manager.isTransientObservationError(Exception("Peripheral is not connected")))
        assertFalse(manager.isTransientObservationError(Exception("Disconnected from device")))
        assertFalse(manager.isTransientObservationError(Exception("operation was cancelled")))
    }

    @Test
    fun `repsBackoffMs grows exponentially then caps`() {
        val (manager, _) = createTestManager()
        assertEquals(100L, manager.repsBackoffMs(1))
        assertEquals(200L, manager.repsBackoffMs(2))
        assertEquals(400L, manager.repsBackoffMs(3))
        assertEquals(800L, manager.repsBackoffMs(4))
        assertEquals(800L, manager.repsBackoffMs(5)) // capped
        assertEquals(800L, manager.repsBackoffMs(10)) // still capped, no overflow
    }

    @Test
    fun `shutdown cancels lifecycle jobs and clears scan state`() = runTest {
        val (manager, tracker) = createTestManager()

        manager.startFakeLifecycleJobsForTest()
        delay(50)

        assertTrue(manager.isLifecycleJobActiveForTest(KableBleConnectionManager.LifecycleJob.SCAN))
        assertTrue(manager.isLifecycleJobActiveForTest(KableBleConnectionManager.LifecycleJob.STATE_OBSERVER))
        assertTrue(tracker.scannedDevicesUpdates.last().isNotEmpty())

        manager.shutdown()
        delay(50)

        assertFalse(manager.isLifecycleJobActiveForTest(KableBleConnectionManager.LifecycleJob.SCAN))
        assertFalse(manager.isLifecycleJobActiveForTest(KableBleConnectionManager.LifecycleJob.STATE_OBSERVER))
        assertEquals(emptyList(), tracker.scannedDevicesUpdates.last())
        assertEquals(ConnectionState.Disconnected, tracker.connectionStates.last())
    }

    // =========================================================================
    // Activation CONFIG summary (log decode only)
    // =========================================================================

    @Test
    fun `activation summary reads eccentric-up min and max as int16 and ramp as float`() {
        val (manager, _) = createTestManager()
        val command = ByteArray(96)
        command[0] = 0x04
        // Same layout BlePacketFactory writes: int16 min, int16 max, float ramp.
        // A float read at 0x48 would swallow the max and hide both velocities.
        putInt16LE(command, 0x48, -260)
        putInt16LE(command, 0x4A, -110)
        putFloatLE(command, 0x4C, 1.0f)
        putFloatLE(command, 0x50, 0.0f)
        putFloatLE(command, 0x54, 50.0f)
        putFloatLE(command, 0x58, 40.0f)
        putFloatLE(command, 0x5C, 3.0f)

        val summary = manager.activationConfigSummary(command)

        assertTrue(summary.contains("eccUpMinMmS[0x48]=-260"), summary)
        assertTrue(summary.contains("eccUpMaxMmS[0x4A]=-110"), summary)
        assertTrue(summary.contains("eccUpRamp[0x4C]=1.0"), summary)
        assertTrue(summary.contains("forceMin[0x50]=0.0"), summary)
        assertTrue(summary.contains("forceMax[0x54]=50.0"), summary)
        assertTrue(summary.contains("softMax[0x58]=40.0"), summary)
        assertTrue(summary.contains("increment[0x5C]=3.0"), summary)
        assertFalse(summary.contains("profileTail"), summary)
    }

    @Test
    fun `activation summary stays empty unless the frame is a 96-byte activation command`() {
        val (manager, _) = createTestManager()
        val shortActivation = ByteArray(95)
        shortActivation[0] = 0x04
        val otherOpcode = ByteArray(96)
        otherOpcode[0] = 0x0A

        assertEquals("", manager.activationConfigSummary(shortActivation))
        assertEquals("", manager.activationConfigSummary(otherOpcode))
    }

    private fun putInt16LE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putFloatLE(buffer: ByteArray, offset: Int, value: Float) {
        val bits = value.toRawBits()
        buffer[offset] = (bits and 0xFF).toByte()
        buffer[offset + 1] = ((bits shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((bits shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((bits shr 24) and 0xFF).toByte()
    }
}

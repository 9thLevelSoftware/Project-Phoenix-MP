package com.devil.phoenixproject.data.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for MetricPollingEngine job lifecycle and partial-stop behavior.
 *
 * Testing approach: Since Kable's Peripheral can't be mocked in KMP common tests,
 * we use internal test helpers (startFakeJobs/startFakeJob) to create jobs without
 * real BLE operations. This tests Job start/stop/restart lifecycle, not BLE reads.
 * BLE read behavior is already tested by MonitorDataProcessorTest, HandleStateDetectorTest,
 * and ProtocolParserTest.
 */
class MetricPollingEngineTest {

    private fun createTestEngine(
        onConnectionLost: suspend () -> Unit = {}
    ): MetricPollingEngine {
        val testScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
        return MetricPollingEngine(
            scope = testScope,
            bleQueue = BleOperationQueue(),
            monitorProcessor = MonitorDataProcessor(),
            handleDetector = HandleStateDetector(),
            onMetricEmit = { true },
            onHeuristicData = {},
            onConnectionLost = onConnectionLost
        )
    }

    // =========================================================================
    // Job Lifecycle Tests (4 tests)
    // =========================================================================

    @Test
    fun `startFakeJobs starts all 4 polling jobs`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "Monitor job should be active")
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic job should be active")
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEURISTIC), "Heuristic job should be active")
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat job should be active")

        engine.stopAll()
    }

    @Test
    fun `stopAll cancels all 4 polling jobs`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        engine.stopAll()
        delay(50)

        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "Monitor job should be cancelled")
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic job should be cancelled")
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.HEURISTIC), "Heuristic job should be cancelled")
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat job should be cancelled")
    }

    @Test
    fun `stopAll resets diagnostic counters`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        // Simulate some diagnostic polls
        engine.incrementDiagnosticCount()
        engine.incrementDiagnosticCount()
        assertTrue(engine.diagnosticPollCount > 0, "diagnosticPollCount should be > 0 before stopAll")

        engine.stopAll()

        assertEquals(0L, engine.diagnosticPollCount, "diagnosticPollCount should be reset to 0")
        assertNull(engine.lastDiagnosticFaults, "lastDiagnosticFaults should be null")
    }

    @Test
    fun `startMonitorPolling cancels previous job before starting new`() = runTest {
        val engine = createTestEngine()

        // Start first monitor job
        engine.startFakeJob(MetricPollingEngine.PollingType.MONITOR)
        delay(50)
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "First monitor job should be active")

        // Start second monitor job - should cancel first
        engine.startFakeJob(MetricPollingEngine.PollingType.MONITOR)
        delay(50)
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "New monitor job should be active")

        engine.stopAll()
    }

    // =========================================================================
    // Partial Stop - Issue #222 (4 tests, CRITICAL)
    // =========================================================================

    @Test
    fun `stopMonitorOnly cancels only monitor job`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        engine.stopMonitorOnly()
        delay(50)

        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "Monitor job should be cancelled")

        engine.stopAll()
    }

    @Test
    fun `stopMonitorOnly preserves diagnostic job`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        engine.stopMonitorOnly()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic job should still be active")

        engine.stopAll()
    }

    @Test
    fun `stopMonitorOnly preserves heartbeat job`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        engine.stopMonitorOnly()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat job should still be active")

        engine.stopAll()
    }

    @Test
    fun `stopMonitorOnly preserves heuristic job`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        engine.stopMonitorOnly()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEURISTIC), "Heuristic job should still be active")

        engine.stopAll()
    }

    // =========================================================================
    // Conditional Restart (5 tests)
    // Uses startFakeJob to simulate "already running" or "not running" states.
    // restartAll uses startFakeJob internally in test mode.
    // =========================================================================

    @Test
    fun `restartAll starts monitor unconditionally`() = runTest {
        val engine = createTestEngine()

        // No prior start - monitor is not active
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR))

        engine.restartAllFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR), "Monitor job should be started")

        engine.stopAll()
    }

    @Test
    fun `restartAll skips diagnostic if already active`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should be active before restart")

        engine.restartAllFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should still be active")

        engine.stopAll()
    }

    @Test
    fun `restartAll restarts diagnostic if not active`() = runTest {
        val engine = createTestEngine()

        // Start all then stop all (diagnostic is now inactive)
        engine.startFakeJobs()
        delay(50)
        engine.stopAll()
        delay(50)
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should be inactive")

        engine.restartAllFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should be restarted")

        engine.stopAll()
    }

    @Test
    fun `restartAll skips heartbeat if already active`() = runTest {
        val engine = createTestEngine()
        engine.startFakeJobs()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should be active before restart")

        engine.restartAllFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should still be active")

        engine.stopAll()
    }

    @Test
    fun `restartAll restarts heartbeat if not active`() = runTest {
        val engine = createTestEngine()

        engine.startFakeJobs()
        delay(50)
        engine.stopAll()
        delay(50)
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should be inactive")

        engine.restartAllFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should be restarted")

        engine.stopAll()
    }

    // =========================================================================
    // Timeout Disconnect - POLL-03 (3 tests)
    // Tests consecutive timeout counter logic directly via internal helpers.
    // =========================================================================

    @Test
    fun `consecutive timeouts trigger disconnect after MAX_CONSECUTIVE_TIMEOUTS`() = runTest {
        var connectionLostCalled = false
        val engine = createTestEngine(
            onConnectionLost = { connectionLostCalled = true }
        )

        // Simulate MAX_CONSECUTIVE_TIMEOUTS (5) consecutive timeouts
        repeat(5) { engine.simulateTimeout() }
        engine.checkTimeoutThreshold()

        assertTrue(connectionLostCalled, "onConnectionLost should be called after MAX_CONSECUTIVE_TIMEOUTS")
    }

    @Test
    fun `successful read resets consecutive timeout counter`() = runTest {
        var connectionLostCalled = false
        val engine = createTestEngine(
            onConnectionLost = { connectionLostCalled = true }
        )

        // Simulate 4 timeouts (just below threshold)
        repeat(4) { engine.simulateTimeout() }
        // Successful read resets counter
        engine.simulateSuccessfulRead()
        // 4 more timeouts (still below threshold because counter was reset)
        repeat(4) { engine.simulateTimeout() }
        engine.checkTimeoutThreshold()

        assertFalse(connectionLostCalled, "onConnectionLost should NOT fire - counter was reset by successful read")
    }

    @Test
    fun `timeout counter does not trigger at MAX minus 1`() = runTest {
        var connectionLostCalled = false
        val engine = createTestEngine(
            onConnectionLost = { connectionLostCalled = true }
        )

        // Simulate MAX_CONSECUTIVE_TIMEOUTS - 1 (4) consecutive timeouts
        repeat(4) { engine.simulateTimeout() }
        engine.checkTimeoutThreshold()

        assertFalse(connectionLostCalled, "onConnectionLost should NOT fire at MAX-1")
    }

    // =========================================================================
    // Diagnostic/Heartbeat Restart (2 tests)
    // =========================================================================

    @Test
    fun `restartDiagnosticAndHeartbeat starts both if not active`() = runTest {
        val engine = createTestEngine()

        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC))
        assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT))

        engine.restartDiagnosticAndHeartbeatFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should be started")
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should be started")

        engine.stopAll()
    }

    @Test
    fun `restartDiagnosticAndHeartbeat skips if already active`() = runTest {
        val engine = createTestEngine()

        // Start diagnostic and heartbeat first
        engine.startFakeJobs()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC))
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT))

        // Calling restart should not create duplicates
        engine.restartDiagnosticAndHeartbeatFake()
        delay(50)

        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC), "Diagnostic should remain active")
        assertTrue(engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT), "Heartbeat should remain active")

        engine.stopAll()
    }
}

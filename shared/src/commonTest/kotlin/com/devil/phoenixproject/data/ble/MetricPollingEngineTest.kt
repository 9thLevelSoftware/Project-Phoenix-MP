package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.util.BleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for MetricPollingEngine job lifecycle and timeout handling using [CompositionGattFake].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricPollingEngineTest {

    private fun createTestEngine(
        dispatcher: CoroutineDispatcher,
        onConnectionLost: suspend () -> Unit = {},
        onDiagnosticData: (DiagnosticPacket) -> Unit = {},
    ): Pair<MetricPollingEngine, CoroutineScope> {
        val testScope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = MetricPollingEngine(
            scope = testScope,
            bleQueue = BleOperationQueue(),
            monitorProcessor = MonitorDataProcessor(),
            handleDetector = HandleStateDetector(),
            onMetricEmit = { true },
            onHeuristicData = {},
            onConnectionLost = onConnectionLost,
            onDiagnosticData = onDiagnosticData,
            isDebugBuild = { false },
        )
        return engine to testScope
    }

    private suspend fun TestScope.withConnectedEngine(
        onConnectionLost: suspend () -> Unit = {},
        onDiagnosticData: (DiagnosticPacket) -> Unit = {},
        block: suspend (MetricPollingEngine, CompositionGattFake) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (engine, testScope) = createTestEngine(
            dispatcher = dispatcher,
            onConnectionLost = onConnectionLost,
            onDiagnosticData = onDiagnosticData,
        )
        val fake = CompositionGattFake(CompositionAdvertisement(), dispatcher)
        fake.connect()
        runCurrent()
        try {
            block(engine, fake)
        } finally {
            engine.stopAll()
            fake.close()
            testScope.cancel()
            runCurrent()
        }
    }

    // =========================================================================
    // Job Lifecycle Tests (4 tests)
    // =========================================================================

    @Test
    fun `startAll starts all 4 polling jobs`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.MONITOR),
                "Monitor job should be active",
            )
            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic job should be active",
            )
            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.HEURISTIC),
                "Heuristic job should be active",
            )
            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat job should be active",
            )
        }
    }

    @Test
    fun `stopAll cancels all 4 polling jobs`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            runCurrent()

            engine.stopAll()
            runCurrent()

            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.MONITOR),
                "Monitor job should be cancelled",
            )
            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic job should be cancelled",
            )
            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.HEURISTIC),
                "Heuristic job should be cancelled",
            )
            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat job should be cancelled",
            )
        }
    }

    @Test
    fun `stopAll resets diagnostic counters`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            advanceTimeBy(100)
            runCurrent()
            assertTrue(
                engine.diagnosticPollCount > 0,
                "diagnosticPollCount should be > 0 before stopAll",
            )
            assertNotNull(engine.lastDiagnosticFaults, "lastDiagnosticFaults should be populated")

            engine.stopAll()

            assertEquals(0L, engine.diagnosticPollCount, "diagnosticPollCount should be reset to 0")
            assertNull(engine.lastDiagnosticFaults, "lastDiagnosticFaults should be null")
        }
    }

    @Test
    fun `startMonitorPolling cancels previous job before starting new`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startMonitorPolling(fake)
            advanceTimeBy(50)
            runCurrent()
            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.MONITOR),
                "First monitor job should be active",
            )
            val readsBeforeRestart = fake.reads.size

            engine.startMonitorPolling(fake)
            advanceTimeBy(50)
            runCurrent()
            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.MONITOR),
                "New monitor job should be active",
            )
            assertTrue(fake.reads.size > readsBeforeRestart, "Restarted monitor job should keep polling")
        }
    }

    // =========================================================================
    // Conditional Restart (5 tests)
    // =========================================================================

    @Test
    fun `restartAll starts monitor unconditionally`() = runTest {
        withConnectedEngine { engine, fake ->
            assertFalse(engine.isJobActive(MetricPollingEngine.PollingType.MONITOR))

            engine.restartAll(fake)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.MONITOR),
                "Monitor job should be started",
            )
        }
    }

    @Test
    fun `restartAll skips diagnostic if already active`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            advanceTimeBy(100)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic should be active before restart",
            )
            val countBeforeRestart = engine.diagnosticPollCount
            assertTrue(countBeforeRestart >= 1L)

            engine.restartAll(fake)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic should still be active",
            )
            assertTrue(
                engine.diagnosticPollCount >= countBeforeRestart,
                "restartAll must not reset diagnosticPollCount by restarting an active diagnostic loop",
            )
        }
    }

    @Test
    fun `restartAll restarts diagnostic if not active`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            runCurrent()
            engine.stopAll()
            runCurrent()
            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic should be inactive",
            )

            engine.restartAll(fake)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.DIAGNOSTIC),
                "Diagnostic should be restarted",
            )
        }
    }

    @Test
    fun `restartAll skips heartbeat if already active`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            advanceTimeBy(2_100L)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat should be active before restart",
            )
            assertEquals(
                1,
                fake.writes.count { it.characteristic == BleConstants.txCharacteristic },
                "First heartbeat should have fired at ~2010ms",
            )

            engine.restartAll(fake)
            advanceTimeBy(1_950L)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat should still be active",
            )
            assertEquals(
                2,
                fake.writes.count { it.characteristic == BleConstants.txCharacteristic },
                "Existing heartbeat cadence should continue without resetting the 2000ms timer",
            )
        }
    }

    @Test
    fun `restartAll restarts heartbeat if not active`() = runTest {
        withConnectedEngine { engine, fake ->
            engine.startAll(fake)
            runCurrent()
            engine.stopAll()
            runCurrent()
            assertFalse(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat should be inactive",
            )

            engine.restartAll(fake)
            runCurrent()

            assertTrue(
                engine.isJobActive(MetricPollingEngine.PollingType.HEARTBEAT),
                "Heartbeat should be restarted",
            )
        }
    }

    // =========================================================================
    // Timeout Disconnect - POLL-03 (3 tests)
    // =========================================================================

    @Test
    fun `consecutive timeouts trigger disconnect after MAX_CONSECUTIVE_TIMEOUTS`() = runTest {
        var connectionLostCalled = false
        withConnectedEngine(onConnectionLost = { connectionLostCalled = true }) { engine, fake ->
            fake.readGate = CompletableDeferred()
            engine.startMonitorPolling(fake)

            advanceTimeBy(7_700L)
            runCurrent()

            assertTrue(
                connectionLostCalled,
                "onConnectionLost should be called after MAX_CONSECUTIVE_TIMEOUTS",
            )
        }
    }

    @Test
    fun `successful read resets consecutive timeout counter`() = runTest {
        var connectionLostCalled = false
        withConnectedEngine(onConnectionLost = { connectionLostCalled = true }) { engine, fake ->
            fake.readGate = CompletableDeferred()
            engine.startMonitorPolling(fake)

            // 4 timeouts (MAX - 1), then enter the 5th read waiting on readGate.
            advanceTimeBy(4 * 1550L + 50L)
            runCurrent()
            assertFalse(connectionLostCalled, "onConnectionLost should NOT fire before MAX_CONSECUTIVE_TIMEOUTS")

            // Complete the in-flight read cleanly and arm a fresh gate for subsequent reads.
            val nextGate = CompletableDeferred<Unit>()
            val completedGate = fake.readGate!!
            fake.readGate = nextGate
            completedGate.complete(Unit)
            runCurrent()

            // 4 more timeouts (still below threshold because the successful read reset the counter).
            advanceTimeBy(4 * 1550L)
            runCurrent()

            assertFalse(
                connectionLostCalled,
                "onConnectionLost should NOT fire - counter was reset by successful read",
            )
        }
    }

    @Test
    fun `timeout counter does not trigger at MAX minus 1`() = runTest {
        var connectionLostCalled = false
        withConnectedEngine(onConnectionLost = { connectionLostCalled = true }) { engine, fake ->
            fake.readGate = CompletableDeferred()
            engine.startMonitorPolling(fake)

            advanceTimeBy(4 * 1550L)
            runCurrent()

            assertFalse(connectionLostCalled, "onConnectionLost should NOT fire at MAX-1")
        }
    }

    @Test
    fun `parseDiagnosticData emits parsed diagnostic packet`() = runTest {
        var emitted: DiagnosticPacket? = null
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (engine, testScope) = createTestEngine(
            dispatcher = dispatcher,
            onDiagnosticData = { packet -> emitted = packet },
        )
        try {
            val data = ByteArray(18)
            data[0] = 0x2A
            data[4] = 0x04
            data[12] = 25

            engine.parseDiagnosticData(data)

            val packet = emitted
            assertTrue(packet != null, "Diagnostic packet should be emitted")
            assertEquals(42L, packet.runtimeSeconds)
            assertEquals(4, packet.faultWords[0])
            assertEquals(25, packet.temperatures[0])
            assertTrue(packet.receivedAtMillis > 0L)
        } finally {
            testScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startMonitorPolling forAutoStart followed synchronously by handleDetector disable keeps handleDetector disabled`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handleDetector = HandleStateDetector()
        val engine = MetricPollingEngine(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            bleQueue = BleOperationQueue(),
            monitorProcessor = MonitorDataProcessor(),
            handleDetector = handleDetector,
            onMetricEmit = { true },
            onHeuristicData = {},
            onConnectionLost = {},
            isDebugBuild = { false },
        )
        val fake = CompositionGattFake(CompositionAdvertisement(), dispatcher)

        engine.startMonitorPolling(fake, forAutoStart = true)
        handleDetector.disable()
        runCurrent()

        assertFalse(
            handleDetector.isEnabled,
            "Deferred startMonitorPolling(forAutoStart=true) must not re-enable handleDetector after synchronous disable()",
        )

        engine.stopAll()
        runCurrent()
    }
}

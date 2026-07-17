package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest

class PhantomBleRepositoryTest {

    @Test
    fun `config defaults to 750 millisecond rep delay`() {
        assertEquals(750L, PhantomBleConfig().repDelayMs)
    }

    @Test
    fun `config rejects non-positive load scale`() {
        assertFailsWith<IllegalArgumentException> {
            PhantomBleConfig(loadScale = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            PhantomBleConfig(loadScale = -1f)
        }
    }

    @Test
    fun `config rejects rep delays below 100 milliseconds`() {
        assertFailsWith<IllegalArgumentException> {
            PhantomBleConfig(repDelayMs = 99L)
        }
        assertFailsWith<IllegalArgumentException> {
            PhantomBleConfig(repDelayMs = -1L)
        }
    }

    @Test
    fun `scanAndConnect publishes simulator device and connected state`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertEquals(1, repository.scannedDevices.value.size)
            assertEquals("Vee_PhantomSimulator", repository.scannedDevices.value.single().name)
            assertTrue(repository.connectionState.value is ConnectionState.Connected)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `cancelConnection invalidates an in-flight connect`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("race", "race-address"))
        }

        try {
            repository.connectionState.first { it == ConnectionState.Connecting }
            repository.cancelConnection()

            val result = connecting.await()

            assertTrue(result.isFailure)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `caller cancellation invalidates an in-flight scan`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            repository.connectionState.first { it == ConnectionState.Scanning }
            scanning.cancel()

            assertFailsWith<CancellationException> { scanning.await() }
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `caller cancellation invalidates an in-flight connect`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("race", "race-address"))
        }

        try {
            repository.connectionState.first { it == ConnectionState.Connecting }
            connecting.cancel()

            assertFailsWith<CancellationException> { connecting.await() }
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopScanning invalidates a scanning attempt and resets state`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            repository.connectionState.first { it == ConnectionState.Scanning }
            repository.stopScanning()

            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(scanning.await().isFailure)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopScanning invalidates a connecting attempt and resets state`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("race", "race-address"))
        }

        try {
            repository.connectionState.first { it == ConnectionState.Connecting }
            repository.stopScanning()

            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(connecting.await().isFailure)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `starting a new scan clears devices from the previous scan`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.scannedDevices.value.isNotEmpty())

            val scanning = async(Dispatchers.Default) { repository.startScanning() }
            withContext(Dispatchers.Default) {
                withTimeout(1_000L) {
                    repository.scannedDevices.first { it.isEmpty() }
                }
            }

            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertTrue(scanning.await().isSuccess)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopScanning is a no-op while initial connection producers publish`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val stopScanningOnDiagnostics = async(Dispatchers.Unconfined) {
            repository.diagnostics.first { diagnostic ->
                if (diagnostic != null) {
                    repository.stopScanning()
                    true
                } else {
                    false
                }
            }
        }

        try {
            val result = repository.connect(ScannedDevice("collector", "collector-address"))

            stopScanningOnDiagnostics.await()
            assertTrue(result.isSuccess)
            assertTrue(repository.connectionState.value is ConnectionState.Connected)
            assertTrue(repository.diagnostics.value != null)
            assertTrue(repository.heuristicData.value != null)
            assertTrue(logRepo.getLogsByEventType(LogEventType.CONNECT_SUCCESS).isNotEmpty())
            assertTrue(logRepo.getLogsByEventType(LogEventType.SCAN_STOP).isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `connected repository initializes diagnostics and heuristic state before workout cancellation`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.diagnostics.value != null)
            assertTrue(repository.heuristicData.value != null)

            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                repository.cancelConnection()

                assertTrue(repository.connectionState.value is ConnectionState.Connected)
                assertTrue(repository.scannedDevices.value.isNotEmpty())
                assertTrue(repository.handleDetection.value.leftDetected)
                assertEquals(HandleState.Grabbed, repository.handleState.value)
                assertTrue(repository.diagnostics.value != null)
                assertTrue(repository.heuristicData.value != null)
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown wins over an in-flight scan`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            repository.connectionState.first { it == ConnectionState.Scanning }
            repository.shutdown()
            val logsAfterShutdown = logRepo.logs.value.size

            assertTrue(scanning.await().isFailure)
            assertEquals(logsAfterShutdown, logRepo.logs.value.size)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertNull(repository.heuristicData.value)
            assertNull(repository.diagnostics.value)
            assertFalse(repository.connectionState.value is ConnectionState.Connected)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown after scan publication prevents post-terminal device-found log`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnPublication = async(Dispatchers.Default) {
            repository.scannedDevices.first { it.isNotEmpty() }
            repository.shutdown()
        }
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            shutdownOnPublication.await()
            val logsAfterShutdown = logRepo.logs.value.size

            assertTrue(scanning.await().isSuccess)
            assertEquals(logsAfterShutdown, logRepo.logs.value.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown wins over a racing connect`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("race", "race-address"))
        }

        try {
            repository.connectionState.first { it == ConnectionState.Connecting }
            repository.shutdown()
            val logsAfterShutdown = logRepo.logs.value.size

            assertTrue(connecting.await().isFailure)
            withContext(Dispatchers.Default) { delay(350L) }
            assertEquals(logsAfterShutdown, logRepo.logs.value.size)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertNull(repository.heuristicData.value)
            assertNull(repository.diagnostics.value)
            assertFalse(repository.connectionState.value is ConnectionState.Connected)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from connected collector invalidates completing connection`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val shutdownOnConnected = async(Dispatchers.Unconfined) {
            repository.connectionState.first { state ->
                if (state is ConnectionState.Connected) {
                    repository.shutdown()
                    true
                } else {
                    false
                }
            }
        }

        try {
            val result = repository.connect(ScannedDevice("collector", "collector-address"))

            shutdownOnConnected.await()

            assertTrue(result.isFailure)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertNull(repository.diagnostics.value)
            assertNull(repository.heuristicData.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from metric collector prevents post-terminal metric logging and state repopulation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnMetric = async(Dispatchers.Unconfined) {
            repository.metricsFlow.first {
                repository.shutdown()
                true
            }
        }

        try {
            repository.scanAndConnect()
            shutdownOnMetric.await()
            val logsAfterShutdown = logRepo.logs.value.size

            withContext(Dispatchers.Default) { delay(350L) }

            assertEquals(logsAfterShutdown, logRepo.logs.value.size)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isEmpty())
            assertNull(repository.diagnostics.value)
            assertNull(repository.heuristicData.value)
            assertFalse(repository.handleDetection.value.leftDetected)
            assertFalse(repository.handleDetection.value.rightDetected)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from connecting publication prevents post-publication connection effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnConnecting = async(Dispatchers.Unconfined) {
            repository.connectionState.first { state ->
                if (state == ConnectionState.Connecting) {
                    repository.shutdown()
                    true
                } else {
                    false
                }
            }
        }

        try {
            val result = repository.connect(ScannedDevice("collector", "collector-address"))

            shutdownOnConnecting.await()

            assertTrue(result.isFailure)
            assertTrue(logRepo.getLogsByEventType(LogEventType.CONNECT_START).isEmpty())
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from scanned-device publication prevents device-found side effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnDevices = async(Dispatchers.Unconfined) {
            repository.scannedDevices.first { devices ->
                if (devices.isNotEmpty()) {
                    repository.shutdown()
                    true
                } else {
                    false
                }
            }
        }

        try {
            val result = repository.startScanning()

            shutdownOnDevices.await()

            assertTrue(result.isFailure)
            assertTrue(logRepo.getLogsByEventType(LogEventType.DEVICE_FOUND).isEmpty())
            assertTrue(repository.scannedDevices.value.isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from timeout teardown prevents stale timeout side effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnDisconnected = async(Dispatchers.Unconfined) {
            repository.connectionState.drop(1).first { state ->
                if (state == ConnectionState.Disconnected) {
                    repository.shutdown()
                    true
                } else {
                    false
                }
            }
        }

        try {
            val result = repository.scanAndConnect(timeoutMs = 50L)

            shutdownOnDisconnected.await()

            assertTrue(result.isFailure)
            assertTrue(logRepo.getLogsByEventType(LogEventType.ERROR).isEmpty())
            repository.reconnectionRequested.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from timeout log prevents reconnection side effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnTimeoutLog = async(Dispatchers.Unconfined) {
            var shutdownTriggered = false
            logRepo.logs.first { logs ->
                if (!shutdownTriggered && logs.any { it.message == "Phantom scan and connect timed out" }) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
                shutdownTriggered
            }
        }

        try {
            repository.reconnectionRequested.test {
                val result = repository.scanAndConnect(timeoutMs = 50L)

                shutdownOnTimeoutLog.await()

                assertTrue(result.isFailure)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from raw monitor deload publication prevents metric side effects`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val shutdownOnDeload = async(Dispatchers.Unconfined) {
            repository.deloadOccurredEvents.first {
                repository.shutdown()
                true
            }
        }
        val monitor = monitorPacket(
            ticks = 42,
            posA = 1250,
            velA = 320,
            loadA = 1234,
            posB = -750,
            velB = -250,
            loadB = 567,
            status = 0x8000,
        )

        try {
            repository.metricsFlow.test {
                val result = repository.injectRawPacket(PhantomRawPacketKind.MONITOR, monitor)

                shutdownOnDeload.await()

                assertTrue(result.isFailure)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from raw ROM publication prevents later ROM and deload side effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnRom = async(Dispatchers.Unconfined) {
            var shutdownTriggered = false
            logRepo.logs.first { logs ->
                if (!shutdownTriggered && logs.any { it.message == "Phantom raw monitor packet reported ROM violation" }) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
                shutdownTriggered
            }
        }
        val monitor = monitorPacket(
            ticks = 42,
            posA = 1250,
            velA = 320,
            loadA = 1234,
            posB = -750,
            velB = -250,
            loadB = 567,
            status = 0x800c,
        )

        try {
            repository.metricsFlow.test {
                val result = repository.injectRawPacket(PhantomRawPacketKind.MONITOR, monitor)

                shutdownOnRom.await()

                assertTrue(result.isFailure)
                assertEquals(
                    1,
                    logRepo.logs.value.count { it.message == "Phantom raw monitor packet reported ROM violation" },
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            repository.deloadOccurredEvents.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `reentrant shutdown log does not recurse disconnect publication`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val shutdownOnDisconnectLog = async(Dispatchers.Unconfined) {
            var shutdownTriggered = false
            logRepo.logs.first { logs ->
                if (!shutdownTriggered && logs.any { it.eventType == LogEventType.DISCONNECT }) {
                    shutdownTriggered = true
                    repository.shutdown()
                }
                shutdownTriggered
            }
        }

        try {
            repository.shutdown()
            shutdownOnDisconnectLog.await()

            assertEquals(1, logRepo.getLogsByEventType(LogEventType.DISCONNECT).size)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `reentrant disconnect log does not duplicate cleanup`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val disconnectOnLog = async(Dispatchers.Unconfined) {
                var disconnectTriggered = false
                logRepo.logs.first { logs ->
                    if (!disconnectTriggered && logs.any { it.eventType == LogEventType.DISCONNECT }) {
                        disconnectTriggered = true
                        repository.disconnect()
                    }
                    disconnectTriggered
                }
            }

            repository.disconnect()
            disconnectOnLog.await()

            assertEquals(1, logRepo.getLogsByEventType(LogEventType.DISCONNECT).size)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown during disconnect cleanup claims terminal state`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val shutdownOnDisconnectLog = async(Dispatchers.Unconfined) {
                logRepo.logs.first { logs ->
                    if (logs.any { it.message == "Disconnected phantom Vitruvian" }) {
                        repository.shutdown()
                        true
                    } else {
                        false
                    }
                }
            }

            repository.disconnect()
            shutdownOnDisconnectLog.await()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(repository.connect(ScannedDevice("terminal", "terminal-address")).isFailure)
            assertEquals(1, logRepo.getLogsByEventType(LogEventType.DISCONNECT).size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopScanning does not log stale cleanup after a reentrant scan`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val restartedScan = async(Dispatchers.Unconfined) {
            var result: Result<Unit>? = null
            repository.connectionState.drop(1).first { state ->
                if (state == ConnectionState.Disconnected) {
                    result = repository.startScanning()
                    true
                } else {
                    false
                }
            }
            result
        }
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            repository.connectionState.first { it == ConnectionState.Scanning }
            logRepo.clearAll()
            repository.stopScanning()

            assertTrue(restartedScan.await()!!.isSuccess)
            assertTrue(scanning.await().isFailure)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isNotEmpty())
            assertTrue(logRepo.getLogsByEventType(LogEventType.SCAN_STOP).isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `cancelConnection does not log stale cleanup after a reentrant connection`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val reconnected = async(Dispatchers.Unconfined) {
            var result: Result<Unit>? = null
            repository.connectionState.drop(1).first { state ->
                if (state == ConnectionState.Disconnected) {
                    result = repository.connect(ScannedDevice("new", "new-address"))
                    true
                } else {
                    false
                }
            }
            result
        }
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("old", "old-address"))
        }

        try {
            repository.connectionState.first { it == ConnectionState.Connecting }
            logRepo.clearAll()
            repository.cancelConnection()

            assertTrue(reconnected.await()!!.isSuccess)
            assertTrue(connecting.await().isFailure)
            assertEquals(ConnectionState.Connected("new", "new-address"), repository.connectionState.value)
            assertTrue(logRepo.getLogsByEventType(LogEventType.DISCONNECT).none { it.message == "Cancelled phantom connection" })
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `disconnect teardown yields to a reentrant scan`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            val restartedScan = async(Dispatchers.Unconfined) {
                var result: Result<Unit>? = null
                repository.handleDetection.drop(1).first { detection ->
                    if (!detection.leftDetected) {
                        result = repository.startScanning()
                        true
                    } else {
                        false
                    }
                }
                result
            }
            logRepo.clearAll()

            repository.disconnect()

            assertTrue(restartedScan.await()!!.isSuccess)
            assertEquals(ConnectionState.Scanning, repository.connectionState.value)
            assertTrue(repository.scannedDevices.value.isNotEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown from workout handle publication prevents post-publication workout effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo, PhantomBleConfig(repDelayMs = 100L))

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val shutdownOnGrabbed = async(Dispatchers.Unconfined) {
                repository.handleState.first { state ->
                    if (state == HandleState.Grabbed) {
                        repository.shutdown()
                        true
                    } else {
                        false
                    }
                }
            }

            val result = repository.startWorkout(workoutParameters())

            shutdownOnGrabbed.await()

            assertTrue(result.isFailure)
            assertTrue(logRepo.logs.value.none { it.message == "Phantom workout started" })
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `handle control does not overwrite reentrant disconnect cleanup`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val disconnectOnHandleDetection = async(Dispatchers.Unconfined) {
                repository.handleDetection.first { detection ->
                    if (!detection.leftDetected && !detection.rightDetected) {
                        repository.disconnect()
                        true
                    } else {
                        false
                    }
                }
            }

            repository.enableHandleDetection(false)
            disconnectOnHandleDetection.await()

            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertFalse(repository.handleDetection.value.leftDetected)
            assertFalse(repository.handleDetection.value.rightDetected)
            assertEquals(HandleState.WaitingForRest, repository.handleState.value)
            assertTrue(logRepo.logs.value.none { it.message == "Phantom handle detection disabled" })
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `command results fail when command log collector disconnects normally`() = runTest {
        val commands = listOf<Pair<String, suspend PhantomBleRepository.() -> Result<Unit>>>(
            "Phantom color scheme set" to { setColorScheme(7) },
            "Phantom received raw workout command" to { sendWorkoutCommand(byteArrayOf(0x01)) },
            "Phantom init sequence accepted" to { sendInitSequence() },
            "Phantom stop command accepted" to { sendStopCommand() },
        )

        commands.forEach { (message, command) ->
            val logRepo = ConnectionLogRepository()
            val repository = PhantomBleRepository(logRepo)
            try {
                assertTrue(repository.scanAndConnect().isSuccess)
                logRepo.clearAll()
                val disconnectOnCommandLog = async(Dispatchers.Unconfined) {
                    var disconnectTriggered = false
                    logRepo.logs.first { logs ->
                        if (!disconnectTriggered && logs.any { it.message == message }) {
                            disconnectTriggered = true
                            repository.disconnect()
                        }
                        disconnectTriggered
                    }
                }

                val result = command(repository)

                disconnectOnCommandLog.await()
                assertTrue(result.isFailure, message)
                assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            } finally {
                repository.shutdown()
            }
        }
    }

    @Test
    fun `replaceConfig aborts when config collector disconnects normally`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val replacement = PhantomBleConfig(loadScale = 2f, repDelayMs = 100L)

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val disconnectOnConfig = async(Dispatchers.Unconfined) {
                repository.config.first { config ->
                    if (config == replacement) {
                        repository.disconnect()
                        true
                    } else {
                        false
                    }
                }
            }

            repository.replaceConfig(replacement)

            disconnectOnConfig.await()
            assertEquals(replacement, repository.config.value)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
            assertTrue(logRepo.logs.value.none { it.message == "Phantom config updated" })
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `injectRawPacket parses legacy rep bytes through protocol parser`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val raw = byteArrayOf(
            0x07, 0x00, // topCounter = 7
            0x00, 0x00,
            0x05, 0x00, // completeCounter = 5
            0x55, 0x66, 0x77, 0x11, // extra legacy bytes should still parse as legacy
        )

        repository.repEvents.test {
            val result = repository.injectRawPacket(PhantomRawPacketKind.REP, raw)
            assertTrue(result.isSuccess)

            val event = awaitItem()
            assertEquals(7, event.topCounter)
            assertEquals(5, event.completeCounter)
            assertTrue(event.isLegacyFormat)
            assertEquals(raw.toList(), event.rawData.toList())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `injectRawPacket parses opcode-prefixed rep bytes through protocol parser`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val raw = byteArrayOf(
            0x02, // opcode prefix
            0x05, 0x00, 0x00, 0x00, // topCounter = 5
            0x04, 0x00, 0x00, 0x00, // completeCounter = 4
            0x00, 0x00, 0x80.toByte(), 0x3F, // rangeTop = 1.0f
            0x00, 0x00, 0x00, 0x00, // rangeBottom = 0.0f
            0x01, 0x00, // repsRomCount = 1
            0x02, 0x00, // repsRomTotal = 2
            0x03, 0x00, // repsSetCount = 3
            0x04, 0x00, // repsSetTotal = 4
        )

        repository.repEvents.test {
            val result = repository.injectRawPacket(PhantomRawPacketKind.REP, raw, hasOpcodePrefix = true)
            assertTrue(result.isSuccess)

            val event = awaitItem()
            assertEquals(5, event.topCounter)
            assertEquals(4, event.completeCounter)
            assertEquals(1, event.repsRomCount)
            assertEquals(3, event.repsSetCount)
            assertFalse(event.isLegacyFormat)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `injectRawPacket parses heuristic bytes into heuristic state`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val raw = ByteArray(48).also { bytes ->
            putFloatLE(bytes, 0, 50f)
            putFloatLE(bytes, 4, 80f)
            putFloatLE(bytes, 24, 40f)
            putFloatLE(bytes, 28, 60f)
        }

        val result = repository.injectRawPacket(PhantomRawPacketKind.HEURISTIC, raw)

        assertTrue(result.isSuccess)
        val heuristic = requireNotNull(repository.heuristicData.value)
        assertEquals(50f, heuristic.concentric.kgAvg)
        assertEquals(80f, heuristic.concentric.kgMax)
        assertEquals(40f, heuristic.eccentric.kgAvg)
        assertEquals(60f, heuristic.eccentric.kgMax)
    }

    @Test
    fun `injectRawPacket parses monitor bytes through monitor processor`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val raw = monitorPacket(
            ticks = 42,
            posA = 1250, // 125.0mm
            velA = 320, // 32.0mm/s
            loadA = 1234, // 12.34kg
            posB = -750, // -75.0mm
            velB = -250, // -25.0mm/s
            loadB = 567, // 5.67kg
        )

        repository.metricsFlow.test {
            val result = repository.injectRawPacket(PhantomRawPacketKind.MONITOR, raw)
            assertTrue(result.isSuccess)

            val metric = awaitItem()
            assertEquals(42, metric.ticks)
            assertEquals(12.34f, metric.loadA)
            assertEquals(5.67f, metric.loadB)
            assertEquals(125.0f, metric.positionA)
            assertEquals(-75.0f, metric.positionB)
            assertEquals(32.0, metric.velocityA)
            assertEquals(-25.0, metric.velocityB)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `injectRawPacket parses diagnostic bytes into diagnostic state`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val raw = ByteArray(18).also { bytes ->
            putUInt32LE(bytes, 0, 1234)
            putUInt16LE(bytes, 4, 0x0001)
            bytes[12] = 31
            bytes[13] = 32
            bytes[14] = 33
            bytes[15] = 34
            bytes[16] = 35
            bytes[17] = 36
        }

        val result = repository.injectRawPacket(PhantomRawPacketKind.DIAGNOSTIC, raw)

        assertTrue(result.isSuccess)
        val packet = repository.diagnostics.value
        requireNotNull(packet)
        assertEquals(1234, packet.runtimeSeconds)
        assertEquals(listOf(1, 0, 0, 0), packet.faultWords)
        assertEquals(listOf(31, 32, 33, 34, 35, 36), packet.temperatures)
        assertTrue(packet.hasFaults)
    }

    @Test
    fun `injectRawPacket returns failure for invalid monitor bytes`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        val result = repository.injectRawPacket(PhantomRawPacketKind.MONITOR, byteArrayOf(1, 2, 3))

        assertFalse(result.isSuccess)
    }

    @Test
    fun `replaceConfig scales generated phantom metrics`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        repository.metricsFlow.test {
            assertTrue(repository.scanAndConnect().isSuccess)
            awaitItem()
            repository.replaceConfig(
                PhantomBleConfig(
                    loadScale = 2f,
                    velocityScale = 3.0,
                    positionScale = 0.5f,
                    repDelayMs = 100L,
                ),
            )
            val metric = awaitItem()

            assertTrue(metric.loadA >= 3.0f, "loadA should reflect doubled inactive phantom load")
            assertTrue(kotlin.math.abs(metric.positionA) <= 325.0f, "positionA should reflect halved range")
            assertTrue(kotlin.math.abs(metric.velocityA) <= 750.0, "velocityA should reflect tripled range")
            repository.disconnect()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fixed rep workout completes at target and stops simulation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters().copy(reps = 2)).isSuccess)

                val first = awaitItem()
                val second = awaitItem()
                assertEquals(1, first.repsSetCount)
                assertEquals(2, second.repsSetCount)
                assertEquals(2, second.repsSetTotal)
                assertTrue(
                    logRepo.getLogsByEventType(LogEventType.COMMAND_RESPONSE)
                        .any { it.message == "Phantom target reps reached" },
                )

                withContext(Dispatchers.Default) { delay(250L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `replaceConfig restarts an active rep simulation`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters().copy(reps = 3)).isSuccess)
                assertEquals(1, awaitItem().repsSetCount)

                repository.replaceConfig(PhantomBleConfig(repDelayMs = 100L))

                val restarted = awaitItem()
                assertEquals(1, restarted.repsSetCount)
                assertEquals(3, restarted.repsSetTotal)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `replaceConfig does not restart a completed fixed rep simulation`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters().copy(reps = 2)).isSuccess)
                assertEquals(1, awaitItem().repsSetCount)
                assertEquals(2, awaitItem().repsSetCount)

                withContext(Dispatchers.Default) { delay(250L) }
                repository.replaceConfig(PhantomBleConfig(repDelayMs = 100L))
                withContext(Dispatchers.Default) { delay(250L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `replaceConfig immediately after final rep does not duplicate completed sequence`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            val replaceOnFinalRep = async(Dispatchers.Unconfined) {
                repository.repEvents.first { event ->
                    if (event.repsSetCount == 2) {
                        repository.replaceConfig(PhantomBleConfig(repDelayMs = 100L))
                        true
                    } else {
                        false
                    }
                }
            }
            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters().copy(reps = 2)).isSuccess)
                assertEquals(1, awaitItem().repsSetCount)
                assertEquals(2, awaitItem().repsSetCount)

                replaceOnFinalRep.await()
                withContext(Dispatchers.Default) { delay(250L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `AMRAP workout continues past requested rep count`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(
                    repository.startWorkout(
                        workoutParameters().copy(reps = 2, isAMRAP = true),
                    ).isSuccess,
                )

                val first = awaitItem()
                val second = awaitItem()
                val third = awaitItem()
                assertEquals(1, first.repsSetCount)
                assertEquals(2, second.repsSetCount)
                assertEquals(2, third.repsSetCount)
                assertEquals(2, third.repsSetTotal)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct reconnect suppresses stale AMRAP rep from previous connection`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 400L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(
                    repository.startWorkout(
                        workoutParameters().copy(reps = 2, isAMRAP = true),
                    ).isSuccess,
                )
                awaitItem()

                val reconnecting = async(Dispatchers.Default) {
                    repository.connect(ScannedDevice("reconnected", "reconnected-address"))
                }
                repository.connectionState.first { it == ConnectionState.Connecting }
                assertTrue(reconnecting.await().isSuccess)
                assertEquals(
                    ConnectionState.Connected("reconnected", "reconnected-address"),
                    repository.connectionState.value,
                )

                withContext(Dispatchers.Default) { delay(500L) }
                expectNoEvents()
                assertTrue(
                    logRepo.getLogsByEventType(LogEventType.REP_RECEIVED)
                        .none { it.message == "Phantom rep notification" && it.details?.contains("rep=2/2") == true },
                )
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct reconnect suppresses stale scheduled metric generation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val reconnected = async(Dispatchers.Unconfined) {
            repository.metricsFlow.first { metric ->
                if (metric.loadA >= 2f) {
                    repository.connect(ScannedDevice("metric-reconnected", "metric-reconnected-address"))
                    true
                } else {
                    false
                }
            }
        }

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
            reconnected.await()

            logRepo.clearAll()
            withContext(Dispatchers.Default) { delay(300L) }

            assertEquals(ConnectionState.Connected("metric-reconnected", "metric-reconnected-address"), repository.connectionState.value)
            assertTrue(
                logRepo.getLogsByEventType(LogEventType.NOTIFICATION)
                    .none { it.message == "Phantom monitor metric" && it.details?.contains("load=10") == true },
            )
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct reconnect suppresses stale scheduled diagnostic generation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val reconnected = async(Dispatchers.Unconfined) {
            logRepo.logs.first { logs ->
                if (logs.any { it.message == "Phantom diagnostic heartbeat" }) {
                    repository.connect(ScannedDevice("diagnostic-reconnected", "diagnostic-reconnected-address"))
                    true
                } else {
                    false
                }
            }
        }

        try {
            repository.connect(ScannedDevice("initial", "initial-address"))
            reconnected.await()

            logRepo.clearAll()
            withContext(Dispatchers.Default) { delay(300L) }

            assertEquals(
                ConnectionState.Connected("diagnostic-reconnected", "diagnostic-reconnected-address"),
                repository.connectionState.value,
            )
            assertTrue(logRepo.getLogsByEventType(LogEventType.DIAGNOSTIC).isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `direct reconnect suppresses stale scheduled heartbeat generation`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val reconnected = async(Dispatchers.Unconfined) {
            logRepo.logs.first { logs ->
                if (logs.any { it.message == "Phantom heartbeat" }) {
                    repository.connect(ScannedDevice("heartbeat-reconnected", "heartbeat-reconnected-address"))
                    true
                } else {
                    false
                }
            }
        }

        try {
            repository.connect(ScannedDevice("initial", "initial-address"))
            reconnected.await()

            withContext(Dispatchers.Default) { delay(100L) }
            logRepo.clearAll()
            withContext(Dispatchers.Default) { delay(300L) }

            assertEquals(
                ConnectionState.Connected("heartbeat-reconnected", "heartbeat-reconnected-address"),
                repository.connectionState.value,
            )
            assertTrue(logRepo.getLogsByEventType(LogEventType.HEARTBEAT).isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `raw monitor injection preserves entry generation across reconnect`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val monitor = monitorPacket(
            ticks = 42,
            posA = 1250,
            velA = 320,
            loadA = 1234,
            posB = -750,
            velB = -250,
            loadB = 567,
        )
        val reconnectOnMetric = async(Dispatchers.Unconfined) {
            repository.metricsFlow.first { metric ->
                if (metric.ticks == 42L) {
                    repository.connect(ScannedDevice("raw-reconnected", "raw-reconnected-address"))
                    true
                } else {
                    false
                }
            }
        }

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(repository.injectRawPacket(PhantomRawPacketKind.MONITOR, monitor).isFailure)
            reconnectOnMetric.await()
            assertEquals(
                ConnectionState.Connected("raw-reconnected", "raw-reconnected-address"),
                repository.connectionState.value,
            )
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopping workout races scheduled rep publication without post-stop rep`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            logRepo.clearAll()
            val stopAfterRep = async(Dispatchers.Default) {
                logRepo.logs.first { logs ->
                    logs.any {
                        it.eventType == LogEventType.REP_RECEIVED && it.message == "Phantom rep notification"
                    }
                }
                repository.stopWorkout()
            }

            assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
            stopAfterRep.await()
            assertEquals(HandleState.Released, repository.handleState.value)
            val repLogsAfterStop = logRepo.getLogsByEventType(LogEventType.REP_RECEIVED).size

            withContext(Dispatchers.Default) { delay(200L) }
            assertEquals(repLogsAfterStop, logRepo.getLogsByEventType(LogEventType.REP_RECEIVED).size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `sendStopCommand preserves active polling until stopWorkout cancels every producer`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 100L),
        )
        val pollingMessages = listOf(
            "Phantom monitor metric",
            "Phantom heuristic update",
            "Phantom diagnostic heartbeat",
            "Phantom heartbeat",
            "Phantom rep notification",
        )
        fun pollingCounts(): Map<String, Int> = pollingMessages.associateWith { message ->
            logRepo.logs.value.count { it.message == message }
        }

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            assertTrue(
                repository.startWorkout(workoutParameters().copy(isAMRAP = true)).isSuccess,
            )

            withContext(Dispatchers.Default) {
                withTimeout(3_500L) {
                    logRepo.logs.first { logs ->
                        pollingMessages.all { message -> logs.any { it.message == message } }
                    }
                }
            }
            val countsBeforeSendStop = pollingCounts()
            assertTrue(countsBeforeSendStop.values.all { it > 0 })

            assertTrue(repository.sendStopCommand().isSuccess)
            assertTrue(repository.connectionState.value is ConnectionState.Connected)
            assertEquals(HandleState.Grabbed, repository.handleState.value)
            val countsAfterSendStop = withContext(Dispatchers.Default) {
                withTimeout(3_500L) {
                    logRepo.logs.first { logs ->
                        pollingMessages.all { message ->
                            logs.count { it.message == message } > countsBeforeSendStop.getValue(message)
                        }
                    }
                    pollingCounts()
                }
            }
            pollingMessages.forEach { message ->
                assertTrue(
                    countsAfterSendStop.getValue(message) > countsBeforeSendStop.getValue(message),
                    "$message should remain active after sendStopCommand",
                )
            }

            assertTrue(repository.stopWorkout().isSuccess)
            assertTrue(repository.connectionState.value is ConnectionState.Connected)
            assertEquals(HandleState.Released, repository.handleState.value)
            val countsAfterStop = pollingCounts()

            withContext(Dispatchers.Default) { delay(2_250L) }
            assertEquals(countsAfterStop, pollingCounts())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopMonitorPollingOnly stops metrics without stopping other polling`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            repository.metricsFlow.test {
                assertTrue(repository.scanAndConnect().isSuccess)
                assertTrue(awaitItem().loadA < 2f)
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                assertTrue(awaitItem().loadA >= 2f)

                repository.stopMonitorPollingOnly()

                withContext(Dispatchers.Default) { delay(500L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `stopMonitorPollingOnly keeps rep heuristic diagnostics and heartbeat active`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo, PhantomBleConfig(repDelayMs = 100L))

        try {
            assertTrue(repository.scanAndConnect().isSuccess)
            repository.repEvents.test {
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                withContext(Dispatchers.Default) { delay(50L) }
                assertTrue(repository.heuristicData.value != null)
                val heuristicTimestamp = requireNotNull(repository.heuristicData.value).timestamp
                val diagnosticTimestamp = requireNotNull(repository.diagnostics.value).receivedAtMillis

                repository.stopMonitorPollingOnly()
                val heartbeatCountAfterStop = logRepo.getLogsByEventType(LogEventType.HEARTBEAT).size

                withContext(Dispatchers.Default) { delay(300L) }
                assertTrue(logRepo.getLogsByEventType(LogEventType.REP_RECEIVED).isNotEmpty())
                awaitItem()
                assertTrue(requireNotNull(repository.heuristicData.value).timestamp > heuristicTimestamp)
                withContext(Dispatchers.Default) { delay(2_200L) }
                assertTrue(requireNotNull(repository.diagnostics.value).receivedAtMillis > diagnosticTimestamp)
                assertTrue(logRepo.getLogsByEventType(LogEventType.HEARTBEAT).size > heartbeatCountAfterStop)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `scanAndConnect timeout returns failure and requests reconnection`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        try {
            repository.reconnectionRequested.test {
                val result = repository.scanAndConnect(timeoutMs = 50L)

                assertTrue(result.isFailure)
                val request = awaitItem()
                assertEquals(PhantomBleRepository.PHANTOM_DEVICE_NAME, request.deviceName)
                assertEquals(PhantomBleRepository.PHANTOM_DEVICE_ADDRESS, request.deviceAddress)
                assertEquals("connection_timeout", request.reason)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `timed out scan does not tear down an explicitly started newer scan`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            repository.reconnectionRequested.test {
                val timedOut = async(Dispatchers.Default) {
                    repository.scanAndConnect(timeoutMs = 250L)
                }
                repository.connectionState.first { it == ConnectionState.Connecting }

                repository.stopScanning()
                val newerScan = async(Dispatchers.Default) { repository.startScanning() }
                repository.connectionState.first { it == ConnectionState.Scanning }

                assertTrue(timedOut.await().isFailure)
                assertTrue(newerScan.await().isSuccess)
                assertTrue(repository.scannedDevices.value.isNotEmpty())
                assertEquals(ConnectionState.Scanning, repository.connectionState.value)
                expectNoEvents()
                assertTrue(logRepo.getLogsByEventType(LogEventType.ERROR).isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown makes scanAndConnect return failure without timeout side effects`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        repository.shutdown()
        val logsAfterShutdown = logRepo.logs.value.size

        repository.reconnectionRequested.test {
            val result = repository.scanAndConnect(timeoutMs = 50L)

            assertTrue(result.isFailure)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(logsAfterShutdown, logRepo.logs.value.size)
        assertTrue(logRepo.getLogsByEventType(LogEventType.ERROR).isEmpty())
    }

    @Test
    fun `scanAndConnect propagates caller timeout without requesting reconnection`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50L) {
                    repository.scanAndConnect(timeoutMs = 10_000L)
                }
            }
            assertTrue(logRepo.getLogsByEventType(LogEventType.ERROR).isEmpty())
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `disco mode requires connection and clears on workout and disconnect`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        try {
            repository.startDiscoMode()
            assertFalse(repository.discoModeActive.value)

            assertTrue(repository.scanAndConnect().isSuccess)
            repository.startDiscoMode()
            assertTrue(repository.discoModeActive.value)

            assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
            assertFalse(repository.discoModeActive.value)

            repository.startDiscoMode()
            assertFalse(repository.discoModeActive.value)
            repository.disconnect()
            assertFalse(repository.discoModeActive.value)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown cancels polling jobs and clears lifecycle state`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(
            logRepo,
            PhantomBleConfig(repDelayMs = 100L),
        )

        assertTrue(repository.scanAndConnect().isSuccess)
        assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
        withContext(Dispatchers.Default) { delay(50L) }
        assertFalse(repository.discoModeActive.value)
        assertTrue(repository.scannedDevices.value.isNotEmpty())
        assertTrue(repository.diagnostics.value != null)
        assertTrue(repository.heuristicData.value != null)

        repository.shutdown()
        val diagnosticLogsAfterShutdown = logRepo.getLogsByEventType(LogEventType.DIAGNOSTIC).size
        val heartbeatLogsAfterShutdown = logRepo.getLogsByEventType(LogEventType.HEARTBEAT).size
        withContext(Dispatchers.Default) { delay(350L) }

        assertEquals(diagnosticLogsAfterShutdown, logRepo.getLogsByEventType(LogEventType.DIAGNOSTIC).size)
        assertEquals(heartbeatLogsAfterShutdown, logRepo.getLogsByEventType(LogEventType.HEARTBEAT).size)
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        assertFalse(repository.handleDetection.value.leftDetected)
        assertFalse(repository.handleDetection.value.rightDetected)
        assertEquals(HandleState.WaitingForRest, repository.handleState.value)
        assertFalse(repository.discoModeActive.value)
        assertNull(repository.diagnostics.value)
        assertTrue(repository.scannedDevices.value.isEmpty())
        assertNull(repository.heuristicData.value)
    }

    @Test
    fun `shutdown makes non-suspend controls no-ops`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())

        repository.shutdown()

        repository.enableHandleDetection(true)
        repository.resetHandleState()
        repository.enableJustLiftWaitingMode()
        repository.startDiscoMode()
        repository.stopDiscoMode()

        assertFalse(repository.handleDetection.value.leftDetected)
        assertFalse(repository.handleDetection.value.rightDetected)
        assertEquals(HandleState.WaitingForRest, repository.handleState.value)
        assertFalse(repository.discoModeActive.value)
    }

    @Test
    fun `shutdown rejects stop and color commands and preserves config`() = runTest {
        val initialConfig = PhantomBleConfig(repDelayMs = 100L)
        val repository = PhantomBleRepository(ConnectionLogRepository(), initialConfig)

        assertTrue(repository.scanAndConnect().isSuccess)
        assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
        repository.shutdown()

        val stopResult = repository.stopWorkout()
        val sendStopResult = repository.sendStopCommand()
        val setColorResult = repository.setColorScheme(7)
        repository.setLastColorSchemeIndex(7)
        repository.replaceConfig(PhantomBleConfig(loadScale = 2f, repDelayMs = 100L))

        assertTrue(stopResult.isFailure)
        assertTrue(sendStopResult.isFailure)
        assertTrue(setColorResult.isFailure)
        assertEquals(initialConfig, repository.config.value)
        assertEquals(HandleState.WaitingForRest, repository.handleState.value)
    }

    @Test
    fun `shutdown seals remaining suspend controls without post-terminal logs`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)

        repository.shutdown()
        val logsAfterShutdown = logRepo.logs.value.size

        repository.stopScanning()
        repository.cancelConnection()
        repository.disconnect()
        val workoutResult = repository.sendWorkoutCommand(byteArrayOf(0x01))
        val initResult = repository.sendInitSequence()
        val stopResult = repository.sendStopCommand()
        repository.stopPolling()
        repository.stopMonitorPollingOnly()
        repository.shutdown()

        assertTrue(workoutResult.isFailure)
        assertTrue(initResult.isFailure)
        assertTrue(stopResult.isFailure)
        assertEquals(logsAfterShutdown, logRepo.logs.value.size)
    }

    @Test
    fun `shutdown prevents scheduled metric emissions`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            repository.metricsFlow.test {
                assertTrue(repository.scanAndConnect().isSuccess)
                awaitItem()
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                awaitItem()

                repository.shutdown()
                withContext(Dispatchers.Default) { delay(350L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown prevents scheduled rep emissions`() = runTest {
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(repDelayMs = 100L),
        )

        try {
            repository.repEvents.test {
                assertTrue(repository.scanAndConnect().isSuccess)
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                awaitItem()

                repository.shutdown()
                withContext(Dispatchers.Default) { delay(200L) }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun `shutdown prevents raw packet routes from publishing`() = runTest {
        val logRepo = ConnectionLogRepository()
        val repository = PhantomBleRepository(logRepo)
        val monitor = monitorPacket(
            ticks = 42,
            posA = 1250,
            velA = 320,
            loadA = 1234,
            posB = -750,
            velB = -250,
            loadB = 567,
        )
        val rep = byteArrayOf(
            0x07, 0x00,
            0x00, 0x00,
            0x05, 0x00,
            0x55, 0x66, 0x77, 0x11,
        )
        val diagnostic = ByteArray(18).also { bytes ->
            putUInt32LE(bytes, 0, 1234)
            putUInt16LE(bytes, 4, 0x0001)
            bytes[12] = 31
            bytes[13] = 32
            bytes[14] = 33
            bytes[15] = 34
            bytes[16] = 35
            bytes[17] = 36
        }
        val heuristic = ByteArray(48).also { bytes ->
            putFloatLE(bytes, 0, 50f)
            putFloatLE(bytes, 4, 80f)
            putFloatLE(bytes, 24, 40f)
            putFloatLE(bytes, 28, 60f)
        }

        repository.shutdown()
        val logsAfterShutdown = logRepo.logs.value.size

        repository.metricsFlow.test {
            assertTrue(repository.injectRawPacket(PhantomRawPacketKind.MONITOR, monitor).isFailure)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        repository.repEvents.test {
            assertTrue(repository.injectRawPacket(PhantomRawPacketKind.REP, rep).isFailure)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repository.injectRawPacket(PhantomRawPacketKind.DIAGNOSTIC, diagnostic).isFailure)
        assertTrue(repository.injectRawPacket(PhantomRawPacketKind.HEURISTIC, heuristic).isFailure)
        assertNull(repository.diagnostics.value)
        assertNull(repository.heuristicData.value)
        assertEquals(logsAfterShutdown, logRepo.logs.value.size)
    }

    private fun workoutParameters(): WorkoutParameters = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = 3,
        weightPerCableKg = 10f,
    )

    private fun monitorPacket(
        ticks: Int,
        posA: Int,
        velA: Int,
        loadA: Int,
        posB: Int,
        velB: Int,
        loadB: Int,
        status: Int = 0,
    ): ByteArray = ByteArray(18).also { bytes ->
        putUInt16LE(bytes, 0, ticks and 0xFFFF)
        putUInt16LE(bytes, 2, ticks ushr 16)
        putInt16LE(bytes, 4, posA)
        putInt16LE(bytes, 6, velA)
        putUInt16LE(bytes, 8, loadA)
        putInt16LE(bytes, 10, posB)
        putInt16LE(bytes, 12, velB)
        putUInt16LE(bytes, 14, loadB)
        putUInt16LE(bytes, 16, status)
    }

    private fun putUInt32LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun putUInt16LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putInt16LE(bytes: ByteArray, offset: Int, value: Int) {
        val encoded = value and 0xFFFF
        putUInt16LE(bytes, offset, encoded)
    }

    private fun putFloatLE(bytes: ByteArray, offset: Int, value: Float) {
        val bits = value.toBits()
        bytes[offset] = bits.toByte()
        bytes[offset + 1] = (bits ushr 8).toByte()
        bytes[offset + 2] = (bits ushr 16).toByte()
        bytes[offset + 3] = (bits ushr 24).toByte()
    }
}

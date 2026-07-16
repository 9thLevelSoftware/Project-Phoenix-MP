package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
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
    fun `shutdown wins over an in-flight scan`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val scanning = async(Dispatchers.Default) { repository.startScanning() }

        try {
            repository.connectionState.first { it == ConnectionState.Scanning }
            repository.shutdown()

            assertTrue(scanning.await().isFailure)
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
    fun `shutdown wins over a racing connect`() = runTest {
        val repository = PhantomBleRepository(ConnectionLogRepository())
        val connecting = async(Dispatchers.Default) {
            repository.connect(ScannedDevice("race", "race-address"))
        }

        try {
            repository.shutdown()

            assertTrue(connecting.await().isFailure)
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
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
            PhantomBleConfig(loadScale = 2f, velocityScale = 3.0, positionScale = 0.5f, repDelayMs = 100L),
        )

        repository.metricsFlow.test {
            assertTrue(repository.scanAndConnect().isSuccess)
            val metric = awaitItem()

            assertTrue(metric.loadA >= 3.0f, "loadA should reflect doubled inactive phantom load")
            assertTrue(kotlin.math.abs(metric.positionA) <= 325.0f, "positionA should reflect halved range")
            assertTrue(kotlin.math.abs(metric.velocityA) <= 750.0, "velocityA should reflect tripled range")
            repository.disconnect()
            cancelAndIgnoreRemainingEvents()
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
                assertTrue(repository.startWorkout(workoutParameters()).isSuccess)
                var metric = awaitItem()
                while (metric.loadA < 2f) {
                    metric = awaitItem()
                }

                repository.stopMonitorPollingOnly()

                delay(500L)
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
        val repository = PhantomBleRepository(
            ConnectionLogRepository(),
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
        withContext(Dispatchers.Default) { delay(350L) }

        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        assertFalse(repository.handleDetection.value.leftDetected)
        assertFalse(repository.handleDetection.value.rightDetected)
        assertEquals(HandleState.WaitingForRest, repository.handleState.value)
        assertFalse(repository.discoModeActive.value)
        assertNull(repository.diagnostics.value)
        assertTrue(repository.scannedDevices.value.isEmpty())
        assertNull(repository.heuristicData.value)
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

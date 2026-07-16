package com.devil.phoenixproject.data.repository

import app.cash.turbine.test
import com.devil.phoenixproject.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhantomBleRepositoryTest {

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
}

package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class KableBleRepositoryTest {

    @Test
    fun `shutdown clears exposed state and cancels repository scope`() = runTest {
        val repository = KableBleRepository()

        repository.shutdown()
        delay(50)

        assertFalse(repository.isRepositoryScopeActiveForTest)
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        assertTrue(repository.scannedDevices.value.isEmpty())
        assertNull(repository.heuristicData.value)
        assertNull(repository.diagnostics.value)
    }

    @Test
    fun `critical rep events dropped under backpressure are counted`() = runTest {
        val repository = KableBleRepository()
        val slowCollector = launch {
            repository.repEvents.collect {
                delay(Long.MAX_VALUE)
            }
        }
        delay(50)

        repeat(128) { index ->
            repository.publishRepEventForTest(repNotification(index))
        }

        assertTrue(
            repository.eventDeliverySnapshotForTest.value.repEventsDropped > 0,
            "Slow collectors should make critical rep drops visible through the delivery counter",
        )

        slowCollector.cancel()
        repository.shutdown()
    }

    @Test
    fun `published rep diagnostics include source packet counters and emit result`() = runTest {
        val logRepo = ConnectionLogRepository.instance
        logRepo.clearAll()
        val repository = KableBleRepository()

        val emitted = repository.publishRepEventForTest(
            repNotification(index = 2).copy(
                completeCounter = 1,
                repsRomCount = 1,
                repsSetCount = 0,
                rawData = byteArrayOf(0x02, 0x02, 0x00, 0x01, 0x00),
                isLegacyFormat = false,
            ),
            source = "rx",
        )

        assertTrue(emitted)
        val logEntry = logRepo.getLogsByEventType(LogEventType.REP_RECEIVED).first()
        assertEquals("Rep event published", logEntry.message)
        val details = logEntry.details ?: error("Expected rep diagnostics details")
        assertTrue(details.contains("source=rx"), details)
        assertTrue(details.contains("packetSize=5"), details)
        assertTrue(details.contains("legacy=false"), details)
        assertTrue(details.contains("up=2"), details)
        assertTrue(details.contains("down=1"), details)
        assertTrue(details.contains("repsRomCount=1"), details)
        assertTrue(details.contains("repsSetCount=0"), details)
        assertTrue(details.contains("emitted=true"), details)

        repository.shutdown()
        logRepo.clearAll()
    }

    @Test
    fun `slow critical lifecycle event collectors do not suspend producers`() = runTest {
        val repository = KableBleRepository()
        val slowDeloadCollector = launch {
            repository.deloadOccurredEvents.collect {
                delay(Long.MAX_VALUE)
            }
        }
        val slowRomCollector = launch {
            repository.romViolationEvents.collect {
                delay(Long.MAX_VALUE)
            }
        }
        val slowReconnectCollector = launch {
            repository.reconnectionRequested.collect {
                delay(Long.MAX_VALUE)
            }
        }
        delay(50)

        withTimeout(1_000) {
            repeat(32) { index ->
                repository.publishDeloadOccurredForTest()
                repository.publishRomViolationForTest(
                    if (index % 2 == 0) {
                        KableBleRepository.RomViolationType.OUTSIDE_HIGH
                    } else {
                        KableBleRepository.RomViolationType.OUTSIDE_LOW
                    },
                )
                repository.publishReconnectionRequestedForTest(
                    ReconnectionRequest(
                        deviceName = "Vee_Test",
                        deviceAddress = "AA:BB:CC:DD:EE:FF",
                        reason = "test",
                        timestamp = index.toLong(),
                    ),
                )
            }
        }

        slowDeloadCollector.cancel()
        slowRomCollector.cancel()
        slowReconnectCollector.cancel()
        repository.shutdown()
    }

    private fun repNotification(index: Int): RepNotification = RepNotification(
        topCounter = index,
        completeCounter = index,
        repsRomCount = index,
        repsRomTotal = 128,
        repsSetCount = index,
        repsSetTotal = 128,
        rawData = byteArrayOf(0x02, index.toByte()),
        timestamp = index.toLong(),
    )
}

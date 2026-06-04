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

package com.devil.phoenixproject.data.csv

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #1242 B4 replay suppression. Review finding on PR #1252: on low-memory Activity recreation
 * (process alive, Activity destroyed + restored) the recreated `RoutineCsvViewModel` owns a
 * fresh `CsvImportIntakeOwnership` with no consumed ids, so the bridge's replay buffer could
 * re-deliver an already-imported offer and commit twice. The bridge's process-scoped
 * acknowledgement is the durable consume record: an acknowledged delivery must never replay to
 * any collector, while a pending (never acknowledged) delivery must still replay to the first
 * collector — that is the cold-start case the replay buffer exists for.
 */
class AndroidCsvImportBridgeReplayTest {
    @Test
    fun anAcknowledgedDeliveryIsNeverReplayedToANewCollector() = runTest {
        val id = UUID.randomUUID().toString()
        AndroidCsvImportBridge.offer(id, CsvImportDeliveryResult.Read("csv"))

        val first = withTimeout(1_000) { AndroidCsvImportBridge.offers.first { it.deliveryId == id } }
        assertEquals(id, first.deliveryId)
        AndroidCsvImportBridge.acknowledge(id)

        // A recreated Activity subscribes a fresh collector against the same replay buffer.
        val replayed = withTimeoutOrNull(500) { AndroidCsvImportBridge.offers.first { it.deliveryId == id } }
        assertNull(replayed, "a consumed delivery must never replay to a new collector")
    }

    @Test
    fun aPendingDeliveryStillReplaysToTheFirstCollector() = runTest {
        val id = UUID.randomUUID().toString()
        // No collector existed when the offer landed (cold start behind splash/EULA).
        AndroidCsvImportBridge.offer(id, CsvImportDeliveryResult.Unreadable)

        val delivered = withTimeout(1_000) { AndroidCsvImportBridge.offers.first { it.deliveryId == id } }
        assertEquals(id, delivered.deliveryId)
        AndroidCsvImportBridge.acknowledge(id)
    }

    @Test
    fun anAcknowledgedDeliveryIsNeverOfferedAgain() = runTest {
        val id = UUID.randomUUID().toString()
        AndroidCsvImportBridge.offer(id, CsvImportDeliveryResult.Unreadable)
        withTimeout(1_000) { AndroidCsvImportBridge.offers.first { it.deliveryId == id } }
        AndroidCsvImportBridge.acknowledge(id)

        // A redelivered intent for a consumed id is a no-op at the source too.
        AndroidCsvImportBridge.offer(id, CsvImportDeliveryResult.Read("csv"))
        val again = withTimeoutOrNull(500) { AndroidCsvImportBridge.offers.first { it.deliveryId == id } }
        assertNull(again, "re-offering a consumed delivery must be a no-op")
    }
}

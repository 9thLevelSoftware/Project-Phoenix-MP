package com.devil.phoenixproject.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Round-2 merge-gate R2 behavior tests for Android provider-metadata
 * resolution: a deliberately blocked document-provider query must never block
 * the activity-result callback thread (the main loop stays responsive while
 * the query is blocked), the reads run off-main inside the caller-owned scope,
 * one whole-batch result is delivered after the block clears, and cancelling
 * the owned scope delivers nothing.
 */
class AndroidPickerBatchTest {

    private val batch = AndroidPickerBatch(Dispatchers.IO)

    private fun photo(handle: String) = PickedPhoto(handle = handle, displayName = "blocked.jpg", byteSize = null)

    @Test
    fun blockedProviderQueryNeverBlocksTheCallbackThreadAndDeliversAfterUnblock() {
        val callerThread = Thread.currentThread().name
        val gate = CountDownLatch(1)
        val resolveThreads = mutableListOf<String>()
        val delivered = mutableListOf<MediaPickResult>()
        val deliveredLatch = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Default)

        try {
            // The activity-result callback path: it hands the batch off and
            // must return while the provider query is still blocked.
            val job: Job = batch.resolveAndDeliver(
                scope = scope,
                handles = listOf("content://u1", "content://u2"),
                resolveOne = { handle ->
                    resolveThreads += Thread.currentThread().name
                    gate.await(5, TimeUnit.SECONDS)
                    photo(handle)
                },
                onResult = { result ->
                    delivered += result
                    deliveredLatch.countDown()
                },
            )

            // We are already past resolveAndDeliver while the query is blocked:
            // the main/caller thread never waits on provider I/O (R2).
            assertFalse(
                deliveredLatch.await(200, TimeUnit.MILLISECONDS),
                "No result may be delivered while the provider query is blocked (R2)",
            )
            assertTrue(job.isActive, "The resolution is in flight, owned by the scope (R2)")

            gate.countDown()
            assertTrue(deliveredLatch.await(5, TimeUnit.SECONDS), "Delivery must follow the unblocked query")
            val result = delivered.single()
            assertTrue(result is MediaPickResult.Picked, "One whole-batch result is delivered (SD11)")
            assertEquals(2, (result as MediaPickResult.Picked).photos.size)
            assertTrue(
                resolveThreads.isNotEmpty() && resolveThreads.all { it != callerThread },
                "Provider metadata reads must run off the callback/main thread (R2)",
            )
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun cancelledOwnedScopeDeliversNothing() = runTest {
        val gate = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val job = batch.resolveAndDeliver(
                scope = scope,
                handles = listOf("content://u1"),
                resolveOne = { handle ->
                    gate.await(5, TimeUnit.SECONDS)
                    photo(handle)
                },
                onResult = { delivered.set(true) },
            )
            // The picker composable left composition (dialog dismissed) while
            // the query was blocked: the owned scope cancels the work.
            job.cancel()
            gate.countDown()
            job.join()
            assertFalse(delivered.get(), "A cancelled resolution must deliver no result (R2 owned lifecycle)")
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }
}

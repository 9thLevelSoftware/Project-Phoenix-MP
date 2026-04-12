package com.devil.phoenixproject.ui.sync

import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests verifying that the coroutine lifecycle pattern used by [LinkAccountViewModel]
 * correctly cancels child coroutines when the parent job is cancelled.
 *
 * These tests validate the pattern without mocking SyncManager, focusing on:
 * - SupervisorJob + CoroutineScope pattern allows proper cancellation
 * - job.cancel() terminates all child coroutines
 * - job.isActive correctly reflects lifecycle state
 */
class LinkAccountViewModelLifecycleTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Test
    fun `SupervisorJob cancellation pattern terminates child coroutines`() = runTest {
        // Arrange: Create the same scope pattern as LinkAccountViewModel
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)
        var coroutineCompleted = false
        var coroutineCancelled = false

        // Act: Launch a long-running coroutine
        scope.launch {
            try {
                delay(10_000L) // Simulate long operation
                coroutineCompleted = true
            } catch (e: CancellationException) {
                coroutineCancelled = true
                throw e
            }
        }

        advanceTimeBy(100) // Let coroutine start
        assertTrue(job.isActive)

        // Cancel the job (equivalent to clear())
        job.cancel()
        advanceUntilIdle()

        // Assert
        assertFalse(job.isActive, "Job should be inactive after cancel")
        assertFalse(coroutineCompleted, "Coroutine should not have completed")
        assertTrue(coroutineCancelled, "Coroutine should have been cancelled")
    }

    @Test
    fun `multiple child coroutines are all cancelled`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)
        var child1Cancelled = false
        var child2Cancelled = false
        var child3Cancelled = false

        scope.launch {
            try {
                delay(10_000L)
            } catch (e: CancellationException) {
                child1Cancelled = true
                throw e
            }
        }

        scope.launch {
            try {
                delay(20_000L)
            } catch (e: CancellationException) {
                child2Cancelled = true
                throw e
            }
        }

        scope.launch {
            try {
                delay(30_000L)
            } catch (e: CancellationException) {
                child3Cancelled = true
                throw e
            }
        }

        advanceTimeBy(100)
        job.cancel()
        advanceUntilIdle()

        assertTrue(child1Cancelled, "Child 1 should be cancelled")
        assertTrue(child2Cancelled, "Child 2 should be cancelled")
        assertTrue(child3Cancelled, "Child 3 should be cancelled")
    }

    @Test
    fun `isActive returns false after cancel`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)

        assertTrue(job.isActive)

        job.cancel()

        assertFalse(job.isActive)
    }

    @Test
    fun `cancel is idempotent - multiple calls do not throw`() = runTest {
        val job = SupervisorJob()

        job.cancel()
        job.cancel()
        job.cancel()

        assertFalse(job.isActive)
    }

    @Test
    fun `coroutines started after cancel do not run`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Main + job)
        var coroutineRan = false

        job.cancel()

        scope.launch {
            coroutineRan = true
        }

        advanceUntilIdle()

        assertFalse(coroutineRan, "Coroutine should not run after job is cancelled")
    }

    @Test
    fun `SupervisorJob allows sibling failures without propagation`() = runTest {
        val job = SupervisorJob()
        // Capture exceptions rather than propagating (required for runTest)
        var caughtException: Throwable? = null
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            caughtException = throwable
        }
        val scope = CoroutineScope(Dispatchers.Main + job + exceptionHandler)
        var child2RanAfterChild1Failed = false

        // Child 1: fails immediately
        scope.launch {
            throw RuntimeException("Child 1 failure")
        }

        // Child 2: should still run due to SupervisorJob
        scope.launch {
            delay(100)
            child2RanAfterChild1Failed = true
        }

        advanceUntilIdle()

        assertTrue(
            caughtException != null && caughtException!!.message == "Child 1 failure",
            "Exception should have been caught by handler",
        )
        assertTrue(
            child2RanAfterChild1Failed,
            "SupervisorJob should isolate child failures",
        )
        assertTrue(job.isActive, "Parent job should still be active after child failure")
    }
}

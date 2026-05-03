package com.devil.phoenixproject.data.auth

import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class AndroidOAuthAbandonmentWatcherTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Test
    fun `resume without callback cancels after grace period`() = runTest {
        val deferred = CompletableDeferred<String>()
        var cancelCount = 0
        val watcher = AndroidOAuthAbandonmentWatcher(
            deferred = deferred,
            scope = this,
            cancelFlow = { cancelCount++ },
        )

        watcher.onActivityStopped()
        watcher.onActivityResumed()

        advanceTimeBy(CALLBACK_RESUME_GRACE_PERIOD_MS - 1)
        assertEquals(0, cancelCount)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(1, cancelCount)
    }

    @Test
    fun `callback received during grace period does not cancel flow`() = runTest {
        val deferred = CompletableDeferred<String>()
        var cancelCount = 0
        val watcher = AndroidOAuthAbandonmentWatcher(
            deferred = deferred,
            scope = this,
            cancelFlow = { cancelCount++ },
        )

        watcher.onActivityStopped()
        watcher.onActivityResumed()
        advanceTimeBy(CALLBACK_RESUME_GRACE_PERIOD_MS / 2)

        deferred.complete("com.devil.phoenixproject://auth-callback?code=123")
        advanceUntilIdle()
        advanceTimeBy(CALLBACK_RESUME_GRACE_PERIOD_MS)
        advanceUntilIdle()

        assertEquals(0, cancelCount)
    }

    @Test
    fun `subsequent resumes restart the grace timer`() = runTest {
        val deferred = CompletableDeferred<String>()
        var cancelCount = 0
        val watcher = AndroidOAuthAbandonmentWatcher(
            deferred = deferred,
            scope = this,
            cancelFlow = { cancelCount++ },
        )

        watcher.onActivityStopped()
        watcher.onActivityResumed()
        advanceTimeBy(CALLBACK_RESUME_GRACE_PERIOD_MS - 100)

        watcher.onActivityResumed()
        advanceTimeBy(CALLBACK_RESUME_GRACE_PERIOD_MS - 1)
        assertEquals(0, cancelCount)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(1, cancelCount)
    }
}

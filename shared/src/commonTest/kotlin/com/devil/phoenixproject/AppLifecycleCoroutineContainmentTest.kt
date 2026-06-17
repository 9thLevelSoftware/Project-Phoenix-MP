package com.devil.phoenixproject

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Regression test for issue #566: uncaught Kotlin coroutine exception from
 * AppLifecycleObserver aborts the process on foreground (TestFlight 0.9.1
 * SIGABRT after wake on iOS-on-mac).
 *
 * AppLifecycleObserver (App.kt) launches syncTriggerManager.onAppForeground()
 * inside scope.launch { ... } using rememberCoroutineScope() — a plain Job with
 * no SupervisorJob and no CoroutineExceptionHandler. Before the fix the launch
 * body had no try/catch, so any non-CancellationException throwable from the
 * foreground sync chain reached propagateExceptionFinalResort ->
 * processUnhandledException -> terminateWithUnhandledException -> abort().
 *
 * These tests pin the containment pattern the fix introduced in App.kt:
 *  - a non-CancellationException throwable from onAppForeground() (or any
 *    transitive call) is caught and logged and does NOT reach the scope's
 *    CoroutineExceptionHandler — i.e. it would not abort the process;
 *  - CancellationException is rethrown so coroutine cancellation semantics are
 *    preserved (the launched job is cancelled, the handler is still not invoked).
 *
 * The scope is constructed as a standalone root (plain Job +
 * CoroutineExceptionHandler + Dispatchers.Unconfined) to mirror
 * rememberCoroutineScope()'s plain Job and to make CoroutineExceptionHandler
 * observable for root coroutines (handlers are ignored for child coroutines).
 */
class AppLifecycleCoroutineContainmentTest {

    /** A non-CancellationException throwable, standing in for a Ktor/IO failure after wake. */
    private class SimulatedForegroundCrash(message: String) : Exception(message)

    /** Mimics rememberCoroutineScope(): plain Job, no SupervisorJob, plus an observable handler. */
    private fun newLifecycleScope(onUncaught: (Throwable) -> Unit): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable -> onUncaught(throwable) }
        return CoroutineScope(Job() + Dispatchers.Unconfined + handler)
    }

    /** The exact wrapper AppLifecycleObserver applies around onAppForeground(). */
    private suspend fun CoroutineScope.launchForegroundGuarded(body: suspend () -> Unit): Job =
        launch {
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Logger.e(e) { "AppLifecycleObserver: onAppForeground failed" } — swallowed, not propagated.
            }
        }

    @Test
    fun nonCancellationThrowableDoesNotReachUncaughtHandler() = runTest {
        var uncaught: Throwable? = null
        val scope = newLifecycleScope { uncaught = it }

        val job = scope.launchForegroundGuarded {
            throw SimulatedForegroundCrash("simulated network failure after wake")
        }
        job.join()

        assertTrue(job.isCompleted, "foreground launch should complete after a swallowed throwable")
        assertFalse(job.isCancelled, "a swallowed non-Cancellation throwable must not cancel the job")
        assertNull(uncaught, "non-Cancellation throwable must not reach CoroutineExceptionHandler (would abort process)")
    }

    @Test
    fun cancellationExceptionIsRethrownAndCancelsJob() = runTest {
        var uncaught: Throwable? = null
        val scope = newLifecycleScope { uncaught = it }

        val job = scope.launchForegroundGuarded {
            throw CancellationException("lifecycle cancelled")
        }
        job.join()

        assertTrue(job.isCancelled, "CancellationException must be rethrown so the job is cancelled")
        assertNull(uncaught, "CancellationException must not be reported as an uncaught exception")
    }
}

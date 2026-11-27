@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.data.auth

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSOrderedAscending
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

class OAuthLauncherTest {

    @Test
    fun launchWithInvalidUrlFromBackgroundDispatcherCompletesSafely() {
        val launcher = OAuthLauncher()
        var completed = false
        var result: Result<String>? = null

        val job = CoroutineScope(Dispatchers.Default).launch {
            result = launcher.launch("https://example.com/bad url with spaces", "phoenix")
            completed = true
        }

        val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
        while (!completed && NSDate().compare(deadline) == NSOrderedAscending) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }

        job.cancel()
        assertTrue(completed, "OAuthLauncher.launch must complete without hanging")
        val failure = result?.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "Expected IllegalArgumentException, got $failure")
    }

    @Test
    fun launchAndCancelFromBackgroundDispatcherConfinesStartAndCancelToMainThread() {
        val launcher = OAuthLauncher()
        var startCalled = false
        var startOnMain = false
        var cancelCalled = false
        var cancelOnMain = false
        var finished = false
        var failure: Throwable? = null

        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                launcher.launchWithSessionFactory("https://example.com/auth", "phoenix") { _, _, _, _ ->
                    object : WebAuthSessionHandle {
                        override fun start(): Boolean {
                            startCalled = true
                            startOnMain = NSThread.isMainThread
                            return true
                        }

                        override fun cancel() {
                            cancelCalled = true
                            cancelOnMain = NSThread.isMainThread
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished = true
            }
        }

        val startDeadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
        while (!startCalled && NSDate().compare(startDeadline) == NSOrderedAscending) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(startCalled, "Session start must be called")
        assertTrue(startOnMain, "Session start must run on the main thread")

        CoroutineScope(Dispatchers.Default).launch {
            job.cancel()
        }

        val cancelDeadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
        while ((!cancelCalled || !finished) && NSDate().compare(cancelDeadline) == NSOrderedAscending) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }

        assertTrue(cancelCalled, "Session cancel must be called when coroutine is cancelled")
        assertTrue(cancelOnMain, "Session cancel must be dispatched onto the main thread")
        assertTrue(failure is CancellationException, "Cancellation must propagate, got $failure")
    }
}

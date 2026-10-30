package com.devil.phoenixproject.data.auth

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CancellationException] extends [Exception], so [OAuthLauncher.launch] must
 * rethrow it after cancelling the in-flight browser flow. Other failures stay
 * [Result.failure], which is what the login screen shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OAuthLauncherCancellationTest {

    @get:Rule
    val testCoroutineRule = TestCoroutineRule()

    @Before
    fun setUp() {
        AndroidOAuthBridge.cancelFlow()
    }

    @After
    fun tearDown() {
        AndroidOAuthBridge.cancelFlow()
    }

    @Test
    fun `caller cancellation cancels the in-flight flow and rethrows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val launcher = OAuthLauncher(context)
        val launched = async(Dispatchers.Unconfined) {
            launcher.launch(
                "https://example.test/authorize",
                "com.devil.phoenixproject",
            )
        }

        assertFalse(launched.isCompleted, "launch returned before the callback wait")
        assertTrue(AndroidOAuthBridge.hasPendingFlow(), "browser flow was not left waiting")

        val cancellation = CancellationException("route popped")
        launched.cancel(cancellation)
        val thrown = assertFailsWith<CancellationException> { launched.await() }

        assertEquals(CancellationException::class, thrown::class)
        assertEquals(cancellation.message, thrown.message)
        assertFalse(AndroidOAuthBridge.hasPendingFlow(), "cancelled caller left the OAuth flow pending")
    }

    @Test
    fun `browser open failure is still returned as Result failure`() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no browser")
            }

            override fun startActivity(intent: Intent, options: Bundle?) {
                throw ActivityNotFoundException("no browser")
            }
        }
        val launcher = OAuthLauncher(context)

        val result = launcher.launch(
            "https://example.test/authorize",
            "com.devil.phoenixproject",
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<ActivityNotFoundException>(error)
        assertEquals("no browser", error.message)
        assertFalse(AndroidOAuthBridge.hasPendingFlow())
    }
}

package com.devil.phoenixproject.auth

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.data.auth.OAuthCancelledException
import com.devil.phoenixproject.data.auth.OAuthLauncher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OAuthRedirectActivityTest {

    @Test
    fun `redirect intent with callback URI completes OAuth flow and routes back to MainActivity`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val launcher = OAuthLauncher(app)
        val callbackUrl = "com.devil.phoenixproject://auth-callback?code=auth-code-123&state=xyz"

        val resultDeferred = async {
            launcher.launch("https://example.supabase.co/auth/v1/authorize", "com.devil.phoenixproject")
        }
        testScheduler.runCurrent()

        val controller = Robolectric.buildActivity(
            OAuthRedirectActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse(callbackUrl)),
        ).create()
        val activity = controller.get()

        val launchResult = resultDeferred.await()
        assertEquals(Result.success(callbackUrl), launchResult)
        assertTrue(activity.isFinishing)
        assertRoutedBackToMainActivity(app)
    }

    @Test
    fun `redirect intent without data cancels OAuth flow and still routes back to MainActivity`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val launcher = OAuthLauncher(app)

        val resultDeferred = async {
            launcher.launch("https://example.supabase.co/auth/v1/authorize", "com.devil.phoenixproject")
        }
        testScheduler.runCurrent()

        val controller = Robolectric.buildActivity(
            OAuthRedirectActivity::class.java,
            Intent(Intent.ACTION_VIEW),
        ).create()
        val activity = controller.get()

        val launchResult = resultDeferred.await()
        assertTrue(launchResult.isFailure)
        assertTrue(launchResult.exceptionOrNull() is OAuthCancelledException)
        assertTrue(activity.isFinishing)
        assertRoutedBackToMainActivity(app)
    }

    @Test
    fun `onNewIntent delivers new callback URI and finishes activity`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val launcher = OAuthLauncher(app)

        val firstDeferred = async {
            launcher.launch("https://example.supabase.co/auth/v1/authorize", "com.devil.phoenixproject")
        }
        testScheduler.runCurrent()

        val controller = Robolectric.buildActivity(
            OAuthRedirectActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("com.devil.phoenixproject://auth-callback?code=first")),
        ).create()
        assertEquals("com.devil.phoenixproject://auth-callback?code=first", firstDeferred.await().getOrThrow())

        // Consume the MainActivity intent from onCreate before testing onNewIntent.
        Shadows.shadowOf(app).nextStartedActivity

        val secondDeferred = async {
            launcher.launch("https://example.supabase.co/auth/v1/authorize", "com.devil.phoenixproject")
        }
        testScheduler.runCurrent()

        val secondUrl = "com.devil.phoenixproject://auth-callback?code=second"
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(secondUrl)))

        assertEquals(secondUrl, secondDeferred.await().getOrThrow())
        assertTrue(controller.get().isFinishing)
        assertRoutedBackToMainActivity(app)
    }

    @Test
    fun `manifest configures OAuthRedirectActivity as singleTask with empty taskAffinity`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mainInfo = app.packageManager.getActivityInfo(
            ComponentName(app, MainActivity::class.java),
            0,
        )
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, OAuthRedirectActivity::class.java),
            0,
        )

        assertTrue(info.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals(app.packageName, mainInfo.taskAffinity)
        // Android/Robolectric represents android:taskAffinity="" as null (cleared vs app.packageName).
        assertTrue(
            "Expected OAuthRedirectActivity taskAffinity to be empty/null, got ${info.taskAffinity}",
            info.taskAffinity.isNullOrEmpty(),
        )
        assertTrue((info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
    }

    private fun assertRoutedBackToMainActivity(app: Application) {
        val shadowApp = Shadows.shadowOf(app)
        var started: Intent? = null
        while (true) {
            val next = shadowApp.nextStartedActivity ?: break
            if (next.component?.className == MainActivity::class.java.name) {
                started = next
            }
        }
        val mainIntent = started
        assertNotNull("Expected OAuthRedirectActivity to route back to MainActivity", mainIntent)
        assertEquals(Intent.ACTION_MAIN, mainIntent!!.action)
        assertTrue(mainIntent.categories.contains(Intent.CATEGORY_LAUNCHER))
        val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(expectedFlags, mainIntent.flags and expectedFlags)
    }
}

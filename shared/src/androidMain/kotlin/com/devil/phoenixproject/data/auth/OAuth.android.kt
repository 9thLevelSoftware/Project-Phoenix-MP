package com.devil.phoenixproject.data.auth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import java.security.MessageDigest
import java.security.SecureRandom

private val log = Logger.withTag("OAuthLauncher")

internal actual fun generateSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return bytes
}

internal actual fun sha256(input: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(input)

/**
 * Android OAuth launcher. Opens the authorize URL in whatever browser the
 * user has set as default (via ACTION_VIEW). The browser-side redirect is
 * captured by [OAuthRedirectActivity] in the host app (registered against
 * the callback URI scheme in its AndroidManifest) and delivered back here
 * via [AndroidOAuthBridge].
 *
 * If the user dismisses the browser without completing the flow (Back / Home
 * / kill the tab), the host activity will resume without a callback ever
 * being delivered. An [Application.ActivityLifecycleCallbacks] listener
 * detects that case and cancels the deferred so [launch] doesn't hang.
 */
actual class OAuthLauncher(private val context: Context) {

    actual suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String> {
        val deferred = AndroidOAuthBridge.beginFlow()
        val application = context.applicationContext as? Application
            ?: return Result.failure(
                IllegalStateException("OAuth requires an Application context"),
            )

        // Watch the activity lifecycle so we can detect the user backing out
        // of the browser without completing OAuth. We set hostStopped = true
        // when our app goes to the background (browser opens), then if any
        // activity in our app resumes again without the deferred being
        // completed, we know the user dismissed the browser.
        //
        // Successful OAuth: OAuthRedirectActivity.onCreate runs deliverCallback
        // before its onResume fires, so the deferred is already completed by
        // the time the listener sees a resume → no cancellation.
        var hostStopped = false
        val abandonmentWatcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                hostStopped = true
            }
            override fun onActivityResumed(activity: Activity) {
                if (hostStopped && !deferred.isCompleted) {
                    AndroidOAuthBridge.cancelFlow()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(abandonmentWatcher)

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val callbackUrl = deferred.await()
            Result.success(callbackUrl)
        } catch (e: Exception) {
            log.w(e) { "OAuth flow ended: ${e.message}" }
            AndroidOAuthBridge.cancelFlow()
            Result.failure(e)
        } finally {
            application.unregisterActivityLifecycleCallbacks(abandonmentWatcher)
        }
    }
}

/**
 * Shared handoff between [OAuthLauncher] (waiting for a callback) and the
 * host app's redirect activity (which receives the callback URL from the
 * system browser via intent filter).
 *
 * At most one flow is in flight at a time; a new [beginFlow] call while an
 * older one is still pending will fail the older one (treated as cancelled).
 */
object AndroidOAuthBridge {
    private var pending: CompletableDeferred<String>? = null

    @Synchronized
    internal fun beginFlow(): CompletableDeferred<String> {
        pending?.let {
            if (!it.isCompleted) {
                it.completeExceptionally(OAuthCancelledException("Superseded by a new OAuth flow"))
            }
        }
        val deferred = CompletableDeferred<String>()
        pending = deferred
        return deferred
    }

    /**
     * Called by the host app's redirect activity when the browser hands the
     * OAuth callback URL back to the app. [url] is the full callback URI
     * (scheme://host/path?code=...).
     */
    @Synchronized
    fun deliverCallback(url: String) {
        pending?.complete(url)
        pending = null
    }

    /**
     * Called by the host app's redirect activity when the browser hands back
     * an intent with no usable URI (e.g., the OS launched us without a
     * payload). The launcher's pending suspend must still complete, so
     * surface this as a cancellation.
     *
     * Public so the redirect activity in `androidApp` (separate Gradle
     * module) can invoke it; the `internal` `OAuthLauncher` use sites stay
     * in this module.
     */
    @Synchronized
    fun cancelFlow() {
        pending?.let {
            if (!it.isCompleted) {
                it.completeExceptionally(OAuthCancelledException("OAuth flow cancelled"))
            }
        }
        pending = null
    }
}


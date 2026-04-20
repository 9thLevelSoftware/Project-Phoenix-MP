package com.devil.phoenixproject.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 */
actual class OAuthLauncher(private val context: Context) {

    actual suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String> {
        val deferred = AndroidOAuthBridge.beginFlow()
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val callbackUrl = deferred.await()
            Result.success(callbackUrl)
        } catch (e: Exception) {
            log.w(e) { "OAuth browser launch failed" }
            AndroidOAuthBridge.cancelFlow()
            Result.failure(e)
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

    @Synchronized
    internal fun cancelFlow() {
        pending?.let {
            if (!it.isCompleted) {
                it.completeExceptionally(OAuthCancelledException("OAuth flow cancelled"))
            }
        }
        pending = null
    }
}


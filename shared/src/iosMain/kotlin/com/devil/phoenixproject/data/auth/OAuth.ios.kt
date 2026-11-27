package com.devil.phoenixproject.data.auth

import com.devil.phoenixproject.util.keyWindow
import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Domain string for NSErrors emitted by ASWebAuthenticationSession. Kotlin/
 * Native does not always re-export Apple's `ASWebAuthenticationSessionErrorDomain`
 * extern as an importable symbol, so we hardcode the public string here.
 * Source: Apple AuthenticationServices framework headers.
 */
private const val ASWEB_AUTH_SESSION_ERROR_DOMAIN =
    "com.apple.AuthenticationServices.WebAuthenticationSession"

@OptIn(ExperimentalForeignApi::class)
internal actual fun generateSecureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative, was $size" }
    // pinned.addressOf(0) is invalid for an empty array, so short-circuit.
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status=$status" }
    }
    return bytes
}

internal interface WebAuthSessionHandle {
    fun start(): Boolean
    fun cancel()
}

private fun runOnMainQueue(block: () -> Unit) {
    if (NSThread.isMainThread) {
        block()
    } else {
        dispatch_async(dispatch_get_main_queue(), block)
    }
}

@OptIn(BetaInteropApi::class)
private fun createPlatformWebAuthSession(
    url: NSURL,
    callbackScheme: String,
    provider: ASWebAuthenticationPresentationContextProvidingProtocol,
    completionHandler: (NSURL?, NSError?) -> Unit,
): WebAuthSessionHandle {
    val webSession = ASWebAuthenticationSession(
        uRL = url,
        callbackURLScheme = callbackScheme,
        completionHandler = completionHandler,
    )
    webSession.presentationContextProvider = provider
    // Share Safari cookies so already-signed-in Google/Apple users
    // don't have to type credentials every time.
    webSession.prefersEphemeralWebBrowserSession = false
    return object : WebAuthSessionHandle {
        override fun start(): Boolean = webSession.start()
        override fun cancel() = webSession.cancel()
    }
}

private fun parseValidAuthorizeUrl(authorizeUrl: String): NSURL? {
    if (authorizeUrl.isBlank()) return null
    if (authorizeUrl.any { it.isWhitespace() || it.isISOControl() }) return null
    val url = NSURL.URLWithString(authorizeUrl) ?: return null
    val scheme = url.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (url.host.isNullOrBlank()) return null
    return url
}

/**
 * iOS OAuth launcher using Apple's [ASWebAuthenticationSession].
 *
 * ASWebAuthenticationSession is the Apple-recommended API for OAuth-style
 * flows in native apps: it presents a system-owned browser sheet, shares
 * cookies with Safari for SSO, and captures the callback URL in-process.
 * The host iOS app must still register the callback scheme in Info.plist
 * URL Types so the system can route the redirect back to this session.
 */
actual class OAuthLauncher {
    private var session: WebAuthSessionHandle? = null
    private var presentationProvider: PresentationContextProvider? = null

    actual suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String> =
        launchWithSessionFactory(authorizeUrl, callbackScheme) { url, scheme, provider, completion ->
            createPlatformWebAuthSession(url, scheme, provider, completion)
        }

    internal suspend fun launchWithSessionFactory(
        authorizeUrl: String,
        callbackScheme: String,
        sessionFactory: (
            NSURL,
            String,
            ASWebAuthenticationPresentationContextProvidingProtocol,
            (NSURL?, NSError?) -> Unit,
        ) -> WebAuthSessionHandle,
    ): Result<String> = withContext(Dispatchers.Main.immediate) {
        val url = parseValidAuthorizeUrl(authorizeUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid authorize URL: $authorizeUrl"))

        suspendCancellableCoroutine { cont ->
            val provider = PresentationContextProvider()
            lateinit var webSession: WebAuthSessionHandle
            webSession = sessionFactory(url, callbackScheme, provider) { callbackURL: NSURL?, error: NSError? ->
                runOnMainQueue {
                    if (session === webSession) {
                        session = null
                        presentationProvider = null
                    }
                }
                val result: Result<String> = when {
                    callbackURL != null -> {
                        val urlString = callbackURL.absoluteString
                        if (urlString != null) {
                            Result.success(urlString)
                        } else {
                            Result.failure(Exception("OAuth callback URL had no absoluteString"))
                        }
                    }

                    error != null -> {
                        val message = error.localizedDescription
                        // ASWebAuthenticationSessionErrorCodeCanceledLogin = 1 inside the
                        // ASWebAuthenticationSessionErrorDomain when the user dismisses
                        // the sheet. We gate on BOTH so we don't misclassify a code=1
                        // error from an unrelated NSError domain as a user cancellation.
                        // The domain string literal mirrors Apple's public extern symbol;
                        // we hardcode it because Kotlin/Native does not always expose the
                        // ASWebAuthenticationSessionErrorDomain constant as importable.
                        val isCancel = error.code == 1L &&
                            error.domain == ASWEB_AUTH_SESSION_ERROR_DOMAIN
                        if (isCancel) {
                            Result.failure(OAuthCancelledException(message))
                        } else {
                            Result.failure(Exception(message))
                        }
                    }

                    else -> Result.failure(Exception("Unknown OAuth error"))
                }
                if (cont.isActive) cont.resume(result)
            }

            session = webSession
            presentationProvider = provider

            cont.invokeOnCancellation {
                runOnMainQueue {
                    webSession.cancel()
                    if (session === webSession) {
                        session = null
                        presentationProvider = null
                    }
                }
            }

            val started = webSession.start()
            if (!started) {
                session = null
                presentationProvider = null
                if (cont.isActive) {
                    cont.resume(Result.failure(Exception("ASWebAuthenticationSession failed to start")))
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PresentationContextProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {
    // Return type is ASPresentationAnchor in ObjC, a typealias for UIWindow;
    // returning UIWindow avoids depending on whether K/N exposes the typealias.
    //
    // Uses the modern `UIApplication.connectedScenes → UIWindowScene.keyWindow`
    // pattern (see `KeyWindowRootViewController.ios.kt` for the same pattern in this project)
    // instead of the deprecated `UIApplication.windows` API, which has had
    // spotty Kotlin/Native interop support across 2.x versions.
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow {
        return keyWindow() ?: UIWindow()
    }
}

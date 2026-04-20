package com.devil.phoenixproject.data.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
internal actual fun generateSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status=$status" }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(input: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    if (input.isEmpty()) {
        digest.usePinned { digestPinned ->
            CC_SHA256(null, 0u, digestPinned.addressOf(0).reinterpret())
        }
        return digest
    }
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                inputPinned.addressOf(0),
                input.size.convert(),
                digestPinned.addressOf(0).reinterpret(),
            )
        }
    }
    return digest
}

/**
 * iOS OAuth launcher using Apple's [ASWebAuthenticationSession].
 *
 * ASWebAuthenticationSession is the Apple-recommended API for OAuth-style
 * flows in native apps: it presents a system-owned browser sheet, shares
 * cookies with Safari for SSO, and captures the callback URL in-process
 * without needing an Info.plist URL scheme registration.
 */
actual class OAuthLauncher {
    private var session: ASWebAuthenticationSession? = null
    private var presentationProvider: PresentationContextProvider? = null

    @OptIn(BetaInteropApi::class)
    actual suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            val url = NSURL.URLWithString(authorizeUrl)
            if (url == null) {
                cont.resume(Result.failure(IllegalArgumentException("Invalid authorize URL: $authorizeUrl")))
                return@suspendCancellableCoroutine
            }

            val provider = PresentationContextProvider()
            val webSession = ASWebAuthenticationSession(
                uRL = url,
                callbackURLScheme = callbackScheme,
                completionHandler = { callbackURL: NSURL?, error: NSError? ->
                    session = null
                    presentationProvider = null
                    when {
                        error != null -> {
                            val result = if (error.code == ASWebAuthenticationSessionErrorCodeCanceledLogin) {
                                Result.failure(OAuthCancelledException("User cancelled OAuth flow"))
                            } else {
                                Result.failure(Exception(error.localizedDescription))
                            }
                            if (cont.isActive) cont.resume(result)
                        }
                        callbackURL != null -> {
                            val urlString = callbackURL.absoluteString ?: ""
                            if (cont.isActive) cont.resume(Result.success(urlString))
                        }
                        else -> {
                            if (cont.isActive) cont.resume(Result.failure(Exception("Unknown OAuth error")))
                        }
                    }
                },
            )
            webSession.presentationContextProvider = provider
            // Share Safari cookies so already-signed-in Google/Apple users
            // don't have to type credentials every time.
            webSession.prefersEphemeralWebBrowserSession = false

            session = webSession
            presentationProvider = provider

            cont.invokeOnCancellation {
                webSession.cancel()
                session = null
                presentationProvider = null
            }

            val started = webSession.start()
            if (!started) {
                cont.resume(Result.failure(Exception("ASWebAuthenticationSession failed to start")))
            }
        }
}

private class PresentationContextProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor {
        val app = UIApplication.sharedApplication
        val keyWindow = app.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow == true } as? UIWindow
        return keyWindow
            ?: app.windows.firstOrNull() as? UIWindow
            ?: UIWindow()
    }
}

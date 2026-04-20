package com.devil.phoenixproject.data.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.CommonCrypto.CC_SHA256
import platform.CommonCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume

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
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status=$status" }
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(input: ByteArray): ByteArray {
    // CC_SHA256_DIGEST_LENGTH comes from CommonCrypto as Long in Kotlin/Native
    // 2.3.x platform libs; ByteArray(size: Int) needs the explicit narrowing.
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
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
                session = null
                presentationProvider = null
                cont.resume(Result.failure(Exception("ASWebAuthenticationSession failed to start")))
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
    // pattern (see `CsvExporter.ios.kt` for the same pattern in this project)
    // instead of the deprecated `UIApplication.windows` API, which has had
    // spotty Kotlin/Native interop support across 2.x versions.
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
        return windowScene?.keyWindow ?: UIWindow()
    }
}

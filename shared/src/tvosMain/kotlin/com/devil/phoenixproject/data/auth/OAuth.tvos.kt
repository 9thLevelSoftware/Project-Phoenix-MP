package com.devil.phoenixproject.data.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun generateSecureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "size must be non-negative, was $size" }
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status=$status" }
    }
    return bytes
}

/** OAuth browser sessions are intentionally not offered in the Apple TV sign-in flow. */
actual class OAuthLauncher {
    actual suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String> =
        Result.failure(UnsupportedOperationException("OAuth browser sign-in is unavailable on tvOS; use email and password."))
}

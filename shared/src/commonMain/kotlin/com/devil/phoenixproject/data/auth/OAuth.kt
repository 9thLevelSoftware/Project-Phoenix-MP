package com.devil.phoenixproject.data.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * OAuth providers supported by the mobile sign-in flow.
 *
 * - [wireName] matches the value passed to Supabase GoTrue's
 *   `/auth/v1/authorize?provider=` endpoint.
 * - [scopes] is a space-separated scope list passed via `&scopes=`.
 *   Mirrors what `phoenix-portal` requests so that a portal user can
 *   sign in on mobile with the same identity. See the Supabase
 *   troubleshooting note on Google Workspace users:
 *   https://supabase.com/docs/guides/troubleshooting/google-auth-fails-for-some-users-XcFXEu
 *
 * Sign-in only: account creation via OAuth happens on the portal web app.
 * The mobile app reuses the existing portal account; it never creates new
 * users through this flow.
 */
enum class OAuthProvider(val wireName: String, val scopes: String) {
    GOOGLE(
        wireName = "google",
        // Explicit `userinfo.email` is required so Workspace tenants with
        // restrictive default scopes still send the email back to Supabase,
        // which uses it to join with existing portal accounts. Without this,
        // a portal user created via Google can hit "invalid credentials" on
        // mobile because GoTrue can't link the session to their portal row.
        scopes = "openid email profile https://www.googleapis.com/auth/userinfo.email",
    ),
    APPLE(
        wireName = "apple",
        // Standard Sign in with Apple scope set; matches what Supabase
        // expects for Apple OAuth so the email claim is included.
        scopes = "name email",
    ),
}

/** A PKCE verifier/challenge pair per RFC 7636. */
data class OAuthPkce(val verifier: String, val challenge: String)

/** Platform-backed secure random. Hash is implemented in common code below. */
internal expect fun generateSecureRandomBytes(size: Int): ByteArray

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4). Used only for the PKCE S256 challenge,
 * which hashes a short verifier string — performance is irrelevant and the
 * output is not a secret. Avoiding `platform.CommonCrypto` keeps the iOS
 * build green across Kotlin/Native platform-library regressions (the K/N
 * 2.3.20 Apple platform libs do not expose `platform.CommonCrypto`).
 */
internal fun sha256(input: ByteArray): ByteArray {
    val k = intArrayOf(
        0x428a2f98.toInt(), 0x71374491, -0x4a3f0431, -0x164a245b,
        0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039,
        -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d,
        -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8,
        -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
    val h = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
        0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLen = input.size.toLong() * 8L
    val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
    val padded = ByteArray(input.size + 1 + padLen + 8)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 1 - i] = (bitLen ushr (i * 8)).toByte()
    }

    val w = IntArray(64)
    var chunkStart = 0
    while (chunkStart < padded.size) {
        for (i in 0 until 16) {
            val j = chunkStart + i * 4
            w[i] = (padded[j].toInt() and 0xff shl 24) or
                (padded[j + 1].toInt() and 0xff shl 16) or
                (padded[j + 2].toInt() and 0xff shl 8) or
                (padded[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        chunkStart += 64
    }

    val out = ByteArray(32)
    for (i in 0 until 8) {
        out[i * 4] = (h[i] ushr 24).toByte()
        out[i * 4 + 1] = (h[i] ushr 16).toByte()
        out[i * 4 + 2] = (h[i] ushr 8).toByte()
        out[i * 4 + 3] = h[i].toByte()
    }
    return out
}

/**
 * Base64URL encoding without padding (RFC 4648 §5). PKCE requires the `=`
 * padding characters to be stripped.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toBase64UrlNoPad(): String =
    Base64.UrlSafe.encode(this).trimEnd('=')

/**
 * Generate a PKCE verifier + S256 challenge.
 *
 * - Verifier: 32 random bytes, base64url-encoded (43 chars, no padding)
 * - Challenge: SHA-256(verifier ASCII) base64url-encoded (43 chars, no padding)
 */
fun generateOAuthPkce(): OAuthPkce {
    val verifier = generateSecureRandomBytes(32).toBase64UrlNoPad()
    val challenge = sha256(verifier.encodeToByteArray()).toBase64UrlNoPad()
    return OAuthPkce(verifier = verifier, challenge = challenge)
}

/**
 * Generate a cryptographically random `state` value for the OAuth authorize
 * request. The caller stores the value, includes it in the authorize URL,
 * and after the redirect verifies that the callback echoes the same value.
 * Mismatch indicates a CSRF / authorization-code-substitution attempt.
 *
 * 16 bytes → 22 base64url chars; well above the OAuth 2.0 §10.10 entropy
 * recommendation of 128 bits.
 */
fun generateOAuthState(): String = generateSecureRandomBytes(16).toBase64UrlNoPad()

/**
 * Platform-specific browser launcher for OAuth authorization flows.
 *
 * Opens [authorizeUrl] in the system browser (Chrome Custom Tabs on Android,
 * ASWebAuthenticationSession on iOS) and suspends until the browser redirects
 * to a URL whose scheme matches [callbackScheme].
 *
 * @return [Result.success] with the full callback URL (including any query
 *         string carrying the OAuth `code` or error) on successful redirect;
 *         [Result.failure] if the user cancelled or the browser could not be
 *         opened.
 */
expect class OAuthLauncher {
    suspend fun launch(authorizeUrl: String, callbackScheme: String): Result<String>
}

/** Thrown by [OAuthLauncher.launch] when the user cancels the browser flow. */
class OAuthCancelledException(message: String) : RuntimeException(message)

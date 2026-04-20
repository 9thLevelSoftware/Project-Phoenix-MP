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

/** Platform-backed cryptographic primitives for PKCE. Implemented per-target. */
internal expect fun generateSecureRandomBytes(size: Int): ByteArray
internal expect fun sha256(input: ByteArray): ByteArray

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

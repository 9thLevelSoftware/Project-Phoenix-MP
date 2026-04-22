package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.auth.OAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalAuthRepositoryOAuthCallbackTest {

    @Test
    fun `extractOAuthCallbackParam reads query parameters`() {
        val callbackUrl =
            "com.devil.phoenixproject://auth-callback?code=auth-code-123&state=state-123"

        assertEquals("auth-code-123", extractOAuthCallbackParam(callbackUrl, "code"))
        assertEquals("state-123", extractOAuthCallbackParam(callbackUrl, "state"))
    }

    @Test
    fun `extractOAuthCallbackParam reads fragment parameters`() {
        val callbackUrl =
            "com.devil.phoenixproject://auth-callback#error=access_denied&state=state-123"

        assertEquals("access_denied", extractOAuthCallbackParam(callbackUrl, "error"))
        assertEquals("state-123", extractOAuthCallbackParam(callbackUrl, "state"))
    }

    @Test
    fun `extractOAuthCallbackParam decodes percent encoded fragment values`() {
        val callbackUrl =
            "com.devil.phoenixproject://auth-callback#error_description=OAuth%20flow%20cancelled"

        assertEquals(
            "OAuth flow cancelled",
            extractOAuthCallbackParam(callbackUrl, "error_description"),
        )
    }

    @Test
    fun `isExpectedOAuthCallbackUrl accepts callback with trailing slash`() {
        val callbackUrl = "com.devil.phoenixproject://auth-callback/?code=auth-code-123"

        assertTrue(
            isExpectedOAuthCallbackUrl(
                callbackUrl = callbackUrl,
                expectedScheme = PortalAuthRepository.OAUTH_CALLBACK_SCHEME,
            ),
        )
    }

    @Test
    fun `isExpectedOAuthCallbackUrl rejects unexpected host`() {
        val callbackUrl = "com.devil.phoenixproject://wrong-host?code=auth-code-123"

        assertFalse(
            isExpectedOAuthCallbackUrl(
                callbackUrl = callbackUrl,
                expectedScheme = PortalAuthRepository.OAUTH_CALLBACK_SCHEME,
            ),
        )
    }

    @Test
    fun `extractOAuthCallbackParam returns null when key is absent`() {
        val callbackUrl = "com.devil.phoenixproject://auth-callback?code=auth-code-123"

        assertNull(extractOAuthCallbackParam(callbackUrl, "state"))
    }

    @Test
    fun `buildOAuthAuthorizeUrl omits client managed state`() {
        val authorizeUrl = buildOAuthAuthorizeUrl(
            authUrl = "https://example.supabase.co/auth/v1",
            provider = OAuthProvider.APPLE,
            codeChallenge = "challenge-123",
            redirectUrl = PortalAuthRepository.OAUTH_CALLBACK_URL,
        )

        assertTrue(authorizeUrl.contains("provider=apple"))
        assertTrue(
            authorizeUrl.contains(
                "redirect_to=com.devil.phoenixproject%3A%2F%2Fauth-callback",
            ),
        )
        assertFalse(authorizeUrl.contains("state="))
    }
}

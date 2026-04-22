package com.devil.phoenixproject.data.repository

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
}

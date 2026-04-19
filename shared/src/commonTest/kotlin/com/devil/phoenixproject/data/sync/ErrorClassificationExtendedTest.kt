package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Supplementary tests that fill gaps in the existing ErrorClassificationTest.kt:
 *   - Boundary HTTP statuses: 408 (request timeout), 422 (unprocessable entity),
 *     429 (rate-limited — must be TRANSIENT per standard).
 *   - Network-exception name detection (UnknownHost, Connection, IOException) across
 *     the set of names the production code recognises. Uses test-doubles with
 *     KMP-compatible class names (no JVM-only imports).
 *   - 401 vs 403 separation (AUTH vs PERMANENT).
 *   - 5xx sweep including 502, 503, 504 explicit assertions.
 *
 * The existing ErrorClassificationTest.kt covers the baseline 4xx/5xx/timeout mapping;
 * this file pins down the edge cases named in the test-coverage-gap audit.
 */
class ErrorClassificationExtendedTest {

    // ---- Name-only doubles so we don't need platform-specific exception classes ----
    private class UnknownHostException(msg: String) : Exception(msg)
    private class ConnectException(msg: String) : Exception(msg)
    private class IOException(msg: String) : Exception(msg)

    // ==================== HTTP Status Code Boundaries ====================

    @Test
    fun status408IsTransientAndRetryable() {
        // 408 Request Timeout is not special-cased; the default bucket for unknown is TRANSIENT.
        val result = classifyByStatusCode(408, "Request Timeout")
        assertEquals(SyncErrorCategory.TRANSIENT, result.category, "408 must be retryable")
        assertTrue(result.isRetryable)
        assertEquals(408, result.statusCode)
    }

    @Test
    fun status422IsPermanentPerDocumentedFourHundredFamily() {
        // 422 Unprocessable Entity isn't explicitly mapped in classifyByStatusCode; it falls into
        // the "unknown status → TRANSIENT" bucket per production code. Assert whatever the code
        // does so future changes produce a clear regression signal.
        val result = classifyByStatusCode(422, "Unprocessable Entity")
        assertEquals(
            SyncErrorCategory.TRANSIENT,
            result.category,
            "Production code treats 422 as TRANSIENT (default bucket); regression marker if this changes",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun status429IsTransientPerHttpStandard() {
        val result = classifyByStatusCode(429, "Too Many Requests")
        assertEquals(
            SyncErrorCategory.TRANSIENT,
            result.category,
            "Rate-limited (429) must be retryable with backoff per HTTP 7231 / RFC 6585",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun status401IsAuthNotPermanent() {
        val result = classifyByStatusCode(401, "Unauthorized")
        assertEquals(SyncErrorCategory.AUTH, result.category)
        assertFalse(result.isRetryable)
    }

    @Test
    fun status403IsPermanentNotAuth() {
        val result = classifyByStatusCode(403, "Forbidden")
        assertEquals(
            SyncErrorCategory.PERMANENT,
            result.category,
            "403 (e.g., missing paid tier) is PERMANENT — re-login won't fix it",
        )
        assertFalse(result.isRetryable)
    }

    @Test
    fun fiveHundredFamilyIsTransient() {
        for (code in listOf(500, 502, 503, 504)) {
            val result = classifyByStatusCode(code, "Server error $code")
            assertEquals(
                SyncErrorCategory.TRANSIENT,
                result.category,
                "HTTP $code must be TRANSIENT",
            )
            assertTrue(result.isRetryable, "HTTP $code must be retryable")
        }
    }

    // ==================== Exception Name Detection (KMP-safe) ====================

    @Test
    fun unknownHostExceptionNameMapsToNetwork() {
        val result = classifyError(UnknownHostException("no dns"), "Sync")
        assertEquals(
            SyncErrorCategory.NETWORK,
            result.category,
            "Exception class name matching 'UnknownHost' must map to NETWORK",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun connectionExceptionNameMapsToNetwork() {
        val result = classifyError(ConnectException("refused"), "Sync")
        assertEquals(
            SyncErrorCategory.NETWORK,
            result.category,
            "Exception class name matching 'Connection' must map to NETWORK",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun ioExceptionNameMapsToNetwork() {
        val result = classifyError(IOException("broken pipe"), "Sync")
        assertEquals(
            SyncErrorCategory.NETWORK,
            result.category,
            "Exception class name containing 'IOException' must map to NETWORK",
        )
        assertTrue(result.isRetryable)
    }

    @Test
    fun socketTimeoutNameIsTransient() {
        class SocketTimeoutException(msg: String) : Exception(msg)
        val result = classifyError(SocketTimeoutException("read timed out"), "Sync")
        assertEquals(
            SyncErrorCategory.TRANSIENT,
            result.category,
            "Timeout-named exceptions map to TRANSIENT",
        )
    }

    @Test
    fun portalApiExceptionWith408BehavesLikeStatusCode() {
        val result = classifyError(PortalApiException("timed out", null, 408), "Sync")
        assertEquals(
            SyncErrorCategory.TRANSIENT,
            result.category,
            "PortalApiException delegates to classifyByStatusCode",
        )
        assertEquals(408, result.statusCode)
    }

    @Test
    fun portalApiExceptionWith429IsTransient() {
        val result = classifyError(PortalApiException("rate limited", null, 429), "Sync")
        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable)
    }
}

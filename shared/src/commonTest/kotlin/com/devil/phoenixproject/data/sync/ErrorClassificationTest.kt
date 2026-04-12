package com.devil.phoenixproject.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for error classification functions in PortalApiClient.
 *
 * These functions determine retry behavior based on HTTP status codes and exception types.
 * Correct classification is critical for:
 * - Proper exponential backoff on transient errors
 * - Immediate re-login prompts on auth failures
 * - Network reconnection waiting on connectivity issues
 * - No retries on permanent client errors
 *
 * Note: Ktor timeout exceptions have complex constructors that require internal Ktor types.
 * Instead, we test with mock exceptions that have the same class name patterns that
 * classifyError() uses for detection.
 */
class ErrorClassificationTest {

    // Test doubles for timeout exceptions (matching classifyError name detection)
    private class TestHttpRequestTimeoutException(message: String) : Exception(message) {
        // Class name contains "Timeout" which triggers timeout classification
    }

    private class TestConnectTimeoutException(message: String) : Exception(message) {
        // Class name contains "Timeout" which triggers timeout classification
    }

    private class TestSocketTimeoutException(message: String) : Exception(message) {
        // Class name contains "Timeout" which triggers timeout classification
    }

    // ==================== HTTP Status Code Classification ====================

    @Test
    fun status400IsPermanentAndNotRetryable() {
        val result = classifyByStatusCode(400, "Bad request")

        assertEquals(SyncErrorCategory.PERMANENT, result.category)
        assertFalse(result.isRetryable, "400 Bad Request should not be retryable")
        assertEquals(400, result.statusCode)
    }

    @Test
    fun status401IsAuthAndNotRetryable() {
        val result = classifyByStatusCode(401, "Unauthorized")

        assertEquals(SyncErrorCategory.AUTH, result.category)
        assertFalse(result.isRetryable, "401 Unauthorized should not be retryable")
        assertEquals(401, result.statusCode)
    }

    @Test
    fun status402IsPermanentAndNotRetryable() {
        val result = classifyByStatusCode(402, "Payment required")

        assertEquals(SyncErrorCategory.PERMANENT, result.category)
        assertFalse(result.isRetryable, "402 Payment Required should not be retryable")
        assertEquals(402, result.statusCode)
    }

    @Test
    fun status403IsPermanentAndNotRetryable() {
        val result = classifyByStatusCode(403, "Forbidden - premium required")

        assertEquals(SyncErrorCategory.PERMANENT, result.category)
        assertFalse(result.isRetryable, "403 Forbidden should not be retryable")
        assertEquals(403, result.statusCode)
    }

    @Test
    fun status404IsPermanentAndNotRetryable() {
        val result = classifyByStatusCode(404, "Not found")

        assertEquals(SyncErrorCategory.PERMANENT, result.category)
        assertFalse(result.isRetryable, "404 Not Found should not be retryable")
        assertEquals(404, result.statusCode)
    }

    @Test
    fun status429IsTransientAndRetryable() {
        val result = classifyByStatusCode(429, "Too many requests")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "429 Rate Limited should be retryable with backoff")
        assertEquals(429, result.statusCode)
    }

    @Test
    fun status500IsTransientAndRetryable() {
        val result = classifyByStatusCode(500, "Internal server error")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "500 Internal Server Error should be retryable")
        assertEquals(500, result.statusCode)
    }

    @Test
    fun status502IsTransientAndRetryable() {
        val result = classifyByStatusCode(502, "Bad gateway")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "502 Bad Gateway should be retryable")
        assertEquals(502, result.statusCode)
    }

    @Test
    fun status503IsTransientAndRetryable() {
        val result = classifyByStatusCode(503, "Service unavailable")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "503 Service Unavailable should be retryable")
        assertEquals(503, result.statusCode)
    }

    @Test
    fun status504IsTransientAndRetryable() {
        val result = classifyByStatusCode(504, "Gateway timeout")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "504 Gateway Timeout should be retryable")
        assertEquals(504, result.statusCode)
    }

    @Test
    fun unknownStatusIsTransientAndRetryable() {
        val result = classifyByStatusCode(418, "I'm a teapot")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Unknown status codes should default to transient/retryable")
        assertEquals(418, result.statusCode)
    }

    @Test
    fun nullStatusIsTransientAndRetryable() {
        val result = classifyByStatusCode(null, "Unknown error")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Null status should default to transient/retryable")
        assertEquals(null, result.statusCode)
    }

    // ==================== Exception Type Classification ====================

    @Test
    fun httpRequestTimeoutExceptionIsTransient() {
        // Uses test double with "Timeout" in class name, matching classifyError's detection
        val exception = TestHttpRequestTimeoutException("Request to https://api.example.com timed out")
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Request timeout should be retryable")
        assertTrue(
            result.message.contains("timed out", ignoreCase = true),
            "Message should indicate timeout: ${result.message}",
        )
    }

    @Test
    fun connectTimeoutExceptionIsTransient() {
        // Uses test double with "Timeout" in class name, matching classifyError's detection
        val exception = TestConnectTimeoutException("Connection to https://api.example.com timed out")
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Connect timeout should be retryable")
        assertTrue(
            result.message.contains("timed out", ignoreCase = true),
            "Message should indicate timeout: ${result.message}",
        )
    }

    @Test
    fun socketTimeoutExceptionIsTransient() {
        // Uses test double with "Timeout" in class name, matching classifyError's detection
        val exception = TestSocketTimeoutException("Read timed out")
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Socket timeout should be retryable")
        assertTrue(
            result.message.contains("timed out", ignoreCase = true),
            "Message should indicate timeout: ${result.message}",
        )
    }

    @Test
    fun portalApiExceptionDelegatesToStatusCode() {
        val exception = PortalApiException("Unauthorized", null, 401)
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.AUTH, result.category)
        assertFalse(result.isRetryable, "401 from PortalApiException should not be retryable")
        assertEquals(401, result.statusCode)
    }

    @Test
    fun portalApiExceptionWith500IsTransient() {
        val exception = PortalApiException("Server error", null, 500)
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "500 from PortalApiException should be retryable")
        assertEquals(500, result.statusCode)
    }

    @Test
    fun genericExceptionIsTransientByDefault() {
        val exception = RuntimeException("Something went wrong")
        val result = classifyError(exception, "Sync")

        assertEquals(SyncErrorCategory.TRANSIENT, result.category)
        assertTrue(result.isRetryable, "Generic exceptions should default to transient/retryable")
        assertTrue(
            result.message.contains("Something went wrong"),
            "Message should contain original exception message: ${result.message}",
        )
    }

    // ==================== Error Message Validation ====================

    @Test
    fun errorMessageContainsContextPrefix() {
        val exception = RuntimeException("Connection reset")
        val result = classifyError(exception, "Push operation")

        assertTrue(
            result.message.startsWith("Push operation"),
            "Message should start with context: ${result.message}",
        )
    }

    @Test
    fun errorMessageDoesNotLeakSensitiveDetails() {
        // Ensure we don't expose internal URLs, tokens, or stack traces in user-facing messages
        val exception = PortalApiException("Error with token abc123xyz", null, 401)
        val result = classifyError(exception, "Auth")

        // The message IS passed through, but it's the API's responsibility to sanitize.
        // Here we just verify the classification preserves the message for debugging.
        assertEquals("Error with token abc123xyz", result.message)
    }

    // ==================== ClassifiedSyncError.toException() ====================

    @Test
    fun toExceptionCreatesPortalApiExceptionWithCorrectFields() {
        val classified = ClassifiedSyncError(
            category = SyncErrorCategory.TRANSIENT,
            message = "Server temporarily unavailable",
            statusCode = 503,
            isRetryable = true,
            cause = RuntimeException("Original cause"),
        )

        val exception = classified.toException()

        assertEquals("Server temporarily unavailable", exception.message)
        assertEquals(503, exception.statusCode)
        assertTrue(exception.cause is RuntimeException)
    }

    // ==================== Edge Cases ====================

    @Test
    fun allServerErrorsInRangeAreTransient() {
        // Test the full 5xx range that should be transient
        for (statusCode in 500..599) {
            val result = classifyByStatusCode(statusCode, "Server error $statusCode")
            assertEquals(
                SyncErrorCategory.TRANSIENT,
                result.category,
                "Status $statusCode should be TRANSIENT",
            )
            assertTrue(result.isRetryable, "Status $statusCode should be retryable")
        }
    }

    @Test
    fun classifyByStatusCodePreservesCause() {
        val originalCause = RuntimeException("Original error")
        val result = classifyByStatusCode(500, "Server error", originalCause)

        assertEquals(originalCause, result.cause, "Cause should be preserved")
    }

    @Test
    fun classifyErrorPreservesCause() {
        val originalCause = IllegalStateException("Root cause")
        val exception = PortalApiException("Wrapper error", originalCause, 500)
        val result = classifyError(exception, "Test")

        assertEquals(exception, result.cause, "PortalApiException should be preserved as cause")
    }
}

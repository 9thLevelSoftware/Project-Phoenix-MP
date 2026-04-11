package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PortalTokenStorage auth edge cases.
 * Validates the 60-second expiry buffer that triggers proactive token refresh (SC-3),
 * clearAuth preservation of deviceId/lastSync, and GoTrue auth field storage.
 */
class PortalTokenStorageTest {

    private fun createStorage(): PortalTokenStorage = PortalTokenStorage(MapSettings())

    /**
     * Helper to save a GoTrue auth response with a specific expiresAt timestamp.
     */
    private fun saveAuthWithExpiry(storage: PortalTokenStorage, expiresAtSec: Long) {
        val response = GoTrueAuthResponse(
            accessToken = "test-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = expiresAtSec,
            refreshToken = "test-refresh-token",
            user = GoTrueUser(
                id = "user-123",
                email = "test@example.com",
            ),
        )
        storage.saveGoTrueAuth(response)
    }

    // ===== isTokenExpired Tests (SC-3 60s buffer) =====

    @Test
    fun isTokenExpiredReturnsTrueWhenTokenExpired() {
        val storage = createStorage()
        val pastSec = currentTimeMillis() / 1000 - 300 // 5 minutes ago
        saveAuthWithExpiry(storage, pastSec)

        assertTrue(storage.isTokenExpired(), "Token expired 5 minutes ago should be expired")
    }

    @Test
    fun isTokenExpiredReturnsTrueWithin60SecondBuffer() {
        val storage = createStorage()
        val nowSec = currentTimeMillis() / 1000
        // Token expires in 30 seconds -- within the 60-second buffer
        saveAuthWithExpiry(storage, nowSec + 30)

        assertTrue(
            storage.isTokenExpired(),
            "Token expiring in 30s (within 60s buffer) should be treated as expired",
        )
    }

    @Test
    fun isTokenExpiredReturnsFalseWhenMoreThan60SecondsRemaining() {
        val storage = createStorage()
        val nowSec = currentTimeMillis() / 1000
        // Token expires in 120 seconds -- well beyond the 60-second buffer
        saveAuthWithExpiry(storage, nowSec + 120)

        assertFalse(
            storage.isTokenExpired(),
            "Token expiring in 120s (beyond 60s buffer) should not be expired",
        )
    }

    @Test
    fun isTokenExpiredReturnsTrueWhenNoTokenStored() {
        val storage = createStorage()
        // No auth saved -- expiresAt defaults to 0L

        assertTrue(storage.isTokenExpired(), "No token stored should be treated as expired")
    }

    // ===== clearAuth Tests =====

    @Test
    fun clearAuthPreservesDeviceIdAndResetsLastSync() {
        val storage = createStorage()

        // Setup: save auth and set device ID / lastSync
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        val deviceId = storage.getDeviceId() // Triggers generation
        storage.setLastSyncTimestamp(1234567890L)

        // Verify auth is present before clearing
        assertTrue(storage.hasToken(), "Should have token before clearAuth")

        // Clear auth
        storage.clearAuth()

        // Token and auth state should be gone
        assertFalse(storage.hasToken(), "Token should be cleared")
        assertFalse(storage.isAuthenticated.value, "isAuthenticated should be false")
        assertNull(storage.currentUser.value, "currentUser should be null")
        assertNull(storage.getToken(), "getToken should return null")
        assertNull(storage.getRefreshToken(), "getRefreshToken should return null")

        // DeviceId should be preserved for stable identity
        assertEquals(
            deviceId,
            storage.getDeviceId(),
            "DeviceId should be preserved after clearAuth",
        )
        // lastSync should be reset so a re-link triggers a full pull
        assertEquals(
            0L,
            storage.getLastSyncTimestamp(),
            "lastSyncTimestamp should be reset after clearAuth",
        )
    }

    // ===== saveGoTrueAuth Tests =====

    @Test
    fun saveGoTrueAuthStoresAllFieldsCorrectly() {
        val storage = createStorage()

        val response = GoTrueAuthResponse(
            accessToken = "my-access-token",
            tokenType = "bearer",
            expiresIn = 3600,
            expiresAt = 1740916800L,
            refreshToken = "my-refresh-token",
            user = GoTrueUser(
                id = "user-abc",
                email = "hello@world.com",
                userMetadata = kotlinx.serialization.json.buildJsonObject {
                    put("display_name", kotlinx.serialization.json.JsonPrimitive("Hello World"))
                },
            ),
        )
        storage.saveGoTrueAuth(response)

        assertEquals("my-access-token", storage.getToken(), "Access token should be stored")
        assertEquals(
            "my-refresh-token",
            storage.getRefreshToken(),
            "Refresh token should be stored",
        )
        assertEquals(1740916800L, storage.getExpiresAt(), "ExpiresAt should be stored")
        assertTrue(storage.isAuthenticated.value, "isAuthenticated should be true")
        assertTrue(storage.hasToken(), "hasToken should be true")

        val user = storage.currentUser.value
        assertNotNull(user, "currentUser should not be null")
        assertEquals("user-abc", user.id)
        assertEquals("hello@world.com", user.email)
    }

    // ===== hasToken after clearAuth =====

    @Test
    fun hasTokenReturnsFalseAfterClearAuth() {
        val storage = createStorage()

        // Save auth
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        assertTrue(storage.hasToken(), "Should have token after save")

        // Clear
        storage.clearAuth()

        assertFalse(storage.hasToken(), "hasToken should return false after clearAuth")
    }
}

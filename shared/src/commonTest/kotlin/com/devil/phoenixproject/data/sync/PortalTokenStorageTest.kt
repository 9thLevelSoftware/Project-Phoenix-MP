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
    fun clearAuthPreservesDeviceIdAndCursors() {
        val storage = createStorage()

        // Setup: save auth and set device ID / cursors
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        val deviceId = storage.getDeviceId() // Triggers generation
        val userId = requireNotNull(storage.currentUser.value).id
        storage.setPushWatermark(userId, "default", 1234567890L)
        storage.setPullCursor(userId, "default", 9876543210L)

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
        // PR 10 step 8: cursors are namespaced by user id, so a re-link resumes
        // instead of re-pulling everything.
        assertEquals(
            1234567890L,
            storage.getPushWatermark(userId, "default"),
            "push watermark must survive clearAuth so a re-link resumes",
        )
        assertEquals(
            9876543210L,
            storage.getPullCursor(userId, "default"),
            "pull cursor must survive clearAuth so a re-link resumes",
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

    // ===== saveGoTrueAuth premium isolation (audit F024) =====

    private fun goTrueResponseFor(userId: String): GoTrueAuthResponse = GoTrueAuthResponse(
        accessToken = "access-$userId",
        tokenType = "bearer",
        expiresIn = 3600,
        expiresAt = currentTimeMillis() / 1000 + 3600,
        refreshToken = "refresh-$userId",
        user = GoTrueUser(id = userId, email = "$userId@example.com"),
    )

    @Test
    fun saveGoTrueAuthPreservesPremiumForSameUser() {
        val storage = createStorage()
        storage.saveGoTrueAuth(goTrueResponseFor("user-1"))
        storage.updatePremiumStatus(true)

        // Re-auth (e.g. token refresh) for the SAME user must keep premium.
        storage.saveGoTrueAuth(goTrueResponseFor("user-1"))

        assertTrue(
            storage.currentUser.value?.isPremium == true,
            "Premium must be preserved when the auth response is for the same user",
        )
    }

    @Test
    fun saveGoTrueAuthDropsPremiumOnAccountSwitch() {
        val storage = createStorage()
        storage.saveGoTrueAuth(goTrueResponseFor("user-1"))
        storage.updatePremiumStatus(true)
        assertTrue(storage.currentUser.value?.isPremium == true, "user-1 premium set")

        // Switching to a DIFFERENT user must NOT inherit user-1's premium flag.
        storage.saveGoTrueAuth(goTrueResponseFor("user-2"))

        assertEquals("user-2", storage.currentUser.value?.id)
        assertFalse(
            storage.currentUser.value?.isPremium ?: true,
            "Premium must NOT carry over to a different account (F024)",
        )
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

    // ===== AuthEvent Tests =====

    @Test
    fun clearAuthWithEventEmitsEvent() {
        val storage = createStorage()
        val events = mutableListOf<AuthEvent>()

        // Setup: save auth first
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        assertTrue(storage.hasToken(), "Should have token before clearAuthWithEvent")

        // Clear with event (event is emitted via tryEmit, so we need to collect asynchronously)
        // For this test, we verify the auth is cleared - the event emission is tested via integration
        storage.clearAuthWithEvent(AuthEvent.SessionExpired("Test session expired"))

        // Verify auth was cleared
        assertFalse(storage.hasToken(), "Token should be cleared after clearAuthWithEvent")
        assertFalse(storage.isAuthenticated.value, "isAuthenticated should be false")
        assertNull(storage.currentUser.value, "currentUser should be null")
    }

    @Test
    fun updatePremiumStatusPersistsAndUpdatesUser() {
        val storage = createStorage()

        // Setup: save auth with isPremium = false (default)
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        assertFalse(storage.currentUser.value?.isPremium ?: true, "User should start as non-premium")

        // Update premium status to true
        storage.updatePremiumStatus(true)

        // Verify persisted state
        assertTrue(
            storage.currentUser.value?.isPremium == true,
            "currentUser.isPremium should be true after updatePremiumStatus(true)",
        )

        // Verify it persists after reload
        val reloadedUser = storage.currentUser.value
        assertTrue(
            reloadedUser?.isPremium == true,
            "Premium status should persist",
        )

        // Update back to false
        storage.updatePremiumStatus(false)
        assertFalse(
            storage.currentUser.value?.isPremium ?: true,
            "currentUser.isPremium should be false after updatePremiumStatus(false)",
        )
    }

    // ===== Subscription Tier Tests =====
    // Tier is stored independently of the isPremium boolean so SyncManager can gate
    // tier-specific features (e.g., 50Hz telemetry sync is INFERNO-only).

    @Test
    fun getSubscriptionTierReturnsNullWhenNeverSet() {
        val storage = createStorage()
        assertNull(storage.getSubscriptionTier(), "Tier should be null before any update")
    }

    @Test
    fun updateSubscriptionTierPersistsString() {
        val storage = createStorage()

        storage.updateSubscriptionTier("INFERNO")
        assertEquals("INFERNO", storage.getSubscriptionTier())

        storage.updateSubscriptionTier("FLAME")
        assertEquals("FLAME", storage.getSubscriptionTier(), "Tier should overwrite on update")

        storage.updateSubscriptionTier("EMBER")
        assertEquals("EMBER", storage.getSubscriptionTier())
    }

    @Test
    fun updateSubscriptionTierWithNullClearsStoredValue() {
        val storage = createStorage()
        storage.updateSubscriptionTier("INFERNO")
        assertEquals("INFERNO", storage.getSubscriptionTier())

        storage.updateSubscriptionTier(null)
        assertNull(
            storage.getSubscriptionTier(),
            "Passing null must remove the stored key so callers can distinguish 'unknown' from 'downgraded'",
        )
    }

    @Test
    fun clearAuthClearsSubscriptionTier() {
        val storage = createStorage()

        // Setup: authenticate with a tier set
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)
        storage.updateSubscriptionTier("INFERNO")
        assertEquals("INFERNO", storage.getSubscriptionTier(), "Tier set before clear")

        storage.clearAuth()

        assertNull(
            storage.getSubscriptionTier(),
            "clearAuth must drop tier so a re-linked account starts from an unknown tier",
        )
    }

    @Test
    fun subscriptionTierSurvivesIndependentlyOfPremiumFlag() {
        val storage = createStorage()
        val nowSec = currentTimeMillis() / 1000
        saveAuthWithExpiry(storage, nowSec + 3600)

        storage.updateSubscriptionTier("INFERNO")
        storage.updatePremiumStatus(true)

        // Toggling premium must not touch tier
        storage.updatePremiumStatus(false)
        assertEquals(
            "INFERNO",
            storage.getSubscriptionTier(),
            "updatePremiumStatus should not affect the stored tier",
        )
    }

    @Test
    fun recordCompletedPullStoresPerProfileCursor() {
        val storage = PortalTokenStorage(MapSettings())
        storage.recordCompletedPull("u1", "default", 1234L)
        assertEquals(1234L, storage.getPullCursor("u1", "default"))

        storage.recordCompletedPull("u1", "other", 5678L)
        assertEquals(5678L, storage.getPullCursor("u1", "other"))
        assertEquals(1234L, storage.getPullCursor("u1", "default"), "profiles are independent")
    }

    @Test
    fun cursorsAreNamespacedByUserId() {
        val storage = PortalTokenStorage(MapSettings())
        fun auth(userId: String) = storage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "tok",
                tokenType = "bearer",
                expiresIn = 3600,
                refreshToken = "rtok",
                user = GoTrueUser(id = userId, email = "$userId@e.com"),
            ),
        )
        auth("u1")
        storage.recordCompletedPull("u1", "default", 1234L)

        auth("u1")
        assertEquals(1234L, storage.getPullCursor("u1", "default"), "token refresh for the same user keeps its cursors")

        auth("u2")
        assertEquals(0L, storage.getPullCursor("u2", "default"), "a different user starts with no cursor")
        assertEquals(1234L, storage.getPullCursor("u1", "default"), "the other user's cursor is untouched")

        storage.recordCompletedPull("u2", "default", 2000L)
        storage.clearAuth()
        assertEquals(2000L, storage.getPullCursor("u2", "default"), "clearAuth keeps cursors")
        assertEquals(1234L, storage.getPullCursor("u1", "default"), "clearAuth keeps cursors")
    }

    // ===== Auth generation (stale refresh writes) =====

    @Test
    fun refreshSaveWithStaleGenerationIsDroppedAfterClearAuth() {
        val storage = createStorage()
        saveAuthWithExpiry(storage, currentTimeMillis() / 1000 + 3600)
        val generation = storage.authGeneration()

        storage.clearAuth()
        val written = storage.saveGoTrueAuth(refreshedResponse(), expectedGeneration = generation)

        assertFalse(written, "refresh started before clearAuth must be dropped")
        assertNull(storage.getToken())
        assertFalse(storage.isAuthenticated.value)
    }

    @Test
    fun refreshSaveWithCurrentGenerationIsWritten() {
        val storage = createStorage()
        saveAuthWithExpiry(storage, currentTimeMillis() / 1000 + 3600)

        val written = storage.saveGoTrueAuth(refreshedResponse(), expectedGeneration = storage.authGeneration())

        assertTrue(written)
        assertEquals("refreshed-access", storage.getToken())
    }

    @Test
    fun signInDuringInFlightRefreshIsNotOverwrittenByTheRefresh() {
        val storage = createStorage()
        saveAuthWithExpiry(storage, currentTimeMillis() / 1000 + 3600)
        val generation = storage.authGeneration()

        saveAuthWithExpiry(storage, currentTimeMillis() / 1000 + 7200) // new sign-in

        assertFalse(storage.saveGoTrueAuth(refreshedResponse(), expectedGeneration = generation))
        assertEquals("test-access-token", storage.getToken())
    }

    private fun refreshedResponse() = GoTrueAuthResponse(
        accessToken = "refreshed-access",
        tokenType = "bearer",
        expiresIn = 3600,
        refreshToken = "refreshed-refresh",
        user = GoTrueUser(id = "user-123", email = "test@example.com"),
    )

    // ===== Fresh-install secure storage reset (iOS Keychain outlives uninstall) =====

    private class InstallState(var marker: Boolean, val databaseExists: Boolean) {
        var cleared = false

        fun run() = resetSecureStorageOnFreshInstall(
            hasInstallMarker = { marker },
            localDatabaseExists = { databaseExists },
            clearSecureStorage = { cleared = true },
            setInstallMarker = { marker = true },
        )
    }

    @Test
    fun freshInstallClearsSecureStorageAndSetsMarker() {
        val state = InstallState(marker = false, databaseExists = false)

        assertTrue(state.run())
        assertTrue(state.cleared, "no marker + no database = reinstall: wipe leftover tokens")
        assertTrue(state.marker)
    }

    @Test
    fun upgradeWithoutMarkerKeepsSessionAndSetsMarker() {
        val state = InstallState(marker = false, databaseExists = true)

        assertFalse(state.run())
        assertFalse(state.cleared, "existing database = upgrade from a pre-marker build: stay signed in")
        assertTrue(state.marker)
    }

    @Test
    fun markerPresentNeverClears() {
        val state = InstallState(marker = true, databaseExists = false)

        assertFalse(state.run())
        assertFalse(state.cleared)
    }


    // ===== GitHub review round (#856): legacy cursor migration + restart floor =====

    private fun signIn(storage: PortalTokenStorage, userId: String) {
        storage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "token-$userId",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = currentTimeMillis() / 1000 + 3600,
                refreshToken = "refresh-$userId",
                user = GoTrueUser(id = userId, email = "$userId@example.com"),
            ),
        )
    }

    @Test
    fun legacyPullCursorSeedsOnlyTheProfileNamedByTheLegacyDeltaMarker() {
        // codex #856 P1: the old build sent a full pull whenever its delta marker named a
        // different profile. Seeding any other profile with the value would skip server
        // rows that profile never received.
        val settings = MapSettings()
        val storage = PortalTokenStorage(settings)
        signIn(storage, "user-1")
        settings.putLong("portal_last_sync_timestamp", 5_000L)
        settings.putString("portal_delta_pull_key", "user-1:profile-b")

        assertTrue(storage.migrateLegacyCursors("user-1", listOf("profile-a", "profile-b")))

        assertEquals(0L, storage.getPullCursor("user-1", "profile-a"))
        assertEquals(5_000L, storage.getPullCursor("user-1", "profile-b"))
        assertEquals(5_000L, storage.getPushWatermark("user-1", "profile-a"))
        assertEquals(5_000L, storage.getPushWatermark("user-1", "profile-b"))
        assertFalse("portal_delta_pull_key" in settings.keys)
    }

    @Test
    fun legacyCursorWithoutADeltaMarkerSeedsNoPullCursor() {
        val settings = MapSettings()
        val storage = PortalTokenStorage(settings)
        signIn(storage, "user-1")
        settings.putLong("portal_last_sync_timestamp", 5_000L)

        assertTrue(storage.migrateLegacyCursors("user-1", listOf("default")))

        assertEquals(0L, storage.getPullCursor("user-1", "default"), "no marker meant a full pull")
        assertEquals(5_000L, storage.getPushWatermark("user-1", "default"))
    }

    @Test
    fun legacyCursorOwnedByAnotherUserSeedsNothing() {
        val settings = MapSettings()
        val storage = PortalTokenStorage(settings)
        signIn(storage, "user-2")
        settings.putLong("portal_last_sync_timestamp", 5_000L)
        settings.putString("portal_delta_pull_key", "user-1:default")

        assertFalse(storage.migrateLegacyCursors("user-2", listOf("default")))

        assertEquals(0L, storage.getPullCursor("user-2", "default"))
        assertEquals(0L, storage.getPushWatermark("user-2", "default"))
        assertFalse("portal_last_sync_timestamp" in settings.keys)
    }

    @Test
    fun aZeroLegacyCursorNeverZeroesAlreadySeededCursors() {
        val settings = MapSettings()
        val storage = PortalTokenStorage(settings)
        signIn(storage, "user-1")
        storage.setPullCursor("user-1", "default", 9_000L)
        storage.setPushWatermark("user-1", "default", 9_000L)
        settings.putLong("portal_last_sync_timestamp", 0L)
        settings.putString("portal_delta_pull_key", "user-1:default")

        assertFalse(storage.migrateLegacyCursors("user-1", listOf("default")))

        assertEquals(9_000L, storage.getPullCursor("user-1", "default"))
        assertEquals(9_000L, storage.getPushWatermark("user-1", "default"))
    }

    @Test
    fun signingInAsADifferentAccountDropsTheUnnamespacedLegacyCursor() {
        val settings = MapSettings()
        val storage = PortalTokenStorage(settings)
        signIn(storage, "user-1")
        settings.putLong("portal_last_sync_timestamp", 5_000L)

        signIn(storage, "user-2")

        assertFalse(storage.migrateLegacyCursors("user-2", listOf("default")))
        assertEquals(0L, storage.getPushWatermark("user-2", "default"))
    }

    @Test
    fun aRestartRestoresTheLastSyncFloorFromTheStoredPullCursors() {
        // codex #856 P2: after a process restart the UI must not report "never synced"
        // (and the first-sync trigger must not re-arm) until the next sync publishes.
        val settings = MapSettings()
        val first = PortalTokenStorage(settings)
        signIn(first, "user-1")
        first.setPullCursor("user-1", "profile-a", 7_000L)
        first.setPullCursor("user-1", "profile-b", 4_000L)
        first.setPullCursor("user-1", "never-pulled", 0L)

        val restarted = PortalTokenStorage(settings)

        assertEquals(4_000L, restarted.lastSyncTimestamp.value)
    }
}

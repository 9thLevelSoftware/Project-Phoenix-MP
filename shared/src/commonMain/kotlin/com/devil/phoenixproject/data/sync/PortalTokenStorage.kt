package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.withPlatformLock
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authentication events that observers can subscribe to for user notifications.
 * These events are emitted when authentication state changes require user action.
 */
sealed class AuthEvent {
    /**
     * Session has expired and user needs to re-authenticate.
     * Emitted when refresh token fails or is rejected by the server.
     */
    data class SessionExpired(val reason: String) : AuthEvent()

    /**
     * Token refresh attempt failed.
     * May be recoverable (network issue) or require re-authentication (token revoked).
     */
    data class RefreshFailed(val reason: String, val isRecoverable: Boolean) : AuthEvent()

    /**
     * User was logged out explicitly (e.g., by calling logout).
     * Distinguished from session expiry for UI messaging purposes.
     */
    object LoggedOut : AuthEvent()
}

/**
 * Secure storage for Portal authentication tokens.
 *
 * SECURITY REQUIREMENTS:
 * - This class MUST be initialized with a Settings instance backed by secure storage
 *   (Android EncryptedSharedPreferences or iOS Keychain).
 * - Tokens stored include JWT access tokens and refresh tokens which grant account access.
 * - Never initialize with plain SharedPreferences or NSUserDefaults on production builds.
 *
 * @param settings A Settings instance backed by secure storage (encrypted at rest).
 * @throws IllegalStateException if secure storage verification fails during initialization.
 */
class PortalTokenStorage(private val settings: Settings) {

    companion object {
        private const val KEY_TOKEN = "portal_auth_token"
        private const val KEY_USER_ID = "portal_user_id"
        private const val KEY_USER_EMAIL = "portal_user_email"
        private const val KEY_USER_NAME = "portal_user_display_name"
        private const val KEY_IS_PREMIUM = "portal_user_is_premium"
        private const val KEY_SUBSCRIPTION_TIER = "portal_user_subscription_tier"
        private const val KEY_REFRESH_TOKEN = "portal_refresh_token"
        private const val KEY_EXPIRES_AT = "portal_token_expires_at"
        private const val KEY_LAST_SYNC = "portal_last_sync_timestamp"
        private const val KEY_DEVICE_ID = "portal_device_id"
        private const val KEY_STORAGE_VERIFIED = "portal_storage_verified"
    }

    init {
        // Defensive check: verify that the storage is writable and can round-trip data.
        // This catches cases where the Settings instance is somehow compromised.
        verifyStorageIntegrity()
    }

    /**
     * Verifies that the secure storage can read/write data correctly.
     * This is a defensive check to catch initialization issues early.
     *
     * @throws IllegalStateException if storage verification fails.
     */
    private fun verifyStorageIntegrity() {
        try {
            // Write a test value and read it back
            val testValue = "storage_check_${currentTimeMillis()}"
            settings[KEY_STORAGE_VERIFIED] = testValue
            val readBack: String? = settings[KEY_STORAGE_VERIFIED]
            if (readBack != testValue) {
                throw IllegalStateException(
                    "Secure storage verification failed: written value '$testValue' " +
                        "but read back '$readBack'. Storage may be corrupted or unavailable.",
                )
            }
            // Clean up test value
            settings.remove(KEY_STORAGE_VERIFIED)
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException(
                "Secure storage verification failed with exception: ${e.message}. " +
                    "Auth tokens cannot be safely stored.",
                e,
            )
        }
    }

    private val deviceIdLock = Any()

    private val _isAuthenticated = MutableStateFlow(hasToken())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow(loadUser())
    val currentUser: StateFlow<PortalUser?> = _currentUser.asStateFlow()

    /**
     * Flow of authentication events for UI notification.
     * Collectors will receive events when session expires, refresh fails, or user logs out.
     * Uses replay=0 so only active collectors receive events (no stale events on new subscriptions).
     */
    private val _authEvents = MutableSharedFlow<AuthEvent>(replay = 0, extraBufferCapacity = 1)
    val authEvents: SharedFlow<AuthEvent> = _authEvents.asSharedFlow()

    fun saveAuth(response: PortalAuthResponse) {
        settings[KEY_TOKEN] = response.token
        settings[KEY_USER_ID] = response.user.id
        settings[KEY_USER_EMAIL] = response.user.email
        settings[KEY_USER_NAME] = response.user.displayName
        settings[KEY_IS_PREMIUM] = response.user.isPremium

        _isAuthenticated.value = true
        _currentUser.value = response.user
    }

    fun saveGoTrueAuth(response: GoTrueAuthResponse) {
        // Preserve existing premium status — GoTrue auth response does not include it,
        // and overwriting would reset paid users to non-premium on every sign-in.
        val existingPremium: Boolean = settings[KEY_IS_PREMIUM, false]

        settings[KEY_TOKEN] = response.accessToken
        settings[KEY_REFRESH_TOKEN] = response.refreshToken
        val expiresAt = response.expiresAt
            ?: (currentTimeMillis() / 1000 + response.expiresIn)
        settings.putLong(KEY_EXPIRES_AT, expiresAt)
        settings[KEY_USER_ID] = response.user.id
        settings[KEY_USER_EMAIL] = response.user.email ?: ""
        settings[KEY_USER_NAME] = response.user.displayName ?: ""
        settings[KEY_IS_PREMIUM] = existingPremium
        _isAuthenticated.value = true
        _currentUser.value = loadUser()
    }

    fun getRefreshToken(): String? = settings.getStringOrNull(KEY_REFRESH_TOKEN)

    fun getExpiresAt(): Long = settings.getLong(KEY_EXPIRES_AT, 0L)

    fun isTokenExpired(): Boolean {
        val expiresAt = getExpiresAt()
        if (expiresAt == 0L) return true
        return currentTimeMillis() / 1000 >= (expiresAt - 60)
    }

    fun getToken(): String? = settings[KEY_TOKEN]

    fun hasToken(): Boolean = settings.getStringOrNull(KEY_TOKEN) != null

    fun getDeviceId(): String = withPlatformLock(deviceIdLock) {
        val existing: String? = settings[KEY_DEVICE_ID]
        if (existing != null) return@withPlatformLock existing

        val newId = generateDeviceId()
        settings[KEY_DEVICE_ID] = newId
        newId
    }

    fun getLastSyncTimestamp(): Long = settings[KEY_LAST_SYNC, 0L]

    fun setLastSyncTimestamp(timestamp: Long) {
        settings[KEY_LAST_SYNC] = timestamp
    }

    fun updatePremiumStatus(isPremium: Boolean) {
        settings[KEY_IS_PREMIUM] = isPremium
        _currentUser.value = _currentUser.value?.copy(isPremium = isPremium)
    }

    /**
     * Persists the active subscription tier string (EMBER, FLAME, INFERNO, or null).
     * Null clears the key so callers can distinguish "not yet resolved" from "no
     * active subscription." Used by [SyncManager] to gate features whose entitlement
     * depends on a specific tier rather than the broad `isPremium` flag (e.g., 50 Hz
     * telemetry sync is Inferno-only).
     */
    fun updateSubscriptionTier(tier: String?) {
        if (tier == null) {
            settings.remove(KEY_SUBSCRIPTION_TIER)
        } else {
            settings[KEY_SUBSCRIPTION_TIER] = tier
        }
    }

    fun getSubscriptionTier(): String? = settings[KEY_SUBSCRIPTION_TIER]

    fun clearAuth() {
        clearAuthInternal()
    }

    /**
     * Clears auth state and emits an authentication event to notify UI.
     * Use this when clearing auth due to session expiry or refresh failure
     * to allow the UI to show appropriate messaging to the user.
     *
     * @param event The authentication event describing why auth was cleared
     */
    fun clearAuthWithEvent(event: AuthEvent) {
        clearAuthInternal()
        _authEvents.tryEmit(event)
    }

    /**
     * Emits a logout event for UI notification when user explicitly logs out.
     * Called by logout flows to inform UI of explicit user action.
     */
    fun emitLogoutEvent() {
        _authEvents.tryEmit(AuthEvent.LoggedOut)
    }

    private fun clearAuthInternal() {
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_REFRESH_TOKEN)
        settings.remove(KEY_EXPIRES_AT)
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_USER_NAME)
        settings.remove(KEY_IS_PREMIUM)
        settings.remove(KEY_SUBSCRIPTION_TIER)
        settings.remove(KEY_LAST_SYNC) // Reset so re-link does a full pull
        // Keep device ID for stable identity

        _isAuthenticated.value = false
        _currentUser.value = null
    }

    private fun loadUser(): PortalUser? {
        val id: String = settings[KEY_USER_ID] ?: return null
        val email: String = settings[KEY_USER_EMAIL] ?: return null
        val displayName: String? = settings[KEY_USER_NAME]
        val isPremium: Boolean = settings[KEY_IS_PREMIUM, false]

        return PortalUser(id, email, displayName, isPremium)
    }

    private fun generateDeviceId(): String {
        // Generate a stable device identifier using multiplatform UUID
        return generateUUID()
    }
}

package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.generateUUID
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PortalTokenStorage(private val settings: Settings) {

    companion object {
        private const val KEY_TOKEN = "portal_auth_token"
        private const val KEY_USER_ID = "portal_user_id"
        private const val KEY_USER_EMAIL = "portal_user_email"
        private const val KEY_USER_NAME = "portal_user_display_name"
        private const val KEY_IS_PREMIUM = "portal_user_is_premium"
        private const val KEY_LAST_SYNC = "portal_last_sync_timestamp"
        private const val KEY_DEVICE_ID = "portal_device_id"
    }

    private val deviceIdLock = Any()

    private val _isAuthenticated = MutableStateFlow(hasToken())
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentUser = MutableStateFlow(loadUser())
    val currentUser: StateFlow<PortalUser?> = _currentUser.asStateFlow()

    fun saveAuth(response: PortalAuthResponse) {
        settings[KEY_TOKEN] = response.token
        settings[KEY_USER_ID] = response.user.id
        settings[KEY_USER_EMAIL] = response.user.email
        settings[KEY_USER_NAME] = response.user.displayName
        settings[KEY_IS_PREMIUM] = response.user.isPremium

        _isAuthenticated.value = true
        _currentUser.value = response.user
    }

    fun getToken(): String? = settings[KEY_TOKEN]

    fun hasToken(): Boolean = settings.getStringOrNull(KEY_TOKEN) != null

    fun getDeviceId(): String = synchronized(deviceIdLock) {
        val existing: String? = settings[KEY_DEVICE_ID]
        if (existing != null) return@synchronized existing

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

    fun clearAuth() {
        settings.remove(KEY_TOKEN)
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_USER_NAME)
        settings.remove(KEY_IS_PREMIUM)
        // Keep device ID and last sync for re-auth

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

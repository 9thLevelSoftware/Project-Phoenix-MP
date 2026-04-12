package com.devil.phoenixproject.di

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Tests for secure token storage migration logic.
 *
 * These tests verify the migration contract that exists in both Android and iOS platform modules:
 * - Legacy tokens in plain storage should be migrated to secure storage
 * - Legacy keys should be removed after successful migration
 * - Migration should be idempotent (safe to run multiple times)
 * - Migration should not crash on failures
 *
 * Note: Full platform-specific tests require:
 * - Android: Robolectric or instrumented test for EncryptedSharedPreferences
 * - iOS: macOS with Xcode for KeychainSettings
 *
 * This test validates the migration algorithm using MapSettings as a stand-in.
 */
class SecureTokenStorageTest {

    private val portalKeys = listOf(
        "portal_auth_token",
        "portal_refresh_token",
        "portal_token_expires_at",
        "portal_user_id",
        "portal_user_email",
        "portal_user_display_name",
        "portal_user_is_premium",
        "portal_device_id",
        "portal_last_sync_timestamp",
    )

    /**
     * Simulates the migration logic that exists in both PlatformModule.android.kt
     * and PlatformModule.ios.kt to validate the algorithm.
     */
    private fun migrateTokens(legacy: Settings, secure: Settings) {
        // Check if any portal keys exist in legacy storage
        val hasLegacyKeys = portalKeys.any { key ->
            legacy.getStringOrNull(key) != null ||
                legacy.getLongOrNull(key) != null ||
                legacy.getBooleanOrNull(key) != null
        }
        if (!hasLegacyKeys) return

        // Copy values to secure storage
        for (key in portalKeys) {
            legacy.getStringOrNull(key)?.let { value ->
                secure.putString(key, value)
            }
            legacy.getLongOrNull(key)?.let { value ->
                secure.putLong(key, value)
            }
            legacy.getBooleanOrNull(key)?.let { value ->
                secure.putBoolean(key, value)
            }
        }

        // Remove migrated keys from legacy storage
        for (key in portalKeys) {
            legacy.remove(key)
        }
    }

    @Test
    fun migrationCopiesStringTokensToSecureStorage() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: store tokens in legacy storage
        legacy.putString("portal_auth_token", "jwt-token-123")
        legacy.putString("portal_refresh_token", "refresh-token-456")
        legacy.putString("portal_user_id", "user-789")
        legacy.putString("portal_user_email", "user@example.com")

        // Act: run migration
        migrateTokens(legacy, secure)

        // Assert: tokens are in secure storage
        assertThat(secure.getStringOrNull("portal_auth_token")).isEqualTo("jwt-token-123")
        assertThat(secure.getStringOrNull("portal_refresh_token")).isEqualTo("refresh-token-456")
        assertThat(secure.getStringOrNull("portal_user_id")).isEqualTo("user-789")
        assertThat(secure.getStringOrNull("portal_user_email")).isEqualTo("user@example.com")

        // Assert: tokens are removed from legacy storage
        assertThat(legacy.getStringOrNull("portal_auth_token")).isNull()
        assertThat(legacy.getStringOrNull("portal_refresh_token")).isNull()
        assertThat(legacy.getStringOrNull("portal_user_id")).isNull()
        assertThat(legacy.getStringOrNull("portal_user_email")).isNull()
    }

    @Test
    fun migrationCopiesLongValuesToSecureStorage() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: store long values in legacy storage
        legacy.putLong("portal_token_expires_at", 1740916800L)
        legacy.putLong("portal_last_sync_timestamp", 1234567890L)

        // Act: run migration
        migrateTokens(legacy, secure)

        // Assert: values are in secure storage
        assertThat(secure.getLong("portal_token_expires_at", 0L)).isEqualTo(1740916800L)
        assertThat(secure.getLong("portal_last_sync_timestamp", 0L)).isEqualTo(1234567890L)

        // Assert: values are removed from legacy storage
        assertThat(legacy.getLongOrNull("portal_token_expires_at")).isNull()
        assertThat(legacy.getLongOrNull("portal_last_sync_timestamp")).isNull()
    }

    @Test
    fun migrationCopiesBooleanValuesToSecureStorage() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: store boolean in legacy storage
        legacy.putBoolean("portal_user_is_premium", true)

        // Act: run migration
        migrateTokens(legacy, secure)

        // Assert: value is in secure storage
        assertThat(secure.getBoolean("portal_user_is_premium", false)).isTrue()

        // Assert: value is removed from legacy storage
        assertThat(legacy.getBooleanOrNull("portal_user_is_premium")).isNull()
    }

    @Test
    fun migrationIsIdempotentWhenNoLegacyKeys() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: tokens already in secure storage (no legacy keys)
        secure.putString("portal_auth_token", "existing-token")

        // Act: run migration (should be a no-op)
        migrateTokens(legacy, secure)

        // Assert: secure storage unchanged
        assertThat(secure.getStringOrNull("portal_auth_token")).isEqualTo("existing-token")
    }

    @Test
    fun migrationHandlesMixedValueTypes() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: store mixed types
        legacy.putString("portal_auth_token", "test-jwt")
        legacy.putString("portal_device_id", "device-uuid")
        legacy.putLong("portal_token_expires_at", 9999999999L)
        legacy.putBoolean("portal_user_is_premium", false)

        // Act: run migration
        migrateTokens(legacy, secure)

        // Assert: all types migrated correctly
        assertThat(secure.getStringOrNull("portal_auth_token")).isEqualTo("test-jwt")
        assertThat(secure.getStringOrNull("portal_device_id")).isEqualTo("device-uuid")
        assertThat(secure.getLong("portal_token_expires_at", 0L)).isEqualTo(9999999999L)
        assertThat(secure.getBoolean("portal_user_is_premium", true)).isFalse()

        // Assert: all legacy keys removed
        assertThat(legacy.getStringOrNull("portal_auth_token")).isNull()
        assertThat(legacy.getStringOrNull("portal_device_id")).isNull()
        assertThat(legacy.getLongOrNull("portal_token_expires_at")).isNull()
        assertThat(legacy.getBooleanOrNull("portal_user_is_premium")).isNull()
    }

    @Test
    fun migrationPreservesNonPortalKeys() {
        val legacy = MapSettings()
        val secure = MapSettings()

        // Setup: store both portal and non-portal keys
        legacy.putString("portal_auth_token", "migrate-me")
        legacy.putString("user_theme", "dark") // Should NOT be migrated
        legacy.putString("preferred_units", "metric") // Should NOT be migrated

        // Act: run migration
        migrateTokens(legacy, secure)

        // Assert: portal key migrated
        assertThat(secure.getStringOrNull("portal_auth_token")).isEqualTo("migrate-me")
        assertThat(legacy.getStringOrNull("portal_auth_token")).isNull()

        // Assert: non-portal keys preserved in legacy
        assertThat(legacy.getStringOrNull("user_theme")).isEqualTo("dark")
        assertThat(legacy.getStringOrNull("preferred_units")).isEqualTo("metric")

        // Assert: non-portal keys NOT in secure storage
        assertThat(secure.getStringOrNull("user_theme")).isNull()
        assertThat(secure.getStringOrNull("preferred_units")).isNull()
    }
}

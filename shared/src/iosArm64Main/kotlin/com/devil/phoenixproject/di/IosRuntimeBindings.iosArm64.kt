package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

private const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"

private val log = Logger.withTag("IosRuntimeBindings")

/**
 * Keys migrated from the pre-Keychain NSUserDefaults store. Keep this list in sync with the
 * existing iOS migration contract so device behavior is unchanged by the binding split.
 */
private val PORTAL_KEYS = listOf(
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

internal actual object IosRuntimeBindings {
    actual fun createBleRepository(): BleRepository = KableBleRepository()

    @OptIn(ExperimentalSettingsImplementation::class)
    actual fun createSecureSettings(legacySettings: Settings): Settings {
        val keychainSettings = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
        migrateTokensToKeychain(legacySettings, keychainSettings)
        return keychainSettings
    }
}

/**
 * One-time migration from NSUserDefaults-backed Settings to Keychain-backed Settings.
 * This is intentionally the same idempotent, non-destructive migration that was previously
 * performed by PlatformModule.ios.kt.
 */
private fun migrateTokensToKeychain(legacy: Settings, keychain: Settings) {
    val hasLegacyKeys = PORTAL_KEYS.any { key ->
        legacy.getStringOrNull(key) != null ||
            legacy.getLongOrNull(key) != null ||
            legacy.getBooleanOrNull(key) != null
    }
    if (!hasLegacyKeys) return

    log.i { "Migrating portal keys from NSUserDefaults to Keychain" }

    try {
        for (key in PORTAL_KEYS) {
            if (keychain.getStringOrNull(key) != null ||
                keychain.getLongOrNull(key) != null ||
                keychain.getBooleanOrNull(key) != null
            ) {
                continue
            }
            legacy.getStringOrNull(key)?.let { keychain.putString(key, it) }
            legacy.getLongOrNull(key)?.let { keychain.putLong(key, it) }
            legacy.getBooleanOrNull(key)?.let { keychain.putBoolean(key, it) }
        }

        for (key in PORTAL_KEYS) {
            legacy.remove(key)
        }
        log.i { "Portal key migration to Keychain complete" }
    } catch (e: Exception) {
        log.e(e) { "Failed to migrate portal keys to Keychain" }
    }
}

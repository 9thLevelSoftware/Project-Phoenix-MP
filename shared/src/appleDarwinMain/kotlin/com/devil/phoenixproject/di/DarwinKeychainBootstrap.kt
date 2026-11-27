package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import co.touchlab.sqliter.DatabaseFileContext
import com.devil.phoenixproject.data.local.DatabaseFileNames
import com.devil.phoenixproject.data.local.legacyLibraryRootPath
import com.devil.phoenixproject.data.sync.resetSecureStorageOnFreshInstall
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults

internal const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"

// NSUserDefaults key; its absence (with no local database) marks a fresh install.
private const val INSTALL_MARKER_KEY = "phoenix_install_marker"

private val log = Logger.withTag("DarwinKeychainBootstrap")

/**
 * Keys that need to be migrated from NSUserDefaults to Keychain.
 * Mirrors the Android PORTAL_KEYS list for parity.
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

/**
 * Keychain items survive uninstall, NSUserDefaults and the database don't. On a
 * fresh install (no marker, no database) wipe the auth Keychain service so a
 * reinstall on a handed-down device isn't signed in as the previous owner. An
 * upgrade from a build without the marker has a database and keeps its session.
 */
@OptIn(ExperimentalSettingsImplementation::class)
internal fun clearKeychainOnFreshInstall() {
    val defaults = NSUserDefaults.standardUserDefaults
    try {
        val cleared = resetSecureStorageOnFreshInstall(
            hasInstallMarker = { defaults.boolForKey(INSTALL_MARKER_KEY) },
            localDatabaseExists = ::anyLocalDatabaseExists,
            clearSecureStorage = { KeychainSettings(service = KEYCHAIN_SERVICE_NAME).clear() },
            setInstallMarker = { defaults.setBool(true, forKey = INSTALL_MARKER_KEY) },
        )
        if (cleared) log.i { "Fresh install: cleared auth Keychain left by a previous install" }
    } catch (e: Exception) {
        // A Keychain capability can be absent in unsigned simulator builds. Never let that
        // prevent start-up; worst case is the pre-existing behaviour.
        log.e(e) { "Fresh-install Keychain check failed" }
    }
}

private fun anyLocalDatabaseExists(): Boolean {
    val fileManager = NSFileManager.defaultManager
    val sqliterPaths = listOf(
        DatabaseFileNames.TARGET,
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.STAGING,
        DatabaseFileNames.RECOVERY,
    ).map { DatabaseFileContext.databasePath(it, null) }
    return (sqliterPaths + legacyLibraryRootPath()).any { fileManager.fileExistsAtPath(it) }
}

/**
 * One-time migration: copies all portal keys from the old NSUserDefaults to
 * the Keychain store, then removes them from NSUserDefaults.
 *
 * This is idempotent: if no legacy keys exist, it returns immediately.
 * Migration runs synchronously during Koin module initialization to ensure
 * tokens are available in Keychain before any auth operations.
 */
internal fun migrateTokensToKeychain(legacy: Settings, keychain: Settings) {
    // Check if any portal keys exist in legacy storage
    val hasLegacyKeys = PORTAL_KEYS.any { key ->
        legacy.getStringOrNull(key) != null ||
            legacy.getLongOrNull(key) != null ||
            legacy.getBooleanOrNull(key) != null
    }
    if (!hasLegacyKeys) return

    log.i { "Migrating portal keys from NSUserDefaults to Keychain" }

    try {
        for (key in PORTAL_KEYS) {
            // Never overwrite a value that already lives in the Keychain. The app
            // may have authenticated directly into the Keychain while stale legacy
            // NSUserDefaults keys still linger; clobbering the fresh token with an
            // old one (and then deleting the legacy copy) would corrupt the session.
            if (keychain.getStringOrNull(key) != null ||
                keychain.getLongOrNull(key) != null ||
                keychain.getBooleanOrNull(key) != null
            ) {
                continue
            }
            // Try each type - Settings stores typed values
            legacy.getStringOrNull(key)?.let { value ->
                keychain.putString(key, value)
            }
            legacy.getLongOrNull(key)?.let { value ->
                keychain.putLong(key, value)
            }
            legacy.getBooleanOrNull(key)?.let { value ->
                keychain.putBoolean(key, value)
            }
        }

        // Remove migrated keys from legacy storage
        for (key in PORTAL_KEYS) {
            legacy.remove(key)
        }

        log.i { "Portal key migration to Keychain complete" }
    } catch (e: Exception) {
        // Log but don't crash - user can re-authenticate if migration fails
        log.e(e) { "Failed to migrate portal keys to Keychain" }
    }
}

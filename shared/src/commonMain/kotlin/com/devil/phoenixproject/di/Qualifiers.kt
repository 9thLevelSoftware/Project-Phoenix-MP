package com.devil.phoenixproject.di

import com.russhwolf.settings.Settings
import org.koin.core.qualifier.named

/**
 * Koin qualifier for the encrypted/secure Settings instance.
 *
 * On Android this resolves to EncryptedSharedPreferences-backed Settings.
 * On iOS this resolves to KeychainSettings (iOS Keychain) for secure storage.
 *
 * Both platforms perform one-time migration from legacy storage (plain SharedPreferences
 * or NSUserDefaults) to secure storage on first access after upgrade.
 *
 * Used by PortalTokenStorage for JWT, refresh token, and user identity.
 */
val SecureSettingsQualifier = named("secureSettings")

private const val RETIRED_INSTALLATION_ID_KEY = "intervals.installationId"
private const val RETIRED_API_KEY_PREFIX = "intervals.apiKey."

/**
 * One-way cleanup of retired v1.1.1 direct-export keys from secure storage.
 * Portal auth tokens (`portal_*`) are never touched.
 */
internal fun purgeRetiredDirectExportSecrets(settings: Settings) {
    runCatching {
        val retiredKeys = settings.keys.filter { key ->
            key == RETIRED_INSTALLATION_ID_KEY || key.startsWith(RETIRED_API_KEY_PREFIX)
        }
        for (key in retiredKeys) {
            settings.remove(key)
        }
    }
}


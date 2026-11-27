package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
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

/** Removes credentials left by the retired v1.1.1 direct Intervals export. */
internal fun purgeRetiredDirectExportSecrets(settings: Settings) {
    try {
        val retiredKeys = settings.keys.filter { key ->
            key == "intervals.installationId" || key.startsWith("intervals.apiKey.")
        }
        retiredKeys.forEach(settings::remove)
    } catch (_: Exception) {
        // The secure binding retries on the next launch. Keep auth and startup usable.
        Logger.w("SecureSettings") { "Retired Intervals credential cleanup will retry next launch" }
    }
}


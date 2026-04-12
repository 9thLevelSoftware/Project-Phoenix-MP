package com.devil.phoenixproject.di

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

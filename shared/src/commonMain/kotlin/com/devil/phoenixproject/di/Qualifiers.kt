package com.devil.phoenixproject.di

import org.koin.core.qualifier.named

/**
 * Koin qualifier for the encrypted/secure Settings instance.
 *
 * On Android this resolves to EncryptedSharedPreferences-backed Settings.
 * On iOS this resolves to the default NSUserDefaults-backed Settings
 * (already app-sandboxed; Keychain migration is a future enhancement).
 *
 * Used by PortalTokenStorage for JWT, refresh token, and user identity.
 */
val SecureSettingsQualifier = named("secureSettings")

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

/**
 * Koin qualifier for the Intervals.icu export's dedicated [io.ktor.client.engine.HttpClientEngine]
 * seam (issue #1384 packet P4). The real client builds its own engine when nothing is
 * bound here; tests bind a MockEngine under this qualifier. The Intervals client never
 * shares the portal client's engine, headers or retry policy.
 */
val IntervalsHttpClientQualifier = named("intervalsHttpClient")

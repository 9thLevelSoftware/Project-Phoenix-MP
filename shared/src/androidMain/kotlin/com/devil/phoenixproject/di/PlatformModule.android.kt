package com.devil.phoenixproject.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.util.AndroidCsvExporter
import com.devil.phoenixproject.util.AndroidCsvImporter
import com.devil.phoenixproject.util.AndroidDataBackupManager
import com.devil.phoenixproject.domain.voice.AndroidSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.SharedPreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private const val ENCRYPTED_PREFS_FILE = "vitruvian_secure_preferences"
private const val PLAINTEXT_PREFS_FILE = "vitruvian_preferences"

private val log = Logger.withTag("PlatformModule")

actual val platformModule: Module = module {
    single { DriverFactory(androidContext()) }

    // General-purpose preferences (non-sensitive settings like units, UI prefs)
    single<Settings> {
        val preferences = androidContext().getSharedPreferences(PLAINTEXT_PREFS_FILE, Context.MODE_PRIVATE)
        SharedPreferencesSettings(preferences)
    }

    // Encrypted preferences for auth tokens (JWT, refresh token, email)
    single<Settings>(SecureSettingsQualifier) {
        val encryptedPrefs = createEncryptedPreferences(androidContext())
        val plainPrefs = androidContext().getSharedPreferences(PLAINTEXT_PREFS_FILE, Context.MODE_PRIVATE)
        migrateTokensToEncrypted(plainPrefs, encryptedPrefs)
        SharedPreferencesSettings(encryptedPrefs)
    }

    factory<BleRepository> { KableBleRepository() }
    single<CsvExporter> { AndroidCsvExporter(androidContext()) }
    single<CsvImporter> { AndroidCsvImporter(androidContext(), get()) }
    single<DataBackupManager> { AndroidDataBackupManager(androidContext(), get()) }
    single { ConnectivityChecker(androidContext()) }
    single<SafeWordListenerFactory> { AndroidSafeWordListenerFactory(androidContext()) }
}

/**
 * Creates an EncryptedSharedPreferences backed by Android Keystore.
 * Falls back to a regular SharedPreferences if the Keystore is unavailable
 * (e.g. some emulators) to avoid a hard crash — tokens will still be
 * app-private via MODE_PRIVATE.
 */
private fun createEncryptedPreferences(context: Context): SharedPreferences {
    return try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        log.w(e) { "EncryptedSharedPreferences unavailable, falling back to MODE_PRIVATE" }
        context.getSharedPreferences(ENCRYPTED_PREFS_FILE, Context.MODE_PRIVATE)
    }
}

/**
 * One-time migration: copies all portal keys from the old plaintext prefs to
 * the encrypted store, then removes them from plaintext.
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

private fun migrateTokensToEncrypted(
    plain: SharedPreferences,
    encrypted: SharedPreferences,
) {
    // Skip if nothing to migrate (no portal keys in plaintext)
    val hasPortalKeys = PORTAL_KEYS.any { plain.contains(it) }
    if (!hasPortalKeys) return

    log.i { "Migrating portal keys from plaintext to encrypted preferences" }
    val editor = encrypted.edit()
    for (key in PORTAL_KEYS) {
        when (val value = plain.all[key]) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            // null or missing — skip
        }
    }
    editor.apply()

    // Remove migrated keys from plaintext
    val plainEditor = plain.edit()
    for (key in PORTAL_KEYS) {
        plainEditor.remove(key)
    }
    plainEditor.apply()
    log.i { "Portal key migration to encrypted storage complete" }
}

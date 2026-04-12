package com.devil.phoenixproject.di

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.domain.voice.IosSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.IosCsvExporter
import com.devil.phoenixproject.util.IosCsvImporter
import com.devil.phoenixproject.util.IosDataBackupManager
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

private const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"

private val log = Logger.withTag("PlatformModule")

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

actual val platformModule: Module = module {
    single {
        val bundle = NSBundle.mainBundle
        SupabaseConfig(
            url = bundle.objectForInfoDictionaryKey("SUPABASE_URL") as? String ?: "",
            anonKey = bundle.objectForInfoDictionaryKey("SUPABASE_ANON_KEY") as? String ?: "",
        )
    }
    single { DriverFactory() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        NSUserDefaultsSettings(defaults)
    }
    // Secure storage using iOS Keychain for auth tokens (JWT, refresh token, user identity).
    // Keychain data persists across app reinstalls and is protected by iOS Data Protection.
    @OptIn(ExperimentalSettingsImplementation::class)
    single<Settings>(SecureSettingsQualifier) {
        val keychainSettings = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
        val legacySettings: Settings = get() // NSUserDefaults
        migrateTokensToKeychain(legacySettings, keychainSettings)
        keychainSettings
    }
    single<BleRepository> { KableBleRepository() }
    single<CsvExporter> { IosCsvExporter() }
    single<CsvImporter> { IosCsvImporter(get()) }
    single<DataBackupManager> { IosDataBackupManager(get()) }
    single { ConnectivityChecker() }
    single<SafeWordListenerFactory> { IosSafeWordListenerFactory() }
    single { HealthIntegration() }
    single<WorkoutServiceController> { NoOpWorkoutServiceController }
    single {
        MainViewModel(
            bleRepository = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            personalRecordRepository = get(),
            repCounter = get(),
            preferencesManager = get(),
            gamificationRepository = get(),
            trainingCycleRepository = get(),
            completedSetRepository = get(),
            syncTriggerManager = get(),
            repMetricRepository = get(),
            biomechanicsRepository = get(),
            resolveWeightsUseCase = get(),
            detectionManager = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
            healthIntegration = get(),
            externalActivityRepository = get(),
            workoutServiceController = get(),
        )
    }
}

/**
 * One-time migration: copies all portal keys from the old NSUserDefaults to
 * the Keychain store, then removes them from NSUserDefaults.
 *
 * This is idempotent: if no legacy keys exist, it returns immediately.
 * Migration runs synchronously during Koin module initialization to ensure
 * tokens are available in Keychain before any auth operations.
 */
private fun migrateTokensToKeychain(legacy: Settings, keychain: Settings) {
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

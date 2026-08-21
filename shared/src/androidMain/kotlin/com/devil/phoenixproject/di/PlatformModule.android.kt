package com.devil.phoenixproject.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.auth.OAuthLauncher
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutWriter
import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.preferences.AndroidPreferenceFileMigrator
import com.devil.phoenixproject.data.preferences.AndroidPreferenceFileOperations
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.domain.voice.AndroidSafeWordListenerFactory
import com.devil.phoenixproject.domain.voice.SafeWordListenerFactory
import com.devil.phoenixproject.presentation.manager.AndroidWorkoutServiceController
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.util.AndroidBackupDestinationResolver
import com.devil.phoenixproject.util.AndroidCsvExporter
import com.devil.phoenixproject.util.AndroidCsvImporter
import com.devil.phoenixproject.util.AndroidDataBackupManager
import com.devil.phoenixproject.util.BackupDestinationResolver
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val log = Logger.withTag("PlatformModule")

private data class AndroidPreferenceStores(
    val plaintext: SharedPreferences,
    val encrypted: SharedPreferences,
)

actual val platformModule: Module = module {
    single { DriverFactory(androidContext()) }

    single {
        val context = androidContext()
        AndroidPreferenceFileOperations(context) { name -> createEncryptedPreferences(context, name) }
    }
    single {
        AndroidPreferenceFileMigrator(get<AndroidPreferenceFileOperations>()) { message, failure ->
            if (failure == null) {
                log.w { message }
            } else {
                log.w(failure) { message }
            }
        }
    }
    single {
        val operations = get<AndroidPreferenceFileOperations>()
        get<AndroidPreferenceFileMigrator>().prepare()
        val plaintext = operations.targetPlaintext()
        val encrypted = operations.targetEncrypted()
        migrateTokensToEncrypted(plaintext, encrypted)
        AndroidPreferenceStores(plaintext, encrypted)
    }

    // General-purpose preferences (non-sensitive settings like units, UI prefs)
    single<Settings> {
        SharedPreferencesSettings(get<AndroidPreferenceStores>().plaintext)
    }

    // Encrypted preferences for auth tokens (JWT, refresh token, email)
    single<Settings>(SecureSettingsQualifier) {
        SharedPreferencesSettings(get<AndroidPreferenceStores>().encrypted)
    }

    single { OAuthLauncher(androidContext()) }
    single<BleRepository> { KableBleRepository() }
    single<CsvExporter> { AndroidCsvExporter(androidContext()) }
    single<CsvImporter> { AndroidCsvImporter(androidContext(), get()) }
    single<BackupDestinationResolver> { AndroidBackupDestinationResolver(androidContext()) }
    single<DataBackupManager> { AndroidDataBackupManager(androidContext(), get(), get(), get(), get(), get()) }
    single { ConnectivityChecker(androidContext()) }
    single<SafeWordListenerFactory> { AndroidSafeWordListenerFactory(androidContext()) }
    single { HealthIntegration(androidContext()) }
    single<HealthWorkoutWriter> { get<HealthIntegration>() }
    single<WorkoutServiceController> { AndroidWorkoutServiceController(androidContext()) }
    viewModel {
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
            activeWorkoutRuntimeRepository = get(),
            dropSetEligibilityPolicy = get(),
            syncTriggerManager = get(),
            repMetricRepository = get(),
            biomechanicsRepository = get(),
            resolveWeightsUseCase = get(),
            applyRoutineModifierUseCase = get(),
            recommendWeightAdjustmentUseCase = get(),
            equipmentRackRepository = get(),
            applyEquipmentRackLoadUseCase = get(),
            dataBackupManager = get(),
            userProfileRepository = get(),
            healthIntegration = get(),
            externalActivityRepository = get(),
            workoutServiceController = get(),
            healthExportCursorRepository = get(),
            computeVelocityOneRepMaxUseCase = get(),
            recordPersonalMvtSampleUseCase = get(),
            velocityOneRepMaxRepository = get(),
            countVelocityOneRepMaxImprovementsUseCase = get(),
            backfillVelocityOneRepMaxUseCase = get(),
        )
    }
}

/**
 * Creates an EncryptedSharedPreferences backed by Android Keystore.
 *
 * SECURITY: This function intentionally does NOT fall back to unencrypted storage.
 * Authentication tokens (JWT, refresh tokens) MUST be encrypted at rest. If the
 * Android Keystore is unavailable (very rare in production), the app will fail
 * to initialize sync features rather than silently downgrade security.
 *
 * If you encounter a KeyStoreException during development on an emulator:
 * 1. Use an emulator with Google Play APIs (includes hardware-backed Keystore)
 * 2. Or run on a physical device
 *
 * @throws SecurityException if EncryptedSharedPreferences cannot be created.
 *         This indicates the device cannot securely store authentication tokens.
 */
private fun createEncryptedPreferences(context: Context, name: String): SharedPreferences = try {
    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    EncryptedSharedPreferences.create(
        name,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
} catch (e: Exception) {
    log.e(e) { "SECURITY: EncryptedSharedPreferences creation failed - cannot securely store auth tokens" }
    // Do NOT fall back to unencrypted storage - throw to prevent silent security downgrade
    throw SecurityException(
        "Secure token storage unavailable. Your device may not support encrypted storage. " +
            "Portal sync features cannot be enabled without secure storage. " +
            "Original error: ${e.message}",
        e,
    )
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

private fun migrateTokensToEncrypted(plain: SharedPreferences, encrypted: SharedPreferences) {
    // Skip if nothing to migrate (no portal keys in plaintext)
    val hasPortalKeys = PORTAL_KEYS.any { plain.contains(it) }
    if (!hasPortalKeys) return

    log.i { "Migrating portal keys from plaintext to encrypted preferences" }
    val keysToWrite = PORTAL_KEYS.filter { key -> plain.contains(key) && !encrypted.contains(key) }
    val editor = encrypted.edit()
    for (key in keysToWrite) {
        when (val value = plain.all[key]) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> {
                if (value.all { item -> item is String }) {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, (value as Set<String>).toSet())
                }
            }
            // null or missing — skip
        }
    }
    // F059: use synchronous commit() and verify success BEFORE removing the
    // plaintext copy. apply() is asynchronous and does not report failure, so a
    // process death or storage error between the encrypted write and the
    // plaintext removal could lose the auth tokens permanently. If the encrypted
    // write fails, leave the plaintext keys intact so migration can retry.
    val committed = keysToWrite.isEmpty() || editor.commit()
    if (!committed) {
        log.w { "Encrypted token migration write failed; keeping plaintext keys for retry" }
        return
    }

    // Remove migrated keys from plaintext only after the encrypted write is durable.
    val plainEditor = plain.edit()
    for (key in PORTAL_KEYS) {
        plainEditor.remove(key)
    }
    if (!plainEditor.commit()) {
        log.w { "Plaintext portal-key cleanup failed; it will retry next launch" }
        return
    }
    log.i { "Portal key migration to encrypted storage complete" }
}

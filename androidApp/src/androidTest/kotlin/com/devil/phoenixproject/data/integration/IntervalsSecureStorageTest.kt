package com.devil.phoenixproject.data.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.devil.phoenixproject.di.SecureSettingsQualifier
import com.devil.phoenixproject.di.platformModule
import com.russhwolf.settings.Settings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Issue #1384 P3 (integration-model §7, R4 evidence): the Intervals.icu secret lives ONLY
 * behind the named secure binding (`SecureSettingsQualifier`) — Android EncryptedSharedPreferences
 * backed by Android Keystore, with no plaintext fallback — and the merged manifest keeps
 * `android:allowBackup="false"` effective, so device transfer/restore can never carry the
 * secure store (or any other app data) into a new installation.
 *
 * Instrumented test: the Keystore-backed encryption itself needs a device/emulator (the host
 * test covers only the store contract). Not run from this checkout — requires
 * `:androidApp:connectedDebugAndroidTest` on a device.
 */
@RunWith(AndroidJUnit4::class)
class IntervalsSecureStorageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        stopKoin()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteAllPreferenceArtifacts()
    }

    @After
    fun tearDown() {
        stopKoin()
        deleteAllPreferenceArtifacts()
    }

    @Test
    fun namedSecureBindingRoundTripsAndStoresNoPlaintext() {
        val koin = startKoin {
            androidContext(context)
            modules(platformModule)
        }.koin
        val secure = koin.get<Settings>(SecureSettingsQualifier)

        // Intervals key reference is stored only as a secure-store round trip.
        secure.putString(OWNED_REF_NAME, STORED_REF_MARKER)
        assertEquals(STORED_REF_MARKER, secure.getStringOrNull(OWNED_REF_NAME))

        // Ciphertext at rest: no preference artifact may contain the marker bytes.
        for (file in preferenceArtifacts()) {
            val text = file.readBytes().decodeToString()
            assertFalse(
                "$file must never contain the stored marker in plaintext",
                text.contains(STORED_REF_MARKER),
            )
        }
    }

    @Test
    fun encryptedBindingSurvivesRestartAndOwnedKeyDeletionTouchesNothingElse() {
        startKoin {
            androidContext(context)
            modules(platformModule)
        }.koin.get<Settings>(SecureSettingsQualifier).apply {
            putString(OWNED_REF_NAME, STORED_REF_MARKER)
            putString("portal.auth.token", "non-owned-token-marker")
        }
        stopKoin()

        // Restart: the same named binding reads the value back (no plaintext fallback).
        val reopened = startKoin {
            androidContext(context)
            modules(platformModule)
        }.koin.get<Settings>(SecureSettingsQualifier)
        assertEquals(STORED_REF_MARKER, reopened.getStringOrNull(OWNED_REF_NAME))

        // Owned-key deletion only: remove the Intervals-owned key, never the whole store
        // (the shared auth Keychain/preferences must never be cleared).
        reopened.remove(OWNED_REF_NAME)
        assertNull(reopened.getStringOrNull(OWNED_REF_NAME))
        assertEquals("non-owned-token-marker", reopened.getStringOrNull("portal.auth.token"))
    }

    @Test
    fun mergedManifestDisallowsBackupSoRestoreCannotResurrectSecrets() {
        // android:allowBackup="false" must be effective in the merged manifest: adb
        // backup/restore and device-to-device transfer carry no app data, so a restored or
        // transferred installation cannot resurrect secrets or installation authorization.
        val flags = context.applicationInfo.flags
        assertEquals(
            "android:allowBackup must be false in the effective merged manifest (integration-model §7)",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        val requested = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }
        assertEquals(0, requested.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun directEncryptedStoreNeverFallsBackToPlaintext() {
        // The binding's contract (createEncryptedPreferences): creation either yields real
        // EncryptedSharedPreferences or throws SecurityException — never plaintext. Prove
        // the direct recipe the binding uses cannot be read back as plaintext either.
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val encrypted = EncryptedSharedPreferences.create(
            "intervals-secure-storage-test",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        encrypted.edit().putString(OWNED_REF_NAME, STORED_REF_MARKER).commit()
        assertEquals(STORED_REF_MARKER, encrypted.getString(OWNED_REF_NAME, null))

        for (file in preferenceArtifacts()) {
            assertFalse(file.readBytes().decodeToString().contains(STORED_REF_MARKER))
        }
    }

    private fun preferenceArtifacts(): List<File> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return prefsDir.listFiles()?.filter(File::isFile).orEmpty()
    }

    private fun deleteAllPreferenceArtifacts() {
        preferenceArtifacts().forEach(File::delete)
    }

    private companion object {
        const val OWNED_REF_NAME = "intervals.ref.v1"
        const val STORED_REF_MARKER = "marker-stored-ref-android"
    }
}

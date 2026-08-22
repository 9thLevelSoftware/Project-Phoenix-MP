package com.devil.phoenixproject.data.preferences

import android.content.Context
import android.content.SharedPreferences
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

@RunWith(AndroidJUnit4::class)
class AndroidPreferenceFileMigrationTest {
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
    fun plaintextAndEncryptedFilesMigrateAndCleanupAfterRestart() {
        context.getSharedPreferences(LEGACY_PLAINTEXT, Context.MODE_PRIVATE).edit()
            .putString("string", "value")
            .putBoolean("boolean", true)
            .putInt("int", 7)
            .putLong("long", 9L)
            .putFloat("float", 1.5f)
            .putStringSet("strings", setOf("one", "two"))
            .commit()
        encrypted(LEGACY_ENCRYPTED).edit()
            .putString("token", "secret")
            .commit()
        preferenceBackupFile(LEGACY_PLAINTEXT).writeBytes(byteArrayOf())
        preferenceBackupFile(LEGACY_ENCRYPTED).writeBytes(byteArrayOf())

        val firstLaunch = startPreferenceKoin()
        firstLaunch.get<Settings>()
        firstLaunch.get<Settings>(SecureSettingsQualifier)

        val plaintextTarget = context.getSharedPreferences(TARGET_PLAINTEXT, Context.MODE_PRIVATE)
        val encryptedTarget = encrypted(TARGET_ENCRYPTED)
        assertEquals("value", plaintextTarget.getString("string", null))
        assertTrue(plaintextTarget.getBoolean("boolean", false))
        assertEquals(7, plaintextTarget.getInt("int", 0))
        assertEquals(9L, plaintextTarget.getLong("long", 0L))
        assertEquals(1.5f, plaintextTarget.getFloat("float", 0f))
        assertEquals(setOf("one", "two"), plaintextTarget.getStringSet("strings", emptySet()))
        assertEquals("secret", encryptedTarget.getString("token", null))
        assertNoFileOrBackup(LEGACY_PLAINTEXT)
        assertNoFileOrBackup(LEGACY_ENCRYPTED)
        assertTrue(preferenceFile(RECOVERY_PLAINTEXT).exists())
        assertTrue(preferenceFile(RECOVERY_ENCRYPTED).exists())

        stopKoin()
        val secondLaunch = startPreferenceKoin()
        secondLaunch.get<Settings>()
        secondLaunch.get<Settings>(SecureSettingsQualifier)

        assertNoFileOrBackup(RECOVERY_PLAINTEXT)
        assertNoFileOrBackup(RECOVERY_ENCRYPTED)
        assertEquals("value", context.getSharedPreferences(TARGET_PLAINTEXT, Context.MODE_PRIVATE).getString("string", null))
        assertEquals("secret", encrypted(TARGET_ENCRYPTED).getString("token", null))
    }

    private fun startPreferenceKoin() = startKoin {
        androidContext(context)
        modules(platformModule)
    }.koin

    private fun encrypted(name: String): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            name,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun assertNoFileOrBackup(name: String) {
        assertFalse("preference XML remains: $name", preferenceFile(name).exists())
        assertFalse("preference backup remains: $name", preferenceBackupFile(name).exists())
    }

    private fun deleteAllPreferenceArtifacts() {
        ALL_FILES.forEach { name ->
            context.deleteSharedPreferences(name)
            preferenceFile(name).delete()
            preferenceBackupFile(name).delete()
        }
    }

    private fun preferenceFile(name: String): File = File(
        File(context.applicationInfo.dataDir, "shared_prefs"),
        "$name.xml",
    )

    private fun preferenceBackupFile(name: String): File = File("${preferenceFile(name).path}.bak")

    private companion object {
        const val LEGACY_PLAINTEXT = "vitruvian_preferences"
        const val TARGET_PLAINTEXT = "phoenix_preferences"
        const val RECOVERY_PLAINTEXT = "phoenix_preferences_recovery"
        const val LEGACY_ENCRYPTED = "vitruvian_secure_preferences"
        const val TARGET_ENCRYPTED = "phoenix_secure_preferences"
        const val RECOVERY_ENCRYPTED = "phoenix_secure_preferences_recovery"
        val ALL_FILES = listOf(
            LEGACY_PLAINTEXT,
            TARGET_PLAINTEXT,
            RECOVERY_PLAINTEXT,
            LEGACY_ENCRYPTED,
            TARGET_ENCRYPTED,
            RECOVERY_ENCRYPTED,
        )
    }
}
